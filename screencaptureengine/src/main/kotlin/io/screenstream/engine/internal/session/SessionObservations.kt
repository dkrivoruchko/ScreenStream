package io.screenstream.engine.internal.session

import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStats
import io.screenstream.engine.internal.observation.DiagnosticRequest
import io.screenstream.engine.internal.observation.ObservationPublisher
import io.screenstream.engine.internal.observation.ObservationTerminalPublication
import io.screenstream.engine.internal.observation.StatsProjection
import io.screenstream.engine.internal.observation.StatsSnapshotBuilder
import io.screenstream.engine.internal.observation.TerminalCleanupSink
import io.screenstream.engine.internal.runtime.WallClock
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Session-facing facade over the direct unlocked observation publisher. */
internal class SessionObservations internal constructor(
    wallClock: WallClock,
) {
    private val publisher = ObservationPublisher(wallClock, zeroStats())

    internal val terminalCleanupSink: TerminalCleanupSink
        get() = publisher.terminalCleanupSink
    internal val state: StateFlow<ScreenCaptureState> = publisher.state
    internal val stats: StateFlow<ScreenCaptureStats> = publisher.stats
    internal val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent> = publisher.diagnosticEvents

    internal fun publishStarting() {
        publisher.assignState(ScreenCaptureState.Starting)
    }

    internal fun publishState(state: ScreenCaptureState) {
        publisher.assignState(state)
    }

    internal fun publishStats(stats: ScreenCaptureStats) {
        publisher.assignStats(stats)
    }

    internal fun emitDiagnostic(request: DiagnosticRequest) {
        publisher.tryEmitDiagnostic(request)
    }

    internal fun publishTerminal(publication: TerminalPublication) {
        publisher.publishTerminal(
            ObservationTerminalPublication(
                finalStats = publication.finalStats,
                terminalDiagnostic = DiagnosticRequest(
                    source = "Session",
                    label = "SessionTerminal",
                    message = publication.diagnosticMessage,
                    cause = publication.cause,
                ),
                terminalState = publication.terminalState,
            ),
        )
    }

}

internal class TerminalPublication internal constructor(
    internal val finalStats: ScreenCaptureStats,
    internal val diagnosticMessage: String,
    internal val cause: Throwable?,
    internal val terminalState: ScreenCaptureState,
)

internal fun zeroStats(): ScreenCaptureStats = StatsSnapshotBuilder.build(StatsProjection.Zero)
