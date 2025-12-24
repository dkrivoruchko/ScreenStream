package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.encodeBase64
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspMessage
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest

internal class RtspClientMessageHandler(
    appVersion: String, host: String, port: Int, path: String, private val username: String?, private val password: String?
) : RtspBaseMessageHandler(appVersion, host, port, path) {

    internal data class Command(val method: Method, val cSeq: Int, val status: Int, val text: String)

    private val lock = Mutex()
    private var authorization: String? = null
    private var sessionId: String = ""

    @Volatile
    private var sessionTimeoutSec: Int? = null
    private var cSeq: Int = 0
    private var authNc: Int = 0
    private var lastAuthNonce: String? = null

    internal fun hasSession(): Boolean = sessionId.isNotBlank()

    internal fun getSuggestedKeepAliveDelayMs(defaultMs: Long = 60_000): Long {
        val timeoutSec = sessionTimeoutSec
        if (timeoutSec == null || timeoutSec <= 0) return defaultMs
        val ms = (timeoutSec - 5).coerceAtLeast(5) * 1000L
        return minOf(ms, defaultMs)
    }

    internal suspend fun reset() = lock.withLock {
        authorization = null
        sdpSessionId = secureRandom.nextInt(Int.MAX_VALUE)
        cSeq = 0
        sessionId = ""
        sessionTimeoutSec = null
        authNc = 0
    }

    internal suspend fun createOptions(): RtspMessage = lock.withLock {
        RequestBuilder(Method.OPTIONS, baseUri)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createGetParameter(): RtspMessage = lock.withLock {
        RequestBuilder(Method.GET_PARAMETER, baseUri)
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createAnnounce(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): RtspMessage =
        lock.withLock {
            RequestBuilder(Method.ANNOUNCE, baseUri)
                .header(RtspHeaders.CONTENT_TYPE, "application/sdp")
                .withCSeq(++cSeq)
                .withUserAgent(userAgent)
                .withAuthorization(authorization)
                .withSession(sessionId.takeIf { it.isNotBlank() })
                .bodyAscii(SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId))
                .build()
        }

    internal suspend fun applyAuthFor(
        method: Method,
        uriPath: String,
        authResponse: String,
        videoParams: RtspClient.VideoParams? = null,
        audioParams: RtspClient.AudioParams? = null,
    ) = lock.withLock {
        val user = username.orEmpty()
        val pass = password.orEmpty()

        authorization = when (val challenge = parseDigestChallenge(authResponse)) {
            null -> {
                val base64 = "$user:$pass".toByteArray(Charsets.ISO_8859_1).encodeBase64()
                "Basic $base64"
            }

            else -> {
                val realm = challenge.realm
                val nonce = challenge.nonce
                val opaque = challenge.opaque.orEmpty()
                val algorithm = challenge.algorithm.orEmpty()
                if (lastAuthNonce != nonce) {
                    authNc = 0
                    lastAuthNonce = nonce
                }
                val selectedQop = challenge.qop.split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { tokens ->
                    val lower = tokens.map { it.lowercase() }
                    when {
                        "auth" in lower -> tokens[lower.indexOf("auth")]
                        tokens.isNotEmpty() -> tokens.first()
                        else -> null
                    }
                }

                val fullUri = if (uriPath.startsWith(path)) "$baseUri${uriPath.removePrefix(path)}" else "$baseUri$uriPath"
                val cnonce = ByteArray(8).also { secureRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }

                val baseHa1 = "$user:$realm:$pass".toByteArray(Charsets.ISO_8859_1).md5String()
                val ha1 = if (algorithm.equals("MD5-sess", ignoreCase = true)) {
                    "$baseHa1:$nonce:$cnonce".toByteArray(Charsets.ISO_8859_1).md5String()
                } else {
                    baseHa1
                }

                val selectedQopLower = selectedQop?.lowercase()
                val entityMd5 = when {
                    selectedQopLower == "auth-int" && method == Method.ANNOUNCE -> {
                        val sdp = if (videoParams != null) {
                            SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId)
                        } else {
                            ""
                        }
                        sdp.toByteArray(Charsets.US_ASCII).md5String()
                    }

                    selectedQopLower == "auth-int" -> ByteArray(0).md5String()
                    else -> null
                }

                val ha2 = if (entityMd5 != null) {
                    "$method:$fullUri:$entityMd5".toByteArray(Charsets.ISO_8859_1).md5String()
                } else {
                    "$method:$fullUri".toByteArray(Charsets.ISO_8859_1).md5String()
                }
                val ncHex = selectedQop?.let { (++authNc).toString(16).padStart(8, '0') }
                val response = if (selectedQop == null) {
                    "$ha1:$nonce:$ha2".toByteArray(Charsets.ISO_8859_1).md5String()
                } else {
                    "$ha1:$nonce:$ncHex:$cnonce:${selectedQop.lowercase()}:$ha2".toByteArray(Charsets.ISO_8859_1).md5String()
                }

                buildString {
                    append("Digest username=\"${quoteParam(user)}\", realm=\"${quoteParam(realm)}\", nonce=\"${quoteParam(nonce)}\", ")
                    append("uri=\"${quoteParam(fullUri)}\", response=\"$response\"")
                    selectedQop?.let { append(", qop=${it.lowercase()}") }
                    if (opaque.isNotEmpty()) append(", opaque=\"${quoteParam(opaque)}\"")
                    if (algorithm.isNotEmpty()) append(", algorithm=\"$algorithm\"")
                    if (selectedQop != null) append(", nc=$ncHex, cnonce=\"$cnonce\"")
                }
            }
        }
    }

    internal suspend fun createSetup(protocol: Protocol, clientRtpPort: Int, clientRtcpPort: Int, trackId: Int): RtspMessage =
        lock.withLock {
            val transport = when (protocol) {
                Protocol.TCP -> "RTP/AVP/TCP;unicast;interleaved=${trackId shl 1}-${(trackId shl 1) + 1};mode=record"
                Protocol.UDP -> "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;mode=record"
            }
            RequestBuilder(Method.SETUP, uri = trackUri(trackId))
                .header(RtspHeaders.TRANSPORT, transport)
                .withCSeq(++cSeq)
                .withUserAgent(userAgent)
                .withAuthorization(authorization)
                .withSession(sessionId.takeIf { it.isNotBlank() })
                .build()
        }

    internal suspend fun createRecord(): RtspMessage = lock.withLock {
        RequestBuilder(Method.RECORD, baseUri)
            .header(RtspHeaders.RANGE, "npt=0.000-")
            .withCSeq(++cSeq)
            .withUserAgent(userAgent)
            .withAuthorization(authorization)
            .withSession(sessionId.takeIf { it.isNotBlank() })
            .build()
    }

    internal suspend fun createTeardown(): RtspMessage = lock.withLock {
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
            extractSessionHeader(text)?.let { sid -> sessionId = sid }
            extractSessionTimeout(text)?.let { sessionTimeoutSec = it }

            return@withLock Command(method, extractCSeq(text), extractStatus(text), text)
        }

    internal fun getPorts(command: Command): Pair<RtspClient.Ports?, RtspClient.Ports?> {
        val transport = extractTransport(command.text).ifEmpty { return null to null }
        val parsed = TransportHeader.parse(transport) ?: return null to null
        val client = parsed.clientPorts?.let { (rtp, rtcp) -> RtspClient.Ports(rtp, rtcp) }
        val server = parsed.serverPorts?.let { (rtp, rtcp) -> RtspClient.Ports(rtp, rtcp) }
        return client to server
    }

    internal fun getInterleaved(command: Command): Pair<Int, Int>? {
        val transport = extractTransport(command.text).ifEmpty { return null }
        val parsed = TransportHeader.parse(transport) ?: return null
        return parsed.interleaved
    }

    private fun ByteArray.md5String(): String = runCatching {
        MessageDigest.getInstance("MD5").digest(this).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun quoteParam(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

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

        fun build(): RtspMessage {
            val header = buildString {
                append(method).append(' ').append(uri).append(' ').append(RTSP_VERSION).append(CRLF)
                body?.let { headers[RtspHeaders.CONTENT_LENGTH] = it.size.toString() }
                for ((k, v) in headers) append(k).append(": ").append(v).append(CRLF)
                append(CRLF)
            }
            return RtspMessage(header = header.toByteArray(Charsets.ISO_8859_1), body = body)
        }
    }
}
