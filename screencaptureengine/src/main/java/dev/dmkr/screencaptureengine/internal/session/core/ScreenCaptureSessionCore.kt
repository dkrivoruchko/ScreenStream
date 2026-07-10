package dev.dmkr.screencaptureengine.internal.session.core

import android.os.SystemClock
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.FrameSubscription
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureDeliveryDropStats
import dev.dmkr.screencaptureengine.ScreenCaptureEffectiveParameters
import dev.dmkr.screencaptureengine.ScreenCaptureEvent
import dev.dmkr.screencaptureengine.ScreenCaptureEventType
import dev.dmkr.screencaptureengine.ScreenCaptureFrameDropStats
import dev.dmkr.screencaptureengine.ScreenCaptureOutputState
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSession
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStats
import dev.dmkr.screencaptureengine.ScreenCaptureStopReason
import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind
import dev.dmkr.screencaptureengine.internal.session.delivery.LatestEncodedFrame
import dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureEngineOwnedContext
import dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal lifecycle, statistics, diagnostics, and latest-frame owner.
 *
 * This class does not own MediaProjection, GL, readback, or encoder resources. Runtime layers supply
 * already-validated state transitions, parameter application, and encoded bytes. Public frame
 * delivery is delegated to [ScreenCaptureFrameDeliveryCoordinator] so lifecycle accounting and
 * callback handoff rules stay separate.
 *
 * Once runtime code materializes a production attempt, it must complete through the returned
 * [ProductionAttemptToken]. That token is the generation fence that guarantees exactly one
 * publication or production-drop outcome even if stop, projection stop, output suspension, or
 * generation replacement wins before the attempt finishes.
 */
