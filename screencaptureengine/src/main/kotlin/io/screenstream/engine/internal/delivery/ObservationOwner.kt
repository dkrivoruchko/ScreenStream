package io.screenstream.engine.internal.delivery

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ScreenCaptureDeliveryDropStats
import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureFrameDropStats
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureRunningState
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStats
import io.screenstream.engine.ScreenCaptureStopReason
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface ObservationStateSnapshot {
    internal object Starting : ObservationStateSnapshot

    internal class Running(
        internal val requestedParameters: ScreenCaptureParameters,
        internal val runningState: ObservationRunningStateSnapshot,
        internal val capturedContentVisible: Boolean?,
    ) : ObservationStateSnapshot

    internal class Stopped(
        internal val reason: ScreenCaptureStopReason,
        internal val requestedParameters: ScreenCaptureParameters,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ObservationStateSnapshot

    internal class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val requestedParameters: ScreenCaptureParameters,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ObservationStateSnapshot
}

internal sealed interface ObservationRunningStateSnapshot {
    internal class Active(
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : ObservationRunningStateSnapshot

    internal class Suspended(
        internal val problem: ScreenCaptureProblem,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val lastKnownCaptureGeometry: CaptureGeometry?,
    ) : ObservationRunningStateSnapshot
}

internal class ObservationStatsSnapshot(
    internal val framesEncoded: Long,
    internal val framesProduced: Long,
    internal val frameDropsByRateLimit: Long,
    internal val frameDropsByPipelineBusy: Long,
    internal val frameDropsByStaleWork: Long,
    internal val frameDropsByFailure: Long,
    internal val deliveryDropsByConsumerBusy: Long,
    internal val deliveryDropsByCallbackFailure: Long,
    internal val averageProducedFps: Double,
    internal val averageEncodeMs: Double,
    internal val averageReadbackMs: Double,
    internal val lastEncodedByteCount: Int,
    internal val averageEncodedByteCount: Int,
)

internal class ObservationDiagnosticRequest(
    internal val sequence: Long,
    internal val timestampEpochMillis: Long,
    internal val source: String,
    internal val label: String,
    internal val message: String,
    internal val cause: Throwable?,
)

internal class ObservationOwner {
    private val mutableState = MutableStateFlow<ScreenCaptureState>(ScreenCaptureState.NotStarted)
    private val mutableStats = MutableStateFlow(initialStats())
    private val mutableDiagnosticEvents = MutableSharedFlow<ScreenCaptureDiagnosticEvent>(
        replay = 0,
        extraBufferCapacity = DIAGNOSTIC_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal val state: StateFlow<ScreenCaptureState> = mutableState.asStateFlow()
    internal val stats: StateFlow<ScreenCaptureStats> = mutableStats.asStateFlow()
    internal val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent> =
        mutableDiagnosticEvents.asSharedFlow()

    internal fun assignState(snapshot: ObservationStateSnapshot) {
        mutableState.value = snapshot.toPublicState()
    }

    internal fun assignStats(snapshot: ObservationStatsSnapshot) {
        mutableStats.value = snapshot.toPublicStats()
    }

    internal fun tryEmitDiagnostic(request: ObservationDiagnosticRequest) {
        mutableDiagnosticEvents.tryEmit(request.toPublicEvent())
    }

    private fun ObservationStateSnapshot.toPublicState(): ScreenCaptureState = when (this) {
        ObservationStateSnapshot.Starting -> ScreenCaptureState.Starting
        is ObservationStateSnapshot.Running -> ScreenCaptureState.Running(
            requestedParameters = requestedParameters,
            runningState = runningState.toPublicRunningState(),
            capturedContentVisible = capturedContentVisible,
        )

        is ObservationStateSnapshot.Stopped -> ScreenCaptureState.Stopped(
            reason = reason,
            requestedParameters = requestedParameters,
            lastEffectiveParameters = lastEffectiveParameters,
        )

        is ObservationStateSnapshot.Failed -> ScreenCaptureState.Failed(
            problem = problem,
            requestedParameters = requestedParameters,
            lastEffectiveParameters = lastEffectiveParameters,
        )
    }

    private fun ObservationRunningStateSnapshot.toPublicRunningState(): ScreenCaptureRunningState = when (this) {
        is ObservationRunningStateSnapshot.Active -> ScreenCaptureRunningState.Active(
            effectiveParameters = effectiveParameters,
        )

        is ObservationRunningStateSnapshot.Suspended -> ScreenCaptureRunningState.Suspended(
            problem = problem,
            lastEffectiveParameters = lastEffectiveParameters,
            lastKnownCaptureGeometry = lastKnownCaptureGeometry,
        )
    }

    private fun ObservationStatsSnapshot.toPublicStats(): ScreenCaptureStats = ScreenCaptureStats(
        framesEncoded = framesEncoded,
        framesProduced = framesProduced,
        droppedFrames = ScreenCaptureFrameDropStats(
            byRateLimit = frameDropsByRateLimit,
            byPipelineBusy = frameDropsByPipelineBusy,
            byStaleWork = frameDropsByStaleWork,
            byFailure = frameDropsByFailure,
        ),
        droppedDeliveries = ScreenCaptureDeliveryDropStats(
            byConsumerBusy = deliveryDropsByConsumerBusy,
            byCallbackFailure = deliveryDropsByCallbackFailure,
        ),
        averageProducedFps = averageProducedFps,
        averageEncodeMs = averageEncodeMs,
        averageReadbackMs = averageReadbackMs,
        lastEncodedByteCount = lastEncodedByteCount,
        averageEncodedByteCount = averageEncodedByteCount,
    )

    private fun ObservationDiagnosticRequest.toPublicEvent(): ScreenCaptureDiagnosticEvent =
        ScreenCaptureDiagnosticEvent(
            sequence = sequence,
            timestampEpochMillis = timestampEpochMillis,
            source = source,
            label = label,
            message = message,
            cause = cause,
        )

    private companion object {
        private const val DIAGNOSTIC_BUFFER_CAPACITY: Int = 128

        private fun initialStats(): ScreenCaptureStats = ScreenCaptureStats(
            framesEncoded = 0L,
            framesProduced = 0L,
            droppedFrames = ScreenCaptureFrameDropStats(
                byRateLimit = 0L,
                byPipelineBusy = 0L,
                byStaleWork = 0L,
                byFailure = 0L,
            ),
            droppedDeliveries = ScreenCaptureDeliveryDropStats(
                byConsumerBusy = 0L,
                byCallbackFailure = 0L,
            ),
            averageProducedFps = 0.0,
            averageEncodeMs = 0.0,
            averageReadbackMs = 0.0,
            lastEncodedByteCount = 0,
            averageEncodedByteCount = 0,
        )
    }
}
