package io.screenstream.engine.internal.delivery

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ScreenCaptureDeliveryDropStats
import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureFrameDropStats
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStats
import io.screenstream.engine.ScreenCaptureStopReason

/** One already-committed public State value. No wrapper Running state is retained here. */
internal sealed interface ObservationStateSnapshot {
    internal object Starting : ObservationStateSnapshot

    internal class Active(
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
        internal val capturedContentVisible: Boolean?,
    ) : ObservationStateSnapshot

    internal class Reconfiguring(
        internal val requestedParameters: ScreenCaptureParameters,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val lastKnownCaptureGeometry: CaptureGeometry?,
        internal val capturedContentVisible: Boolean?,
    ) : ObservationStateSnapshot

    internal class Suspended(
        internal val requestedParameters: ScreenCaptureParameters,
        internal val problem: ScreenCaptureProblem,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val lastKnownCaptureGeometry: CaptureGeometry?,
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

internal class ObservationStatsSnapshot(
    internal val framesEncoded: Long,
    internal val framesProduced: Long,
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
) {
    internal companion object {
        internal val Zero: ObservationStatsSnapshot = ObservationStatsSnapshot(
            framesEncoded = 0L,
            framesProduced = 0L,
            frameDropsByPipelineBusy = 0L,
            frameDropsByStaleWork = 0L,
            frameDropsByFailure = 0L,
            deliveryDropsByConsumerBusy = 0L,
            deliveryDropsByCallbackFailure = 0L,
            averageProducedFps = 0.0,
            averageEncodeMs = 0.0,
            averageReadbackMs = 0.0,
            lastEncodedByteCount = 0,
            averageEncodedByteCount = 0,
        )
    }
}

internal class ObservationDiagnosticRequest(
    internal val sequence: Long,
    internal val timestampEpochMillis: Long,
    internal val source: String,
    internal val label: String,
    internal val site: ObservationDiagnosticSite,
    internal val payload: ObservationDiagnosticPayload,
    internal val cause: Throwable?,
)

/** Closed engine site vocabulary; public source and label remain aggregate-selected extensible strings. */
internal enum class ObservationDiagnosticSite {
    CapabilityBoundary,
    MetricsCompletion,
    InitialRuntimeProfile,
    RuntimeModeChange,
    CallbackFailure,
    StatsProtection,
    ColorClassification,
    QuarantineMutation,
    TerminalWinner,
}

/** Throwable-free semantic inputs from which the observation leaf constructs the noncontractual short message. */
internal sealed interface ObservationDiagnosticPayload {
    internal class Decision(
        internal val boundary: String,
        internal val decision: String,
        internal val action: String,
    ) : ObservationDiagnosticPayload

    internal class RuntimeProfile(
        internal val targetMode: String,
        internal val jpegMode: String,
        internal val colorMode: String,
    ) : ObservationDiagnosticPayload

    internal class ModeChange(
        internal val axis: String,
        internal val previous: String,
        internal val current: String,
        internal val action: String,
    ) : ObservationDiagnosticPayload

    internal class Action(
        internal val subject: String,
        internal val action: String,
    ) : ObservationDiagnosticPayload

    internal class Terminal(
        internal val outcome: String,
        internal val targetMode: String?,
        internal val jpegMode: String?,
    ) : ObservationDiagnosticPayload
}

internal fun ObservationStateSnapshot.toPublicState(): ScreenCaptureState = when (this) {
    ObservationStateSnapshot.Starting -> ScreenCaptureState.Starting
    is ObservationStateSnapshot.Active -> ScreenCaptureState.Active.create(
        effectiveParameters = effectiveParameters,
        capturedContentVisible = capturedContentVisible,
    )

    is ObservationStateSnapshot.Reconfiguring -> ScreenCaptureState.Reconfiguring.create(
        requestedParameters = requestedParameters,
        lastEffectiveParameters = lastEffectiveParameters,
        lastKnownCaptureGeometry = lastKnownCaptureGeometry,
        capturedContentVisible = capturedContentVisible,
    )

    is ObservationStateSnapshot.Suspended -> ScreenCaptureState.Suspended.create(
        requestedParameters = requestedParameters,
        problem = problem,
        lastEffectiveParameters = lastEffectiveParameters,
        lastKnownCaptureGeometry = lastKnownCaptureGeometry,
        capturedContentVisible = capturedContentVisible,
    )

    is ObservationStateSnapshot.Stopped -> ScreenCaptureState.Stopped.create(
        reason = reason,
        requestedParameters = requestedParameters,
        lastEffectiveParameters = lastEffectiveParameters,
    )

    is ObservationStateSnapshot.Failed -> ScreenCaptureState.Failed.create(
        problem = problem,
        requestedParameters = requestedParameters,
        lastEffectiveParameters = lastEffectiveParameters,
    )
}

internal fun ObservationStatsSnapshot.toPublicStats(): ScreenCaptureStats = ScreenCaptureStats.create(
    framesEncoded = framesEncoded,
    framesProduced = framesProduced,
    droppedFrames = ScreenCaptureFrameDropStats.create(
        byPipelineBusy = frameDropsByPipelineBusy,
        byStaleWork = frameDropsByStaleWork,
        byFailure = frameDropsByFailure,
    ),
    droppedDeliveries = ScreenCaptureDeliveryDropStats.create(
        byConsumerBusy = deliveryDropsByConsumerBusy,
        byCallbackFailure = deliveryDropsByCallbackFailure,
    ),
    averageProducedFps = averageProducedFps,
    averageEncodeMs = averageEncodeMs,
    averageReadbackMs = averageReadbackMs,
    lastEncodedByteCount = lastEncodedByteCount,
    averageEncodedByteCount = averageEncodedByteCount,
)

internal fun ObservationDiagnosticRequest.toPublicEvent(): ScreenCaptureDiagnosticEvent =
    ScreenCaptureDiagnosticEvent.create(
        sequence = sequence,
        timestampEpochMillis = timestampEpochMillis,
        source = source,
        label = label,
        message = buildDiagnosticMessage(site, payload),
        cause = cause,
    )

private fun buildDiagnosticMessage(
    site: ObservationDiagnosticSite,
    payload: ObservationDiagnosticPayload,
): String {
    val semantics = when (payload) {
        is ObservationDiagnosticPayload.Decision ->
            "${payload.boundary}: ${payload.decision}; ${payload.action}"

        is ObservationDiagnosticPayload.RuntimeProfile ->
            "target=${payload.targetMode}; jpeg=${payload.jpegMode}; color=${payload.colorMode}"

        is ObservationDiagnosticPayload.ModeChange ->
            "${payload.axis}: ${payload.previous} -> ${payload.current}; ${payload.action}"

        is ObservationDiagnosticPayload.Action -> "${payload.subject}: ${payload.action}"
        is ObservationDiagnosticPayload.Terminal -> buildString {
            append(payload.outcome)
            payload.targetMode?.let { append("; target=").append(it) }
            payload.jpegMode?.let { append("; jpeg=").append(it) }
        }
    }
    return "${site.name}: ${semantics.compactDiagnosticToken()}".take(MAX_DIAGNOSTIC_MESSAGE_LENGTH)
}

private fun String.compactDiagnosticToken(): String =
    trim().replace(DIAGNOSTIC_WHITESPACE, " ").take(MAX_DIAGNOSTIC_SEMANTICS_LENGTH)

private val DIAGNOSTIC_WHITESPACE: Regex = Regex("\\s+")
private const val MAX_DIAGNOSTIC_SEMANTICS_LENGTH: Int = 192
private const val MAX_DIAGNOSTIC_MESSAGE_LENGTH: Int = 224
