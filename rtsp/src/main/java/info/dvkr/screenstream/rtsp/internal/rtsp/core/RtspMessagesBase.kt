package info.dvkr.screenstream.rtsp.internal.rtsp.core

internal abstract class RtspMessagesBase(
    protected val appVersion: String,
    protected val host: String,
    protected val port: Int,
    protected val path: String,
) {
    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    protected val baseUri: String
        get() = "rtsp://$host:$port$path"

    protected companion object {
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
