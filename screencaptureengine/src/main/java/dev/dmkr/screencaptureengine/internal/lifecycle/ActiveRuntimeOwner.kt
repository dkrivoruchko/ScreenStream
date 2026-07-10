package dev.dmkr.screencaptureengine.internal.lifecycle

import android.os.SystemClock
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.FrameSubscription
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureEffectiveParameters
import dev.dmkr.screencaptureengine.ScreenCaptureEvent
import dev.dmkr.screencaptureengine.ScreenCaptureOutputState
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSession
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStartException
import dev.dmkr.screencaptureengine.ScreenCaptureStats
import dev.dmkr.screencaptureengine.ScreenCaptureStopReason
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparationResult
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.encoding.runtime.EncodedAttemptScratch
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFailureSink
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.gl.scheduleStartupCleanup
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.RuntimeParameterActiveSnapshot
import dev.dmkr.screencaptureengine.internal.planning.RuntimeParameterOutputState
import dev.dmkr.screencaptureengine.internal.planning.RuntimeParameterUpdateClassification
import dev.dmkr.screencaptureengine.internal.planning.RuntimeParameterUpdateClassifier
import dev.dmkr.screencaptureengine.internal.planning.RuntimeProjectionTargetIdentity
import dev.dmkr.screencaptureengine.internal.planning.RuntimeProjectionTargetSemantics
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import dev.dmkr.screencaptureengine.internal.platform.metrics.CaptureMetricsObservation
import dev.dmkr.screencaptureengine.internal.platform.projection.MediaProjectionCallbackAdapter
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionVirtualDisplayOwner
import dev.dmkr.screencaptureengine.internal.platform.projection.StartupProjectionCallbackRouter
import dev.dmkr.screencaptureengine.internal.rendering.es2.ImageEncoderPrepareOperation
import dev.dmkr.screencaptureengine.internal.rendering.es2.RgbaReadbackLease
import dev.dmkr.screencaptureengine.internal.rendering.es2.RuntimeEs2FrameRenderer
import dev.dmkr.screencaptureengine.internal.rendering.es2.RuntimeEs2RenderReadbackRequest
import dev.dmkr.screencaptureengine.internal.rendering.es2.RuntimeEs2RenderReadbackResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.ActiveRuntimeEncoderResourcesCandidate
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.ActiveRuntimePreparedRenderingPipelineResources
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.ActiveRuntimePreparedRenderingPipelineResourcesCandidate
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PlanRenderingAccess
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RetiredActiveRuntimeEncoderResources
import dev.dmkr.screencaptureengine.internal.session.core.ProductionFrameDropKind
import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureParameterCommitGate
import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionCore
import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionTerminalCommit
import dev.dmkr.screencaptureengine.internal.startup.StartupRuntimePendingSignals
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetOwnerAbandonment
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetGlAccess
import dev.dmkr.screencaptureengine.internal.target.consumeLatestFrame
import dev.dmkr.screencaptureengine.internal.target.snapshot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Non-public active runtime owner prepared after rendering pipeline readiness.
 *
 * The owner installs a gated [RuntimeFrameLoop] before [commitInitialActiveSession]. The public
 * session identity is created only inside the non-suspending initial Active commit boundary. Once
 * that boundary begins, returning the session is a handoff path: it must not consume frames, touch
 * GL/readback/encoder resources, or wait for heavy cleanup before returning the committed session.
 */
