package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.rtsp.RtspMessage
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

internal class RtspServerMessages(
    appVersion: String, host: String, port: Int, path: String
) : RtspMessagesBase(appVersion, host, port, path) {

    private companion object {
        private const val DEFAULT_SESSION_TIMEOUT_SEC = 60
        private const val ALLOWED_METHODS = "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"
    }

    internal data class PlayTrackInfo(val seq: Int, val rtpTime: Long)

    internal fun parseRequest(request: String): Pair<Method, Int> = parseMethod(request) to extractCSeq(request)

    internal fun getTransport(request: String): String = extractTransport(request)

    internal fun getSessionFromRequest(request: String): String? = extractSessionHeader(request)

    internal fun parseClientPorts(transport: String): Pair<Int, Int>? = TransportHeader.parse(transport)?.clientPorts

    internal fun createOptionsResponse(cSeq: Int): RtspMessage =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .header(RtspHeaders.PUBLIC, ALLOWED_METHODS)
            .withUserAgent(userAgent)
            .build()

    internal fun createDescribeResponse(
        cSeq: Int,
        videoParams: RtspClient.VideoParams,
        audioParams: RtspClient.AudioParams?
    ): RtspMessage =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .header(RtspHeaders.CONTENT_BASE, "$baseUri/")
            .header(RtspHeaders.CONTENT_TYPE, "application/sdp")
            .withUserAgent(userAgent)
            .bodyAscii(SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId))
            .build()

    internal fun createSetupResponse(
        cSeq: Int,
        transport: String,
        serverRtpPort: Int,
        serverRtcpPort: Int,
        sessionId: String
    ): RtspMessage {
        val parsed = TransportHeader.parse(transport)
        val value = if (parsed?.clientPorts != null) parsed.withServerPorts(serverRtpPort, serverRtcpPort).toString() else transport
        return ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId, DEFAULT_SESSION_TIMEOUT_SEC)
            .header(RtspHeaders.TRANSPORT, value)
            .withUserAgent(userAgent)
            .build()
    }

    internal fun createPlayResponse(cSeq: Int, sessionId: String, trackInfo: Map<Int, PlayTrackInfo>): RtspMessage {
        val rtpInfo = trackInfo.toSortedMap().entries.joinToString(",") { (tid, info) ->
            "url=${trackUri(tid)};seq=${max(0, info.seq)};rtptime=${info.rtpTime}"
        }
        return ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .header(RtspHeaders.RANGE, "npt=0.000-")
            .header(RtspHeaders.RTP_INFO, rtpInfo)
            .withUserAgent(userAgent)
            .build()
    }

    internal fun createPauseResponse(cSeq: Int, sessionId: String): RtspMessage =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .withUserAgent(userAgent)
            .build()

    internal fun createTeardownResponse(cSeq: Int, sessionId: String): RtspMessage =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .withSession(sessionId)
            .withUserAgent(userAgent)
            .build()

    internal fun createGetParameterResponse(cSeq: Int, sessionId: String): RtspMessage =
        ResponseBuilder.ok()
            .withCSeq(cSeq)
            .apply { if (sessionId.isNotBlank()) withSession(sessionId) }
            .withUserAgent(userAgent)
            .build()

    internal fun createErrorResponse(code: Int, cSeq: Int = -1): RtspMessage {
        val status = when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            415 -> "Unsupported Media Type"
            454 -> "Session Not Found"
            461 -> "Unsupported Transport"
            500 -> "Internal Server Error"
            else -> "Unknown Error"
        }
        return ResponseBuilder.error(code, status)
            .withCSeq(cSeq)
            .apply {
                if (code == 405) {
                    header("Allow", ALLOWED_METHODS)
                    header(RtspHeaders.PUBLIC, ALLOWED_METHODS)
                }
            }
            .withUserAgent(userAgent)
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

        fun withCSeq(cSeq: Int): ResponseBuilder = apply { if (cSeq >= 0) header(RtspHeaders.CSEQ, cSeq.toString()) }

        fun withSession(session: String, timeoutSec: Int? = null): ResponseBuilder {
            val sessionHeader = if (timeoutSec != null && timeoutSec > 0) "$session;timeout=$timeoutSec" else session
            return header(RtspHeaders.SESSION, sessionHeader)
        }

        fun withUserAgent(userAgent: String): ResponseBuilder = header("Server", userAgent)

        fun build(): RtspMessage {
            if (!headers.containsKey("Date")) headers["Date"] = rfc1123Date()
            if (!headers.containsKey(RtspHeaders.CACHE_CONTROL)) headers[RtspHeaders.CACHE_CONTROL] = "no-cache"

            val header = buildString {
                append(RTSP_VERSION).append(' ').append(statusCode).append(' ').append(statusText).append(CRLF)
                body?.let { headers[RtspHeaders.CONTENT_LENGTH] = it.size.toString() }
                for ((k, v) in headers) append(k).append(": ").append(v).append(CRLF)
                append(CRLF)
            }
            return RtspMessage(header = header.toByteArray(Charsets.ISO_8859_1), body = body)
        }

        companion object {
            fun ok(): ResponseBuilder = ResponseBuilder(200, "OK")
            fun error(code: Int, text: String): ResponseBuilder = ResponseBuilder(code, text)

            private fun rfc1123Date(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .format(Date())
        }
    }
}
