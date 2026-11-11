package info.dvkr.screenstream.rtmp.internal.rtmp.flv

import info.dvkr.screenstream.rtmp.internal.AudioParams
import info.dvkr.screenstream.rtmp.internal.Codec
import info.dvkr.screenstream.rtmp.internal.VideoParams
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpConst
import info.dvkr.screenstream.rtmp.internal.rtmp.amf.Amf0Writer
import info.dvkr.screenstream.rtmp.internal.stripAnnexBStartCode
import java.io.ByteArrayOutputStream

/** Optional values advertised in onMetaData. Zero means "unknown, omit". */
internal data class StreamMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val videoBitrateBits: Int = 0,
    val audioBitrateBits: Int = 0,
)

internal class RtmpMediaPacket(val typeId: Int, val timestampMs: Long, val payload: ByteArray)

/**
 * Converts encoder output (Annex-B/AVCC video, ADTS/raw AAC, G.711) into RTMP audio/video message payloads (FLV tag bodies).
 *
 * - H.264: classic FLV (AVCDecoderConfigurationRecord + length-prefixed NALUs).
 * - H.265: Enhanced RTMP ("hvc1" FourCC). Needs an Enhanced-RTMP-capable peer (e.g. MediaMTX, recent FFmpeg/OBS/VLC 4).
 * - AAC-LC and G.711 A-law audio. Opus/AV1 are not supported.
 *
 * Instances are not thread-safe; use one per connection.
 */
