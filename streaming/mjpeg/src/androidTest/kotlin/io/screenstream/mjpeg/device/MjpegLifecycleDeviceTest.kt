package io.screenstream.mjpeg.device

import android.app.Activity
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
                assertArrayEquals(logo, stream.nextFrame())
                val declined = submitCapture(host)
                assertTrue("First attempt must submit real Android consent", declined.usesSystemConsent)
                awaitConsentDialog()
                declineSystemConsent()
                await(host, "real negative Activity result receipt") {
                    host.consentResults.any { it.attempt == declined.attempt && it.resultCode == Activity.RESULT_CANCELED }
                }
                await(host, "declined attempt cleanup") {
                    running(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true
                }
                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_start_capture)).assertIsEnabled()
                assertTrue(serviceIsRunning())
                assertTrue(canConnect(host.port))
                assertArrayEquals(logo, requestJpeg(host.port))
                stream.awaitFrame("placeholder after denied consent") { it.contentEquals(logo) }

                val accepted = startCapture(host)
                assertNotEquals(declined.attempt, accepted)
                assertEquals(2, host.submittedConsents.size)
                assertTrue(host.reusedConsents.isEmpty())
                awaitJpegTarget(host.port, green = false)
                stream.awaitTarget(green = false)
                assertExitAndReleased(host, stream)
            }
        }
    }

    @Test
    fun lateSystemApprovalAfterExitCannotResurrectRetiredAttemptAndReopeningCaptures() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            MultipartViewer(host.port).use { stream ->
                stream.nextFrame()
                val pending = submitCapture(host)
                assertTrue("First attempt must submit real Android consent", pending.usesSystemConsent)
                awaitConsentDialog()
                await(host, "Android consent launch request code") { host.consentLaunches.any { it.attempt == pending.attempt } }
                assertTrue(host.consentResults.none { it.attempt == pending.attempt })
                assertExitAndReleased(host, stream)
                val exitedAt = SystemClock.elapsedRealtime()
                check(consentDialogVisible()) {
                    "Exit and resource shutdown were established, but Android dismissed the consent dialog. " +
                            "Late-approved-result coverage is UNQUALIFIED on this runtime; no old approval was delivered."
                }
                approveSystemConsent()
                await(host, "old RESULT_OK received after Exit and forwarded through ActivityResultRegistry") {
                    host.consentResults.any {
                        it.attempt == pending.attempt && it.resultCode == Activity.RESULT_OK && it.receivedAtElapsedMillis >= exitedAt
                    }
                }
                val observationEnd = SystemClock.elapsedRealtime() + 2_000
                await(host, "bounded non-resurrection after actual old result receipt") {
                    assertTrue("Late result changed the exited module state", host.manager.state.value is StreamingModuleManager.State.Exiting)
                    assertFalse("Late result restarted the Service", serviceIsRunning())
                    assertFalse("Late result reopened the listener", canConnect(host.port))
                    SystemClock.elapsedRealtime() >= observationEnd
                }
                assertPortReusable(host.port)
                await(host, "new instance after completed Exit") {
                    compose.runOnUiThread { host.manager.selectModule(StreamingModule.Id("MJPEG")) }
                    running(host)?.instanceId?.let { it != pending.attempt.instanceId } == true
                }
                await(host, "reopened listener") { canConnect(host.port) }
                val next = startCapture(host)
                assertNotEquals(pending.attempt.instanceId, next.instanceId)
                assertEquals(2, host.submittedConsents.size)
                assertTrue(host.reusedConsents.isEmpty())
                awaitJpegTarget(host.port, green = false)
                MultipartViewer(host.port).use { reopened ->
                    reopened.awaitTarget(green = false)
                    assertExitAndReleased(host, reopened)
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun systemProjectionChipStopRestoresIdleHttpAndRestartRequiresFreshConsent() = with(MjpegDeviceTestSupport(compose)) {
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
                await(host, "OS projection stop propagated through real Engine and MJPEG cleanup") {
                    running(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true
                }
                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_start_capture)).assertIsEnabled()
                assertTrue("System projection stop must preserve the module Service", serviceIsRunning())
                assertTrue("System projection stop must preserve the HTTP listener", canConnect(host.port))
                await(host, "post-system-stop JPEG placeholder") { requestJpeg(host.port).contentEquals(logo) }
                stream.awaitFrame("post-system-stop multipart placeholder") { it.contentEquals(logo) }
                compose.runOnUiThread { host.greenTarget.value = true }
                assertArrayEquals("Post-stop target changes must not become live images", logo, requestJpeg(host.port))
                stream.awaitFrame("placeholder after target changed while stopped") { it.contentEquals(logo) }

                val next = startCapture(host)
                assertNotEquals(first, next)
                assertEquals("OS-revoked projection cannot offer reusable consent", 2, host.submittedConsents.size)
                assertTrue(host.reusedConsents.isEmpty())
                awaitJpegTarget(host.port, green = true)
                stream.awaitTarget(green = true)
                assertExitAndReleased(host, stream)
            }
        }
    }

    private fun stopProjectionUsingSystemChip(support: MjpegDeviceTestSupport) {
        support.saveUiEvidence("projection-active-before-system-chip")
        val arguments = InstrumentationRegistry.getArguments()
        val resource = arguments.getString("mjpeg.systemStopChipResource")
        val description = arguments.getString("mjpeg.systemStopChipDescription")
        val selector = when {
            !resource.isNullOrBlank() -> By.res(resource).pkg("com.android.systemui")
            !description.isNullOrBlank() -> By.desc(description).pkg("com.android.systemui")
            else -> {
                val resources = support.context.packageManager.getResourcesForApplication("com.android.systemui")
                val id = resources.getIdentifier("share_to_app_chip_accessibility_label", "string", "com.android.systemui")
                check(id != 0) { "Qualify this SystemUI chip selector and supply mjpeg.systemStopChipResource or mjpeg.systemStopChipDescription" }
                By.desc(resources.getString(id)).pkg("com.android.systemui")
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
