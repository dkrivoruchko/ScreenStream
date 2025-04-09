package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import android.media.MediaCodec
import android.util.Log
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import io.ktor.util.copy
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
            val av1Data = ByteArray(buffer.remaining()).also { buffer.get(it) }
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
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)

        // Skip a possible Temporal Delimiter OBU
        if (getObuType(fixedBuffer.get(0)) == ObuType.TEMPORAL_DELIMITER) {
            // Typically 2 bytes in a TD OBU (header + extension), so skip them
            if (fixedBuffer.remaining() >= 2) {
                fixedBuffer.position(2)
            }
            fixedBuffer = fixedBuffer.slice()
        }

        // Parse out all OBUs in the buffer
        val obuList = getObus(fixedBuffer.copy().toByteArray())
        if (obuList.isEmpty()) return emptyList()

        // Use 90kHz timestamp for RTP
        val ts = mediaFrame.info.timestamp * 1000L

        // Concatenate the OBUs in one chunk, inserting LEB128 lengths for all but the last OBU
        var data = byteArrayOf()
        obuList.forEachIndexed { i, obu ->
            val obuData = obu.getFullData()
            data = if (i == obuList.size - 1) {
                data.plus(obuData)
            } else {
                data.plus(writeLeb128(obuData.size.toLong())).plus(obuData)
            }
        }

        // Prepare to fragment this chunk into RTP packets if needed
        fixedBuffer = ByteBuffer.wrap(data)
        val totalSize = fixedBuffer.remaining()
        var sum = 0
        val frames = mutableListOf<RtpFrame>()

        while (sum < totalSize) {
            val isFirstPacket = (sum == 0)
            val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 1  // 1 byte for aggregation header
            val length = if (totalSize - sum > maxPayload) maxPayload else fixedBuffer.remaining()

            // Allocate space for RTP header + Aggregation Header + data chunk
            val buffer = getBuffer(length + RTP_HEADER_LENGTH + 1)
            val rtpTs = updateTimeStamp(buffer, ts)

            fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 1, length)
            sum += length

            // Decide if this is the last packet
            val isLastPacket = (sum >= totalSize)
            if (isLastPacket) {
                markPacket(buffer) // set marker bit
            }

            // For AV1's Aggregation Header (1 byte):
            // bits 7..7 = Z: 0 if first packet, else 1
            // bits 6..6 = Y: 0 if last packet, else 1
            // bits 5..4 = W: number of OBUs in this packet (0..3)
            // bit  3    = N: 1 if starts keyframe, else 0
            val obuCount = if (isFirstPacket) obuList.size else 1
            buffer[RTP_HEADER_LENGTH] = generateAv1AggregationHeader(mediaFrame.info.isKeyFrame, isFirstPacket, isLastPacket, obuCount)
            updateSeq(buffer)
            frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
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