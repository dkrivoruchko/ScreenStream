package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanResult
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns startup resources after `AuthoritativeStartupGeometryReady` and before public activation.
 *
 * This owner prepares the initial Active output plan and authoritative generation-owned projection
 * target from the frozen startup geometry. It does not prepare rendering/readback/encoder
 * resources, publish public state, or commit `InitialActivePlanCommitted`. Resize and metrics
 * changes observed after the startup geometry freeze are retained as pending runtime signals.
 * Visibility is retained as the latest initial `capturedContentVisible` value for the later public
 * commit, not as a replayed runtime visibility update.
 */
internal class PreActiveRuntimeOwner internal constructor(
    transfer: PreActiveRuntimeTransfer,
) : AutoCloseable, StartupProjectionCallbackRouter.SelectedRuntimeListener {
    private val ownerToken = Any()
    private val callbackRouter = transfer.callbackRouter
    private val callbackAdapter = transfer.callbackAdapter
    private val projectionTargetOwner = transfer.projectionTargetOwner
    private val virtualDisplayOwner = transfer.virtualDisplayOwner
    private val metricsObservation = transfer.metricsObservation
    private val cleanupScheduler = transfer.cleanupScheduler
    private val cleanupFailureSink = transfer.cleanupFailureSink
    private val newProblem = transfer.newProblem
    private val projectionStopObserved = transfer.projectionStopObserved
    private val projectionStoppedProblem = transfer.projectionStoppedProblem
    private val stopProjectionIfRequired = transfer.stopProjectionIfRequired
    internal val startupGeometry: CaptureGeometry = transfer.startupGeometry
    internal val milestones: List<ScreenCaptureStartupMilestone> = transfer.milestones

    private val lock = Any()
    private var state: PreActiveRuntimeOwnerState = PreActiveRuntimeOwnerState.Open
    private var currentProjectionTarget = transfer.currentProjectionTarget
    private var runtimeProjectionStopObserved = false
    private var initialPlanPreparationStarted = false
    private var initialPlanToken = 0L
    private var transferredRuntimeOwner: InitialRuntimeResourceOwner? = null
    private val signalMailbox = StartupToRuntimeSignalMailbox(startupGeometry = startupGeometry)

    init {
        callbackRouter.handoffTo(this)
    }

    internal val latestCaptureMetrics: CaptureMetrics
        get() {
            checkOpen()
            return metricsObservation.latestMetrics
        }

    internal suspend fun prepareInitialActivePlan(
        config: ScreenCaptureConfig,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): PreActiveInitialRuntimePlan =
        try {
            prepareInitialActivePlanOrThrow(config = config, initialParameters = initialParameters)
        } catch (cause: CancellationException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: ScreenCaptureStartException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            val exception = startException(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Pre-active runtime preparation failed.",
                cause = cause,
            )
            closeAfterStartupFailure(exception)
            throw exception
        }

    private suspend fun prepareInitialActivePlanOrThrow(
        config: ScreenCaptureConfig,
        initialParameters: ScreenCaptureParameters,
    ): PreActiveInitialRuntimePlan {
        val coroutineContext = currentCoroutineContext()
        beginInitialPlanPreparation()
        coroutineContext.ensureActive()

        val targetSizeLimits = try {
            projectionTargetOwner.targetSizeLimits()
        } catch (cause: Throwable) {
            coroutineContext.ensureActive()
            if (isProjectionStopped()) throw projectionStoppedException()
            throw startException(
                kind = ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed,
                message = "Projection target limits could not be queried.",
                cause = cause,
            )
        }
        throwIfProjectionStopped()
        coroutineContext.ensureActive()
        val planner = ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = config.maxOutputPixels,
                maxEncodedBytes = config.maxEncodedBytes,
                maxCaptureTargetWidth = targetSizeLimits.maxWidth,
                maxCaptureTargetHeight = targetSizeLimits.maxHeight,
            ),
        )
        val planResult = planner.plan(geometry = startupGeometry, parameters = initialParameters)
        throwIfProjectionStopped()
        coroutineContext.ensureActive()
        val plan = when (planResult) {
            is OutputPlanResult.Success -> planResult.plan
            is OutputPlanResult.Failure -> throw startException(kind = planResult.kind, message = planResult.message, cause = null)
        }

        throwIfProjectionStopped()
        coroutineContext.ensureActive()

        bindVirtualDisplayToPlan(plan)

        throwIfProjectionStopped()
        coroutineContext.ensureActive()

        return buildInitialRuntimePlan(plan)
    }

    private fun beginInitialPlanPreparation() {
        synchronized(lock) {
            check(state == PreActiveRuntimeOwnerState.Open) { "PreActiveRuntimeOwner is $state." }
            check(!initialPlanPreparationStarted) { "Initial active plan preparation was already started." }
            if (isProjectionStoppedLocked()) throw projectionStoppedException()
            initialPlanPreparationStarted = true
            initialPlanToken = Math.addExact(initialPlanToken, 1L)
        }
    }

    private fun buildInitialRuntimePlan(plan: ScreenCaptureOutputPlan): PreActiveInitialRuntimePlan =
        synchronized(lock) {
            check(state == PreActiveRuntimeOwnerState.Open) { "PreActiveRuntimeOwner is $state." }
            if (isProjectionStoppedLocked()) throw projectionStoppedException()
            PreActiveInitialRuntimePlan(
                ownerToken = ownerToken,
                planToken = initialPlanToken,
                outputPlan = plan,
                projectionTarget = currentProjectionTarget.snapshot(),
            )
        }

    internal suspend fun transferToInitialRuntimeResourceOwner(
        preparedPlan: PreActiveInitialRuntimePlan,
    ): InitialRuntimeResourceOwner =
        try {
            transferToInitialRuntimeResourceOwnerOrThrow(preparedPlan)
        } catch (cause: CancellationException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: ScreenCaptureStartException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            val exception = startException(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Initial runtime ownership handoff failed.",
                cause = cause,
            )
            closeAfterStartupFailure(exception)
            throw exception
        }

    private suspend fun transferToInitialRuntimeResourceOwnerOrThrow(
        preparedPlan: PreActiveInitialRuntimePlan,
    ): InitialRuntimeResourceOwner {
        val coroutineContext = currentCoroutineContext()
        val runtimeOwner = synchronized(lock) {
            when (
                val decision = InitialRuntimeHandoffGate.decide(
                    InitialRuntimeHandoffGateSnapshot(
                        ownerOpen = state == PreActiveRuntimeOwnerState.Open,
                        ownerStateDescription = state.toString(),
                        planOwnerMatches = preparedPlan.ownerToken === ownerToken,
                        planTokenMatches = preparedPlan.planToken == initialPlanToken,
                        targetGenerationMatches = currentProjectionTarget.generation == preparedPlan.projectionTarget.generation,
                        projectionStopped = isProjectionStoppedLocked(),
                        callerActive = coroutineContext[Job]?.isActive != false,
                    ),
                )
            ) {
                InitialRuntimeHandoffDecision.Ready -> Unit
                InitialRuntimeHandoffDecision.ProjectionStopped -> throw projectionStoppedException()
                InitialRuntimeHandoffDecision.CallerCancelled -> coroutineContext.ensureActive()
                is InitialRuntimeHandoffDecision.InvariantViolation -> error(decision.message)
            }
            val pendingSignals = signalMailbox.drain(
                latestMetrics = metricsObservation.latestMetrics,
                projectionStopObserved = false,
            )
            val owner = InitialRuntimeResourceOwner(
                transfer = InitialRuntimeResourceTransfer(
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
                    projectionStopObserved = projectionStopObserved,
                    stopProjectionIfRequired = stopProjectionIfRequired,
                    initialOutputPlan = preparedPlan.outputPlan,
                    initialProjectionTarget = preparedPlan.projectionTarget,
                    pendingSignals = pendingSignals,
                ),
            )
            callbackRouter.replaceRuntimeListener(expectedCurrent = this, replacement = owner)
            transferredRuntimeOwner = owner
            state = PreActiveRuntimeOwnerState.Transferred
            owner
        }
        return runtimeOwner
    }

    private suspend fun bindVirtualDisplayToPlan(plan: ScreenCaptureOutputPlan) {
        if (currentProjectionTargetMatches(plan)) return
        var firstFailure: Throwable? = null
        repeat(PRE_ACTIVE_TARGET_BIND_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            checkOpen()
            val candidate = try {
                projectionTargetOwner.createTarget(
                    width = plan.captureTarget.width,
                    height = plan.captureTarget.height,
                    densityDpi = plan.captureGeometry.densityDpi,
                )
            } catch (cause: Throwable) {
                firstFailure = firstFailure.collectFailure(cause)
                if (isProjectionStopped()) throw projectionStoppedException()
                return@repeat
            }
            retireTargetIfMovedOrClosed(candidate)
            if (isProjectionStopped()) {
                retireTarget(candidate)
                throw projectionStoppedException()
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cause: CancellationException) {
                retireTarget(candidate)
                throw cause
            }

            val previousTarget = try {
                checkOpen()
                virtualDisplayOwner.bindTarget(candidate)
            } catch (cause: Throwable) {
                firstFailure = firstFailure.collectFailure(cause)
                retireTarget(candidate)
                if (isProjectionStopped()) throw projectionStoppedException()
                if (virtualDisplayOwner.isClosed) {
                    throw startException(
                        kind = ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed,
                        message = "Projection target surface could not be prepared for the initial output plan.",
                        cause = firstFailure,
                    )
                }
                return@repeat
            }
            synchronized(lock) {
                checkOpenLocked()
                currentProjectionTarget = candidate
            }
            previousTarget?.let(::retireTarget)
            checkOpen()
            return
        }
        currentCoroutineContext().ensureActive()
        if (currentProjectionTargetMatches(plan)) return
        if (isProjectionStopped()) throw projectionStoppedException()
        throw startException(
            kind = ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed,
            message = "Projection target surface could not be prepared for the initial output plan.",
            cause = firstFailure,
        )
    }

    private fun retireTarget(target: ProjectionTargetHandle) {
        runCatching { target.close() }
            .onFailure(::reportCleanupFailure)
    }

    private fun throwIfProjectionStopped() {
        checkOpen()
        if (isProjectionStopped()) throw projectionStoppedException()
    }

    private fun isProjectionStopped(): Boolean =
        synchronized(lock) { isProjectionStoppedLocked() }

    private fun isProjectionStoppedLocked(): Boolean =
        projectionStopObserved() || callbackAdapter.projectionStopObserved || runtimeProjectionStopObserved

    private fun projectionStoppedException(): ScreenCaptureStartException =
        ScreenCaptureStartException(requiresFreshProjection = true, problem = projectionStoppedProblem())

    private fun startException(kind: ScreenCaptureProblemKind, message: String, cause: Throwable?): ScreenCaptureStartException =
        ScreenCaptureStartException(
            requiresFreshProjection = true,
            problem = newProblem(kind, message, cause),
        )

    private fun closeAfterStartupFailure(primary: Throwable) {
        runCatching { close() }
            .onFailure(primary::addSuppressed)
    }

    private fun reportCleanupFailure(failure: Throwable) {
        runCatching { cleanupFailureSink.onCleanupFailure(failure) }
    }

    private fun checkOpen() {
        synchronized(lock) {
            checkOpenLocked()
        }
    }

    private fun checkOpenLocked() {
        check(state == PreActiveRuntimeOwnerState.Open) { "PreActiveRuntimeOwner is $state." }
    }

    private fun currentProjectionTargetMatches(plan: ScreenCaptureOutputPlan): Boolean =
        synchronized(lock) {
            checkOpenLocked()
            currentProjectionTarget.matches(plan)
        }

    private fun retireTargetIfMovedOrClosed(target: ProjectionTargetHandle) {
        synchronized(lock) {
            if (state == PreActiveRuntimeOwnerState.Open) return
        }
        retireTarget(target)
        checkOpen()
    }

    override fun onProjectionStopped() {
        handleProjectionStopped(forwardAfterTransfer = false)
    }

    override fun onRouterSelectedProjectionStopped() {
        handleProjectionStopped(forwardAfterTransfer = true)
    }

    override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        handleCapturedContentResized(resize = resize, forwardAfterTransfer = false)
    }

    override fun onCapturedContentResized(width: Int, height: Int) {
        onCapturedContentResized(ProjectionCapturedContentResize(id = 0L, width = width, height = height))
    }

    override fun onRouterSelectedCapturedContentResized(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        handleCapturedContentResized(resize = resize, forwardAfterTransfer = true)
    }

    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        handleCapturedContentVisibilityChanged(isVisible = isVisible, forwardAfterTransfer = false)
    }

    override fun onRouterSelectedCapturedContentVisibilityChanged(isVisible: Boolean) {
        handleCapturedContentVisibilityChanged(isVisible = isVisible, forwardAfterTransfer = true)
    }

    private fun handleProjectionStopped(forwardAfterTransfer: Boolean) {
        val runtimeOwner = synchronized(lock) {
            when (state) {
                PreActiveRuntimeOwnerState.Open -> {
                    runtimeProjectionStopObserved = true
                    signalMailbox.recordProjectionStopped()
                    null
                }

                PreActiveRuntimeOwnerState.Transferred -> transferredRuntimeOwner.takeIf { forwardAfterTransfer }
                PreActiveRuntimeOwnerState.Closed -> null
            }
        }
        runtimeOwner?.onProjectionStopped()
    }

    private fun handleCapturedContentResized(
        resize: ProjectionCapturedContentResize,
        forwardAfterTransfer: Boolean,
    ) {
        val runtimeOwner = synchronized(lock) {
            when (state) {
                PreActiveRuntimeOwnerState.Open -> {
                    if (!runtimeProjectionStopObserved) {
                        signalMailbox.recordCapturedContentResize(resize)
                    }
                    null
                }

                PreActiveRuntimeOwnerState.Transferred -> transferredRuntimeOwner.takeIf { forwardAfterTransfer }
                PreActiveRuntimeOwnerState.Closed -> null
            }
        }
        runtimeOwner?.onCapturedContentResized(resize)
    }

    private fun handleCapturedContentVisibilityChanged(
        isVisible: Boolean,
        forwardAfterTransfer: Boolean,
    ) {
        val runtimeOwner = synchronized(lock) {
            when (state) {
                PreActiveRuntimeOwnerState.Open -> {
                    if (!runtimeProjectionStopObserved) {
                        signalMailbox.recordCapturedContentVisibility(isVisible)
                    }
                    null
                }

                PreActiveRuntimeOwnerState.Transferred -> transferredRuntimeOwner.takeIf { forwardAfterTransfer }
                PreActiveRuntimeOwnerState.Closed -> null
            }
        }
        runtimeOwner?.onCapturedContentVisibilityChanged(isVisible = isVisible)
    }

    override fun close() {
        synchronized(lock) {
            if (state != PreActiveRuntimeOwnerState.Open) return
            state = PreActiveRuntimeOwnerState.Closed
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

    private companion object {
        private const val PRE_ACTIVE_TARGET_BIND_ATTEMPTS: Int = 2
    }
}

internal class PreActiveInitialRuntimePlan internal constructor(
    internal val ownerToken: Any,
    internal val planToken: Long,
    internal val outputPlan: ScreenCaptureOutputPlan,
    internal val projectionTarget: ProjectionTargetSnapshot,
)

private enum class PreActiveRuntimeOwnerState {
    Open,
    Transferred,
    Closed,
}
