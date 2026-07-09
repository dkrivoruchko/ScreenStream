package dev.dmkr.screencaptureengine.internal.lifecycle

import android.os.SystemClock
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFailureSink
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.scheduleStartupCleanup
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.metrics.CaptureMetricsObservation
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionCallbackAdapter
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupProjectionCallbackRouter
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackage
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.ActiveRuntimePreparedRenderingPipelineResources
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.InitialRuntimePreparedRenderingPipelineResources
import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionTerminalCommit
import dev.dmkr.screencaptureengine.internal.startup.ScreenCaptureStartupMilestone
import dev.dmkr.screencaptureengine.internal.startup.StartupRuntimePendingSignals
import dev.dmkr.screencaptureengine.internal.startup.StartupToRuntimeSignalMailbox

/**
 * Move-only owner for resources handed off after pre-active plan preparation.
 *
 * This object owns the callback route, callback registration, metrics observation, single
 * `VirtualDisplay`, current generation-owned projection target, prepared rendering pipeline
 * resources, cleanup policy, and initial plan snapshots. It is still internal ownership: it does
 * not publish public state, expose a session, or run the render loop.
 *
 * [initialPendingSignals] is the exactly-once startup-to-runtime handoff snapshot. Pending
 * geometry and density are carried without mutating the frozen initial snapshots. The latest
 * visibility value is retained as initial state and is not replayed as a runtime visibility update.
 * After [transferToActiveRuntimeOwner] succeeds, this owner is inert and must not close or reuse any
 * moved runtime resources.
 */
internal class InitialRuntimeResourceOwner internal constructor(
    transfer: InitialRuntimeResourceTransfer,
) : AutoCloseable, MediaProjectionCallbackAdapter.Listener {
    private val lock = Any()
    private val callbackRouter = transfer.callbackRouter
    private val callbackAdapter = transfer.callbackAdapter
    private val projectionTargetOwner = transfer.projectionTargetOwner
    private val virtualDisplayOwner = transfer.virtualDisplayOwner
    private val currentProjectionTarget = transfer.currentProjectionTarget
    private val metricsObservation = transfer.metricsObservation
    private val cleanupScheduler = transfer.cleanupScheduler
    private val cleanupFailureSink = transfer.cleanupFailureSink
    private val projectionStopObserved = transfer.projectionStopObserved
    private val stopProjectionIfRequired = transfer.stopProjectionIfRequired
    private val preparedRenderingPipelineResources = transfer.preparedRenderingPipelineResources
    private val signalMailbox = StartupToRuntimeSignalMailbox(startupGeometry = transfer.startupGeometry)
    private var state = InitialRuntimeResourceOwnerState.Open

    internal val startupGeometry: CaptureGeometry = transfer.startupGeometry
    internal val initialOutputPlan: ScreenCaptureOutputPlan = transfer.initialOutputPlan

    @Suppress("unused") // Read by lifecycle tests to verify initial rendering handoff identity.
    internal val initialRenderTransformPackage: FirstPlanRenderTransformPackage =
        preparedRenderingPipelineResources.renderTransformPackage
    internal val initialPendingSignals: StartupRuntimePendingSignals = transfer.pendingSignals

    @Suppress("unused") // Read by lifecycle tests to inspect pending startup-to-runtime signals.
    internal fun pendingSignalsSnapshot(): StartupRuntimePendingSignals =
        synchronized(lock) {
            checkOpenLocked()
            signalMailbox.snapshot(
                latestMetrics = metricsObservation.latestMetrics,
                projectionStopObserved = isProjectionStoppedLocked(),
            )
        }

    private fun isProjectionStoppedLocked(): Boolean =
        projectionStopObserved() || callbackAdapter.projectionStopObserved

    override fun onProjectionStopped() {
        synchronized(lock) {
            if (state != InitialRuntimeResourceOwnerState.Open) return
            signalMailbox.recordProjectionStopped()
        }
    }

    override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        synchronized(lock) {
            if (state != InitialRuntimeResourceOwnerState.Open) return
            signalMailbox.recordCapturedContentResize(resize)
        }
    }

    override fun onCapturedContentResized(width: Int, height: Int) {
        onCapturedContentResized(ProjectionCapturedContentResize(id = 0L, width = width, height = height))
    }

    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        synchronized(lock) {
            if (state != InitialRuntimeResourceOwnerState.Open) return
            signalMailbox.recordCapturedContentVisibility(isVisible)
        }
    }

    internal fun transferToActiveRuntimeOwner(
        config: ScreenCaptureConfig,
        commitBoundary: InitialActivationCommitBoundary = InitialActivationCommitBoundary(),
        elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
        terminalCommitHandler: (ScreenCaptureSessionTerminalCommit) -> Unit = {},
        terminalCleanupFenceFactory: () -> AutoCloseable = { AutoCloseable {} },
    ): ActiveRuntimeOwner {
        var movedPreparedResourcesToRetire: ActiveRuntimePreparedRenderingPipelineResources? = null
        return try {
            synchronized(lock) {
                checkOpenLocked()
                val movedResources = preparedRenderingPipelineResources.moveToActiveRuntimeOwner()
                movedPreparedResourcesToRetire = movedResources
                val pendingSignals = initialPendingSignals.mergeWith(
                    signalMailbox.drain(
                        latestMetrics = metricsObservation.latestMetrics,
                        projectionStopObserved = isProjectionStoppedLocked(),
                    )
                )
                val owner = ActiveRuntimeOwner(
                    transfer = ActiveRuntimeTransfer(
                        config = config,
                        callbackRouter = callbackRouter,
                        callbackAdapter = callbackAdapter,
                        projectionTargetOwner = projectionTargetOwner,
                        virtualDisplayOwner = virtualDisplayOwner,
                        currentProjectionTarget = currentProjectionTarget,
                        startupGeometry = startupGeometry,
                        metricsObservation = metricsObservation,
                        cleanupScheduler = cleanupScheduler,
                        cleanupFailureSink = cleanupFailureSink,
                        projectionStopObserved = projectionStopObserved,
                        stopProjectionIfRequired = stopProjectionIfRequired,
                        preparedRenderingPipelineResources = movedResources,
                        initialOutputPlan = initialOutputPlan,
                        pendingSignals = pendingSignals,
                        expectedCurrentListener = this,
                    ),
                    commitBoundary = commitBoundary,
                    elapsedRealtimeNanos = elapsedRealtimeNanos,
                    terminalCommitHandler = terminalCommitHandler,
                    terminalCleanupFenceFactory = terminalCleanupFenceFactory,
                )
                state = InitialRuntimeResourceOwnerState.Transferred
                movedPreparedResourcesToRetire = null
                owner
            }
        } catch (cause: Throwable) {
            movedPreparedResourcesToRetire?.let { resources ->
                runCatching { resources.close() }.onFailure(::reportCleanupFailure)
            }
            throw cause
        }
    }

    override fun close() {
        synchronized(lock) {
            if (state != InitialRuntimeResourceOwnerState.Open) return
            state = InitialRuntimeResourceOwnerState.Closed
        }
        callbackRouter.close()
        runCatching { callbackAdapter.close() }.onFailure(::reportCleanupFailure)
        runCatching { metricsObservation.close() }.onFailure(::reportCleanupFailure)
        stopProjectionIfRequired()
        scheduleStartupCleanup(
            cleanupScheduler = cleanupScheduler,
            cleanupFailureSink = cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            cleanupFailures.collect { preparedRenderingPipelineResources.close() }
            cleanupFailures.collect { virtualDisplayOwner.close() }
            cleanupFailures.collect { projectionTargetOwner.close() }
            cleanupFailures.throwIfAny()
        }
    }

    private fun reportCleanupFailure(failure: Throwable) {
        runCatching { cleanupFailureSink.onCleanupFailure(failure) }
    }

    private fun checkOpenLocked() {
        check(state == InitialRuntimeResourceOwnerState.Open) { "InitialRuntimeResourceOwner is $state." }
    }
}

