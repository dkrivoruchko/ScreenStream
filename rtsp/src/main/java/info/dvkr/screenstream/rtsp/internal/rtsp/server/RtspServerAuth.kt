package info.dvkr.screenstream.rtsp.internal.rtsp.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.math.abs

internal data class RtspServerCredentials(val username: String, val password: String) {
    override fun toString(): String = "RtspServerCredentials(username=$username, password=***)"
}

/**
 * HTTP/RTSP Digest authentication (RFC 2617 / RFC 7616) shared by the RTSP server and the ONVIF device service.
 *
 * - Challenges use MD5 (universally supported by VLC/live555, ffmpeg, GStreamer); SHA-256 and `-sess` variants are accepted
 *   when the client chooses them.
 * - Both `qop=auth` and legacy RFC 2069 (no qop, used by live555/VLC) responses are accepted.
 * - Nonces are 128-bit [SecureRandom] values that expire after [NONCE_IDLE_TIMEOUT_MS] without use
 *   (or [NONCE_MAX_AGE_MS] in total); a correct response with an expired/unknown nonce gets `stale=TRUE`.
 * - Per remote IP brute-force throttle: [MAX_FAILURES] failed attempts within [FAILURE_WINDOW_MS] block that IP for [BLOCK_MS].
 *
 * [credentialsProvider] returns `null` when authentication is disabled.
 */
internal class RtspServerAuth(private val credentialsProvider: () -> RtspServerCredentials?) {

    internal sealed interface Decision {
        data object Allowed : Decision
        data class Challenge(val wwwAuthenticate: String, val failedAttempt: Boolean) : Decision
        data object Blocked : Decision
    }

    private data class NonceState(val createdMs: Long, @Volatile var lastUsedMs: Long)

    private class FailureState(var blockedUntilMs: Long = 0L, val failures: ArrayDeque<Long> = ArrayDeque())

    private val secureRandom = SecureRandom()
    private val nonces = ConcurrentHashMap<String, NonceState>()
    private val failures = HashMap<String, FailureState>()
    private val wsNonces = ConcurrentHashMap<String, Long>()

    internal fun isEnabled(): Boolean = credentialsProvider() != null

    internal fun isBlocked(remoteIp: String): Boolean = synchronized(failures) {
        val state = failures[remoteIp]
        state != null && state.blockedUntilMs > nowMs()
    }

    /**
     * @param method request method (e.g. "DESCRIBE", "POST")
     * @param requestUri request-URI from the request line
     * @param authorization value of the `Authorization` header, if any
     */
    internal fun authorize(method: String, requestUri: String, authorization: String?, remoteIp: String): Decision {
        val credentials = credentialsProvider() ?: return Decision.Allowed
        if (isBlocked(remoteIp)) return Decision.Blocked

        if (authorization.isNullOrBlank()) return Decision.Challenge(createChallenge(stale = false), failedAttempt = false)

        return when (verify(method, requestUri, authorization, credentials)) {
            VerifyResult.OK -> Decision.Allowed
            VerifyResult.STALE -> Decision.Challenge(createChallenge(stale = true), failedAttempt = false)
            VerifyResult.INVALID -> {
                if (onFailure(remoteIp)) Decision.Blocked
                else Decision.Challenge(createChallenge(stale = false), failedAttempt = true)
            }
        }
    }

    /**
     * Verifies an ONVIF WS-Security UsernameToken (PasswordDigest) from a SOAP request body.
     * Returns `null` when the body contains no UsernameToken.
     */
    internal fun verifyWsUsernameToken(soapBody: String, remoteIp: String): Boolean? {
        val credentials = credentialsProvider() ?: return true
        if (!WS_USERNAME_TOKEN_REGEX.containsMatchIn(soapBody)) return null
        if (isBlocked(remoteIp)) return false

        val ok = runCatching {
            val username = WS_USERNAME_REGEX.find(soapBody)?.groupValues?.get(1)?.trim().orEmpty()
            val passwordMatch = WS_PASSWORD_REGEX.find(soapBody) ?: return@runCatching false
            val passwordAttrs = passwordMatch.groupValues[1]
            val password = passwordMatch.groupValues[2].trim()
            val nonceB64 = WS_NONCE_REGEX.find(soapBody)?.groupValues?.get(1)?.trim().orEmpty()
            val created = WS_CREATED_REGEX.find(soapBody)?.groupValues?.get(1)?.trim().orEmpty()
            // Only PasswordDigest is accepted: PasswordText would expose the password in cleartext
            if (!passwordAttrs.contains("PasswordDigest") || nonceB64.isEmpty() || created.isEmpty()) return@runCatching false
            if (credentials.password.isEmpty()) return@runCatching false
            if (!isCreatedFresh(created)) return@runCatching false

            val nonce = Base64.decode(nonceB64)
            val digest = MessageDigest.getInstance("SHA-1").apply {
                update(nonce)
                update(created.toByteArray(Charsets.UTF_8))
                update(credentials.password.toByteArray(Charsets.UTF_8))
            }.digest()
            val expected = Base64.encode(digest)

            constantTimeEquals(username, credentials.username) && constantTimeEquals(password, expected) && markWsNonceUsed(nonceB64)
        }.getOrDefault(false)

        if (!ok) onFailure(remoteIp)
        return ok
    }

