package dev.dmkr.screencaptureengine.internal.platform.metrics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.WindowMetricsCalculator
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureMetricsProviders
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.EngineAttachableCaptureMetricsProvider
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCaptureMetricsProviderTest {
    @Test
    fun bestEffortReturnsAvailablePositiveMetricsForApplicationContext() {
        val provider = CaptureMetricsProviders.bestEffort(RuntimeEnvironment.getApplication())

        provider.assertAvailablePositiveMetrics()
    }

    @Test
    fun fromUiContextDelegatesApplicationContextToBestEffortMetrics() {
        val provider = CaptureMetricsProviders.fromUiContext(RuntimeEnvironment.getApplication())

        provider.assertAvailablePositiveMetrics()
    }

    @Test
    @Config(sdk = [24])
    fun fromUiContextDelegatesNonUiWrapperToBestEffortOnApi24() {
        assertNonUiWrapperDelegatesToBestEffort()
    }

    @Test
    @Config(sdk = [30])
    fun fromUiContextDelegatesNonUiWrapperToBestEffortOnApi30() {
        assertNonUiWrapperDelegatesToBestEffort()
    }

    @Test
    @Config(sdk = [31])
    fun fromUiContextDelegatesNonUiWrapperToBestEffortOnApi31() {
        assertNonUiWrapperDelegatesToBestEffort()
    }

    @Test
    @Config(sdk = [30])
    fun fromUiContextUsesApi30WindowContextMetrics() {
        val application = RuntimeEnvironment.getApplication()
        val displayManager = application.getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w321dp-h234dp")
        try {
            val display = checkNotNull(displayManager.getDisplay(displayId))
            val windowContext = application
                .createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)

            val provider = CaptureMetricsProviders.fromUiContext(windowContext)

            assertEquals(windowContext.currentWindowCaptureMetrics(), provider.requireAvailableMetrics())
        } finally {
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test
    fun fromActivityReturnsAvailablePositiveMetricsForStartedActivity() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()

        try {
            val provider = CaptureMetricsProviders.fromActivity(controller.get())

            provider.assertAvailablePositiveMetrics()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun fromActivityDestroyedActivityReturnsSourceNoLongerAvailable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        controller.pause().stop().destroy()

        val provider = CaptureMetricsProviders.fromActivity(activity)

        assertEquals(
            CaptureMetricsState.Unavailable(
                reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
                message = "Activity is destroyed.",
            ),
            provider.metrics.value,
        )
    }

    @Test
    fun fromActivityRefreshesToSourceNoLongerAvailableOnDestroyWhileAttached() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val provider = CaptureMetricsProviders.fromActivity(controller.get())
        val callbackCount = AtomicInteger()
        val attachment = (provider as EngineAttachableCaptureMetricsProvider).attachSessionAttachment {
            callbackCount.incrementAndGet()
        }

        try {
            provider.assertAvailablePositiveMetrics()

            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                CaptureMetricsState.Unavailable(
                    reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
                    message = "Activity is destroyed.",
                ),
                provider.metrics.value,
            )
            assertEquals(1, callbackCount.get())
        } finally {
            attachment.dispose()
        }
    }

    @Test
    fun fromDisplayReturnsMetricsForRequestedDefaultDisplay() {
        val application = RuntimeEnvironment.getApplication()
        val display = checkNotNull(application.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))

        val provider = CaptureMetricsProviders.fromDisplay(application, display)

        provider.assertAvailablePositiveMetrics()
    }

    @Test
    fun fromDisplayRemovedDisplayRefreshesToSourceNoLongerAvailable() {
        val application = RuntimeEnvironment.getApplication()
        val displayManager = application.getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w320dp-h240dp")
        val display = checkNotNull(displayManager.getDisplay(displayId))
        val provider = CaptureMetricsProviders.fromDisplay(application, display)

        provider.assertAvailablePositiveMetrics()
        val attachment = (provider as EngineAttachableCaptureMetricsProvider).attachSessionAttachment {}

        try {
            provider.assertAvailablePositiveMetrics()
            ShadowDisplayManager.removeDisplay(displayId)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                CaptureMetricsState.Unavailable(
                    reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
                    message = "Display is no longer available.",
                ),
                provider.metrics.value,
            )
        } finally {
            attachment.dispose()
        }
    }

    @Test
    fun fromDisplayRemovedBeforeFirstAttachRefreshesToSourceNoLongerAvailable() {
        val application = RuntimeEnvironment.getApplication()
        val displayManager = application.getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w320dp-h240dp")
        val display = checkNotNull(displayManager.getDisplay(displayId))
        val provider = CaptureMetricsProviders.fromDisplay(application, display)
        val callbackCount = AtomicInteger()

        provider.assertAvailablePositiveMetrics()
        ShadowDisplayManager.removeDisplay(displayId)
        shadowOf(Looper.getMainLooper()).idle()

        val attachment = (provider as EngineAttachableCaptureMetricsProvider).attachSessionAttachment {
            callbackCount.incrementAndGet()
        }

        try {
            assertEquals(
                CaptureMetricsState.Unavailable(
                    reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
                    message = "Display is no longer available.",
                ),
                provider.metrics.value,
            )
            assertEquals(1, callbackCount.get())
        } finally {
            attachment.dispose()
        }
    }

    @Test
    fun builtInProviderCanBeObservedAndDisposed() = runTest {
        val provider = CaptureMetricsProviders.bestEffort(RuntimeEnvironment.getApplication())

        val firstObservation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        val secondObservation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        runCurrent()

        assertNotNull(firstObservation.latestMetricsOrNull)
        assertNotNull(secondObservation.latestMetricsOrNull)

        firstObservation.close()
        secondObservation.close()
    }

    @Test
    fun attachSessionAttachmentRollsBackCallbackWhenSourceAttachThrows() {
        val source = TestMetricsSource(failFirstAttach = true)
        val provider = AndroidCaptureMetricsProvider(source)
        val failure = expectAttachFailure {
            provider.attachSessionAttachment { fail("Callback should not run when source attach throws.") }
        }

        assertSame(source.attachFailure, failure)
        assertEquals(1, source.attachCalls)
        assertEquals(0, source.activeAttachmentCount)

        val attachment = provider.attachSessionAttachment {}

        assertEquals(2, source.attachCalls)
        assertEquals(1, source.activeAttachmentCount)
        attachment.dispose()
        assertEquals(0, source.activeAttachmentCount)
    }

    @Test
    fun attachSessionAttachmentDisposesNewSourceAttachmentWhenAttachTimeCallbackThrows() {
        val source = TestMetricsSource(
            stateAfterInitialRead = CaptureMetricsState.Available(CaptureMetrics(widthPx = 200, heightPx = 100, densityDpi = 320)),
        )
        val provider = AndroidCaptureMetricsProvider(source)
        val callbackFailure = TestCallbackFailure()

        val failure = expectCallbackFailure {
            provider.attachSessionAttachment { throw callbackFailure }
        }

        assertSame(callbackFailure, failure)
        assertEquals(1, source.attachCalls)
        assertEquals(1, source.disposeCount)
        assertEquals(0, source.activeAttachmentCount)

        val attachment = provider.attachSessionAttachment {}

        assertEquals(2, source.attachCalls)
        assertEquals(1, source.activeAttachmentCount)
        attachment.dispose()
        assertEquals(0, source.activeAttachmentCount)
    }

    @Test
    fun concurrentAttachWaitsForInFlightFirstAttachAndSharesOneSourceAttachment() {
        val source = BlockingAttachMetricsSource()
        val provider = AndroidCaptureMetricsProvider(source)
        val firstAttach = asyncAttach(provider)

        source.awaitAttachStarted()
        val secondAttach = asyncAttach(provider)
        secondAttach.assertStillBlocked()
        source.releaseAttach()

        val firstHandle = firstAttach.awaitHandle()
        val secondHandle = secondAttach.awaitHandle()

        assertEquals(1, source.attachCalls.get())
        assertEquals(1, source.activeAttachmentCount.get())
        firstHandle.dispose()
        assertEquals(1, source.activeAttachmentCount.get())
        secondHandle.dispose()
        assertEquals(0, source.activeAttachmentCount.get())
    }

    @Test
    fun firstDisposeAfterInFlightAttachCompletesKeepsSourceAttachedForSecondObserver() {
        val source = BlockingAttachMetricsSource()
        val provider = AndroidCaptureMetricsProvider(source)
        val firstAttach = asyncAttach(provider)

        source.awaitAttachStarted()
        val secondAttach = asyncAttach(provider)
        secondAttach.assertStillBlocked()
        source.releaseAttach()

        val firstHandle = firstAttach.awaitHandle()
        firstHandle.dispose()
        val secondHandle = secondAttach.awaitHandle()

        assertEquals(1, source.attachCalls.get())
        assertEquals(1, source.activeAttachmentCount.get())
        secondHandle.dispose()
        assertEquals(0, source.activeAttachmentCount.get())
    }

    @Test
    fun attachFailureWhileSecondObserverWaitsLeavesNoCallbacksWithoutListener() {
        val source = BlockingAttachMetricsSource(failFirstAttach = true)
        val provider = AndroidCaptureMetricsProvider(source)
        val firstAttach = asyncAttach(provider)

        source.awaitAttachStarted()
        val secondAttach = asyncAttach(provider)
        secondAttach.assertStillBlocked()
        source.releaseAttach()

        assertSame(source.attachFailure, firstAttach.awaitFailure())
        assertSame(source.attachFailure, secondAttach.awaitFailure())
        assertEquals(1, source.attachCalls.get())
        assertEquals(0, source.activeAttachmentCount.get())

        val attachment = provider.attachSessionAttachment {}

        assertEquals(2, source.attachCalls.get())
        assertEquals(1, source.activeAttachmentCount.get())
        attachment.dispose()
        assertEquals(0, source.activeAttachmentCount.get())
    }

    @Test
    fun duplicateActiveAttachmentsShareSourceUntilLastDispose() {
        val source = TestMetricsSource()
        val provider = AndroidCaptureMetricsProvider(source)

        val firstAttachment = provider.attachSessionAttachment {}
        val secondAttachment = provider.attachSessionAttachment {}

        assertEquals(1, source.attachCalls)
        assertEquals(1, source.activeAttachmentCount)
        firstAttachment.dispose()
        assertEquals(1, source.activeAttachmentCount)
        secondAttachment.dispose()
        assertEquals(0, source.activeAttachmentCount)
    }

    @Test
    fun attachAllRollsBackEarlierRegistrationWhenLaterRegistrationThrows() {
        val firstHandle = RecordingHandle()
        val registrationFailure = TestRegistrationFailure()

        val failure = expectRegistrationFailure {
            attachAll(
                { firstHandle },
                { throw registrationFailure },
            )
        }

        assertSame(registrationFailure, failure)
        assertEquals(1, firstHandle.disposeCount)
    }

    @Test
    fun attachAllSuppressesRollbackDisposeFailureOnRegistrationFailure() {
        val disposeFailure = TestDisposeFailure()
        val firstHandle = RecordingHandle(disposeFailure = disposeFailure)
        val registrationFailure = TestRegistrationFailure()

        val failure = expectRegistrationFailure {
            attachAll(
                { firstHandle },
                { throw registrationFailure },
            )
        }

        assertSame(registrationFailure, failure)
        assertEquals(1, firstHandle.disposeCount)
        assertEquals(1, failure.suppressed.size)
        assertSame(disposeFailure, failure.suppressed.single())
    }

    @Test
    fun compositeDisposeContinuesAfterThrowingHandleAndReportsFirstFailure() {
        val firstFailure = TestDisposeFailure()
        val firstHandle = RecordingHandle(disposeFailure = firstFailure)
        val secondHandle = RecordingHandle()

        val failure = expectDisposeFailure {
            CompositeDisposableHandle(listOf(firstHandle, secondHandle)).dispose()
        }

        assertSame(firstFailure, failure)
        assertEquals(1, firstHandle.disposeCount)
        assertEquals(1, secondHandle.disposeCount)
    }

    @Test
    fun sameStateRefreshDoesNotNotifyCallbacks() {
        val available = CaptureMetricsState.Available(CaptureMetrics(widthPx = 100, heightPx = 100, densityDpi = 320))
        val unavailable = CaptureMetricsState.Unavailable(
            reason = CaptureMetricsUnavailableReason.SourceNotReady,
            message = "not ready",
        )
        val source = TriggerableMetricsSource(available)
        val provider = AndroidCaptureMetricsProvider(source)
        val callbackCount = AtomicInteger()
        val attachment = provider.attachSessionAttachment {
            callbackCount.incrementAndGet()
        }

        try {
            source.trigger()
            source.trigger()
            assertEquals(0, callbackCount.get())

            source.state = unavailable
            source.trigger()
            assertEquals(1, callbackCount.get())

            source.trigger()
            source.trigger()
            assertEquals(1, callbackCount.get())

            source.state = CaptureMetricsState.Available(CaptureMetrics(widthPx = 200, heightPx = 100, densityDpi = 320))
            source.trigger()
            assertEquals(2, callbackCount.get())
        } finally {
            attachment.dispose()
        }
    }

    private fun assertNonUiWrapperDelegatesToBestEffort() {
        val context = NonUiContextWrapper(RuntimeEnvironment.getApplication())
        val provider = CaptureMetricsProviders.fromUiContext(context)

        provider.assertAvailablePositiveMetrics()
    }

    private fun CaptureMetricsProvider.assertAvailablePositiveMetrics() {
        val available = metrics.value
        assertTrue("Expected available metrics, was $available", available is CaptureMetricsState.Available)
        val captureMetrics = (available as CaptureMetricsState.Available).metrics
        captureMetrics.assertPositive()
    }

    private fun CaptureMetricsProvider.requireAvailableMetrics(): CaptureMetrics {
        val available = metrics.value
        assertTrue("Expected available metrics, was $available", available is CaptureMetricsState.Available)
        return (available as CaptureMetricsState.Available).metrics
    }

    private fun Context.currentWindowCaptureMetrics(): CaptureMetrics {
        val windowMetrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        val densityDpi = (windowMetrics.density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        return CaptureMetrics(
            widthPx = windowMetrics.bounds.width(),
            heightPx = windowMetrics.bounds.height(),
            densityDpi = densityDpi,
        )
    }

    private fun CaptureMetrics.assertPositive() {
        assertTrue(widthPx > 0)
        assertTrue(heightPx > 0)
        assertTrue(densityDpi > 0)
    }

    private fun expectAttachFailure(block: () -> Unit): TestAttachFailure {
        try {
            block()
            fail("Expected TestAttachFailure")
        } catch (failure: TestAttachFailure) {
            return failure
        }
        error("Unreachable")
    }

    private fun expectCallbackFailure(block: () -> Unit): TestCallbackFailure {
        try {
            block()
            fail("Expected TestCallbackFailure")
        } catch (failure: TestCallbackFailure) {
            return failure
        }
        error("Unreachable")
    }

    private fun expectRegistrationFailure(block: () -> Unit): TestRegistrationFailure {
        try {
            block()
            fail("Expected TestRegistrationFailure")
        } catch (failure: TestRegistrationFailure) {
            return failure
        }
        error("Unreachable")
    }

    private fun expectDisposeFailure(block: () -> Unit): TestDisposeFailure {
        try {
            block()
            fail("Expected TestDisposeFailure")
        } catch (failure: TestDisposeFailure) {
            return failure
        }
        error("Unreachable")
    }

    private fun asyncAttach(
        provider: AndroidCaptureMetricsProvider,
    ): AsyncAttachment {
        val handle = AtomicReference<DisposableHandle?>()
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val attachThread = thread(start = true) {
            try {
                handle.set(provider.attachSessionAttachment {})
            } catch (cause: Throwable) {
                failure.set(cause)
            } finally {
                completed.countDown()
            }
        }
        return AsyncAttachment(handle = handle, failure = failure, completed = completed, attachThread = attachThread)
    }

    private class NonUiContextWrapper(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? {
            if (name == WINDOW_SERVICE) {
                throw AssertionError("fromUiContext should delegate this non-UI context to bestEffort.")
            }
            return super.getSystemService(name)
        }
    }

    private class TestMetricsSource(
        private val failFirstAttach: Boolean = false,
        private val initialState: CaptureMetricsState = CaptureMetricsState.Available(CaptureMetrics(widthPx = 100, heightPx = 100, densityDpi = 320)),
        private val stateAfterInitialRead: CaptureMetricsState = initialState,
    ) : MetricsSource {
        val attachFailure = TestAttachFailure()
        var attachCalls = 0
            private set
        var disposeCount = 0
            private set
        var activeAttachmentCount = 0
            private set
        private var readCalls = 0

        override fun read(): CaptureMetricsState {
            readCalls++
            return if (readCalls == 1) initialState else stateAfterInitialRead
        }

        override fun attach(onChanged: () -> Unit): DisposableHandle {
            attachCalls++
            if (failFirstAttach && attachCalls == 1) {
                throw attachFailure
            }
            activeAttachmentCount++
            return object : DisposableHandle {
                private var disposed = false

                override fun dispose() {
                    if (!disposed) {
                        disposed = true
                        disposeCount++
                        activeAttachmentCount--
                    }
                }
            }
        }
    }

    private class BlockingAttachMetricsSource(
        private val failFirstAttach: Boolean = false,
    ) : MetricsSource {
        val attachFailure = TestAttachFailure()
        val attachCalls = AtomicInteger()
        val activeAttachmentCount = AtomicInteger()
        private val attachStarted = CountDownLatch(1)
        private val releaseAttach = CountDownLatch(1)

        override fun read(): CaptureMetricsState {
            return CaptureMetricsState.Available(CaptureMetrics(widthPx = 100, heightPx = 100, densityDpi = 320))
        }

        override fun attach(onChanged: () -> Unit): DisposableHandle {
            val call = attachCalls.incrementAndGet()
            attachStarted.countDown()
            check(releaseAttach.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release source attach." }
            if (failFirstAttach && call == 1) {
                throw attachFailure
            }
            activeAttachmentCount.incrementAndGet()
            return object : DisposableHandle {
                private val disposed = AtomicInteger()

                override fun dispose() {
                    if (disposed.compareAndSet(0, 1)) {
                        activeAttachmentCount.decrementAndGet()
                    }
                }
            }
        }

        fun awaitAttachStarted() {
            check(attachStarted.await(5, TimeUnit.SECONDS)) { "Timed out waiting for source attach to start." }
        }

        fun releaseAttach() {
            releaseAttach.countDown()
        }
    }

    private class TriggerableMetricsSource(
        initialState: CaptureMetricsState,
    ) : MetricsSource {
        var state: CaptureMetricsState = initialState
        private var onChanged: (() -> Unit)? = null

        override fun read(): CaptureMetricsState = state

        override fun attach(onChanged: () -> Unit): DisposableHandle {
            this.onChanged = onChanged
            return DisposableHandle { this.onChanged = null }
        }

        fun trigger() {
            checkNotNull(onChanged) { "Source is not attached." }.invoke()
        }
    }

    private class AsyncAttachment(
        private val handle: AtomicReference<DisposableHandle?>,
        private val failure: AtomicReference<Throwable?>,
        private val completed: CountDownLatch,
        private val attachThread: Thread,
    ) {
        fun awaitHandle(): DisposableHandle {
            awaitCompleted()
            failure.get()?.let { throw AssertionError("Expected attachment handle, got failure.", it) }
            return checkNotNull(handle.get()) { "Attachment handle was not returned." }
        }

        fun awaitFailure(): Throwable {
            awaitCompleted()
            return checkNotNull(failure.get()) { "Expected attachment failure." }
        }

        fun assertStillBlocked() {
            assertFalse("Attach returned before source attach was released.", completed.await(100, TimeUnit.MILLISECONDS))
        }

        private fun awaitCompleted() {
            check(completed.await(5, TimeUnit.SECONDS)) { "Timed out waiting for attach thread ${attachThread.name}." }
        }
    }

    private class RecordingHandle(
        private val disposeFailure: RuntimeException? = null,
    ) : DisposableHandle {
        var disposeCount = 0
            private set

        override fun dispose() {
            disposeCount++
            disposeFailure?.let { throw it }
        }
    }

    private class TestAttachFailure : RuntimeException("source attach failed")

    private class TestCallbackFailure : RuntimeException("metrics callback failed")

    private class TestRegistrationFailure : RuntimeException("registration failed")

    private class TestDisposeFailure : RuntimeException("dispose failed")
}
