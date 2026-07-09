package dev.dmkr.screencaptureengine.internal.startup

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.EngineAttachableCaptureMetricsProvider
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.internal.gl.GlLaneAbandonment
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeFrameSignalSink
import dev.dmkr.screencaptureengine.internal.platform.metrics.CaptureMetricsObservation
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionCallbackAdapter
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackHandlerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRawEvent
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionStopArbiter
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionSurfaceHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSizeLimits
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetGlCapability
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetGlScope
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetOwnerAbandonment
import dev.dmkr.screencaptureengine.internal.target.RuntimeExternalOesTexture
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetGlAccess
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetGlScope
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetInstanceIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetOwnerIdentity
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private val runCleanupSynchronously: Boolean = true,
    beforeInputValidationForTesting: (CaptureMetricsObservation) -> Unit = {},
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
    private val scheduledCleanupBlocks = ArrayDeque<() -> Unit>()

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
            if (runCleanupSynchronously) {
                block()
            } else {
                scheduledCleanupBlocks += block
            }
        },
        cleanupFailureSink = {
            cleanupFailures += it
            if (failOnCleanupFailure) throw AssertionError("Unexpected cleanup failure", it)
        },
        beforeInputValidationForTesting = beforeInputValidationForTesting,
    )

    suspend fun start(onMetricsChanged: () -> Unit = {}): ScreenCaptureStartupResources =
        transaction.startThroughAuthoritativeStartupGeometry(
            config = ScreenCaptureConfig(metricsProvider = metricsProvider),
            projection = projection,
            onMetricsChanged = onMetricsChanged,
        )

    fun runScheduledCleanup() {
        while (scheduledCleanupBlocks.isNotEmpty()) {
            scheduledCleanupBlocks.removeFirst().invoke()
        }
    }
}

internal class TestMetricsProvider(initialMetrics: CaptureMetrics) : EngineAttachableCaptureMetricsProvider {
    private val mutableMetrics = MutableStateFlow<CaptureMetricsState>(CaptureMetricsState.Available(initialMetrics))

    override val metrics: StateFlow<CaptureMetricsState> = mutableMetrics

    var attachmentDisposeCount = 0
    var attachmentChangedCallback: (() -> Unit)? = null

    val activeCollectorCount: Int
        get() = mutableMetrics.subscriptionCount.value

    override fun attachSessionAttachment(onMetricsChanged: () -> Unit): DisposableHandle {
        attachmentChangedCallback = onMetricsChanged
        return DisposableHandle {
            attachmentChangedCallback = null
            attachmentDisposeCount++
        }
    }

    fun update(metrics: CaptureMetrics) {
        mutableMetrics.value = CaptureMetricsState.Available(metrics)
    }

    fun updateUnavailable(
        reason: CaptureMetricsUnavailableReason = CaptureMetricsUnavailableReason.SourceNotReady,
        message: String? = null,
    ) {
        mutableMetrics.value = CaptureMetricsState.Unavailable(reason = reason, message = message)
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
    override val projectionStopArbiter: ProjectionStopArbiter = ProjectionStopArbiter()
    override val projectionStopObserved: Boolean
        get() = projectionStopArbiter.projectionStopObserved

    var listener: MediaProjectionCallbackAdapter.Listener? = null
    var synchronousEventObserver: ((ProjectionCallbackRawEvent) -> Unit)? = null
    var registerFailure: Throwable? = null
    var emitStopDuringRegister = false
    var closeCount = 0

    private var pendingStopListenerDelivery = false
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
        emitStopRawOnly()
        deliverPendingStopToListener()
    }

    fun emitStopRawOnly() {
        if (!projectionStopArbiter.markRawStopObserved()) return
        pendingStopListenerDelivery = true
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Stop)
    }

    fun deliverPendingStopToListener() {
        if (!pendingStopListenerDelivery) return
        pendingStopListenerDelivery = false
        listener?.onProjectionStopped()
    }

    fun emitResize(width: Int, height: Int) {
        if (projectionStopObserved) return
        val resize = ProjectionCapturedContentResize(id = nextResizeId++, width = width, height = height)
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Resize(resize))
        emitResizeToListener(resize)
    }

    fun emitResizeRawOnly(width: Int, height: Int): ProjectionCapturedContentResize {
        check(!projectionStopObserved)
        val resize = ProjectionCapturedContentResize(id = nextResizeId++, width = width, height = height)
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Resize(resize))
        return resize
    }

    fun emitResizeToListener(resize: ProjectionCapturedContentResize) {
        if (projectionStopObserved) return
        listener?.onCapturedContentResized(resize)
    }

    fun emitVisibility(isVisible: Boolean) {
        if (projectionStopObserved) return
        synchronousEventObserver?.invoke(ProjectionCallbackRawEvent.Visibility(isVisible))
        listener?.onCapturedContentVisibilityChanged(isVisible)
    }
}