    /** WS-Security `Created` must be within [WS_CREATED_TOLERANCE_MS] of the device clock. */
    private fun isCreatedFresh(created: String): Boolean {
        if (created.length < 19) return false
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val createdMs = runCatching { format.parse(created.substring(0, 19))?.time }.getOrNull() ?: return false
        return abs(System.currentTimeMillis() - createdMs) <= WS_CREATED_TOLERANCE_MS
    }

    /** Rejects WS-Security nonce replays within the freshness window. @return `true` if nonce was not seen before. */
    private fun markWsNonceUsed(nonce: String): Boolean {
        val now = nowMs()
        if (wsNonces.size >= MAX_NONCES) wsNonces.entries.removeAll { (_, seenMs) -> now - seenMs > 2 * WS_CREATED_TOLERANCE_MS }
        if (wsNonces.size >= MAX_NONCES) return false
        return wsNonces.putIfAbsent(nonce, now) == null
    }

    internal fun createChallenge(stale: Boolean): String {
        // Order matters: live555 (VLC) parses `Digest realm="...", nonce="..."[, stale=...]` positionally.
        return buildString {
            append("Digest realm=\"").append(REALM).append("\", nonce=\"").append(newNonce()).append('"')
            if (stale) append(", stale=TRUE")
            append(", algorithm=MD5, qop=\"auth\"")
        }
    }

    private enum class VerifyResult { OK, STALE, INVALID }

    private fun verify(method: String, requestUri: String, authorization: String, credentials: RtspServerCredentials): VerifyResult {
        val header = authorization.trim()
        if (!header.startsWith("Digest", ignoreCase = true)) return VerifyResult.INVALID // Basic over cleartext is not accepted
        if (credentials.password.isEmpty()) return VerifyResult.INVALID

        val params = parseAuthParams(header.substring(6))
        val username = params["username"] ?: return VerifyResult.INVALID
        val realm = params["realm"] ?: return VerifyResult.INVALID
        val nonce = params["nonce"] ?: return VerifyResult.INVALID
        val uri = params["uri"] ?: return VerifyResult.INVALID
        val response = params["response"]?.lowercase() ?: return VerifyResult.INVALID
        val qop = params["qop"]?.lowercase()
        val nc = params["nc"]
        val cnonce = params["cnonce"]
        val algorithm = params["algorithm"]?.uppercase() ?: "MD5"

        if (realm != REALM) return VerifyResult.INVALID
        if (!uriMatches(uri, requestUri)) return VerifyResult.INVALID

        val (digestName, sess) = when (algorithm) {
            "MD5" -> "MD5" to false
            "MD5-SESS" -> "MD5" to true
            "SHA-256" -> "SHA-256" to false
            "SHA-256-SESS" -> "SHA-256" to true
            else -> return VerifyResult.INVALID
        }

        val ha1Base = hash(digestName, "${credentials.username}:$REALM:${credentials.password}")
        val ha1 = if (sess) {
            if (cnonce == null) return VerifyResult.INVALID
            hash(digestName, "$ha1Base:$nonce:$cnonce")
        } else ha1Base
        val ha2 = hash(digestName, "$method:$uri")
        val expected = when (qop) {
            null -> hash(digestName, "$ha1:$nonce:$ha2")
            "auth" -> {
                if (nc == null || cnonce == null) return VerifyResult.INVALID
                hash(digestName, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            }

            else -> return VerifyResult.INVALID // auth-int not supported
        }

        val userOk = constantTimeEquals(username, credentials.username)
        val responseOk = MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), response.toByteArray(Charsets.US_ASCII))
        if (!userOk || !responseOk) return VerifyResult.INVALID