internal class ActiveRuntimeOwner internal constructor(
    private val transfer: ActiveRuntimeTransfer,
    private val commitBoundary: InitialActivationCommitBoundary = InitialActivationCommitBoundary(),
    private var frameRenderer: RuntimeEs2FrameRenderer = RuntimeEs2FrameRenderer(),
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val terminalCommitHandler: (ScreenCaptureSessionTerminalCommit) -> Unit = {},
    private val terminalCleanupFenceFactory: () -> AutoCloseable = { NoopCloseable },
) : AutoCloseable, StartupProjectionCallbackRouter.SelectedRuntimeListener {
    private val lock = Any()
    private val runtimeFrameLoop = RuntimeFrameLoop(
        startupGeometry = transfer.startupGeometry,
        onRuntimeSignalRecorded = ::scheduleRuntimeTurn,
    )
    private val runtimeScheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ScreenCaptureRuntimeLoop").apply { isDaemon = true }
    }
    private val encoderLane = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenCaptureRuntimeEncoder").apply { isDaemon = true }
    }
    private val runtimeTurnScheduled = AtomicBoolean()
    private val periodicRefreshScheduled = AtomicBoolean()
    private val readbackInFlight = AtomicBoolean()
    private val encoderInFlight = AtomicBoolean()
    private val terminalCleanupFence = AtomicReference<AutoCloseable?>(null)
    private val parameterUpdateClassifier = RuntimeParameterUpdateClassifier(
        ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = transfer.config.maxOutputPixels,
                maxEncodedBytes = transfer.config.maxEncodedBytes,
            ),
        ),
    )
    private var materializedRuntimeWorkInFlight = false
    private val oesMatrixScratch = FloatArray(RUNTIME_MATRIX_ELEMENT_COUNT)
    private val composedTextureMatrixScratch = FloatArray(RUNTIME_MATRIX_ELEMENT_COUNT)
    private val startupPendingSignals = transfer.pendingSignals
    private var state = ActiveRuntimeOwnerState.Prepared
    private var activeResources: ActiveRuntimePreparedRenderingPipelineResources? = transfer.preparedRenderingPipelineResources
    private var activeRequestedParameters: ScreenCaptureParameters? = null
    private var activeOutputPlan: ScreenCaptureOutputPlan? = null
    private var activeEffectiveParameters: ScreenCaptureEffectiveParameters? = null
    private var activeOutputGeneration: Long? = null
    private var runtimeParameterPlanToken = 0L
    private var activeRuntimeParameterPreparationToken: PlanPreparationToken? = null
    private var pendingRuntimeParameterProductionResume = false
    private var deferredActiveResourcesClose: ActiveRuntimePreparedRenderingPipelineResources? = null
    private val deferredRetiredActiveResourcesClose = mutableListOf<ActiveRuntimePreparedRenderingPipelineResources>()
    private val deferredRetiredEncoderResourcesClose = mutableListOf<RetiredActiveRuntimeEncoderResources>()
    private var deferredHeavyRuntimeClose = false
    private var virtualDisplayOwnerCloseScheduled = false
    private var projectionTargetOwnerCloseScheduled = false
    private var sessionCore: ScreenCaptureSessionCore? = null
    private var publicSession: ScreenCaptureSession? = null
    private var encodedScratch: EncodedAttemptScratch? = null
    private var pendingEncodedScratchTrim = false
    private var releaseEncodedScratchOnRuntimeClose = false
    private var frameLoopInstalled = false
    private var queuedPostCommitRuntimeSignalDrain: PostCommitRuntimeSignalDrain? = null
    private var returnedSessionRuntimeSignalsArmed = false
    private var latestCapturedContentVisibleForInitialCommit: Boolean? = transfer.pendingSignals.latestCapturedContentVisible
    private var lastFrameRateAdmitNanos: Long? = null
    private var hasSuccessfulRuntimeSourceFrame = false
    private val encodeHardFailureTracker = RuntimeEncodeHardFailureTracker<RuntimeEncodeHealthKey>(
        threshold = RUNTIME_ENCODE_HARD_FAILURE_THRESHOLD,
    )
    private var latestPeriodicRefreshFrame: PeriodicRefreshEncodedFrame? = null
    private var periodicRefreshFuture: ScheduledFuture<*>? = null
    private var periodicRefreshScheduleToken = 0L
    private var periodicRefreshNoSourceWakeCount = 0L
    private var glOperationTimeoutMillis = RUNTIME_GL_OPERATION_TIMEOUT_MS
    private var encoderOperationTimeoutMillis = RUNTIME_ENCODER_OPERATION_TIMEOUT_MS
    private var periodicRefreshDelayOverrideMillis: Long? = null
    private var beforeProductionAttemptMaterializationForTesting: (() -> Unit)? = null
    private var afterProductionAttemptMaterializedForTesting: (() -> Unit)? = null
    private var beforeOwnerStopTerminalCommitForTesting: (() -> Unit)? = null
    private var beforeFinalEncodedPublicationForTesting: (() -> Unit)? = null
    private var beforeFinalPeriodicRefreshPublicationForTesting: (() -> Unit)? = null
    private var beforePeriodicRefreshRetentionForTesting: (() -> Unit)? = null
    private var beforePeriodicRefreshWakeEnqueueForTesting: (() -> Unit)? = null
    private var afterPeriodicRefreshWakeEnqueueAttemptForTesting: (() -> Unit)? = null
    private var beforeFrameRatePolicyAdmissionForTesting: (() -> Unit)? = null
    private var beforeFrameRatePolicyDropForTesting: (() -> Unit)? = null
    private var beforeRuntimeFailureTerminalCommitForTesting: (() -> Unit)? = null
    private var beforeEncodeNonSuccessDropForTesting: (() -> Unit)? = null
    private var beforeRuntimeParameterCommitOwnerLockForTesting: (() -> Unit)? = null
    private var beforeRuntimeParameterCommitBridgeForTesting: (() -> Unit)? = null
    private var ownerStopTerminalCommitted = false
    private var lateRenderReadbackLeaseReleaseCountForTesting = 0
    private var readbackBorrowedByEncoderObserverForTesting: ((Boolean) -> Unit)? = null

    @Volatile
    private var projectionStopRuntimeFence = false

    init {
        runtimeScheduler.removeOnCancelPolicy = true
        transfer.callbackRouter.replaceRuntimeListener(expectedCurrent = transfer.expectedCurrentListener, replacement = this)
        transfer.metricsObservation.installRuntimeMetricsChangedListener(runtimeFrameLoop::recordMetricsObservationChanged)
        if (transfer.metricsObservation.latestProviderState is CaptureMetricsState.Unavailable) {
            runtimeFrameLoop.recordMetricsObservationChanged()
        }
    }

    internal val sessionForTesting: ScreenCaptureSession?
        get() = synchronized(lock) { publicSession }

    internal fun recordSourceFrameAvailable(generation: Long) {
        runtimeFrameLoop.recordSourceFrameAvailable(generation)
    }

    internal fun recordSourceFrameAvailableWithoutRuntimeWakeForTesting(generation: Long) {
        runtimeFrameLoop.recordSourceFrameAvailableWithoutRuntimeWakeForTesting(generation)
    }

    internal fun recordPeriodicRefreshWakeForTesting() {
        runtimeFrameLoop.recordPeriodicRefreshWakeForTesting()
    }

    internal fun runtimeFrameLoopSnapshot(): RuntimeFrameLoopSnapshot =
        runtimeFrameLoop.snapshot()

    internal fun periodicRefreshNoSourceWakeCountForTesting(): Long =
        synchronized(lock) { periodicRefreshNoSourceWakeCount }

    internal fun hasPeriodicRefreshFrameForTesting(): Boolean =
        synchronized(lock) { latestPeriodicRefreshFrame != null }

    internal fun hasScheduledPeriodicRefreshForTesting(): Boolean =
        synchronized(lock) { periodicRefreshFuture != null }

    internal fun replaceRuntimeFrameRendererForTesting(renderer: RuntimeEs2FrameRenderer) {
        frameRenderer = renderer
    }

    internal fun encodedScratchByteCountForTesting(): Int? =
        synchronized(lock) { encodedScratch?.byteCount }

    internal fun forceEncoderBusyForTesting(isBusy: Boolean) {
        encoderInFlight.set(isBusy)
    }

    internal fun forceReadbackBusyForTesting(isBusy: Boolean) {
        readbackInFlight.set(isBusy)
    }

    internal fun setReadbackBorrowedByEncoderObserverForTesting(observer: ((Boolean) -> Unit)?) {
        readbackBorrowedByEncoderObserverForTesting = observer
    }

    internal fun lateRenderReadbackLeaseReleaseCountForTesting(): Int =
        synchronized(lock) { lateRenderReadbackLeaseReleaseCountForTesting }

    internal fun currentOutputGenerationForTesting(): Long? =
        synchronized(lock) { sessionCore?.currentOutputGeneration() }

    internal fun overrideEncoderOperationTimeoutForTesting(timeoutMillis: Long) {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive, was $timeoutMillis" }
        encoderOperationTimeoutMillis = timeoutMillis
    }

    internal fun overrideGlOperationTimeoutForTesting(timeoutMillis: Long) {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive, was $timeoutMillis" }
        glOperationTimeoutMillis = timeoutMillis
    }

    internal fun overridePeriodicRefreshDelayForTesting(delayMillis: Long) {
        require(delayMillis > 0L) { "delayMillis must be positive, was $delayMillis" }
        periodicRefreshDelayOverrideMillis = delayMillis
    }

    internal fun setBeforeProductionAttemptMaterializationForTesting(callback: (() -> Unit)?) {
        beforeProductionAttemptMaterializationForTesting = callback
    }

    internal fun setAfterProductionAttemptMaterializedForTesting(callback: (() -> Unit)?) {
        afterProductionAttemptMaterializedForTesting = callback
    }

    internal fun setBeforeOwnerStopTerminalCommitForTesting(callback: (() -> Unit)?) {
        beforeOwnerStopTerminalCommitForTesting = callback
    }

    internal fun setBeforeFinalEncodedPublicationForTesting(callback: (() -> Unit)?) {
        beforeFinalEncodedPublicationForTesting = callback
    }

    internal fun setBeforeFinalPeriodicRefreshPublicationForTesting(callback: (() -> Unit)?) {
        beforeFinalPeriodicRefreshPublicationForTesting = callback
    }

    internal fun setBeforePeriodicRefreshRetentionForTesting(callback: (() -> Unit)?) {
        beforePeriodicRefreshRetentionForTesting = callback
    }

    internal fun setBeforePeriodicRefreshWakeEnqueueForTesting(callback: (() -> Unit)?) {
        beforePeriodicRefreshWakeEnqueueForTesting = callback
    }

    internal fun setAfterPeriodicRefreshWakeEnqueueAttemptForTesting(callback: (() -> Unit)?) {
        afterPeriodicRefreshWakeEnqueueAttemptForTesting = callback
    }

    internal fun setBeforeFrameRatePolicyAdmissionForTesting(callback: (() -> Unit)?) {
        beforeFrameRatePolicyAdmissionForTesting = callback
    }

    internal fun setBeforeFrameRatePolicyDropForTesting(callback: (() -> Unit)?) {
        beforeFrameRatePolicyDropForTesting = callback
    }

    internal fun setBeforeRuntimeFailureTerminalCommitForTesting(callback: (() -> Unit)?) {
        beforeRuntimeFailureTerminalCommitForTesting = callback
    }

    internal fun setBeforeEncodeNonSuccessDropForTesting(callback: (() -> Unit)?) {
        beforeEncodeNonSuccessDropForTesting = callback
    }

    internal fun setBeforeRuntimeParameterCommitOwnerLockForTesting(callback: (() -> Unit)?) {
        beforeRuntimeParameterCommitOwnerLockForTesting = callback
    }

    internal fun setBeforeRuntimeParameterCommitBridgeForTesting(callback: (() -> Unit)?) {
        beforeRuntimeParameterCommitBridgeForTesting = callback
    }

    internal fun pendingSignalsSnapshot(): StartupRuntimePendingSignals =
        runtimeFrameLoop.pendingSignalsSnapshot(
            latestMetrics = transfer.metricsObservation.latestMetrics,
            projectionStopObserved = isProjectionStopped(),
        ).let { signals ->
            synchronized(lock) {
                if (state == ActiveRuntimeOwnerState.Prepared) {
                    startupPendingSignals.mergeForCommit(signals)
                } else {
                    signals
                }
            }
        }

    internal suspend fun commitInitialActiveSession(armRuntimeSignals: Boolean = true): ScreenCaptureSession =
        try {
            throwIfProjectionStoppedBeforeCommit()
            currentCoroutineContext().ensureActive()
            installRuntimeFrameLoopBeforeCommit()
            currentCoroutineContext().ensureActive()
            commitBoundary.afterLastCancellableCheckpoint()
            commitInitialActivePlanCommitted(armRuntimeSignals = armRuntimeSignals)
        } catch (cause: CancellationException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: ScreenCaptureStartException) {
            closeAfterStartupFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            val exception = startupException(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Initial Active commit failed.",
                cause = cause,
            )
            closeAfterStartupFailure(exception)
            throw exception
        }

    /**
     * Final startup success boundary.
     *
     * This method creates the public session and transfers pending runtime work, then returns
     * without GL/readback/encode work or blocking cleanup. Runtime terminal and geometry signals
     * observed during the handoff are queued for the runtime turn after the session is visible.
     */
    private fun commitInitialActivePlanCommitted(armRuntimeSignals: Boolean): ScreenCaptureSession {
        val resources = synchronized(lock) {
            check(state == ActiveRuntimeOwnerState.Prepared) { "ActiveRuntimeOwner is $state." }
            activeResources ?: error("Active runtime resources are missing.")
        }
        val commitSignals = runtimeFrameLoop.drainPendingSignalsForCommit(
            latestMetrics = transfer.metricsObservation.latestMetrics,
            projectionStopObserved = isProjectionStopped(),
        )
        val pendingSignals = startupPendingSignals.mergeForCommit(commitSignals)
        val initialCapturedContentVisible = synchronized(lock) {
            latestCapturedContentVisibleForInitialCommit ?: pendingSignals.latestCapturedContentVisible
        }
        val effectiveParameters = transfer.initialOutputPlan.toEffectiveParameters(resources.encoderInfo)
        val scratch = EncodedAttemptScratch(maxByteCount = resources.encoderResourcesForRuntime.request.maxEncodedBytes)
        lateinit var core: ScreenCaptureSessionCore
        core = ScreenCaptureSessionCore(
            config = transfer.config,
            initialState = ScreenCaptureSessionState.Running(
                output = ScreenCaptureOutputState.Active(effectiveParameters),
                capturedContentVisible = initialCapturedContentVisible,
            ),
            parameterUpdater = { parameters, commitGate -> updateRuntimeParameters(parameters, commitGate) },
            trimMemoryHandler = { trimEncodedScratch(scratch) },
            terminalCommitHandler = { terminalCommit ->
                reserveTerminalCleanupFence()
                runtimeFrameLoop.fenceTerminal()
                terminalCommitHandler(terminalCommit)
            },
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
        val session = ActiveRuntimeSession(owner = this, core = core)
        synchronized(lock) {
            check(state == ActiveRuntimeOwnerState.Prepared) { "ActiveRuntimeOwner is $state." }
            sessionCore = core
            publicSession = session
            encodedScratch = scratch
            activeRequestedParameters = transfer.initialParameters
            activeOutputPlan = transfer.initialOutputPlan
            activeEffectiveParameters = effectiveParameters
            activeOutputGeneration = core.currentOutputGeneration()
            state = ActiveRuntimeOwnerState.Committed
        }
        schedulePostCommitRuntimeSignalDrain(
            pendingSignals = pendingSignals,
            previousEffectiveParameters = effectiveParameters,
            core = core,
        )
        if (armRuntimeSignals) {
            armReturnedSessionRuntimeSignals()
        }
        return session
    }

    private suspend fun updateRuntimeParameters(
        parameters: ScreenCaptureParameters,
        commitGate: ScreenCaptureParameterCommitGate,
    ): ScreenCaptureParameterUpdateResult {
        val fullCandidate = when (val preparation = prepareFullRuntimeParameterUpdate(parameters)) {
            RuntimeFullPreparation.NotNeeded -> null
            is RuntimeFullPreparation.Rejected -> {
                return commitRuntimeParameterPreparationRejection(
                    rejection = preparation.result,
                    commitGate = commitGate,
                )
            }

            is RuntimeFullPreparation.Prepared -> preparation.candidate
        }
        val candidate = if (fullCandidate != null) {
            null
        } else {
            when (val preparation = prepareProviderOnlyRuntimeParameterUpdate(parameters)) {
                RuntimeProviderOnlyPreparation.NotNeeded -> null
                is RuntimeProviderOnlyPreparation.Rejected -> {
                    return commitRuntimeParameterPreparationRejection(
                        rejection = preparation.result,
                        commitGate = commitGate,
                    )
                }

                is RuntimeProviderOnlyPreparation.Prepared -> preparation.candidate
            }
        }
        return try {
            commitRuntimeParameterUpdate(
                parameters = parameters,
                commitGate = commitGate,
                candidate = candidate,
                fullCandidate = fullCandidate,
            )
        } finally {
            candidate?.let { rollbackCandidate ->
                runCatching { rollbackCandidate.close() }
                    .onFailure(::reportCleanupFailure)
                clearRuntimeParameterPreparationToken(rollbackCandidate.planPreparationToken)
            }
            fullCandidate?.let { rollbackCandidate ->
                runCatching { rollbackCandidate.close() }
                    .onFailure(::reportCleanupFailure)
                clearRuntimeParameterPreparationToken(rollbackCandidate.planPreparationToken)
            }
            closeDeferredRuntimeResourcesIfReady()
        }
    }

    private suspend fun prepareFullRuntimeParameterUpdate(
        parameters: ScreenCaptureParameters,
    ): RuntimeFullPreparation {
        val start = beginFullRuntimeParameterPreparation(parameters)
            ?: return RuntimeFullPreparation.NotNeeded
        val token = start.planPreparationToken
        var preparedComponents: PreparedRenderingPipelineComponents? = null
        return try {
            currentCoroutineContext().ensureActive()
            val result = start.outputPlanPreparer.prepareOutputPlan(
                OutputPlanPrepareRequest(
                    planPreparationToken = token,
                    outputPlan = start.candidatePlan,
                    projectionTarget = start.projectionTarget,
                    projectionTargetHandle = transfer.currentProjectionTarget,
                    planRenderingAccess = transfer.planRenderingAccess,
                    encoderProvider = parameters.encoderProvider,
                    abandonGlLaneOnTimeout = false,
                ),
            )
            if (result is RenderingPipelinePreparationResult.Success) {
                preparedComponents = result.components
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is RenderingPipelinePreparationResult.Failure -> {
                    token.invalidate()
                    clearRuntimeParameterPreparationToken(token)
                    RuntimeFullPreparation.Rejected(
                        ScreenCaptureParameterUpdateResult.Rejected(
                            problem = start.core.newProblem(
                                kind = result.failure.kind,
                                message = result.failure.message,
                                cause = result.failure.cause,
                            ),
                        ),
                    )
                }

                RenderingPipelinePreparationResult.LifecycleStale -> {
                    token.invalidate()
                    clearRuntimeParameterPreparationToken(token)
                    RuntimeFullPreparation.Rejected(unavailableRuntimeParameterUpdate(start.core))
                }

                is RenderingPipelinePreparationResult.Success -> RuntimeFullPreparation.Prepared(
                    RuntimeFullOutputPlanCandidate(
                        planPreparationToken = token,
                        baseOutputGeneration = start.baseOutputGeneration,
                        projectionTargetGeneration = start.projectionTarget.generation,
                        candidatePlan = start.candidatePlan,
                        resourcesCandidate = result.components.moveToActiveRuntimeCandidate(
                            outputPlan = start.candidatePlan,
                            projectionTarget = start.projectionTarget,
                        ),
                    ).also {
                        preparedComponents = null
                    },
                )
            }
        } catch (cancellation: CancellationException) {
            token.invalidate()
            clearRuntimeParameterPreparationToken(token)
            preparedComponents?.let { components ->
                runCatching { components.close() }
                    .onFailure(::reportCleanupFailure)
            }
            throw cancellation
        } catch (cause: Throwable) {
            token.invalidate()
            clearRuntimeParameterPreparationToken(token)
            preparedComponents?.let { components ->
                runCatching { components.close() }
                    .onFailure(::reportCleanupFailure)
            }
            throw cause
        }
    }

    private fun beginFullRuntimeParameterPreparation(
        parameters: ScreenCaptureParameters,
    ): RuntimeFullPreparationStart? =
        synchronized(lock) {
            val core = sessionCore ?: return@synchronized null
            if (runtimeTerminalRejectionLocked(core) != null) return@synchronized null
            val classification = classifyRuntimeParameterUpdateLocked(parameters = parameters, core = core)
            if (classification !is RuntimeParameterUpdateClassification.FullSameTargetReplacement) {
                return@synchronized null
            }
            val outputPlanPreparer = transfer.outputPlanPreparer ?: return@synchronized null
            val baseGeneration = activeOutputGeneration ?: return@synchronized null
            runtimeParameterPlanToken = Math.addExact(runtimeParameterPlanToken, 1L)
            check(activeRuntimeParameterPreparationToken == null) {
                "A runtime parameter preparation token is already active."
            }
            val planPreparationToken = PlanPreparationToken(
                ownerToken = this,
                planToken = runtimeParameterPlanToken,
                projectionTargetGeneration = transfer.currentProjectionTarget.generation,
            )
            activeRuntimeParameterPreparationToken = planPreparationToken
            RuntimeFullPreparationStart(
                core = core,
                outputPlanPreparer = outputPlanPreparer,
                planPreparationToken = planPreparationToken,
                baseOutputGeneration = baseGeneration,
                projectionTarget = transfer.currentProjectionTarget.snapshot(),
                candidatePlan = classification.candidatePlan,
            )
        }

    private suspend fun prepareProviderOnlyRuntimeParameterUpdate(
        parameters: ScreenCaptureParameters,
    ): RuntimeProviderOnlyPreparation {
        val start = beginProviderOnlyRuntimeParameterPreparation(parameters)
            ?: return RuntimeProviderOnlyPreparation.NotNeeded
        val token = start.planPreparationToken
        var preparedEncoderResources: PreparedImageEncoderResources? = null
        return try {
            currentCoroutineContext().ensureActive()
            val result = transfer.encoderPrepare.prepare(
                token = token,
                provider = parameters.encoderProvider,
                request = start.candidatePlan.encoderRequest,
            )
            if (result is ImageEncoderPreparationResult.Success) {
                preparedEncoderResources = result.preparedEncoder
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is ImageEncoderPreparationResult.Failure -> {
                    token.invalidate()
                    clearRuntimeParameterPreparationToken(token)
                    RuntimeProviderOnlyPreparation.Rejected(
                        ScreenCaptureParameterUpdateResult.Rejected(
                            problem = start.core.newProblem(
                                kind = result.kind,
                                message = result.message,
                                cause = result.cause,
                            ),
                        ),
                    )
                }

                is ImageEncoderPreparationResult.Success -> RuntimeProviderOnlyPreparation.Prepared(
                    RuntimeProviderOnlyEncoderCandidate(
                        planPreparationToken = token,
                        baseOutputGeneration = start.baseOutputGeneration,
                        projectionTargetGeneration = start.projectionTargetGeneration,
                        candidatePlan = start.candidatePlan,
                        encoderResourcesCandidate = ActiveRuntimeEncoderResourcesCandidate(result.preparedEncoder),
                    ).also {
                        preparedEncoderResources = null
                    },
                )
            }
        } catch (cancellation: CancellationException) {
            token.invalidate()
            clearRuntimeParameterPreparationToken(token)
            preparedEncoderResources?.let { resources ->
                runCatching { resources.close() }
                    .onFailure(::reportCleanupFailure)
            }
            throw cancellation
        } catch (cause: Throwable) {
            token.invalidate()
            clearRuntimeParameterPreparationToken(token)
            preparedEncoderResources?.let { resources ->
                runCatching { resources.close() }
                    .onFailure(::reportCleanupFailure)
            }
            throw cause
        }
    }

    private fun beginProviderOnlyRuntimeParameterPreparation(
        parameters: ScreenCaptureParameters,
    ): RuntimeProviderOnlyPreparationStart? =
        synchronized(lock) {
            val core = sessionCore ?: return@synchronized null
            if (runtimeTerminalRejectionLocked(core) != null) return@synchronized null
            val classification = classifyRuntimeParameterUpdateLocked(parameters = parameters, core = core)
            if (classification !is RuntimeParameterUpdateClassification.ProviderOnlySameTarget) {
                return@synchronized null
            }
            val baseGeneration = activeOutputGeneration ?: return@synchronized null
            runtimeParameterPlanToken = Math.addExact(runtimeParameterPlanToken, 1L)
            check(activeRuntimeParameterPreparationToken == null) {
                "A runtime parameter preparation token is already active."
            }
            val planPreparationToken = PlanPreparationToken(
                ownerToken = this,
                planToken = runtimeParameterPlanToken,
                projectionTargetGeneration = transfer.currentProjectionTarget.generation,
            )
            activeRuntimeParameterPreparationToken = planPreparationToken
            RuntimeProviderOnlyPreparationStart(
                core = core,
                planPreparationToken = planPreparationToken,
                baseOutputGeneration = baseGeneration,
                projectionTargetGeneration = transfer.currentProjectionTarget.generation,
                candidatePlan = classification.candidatePlan,
            )
        }

    private fun clearRuntimeParameterPreparationToken(token: PlanPreparationToken) {
        synchronized(lock) {
            if (activeRuntimeParameterPreparationToken === token) {
                activeRuntimeParameterPreparationToken = null
            }
        }
    }

    private fun commitRuntimeParameterPreparationRejection(
        rejection: ScreenCaptureParameterUpdateResult.Rejected,
        commitGate: ScreenCaptureParameterCommitGate,
    ): ScreenCaptureParameterUpdateResult =
        synchronized(lock) {
            val core = sessionCore ?: return@synchronized rejection
            commitGate.commit {
                runtimeTerminalRejectionLocked(core) ?: rejection
            }
        }

    private fun commitRuntimeParameterUpdate(
        parameters: ScreenCaptureParameters,
        commitGate: ScreenCaptureParameterCommitGate,
        candidate: RuntimeProviderOnlyEncoderCandidate? = null,
        fullCandidate: RuntimeFullOutputPlanCandidate? = null,
    ): ScreenCaptureParameterUpdateResult {
        var resumeProductionAdmissionAfterCommit = false
        beforeRuntimeParameterCommitOwnerLockForTesting?.invoke()
        val result = synchronized(lock) {
            beforeRuntimeParameterCommitBridgeForTesting?.invoke()
            val core = checkNotNull(sessionCore) { "Runtime parameter update has no committed session core." }
            val initialClassification = classifyRuntimeParameterUpdateLocked(parameters = parameters, core = core)
            var productionAdmissionPaused =
                initialClassification is RuntimeParameterUpdateClassification.FrameRateOnly ||
                        candidate != null || fullCandidate != null
            if (productionAdmissionPaused) {
                runtimeFrameLoop.pauseProductionAdmission()
            }
            try {
                commitGate.commit {
                    runtimeTerminalRejectionLocked(core)?.let { rejection -> return@commit rejection }
                    applyRuntimeParameterUpdateClassificationLocked(
                        parameters = parameters,
                        core = core,
                        classification = classifyRuntimeParameterUpdateLocked(parameters = parameters, core = core),
                        productionAdmissionPaused = productionAdmissionPaused,
                        candidate = candidate,
                        fullCandidate = fullCandidate,
                    ).also { outcome ->
                        productionAdmissionPaused = outcome.productionAdmissionStillPaused
                        resumeProductionAdmissionAfterCommit = outcome.resumeProductionAdmissionAfterCommit
                    }.result
                }
            } finally {
                if (productionAdmissionPaused) {
                    resumeProductionAdmissionAfterCommit = true
                }
            }
        }
        if (resumeProductionAdmissionAfterCommit) {
            runtimeFrameLoop.resumeProductionAdmission()
        }
        return result
    }

    private fun runtimeTerminalRejectionLocked(core: ScreenCaptureSessionCore): ScreenCaptureParameterUpdateResult.Rejected? {
        if (state != ActiveRuntimeOwnerState.Committed || ownerStopTerminalCommitted) {
            return ScreenCaptureParameterUpdateResult.Rejected(
                problem = core.newProblem(
                    kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                    message = "Session was stopped by its owner.",
                    cause = null,
                ),
            )
        }
        val queuedPrePublicProjectionStop =
            queuedPostCommitRuntimeSignalDrain?.pendingSignals?.projectionStopObserved == true
        if (!queuedPrePublicProjectionStop && isProjectionStoppedLocked()) {
            return ScreenCaptureParameterUpdateResult.Rejected(
                problem = core.newProblem(
                    kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                    message = "Screen capture projection stopped.",
                    cause = null,
                ),
            )
        }
        return null
    }

    private fun classifyRuntimeParameterUpdateLocked(
        parameters: ScreenCaptureParameters,
        core: ScreenCaptureSessionCore,
    ): RuntimeParameterUpdateClassification =
        parameterUpdateClassifier.classify(
            activeSnapshot = activeRuntimeParameterSnapshotLocked(core),
            requestedParameters = parameters,
        )

    private fun activeRuntimeParameterSnapshotLocked(core: ScreenCaptureSessionCore): RuntimeParameterActiveSnapshot {
        val requestedParameters = checkNotNull(activeRequestedParameters) { "Active requested parameters are missing." }
        val outputPlan = checkNotNull(activeOutputPlan) { "Active output plan is missing." }
        val effectiveParameters = checkNotNull(activeEffectiveParameters) { "Active effective parameters are missing." }
        val running = core.state.value as? ScreenCaptureSessionState.Running
        val outputState = when (running?.output) {
            is ScreenCaptureOutputState.Active -> RuntimeParameterOutputState.Active
            is ScreenCaptureOutputState.Suspended -> RuntimeParameterOutputState.Suspended
            null -> RuntimeParameterOutputState.Suspended
        }
        val currentGeometry = when (val output = running?.output) {
            is ScreenCaptureOutputState.Suspended -> output.currentCaptureGeometry
            else -> outputPlan.captureGeometry
        }
        return RuntimeParameterActiveSnapshot(
            outputState = outputState,
            currentRequestedParameters = requestedParameters,
            currentOutputPlan = outputPlan,
            currentEffectiveParameters = effectiveParameters,
            currentCaptureGeometry = currentGeometry,
            currentCaptureGeometryGeneration = transfer.currentProjectionTarget.generation,
            currentProjectionTarget = RuntimeProjectionTargetIdentity(
                width = transfer.currentProjectionTarget.width,
                height = transfer.currentProjectionTarget.height,
                densityDpi = transfer.currentProjectionTarget.densityDpi,
                captureGeometryGeneration = transfer.currentProjectionTarget.generation,
                targetGeneration = transfer.currentProjectionTarget.generation,
                semantics = RuntimeProjectionTargetSemantics.SurfaceTextureOes,
            ),
        )
    }

    private fun applyRuntimeParameterUpdateClassificationLocked(
        parameters: ScreenCaptureParameters,
        core: ScreenCaptureSessionCore,
        classification: RuntimeParameterUpdateClassification,
        productionAdmissionPaused: Boolean,
        candidate: RuntimeProviderOnlyEncoderCandidate?,
        fullCandidate: RuntimeFullOutputPlanCandidate?,
    ): RuntimeParameterCommitBridgeOutcome =
        when (classification) {
            is RuntimeParameterUpdateClassification.NormalizedNoOp ->
                RuntimeParameterCommitBridgeOutcome(
                    result = ScreenCaptureParameterUpdateResult.Applied,
                    productionAdmissionStillPaused = productionAdmissionPaused,
                    resumeProductionAdmissionAfterCommit = false,
                )

            is RuntimeParameterUpdateClassification.FrameRateOnly ->
                applyRuntimeFrameRateOnlyUpdateLocked(
                    parameters = parameters,
                    core = core,
                    classification = classification,
                    productionAdmissionPaused = productionAdmissionPaused,
                )

            is RuntimeParameterUpdateClassification.ProviderOnlySameTarget ->
                applyRuntimeProviderOnlyUpdateLocked(
                    parameters = parameters,
                    core = core,
                    classification = classification,
                    productionAdmissionPaused = productionAdmissionPaused,
                    candidate = candidate,
                )

            is RuntimeParameterUpdateClassification.FullSameTargetReplacement ->
                applyRuntimeFullOutputPlanUpdateLocked(
                    parameters = parameters,
                    core = core,
                    classification = classification,
                    productionAdmissionPaused = productionAdmissionPaused,
                    candidate = fullCandidate,
                )

            is RuntimeParameterUpdateClassification.Unavailable ->
                RuntimeParameterCommitBridgeOutcome(
                    result = unavailableRuntimeParameterUpdate(core),
                    productionAdmissionStillPaused = productionAdmissionPaused,
                    resumeProductionAdmissionAfterCommit = false,
                )

            is RuntimeParameterUpdateClassification.Rejected ->
                RuntimeParameterCommitBridgeOutcome(
                    result = ScreenCaptureParameterUpdateResult.Rejected(
                        problem = core.newProblem(
                            kind = classification.problem.kind,
                            message = classification.problem.message,
                            cause = null,
                        ),
                    ),
                    productionAdmissionStillPaused = productionAdmissionPaused,
                    resumeProductionAdmissionAfterCommit = false,
                )
        }

    private fun applyRuntimeFullOutputPlanUpdateLocked(
        parameters: ScreenCaptureParameters,
        core: ScreenCaptureSessionCore,
        classification: RuntimeParameterUpdateClassification.FullSameTargetReplacement,
        productionAdmissionPaused: Boolean,
        candidate: RuntimeFullOutputPlanCandidate?,
    ): RuntimeParameterCommitBridgeOutcome {
        if (candidate == null) {
            return RuntimeParameterCommitBridgeOutcome(
                result = unavailableRuntimeParameterUpdate(core),
                productionAdmissionStillPaused = productionAdmissionPaused,
                resumeProductionAdmissionAfterCommit = false,
            )
        }
        if (!productionAdmissionPaused) {
            runtimeFrameLoop.pauseProductionAdmission()
        }
        val previousGeneration = checkNotNull(activeOutputGeneration) { "Active output generation is missing." }
        if (candidate.baseOutputGeneration != previousGeneration ||
            core.currentOutputGeneration() != previousGeneration ||
            candidate.projectionTargetGeneration != transfer.currentProjectionTarget.generation ||
            !candidate.planPreparationToken.consumeForHandoff()
        ) {
            return RuntimeParameterCommitBridgeOutcome(
                result = unavailableRuntimeParameterUpdate(core),
                productionAdmissionStillPaused = true,
                resumeProductionAdmissionAfterCommit = false,
            )
        }
        val previousResources = activeResources ?: return RuntimeParameterCommitBridgeOutcome(
            result = unavailableRuntimeParameterUpdate(core),
            productionAdmissionStillPaused = true,
            resumeProductionAdmissionAfterCommit = false,
        )
        val nextGeneration = Math.addExact(previousGeneration, 1L)
        val effectiveParameters = classification.candidatePlan.toEffectiveParameters(candidate.encoderInfo)
        val replacementResources = candidate.moveToActiveRuntimeOwner()
        activeResources = replacementResources
        deferredRetiredActiveResourcesClose += previousResources
        activeRequestedParameters = parameters
        activeOutputPlan = classification.candidatePlan
        activeEffectiveParameters = effectiveParameters
        activeOutputGeneration = nextGeneration
        lastFrameRateAdmitNanos = null
        hasSuccessfulRuntimeSourceFrame = false
        encodeHardFailureTracker.reset()
        clearRetainedPeriodicRefreshStateLocked()
        val applied = core.updateOutputActive(effectiveParameters = effectiveParameters, generation = nextGeneration)
        check(applied) { "Full runtime output-plan update could not publish active output." }
        return if (isRuntimeWorkInFlightLocked()) {
            pendingRuntimeParameterProductionResume = true
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = false,
            )
        } else {
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = true,
            )
        }
    }

    private fun applyRuntimeProviderOnlyUpdateLocked(
        parameters: ScreenCaptureParameters,
        core: ScreenCaptureSessionCore,
        classification: RuntimeParameterUpdateClassification.ProviderOnlySameTarget,
        productionAdmissionPaused: Boolean,
        candidate: RuntimeProviderOnlyEncoderCandidate?,
    ): RuntimeParameterCommitBridgeOutcome {
        if (candidate == null) {
            return RuntimeParameterCommitBridgeOutcome(
                result = unavailableRuntimeParameterUpdate(core),
                productionAdmissionStillPaused = productionAdmissionPaused,
                resumeProductionAdmissionAfterCommit = false,
            )
        }
        if (!productionAdmissionPaused) {
            runtimeFrameLoop.pauseProductionAdmission()
        }
        val previousGeneration = checkNotNull(activeOutputGeneration) { "Active output generation is missing." }
        if (candidate.baseOutputGeneration != previousGeneration ||
            core.currentOutputGeneration() != previousGeneration ||
            candidate.projectionTargetGeneration != transfer.currentProjectionTarget.generation ||
            !candidate.planPreparationToken.consumeForHandoff()
        ) {
            return RuntimeParameterCommitBridgeOutcome(
                result = unavailableRuntimeParameterUpdate(core),
                productionAdmissionStillPaused = true,
                resumeProductionAdmissionAfterCommit = false,
            )
        }
        val resources = activeResources ?: return RuntimeParameterCommitBridgeOutcome(
            result = unavailableRuntimeParameterUpdate(core),
            productionAdmissionStillPaused = true,
            resumeProductionAdmissionAfterCommit = false,
        )
        val nextGeneration = Math.addExact(previousGeneration, 1L)
        val candidateEncoderInfo = candidate.encoderInfo
        val effectiveParameters = classification.candidatePlan.toEffectiveParameters(candidateEncoderInfo)
        val retiredResources = resources.replaceEncoderResourcesOnly(candidate.encoderResourcesCandidate)
        candidate.markCommitted()
        deferredRetiredEncoderResourcesClose += retiredResources
        activeRequestedParameters = parameters
        activeOutputPlan = classification.candidatePlan
        activeEffectiveParameters = effectiveParameters
        activeOutputGeneration = nextGeneration
        lastFrameRateAdmitNanos = null
        hasSuccessfulRuntimeSourceFrame = false
        encodeHardFailureTracker.reset()
        clearRetainedPeriodicRefreshStateLocked()
        val applied = core.updateOutputActive(effectiveParameters = effectiveParameters, generation = nextGeneration)
        check(applied) { "Provider-only runtime parameter update could not publish active output." }
        return if (isRuntimeWorkInFlightLocked()) {
            pendingRuntimeParameterProductionResume = true
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = false,
            )
        } else {
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = true,
            )
        }
    }

    private fun applyRuntimeFrameRateOnlyUpdateLocked(
        parameters: ScreenCaptureParameters,
        core: ScreenCaptureSessionCore,
        classification: RuntimeParameterUpdateClassification.FrameRateOnly,
        productionAdmissionPaused: Boolean,
    ): RuntimeParameterCommitBridgeOutcome {
        if (!productionAdmissionPaused) {
            runtimeFrameLoop.pauseProductionAdmission()
        }
        val previousGeneration = checkNotNull(activeOutputGeneration) { "Active output generation is missing." }
        check(core.currentOutputGeneration() == previousGeneration) {
            "Active output generation snapshot is stale."
        }
        val resources = activeResources ?: return RuntimeParameterCommitBridgeOutcome(
            result = unavailableRuntimeParameterUpdate(core),
            productionAdmissionStillPaused = true,
            resumeProductionAdmissionAfterCommit = false,
        )
        val nextGeneration = Math.addExact(previousGeneration, 1L)
        val effectiveParameters = classification.candidatePlan.toEffectiveParameters(resources.encoderInfo)
        activeRequestedParameters = parameters
        activeOutputPlan = classification.candidatePlan
        activeEffectiveParameters = effectiveParameters
        activeOutputGeneration = nextGeneration
        lastFrameRateAdmitNanos = null
        hasSuccessfulRuntimeSourceFrame = false
        encodeHardFailureTracker.reset()
        clearRetainedPeriodicRefreshStateLocked()
        val applied = core.updateOutputActive(effectiveParameters = effectiveParameters, generation = nextGeneration)
        check(applied) { "Frame-rate-only runtime parameter update could not publish active output." }
        return if (isRuntimeWorkInFlightLocked()) {
            pendingRuntimeParameterProductionResume = true
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = false,
            )
        } else {
            RuntimeParameterCommitBridgeOutcome(
                result = ScreenCaptureParameterUpdateResult.Applied,
                productionAdmissionStillPaused = false,
                resumeProductionAdmissionAfterCommit = true,
            )
        }
    }

    private fun unavailableRuntimeParameterUpdate(core: ScreenCaptureSessionCore): ScreenCaptureParameterUpdateResult.Rejected =
        ScreenCaptureParameterUpdateResult.Rejected(
            problem = core.newProblem(
                kind = ScreenCaptureProblemKind.ParameterUpdateUnavailable,
                message = "Parameter update is outside the active same-target boundary or became stale before commit.",
                cause = null,
            ),
        )

    internal fun armReturnedSessionRuntimeSignals() {
        val outputGeneration = synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed || returnedSessionRuntimeSignalsArmed) return
            returnedSessionRuntimeSignalsArmed = true
            activeOutputGeneration
        }
        outputGeneration?.let(::scheduleNextPeriodicRefreshIfNeeded)
        if (hasPendingRuntimeWorkForScheduling()) {
            scheduleRuntimeTurn()
        }
    }

    internal suspend fun drainRuntimeProductionTick(): RuntimeFrameProductionTickResult {
        drainQueuedPostCommitRuntimeSignals()
        drainLatestRuntimeSignals()
        val periodicRefreshWake = runtimeFrameLoop.consumePeriodicRefreshWake()
        val signal = runtimeFrameLoop.admitLatestFrameSignal() ?: run {
            return when {
                periodicRefreshWake && !synchronized(lock) { hasSuccessfulRuntimeSourceFrame } -> {
                    recordPeriodicRefreshNoSourceWake()
                    RuntimeFrameProductionTickResult.PeriodicRefreshNoSourceFrame
                }

                periodicRefreshWake -> publishPeriodicRefreshFrame()
                else -> RuntimeFrameProductionTickResult.NoFrameSignal
            }
        }
        val production = synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed) return RuntimeFrameProductionTickResult.NoCommittedSession
            val core = sessionCore ?: return RuntimeFrameProductionTickResult.NoCommittedSession
            val resources = activeResources ?: return RuntimeFrameProductionTickResult.NoCommittedSession
            val scratch = encodedScratch ?: return RuntimeFrameProductionTickResult.NoCommittedSession
            val outputGeneration = core.currentOutputGeneration()
            val encoderResources = resources.encoderResourcesForRuntime
            materializedRuntimeWorkInFlight = true
            RuntimeProductionState(
                core = core,
                resources = resources,
                scratch = scratch,
                outputGeneration = outputGeneration,
                encoderResources = encoderResources,
                effectiveParameters = checkNotNull(activeEffectiveParameters) {
                    "Active effective parameters are missing."
                },
                captureGeometry = checkNotNull(activeOutputPlan) {
                    "Active output plan is missing."
                }.captureGeometry,
                encodeHealthKey = RuntimeEncodeHealthKey(
                    outputGeneration = outputGeneration,
                    encoderResources = encoderResources,
                ),
            )
        }
        if (signal.generation != transfer.currentProjectionTarget.generation) {
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            return RuntimeFrameProductionTickResult.StaleProjectionTargetSignal
        }
        beforeFrameRatePolicyAdmissionForTesting?.invoke()
        when (admitFrameRatePolicy(production)) {
            FrameRatePolicyAdmission.Admitted -> Unit
            FrameRatePolicyAdmission.DroppedByPolicy -> {
                finishRuntimeProductionWork()
                closeDeferredRuntimeResourcesIfReady()
                return RuntimeFrameProductionTickResult.FrameRatePolicyDrop
            }

            FrameRatePolicyAdmission.ProjectionStopped -> {
                finishRuntimeProductionWork()
                closeDeferredRuntimeResourcesIfReady()
                finishProjectionStopped()
                return RuntimeFrameProductionTickResult.StaleDrop
            }
        }
        val outputGeneration = production.outputGeneration
        beforeProductionAttemptMaterializationForTesting?.invoke()
        if (isProjectionStopped()) {
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            finishProjectionStopped()
            return RuntimeFrameProductionTickResult.NotMaterialized
        }
        val attempt = production.core.beginProductionAttempt(generation = outputGeneration) ?: run {
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            return RuntimeFrameProductionTickResult.NotMaterialized
        }
        afterProductionAttemptMaterializedForTesting?.invoke()
        if (!encoderInFlight.compareAndSet(false, true)) {
            val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.EncoderBusy)
            finishRuntimeProductionWork()
            if (dropCompletion.projectionStopped) finishProjectionStopped()
            return if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncoderBusyDrop
        }
        if (!readbackInFlight.compareAndSet(false, true)) {
            encoderInFlight.set(false)
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.ReadbackBusy)
            if (dropCompletion.projectionStopped) finishProjectionStopped()
            return if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.ReadbackBusyDrop
        }
        val renderResult = try {
            renderReadbackWithWatchdog(production.resources)
        } catch (timeout: TimeoutCancellationException) {
            val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
            if (dropCompletion.isStaleGeneration) {
                readbackInFlight.set(false)
                encoderInFlight.set(false)
                finishRuntimeProductionWork()
                if (dropCompletion.projectionStopped) finishProjectionStopped()
                closeDeferredRuntimeResourcesIfReady()
                return RuntimeFrameProductionTickResult.StaleDrop
            }
            quarantineNonGlResourcesAfterGlTimeout(production.resources)
            if (dropCompletion.projectionStopped) {
                finishProjectionStopped()
            } else {
                failRuntimeGlProduction(production.core, "Runtime GL frame production timed out.", timeout)
            }
            return RuntimeFrameProductionTickResult.GlFailed
        } catch (cause: Throwable) {
            readbackInFlight.set(false)
            encoderInFlight.set(false)
            finishRuntimeProductionWork()
            val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
            if (dropCompletion.projectionStopped) {
                finishProjectionStopped()
            }
            if (dropCompletion.isStaleGeneration) {
                closeDeferredRuntimeResourcesIfReady()
                return RuntimeFrameProductionTickResult.StaleDrop
            } else {
                failRuntimeGlProduction(production.core, "Runtime GL frame production failed.", cause)
                closeDeferredRuntimeResourcesIfReady()
                return RuntimeFrameProductionTickResult.GlFailed
            }
        }
        readbackInFlight.set(false)
        return when (renderResult.result) {
            RuntimeEs2RenderReadbackResult.ReadbackBusy -> {
                encoderInFlight.set(false)
                finishRuntimeProductionWork()
                closeDeferredRuntimeResourcesIfReady()
                val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.ReadbackBusy)
                if (dropCompletion.projectionStopped) finishProjectionStopped()
                if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.ReadbackBusyDrop
            }

            is RuntimeEs2RenderReadbackResult.Success -> {
                if (isRuntimeTerminalOrClosed() || isProjectionStopped()) {
                    renderResult.result.lease.close()
                    encoderInFlight.set(false)
                    finishRuntimeProductionWork()
                    attempt.completeDrop(ProductionFrameDropKind.StaleGeneration)
                    if (isProjectionStopped()) finishProjectionStopped()
                    closeDeferredRuntimeResourcesIfReady()
                    return RuntimeFrameProductionTickResult.StaleDrop
                }
                markSuccessfulRuntimeSourceFrameIfCurrent(production)
                attempt.recordReadbackSuccess(durationNanos = renderResult.durationNanos)
                readbackBorrowedByEncoderObserverForTesting?.invoke(true)
                encodeReadback(
                    production = production,
                    attempt = attempt,
                    outputGeneration = outputGeneration,
                    readback = renderResult.result,
                )
            }
        }
    }

    private suspend fun installRuntimeFrameLoopBeforeCommit() {
        synchronized(lock) {
            check(state == ActiveRuntimeOwnerState.Prepared) { "ActiveRuntimeOwner is $state." }
            if (frameLoopInstalled) return
        }
        val access = transfer.projectionTargetOwner as? RuntimeProjectionTargetGlAccess
            ?: error("ProjectionTargetOwner does not provide runtime frame signal access.")
        access.installRuntimeFrameSignalSink(
            target = transfer.currentProjectionTarget,
            generation = transfer.currentProjectionTarget.generation,
            sink = runtimeFrameLoop.frameSignalSink,
        )
        synchronized(lock) {
            check(state == ActiveRuntimeOwnerState.Prepared) { "ActiveRuntimeOwner is $state." }
            frameLoopInstalled = true
        }
    }

    private suspend fun renderReadbackWithWatchdog(
        resources: ActiveRuntimePreparedRenderingPipelineResources,
    ): TimedRuntimeRenderReadbackResult =
        withTimeout(glOperationTimeoutMillis.milliseconds) {
            val access = transfer.projectionTargetOwner as? RuntimeProjectionTargetGlAccess
                ?: error("ProjectionTargetOwner does not provide runtime projection target access.")
            val readbackResources = resources.es2ReadbackResourcesForRuntime()
            val transformPackage = resources.renderTransformPackage
            val startedNanos = elapsedRealtimeNanos()
            val result = access.withCurrentRuntimeProjectionTarget(
                target = transfer.currentProjectionTarget,
                generation = transfer.currentProjectionTarget.generation,
                onCancellation = ::closeLateRenderReadbackResult,
            ) {
                val frame = consumeLatestFrame(oesMatrixScratch)
                frameRenderer.renderReadback(
                    RuntimeEs2RenderReadbackRequest(
                        gl = gl,
                        projectionTargetGeneration = transfer.currentProjectionTarget.generation,
                        projectionTargetIdentity = projectionTargetIdentity,
                        frame = frame,
                        resources = readbackResources,
                        transformPackage = transformPackage,
                        composedTextureMatrixScratch = composedTextureMatrixScratch,
                    ),
                )
            }
            TimedRuntimeRenderReadbackResult(
                result = result,
                durationNanos = (elapsedRealtimeNanos() - startedNanos).coerceAtLeast(0L),
            )
        }

    private fun encodeReadback(
        production: RuntimeProductionState,
        attempt: dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken,
        outputGeneration: Long,
        readback: RuntimeEs2RenderReadbackResult.Success,
    ): RuntimeFrameProductionTickResult {
        val encoderResources = production.encoderResources
        val scratch = production.scratch
        val encodeStartedNanos = elapsedRealtimeNanos()
        var quarantinedEncoderWork = false
        return try {
            if (isRuntimeTerminalOrClosed() || isProjectionStopped()) {
                attempt.completeDrop(ProductionFrameDropKind.StaleGeneration)
                if (isProjectionStopped()) finishProjectionStopped()
                return RuntimeFrameProductionTickResult.StaleDrop
            }
            val input = LeasedRgbaImageEncoderInput(readback.lease)
            val sink = scratch.begin()
            val encodeResult = try {
                encodeWithTimeout(
                    encoderResources = encoderResources,
                    scratch = scratch,
                    input = input,
                    sink = sink,
                    lease = readback.lease,
                )
            } catch (timeout: RuntimeEncoderTimeoutException) {
                quarantinedEncoderWork = true
                val dropCompletion = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
                if (dropCompletion.isStaleGeneration) {
                    if (dropCompletion.projectionStopped) finishProjectionStopped()
                    return RuntimeFrameProductionTickResult.StaleDrop
                }
                quarantineNonEncoderResourcesAfterEncodeTimeout()
                if (dropCompletion.projectionStopped) {
                    finishProjectionStopped()
                } else {
                    failRuntimeEncoderProduction(production.core, timeout)
                }
                return RuntimeFrameProductionTickResult.EncodeTimedOutDrop
            } catch (cause: Throwable) {
                val rejected = scratch.wasRejected
                scratch.finishDiscard()
                val dropCompletion = when {
                    rejected -> completeEncodeNonSuccessDropWithProjectionFence(
                        attempt = attempt,
                        kind = ProductionFrameDropKind.EncodedSizeLimit,
                    )

                    cause is CancellationException || cause is InterruptedException ->
                        completeEncodeNonSuccessDropWithProjectionFence(
                            attempt = attempt,
                            kind = ProductionFrameDropKind.TransientFailure,
                        )

                    else -> completeRuntimeEncodeHardFailure(production = production, attempt = attempt, cause = cause)
                }
                return if (dropCompletion.isStaleGeneration) {
                    RuntimeFrameProductionTickResult.StaleDrop
                } else if (rejected) {
                    RuntimeFrameProductionTickResult.EncodedSizeLimitDrop
                } else {
                    RuntimeFrameProductionTickResult.EncodeThrewDrop
                }
            }
            if (isRuntimeTerminalOrClosed() || isProjectionStopped()) {
                scratch.finishDiscard()
                attempt.completeDrop(ProductionFrameDropKind.StaleGeneration)
                if (isProjectionStopped()) finishProjectionStopped()
                return RuntimeFrameProductionTickResult.StaleDrop
            }
            when (encodeResult) {
                ImageEncodeResult.Success -> {
                    val rejected = scratch.wasRejected
                    val bytes = scratch.finishSuccess()
                    when {
                        bytes != null -> {
                            recordRuntimeEncodeSuccessIfCurrent(production)
                            val encodeDurationNanos = (elapsedRealtimeNanos() - encodeStartedNanos).coerceAtLeast(0L)
                            val publicationNanos = elapsedRealtimeNanos()
                            val published = completeEncodedSuccessWithProjectionFence(
                                attempt = attempt,
                                format = encoderResources.info.outputFormat,
                                bytes = bytes,
                                encodeDurationNanos = encodeDurationNanos,
                                timestampElapsedRealtimeNanos = publicationNanos,
                            )
                            if (published) {
                                beforePeriodicRefreshRetentionForTesting?.invoke()
                                val retained = rememberPeriodicRefreshFrameIfCurrent(
                                    production = production,
                                    format = encoderResources.info.outputFormat,
                                    bytes = bytes,
                                    publicationElapsedRealtimeNanos = publicationNanos,
                                )
                                if (retained) {
                                    scheduleNextPeriodicRefreshIfNeeded(outputGeneration)
                                }
                                RuntimeFrameProductionTickResult.Published
                            } else {
                                RuntimeFrameProductionTickResult.StaleDrop
                            }
                        }

                        rejected -> {
                            val dropCompletion = completeEncodeNonSuccessDropWithProjectionFence(
                                attempt,
                                ProductionFrameDropKind.EncodedSizeLimit,
                            )
                            if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodedSizeLimitDrop
                        }

                        else -> {
                            val dropCompletion = completeRuntimeEncodeHardFailure(
                                production = production,
                                attempt = attempt,
                                cause = null,
                            )
                            if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodeFailedDrop
                        }
                    }
                }

                is ImageEncodeResult.Failed -> {
                    val rejected = scratch.wasRejected
                    scratch.finishDiscard()
                    if (rejected) {
                        val dropCompletion = completeEncodeNonSuccessDropWithProjectionFence(
                            attempt,
                            ProductionFrameDropKind.EncodedSizeLimit,
                        )
                        if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodedSizeLimitDrop
                    } else {
                        val dropCompletion = completeRuntimeEncodeHardFailure(
                            production = production,
                            attempt = attempt,
                            cause = encodeResult.cause,
                        )
                        if (dropCompletion.isStaleGeneration) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodeFailedDrop
                    }
                }
            }
        } finally {
            if (!quarantinedEncoderWork) {
                try {
                    readback.lease.close()
                } finally {
                    readbackBorrowedByEncoderObserverForTesting?.invoke(false)
                    encoderInFlight.set(false)
                    finishRuntimeProductionWork()
                    applyPendingEncodedScratchTrimIfReady()
                    closeDeferredRuntimeResourcesIfReady()
                }
            }
        }
    }

    private fun encodeWithTimeout(
        encoderResources: PreparedImageEncoderResources,
        scratch: EncodedAttemptScratch,
        input: ImageEncoderInput,
        sink: dev.dmkr.screencaptureengine.EncodedImageSink,
        lease: RgbaReadbackLease,
    ): ImageEncodeResult {
        val timedOut = AtomicBoolean(false)
        val cleanupClaimed = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val encodeFuture: Future<ImageEncodeResult> = try {
            encoderLane.submit<ImageEncodeResult> {
                started.set(true)
                try {
                    encoderResources.encoder.encode(input, sink)
                } finally {
                    if (timedOut.get() && cleanupClaimed.compareAndSet(false, true)) {
                        cleanupTimedOutEncoderWork(scratch = scratch, lease = lease)
                    }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            throw IllegalStateException("Runtime encoder lane rejected encode work.", rejected)
        }
        return try {
            encodeFuture.get(encoderOperationTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            timedOut.set(true)
            val cancelled = encodeFuture.cancel(true)
            if (cancelled && !started.get() && cleanupClaimed.compareAndSet(false, true)) {
                cleanupTimedOutEncoderWork(scratch = scratch, lease = lease)
            }
            // Timed-out provider code may still be inside encode. The runtime fences publication and
            // keeps the borrowed raw buffer/encoder out of reuse until the worker returns.
            throw RuntimeEncoderTimeoutException(
                message = "Runtime image encoding exceeded ${encoderOperationTimeoutMillis}ms.",
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (execution: ExecutionException) {
            throw execution.cause ?: execution
        }
    }

    private fun cleanupTimedOutEncoderWork(scratch: EncodedAttemptScratch, lease: RgbaReadbackLease) {
        runCatching { scratch.finishDiscard() }.onFailure(::reportCleanupFailure)
        runCatching { lease.close() }.onFailure(::reportCleanupFailure)
        readbackBorrowedByEncoderObserverForTesting?.invoke(false)
        encoderInFlight.set(false)
        finishRuntimeProductionWork()
        applyPendingEncodedScratchTrimIfReady()
        closeDeferredRuntimeResourcesIfReady()
    }

    private fun completeEncodedSuccessWithProjectionFence(
        attempt: dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken,
        format: EncodedImageFormat,
        bytes: ByteArray,
        encodeDurationNanos: Long,
        timestampElapsedRealtimeNanos: Long,
    ): Boolean {
        var shouldFinishProjectionStopped = false
        beforeFinalEncodedPublicationForTesting?.invoke()
        val published = arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                if (isProjectionStoppedLocked(rawStopObserved)) {
                    attempt.completeDrop(ProductionFrameDropKind.StaleGeneration)
                    shouldFinishProjectionStopped = true
                    false
                } else {
                    attempt.completeEncodedSuccess(
                        format = format,
                        bytes = bytes,
                        encodeDurationNanos = encodeDurationNanos,
                        timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
                        copyBytes = false,
                    )
                }
            }
        }
        if (shouldFinishProjectionStopped) {
            clearRetainedPeriodicRefreshState()
            finishProjectionStopped()
        }
        return published
    }

    private fun completeProductionDropWithProjectionFence(
        attempt: dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken,
        kind: ProductionFrameDropKind,
    ): RuntimeProductionDropCompletion {
        var projectionStopped = false
        var resolvedKind: ProductionFrameDropKind? = null
        arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                projectionStopped = isProjectionStoppedLocked(rawStopObserved)
                resolvedKind = attempt.completeDropAndResolve(if (projectionStopped) ProductionFrameDropKind.StaleGeneration else kind)
            }
        }
        return RuntimeProductionDropCompletion(
            projectionStopped = projectionStopped,
            resolvedKind = resolvedKind,
        )
    }

    private fun completeEncodeNonSuccessDropWithProjectionFence(
        attempt: dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken,
        kind: ProductionFrameDropKind,
    ): RuntimeProductionDropCompletion {
        beforeEncodeNonSuccessDropForTesting?.invoke()
        val dropCompletion = completeProductionDropWithProjectionFence(attempt, kind)
        if (dropCompletion.projectionStopped) finishProjectionStopped()
        return dropCompletion
    }

    private fun completeRuntimeEncodeHardFailure(
        production: RuntimeProductionState,
        attempt: dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken,
        cause: Throwable?,
    ): RuntimeProductionDropCompletion {
        beforeEncodeNonSuccessDropForTesting?.invoke()
        var projectionStopped = false
        var resolvedKind: ProductionFrameDropKind? = null
        arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                projectionStopped = isProjectionStoppedLocked(rawStopObserved)
                resolvedKind = attempt.completeDropAndResolve(
                    if (projectionStopped) ProductionFrameDropKind.StaleGeneration else ProductionFrameDropKind.TransientFailure,
                )
                if (!projectionStopped &&
                    resolvedKind == ProductionFrameDropKind.TransientFailure &&
                    isCurrentRuntimeProductionLocked(production)
                ) {
                    when (encodeHardFailureTracker.recordHardFailure(production.encodeHealthKey)) {
                        RuntimeEncodeHardFailureResult.BelowThreshold -> Unit
                        RuntimeEncodeHardFailureResult.ThresholdReached -> {
                            runtimeFrameLoop.pauseProductionAdmission()
                            val nextGeneration = Math.addExact(production.outputGeneration, 1L)
                            val problem = production.core.newProblem(
                                kind = ScreenCaptureProblemKind.EncodeRepeatedFailure,
                                message = "Runtime image encoding failed repeatedly.",
                                cause = cause,
                            )
                            val suspended = production.core.updateOutputSuspended(
                                problem = problem,
                                previousEffectiveParameters = production.effectiveParameters,
                                currentCaptureGeometry = production.captureGeometry,
                                generation = nextGeneration,
                            )
                            if (suspended) {
                                activeOutputGeneration = nextGeneration
                                lastFrameRateAdmitNanos = null
                                hasSuccessfulRuntimeSourceFrame = false
                                clearRetainedPeriodicRefreshStateLocked()
                            }
                        }
                    }
                }
            }
        }
        if (projectionStopped) finishProjectionStopped()
        return RuntimeProductionDropCompletion(
            projectionStopped = projectionStopped,
            resolvedKind = resolvedKind,
        )
    }

    private fun recordRuntimeEncodeSuccessIfCurrent(production: RuntimeProductionState) {
        synchronized(lock) {
            if (isCurrentRuntimeProductionLocked(production)) {
                encodeHardFailureTracker.recordSuccess(production.encodeHealthKey)
            }
        }
    }

    private fun markSuccessfulRuntimeSourceFrameIfCurrent(production: RuntimeProductionState) {
        synchronized(lock) {
            if (isCurrentRuntimeProductionLocked(production)) {
                hasSuccessfulRuntimeSourceFrame = true
            }
        }
    }

    private fun isCurrentRuntimeProductionLocked(production: RuntimeProductionState): Boolean =
        state == ActiveRuntimeOwnerState.Committed &&
                !isProjectionStoppedLocked() &&
                activeOutputGeneration == production.outputGeneration &&
                activeResources === production.resources &&
                production.core.currentOutputGeneration() == production.outputGeneration

    private fun <T> arbitrateProjectionStopPublicOutcome(block: (rawStopObserved: Boolean) -> T): T =
        transfer.callbackAdapter.projectionStopArbiter.arbitratePublicOutcome(block)

    private fun quarantineNonGlResourcesAfterGlTimeout(resources: ActiveRuntimePreparedRenderingPipelineResources) {
        var scratchToRelease: EncodedAttemptScratch? = null
        val closeVirtualDisplay = synchronized(lock) {
            scratchToRelease = encodedScratch
            encodedScratch = null
            pendingEncodedScratchTrim = false
            releaseEncodedScratchOnRuntimeClose = false
            markVirtualDisplayOwnerCloseScheduledLocked()
        }
        scratchToRelease?.let(::releaseEncodedScratch)
        scheduleStartupCleanup(
            cleanupScheduler = transfer.cleanupScheduler,
            cleanupFailureSink = transfer.cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            cleanupFailures.collect { resources.closeEncoderResourcesOnly() }
            if (closeVirtualDisplay) {
                cleanupFailures.collect { transfer.virtualDisplayOwner.close() }
            }
            cleanupFailures.throwIfAny()
        }
    }

    private fun quarantineNonEncoderResourcesAfterEncodeTimeout() {
        val closeVirtualDisplay: Boolean
        val closeProjectionTargetOwner: Boolean
        synchronized(lock) {
            closeVirtualDisplay = markVirtualDisplayOwnerCloseScheduledLocked()
            closeProjectionTargetOwner = markProjectionTargetOwnerCloseScheduledLocked()
        }
        scheduleStartupCleanup(
            cleanupScheduler = transfer.cleanupScheduler,
            cleanupFailureSink = transfer.cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            if (closeVirtualDisplay) {
                cleanupFailures.collect { transfer.virtualDisplayOwner.close() }
            }
            if (closeProjectionTargetOwner) {
                cleanupFailures.collect { transfer.projectionTargetOwner.close() }
            }
            cleanupFailures.throwIfAny()
        }
    }

    private fun failRuntimeGlProduction(core: ScreenCaptureSessionCore, message: String, cause: Throwable) {
        runCatching { (transfer.projectionTargetOwner as? ProjectionTargetOwnerAbandonment)?.abandonGlLane() }
        val projectionStopped = finishRuntimeFailureWithProjectionFence(
            core = core,
            kind = ScreenCaptureProblemKind.GlResourceFailure,
            message = message,
            cause = cause,
        )
        closeRuntimeResources(stopProjection = !projectionStopped)
    }

    private fun failRuntimeEncoderProduction(core: ScreenCaptureSessionCore, cause: Throwable) {
        val projectionStopped = finishRuntimeFailureWithProjectionFence(
            core = core,
            kind = ScreenCaptureProblemKind.EncodeRepeatedFailure,
            message = "Runtime image encoding timed out.",
            cause = cause,
        )
        closeRuntimeResources(stopProjection = !projectionStopped)
    }

    private fun finishRuntimeFailureWithProjectionFence(
        core: ScreenCaptureSessionCore,
        kind: ScreenCaptureProblemKind,
        message: String,
        cause: Throwable,
    ): Boolean {
        var projectionStopped = false
        beforeRuntimeFailureTerminalCommitForTesting?.invoke()
        arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                projectionStopped = isProjectionStoppedLocked(rawStopObserved)
                if (projectionStopped) {
                    core.finishStopped(
                        reason = ScreenCaptureStopReason.CaptureEnded,
                        problem = core.newProblem(
                            kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                            message = "Screen capture projection stopped.",
                            cause = null,
                        ),
                    )
                } else {
                    core.finishFailed(
                        core.newProblem(
                            kind = kind,
                            message = message,
                            cause = cause,
                        ),
                    )
                }
            }
        }
        return projectionStopped
    }

    private fun closeLateRenderReadbackResult(result: RuntimeEs2RenderReadbackResult) {
        if (result is RuntimeEs2RenderReadbackResult.Success) {
            runCatching { result.lease.close() }
                .onSuccess {
                    synchronized(lock) {
                        lateRenderReadbackLeaseReleaseCountForTesting++
                    }
                }
        }
        readbackInFlight.set(false)
        encoderInFlight.set(false)
        finishRuntimeProductionWork()
        applyPendingEncodedScratchTrimIfReady()
        closeDeferredRuntimeResourcesIfReady()
    }

    private fun trimEncodedScratch(scratch: EncodedAttemptScratch) {
        clearRetainedPeriodicRefreshState()
        if (encoderInFlight.get()) {
            synchronized(lock) {
                pendingEncodedScratchTrim = true
            }
            return
        }
        runCatching { scratch.trimToSize() }
            .onFailure {
                synchronized(lock) {
                    pendingEncodedScratchTrim = true
                }
            }
    }

    private fun applyPendingEncodedScratchTrimIfReady() {
        val scratch = synchronized(lock) {
            if (encoderInFlight.get()) return
            if (!pendingEncodedScratchTrim) return
            pendingEncodedScratchTrim = false
            encodedScratch
        } ?: return
        runCatching { scratch.trimToSize() }
            .onFailure {
                synchronized(lock) {
                    pendingEncodedScratchTrim = true
                }
            }
    }

    internal fun drainQueuedRuntimeTerminalSignals() {
        val queuedTerminalDrain = synchronized(lock) {
            if (!returnedSessionRuntimeSignalsArmed) return
            val drain = queuedPostCommitRuntimeSignalDrain
            if (drain?.pendingSignals?.projectionStopObserved == true) {
                queuedPostCommitRuntimeSignalDrain = null
                true
            } else {
                false
            }
        }
        if (queuedTerminalDrain) {
            finishProjectionStopped()
            return
        }
        val signals = runtimeFrameLoop.pendingSignalsSnapshot(
            latestMetrics = transfer.metricsObservation.latestMetrics,
            projectionStopObserved = isProjectionStopped(),
        )
        if (signals.projectionStopObserved) {
            synchronized(lock) {
                queuedPostCommitRuntimeSignalDrain = null
            }
            finishProjectionStopped()
        }
    }

    internal fun drainQueuedPostCommitRuntimeSignals() {
        val pendingDrain = synchronized(lock) {
            if (!returnedSessionRuntimeSignalsArmed) return
            val drain = queuedPostCommitRuntimeSignalDrain ?: return
            queuedPostCommitRuntimeSignalDrain = null
            drain
        }
        runCatching {
            drainPostCommitRuntimeSignals(
                pendingSignals = pendingDrain.pendingSignals,
                previousEffectiveParameters = pendingDrain.previousEffectiveParameters,
                core = pendingDrain.core,
            )
        }.onFailure(::reportCleanupFailure)
    }

    private fun drainLatestRuntimeSignals() {
        val core = synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed) return
            sessionCore
        } ?: return
        val pendingSignals = runtimeFrameLoop.drainPendingSignalsForRuntime(
            latestMetrics = transfer.metricsObservation.latestMetrics,
            projectionStopObserved = isProjectionStopped(),
        )
        if (pendingSignals.projectionStopObserved || isProjectionStopped()) {
            finishProjectionStopped()
            return
        }
        if (pendingSignals.metricsObservationChanged) {
            emitInvalidMetricsIgnoredIfUnavailable(core)
        }
        pendingSignals.latestCapturedContentVisible?.let(core::updateCapturedContentVisibility)
        val pendingCaptureGeometry = pendingSignals.pendingCaptureGeometry ?: return
        val previousEffectiveParameters = currentEffectiveParameters(core) ?: return
        clearRetainedPeriodicRefreshState()
        core.updateOutputSuspended(
            problem = core.newProblem(
                kind = ScreenCaptureProblemKind.OutputPlanInvalid,
                message = "Capture geometry changed and this session cannot reconfigure the output plan.",
                cause = null,
            ),
            previousEffectiveParameters = previousEffectiveParameters,
            currentCaptureGeometry = pendingCaptureGeometry,
        )
    }

    private fun schedulePostCommitRuntimeSignalDrain(
        pendingSignals: StartupRuntimePendingSignals,
        previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        core: ScreenCaptureSessionCore,
    ) {
        if (!pendingSignals.projectionStopObserved && pendingSignals.pendingCaptureGeometry == null && !pendingSignals.metricsObservationChanged) return
        synchronized(lock) {
            if (state == ActiveRuntimeOwnerState.Committed) {
                queuedPostCommitRuntimeSignalDrain = PostCommitRuntimeSignalDrain(
                    pendingSignals = pendingSignals,
                    previousEffectiveParameters = previousEffectiveParameters,
                    core = core,
                )
            }
        }
        if (synchronized(lock) { returnedSessionRuntimeSignalsArmed }) {
            scheduleRuntimeTurn()
        }
    }

    private fun drainPostCommitRuntimeSignals(
        pendingSignals: StartupRuntimePendingSignals,
        previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        core: ScreenCaptureSessionCore,
    ) {
        if (pendingSignals.projectionStopObserved || isProjectionStopped()) {
            finishProjectionStopped()
            return
        }
        if (pendingSignals.metricsObservationChanged) {
            emitInvalidMetricsIgnoredIfUnavailable(core)
        }
        val pendingCaptureGeometry = pendingSignals.pendingCaptureGeometry ?: return
        clearRetainedPeriodicRefreshState()
        core.updateOutputSuspended(
            problem = core.newProblem(
                kind = ScreenCaptureProblemKind.OutputPlanInvalid,
                message = "Capture geometry changed and this session cannot reconfigure the output plan.",
                cause = null,
            ),
            previousEffectiveParameters = previousEffectiveParameters,
            currentCaptureGeometry = pendingCaptureGeometry,
        )
    }

    private fun emitInvalidMetricsIgnoredIfUnavailable(core: ScreenCaptureSessionCore) {
        if (transfer.metricsObservation.latestProviderState !is CaptureMetricsState.Unavailable) return
        core.recordInvalidMetricsIgnored("Runtime capture metrics are unavailable; keeping the last valid metrics.")
    }

    private fun throwIfProjectionStoppedBeforeCommit() {
        if (isProjectionStopped()) {
            throw startupException(
                kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                message = "Screen capture projection stopped before initial Active commit.",
                cause = null,
            )
        }
    }

    private fun isProjectionStopped(): Boolean =
        isProjectionStoppedLocked()

    private fun isProjectionStoppedLocked(): Boolean =
        isProjectionStoppedLocked(rawStopObserved = transfer.callbackAdapter.projectionStopObserved)

    private fun isProjectionStoppedLocked(rawStopObserved: Boolean): Boolean =
        !ownerStopTerminalCommitted &&
                (rawStopObserved || projectionStopRuntimeFence || transfer.projectionStopObserved() ||
                        runtimeFrameLoop.pendingSignalsSnapshot(
                            latestMetrics = transfer.metricsObservation.latestMetrics,
                            projectionStopObserved = false,
                        ).projectionStopObserved)

    private fun finishProjectionStopped() {
        val core = synchronized(lock) { sessionCore } ?: return
        core.finishStopped(
            reason = ScreenCaptureStopReason.CaptureEnded,
            problem = core.newProblem(
                kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
                message = "Screen capture projection stopped.",
                cause = null,
            ),
        )
        closeRuntimeResources(stopProjection = false)
    }

    private fun stopFromOwner() {
        var finishProjectionStopped = false
        var closeOwnerResources = false
        synchronized(lock) {
            if (isProjectionStoppedLocked()) {
                finishProjectionStopped = true
                return@synchronized
            }
        }
        if (finishProjectionStopped) {
            finishProjectionStopped()
            return
        }
        beforeOwnerStopTerminalCommitForTesting?.invoke()
        arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                if (isProjectionStoppedLocked(rawStopObserved)) {
                    finishProjectionStopped = true
                    return@synchronized
                }
                val core = sessionCore
                ownerStopTerminalCommitted = true
                clearRetainedPeriodicRefreshStateLocked()
                core?.finishStopped(reason = ScreenCaptureStopReason.OwnerStop, problem = null)
                closeOwnerResources = true
            }
        }
        if (finishProjectionStopped) {
            finishProjectionStopped()
            return
        }
        if (closeOwnerResources) {
            closeRuntimeResources(stopProjection = true)
        }
    }

    override fun close() {
        stopFromOwner()
    }

    private fun closeAfterStartupFailure(primary: Throwable) {
        runCatching { closeRuntimeResources(stopProjection = true) }
            .onFailure(primary::addSuppressed)
    }

    private fun reserveTerminalCleanupFence() {
        if (terminalCleanupFence.get() != null) return
        val fence = terminalCleanupFenceFactory()
        if (!terminalCleanupFence.compareAndSet(null, fence)) {
            fence.close()
        }
    }

    private fun releaseTerminalCleanupFence() {
        terminalCleanupFence.getAndSet(null)?.close()
    }

    private fun closeRuntimeResources(stopProjection: Boolean) {
        var resourcesToClose: ActiveRuntimePreparedRenderingPipelineResources? = null
        var retiredActiveResourcesToClose: List<ActiveRuntimePreparedRenderingPipelineResources> = emptyList()
        var retiredEncoderResourcesToClose: List<RetiredActiveRuntimeEncoderResources> = emptyList()
        var scratchToRelease: EncodedAttemptScratch? = null
        var closeVirtualDisplayNow = false
        var closeProjectionTargetOwnerNow = false
        var releaseTerminalCleanupFenceAfterScheduling = false
        var alreadyClosed = false
        var parameterPreparationTokenToInvalidate: PlanPreparationToken? = null
        synchronized(lock) {
            if (state == ActiveRuntimeOwnerState.Closed) {
                alreadyClosed = true
            } else {
                state = ActiveRuntimeOwnerState.Closed
                parameterPreparationTokenToInvalidate = activeRuntimeParameterPreparationToken
                activeRuntimeParameterPreparationToken = null
                pendingRuntimeParameterProductionResume = false
                clearRetainedPeriodicRefreshStateLocked()
                val runtimeWorkInFlight = isRuntimeWorkInFlightLocked()
                val resources = activeResources
                val deferRuntimeResourcesClose = runtimeWorkInFlight && resources != null
                scratchToRelease = if (runtimeWorkInFlight) {
                    releaseEncodedScratchOnRuntimeClose = true
                    null
                } else {
                    val scratch = encodedScratch
                    encodedScratch = null
                    pendingEncodedScratchTrim = false
                    scratch
                }
                resourcesToClose = if (deferRuntimeResourcesClose) {
                    // Stop/close is a lifecycle fence, not permission to close GL/readback/encoder
                    // resources still borrowed by a materialized production attempt.
                    deferredActiveResourcesClose = resources
                    deferredHeavyRuntimeClose = true
                    null
                } else {
                    resources
                }
                if (!runtimeWorkInFlight) {
                    retiredActiveResourcesToClose = deferredRetiredActiveResourcesClose.toList()
                    deferredRetiredActiveResourcesClose.clear()
                    retiredEncoderResourcesToClose = deferredRetiredEncoderResourcesClose.toList()
                    deferredRetiredEncoderResourcesClose.clear()
                }
                val closeHeavyRuntimeResourcesNow = !runtimeWorkInFlight
                closeVirtualDisplayNow = closeHeavyRuntimeResourcesNow && markVirtualDisplayOwnerCloseScheduledLocked()
                closeProjectionTargetOwnerNow = closeHeavyRuntimeResourcesNow && markProjectionTargetOwnerCloseScheduledLocked()
                releaseTerminalCleanupFenceAfterScheduling = !deferRuntimeResourcesClose
                activeResources = null
            }
        }
        if (alreadyClosed) {
            return
        }
        parameterPreparationTokenToInvalidate?.invalidate()
        runtimeScheduler.shutdown()
        encoderLane.shutdown()
        runtimeFrameLoop.close()
        transfer.callbackRouter.close()
        runCatching { transfer.callbackAdapter.close() }.onFailure(::reportCleanupFailure)
        runCatching { transfer.metricsObservation.close() }.onFailure(::reportCleanupFailure)
        scratchToRelease?.let(::releaseEncodedScratch)
        if (stopProjection) {
            transfer.stopProjectionIfRequired()
        }
        try {
            scheduleStartupCleanup(
                cleanupScheduler = transfer.cleanupScheduler,
                cleanupFailureSink = transfer.cleanupFailureSink,
            ) {
                val cleanupFailures = CleanupFailureCollector()
                resourcesToClose?.let { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                retiredActiveResourcesToClose.forEach { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                retiredEncoderResourcesToClose.forEach { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                if (closeVirtualDisplayNow) {
                    cleanupFailures.collect { transfer.virtualDisplayOwner.close() }
                }
                if (closeProjectionTargetOwnerNow) {
                    cleanupFailures.collect { transfer.projectionTargetOwner.close() }
                }
                cleanupFailures.throwIfAny()
            }
        } finally {
            if (releaseTerminalCleanupFenceAfterScheduling) {
                releaseTerminalCleanupFence()
            }
        }
    }

    private fun closeDeferredRuntimeResourcesIfReady() {
        val resourcesToClose: ActiveRuntimePreparedRenderingPipelineResources?
        val retiredActiveResourcesToClose: List<ActiveRuntimePreparedRenderingPipelineResources>
        val retiredEncoderResourcesToClose: List<RetiredActiveRuntimeEncoderResources>
        val scratchToRelease: EncodedAttemptScratch?
        val closeHeavyRuntimeResources: Boolean
        val closeVirtualDisplay: Boolean
        val closeProjectionTargetOwner: Boolean
        synchronized(lock) {
            if (isRuntimeWorkInFlightLocked()) return
            if (deferredActiveResourcesClose == null &&
                deferredRetiredActiveResourcesClose.isEmpty() &&
                deferredRetiredEncoderResourcesClose.isEmpty()
            ) return
            val resources = deferredActiveResourcesClose
            deferredActiveResourcesClose = null
            resourcesToClose = resources
            retiredActiveResourcesToClose = deferredRetiredActiveResourcesClose.toList()
            deferredRetiredActiveResourcesClose.clear()
            retiredEncoderResourcesToClose = deferredRetiredEncoderResourcesClose.toList()
            deferredRetiredEncoderResourcesClose.clear()
            scratchToRelease = if (releaseEncodedScratchOnRuntimeClose) {
                releaseEncodedScratchOnRuntimeClose = false
                val scratch = encodedScratch
                encodedScratch = null
                pendingEncodedScratchTrim = false
                scratch
            } else {
                null
            }
            closeHeavyRuntimeResources = deferredHeavyRuntimeClose
            deferredHeavyRuntimeClose = false
            closeVirtualDisplay = closeHeavyRuntimeResources && markVirtualDisplayOwnerCloseScheduledLocked()
            closeProjectionTargetOwner = closeHeavyRuntimeResources && markProjectionTargetOwnerCloseScheduledLocked()
        }
        scratchToRelease?.let(::releaseEncodedScratch)
        try {
            scheduleStartupCleanup(
                cleanupScheduler = transfer.cleanupScheduler,
                cleanupFailureSink = transfer.cleanupFailureSink,
            ) {
                val cleanupFailures = CleanupFailureCollector()
                resourcesToClose?.let { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                retiredActiveResourcesToClose.forEach { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                retiredEncoderResourcesToClose.forEach { resources ->
                    cleanupFailures.collect { resources.close() }
                }
                if (closeVirtualDisplay) {
                    cleanupFailures.collect { transfer.virtualDisplayOwner.close() }
                }
                if (closeProjectionTargetOwner) {
                    cleanupFailures.collect { transfer.projectionTargetOwner.close() }
                }
                cleanupFailures.throwIfAny()
            }
        } finally {
            releaseTerminalCleanupFence()
        }
    }

    private fun markVirtualDisplayOwnerCloseScheduledLocked(): Boolean {
        if (virtualDisplayOwnerCloseScheduled) return false
        virtualDisplayOwnerCloseScheduled = true
        return true
    }

    private fun markProjectionTargetOwnerCloseScheduledLocked(): Boolean {
        if (projectionTargetOwnerCloseScheduled) return false
        projectionTargetOwnerCloseScheduled = true
        return true
    }

    private fun releaseEncodedScratch(scratch: EncodedAttemptScratch) {
        runCatching { scratch.trimToSize() }
            .onFailure(::reportCleanupFailure)
    }

    private fun isRuntimeWorkInFlightLocked(): Boolean =
        materializedRuntimeWorkInFlight || readbackInFlight.get() || encoderInFlight.get()

    private fun finishRuntimeProductionWork() {
        var resumeProductionAdmission = false
        synchronized(lock) {
            materializedRuntimeWorkInFlight = false
            if (pendingRuntimeParameterProductionResume && !isRuntimeWorkInFlightLocked()) {
                pendingRuntimeParameterProductionResume = false
                resumeProductionAdmission = state == ActiveRuntimeOwnerState.Committed
            }
        }
        if (resumeProductionAdmission) {
            runtimeFrameLoop.resumeProductionAdmission()
        }
    }

    private fun admitFrameRatePolicy(production: RuntimeProductionState): FrameRatePolicyAdmission {
        return when (val frameRate = production.effectiveParameters.frameRate) {
            FrameRate.Auto,
            is FrameRate.PeriodicRefresh -> FrameRatePolicyAdmission.Admitted

            is FrameRate.MaxFps -> {
                val nowNanos = elapsedRealtimeNanos()
                val minimumIntervalNanos = NANOS_PER_SECOND / frameRate.fps
                val admitted = synchronized(lock) {
                    if (!isCurrentRuntimeProductionLocked(production)) return@synchronized true
                    val lastAdmitNanos = lastFrameRateAdmitNanos
                    if (lastAdmitNanos != null && nowNanos - lastAdmitNanos < minimumIntervalNanos) {
                        false
                    } else {
                        lastFrameRateAdmitNanos = nowNanos
                        true
                    }
                }
                if (!admitted) {
                    var projectionStopped = false
                    beforeFrameRatePolicyDropForTesting?.invoke()
                    arbitrateProjectionStopPublicOutcome { rawStopObserved ->
                        synchronized(lock) {
                            projectionStopped = isProjectionStoppedLocked(rawStopObserved)
                            if (!projectionStopped && isCurrentRuntimeProductionLocked(production)) {
                                production.core.recordCurrentUnmaterializedProductionFrameDrop(ProductionFrameDropKind.FrameRatePolicy)
                            }
                        }
                    }
                    return if (projectionStopped) {
                        FrameRatePolicyAdmission.ProjectionStopped
                    } else {
                        FrameRatePolicyAdmission.DroppedByPolicy
                    }
                }
                FrameRatePolicyAdmission.Admitted
            }
        }
    }

    private fun rememberPeriodicRefreshFrameIfCurrent(
        production: RuntimeProductionState,
        format: EncodedImageFormat,
        bytes: ByteArray,
        publicationElapsedRealtimeNanos: Long,
    ): Boolean =
        synchronized(lock) {
            if (!isCurrentRuntimeProductionLocked(production)) return@synchronized false
            latestPeriodicRefreshFrame = PeriodicRefreshEncodedFrame(
                generation = production.outputGeneration,
                format = format,
                bytes = bytes,
                publicationElapsedRealtimeNanos = publicationElapsedRealtimeNanos,
            )
            true
        }

    private fun rememberPeriodicRefreshFrameIfCurrent(
        core: ScreenCaptureSessionCore,
        frame: PeriodicRefreshEncodedFrame,
        publicationElapsedRealtimeNanos: Long,
    ): Boolean =
        synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed ||
                isProjectionStoppedLocked() ||
                activeOutputGeneration != frame.generation ||
                core.currentOutputGeneration() != frame.generation
            ) return@synchronized false
            latestPeriodicRefreshFrame = PeriodicRefreshEncodedFrame(
                generation = frame.generation,
                format = frame.format,
                bytes = frame.bytes,
                publicationElapsedRealtimeNanos = publicationElapsedRealtimeNanos,
            )
            true
        }

    private fun recordPeriodicRefreshNoSourceWake() {
        synchronized(lock) {
            periodicRefreshNoSourceWakeCount = Math.addExact(periodicRefreshNoSourceWakeCount, 1L)
        }
    }

    private fun clearRetainedPeriodicRefreshState() {
        synchronized(lock) {
            clearRetainedPeriodicRefreshStateLocked()
        }
    }

    private fun clearRetainedPeriodicRefreshStateLocked() {
        latestPeriodicRefreshFrame = null
        periodicRefreshFuture?.cancel(false)
        periodicRefreshFuture = null
        periodicRefreshScheduled.set(false)
        periodicRefreshScheduleToken = Math.addExact(periodicRefreshScheduleToken, 1L)
    }

    private fun publishPeriodicRefreshFrame(): RuntimeFrameProductionTickResult {
        val core = synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed) return RuntimeFrameProductionTickResult.NoCommittedSession
            sessionCore ?: return RuntimeFrameProductionTickResult.NoCommittedSession
        }
        if (isProjectionStopped()) {
            finishProjectionStopped()
            return RuntimeFrameProductionTickResult.StaleDrop
        }
        val frame = synchronized(lock) { latestPeriodicRefreshFrame }
            ?: return RuntimeFrameProductionTickResult.NoFrameSignal
        if (frame.generation != core.currentOutputGeneration()) {
            return RuntimeFrameProductionTickResult.StaleDrop
        }
        val nowNanos = elapsedRealtimeNanos()
        val delayMillis = periodicRefreshDelayMillis(frame.generation) ?: return RuntimeFrameProductionTickResult.NoFrameSignal
        val minimumIntervalNanos = delayMillis * NANOS_PER_MILLISECOND
        val elapsedSinceLastPublication = nowNanos - frame.publicationElapsedRealtimeNanos
        if (elapsedSinceLastPublication < minimumIntervalNanos) {
            val remainingMillis = ((minimumIntervalNanos - elapsedSinceLastPublication) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
            scheduleNextPeriodicRefreshIfNeeded(frame.generation, delayMillis = remainingMillis)
            return RuntimeFrameProductionTickResult.NoFrameSignal
        }
        var finishProjectionStopped = false
        beforeFinalPeriodicRefreshPublicationForTesting?.invoke()
        val published = arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                if (state != ActiveRuntimeOwnerState.Committed || isProjectionStoppedLocked(rawStopObserved)) {
                    finishProjectionStopped = isProjectionStoppedLocked(rawStopObserved)
                    false
                } else {
                    core.publishEncodedFrame(
                        generation = frame.generation,
                        format = frame.format,
                        bytes = frame.bytes,
                        timestampElapsedRealtimeNanos = nowNanos,
                        countEncodedFrame = false,
                        copyBytes = false,
                    )
                }
            }
        }
        if (finishProjectionStopped) {
            clearRetainedPeriodicRefreshState()
            finishProjectionStopped()
        }
        if (!published) return RuntimeFrameProductionTickResult.StaleDrop
        beforePeriodicRefreshRetentionForTesting?.invoke()
        val retained = rememberPeriodicRefreshFrameIfCurrent(
            core = core,
            frame = frame,
            publicationElapsedRealtimeNanos = nowNanos,
        )
        if (retained) {
            scheduleNextPeriodicRefreshIfNeeded(frame.generation)
        }
        return RuntimeFrameProductionTickResult.Published
    }

    private fun scheduleNextPeriodicRefreshIfNeeded(expectedOutputGeneration: Long, delayMillis: Long? = null) {
        val effectiveDelayMillis = delayMillis ?: periodicRefreshDelayMillis(expectedOutputGeneration) ?: return
        val scheduleToken = synchronized(lock) {
            if (!isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration)) return
            if (!periodicRefreshScheduled.compareAndSet(false, true)) return
            periodicRefreshScheduleToken = Math.addExact(periodicRefreshScheduleToken, 1L)
            periodicRefreshScheduleToken
        }
        try {
            val future = runtimeScheduler.schedule(
                {
                    var wakeFenceToken: Long? = null
                    synchronized(lock) {
                        if (periodicRefreshScheduleToken == scheduleToken && periodicRefreshScheduled.get()) {
                            periodicRefreshFuture = null
                            periodicRefreshScheduleToken = Math.addExact(periodicRefreshScheduleToken, 1L)
                            periodicRefreshScheduled.set(false)
                            if (isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration)) {
                                wakeFenceToken = periodicRefreshScheduleToken
                            }
                        }
                    }
                    wakeFenceToken?.let { expectedWakeFenceToken ->
                        beforePeriodicRefreshWakeEnqueueForTesting?.invoke()
                        synchronized(lock) {
                            if (periodicRefreshScheduleToken == expectedWakeFenceToken &&
                                isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration)
                            ) {
                                runtimeFrameLoop.recordPeriodicRefreshWake()
                            }
                        }
                    }
                    afterPeriodicRefreshWakeEnqueueAttemptForTesting?.invoke()
                },
                effectiveDelayMillis,
                TimeUnit.MILLISECONDS,
            )
            synchronized(lock) {
                if (periodicRefreshScheduleToken == scheduleToken &&
                    isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration) &&
                    periodicRefreshScheduled.get()
                ) {
                    periodicRefreshFuture = future
                } else {
                    future.cancel(false)
                }
            }
        } catch (cause: RejectedExecutionException) {
            synchronized(lock) {
                if (periodicRefreshScheduleToken == scheduleToken) {
                    periodicRefreshFuture = null
                    periodicRefreshScheduleToken = Math.addExact(periodicRefreshScheduleToken, 1L)
                    periodicRefreshScheduled.set(false)
                }
            }
            if (shouldAcceptRuntimeTurn()) reportCleanupFailure(cause)
        }
    }

    private fun periodicRefreshDelayMillis(expectedOutputGeneration: Long): Long? =
        synchronized(lock) {
            if (!isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration)) return@synchronized null
            val frameRate = activeEffectiveParameters?.frameRate as? FrameRate.PeriodicRefresh ?: return@synchronized null
            periodicRefreshDelayOverrideMillis ?: frameRate.intervalMillis
        }

    private fun isCurrentPeriodicRefreshGenerationLocked(expectedOutputGeneration: Long): Boolean =
        state == ActiveRuntimeOwnerState.Committed &&
                !isProjectionStoppedLocked() &&
                activeOutputGeneration == expectedOutputGeneration &&
                activeEffectiveParameters?.frameRate is FrameRate.PeriodicRefresh

    private fun scheduleRuntimeTurn(delayMillis: Long = 0L) {
        if (!shouldAcceptRuntimeTurn()) return
        if (!runtimeTurnScheduled.compareAndSet(false, true)) return
        try {
            runtimeScheduler.schedule(::runScheduledRuntimeTurn, delayMillis, TimeUnit.MILLISECONDS)
        } catch (cause: RejectedExecutionException) {
            runtimeTurnScheduled.set(false)
            if (shouldAcceptRuntimeTurn()) reportCleanupFailure(cause)
        }
    }

    private fun runScheduledRuntimeTurn() {
        try {
            runBlocking {
                drainRuntimeProductionTick()
            }
        } catch (cause: Throwable) {
            reportCleanupFailure(cause)
        } finally {
            runtimeTurnScheduled.set(false)
            if (hasPendingRuntimeWorkForScheduling()) {
                scheduleRuntimeTurn()
            }
        }
    }

    private fun shouldAcceptRuntimeTurn(): Boolean =
        synchronized(lock) { state == ActiveRuntimeOwnerState.Committed && returnedSessionRuntimeSignalsArmed }

    private fun hasPendingRuntimeWorkForScheduling(): Boolean {
        synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed) return false
            if (queuedPostCommitRuntimeSignalDrain != null) return returnedSessionRuntimeSignalsArmed
        }
        return runtimeFrameLoop.hasPendingRuntimeWork(
            latestMetrics = transfer.metricsObservation.latestMetrics,
            projectionStopObserved = isProjectionStopped(),
        )
    }

    private fun isRuntimeTerminalOrClosed(): Boolean =
        synchronized(lock) { state != ActiveRuntimeOwnerState.Committed }

    private fun currentEffectiveParameters(core: ScreenCaptureSessionCore): ScreenCaptureEffectiveParameters? {
        val running = core.state.value as? ScreenCaptureSessionState.Running ?: return null
        return when (val output = running.output) {
            is ScreenCaptureOutputState.Active -> output.effectiveParameters
            is ScreenCaptureOutputState.Suspended -> output.previousEffectiveParameters
        }
    }

    private fun reportCleanupFailure(failure: Throwable) {
        runCatching { transfer.cleanupFailureSink.onCleanupFailure(failure) }
    }

    private fun startupException(
        kind: ScreenCaptureProblemKind,
        message: String,
        cause: Throwable?,
    ): ScreenCaptureStartException =
        ScreenCaptureStartException(
            requiresFreshProjection = true,
            problem = ScreenCaptureProblem(sequence = 0L, kind = kind, message = message, cause = cause),
        )

    override fun onProjectionStopped() {
        synchronized(lock) {
            if (ownerStopTerminalCommitted) return
            projectionStopRuntimeFence = true
        }
        runtimeFrameLoop.recordProjectionStopped()
    }

    override fun onRouterSelectedProjectionStopped() {
        synchronized(lock) {
            if (ownerStopTerminalCommitted) return
            projectionStopRuntimeFence = true
        }
        runtimeFrameLoop.recordProjectionStopped()
    }

    override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
        runtimeFrameLoop.recordCapturedContentResize(resize)
    }

    override fun onCapturedContentResized(width: Int, height: Int) {
        onCapturedContentResized(ProjectionCapturedContentResize(id = 0L, width = width, height = height))
    }

    override fun onRouterSelectedCapturedContentResized(resize: ProjectionCapturedContentResize) {
        runtimeFrameLoop.recordCapturedContentResize(resize)
    }

    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        recordLatestCapturedContentVisibleForInitialCommit(isVisible)
        runtimeFrameLoop.recordCapturedContentVisibility(isVisible)
    }

    override fun onRouterSelectedCapturedContentVisibilityChanged(isVisible: Boolean) {
        recordLatestCapturedContentVisibleForInitialCommit(isVisible)
        runtimeFrameLoop.recordCapturedContentVisibility(isVisible)
    }

    private fun recordLatestCapturedContentVisibleForInitialCommit(isVisible: Boolean) {
        synchronized(lock) {
            if (state == ActiveRuntimeOwnerState.Prepared) {
                latestCapturedContentVisibleForInitialCommit = isVisible
            }
        }
    }

    private class ActiveRuntimeSession(
        private val owner: ActiveRuntimeOwner,
        private val core: ScreenCaptureSessionCore,
    ) : ScreenCaptureSession {
        override val state: StateFlow<ScreenCaptureSessionState> = core.state
        override val stats: StateFlow<ScreenCaptureStats> = core.stats
        override val events: SharedFlow<ScreenCaptureEvent> = core.events

        override suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult =
            core.setParameters(parameters)

        override fun trimMemory(level: Int) {
            core.trimMemory(level)
        }

        override fun stop() {
            owner.stopFromOwner()
        }

        override fun close() {
            owner.stopFromOwner()
        }

        override fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription =
            core.onFrame(callback)
    }
}