internal data object FakeProjectionCallbackHandlerHandle : ProjectionCallbackHandlerHandle

internal class FakeProjectionTargetOwner(
    private val events: MutableList<String>,
) : ProjectionTargetOwnerHandle,
    ProjectionTargetGlCapability,
    ProjectionTargetOwnerAbandonment,
    RuntimeProjectionTargetGlAccess,
    StartupRenderingGlAccess {
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
    var abandonGlLaneCount = 0
        private set
    override val isGlLaneAbandoned: Boolean
        get() = abandonGlLaneCount > 0
    val runtimeOwnerIdentity = RuntimeProjectionTargetOwnerIdentity()
    var installedRuntimeFrameSignalSink: RuntimeFrameSignalSink? = null
    var runtimeFrameSignalInstallCount = 0
    var runtimeFrameSignalClearCount = 0
    var runtimeUpdateTexImageCount = 0
    var runtimeGetTransformMatrixCount = 0
    var runtimeTimestampReadCount = 0
    var runtimeTimestampNanos = 123_456L
    var runtimeOesMatrix: FloatArray = identityMatrix4()
    var invokeRuntimeCancellationWithResult = false
    var suspendRuntimeAccessUntilCancelledWithLateResult = false
    var suspendRuntimeAccessUntilCancelledWithoutLateResult = false

    override fun startupRenderingGlAccess(): StartupRenderingGlAccess = this

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
            owner = this,
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

    override suspend fun withCurrentProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        block: ProjectionTargetGlScope.() -> Unit,
    ) {
        val fakeTarget = target as? FakeProjectionTargetHandle
            ?: error("Projection target is not owned by this FakeProjectionTargetOwner.")
        check(fakeTarget.owner === this) { "Projection target is owned by a different ProjectionTargetOwner." }
        check(fakeTarget.generation == generation) {
            "Projection target generation mismatch. Expected $generation, was ${fakeTarget.generation}."
        }
        check(fakeTarget.closeCount == 0) { "Projection target generation $generation is closed." }
        block(FakeProjectionTargetGlScope(fakeTarget))
    }

    override fun abandonGlLane() {
        abandonGlLaneCount++
    }

    override suspend fun <T> withCurrentStartupRenderingTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit,
        block: StartupRenderingGlScope.() -> T,
    ): T {
        val fakeTarget = target as? FakeProjectionTargetHandle
            ?: error("Projection target is not owned by this FakeProjectionTargetOwner.")
        check(fakeTarget.owner === this) { "Projection target is owned by a different ProjectionTargetOwner." }
        check(fakeTarget.generation == generation) {
            "Projection target generation mismatch. Expected $generation, was ${fakeTarget.generation}."
        }
        check(fakeTarget.closeCount == 0) { "Projection target generation $generation is closed." }
        return block(FakeStartupRenderingGlScope(target = fakeTarget, abandonment = this))
    }

    override suspend fun installRuntimeFrameSignalSink(
        target: ProjectionTargetHandle,
        generation: Long,
        sink: RuntimeFrameSignalSink,
    ) {
        validateRuntimeTarget(target = target, generation = generation)
        runtimeFrameSignalInstallCount++
        installedRuntimeFrameSignalSink = sink
    }

    override suspend fun clearRuntimeFrameSignalSink(target: ProjectionTargetHandle, generation: Long) {
        validateRuntimeTarget(target = target, generation = generation)
        runtimeFrameSignalClearCount++
        installedRuntimeFrameSignalSink = null
    }

    override suspend fun <T> withCurrentRuntimeProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit,
        block: RuntimeProjectionTargetGlScope.() -> T,
    ): T {
        val fakeTarget = validateRuntimeTarget(target = target, generation = generation)
        if (suspendRuntimeAccessUntilCancelledWithoutLateResult) {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    // Deliberately no late result: simulates a GL operation that never reports completion.
                }
            }
        }
        if (suspendRuntimeAccessUntilCancelledWithLateResult) {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    runCatching { block(FakeRuntimeProjectionTargetGlScope(owner = this, target = fakeTarget)) }
                        .onSuccess(onCancellation)
                }
            }
        }
        val result = block(FakeRuntimeProjectionTargetGlScope(owner = this, target = fakeTarget))
        if (invokeRuntimeCancellationWithResult) {
            onCancellation(result)
            throw IllegalStateException("Test runtime cancellation after successful result.")
        }
        return result
    }

    fun emitRuntimeFrameAvailable(target: FakeProjectionTargetHandle = createdHandles.single()) {
        installedRuntimeFrameSignalSink?.enqueueFrameAvailable(generation = target.generation)
    }

    private fun validateRuntimeTarget(target: ProjectionTargetHandle, generation: Long): FakeProjectionTargetHandle {
        val fakeTarget = target as? FakeProjectionTargetHandle
            ?: error("Projection target is not owned by this FakeProjectionTargetOwner.")
        check(fakeTarget.owner === this) { "Projection target is owned by a different ProjectionTargetOwner." }
        check(fakeTarget.generation == generation) {
            "Projection target generation mismatch. Expected $generation, was ${fakeTarget.generation}."
        }
        check(fakeTarget.closeCount == 0) { "Projection target generation $generation is closed." }
        return fakeTarget
    }
}

