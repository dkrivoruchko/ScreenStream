package io.screenstream.mjpeg.device

import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Display
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.OutputSize
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.Companion.matchesTarget
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.MultipartViewer
import io.screenstream.mjpeg.settings.ImageSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class MjpegReconfigurationDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun invalidCropShowsPlaceholderAndValidSettingsResumeSameAttempt() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            val attempt = startCapture(host)
            awaitJpegTarget(host.port, green = false)
            val submissions = host.systemConsentAttempts.toList()
            val reuses = host.reusedConsentAttempts.toList()
            val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            MultipartViewer(host.port).use { stream ->
                stream.awaitTarget(green = false)
                // Nonnegative values are legal settings. This inset exceeds every positive Int-width
                // source and is rejected by the real Engine only once authoritative geometry is applied.
                runBlocking {
                    host.settings.updateData {
                        copy(image = image.copy(cropEnabled = true, cropInsets = CropInsetsPx(Int.MAX_VALUE, 0, 0, 0)))
                    }
                }
                awaitHostCondition(host, "real invalid-geometry suspension selects the JPEG placeholder") {
                    requestJpeg(host.port).contentEquals(logo)
                }
                stream.awaitFrame("placeholder after the live invalid crop") { it.contentEquals(logo) }
                val suspension = context.getString(R.string.mjpeg_error_suspension_invalid_request)
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(suspension))
                compose.onNodeWithText(suspension).assertIsDisplayed()
                assertEquals(attempt, runningModule(host)?.status?.captureAttempt)
                assertEquals(true, runningModule(host)?.status?.isStreaming, "Confirmed capture remains streaming while its Engine is suspended")
                assertTrue(serviceIsRunning())
                assertTrue(canConnect(host.port))

                compose.runOnUiThread { host.showGreenTarget.value = true }
                runBlocking {
                    host.settings.updateData {
                        copy(
                            image = image.copy(
                                cropEnabled = false,
                                cropInsets = CropInsetsPx.ZERO,
                                outputSelection = ImageSettings.OutputSelection.TargetSize,
                                targetSize = OutputSize.TargetSize(320, 192, OutputSize.ContentMode.Stretch),
                            )
                        )
                    }
                }
                val recoveredJpeg = awaitJpeg(
                    host.port, "crop-recovered-320x192", "decoded 320x192 recovery with a newly changed target",
                ) { jpeg ->
                    dimensions(jpeg) == (320 to 192) && matchesTarget(jpeg, green = true)
                }
                stream.awaitFrame("320x192 recovered multipart target") {
                    dimensions(it) == (320 to 192) && matchesTarget(it, green = true)
                }
                saveJpegEvidence("crop-recovered-320x192", recoveredJpeg)
                assertEquals(attempt, runningModule(host)?.status?.captureAttempt)
                assertEquals(true, runningModule(host)?.status?.isStreaming)
                assertEquals(submissions, host.systemConsentAttempts.toList())
                assertEquals(reuses, host.reusedConsentAttempts.toList())
                exitAndAssertResourcesReleased(host, stream)
            }
        }
    }

    @Test
    fun displayRotationUpdatesJpegGeometryWithoutReplacingCaptureOrViewer() = with(MjpegDeviceTestSupport(compose)) {
        val resolver = context.contentResolver
        val originalAutoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val originalUserRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        val originalDisplay = displayGeometry(this)
        var primaryFailure: Throwable? = null
        try {
            openHost(imageSettings = ImageSettings(scalePercent = 100.0)).use { fixture ->
                val host = fixture.host
                // At 100%, expected JPEG dimensions are independently read platform pixels, with
                // no rounding/topology calculation borrowed from the Engine's output observations.
                val attempt = startCapture(host)
                val initialDisplay = displayGeometry(this)
                val initialJpeg = awaitJpeg(
                    host.port, "rotation-initial", "initial decoded full-display geometry",
                    details = { "expectedPlatform=$initialDisplay; actualPlatform=${displayGeometry(this)}" },
                ) { jpeg ->
                    dimensions(jpeg) == (initialDisplay.width to initialDisplay.height) && matchesTarget(jpeg, green = false)
                }
                saveJpegEvidence("rotation-initial", initialJpeg)
                lateinit var initialActivity: WeakReference<MjpegCaptureTestActivity>
                fixture.scenario.onActivity { initialActivity = WeakReference(it) }
                val submissions = host.systemConsentAttempts.toList()
                val reuses = host.reusedConsentAttempts.toList()
                MultipartViewer(host.port).use { stream ->
                    stream.awaitFrame("initial full-display multipart target") {
                        dimensions(it) == (initialDisplay.width to initialDisplay.height) && matchesTarget(it, green = false)
                    }
                    val nextRotation = (initialDisplay.rotation + 1) % 4
                    assertTrue(instrumentation.uiAutomation.setRotation(nextRotation), "Android rejected the real display rotation")
                    awaitHostCondition(host, "actual platform display geometry change (ignored rotation is not coverage)") {
                        val actual = displayGeometry(this)
                        actual.rotation == nextRotation && actual.width == initialDisplay.height && actual.height == initialDisplay.width &&
                                actual.width != initialDisplay.width
                    }
                    val rotatedDisplay = displayGeometry(this)
                    assertNotEquals(initialDisplay.width to initialDisplay.height, rotatedDisplay.width to rotatedDisplay.height)
                    val rotatedJpeg = awaitJpeg(
                        host.port, "rotation-changed", "rotated decoded JPEG geometry and asymmetric target",
                        details = { "expectedPlatform=$rotatedDisplay; actualPlatform=${displayGeometry(this)}" },
                    ) { jpeg ->
                        dimensions(jpeg) == (rotatedDisplay.width to rotatedDisplay.height) && matchesTarget(jpeg, green = false)
                    }
                    stream.awaitFrame("rotated geometry on the original multipart connection") {
                        dimensions(it) == (rotatedDisplay.width to rotatedDisplay.height) && matchesTarget(it, green = false)
                    }
                    saveJpegEvidence("rotation-changed", rotatedJpeg)
                    fixture.scenario.onActivity {
                        assertNotSame(initialActivity.get(), it, "Actual display rotation must recreate the test Activity")
                    }
                    saveTextEvidence("rotation-activity-recreation", "ActivityScenario observed a new Activity instance after actual display rotation.\n")
                    assertEquals(attempt, runningModule(host)?.status?.captureAttempt)
                    assertTrue(
                        instrumentation.uiAutomation.setRotation(initialDisplay.rotation),
                        "Android rejected restoration to the initial display rotation",
                    )
                    awaitHostCondition(host, "platform geometry restored") {
                        displayGeometry(this).let { it.width == initialDisplay.width && it.height == initialDisplay.height && it.rotation == initialDisplay.rotation }
                    }
                    compose.runOnUiThread { host.showGreenTarget.value = true }
                    val restoredJpeg = awaitJpeg(
                        host.port, "rotation-restored-green", "fresh decoded progress after restoring orientation",
                        details = { "expectedPlatform=$initialDisplay; actualPlatform=${displayGeometry(this)}" },
                    ) { jpeg ->
                        dimensions(jpeg) == (initialDisplay.width to initialDisplay.height) && matchesTarget(jpeg, green = true)
                    }
                    stream.awaitFrame("fresh restored target on the same multipart connection") {
                        dimensions(it) == (initialDisplay.width to initialDisplay.height) && matchesTarget(it, green = true)
                    }
                    saveJpegEvidence("rotation-restored-green", restoredJpeg)
                    assertEquals(attempt, runningModule(host)?.status?.captureAttempt)
                    assertEquals(submissions, host.systemConsentAttempts.toList())
                    assertEquals(reuses, host.reusedConsentAttempts.toList())
                    exitAndAssertResourcesReleased(host, stream)
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                restoreRotationPolicy(this, originalAutoRotate, originalUserRotation, originalDisplay)
            } catch (restorationFailure: Throwable) {
                val failure = primaryFailure ?: throw restorationFailure
                failure.addSuppressed(restorationFailure)
            }
        }
    }

    private fun restoreRotationPolicy(
        support: MjpegDeviceTestSupport,
        originalAutoRotate: Int,
        originalUserRotation: Int,
        originalDisplay: DisplayGeometry,
    ) {
        val failures = mutableListOf<Throwable>()
        fun attempt(description: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += AssertionError("Rotation restoration: $description", failure)
            }
        }

        val resolver = support.context.contentResolver
        val automation = support.instrumentation.uiAutomation
        attempt("locked rotation checkpoint") {
            check(automation.setRotation(originalUserRotation))
            support.compose.waitUntil(15_000) {
                Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == 0 &&
                        Settings.System.getInt(resolver, Settings.System.USER_ROTATION) == originalUserRotation &&
                        displayGeometry(support).rotation == originalUserRotation
            }
        }
        if (originalAutoRotate != 0) {
            attempt("restore automatic rotation") {
                check(automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            }
        }
        // Freeze/thaw updates are asynchronous in WindowManager. Preserve the saved user value
        // even if thaw used an older cached value; do not mutate the per-posture auto-rotate policy.
        attempt("restore saved user rotation value") {
            val command = "settings put --user current system ${Settings.System.USER_ROTATION} $originalUserRotation"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
                check(input.bufferedReader().readText().isBlank()) { "User rotation restoration returned output" }
            }
        }
        attempt("verify saved user rotation") {
            support.compose.waitUntil(15_000) {
                Settings.System.getInt(resolver, Settings.System.USER_ROTATION) == originalUserRotation
            }
        }
        attempt("verify saved automatic rotation policy") {
            support.compose.waitUntil(15_000) {
                Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == originalAutoRotate
            }
        }
        var finalSnapshot = "Final policy snapshot unavailable"
        attempt("verify saved policy and original display geometry together") {
            support.compose.waitUntil(15_000) {
                val actualAutoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
                val actualUserRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION)
                val actualDisplay = displayGeometry(support)
                finalSnapshot = "actualAutoRotate=$actualAutoRotate; actualUserRotation=$actualUserRotation; actualDisplay=$actualDisplay"
                actualAutoRotate == originalAutoRotate && actualUserRotation == originalUserRotation && actualDisplay == originalDisplay
            }
        }
        attempt("save restoration evidence") {
            support.saveTextEvidence("rotation-restoration", buildString {
                appendLine("expectedAutoRotate=$originalAutoRotate; expectedUserRotation=$originalUserRotation; expectedDisplay=$originalDisplay")
                appendLine(finalSnapshot)
                appendLine("restorationFailures=${failures.map { it.message }}")
            })
        }
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)

    @Suppress("DEPRECATION")
    private fun displayGeometry(support: MjpegDeviceTestSupport): DisplayGeometry {
        val display = checkNotNull(support.context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        check(display.isValid)
        val size = Point()
        display.getRealSize(size)
        check(size.x > 0 && size.y > 0)
        return DisplayGeometry(size.x, size.y, display.rotation)
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }
}