@Suppress("unused")
internal class ScreenCaptureSessionCore internal constructor(
    private val config: ScreenCaptureConfig,
    initialState: ScreenCaptureSessionState.Running,
    private val parameterUpdater: suspend (ScreenCaptureParameters, ScreenCaptureParameterCommitGate) -> ScreenCaptureParameterUpdateResult,
    private val trimMemoryHandler: (Int) -> Unit = {},
    private val terminalCommitHandler: (ScreenCaptureSessionTerminalCommit) -> Unit = {},
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ScreenCaptureSession {
    private val terminalProblem = AtomicReference<ScreenCaptureProblem?>(null)
    private val problemSequence = AtomicLong()
    private val eventSequence = AtomicLong()
    private val frameSequence = AtomicLong()
    private val sessionGate = Any()
    private val statsLock = Any()
    private val diagnosticEventLock = Any()
    private val activeParameterTransactionSlot = AtomicReference<ParameterTransactionSlot?>(null)
    private val lastDiagnosticEventNanos = LinkedHashMap<DiagnosticEventKey, Long>()
    private var latestFrame: LatestEncodedFrame? = null
    private var currentOutputGeneration = INITIAL_OUTPUT_GENERATION
    private var statsAccumulator = StatsAccumulator(startTimestampNanos = nowNanos())
    private var latestSubscriptionStatsVersion = 0L

    private val frameDeliveryCoordinator = ScreenCaptureFrameDeliveryCoordinator(
        config = config,
        callbackEntryGate = sessionGate,
        isSessionTerminal = ::isTerminal,
        latestFrameBySequence = ::latestFrameBySequence,
        onDeliveryDrop = ::recordDeliveryDrop,
        onFrameDeliveryFailure = ::emitFrameDeliveryFailure,
        onSlowConsumerPressure = ::emitSlowConsumerPressure,
        onSubscriptionStatsChanged = ::publishSubscriptionStats,
    )

    private val mutableState = MutableStateFlow<ScreenCaptureSessionState>(initialState)
    private val mutableStats = MutableStateFlow(ScreenCaptureStats())
    private val mutableEvents = MutableSharedFlow<ScreenCaptureEvent>(
        replay = 0, extraBufferCapacity = EVENT_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val state: StateFlow<ScreenCaptureSessionState> = mutableState.asStateFlow()
    override val stats: StateFlow<ScreenCaptureStats> = mutableStats.asStateFlow()
    override val events: SharedFlow<ScreenCaptureEvent> = mutableEvents.asSharedFlow()

    init {
        emitEvent(ScreenCaptureEventType.SessionStarted, problem = null, message = "Session started.")
    }

    override suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult {
        check(!ScreenCaptureEngineOwnedContext.isCurrent) {
            "setParameters must not be called from engine-owned execution contexts."
        }
        val slot = reserveParameterTransactionSlot()
            ?: return ScreenCaptureParameterUpdateResult.Rejected(problem = currentTerminalProblem())
        try {
            if (isTerminal()) {
                return ScreenCaptureParameterUpdateResult.Rejected(problem = currentTerminalProblem())
            }
            return parameterUpdater(parameters, ::commitParameterUpdate)
        } finally {
            releaseParameterTransactionSlot(slot)
        }
    }

    override fun trimMemory(level: Int) {
        if (isTerminal()) return
        trimMemoryHandler(level)
        emitEvent(type = ScreenCaptureEventType.MemoryTrimmed, problem = null, message = "Memory trim requested.", rateLimitDiagnostic = true)
    }

    override fun stop() {
        finishStopped(reason = ScreenCaptureStopReason.OwnerStop, problem = null)
    }

    override fun close() {
        stop()
    }

    override fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription =
        synchronized(sessionGate) {
            if (isTerminal()) CancelledFrameSubscription else frameDeliveryCoordinator.register(callback)
        }

    /**
     * Publishes one already encoded frame to the latest slot and active subscriptions.
     *
     * The byte array is copied for the internal latest frame before this method returns. Public callback delivery is signalled as latest-only coordinator work;
     * any public snapshot copy happens asynchronously from the internal latest frame and is discarded if the session becomes terminal before delivery records
     * are materialized. The caller remains free to reuse or discard [bytes] after this returns.
     */
    internal fun publishEncodedFrame(
        generation: Long,
        format: EncodedImageFormat,
        bytes: ByteArray,
        timestampElapsedRealtimeNanos: Long = nowNanos(),
        countEncodedFrame: Boolean = true,
        copyBytes: Boolean = true,
    ): Boolean {
        require(generation >= 0L) { "generation must be non-negative, was $generation" }
        require(bytes.isNotEmpty()) { "bytes must not be empty" }
        require(timestampElapsedRealtimeNanos >= 0L) { "timestampElapsedRealtimeNanos must be non-negative, was $timestampElapsedRealtimeNanos" }
        if (isTerminal()) return false
        val attempt = beginProductionAttempt(generation = generation, materializeInvalidOutput = true) ?: return false
        return attempt.completeEncodedSuccess(
            format = format,
            bytes = bytes,
            encodeDurationNanos = null,
            timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
            countEncodedFrame = countEncodedFrame,
            copyBytes = copyBytes,
        )
    }

    internal fun beginProductionAttempt(generation: Long): ProductionAttemptToken? {
        require(generation >= 0L) { "generation must be non-negative, was $generation" }
        return beginProductionAttempt(generation = generation, materializeInvalidOutput = false)
    }

    internal fun currentOutputGeneration(): Long =
        synchronized(sessionGate) {
            currentOutputGeneration
        }

    internal fun updateOutputActive(effectiveParameters: ScreenCaptureEffectiveParameters, generation: Long? = null): Boolean {
        require((generation == null) || (generation >= 0L)) { "generation must be non-negative, was $generation" }
        val invalidateLatest: Boolean
        val eventType: ScreenCaptureEventType
        synchronized(sessionGate) {
            if (isTerminal()) return false
            val state = mutableState.value
            if (state !is ScreenCaptureSessionState.Running) return false
            val newGeneration = nextOutputGenerationLocked(generation)
            val outputSuspended = state.output is ScreenCaptureOutputState.Suspended
            invalidateLatest = (newGeneration != currentOutputGeneration) || outputSuspended
            currentOutputGeneration = newGeneration
            if (invalidateLatest) {
                latestFrame = null
            }
            eventType = if (state.output is ScreenCaptureOutputState.Suspended) {
                ScreenCaptureEventType.OutputPlanResumed
            } else {
                ScreenCaptureEventType.OutputPlanApplied
            }
            mutableState.value = ScreenCaptureSessionState.Running(
                output = ScreenCaptureOutputState.Active(effectiveParameters),
                capturedContentVisible = state.capturedContentVisible,
            )
            if (invalidateLatest) {
                frameDeliveryCoordinator.invalidateLatestFromSession()
            }
        }
        emitEvent(type = eventType, problem = null, message = "Output plan active.")
        return true
    }

    internal fun updateOutputSuspended(
        problem: ScreenCaptureProblem,
        previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        currentCaptureGeometry: CaptureGeometry,
        generation: Long? = null,
    ): Boolean {
        require(generation == null || generation >= 0L) { "generation must be non-negative, was $generation" }
        synchronized(sessionGate) {
            if (isTerminal()) return false
            val running = mutableState.value as? ScreenCaptureSessionState.Running ?: return false
            currentOutputGeneration = nextOutputGenerationLocked(generation)
            latestFrame = null
            mutableState.value = ScreenCaptureSessionState.Running(
                output = ScreenCaptureOutputState.Suspended(
                    problem = problem,
                    previousEffectiveParameters = previousEffectiveParameters,
                    currentCaptureGeometry = currentCaptureGeometry,
                ),
                capturedContentVisible = running.capturedContentVisible,
            )
            frameDeliveryCoordinator.invalidateLatestFromSession()
        }
        emitEvent(type = ScreenCaptureEventType.OutputPlanSuspended, problem = problem, message = "Output plan suspended.")
        return true
    }

    internal fun updateCapturedContentVisibility(isVisible: Boolean): Boolean =
        synchronized(sessionGate) {
            if (isTerminal()) return@synchronized false
            val running = mutableState.value as? ScreenCaptureSessionState.Running ?: return@synchronized false
            if (running.capturedContentVisible == isVisible) {
                true
            } else {
                mutableState.value = ScreenCaptureSessionState.Running(output = running.output, capturedContentVisible = isVisible)
                true
            }
        }

    internal fun recordInvalidMetricsIgnored(message: String) {
        val problem = newProblem(kind = ScreenCaptureProblemKind.MetricsUnavailableOrInvalid, message = message, cause = null)
        emitEvent(type = ScreenCaptureEventType.InvalidMetricsIgnored, problem = problem, message = message, rateLimitDiagnostic = true)
    }

    /**
     * Records a current-session production drop only when no generation-scoped attempt was materialized.
     *
     * Runtime work that borrowed GL/readback/encoder resources must complete through [ProductionAttemptToken] so terminal and generation races resolve
     * against the generation that admitted that attempt.
     */
    internal fun recordCurrentUnmaterializedProductionFrameDrop(kind: ProductionFrameDropKind) {
        synchronized(sessionGate) {
            if (isTerminal()) return
            recordProductionFrameDropLocked(kind)
        }
    }

    internal fun finishStopped(reason: ScreenCaptureStopReason, problem: ScreenCaptureProblem?) {
        val rejectionProblem = terminalRejectionProblem(reason, problem)
        val terminalCommit: ScreenCaptureSessionTerminalCommit
        synchronized(sessionGate) {
            if (!terminalProblem.compareAndSet(null, rejectionProblem)) return
            latestFrame = null
            terminalCommit = ScreenCaptureSessionTerminalCommit.Stopped(reason = reason, problem = problem)
        }
        signalParameterTransactionsTerminal()
        invokeTerminalCommitHandler(terminalCommit)
        frameDeliveryCoordinator.closeFromSession()
        mutableState.value = ScreenCaptureSessionState.Stopped(reason = reason, problem = problem)
        emitEvent(ScreenCaptureEventType.SessionStopped, problem = problem, message = "Session stopped.")
    }

    internal fun finishFailed(problem: ScreenCaptureProblem) {
        val terminalCommit: ScreenCaptureSessionTerminalCommit
        synchronized(sessionGate) {
            if (!terminalProblem.compareAndSet(null, problem)) return
            latestFrame = null
            terminalCommit = ScreenCaptureSessionTerminalCommit.Failed(problem)
        }
        signalParameterTransactionsTerminal()
        invokeTerminalCommitHandler(terminalCommit)
        frameDeliveryCoordinator.closeFromSession()
        mutableState.value = ScreenCaptureSessionState.Failed(problem)
        emitEvent(ScreenCaptureEventType.SessionFailed, problem = problem, message = "Session failed.")
    }

    internal fun newProblem(kind: ScreenCaptureProblemKind, message: String?, cause: Throwable?): ScreenCaptureProblem =
        ScreenCaptureProblem(sequence = problemSequence.incrementAndGet(), kind = kind, message = message, cause = cause)

    private fun isTerminal(): Boolean = terminalProblem.get() != null

    private fun currentTerminalProblem(): ScreenCaptureProblem =
        terminalProblem.get() ?: newProblem(kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped, message = "Session is terminal.", cause = null)

    private suspend fun reserveParameterTransactionSlot(): ParameterTransactionSlot? {
        while (true) {
            if (isTerminal()) return null
            val slot = ParameterTransactionSlot()
            if (activeParameterTransactionSlot.compareAndSet(null, slot)) {
                if (isTerminal()) {
                    releaseParameterTransactionSlot(slot)
                    return null
                }
                return slot
            }
            activeParameterTransactionSlot.get()?.completed?.await()
        }
    }

    private fun releaseParameterTransactionSlot(slot: ParameterTransactionSlot) {
        activeParameterTransactionSlot.compareAndSet(slot, null)
        slot.completed.complete(Unit)
    }

    private fun signalParameterTransactionsTerminal() {
        activeParameterTransactionSlot.get()?.completed?.complete(Unit)
    }

    private fun commitParameterUpdate(commit: () -> ScreenCaptureParameterUpdateResult): ScreenCaptureParameterUpdateResult =
        synchronized(sessionGate) {
            terminalProblem.get()?.let { problem -> ScreenCaptureParameterUpdateResult.Rejected(problem) } ?: commit()
        }

    private fun latestFrameBySequence(sequence: Long): LatestEncodedFrame? =
        synchronized(sessionGate) {
            latestFrame?.takeIf { frame -> !isTerminal() && frame.sequence == sequence }
        }

    private fun invokeTerminalCommitHandler(terminalCommit: ScreenCaptureSessionTerminalCommit) {
        try {
            // This hook is an inline fast fence/steal point only. It must not wait for runtime cleanup, GL work, encoders, or callback delivery.
            terminalCommitHandler(terminalCommit)
        } catch (_: Throwable) {
            // Terminal publication and delivery shutdown must still complete even if an internal fence hook is faulty.
        }
    }

    private fun beginProductionAttempt(generation: Long, materializeInvalidOutput: Boolean): ProductionAttemptToken? =
        synchronized(sessionGate) {
            if (isTerminal()) {
                null
            } else if (!materializeInvalidOutput && publicationDropKindLocked(generation, byteCount = 1) != null) {
                null
            } else {
                ProductionAttemptToken(session = this, generation = generation)
            }
        }

    private fun terminalRejectionProblem(reason: ScreenCaptureStopReason, problem: ScreenCaptureProblem?): ScreenCaptureProblem =
        problem ?: newProblem(
            kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
            message = when (reason) {
                ScreenCaptureStopReason.OwnerStop -> "Session was stopped by its owner."
                ScreenCaptureStopReason.CaptureEnded -> "Screen capture projection stopped."
            },
            cause = null,
        )

    private fun recordDeliveryDrop(kind: DeliveryDropKind) {
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withDeliveryDrop(kind)
            publishStatsLocked()
        }
    }

    private fun publicationDropKindLocked(generation: Long, byteCount: Int): ProductionFrameDropKind? =
        when {
            isTerminal() -> ProductionFrameDropKind.StaleGeneration
            generation != currentOutputGeneration -> ProductionFrameDropKind.StaleGeneration
            (mutableState.value as? ScreenCaptureSessionState.Running)?.output !is ScreenCaptureOutputState.Active ->
                ProductionFrameDropKind.OutputSuspended

            byteCount > config.maxEncodedBytes -> ProductionFrameDropKind.EncodedSizeLimit
            else -> null
        }

    private fun nextOutputGenerationLocked(requestedGeneration: Long?): Long =
        requestedGeneration?.also { generation ->
            check(generation > currentOutputGeneration) {
                "generation must be greater than currentOutputGeneration."
            }
        } ?: Math.addExact(currentOutputGeneration, 1L)

    private fun recordProductionFrameDropLocked(kind: ProductionFrameDropKind) {
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withFrameDrop(kind)
            publishStatsLocked()
        }
        emitEvent(type = ScreenCaptureEventType.EncodedFrameDropped, problem = null, message = "Encoded frame dropped.", rateLimitDiagnostic = true)
    }

    internal fun recordReadbackSample(durationNanos: Long) {
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withReadbackSample(durationNanos)
            publishStatsLocked()
        }
    }

    internal fun completeProductionDrop(generation: Long, kind: ProductionFrameDropKind): ProductionFrameDropKind {
        val resolvedKind = synchronized(sessionGate) {
            when {
                isTerminal() || generation != currentOutputGeneration -> ProductionFrameDropKind.StaleGeneration
                (mutableState.value as? ScreenCaptureSessionState.Running)?.output !is ScreenCaptureOutputState.Active ->
                    ProductionFrameDropKind.OutputSuspended

                else -> kind
            }
        }
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withFrameDrop(resolvedKind)
            publishStatsLocked()
        }
        emitEvent(type = ScreenCaptureEventType.EncodedFrameDropped, problem = null, message = "Encoded frame dropped.", rateLimitDiagnostic = true)
        return resolvedKind
    }

    internal fun completeEncodedSuccess(
        generation: Long,
        format: EncodedImageFormat,
        bytes: ByteArray,
        encodeDurationNanos: Long?,
        timestampElapsedRealtimeNanos: Long,
        countEncodedFrame: Boolean = true,
        copyBytes: Boolean = true,
    ): Boolean {
        synchronized(sessionGate) {
            publicationDropKindLocked(generation, bytes.size)?.let { dropKind ->
                if (countEncodedFrame && bytes.size <= config.maxEncodedBytes) {
                    recordEncodedFrameDroppedLocked(byteCount = bytes.size, encodeDurationNanos = encodeDurationNanos, kind = dropKind)
                } else {
                    recordProductionFrameDropLocked(dropKind)
                }
                return false
            }
        }
        val latestBytes = if (copyBytes) bytes.copyOf() else bytes
        val publishedFrame = synchronized(sessionGate) {
            publicationDropKindLocked(generation, latestBytes.size)?.let { dropKind ->
                if (countEncodedFrame && latestBytes.size <= config.maxEncodedBytes) {
                    recordEncodedFrameDroppedLocked(byteCount = bytes.size, encodeDurationNanos = encodeDurationNanos, kind = dropKind)
                } else {
                    recordProductionFrameDropLocked(dropKind)
                }
                return false
            }
            LatestEncodedFrame(format, latestBytes, frameSequence.incrementAndGet(), timestampElapsedRealtimeNanos).also { frame ->
                latestFrame = frame
                synchronized(statsLock) {
                    if (countEncodedFrame) {
                        statsAccumulator = statsAccumulator.withEncodedFrame(byteCount = bytes.size, encodeDurationNanos = encodeDurationNanos)
                    }
                    statsAccumulator = statsAccumulator.withPublishedFrame()
                    publishStatsLocked()
                }
            }
        }
        frameDeliveryCoordinator.signalLatestFramePublished(publishedFrame.sequence)
        return true
    }

    private fun recordEncodedFrameDroppedLocked(byteCount: Int, encodeDurationNanos: Long?, kind: ProductionFrameDropKind) {
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withEncodedFrame(byteCount = byteCount, encodeDurationNanos = encodeDurationNanos).withFrameDrop(kind)
            publishStatsLocked()
        }
        emitEvent(type = ScreenCaptureEventType.EncodedFrameDropped, problem = null, message = "Encoded frame dropped.", rateLimitDiagnostic = true)
    }

    private fun emitFrameDeliveryFailure(kind: ScreenCaptureProblemKind, message: String, cause: Throwable) {
        val problem = newProblem(kind, message, cause)
        if (!isTerminal()) {
            emitEvent(type = ScreenCaptureEventType.FrameDeliveryFailure, problem = problem, message = message, rateLimitDiagnostic = true)
        }
    }

    private fun emitSlowConsumerPressure(message: String) {
        emitEvent(type = ScreenCaptureEventType.SlowConsumerPressure, problem = null, message = message, rateLimitDiagnostic = true)
    }

    private fun publishSubscriptionStats(activeFrameSubscriptions: Int, slowConsumers: Int, version: Long) {
        synchronized(statsLock) {
            if (version < latestSubscriptionStatsVersion) return
            latestSubscriptionStatsVersion = version
            statsAccumulator = statsAccumulator.withSubscriptions(activeFrameSubscriptions = activeFrameSubscriptions, slowConsumers = slowConsumers)
            publishStatsLocked()
        }
    }

    private fun publishStatsLocked() {
        mutableStats.value = statsAccumulator.toStats(nowNanos())
    }

    private fun emitEvent(type: ScreenCaptureEventType, problem: ScreenCaptureProblem?, message: String?, rateLimitDiagnostic: Boolean = false) {
        if (isTerminal() && type != ScreenCaptureEventType.SessionStopped && type != ScreenCaptureEventType.SessionFailed) return
        if (rateLimitDiagnostic && !shouldEmitDiagnosticEvent(type, problem?.kind)) return
        mutableEvents.tryEmit(
            ScreenCaptureEvent(
                sequence = eventSequence.incrementAndGet(),
                timestampElapsedRealtimeNanos = nowNanos(),
                type = type,
                problem = problem,
                message = message,
            ),
        )
    }

    private fun shouldEmitDiagnosticEvent(type: ScreenCaptureEventType, problemKind: ScreenCaptureProblemKind?): Boolean {
        if (isTerminal()) return false
        val nowNanos = nowNanos()
        val key = DiagnosticEventKey(type = type, problemKind = problemKind)
        return synchronized(diagnosticEventLock) {
            val lastNanos = lastDiagnosticEventNanos[key]
            if (lastNanos == null || nowNanos - lastNanos >= DIAGNOSTIC_EVENT_RATE_LIMIT_NANOS) {
                lastDiagnosticEventNanos[key] = nowNanos
                true
            } else {
                false
            }
        }
    }

    internal fun nowNanos(): Long = elapsedRealtimeNanos()
}