internal class InitialActivationCommitBoundary internal constructor(
    internal val afterLastCancellableCheckpoint: () -> Unit = {},
)

internal class ActiveRuntimeTransfer internal constructor(
    val config: ScreenCaptureConfig,
    val callbackRouter: StartupProjectionCallbackRouter,
    val callbackAdapter: ProjectionCallbackRegistration,
    val projectionTargetOwner: ProjectionTargetOwnerHandle,
    val virtualDisplayOwner: ProjectionVirtualDisplayOwner,
    val currentProjectionTarget: ProjectionTargetHandle,
    val startupGeometry: CaptureGeometry,
    val metricsObservation: CaptureMetricsObservation,
    val cleanupScheduler: StartupCleanupScheduler,
    val cleanupFailureSink: StartupCleanupFailureSink,
    val projectionStopObserved: () -> Boolean,
    val stopProjectionIfRequired: () -> Unit,
    val encoderPrepare: ImageEncoderPrepareOperation,
    val outputPlanPreparer: OutputPlanPreparer?,
    val planRenderingAccess: PlanRenderingAccess,
    val preparedRenderingPipelineResources: ActiveRuntimePreparedRenderingPipelineResources,
    val initialOutputPlan: ScreenCaptureOutputPlan,
    val initialParameters: ScreenCaptureParameters,
    val pendingSignals: StartupRuntimePendingSignals,
    val expectedCurrentListener: MediaProjectionCallbackAdapter.Listener,
)

