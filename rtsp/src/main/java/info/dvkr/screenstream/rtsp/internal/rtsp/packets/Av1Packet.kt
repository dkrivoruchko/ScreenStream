package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import android.media.MediaCodec
import android.util.Log
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.or

/**
 * Implementation of AV1 over RTP based on
 * https://aomediacodec.github.io/av1-rtp-spec/
 */
internal class Av1Packet : BaseRtpPacket(VIDEO_CLOCK_FREQUENCY, PAYLOAD_TYPE) {

    companion object {
        const val PAYLOAD_TYPE = 96
        private const val TAG = "Av1Packet"
        private var warnedNoSizeField: Boolean = false

        private data class ParsedHeader(
            val header: ByteArray,
            val hasSizeField: Boolean
        )

        /**
         * Extracts the OBU of type SEQUENCE_HEADER from a keyframe if present.
         */
        fun extractObuSeq(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): ByteArray? {
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return null
            val dup = buffer.duplicate()
            val av1Data = ByteArray(dup.remaining()).also { dup.get(it) }
            return getObus(av1Data)
                .firstOrNull { getObuType(it.header[0]) == ObuType.SEQUENCE_HEADER }
                ?.getFullData()
        }

        /**
         * Identifies the OBU type from the first byte of the header.
         */
        private fun getObuType(header: Byte): ObuType {
            val value = ((header.toInt() and 0x7F) and 0xF8) ushr 3
            return ObuType.entries.firstOrNull { it.value == value } ?: ObuType.RESERVED
        }

        /**
         * Parses a raw AV1 byte array to extract OBUs in sequence.
         */
        private fun getObus(av1Data: ByteArray): List<Obu> {
            val obuList = mutableListOf<Obu>()
            var index = 0

            while (index < av1Data.size) {
                val parsedHeader = parseHeader(av1Data, index) ?: break
                index += parsedHeader.header.size
                if (index >= av1Data.size) break

                val (obuPayloadSize, leb128BytesUsed, lebBytes) = if (parsedHeader.hasSizeField) {
                    val (leb128Size, bytesUsed) = readLeb128(av1Data, index)
                    // Negative means malformed LEB128 or out-of-bounds
                    if (leb128Size < 0) {
                        Log.e(TAG, "Malformed or out-of-bounds LEB128. Stopping OBU parsing.")
                        break
                    }
                    Triple(leb128Size.toInt(), bytesUsed, av1Data.sliceArray(index until index + bytesUsed))
                } else {
                    if (!warnedNoSizeField) {
                        Log.w(TAG, "OBU has no size field; treating remainder as a single OBU.")
                        warnedNoSizeField = true
                    }
                    Triple(av1Data.size - index, 0, null)
                }

                index += leb128BytesUsed
                if (index >= av1Data.size && obuPayloadSize == 0) break

                // Check that the OBU data fits in the remaining buffer
                if (obuPayloadSize > av1Data.size - index) {
                    Log.e(TAG, "OBU size exceeds remaining buffer. Stopping OBU parsing.")
                    break
                }

                // The OBU payload
                val data = av1Data.sliceArray(index until index + obuPayloadSize)
                index += data.size

                obuList.add(Obu(header = parsedHeader.header, leb128 = lebBytes, data = data))

                // Without size fields, we cannot reliably parse subsequent OBUs
                if (!parsedHeader.hasSizeField) break
            }
            return obuList
        }

        /**
         * Reads the OBU header (1 or 2 bytes, depending on extension_flag).
         */
        private fun parseHeader(av1Data: ByteArray, offset: Int): ParsedHeader? {
            if (offset >= av1Data.size) return null
            val info = av1Data[offset].toInt() and 0xFF
            // extension_flag is bit 2 from the right: (info >> 2) & 0x01
            val containExtended = ((info ushr 2) and 0x01) == 1
            val hasSizeField = ((info ushr 1) and 0x01) == 1
            val headerLen = if (containExtended) 2 else 1
            if (offset + headerLen > av1Data.size) {
                Log.e(TAG, "Truncated OBU header at offset $offset.")
                return null
            }
            val header = if (headerLen == 2) byteArrayOf(av1Data[offset], av1Data[offset + 1]) else byteArrayOf(av1Data[offset])
            return ParsedHeader(header, hasSizeField)
        }

        /**
         * Reads a variable-length integer in LEB128 format, up to 8 bytes.
         * Returns Pair(value, bytesUsed). A negative value indicates malformed data.
         */
        private fun readLeb128(data: ByteArray, offset: Int): Pair<Long, Int> {
            var result: Long = 0
            var index = 0
            var shift = 0
            while (true) {
                if (offset + index >= data.size) {
                    // No more data to read => malformed
                    Log.e(TAG, "Unexpected end of data while reading LEB128.")
                    return Pair(-1, index)
                }
                val b = data[offset + index]
                result = result or ((b.toLong() and 0x7F) shl shift)
                index++
                shift += 7

                // If the top bit isn't set, we're done
                if ((b.toInt() and 0x80) == 0) break

                // If we've read 8 bytes and still haven't encountered a stop bit, treat as malformed
                if (index >= 8) {
                    Log.e(TAG, "Malformed LEB128: exceeded 8 bytes without stop bit.")
                    return Pair(-1, index)
                }
            }
            return Pair(result, index)
        }

        /**
         * Generates the 1-byte Aggregation Header for AV1.
         * Limits W to 0..3. If more than 4 OBUs occur, W=3 is used (max representable).
         */
        private fun generateAv1AggregationHeader(isKeyFrame: Boolean, isFirstPacket: Boolean, isLastPacket: Boolean, numObu: Int): Byte {
            val z = if (isFirstPacket) 0 else 1
            val y = if (isLastPacket) 0 else 1
            val w = numObu.coerceAtMost(3) // enforce max 3 to fit 2 bits
            val n = if (isKeyFrame && isFirstPacket) 1 else 0
            return ((z shl 7) or (y shl 6) or (w shl 4) or (n shl 3)).toByte()
        }

        /**
         * Writes a long length into LEB128 format.
         */
        private fun writeLeb128(length: Long): ByteArray {
            val result = mutableListOf<Byte>()
            var remainingValue = length
            do {
                var byte = (remainingValue and 0x7F).toByte()
                remainingValue = remainingValue ushr 7
                if (remainingValue != 0L) {
                    byte = byte or 0x80.toByte()
                }
                result.add(byte)
            } while (remainingValue != 0L)
            return result.toByteArray()
        }
    }

