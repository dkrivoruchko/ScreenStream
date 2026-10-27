package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HttpAccessTest {
    // Cases: MJPEG-ACC-01
    @Test
    fun openViewingNeedsNoCredentialAndProtectionRequiresSavedPin() {
        val access = HttpAccess()

        assertTrue(access.allowsViewing(null, null))
        assertEquals(HttpAccess.PinResult.Authorized(null), access.verifyPin(null, "peer", 0))
        assertNull(access.exchangeAccessToken("irrelevant"))
        assertThrows(IllegalArgumentException::class.java) {
            access.updateSettings(AccessSettings(pinEnabled = true))
        }
        assertTrue(access.allowsViewing(null, null))
    }

    // Cases: MJPEG-ACC-01
    @Test
    fun protectedViewingRequiresCurrentTokenOrCookieAndSixAsciiDigitPin() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings().copy(limitWrongPins = false))
        val token = checkNotNull(access.accessToken)
        val cookie = authorizedCookie(access, "123456")
        val wrongToken = token.value.dropLast(1) + if (token.value.last() == 'A') "B" else "A"
        val wrongCookie = cookie.value.dropLast(1) + if (cookie.value.last() == '0') "1" else "0"

        assertFalse(access.allowsViewing(null, null))
        assertFalse(access.allowsViewing("wrong", "wrong"))
        assertFalse(access.allowsViewing(wrongToken, null))
        assertFalse(access.allowsViewing(null, wrongCookie))
        assertNull(access.exchangeAccessToken(wrongToken))
        assertTrue(access.allowsViewing(token.value, null))
        assertTrue(access.allowsViewing(null, cookie.value))
        assertTrue(access.allowsViewing(token.value, wrongCookie))
        assertTrue(access.allowsViewing(wrongToken, cookie.value))
        for (pin in listOf(null, "", "12345", "1234567", "12345x", "１２３４５６", "654321")) {
            assertEquals(HttpAccess.PinResult.Wrong, access.verifyPin(pin, "peer", 0))
        }
        assertEquals(cookie, authorizedCookie(access, "123456"))
    }

    // Cases: MJPEG-ACC-02
    @Test
    fun repeatedPinLoginAndTokenExchangeKeepCookieAndWrongAttemptBudget() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings())
        val token = checkNotNull(access.accessToken)
        val cookie = authorizedCookie(access, "123456")
        val generation = access.generation

        assertEquals(cookie, authorizedCookie(access, "123456"))
        wrongAttempts(access, count = 4)
        repeat(2) { assertEquals(cookie, access.exchangeAccessToken(token.value)) }
        assertNull(access.exchangeAccessToken(null))
        assertNull(access.exchangeAccessToken("wrong"))
        assertEquals(generation, access.generation)
        assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 0))
        assertTrue(access.allowsViewing(null, cookie.value))
    }

    // Cases: MJPEG-ACC-03
    @Test
    fun equalPinAndPolicyOnlyEditsPreserveExistingViewingAuthority() {
        val access = HttpAccess()
        val settings = protectedSettings()
        access.updateSettings(settings)
        val token = checkNotNull(access.accessToken)
        val cookie = authorizedCookie(access, "123456")
        val generation = access.generation
        val edited = settings.copy(
            pin = SecretValue(String(charArrayOf('1', '2', '3', '4', '5', '6'))),
            pinPolicy = AccessSettings.PinPolicy.Permanent,
            hidePinOnCaptureStart = false,
            limitWrongPins = false,
            blockMinutes = 3,
        )

        assertFalse(access.requiresCredentialRotation(edited))
        assertFalse(access.updateSettings(edited))
        assertEquals(generation, access.generation)
        assertEquals(token, access.accessToken)
        assertEquals(cookie, access.exchangeAccessToken(token.value))
        assertTrue(access.allowsViewing(token.value, cookie.value))
    }

    // Cases: MJPEG-ACC-03
    @Test
    fun pinRotationAndReenablingProtectionRejectOldViewingAuthority() {
        val access = HttpAccess()
        val settings = protectedSettings()
        assertTrue(access.updateSettings(settings))
        val oldToken = checkNotNull(access.accessToken)
        val oldCookie = authorizedCookie(access, "123456")
        val generation = access.generation
        val changedPin = settings.copy(pin = SecretValue("654321"))

        assertTrue(access.requiresCredentialRotation(changedPin))
        assertTrue(access.updateSettings(changedPin))
        assertEquals(generation + 1, access.generation)
        assertFalse(access.allowsViewing(oldToken.value, oldCookie.value))
        assertNull(access.exchangeAccessToken(oldToken.value))
        val rotatedToken = checkNotNull(access.accessToken)
        val rotatedCookie = authorizedCookie(access, "654321")
        assertTrue(access.allowsViewing(rotatedToken.value, rotatedCookie.value))

        assertTrue(access.updateSettings(changedPin.copy(pinEnabled = false)))
        assertEquals(generation + 2, access.generation)
        assertNull(access.accessToken)
        assertNull(access.exchangeAccessToken(rotatedToken.value))
        assertTrue(access.allowsViewing(null, null))
        assertTrue(access.updateSettings(changedPin))
        assertEquals(generation + 3, access.generation)
        assertFalse(access.allowsViewing(rotatedToken.value, rotatedCookie.value))
        assertTrue(access.allowsViewing(checkNotNull(access.accessToken).value, null))
    }

    // Cases: MJPEG-ACC-04
    @Test
    fun fifthWrongPinBlocksOnlyItsImmediatePeer() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings().copy(blockMinutes = 2))

        wrongAttempts(access, count = 4, nowMillis = 1_000)
        assertEquals(HttpAccess.PinResult.Wrong, access.verifyPin(null, "other-peer", 1_000))
        assertEquals(HttpAccess.PinResult.Blocked(120_000), access.verifyPin(null, "peer", 1_000))
        authorizedCookie(access, "123456", peer = "other-peer", nowMillis = 1_000)
        assertEquals(120_000L, access.remainingBlockMillis("peer", 1_000))
    }

    // Cases: MJPEG-ACC-05
    @Test
    fun blockedPinRequestsCannotBypassOrExtendDeadlineButViewingStillWorks() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings())
        val token = checkNotNull(access.accessToken)
        val cookie = authorizedCookie(access, "123456")
        wrongAttempts(access, count = 4, nowMillis = 1_000)
        assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 1_000))

        assertEquals(HttpAccess.PinResult.Blocked(50_000), access.verifyPin("123456", "peer", 11_000))
        assertEquals(HttpAccess.PinResult.Blocked(40_000), access.verifyPin("wrong", "peer", 21_000))
        assertEquals(1L, access.remainingBlockMillis("peer", 60_999))
        assertTrue(access.allowsViewing(token.value, null))
        assertTrue(access.allowsViewing(null, cookie.value))
    }

    // Cases: MJPEG-ACC-06
    @Test
    fun successfulPinResetsFourPreviousWrongAttempts() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings())
        wrongAttempts(access, count = 4)

        authorizedCookie(access, "123456")

        wrongAttempts(access, count = 4)
        assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 0))
    }

    // Cases: MJPEG-ACC-06
    @Test
    fun exactExpiryAndLaterVerificationStartFreshWrongAttemptBudget() {
        for (verificationTime in listOf(61_000L, 61_001L)) {
            val access = HttpAccess()
            access.updateSettings(protectedSettings())
            wrongAttempts(access, count = 4, nowMillis = 1_000)
            assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 1_000))
            assertEquals(HttpAccess.PinResult.Blocked(1), access.verifyPin("123456", "peer", 60_999))

            wrongAttempts(access, count = 4, nowMillis = verificationTime)
            assertEquals(0L, access.remainingBlockMillis("peer", verificationTime))
            assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", verificationTime))
        }
    }

    // Cases: MJPEG-ACC-07
    @Test
    fun rotationPreservesPendingFailuresAndActiveBlockWhileDurationEditAffectsNextBlock() {
        val access = HttpAccess()
        val settings = protectedSettings()
        access.updateSettings(settings)
        wrongAttempts(access, count = 4, nowMillis = 1_000)
        val rotated = settings.copy(pin = SecretValue("654321"))

        access.updateSettings(rotated)
        assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 1_000))
        access.updateSettings(rotated.copy(pin = SecretValue("111111"), blockMinutes = 3))
        assertEquals(HttpAccess.PinResult.Blocked(50_000), access.verifyPin("111111", "peer", 11_000))

        wrongAttempts(access, count = 4, nowMillis = 61_000)
        assertEquals(HttpAccess.PinResult.Blocked(180_000), access.verifyPin("wrong", "peer", 61_000))
    }

    // Cases: MJPEG-ACC-07
    @Test
    fun disablingLimiterOrOpeningAccessClearsPendingFailuresAndBlocks() {
        for (hadBlock in listOf(false, true)) {
            for (openAccess in listOf(false, true)) {
                val access = HttpAccess()
                val settings = protectedSettings()
                access.updateSettings(settings)
                wrongAttempts(access, count = 4)
                if (hadBlock) {
                    assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 0))
                }

                access.updateSettings(if (openAccess) settings.copy(pinEnabled = false) else settings.copy(limitWrongPins = false))
                assertEquals(0L, access.remainingBlockMillis("peer", 0))
                repeat(6) {
                    val expected = if (openAccess) HttpAccess.PinResult.Authorized(null) else HttpAccess.PinResult.Wrong
                    assertEquals(expected, access.verifyPin("wrong", "peer", 0))
                }
                access.updateSettings(settings)
                wrongAttempts(access, count = 4)
                assertEquals(HttpAccess.PinResult.Blocked(60_000), access.verifyPin("wrong", "peer", 0))
            }
        }
    }

    // Cases: MJPEG-ACC-08
    @Test
    fun overflowingBlockDeadlineSaturatesAndExpiresAtLongMaximum() {
        val access = HttpAccess()
        access.updateSettings(protectedSettings().copy(blockMinutes = 2))
        val now = Long.MAX_VALUE - 1_000
        wrongAttempts(access, count = 4, nowMillis = now)

        assertEquals(HttpAccess.PinResult.Blocked(1_000), access.verifyPin("wrong", "peer", now))
        assertEquals(HttpAccess.PinResult.Blocked(1), access.verifyPin("123456", "peer", Long.MAX_VALUE - 1))
        assertEquals(0L, access.remainingBlockMillis("peer", Long.MAX_VALUE))
        authorizedCookie(access, "123456", nowMillis = Long.MAX_VALUE)
    }

    private fun protectedSettings(): AccessSettings = AccessSettings(pinEnabled = true, pin = SecretValue("123456"))

    private fun authorizedCookie(access: HttpAccess, pin: String, peer: String = "peer", nowMillis: Long = 0): SecretValue {
        val result = access.verifyPin(pin, peer, nowMillis)
        assertTrue(result is HttpAccess.PinResult.Authorized)
        return checkNotNull((result as HttpAccess.PinResult.Authorized).cookie)
    }

    private fun wrongAttempts(access: HttpAccess, count: Int, nowMillis: Long = 0) {
        repeat(count) { assertEquals(HttpAccess.PinResult.Wrong, access.verifyPin("wrong", "peer", nowMillis)) }
    }
}
