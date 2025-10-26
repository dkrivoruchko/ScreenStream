package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class SdpBuilder {

    fun createSdpBody(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?, sdpSessionId: Int): String {
        val spsString = videoParams.sps.encodeBase64()
        val ppsString = videoParams.pps?.encodeBase64().orEmpty()
        val vpsString = videoParams.vps?.encodeBase64().orEmpty()
        val videoCodecBody = when (videoParams.codec) {
            Codec.Video.H264 -> createH264Body(0, spsString, ppsString)
            Codec.Video.H265 -> createH265Body(0, spsString, ppsString, vpsString)
            Codec.Video.AV1 -> createAV1Body(0)
        }

        val audioCodecBody = when (audioParams) {
            null -> ""
            else -> when (audioParams.codec) {
                Codec.Audio.G711 -> createG711Body(1)
                Codec.Audio.AAC -> createAacBody(1, audioParams.sampleRate, audioParams.isStereo)
                Codec.Audio.OPUS -> createOpusBody(1)
            }
        }
        return buildString {
            append("v=0\r\n")
            append("o=- $sdpSessionId $sdpSessionId IN IP4 127.0.0.1\r\n")
            append("s=ScreenStream\r\n")
            append("i=ScreenStream\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=type:broadcast\r\n")
            append("a=control:*\r\n")
            append(videoCodecBody)
            if (audioCodecBody.isNotEmpty()) append(audioCodecBody)
        }
    }

    private fun createOpusBody(trackAudio: Int): String = buildString {
        val payload = OpusPacket.Companion.PAYLOAD_TYPE + trackAudio
        append("m=audio 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload OPUS/48000/2\r\n")
        // RFC 7587 declarative SDP parameters to help some clients (e.g., GStreamer)
        append("a=fmtp:$payload sprop-stereo=1; maxplaybackrate=48000; sprop-maxcapturerate=48000\r\n")
        append("a=ptime:20\r\n")
        append("a=control:trackID=$trackAudio\r\n")
    }

    private fun createG711Body(trackAudio: Int): String = buildString {
        val payload = G711Packet.Companion.PAYLOAD_TYPE
        append("m=audio 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload PCMA/8000/1\r\n")
        append("a=control:trackID=$trackAudio\r\n")
    }

    private fun createAacBody(trackAudio: Int, sampleRate: Int, isStereo: Boolean): String {
        val sampleRateIndex = AUDIO_SAMPLING_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 3 // default 48k
        val channels = if (isStereo) 2 else 1
        val config = ((2 /*AAC-LC*/ and 0x1F) shl 11) or ((sampleRateIndex and 0x0F) shl 7) or ((channels and 0x0F) shl 3)
        val payload = AacPacket.Companion.PAYLOAD_TYPE + trackAudio
        return buildString {
            append("m=audio 0 RTP/AVP $payload\r\n")
            // RFC 3640 (mpeg4-generic) AAC-LC, one AU per RTP, size+index header (16 bits)
            append("a=rtpmap:$payload mpeg4-generic/$sampleRate/$channels\r\n")
            append("a=fmtp:$payload streamtype=5; profile-level-id=16; mode=AAC-hbr; config=")
            append(java.lang.String.format("%04x", config))
            append("; sizeLength=13; indexLength=3; indexDeltaLength=3; constantDuration=1024\r\n")
            append("a=control:trackID=$trackAudio\r\n")
        }
    }

    private fun createAV1Body(trackVideo: Int): String = buildString {
        val payload = Av1Packet.Companion.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload AV1/${BaseRtpPacket.Companion.VIDEO_CLOCK_FREQUENCY}\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    private fun createH264Body(trackVideo: Int, sps: String, pps: String): String = buildString {
        val payload = H264Packet.Companion.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload H264/${BaseRtpPacket.Companion.VIDEO_CLOCK_FREQUENCY}\r\n")
        val parsedProfile = runCatching { extractH264ProfileLevelId(sps) }.getOrNull()
        val profileLevelId = parsedProfile ?: "42e01f"
        append("a=fmtp:$payload packetization-mode=1; level-asymmetry-allowed=1; profile-level-id=$profileLevelId;")
        append(" sprop-parameter-sets=$sps,$pps\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    private fun extractH264ProfileLevelId(spsB64: String): String? = runCatching {
        val sps = Base64.Default.decode(spsB64)
        if (sps.size < 4) return null
        val profileIdc = sps[1].toInt() and 0xFF
        val constraints = sps[2].toInt() and 0xFF
        val levelIdc = sps[3].toInt() and 0xFF
        String.format("%02x%02x%02x", profileIdc, constraints, levelIdc)
    }.getOrNull()

    private fun createH265Body(trackVideo: Int, sps: String, pps: String, vps: String): String = buildString {
        val payload = H265Packet.Companion.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload H265/${BaseRtpPacket.Companion.VIDEO_CLOCK_FREQUENCY}\r\n")
        val parts = buildList {
            if (vps.isNotEmpty()) add("sprop-vps=$vps")
            if (sps.isNotEmpty()) add("sprop-sps=$sps")
            if (pps.isNotEmpty()) add("sprop-pps=$pps")
        }.joinToString("; ")
        if (parts.isNotEmpty()) append("a=fmtp:$payload $parts\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.encodeBase64(): String = Base64.Default.encode(this)

    private companion object {
        private val AUDIO_SAMPLING_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350, -1, -1, -1
        )
    }
}