/**
 * Serializes the visible parameter commit point against terminal transitions.
 *
 * Preparation and validation may run before this gate. The updater must perform the first externally visible state/resource swap inside [commit] so the
 * returned acknowledgement matches the lifecycle winner: committed updates return their real result, while terminal sessions return the terminal rejection
 * problem without exposing a partial plan.
 */
internal fun interface ScreenCaptureParameterCommitGate {
    fun commit(block: () -> ScreenCaptureParameterUpdateResult): ScreenCaptureParameterUpdateResult
}

private data object CancelledFrameSubscription : FrameSubscription {
    override fun cancel() = Unit
}

private class ParameterTransactionSlot {
    val completed = CompletableDeferred<Unit>()
}

internal sealed class ScreenCaptureSessionTerminalCommit private constructor() {
    internal class Stopped internal constructor(
        internal val reason: ScreenCaptureStopReason,
        internal val problem: ScreenCaptureProblem?,
    ) : ScreenCaptureSessionTerminalCommit()

    internal class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
    ) : ScreenCaptureSessionTerminalCommit()
}

/**
 * One-shot completion handle for a materialized runtime production attempt.
 *
 * Readback timing is sampled once when valid raw input was produced, even if the attempt later
 * becomes stale. Encode timing and `framesEncoded` are sampled only by [completeEncodedSuccess].
 * Every terminal path is guarded by [completed], so callers may race cleanup/cancellation paths
 * without double publishing or double-counting a drop.
 */
