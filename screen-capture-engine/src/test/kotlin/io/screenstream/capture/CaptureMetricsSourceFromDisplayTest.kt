package io.screenstream.capture

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import java.lang.AutoCloseable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class CaptureMetricsSourceFromDisplayTest {
    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N])
    fun fixedDisplayUsesNormalizedContextAndCloseFencesOneOfTwoObservers() {
        val application: Application = RuntimeEnvironment.getApplication()
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, DEFAULT_DISPLAY_QUALIFIERS)
        val displayManager = checkNotNull(application.getSystemService(DisplayManager::class.java))
        val fixedDisplayId = ShadowDisplayManager.addDisplay(FIXED_DISPLAY_QUALIFIERS)
        val fixedDisplay = checkNotNull(displayManager.getDisplay(fixedDisplayId))
        assertEquals(DEFAULT_DENSITY_DPI, application.resources.configuration.densityDpi)
        assertEquals(
            FIXED_DENSITY_DPI,
            application.createDisplayContext(fixedDisplay).resources.configuration.densityDpi,
        )

        val callerContext = RejectingAfterNormalizationContext(application)
        val source = CaptureMetricsSource.fromDisplay(callerContext, fixedDisplay)
        callerContext.rejectFurtherInteraction()
        val firstObserver = BoundedObserver()
        val survivingObserver = BoundedObserver()
        val workerFailures = WorkerFailureCapture()
        val firstHandle = source.subscribe(firstObserver)
        val survivingHandle = source.subscribe(survivingObserver)

        try {
            val expectedInitial = CaptureMetrics(widthPx = 960, heightPx = 720, densityDpi = FIXED_DENSITY_DPI)
            assertEquals(expectedInitial, firstObserver.awaitPositive(workerFailures))
            assertEquals(expectedInitial, survivingObserver.awaitPositive(workerFailures))

            firstHandle.close()
            ShadowDisplayManager.changeDisplay(fixedDisplayId, CHANGED_FIXED_DISPLAY_QUALIFIERS)
            shadowOf(Looper.getMainLooper()).idle()

            val expectedChanged = CaptureMetrics(widthPx = 1280, heightPx = 960, densityDpi = CHANGED_DENSITY_DPI)
            assertEquals(expectedChanged, survivingObserver.awaitPositive(workerFailures))
            assertEquals(listOf(expectedInitial), firstObserver.changes())
            assertEquals(listOf(expectedInitial, expectedChanged), survivingObserver.changes())
            assertTrue(firstObserver.failures().isEmpty())
            assertTrue(survivingObserver.failures().isEmpty())
            workerFailures.throwIfCaptured()
        } finally {
            firstHandle.close()
            survivingHandle.close()
            workerFailures.close()
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N])
    fun constructionRejectsUnavailableContextServiceInvalidAndUnassociatedDisplay() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = checkNotNull(application.getSystemService(DisplayManager::class.java))
        val displayId = ShadowDisplayManager.addDisplay(FIXED_DISPLAY_QUALIFIERS)
        val display = checkNotNull(displayManager.getDisplay(displayId))

        val absentApplicationContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context? = null
        }
        assertEquals(
            "context.applicationContext must be available",
            assertThrows(IllegalArgumentException::class.java) {
                CaptureMetricsSource.fromDisplay(absentApplicationContext, display)
            }.message,
        )

        val absentDisplayManagerContext = DisplayManagerApplicationContext(application, displayManager = null)
        assertEquals(
            "DisplayManager must be available",
            assertThrows(IllegalArgumentException::class.java) {
                CaptureMetricsSource.fromDisplay(absentDisplayManagerContext, display)
            }.message,
        )

        ShadowDisplayManager.removeDisplay(displayId)
        assertFalse(display.isValid)
        assertEquals(
            "display must be valid",
            assertThrows(IllegalArgumentException::class.java) {
                CaptureMetricsSource.fromDisplay(application, display)
            }.message,
        )

        val unassociatedId = ShadowDisplayManager.addDisplay(FIXED_DISPLAY_QUALIFIERS)
        val unassociatedDisplay = checkNotNull(displayManager.getDisplay(unassociatedId))
        assertTrue(unassociatedDisplay.isValid)
        val unassociatedManager = mockk<DisplayManager> {
            every { getDisplay(unassociatedId) } returns null
        }
        val unassociatedContext = DisplayManagerApplicationContext(application, unassociatedManager)
        assertEquals(
            "display must be associated with the application DisplayManager",
            assertThrows(IllegalArgumentException::class.java) {
                CaptureMetricsSource.fromDisplay(unassociatedContext, unassociatedDisplay)
            }.message,
        )
    }

    private class RejectingAfterNormalizationContext(
        private val normalizedApplication: Context,
    ) : ContextWrapper(normalizedApplication) {
        @Volatile
        private var rejected = false

        override fun getApplicationContext(): Context {
            check(!rejected) { "caller context was consulted after normalization" }
            return normalizedApplication
        }

        override fun getSystemService(name: String): Any? {
            error("caller context service lookup must be normalized")
        }

        override fun createDisplayContext(display: Display): Context {
            error("caller context display lookup must be normalized")
        }

        fun rejectFurtherInteraction() {
            rejected = true
        }
    }

    private class DisplayManagerApplicationContext(
        base: Context,
        private val displayManager: DisplayManager?,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? =
            if (name == DISPLAY_SERVICE) displayManager else super.getSystemService(name)
    }

    private class BoundedObserver : CaptureMetricsSource.Observer {
        private val gate = Any()
        private val recordedChanges = ArrayList<CaptureMetrics?>()
        private val recordedFailures = ArrayList<Throwable>()
        private val callbacks = LinkedBlockingQueue<Callback>()

        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            synchronized(gate) {
                recordedChanges += metrics
            }
            if (metrics == null) {
                callbacks.add(Callback.Unavailable)
            } else {
                callbacks.add(Callback.Available(metrics))
            }
        }

        override fun onComplete() {
            callbacks.add(Callback.Complete)
        }

        override fun onFailure(cause: Throwable) {
            synchronized(gate) {
                recordedFailures += cause
            }
            callbacks.add(Callback.Failure(cause))
        }

        fun awaitPositive(workerFailures: WorkerFailureCapture): CaptureMetrics {
            val callback = callbacks.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (callback == null) {
                workerFailures.throwIfCaptured()
                error("timed out waiting for a positive Metrics callback")
            }
            return when (callback) {
                is Callback.Available -> callback.metrics
                is Callback.Failure -> throw AssertionError("Metrics observation failed", callback.cause)
                Callback.Complete -> error("Metrics observation completed before publishing")
                Callback.Unavailable -> error("Metrics observation published unavailable")
            }
        }

        fun changes(): List<CaptureMetrics?> = synchronized(gate) { recordedChanges.toList() }

        fun failures(): List<Throwable> = synchronized(gate) { recordedFailures.toList() }
    }

    private sealed interface Callback {
        data class Available(val metrics: CaptureMetrics) : Callback

        data class Failure(val cause: Throwable) : Callback

        data object Complete : Callback

        data object Unavailable : Callback
    }

    private class WorkerFailureCapture : AutoCloseable {
        private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        private val captured = AtomicReference<Throwable?>()

        init {
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                if (thread.name.startsWith(WORKER_THREAD_PREFIX)) {
                    captured.compareAndSet(null, failure)
                } else {
                    previousHandler?.uncaughtException(thread, failure)
                }
            }
        }

        fun throwIfCaptured() {
            captured.get()?.let { throw AssertionError("Screen Capture Engine worker failed", it) }
        }

        override fun close() {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val DEFAULT_DISPLAY_QUALIFIERS = "w360dp-h640dp-mdpi"
        const val FIXED_DISPLAY_QUALIFIERS = "w640dp-h480dp-hdpi"
        const val CHANGED_FIXED_DISPLAY_QUALIFIERS = "w640dp-h480dp-xhdpi"
        const val DEFAULT_DENSITY_DPI = 160
        const val FIXED_DENSITY_DPI = 240
        const val CHANGED_DENSITY_DPI = 320
        const val TIMEOUT_SECONDS = 5L
        const val WORKER_THREAD_PREFIX = "ScreenCaptureEngine-Worker-"
    }
}
