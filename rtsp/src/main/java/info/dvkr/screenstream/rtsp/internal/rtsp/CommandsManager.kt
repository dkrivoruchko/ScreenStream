package info.dvkr.screenstream.rtsp.internal.rtsp

import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class CommandsManager(
    private val appVersion: String,
    private val host: String?,
    private val port: Int,
    private val path: String,
    private val user: String?,
    private val password: String?
) {
    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, UNKNOWN }

    internal data class Command(val method: Method, val cSeq: Int, val status: Int, val text: String)

    private companion object {
        private val DIGEST_AUTH_REGEX = Regex("""realm="([^"]+)",\s*nonce="([^"]+)"(.*)""", RegexOption.IGNORE_CASE)
        private val QOP_REGEX = Regex("""qop="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val OPAQUE_REGEX = Regex("""opaque="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val ALGORITHM_REGEX = Regex("""algorithm="?([^," ]+)"?""", RegexOption.IGNORE_CASE)
        private val CLIENT_PORT_REGEX = Regex("""client_port=([0-9]+)-([0-9]+)""")
        private val SERVER_PORT_REGEX = Regex("""server_port=([0-9]+)-([0-9]+)""")
        private val SESSION_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)
        private val CSEQ_REGEX = Regex("""CSeq\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val STATUS_REGEX = Regex("""RTSP/\d.\d\s+(\d+)\s+(\S+)""", RegexOption.IGNORE_CASE)
        private val CONTENT_LENGTH_REGEX = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)

        // Supported AAC sampling rates
        private val AUDIO_SAMPLING_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, -1, -1, -1
        )
    }

    private val lock = Mutex()

    private var authorization: String? = null
    private var sdpSessionId: Int = SecureRandom().nextInt(Int.MAX_VALUE)
    private var sessionId: String? = null
    private var cSeq: Int = 0

    internal suspend fun reset() = lock.withLock {
        authorization = null
        sdpSessionId = SecureRandom().nextInt(Int.MAX_VALUE)
        sessionId = null
        cSeq = 0
    }

    internal suspend fun createOptions(): String = lock.withLock {
        buildString {
            append("OPTIONS rtsp://$host:$port$path RTSP/1.0\r\n")
            addHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createAnnounce(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String = lock.withLock {
        buildString {
            val videoCodecBody = videoParams.toVideoCodecBody()
            val audioCodecBody = audioParams.toAudioCodecBody()
            val body = buildString {
                append("v=0\r\n")
                append("o=- $sdpSessionId $sdpSessionId IN IP4 127.0.0.1\r\n")
                append("s=Unnamed\r\n")
                append("i=N/A\r\n")
                append("c=IN IP4 $host\r\n")
                append("t=0 0\r\n")
                append("a=recvonly\r\n")
                append(videoCodecBody)
                append(audioCodecBody)
            }

            append("ANNOUNCE rtsp://$host:$port$path RTSP/1.0\r\n")
            append("Content-Type: application/sdp\r\n")
            addHeaders()
            append("Content-Length: ${body.toByteArray(Charsets.US_ASCII).size}\r\n")
            append("\r\n")
            append(body)
        }
    }

    internal suspend fun createAnnounceWithAuth(
        videoParams: RtspClient.VideoParams,
        audioParams: RtspClient.AudioParams?,
        authResponse: String
    ): String = lock.withLock {
        authorization = when (val digestMatch = DIGEST_AUTH_REGEX.find(authResponse)) {
            null -> {
                val base64Data = "$user:$password".toByteArray().encodeBase64()
                "Basic $base64Data"
            }

            else -> {
                val realm = digestMatch.groupValues[1]
                val nonce = digestMatch.groupValues[2]
                val remains = digestMatch.groupValues[3]
                val qop = QOP_REGEX.find(remains)?.groupValues?.get(1).orEmpty()
                val opaque = OPAQUE_REGEX.find(remains)?.groupValues?.get(1).orEmpty()
                val algMatch = ALGORITHM_REGEX.find(remains)
                val algorithm = algMatch?.groupValues?.get(1)?.uppercase() ?: "MD5"

                var hash1 = "$user:$realm:$password".md5()
                val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                if (algorithm == "MD5-SESS") {
                    hash1 = "$hash1:$nonce:$cnonce".md5()
                }

                val hash2 = "ANNOUNCE:rtsp://$host:$port$path".md5()
                val finalHash = hash1PlusNonce(hash1, nonce, hash2, qop, cnonce)

                buildString {
                    append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", ")
                    append("uri=\"rtsp://$host:$port$path\", response=\"$finalHash\"")
                    if (qop.isNotEmpty()) append(", qop=\"$qop\"")
                    if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
                    append(", algorithm=\"$algorithm\"")
                    if (algorithm == "MD5-SESS" && cnonce.isNotEmpty()) {
                        append(", cnonce=\"$cnonce\"")
                    }
                }
            }
        }
    }.run { createAnnounce(videoParams, audioParams) }

    internal suspend fun createSetup(protocol: Protocol, udpPortClient: Int, udpPortServer: Int, trackId: Int): String = lock.withLock {
        val transport = if (protocol == Protocol.UDP) {
            "UDP;unicast;client_port=$udpPortClient-$udpPortServer;mode=record"
        } else {
            "TCP;unicast;interleaved=${2 * trackId}-${2 * trackId + 1};mode=record"
        }
        return buildString {
            append("SETUP rtsp://$host:$port$path/trackID=$trackId RTSP/1.0\r\n")
            append("Transport: RTP/AVP/$transport\r\n")
            addHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createRecord(): String = lock.withLock {
        buildString {
            append("RECORD rtsp://$host:$port$path RTSP/1.0\r\n")
            append("Range: npt=0.000-\r\n")
            addHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createTeardown(): String = lock.withLock {
        buildString {
            append("TEARDOWN rtsp://$host:$port$path RTSP/1.0\r\n")
            addHeaders()
            append("\r\n")
        }
    }

    @Throws
    internal suspend fun getResponse(
        readLine: suspend () -> String?,
        readBytes: suspend (ByteArray, Int) -> Unit,
        method: Method
    ): Command = lock.withLock {
        val headerLines = mutableListOf<String>()
        var contentLength = 0
        while (true) {
            val line = readLine()?.ifBlank { null } ?: break
            headerLines.add(line)
            val match = CONTENT_LENGTH_REGEX.find(line)
            if (match != null) contentLength = match.groupValues[1].toIntOrNull() ?: 0
        }

        val body = if (contentLength <= 0) "" else
            ByteArray(contentLength).also { readBytes(it, contentLength) }.decodeToString()

        val responseText = buildString {
            headerLines.forEach { append(it).append("\r\n") }
            append("\r\n")
            append(body)
        }

        val command = Command(method, CSEQ_REGEX.parseToInt(responseText), STATUS_REGEX.parseToInt(responseText), responseText)

        val sessionMatches = SESSION_REGEX.findAll(command.text).map { it.groupValues[1] }.toList()
        if (sessionMatches.isNotEmpty()) {
            sessionId = sessionMatches.joinToString(",") { it.trim() }
        }

        return command
    }

    internal fun getPorts(command: Command): Pair<RtspClient.Ports?, RtspClient.Ports?> {
        val client = CLIENT_PORT_REGEX.find(command.text)?.destructured?.let { (client, server) ->
            client.toIntOrNull()?.let { c ->
                server.toIntOrNull()?.let { s ->
                    RtspClient.Ports(c, s)
                }
            }
        }

        val server = SERVER_PORT_REGEX.find(command.text)?.destructured?.let { (client, server) ->
            client.toIntOrNull()?.let { c ->
                server.toIntOrNull()?.let { s ->
                    RtspClient.Ports(c, s)
                }
            }
        }

        return client to server
    }

    @Throws(IOException::class)
    internal suspend fun getResponseWithTimeout(
        readLine: suspend () -> String?,
        readBytes: suspend (ByteArray, Int) -> Unit,
        method: Method,
        timeoutMs: Long = 15_000
    ): Command =
        withTimeout(timeoutMs) { getResponse(readLine, readBytes, method) }

    private fun StringBuilder.addHeaders() {
        append("CSeq: ${++cSeq}\r\n")
        if (!sessionId.isNullOrEmpty()) append("Session: $sessionId\r\n")
        if (!authorization.isNullOrEmpty()) append("Authorization: $authorization\r\n")
        append("User-Agent: ScreenStream/$appVersion\r\n")
    }

    private fun RtspClient.AudioParams?.toAudioCodecBody(): String = when {
        this == null -> ""
        else -> when (codec) {
            Codec.Audio.G711 -> createG711Body(1)
            Codec.Audio.AAC -> createAacBody(1, sampleRate, isStereo)
            Codec.Audio.OPUS -> createOpusBody(1)
        }
    }

    private fun createOpusBody(trackAudio: Int): String {
        val payload = OpusPacket.PAYLOAD_TYPE + trackAudio
        return buildString {
            append("m=audio 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload OPUS/48000/2\r\n")
            append("a=control:trackID=$trackAudio\r\n")
        }
    }

    private fun createG711Body(trackAudio: Int): String {
        val payload = G711Packet.PAYLOAD_TYPE
        return buildString {
            append("m=audio 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload PCMA/8000/1\r\n")
            append("a=control:trackID=$trackAudio\r\n")
        }
    }

    private fun createAacBody(trackAudio: Int, sampleRate: Int, isStereo: Boolean): String {
        val sampleRateIndex = AUDIO_SAMPLING_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 3
        val channel = if (isStereo) 2 else 1
        val config = ((2 and 0x1F) shl 11) or ((sampleRateIndex and 0x0F) shl 7) or ((channel and 0x0F) shl 3)
        val payload = AacPacket.PAYLOAD_TYPE + trackAudio
        return buildString {
            append("m=audio 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload MPEG4-GENERIC/$sampleRate/$channel\r\n")
            append("a=fmtp:$payload profile-level-id=1; mode=AAC-hbr; config=")
            append(Integer.toHexString(config))
            append("; sizelength=13; indexlength=3; indexdeltalength=3\r\n")
            append("a=control:trackID=$trackAudio\r\n")
        }
    }

    private fun RtspClient.VideoParams.toVideoCodecBody(): String {
        val spsString = sps.getData().encodeBase64()
        val ppsString = pps?.getData()?.encodeBase64() ?: ""
        val vpsString = vps?.getData()?.encodeBase64() ?: ""

        return when (codec) {
            Codec.Video.H264 -> createH264Body(0, spsString, ppsString)
            Codec.Video.H265 -> createH265Body(0, spsString, ppsString, vpsString)
            Codec.Video.AV1 -> createAV1Body(0)
        }
    }

    private fun createAV1Body(trackVideo: Int): String {
        val payload = Av1Packet.PAYLOAD_TYPE + trackVideo
        return buildString {
            append("m=video 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload AV1/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
            append("a=fmtp:$payload profile-id=0; level-idx=0\r\n")
            append("a=control:trackID=$trackVideo\r\n")
        }
    }

    private fun createH264Body(trackVideo: Int, sps: String, pps: String): String {
        val payload = H264Packet.PAYLOAD_TYPE + trackVideo
        return buildString {
            append("m=video 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload H264/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
            append("a=fmtp:$payload packetization-mode=1; sprop-parameter-sets=$sps,$pps\r\n")
            append("a=control:trackID=$trackVideo\r\n")
        }
    }

    private fun createH265Body(trackVideo: Int, sps: String, pps: String, vps: String): String {
        val payload = H265Packet.PAYLOAD_TYPE + trackVideo
        return buildString {
            append("m=video 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload H265/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
            append("a=fmtp:$payload packetization-mode=1; sprop-sps=$sps; sprop-pps=$pps; sprop-vps=$vps\r\n")
            append("a=control:trackID=$trackVideo\r\n")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.encodeBase64(): String = Base64.encode(this)

    private fun ByteArray.getData(): ByteArray {
        val startCodeSize = when {
            size >= 4 && this[0] == 0x00.toByte() && this[1] == 0x00.toByte() && this[2] == 0x00.toByte() && this[3] == 0x01.toByte() -> 4
            size >= 3 && this[0] == 0x00.toByte() && this[1] == 0x00.toByte() && this[2] == 0x01.toByte() -> 3
            else -> 0
        }
        return copyOfRange(startCodeSize, size)
    }

    private fun String.md5(): String = runCatching {
        MessageDigest.getInstance("MD5").digest(toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun Regex.parseToInt(input: String): Int =
        find(input)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private fun hash1PlusNonce(hash1: String, nonce: String, hash2: String, qop: String, cnonce: String): String =
        if (qop.equals("auth", ignoreCase = true)) {
            val nonceCount = "00000001"
            "$hash1:$nonce:$nonceCount:$cnonce:$qop:$hash2".md5()
        } else {
            "$hash1:$nonce:$hash2".md5()
        }
}