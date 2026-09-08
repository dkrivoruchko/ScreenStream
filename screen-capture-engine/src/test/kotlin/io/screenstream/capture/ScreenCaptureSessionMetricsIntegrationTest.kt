package io.screenstream.capture

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.session.SessionCoordinator
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ManualDelayedEntryScheduler
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Coordinator-level custom Metrics integration evidence. Controlled worker and Handler entry only arrange owner
 * and Control execution; public State/start settlement and the exact custom-source handle close are the oracles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionMetricsIntegrationTest {
    // Verification: MET-03
    // Verification: SES-02
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sourceFailureDuringOpenAdmissionFailsStartWithInternalFailure() = runTest {
        // Cell A: source failure while ordinary admission is open selects the exact public failure.
        val source = ControllableMetricsSource()
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    harness.session.start(parameters)
                    null
                } catch (failure: ScreenCaptureException) {
                    failure
                }
            }
            harness.driveUntil(source::isSubscribed)

            source.fail(IllegalStateException("expected Metrics failure"))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }
            harness.driveUntil { source.handleCloseCount() == 1 }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            val startFailure = checkNotNull(start.await())
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertNull(failed.lastEffectiveParameters)
            assertSame(ScreenCaptureProblem.InternalFailure, startFailure.problem)
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-02
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun preActiveCompletionSettlesFromFrozenAvailabilityBeforeExactHandleCloseReturns() = runTest {
        // Cell B: the real handle close remains entered while completed-unavailable settles startup failure.
        run {
            val closeEntered = CountDownLatch(1)
            val allowCloseReturn = CountDownLatch(1)
            val source = ControllableMetricsSource {
                closeEntered.countDown()
                allowCloseReturn.await()
            }
            val platform = HappyCapturePlatform()
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
            var closeTask: ControlledNonInlineDispatcher.TaskHandle? = null

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                try {
                    val start = async(UnconfinedTestDispatcher(testScheduler)) {
                        try {
                            harness.session.start(parameters)
                            null
                        } catch (failure: ScreenCaptureException) {
                            failure
                        }
                    }
                    harness.driveUntil(source::isSubscribed)

                    source.complete()
                    harness.settleNextMetricsChange()
                    closeTask = harness.enterNextWorker()
                    assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
                    harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                    val failed = harness.session.state.value as ScreenCaptureState.Failed
                    val startFailure = checkNotNull(start.await())
                    assertSame(ScreenCaptureProblem.CaptureUnavailable, failed.problem)
                    assertEquals(parameters, failed.requestedParameters)
                    assertNull(failed.lastEffectiveParameters)
                    assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                    assertEquals(1, source.handleCloseCount())
                    assertFalse(closeTask.completed)
                } finally {
                    allowCloseReturn.countDown()
                    closeTask?.awaitSuccessfulCompletion()
                }
            }
            assertEquals(1, source.handleCloseCount())
        }

        // Cell C: frozen positive Metrics reaches exact Active geometry and a successful start while close is held.
        run {
            val closeEntered = CountDownLatch(1)
            val allowCloseReturn = CountDownLatch(1)
            val source = ControllableMetricsSource {
                closeEntered.countDown()
                allowCloseReturn.await()
            }
            val platform = HappyCapturePlatform()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
            var closeTask: ControlledNonInlineDispatcher.TaskHandle? = null

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                try {
                    val startReturned = AtomicBoolean()
                    val start = async(UnconfinedTestDispatcher(testScheduler)) {
                        harness.session.start(parameters)
                        startReturned.set(true)
                        harness.session.state.value
                    }
                    harness.driveUntil(source::isSubscribed)

                    source.emit(metrics)
                    source.complete()
                    harness.settleNextMetricsChange()
                    closeTask = harness.enterNextWorker()
                    assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
                    harness.driveUntil { startReturned.get() }

                    val active = start.await() as ScreenCaptureState.Active
                    assertSame(active, harness.session.state.value)
                    assertEquals(parameters, active.requestedParameters)
                    assertEquals(metrics.widthPx, active.effectiveParameters.captureGeometry.widthPx)
                    assertEquals(metrics.heightPx, active.effectiveParameters.captureGeometry.heightPx)
                    assertEquals(metrics.densityDpi, active.effectiveParameters.captureGeometry.densityDpi)
                    assertEquals(1, source.handleCloseCount())
                    assertFalse(closeTask.completed)
                } finally {
                    allowCloseReturn.countDown()
                    closeTask?.awaitSuccessfulCompletion()
                }
            }
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun postActiveCompletionPreservesPositiveOrUnavailableNonterminalState() = runTest {
        // Cell D: positive completion settles the handle without replacing the current Active value.
        run {
            val source = ControllableMetricsSource()
            val platform = HappyCapturePlatform()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(parameters)
                    harness.session.state.value
                }
                harness.driveUntil(source::isSubscribed)
                source.emit(metrics)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val active = start.await() as ScreenCaptureState.Active

                source.complete()
                harness.driveUntil { source.handleCloseCount() == 1 }
                harness.drainAcceptedWork()

                assertEquals(active, harness.session.state.value)
                assertEquals(1, source.handleCloseCount())
            }
        }

        // Cell E: completion after null Metrics preserves the recoverable suspension after first Active.
        run {
            val source = ControllableMetricsSource()
            val platform = HappyCapturePlatform()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(parameters)
                    harness.session.state.value
                }
                harness.driveUntil(source::isSubscribed)
                source.emit(metrics)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                start.await() as ScreenCaptureState.Active

                source.emit(null)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
                val suspended = harness.session.state.value as ScreenCaptureState.Suspended
                assertSame(ScreenCaptureProblem.CaptureUnavailable, suspended.problem)

                source.complete()
                harness.driveUntil { source.handleCloseCount() == 1 }
                harness.drainAcceptedWork()

                assertEquals(suspended, harness.session.state.value)
                assertEquals(1, source.handleCloseCount())
            }
        }
    }

    // Verification: MET-03
    // Verification: SES-02
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun ordinaryCompletionCloseFailureAfterActiveFailsTheStillOpenSession() = runTest {
        val closeFailure = IllegalStateException("expected Metrics close failure")
        val source = ControllableMetricsSource { throw closeFailure }
        val platform = HappyCapturePlatform()
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }
            harness.driveUntil(source::isSubscribed)
            source.emit(metrics)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val active = start.await() as ScreenCaptureState.Active

            source.complete()
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(active.effectiveParameters, failed.lastEffectiveParameters)
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun reentrantActiveInvalidationCannotSettleStaleStartAndCurrentRecoverySucceeds() = runTest {
        val source = ControllableMetricsSource()
        val platform = HappyCapturePlatform()
        val initialMetrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val recoveredMetrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val invalidated = AtomicBoolean()
            val startReturned = AtomicBoolean()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.state.collect { state ->
                    if (state is ScreenCaptureState.Active && invalidated.compareAndSet(false, true)) {
                        // Make a real source opportunity available before the reentrant Metrics invalidation.
                        // The stale Active settlement must keep Capture production closed despite that opportunity.
                        platform.deliverSourceFrame(rgbaSeed = 41)
                        source.emit(null)
                    }
                }
            }
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                startReturned.set(true)
                harness.session.state.value
            }
            harness.driveUntil(source::isSubscribed)

            source.emit(initialMetrics)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            harness.drainAcceptedWork()

            val suspended = harness.session.state.value as ScreenCaptureState.Suspended
            assertTrue(invalidated.get())
            assertFalse(startReturned.get())
            assertSame(ScreenCaptureProblem.CaptureUnavailable, suspended.problem)
            assertEquals(0, platform.sourceUpdateCount())

            source.emit(recoveredMetrics)
            harness.driveUntil { startReturned.get() }

            val active = start.await() as ScreenCaptureState.Active
            assertSame(active, harness.session.state.value)
            assertEquals(recoveredMetrics.widthPx, active.effectiveParameters.captureGeometry.widthPx)
            assertEquals(recoveredMetrics.heightPx, active.effectiveParameters.captureGeometry.heightPx)
            assertEquals(recoveredMetrics.densityDpi, active.effectiveParameters.captureGeometry.densityDpi)

            platform.deliverSourceFrame(rgbaSeed = 83)
            harness.driveUntil { platform.sourceUpdateCount() > 0 }
            assertEquals(1, platform.sourceUpdateCount())
        }
    }
}

