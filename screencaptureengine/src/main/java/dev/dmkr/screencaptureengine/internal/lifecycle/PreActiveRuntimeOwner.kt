package dev.dmkr.screencaptureengine.internal.lifecycle

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.collectFailure
import dev.dmkr.screencaptureengine.internal.gl.scheduleStartupCleanup
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanResult
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupProjectionCallbackRouter
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.InitialRuntimePreparedRenderingPipelineResources
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineResources
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationFailure
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.startup.PreActiveRuntimeTransfer
import dev.dmkr.screencaptureengine.internal.startup.ScreenCaptureStartupMilestone
import dev.dmkr.screencaptureengine.internal.startup.StartupToRuntimeSignalMailbox
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess
import dev.dmkr.screencaptureengine.internal.target.matches
import dev.dmkr.screencaptureengine.internal.target.snapshot
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns startup resources after `AuthoritativeStartupGeometryReady` and before public activation.
 *
 * This owner prepares the initial Active output plan and authoritative generation-owned projection
 * target from the frozen startup geometry. It prepares rendering/readback/encoder ownership only
 * through [prepareInitialRenderingPipeline], and does not publish public state or expose a session.
 * Resize and metrics changes observed after the startup geometry freeze are retained as pending
 * runtime signals. Visibility is retained as the initial visibility handoff value, not as a replayed
 * runtime visibility update.
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
    private var initialRenderingPipelinePreparationStarted = false
    private var activePlanPreparationToken: PlanPreparationToken? = null
    private var preparedRenderingPipelineResources: PreparedRenderingPipelineResources? = null
    private var transferredRuntimeOwner: InitialRuntimeResourceOwner? = null
    private val signalMailbox = StartupToRuntimeSignalMailbox(startupGeometry = startupGeometry)
    internal var afterInitialRenderingPipelineResourcesMovedForTesting: (() -> Unit)? = null

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

        return buildInitialRuntimePlan(plan = plan, encoderProvider = initialParameters.encoderProvider)
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

    private fun buildInitialRuntimePlan(
        plan: ScreenCaptureOutputPlan,
        encoderProvider: ImageEncoderProvider,
    ): PreActiveInitialRuntimePlan =
        synchronized(lock) {
            check(state == PreActiveRuntimeOwnerState.Open) { "PreActiveRuntimeOwner is $state." }
            if (isProjectionStoppedLocked()) throw projectionStoppedException()
            PreActiveInitialRuntimePlan(
                ownerToken = ownerToken,
                planToken = initialPlanToken,
                outputPlan = plan,
                projectionTarget = currentProjectionTarget.snapshot(),
                projectionTargetHandle = currentProjectionTarget,
                startupRenderingGlAccess = projectionTargetOwner.startupRenderingGlAccess(),
                encoderProvider = encoderProvider,
            )
        }

    internal suspend fun prepareInitialRenderingPipeline(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparer: RenderingPipelinePreparer,
    ): PreparedRenderingPipelineResources =
        try {
            prepareInitialRenderingPipelineOrThrow(preparedPlan = preparedPlan, preparer = preparer)
        } catch (cause: CancellationException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: ScreenCaptureStartException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            val exception = startException(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Initial rendering pipeline preparation failed.",
                cause = cause,
            )
            closeAfterStartupFailure(exception)
            throw exception
        }

    private suspend fun prepareInitialRenderingPipelineOrThrow(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparer: RenderingPipelinePreparer,
    ): PreparedRenderingPipelineResources {
        val coroutineContext = currentCoroutineContext()
        val preparationStart = beginInitialRenderingPipelinePreparation(
            preparedPlan = preparedPlan,
            callerActive = coroutineContext[Job]?.isActive != false,
        )
        preparationStart.supersededToken?.invalidate()
        preparationStart.decision.throwIfNotReady(coroutineContext)
        val planPreparationToken = preparationStart.planPreparationToken
            ?: error("Initial rendering pipeline preparation did not create a token.")
        val cancellationHook = installCallerCancellationHook(
            callerJob = coroutineContext[Job],
            planPreparationToken = planPreparationToken,
        )

        var preparedComponents: PreparedRenderingPipelineComponents? = null
        var movedPreparedResources: PreparedRenderingPipelineResources? = null
        val preparedResources: PreparedRenderingPipelineResources
        try {
            preparedResources = try {
                val request = RenderingPipelinePrepareRequest(
                    planPreparationToken = planPreparationToken,
                    outputPlan = preparedPlan.outputPlan,
                    projectionTarget = preparedPlan.projectionTarget,
                    projectionTargetHandle = preparedPlan.projectionTargetHandle,
                    startupRenderingGlAccess = preparedPlan.startupRenderingGlAccess,
                    encoderProvider = preparedPlan.encoderProvider,
                )
                when (val result = preparer.prepareInitialRenderingPipeline(request)) {
                    is RenderingPipelinePreparationResult.Success -> {
                        val components = result.components
                        preparedComponents = components
                        validateInitialRenderingPipelineSuccessAcceptance(
                            preparedPlan = preparedPlan,
                            planPreparationToken = planPreparationToken,
                            callerActive = coroutineContext[Job]?.isActive != false,
                        ).throwIfNotReady(coroutineContext)
                        val resources = components.moveToPreActiveOwner(
                            planPreparationToken = planPreparationToken,
                            ownerToken = ownerToken,
                            planToken = preparedPlan.planToken,
                            outputPlan = preparedPlan.outputPlan,
                            projectionTarget = preparedPlan.projectionTarget,
                            projectionTargetGeneration = preparedPlan.projectionTarget.generation,
                            startupRenderingGlAccess = preparedPlan.startupRenderingGlAccess,
                        )
                        preparedComponents = null
                        movedPreparedResources = resources
                        afterInitialRenderingPipelineResourcesMovedForTesting?.invoke()
                        movedPreparedResources = null
                        resources
                    }

                    is RenderingPipelinePreparationResult.Failure ->
                        throwPreparationFailureWithLifecyclePriority(coroutineContext, result.failure)

                    RenderingPipelinePreparationResult.LifecycleStale ->
                        throwLifecycleStalePreparationWithLifecyclePriority(
                            coroutineContext = coroutineContext,
                            preparedPlan = preparedPlan,
                            planPreparationToken = planPreparationToken,
                        )
                }
            } catch (cause: CancellationException) {
                preparedComponents?.let(::retirePreparedResourcesAfterFailure)
                movedPreparedResources?.let(::retirePreparedResourcesAfterFailure)
                throw cause
            } catch (cause: InitialRenderingPipelineInvariantViolation) {
                preparedComponents?.let(::retirePreparedResourcesAfterFailure)
                movedPreparedResources?.let(::retirePreparedResourcesAfterFailure)
                throw cause
            } catch (cause: ScreenCaptureStartException) {
                preparedComponents?.let(::retirePreparedResourcesAfterFailure)
                movedPreparedResources?.let(::retirePreparedResourcesAfterFailure)
                throwClassifiedPreparationFailureWithLifecyclePriority(coroutineContext, cause)
            } catch (cause: Throwable) {
                preparedComponents?.let(::retirePreparedResourcesAfterFailure)
                movedPreparedResources?.let(::retirePreparedResourcesAfterFailure)
                throwPreparationFailureWithLifecyclePriority(coroutineContext, cause)
            }

            try {
                commitInitialRenderingPipelinePreparation(
                    preparedPlan = preparedPlan,
                    preparedResources = preparedResources,
                    callerActive = coroutineContext[Job]?.isActive != false,
                ).throwIfNotReady(coroutineContext)
                return preparedResources
            } catch (cause: Throwable) {
                retirePreparedResourcesAfterFailure(preparedResources)
                throw cause
            }
        } finally {
            cancellationHook?.dispose()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun installCallerCancellationHook(
        callerJob: Job?,
        planPreparationToken: PlanPreparationToken,
    ): DisposableHandle? =
        callerJob?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                invalidateActivePlanPreparationToken(planPreparationToken)
            }
        }

    private fun invalidateActivePlanPreparationToken(planPreparationToken: PlanPreparationToken) {
        val tokenToInvalidate = synchronized(lock) {
            if (activePlanPreparationToken === planPreparationToken) {
                activePlanPreparationToken = null
                planPreparationToken
            } else {
                null
            }
        }
        tokenToInvalidate?.invalidate()
    }

    private fun beginInitialRenderingPipelinePreparation(
        preparedPlan: PreActiveInitialRuntimePlan,
        callerActive: Boolean,
    ): InitialRenderingPipelinePreparationStart {
        var supersededToken: PlanPreparationToken? = null
        var decisionResult: InitialRuntimeHandoffDecision? = null
        var planPreparationToken: PlanPreparationToken? = null
        synchronized(lock) {
            val decision = initialRuntimeHandoffDecision(
                preparedPlan = preparedPlan,
                preparedResources = null,
                callerActive = callerActive,
            )
            if (decision == InitialRuntimeHandoffDecision.Ready) {
                check(!initialRenderingPipelinePreparationStarted) { "Initial rendering pipeline preparation was already started." }
                initialRenderingPipelinePreparationStarted = true
                supersededToken = activePlanPreparationToken
                planPreparationToken = PlanPreparationToken(
                    ownerToken = ownerToken,
                    planToken = preparedPlan.planToken,
                    projectionTargetGeneration = preparedPlan.projectionTarget.generation,
                )
                activePlanPreparationToken = planPreparationToken
            }
            decisionResult = decision
        }
        return InitialRenderingPipelinePreparationStart(
            decision = checkNotNull(decisionResult),
            planPreparationToken = planPreparationToken,
            supersededToken = supersededToken,
        )
    }

    private fun commitInitialRenderingPipelinePreparation(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparedResources: PreparedRenderingPipelineResources,
        callerActive: Boolean,
    ): InitialRenderingPipelineSuccessAcceptanceDecision =
        synchronized(lock) {
            val decision = initialRenderingPipelineCommitDecision(
                preparedPlan = preparedPlan,
                preparedResources = preparedResources,
                callerActive = callerActive,
            )
            if (decision == InitialRenderingPipelineSuccessAcceptanceDecision.Ready) {
                check(preparedRenderingPipelineResources == null) { "Initial rendering pipeline preparation was already completed." }
                preparedRenderingPipelineResources = preparedResources
            }
            decision
        }

    internal suspend fun transferToInitialRuntimeResourceOwner(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparedResources: PreparedRenderingPipelineResources,
    ): InitialRuntimeResourceOwner =
        try {
            transferToInitialRuntimeResourceOwnerOrThrow(
                preparedPlan = preparedPlan,
                preparedResources = preparedResources,
            )
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
        preparedResources: PreparedRenderingPipelineResources,
    ): InitialRuntimeResourceOwner {
        val coroutineContext = currentCoroutineContext()
        var movedPreparedResourcesToRetire: InitialRuntimePreparedRenderingPipelineResources? = null
        val runtimeOwner = try {
            synchronized(lock) {
                validateInitialRuntimeHandoffReadiness(
                    preparedPlan = preparedPlan,
                    preparedResources = preparedResources,
                    callerActive = coroutineContext[Job]?.isActive != false,
                ).throwIfNotReady(coroutineContext)
                check(preparedRenderingPipelineResources === preparedResources) { "PreparedRenderingPipelineResources were not prepared by this owner." }
                val movedResources = preparedResources.moveToInitialRuntimeOwner()
                movedPreparedResourcesToRetire = movedResources
                check(preparedResources.planPreparationToken.consumeForHandoff()) {
                    "PreparedRenderingPipelineResources plan-preparation token was not current."
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
                        preparedRenderingPipelineResources = movedResources,
                        initialOutputPlan = preparedPlan.outputPlan,
                        initialProjectionTarget = preparedPlan.projectionTarget,
                        pendingSignals = pendingSignals,
                    ),
                )
                callbackRouter.replaceRuntimeListener(expectedCurrent = this, replacement = owner)
                activePlanPreparationToken = null
                preparedRenderingPipelineResources = null
                transferredRuntimeOwner = owner
                state = PreActiveRuntimeOwnerState.Transferred
                movedPreparedResourcesToRetire = null
                owner
            }
        } catch (cause: Throwable) {
            movedPreparedResourcesToRetire?.let(::retirePreparedResourcesAfterFailure)
            throw cause
        }
        return runtimeOwner
    }

    private fun validateInitialRuntimeHandoffReadiness(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparedResources: PreparedRenderingPipelineResources?,
        callerActive: Boolean,
    ): InitialRuntimeHandoffDecision =
        synchronized(lock) {
            initialRuntimeHandoffDecision(
                preparedPlan = preparedPlan,
                preparedResources = preparedResources,
                callerActive = callerActive,
            )
        }

    private fun initialRuntimeHandoffDecision(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparedResources: PreparedRenderingPipelineResources?,
        callerActive: Boolean,
    ): InitialRuntimeHandoffDecision =
        InitialRuntimeHandoffGate.decide(
            InitialRuntimeHandoffGateSnapshot(
                ownerOpen = state == PreActiveRuntimeOwnerState.Open,
                ownerStateDescription = state.toString(),
                planOwnerMatches = preparedPlan.ownerToken === ownerToken,
                planTokenMatches = preparedPlan.planToken == initialPlanToken,
                targetHandleMatches = currentProjectionTarget === preparedPlan.projectionTargetHandle,
                targetGenerationMatches = currentProjectionTarget.generation == preparedPlan.projectionTarget.generation,
                startupRenderingGlAccessMatches = preparedPlan.startupRenderingGlAccess ===
                        projectionTargetOwner.startupRenderingGlAccess(),
                preparedResourcesOwnerMatches = preparedResources == null || preparedResources.ownerToken === ownerToken,
                preparedResourcesPlanTokenMatches = preparedResources == null || preparedResources.planToken == preparedPlan.planToken,
                preparedResourcesTargetGenerationMatches = preparedResources == null ||
                        preparedResources.projectionTargetGeneration == preparedPlan.projectionTarget.generation,
                preparedResourcesStartupRenderingGlAccessMatches = preparedResources == null ||
                        preparedResources.startupRenderingGlAccess === preparedPlan.startupRenderingGlAccess,
                preparedResourcesPlanPreparationTokenMatches = preparedResources == null ||
                        preparedResources.planPreparationToken.matches(
                            ownerToken = ownerToken,
                            planToken = preparedPlan.planToken,
                            projectionTargetGeneration = preparedPlan.projectionTarget.generation,
                        ),
                preparedResourcesPlanPreparationTokenCurrent = preparedResources == null ||
                        preparedResources.planPreparationToken.isCurrent,
                preparedResourcesOpen = preparedResources?.isOpenForHandoff != false,
                projectionStopped = isProjectionStoppedLocked(),
                callerActive = callerActive,
            ),
        )

    private fun InitialRuntimeHandoffDecision.throwIfNotReady(coroutineContext: kotlin.coroutines.CoroutineContext) {
        when (this) {
            InitialRuntimeHandoffDecision.Ready -> Unit
            InitialRuntimeHandoffDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRuntimeHandoffDecision.CallerCancelled -> coroutineContext.ensureActive()
            is InitialRuntimeHandoffDecision.InvariantViolation -> error(message)
        }
    }

    private fun throwPreparationFailureWithLifecyclePriority(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        cause: Throwable,
    ): Nothing {
        when (val decision = preparationFailureLifecycleDecision(coroutineContext)) {
            InitialRuntimeHandoffDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRuntimeHandoffDecision.CallerCancelled -> coroutineContext.ensureActive()
            is InitialRuntimeHandoffDecision.InvariantViolation -> error(decision.message)
            InitialRuntimeHandoffDecision.Ready -> throw startException(
                kind = ScreenCaptureProblemKind.GlResourceFailure,
                message = "Initial rendering pipeline resources could not be prepared.",
                cause = cause,
            )
        }
        throw cause
    }

    private fun throwLifecycleStalePreparationWithLifecyclePriority(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        preparedPlan: PreActiveInitialRuntimePlan,
        planPreparationToken: PlanPreparationToken,
    ): Nothing {
        when (val decision = lifecycleStalePreparationDecision(
            coroutineContext = coroutineContext,
            preparedPlan = preparedPlan,
            planPreparationToken = planPreparationToken,
        )) {
            InitialRenderingPipelineLifecycleStaleDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRenderingPipelineLifecycleStaleDecision.CallerCancelled -> coroutineContext.ensureActive()
            InitialRenderingPipelineLifecycleStaleDecision.LegitimateStale -> throw CancellationException(
                "Initial rendering pipeline preparation became stale.",
            )

            is InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation ->
                throw InitialRenderingPipelineInvariantViolation(decision.message)
        }
        throw IllegalStateException("Unreachable rendering pipeline lifecycle-stale decision.")
    }

    private fun lifecycleStalePreparationDecision(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        preparedPlan: PreActiveInitialRuntimePlan,
        planPreparationToken: PlanPreparationToken,
    ): InitialRenderingPipelineLifecycleStaleDecision =
        synchronized(lock) {
            when {
                isProjectionStoppedLocked() -> InitialRenderingPipelineLifecycleStaleDecision.ProjectionStopped
                coroutineContext[Job]?.isActive == false -> InitialRenderingPipelineLifecycleStaleDecision.CallerCancelled
                !planPreparationToken.matches(
                    ownerToken = ownerToken,
                    planToken = preparedPlan.planToken,
                    projectionTargetGeneration = preparedPlan.projectionTarget.generation,
                ) -> InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation(
                    message = "Initial rendering pipeline lifecycle-stale token belongs to a different plan.",
                )

                state == PreActiveRuntimeOwnerState.Closed &&
                        activePlanPreparationToken == null &&
                        !planPreparationToken.isCurrent ->
                    InitialRenderingPipelineLifecycleStaleDecision.LegitimateStale

                state != PreActiveRuntimeOwnerState.Open -> InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation(
                    message = "PreActiveRuntimeOwner is $state.",
                )

                activePlanPreparationToken !== planPreparationToken && !planPreparationToken.isCurrent ->
                    InitialRenderingPipelineLifecycleStaleDecision.LegitimateStale

                activePlanPreparationToken !== planPreparationToken ->
                    InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation(
                        message = "Initial rendering pipeline lifecycle-stale token is no longer active.",
                    )

                !planPreparationToken.isCurrent -> InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation(
                    message = "Initial rendering pipeline lifecycle-stale result had no legitimate stale cause.",
                )

                else -> InitialRenderingPipelineLifecycleStaleDecision.InvariantViolation(
                    message = "Initial rendering pipeline lifecycle-stale result was returned for a current token.",
                )
            }
        }

    private fun throwClassifiedPreparationFailureWithLifecyclePriority(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        cause: ScreenCaptureStartException,
    ): Nothing {
        when (val decision = preparationFailureLifecycleDecision(coroutineContext)) {
            InitialRuntimeHandoffDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRuntimeHandoffDecision.CallerCancelled -> coroutineContext.ensureActive()
            is InitialRuntimeHandoffDecision.InvariantViolation -> error(decision.message)
            InitialRuntimeHandoffDecision.Ready -> throw cause
        }
        throw cause
    }

    private fun preparationFailureLifecycleDecision(
        coroutineContext: kotlin.coroutines.CoroutineContext,
    ): InitialRuntimeHandoffDecision =
        synchronized(lock) {
            when {
                isProjectionStoppedLocked() -> InitialRuntimeHandoffDecision.ProjectionStopped
                coroutineContext[Job]?.isActive == false -> InitialRuntimeHandoffDecision.CallerCancelled
                else -> InitialRuntimeHandoffDecision.Ready
            }
        }

    private fun validateInitialRenderingPipelineSuccessAcceptance(
        preparedPlan: PreActiveInitialRuntimePlan,
        planPreparationToken: PlanPreparationToken,
        callerActive: Boolean,
    ): InitialRenderingPipelineSuccessAcceptanceDecision =
        synchronized(lock) {
            when {
                preparedPlan.ownerToken !== ownerToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveInitialRuntimePlan belongs to a different owner.",
                )

                preparedPlan.planToken != initialPlanToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveInitialRuntimePlan is stale.",
                )

                currentProjectionTarget !== preparedPlan.projectionTargetHandle -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveInitialRuntimePlan target handle is stale.",
                )

                currentProjectionTarget.generation != preparedPlan.projectionTarget.generation ->
                    InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                        message = "PreActiveInitialRuntimePlan target generation is stale.",
                    )

                preparedPlan.startupRenderingGlAccess !== projectionTargetOwner.startupRenderingGlAccess() ->
                    InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                        message = "PreActiveInitialRuntimePlan startup rendering GL access is stale.",
                    )

                !planPreparationToken.matches(
                    ownerToken = ownerToken,
                    planToken = preparedPlan.planToken,
                    projectionTargetGeneration = preparedPlan.projectionTarget.generation,
                ) -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "Initial rendering pipeline plan-preparation token belongs to a different plan.",
                )

                isProjectionStoppedLocked() -> InitialRenderingPipelineSuccessAcceptanceDecision.ProjectionStopped
                !callerActive -> InitialRenderingPipelineSuccessAcceptanceDecision.CallerCancelled
                state == PreActiveRuntimeOwnerState.Closed -> InitialRenderingPipelineSuccessAcceptanceDecision.StaleToken
                state != PreActiveRuntimeOwnerState.Open -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveRuntimeOwner is $state.",
                )

                activePlanPreparationToken !== planPreparationToken ->
                    InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                        message = "Initial rendering pipeline plan-preparation token is no longer active.",
                    )

                !planPreparationToken.isCurrent ->
                    InitialRenderingPipelineSuccessAcceptanceDecision.StaleToken

                else -> InitialRenderingPipelineSuccessAcceptanceDecision.Ready
            }
        }

    private fun InitialRenderingPipelineSuccessAcceptanceDecision.throwIfNotReady(
        coroutineContext: kotlin.coroutines.CoroutineContext,
    ) {
        when (this) {
            InitialRenderingPipelineSuccessAcceptanceDecision.Ready -> Unit
            InitialRenderingPipelineSuccessAcceptanceDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRenderingPipelineSuccessAcceptanceDecision.CallerCancelled -> coroutineContext.ensureActive()
            InitialRenderingPipelineSuccessAcceptanceDecision.StaleToken -> throw startException(
                kind = ScreenCaptureProblemKind.GlResourceFailure,
                message = "Initial rendering pipeline preparation became stale before acceptance.",
                cause = null,
            )

            is InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation ->
                throw InitialRenderingPipelineInvariantViolation(message)
        }
    }

    private fun initialRenderingPipelineCommitDecision(
        preparedPlan: PreActiveInitialRuntimePlan,
        preparedResources: PreparedRenderingPipelineResources,
        callerActive: Boolean,
    ): InitialRenderingPipelineSuccessAcceptanceDecision =
        when {
            preparedPlan.ownerToken !== ownerToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan belongs to a different owner.",
            )

            preparedPlan.planToken != initialPlanToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan is stale.",
            )

            currentProjectionTarget !== preparedPlan.projectionTargetHandle -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan target handle is stale.",
            )

            currentProjectionTarget.generation != preparedPlan.projectionTarget.generation ->
                InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveInitialRuntimePlan target generation is stale.",
                )

            preparedPlan.startupRenderingGlAccess !== projectionTargetOwner.startupRenderingGlAccess() ->
                InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreActiveInitialRuntimePlan startup rendering GL access is stale.",
                )

            preparedResources.ownerToken !== ownerToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources belong to a different owner.",
            )

            preparedResources.planToken != preparedPlan.planToken -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources are stale.",
            )

            preparedResources.projectionTargetGeneration != preparedPlan.projectionTarget.generation ->
                InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreparedRenderingPipelineResources target generation is stale.",
                )

            preparedResources.startupRenderingGlAccess !== preparedPlan.startupRenderingGlAccess ->
                InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreparedRenderingPipelineResources startup rendering GL access is stale.",
                )

            !preparedResources.planPreparationToken.matches(
                ownerToken = ownerToken,
                planToken = preparedPlan.planToken,
                projectionTargetGeneration = preparedPlan.projectionTarget.generation,
            ) -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources plan-preparation token is stale.",
            )

            !preparedResources.isOpenForHandoff -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources are not open for handoff.",
            )

            isProjectionStoppedLocked() -> InitialRenderingPipelineSuccessAcceptanceDecision.ProjectionStopped
            !callerActive -> InitialRenderingPipelineSuccessAcceptanceDecision.CallerCancelled
            state == PreActiveRuntimeOwnerState.Closed -> InitialRenderingPipelineSuccessAcceptanceDecision.StaleToken
            state != PreActiveRuntimeOwnerState.Open -> InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                message = "PreActiveRuntimeOwner is $state.",
            )

            activePlanPreparationToken !== preparedResources.planPreparationToken ->
                InitialRenderingPipelineSuccessAcceptanceDecision.InvariantViolation(
                    message = "PreparedRenderingPipelineResources plan-preparation token is no longer active.",
                )

            !preparedResources.planPreparationToken.isCurrent ->
                InitialRenderingPipelineSuccessAcceptanceDecision.StaleToken

            else -> InitialRenderingPipelineSuccessAcceptanceDecision.Ready
        }

    private fun throwPreparationFailureWithLifecyclePriority(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        failure: RenderingPipelinePreparationFailure,
    ): Nothing {
        when (val decision = preparationFailureLifecycleDecision(coroutineContext)) {
            InitialRuntimeHandoffDecision.ProjectionStopped -> throw projectionStoppedException()
            InitialRuntimeHandoffDecision.CallerCancelled -> coroutineContext.ensureActive()
            is InitialRuntimeHandoffDecision.InvariantViolation -> error(decision.message)
            InitialRuntimeHandoffDecision.Ready -> throw startException(
                kind = failure.kind,
                message = failure.message,
                cause = failure.cause,
            )
        }
        throw IllegalStateException("Unreachable rendering pipeline preparation failure decision.")
    }

    private fun retirePreparedResourcesAfterFailure(resources: AutoCloseable) {
        scheduleStartupCleanup(
            cleanupScheduler = cleanupScheduler,
            cleanupFailureSink = cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            cleanupFailures.collect { resources.close() }
            cleanupFailures.throwIfAny()
        }
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
        var tokenToInvalidate: PlanPreparationToken? = null
        val runtimeOwner = synchronized(lock) {
            when (state) {
                PreActiveRuntimeOwnerState.Open -> {
                    runtimeProjectionStopObserved = true
                    signalMailbox.recordProjectionStopped()
                    tokenToInvalidate = activePlanPreparationToken
                    null
                }

                PreActiveRuntimeOwnerState.Transferred -> transferredRuntimeOwner.takeIf { forwardAfterTransfer }
                PreActiveRuntimeOwnerState.Closed -> null
            }
        }
        tokenToInvalidate?.invalidate()
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
        val tokenToInvalidate: PlanPreparationToken?
        val preparedResourcesToClose: PreparedRenderingPipelineResources?
        synchronized(lock) {
            if (state != PreActiveRuntimeOwnerState.Open) return
            state = PreActiveRuntimeOwnerState.Closed
            tokenToInvalidate = activePlanPreparationToken
            activePlanPreparationToken = null
            preparedResourcesToClose = preparedRenderingPipelineResources
            preparedRenderingPipelineResources = null
        }
        tokenToInvalidate?.invalidate()
        callbackRouter.close()
        runCatching { callbackAdapter.close() }.onFailure(::reportCleanupFailure)
        runCatching { metricsObservation.close() }.onFailure(::reportCleanupFailure)
        stopProjectionIfRequired()
        scheduleStartupCleanup(
            cleanupScheduler = cleanupScheduler,
            cleanupFailureSink = cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            preparedResourcesToClose?.let { resources ->
                cleanupFailures.collect { resources.close() }
            }
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
    internal val projectionTargetHandle: ProjectionTargetHandle,
    internal val startupRenderingGlAccess: StartupRenderingGlAccess,
    internal val encoderProvider: ImageEncoderProvider,
)

private class InitialRenderingPipelinePreparationStart(
    val decision: InitialRuntimeHandoffDecision,
    val planPreparationToken: PlanPreparationToken?,
    val supersededToken: PlanPreparationToken?,
)

private sealed interface InitialRenderingPipelineSuccessAcceptanceDecision {
    data object Ready : InitialRenderingPipelineSuccessAcceptanceDecision
    data object ProjectionStopped : InitialRenderingPipelineSuccessAcceptanceDecision
    data object CallerCancelled : InitialRenderingPipelineSuccessAcceptanceDecision
    data object StaleToken : InitialRenderingPipelineSuccessAcceptanceDecision

    class InvariantViolation(
        val message: String,
    ) : InitialRenderingPipelineSuccessAcceptanceDecision
}

private sealed interface InitialRenderingPipelineLifecycleStaleDecision {
    data object ProjectionStopped : InitialRenderingPipelineLifecycleStaleDecision
    data object CallerCancelled : InitialRenderingPipelineLifecycleStaleDecision
    data object LegitimateStale : InitialRenderingPipelineLifecycleStaleDecision

    class InvariantViolation(
        val message: String,
    ) : InitialRenderingPipelineLifecycleStaleDecision
}

private class InitialRenderingPipelineInvariantViolation(
    message: String,
) : IllegalStateException(message)

private enum class PreActiveRuntimeOwnerState {
    Open,
    Transferred,
    Closed,
}
