package io.screenstream.mjpeg.access

import android.util.Base64
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import java.security.SecureRandom

/** The result of one stateless, durable access preparation. */
internal sealed interface AccessPreparation {
    /** Saved preferences and the prepared token; null deliberately means open access. */
    data class Prepared(val settings: AccessSettings, val token: SecretValue?) : AccessPreparation
    data object Obsolete : AccessPreparation
}

/**
 * Save a generated PIN before returning its token. [admitSave] records the exact owned candidate
 * inside the conditional transaction, before it can reach the settings observer. An admitted
 * durable write may finish after cancellation; the caller must still validate the operation
 * before applying its result. This function owns no jobs, HTTP state or cached input.
 */
internal suspend fun prepareAccess(
    expected: AccessSettings,
    rotate: Boolean,
    previousApplied: AccessPreparation.Prepared?,
    settings: MjpegSettings,
    admitSave: (AccessSettings) -> Boolean,
): AccessPreparation {
    val random = SecureRandom()
    var access = expected
    if (access.pinEnabled && (access.pin == null || rotate && access.pinPolicy != AccessSettings.PinPolicy.Permanent)) {
        val pin = SecretValue(random.nextInt(PIN_RANGE).toString().padStart(PIN_DIGITS, '0'))
        val candidate = access.copy(pin = pin)
        var saved = false
        settings.updateData {
            if (this.access != expected || !admitSave(candidate)) this else {
                saved = true
                copy(access = candidate)
            }
        }
        if (!saved) return AccessPreparation.Obsolete
        access = candidate
    }
    val token = when {
        !access.pinEnabled -> null
        previousApplied?.settings?.pinEnabled == true && previousApplied.settings.pin == access.pin && !rotate -> previousApplied.token
        else -> SecretValue(Base64.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }
    return AccessPreparation.Prepared(access, token)
}

private const val PIN_RANGE: Int = 1_000_000
private const val PIN_DIGITS: Int = 6
private const val TOKEN_BYTES: Int = 24
