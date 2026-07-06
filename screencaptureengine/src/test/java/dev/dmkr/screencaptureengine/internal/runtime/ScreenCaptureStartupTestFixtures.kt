package dev.dmkr.screencaptureengine.internal.runtime

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.EngineAttachableCaptureMetricsProvider
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue

internal suspend fun expectStartException(block: suspend () -> Any): ScreenCaptureStartException =
    try {
        block()
        throw AssertionError("Expected ScreenCaptureStartException")
    } catch (exception: ScreenCaptureStartException) {
        exception
    }

internal fun Result<*>.screenCaptureStartException(): ScreenCaptureStartException {
    val failure = exceptionOrNull()
    if (failure is ScreenCaptureStartException) return failure
    throw AssertionError("Expected ScreenCaptureStartException", failure)
}

internal fun assertMilestonesInOrder(
    actual: List<ScreenCaptureStartupMilestone>,
    vararg expected: ScreenCaptureStartupMilestone,
) {
    var previousIndex = -1
    for (milestone in expected) {
        val index = actual.indexOf(milestone)
        assertTrue("$milestone missing from $actual", index >= 0)
        assertTrue("$milestone was not after the previous required milestone in $actual", index > previousIndex)
        previousIndex = index
    }
}

internal class TestRuntime(
    apiLevel: Int,
    startupResizeTimeoutMillis: Long = 3_000L,
) {
    val events = mutableListOf<String>()
    val metricsProvider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
    val projection = FakeProjectionHandle()
    val callbackRegistration = FakeProjectionCallbackRegistration(events)
    val targetOwner = FakeProjectionTargetOwner(events)
    val virtualDisplayOwner = FakeProjectionVirtualDisplayOwner()
    var virtualDisplayCreateFailure: Throwable? = null
    var virtualDisplayCreateCount = 0
    var cleanupSchedulerFailure: Throwable? = null
    var failOnCleanupFailure = true
    val cleanupFailures = mutableListOf<Throwable>()

    private val transaction = ScreenCaptureStartupTransaction(
        apiLevel = apiLevel,
        startupResizeTimeoutMillis = startupResizeTimeoutMillis,
        callbackAdapterFactory = { listener, synchronousEventObserver ->
            callbackRegistration.listener = listener
            callbackRegistration.synchronousEventObserver = synchronousEventObserver
            callbackRegistration
        },
        projectionTargetOwnerFactory = {
            targetOwner
        },
        virtualDisplayFactory = { _, _, target, _ ->
            events += "virtualDisplay.create"
            virtualDisplayCreateCount++
            virtualDisplayCreateFailure?.let { throw it }
            virtualDisplayOwner.bindTarget(target)
            virtualDisplayOwner
        },
        cleanupScheduler = { block ->
            cleanupSchedulerFailure?.let { throw it }
            block()
        },
        cleanupFailureSink = {
            cleanupFailures += it
            if (failOnCleanupFailure) throw AssertionError("Unexpected cleanup failure", it)
        },
    )

    suspend fun start(): ScreenCaptureStartupResources =
        transaction.startThroughAuthoritativeStartupGeometry(
            config = ScreenCaptureConfig(metricsProvider = metricsProvider),
            projection = projection,
        )
}

internal class TestMetricsProvider(initialMetrics: CaptureMetrics) : EngineAttachableCaptureMetricsProvider {
    private val mutableMetrics = MutableStateFlow(initialMetrics)

    override val metrics: StateFlow<CaptureMetrics> = mutableMetrics

    var attachmentDisposeCount = 0

    val activeCollectorCount: Int
        get() = mutableMetrics.subscriptionCount.value

    override fun attachSessionAttachment(onMetricsChanged: () -> Unit): DisposableHandle =
        DisposableHandle {
            attachmentDisposeCount++
        }

    fun update(metrics: CaptureMetrics) {
        mutableMetrics.value = metrics
    }
}

internal class FakeProjectionHandle : ProjectionHandle {
    var stopCount = 0

    override fun registerCallback(callback: MediaProjection.Callback, callbackHandler: ProjectionCallbackHandlerHandle?) = Unit

    override fun unregisterCallback(callback: MediaProjection.Callback) = Unit

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: ProjectionSurfaceHandle,
        callback: VirtualDisplay.Callback?,
        callbackHandler: ProjectionCallbackHandlerHandle?,
    ): VirtualDisplay = error("Virtual display creation should go through StartupVirtualDisplayFactory.")

    override fun stop() {
        stopCount++
    }
}