internal class ControllableMetricsSource(
    private val closeAction: () -> Unit = { },
) : CaptureMetricsSource {
    private val observer = AtomicReference<CaptureMetricsSource.Observer?>()
    private val handleCloses = AtomicInteger()

    override fun subscribe(observer: CaptureMetricsSource.Observer): AutoCloseable {
        check(this.observer.compareAndSet(null, observer)) { "Metrics source subscribed more than once" }
        return AutoCloseable {
            handleCloses.incrementAndGet()
            closeAction()
        }
    }

    internal fun isSubscribed(): Boolean = observer.get() != null

    internal fun emit(metrics: CaptureMetrics?) {
        checkNotNull(observer.get()).onMetricsChanged(metrics)
    }

    internal fun complete() {
        checkNotNull(observer.get()).onComplete()
    }

    internal fun fail(cause: Throwable) {
        checkNotNull(observer.get()).onFailure(cause)
    }

    internal fun handleCloseCount(): Int = handleCloses.get()
}

internal class CoordinatorMetricsHarness(
    source: ControllableMetricsSource,
    platform: HappyCapturePlatform,
    platformSdkInt: Int,
) : AutoCloseable {
    private val handlerEnvironment = ManualHandlerEnvironment()
    private val workerDispatcher = ControlledNonInlineDispatcher(workerThreadCount = 2)
    private val delayedEntryScheduler = ManualDelayedEntryScheduler()

    private val coordinator = SessionCoordinator(
        metricsSourceSelection = SessionMetricsSourceSelection.Explicit(source),
        jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
        workerDispatcher = workerDispatcher,
        handlerThreadPlatform = handlerEnvironment,
        handlerTaskPoster = handlerEnvironment,
        delayedEntryScheduler = delayedEntryScheduler,
        executionClock = { 0L },
        currentEpochMillis = { 0L },
        platformSdkInt = platformSdkInt,
        projectionPlatform = platform.projectionPlatform,
        eglPlatform = platform.eglPlatform,
        glesPlatform = platform.glesPlatform,
        targetPlatform = platform.targetPlatform,
    )

    internal val session: ScreenCaptureSession = ScreenCaptureSession.create(coordinator)

    init {
        coordinator.adoptProjection(platform.projection)
    }

    internal fun driveUntil(condition: () -> Boolean) {
        repeat(DRIVE_LIMIT) {
            if (condition()) return
            var progressed = enterNextWorkerSuccessfully()
            if (condition()) return
            progressed = handlerEnvironment.enterNextControl() || progressed
            if (condition()) return
            progressed = handlerEnvironment.enterNextCapture() || progressed
            if (!progressed) {
                check(condition()) { "Controlled Coordinator work became idle before the requested condition" }
            }
        }
        check(condition()) { "Controlled Coordinator work did not reach the requested condition" }
    }

    internal fun settleNextMetricsChange() {
        check(enterNextWorkerSuccessfully()) { "Metrics callback did not schedule its owner turn" }
        check(handlerEnvironment.enterNextControl()) { "Metrics owner did not request its Control turn" }
    }

    internal fun enterNextWorker(): ControlledNonInlineDispatcher.TaskHandle =
        workerDispatcher.enterNext() ?: error("Controlled Coordinator worker task was not retained")

    internal fun drainAcceptedWork() {
        repeat(DRIVE_LIMIT) {
            var progressed = enterNextWorkerSuccessfully()
            progressed = handlerEnvironment.enterNextControl() || progressed
            progressed = handlerEnvironment.enterNextCapture() || progressed
            if (!progressed) return
        }
        error("Controlled Coordinator work did not quiesce")
    }

    private fun enterNextWorkerSuccessfully(): Boolean {
        val task = workerDispatcher.enterNext() ?: return false
        task.awaitSuccessfulCompletion()
        return true
    }

    override fun close() {
        try {
            if (session.state.value !== ScreenCaptureState.NotStarted &&
                session.state.value !is ScreenCaptureState.Stopped &&
                session.state.value !is ScreenCaptureState.Failed
            ) {
                session.stop()
                driveUntil {
                    session.state.value is ScreenCaptureState.Stopped ||
                            session.state.value is ScreenCaptureState.Failed
                }
            }
            drainAcceptedWork()
        } finally {
            delayedEntryScheduler.close()
            workerDispatcher.close()
        }
    }

    private companion object {
        private const val DRIVE_LIMIT: Int = 64
    }
}

