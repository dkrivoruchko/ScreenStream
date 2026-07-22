package io.screenstream.engine.internal.delivery

import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStats
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unlocked construction and Flow-publication leaf.
 *
 * The Session aggregate supplies already-authoritative immutable snapshots and owns publication eligibility,
 * cadence, terminal cutoff, and ordering. This owner deliberately retains no lifecycle or accounting state.
 */
internal class ObservationOwner {
    private val mutableState = MutableStateFlow<ScreenCaptureState>(ScreenCaptureState.NotStarted)
    private val mutableStats = MutableStateFlow(ObservationStatsSnapshot.Zero.toPublicStats())
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

    private companion object {
        private const val DIAGNOSTIC_BUFFER_CAPACITY: Int = 128
    }
}
