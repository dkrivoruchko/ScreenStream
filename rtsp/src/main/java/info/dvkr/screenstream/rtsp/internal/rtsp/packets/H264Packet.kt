package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and

/**
 * RFC 3984 for H264.
 */
internal class H264Packet : BaseRtpPacket(VIDEO_CLOCK_FREQUENCY, PAYLOAD_TYPE) {

    companion object {
        const val PAYLOAD_TYPE = 96
        const val IDR = 5

        fun extractSpsPps(outputBuffer: ByteBuffer): Pair<ByteArray, ByteArray>? {
            val csd = ByteArray(outputBuffer.remaining()).also {
                outputBuffer.mark()
                outputBuffer.get(it)
                outputBuffer.reset()
            }

            val startCodes = findAnnexBStartCodes(csd)
            if (startCodes.size < 2) return null

            val (spsStart, ppsStart) = startCodes.take(2)
            val sps = csd.copyOfRange(spsStart, ppsStart)
            val pps = csd.copyOfRange(ppsStart, csd.size)

            return sps to pps
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

    private var stapA: ByteArray? = null
    private var sendKeyFrame = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun setVideoInfo(sps: ByteArray, pps: ByteArray) {
        setSpsPps(sps, pps)
        sendKeyFrame = false
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)

        if (getHeaderSize(fixedBuffer) == 0) {
            convertAvccToAnnexB(fixedBuffer)?.let { fixedBuffer = it }
        }

        val header = ByteArray(getHeaderSize(fixedBuffer) + 1)
        if (header.size == 1) return emptyList()
        fixedBuffer.rewind()
        fixedBuffer.get(header, 0, header.size)

        val ts = mediaFrame.info.timestamp * 1000L
        var naluLength = fixedBuffer.remaining()
        var type = (header.last() and 0x1F).toInt()

        // Skip leading non-VCL NALs (AUD=9, SEI=6, SPS=7, PPS=8, Filler=12) to reach first VCL if present
        while (type != IDR && (type == 9 || type == 6 || type == 7 || type == 8 || type == 12)) {
            val next = findNextStartCodeIndex(fixedBuffer) ?: break
            val (offset, startCodeSize) = next
            fixedBuffer.position(fixedBuffer.position() + offset + startCodeSize)
            if (fixedBuffer.remaining() <= 0) return emptyList()
            val nalHeader = fixedBuffer.get(fixedBuffer.position())
            header[header.size - 1] = nalHeader
            type = (nalHeader and 0x1F).toInt()
            naluLength = fixedBuffer.remaining()
        }
        val frames = mutableListOf<RtpFrame>()

        if (type == IDR || mediaFrame.info.isKeyFrame) {
            stapA?.let {
                val buffer = getBuffer(it.size + RTP_HEADER_LENGTH)
                val rtpTs = updateTimeStamp(buffer, ts)
                updateSeq(buffer)
                System.arraycopy(it, 0, buffer, RTP_HEADER_LENGTH, it.size)
                frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
                sendKeyFrame = true
            } ?: run {
                XLog.w(getLog("createPacket", "Can't create keyframe, setSpsPps wasn't called"))
            }
        }

        if (sendKeyFrame) {
            // Limit to this NAL only (do not consume subsequent NALs inside the same buffer)
            val nextNal = findNextStartCodeIndex(fixedBuffer)
            if (nextNal != null) naluLength = nextNal.first

            if (naluLength <= MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 1) {
                val buffer = getBuffer(naluLength + RTP_HEADER_LENGTH + 1)
                buffer[RTP_HEADER_LENGTH] = header.last()
                fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 1, naluLength)
                val rtpTs = updateTimeStamp(buffer, ts)
                updateSeq(buffer)
                markPacket(buffer)
                frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
            } else {
                val nalHeader = header.last()
                // FU-A indicator and header
                header[0] = ((nalHeader.toInt() and 0x60) or 28).toByte()
                header[1] = (nalHeader and 0x1F)
                header[1] = header[1].plus(0x80).toByte() // Set Start bit

                var sum = 0
                while (sum < naluLength) {
                    val length =
                        if (naluLength - sum > MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 2)
                            MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 2
                        else
                            fixedBuffer.remaining()

                    val buffer = getBuffer(length + RTP_HEADER_LENGTH + 2)
                    buffer[RTP_HEADER_LENGTH] = header[0]
                    buffer[RTP_HEADER_LENGTH + 1] = header[1]
                    val rtpTs = updateTimeStamp(buffer, ts)
                    fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 2, length)
                    sum += length

                    if (sum >= naluLength) {
                        buffer[RTP_HEADER_LENGTH + 1] = buffer[RTP_HEADER_LENGTH + 1].plus(0x40).toByte() // end bit
                        markPacket(buffer)
                    }
                    updateSeq(buffer)
                    frames.add(RtpFrame.Video(buffer, rtpTs, buffer.size))

                    header[1] = header[1] and 0x7F
                }
            }
        } else {
            XLog.w(getLog("createPacket", "Waiting for keyframe"))
        }
        return frames
    }

    private fun convertAvccToAnnexB(src: ByteBuffer): ByteBuffer? {
        val duplicate = src.duplicate()
        if (duplicate.remaining() < 4) return null

        // Try 4-byte NAL length first; fallback to 2-byte if invalid
        fun convert(lengthFieldBytes: Int): ByteBuffer? {
            val tmp = duplicate.duplicate()
            var remaining = tmp.remaining()
            var nalCount = 0
            while (remaining >= lengthFieldBytes) {
                var len = 0
                repeat(lengthFieldBytes) { _ -> len = (len shl 8) or (tmp.get().toInt() and 0xFF) }
                if (len <= 0 || len > tmp.remaining()) return null
                tmp.position(tmp.position() + len)
                remaining = tmp.remaining()
                nalCount++
            }
            if (remaining != 0 || nalCount == 0) return null

            val inSize = duplicate.remaining()
            val outSize = inSize - nalCount * lengthFieldBytes + nalCount * 4
            val out = ByteArray(outSize)
            val src2 = duplicate.duplicate()
            var dst = 0
            while (src2.remaining() >= lengthFieldBytes) {
                var len = 0
                repeat(lengthFieldBytes) { _ -> len = (len shl 8) or (src2.get().toInt() and 0xFF) }
                if (len <= 0 || len > src2.remaining()) return null
                out[dst++] = 0; out[dst++] = 0; out[dst++] = 0; out[dst++] = 1
                src2.get(out, dst, len)
                dst += len
            }
            return ByteBuffer.wrap(out, 0, dst)
        }

        return convert(4) ?: convert(2)
    }

    private fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.sps = sps
        this.pps = pps
        stapA = ByteArray(sps.size + pps.size + 5).apply {
            this[0] = 24
            this[1] = (sps.size shr 8).toByte()
            this[2] = (sps.size and 0xFF).toByte()
            this[sps.size + 3] = (pps.size shr 8).toByte()
            this[sps.size + 4] = (pps.size and 0xFF).toByte()

            System.arraycopy(sps, 0, this, 3, sps.size)
            System.arraycopy(pps, 0, this, 5 + sps.size, pps.size)
        }
    }

    private fun getHeaderSize(byteBuffer: ByteBuffer): Int {
        if (byteBuffer.remaining() < 4) return 0
        val sps = this.sps
        val pps = this.pps
        if (sps != null && pps != null) {
            val startCodeSize = byteBuffer.getVideoStartCodeSize()
            if (startCodeSize == 0) return 0
            val startCode = ByteArray(startCodeSize) { 0x00 }
            startCode[startCodeSize - 1] = 0x01
            val avcHeader = startCode + sps + startCode + pps + startCode
            if (byteBuffer.remaining() < avcHeader.size) return startCodeSize

            val possibleHeader = ByteArray(avcHeader.size)
            byteBuffer.get(possibleHeader, 0, possibleHeader.size)
            return if (avcHeader.contentEquals(possibleHeader)) {
                avcHeader.size
            } else {
                startCodeSize
            }
        }
        return 0
    }

    private fun ByteBuffer.getVideoStartCodeSize(): Int = when {
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x00.toByte() && get(3) == 0x01.toByte() -> 4
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x01.toByte() -> 3
        else -> 0
    }

    private fun findNextStartCodeIndex(buffer: ByteBuffer): Pair<Int, Int>? {
        val dup = buffer.duplicate()
        val limit = dup.limit()
        var i = dup.position()
        val end = limit - 3
        while (i < end) {
            if (dup.get(i).toInt() == 0 && dup.get(i + 1).toInt() == 0) {
                if (dup.get(i + 2).toInt() == 1) return (i - dup.position()) to 3
                if (i + 3 < limit && dup.get(i + 2).toInt() == 0 && dup.get(i + 3).toInt() == 1) return (i - dup.position()) to 4
            }
            i++
        }
        return null
    }

    override fun reset() {
        super.reset()
        sendKeyFrame = false
    }
}