internal class FlvPacketizer(
    private var video: VideoParams,
    private val audio: AudioParams?,
    private val metadata: StreamMetadata = StreamMetadata(),
) {
    val hasAudio: Boolean get() = audio != null

    private var baseUs = Long.MIN_VALUE
    private var lastVideoMs = 0L
    private var lastAudioMs = 0L

    init {
        require(video.codec == Codec.Video.H264 || video.codec == Codec.Video.H265) { "RTMP supports only H.264/H.265 (got ${video.codec.name})" }
        require(audio == null || audio.codec == Codec.Audio.AAC || audio.codec == Codec.Audio.G711) { "RTMP supports only AAC/G.711 (got ${audio?.codec?.name})" }
    }

    /** Swaps SPS/PPS/VPS (e.g. after a resize) while keeping the running timestamp base. */
    fun updateVideo(params: VideoParams) {
        require(params.codec == video.codec) { "Video codec cannot change while streaming" }
        video = params
    }

    // ------------------------------------------------------------------------------------------ metadata

    fun metadataPayload(forPublish: Boolean): ByteArray {
        val props = LinkedHashMap<String, Any?>()
        if (metadata.width > 0) props["width"] = metadata.width
        if (metadata.height > 0) props["height"] = metadata.height
        if (metadata.fps > 0) props["framerate"] = metadata.fps
        props["videocodecid"] = if (video.codec == Codec.Video.H264) 7 else FOURCC_HVC1
        if (metadata.videoBitrateBits > 0) props["videodatarate"] = metadata.videoBitrateBits / 1000.0
        if (audio != null) {
            props["audiocodecid"] = if (audio.codec == Codec.Audio.AAC) 10 else 7
            props["audiosamplerate"] = audio.sampleRate
            props["audiosamplesize"] = 16
            props["stereo"] = audio.isStereo
            props["audiochannels"] = if (audio.isStereo) 2 else 1
            if (metadata.audioBitrateBits > 0) props["audiodatarate"] = metadata.audioBitrateBits / 1000.0
        }
        props["encoder"] = "ScreenStream"

        val writer = Amf0Writer()
        if (forPublish) writer.string("@setDataFrame")
        return writer.string("onMetaData").ecmaArray(props).toByteArray()
    }

    // ------------------------------------------------------------------------------------------ sequence headers

    fun videoSequenceHeader(): ByteArray = when (video.codec) {
        Codec.Video.H264 -> {
            val sps = video.sps.stripAnnexBStartCode()
            val pps = checkNotNull(video.pps) { "PPS missing" }.stripAnnexBStartCode()
            byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00) + avcDecoderConfigurationRecord(sps, pps)
        }

        Codec.Video.H265 -> {
            val vps = checkNotNull(video.vps) { "VPS missing" }.stripAnnexBStartCode()
            val sps = video.sps.stripAnnexBStartCode()
            val pps = checkNotNull(video.pps) { "PPS missing" }.stripAnnexBStartCode()
            byteArrayOf((0x80 or (1 shl 4) or EX_SEQUENCE_START).toByte()) + HVC1 + hevcDecoderConfigurationRecord(vps, sps, pps)
        }

        Codec.Video.AV1 -> error("AV1 is not supported over RTMP")
    }

    fun audioSequenceHeader(): ByteArray? {
        val params = audio ?: return null
        return if (params.codec == Codec.Audio.AAC) {
            byteArrayOf(AAC_TAG_HEADER, 0x00) + audioSpecificConfig(params.sampleRate, if (params.isStereo) 2 else 1)
        } else {
            null // G.711 has no configuration packet
        }
    }

    // ------------------------------------------------------------------------------------------ frames

    /** Returns null when the access unit carried only parameter sets/AUD (nothing to send). */
    fun packVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyFrame: Boolean): RtmpMediaPacket? {
        val isHevc = video.codec == Codec.Video.H265
        val nalus = toLengthPrefixed(data, offset, length, isHevc) ?: return null
        val frameType = if (isKeyFrame) 1 else 2
        val header = if (isHevc) {
            byteArrayOf((0x80 or (frameType shl 4) or EX_CODED_FRAMES_X).toByte()) + HVC1
        } else {
            byteArrayOf(((frameType shl 4) or 7).toByte(), 0x01, 0x00, 0x00, 0x00)
        }
        val payload = ByteArray(header.size + nalus.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(nalus, 0, payload, header.size, nalus.size)
        lastVideoMs = maxOf(lastVideoMs, toMs(timestampUs))
        return RtmpMediaPacket(RtmpConst.MSG_VIDEO, lastVideoMs, payload)
    }

    fun packAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long): RtmpMediaPacket? {
        val params = audio ?: return null
        val payload = when (params.codec) {
            Codec.Audio.AAC -> {
                var start = offset
                var size = length
                val adts = adtsHeaderLength(data, offset, length)
                if (adts > 0) { start += adts; size -= adts }
                if (size <= 2 && adts == 0) return null // stray AudioSpecificConfig buffer
                val out = ByteArray(2 + size)
                out[0] = AAC_TAG_HEADER; out[1] = 0x01
                System.arraycopy(data, start, out, 2, size)
                out
            }

            Codec.Audio.G711 -> {
                val out = ByteArray(1 + length)
                out[0] = G711_ALAW_TAG_HEADER
                System.arraycopy(data, offset, out, 1, length)
                out
            }

            else -> return null
        }
        lastAudioMs = maxOf(lastAudioMs, toMs(timestampUs))
        return RtmpMediaPacket(RtmpConst.MSG_AUDIO, lastAudioMs, payload)
    }

    @Synchronized
    private fun toMs(timestampUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) baseUs = timestampUs
        return ((timestampUs - baseUs) / 1000L).coerceAtLeast(0L)
    }

    // ------------------------------------------------------------------------------------------ NAL handling

    private fun toLengthPrefixed(src: ByteArray, off: Int, len: Int, isHevc: Boolean): ByteArray? {
        val end = off + len
        if (startCodeLength(src, off, end) == 0) {
            // Already length-prefixed (AVCC/HVCC) — pass through untouched.
            return if (len > 4) src.copyOfRange(off, end) else null
        }

        val out = ByteArrayOutputStream(len + 16)
        var written = 0
        var i = off
        while (i < end) {
            val sc = startCodeLength(src, i, end)
            if (sc == 0) { i++; continue }
            val nalStart = i + sc
            var j = nalStart
            while (j < end && startCodeLength(src, j, end) == 0) j++
            var nalEnd = j
            while (nalEnd > nalStart && src[nalEnd - 1].toInt() == 0) nalEnd-- // trailing_zero_8bits
            val size = nalEnd - nalStart
            if (size > 0) {
                val b0 = src[nalStart].toInt() and 0xFF
                val skip = if (isHevc) (b0 shr 1) and 0x3F in 32..35 else (b0 and 0x1F) in 7..9
                if (!skip) {
                    out.write(size ushr 24); out.write(size ushr 16); out.write(size ushr 8); out.write(size)
                    out.write(src, nalStart, size)
                    written++
                }
            }
            i = j
        }
        return if (written == 0) null else out.toByteArray()
    }

    private fun startCodeLength(b: ByteArray, i: Int, end: Int): Int = when {
        i + 3 < end && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1 -> 4
        i + 2 < end && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 -> 3
        else -> 0
    }

    // ------------------------------------------------------------------------------------------ configuration records

    private fun avcDecoderConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "SPS too short" }
        val out = ByteArrayOutputStream()
        out.write(1); out.write(sps[1].toInt()); out.write(sps[2].toInt()); out.write(sps[3].toInt())
        out.write(0xFF)                         // 4-byte NAL length
        out.write(0xE1)                         // 1 SPS
        out.write(sps.size ushr 8); out.write(sps.size and 0xFF); out.write(sps)
        out.write(1)                            // 1 PPS
        out.write(pps.size ushr 8); out.write(pps.size and 0xFF); out.write(pps)
        return out.toByteArray()
    }

    private fun hevcDecoderConfigurationRecord(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val info = parseHevcSps(sps)
        val out = ByteArrayOutputStream()
        out.write(1)                                              // configurationVersion
        out.write(info.profileTierLevel)                          // profile(1) + compat(4) + constraints(6) + level(1)
        out.write(0xF0); out.write(0x00)                          // min_spatial_segmentation_idc = 0
        out.write(0xFC)                                           // parallelismType = 0
        out.write(0xFC or info.chromaFormat)
        out.write(0xF8 or info.bitDepthLuma)
        out.write(0xF8 or info.bitDepthChroma)
        out.write(0x00); out.write(0x00)                          // avgFrameRate
        out.write(((info.maxSubLayers.coerceIn(1, 7)) shl 3) or (info.temporalIdNested shl 2) or 0x03)
        out.write(3)                                              // numOfArrays
        for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) {
            out.write(0x80 or type)                               // array_completeness = 1
            out.write(0); out.write(1)                            // numNalus
            out.write(nal.size ushr 8); out.write(nal.size and 0xFF); out.write(nal)
        }
        return out.toByteArray()
    }

    private class HevcSpsInfo(
        val profileTierLevel: ByteArray,
        val chromaFormat: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val maxSubLayers: Int,
        val temporalIdNested: Int,
    )

    private fun parseHevcSps(nal: ByteArray): HevcSpsInfo {
        val rbsp = unescape(nal)
        require(rbsp.size >= 15) { "HEVC SPS too short" }
        val maxSubLayersMinus1 = (rbsp[2].toInt() shr 1) and 0x07
        val nested = rbsp[2].toInt() and 0x01
        val ptl = rbsp.copyOfRange(3, 15)

        var chroma = 1; var bdLuma = 0; var bdChroma = 0
        if (maxSubLayersMinus1 == 0) runCatching {
            val br = BitReader(rbsp, 15)
            br.ue()                                                // sps_seq_parameter_set_id
            chroma = br.ue()
            if (chroma == 3) br.bit()
            br.ue(); br.ue()                                       // width, height
            if (br.bit() == 1) { br.ue(); br.ue(); br.ue(); br.ue() } // conformance window
            bdLuma = br.ue(); bdChroma = br.ue()
        }
        return HevcSpsInfo(ptl, chroma and 3, bdLuma and 7, bdChroma and 7, maxSubLayersMinus1 + 1, nested)
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var pos = startByte * 8
        fun bit(): Int {
            val byteIndex = pos ushr 3
            if (byteIndex >= data.size) throw IndexOutOfBoundsException()
            return ((data[byteIndex].toInt() shr (7 - (pos++ and 7))) and 1)
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException() }
            var value = 1
            repeat(zeros) { value = (value shl 1) or bit() }
            return value - 1
        }
    }

    // ------------------------------------------------------------------------------------------ AAC

    private fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val index = AAC_SAMPLE_RATES.indexOf(sampleRate)
        require(index >= 0) { "Unsupported AAC sample rate $sampleRate" }
        val objectType = 2 // AAC-LC
        val v = (objectType shl 11) or (index shl 7) or (channels shl 3)
        return byteArrayOf((v ushr 8).toByte(), v.toByte())
    }

    private fun adtsHeaderLength(b: ByteArray, off: Int, len: Int): Int {
        if (len < 7) return 0
        if ((b[off].toInt() and 0xFF) != 0xFF || (b[off + 1].toInt() and 0xF0) != 0xF0) return 0
        val protectionAbsent = (b[off + 1].toInt() and 0x01) == 1
        return if (protectionAbsent) 7 else 9
    }

    private companion object {
        const val EX_SEQUENCE_START = 0
        const val EX_CODED_FRAMES_X = 3
        const val FOURCC_HVC1 = 0x68766331 // "hvc1"
        val HVC1 = byteArrayOf('h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())

        /** SoundFormat=10 (AAC), rate=3 (44 kHz marker), 16-bit, stereo marker — fixed for AAC by the FLV spec. */
        const val AAC_TAG_HEADER: Byte = 0xAF.toByte()

        /** SoundFormat=7 (G.711 A-law), 16-bit, mono. */
        const val G711_ALAW_TAG_HEADER: Byte = 0x72

        val AAC_SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
