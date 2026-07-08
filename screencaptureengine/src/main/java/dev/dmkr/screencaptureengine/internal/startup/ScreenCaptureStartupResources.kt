package dev.dmkr.screencaptureengine.internal.startup

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFailureSink
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.scheduleStartupCleanup
import dev.dmkr.screencaptureengine.internal.lifecycle.PreActiveRuntimeOwner
import dev.dmkr.screencaptureengine.internal.platform.metrics.CaptureMetricsObservation
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupProjectionCallbackRouter

/**
 * Move-only resource bag returned after `AuthoritativeStartupGeometryReady`.
 *
 * The bag owns callback routing, metrics observation, the single `VirtualDisplay`, and the current
 * generation-owned projection target until it is closed or transferred to pre-active runtime
 * ownership. Rendering/readback/encoder resources and public session state are outside this bag.
 */
internal class ScreenCaptureStartupResources internal constructor(
    private val callbackRouter: StartupProjectionCallbackRouter,
    private val callbackAdapter: ProjectionCallbackRegistration,
    private val projectionTargetOwner: ProjectionTargetOwnerHandle,
    private val virtualDisplayOwner: ProjectionVirtualDisplayOwner,
    private val currentProjectionTarget: ProjectionTargetHandle,
    internal val startupGeometry: CaptureGeometry,
    internal val milestones: List<ScreenCaptureStartupMilestone>,
    private val metricsObservation: CaptureMetricsObservation,
    private val cleanupScheduler: StartupCleanupScheduler,
    private val cleanupFailureSink: StartupCleanupFailureSink,
    private val newProblem: (ScreenCaptureProblemKind, String, Throwable?) -> ScreenCaptureProblem,
    private val projectionStopObserved: () -> Boolean,
    private val projectionStoppedProblem: () -> ScreenCaptureProblem,
    private val stopProjectionIfRequired: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var state = StartupResourcesState.Open

    internal fun transferToPreActiveRuntimeOwner(): PreActiveRuntimeOwner {
        val transfer = synchronized(lock) {
            check(state == StartupResourcesState.Open) { "ScreenCaptureStartupResources is $state." }
            state = StartupResourcesState.Transferred
            PreActiveRuntimeTransfer(
                callbackRouter = callbackRouter,
                callbackAdapter = callbackAdapter,
                projectionTargetOwner = projectionTargetOwner,
                virtualDisplayOwner = virtualDisplayOwner,
                currentProjectionTarget = currentProjectionTarget,
                startupGeometry = startupGeometry,
                milestones = milestones,
                metricsObservation = metricsObservation,
                cleanupScheduler = cleanupScheduler,
                cleanupFailureSink = cleanupFailureSink,
                newProblem = newProblem,
                projectionStopObserved = projectionStopObserved,
                projectionStoppedProblem = projectionStoppedProblem,
                stopProjectionIfRequired = stopProjectionIfRequired,
            )
        }
        return PreActiveRuntimeOwner(transfer)
    }

    override fun close() {
        synchronized(lock) {
            if (state != StartupResourcesState.Open) return
            state = StartupResourcesState.Closed
        }
        callbackRouter.close()
        runCatching { callbackAdapter.close() }.onFailure(cleanupFailureSink::onCleanupFailure)
        runCatching { metricsObservation.close() }.onFailure(cleanupFailureSink::onCleanupFailure)
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
}

private enum class StartupResourcesState {
    Open,
    Transferred,
    Closed,
}

internal class PreActiveRuntimeTransfer internal constructor(
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
    val newProblem: (ScreenCaptureProblemKind, String, Throwable?) -> ScreenCaptureProblem,
    val projectionStopObserved: () -> Boolean,
    val projectionStoppedProblem: () -> ScreenCaptureProblem,
    val stopProjectionIfRequired: () -> Unit,
)