private class ManualHandlerEnvironment : HandlerThreadPlatform, HandlerTaskPoster {
    private class DelayedTask(val handler: Handler, val task: Runnable)

    private val gate = Any()
    private val controlThread: HandlerThread = mockk()
    private val captureThread: HandlerThread = mockk()
    private val looper: Looper = Looper.getMainLooper()
    private val controlTasks = ArrayDeque<Runnable>()
    private val captureTasks = ArrayDeque<Runnable>()
    private val delayedTasks = ArrayDeque<DelayedTask>()
    private var controlHandler: Handler? = null
    private var captureHandler: Handler? = null
    private var threadCount: Int = 0
    private var handlerCount: Int = 0

    init {
        every { controlThread.quitSafely() } returns true
        every { captureThread.quitSafely() } returns true
    }

    override fun newThread(name: String): HandlerThread = when (threadCount++) {
        0 -> controlThread
        1 -> captureThread
        else -> error("Unexpected HandlerThread request: $name")
    }

    override fun start(thread: HandlerThread) {
        check(thread === controlThread || thread === captureThread)
    }

    override fun looper(thread: HandlerThread): Looper {
        check(thread === controlThread || thread === captureThread)
        return looper
    }

    override fun handler(looper: Looper): Handler {
        check(looper === this.looper)
        return Handler(looper).also { handler ->
            when (handlerCount++) {
                0 -> controlHandler = handler
                1 -> captureHandler = handler
                else -> error("Unexpected Handler request")
            }
        }
    }

    override fun post(handler: Handler, task: Runnable): Boolean = synchronized(gate) {
        queueFor(handler).addLast(task)
        true
    }

    override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean = synchronized(gate) {
        require(delayMillis >= 0L)
        queueFor(handler)
        delayedTasks.addLast(DelayedTask(handler, task))
        true
    }

    override fun removeCallbacks(handler: Handler, task: Runnable) {
        synchronized(gate) {
            queueFor(handler).removeAll { candidate -> candidate === task }
            delayedTasks.removeAll { candidate -> candidate.handler === handler && candidate.task === task }
        }
    }

    fun enterNextControl(): Boolean = enterNext(controlTasks)

    fun enterNextCapture(): Boolean = enterNext(captureTasks)

    private fun enterNext(tasks: ArrayDeque<Runnable>): Boolean {
        val task = synchronized(gate) { tasks.removeFirstOrNull() } ?: return false
        task.run()
        return true
    }

    private fun queueFor(handler: Handler): ArrayDeque<Runnable> = when {
        handler === controlHandler -> controlTasks
        handler === captureHandler -> captureTasks
        else -> error("Unexpected Handler")
    }
}
