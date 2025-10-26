package info.dvkr.screenstream.rtsp.internal.rtsp.core

import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.core.SdpBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class RtspClientMessages(
    appVersion: String,
    host: String,
    port: Int,
    path: String,
    private val username: String?,
    private val password: String?
) : RtspMessagesBase(appVersion, host, port, path) {

    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }
    internal data class Command(val method: Method, val cSeq: Int, val status: Int, val text: String)

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
        buildString {
            append("OPTIONS rtsp://$host:$port$path RTSP/1.0\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createGetParameter(): String = lock.withLock {
        buildString {
            append("GET_PARAMETER rtsp://$host:$port$path RTSP/1.0\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createAnnounce(videoParams: RtspClient.VideoParams, audioParams: RtspClient.AudioParams?): String =
        lock.withLock {
            buildString {
                val body = SdpBuilder().createSdpBody(videoParams, audioParams, sdpSessionId)
                append("ANNOUNCE rtsp://$host:$port$path RTSP/1.0\r\n")
                append("Content-Type: application/sdp\r\n")
                addDefaultHeaders(contentLength = body.toByteArray(Charsets.US_ASCII).size)
                append("\r\n")
                append(body)
            }
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
                val ha2 = "$method:rtsp://$host:$port$uriPath".md5()
                val cnonce = SecureRandom().nextInt().toString(16)
                val response = if (qop.isEmpty()) "$ha1:$nonce:$ha2".md5() else "$ha1:$nonce:00000001:$cnonce:$qop:$ha2".md5()
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
        buildString {
            append("RECORD rtsp://$host:$port$path RTSP/1.0\r\n")
            append("Range: npt=0.000-\r\n")
            addDefaultHeaders()
            append("\r\n")
        }
    }

    internal suspend fun createTeardown(): String = lock.withLock {
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

    private suspend fun getResponse(
        readLine: suspend () -> String?,
        readBytes: suspend (ByteArray, Int) -> Unit,
        method: Method
    ): Command = lock.withLock {
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
        val client = CLIENT_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) -> RtspClient.Ports(rtp.toInt(), rtcp.toInt()) }
        val server = SERVER_PORT_REGEX.find(transport)?.destructured?.let { (rtp, rtcp) -> RtspClient.Ports(rtp.toInt(), rtcp.toInt()) }
        return client to server
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

    private companion object {
        private val DIGEST_AUTH_REGEX = Regex("""realm="([^"]+)",\s*nonce="([^"]+)"(.*)""", RegexOption.IGNORE_CASE)
        private val QOP_REGEX = Regex("""qop="([^"]+)""", RegexOption.IGNORE_CASE)
        private val OPAQUE_REGEX = Regex("""opaque="([^"]+)""", RegexOption.IGNORE_CASE)
        private val ALGORITHM_REGEX = Regex("""algorithm="?([^," ]+)"?""", RegexOption.IGNORE_CASE)
    }
}