internal class FakeProjectionCallbackRegistration(
    private val events: MutableList<String>,
) : ProjectionCallbackRegistration {
    override val callbackHandler: ProjectionCallbackHandlerHandle = FakeProjectionCallbackHandlerHandle
    override val projectionStopObserved: Boolean
        get() = stopObserved

    var listener: MediaProjectionCallbackAdapter.Listener? = null
    var synchronousEventObserver: ((ProjectionCallbackRawEvent) -> Unit)? = null
    var registerFailure: Throwable? = null
    var emitStopDuringRegister = false
    var closeCount = 0

    private var stopObserved = false
    private var nextResizeId = 1L

    override fun register(projection: ProjectionHandle) {
        events += "callback.register"
        registerFailure?.let { throw it }
        if (emitStopDuringRegister) {
            emitStop()
        }
    }

    override fun close() {
        closeCount++
    }

    fun emitStop() {
        if (stopObserved) return
        stopObserved = true
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Stop)
        listener?.onProjectionStopped()
    }

    fun emitResize(width: Int, height: Int) {
        if (stopObserved) return
        val resize = ProjectionCapturedContentResize(id = nextResizeId++, width = width, height = height)
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Resize(resize))
        emitResizeToListener(resize)
    }

    fun emitResizeRawOnly(width: Int, height: Int): ProjectionCapturedContentResize {
        check(!stopObserved)
        val resize = ProjectionCapturedContentResize(id = nextResizeId++, width = width, height = height)
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Resize(resize))
        return resize
    }

    fun emitResizeToListener(resize: ProjectionCapturedContentResize) {
        if (stopObserved) return
        listener?.onCapturedContentResized(resize)
    }

    fun emitVisibility(isVisible: Boolean) {
        if (stopObserved) return
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Visibility(isVisible))
        listener?.onCapturedContentVisibilityChanged(isVisible)
    }
}

internal data object FakeProjectionCallbackHandlerHandle : ProjectionCallbackHandlerHandle

internal class FakeProjectionTargetOwner(
    private val events: MutableList<String>,
) : ProjectionTargetOwnerHandle {
    val createdTargets = mutableListOf<TargetCreation>()
    val createdHandles = mutableListOf<FakeProjectionTargetHandle>()
    var closeCount = 0
    var createCount = 0
    var closeFailure: Throwable? = null
    val createFailures = ArrayDeque<Throwable>()
    var targetSizeLimitsFailure: Throwable? = null
    var maxTargetWidth = Int.MAX_VALUE
    var maxTargetHeight = Int.MAX_VALUE
    var afterTargetSizeLimits: (() -> Unit)? = null
    var afterCreateTarget: (() -> Unit)? = null

    override fun targetSizeLimits(): ProjectionTargetSizeLimits {
        val failure = targetSizeLimitsFailure
        afterTargetSizeLimits?.invoke()
        if (failure != null) throw failure
        return ProjectionTargetSizeLimits(maxWidth = maxTargetWidth, maxHeight = maxTargetHeight)
    }

    override fun createTarget(width: Int, height: Int, densityDpi: Int): ProjectionTargetHandle {
        events += "target.create"
        createCount++
        createFailures.removeFirstOrNull()?.let { throw it }
        val handle = FakeProjectionTargetHandle(
            generation = createCount.toLong(),
            width = width,
            height = height,
            densityDpi = densityDpi,
        )
        createdTargets += TargetCreation(width = width, height = height, densityDpi = densityDpi)
        createdHandles += handle
        afterCreateTarget?.invoke()
        return handle
    }

    override fun close() {
        closeCount++
        createdHandles.forEach { handle -> handle.closeFromOwner() }
        closeFailure?.let { throw it }
    }
}

internal class FakeProjectionTargetHandle(
    override val generation: Long,
    override val width: Int,
    override val height: Int,
    override val densityDpi: Int,
) : ProjectionTargetHandle {
    override val surface: ProjectionSurfaceHandle = FakeProjectionSurfaceHandle
    var closeCount = 0
    var directCloseCount = 0
    var ownerCloseCount = 0

    override fun close() {
        directCloseCount++
        closeIfNeeded()
    }

    fun closeFromOwner() {
        ownerCloseCount++
        closeIfNeeded()
    }

    private fun closeIfNeeded() {
        if (closeCount == 0) closeCount++
    }
}

internal data object FakeProjectionSurfaceHandle : ProjectionSurfaceHandle

internal class FakeProjectionVirtualDisplayOwner : ProjectionVirtualDisplayOwner {
    var closeCount = 0
    var closeFailure: Throwable? = null
    var bindCount = 0
    val bindFailures = ArrayDeque<Throwable>()
    val unsafeBindFailures = ArrayDeque<Throwable>()
    var afterSuccessfulBind: (() -> Unit)? = null
    private var currentTarget: ProjectionTargetHandle? = null
    private var closed = false

    override val isClosed: Boolean
        get() = closed

    override fun currentTargetSnapshot(): ProjectionTargetSnapshot? =
        currentTarget?.let { target ->
            ProjectionTargetSnapshot(
                generation = target.generation,
                width = target.width,
                height = target.height,
                densityDpi = target.densityDpi,
                surface = target.surface,
            )
        }

    override fun bindTarget(target: ProjectionTargetHandle): ProjectionTargetHandle? {
        bindCount++
        if (closed) {
            throw IllegalStateException("VirtualDisplayOwner is closed.")
        }
        bindFailures.removeFirstOrNull()?.let { failure ->
            throw failure
        }
        unsafeBindFailures.removeFirstOrNull()?.let { failure ->
            currentTarget = null
            closed = true
            closeCount++
            throw failure
        }
        val previous = currentTarget
        currentTarget = target
        afterSuccessfulBind?.invoke()
        return previous
    }

    override fun close() {
        if (closed) return
        closed = true
        closeCount++
        closeFailure?.let { throw it }
    }
}

internal data class TargetCreation(val width: Int, val height: Int, val densityDpi: Int)
