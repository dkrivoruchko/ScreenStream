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
    fun realDefaultDisplayPublishesPositiveMetricsAndClosesIdempotently() {
        val applicationContext = checkNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        )
        val displayManager = checkNotNull(applicationContext.getSystemService(DisplayManager::class.java))
        val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        assertTrue(display.isValid)

        val dispatcher = QueuedNonInlineDispatcher()
        val observer = RecordingObserver()
        val handle = BuiltInCaptureMetricsSource
            .forFixedDisplay(applicationContext, display, dispatcher)
            .subscribe(observer)
        try {
            var acceptedWork = 0
            while ((observer.publications.none { it != null }) && observer.failures.isEmpty()) {
                check(acceptedWork < MAX_ACCEPTED_WORK) {
                    "Real-display Metrics did not produce a positive tuple or failure within the accepted-work bound"
                }
                check(dispatcher.pendingCount() > 0) {
                    "Real-display Metrics had no accepted work before producing a positive tuple or failure"
                }
                dispatcher.runNext()
                acceptedWork += 1
            }
            observer.failures.singleOrNull()?.let { failure ->
                throw AssertionError("Real-display Metrics failed before producing a positive tuple", failure)
            }
            val published = checkNotNull(observer.publications.lastOrNull { it != null })
            val publicationsBeforeClose = observer.publications.toList()
            handle.close()
            handle.close()
            dispatcher.drain()
            assertEquals(publicationsBeforeClose, observer.publications)
            assertTrue(published.widthPx > 0)
            assertTrue(published.heightPx > 0)
            assertTrue(published.densityDpi > 0)
            val currentDefaultDisplay = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
            assertEquals(display.displayId, currentDefaultDisplay.displayId)
            assertTrue(display.isValid)
            assertTrue(observer.failures.isEmpty())
            assertEquals(0, observer.completionCount)
            assertEquals(0, dispatcher.pendingCount())
        } finally {
            handle.close()
            dispatcher.drain()
        }
    }

    // Verification: MET-02
    @Test
    fun closeBeforeInitialRefreshSuppressesMetricsPublication() {
        val applicationContext = checkNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        )
        val displayManager = checkNotNull(applicationContext.getSystemService(DisplayManager::class.java))
        val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        assertTrue(display.isValid)

        val dispatcher = QueuedNonInlineDispatcher()
        val observer = RecordingObserver()
        val handle = BuiltInCaptureMetricsSource
            .forFixedDisplay(applicationContext, display, dispatcher)
            .subscribe(observer)
        try {
            assertEquals(1, dispatcher.pendingCount())
            handle.close()
            handle.close()
            dispatcher.runNext()
            assertTrue(observer.publications.isEmpty())
            assertTrue(observer.failures.isEmpty())
            assertEquals(0, observer.completionCount)
            assertEquals(0, dispatcher.pendingCount())
        } finally {
            handle.close()
            dispatcher.drain()
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
