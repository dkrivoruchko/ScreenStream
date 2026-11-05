package io.screenstream.mjpeg.device

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.Companion.matchesTarget
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.MultipartViewer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real consent, production Service/Engine and CIO in the library's own test APK. */
@RunWith(AndroidJUnit4::class)
internal class MjpegCapturePipelineTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Cases: MJPEG-CTL-16
    @Test
    fun changingCapturedFramesSurviveStopRestartAndExitReleasesTheServiceAndSocket() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            val port = host.port
            val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            assertArrayEquals("Inactive /jpeg must contain the production placeholder", logo, requestJpeg(port))
            MultipartViewer(port).use { stream ->
                assertArrayEquals("Inactive multipart must contain the production placeholder", logo, stream.nextFrame())

                val firstAttempt = startCapture(host)
                val redJpeg = awaitJpegTarget(port, green = false)
                assertTrue("/jpeg must decode the visible red/blue/white target", matchesTarget(redJpeg, green = false))
                stream.awaitTarget(green = false)

                compose.runOnUiThread { host.greenTarget.value = true }
                val greenJpeg = awaitJpegTarget(port, green = true)
                assertFalse("Changing the Activity must change decoded /jpeg output", redJpeg.contentEquals(greenJpeg))
                saveJpegEvidence("red-target", redJpeg)
                saveJpegEvidence("green-target", greenJpeg)
                stream.awaitTarget(green = true)

                val statistics = MjpegStatisticsUi(compose, context)
                statistics.expand()
                val firstTotals = awaitLiveStatistics(host, firstAttempt, statistics, minimumProduced = 2)
                val laterTotals = awaitLiveStatistics(host, firstAttempt, statistics, minimumProduced = firstTotals.produced + 1)
                saveTextEvidence("live-statistics", "first=$firstTotals; later=$laterTotals\n")

                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_stop_capture)).performClick()
                await(host, "capture stop") { running(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true }
                await(host, "post-stop placeholder") { requestJpeg(port).contentEquals(logo) }
                stream.awaitFrame("post-stop placeholder") { it.contentEquals(logo) }
                assertTrue("Stop must preserve the production Service", serviceIsRunning(context))
                assertTrue("Stop must preserve the listener", canConnect(port))

                await(host, "stopped frame-rate chart is unavailable") { statistics.currentFps() == null }
                val stoppedTotals = statistics.readTotals()
                assertTrue("Stop must retain the last live Produced lower bound", stoppedTotals.produced >= laterTotals.produced)
                assertTrue("Stop must retain the last live Encoded lower bound", stoppedTotals.encoded >= laterTotals.encoded)
                saveTextEvidence("stopped-statistics", "$stoppedTotals; currentFps=unavailable\n")

                compose.runOnUiThread { host.greenTarget.value = false }
                val secondAttempt = startCapture(host)
                assertNotEquals("Restart must own a new attempt", firstAttempt, secondAttempt)
                awaitJpegTarget(port, green = false)
                stream.awaitTarget(green = false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    assertEquals("API34+ needs a fresh system consent per capture", 2, host.submittedConsents.size)
                    assertTrue(host.reusedConsents.isEmpty())
                } else {
                    assertEquals(2, host.submittedConsents.size + host.reusedConsents.size)
                    assertEquals("The first capture must obtain real system consent", firstAttempt, host.submittedConsents.first())
                }

                assertExitAndReleased(host, stream, expectedInstanceId = secondAttempt.instanceId)
            }
        }
    }

    private fun MjpegDeviceTestSupport.awaitLiveStatistics(
        host: MjpegCaptureTestHost,
        attempt: StreamingModule.CaptureAttemptId,
        statistics: MjpegStatisticsUi,
        minimumProduced: Long,
    ): MjpegStatisticsUi.Totals {
        var totals: MjpegStatisticsUi.Totals? = null
        var green = !host.greenTarget.value
        compose.runOnUiThread { host.greenTarget.value = green }
        // Engine snapshots are activity-driven, not timer-driven. Keep changing the actual source
        // until a fresh decoded target and the later visible statistics establish this checkpoint.
        await(host, "live Produced >= $minimumProduced, Encoded >= 2 and an available frame-rate chart") {
            assertEquals(attempt, running(host)?.status?.captureAttempt)
            assertTrue("Statistics checkpoints require the same live capture", running(host)?.status?.isStreaming == true)
            if (!matchesTarget(requestJpeg(host.port), green)) return@await false
            val currentFps = statistics.currentFps()
            val observed = statistics.readTotals()
            if (observed.produced >= minimumProduced && observed.encoded >= 2 && currentFps != null) {
                totals = observed
                true
            } else {
                green = !green
                compose.runOnUiThread { host.greenTarget.value = green }
                false
            }
        }
        return checkNotNull(totals)
    }
}
