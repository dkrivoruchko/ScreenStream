package io.screenstream.capture.internal.session

import io.screenstream.capture.ScreenCaptureDiagnosticEvent
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStats
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SessionObservationPublisher(private val currentEpochMillis: () -> Long) {
    internal class DiagnosticRequest(
        internal val source: String,
        internal val eventName: String,
        internal val message: String,
        internal val cause: Throwable?,
    ) {
        init {
            require(source.isNotEmpty())
            require(eventName.isNotEmpty())
            require((message.isNotEmpty()) && (message.length <= MAX_DIAGNOSTIC_MESSAGE_LENGTH))
        }
    }

    private val diagnosticGate = Any()
    private var diagnosticSequence = 0L
    private val mutableState = MutableStateFlow<ScreenCaptureState>(ScreenCaptureState.NotStarted)
    private val mutableStats = MutableStateFlow(ScreenCaptureStats.EMPTY)
    private val mutableDiagnosticEvents = MutableSharedFlow<ScreenCaptureDiagnosticEvent>(
        replay = 0,
        extraBufferCapacity = DIAGNOSTIC_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal val state: StateFlow<ScreenCaptureState> = mutableState.asStateFlow()
    internal val stats: StateFlow<ScreenCaptureStats> = mutableStats.asStateFlow()
    internal val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent> = mutableDiagnosticEvents.asSharedFlow()

    internal fun publishState(state: ScreenCaptureState) {
        mutableState.value = state
    }

    internal fun publishStats(stats: ScreenCaptureStats) {
        mutableStats.value = stats
    }

    internal fun tryPublishDiagnostic(request: DiagnosticRequest) {
        val sequence = synchronized(diagnosticGate) {
            if (diagnosticSequence == Long.MAX_VALUE) return
            diagnosticSequence += 1L
            diagnosticSequence
        }
        try {
            mutableDiagnosticEvents.tryEmit(
                ScreenCaptureDiagnosticEvent.create(
                    sequence = sequence,
                    timestampEpochMillis = currentEpochMillis(),
                    source = request.source,
                    eventName = request.eventName,
                    message = request.message,
                    cause = request.cause,
                ),
            )
        } catch (_: Exception) {
        }
    }

    internal fun publishTerminal(finalStats: ScreenCaptureStats, diagnosticRequest: DiagnosticRequest?, terminalState: ScreenCaptureState) {
        publishStats(finalStats)
        diagnosticRequest?.let(::tryPublishDiagnostic)
        publishState(terminalState)
    }

    private companion object {
        private const val MAX_DIAGNOSTIC_MESSAGE_LENGTH: Int = 224
        private const val DIAGNOSTIC_BUFFER_CAPACITY: Int = 128
    }
}