internal class ProductionAttemptToken internal constructor(
    private val session: ScreenCaptureSessionCore,
    private val generation: Long,
) {
    private val readbackCompleted = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)

    internal fun recordReadbackSuccess(durationNanos: Long) {
        require(durationNanos >= 0L) { "durationNanos must be non-negative, was $durationNanos" }
        if (readbackCompleted.compareAndSet(false, true)) {
            session.recordReadbackSample(durationNanos)
        }
    }

    internal fun completeEncodedSuccess(
        format: EncodedImageFormat,
        bytes: ByteArray,
        encodeDurationNanos: Long?,
        timestampElapsedRealtimeNanos: Long = session.nowNanos(),
        countEncodedFrame: Boolean = true,
        copyBytes: Boolean = true,
    ): Boolean {
        require(encodeDurationNanos == null || encodeDurationNanos >= 0L) {
            "encodeDurationNanos must be null or non-negative, was $encodeDurationNanos"
        }
        require(bytes.isNotEmpty()) { "bytes must not be empty" }
        require(timestampElapsedRealtimeNanos >= 0L) { "timestampElapsedRealtimeNanos must be non-negative, was $timestampElapsedRealtimeNanos" }
        if (!completed.compareAndSet(false, true)) return false
        return session.completeEncodedSuccess(
            generation = generation,
            format = format,
            bytes = bytes,
            encodeDurationNanos = encodeDurationNanos,
            timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
            countEncodedFrame = countEncodedFrame,
            copyBytes = copyBytes,
        )
    }

    internal fun completeEncodedSizeLimitDrop(): Boolean =
        completeDrop(ProductionFrameDropKind.EncodedSizeLimit)

    internal fun completeEncodeFailedDrop(): Boolean =
        completeDrop(ProductionFrameDropKind.TransientFailure)

    internal fun completeEncodeThrewDrop(): Boolean =
        completeDrop(ProductionFrameDropKind.TransientFailure)

    internal fun completeDrop(kind: ProductionFrameDropKind): Boolean {
        return completeDropAndResolve(kind) != null
    }

    internal fun completeDropAndResolve(kind: ProductionFrameDropKind): ProductionFrameDropKind? {
        if (!completed.compareAndSet(false, true)) return null
        return session.completeProductionDrop(generation = generation, kind = kind)
    }
}

