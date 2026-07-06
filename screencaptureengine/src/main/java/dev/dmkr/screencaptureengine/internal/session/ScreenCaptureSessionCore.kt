package dev.dmkr.screencaptureengine.internal.session

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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal lifecycle, statistics, diagnostics, and latest-frame owner.
 *
 * This class does not own MediaProjection, GL, readback, or encoder resources. Runtime layers supply already-validated state transitions, parameter
 * application, and encoded bytes. Public frame delivery is delegated to [ScreenCaptureFrameDeliveryCoordinator] so lifecycle accounting and callback
 * handoff rules stay separate.
 */
@Suppress("unused")
internal class ScreenCaptureSessionCore internal constructor(
    private val config: ScreenCaptureConfig,
    initialState: ScreenCaptureSessionState.Running,
    private val parameterUpdater: suspend (ScreenCaptureParameters, ScreenCaptureParameterCommitGate) -> ScreenCaptureParameterUpdateResult,
    private val trimMemoryHandler: (Int) -> Unit = {},
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ScreenCaptureSession {
    private val terminalProblem = AtomicReference<ScreenCaptureProblem?>(null)
    private val problemSequence = AtomicLong()
    private val eventSequence = AtomicLong()
    private val frameSequence = AtomicLong()
    private val sessionGate = Any()
    private val statsLock = Any()
    private val diagnosticEventLock = Any()
    private val parameterMutex = Mutex()
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
        return parameterMutex.withLock {
            if (isTerminal()) {
                return@withLock ScreenCaptureParameterUpdateResult.Rejected(problem = currentTerminalProblem())
            }
            parameterUpdater(parameters) { commit ->
                synchronized(sessionGate) {
                    terminalProblem.get()?.let { problem -> ScreenCaptureParameterUpdateResult.Rejected(problem) } ?: commit()
                }
            }
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
    ): Boolean {
        require(generation >= 0L) { "generation must be non-negative, was $generation" }
        require(bytes.isNotEmpty()) { "bytes must not be empty" }
        require(timestampElapsedRealtimeNanos >= 0L) { "timestampElapsedRealtimeNanos must be non-negative, was $timestampElapsedRealtimeNanos" }
        if (isTerminal()) return false
        synchronized(sessionGate) {
            if (isTerminal()) {
                return false
            }
            publicationDropKindLocked(generation, bytes.size)?.let { dropKind ->
                recordEncodedFrameDroppedLocked(bytes.size, dropKind)
                return false
            }
        }
        val latestBytes = bytes.copyOf()
        val publishedFrame = synchronized(sessionGate) {
            if (isTerminal()) {
                return false
            }
            publicationDropKindLocked(generation, latestBytes.size)?.let { dropKind ->
                recordEncodedFrameDroppedLocked(bytes.size, dropKind)
                return false
            }
            LatestEncodedFrame(format, latestBytes, frameSequence.incrementAndGet(), timestampElapsedRealtimeNanos).also { frame ->
                latestFrame = frame
                synchronized(statsLock) {
                    statsAccumulator = statsAccumulator.withEncodedFrame(bytes.size)
                    statsAccumulator = statsAccumulator.withPublishedFrame()
                    publishStatsLocked()
                }
            }
        }
        frameDeliveryCoordinator.signalLatestFramePublished(publishedFrame.sequence)
        return true
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

    internal fun recordProductionFrameDrop(kind: ProductionFrameDropKind) {
        synchronized(sessionGate) {
            if (isTerminal()) return
            recordProductionFrameDropLocked(kind)
        }
    }

    internal fun finishStopped(reason: ScreenCaptureStopReason, problem: ScreenCaptureProblem?) {
        val rejectionProblem = terminalRejectionProblem(reason, problem)
        synchronized(sessionGate) {
            if (!terminalProblem.compareAndSet(null, rejectionProblem)) return
            latestFrame = null
        }
        frameDeliveryCoordinator.closeFromSession()
        mutableState.value = ScreenCaptureSessionState.Stopped(reason = reason, problem = problem)
        emitEvent(ScreenCaptureEventType.SessionStopped, problem = problem, message = "Session stopped.")
    }

    internal fun finishFailed(problem: ScreenCaptureProblem) {
        synchronized(sessionGate) {
            if (!terminalProblem.compareAndSet(null, problem)) return
            latestFrame = null
        }
        frameDeliveryCoordinator.closeFromSession()
        mutableState.value = ScreenCaptureSessionState.Failed(problem)
    }

    internal fun newProblem(kind: ScreenCaptureProblemKind, message: String?, cause: Throwable?): ScreenCaptureProblem =
        ScreenCaptureProblem(sequence = problemSequence.incrementAndGet(), kind = kind, message = message, cause = cause)

    private fun isTerminal(): Boolean = terminalProblem.get() != null

    private fun currentTerminalProblem(): ScreenCaptureProblem =
        terminalProblem.get() ?: newProblem(kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped, message = "Session is terminal.", cause = null)

    private fun latestFrameBySequence(sequence: Long): LatestEncodedFrame? =
        synchronized(sessionGate) {
            latestFrame?.takeIf { frame -> !isTerminal() && frame.sequence == sequence }
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

    private fun recordEncodedFrameDroppedLocked(byteCount: Int, kind: ProductionFrameDropKind) {
        synchronized(statsLock) {
            statsAccumulator = statsAccumulator.withEncodedFrame(byteCount).withFrameDrop(kind)
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
        if (type != ScreenCaptureEventType.SessionStopped && isTerminal()) return
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

    private fun nowNanos(): Long = elapsedRealtimeNanos()
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
    val activeFrameSubscriptions: Int = 0,
    val slowConsumers: Int = 0,
) {
    fun withEncodedFrame(byteCount: Int): StatsAccumulator = copy(
        framesEncoded = Math.addExact(framesEncoded, 1L),
        lastEncodedByteCount = byteCount,
        totalEncodedByteCount = Math.addExact(totalEncodedByteCount, byteCount.toLong()),
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
            averageEncodeMs = 0.0,
            averageReadbackMs = 0.0,
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
