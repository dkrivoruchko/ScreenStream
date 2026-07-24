package io.screenstream.engine.internal.observation

import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStats
import io.screenstream.engine.internal.runtime.WallClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DiagnosticRequest internal constructor(
    internal val source: String,
    internal val label: String,
    internal val message: String,
    internal val cause: Throwable?,
) {
    init {
        require(source.isNotEmpty())
        require(label.isNotEmpty())
        require(message.isNotEmpty() && message.length <= 224)
    }
}

internal class ObservationTerminalPublication internal constructor(
    internal val finalStats: ScreenCaptureStats,
    internal val terminalDiagnostic: DiagnosticRequest,
    internal val terminalState: ScreenCaptureState,
)

/** Direct unlocked State/Stats/diagnostic publication; there is no observation execution lane. */
internal class ObservationPublisher internal constructor(
    wallClock: WallClock,
    initialStats: ScreenCaptureStats,
) {
    private val mutableState = MutableStateFlow<ScreenCaptureState>(ScreenCaptureState.NotStarted)
    private val mutableStats = MutableStateFlow(initialStats)
    private val mutableDiagnostics = MutableSharedFlow<ScreenCaptureDiagnosticEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal val terminalCleanupSink: TerminalCleanupSink =
        TerminalCleanupSink(wallClock, mutableDiagnostics)

    internal val state: StateFlow<ScreenCaptureState> = mutableState.asStateFlow()
    internal val stats: StateFlow<ScreenCaptureStats> = mutableStats.asStateFlow()
    internal val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent> = mutableDiagnostics.asSharedFlow()

    internal fun assignState(value: ScreenCaptureState) {
        mutableState.value = value
    }

    internal fun assignStats(value: ScreenCaptureStats) {
        mutableStats.value = value
    }

    internal fun tryEmitDiagnostic(request: DiagnosticRequest) {
        terminalCleanupSink.tryOrdinary(request)
    }

    /** One contiguous terminal prefix; diagnostic failure cannot suppress terminal State or the mode switch. */
    internal fun publishTerminal(publication: ObservationTerminalPublication) {
        mutableStats.value = publication.finalStats
        try {
            terminalCleanupSink.tryTerminal(publication.terminalDiagnostic)
        } catch (_: Exception) {
            // Diagnostics are best-effort observation only.
        } catch (_: OutOfMemoryError) {
            // Terminal State and routing must still publish under allocation pressure.
        } finally {
            try {
                mutableState.value = publication.terminalState
            } finally {
                terminalCleanupSink.switchToTerminalCleanupOnly()
            }
        }
    }
}