        val now = nowMs()
        val state = nonces[nonce]
        if (state == null || now - state.lastUsedMs > NONCE_IDLE_TIMEOUT_MS || now - state.createdMs > NONCE_MAX_AGE_MS) {
            nonces.remove(nonce)
            return VerifyResult.STALE
        }
        state.lastUsedMs = now
        return VerifyResult.OK
    }

    private fun newNonce(): String {
        val now = nowMs()
        if (nonces.size >= MAX_NONCES) {
            nonces.entries.removeAll { (_, s) -> now - s.lastUsedMs > NONCE_IDLE_TIMEOUT_MS || now - s.createdMs > NONCE_MAX_AGE_MS }
            if (nonces.size >= MAX_NONCES) nonces.clear()
        }
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val nonce = bytes.toHex()
        nonces[nonce] = NonceState(now, now)
        return nonce
    }

    /** @return `true` if the IP is now blocked. */
    private fun onFailure(remoteIp: String): Boolean = synchronized(failures) {
        val now = nowMs()
        if (failures.size >= MAX_TRACKED_IPS) {
            failures.entries.removeAll { (_, s) -> s.blockedUntilMs <= now && (s.failures.lastOrNull() ?: 0L) < now - FAILURE_WINDOW_MS }
            if (failures.size >= MAX_TRACKED_IPS) failures.clear()
        }
        val state = failures.getOrPut(remoteIp) { FailureState() }
        while (state.failures.isNotEmpty() && state.failures.first() < now - FAILURE_WINDOW_MS) state.failures.removeFirst()
        state.failures.addLast(now)
        if (state.failures.size >= MAX_FAILURES) {
            state.blockedUntilMs = now + BLOCK_MS
            state.failures.clear()
            true
        } else {
            false
        }
    }

    private fun uriMatches(digestUri: String, requestUri: String): Boolean {
        if (digestUri == requestUri) return true
        return normalizePath(digestUri) == normalizePath(requestUri)
    }

    private fun parseAuthParams(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in AUTH_PARAM_REGEX.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: ""
            result[key] = value.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return result
    }

    private fun hash(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.ISO_8859_1)).toHex()

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        var i = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            chars[i++] = HEX[v ushr 4]
            chars[i++] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    internal companion object {
        internal const val REALM: String = "ScreenStream"

        private const val NONCE_IDLE_TIMEOUT_MS: Long = 5 * 60 * 1000L
        private const val NONCE_MAX_AGE_MS: Long = 60 * 60 * 1000L
        private const val MAX_NONCES: Int = 1024

        private const val MAX_FAILURES: Int = 10
        private const val FAILURE_WINDOW_MS: Long = 60 * 1000L
        private const val BLOCK_MS: Long = 60 * 1000L
        private const val MAX_TRACKED_IPS: Int = 1024
        private const val WS_CREATED_TOLERANCE_MS: Long = 5 * 60 * 1000L

        private val HEX = "0123456789abcdef".toCharArray()
        private val AUTH_PARAM_REGEX = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))""")

        private val WS_USERNAME_TOKEN_REGEX = Regex("<[^>:/]*:?UsernameToken[\\s>/]")
        private val WS_USERNAME_REGEX = Regex("<[^>:/]*:?Username(?:\\s[^>]*)?>(.*?)</[^>:/]*:?Username>", RegexOption.DOT_MATCHES_ALL)
        private val WS_PASSWORD_REGEX = Regex("<[^>:/]*:?Password([^>]*)>(.*?)</[^>:/]*:?Password>", RegexOption.DOT_MATCHES_ALL)
        private val WS_NONCE_REGEX = Regex("<[^>:/]*:?Nonce[^>]*>(.*?)</[^>:/]*:?Nonce>", RegexOption.DOT_MATCHES_ALL)
        private val WS_CREATED_REGEX = Regex("<[^>:/]*:?Created[^>]*>(.*?)</[^>:/]*:?Created>", RegexOption.DOT_MATCHES_ALL)

        /** Extracts the path of an RTSP/HTTP request URI (absolute or relative), without query and trailing slashes. */
        internal fun normalizePath(uri: String): String {
            val schemeEnd = uri.indexOf("://")
            val afterAuthority = if (schemeEnd >= 0) {
                val pathStart = uri.indexOf('/', schemeEnd + 3)
                if (pathStart < 0) "" else uri.substring(pathStart)
            } else uri
            return afterAuthority.substringBefore('?').substringBefore('#').trimEnd('/')
        }
    }
}
