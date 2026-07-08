package dev.dmkr.screencaptureengine.internal.runtime

import android.os.SystemClock
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.FrameSubscription
import dev.dmkr.screencaptureengine.ImageEncodeResult
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
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.session.EncodedAttemptScratch
import dev.dmkr.screencaptureengine.internal.session.ProductionFrameDropKind
import dev.dmkr.screencaptureengine.internal.session.ScreenCaptureSessionCore
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
import kotlin.coroutines.cancellation.CancellationException

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
    private var materializedRuntimeWorkInFlight = false
    private val oesMatrixScratch = FloatArray(RUNTIME_MATRIX_ELEMENT_COUNT)
    private val composedTextureMatrixScratch = FloatArray(RUNTIME_MATRIX_ELEMENT_COUNT)
    private val startupPendingSignals = transfer.pendingSignals
    private var state = ActiveRuntimeOwnerState.Prepared
    private var activeResources: ActiveRuntimePreparedRenderingPipelineResources? = transfer.preparedRenderingPipelineResources
    private var deferredActiveResourcesClose: ActiveRuntimePreparedRenderingPipelineResources? = null
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
    private var latestCapturedContentVisibleForInitialCommit: Boolean? = transfer.pendingSignals.latestCapturedContentVisible
    private var lastFrameRateAdmitNanos: Long? = null
    private var hasSuccessfulRuntimeSourceFrame = false
    private var latestPeriodicRefreshFrame: PeriodicRefreshEncodedFrame? = null
    private var periodicRefreshFuture: ScheduledFuture<*>? = null
    private var periodicRefreshNoSourceWakeCount = 0L
    private var glOperationTimeoutMillis = RUNTIME_GL_OPERATION_TIMEOUT_MS
    private var encoderOperationTimeoutMillis = RUNTIME_ENCODER_OPERATION_TIMEOUT_MS
    private var periodicRefreshDelayOverrideMillis: Long? = null
    private var beforeProductionAttemptMaterializationForTesting: (() -> Unit)? = null
    private var afterProductionAttemptMaterializedForTesting: (() -> Unit)? = null
    private var beforeOwnerStopTerminalCommitForTesting: (() -> Unit)? = null
    private var beforeFinalEncodedPublicationForTesting: (() -> Unit)? = null
    private var beforeFinalPeriodicRefreshPublicationForTesting: (() -> Unit)? = null
    private var beforeFrameRatePolicyDropForTesting: (() -> Unit)? = null
    private var beforeRuntimeFailureTerminalCommitForTesting: (() -> Unit)? = null
    private var beforeEncodeNonSuccessDropForTesting: (() -> Unit)? = null
    private var ownerStopTerminalCommitted = false
    private var lateRenderReadbackLeaseReleaseCountForTesting = 0
    private var readbackBorrowedByEncoderObserverForTesting: ((Boolean) -> Unit)? = null

    @Volatile
    private var projectionStopRuntimeFence = false

    init {
        runtimeScheduler.removeOnCancelPolicy = true
        transfer.callbackRouter.replaceRuntimeListener(expectedCurrent = transfer.expectedCurrentListener, replacement = this)
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

    internal fun setBeforeFrameRatePolicyDropForTesting(callback: (() -> Unit)?) {
        beforeFrameRatePolicyDropForTesting = callback
    }

    internal fun setBeforeRuntimeFailureTerminalCommitForTesting(callback: (() -> Unit)?) {
        beforeRuntimeFailureTerminalCommitForTesting = callback
    }

    internal fun setBeforeEncodeNonSuccessDropForTesting(callback: (() -> Unit)?) {
        beforeEncodeNonSuccessDropForTesting = callback
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

    internal suspend fun commitInitialActiveSession(): ScreenCaptureSession =
        try {
            throwIfProjectionStoppedBeforeCommit()
            currentCoroutineContext().ensureActive()
            installRuntimeFrameLoopBeforeCommit()
            currentCoroutineContext().ensureActive()
            commitBoundary.afterLastCancellableCheckpoint()
            commitInitialActivePlanCommitted()
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
    private fun commitInitialActivePlanCommitted(): ScreenCaptureSession {
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
            parameterUpdater = { _, _ ->
                ScreenCaptureParameterUpdateResult.Rejected(
                    problem = core.newProblem(
                        kind = ScreenCaptureProblemKind.OutputPlanInvalid,
                        message = "Runtime parameter updates are not available for this session.",
                        cause = null,
                    ),
                )
            },
            trimMemoryHandler = { trimEncodedScratch(scratch) },
            terminalCommitHandler = { runtimeFrameLoop.fenceTerminal() },
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
        val session = ActiveRuntimeSession(owner = this, core = core)
        synchronized(lock) {
            check(state == ActiveRuntimeOwnerState.Prepared) { "ActiveRuntimeOwner is $state." }
            sessionCore = core
            publicSession = session
            encodedScratch = scratch
            state = ActiveRuntimeOwnerState.Committed
        }
        schedulePostCommitRuntimeSignalDrain(
            pendingSignals = pendingSignals,
            previousEffectiveParameters = effectiveParameters,
            core = core,
        )
        scheduleNextPeriodicRefreshIfNeeded(core)
        if (hasPendingRuntimeWorkForScheduling()) {
            scheduleRuntimeTurn(delayMillis = POST_COMMIT_RUNTIME_SIGNAL_DRAIN_DELAY_MS)
        }
        return session
    }

    internal suspend fun drainRuntimeProductionTick(): RuntimeFrameProductionTickResult {
        drainQueuedPostCommitRuntimeSignals()
        drainLatestRuntimeSignals()
        val periodicRefreshWake = runtimeFrameLoop.consumePeriodicRefreshWake()
        val signal = runtimeFrameLoop.admitLatestFrameSignal() ?: run {
            return when {
                periodicRefreshWake && !hasSuccessfulRuntimeSourceFrame -> {
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
            materializedRuntimeWorkInFlight = true
            RuntimeProductionState(core = core, resources = resources, scratch = scratch)
        }
        if (signal.generation != transfer.currentProjectionTarget.generation) {
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            return RuntimeFrameProductionTickResult.StaleProjectionTargetSignal
        }
        when (admitFrameRatePolicy(production.core)) {
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
        val outputGeneration = production.core.currentOutputGeneration()
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
            val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.EncoderBusy)
            finishRuntimeProductionWork()
            if (projectionStopped) finishProjectionStopped()
            return if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncoderBusyDrop
        }
        if (!readbackInFlight.compareAndSet(false, true)) {
            encoderInFlight.set(false)
            finishRuntimeProductionWork()
            closeDeferredRuntimeResourcesIfReady()
            val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.ReadbackBusy)
            if (projectionStopped) finishProjectionStopped()
            return if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.ReadbackBusyDrop
        }
        val renderResult = try {
            renderReadbackWithWatchdog(production.resources)
        } catch (timeout: TimeoutCancellationException) {
            val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
            quarantineNonGlResourcesAfterGlTimeout(production.resources)
            if (projectionStopped) {
                finishProjectionStopped()
            } else {
                failRuntimeGlProduction(production.core, "Runtime GL frame production timed out.", timeout)
            }
            return RuntimeFrameProductionTickResult.GlFailed
        } catch (cause: Throwable) {
            readbackInFlight.set(false)
            encoderInFlight.set(false)
            finishRuntimeProductionWork()
            val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
            if (projectionStopped) {
                finishProjectionStopped()
            } else {
                failRuntimeGlProduction(production.core, "Runtime GL frame production failed.", cause)
            }
            closeDeferredRuntimeResourcesIfReady()
            return RuntimeFrameProductionTickResult.GlFailed
        }
        readbackInFlight.set(false)
        return when (renderResult.result) {
            RuntimeEs2RenderReadbackResult.ReadbackBusy -> {
                encoderInFlight.set(false)
                finishRuntimeProductionWork()
                closeDeferredRuntimeResourcesIfReady()
                val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.ReadbackBusy)
                if (projectionStopped) finishProjectionStopped()
                if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.ReadbackBusyDrop
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
                hasSuccessfulRuntimeSourceFrame = true
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
        withTimeout(glOperationTimeoutMillis) {
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
        attempt: dev.dmkr.screencaptureengine.internal.session.ProductionAttemptToken,
        outputGeneration: Long,
        readback: RuntimeEs2RenderReadbackResult.Success,
    ): RuntimeFrameProductionTickResult {
        val encoderResources = production.resources.encoderResourcesForRuntime
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
                    production = production,
                    input = input,
                    sink = sink,
                    lease = readback.lease,
                )
            } catch (timeout: RuntimeEncoderTimeoutException) {
                quarantinedEncoderWork = true
                val projectionStopped = completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)
                quarantineNonEncoderResourcesAfterEncodeTimeout()
                if (projectionStopped) {
                    finishProjectionStopped()
                } else {
                    failRuntimeEncoderProduction(production.core, "Runtime image encoding timed out.", timeout)
                }
                return RuntimeFrameProductionTickResult.EncodeTimedOutDrop
            } catch (cause: Throwable) {
                scratch.finishDiscard()
                if (completeProductionDropWithProjectionFence(attempt, ProductionFrameDropKind.TransientFailure)) {
                    finishProjectionStopped()
                    return RuntimeFrameProductionTickResult.StaleDrop
                } else {
                    return RuntimeFrameProductionTickResult.EncodeThrewDrop
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
                                rememberPeriodicRefreshFrame(
                                    generation = outputGeneration,
                                    format = encoderResources.info.outputFormat,
                                    bytes = bytes,
                                    publicationElapsedRealtimeNanos = publicationNanos,
                                )
                                scheduleNextPeriodicRefreshIfNeeded(production.core)
                                RuntimeFrameProductionTickResult.Published
                            } else {
                                RuntimeFrameProductionTickResult.StaleDrop
                            }
                        }

                        rejected -> {
                            val projectionStopped = completeEncodeNonSuccessDropWithProjectionFence(
                                attempt,
                                ProductionFrameDropKind.EncodedSizeLimit,
                            )
                            if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodedSizeLimitDrop
                        }

                        else -> {
                            val projectionStopped = completeEncodeNonSuccessDropWithProjectionFence(
                                attempt,
                                ProductionFrameDropKind.TransientFailure,
                            )
                            if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodeFailedDrop
                        }
                    }
                }

                is ImageEncodeResult.Failed -> {
                    val rejected = scratch.wasRejected
                    scratch.finishDiscard()
                    if (rejected) {
                        val projectionStopped = completeEncodeNonSuccessDropWithProjectionFence(
                            attempt,
                            ProductionFrameDropKind.EncodedSizeLimit,
                        )
                        if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodedSizeLimitDrop
                    } else {
                        val projectionStopped = completeEncodeNonSuccessDropWithProjectionFence(
                            attempt,
                            ProductionFrameDropKind.TransientFailure,
                        )
                        if (projectionStopped) RuntimeFrameProductionTickResult.StaleDrop else RuntimeFrameProductionTickResult.EncodeFailedDrop
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
        production: RuntimeProductionState,
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
                    production.resources.encoderResourcesForRuntime.encoder.encode(input, sink)
                } finally {
                    if (timedOut.get() && cleanupClaimed.compareAndSet(false, true)) {
                        cleanupTimedOutEncoderWork(production = production, lease = lease)
                    }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            throw IllegalStateException("Runtime encoder lane rejected encode work.", rejected)
        }
        return try {
            encodeFuture.get(encoderOperationTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            timedOut.set(true)
            val cancelled = encodeFuture.cancel(true)
            if (cancelled && !started.get() && cleanupClaimed.compareAndSet(false, true)) {
                cleanupTimedOutEncoderWork(production = production, lease = lease)
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

    private fun cleanupTimedOutEncoderWork(production: RuntimeProductionState, lease: RgbaReadbackLease) {
        runCatching { production.scratch.finishDiscard() }.onFailure(::reportCleanupFailure)
        runCatching { lease.close() }.onFailure(::reportCleanupFailure)
        readbackBorrowedByEncoderObserverForTesting?.invoke(false)
        encoderInFlight.set(false)
        finishRuntimeProductionWork()
        applyPendingEncodedScratchTrimIfReady()
        closeDeferredRuntimeResourcesIfReady()
    }

    private fun completeEncodedSuccessWithProjectionFence(
        attempt: dev.dmkr.screencaptureengine.internal.session.ProductionAttemptToken,
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
        attempt: dev.dmkr.screencaptureengine.internal.session.ProductionAttemptToken,
        kind: ProductionFrameDropKind,
    ): Boolean {
        var projectionStopped = false
        arbitrateProjectionStopPublicOutcome { rawStopObserved ->
            synchronized(lock) {
                projectionStopped = isProjectionStoppedLocked(rawStopObserved)
                attempt.completeDrop(if (projectionStopped) ProductionFrameDropKind.StaleGeneration else kind)
            }
        }
        return projectionStopped
    }

    private fun completeEncodeNonSuccessDropWithProjectionFence(
        attempt: dev.dmkr.screencaptureengine.internal.session.ProductionAttemptToken,
        kind: ProductionFrameDropKind,
    ): Boolean {
        beforeEncodeNonSuccessDropForTesting?.invoke()
        val projectionStopped = completeProductionDropWithProjectionFence(attempt, kind)
        if (projectionStopped) finishProjectionStopped()
        return projectionStopped
    }

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

    private fun failRuntimeEncoderProduction(core: ScreenCaptureSessionCore, message: String, cause: Throwable) {
        val projectionStopped = finishRuntimeFailureWithProjectionFence(
            core = core,
            kind = ScreenCaptureProblemKind.EncodeRepeatedFailure,
            message = message,
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
        if (!pendingSignals.projectionStopObserved && pendingSignals.pendingCaptureGeometry == null) return
        synchronized(lock) {
            if (state == ActiveRuntimeOwnerState.Committed) {
                queuedPostCommitRuntimeSignalDrain = PostCommitRuntimeSignalDrain(
                    pendingSignals = pendingSignals,
                    previousEffectiveParameters = previousEffectiveParameters,
                    core = core,
                )
            }
        }
        scheduleRuntimeTurn(delayMillis = POST_COMMIT_RUNTIME_SIGNAL_DRAIN_DELAY_MS)
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

    private fun closeRuntimeResources(stopProjection: Boolean) {
        val resourcesToClose: ActiveRuntimePreparedRenderingPipelineResources?
        val scratchToRelease: EncodedAttemptScratch?
        val closeHeavyRuntimeResourcesNow: Boolean
        val closeVirtualDisplayNow: Boolean
        val closeProjectionTargetOwnerNow: Boolean
        synchronized(lock) {
            if (state == ActiveRuntimeOwnerState.Closed) return
            state = ActiveRuntimeOwnerState.Closed
            clearRetainedPeriodicRefreshStateLocked()
            val runtimeWorkInFlight = isRuntimeWorkInFlightLocked()
            scratchToRelease = if (runtimeWorkInFlight) {
                releaseEncodedScratchOnRuntimeClose = true
                null
            } else {
                val scratch = encodedScratch
                encodedScratch = null
                pendingEncodedScratchTrim = false
                scratch
            }
            resourcesToClose = if (runtimeWorkInFlight) {
                // Stop/close is a lifecycle fence, not permission to close GL/readback/encoder
                // resources still borrowed by a materialized production attempt.
                deferredActiveResourcesClose = activeResources
                deferredHeavyRuntimeClose = true
                null
            } else {
                activeResources
            }
            closeHeavyRuntimeResourcesNow = !runtimeWorkInFlight
            closeVirtualDisplayNow = closeHeavyRuntimeResourcesNow && markVirtualDisplayOwnerCloseScheduledLocked()
            closeProjectionTargetOwnerNow = closeHeavyRuntimeResourcesNow && markProjectionTargetOwnerCloseScheduledLocked()
            activeResources = null
        }
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
        scheduleStartupCleanup(
            cleanupScheduler = transfer.cleanupScheduler,
            cleanupFailureSink = transfer.cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            resourcesToClose?.let { resources ->
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
    }

    private fun closeDeferredRuntimeResourcesIfReady() {
        val resourcesToClose: ActiveRuntimePreparedRenderingPipelineResources?
        val scratchToRelease: EncodedAttemptScratch?
        val closeHeavyRuntimeResources: Boolean
        val closeVirtualDisplay: Boolean
        val closeProjectionTargetOwner: Boolean
        synchronized(lock) {
            if (isRuntimeWorkInFlightLocked()) return
            val resources = deferredActiveResourcesClose ?: return
            deferredActiveResourcesClose = null
            resourcesToClose = resources
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
        scheduleStartupCleanup(
            cleanupScheduler = transfer.cleanupScheduler,
            cleanupFailureSink = transfer.cleanupFailureSink,
        ) {
            val cleanupFailures = CleanupFailureCollector()
            resourcesToClose?.let { resources ->
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
        synchronized(lock) {
            materializedRuntimeWorkInFlight = false
        }
    }

    private fun admitFrameRatePolicy(core: ScreenCaptureSessionCore): FrameRatePolicyAdmission {
        val effectiveParameters = currentActiveEffectiveParameters(core) ?: return FrameRatePolicyAdmission.Admitted
        return when (val frameRate = effectiveParameters.frameRate) {
            FrameRate.Auto,
            is FrameRate.PeriodicRefresh -> FrameRatePolicyAdmission.Admitted

            is FrameRate.MaxFps -> {
                val nowNanos = elapsedRealtimeNanos()
                val minimumIntervalNanos = NANOS_PER_SECOND / frameRate.fps
                val admitted = synchronized(lock) {
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
                            if (!projectionStopped) {
                                core.recordCurrentUnmaterializedProductionFrameDrop(ProductionFrameDropKind.FrameRatePolicy)
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

    private fun rememberPeriodicRefreshFrame(
        generation: Long,
        format: EncodedImageFormat,
        bytes: ByteArray,
        publicationElapsedRealtimeNanos: Long,
    ) {
        synchronized(lock) {
            latestPeriodicRefreshFrame = PeriodicRefreshEncodedFrame(
                generation = generation,
                format = format,
                bytes = bytes,
                publicationElapsedRealtimeNanos = publicationElapsedRealtimeNanos,
            )
        }
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
        val delayMillis = periodicRefreshDelayMillis(core) ?: return RuntimeFrameProductionTickResult.NoFrameSignal
        val minimumIntervalNanos = delayMillis * NANOS_PER_MILLISECOND
        val elapsedSinceLastPublication = nowNanos - frame.publicationElapsedRealtimeNanos
        if (elapsedSinceLastPublication < minimumIntervalNanos) {
            val remainingMillis = ((minimumIntervalNanos - elapsedSinceLastPublication) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
            scheduleNextPeriodicRefreshIfNeeded(core, delayMillis = remainingMillis)
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
        rememberPeriodicRefreshFrame(
            generation = frame.generation,
            format = frame.format,
            bytes = frame.bytes,
            publicationElapsedRealtimeNanos = nowNanos,
        )
        scheduleNextPeriodicRefreshIfNeeded(core)
        return RuntimeFrameProductionTickResult.Published
    }

    private fun scheduleNextPeriodicRefreshIfNeeded(core: ScreenCaptureSessionCore, delayMillis: Long? = null) {
        val effectiveDelayMillis = delayMillis ?: periodicRefreshDelayMillis(core) ?: return
        if (!periodicRefreshScheduled.compareAndSet(false, true)) return
        var future: ScheduledFuture<*>? = null
        try {
            future = runtimeScheduler.schedule(
                {
                    synchronized(lock) {
                        if (periodicRefreshFuture === future) {
                            periodicRefreshFuture = null
                        }
                        periodicRefreshScheduled.set(false)
                    }
                    if (shouldAcceptRuntimeTurn()) {
                        runtimeFrameLoop.recordPeriodicRefreshWake()
                    }
                },
                effectiveDelayMillis,
                TimeUnit.MILLISECONDS,
            )
            synchronized(lock) {
                if (state == ActiveRuntimeOwnerState.Committed && periodicRefreshScheduled.get()) {
                    periodicRefreshFuture = future
                } else {
                    future.cancel(false)
                }
            }
        } catch (cause: RejectedExecutionException) {
            synchronized(lock) {
                periodicRefreshFuture = null
                periodicRefreshScheduled.set(false)
            }
            if (shouldAcceptRuntimeTurn()) reportCleanupFailure(cause)
        }
    }

    private fun periodicRefreshDelayMillis(core: ScreenCaptureSessionCore): Long? {
        val frameRate = currentEffectiveParameters(core)?.frameRate as? FrameRate.PeriodicRefresh ?: return null
        return periodicRefreshDelayOverrideMillis ?: frameRate.intervalMillis
    }

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
        synchronized(lock) { state == ActiveRuntimeOwnerState.Committed }

    private fun hasPendingRuntimeWorkForScheduling(): Boolean {
        synchronized(lock) {
            if (state != ActiveRuntimeOwnerState.Committed) return false
            if (queuedPostCommitRuntimeSignalDrain != null) return true
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

    private fun currentActiveEffectiveParameters(core: ScreenCaptureSessionCore): ScreenCaptureEffectiveParameters? {
        val running = core.state.value as? ScreenCaptureSessionState.Running ?: return null
        return (running.output as? ScreenCaptureOutputState.Active)?.effectiveParameters
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
    val preparedRenderingPipelineResources: ActiveRuntimePreparedRenderingPipelineResources,
    val initialOutputPlan: ScreenCaptureOutputPlan,
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
    )
}

private const val RUNTIME_GL_OPERATION_TIMEOUT_MS: Long = 5_000L
private const val RUNTIME_ENCODER_OPERATION_TIMEOUT_MS: Long = 5_000L
private const val NANOS_PER_SECOND: Long = 1_000_000_000L
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
private const val RUNTIME_MATRIX_ELEMENT_COUNT: Int = 16
private const val POST_COMMIT_RUNTIME_SIGNAL_DRAIN_DELAY_MS: Long = 1L