internal enum class ProductionFrameDropKind {
    FrameRatePolicy,
    ReadbackBusy,
    EncoderBusy,
    OutputSuspended,
    StaleGeneration,
    EncodedSizeLimit,
    TransientFailure,
}

private data class DiagnosticEventKey(val type: ScreenCaptureEventType, val problemKind: ScreenCaptureProblemKind?)

private data class StatsAccumulator(
    val startTimestampNanos: Long,
    val framesEncoded: Long = 0L,
    val framesPublished: Long = 0L,
    val frameDrops: ScreenCaptureFrameDropStats = ScreenCaptureFrameDropStats(),
    val deliveryDrops: ScreenCaptureDeliveryDropStats = ScreenCaptureDeliveryDropStats(),
    val lastEncodedByteCount: Int = 0,
    val totalEncodedByteCount: Long = 0L,
    val encodeSampleCount: Long = 0L,
    val totalEncodeDurationNanos: Long = 0L,
    val readbackSampleCount: Long = 0L,
    val totalReadbackDurationNanos: Long = 0L,
    val activeFrameSubscriptions: Int = 0,
    val slowConsumers: Int = 0,
) {
    fun withEncodedFrame(byteCount: Int, encodeDurationNanos: Long?): StatsAccumulator {
        val hasEncodeSample = encodeDurationNanos != null
        return copy(
            framesEncoded = Math.addExact(framesEncoded, 1L),
            lastEncodedByteCount = byteCount,
            totalEncodedByteCount = Math.addExact(totalEncodedByteCount, byteCount.toLong()),
            encodeSampleCount = if (hasEncodeSample) Math.addExact(encodeSampleCount, 1L) else encodeSampleCount,
            totalEncodeDurationNanos = if (hasEncodeSample) {
                Math.addExact(totalEncodeDurationNanos, encodeDurationNanos)
            } else {
                totalEncodeDurationNanos
            },
        )
    }

    fun withReadbackSample(durationNanos: Long): StatsAccumulator = copy(
        readbackSampleCount = Math.addExact(readbackSampleCount, 1L),
        totalReadbackDurationNanos = Math.addExact(totalReadbackDurationNanos, durationNanos),
    )

    fun withPublishedFrame(): StatsAccumulator = copy(framesPublished = Math.addExact(framesPublished, 1L))

    fun withFrameDrop(kind: ProductionFrameDropKind): StatsAccumulator = copy(
        frameDrops = when (kind) {
            ProductionFrameDropKind.FrameRatePolicy -> frameDrops.copyFrameDrop(
                byFrameRatePolicy = frameDrops.byFrameRatePolicy.checkedIncrement(),
            )

            ProductionFrameDropKind.ReadbackBusy -> frameDrops.copyFrameDrop(byReadbackBusy = frameDrops.byReadbackBusy.checkedIncrement())
            ProductionFrameDropKind.EncoderBusy -> frameDrops.copyFrameDrop(byEncoderBusy = frameDrops.byEncoderBusy.checkedIncrement())
            ProductionFrameDropKind.OutputSuspended -> frameDrops.copyFrameDrop(
                byOutputSuspended = frameDrops.byOutputSuspended.checkedIncrement(),
            )

            ProductionFrameDropKind.StaleGeneration -> frameDrops.copyFrameDrop(
                byStaleGeneration = frameDrops.byStaleGeneration.checkedIncrement()
            )

            ProductionFrameDropKind.EncodedSizeLimit -> frameDrops.copyFrameDrop(
                byEncodedSizeLimit = frameDrops.byEncodedSizeLimit.checkedIncrement()
            )

            ProductionFrameDropKind.TransientFailure -> frameDrops.copyFrameDrop(
                byTransientFailure = frameDrops.byTransientFailure.checkedIncrement()
            )
        },
    )

    fun withDeliveryDrop(kind: DeliveryDropKind): StatsAccumulator = copy(
        deliveryDrops = when (kind) {
            DeliveryDropKind.SubscriptionBusy -> deliveryDrops.copyDelivery(bySubscriptionBusy = deliveryDrops.bySubscriptionBusy.checkedIncrement())
            DeliveryDropKind.DispatchFailed -> deliveryDrops.copyDelivery(byDispatchFailed = deliveryDrops.byDispatchFailed.checkedIncrement())
            DeliveryDropKind.SnapshotSlotsExhausted -> deliveryDrops.copyDelivery(
                bySnapshotSlotsExhausted = deliveryDrops.bySnapshotSlotsExhausted.checkedIncrement()
            )

            DeliveryDropKind.CallbackThrew -> deliveryDrops.copyDelivery(byCallbackThrew = deliveryDrops.byCallbackThrew.checkedIncrement())
            DeliveryDropKind.StaleSession -> deliveryDrops.copyDelivery(byStaleSession = deliveryDrops.byStaleSession.checkedIncrement())
        },
    )

    fun withSubscriptions(activeFrameSubscriptions: Int, slowConsumers: Int): StatsAccumulator =
        copy(activeFrameSubscriptions = activeFrameSubscriptions, slowConsumers = slowConsumers)

    fun toStats(nowNanos: Long): ScreenCaptureStats {
        val elapsedSeconds = ((nowNanos - startTimestampNanos).coerceAtLeast(0L)).toDouble() / NANOS_PER_SECOND
        return ScreenCaptureStats(
            framesEncoded = framesEncoded,
            framesPublished = framesPublished,
            droppedFrames = frameDrops,
            droppedDeliveries = deliveryDrops,
            publishedFps = if (elapsedSeconds > 0.0) framesPublished.toDouble() / elapsedSeconds else 0.0,
            averageEncodeMs = averageDurationMs(totalEncodeDurationNanos, encodeSampleCount),
            averageReadbackMs = averageDurationMs(totalReadbackDurationNanos, readbackSampleCount),
            lastEncodedByteCount = lastEncodedByteCount,
            averageEncodedByteCount = if (framesEncoded > 0L) {
                (totalEncodedByteCount / framesEncoded).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            },
            activeFrameSubscriptions = activeFrameSubscriptions,
            slowConsumers = slowConsumers,
        )
    }
}

