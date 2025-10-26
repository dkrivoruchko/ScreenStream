package info.dvkr.screenstream.rtsp.internal.rtsp.core

import java.security.SecureRandom

internal abstract class RtspMessagesBase(
    protected val appVersion: String,
    protected val host: String,
    protected val port: Int,
    path: String,
) {
    internal enum class Method { OPTIONS, ANNOUNCE, RECORD, SETUP, TEARDOWN, DESCRIBE, PLAY, PAUSE, GET_PARAMETER, UNKNOWN }

    protected data class DigestChallenge(val realm: String, val nonce: String, val qop: String, val opaque: String?, val algorithm: String?)

    protected val path = "/" + path.trimStart('/')

    protected val baseUri: String
        get() = "rtsp://${formatHostForRtspAuthority(host)}:$port$path"

    protected val userAgent: String
        get() = "ScreenStream/$appVersion"

    protected fun trackUri(trackId: Int): String = "$baseUri/trackID=$trackId"

    private fun formatHostForRtspAuthority(host: String): String {
        if (host.isEmpty()) return host
        val alreadyBracketed = host.first() == '[' && host.last() == ']'
        val isIpv6Literal = ':' in host
        return when {
            alreadyBracketed -> host
            isIpv6Literal -> "[${host}]"
            else -> host
        }
    }

    protected val secureRandom = SecureRandom()
    protected var sdpSessionId: Int = secureRandom.nextInt(Int.MAX_VALUE)

    protected companion object {
        protected const val RTSP_VERSION: String = "RTSP/1.0"
        protected const val CRLF: String = "\r\n"

        @JvmStatic
        private val CSEQ_REGEX = Regex("""CSeq\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val METHOD_REGEX = Regex("""^([A-Za-z]+)\s+(\S+)\s+RTSP/\d+\.\d+""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val TRANSPORT_REGEX = Regex("""Transport\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val SESSION_HEADER_REGEX = Regex("""Session\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val CONTENT_LENGTH_REGEX = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val STATUS_REGEX = Regex("""RTSP/\d\.\d\s+(\d+)\s+(.+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val AUTH_PARAM_REGEX = Regex("""([A-Za-z]+)\s*=\s*(?:\"([^\"]*)\"|([^,\s]+))""", RegexOption.IGNORE_CASE)

        @JvmStatic
        private val SESSION_TIMEOUT_REGEX = Regex("""Session\s*:\s*[^\r\n;]+;[^\r\n]*timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
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

    protected fun extractSessionTimeout(text: String): Int? =
        SESSION_TIMEOUT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

    protected fun parseDigestChallenge(text: String): DigestChallenge? {
        val headerLine = text.split("\r\n")
            .firstOrNull { it.startsWith("WWW-Authenticate", ignoreCase = true) }
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.trim()

        val raw = when {
            headerLine?.contains("Digest", ignoreCase = true) == true -> headerLine
            else -> {
                val idx = text.indexOf("Digest", ignoreCase = true)
                if (idx >= 0) text.substring(idx + 6).substringBefore("\r\n").trim() else null
            }
        } ?: return null

        val paramsPart = raw.substringAfter("Digest", missingDelimiterValue = "").trim().trimStart(',').trim()
        val params = mutableMapOf<String, String>()
        for (matchResult in AUTH_PARAM_REGEX.findAll(paramsPart)) {
            val key = matchResult.groupValues[1].lowercase()
            val value = when {
                matchResult.groupValues.size >= 3 && matchResult.groupValues[2].isNotEmpty() -> matchResult.groupValues[2]
                matchResult.groupValues.size >= 4 && matchResult.groupValues[3].isNotEmpty() -> matchResult.groupValues[3]
                else -> ""
            }
            params[key] = value
        }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"].orEmpty()
        val opaque = params["opaque"]
        val algorithm = params["algorithm"]
        return DigestChallenge(realm, nonce, qop, opaque, algorithm)
    }

    protected fun parseMethod(text: String): Method {
        val token = extractMethodToken(text)?.uppercase().orEmpty()
        return runCatching { Method.valueOf(token) }.getOrDefault(Method.UNKNOWN)
    }
}
