package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import kotlin.math.max

internal class RtspServerMessages(
    appVersion: String, host: String, port: Int, path: String
) : RtspMessagesBase(appVersion, host, port, path) {

    internal data class PlayTrackInfo(val seq: Int, val rtptime: Long)

    internal fun parseRequest(request: String): Pair<Method, Int> = parseMethod(request) to extractCSeq(request)

    internal fun getTransport(request: String): String = extractTransport(request)

    internal fun getSessionFromRequest(request: String): String? = extractSessionHeader(request)

    internal fun parseClientPorts(transport: String): Pair<Int, Int>? = TransportHeader.parse(transport)?.clientPorts

    internal fun createOptionsResponse(cSeq: Int): String =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .header(RtspHeaders.PUBLIC, "DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER")
            .build()

    internal fun createDescribeResponse(cSeq: Int, videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .header(RtspHeaders.CONTENT_BASE, "$baseUri/")
            .header(RtspHeaders.CONTENT_TYPE, "application/sdp")
            .bodyAscii(SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId))
            .build()

    internal fun createSetupResponse(cSeq: Int, transport: String, serverRtpPort: Int, serverRtcpPort: Int, sessionId: String): String {
        val parsed = TransportHeader.parse(transport)
        val value = if (parsed?.clientPorts != null) parsed.withServerPorts(serverRtpPort, serverRtcpPort).toString() else transport
        return ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .header(RtspHeaders.TRANSPORT, value)
            .header(RtspHeaders.CACHE_CONTROL, "no-cache")
            .build()
    }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, trackInfo: Map<Int, PlayTrackInfo>): String {
        val rtpInfo = trackInfo.toSortedMap().entries.joinToString(",") { (tid, info) ->
            "url=${trackUri(tid)};seq=${max(0, info.seq)};rtptime=${info.rtptime}"
        }
        return ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .header(RtspHeaders.RANGE, "npt=0.000-")
            .header(RtspHeaders.RTP_INFO, rtpInfo)
            .build()
    }

    internal fun createPauseResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .build()

    internal fun createTeardownResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .build()

    internal fun createGetParameterResponse(cSeq: Int, sessionId: String): String =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .apply { if (sessionId.isNotBlank()) withSession(sessionId) }
            .build()

    internal fun createServiceUnavailableResponse(cSeq: Int, retryAfterSeconds: Int = 2): String =
        ResponseBuilder.error(503, "Service Unavailable")
            .withCSeq(cSeq)
            .header(RtspHeaders.RETRY_AFTER, retryAfterSeconds.toString())
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
            .withCSeq(cSeq)
            .build()
    }

    private class ResponseBuilder(private val statusCode: Int, private val statusText: String) {
        private val headers: LinkedHashMap<String, String> = LinkedHashMap()
        private var body: ByteArray? = null

        fun header(name: String, value: String): ResponseBuilder {
            headers[name] = value
            return this
        }

        fun bodyAscii(text: String): ResponseBuilder {
            body = text.toByteArray(Charsets.US_ASCII)
            return this
        }

        fun withCSeq(cSeq: Int): ResponseBuilder = header(RtspHeaders.CSEQ, cSeq.toString())

        fun withSession(session: String): ResponseBuilder = header(RtspHeaders.SESSION, session)

        fun build(): String {
            val sb = StringBuilder()
            sb.append(RTSP_VERSION).append(' ').append(statusCode).append(' ').append(statusText).append(CRLF)
            val bodyBytes = body
            if (bodyBytes != null) headers[RtspHeaders.CONTENT_LENGTH] = bodyBytes.size.toString()
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append(CRLF)
            sb.append(CRLF)
            if (bodyBytes != null) sb.append(bodyBytes.toString(Charsets.US_ASCII))
            return sb.toString()
        }

        companion object {
            fun ok(): ResponseBuilder = ResponseBuilder(200, "OK")
            fun error(code: Int, text: String): ResponseBuilder = ResponseBuilder(code, text)
        }
    }
}