private enum class ActiveRuntimeOwnerState {
    Prepared,
    Committed,
    Closed,
}

private class PostCommitRuntimeSignalDrain(
    val pendingSignals: StartupRuntimePendingSignals,
    val previousEffectiveParameters: ScreenCaptureEffectiveParameters,
    val core: ScreenCaptureSessionCore,
)

internal enum class RuntimeFrameProductionTickResult {
    NoCommittedSession,
    NoFrameSignal,
    NotMaterialized,
    StaleProjectionTargetSignal,
    ReadbackBusyDrop,
    EncoderBusyDrop,
    EncodeFailedDrop,
    EncodeThrewDrop,
    EncodedSizeLimitDrop,
    StaleDrop,
    GlFailed,
    FrameRatePolicyDrop,
    PeriodicRefreshNoSourceFrame,
    EncodeTimedOutDrop,
    Published,
}

private class RuntimeProductionState(
    val core: ScreenCaptureSessionCore,
    val resources: ActiveRuntimePreparedRenderingPipelineResources,
    val scratch: EncodedAttemptScratch,
    val outputGeneration: Long,
    val encoderResources: PreparedImageEncoderResources,
    val effectiveParameters: ScreenCaptureEffectiveParameters,
    val captureGeometry: CaptureGeometry,
    val encodeHealthKey: RuntimeEncodeHealthKey,
)