    /**
     * Represents a single OBU structure. The optional extension header is considered part of `header` if present.
     */
    data class Obu(
        val header: ByteArray,      // OBU header (+ optional extension byte)
        val leb128: ByteArray?,     // The LEB128 size field
        val data: ByteArray         // OBU payload
    ) {
        fun getFullData(): ByteArray = header.plus(leb128 ?: byteArrayOf()).plus(data)
    }

    /**
     * Known OBU types for AV1.
     */
    enum class ObuType(val value: Int) {
        RESERVED(0), SEQUENCE_HEADER(1), TEMPORAL_DELIMITER(2), FRAME_HEADER(3), TILE_GROUP(4), METADATA(5), FRAME(6),
        REDUNDANT_FRAME_HEADER(7), TILE_LIST(8), PADDING(15)
    }

    /**
     * Creates one or more RTP packets from a single AV1 frame and sends them.
     */
    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        // Remove any extra info from the encoded buffer
        val src = mediaFrame.data.removeInfo(mediaFrame.info)

        // Do not manually skip TD; parser below will remove TD OBUs safely

        // Parse OBUs and keep structured header/leb/payload
        val parsedObus = getObus(src.toByteArray()).filter { getObuType(it.header[0]) != ObuType.TEMPORAL_DELIMITER }
        if (parsedObus.isEmpty()) return emptyList()

