package io.screenstream.capture.internal.metrics

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.testutil.QueuedNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class BuiltInCaptureMetricsRealDisplayTest {
    // Verification: MET-02
    @Test
    fun realDefaultDisplayPublishesPositiveCurrentTupleAndCloseFencesAcceptedRefresh() {
        val applicationContext = checkNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        )
        val displayManager = checkNotNull(applicationContext.getSystemService(DisplayManager::class.java))
        val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        assertTrue(display.isValid)

        val firstDispatcher = QueuedNonInlineDispatcher()
        val firstObserver = RecordingObserver()
        val firstHandle = BuiltInCaptureMetricsSource
            .forFixedDisplay(applicationContext, display, firstDispatcher)
            .subscribe(firstObserver)
        try {
            var acceptedWork = 0
            while ((firstObserver.publications.none { it != null }) && firstObserver.failures.isEmpty()) {
                check(acceptedWork < MAX_ACCEPTED_WORK) {
                    "Real-display Metrics did not produce a positive tuple or failure within the accepted-work bound"
                }
                check(firstDispatcher.pendingCount() > 0) {
                    "Real-display Metrics had no accepted work before producing a positive tuple or failure"
                }
                firstDispatcher.runNext()
                acceptedWork += 1
            }
            firstObserver.failures.singleOrNull()?.let { failure ->
                throw AssertionError("Real-display Metrics failed before producing a positive tuple", failure)
            }
            val published = checkNotNull(firstObserver.publications.lastOrNull { it != null })
            val publicationsBeforeClose = firstObserver.publications.toList()
            firstHandle.close()
            firstHandle.close()
            firstDispatcher.drain()
            assertEquals(publicationsBeforeClose, firstObserver.publications)
            assertTrue(published.widthPx > 0)
            assertTrue(published.heightPx > 0)
            assertTrue(published.densityDpi > 0)
            val currentDefaultDisplay = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
            assertEquals(display.displayId, currentDefaultDisplay.displayId)
            assertTrue(display.isValid)
            assertTrue(firstObserver.failures.isEmpty())
            assertEquals(0, firstObserver.completionCount)
            assertEquals(0, firstDispatcher.pendingCount())
        } finally {
            firstHandle.close()
            firstDispatcher.drain()
        }

        val fencedDispatcher = QueuedNonInlineDispatcher()
        val fencedObserver = RecordingObserver()
        val fencedHandle = BuiltInCaptureMetricsSource
            .forFixedDisplay(applicationContext, display, fencedDispatcher)
            .subscribe(fencedObserver)
        try {
            assertEquals(1, fencedDispatcher.pendingCount())
            fencedHandle.close()
            fencedHandle.close()
            fencedDispatcher.runNext()
            assertTrue(fencedObserver.publications.isEmpty())
            assertTrue(fencedObserver.failures.isEmpty())
            assertEquals(0, fencedObserver.completionCount)
            assertEquals(0, fencedDispatcher.pendingCount())
        } finally {
            fencedHandle.close()
            fencedDispatcher.drain()
        }
    }

    private class RecordingObserver : CaptureMetricsSource.Observer {
        val publications: MutableList<CaptureMetrics?> = ArrayList()
        val failures: MutableList<Throwable> = ArrayList()
        var completionCount: Int = 0

        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            publications.add(metrics)
        }

        override fun onComplete() {
            completionCount += 1
        }

        override fun onFailure(cause: Throwable) {
            failures.add(cause)
        }
    }

    private companion object {
        const val MAX_ACCEPTED_WORK = 16
    }
}