private class RuntimeEncodeHealthKey(
    val outputGeneration: Long,
    private val encoderResources: PreparedImageEncoderResources,
) {
    private val encoderInfo: ImageEncoderInfo = encoderResources.info

    override fun equals(other: Any?): Boolean =
        other is RuntimeEncodeHealthKey &&
                outputGeneration == other.outputGeneration &&
                encoderResources === other.encoderResources &&
                encoderInfo == other.encoderInfo

    override fun hashCode(): Int =
        31 * (31 * outputGeneration.hashCode() + encoderInfo.hashCode()) + System.identityHashCode(encoderResources)
}

private class RuntimeProductionDropCompletion(
    val projectionStopped: Boolean,
    val resolvedKind: ProductionFrameDropKind?,
) {
    val isStaleGeneration: Boolean =
        projectionStopped || resolvedKind == ProductionFrameDropKind.StaleGeneration
}

private class RuntimeProviderOnlyPreparationStart(
    val core: ScreenCaptureSessionCore,
    val planPreparationToken: PlanPreparationToken,
    val baseOutputGeneration: Long,
    val projectionTargetGeneration: Long,
    val candidatePlan: ScreenCaptureOutputPlan,
)

private class RuntimeFullPreparationStart(
    val core: ScreenCaptureSessionCore,
    val outputPlanPreparer: OutputPlanPreparer,
    val planPreparationToken: PlanPreparationToken,
    val baseOutputGeneration: Long,
    val projectionTarget: ProjectionTargetSnapshot,
    val candidatePlan: ScreenCaptureOutputPlan,
)

