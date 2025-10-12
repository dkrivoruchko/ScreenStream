package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer

internal class H265Packet : BaseRtpPacket(VIDEO_CLOCK_FREQUENCY, PAYLOAD_TYPE) {

    companion object {
        const val PAYLOAD_TYPE = 96
        const val IDR_N_LP = 20
        const val IDR_W_DLP = 19

        fun extractSpsPpsVps(csd0Buffer: ByteBuffer): Triple<ByteArray, ByteArray, ByteArray>? {
            val csd = ByteArray(csd0Buffer.remaining()).also {
                csd0Buffer.mark(); csd0Buffer.get(it); csd0Buffer.reset()
            }

            fun startCodeLen(pos: Int): Int = when {
                pos + 3 < csd.size && csd[pos] == 0.toByte() && csd[pos + 1] == 0.toByte() && csd[pos + 2] == 0.toByte() && csd[pos + 3] == 1.toByte() -> 4
                pos + 2 < csd.size && csd[pos] == 0.toByte() && csd[pos + 1] == 0.toByte() && csd[pos + 2] == 1.toByte() -> 3
                else -> 0
            }

            var i = 0
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
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
                    val nalHeader = csd[nalStart].toInt() and 0xFF
                    val nalType = (nalHeader shr 1) and 0x3F
                    when (nalType) {
                        32 -> if (vps == null) vps = csd.copyOfRange(nalStart, nalEnd)
                        33 -> if (sps == null) sps = csd.copyOfRange(nalStart, nalEnd)
                        34 -> if (pps == null) pps = csd.copyOfRange(nalStart, nalEnd)
                    }
                    if (vps != null && sps != null && pps != null) break
                }
                i = nalEnd
            }
            val vv = vps ?: return null
            val ss = sps ?: return null
            val pp = pps ?: return null
            return Triple(ss, pp, vv)
        }
    }

    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var sendKeyFrame = false
    private var forceParamsOnce = false

    fun setVideoInfo(sps: ByteArray?, pps: ByteArray?, vps: ByteArray?) {
        this.sps = sps
        this.pps = pps
        this.vps = vps
        sendKeyFrame = false
    }

    fun forceParamsOnce() {
        forceParamsOnce = true
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
        if (fixedBuffer.getVideoStartCodeSize() == 0) {
            convertHvccToAnnexB(fixedBuffer)?.let { fixedBuffer = it }
        }

        // If still no start code, we can't safely packetize
        if (fixedBuffer.remaining() < 4 && fixedBuffer.getVideoStartCodeSize() == 0) return emptyList()

        val ts = mediaFrame.info.timestamp * 1000L
        val frames = mutableListOf<RtpFrame>()

        var paramsInjectedForThisAu = false
        var audForThisAuSent = false

        // Iterate through Annex‑B NAL units within this access unit
        var slice = fixedBuffer.slice()
        while (slice.remaining() > 0) {
            val startCodeSize = slice.getStartCodeSizeAtPos()
            if (startCodeSize == 0) {
                // No further start codes: treat the remainder as a single NAL
                val startPos = slice.position()
                if (slice.remaining() < 2) break
                val header0 = slice.get(startPos).toInt() and 0xFF
                val nalType = (header0 shr 1) and 0x3F
                val isParamNal = (nalType == 32 || nalType == 33 || nalType == 34)

                if (!audForThisAuSent) {
                    // Insert HEVC AUD (NAL type 35) to help delimiting access units over TCP
                    val aud = byteArrayOf(((35 and 0x3F) shl 1).toByte(), 0x01, 0x80.toByte())
                    val b = getBuffer(aud.size + RTP_HEADER_LENGTH)
                    val rtpTs = updateTimeStamp(b, ts)
                    updateSeq(b)
                    System.arraycopy(aud, 0, b, RTP_HEADER_LENGTH, aud.size)
                    frames.add(RtpFrame.Video(b, rtpTs, b.size))
                }

                if (((nalType == IDR_W_DLP) || (nalType == IDR_N_LP) || mediaFrame.info.isKeyFrame || forceParamsOnce) && !isParamNal && !paramsInjectedForThisAu) {
                    listOfNotNull(vps, sps, pps).forEach { nal ->
                        val b = getBuffer(nal.size + RTP_HEADER_LENGTH)
                        val rtpTs = updateTimeStamp(b, ts)
                        System.arraycopy(nal, 0, b, RTP_HEADER_LENGTH, nal.size)
                        updateSeq(b)
                        frames.add(RtpFrame.Video(b, rtpTs, b.size))
                    }
                    sendKeyFrame = true
                    if (forceParamsOnce) forceParamsOnce = false
                }

                val naluLen = slice.remaining()
                packetizeHevcNal(slice, startPos, naluLen, ts, frames, markLast = true)
                slice.position(startPos + naluLen)
                break
            }

            val startPos = slice.position() + startCodeSize
            if (startPos >= slice.limit()) break

            // Find the next start code to delimit this NAL
            val search = slice.duplicate().apply { position(startPos) }
            val next = findNextStartCodeIndex(search)
            val naluLen = next?.first ?: (slice.limit() - startPos)

            // Injection of VPS/SPS/PPS once per AU before first non-parameter NAL if requested
            val header0 = slice.get(startPos).toInt() and 0xFF
            val nalType = (header0 shr 1) and 0x3F
            val isParamNal = (nalType == 32 || nalType == 33 || nalType == 34)
            if (!audForThisAuSent) {
                val aud = byteArrayOf(((35 and 0x3F) shl 1).toByte(), 0x01, 0x80.toByte())
                val b = getBuffer(aud.size + RTP_HEADER_LENGTH)
                val rtpTs = updateTimeStamp(b, ts)
                updateSeq(b)
                System.arraycopy(aud, 0, b, RTP_HEADER_LENGTH, aud.size)
                frames.add(RtpFrame.Video(b, rtpTs, b.size))
                audForThisAuSent = true
            }
            if (((nalType == IDR_W_DLP) || (nalType == IDR_N_LP) || mediaFrame.info.isKeyFrame || forceParamsOnce) && !isParamNal && !paramsInjectedForThisAu) {
                listOfNotNull(vps, sps, pps).forEach { nal ->
                    val b = getBuffer(nal.size + RTP_HEADER_LENGTH)
                    val rtpTs = updateTimeStamp(b, ts)
                    System.arraycopy(nal, 0, b, RTP_HEADER_LENGTH, nal.size)
                    updateSeq(b)
                    frames.add(RtpFrame.Video(b, rtpTs, b.size))
                }
                sendKeyFrame = true
                paramsInjectedForThisAu = true
                if (forceParamsOnce) forceParamsOnce = false
            }

            val isLastNal = (next == null)
            packetizeHevcNal(slice, startPos, naluLen, ts, frames, markLast = isLastNal)

            // Advance to next NAL start (position after this NAL and before next start code)
            slice.position(startPos + naluLen)
            if (isLastNal) break
        }

        return frames
    }

    internal fun ByteBuffer.getVideoStartCodeSize(): Int = when {
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x00.toByte() && get(3) == 0x01.toByte() -> 4
        get(0) == 0x00.toByte() && get(1) == 0x00.toByte() && get(2) == 0x01.toByte() -> 3
        else -> 0
    }

    private fun ByteBuffer.getStartCodeSizeAtPos(): Int {
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

    private fun packetizeHevcNal(
        base: ByteBuffer,
        startPos: Int,
        naluLen: Int,
        ts: Long,
        out: MutableList<RtpFrame>,
        markLast: Boolean
    ) {
        // The NAL length includes the 2-byte HEVC NAL header
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
            return
        }

        // FU fragmentation (RFC 7798)
        val nalHeader0 = base.get(startPos).toInt() and 0xFF
        val nalHeader1 = base.get(startPos + 1).toInt() and 0xFF
        val nalType = (nalHeader0 shr 1) and 0x3F

        val fuIndicator0 = (((49 and 0x3F) shl 1) or (nalHeader0 and 0x81)).toByte() // replace type with 49, keep F and reserved bits
        val fuIndicator1 = nalHeader1.toByte() // preserve nuh_layer_id and nuh_temporal_id_plus1

        val maxFrag = MAX_PACKET_SIZE - RTP_HEADER_LENGTH - 3
        var offset = 2 // skip original 2-byte NAL header
        var first = true
        while (offset < naluLen) {
            val remaining = naluLen - offset
            val chunk = if (remaining > maxFrag) maxFrag else remaining
            val buffer = getBuffer(chunk + RTP_HEADER_LENGTH + 3)
            buffer[RTP_HEADER_LENGTH] = fuIndicator0
            buffer[RTP_HEADER_LENGTH + 1] = fuIndicator1
            var fuHeader = (nalType and 0x3F).toByte()
            if (first) fuHeader = (fuHeader.toInt() or 0x80).toByte() // S bit
            if (remaining <= maxFrag) fuHeader = (fuHeader.toInt() or 0x40).toByte() // E bit on last fragment of this NAL
            buffer[RTP_HEADER_LENGTH + 2] = fuHeader

            val rtpTs = updateTimeStamp(buffer, ts)

            val dup = base.duplicate()
            dup.limit(startPos + offset + chunk)
            dup.position(startPos + offset)
            dup.get(buffer, RTP_HEADER_LENGTH + 3, chunk)
            offset += chunk

            if (remaining <= maxFrag && markLast) markPacket(buffer)
            updateSeq(buffer)
            out.add(RtpFrame.Video(buffer, rtpTs, buffer.size))
            first = false
        }
    }

    // Converts a length-prefixed HEVC stream (HVCC) to Annex-B start code delimited stream.
    private fun convertHvccToAnnexB(src: ByteBuffer): ByteBuffer? {
        val duplicate = src.duplicate()
        if (duplicate.remaining() < 4) return null

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

    override fun reset() {
        super.reset()
        sendKeyFrame = false
    }
}
