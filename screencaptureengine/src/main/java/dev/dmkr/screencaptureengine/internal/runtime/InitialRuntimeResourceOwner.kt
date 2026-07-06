package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan

/**
 * Move-only owner for resources handed off after pre-active plan preparation.
 *
 * This object owns the callback route, callback registration, metrics observation, single
 * `VirtualDisplay`, current generation-owned projection target, cleanup policy, and initial plan
 * snapshots until public runtime integration consumes them. It is still internal ownership: it does
 * not publish public state, prepare rendering/readback/encoder resources, or itself mark
 * `InitialActivePlanCommitted`.
 *
 * [initialPendingSignals] is the exactly-once startup-to-runtime handoff snapshot. Pending
 * geometry and density represent runtime work after the first Active commit. The latest visibility
 * value initializes the first public `capturedContentVisible` value and is not a replayed runtime
 * visibility update.
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
    private val signalMailbox = StartupToRuntimeSignalMailbox(startupGeometry = transfer.startupGeometry)
    private var closed = false

    internal val startupGeometry: CaptureGeometry = transfer.startupGeometry
    internal val milestones: List<ScreenCaptureStartupMilestone> = transfer.milestones
    internal val initialOutputPlan: ScreenCaptureOutputPlan = transfer.initialOutputPlan
    internal val initialProjectionTarget: ProjectionTargetSnapshot = transfer.initialProjectionTarget
    internal val initialPendingSignals: StartupRuntimePendingSignals = transfer.pendingSignals
    internal val currentProjectionTargetSnapshot: ProjectionTargetSnapshot
        get() = currentProjectionTarget.snapshot()

    internal val latestCaptureMetrics: CaptureMetrics
        get() {
            synchronized(lock) {
                checkOpenLocked()
                return metricsObservation.latestMetrics
            }
        }

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
            if (closed) return
            signalMailbox.recordProjectionStopped()
        }
    }

    override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        synchronized(lock) {
            if (closed) return
            signalMailbox.recordCapturedContentResize(resize)
        }
    }

    override fun onCapturedContentResized(width: Int, height: Int) {
        onCapturedContentResized(ProjectionCapturedContentResize(id = 0L, width = width, height = height))
    }

    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        synchronized(lock) {
            if (closed) return
            signalMailbox.recordCapturedContentVisibility(isVisible)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
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
            cleanupFailures.collect { virtualDisplayOwner.close() }
            cleanupFailures.collect { projectionTargetOwner.close() }
            cleanupFailures.throwIfAny()
        }
    }

    private fun reportCleanupFailure(failure: Throwable) {
        runCatching { cleanupFailureSink.onCleanupFailure(failure) }
    }

    private fun checkOpenLocked() {
        check(!closed) { "InitialRuntimeResourceOwner is closed." }
    }
}

internal class InitialRuntimeResourceTransfer internal constructor(
    val callbackRouter: StartupProjectionCallbackRouter,
    val callbackAdapter: ProjectionCallbackRegistration,
    val projectionTargetOwner: ProjectionTargetOwnerHandle,
    val virtualDisplayOwner: ProjectionVirtualDisplayOwner,
    val currentProjectionTarget: ProjectionTargetHandle,
    val startupGeometry: CaptureGeometry,
    val milestones: List<ScreenCaptureStartupMilestone>,
    val metricsObservation: CaptureMetricsObservation,
    val cleanupScheduler: StartupCleanupScheduler,
    val cleanupFailureSink: StartupCleanupFailureSink,
    val projectionStopObserved: () -> Boolean,
    val stopProjectionIfRequired: () -> Unit,
    val initialOutputPlan: ScreenCaptureOutputPlan,
    val initialProjectionTarget: ProjectionTargetSnapshot,
    val pendingSignals: StartupRuntimePendingSignals,
)
