package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.core.SdpBuilder
import java.security.SecureRandom
import kotlin.math.max

internal class RtspServerMessages(appVersion: String, host: String, port: Int, path: String) :
    RtspMessagesBase(appVersion, host, port, path) {

    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    internal data class PlayTrackInfo(val seq: Int, val rtptime: Long)

    private var sdpSessionId: Int = SecureRandom().nextInt(Int.MAX_VALUE)

    internal fun parseRequest(request: String): Pair<Method, Int> {
        val token = extractMethodToken(request)?.uppercase().orEmpty()
        val method = runCatching { Method.valueOf(token) }.getOrDefault(Method.UNKNOWN)
        val cseq = extractCSeq(request)
        return method to cseq
    }

    internal fun getTransport(request: String): String =
        extractTransport(request)

    internal fun getSessionFromRequest(request: String): String? =
        extractSessionHeader(request)

    internal fun parseClientPorts(transport: String): Pair<Int, Int>? =
        TransportHeader.parse(transport)?.clientPorts

    internal fun createOptionsResponse(cSeq: Int): String =
        ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Public", "DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER")
            .build()

    internal fun createDescribeResponse(cSeq: Int, videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String {
        val sdp = SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId)
        return ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Content-Base", "rtsp://$host:$port$path/")
            .header("Content-Type", "application/sdp")
            .bodyAscii(sdp)
            .build()
    }

    internal fun createSetupResponse(cSeq: Int, transport: String, serverRtpPort: Int, serverRtcpPort: Int, sessionId: String): String {
        val parsed = TransportHeader.parse(transport)
        val value = if (parsed?.clientPorts != null) parsed.withServerPorts(serverRtpPort, serverRtcpPort).toString() else transport
        return ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Transport", value)
            .header("Session", sessionId)
            .header("Cache-Control", "no-cache")
            .build()
    }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, trackInfo: Map<Int, PlayTrackInfo>): String {
        val rtpInfo = trackInfo.toSortedMap().entries.joinToString(",") { (tid, info) ->
            "url=rtsp://$host:$port$path/trackID=$tid;seq=${max(0, info.seq)};rtptime=${info.rtptime}"
        }
        return ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Session", sessionId)
            .header("Range", "npt=0.000-")
            .header("RTP-Info", rtpInfo)
            .build()
    }

    internal fun createPauseResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Session", sessionId)
            .build()

    internal fun createTeardownResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .header("Session", sessionId)
            .build()

    internal fun createGetParameterResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .header("CSeq", cSeq.toString())
            .apply { if (sessionId.isNotBlank()) header("Session", sessionId) }
            .build()

    internal fun createServiceUnavailableResponse(cSeq: Int, retryAfterSeconds: Int = 2): String =
        ResponseBuilder.error(503, "Service Unavailable")
            .header("CSeq", cSeq.toString())
            .header("Retry-After", retryAfterSeconds.toString())
            .build()

    internal fun createErrorResponse(code: Int, cSeq: Int): String {
        val status = when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            454 -> "Session Not Found"
            461 -> "Unsupported Transport"
            500 -> "Internal Server Error"
            else -> "Unknown Error"
        }
        return ResponseBuilder.error(code, status)
            .header("CSeq", cSeq.toString())
            .build()
    }
}