private fun averageDurationMs(totalDurationNanos: Long, sampleCount: Long): Double =
    if (sampleCount > 0L) totalDurationNanos.toDouble() / sampleCount.toDouble() / NANOS_PER_MILLISECOND else 0.0

private fun Long.checkedIncrement(): Long = Math.addExact(this, 1L)

private fun ScreenCaptureDeliveryDropStats.copyDelivery(
    bySubscriptionBusy: Long = this.bySubscriptionBusy,
    byDispatchFailed: Long = this.byDispatchFailed,
    bySnapshotSlotsExhausted: Long = this.bySnapshotSlotsExhausted,
    byCallbackThrew: Long = this.byCallbackThrew,
    byStaleSession: Long = this.byStaleSession,
): ScreenCaptureDeliveryDropStats = ScreenCaptureDeliveryDropStats(
    total = Math.addExact(
        Math.addExact(bySubscriptionBusy, byDispatchFailed),
        Math.addExact(Math.addExact(bySnapshotSlotsExhausted, byCallbackThrew), byStaleSession)
    ),
    bySubscriptionBusy = bySubscriptionBusy,
    byDispatchFailed = byDispatchFailed,
    bySnapshotSlotsExhausted = bySnapshotSlotsExhausted,
    byCallbackThrew = byCallbackThrew,
    byStaleSession = byStaleSession,
)

