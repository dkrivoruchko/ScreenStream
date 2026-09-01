package io.screenstream.capture.internal.session

import io.screenstream.capture.ScreenCaptureDeliveryDropStats
import io.screenstream.capture.ScreenCaptureDiagnosticEvent
import io.screenstream.capture.ScreenCaptureFrameDropStats
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

internal class SessionObservationPublisherLifecycleTest {
    // Verification: OBS-01
    @Test
    fun diagnosticsHaveNoReplayAndPublishPositiveIncreasingSequencesWithExactFields() = runTest {
        var nextEpochMillis = 100L
        val publisher = SessionObservationPublisher { nextEpochMillis++ }
        publisher.tryPublishDiagnostic(
            diagnosticRequest(source = "Before", eventName = "BeforeSubscription", message = "not replayed"),
        )
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            publisher.diagnosticEvents.take(2).toList()
        }
        val cause = IllegalStateException("opaque diagnostic cause")

        publisher.tryPublishDiagnostic(
            diagnosticRequest(source = "Capture", eventName = "First", message = "first live", cause = cause),
        )
        publisher.tryPublishDiagnostic(
            diagnosticRequest(source = "Encoding", eventName = "Second", message = "second live"),
        )
        val events = collected.await()

        assertEquals(2, events.size)
        val first = events[0]
        val second = events[1]
        assertTrue(first.sequence > 0L)
        assertEquals(first.sequence + 1L, second.sequence)
        assertEquals(101L, first.timestampEpochMillis)
        assertEquals("Capture", first.source)
        assertEquals("First", first.eventName)
        assertEquals("first live", first.message)
        assertSame(cause, first.cause)
        assertEquals(102L, second.timestampEpochMillis)
        assertEquals("Encoding", second.source)
        assertEquals("Second", second.eventName)
        assertEquals("second live", second.message)
        assertNull(second.cause)
    }

    // Verification: OBS-01
    @Test
    fun slowCollectorKeepsNewestTerminalEventWithoutBlocking() = runTest {
        val publisher = SessionObservationPublisher { 1L }
        val firstCallbackEntered = CompletableDeferred<Unit>()
        val releaseFirstCallback = CompletableDeferred<Unit>()
        val terminalEventObserved = CompletableDeferred<Unit>()
        val observedEvents = mutableListOf<ScreenCaptureDiagnosticEvent>()
        val finalEventName = "Terminal"
        val finalStats = stats(lastEncodedByteCount = 11, averageEncodedByteCount = 11)
        val initialStats = publisher.stats.value
        val initialState = publisher.state.value
        val terminalState = ScreenCaptureState.Stopped.create(
            reason = ScreenCaptureStopReason.Requested,
            requestedParameters = ScreenCaptureParameters.DEFAULT,
            lastEffectiveParameters = null,
        )
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.diagnosticEvents.collect { event ->
                observedEvents += event
                if (observedEvents.size == 1) {
                    firstCallbackEntered.complete(Unit)
                    releaseFirstCallback.await()
                }
                if (event.eventName == finalEventName) terminalEventObserved.complete(Unit)
            }
        }

        publisher.tryPublishDiagnostic(diagnosticRequest(eventName = "BlockingCollector"))
        firstCallbackEntered.await()
        val burstSize = 10_000
        repeat(burstSize) { index ->
            publisher.tryPublishDiagnostic(
                diagnosticRequest(eventName = "Burst-$index"),
            )
        }
        assertSame(initialStats, publisher.stats.value)
        assertSame(initialState, publisher.state.value)

        publisher.publishTerminal(
            finalStats = finalStats,
            diagnosticRequest = diagnosticRequest(eventName = finalEventName),
            terminalState = terminalState,
        )
        assertSame(finalStats, publisher.stats.value)
        assertSame(terminalState, publisher.state.value)

        releaseFirstCallback.complete(Unit)
        terminalEventObserved.await()
        collector.cancelAndJoin()

        val observedEventNames = observedEvents.map { it.eventName }
        assertEquals("BlockingCollector", observedEventNames.first())
        assertEquals(finalEventName, observedEventNames.last())
        val observedBurstIndexes = observedEventNames.drop(1).dropLast(1).map { eventName ->
            check(eventName.startsWith("Burst-")) { "Unexpected diagnostic event $eventName" }
            eventName.removePrefix("Burst-").toInt()
        }
        assertTrue(observedBurstIndexes.isNotEmpty())
        assertTrue(observedBurstIndexes.first() > 0)
        assertEquals(burstSize - 1, observedBurstIndexes.last())
        assertTrue(observedBurstIndexes.zipWithNext().all { (first, second) -> second == first + 1 })
        assertSame(finalStats, publisher.stats.value)
        assertSame(terminalState, publisher.state.value)
    }

    // Verification: SES-02
    // Verification: OBS-01
    @Test
    fun facadesAreStableAndTerminalInvokesStatsThenDiagnosticThenState() {
        val finalStats = stats(lastEncodedByteCount = 7, averageEncodedByteCount = 7)
        val terminalState = ScreenCaptureState.Stopped.create(
            reason = ScreenCaptureStopReason.Requested,
            requestedParameters = ScreenCaptureParameters.DEFAULT,
            lastEffectiveParameters = null,
        )
        lateinit var publisher: SessionObservationPublisher
        var diagnosticAttempted = false
        publisher = SessionObservationPublisher {
            assertSame(finalStats, publisher.stats.value)
            assertSame(ScreenCaptureState.Starting, publisher.state.value)
            diagnosticAttempted = true
            42L
        }
        val stateFacade = publisher.state
        val statsFacade = publisher.stats
        val diagnosticsFacade = publisher.diagnosticEvents
        publisher.publishState(ScreenCaptureState.Starting)

        publisher.publishTerminal(
            finalStats = finalStats,
            diagnosticRequest = SessionObservationPublisher.DiagnosticRequest(
                source = "Session",
                eventName = "Terminal",
                message = "terminal diagnostic",
                cause = null,
            ),
            terminalState = terminalState,
        )

        assertSame(stateFacade, publisher.state)
        assertSame(statsFacade, publisher.stats)
        assertSame(diagnosticsFacade, publisher.diagnosticEvents)
        assertSame(finalStats, publisher.stats.value)
        assertSame(terminalState, publisher.state.value)
        assertTrue(diagnosticAttempted)
    }

    // Verification: SES-02
    // Verification: OBS-01
    @Test
    fun caughtOrdinaryDiagnosticExceptionCannotBlockTerminalState() {
        val publisher = SessionObservationPublisher { throw IllegalStateException("ordinary diagnostic exception") }
        val finalStats = stats(lastEncodedByteCount = 3, averageEncodedByteCount = 3)
        val terminalState = ScreenCaptureState.Stopped.create(
            reason = ScreenCaptureStopReason.ProjectionStopped,
            requestedParameters = ScreenCaptureParameters.DEFAULT,
            lastEffectiveParameters = null,
        )
        publisher.publishState(ScreenCaptureState.Starting)

        publisher.publishTerminal(
            finalStats = finalStats,
            diagnosticRequest = SessionObservationPublisher.DiagnosticRequest(
                source = "Session",
                eventName = "Terminal",
                message = "terminal diagnostic",
                cause = null,
            ),
            terminalState = terminalState,
        )

        assertSame(finalStats, publisher.stats.value)
        assertSame(terminalState, publisher.state.value)
    }

    // Verification: OBS-01
    @Test
    fun diagnosticErrorEscapesAfterFinalStatsWhileStateStaysPrior() {
        val sentinel = SentinelDiagnosticError()
        val publisher = SessionObservationPublisher { throw sentinel }
        val finalStats = stats(lastEncodedByteCount = 5, averageEncodedByteCount = 5)
        val priorState = ScreenCaptureState.Starting
        val terminalState = ScreenCaptureState.Stopped.create(
            reason = ScreenCaptureStopReason.Requested,
            requestedParameters = ScreenCaptureParameters.DEFAULT,
            lastEffectiveParameters = null,
        )
        publisher.publishState(priorState)

        val escaped = assertThrows(SentinelDiagnosticError::class.java) {
            publisher.publishTerminal(
                finalStats = finalStats,
                diagnosticRequest = SessionObservationPublisher.DiagnosticRequest(
                    source = "Session",
                    eventName = "Terminal",
                    message = "terminal diagnostic",
                    cause = null,
                ),
                terminalState = terminalState,
            )
        }

        assertSame(sentinel, escaped)
        assertSame(finalStats, publisher.stats.value)
        assertSame(priorState, publisher.state.value)
    }

    private fun stats(
        lastEncodedByteCount: Int,
        averageEncodedByteCount: Int,
    ): ScreenCaptureStats = ScreenCaptureStats.create(
        encodedFrameCount = 1L,
        producedFrameCount = 0L,
        droppedFrames = ScreenCaptureFrameDropStats.create(byStaleWork = 0L, byFailure = 0L),
        droppedDeliveries = ScreenCaptureDeliveryDropStats.create(byConsumerBusy = 0L, byCallbackFailure = 0L),
        averageProducedFps = 0.0,
        averageEncodingDuration = Duration.ZERO,
        averageReadbackDuration = Duration.ZERO,
        lastEncodedByteCount = lastEncodedByteCount,
        averageEncodedByteCount = averageEncodedByteCount,
    )

    private fun diagnosticRequest(
        source: String = "Session",
        eventName: String,
        message: String = "diagnostic",
        cause: Throwable? = null,
    ): SessionObservationPublisher.DiagnosticRequest = SessionObservationPublisher.DiagnosticRequest(
        source = source,
        eventName = eventName,
        message = message,
        cause = cause,
    )

    private class SentinelDiagnosticError : Error()
}
