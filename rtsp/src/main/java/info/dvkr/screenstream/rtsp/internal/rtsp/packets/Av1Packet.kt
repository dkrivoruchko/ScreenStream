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
                val header = readHeader(av1Data, index)
                index += header.size
                if (index >= av1Data.size) break

                val (leb128Size, leb128BytesUsed) = readLeb128(av1Data, index)
                // Negative means malformed LEB128 or out-of-bounds
                if (leb128Size < 0) {
                    Log.e(TAG, "Malformed or out-of-bounds LEB128. Stopping OBU parsing.")
                    break
                }

                index += leb128BytesUsed
                if (index >= av1Data.size) break

                // Check that the OBU data fits in the remaining buffer
                if (leb128Size.toInt() > av1Data.size - index) {
                    Log.e(TAG, "LEB128 size exceeds remaining buffer. Stopping OBU parsing.")
                    break
                }

                // The length LEB128 itself
                val lengthBytes = av1Data.sliceArray(index - leb128BytesUsed until index)

                // The OBU payload
                val data = av1Data.sliceArray(index until index + leb128Size.toInt())
                index += data.size

                obuList.add(Obu(header = header, leb128 = lengthBytes, data = data))
            }
            return obuList
        }

        /**
         * Reads the OBU header (1 or 2 bytes, depending on extension_flag).
         */
        private fun readHeader(av1Data: ByteArray, offset: Int): ByteArray {
            val header = mutableListOf<Byte>()
            val info = av1Data[offset]
            header.add(info)

            // extension_flag is bit 2 from the right: (info >> 2) & 0x01
            val containExtended = ((info.toInt() ushr 2) and 0x01) == 1
            if (containExtended && offset + 1 < av1Data.size) {
                header.add(av1Data[offset + 1])
            }
            return header.toByteArray()
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
        var src = mediaFrame.data.removeInfo(mediaFrame.info)

        // Skip a Temporal Delimiter OBU if present at start
        if (src.remaining() >= 1 && getObuType(src.get(src.position())) == ObuType.TEMPORAL_DELIMITER) {
            if (src.remaining() >= 2) src.position(src.position() + 2)
            src = src.slice()
        }

        // Parse OBUs and assemble exact bytes for each OBU (header + leb128 + payload)
        val obuList = getObus(src.toByteArray()).filter { getObuType(it.header[0]) != ObuType.TEMPORAL_DELIMITER }
        if (obuList.isEmpty()) return emptyList()
        val obuBytes = obuList.map { it.getFullData() }

        val ts = mediaFrame.info.timestamp * 1000L
        val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 1 // 1 byte for aggregation header
        val frames = mutableListOf<RtpFrame>()

        var firstPacket = true
        var obuIndex = 0
        var offsetInObu = 0

        while (obuIndex < obuBytes.size) {
            val bufferPayload = ArrayList<Byte>(maxPayload)
            var remaining = maxPayload
            var newObuCount = 0
            val startingWithContinuation = offsetInObu > 0

            // Fill this packet payload
            while (remaining > 0 && obuIndex < obuBytes.size) {
                val bytes = obuBytes[obuIndex]
                val toCopy = minOf(remaining, bytes.size - offsetInObu)
                // Count a new OBU start only when we begin copying at offset 0
                if (offsetInObu == 0) newObuCount++
                for (i in 0 until toCopy) bufferPayload.add(bytes[offsetInObu + i])
                remaining -= toCopy
                offsetInObu += toCopy
                if (offsetInObu >= bytes.size) {
                    obuIndex++
                    offsetInObu = 0
                } else {
                    // Current OBU didn't fit completely; stop to avoid splitting LEB128/header across packets (we started at 0)
                    break
                }
            }

            if (startingWithContinuation && newObuCount > 0) {
                // If packet starts mid-OBU, first OBU didn't start here; adjust count
                newObuCount--
            }
            // Cap W to 3
            val w = newObuCount.coerceIn(0, 3)

            val size = bufferPayload.size
            val out = getBuffer(size + RTP_HEADER_LENGTH + 1)
            val rtpTs = updateTimeStamp(out, ts)
            for (i in 0 until size) out[RTP_HEADER_LENGTH + 1 + i] = bufferPayload[i]

            val isLast = (obuIndex >= obuBytes.size && offsetInObu == 0)
            if (isLast) markPacket(out)

            out[RTP_HEADER_LENGTH] = generateAv1AggregationHeader(mediaFrame.info.isKeyFrame, firstPacket, isLast, w)
            updateSeq(out)
            frames.add(RtpFrame.Video(out, rtpTs, out.size))

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
}