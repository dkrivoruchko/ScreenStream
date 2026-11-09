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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real consent, production Service/Engine and CIO in the library's own test APK. */
@RunWith(AndroidJUnit4::class)
internal class MjpegCapturePipelineTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Cases: MJPEG-CTL-16
    @Test
    fun changingFramesResumeAfterRestartAndExitReleasesServiceAndSocket() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            val port = host.port
            val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            assertContentEquals(logo, requestJpeg(port), "Inactive /jpeg must contain the production placeholder")
            MultipartViewer(port).use { stream ->
                assertContentEquals(logo, stream.nextFrame(), "Inactive multipart must contain the production placeholder")

                val firstAttempt = startCapture(host)
                val redJpeg = awaitJpegTarget(port, green = false)
                assertTrue(matchesTarget(redJpeg, green = false), "/jpeg must decode the visible red/blue/white target")
                stream.awaitTarget(green = false)

                compose.runOnUiThread { host.showGreenTarget.value = true }
                val greenJpeg = awaitJpegTarget(port, green = true)
                assertFalse(redJpeg.contentEquals(greenJpeg), "Changing the Activity must change decoded /jpeg output")
                saveJpegEvidence("red-target", redJpeg)
                saveJpegEvidence("green-target", greenJpeg)
                stream.awaitTarget(green = true)

                val statistics = MjpegStatisticsUi(compose, context)
                statistics.expand()
                val firstTotals = awaitLiveStatistics(host, firstAttempt, statistics, minimumProduced = 2)
                val laterTotals = awaitLiveStatistics(host, firstAttempt, statistics, minimumProduced = firstTotals.produced + 1)
                saveTextEvidence("live-statistics", "first=$firstTotals; later=$laterTotals\n")

                compose.onNodeWithText(context.getString(R.string.mjpeg_stream_stop_capture)).performClick()
                awaitHostCondition(host, "capture stop") { runningModule(host)?.status?.let { !it.isStreaming && it.captureAttempt == null } == true }
                awaitHostCondition(host, "post-stop placeholder") { requestJpeg(port).contentEquals(logo) }
                stream.awaitFrame("post-stop placeholder") { it.contentEquals(logo) }
                assertTrue(serviceIsRunning(context), "Stop must preserve the production Service")
                assertTrue(canConnect(port), "Stop must preserve the listener")

                awaitHostCondition(host, "stopped frame-rate chart is unavailable") { statistics.readCurrentFps() == null }
                val stoppedTotals = statistics.readFrameTotals()
                assertTrue(stoppedTotals.produced >= laterTotals.produced, "Stop must retain the last live Produced lower bound")
                assertTrue(stoppedTotals.encoded >= laterTotals.encoded, "Stop must retain the last live Encoded lower bound")
                saveTextEvidence("stopped-statistics", "$stoppedTotals; currentFps=unavailable\n")

                compose.runOnUiThread { host.showGreenTarget.value = false }
                val secondAttempt = startCapture(host)
                assertNotEquals(firstAttempt, secondAttempt, "Restart must own a new attempt")
                awaitJpegTarget(port, green = false)
                stream.awaitTarget(green = false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    assertEquals(2, host.systemConsentAttempts.size, "API34+ needs a fresh system consent per capture")
                    assertTrue(host.reusedConsentAttempts.isEmpty())
                } else {
                    assertEquals(2, host.systemConsentAttempts.size + host.reusedConsentAttempts.size)
                    assertEquals(firstAttempt, host.systemConsentAttempts.first(), "The first capture must obtain real system consent")
                }

                exitAndAssertResourcesReleased(host, stream, expectedInstanceId = secondAttempt.instanceId)
            }
        }
    }

    private fun MjpegDeviceTestSupport.awaitLiveStatistics(
        host: MjpegCaptureTestHost,
        attempt: StreamingModule.CaptureAttemptId,
        statistics: MjpegStatisticsUi,
        minimumProduced: Long,
    ): MjpegStatisticsUi.FrameTotals {
        var totals: MjpegStatisticsUi.FrameTotals? = null
        var green = !host.showGreenTarget.value
        compose.runOnUiThread { host.showGreenTarget.value = green }
        // Engine snapshots are activity-driven, not timer-driven. Keep changing the actual source
        // until a fresh decoded target and the later visible statistics establish this checkpoint.
        awaitHostCondition(host, "live Produced >= $minimumProduced, Encoded >= 2 and an available frame-rate chart") {
            assertEquals(attempt, runningModule(host)?.status?.captureAttempt)
            assertEquals(true, runningModule(host)?.status?.isStreaming, "Statistics checkpoints require the same live capture")
            if (!matchesTarget(requestJpeg(host.port), green = green)) return@awaitHostCondition false
            val currentFps = statistics.readCurrentFps()
            val observed = statistics.readFrameTotals()
            if (observed.produced >= minimumProduced && observed.encoded >= 2 && currentFps != null) {
                totals = observed
                true
            } else {
                green = !green
                compose.runOnUiThread { host.showGreenTarget.value = green }
                false
            }
        }
        return assertNotNull(totals)
    }
}
