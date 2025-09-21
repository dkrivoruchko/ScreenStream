package info.dvkr.screenstream.rtsp.internal.rtsp

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
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
    private val mode: Mode,
    private val host: String,
    private val port: Int,
    private val path: String,
    private val username: String?,
    private val password: String?
) {
    internal enum class Mode { CLIENT, SERVER }
    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    internal data class Command(val method: Method, val cSeq: Int, val status: Int, val text: String)
    internal data class PlayTrackInfo(val seq: Int? = null, val rtptime: Long? = null)

    private companion object {
        private val DIGEST_AUTH_REGEX = Regex("""realm="([^"]+)",\s*nonce="([^"]+)"(.*)""", RegexOption.IGNORE_CASE)
        private val QOP_REGEX = Regex("""qop="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val OPAQUE_REGEX = Regex("""opaque="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val ALGORITHM_REGEX = Regex("""algorithm="?([^," ]+)"?""", RegexOption.IGNORE_CASE)
        private val CLIENT_PORT_REGEX = Regex("""client_port=([0-9]+)-([0-9]+)""")
        private val SERVER_PORT_REGEX = Regex("""server_port=([0-9]+)-([0-9]+)""")
        private val SESSION_ID_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)
        private val SESSION_TIMEOUT_REGEX = Regex("""Session\s*:\s*[^\r\n;]+;[^\r\n]*timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val CSEQ_REGEX = Regex("""CSeq\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val STATUS_REGEX = Regex("""RTSP/\d.\d\s+(\d+)\s+(.+)""", RegexOption.IGNORE_CASE)
        private val CONTENT_LENGTH_REGEX = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val METHOD_REGEX = Regex("""(\w+) (\S+) RTSP""", RegexOption.IGNORE_CASE)
        private val TRANSPORT_REGEX = Regex("""Transport\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
        private val SESSION_HEADER_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)

        private val AUDIO_SAMPLING_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, -1, -1, -1
        )
    }

    private val lock = Mutex()
    private var authorization: String? = null
    private var sessionId: String = ""

    @Volatile
    private var sessionTimeoutSec: Int? = null
    private var sdpSessionId: Int = SecureRandom().nextInt(Int.MAX_VALUE)
    private var cSeq: Int = 0

    internal fun hasSession(): Boolean = sessionId.isNotBlank()

    internal fun getSuggestedKeepAliveDelayMs(defaultMs: Long = 60_000): Long {
        val t = sessionTimeoutSec
        if (t == null || t <= 0) return defaultMs
        val ms = (t - 5).coerceAtLeast(1) * 1000L
        return minOf(ms, defaultMs)
    }

    internal suspend fun reset() = lock.withLock {
        authorization = null
        sdpSessionId = SecureRandom().nextInt(Int.MAX_VALUE)
        cSeq = 0
        sessionId = ""
        sessionTimeoutSec = null
    }

    internal suspend fun createOptions(): String = lock.withLock {
        require(mode == Mode.CLIENT)
        buildString {
            append("OPTIONS rtsp://$host:$port$path RTSP/1.0\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createGetParameter(): String = lock.withLock {
        require(mode == Mode.CLIENT)
        buildString {
            append("GET_PARAMETER rtsp://$host:$port$path RTSP/1.0\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createAnnounce(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String = lock.withLock {
        require(mode == Mode.CLIENT)
        buildString {
            val body = createSdpBody(videoParams, audioParams)
            append("ANNOUNCE rtsp://$host:$port$path RTSP/1.0\r\n")
            append("Content-Type: application/sdp\r\n")
            addDefaultHeaders(contentLength = body.toByteArray(Charsets.US_ASCII).size)
            append("\r\n")
            append(body)
        }
    }

    internal suspend fun applyAuthFor(method: Method, uriPath: String, authResponse: String) = lock.withLock {
        require(mode == Mode.CLIENT)
        val user = username.orEmpty()
        val pass = password.orEmpty()

        authorization = when (val digestAuthMatch = DIGEST_AUTH_REGEX.find(authResponse)) {
            null -> {
                val base64 = "$user:$pass".toByteArray(Charsets.ISO_8859_1).encodeBase64()
                "Basic $base64"
            }

            else -> {
                val realm = digestAuthMatch.groupValues[1]
                val nonce = digestAuthMatch.groupValues[2]
                val remains = digestAuthMatch.groupValues[3]
                val qopRaw = QOP_REGEX.find(remains)?.groupValues?.get(1)
                val qop = qopRaw?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", ignoreCase = true) }
                    ?: qopRaw?.split(',')?.map { it.trim() }?.firstOrNull().orEmpty()
                val opaque = OPAQUE_REGEX.find(remains)?.groupValues?.get(1).orEmpty()
                val algorithm = ALGORITHM_REGEX.find(remains)?.groupValues?.get(1)?.uppercase() ?: "MD5"

                var ha1 = "$user:$realm:$pass".md5()
                val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                if (algorithm == "MD5-SESS") ha1 = "$ha1:$nonce:$cnonce".md5()
                val methodName = method.name
                val ha2 = "$methodName:rtsp://$host:$port$uriPath".md5()
                val response = if (qop.isNotEmpty()) {
                    val nc = "00000001"
                    "$ha1:$nonce:$nc:$cnonce:$qop:$ha2".md5()
                } else {
                    "$ha1:$nonce:$ha2".md5()
                }

                buildString {
                    append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", ")
                    append("uri=\"rtsp://$host:$port$uriPath\", response=\"$response\"")
                    if (qop.isNotEmpty()) append(", qop=\"$qop\"")
                    if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
                    append(", algorithm=\"$algorithm\"")
                    if (qop.isNotEmpty()) append(", nc=00000001, cnonce=\"$cnonce\"")
                }
            }
        }
    }

    internal suspend fun createSetup(protocol: Protocol, clientRtpPort: Int, clientRtcpPort: Int, trackId: Int): String = lock.withLock {
        require(mode == Mode.CLIENT)
        val uri = "rtsp://$host:$port$path/trackID=$trackId"
        val transport = when (protocol) {
            Protocol.TCP -> "RTP/AVP/TCP;unicast;interleaved=${trackId shl 1}-${(trackId shl 1) + 1}"
            Protocol.UDP -> "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort"
        }
        buildString {
            append("SETUP $uri RTSP/1.0\r\n")
            append("Transport: $transport\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createRecord(): String = lock.withLock {
        require(mode == Mode.CLIENT)
        buildString {
            append("RECORD rtsp://$host:$port$path RTSP/1.0\r\n")
            append("Range: npt=0.000-\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createTeardown(): String = lock.withLock {
        require(mode == Mode.CLIENT)
        buildString {
            append("TEARDOWN rtsp://$host:$port$path RTSP/1.0\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    @Throws(IOException::class)
    internal suspend fun getResponseWithTimeout(
        readLine: suspend () -> String?,
        readBytes: suspend (ByteArray, Int) -> Unit,
        method: Method,
        timeoutMs: Long = 15_000
    ): Command = withTimeout(timeoutMs) { getResponse(readLine, readBytes, method) }

    private suspend fun getResponse(readLine: suspend () -> String?, readBytes: suspend (ByteArray, Int) -> Unit, method: Method): Command =
        lock.withLock {
            require(mode == Mode.CLIENT)
            val headerLines = mutableListOf<String>()
            var contentLength = 0
            while (true) {
                val line = readLine()?.ifBlank { null } ?: break
                headerLines.add(line)
                val matchResult = CONTENT_LENGTH_REGEX.find(line)
                if (matchResult != null) contentLength = matchResult.groupValues[1].toIntOrNull() ?: 0
            }
            val body = if (contentLength <= 0) "" else
                ByteArray(contentLength).also { readBytes(it, contentLength) }.decodeToString()

            val text = buildString {
                headerLines.forEach { append(it).append("\r\n") }
                append("\r\n")
                append(body)
            }
            SESSION_ID_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { sid ->
                if (sid.isNotEmpty()) sessionId = sid
            }
            SESSION_TIMEOUT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { t ->
                if (t > 0) sessionTimeoutSec = t
            }

            val cmd = Command(
                method,
                CSEQ_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                STATUS_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                text
            )
            if (cmd.cSeq != cSeq) {
                val message = "CSeq mismatch: expected $cSeq, got ${cmd.cSeq} (method=$method)"
                val isHandshake = when (method) {
                    Method.OPTIONS, Method.ANNOUNCE, Method.SETUP, Method.RECORD -> true
                    else -> false
                }
                if (isHandshake) throw IOException(message)
                XLog.w(getLog("getResponse", message), IOException(message))
            }
            return cmd
        }

    internal fun getPorts(command: Command): Pair<RtspClient.Ports?, RtspClient.Ports?> {
        require(mode == Mode.CLIENT)
        val transport = TRANSPORT_REGEX.find(command.text)?.groupValues?.get(1) ?: return null to null
        val client = CLIENT_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) ->
            RtspClient.Ports(rtp.toInt(), rtcp.toInt())
        }
        val server = SERVER_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) ->
            RtspClient.Ports(rtp.toInt(), rtcp.toInt())
        }
        return client to server
    }

    internal fun parseRequest(request: String): Pair<Method, Int> {
        require(mode == Mode.SERVER)
        val method = runCatching { Method.valueOf(METHOD_REGEX.find(request)?.groups?.get(1)?.value?.uppercase() ?: "") }
            .getOrDefault(Method.UNKNOWN)
        val cseq = CSEQ_REGEX.find(request)?.groups?.get(1)?.value?.toIntOrNull() ?: -1
        return method to cseq
    }

    internal fun getTransport(request: String): String {
        require(mode == Mode.SERVER)
        return TRANSPORT_REGEX.find(request)?.groupValues?.get(1)?.trim().orEmpty()
    }

    internal fun getSessionFromRequest(request: String): String? {
        require(mode == Mode.SERVER)
        return SESSION_HEADER_REGEX.find(request)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }

    internal fun parseClientPorts(transport: String): Pair<Int, Int>? {
        require(mode == Mode.SERVER)
        return CLIENT_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) ->
            rtp.toIntOrNull() to rtcp.toIntOrNull()
        }?.takeIf { it.first != null && it.second != null }?.let { it.first!! to it.second!! }
    }

    internal fun createOptionsResponse(cSeq: Int): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER\r\n")
        append("\r\n")
    }

    internal fun createDescribeResponse(cSeq: Int, videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String {
        require(mode == Mode.SERVER)
        val sdp = createSdpBody(videoParams, audioParams)
        return buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("CSeq: $cSeq\r\n")
            append("Content-Base: rtsp://$host:$port$path/\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.toByteArray(Charsets.US_ASCII).size}\r\n")
            append("\r\n")
            append(sdp)
        }
    }

    internal fun createSetupResponse(cSeq: Int, transport: String, serverRtpPort: Int, serverRtcpPort: Int, sessionId: String): String =
        buildString {
            require(mode == Mode.SERVER)
            append("RTSP/1.0 200 OK\r\n")
            append("CSeq: $cSeq\r\n")
            val newTransport = if (transport.contains("client_port")) "$transport;server_port=$serverRtpPort-$serverRtcpPort" else transport
            append("Transport: $newTransport\r\n")
            append("Session: $sessionId\r\n")
            append("Cache-Control: no-cache\r\n")
            append("\r\n")
        }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, vararg tracks: Int): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        val rtpInfo = tracks.distinct().sorted().joinToString(separator = ",") { tid -> "url=rtsp://$host:$port$path/trackID=$tid" }
        if (rtpInfo.isNotEmpty()) append("RTP-Info: ").append(rtpInfo).append("\r\n")
        append("\r\n")
    }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, trackInfo: Map<Int, PlayTrackInfo>): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        val rtpInfo = trackInfo.toSortedMap().entries.joinToString(",") { (tid, info) ->
            buildString {
                append("url=rtsp://$host:$port$path/trackID=$tid")
                info.seq?.let { append(";seq=$it") }
                info.rtptime?.let { append(";rtptime=$it") }
            }
        }
        if (rtpInfo.isNotEmpty()) append("RTP-Info: ").append(rtpInfo).append("\r\n")
        append("\r\n")
    }


    internal fun createPauseResponse(cSeq: Int, sessionId: String): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createTeardownResponse(cSeq: Int, sessionId: String): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createGetParameterResponse(cSeq: Int, sessionId: String): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        if (sessionId.isNotBlank()) append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createServiceUnavailableResponse(cSeq: Int, retryAfterSeconds: Int = 2): String = buildString {
        require(mode == Mode.SERVER)
        append("RTSP/1.0 503 Service Unavailable\r\n")
        append("CSeq: $cSeq\r\n")
        append("Retry-After: ${retryAfterSeconds}\r\n")
        append("\r\n")
    }

    internal fun createErrorResponse(code: Int, cSeq: Int): String = buildString {
        require(mode == Mode.SERVER)
        val status = when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            454 -> "Session Not Found"
            461 -> "Unsupported Transport"
            500 -> "Internal Server Error"
            else -> "Unknown Error"
        }
        append("RTSP/1.0 $code $status\r\n")
        append("CSeq: $cSeq\r\n")
        append("\r\n")
    }

    private fun createSdpBody(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String {
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
        val payload = OpusPacket.PAYLOAD_TYPE + trackAudio
        append("m=audio 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload OPUS/48000/2\r\n")
        append("a=control:trackID=$trackAudio\r\n")
    }

    private fun createG711Body(trackAudio: Int): String = buildString {
        val payload = G711Packet.PAYLOAD_TYPE
        append("m=audio 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload PCMA/8000/1\r\n")
        append("a=control:trackID=$trackAudio\r\n")
    }

    private fun createAacBody(trackAudio: Int, sampleRate: Int, isStereo: Boolean): String {
        val sampleRateIndex = AUDIO_SAMPLING_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 3 // default 48k
        val channels = if (isStereo) 2 else 1
        val config = ((2 /*AAC-LC*/ and 0x1F) shl 11) or ((sampleRateIndex and 0x0F) shl 7) or ((channels and 0x0F) shl 3)
        val payload = AacPacket.PAYLOAD_TYPE + trackAudio
        return buildString {
            append("m=audio 0 RTP/AVP $payload\r\n")
            append("a=rtpmap:$payload MPEG4-GENERIC/$sampleRate/$channels\r\n")
            append("a=fmtp:$payload profile-level-id=1; mode=AAC-hbr; config=")
            append(java.lang.String.format("%04x", config))
            append("; sizelength=13; indexlength=3; indexdeltalength=3\r\n")
            append("a=control:trackID=$trackAudio\r\n")
        }
    }

    private fun createAV1Body(trackVideo: Int): String = buildString {
        val payload = Av1Packet.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload AV1/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    private fun createH264Body(trackVideo: Int, sps: String, pps: String): String = buildString {
        val payload = H264Packet.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload H264/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
        append("a=fmtp:$payload packetization-mode=1; sprop-parameter-sets=$sps,$pps\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    private fun createH265Body(trackVideo: Int, sps: String, pps: String, vps: String): String = buildString {
        val payload = H265Packet.PAYLOAD_TYPE + trackVideo
        append("m=video 0 RTP/AVP $payload\r\n")
        append("a=rtpmap:$payload H265/${BaseRtpPacket.VIDEO_CLOCK_FREQUENCY}\r\n")
        val parts = buildList {
            if (vps.isNotEmpty()) add("sprop-vps=$vps")
            if (sps.isNotEmpty()) add("sprop-sps=$sps")
            if (pps.isNotEmpty()) add("sprop-pps=$pps")
        }.joinToString("; ")
        if (parts.isNotEmpty()) append("a=fmtp:$payload $parts\r\n")
        append("a=control:trackID=$trackVideo\r\n")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.encodeBase64(): String = Base64.encode(this)

    private fun String.md5(): String = runCatching {
        MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun StringBuilder.addDefaultHeaders(contentLength: Int? = null) {
        append("CSeq: ${++cSeq}\r\n")
        authorization?.let { append("Authorization: $it\r\n") }
        append("User-Agent: ScreenStream/$appVersion\r\n")
        if (contentLength != null) append("Content-Length: $contentLength\r\n")
        if (sessionId.isNotBlank()) append("Session: $sessionId\r\n")
    }
}