        // Transform to RTP wire-form per RFC: clear HAS_SIZE_FIELD and drop OBU-size LEB for each OBU
        val wireObus = mutableListOf<ByteArray>()
        val headerLens = mutableListOf<Int>()
        for (o in parsedObus) {
            val hdr = o.header.copyOf()
            // AV1 OBU header: clear forbidden (bit7), has_size_field (bit1) and reserved1bit (bit0)
            hdr[0] = (hdr[0].toInt() and 0x7C).toByte()
            wireObus += (hdr + o.data)
            headerLens += hdr.size
        }

        // Optional: inject sequence header before keyframe if available
        val listForPacket = mutableListOf<ByteArray>()
        val headerLensForPacket = mutableListOf<Int>()
        val includeSeqHeader = mediaFrame.info.isKeyFrame && seqHeaderWire != null
        if (includeSeqHeader) {
            seqHeaderWire?.let {
                listForPacket += it
                headerLensForPacket += seqHeaderHeaderLen
            }
        }
        listForPacket += wireObus
        headerLensForPacket += headerLens

        val ts = mediaFrame.info.timestamp * 1000L
        val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 1 // 1 byte for aggregation header
        val frames = mutableListOf<RtpFrame>()

        var firstPacket = true
        var obuIndex = 0
        var offsetInObu = 0

        fun lebLenInt(x: Int): Int {
            var v = x
            var n = 0
            do { n++; v = v ushr 7 } while (v != 0)
            return n
        }

