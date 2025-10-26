package info.dvkr.screenstream.rtsp.internal.rtsp.core

import java.security.SecureRandom

internal abstract class RtspMessagesBase(
    protected val appVersion: String,
    protected val host: String,
    protected val port: Int,
    protected val path: String,
) {
    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    protected val baseUri: String
        get() = "rtsp://$host:$port$path"

    protected val userAgent: String
        get() = "ScreenStream/$appVersion"

    protected fun trackUri(trackId: Int): String = "$baseUri/trackID=$trackId"

    protected val rng = SecureRandom()
    protected var sdpSessionId: Int = rng.nextInt(Int.MAX_VALUE)

    protected companion object {
        protected const val RTSP_VERSION: String = "RTSP/1.0"
        protected const val CRLF: String = "\r\n"

        @JvmStatic
        protected val CSEQ_REGEX = Regex("""CSeq\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val METHOD_REGEX = Regex("""(\w+) (\S+) RTSP""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val TRANSPORT_REGEX = Regex("""Transport\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val SESSION_HEADER_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val CONTENT_LENGTH_REGEX = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val STATUS_REGEX = Regex("""RTSP/\d.\d\s+(\d+)\s+(.+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val SESSION_ID_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val SESSION_TIMEOUT_REGEX = Regex("""Session\s*:\s*[^\r\n;]+;[^\r\n]*timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val DIGEST_AUTH_REGEX = Regex("""realm="([^"]+)",\s*nonce="([^"]+)"(.*)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val QOP_REGEX = Regex("""qop="([^"]+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val OPAQUE_REGEX = Regex("""opaque="([^"]+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        protected val ALGORITHM_REGEX = Regex("""algorithm="?([^," ]+)"?""", RegexOption.IGNORE_CASE)
    }

    protected object RtspHeaders {
        const val CSEQ = "CSeq"
        const val SESSION = "Session"
        const val TRANSPORT = "Transport"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_LENGTH = "Content-Length"
        const val USER_AGENT = "User-Agent"
        const val RTP_INFO = "RTP-Info"
        const val PUBLIC = "Public"
        const val RANGE = "Range"
        const val AUTHORIZATION = "Authorization"
        const val CONTENT_BASE = "Content-Base"
        const val CACHE_CONTROL = "Cache-Control"
        const val RETRY_AFTER = "Retry-After"
    }

    protected fun extractCSeq(text: String): Int =
        CSEQ_REGEX.find(text)?.groups?.get(1)?.value?.toIntOrNull() ?: -1

    protected fun extractTransport(text: String): String =
        TRANSPORT_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()

    protected fun extractSessionHeader(text: String): String? =
        SESSION_HEADER_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    protected fun extractMethodToken(text: String): String? =
        METHOD_REGEX.find(text)?.groups?.get(1)?.value

    protected fun extractContentLength(text: String): Int =
        CONTENT_LENGTH_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    protected fun extractStatus(text: String): Int =
        STATUS_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    protected fun parseMethod(text: String): Method {
        val token = extractMethodToken(text)?.uppercase().orEmpty()
        return runCatching { Method.valueOf(token) }.getOrDefault(Method.UNKNOWN)
    }
}
