package io.screenstream.mjpeg.device

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.MultipartViewer
import io.screenstream.mjpeg.settings.ImageSettings
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real Android result dispatch and projection termination, with production MJPEG owners. */
@RunWith(AndroidJUnit4::class)
internal class MjpegLifecycleDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun declinedSystemConsentKeepsIdleHttpAndAllowsFreshCapture() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            MultipartViewer(host.port).use { stream ->
                assertContentEquals(logo, stream.nextFrame())
                val declined = submitCapture(host)
                assertTrue(declined.usesSystemConsent, "First attempt must submit real Android consent")
                awaitConsentDialog()
                declineSystemConsent()
                awaitHostCondition(host, "real negative Activity result receipt") {
                    host.consentResultReceipts.any { it.attempt == declined.attempt && it.resultCode == Activity.RESULT_CANCELED }
                }
                awaitHostCondition(host, "declined attempt cleanup") {
                    runningModule(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true
                }
                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_start_capture)).assertIsEnabled()
                assertTrue(serviceIsRunning())
                assertTrue(canConnect(host.port))
                assertContentEquals(logo, requestJpeg(host.port))
                stream.awaitFrame("placeholder after denied consent") { it.contentEquals(logo) }

                val accepted = startCapture(host)
                assertNotEquals(declined.attempt, accepted)
                assertEquals(2, host.systemConsentAttempts.size)
                assertTrue(host.reusedConsentAttempts.isEmpty())
                awaitJpegTarget(host.port, green = false)
                stream.awaitTarget(green = false)
                exitAndAssertResourcesReleased(host, stream)
            }
        }
    }

    @Test
    fun lateConsentResultCannotRestartExitedInstanceAndNewInstanceCaptures() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            MultipartViewer(host.port).use { stream ->
                stream.nextFrame()
                val pending = submitCapture(host)
                assertTrue(pending.usesSystemConsent, "First attempt must submit real Android consent")
                awaitConsentDialog()
                awaitHostCondition(host, "Android consent launch request code") { host.consentLaunchReceipts.any { it.attempt == pending.attempt } }
                assertTrue(host.consentResultReceipts.none { it.attempt == pending.attempt })
                exitAndAssertResourcesReleased(host, stream)
                val exitedAt = SystemClock.elapsedRealtime()
                check(isConsentDialogVisible()) {
                    "Exit and resource shutdown were established, but Android dismissed the consent dialog. " +
                            "Late-approved-result coverage is UNQUALIFIED on this runtime; no old approval was delivered."
                }
                approveSystemConsent()
                awaitHostCondition(host, "old RESULT_OK received after Exit and forwarded through ActivityResultRegistry") {
                    host.consentResultReceipts.any {
                        it.attempt == pending.attempt && it.resultCode == Activity.RESULT_OK && it.receivedAtElapsedMillis >= exitedAt
                    }
                }
                val observationEnd = SystemClock.elapsedRealtime() + 2_000
                awaitHostCondition(host, "bounded non-resurrection after actual old result receipt") {
                    assertIs<StreamingModuleManager.State.Exiting>(host.manager.state.value, "Late result changed the exited module state")
                    assertFalse(serviceIsRunning(), "Late result restarted the Service")
                    assertFalse(canConnect(host.port), "Late result reopened the listener")
                    SystemClock.elapsedRealtime() >= observationEnd
                }
                assertPortReusable(host.port)
                awaitHostCondition(host, "new instance after completed Exit") {
                    compose.runOnUiThread { host.manager.selectModule(StreamingModule.Id("MJPEG")) }
                    runningModule(host)?.instanceId?.let { it != pending.attempt.instanceId } == true
                }
                awaitHostCondition(host, "reopened listener") { canConnect(host.port) }
                val next = startCapture(host)
                assertNotEquals(pending.attempt.instanceId, next.instanceId)
                assertEquals(2, host.systemConsentAttempts.size)
                assertTrue(host.reusedConsentAttempts.isEmpty())
                awaitJpegTarget(host.port, green = false)
                MultipartViewer(host.port).use { reopened ->
                    reopened.awaitTarget(green = false)
                    exitAndAssertResourcesReleased(host, reopened)
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun systemProjectionChipStopRestoresIdleHttpAndRestartRequiresFreshConsent() = with(MjpegDeviceTestSupport(compose)) {
        qualifySystemChipSelector()
        // Isolate OS termination from the independently retained default-50% scaling baseline.
        openHost(imageSettings = ImageSettings(scalePercent = 100.0)).use { fixture ->
            val host = fixture.host
            val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            val first = startCapture(host)
            awaitJpegTarget(host.port, green = false)
            MultipartViewer(host.port).use { stream ->
                stream.awaitTarget(green = false)
                // The fixture explicitly disables MJPEG's ACTION_SCREEN_OFF automation and selects Placeholder.
                stopProjectionUsingSystemChip(this)
                awaitHostCondition(host, "OS projection stop propagated through real Engine and MJPEG cleanup") {
                    runningModule(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true
                }
                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_start_capture)).assertIsEnabled()
                assertTrue(serviceIsRunning(), "System projection stop must preserve the module Service")
                assertTrue(canConnect(host.port), "System projection stop must preserve the HTTP listener")
                awaitHostCondition(host, "post-system-stop JPEG placeholder") { requestJpeg(host.port).contentEquals(logo) }
                stream.awaitFrame("post-system-stop multipart placeholder") { it.contentEquals(logo) }
                compose.runOnUiThread { host.showGreenTarget.value = true }
                assertContentEquals(logo, requestJpeg(host.port), "Post-stop target changes must not become live images")
                stream.awaitFrame("placeholder after target changed while stopped") { it.contentEquals(logo) }

                val next = startCapture(host)
                assertNotEquals(first, next)
                assertEquals(2, host.systemConsentAttempts.size, "OS-revoked projection cannot offer reusable consent")
                assertTrue(host.reusedConsentAttempts.isEmpty())
                awaitJpegTarget(host.port, green = true)
                stream.awaitTarget(green = true)
                exitAndAssertResourcesReleased(host, stream)
            }
        }
    }

    private fun qualifySystemChipSelector() {
        if (Build.VERSION.SDK_INT != 35) return
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.getString("mjpeg.systemStopChipResource").isNullOrBlank() ||
            !arguments.getString("mjpeg.systemStopChipDescription").isNullOrBlank()
        ) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = context.packageManager.getResourcesForApplication("com.android.systemui")
        assumeTrue(
            "SystemUI chip selector unavailable; this API 35 runtime is unqualified for system-stop coverage",
            resources.getIdentifier("share_to_app_chip_accessibility_label", "string", "com.android.systemui") != 0,
        )
    }

    private fun stopProjectionUsingSystemChip(support: MjpegDeviceTestSupport) {
        support.saveUiEvidence("projection-active-before-system-chip")
        val arguments = InstrumentationRegistry.getArguments()
        val chipResource = arguments.getString("mjpeg.systemStopChipResource")
        val chipDescription = arguments.getString("mjpeg.systemStopChipDescription")
        val selector = when {
            !chipResource.isNullOrBlank() -> By.res(chipResource).pkg("com.android.systemui")
            !chipDescription.isNullOrBlank() -> By.desc(chipDescription).pkg("com.android.systemui")
            else -> {
                val systemUiResources = support.context.packageManager.getResourcesForApplication("com.android.systemui")
                val id = systemUiResources.getIdentifier("share_to_app_chip_accessibility_label", "string", "com.android.systemui")
                check(id != 0) { "Qualify this SystemUI chip selector and supply mjpeg.systemStopChipResource or mjpeg.systemStopChipDescription" }
                By.desc(systemUiResources.getString(id)).pkg("com.android.systemui")
            }
        }
        checkNotNull(support.device.wait(Until.findObject(selector), 10_000)) {
            "Real projection status chip was not found; runtime/selectors are unqualified"
        }.click()
        checkNotNull(support.device.wait(Until.findObject(By.res("android:id/button1").pkg("com.android.systemui")), 5_000)) {
            "SystemUI projection-stop confirmation did not appear"
        }.click()
    }
}
