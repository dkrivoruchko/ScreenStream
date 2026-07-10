package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.internal.control.ControllerCancellationMarkerRevision
import dev.dmkr.screencaptureengine.internal.control.ControllerMetricsAttachmentIdentity
import dev.dmkr.screencaptureengine.internal.control.ControllerOperationIdentity
import dev.dmkr.screencaptureengine.internal.control.SessionIdentity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class SessionMetricsCollectorTest {
    @Test
    fun getterCollectSinkCancellationAndBarrierRunOnlyOnTheSerialSessionLane() = runTest {
        val active = AtomicBoolean()
        val executions = AtomicInteger()
        val dispatcher = MarkingDispatcher(StandardTestDispatcher(testScheduler), active, executions)
        val state = CaptureMetricsState.Available(CaptureMetrics(6, 7, 8))
        val flow = CompletingStateFlow(state) { assertTrue(active.get()) }
        val provider = RecordingProvider(flow.typed()) { assertTrue(active.get()) }
        val facts = mutableListOf<SessionMetricsFact>()
        val collector = SessionMetricsCollector.start(provider, dispatcher, tag) { fact ->
            assertTrue(active.get())
            facts += fact
        }
        runCurrent()

        assertEquals(1, provider.getterCount)
        assertEquals(1, flow.collectCount)
        assertTrue(facts.single() is SessionMetricsFact.CollectionCompleted)
        val beforeCancellation = executions.get()
        collector.requestCancellation(ControllerCancellationMarkerRevision(12))
        assertEquals(beforeCancellation + 1, executions.get())
        assertTrue(facts.last() is SessionMetricsFact.CancellationMarked)
        val beforeMismatch = executions.get()
        val mismatch = runCatching {
            collector.proveSessionBarrier(SessionMetricsBarrierProof(SessionIdentity(99), tag.attachment))
        }
        assertTrue(mismatch.exceptionOrNull() is IllegalArgumentException)
        assertEquals(beforeMismatch + 1, executions.get())
        val beforeBarrier = executions.get()
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertEquals(beforeBarrier + 1, executions.get())
        assertAllTagsSame(facts)
    }

    @Test
    fun getterRunsExactlyOnceOnInjectedDispatcherAndInitialReplayIsCopied() = runTest {
        val marker = AtomicBoolean()
        val dispatcher = MarkingDispatcher(StandardTestDispatcher(testScheduler), marker, AtomicInteger())
        val state = CaptureMetricsState.Available(CaptureMetrics(1080, 1920, 440))
        val provider = RecordingProvider(MutableStateFlow(state)) { assertTrue(marker.get()) }
        val facts = mutableListOf<SessionMetricsFact>()

        val collector = SessionMetricsCollector.start(provider, dispatcher, tag, facts::add)
        assertEquals(0, provider.getterCount)
        runCurrent()

        assertEquals(1, provider.getterCount)
        assertEquals(
            SessionMetricsFact.Available(tag, widthPx = 1080, heightPx = 1920, densityDpi = 440),
            facts.single(),
        )
        assertAllTagsSame(facts)
        retire(collector, marker = 1)
    }

    @Test
    fun everyAvailableAndUnavailableEmissionIsCopied() = runTest {
        val flow = MutableStateFlow<CaptureMetricsState>(CaptureMetricsState.Available(CaptureMetrics(1, 2, 3)))
        val provider = RecordingProvider(flow)
        val facts = mutableListOf<SessionMetricsFact>()
        val collector = SessionMetricsCollector.start(
            provider,
            StandardTestDispatcher(testScheduler),
            tag,
            facts::add,
        )
        runCurrent()

        flow.value = CaptureMetricsState.Available(CaptureMetrics(Int.MAX_VALUE, 7, 8))
        runCurrent()
        flow.value = CaptureMetricsState.Unavailable(
            CaptureMetricsUnavailableReason.SourceInvalid,
            "caller detail",
        )
        runCurrent()

        assertEquals(
            listOf(
                SessionMetricsFact.Available(tag, 1, 2, 3),
                SessionMetricsFact.Available(tag, Int.MAX_VALUE, 7, 8),
                SessionMetricsFact.Unavailable(
                    tag,
                    CaptureMetricsUnavailableReason.SourceInvalid,
                    "caller detail",
                ),
            ),
            facts,
        )
        assertEquals(1, provider.getterCount)
        assertAllTagsSame(facts)
        retire(collector, marker = 2)
    }

    @Test
    fun equalSamplesAreForwardedWithoutCollectorDeduplication() = runTest {
        val sample = CaptureMetricsState.Available(CaptureMetrics(9, 8, 7))
        val flow = DuplicateStateFlow(sample)
        val facts = mutableListOf<SessionMetricsFact>()

        val collector = SessionMetricsCollector.start(
            RecordingProvider(flow),
            StandardTestDispatcher(testScheduler),
            tag,
            facts::add,
        )
        runCurrent()

        assertEquals(
            listOf(
                SessionMetricsFact.Available(tag, 9, 8, 7),
                SessionMetricsFact.Available(tag, 9, 8, 7),
            ),
            facts,
        )
        assertEquals(1, flow.collectCount)
        assertAllTagsSame(facts)
        retire(collector, marker = 3)
    }

    @Test
    fun neutralAvailableFactPreservesInvalidScalarsForControllerClassification() {
        val fact = SessionMetricsFact.Available(tag, widthPx = 0, heightPx = -1, densityDpi = Int.MIN_VALUE)

        assertEquals(0, fact.widthPx)
        assertEquals(-1, fact.heightPx)
        assertEquals(Int.MIN_VALUE, fact.densityDpi)
    }

    @Test
    fun getterThrowAndCollectionThrowRetainExactCauses() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val getterFailure = IllegalStateException("getter")
        val getterFacts = mutableListOf<SessionMetricsFact>()
        val throwingProvider = object : CaptureMetricsProvider {
            var getterCount = 0
            override val metrics: StateFlow<CaptureMetricsState>
                get() {
                    getterCount++
                    throw getterFailure
                }
        }
        val getterCollector = SessionMetricsCollector.start(throwingProvider, dispatcher, tag, getterFacts::add)
        runCurrent()

        val getterFact = getterFacts.single() as SessionMetricsFact.GetterThrew
        assertSame(tag, getterFact.tag)
        assertSame(getterFailure, getterFact.cause)
        assertEquals(1, throwingProvider.getterCount)
        retire(getterCollector, marker = 4)

        val collectionFailure = IllegalArgumentException("collect")
        val failingFlow = ThrowingStateFlow(
            CaptureMetricsState.Available(CaptureMetrics(4, 5, 6)),
            collectionFailure,
        )
        val collectionFacts = mutableListOf<SessionMetricsFact>()
        val collectionCollector = SessionMetricsCollector.start(
            RecordingProvider(failingFlow),
            dispatcher,
            tag,
            collectionFacts::add,
        )
        runCurrent()

        assertEquals(1, failingFlow.collectCount)
        assertTrue(collectionFacts.first() is SessionMetricsFact.Available)
        assertSame(collectionFailure, (collectionFacts.last() as SessionMetricsFact.CollectionThrew).cause)
        assertAllTagsSame(collectionFacts)
        retire(collectionCollector, marker = 5)
    }

    @Test
    fun normalCompletionIsASeparateMechanicalFact() = runTest {
        val flow = CompletingStateFlow(CaptureMetricsState.Available(CaptureMetrics(4, 5, 6)))
        val facts = mutableListOf<SessionMetricsFact>()

        val collector = SessionMetricsCollector.start(
            RecordingProvider(flow.typed()),
            StandardTestDispatcher(testScheduler),
            tag,
            facts::add,
        )
        runCurrent()

        assertEquals(1, flow.collectCount)
        assertEquals(SessionMetricsFact.CollectionCompleted(tag), facts.single())
        assertAllTagsSame(facts)
        retire(collector, marker = 6)
    }

    @Test
    fun cancellationMarkerIsDeliveredBeforeCancelAndAfterAnAlreadyReturnedFact() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failure = CancellationException("provider-owned cancellation")
        val flow = ThrowingStateFlow(
            CaptureMetricsState.Available(CaptureMetrics(4, 5, 6)),
            failure,
        )
        val facts = mutableListOf<SessionMetricsFact>()
        val collector = SessionMetricsCollector.start(RecordingProvider(flow), dispatcher, tag, facts::add)
        runCurrent()

        collector.requestCancellation(ControllerCancellationMarkerRevision(9))
        runCurrent()

        assertSame(failure, (facts[1] as SessionMetricsFact.CollectionThrew).cause)
        assertEquals(
            SessionMetricsFact.CancellationMarked(tag, ControllerCancellationMarkerRevision(9)),
            facts[2],
        )
        assertAllTagsSame(facts)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
    }

    @Test
    fun cancellationMarkerBeforeReturnIsReportedWithoutClassifyingTheExactCause() = runTest {
        val flow = MutableStateFlow<CaptureMetricsState>(
            CaptureMetricsState.Available(CaptureMetrics(4, 5, 6)),
        )
        val facts = mutableListOf<SessionMetricsFact>()
        val collector = SessionMetricsCollector.start(
            RecordingProvider(flow),
            StandardTestDispatcher(testScheduler),
            tag,
            facts::add,
        )
        runCurrent()

        collector.requestCancellation(ControllerCancellationMarkerRevision(10))
        runCurrent()

        assertEquals(
            SessionMetricsFact.CancellationMarked(tag, ControllerCancellationMarkerRevision(10)),
            facts[1],
        )
        val returned = facts[2] as SessionMetricsFact.CollectionThrew
        assertTrue(returned.cause is CancellationException)
        assertAllTagsSame(facts)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
    }

    @Test
    fun noncooperativeLateEmissionIsFencedAndProviderIsNeverClosed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flow = LateAfterCancellationStateFlow(
            value = CaptureMetricsState.Available(CaptureMetrics(10, 20, 30)),
            late = CaptureMetricsState.Available(CaptureMetrics(40, 50, 60)),
            gate = gate,
        )
        val provider = CloseTrackingProvider(flow)
        val facts = mutableListOf<SessionMetricsFact>()
        val collector = SessionMetricsCollector.start(
            provider,
            StandardTestDispatcher(testScheduler),
            tag,
            facts::add,
        )
        runCurrent()

        collector.requestCancellation(ControllerCancellationMarkerRevision(1))
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertEquals(
            SessionMetricsCollector.TestSnapshot(
                cancellationMarked = true,
                collectionReturned = false,
                barrierProved = true,
                sourceReferencesRetained = true,
                sourceReleaseCount = 0,
            ),
            collector.testSnapshot(),
        )
        assertEquals(
            listOf(
                SessionMetricsFact.Available(tag, 10, 20, 30),
                SessionMetricsFact.CancellationMarked(tag, ControllerCancellationMarkerRevision(1)),
            ),
            facts,
        )
        gate.complete(Unit)
        runCurrent()

        assertEquals(
            listOf(
                SessionMetricsFact.Available(tag, 10, 20, 30),
                SessionMetricsFact.CancellationMarked(tag, ControllerCancellationMarkerRevision(1)),
            ),
            facts,
        )
        assertEquals(1, flow.collectCount)
        assertFalse(provider.closed)
        assertAllTagsSame(facts)
        assertEquals(1, collector.testSnapshot().sourceReleaseCount)
        assertFalse(collector.testSnapshot().sourceReferencesRetained)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertEquals(1, collector.testSnapshot().sourceReleaseCount)
    }

    @Test
    fun synchronousSinkProviderReentryIsSerializedAndMismatchedBarrierIsRejected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val facts = mutableListOf<SessionMetricsFact>()
        val flow = MutableStateFlow<CaptureMetricsState>(
            CaptureMetricsState.Available(CaptureMetrics(1, 1, 1)),
        )
        val collector = SessionMetricsCollector.start(
            RecordingProvider(flow),
            dispatcher,
            tag,
        ) { fact ->
            facts += fact
            if (fact is SessionMetricsFact.Available) {
                flow.value = CaptureMetricsState.Unavailable(CaptureMetricsUnavailableReason.SourceNotReady)
            }
        }
        runCurrent()

        assertTrue(facts[0] is SessionMetricsFact.Available)
        assertTrue(facts[1] is SessionMetricsFact.Unavailable)
        collector.requestCancellation(ControllerCancellationMarkerRevision(2))
        val mismatch = runCatching {
            collector.proveSessionBarrier(
                SessionMetricsBarrierProof(SessionIdentity(99), tag.attachment),
            )
        }
        assertTrue(mismatch.exceptionOrNull() is IllegalArgumentException)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertAllTagsSame(facts)
    }

    @Test
    fun returnedCollectorRetainsSourceUntilBarrierThenReleasesExactlyOnce() = runTest {
        val failure = IllegalStateException("getter")
        val provider = object : CaptureMetricsProvider {
            override val metrics: StateFlow<CaptureMetricsState>
                get() = throw failure
        }
        val collector = SessionMetricsCollector.start(
            provider,
            StandardTestDispatcher(testScheduler),
            tag,
        ) {}
        runCurrent()

        assertEquals(
            SessionMetricsCollector.TestSnapshot(
                cancellationMarked = false,
                collectionReturned = true,
                barrierProved = false,
                sourceReferencesRetained = true,
                sourceReleaseCount = 0,
            ),
            collector.testSnapshot(),
        )
        collector.requestCancellation(ControllerCancellationMarkerRevision(14))
        assertTrue(collector.testSnapshot().sourceReferencesRetained)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertFalse(collector.testSnapshot().sourceReferencesRetained)
        assertEquals(1, collector.testSnapshot().sourceReleaseCount)
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
        assertEquals(1, collector.testSnapshot().sourceReleaseCount)
    }

    private suspend fun retire(collector: SessionMetricsCollector, marker: Long) {
        collector.requestCancellation(ControllerCancellationMarkerRevision(marker))
        collector.proveSessionBarrier(SessionMetricsBarrierProof(tag.session, tag.attachment))
    }

    private fun assertAllTagsSame(facts: List<SessionMetricsFact>) {
        facts.forEach { fact -> assertSame(tag, fact.tag) }
    }

    private class RecordingProvider(
        private val flow: StateFlow<CaptureMetricsState>,
        private val onGet: () -> Unit = {},
    ) : CaptureMetricsProvider {
        var getterCount = 0
            private set

        override val metrics: StateFlow<CaptureMetricsState>
            get() {
                getterCount++
                onGet()
                return flow
            }
    }

    private class CloseTrackingProvider(
        private val flow: StateFlow<CaptureMetricsState>,
    ) : CaptureMetricsProvider, AutoCloseable {
        var closed = false
            private set

        override val metrics: StateFlow<CaptureMetricsState>
            get() = flow

        override fun close() {
            closed = true
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
    private class ThrowingStateFlow(
        override val value: CaptureMetricsState,
        private val failure: Throwable,
    ) : StateFlow<CaptureMetricsState> {
        var collectCount = 0
            private set
        override val replayCache: List<CaptureMetricsState> = listOf(value)

        override suspend fun collect(collector: FlowCollector<CaptureMetricsState>): Nothing {
            collectCount++
            collector.emit(value)
            throw failure
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
    private class LateAfterCancellationStateFlow(
        override val value: CaptureMetricsState,
        private val late: CaptureMetricsState,
        private val gate: CompletableDeferred<Unit>,
    ) : StateFlow<CaptureMetricsState> {
        var collectCount = 0
            private set
        override val replayCache: List<CaptureMetricsState> = listOf(value)

        override suspend fun collect(collector: FlowCollector<CaptureMetricsState>): Nothing {
            collectCount++
            collector.emit(value)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    gate.await()
                    collector.emit(late)
                }
                throw CancellationException("late return")
            }
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
    private class DuplicateStateFlow(
        override val value: CaptureMetricsState,
    ) : StateFlow<CaptureMetricsState> {
        var collectCount = 0
            private set
        override val replayCache: List<CaptureMetricsState> = listOf(value)

        override suspend fun collect(collector: FlowCollector<CaptureMetricsState>): Nothing {
            collectCount++
            collector.emit(value)
            collector.emit(value)
            awaitCancellation()
        }
    }

    private class MarkingDispatcher(
        private val delegate: CoroutineDispatcher,
        private val marker: AtomicBoolean,
        private val executions: AtomicInteger,
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                check(marker.compareAndSet(false, true))
                executions.incrementAndGet()
                try {
                    block.run()
                } finally {
                    check(marker.compareAndSet(true, false))
                }
            }
        }
    }

    private companion object {
        val tag = SessionMetricsFactTag(
            session = SessionIdentity(7),
            attachment = ControllerMetricsAttachmentIdentity(8),
            operation = ControllerOperationIdentity(9),
        )
    }
}