internal class FakeProjectionTargetHandle(
    val owner: FakeProjectionTargetOwner,
    override val generation: Long,
    override val width: Int,
    override val height: Int,
    override val densityDpi: Int,
) : ProjectionTargetHandle {
    override val surface: ProjectionSurfaceHandle = FakeProjectionSurfaceHandle
    val runtimeTargetIdentity = RuntimeProjectionTargetInstanceIdentity()
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

internal class FakeProjectionTargetGlScope(
    private val target: FakeProjectionTargetHandle,
) : ProjectionTargetGlScope {
    override val generation: Long = target.generation
    override val width: Int = target.width
    override val height: Int = target.height
    override val densityDpi: Int = target.densityDpi

    override fun validateExternalOesTexture() = Unit
}

internal class FakeStartupRenderingGlScope(
    target: FakeProjectionTargetHandle,
    override val abandonment: GlLaneAbandonment,
) : StartupRenderingGlScope {
    override val gl: GlLaneScope = FakeGlLaneScope
    override val projectionTarget: ProjectionTargetGlScope = FakeProjectionTargetGlScope(target)
    override val retirementLane: GlResourceRetirementLane = GlResourceRetirementLane { _, block ->
        block(FakeGlLaneScope)
        true
    }
}

internal class FakeRuntimeProjectionTargetGlScope(
    private val owner: FakeProjectionTargetOwner,
    private val target: FakeProjectionTargetHandle,
) : RuntimeProjectionTargetGlScope {
    override val gl: GlLaneScope = FakeGlLaneScope
    override val generation: Long = target.generation
    override val width: Int = target.width
    override val height: Int = target.height
    override val densityDpi: Int = target.densityDpi
    override val externalOesTexture: RuntimeExternalOesTexture = RuntimeExternalOesTexture(textureId = 77)
    override val projectionTargetIdentity: RuntimeProjectionTargetIdentity =
        RuntimeProjectionTargetIdentity(
            ownerIdentity = owner.runtimeOwnerIdentity,
            targetIdentity = target.runtimeTargetIdentity,
            generation = target.generation,
            externalOesTexture = externalOesTexture,
        )

    override fun updateTexImage() {
        owner.runtimeUpdateTexImageCount++
    }

    override fun getTransformMatrix(destination: FloatArray) {
        owner.runtimeGetTransformMatrixCount++
        owner.runtimeOesMatrix.copyInto(destination, endIndex = 16)
    }

    override fun timestampNanos(): Long {
        owner.runtimeTimestampReadCount++
        return owner.runtimeTimestampNanos
    }
}

private data object FakeGlLaneScope : GlLaneScope {
    override fun targetSizeLimits(): ProjectionTargetSizeLimits =
        ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

    override fun checkCurrentContext(operation: String) = Unit

    override fun checkGl(operation: String) = Unit
}

private fun identityMatrix4(): FloatArray =
    floatArrayOf(
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    )

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
