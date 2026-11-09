package io.screenstream.capture.testutil

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureSession
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.session.SessionCoordinator
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    platform: CapturePlatformFixture,
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
                session.requestStop()
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
