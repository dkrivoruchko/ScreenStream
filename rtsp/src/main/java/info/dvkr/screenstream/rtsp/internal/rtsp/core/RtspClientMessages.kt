package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class RtspClientMessages(
    appVersion: String, host: String, port: Int, path: String, private val username: String?, private val password: String?
) : RtspMessagesBase(appVersion, host, port, path) {

    internal data class Command(val method: Method, val cSeq: Int, val status: Int, val text: String)

    private val lock = Mutex()
    private var authorization: String? = null
    private var sessionId: String = ""

    @Volatile
    private var sessionTimeoutSec: Int? = null
    private var cSeq: Int = 0
    private var authNc: Int = 0

    internal fun hasSession(): Boolean = sessionId.isNotBlank()

    internal fun getSuggestedKeepAliveDelayMs(defaultMs: Long = 60_000): Long {
        val timeoutSec = sessionTimeoutSec
        if (timeoutSec == null || timeoutSec <= 0) return defaultMs
        val ms = (timeoutSec - 5).coerceAtLeast(1) * 1000L
        return minOf(ms, defaultMs)
    }

    internal suspend fun reset() = lock.withLock {
        authorization = null
        sdpSessionId = rng.nextInt(Int.MAX_VALUE)
        cSeq = 0
        sessionId = ""
        sessionTimeoutSec = null
        authNc = 0
    }

    internal suspend fun createOptions(): String = lock.withLock {
        RequestBuilder(Method.OPTIONS, baseUri)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createGetParameter(): String = lock.withLock {
        RequestBuilder(Method.GET_PARAMETER, baseUri)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createAnnounce(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String = lock.withLock {
        RequestBuilder(Method.ANNOUNCE, baseUri)
            .header(RtspHeaders.CONTENT_TYPE, "application/sdp")
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .bodyAscii(SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId))
            .build()
    }

    internal suspend fun applyAuthFor(method: Method, uriPath: String, authResponse: String) = lock.withLock {
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
                val qop = QOP_REGEX.find(authResponse)?.groupValues?.getOrNull(1).orEmpty()
                val opaque = OPAQUE_REGEX.find(authResponse)?.groupValues?.getOrNull(1).orEmpty()
                val algorithm = ALGORITHM_REGEX.find(authResponse)?.groupValues?.getOrNull(1).orEmpty()
                val ha1 = "$user:$realm:$pass".md5()
                val fullUri = if (uriPath.startsWith(path)) "$baseUri${uriPath.removePrefix(path)}" else "$baseUri$uriPath"
                val ha2 = "$method:$fullUri".md5()
                val cnonce = rng.nextInt().toString(16)
                val ncHex = if (qop.isNotEmpty()) (++authNc).toString(16).padStart(8, '0') else null
                val response = if (qop.isEmpty()) {
                    "$ha1:$nonce:$ha2".md5()
                } else {
                    "$ha1:$nonce:$ncHex:$cnonce:$qop:$ha2".md5()
                }
                buildString {
                    append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", ")
                    append("uri=\"$fullUri\", response=\"$response\"")
                    if (qop.isNotEmpty()) append(", qop=\"$qop\"")
                    if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
                    append(", algorithm=\"$algorithm\"")
                    if (qop.isNotEmpty()) append(", nc=$ncHex, cnonce=\"$cnonce\"")
                }
            }
        }
    }

    internal suspend fun createSetup(protocol: Protocol, clientRtpPort: Int, clientRtcpPort: Int, trackId: Int): String = lock.withLock {
        val transport = when (protocol) {
            Protocol.TCP -> "RTP/AVP/TCP;unicast;interleaved=${trackId shl 1}-${(trackId shl 1) + 1}"
            Protocol.UDP -> "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort"
        }
        RequestBuilder(Method.SETUP, uri = trackUri(trackId))
            .header(RtspHeaders.TRANSPORT, transport)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createRecord(): String = lock.withLock {
        RequestBuilder(Method.RECORD, baseUri)
            .header(RtspHeaders.RANGE, "npt=0.000-")
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createTeardown(): String = lock.withLock {
        RequestBuilder(Method.TEARDOWN, baseUri)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    @Throws(IOException::class)
    internal suspend fun getResponseWithTimeout(
        readLine: suspend () -> String?, readBytes: suspend (ByteArray, Int) -> Unit, method: Method, timeoutMs: Long = 15_000
    ): Command = withTimeout(timeoutMs) { getResponse(readLine, readBytes, method) }

    private suspend fun getResponse(readLine: suspend () -> String?, readBytes: suspend (ByteArray, Int) -> Unit, method: Method): Command =
        lock.withLock {
            val headerLines = mutableListOf<String>()
            var contentLength = 0
            while (true) {
                val line = readLine()?.ifBlank { null } ?: break
                headerLines.add(line)
                contentLength = extractContentLength(line).takeIf { it > 0 } ?: contentLength
            }
            val body = if (contentLength <= 0) "" else ByteArray(contentLength).also { readBytes(it, contentLength) }.decodeToString()

            val text = buildString {
                headerLines.forEach { append(it).append("\r\n") }
                append("\r\n")
                append(body)
            }
            SESSION_ID_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { sid -> if (sid.isNotEmpty()) sessionId = sid }
            SESSION_TIMEOUT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { t -> if (t > 0) sessionTimeoutSec = t }

            return@withLock Command(method, extractCSeq(text), extractStatus(text), text)
        }

    internal fun getPorts(command: Command): Pair<RtspClient.Ports?, RtspClient.Ports?> {
        val transport = extractTransport(command.text).ifEmpty { return null to null }
        val parsed = TransportHeader.parse(transport) ?: return null to null
        val client = parsed.clientPorts?.let { (rtp, rtcp) -> RtspClient.Ports(rtp, rtcp) }
        val server = parsed.serverPorts?.let { (rtp, rtcp) -> RtspClient.Ports(rtp, rtcp) }
        return client to server
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.encodeBase64(): String = Base64.encode(this)

    private fun String.md5(): String = runCatching {
        MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private class RequestBuilder(private val method: Method, private val uri: String) {
        private val headers: LinkedHashMap<String, String> = LinkedHashMap()
        private var body: ByteArray? = null

        fun header(name: String, value: String): RequestBuilder {
            headers[name] = value
            return this
        }

        fun bodyAscii(text: String): RequestBuilder {
            body = text.toByteArray(Charsets.US_ASCII)
            return this
        }

        fun withCSeq(cSeq: Int): RequestBuilder = header(RtspHeaders.CSEQ, cSeq.toString())

        fun withUserAgent(userAgent: String): RequestBuilder = header(RtspHeaders.USER_AGENT, userAgent)

        fun withAuthorization(authorization: String?): RequestBuilder = apply {
            authorization?.let { header(RtspHeaders.AUTHORIZATION, it) }
        }

        fun withSession(sessionId: String?): RequestBuilder = apply {
            sessionId?.takeIf { it.isNotBlank() }?.let { header(RtspHeaders.SESSION, it) }
        }

        fun build(): String {
            val sb = StringBuilder()
            sb.append(method).append(' ').append(uri).append(' ').append(RTSP_VERSION).append(CRLF)
            val bodyBytes = body
            if (bodyBytes != null) headers[RtspHeaders.CONTENT_LENGTH] = bodyBytes.size.toString()
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append(CRLF)
            sb.append(CRLF)
            if (bodyBytes != null) sb.append(bodyBytes.toString(Charsets.US_ASCII))
            return sb.toString()
        }
    }
}
