package io.screenstream.capture.testutil

import android.media.projection.MediaProjection
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
import io.screenstream.capture.internal.capture.AndroidEglPlatform
import io.screenstream.capture.internal.capture.AndroidGlesPlatform
import io.screenstream.capture.internal.capture.AndroidProjectionPlatform
import io.screenstream.capture.internal.capture.AndroidTargetPlatform
import io.screenstream.capture.internal.capture.EglPlatform
import io.screenstream.capture.internal.capture.GlesPlatform
import io.screenstream.capture.internal.capture.ProjectionPlatform
import io.screenstream.capture.internal.capture.TargetPlatform
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.encoding.NativeJpegProcess
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.session.SessionCoordinator
import java.lang.AutoCloseable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SessionStartHarness(
    workerOutcome: DispatchOutcome = DispatchOutcome.Accept,
    workerThreadCount: Int = 1,
    bootstrapMode: BootstrapMode = BootstrapMode.FailFast,
    bootstrapFault: BootstrapFault = BootstrapFault.None,
    metrics: CaptureMetrics = CaptureMetrics(100, 200, 300),
    platformSdkInt: Int = 36,
    projectionPlatform: ProjectionPlatform = AndroidProjectionPlatform,
    eglPlatform: EglPlatform = AndroidEglPlatform,
    glesPlatform: GlesPlatform = AndroidGlesPlatform,
    targetPlatform: TargetPlatform = AndroidTargetPlatform,
    jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
    nativeJpeg: NativeJpegFacade = NativeJpegProcess,
) : AutoCloseable {
    internal enum class BootstrapMode {
        FailFast,
        ImmediateMetrics,
        BlockingMetrics,
    }

    internal enum class BootstrapFault {
        None,
        ControlThreadStartThrows,
        ControlLooperReturnsNull,
        ControlHandlerConstructionThrows,
        FirstControlPostReturnsFalse,
        FirstControlPostThrows,
    }

    private sealed interface ClockOutcome {
        class Value(
            val nanos: Long,
            val afterRead: () -> Unit = {},
        ) : ClockOutcome

        class Failure(val exception: Exception) : ClockOutcome
    }

    internal class ScriptedClock : ElapsedRealtimeClock {
        private val gate = Any()
        private val outcomes = ArrayDeque<ClockOutcome>()
        private var defaultNanos = 0L
        private var reads = 0

        override fun nowNanos(): Long {
            val outcome = synchronized(gate) {
                reads += 1
                outcomes.removeFirstOrNull() ?: ClockOutcome.Value(defaultNanos)
            }
            return when (outcome) {
                is ClockOutcome.Value -> outcome.nanos.also { outcome.afterRead() }
                is ClockOutcome.Failure -> throw outcome.exception
            }
        }

        internal fun enqueueValue(nanos: Long, afterRead: () -> Unit = {}) = synchronized(gate) {
            outcomes.addLast(ClockOutcome.Value(nanos, afterRead))
        }

        internal fun enqueueFailure(exception: Exception) = synchronized(gate) {
            outcomes.addLast(ClockOutcome.Failure(exception))
        }

        internal fun setDefaultNanos(nanos: Long) = synchronized(gate) {
            require(nanos >= 0L) { "default clock value must be nonnegative" }
            defaultNanos = nanos
        }

        internal fun readCount(): Int = synchronized(gate) { reads }

        internal fun resetReadCount() = synchronized(gate) {
            reads = 0
        }
    }

    private class FailFastHandlerThreadPlatform : HandlerThreadPlatform {
        private val calls = AtomicInteger()

        fun callCount(): Int = calls.get()

        override fun newThread(name: String): HandlerThread {
            calls.incrementAndGet()
            throw AssertionError("HandlerThread creation was not expected")
        }

        override fun start(thread: HandlerThread) {
            calls.incrementAndGet()
            throw AssertionError("HandlerThread start was not expected")
        }

        @Suppress("RedundantNullableReturnType")
        override fun looper(thread: HandlerThread): Looper? {
            calls.incrementAndGet()
            throw AssertionError("Looper access was not expected")
        }

        override fun handler(looper: Looper): Handler {
            calls.incrementAndGet()
            throw AssertionError("Handler creation was not expected")
        }
    }

    private class FailFastHandlerTaskPoster : HandlerTaskPoster {
        private val calls = AtomicInteger()

        fun callCount(): Int = calls.get()

        override fun post(handler: Handler, task: Runnable): Boolean {
            calls.incrementAndGet()
            throw AssertionError("Handler post was not expected")
        }

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean {
            calls.incrementAndGet()
            throw AssertionError("Handler delayed post was not expected")
        }

        override fun removeCallbacks(handler: Handler, task: Runnable) {
            calls.incrementAndGet()
            throw AssertionError("Handler callback removal was not expected")
        }
    }

    private class ManualHandlerThreadPlatform(
        private val bootstrapFault: BootstrapFault,
        private val recordFault: (BootstrapFault) -> Unit,
        private val recordState: () -> Unit,
        private val registerHandler: (Handler) -> Unit,
    ) : HandlerThreadPlatform {
        val controlThread: HandlerThread = mockk()
        val captureThread: HandlerThread = mockk()
        private val looper: Looper = Looper.getMainLooper()
        private var threadCount = 0
        private var handlerCount = 0

        init {
            every { controlThread.quitSafely() } returns true
            every { captureThread.quitSafely() } returns true
        }

        override fun newThread(name: String): HandlerThread {
            recordState()
            return when (threadCount++) {
                0 -> controlThread
                1 -> captureThread
                else -> error("Unexpected HandlerThread request: $name")
            }
        }

        override fun start(thread: HandlerThread) {
            recordState()
            check((thread === controlThread) || (thread === captureThread))
            if ((thread === controlThread) && (bootstrapFault == BootstrapFault.ControlThreadStartThrows)) {
                recordFault(bootstrapFault)
                throw IllegalStateException("Injected Control HandlerThread start failure")
            }
        }

        override fun looper(thread: HandlerThread): Looper? {
            recordState()
            check((thread === controlThread) || (thread === captureThread))
            if ((thread === controlThread) && (bootstrapFault == BootstrapFault.ControlLooperReturnsNull)) {
                recordFault(bootstrapFault)
                return null
            }
            return looper
        }

        override fun handler(looper: Looper): Handler {
            recordState()
            check(looper === this.looper)
            check(handlerCount < 2)
            if ((handlerCount == 0) && (bootstrapFault == BootstrapFault.ControlHandlerConstructionThrows)) {
                recordFault(bootstrapFault)
                throw IllegalStateException("Injected Control Handler construction failure")
            }
            handlerCount += 1
            return Handler(looper).also(registerHandler)
        }
    }

    private class ManualHandlerTaskPoster(
        private val bootstrapFault: BootstrapFault,
        private val recordFault: (BootstrapFault) -> Unit,
    ) : HandlerTaskPoster {
        private val gate = Any()
        private val controlTasks = ArrayDeque<Runnable>()
        private val captureTasks = ArrayDeque<Runnable>()
        private var controlHandler: Handler? = null
        private var captureHandler: Handler? = null
        private var controlPosts = 0
        private var capturePosts = 0
        private var removals = 0
        private var firstControlPostAttempted = false
        private var beforeNextControlPost: (() -> Unit)? = null

        fun registerHandler(handler: Handler) = synchronized(gate) {
            when {
                controlHandler == null -> controlHandler = handler
                captureHandler == null -> captureHandler = handler
                else -> error("Unexpected Handler registration")
            }
        }

        override fun post(handler: Handler, task: Runnable): Boolean = synchronized(gate) {
            when (handler) {
                controlHandler -> {
                    controlPosts += 1
                    if (!firstControlPostAttempted) {
                        firstControlPostAttempted = true
                        when (bootstrapFault) {
                            BootstrapFault.FirstControlPostReturnsFalse -> {
                                recordFault(bootstrapFault)
                                return@synchronized false
                            }

                            BootstrapFault.FirstControlPostThrows -> {
                                recordFault(bootstrapFault)
                                throw IllegalStateException("Injected first Control Handler post failure")
                            }

                            else -> Unit
                        }
                    }
                    beforeNextControlPost?.also {
                        beforeNextControlPost = null
                        it()
                    }
                    controlTasks.addLast(task)
                }

                captureHandler -> {
                    captureTasks.addLast(task)
                    capturePosts += 1
                }

                else -> error("Task was posted to an unknown Handler")
            }
            true
        }

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
            error("Handler delayed post was not expected")

        override fun removeCallbacks(handler: Handler, task: Runnable) = synchronized(gate) {
            check(handler === controlHandler)
            controlTasks.removeAll { it === task }
            removals += 1
        }

        fun enterNextControl(): Boolean = takeNext(controlTasks)?.let {
            it.run()
            true
        } ?: false

        fun enterNextCapture(): Boolean = takeNext(captureTasks)?.let {
            it.run()
            true
        } ?: false

        fun runBeforeNextControlPost(action: () -> Unit) = synchronized(gate) {
            check(beforeNextControlPost == null) { "A before-Control-post action is already armed" }
            beforeNextControlPost = action
        }

        fun pendingControlCount(): Int = synchronized(gate) { controlTasks.size }

        fun pendingCaptureCount(): Int = synchronized(gate) { captureTasks.size }

        fun controlPostCount(): Int = synchronized(gate) { controlPosts }

        fun capturePostCount(): Int = synchronized(gate) { capturePosts }

        fun removalCount(): Int = synchronized(gate) { removals }

        private fun takeNext(tasks: ArrayDeque<Runnable>): Runnable? = synchronized(gate) {
            tasks.removeFirstOrNull()
        }
    }

    private class RecordingCloseHandle : AutoCloseable {
        private val closes = AtomicInteger()

        override fun close() {
            closes.incrementAndGet()
        }

        fun closeCount(): Int = closes.get()
    }

    internal val clock = ScriptedClock()
    internal val workerDispatcher = ControlledNonInlineDispatcher(
        initialOutcome = workerOutcome,
        workerThreadCount = workerThreadCount,
    )
    internal val delayedEntryScheduler = ManualDelayedEntryScheduler()

    private val metricsSubscriptions = AtomicInteger()
    private val metricsSubscribeEntered = CountDownLatch(1)
    private val metricsSubscribeMayReturn = CountDownLatch(1)
    private val metricsCloseHandle = RecordingCloseHandle()
    private val handlerPlatformStates = ArrayList<ScreenCaptureState>()
    private val consumedBootstrapFault = AtomicReference<BootstrapFault?>()
    private val failFastHandlerThreadPlatform = FailFastHandlerThreadPlatform()
    private val failFastHandlerTaskPoster = FailFastHandlerTaskPoster()
    private var manualHandlerThreadPlatform: ManualHandlerThreadPlatform? = null
    private var manualHandlerTaskPoster: ManualHandlerTaskPoster? = null
    private val workerSubmissionGate = Any()
    private val workerSubmissionStates = ArrayList<ScreenCaptureState>()
    private var constructedSession: ScreenCaptureSession? = null

    internal val session: ScreenCaptureSession

    init {
        val controlledBootstrap = (bootstrapMode != BootstrapMode.FailFast) || (bootstrapFault != BootstrapFault.None)
        val handlerThreadPlatform = if (controlledBootstrap) {
            val recordFault: (BootstrapFault) -> Unit = consumedBootstrapFault::set
            val poster = ManualHandlerTaskPoster(bootstrapFault, recordFault).also { manualHandlerTaskPoster = it }
            ManualHandlerThreadPlatform(
                bootstrapFault = bootstrapFault,
                recordFault = recordFault,
                recordState = {
                    synchronized(handlerPlatformStates) {
                        handlerPlatformStates += checkNotNull(constructedSession).state.value
                    }
                },
                registerHandler = poster::registerHandler,
            ).also { manualHandlerThreadPlatform = it }
        } else {
            failFastHandlerThreadPlatform
        }
        val handlerTaskPoster = if (controlledBootstrap) {
            checkNotNull(manualHandlerTaskPoster)
        } else {
            failFastHandlerTaskPoster
        }
        val observedWorkerDispatcher = NonInlineDispatcher { task ->
            synchronized(workerSubmissionGate) {
                workerSubmissionStates += checkNotNull(constructedSession).state.value
            }
            workerDispatcher.tryDispatch(task)
        }
        val metricsSource = CaptureMetricsSource { observer ->
            metricsSubscriptions.incrementAndGet()
            when (bootstrapMode) {
                BootstrapMode.FailFast -> throw AssertionError("Metrics subscription was not expected")
                BootstrapMode.ImmediateMetrics -> observer.onMetricsChanged(metrics)
                BootstrapMode.BlockingMetrics -> {
                    metricsSubscribeEntered.countDown()
                    check(metricsSubscribeMayReturn.await(5L, TimeUnit.SECONDS)) {
                        "Metrics subscribe return was not released"
                    }
                }
            }
            metricsCloseHandle
        }
        val coordinator = SessionCoordinator(
            metricsSourceSelection = SessionMetricsSourceSelection.Explicit(metricsSource),
            jpegBackendPolicy = jpegBackendPolicy,
            workerDispatcher = observedWorkerDispatcher,
            handlerThreadPlatform = handlerThreadPlatform,
            handlerTaskPoster = handlerTaskPoster,
            delayedEntryScheduler = delayedEntryScheduler,
            executionClock = clock,
            currentEpochMillis = { 0L },
            platformSdkInt = platformSdkInt,
            projectionPlatform = projectionPlatform,
            eglPlatform = eglPlatform,
            glesPlatform = glesPlatform,
            targetPlatform = targetPlatform,
            nativeJpeg = nativeJpeg,
        )
        val createdSession = ScreenCaptureSession.create(coordinator)
        constructedSession = createdSession
        session = createdSession
        clock.resetReadCount()
    }

    internal fun projection(): MediaProjection = mockk()

    internal fun metricsSubscriptionCount(): Int = metricsSubscriptions.get()

    internal fun metricsHandleCloseCount(): Int = metricsCloseHandle.closeCount()

    internal fun consumedBootstrapFault(): BootstrapFault? = consumedBootstrapFault.get()

    internal fun awaitMetricsSubscribeEntered(): Boolean = metricsSubscribeEntered.await(5L, TimeUnit.SECONDS)

    internal fun releaseMetricsSubscribeReturn() {
        metricsSubscribeMayReturn.countDown()
    }

    internal fun handlerPlatformCallCount(): Int = failFastHandlerThreadPlatform.callCount()

    internal fun handlerPostCallCount(): Int = failFastHandlerTaskPoster.callCount()

    internal fun handlerPlatformStates(): List<ScreenCaptureState> =
        synchronized(handlerPlatformStates) { handlerPlatformStates.toList() }

    internal fun enterNextWorker(): ControlledNonInlineDispatcher.TaskHandle? = workerDispatcher.enterNext()

    internal fun enterNextWorkerSuccessfully(): Boolean {
        val handle = enterNextWorker() ?: return false
        handle.awaitSuccessfulCompletion()
        return true
    }

    internal fun drainWorkerTasks(limit: Int = 16) {
        repeat(limit) {
            if (!enterNextWorkerSuccessfully()) return
        }
        error("Worker tasks did not drain within $limit entries")
    }

    internal fun enterNextControlTask(): Boolean = checkNotNull(manualHandlerTaskPoster).enterNextControl()

    internal fun enterNextCaptureTask(): Boolean = checkNotNull(manualHandlerTaskPoster).enterNextCapture()

    internal fun runBeforeNextControlPost(action: () -> Unit) {
        checkNotNull(manualHandlerTaskPoster).runBeforeNextControlPost(action)
    }

    internal fun driveUntil(condition: () -> Boolean) {
        repeat(DRIVE_ROUND_LIMIT) {
            if (condition()) return
            var progressed = enterNextWorkerSuccessfully()
            if (condition()) return
            progressed = enterNextControlTask() || progressed
            if (condition()) return
            progressed = enterNextCaptureTask() || progressed
            if (!progressed) {
                check(condition()) { "Controlled Session work became idle before the requested condition" }
            }
        }
        check(condition()) { "Controlled Session work did not reach the requested condition within the bounded drive" }
    }

    internal fun pendingControlTaskCount(): Int = checkNotNull(manualHandlerTaskPoster).pendingControlCount()

    internal fun pendingCaptureTaskCount(): Int = checkNotNull(manualHandlerTaskPoster).pendingCaptureCount()

    internal fun controlPostCount(): Int = checkNotNull(manualHandlerTaskPoster).controlPostCount()

    internal fun capturePostCount(): Int = checkNotNull(manualHandlerTaskPoster).capturePostCount()

    internal fun handlerRemovalCount(): Int = checkNotNull(manualHandlerTaskPoster).removalCount()

    internal fun controlThread(): HandlerThread = checkNotNull(manualHandlerThreadPlatform).controlThread

    internal fun captureThread(): HandlerThread = checkNotNull(manualHandlerThreadPlatform).captureThread

    internal fun workerSubmissionStates(): List<ScreenCaptureState> =
        synchronized(workerSubmissionGate) { workerSubmissionStates.toList() }

    override fun close() {
        metricsSubscribeMayReturn.countDown()
        delayedEntryScheduler.close()
        workerDispatcher.close()
    }

    private companion object {
        private const val DRIVE_ROUND_LIMIT = 32
    }
}
