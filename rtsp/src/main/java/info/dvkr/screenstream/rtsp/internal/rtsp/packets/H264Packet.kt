package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and

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

            var i = 0
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            fun startCodeLen(pos: Int): Int = when {
                pos + 3 < csd.size && csd[pos] == 0.toByte() && csd[pos + 1] == 0.toByte() && csd[pos + 2] == 0.toByte() && csd[pos + 3] == 1.toByte() -> 4
                pos + 2 < csd.size && csd[pos] == 0.toByte() && csd[pos + 1] == 0.toByte() && csd[pos + 2] == 1.toByte() -> 3
                else -> 0
            }
            while (i < csd.size - 3) {
                val sc = startCodeLen(i)
                if (sc == 0) {
                    i++; continue
                }
                val nalStart = i + sc
                var j = nalStart
                while (j < csd.size - 3 && startCodeLen(j) == 0) j++
                val nalEnd = if (j >= csd.size - 3) csd.size else j
                if (nalEnd - nalStart > 0) {
                    val nalType = (csd[nalStart] and 0x1F).toInt()
                    if (nalType == 7 && sps == null) sps = csd.copyOfRange(nalStart, nalEnd)
                    if (nalType == 8 && pps == null) pps = csd.copyOfRange(nalStart, nalEnd)
                    if (sps != null && pps != null) break
                }
                i = nalEnd
            }
            val s = sps ?: return null
            val p = pps ?: return null
            return s to p
        }
    }

    private var stapA: ByteArray? = null
    private var sendKeyFrame = false
    private var forceStapOnce = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun setVideoInfo(sps: ByteArray, pps: ByteArray) {
        setSpsPps(sps, pps)
        sendKeyFrame = false
    }

    // Request to prepend SPS/PPS (STAP‑A) before the next access unit, even if it is not an IDR.
    fun forceStapAOnce() {
        forceStapOnce = true
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)

        if (getHeaderSize(fixedBuffer) == 0) {
            convertAvccToAnnexB(fixedBuffer)?.let { fixedBuffer = it }
        }

        val ts = mediaFrame.info.timestamp * 1000L
        val frames = mutableListOf<RtpFrame>()

        val prefix = getHeaderSize(fixedBuffer)
        fixedBuffer.rewind()
        if (prefix > 0) fixedBuffer.position(prefix)

        var stapForThisAuSent = false
        var audForThisAuSent = false
        var slice = fixedBuffer.slice()
        while (slice.remaining() > 0) {
            val startCodeSize = slice.getVideoStartCodeSize()
            if (startCodeSize == 0) {
                val startPos = slice.position()
                val nalHeader = slice.get(startPos)
                val naluType = (nalHeader and 0x1F).toInt()
                val isIdr = (naluType == IDR)
                if (((!sendKeyFrame) || isIdr || forceStapOnce) && !stapForThisAuSent) {
                    stapA?.let {
                        val b = getBuffer(it.size + RTP_HEADER_LENGTH)
                        val rtpTs = updateTimeStamp(b, ts)
                        updateSeq(b)
                        System.arraycopy(it, 0, b, RTP_HEADER_LENGTH, it.size)
                        frames.add(RtpFrame.Video(b, rtpTs, b.size))
                        sendKeyFrame = true
                        stapForThisAuSent = true
                        if (forceStapOnce) forceStapOnce = false
                    }
                }
                if (!audForThisAuSent) {
                    // Insert an Access Unit Delimiter (AUD) to help some clients resync
                    val aud = byteArrayOf(0x09, 0xF0.toByte())
                    val b = getBuffer(aud.size + RTP_HEADER_LENGTH)
                    val rtpTs = updateTimeStamp(b, ts)
                    updateSeq(b)
                    System.arraycopy(aud, 0, b, RTP_HEADER_LENGTH, aud.size)
                    frames.add(RtpFrame.Video(b, rtpTs, b.size))
                    audForThisAuSent = true
                }
                packetizeSingleNal(slice, startPos, slice.remaining(), ts, frames, markLast = true)
                break
            }

            // Position to first byte of NAL (after start code)
            val startPos = slice.position() + startCodeSize
            if (startPos >= slice.limit()) break

            // Find next start code to delimit this NAL
            val search = slice.duplicate().apply { position(startPos) }
            val next = findNextStartCodeIndex(search)
            val naluLen = next?.first ?: (slice.limit() - startPos)

            // NAL header to decide if we should prepend SPS/PPS for IDR
            val nalHeader = slice.get(startPos)
            val naluType = (nalHeader and 0x1F).toInt()
            val isIdr = (naluType == IDR)
            // Prepend SPS/PPS for every IDR to improve compatibility with strict players (e.g., IINA/mpv).
            if ((isIdr || forceStapOnce || !sendKeyFrame) && !stapForThisAuSent) {
                stapA?.let {
                    val b = getBuffer(it.size + RTP_HEADER_LENGTH)
                    val rtpTs = updateTimeStamp(b, ts)
                    updateSeq(b)
                    System.arraycopy(it, 0, b, RTP_HEADER_LENGTH, it.size)
                    frames.add(RtpFrame.Video(b, rtpTs, b.size))
                    sendKeyFrame = true
                    stapForThisAuSent = true
                    if (forceStapOnce) forceStapOnce = false
                }
            }

            if (!audForThisAuSent) {
                val aud = byteArrayOf(0x09, 0xF0.toByte())
                val b = getBuffer(aud.size + RTP_HEADER_LENGTH)
                val rtpTs = updateTimeStamp(b, ts)
                updateSeq(b)
                System.arraycopy(aud, 0, b, RTP_HEADER_LENGTH, aud.size)
                frames.add(RtpFrame.Video(b, rtpTs, b.size))
                audForThisAuSent = true
            }

            val isLastNal = next == null
            packetizeSingleNal(slice, startPos, naluLen, ts, frames, markLast = isLastNal)

            // Advance slice to next NAL start (position after this NAL and before next start code)
            slice.position(startPos + naluLen)
            if (next == null) break
        }
        return frames
    }

    private fun packetizeSingleNal(
        base: ByteBuffer,
        startPos: Int,
        naluLen: Int,
        ts: Long,
        out: MutableList<RtpFrame>,
        markLast: Boolean
    ) {
        val maxPayloadSingle = MAX_PACKET_SIZE - RTP_HEADER_LENGTH
        if (naluLen <= maxPayloadSingle) {
            val buffer = getBuffer(naluLen + RTP_HEADER_LENGTH)
            val dup = base.duplicate()
            dup.limit(startPos + naluLen)
            dup.position(startPos)
            dup.get(buffer, RTP_HEADER_LENGTH, naluLen)
            val rtpTs = updateTimeStamp(buffer, ts)
            updateSeq(buffer)
            if (markLast) markPacket(buffer)
            out.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
        } else {
            // FU-A fragmentation
            val nalHeader = base.get(startPos)
            val indicator = ((nalHeader.toInt() and 0x60) or 28).toByte()
            val fuHeaderBase = (nalHeader and 0x1F)
            val maxFrag = MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 2

            var offset = 1 // skip original nal header in payload
            var first = true
            while (offset < naluLen) {
                val remaining = naluLen - offset
                val chunk = if (remaining > maxFrag) maxFrag else remaining
                val buffer = getBuffer(chunk + RTP_HEADER_LENGTH + 2)
                buffer[RTP_HEADER_LENGTH] = indicator
                var fuHeader = fuHeaderBase
                if (first) fuHeader = fuHeader.plus(0x80).toByte() // S
                // FU-A end (E) bit must be set on the last fragment of this NAL,
                // regardless of whether this NAL is the last in the access unit.
                if (remaining <= maxFrag) fuHeader = fuHeader.plus(0x40).toByte() // E
                buffer[RTP_HEADER_LENGTH + 1] = fuHeader
                val rtpTs = updateTimeStamp(buffer, ts)

                val dup = base.duplicate()
                dup.limit(startPos + offset + chunk)
                dup.position(startPos + offset)
                dup.get(buffer, RTP_HEADER_LENGTH + 2, chunk)
                offset += chunk

                // RTP marker bit is only set on the very last packet of the access unit.
                if (remaining <= maxFrag && markLast) markPacket(buffer)
                updateSeq(buffer)
                out.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
                first = false
            }
        }
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
            // STAP-A NAL: set NRI bits to 3 (0x60) for better compatibility
            this[0] = (0x60 or 24).toByte()
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

    private fun ByteBuffer.getVideoStartCodeSize(): Int {
        val p = position()
        val rem = remaining()
        return when {
            rem >= 4 && get(p) == 0x00.toByte() && get(p + 1) == 0x00.toByte() && get(p + 2) == 0x00.toByte() && get(p + 3) == 0x01.toByte() -> 4
            rem >= 3 && get(p) == 0x00.toByte() && get(p + 1) == 0x00.toByte() && get(p + 2) == 0x01.toByte() -> 3
            else -> 0
        }
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
