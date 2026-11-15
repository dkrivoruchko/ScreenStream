package info.dvkr.screenstream.rtmp.internal.rtmp.packets

import java.nio.ByteBuffer
import kotlin.experimental.and

// Helpers used by VideoEncoder to pull parameter sets out of MediaCodec config buffers (Annex-B).
// Names are kept from the former RTP packetizers so that VideoEncoder stays unchanged.

internal object H264Packet {
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

internal object H265Packet {
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

/** AV1 is not supported over RTMP; the encoder list never offers it. */
internal object Av1Packet {
    @Suppress("UNUSED_PARAMETER")
    fun extractObuSeq(buffer: ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo): ByteArray? = null
}
