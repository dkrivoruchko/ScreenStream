package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes

/** Viewing credentials and peer PIN blocks. Every operation requires the runtime's control monitor. */
internal class HttpAccess {
    private val random = SecureRandom()
    private var appliedSettings = AccessSettings()
    private var viewingCredentials: ViewingCredentials? = null
    private val pinAttemptsByPeer = HashMap<String, PinAttempts>()

    /** Unsaved URL credential; null while access is open. */
    val accessToken: SecretValue? get() = viewingCredentials?.token

    /** Credential incarnation used to fence admitted WebSocket state. */
    var credentialGeneration: Long = 0
        private set

    val pinEnabled: Boolean get() = viewingCredentials != null

    /**
     * True means credentials changed and the caller must revoke the old generation.
     * Wrong attempts and active blocks survive rotation; open access or disabling the limiter clears them.
     * A changed block duration applies only to future blocks.
     */
    fun updateSettings(settings: AccessSettings): Boolean {
        require(!settings.pinEnabled || settings.pin != null) { "Protected access requires a saved PIN" }
        val credentialsChanged = requiresCredentialRotation(settings)
        if (credentialsChanged) {
            viewingCredentials = if (settings.pinEnabled) {
                ViewingCredentials(
                    token = SecretValue(Base64.UrlSafe.encode(ByteArray(ACCESS_TOKEN_BYTES).also(random::nextBytes))),
                    cookie = SecretValue(ByteArray(COOKIE_BYTES).also(random::nextBytes).toHexString()),
                )
            } else null
            credentialGeneration++
        }
        appliedSettings = settings
        if (!settings.pinEnabled || !settings.limitWrongPins) pinAttemptsByPeer.clear()
        return credentialsChanged
    }

    fun requiresCredentialRotation(settings: AccessSettings): Boolean =
        pinEnabled != settings.pinEnabled || settings.pinEnabled && appliedSettings.pin != settings.pin

    /** PIN blocks restrict verification, not existing viewing credentials. */
    fun allowsViewing(accessToken: String?, cookie: String?): Boolean {
        val credentials = viewingCredentials ?: return true
        return matches(credentials.token, accessToken) || matches(credentials.cookie, cookie)
    }

    /** Valid links exchange for the existing cookie without rotating credentials. */
    fun exchangeAccessToken(accessToken: String?): SecretValue? {
        val credentials = viewingCredentials ?: return null
        return if (matches(credentials.token, accessToken)) credentials.cookie else null
    }

    /**
     * Require six ASCII digits. With limiting enabled, the fifth wrong attempt blocks this peer
     * across all listeners.
     * Requests during a block cannot extend its deadline or bypass it with the correct PIN.
     * New peers wait for capacity before PIN comparison; live attempt records are never evicted.
     */
    fun verifyPin(pin: String?, peerIp: String, nowMillis: Long): PinResult {
        val credentials = viewingCredentials ?: return PinResult.Authorized(null)
        val retryAfterMillis = pinRetryAfterMillis(peerIp, nowMillis)
        if (retryAfterMillis > 0) return PinResult.Blocked(retryAfterMillis)
        if (pin != null && AccessSettings.isValidPin(pin) && matches(appliedSettings.pin, pin)) {
            pinAttemptsByPeer.remove(peerIp)
            return PinResult.Authorized(credentials.cookie)
        }
        if (!appliedSettings.limitWrongPins) return PinResult.Wrong
        val peerAttempts = pinAttemptsByPeer.getOrPut(peerIp) { PinAttempts() }
        peerAttempts.wrongCount++
        val isBlocked = peerAttempts.wrongCount >= MAX_WRONG_PINS
        val recordLifetimeMillis = if (isBlocked) {
            appliedSettings.blockMinutes.minutes.inWholeMilliseconds
        } else {
            PIN_ATTEMPT_IDLE_TIMEOUT_MILLIS
        }
        peerAttempts.expiresAtMillis = deadlineAfter(nowMillis, recordLifetimeMillis)
        return if (isBlocked) PinResult.Blocked(peerAttempts.expiresAtMillis - nowMillis) else PinResult.Wrong
    }

    /** Prune expired records without refreshing them; return the peer block or full-table capacity wait. */
    fun pinRetryAfterMillis(peerIp: String, nowMillis: Long): Long {
        if (!pinEnabled || !appliedSettings.limitWrongPins) return 0
        pinAttemptsByPeer.values.removeAll { nowMillis >= it.expiresAtMillis }
        val peerAttempts = pinAttemptsByPeer[peerIp]
        if (peerAttempts != null) {
            if (peerAttempts.wrongCount < MAX_WRONG_PINS) return 0
            return peerAttempts.expiresAtMillis - nowMillis
        }
        if (pinAttemptsByPeer.size < MAX_PIN_ATTEMPT_RECORDS) return 0
        return pinAttemptsByPeer.values.minOf { it.expiresAtMillis } - nowMillis
    }

    private fun deadlineAfter(nowMillis: Long, durationMillis: Long): Long =
        if (nowMillis > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE else nowMillis + durationMillis

    private fun matches(expected: SecretValue?, supplied: String?): Boolean {
        // Length is public; compare equal-length credentials without an early byte mismatch return.
        return expected != null && supplied != null && supplied.length == expected.value.length &&
                MessageDigest.isEqual(expected.value.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }

    sealed interface PinResult {
        data class Authorized(val cookie: SecretValue?) : PinResult
        data object Wrong : PinResult
        data class Blocked(val retryAfterMs: Long) : PinResult
    }

    private class ViewingCredentials(val token: SecretValue, val cookie: SecretValue)

    private class PinAttempts {
        var wrongCount = 0
        var expiresAtMillis = 0L
    }

    private companion object {
        const val MAX_WRONG_PINS = 5
        const val MAX_PIN_ATTEMPT_RECORDS = 1_024
        val PIN_ATTEMPT_IDLE_TIMEOUT_MILLIS = 15.minutes.inWholeMilliseconds
        const val COOKIE_BYTES = 32
        const val ACCESS_TOKEN_BYTES = 24
    }
}