private enum class InitialRuntimeResourceOwnerState {
    Open,
    Transferred,
    Closed,
}

private fun StartupRuntimePendingSignals.mergeWith(
    later: StartupRuntimePendingSignals,
): StartupRuntimePendingSignals =
    StartupRuntimePendingSignals(
        projectionStopObserved = projectionStopObserved || later.projectionStopObserved,
        pendingCapturedContentResize = later.pendingCapturedContentResize ?: pendingCapturedContentResize,
        latestCaptureMetrics = later.latestCaptureMetrics,
        pendingCaptureGeometry = later.pendingCaptureGeometry ?: pendingCaptureGeometry,
        latestCapturedContentVisible = later.latestCapturedContentVisible ?: latestCapturedContentVisible,
        metricsObservationChanged = metricsObservationChanged || later.metricsObservationChanged,
    )

internal class InitialRuntimeResourceTransfer internal constructor(
    val callbackRouter: StartupProjectionCallbackRouter,
    val callbackAdapter: ProjectionCallbackRegistration,
    val projectionTargetOwner: ProjectionTargetOwnerHandle,
    val virtualDisplayOwner: ProjectionVirtualDisplayOwner,
    val currentProjectionTarget: ProjectionTargetHandle,
    val startupGeometry: CaptureGeometry,
    @Suppress("UNUSED_PARAMETER") // Kept for current handoff construction shape; no owner accessor remains.
    milestones: List<ScreenCaptureStartupMilestone>,
    val metricsObservation: CaptureMetricsObservation,
    val cleanupScheduler: StartupCleanupScheduler,
    val cleanupFailureSink: StartupCleanupFailureSink,
    val projectionStopObserved: () -> Boolean,
    val stopProjectionIfRequired: () -> Unit,
    val preparedRenderingPipelineResources: InitialRuntimePreparedRenderingPipelineResources,
    val initialOutputPlan: ScreenCaptureOutputPlan,
    @Suppress("UNUSED_PARAMETER") // Kept for current handoff construction shape; no owner accessor remains.
    initialProjectionTarget: ProjectionTargetSnapshot,
    val pendingSignals: StartupRuntimePendingSignals,
)