private sealed class RuntimeFullPreparation {
    data object NotNeeded : RuntimeFullPreparation()

    class Prepared(
        val candidate: RuntimeFullOutputPlanCandidate,
    ) : RuntimeFullPreparation()

    class Rejected(
        val result: ScreenCaptureParameterUpdateResult.Rejected,
    ) : RuntimeFullPreparation()
}

private class RuntimeFullOutputPlanCandidate(
    val planPreparationToken: PlanPreparationToken,
    val baseOutputGeneration: Long,
    val projectionTargetGeneration: Long,
    val candidatePlan: ScreenCaptureOutputPlan,
    private val resourcesCandidate: ActiveRuntimePreparedRenderingPipelineResourcesCandidate,
) : AutoCloseable {
    private var committed = false

    val encoderInfo: ImageEncoderInfo
        get() = resourcesCandidate.encoderInfo

    fun moveToActiveRuntimeOwner(): ActiveRuntimePreparedRenderingPipelineResources {
        val resources = resourcesCandidate.moveToActiveRuntimeOwner()
        committed = true
        return resources
    }

    override fun close() {
        if (committed) return
        planPreparationToken.invalidate()
        resourcesCandidate.close()
    }
}

private sealed class RuntimeProviderOnlyPreparation {
    data object NotNeeded : RuntimeProviderOnlyPreparation()

