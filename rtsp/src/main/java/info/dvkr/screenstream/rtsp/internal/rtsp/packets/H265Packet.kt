package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and

/**
 * RFC 7798 for H.265 (HEVC).
 */
internal class H265Packet : BaseRtpPacket(VIDEO_CLOCK_FREQUENCY, PAYLOAD_TYPE) {

    companion object {
        const val PAYLOAD_TYPE = 96
        const val IDR_N_LP = 20
        const val IDR_W_DLP = 19

        fun extractVpsSpsPps(csd0Buffer: ByteBuffer): Triple<ByteArray, ByteArray, ByteArray>? {
            val csdArray = ByteArray(csd0Buffer.remaining()).also {
                csd0Buffer.mark()
                csd0Buffer.get(it)
                csd0Buffer.reset()
            }

            val startCodes = findAnnexBStartCodes(csdArray)
            if (startCodes.size < 3) return null

            val (vpsStart, spsStart, ppsStart) = startCodes

            val vps = csdArray.copyOfRange(vpsStart, spsStart)
            val sps = csdArray.copyOfRange(spsStart, ppsStart)
            val pps = csdArray.copyOfRange(ppsStart, csdArray.size)

            return Triple(sps, pps, vps)
        }

        private fun findAnnexBStartCodes(data: ByteArray): List<Int> {
            val positions = mutableListOf<Int>()
            var i = 0
            while (i < data.size - 3) {
                // Look for 00 00 01 or 00 00 00 01
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        // Short start code: 00 00 01
                        positions.add(i)
                        i += 3
                        continue
                    } else if (i < data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        // Long start code: 00 00 00 01
                        positions.add(i)
                        i += 4
                        continue
                    }
                }
                i++
            }
            return positions
        }
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        val fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
        val header = ByteArray(fixedBuffer.getVideoStartCodeSize() + 2)
        if (header.size == 2) return emptyList()

        fixedBuffer.get(header, 0, header.size)
        val ts = mediaFrame.info.timestamp * 1000L
        val naluLength = fixedBuffer.remaining()

        val type = ((header[header.size - 2].toInt() shr 1) and 0x3F)

        val frames = mutableListOf<RtpFrame>()

        if (naluLength <= MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 2) {
            val buffer = getBuffer(naluLength + RTP_HEADER_LENGTH + 2)

            buffer[RTP_HEADER_LENGTH] = header[header.size - 2]
            buffer[RTP_HEADER_LENGTH + 1] = header[header.size - 1]
            fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 2, naluLength)

            val rtpTs = updateTimeStamp(buffer, ts)
            markPacket(buffer)
            updateSeq(buffer)

            frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
        } else {
            header[0] = (49 shl 1).toByte()
            header[1] = 1
            header[2] = type.toByte()
            header[2] = header[2].plus(0x80).toByte()

            var sum = 0
            while (sum < naluLength) {
                val length = if (naluLength - sum > MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 3) {
                    MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 3
                } else {
                    fixedBuffer.remaining()
                }
                val buffer = getBuffer(length + RTP_HEADER_LENGTH + 3)

                buffer[RTP_HEADER_LENGTH] = header[0]
                buffer[RTP_HEADER_LENGTH + 1] = header[1]
                buffer[RTP_HEADER_LENGTH + 2] = header[2]

                val rtpTs = updateTimeStamp(buffer, ts)
                fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 3, length)
                sum += length

                if (sum >= naluLength) {
                    buffer[RTP_HEADER_LENGTH + 2] = buffer[RTP_HEADER_LENGTH + 2].plus(0x40).toByte() // set E bit
                    markPacket(buffer)
                }
                updateSeq(buffer)

                frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))

                header[2] = header[2] and 0x7F
            }
        }
        return frames
    }

    internal fun ByteBuffer.getVideoStartCodeSize(): Int = when {
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x00.toByte() && get(3) == 0x01.toByte() -> 4
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x01.toByte() -> 3
        else -> 0
    }
}