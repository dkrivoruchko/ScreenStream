package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** Viewing credentials and peer PIN blocks. Every operation requires the runtime's control monitor. */
internal class HttpAccess {
    private val random = SecureRandom()
    private var appliedSettings = AccessSettings()
    private var viewingCookie: SecretValue? = null
    private val pinAttemptsByPeer = HashMap<String, PinAttempts>()

    /** Unsaved URL credential; null while access is open. */
    var accessToken: SecretValue? = null
        private set

    /** Credential incarnation used to fence admitted WebSocket state. */
    var generation: Long = 0
        private set

    val pinEnabled: Boolean get() = appliedSettings.pinEnabled

    /**
     * True means credentials changed and the caller must revoke the old generation.
     * Wrong attempts and active blocks survive rotation; open access or disabling the limiter clears them.
     * A changed block duration applies only to future blocks.
     */
    fun updateSettings(settings: AccessSettings): Boolean {
        require(!settings.pinEnabled || settings.pin != null) { "Protected access requires a saved PIN" }
        val credentialsChanged = requiresCredentialRotation(settings)
        if (credentialsChanged) {
            // Generate both credentials before committing either one.
            val nextAccessToken = if (settings.pinEnabled) {
                SecretValue(Base64.UrlSafe.encode(ByteArray(ACCESS_TOKEN_BYTES).also(random::nextBytes)))
            } else null
            val nextCookie = if (settings.pinEnabled) {
                SecretValue(ByteArray(COOKIE_BYTES).also(random::nextBytes).toHexString())
            } else null
            accessToken = nextAccessToken
            viewingCookie = nextCookie
            generation++
        }
        appliedSettings = settings
        if (!settings.pinEnabled || !settings.limitWrongPins) pinAttemptsByPeer.clear()
        return credentialsChanged
    }

    fun requiresCredentialRotation(settings: AccessSettings): Boolean =
        appliedSettings.pinEnabled != settings.pinEnabled || settings.pinEnabled && appliedSettings.pin != settings.pin

    /** PIN blocks restrict verification, not existing viewing credentials. */
    fun allowsViewing(accessToken: String?, cookie: String?): Boolean =
        !pinEnabled || matches(this.accessToken, accessToken) || matches(viewingCookie, cookie)

    /** Valid links exchange for the existing cookie without rotating credentials. */
    fun exchangeAccessToken(accessToken: String?): SecretValue? =
        if (pinEnabled && matches(this.accessToken, accessToken)) viewingCookie else null

    /**
     * Require six ASCII digits. With limiting enabled, the fifth wrong attempt blocks this peer
     * across all listeners.
     * Requests during a block cannot extend its deadline or bypass it with the correct PIN.
     */
    fun verifyPin(pin: String?, peerIp: String, nowMillis: Long): PinResult {
        if (!pinEnabled) return PinResult.Authorized(null)
        val retryAfterMillis = remainingBlockMillis(peerIp, nowMillis)
        if (retryAfterMillis > 0) return PinResult.Blocked(retryAfterMillis)
        if (pin != null && AccessSettings.isValidPin(pin) && matches(appliedSettings.pin, pin)) {
            pinAttemptsByPeer.remove(peerIp)
            return PinResult.Authorized(checkNotNull(viewingCookie))
        }
        if (!appliedSettings.limitWrongPins) return PinResult.Wrong
        val peerAttempts = pinAttemptsByPeer.getOrPut(peerIp) { PinAttempts() }
        peerAttempts.wrongCount++
        if (peerAttempts.wrongCount < MAX_WRONG_PINS) return PinResult.Wrong
        val blockDurationMillis = appliedSettings.blockMinutes.toLong() * MINUTE_MILLIS
        peerAttempts.blockedUntilMillis = if (nowMillis > Long.MAX_VALUE - blockDurationMillis) {
            Long.MAX_VALUE
        } else {
            nowMillis + blockDurationMillis
        }
        return PinResult.Blocked(peerAttempts.blockedUntilMillis - nowMillis)
    }

    /** Expiry resets the wrong-attempt count. */
    fun remainingBlockMillis(peerIp: String, nowMillis: Long): Long {
        if (!pinEnabled || !appliedSettings.limitWrongPins) return 0
        val peerAttempts = pinAttemptsByPeer[peerIp] ?: return 0
        if (peerAttempts.blockedUntilMillis == 0L) return 0
        if (nowMillis >= peerAttempts.blockedUntilMillis) {
            pinAttemptsByPeer.remove(peerIp)
            return 0
        }
        return peerAttempts.blockedUntilMillis - nowMillis
    }

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

    private class PinAttempts {
        var wrongCount = 0
        var blockedUntilMillis = 0L
    }

    private companion object {
        const val MAX_WRONG_PINS = 5
        const val MINUTE_MILLIS = 60_000L
        const val COOKIE_BYTES = 32
        const val ACCESS_TOKEN_BYTES = 24
    }
}