    class Prepared(
        val candidate: RuntimeProviderOnlyEncoderCandidate,
    ) : RuntimeProviderOnlyPreparation()

    class Rejected(
        val result: ScreenCaptureParameterUpdateResult.Rejected,
    ) : RuntimeProviderOnlyPreparation()
}

private class RuntimeProviderOnlyEncoderCandidate(
    val planPreparationToken: PlanPreparationToken,
    val baseOutputGeneration: Long,
    val projectionTargetGeneration: Long,
    val candidatePlan: ScreenCaptureOutputPlan,
    val encoderResourcesCandidate: ActiveRuntimeEncoderResourcesCandidate,
) : AutoCloseable {
    private var committed = false

    val encoderInfo: ImageEncoderInfo
        get() = encoderResourcesCandidate.encoderInfo

    fun markCommitted() {
        committed = true
    }

    override fun close() {
        if (committed) return
        planPreparationToken.invalidate()
        encoderResourcesCandidate.close()
    }
}

private class RuntimeParameterCommitBridgeOutcome(
    val result: ScreenCaptureParameterUpdateResult,
    val productionAdmissionStillPaused: Boolean,
    val resumeProductionAdmissionAfterCommit: Boolean,
)

private enum class FrameRatePolicyAdmission {
    Admitted,
    DroppedByPolicy,
    ProjectionStopped,
}

