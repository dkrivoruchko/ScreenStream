package io.screenstream.capture.internal.metrics

import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class BuiltInCaptureMetricsSourceLifecycleTest {
    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.R])
    fun fixedDisplayPublishesInitialChangedAndUnavailableMetrics() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = displayManager(application)
        val displayId = ShadowDisplayManager.addDisplay(INITIAL_QUALIFIERS)
        val display = checkNotNull(displayManager.getDisplay(displayId))
        val observer = RecordingObserver()

        ControlledNonInlineDispatcher().use { dispatcher ->
            val source = BuiltInCaptureMetricsSource.forFixedDisplay(application, display, dispatcher)
            val handle = source.subscribe(observer)

            enterOne(dispatcher)
            assertEquals(listOf(INITIAL_METRICS), observer.changes())
            assertNull(observer.failure())

            ShadowDisplayManager.changeDisplay(displayId, CHANGED_QUALIFIERS)
            enterOne(dispatcher)
            assertEquals(listOf(INITIAL_METRICS, CHANGED_METRICS), observer.changes())
            assertNull(observer.failure())

            ShadowDisplayManager.removeDisplay(displayId)
            drain(dispatcher)
            assertEquals(listOf(INITIAL_METRICS, CHANGED_METRICS, null), observer.changes())
            assertNull(observer.failure())

            handle.close()
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.BAKLAVA])
    fun windowContextsPublishAvailabilitySequence() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = displayManager(application)
        val displayId = ShadowDisplayManager.addDisplay(INITIAL_QUALIFIERS)
        val display = checkNotNull(displayManager.getDisplay(displayId))
        val observer = RecordingObserver()

        // Robolectric models the display-context density, but not qualifier-derived maximum WindowContext bounds.
        ControlledNonInlineDispatcher().use { dispatcher ->
            val handle = BuiltInCaptureMetricsSource.forFixedDisplay(application, display, dispatcher).subscribe(observer)

            enterOne(dispatcher)
            assertEquals(listOf(true), observer.changes().map { it != null })
            assertEquals(listOf(DENSITY_DPI), observer.changes().mapNotNull { it?.densityDpi })

            ShadowDisplayManager.changeDisplay(displayId, CHANGED_QUALIFIERS)
            enterOne(dispatcher)
            assertEquals(listOf(true, true), observer.changes().map { it != null })
            assertEquals(listOf(DENSITY_DPI, DENSITY_DPI), observer.changes().mapNotNull { it?.densityDpi })

            ShadowDisplayManager.removeDisplay(displayId)
            drain(dispatcher)
            assertEquals(listOf(true, true, false), observer.changes().map { it != null })
            assertNull(observer.failure())

            handle.close()
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun fixedAndDefaultSourcesFollowSelectedDisplay() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = displayManager(application)
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, DEFAULT_INITIAL_QUALIFIERS)
        val fixedDisplayId = ShadowDisplayManager.addDisplay(INITIAL_QUALIFIERS)
        val fixedDisplay = checkNotNull(displayManager.getDisplay(fixedDisplayId))
        val defaultObserver = RecordingObserver()
        val fixedObserver = RecordingObserver()

        ControlledNonInlineDispatcher().use { defaultDispatcher ->
            ControlledNonInlineDispatcher().use { fixedDispatcher ->
                val defaultHandle = BuiltInCaptureMetricsSource
                    .forDefaultDisplay(application, defaultDispatcher)
                    .subscribe(defaultObserver)
                val fixedHandle = BuiltInCaptureMetricsSource
                    .forFixedDisplay(application, fixedDisplay, fixedDispatcher)
                    .subscribe(fixedObserver)

                enterOne(defaultDispatcher)
                enterOne(fixedDispatcher)
                assertEquals(1, defaultObserver.changes().size)
                assertNotNull(defaultObserver.changes().single())
                assertEquals(1, fixedObserver.changes().size)
                assertNotNull(fixedObserver.changes().single())

                ShadowDisplayManager.changeDisplay(fixedDisplayId, CHANGED_QUALIFIERS)
                enterOne(fixedDispatcher)
                assertEquals(0, defaultDispatcher.pendingCount())
                assertEquals(1, defaultObserver.changes().size)
                assertEquals(2, fixedObserver.changes().size)
                assertNotNull(fixedObserver.changes().last())

                ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, DEFAULT_CHANGED_QUALIFIERS)
                enterOne(defaultDispatcher)
                assertEquals(0, fixedDispatcher.pendingCount())
                assertEquals(2, defaultObserver.changes().size)
                assertNotNull(defaultObserver.changes().last())
                assertEquals(2, fixedObserver.changes().size)

                defaultHandle.close()
                fixedHandle.close()
            }
        }
    }

    // Verification: MET-02
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
    fun repeatedCloseFencesWorkAndDisplayChanges() {
        val application: Application = RuntimeEnvironment.getApplication()
        val displayManager = displayManager(application)
        val displayId = ShadowDisplayManager.addDisplay(INITIAL_QUALIFIERS)
        val display = checkNotNull(displayManager.getDisplay(displayId))
        val observer = RecordingObserver()

        ControlledNonInlineDispatcher().use { dispatcher ->
            val handle = BuiltInCaptureMetricsSource
                .forFixedDisplay(application, display, dispatcher)
                .subscribe(observer)
            assertEquals(1, dispatcher.pendingCount())

            handle.close()
            handle.close()
            ShadowDisplayManager.changeDisplay(displayId, CHANGED_QUALIFIERS)
            assertEquals(1, dispatcher.pendingCount())

            enterOne(dispatcher)
            assertEquals(emptyList<CaptureMetrics?>(), observer.changes())
            assertNull(observer.failure())
            assertEquals(0, dispatcher.pendingCount())
        }
    }

    private fun displayManager(application: Application): DisplayManager =
        checkNotNull(application.getSystemService(DisplayManager::class.java))

    private fun enterOne(dispatcher: ControlledNonInlineDispatcher) {
        val task = dispatcher.enterNext() ?: error("expected an accepted built-in Metrics task")
        task.awaitSuccessfulCompletion()
    }

    private fun drain(dispatcher: ControlledNonInlineDispatcher) {
        while (dispatcher.pendingCount() > 0) enterOne(dispatcher)
    }

    private class RecordingObserver : CaptureMetricsSource.Observer {
        private val gate = Any()
        private val recordedChanges = ArrayList<CaptureMetrics?>()
        private var recordedFailure: Throwable? = null

        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            synchronized(gate) {
                recordedChanges += metrics
            }
        }

        override fun onComplete() = Unit

        override fun onFailure(cause: Throwable) {
            synchronized(gate) {
                recordedFailure = cause
            }
        }

        fun changes(): List<CaptureMetrics?> = synchronized(gate) { recordedChanges.toList() }

        fun failure(): Throwable? = synchronized(gate) { recordedFailure }
    }

    private companion object {
        const val INITIAL_QUALIFIERS = "w640dp-h480dp-mdpi"
        const val CHANGED_QUALIFIERS = "w800dp-h600dp-mdpi"
        const val DEFAULT_INITIAL_QUALIFIERS = "w360dp-h640dp-mdpi"
        const val DEFAULT_CHANGED_QUALIFIERS = "w480dp-h800dp-mdpi"
        const val DENSITY_DPI = 160

        val INITIAL_METRICS = CaptureMetrics(widthPx = 640, heightPx = 480, densityDpi = DENSITY_DPI)
        val CHANGED_METRICS = CaptureMetrics(widthPx = 800, heightPx = 600, densityDpi = DENSITY_DPI)
    }
}
