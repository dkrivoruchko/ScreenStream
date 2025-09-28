package info.dvkr.screenstream.rtsp.internal.rtsp

import info.dvkr.screenstream.rtsp.internal.rtsp.sdp.SdpBuilder
import kotlin.math.max

internal class RtspServerMessages(appVersion: String, host: String, port: Int, path: String) :
    RtspMessagesBase(appVersion, host, port, path) {

    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    internal data class PlayTrackInfo(val seq: Int, val rtptime: Long)

    private var sdpSessionId: Int = java.security.SecureRandom().nextInt(Int.MAX_VALUE)

    internal fun parseRequest(request: String): Pair<Method, Int> {
        val token = extractMethodToken(request)?.uppercase().orEmpty()
        val method = runCatching { Method.valueOf(token) }
            .getOrDefault(Method.UNKNOWN)
        val cseq = extractCSeq(request)
        return method to cseq
    }

    internal fun getTransport(request: String): String =
        extractTransport(request)

    internal fun getSessionFromRequest(request: String): String? =
        extractSessionHeader(request)

    internal fun parseClientPorts(transport: String): Pair<Int, Int>? =
        CLIENT_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) ->
            rtp.toIntOrNull() to rtcp.toIntOrNull()
        }?.takeIf { it.first != null && it.second != null }?.let { it.first!! to it.second!! }

    internal fun createOptionsResponse(cSeq: Int): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER\r\n")
        append("\r\n")
    }

    internal fun createDescribeResponse(cSeq: Int, videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String {
        val sdp = SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId)
        val len = sdp.toByteArray(Charsets.US_ASCII).size
        return buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("CSeq: $cSeq\r\n")
            append("Content-Base: rtsp://$host:$port$path/\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: $len\r\n")
            append("\r\n")
            append(sdp)
        }
    }

    internal fun createSetupResponse(cSeq: Int, transport: String, serverRtpPort: Int, serverRtcpPort: Int, sessionId: String): String =
        buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("CSeq: $cSeq\r\n")
            val newTransport = if (transport.contains("client_port")) "$transport;server_port=$serverRtpPort-$serverRtcpPort" else transport
            append("Transport: $newTransport\r\n")
            append("Session: $sessionId\r\n")
            append("Cache-Control: no-cache\r\n")
            append("\r\n")
        }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, trackInfo: Map<Int, PlayTrackInfo>): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        append("Range: npt=0.000-\r\n")
        val rtpInfo = trackInfo.toSortedMap().entries.joinToString(",") { (tid, info) ->
            "url=rtsp://$host:$port$path/trackID=$tid;seq=${max(0, info.seq)};rtptime=${info.rtptime}"
        }
        append("RTP-Info: ").append(rtpInfo).append("\r\n")
        append("\r\n")
    }

    internal fun createPauseResponse(cSeq: Int, sessionId: String): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createTeardownResponse(cSeq: Int, sessionId: String): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createGetParameterResponse(cSeq: Int, sessionId: String): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cSeq\r\n")
        if (sessionId.isNotBlank()) append("Session: $sessionId\r\n")
        append("\r\n")
    }

    internal fun createServiceUnavailableResponse(cSeq: Int, retryAfterSeconds: Int = 2): String = buildString {
        append("RTSP/1.0 503 Service Unavailable\r\n")
        append("CSeq: $cSeq\r\n")
        append("Retry-After: $retryAfterSeconds\r\n")
        append("\r\n")
    }

    internal fun createErrorResponse(code: Int, cSeq: Int): String = buildString {
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
}