private class PeriodicRefreshEncodedFrame(
    val generation: Long,
    val format: EncodedImageFormat,
    val bytes: ByteArray,
    val publicationElapsedRealtimeNanos: Long,
)

private class TimedRuntimeRenderReadbackResult(
    val result: RuntimeEs2RenderReadbackResult,
    val durationNanos: Long,
)

private class RuntimeEncoderTimeoutException(message: String) : RuntimeException(message)

private object NoopCloseable : AutoCloseable {
    override fun close() = Unit
}

private class LeasedRgbaImageEncoderInput(
    lease: RgbaReadbackLease,
) : ImageEncoderInput {
    override val width: Int = lease.width
    override val height: Int = lease.height
    override val rowStrideBytes: Int = lease.rowStrideBytes
    override val buffer: ByteBuffer = lease.readOnlyBufferView()
    override val format = lease.inputFormat
}

private fun StartupRuntimePendingSignals.mergeForCommit(
    later: StartupRuntimePendingSignals,
): StartupRuntimePendingSignals {
    val projectionStopped = projectionStopObserved || later.projectionStopObserved
    return StartupRuntimePendingSignals(
        projectionStopObserved = projectionStopped,
        pendingCapturedContentResize = if (projectionStopped) null else later.pendingCapturedContentResize ?: pendingCapturedContentResize,
        latestCaptureMetrics = later.latestCaptureMetrics,
        pendingCaptureGeometry = if (projectionStopped) null else later.pendingCaptureGeometry ?: pendingCaptureGeometry,
        latestCapturedContentVisible = later.latestCapturedContentVisible ?: latestCapturedContentVisible,
        metricsObservationChanged = if (projectionStopped) false else metricsObservationChanged || later.metricsObservationChanged,
    )
}

private const val RUNTIME_GL_OPERATION_TIMEOUT_MS: Long = 5_000L
private const val RUNTIME_ENCODER_OPERATION_TIMEOUT_MS: Long = 5_000L
private const val RUNTIME_ENCODE_HARD_FAILURE_THRESHOLD: Int = 3
private const val NANOS_PER_SECOND: Long = 1_000_000_000L
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
private const val RUNTIME_MATRIX_ELEMENT_COUNT: Int = 16
