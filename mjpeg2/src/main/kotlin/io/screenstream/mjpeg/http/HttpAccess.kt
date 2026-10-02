package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Shared access state for all of one delivery's listeners. The delivery owns every call under its
 * short HTTP gate, together with page/media admission; this helper owns no locks, jobs or sockets.
 * Applied PINs have already been saved by the controller. Page identities are owned separately.
 */
internal class HttpAccess {
    private val random = SecureRandom()
    private var settings = AccessSettings()
    private var token: SecretValue? = null
    private var cookie: SecretValue? = null
    private val attempts = HashMap<String, PinAttempts>()

    /** Admission generation; replacing credentials revokes previously admitted pages and media. */
    var generation: Long = 0
        private set

    val pinEnabled: Boolean get() = settings.pinEnabled

    /**
     * Apply one prepared snapshot. True means the caller must revoke the previous generation.
     * Presentation/PIN policy and blocking preferences do not replace viewing credentials.
     * Blocks and wrong attempts survive credential replacement, so rotation cannot bypass a block;
     * open access or disabling the limiter clears them. Duration changes affect only future blocks.
     */
    fun apply(settings: AccessSettings, token: SecretValue?): Boolean {
        require(!settings.pinEnabled || settings.pin != null && token != null) { "Protected access must be prepared" }
        require(settings.pinEnabled || token == null) { "Open access cannot have a token" }
        val changed = this.settings.pinEnabled != settings.pinEnabled || this.token != token ||
                settings.pinEnabled && this.settings.pin != settings.pin
        val nextCookie = if (changed && settings.pinEnabled) newCookie() else cookie
        this.settings = settings
        this.token = token
        if (!settings.pinEnabled || !settings.limitWrongPins) attempts.clear()
        if (changed) {
            cookie = if (settings.pinEnabled) nextCookie else null
            generation++
        }
        return changed
    }

    /** A page identity or the peer's PIN block never grants or revokes viewing admission. */
    fun admits(token: String?, cookie: String?): Boolean = !pinEnabled ||
            matches(this.token, token) || matches(this.cookie, cookie)

    /** Valid link tokens exchange for the same cookie; invalid links leave existing grants intact. */
    fun exchangeToken(token: String?): SecretValue? = if (pinEnabled && matches(this.token, token)) cookie else null

    /**
     * Verify exactly six ASCII digits against the applied PIN. The fifth wrong attempt starts a
     * block shared across listeners for this immediate peer IP. Monotonic time comes from HTTP.
     * Requests during a block never change its deadline, including requests containing a valid PIN.
     */
    fun verifyPin(pin: String?, peerIp: String, nowMillis: Long): PinResult {
        if (!pinEnabled) return PinResult.Authorized(null)
        val remaining = remainingBlockMillis(peerIp, nowMillis)
        if (remaining > 0) return PinResult.Blocked(remaining)
        if (pin != null && pin.length == PIN_DIGITS && pin.all { it in '0'..'9' } && matches(settings.pin, pin)) {
            attempts.remove(peerIp)
            return PinResult.Authorized(checkNotNull(cookie))
        }
        if (!settings.limitWrongPins) return PinResult.Wrong
        val peer = attempts.getOrPut(peerIp) { PinAttempts() }
        peer.wrong++
        if (peer.wrong < MAX_WRONG_PINS) return PinResult.Wrong
        val duration = settings.blockMinutes.toLong() * MINUTE_MILLIS
        // Saturation avoids a wrapped deadline even for an unusually large monotonic timestamp.
        peer.blockedUntil = if (nowMillis > Long.MAX_VALUE - duration) Long.MAX_VALUE else nowMillis + duration
        return PinResult.Blocked(peer.blockedUntil - nowMillis)
    }

    /** Called after viewing admission failed; this only describes the PIN-entry restriction. */
    fun deniedState(peerIp: String, nowMillis: Long): DeniedState {
        val remaining = remainingBlockMillis(peerIp, nowMillis)
        return if (remaining > 0) DeniedState.Blocked(remaining) else DeniedState.PinRequired
    }

    /** Expiry resets the count; a check during the block cannot extend it. */
    fun remainingBlockMillis(peerIp: String, nowMillis: Long): Long {
        if (!pinEnabled || !settings.limitWrongPins) return 0
        val peer = attempts[peerIp] ?: return 0
        if (peer.blockedUntil == 0L) return 0
        if (nowMillis >= peer.blockedUntil) {
            attempts.remove(peerIp)
            return 0
        }
        return peer.blockedUntil - nowMillis
    }

    sealed interface PinResult {
        data class Authorized(val cookie: SecretValue?) : PinResult
        data object Wrong : PinResult
        data class Blocked(val retryAfterMs: Long) : PinResult
    }

    sealed interface DeniedState {
        data object PinRequired : DeniedState
        data class Blocked(val retryAfterMs: Long) : DeniedState
    }

    private class PinAttempts {
        var wrong = 0
        var blockedUntil = 0L
    }

    private fun matches(expected: SecretValue?, supplied: String?): Boolean {
        if (expected == null || supplied == null || supplied.length != expected.value.length) return false
        // Equal public lengths are checked first; comparison examines every expected UTF-8 byte.
        return MessageDigest.isEqual(expected.value.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }

    private fun newCookie(): SecretValue {
        val bytes = ByteArray(COOKIE_BYTES).also(random::nextBytes)
        val encoded = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            encoded[index * 2] = HEX[unsigned ushr 4]
            encoded[index * 2 + 1] = HEX[unsigned and 0x0f]
        }
        return SecretValue(encoded.concatToString())
    }

    private companion object {
        const val PIN_DIGITS = 6
        const val MAX_WRONG_PINS = 5
        const val MINUTE_MILLIS = 60_000L
        const val COOKIE_BYTES = 32
        const val HEX = "0123456789abcdef"
    }
}