        while (obuIndex < listForPacket.size) {
            // Build list of elements for this RTP packet
            data class Elem(val idx: Int, val start: Int, val len: Int, val isContinuation: Boolean)
            val elems = mutableListOf<Elem>()
            var remaining = maxPayload
            var startingWithContinuation = offsetInObu > 0
            var lastIsFragmented = false

            while (remaining > 0 && obuIndex < listForPacket.size) {
                val full = listForPacket[obuIndex]
                val headerLen = headerLensForPacket[obuIndex]
                val totalLen = full.size

                // Decide how many bytes of this OBU we can copy in this packet for this element
                var toCopy: Int
                if (offsetInObu == 0) {
                    // Ensure we can at least include header + OBU LEB fully in the first fragment
                    // Compute maximum payload we can fit after accounting for at least 1 byte LEB (aggregator length)
                    var maxData = remaining - 1
                    if (maxData <= 0) break
                    // Prefer full OBU if it fits (including aggregator LEB)
                    fun lebLen(x: Int): Int {
                        var v = x
                        var n = 0
                        do { n++; v = v ushr 7 } while (v != 0)
                        return n
                    }
                    val fullNeed = lebLen(totalLen) + totalLen
                    if (fullNeed <= remaining) {
                        toCopy = totalLen
                    } else {
                        // Fragment: ensure we keep header+OBU-LEB intact
                        // Start with as much as fits, then shrink until LEB(toCopy)+toCopy <= remaining and toCopy>=headerLen
                        toCopy = maxData
                        while (toCopy > 0 && (lebLen(toCopy) + toCopy > remaining)) toCopy--
                        if (toCopy < headerLen) break // can't start this OBU in this packet
                        lastIsFragmented = true
                    }
                } else {
                    // Continuation of a previous OBU: no header/OBU-LEB in this fragment
                    fun lebLen(x: Int): Int {
                        var v = x
                        var n = 0
                        do { n++; v = v ushr 7 } while (v != 0)
                        return n
                    }
                    val remainInObu = totalLen - offsetInObu
                    var maxData = remaining - 1
                    if (maxData <= 0) break
                    toCopy = if (remainInObu + lebLen(remainInObu) <= remaining) remainInObu else maxData
                    while (toCopy > 0 && (lebLen(toCopy) + toCopy > remaining)) toCopy--
                    if (toCopy <= 0) break
                    if (toCopy < remainInObu) lastIsFragmented = true
                }

                // Append element and update counters (single element per packet policy)
                elems.add(Elem(obuIndex, offsetInObu, toCopy, isContinuation = (offsetInObu > 0)))
                remaining = 0 // force one element per RTP packet

                if (offsetInObu + toCopy >= full.size) {
                    // Finished this OBU
                    obuIndex++
                    offsetInObu = 0
                } else {
                    // Fragmented OBU; do not place more elements in this packet to keep Y semantics simple
                    offsetInObu += toCopy
                    lastIsFragmented = true
                    break
                }
            }

            if (elems.isEmpty()) break

            // Compute total size: 1 (agg hdr) + sum(LEB(len) + len) for W=0 (length-present)
            val payloadSize = elems.sumOf { lebLenInt(it.len) + it.len }
            val out = getBuffer(payloadSize + RTP_HEADER_LENGTH + 1)
            val rtpTs = updateTimeStamp(out, ts)

            // Aggregation header: Z,Y,W(=0 -> single element, length present),N
            val z = if (startingWithContinuation) 1 else 0
            val y = if (lastIsFragmented) 1 else 0
            val w = 0 // single element; length present via LEB
            val n = if (newSequence && includeSeqHeader && firstPacket) 1 else 0
            out[RTP_HEADER_LENGTH] = (((z shl 7) or (y shl 6) or (w shl 4) or (n shl 3))).toByte()

            // Write per-element LEB length followed by element bytes (W=0)
            var pos = RTP_HEADER_LENGTH + 1
            for (e in elems) {
                val leb = writeLeb128(e.len.toLong())
                for (i in leb.indices) out[pos + i] = leb[i]
                pos += leb.size
                val fullBytes = listForPacket[e.idx]
                for (i in 0 until e.len) {
                    out[pos + i] = fullBytes[e.start + i]
                }
                pos += e.len
            }

            val isLast = (obuIndex >= listForPacket.size && offsetInObu == 0)
            if (isLast) markPacket(out)
            updateSeq(out)
            frames.add(RtpFrame.Video(out, rtpTs, out.size))
            if (newSequence && includeSeqHeader && firstPacket) newSequence = false

            firstPacket = false
        }
        return frames
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicateBuffer = duplicate()
        val bytes = ByteArray(duplicateBuffer.remaining())
        duplicateBuffer.get(bytes)
        return bytes
    }

    // Sequence header support (optional injection on keyframes)
    private var seqHeaderWire: ByteArray? = null
    private var seqHeaderHeaderLen: Int = 0
    private var newSequence: Boolean = false

    fun setSequenceHeader(obuFull: ByteArray) {
        if (obuFull.isEmpty()) return
        val parsedHeader = parseHeader(obuFull, 0) ?: return
        val header = parsedHeader.header.copyOf()
        var payloadOffset = parsedHeader.header.size
        var payloadSize = obuFull.size - payloadOffset

        if (parsedHeader.hasSizeField) {
            val (leb128Size, lebBytesUsed) = readLeb128(obuFull, payloadOffset)
            if (leb128Size < 0) return
            payloadOffset += lebBytesUsed
            if (payloadOffset > obuFull.size) return
            if (leb128Size.toInt() > obuFull.size - payloadOffset) return
            payloadSize = leb128Size.toInt()
        }

        // AV1 OBU header (wire form): clear forbidden (bit7), has_size_field (bit1) and reserved (bit0)
        header[0] = (header[0].toInt() and 0x7C).toByte()
        val payload = if (payloadSize > 0 && payloadOffset + payloadSize <= obuFull.size) {
            obuFull.copyOfRange(payloadOffset, payloadOffset + payloadSize)
        } else {
            byteArrayOf()
        }
        seqHeaderWire = header + payload
        seqHeaderHeaderLen = header.size
        newSequence = true
    }
}
