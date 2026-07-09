package dev.dmkr.screencaptureengine.internal

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.FrameSubscription
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureEngine
import dev.dmkr.screencaptureengine.ScreenCaptureEvent
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSession
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.ScreenCaptureStats
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparer
import dev.dmkr.screencaptureengine.internal.encoding.provider.ProviderPreparationContext
import dev.dmkr.screencaptureengine.internal.gl.DefaultStartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFallbackCompletion
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.lifecycle.InitialActivationCommitBoundary
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionHandle
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.rendering.es2.ImageEncoderPrepareOperation
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureEngineOwnedContext
import dev.dmkr.screencaptureengine.internal.startup.ScreenCaptureStartupTransaction
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class DefaultScreenCaptureEngine internal constructor(
    context: Context,
    startupCleanupScheduler: StartupCleanupScheduler = DefaultStartupCleanupScheduler,
    private val startupTransactionFactory: (StartupCleanupScheduler) -> ScreenCaptureStartupTransaction = { cleanupScheduler ->
        ScreenCaptureStartupTransaction(cleanupScheduler = cleanupScheduler)
    },
    private val renderingPipelinePreparerFactory: (ProviderPreparationContext) -> RenderingPipelinePreparer = ::defaultRenderingPipelinePreparer,
    private val commitBoundaryFactory: () -> InitialActivationCommitBoundary = { InitialActivationCommitBoundary() },
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ScreenCaptureEngine {
    private val applicationContext: Context = requireNotNull(context.applicationContext) {
        "Context.applicationContext must be available."
    }

    @Suppress("unused")
    internal val applicationContextForTesting: Context = applicationContext

    private val slotLock = Any()
    private val providerContextLock = Any()
    private val problemSequence = AtomicLong()
    private val slotSequence = AtomicLong()
    private val cleanupTracker = EngineCleanupTracker(
        idleStateChanged = { tryShutdownProviderPreparationContextIfIdle() },
        isSessionSlotActive = { synchronized(slotLock) { activeSlot != null } },
    )
    private val trackedStartupCleanupScheduler = cleanupTracker.trackingScheduler(startupCleanupScheduler)
    private var activeSlot: EngineSessionSlot? = null

    // Engine-scoped and lazy; retained after successful startup because returned sessions may use
    // prepared provider resources.
    private var providerPreparationContext: ProviderPreparationContext? = null

    override suspend fun startSession(
        config: ScreenCaptureConfig,
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters,
    ): ScreenCaptureSession =
        startSession(
            config = config,
            projection = MediaProjectionHandle(mediaProjection),
            initialParameters = initialParameters,
        )

    internal suspend fun startSession(
        config: ScreenCaptureConfig,
        projection: ProjectionHandle,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): ScreenCaptureSession {
        check(!ScreenCaptureEngineOwnedContext.isCurrent) {
            "startSession must not be called from engine-owned execution contexts."
        }
        val slot = reserveSessionSlot()
        var ownerToClose: AutoCloseable? = null
        try {
            val startupResources = startupTransactionFactory(trackedStartupCleanupScheduler).startThroughAuthoritativeStartupGeometry(
                config = config,
                projection = projection,
                initialParameters = initialParameters,
            )
            ownerToClose = startupResources
            val preActiveOwner = startupResources.transferToPreActiveRuntimeOwner()
            ownerToClose = preActiveOwner
            val preparedPlan = preActiveOwner.prepareInitialActivePlan(
                config = config,
                initialParameters = initialParameters,
            )
            val preparedResources = preActiveOwner.prepareInitialRenderingPipeline(
                preparedPlan = preparedPlan,
                preparer = renderingPipelinePreparerFactory(providerPreparationContext()),
            )
            val initialOwner = preActiveOwner.transferToInitialRuntimeResourceOwner(
                preparedPlan = preparedPlan,
                preparedResources = preparedResources,
            )
            ownerToClose = initialOwner
            val activeOwner = initialOwner.transferToActiveRuntimeOwner(
                config = config,
                commitBoundary = commitBoundaryFactory(),
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                terminalCommitHandler = { releaseSessionSlot(slot) },
                terminalCleanupFenceFactory = cleanupTracker::openTerminalCleanupFence,
            )
            ownerToClose = activeOwner
            val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
            val returnedSession = ReturnArmingScreenCaptureSession(
                delegate = session,
                armReturnedSessionRuntimeSignals = activeOwner::armReturnedSessionRuntimeSignals,
            )
            ownerToClose = null
            return returnedSession
        } catch (cause: Throwable) {
            ownerToClose?.let { owner ->
                runCatching { owner.close() }.onFailure(cause::addSuppressed)
            }
            releaseSessionSlot(slot)
            throw cause
        }
    }

    @Suppress("unused")
    internal val isProviderPreparationContextCreatedForTesting: Boolean
        get() = synchronized(providerContextLock) { providerPreparationContext != null }

    @Suppress("unused")
    internal fun closeProviderPreparationContextIfNoSessionSlotForTesting() {
        val closedOrAbsent = synchronized(slotLock) {
            check(activeSlot == null) {
                "ProviderPreparationContext can only be closed when the engine has no startup or active session."
            }
            tryShutdownProviderPreparationContextIfIdle()
        }
        check(closedOrAbsent) {
            "ProviderPreparationContext can only be closed when provider and cleanup work are idle."
        }
    }

    private fun providerPreparationContext(): ProviderPreparationContext =
        synchronized(providerContextLock) {
            providerPreparationContext ?: ProviderPreparationContext(
                idleStateChanged = ::tryShutdownProviderPreparationContextIfIdle,
            ).also { providerPreparationContext = it }
        }

    private fun reserveSessionSlot(): EngineSessionSlot =
        synchronized(slotLock) {
            activeSlot?.let {
                throw ScreenCaptureStartException(
                    requiresFreshProjection = false,
                    problem = newSessionAlreadyActiveProblem(),
                )
            }
            EngineSessionSlot(id = slotSequence.incrementAndGet()).also { activeSlot = it }
        }

    private fun releaseSessionSlot(slot: EngineSessionSlot) {
        val released = synchronized(slotLock) {
            if (activeSlot === slot) {
                activeSlot = null
                true
            } else {
                false
            }
        }
        if (released) {
            cleanupTracker.onSessionSlotReleased()
        }
    }

    private fun tryShutdownProviderPreparationContextIfIdle(): Boolean =
        synchronized(slotLock) {
            // Do not close from local success/finally paths; shutdown requires no active slot,
            // provider work, or cleanup fences.
            if (activeSlot != null || cleanupTracker.hasPendingCleanupWork) {
                false
            } else {
                synchronized(providerContextLock) {
                    if (cleanupTracker.hasPendingCleanupWork) {
                        false
                    } else {
                        val context = providerPreparationContext
                        if (context == null) {
                            true
                        } else if (context.closeIfIdle()) {
                            providerPreparationContext = null
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

    private fun newSessionAlreadyActiveProblem(): ScreenCaptureProblem =
        ScreenCaptureProblem(
            sequence = problemSequence.incrementAndGet(),
            kind = ScreenCaptureProblemKind.EngineSessionAlreadyActive,
            message = "This ScreenCaptureEngine already has a non-terminal session.",
            cause = null,
        )
}

private fun defaultRenderingPipelinePreparer(providerContext: ProviderPreparationContext): RenderingPipelinePreparer =
    Es2RenderingPipelinePreparer(
        encoderPrepare = ImageEncoderPrepareOperation(ImageEncoderPreparer(providerContext)::prepare),
    )

internal class ReturnArmingScreenCaptureSession(
    private val delegate: ScreenCaptureSession,
    armReturnedSessionRuntimeSignals: () -> Unit,
) : ScreenCaptureSession {
    private val armed = AtomicBoolean(false)
    private val armOnce: () -> Unit = {
        if (armed.compareAndSet(false, true)) {
            armReturnedSessionRuntimeSignals()
        }
    }

    override val state: StateFlow<ScreenCaptureSessionState> = ReturnArmingStateFlow(delegate.state, armOnce)
    override val stats: StateFlow<ScreenCaptureStats> = ReturnArmingStateFlow(delegate.stats, armOnce)
    override val events: SharedFlow<ScreenCaptureEvent> = ReturnArmingSharedFlow(delegate.events, armOnce)

    override suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult {
        armOnce()
        return delegate.setParameters(parameters)
    }

    override fun trimMemory(level: Int) {
        armOnce()
        delegate.trimMemory(level)
    }

    override fun stop() {
        armOnce()
        delegate.stop()
    }

    override fun close() {
        armOnce()
        delegate.close()
    }

    override fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription {
        return try {
            delegate.onFrame(callback)
        } finally {
            armOnce()
        }
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ReturnArmingStateFlow<T>(
    private val delegate: StateFlow<T>,
    private val armReturnedSessionRuntimeSignals: () -> Unit,
) : StateFlow<T> {
    override val replayCache: List<T>
        get() {
            val cache = delegate.replayCache
            armReturnedSessionRuntimeSignals()
            return cache
        }

    override val value: T
        get() {
            val current = delegate.value
            armReturnedSessionRuntimeSignals()
            return current
        }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        val initial = delegate.value
        try {
            collector.emit(initial)
        } finally {
            armReturnedSessionRuntimeSignals()
        }
        var skipInitialReplay = true
        delegate.collect { value ->
            if (skipInitialReplay && value == initial) {
                skipInitialReplay = false
            } else {
                skipInitialReplay = false
                collector.emit(value)
            }
        }
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ReturnArmingSharedFlow<T>(
    private val delegate: SharedFlow<T>,
    private val armReturnedSessionRuntimeSignals: () -> Unit,
) : SharedFlow<T> {
    override val replayCache: List<T>
        get() {
            val cache = delegate.replayCache
            armReturnedSessionRuntimeSignals()
            return cache
        }

    override suspend fun collect(collector: FlowCollector<T>): Nothing =
        delegate
            .onSubscription { armReturnedSessionRuntimeSignals() }
            .collect(collector)
}

private class EngineSessionSlot(
    val id: Long,
)

private class EngineCleanupTracker(
    private val idleStateChanged: () -> Unit,
    private val isSessionSlotActive: () -> Boolean,
) {
    private val pendingCleanupTasks = AtomicInteger(0)
    private val pendingTerminalCleanupFences = AtomicInteger(0)
    private val cleanupCompletedWhileSlotActive = AtomicBoolean(false)

    val hasPendingCleanupWork: Boolean
        get() = pendingCleanupTasks.get() > 0 || pendingTerminalCleanupFences.get() > 0

    fun openTerminalCleanupFence(): AutoCloseable {
        pendingTerminalCleanupFences.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                pendingTerminalCleanupFences.decrementAndGet()
                idleStateChanged()
            }
        }
    }

    fun trackingScheduler(delegate: StartupCleanupScheduler): StartupCleanupScheduler =
        object : StartupCleanupScheduler, StartupCleanupFallbackCompletion {
            override fun schedule(block: () -> Unit) {
                pendingCleanupTasks.incrementAndGet()
                delegate.schedule {
                    try {
                        block()
                    } finally {
                        finishCleanupTask()
                    }
                }
            }

            override fun onStartupCleanupFallbackComplete() {
                finishCleanupTask()
            }
        }

    fun onSessionSlotReleased() {
        if (cleanupCompletedWhileSlotActive.compareAndSet(true, false)) {
            idleStateChanged()
        }
    }

    private fun finishCleanupTask() {
        pendingCleanupTasks.decrementAndGet()
        if (isSessionSlotActive()) {
            cleanupCompletedWhileSlotActive.set(true)
        }
        idleStateChanged()
    }
}
