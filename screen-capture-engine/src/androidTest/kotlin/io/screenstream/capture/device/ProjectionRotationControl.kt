package io.screenstream.capture.device

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.uiautomator.UiDevice

/** Owns actual display rotation and saved platform policy; host layout and image readiness belong to the fixture. */
internal class ProjectionRotationControl(
    private val context: Context,
    private val automation: UiAutomation,
    private val device: UiDevice,
) {
    val originalPolicy: Pair<Int, Int> = policy()
    var touched: Boolean = false
        private set
    var baseline: ProjectionDisplay? = null
        private set

    fun policy(): Pair<Int, Int> =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) to
                Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION)

    fun controllerMatches(width: Int, height: Int, rotation: Int): Boolean =
        device.displayWidth == width && device.displayHeight == height && device.displayRotation == rotation

    fun rememberBaseline(display: ProjectionDisplay) {
        baseline = display
    }

    fun rotateTo(rotation: Int) {
        // Mark first: treat a rejected or thrown call as possibly applied, so restoration stays due.
        touched = true
        check(automation.setRotation(rotation)) { "Android rejected actual display rotation" }
    }

    fun restoreSavedUserRotationLocked() {
        check(automation.setRotation(originalPolicy.second))
    }

    fun savedUserRotationMatches(): Boolean {
        val display = checkNotNull(baseline)
        val sameAxes = originalPolicy.second % 2 == display.rotation % 2
        val width = if (sameAxes) display.width else display.height
        val height = if (sameAxes) display.height else display.width
        return policy() == (0 to originalPolicy.second) && controllerMatches(width, height, originalPolicy.second)
    }

    fun restoreAutomaticPolicy() {
        check(automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
    }

    fun restoreSavedUserRotationValue() {
        // Freeze/thaw updates are asynchronous. Preserve only the saved value, not posture-specific policies.
        val command = "settings put --user current system ${Settings.System.USER_ROTATION} ${originalPolicy.second}"
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
            check(input.bufferedReader().readText().isBlank()) { "Saved user rotation restoration returned output" }
        }
    }

    fun originalStateMatches(): Boolean {
        val display = checkNotNull(baseline)
        return policy() == originalPolicy && controllerMatches(display.width, display.height, display.rotation)
    }

    fun controllerFacts(): String = "controller=${device.displayWidth}x${device.displayHeight} rotation=${device.displayRotation}"
}