private fun ScreenCaptureFrameDropStats.copyFrameDrop(
    byFrameRatePolicy: Long = this.byFrameRatePolicy,
    byReadbackBusy: Long = this.byReadbackBusy,
    byEncoderBusy: Long = this.byEncoderBusy,
    byOutputSuspended: Long = this.byOutputSuspended,
    byStaleGeneration: Long = this.byStaleGeneration,
    byEncodedSizeLimit: Long = this.byEncodedSizeLimit,
    byTransientFailure: Long = this.byTransientFailure,
): ScreenCaptureFrameDropStats = ScreenCaptureFrameDropStats(
    total = Math.addExact(
        Math.addExact(Math.addExact(byFrameRatePolicy, byReadbackBusy), Math.addExact(byEncoderBusy, byOutputSuspended)),
        Math.addExact(Math.addExact(byStaleGeneration, byEncodedSizeLimit), byTransientFailure),
    ),
    byFrameRatePolicy = byFrameRatePolicy,
    byReadbackBusy = byReadbackBusy,
    byEncoderBusy = byEncoderBusy,
    byOutputSuspended = byOutputSuspended,
    byStaleGeneration = byStaleGeneration,
    byEncodedSizeLimit = byEncodedSizeLimit,
    byTransientFailure = byTransientFailure,
)

private const val EVENT_BUFFER_CAPACITY: Int = 32
private const val DIAGNOSTIC_EVENT_RATE_LIMIT_NANOS: Long = 1_000_000_000L
private const val INITIAL_OUTPUT_GENERATION: Long = 0L
private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
private const val NANOS_PER_MILLISECOND: Double = 1_000_000.0
