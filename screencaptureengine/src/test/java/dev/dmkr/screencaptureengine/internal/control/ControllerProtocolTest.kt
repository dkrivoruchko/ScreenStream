package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProtocolTest {
    @Test
    fun identityDomainsAreDistinctPositiveTypes() {
        assertEquals(1L, ControllerIdentity(1).value)
        assertEquals(1L, SessionIdentity(1).value)
        assertEquals(1L, TransactionIdentity(1).value)
        assertEquals(1L, CompleteOwnerIdentity(1).value)
        assertEquals(1L, TargetIdentity(1).value)
        assertEquals(1L, IngressSequence(1).value)
        assertEquals(1L, ReasonToken(1).value)
        assertEquals(1L, ReconfigurationIdentity(1).value)
        assertEquals(1L, CandidateIdentity(1).value)
        assertEquals(1L, DesiredSnapshotIdentity(1).value)
        assertEquals(1L, EffectiveSnapshotIdentity(1).value)
        listOf<() -> Unit>(
            { ControllerIdentity(0) },
            { SessionIdentity(0) },
            { TransactionIdentity(0) },
            { ReconfigurationIdentity(0) },
            { CandidateIdentity(0) },
            { CompleteOwnerIdentity(0) },
            { TargetIdentity(0) },
            { IngressSequence(0) },
            { ReasonToken(0) },
            { DesiredSnapshotIdentity(0) },
            { EffectiveSnapshotIdentity(0) },
        ).forEach { constructor -> assertTrue(runCatching(constructor).isFailure) }
    }

    @Test
    fun independentSlotsSurviveOneFinitePriorityOrderedSnapshot() {
        val store = ControllerIngressStore()
        val records = listOf(
            IngressAt(ControllerIngressPayload.Visibility(true), 8),
            IngressAt(ControllerIngressPayload.Metrics(MetricsEvidence(10, 20, 30)), 5),
            IngressAt(ControllerIngressPayload.CapturedResize(CapturedResizeEvidence(40, 50)), 4),
            IngressAt(ControllerIngressPayload.SourceTrust(SourceTrustEvidence.NotReady), 7),
            IngressAt(ControllerIngressPayload.Pause(true), 3),
            IngressAt(ControllerIngressPayload.Pause(false), 6),
            IngressAt(ControllerIngressPayload.Terminal(terminalCauseForTest(TerminalEvidence.OwnerStopped)), 2),
            IngressAt(ControllerIngressPayload.Terminal(terminalCauseForTest(TerminalEvidence.ProjectionStopped)), 1),
        )
        records.forEach { store.accept(it.payload, it.sequence) }

        val snapshot = store.snapshot()

        assertEquals(records.size - 1, snapshot.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L), snapshot.map { it.sequence.value })
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun latestSlotsConflateStormIntoOneWakeAndKeepLatest() {
        val store = ControllerIngressStore()
        assertEquals(
            ControllerWakeIntent.Schedule,
            store.accept(ControllerIngressPayload.Metrics(MetricsEvidence(1, 1, 1)), 1),
        )
        repeat(100) { index ->
            assertEquals(
                ControllerWakeIntent.AlreadyScheduled,
                store.accept(
                    ControllerIngressPayload.Metrics(MetricsEvidence(index + 2, index + 2, index + 2)),
                    index + 2L,
                ),
            )
        }
        val source = store.snapshot().single() as ControllerIngress.Metrics
        assertEquals(MetricsEvidence(101, 101, 101), source.evidence)
    }

    @Test
    fun duplicatesAreIgnoredWithoutCreatingAnotherWake() {
        val store = ControllerIngressStore()
        var allocations = 0L
        fun offer(payload: ControllerIngressPayload): ControllerWakeIntent {
            val accepted = store.offer(payload) ?: return ControllerWakeIntent.Ignored
            allocations += 1
            return store.accept(accepted, IngressSequence(allocations))
        }

        val first = ControllerIngressPayload.Visibility(true)
        val duplicate = ControllerIngressPayload.Visibility(true)
        assertEquals(ControllerWakeIntent.Schedule, offer(first))
        assertEquals(ControllerWakeIntent.Ignored, offer(duplicate))
        assertEquals(1L, allocations)
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun everyTerminalSourceHasOneLosslessBoundedSlotAndWakeResetsAfterSnapshot() {
        val store = ControllerIngressStore()
        TerminalEvidence.entries.forEachIndexed { index, evidence ->
            val expected = if (index == 0) ControllerWakeIntent.Schedule else ControllerWakeIntent.AlreadyScheduled
            assertEquals(
                expected,
                store.accept(ControllerIngressPayload.Terminal(terminalCauseForTest(evidence, index + 1L)), index + 1L),
            )
            assertEquals(
                ControllerWakeIntent.Ignored,
                store.offer(ControllerIngressPayload.Terminal(terminalCauseForTest(evidence, 100L)))?.let {
                    store.accept(it, IngressSequence(index + 100L))
                } ?: ControllerWakeIntent.Ignored,
            )
        }
        assertEquals(TerminalEvidence.entries.size, store.snapshot().size)
        assertEquals(
            ControllerWakeIntent.Schedule,
            store.accept(
                ControllerIngressPayload.Terminal(terminalCauseForTest(TerminalEvidence.OwnerStopped)),
                500,
            ),
        )
    }

    @Test
    fun cancellationAndVisibilityHaveIndependentSlots() {
        val store = ControllerIngressStore()
        val transaction = TransactionIdentity(1)
        store.accept(ControllerIngressPayload.Cancellation(transaction), 1, transaction)
        store.accept(ControllerIngressPayload.Visibility(true), 2)
        assertEquals(
            listOf(ControllerIngress.Cancellation::class, ControllerIngress.Visibility::class),
            store.snapshot().map { it::class },
        )
    }

    @Test
    fun independentSourceTrustChannelsSurviveBothArrivalOrdersAndDrain() {
        listOf(
            listOf(SourceTrustEvidence.InvalidResize, SourceTrustEvidence.NotReady),
            listOf(SourceTrustEvidence.Invalid, SourceTrustEvidence.InvalidResize),
        ).forEach { evidenceOrder ->
            val store = ControllerIngressStore()
            evidenceOrder.forEachIndexed { index, evidence ->
                store.accept(ControllerIngressPayload.SourceTrust(evidence), index + 1L)
            }

            val snapshot = store.snapshot().filterIsInstance<ControllerIngress.SourceTrust>()
            assertEquals(evidenceOrder, snapshot.map { it.evidence })
            assertTrue(store.snapshot().isEmpty())

            evidenceOrder.forEachIndexed { index, evidence ->
                store.accept(ControllerIngressPayload.SourceTrust(evidence), index + 10L)
            }
            assertEquals(evidenceOrder, store.snapshot().map { (it as ControllerIngress.SourceTrust).evidence })
        }
    }

    @Test
    fun metricsTrustStormCannotEraseCapturedResizeTrust() {
        val store = ControllerIngressStore()
        store.accept(ControllerIngressPayload.SourceTrust(SourceTrustEvidence.InvalidResize), 1)
        repeat(100) { index ->
            val evidence = if (index % 2 == 0) SourceTrustEvidence.Invalid else SourceTrustEvidence.NoLongerAvailable
            store.accept(ControllerIngressPayload.SourceTrust(evidence), index + 2L)
        }

        val snapshot = store.snapshot().filterIsInstance<ControllerIngress.SourceTrust>()
        assertEquals(2, snapshot.size)
        assertEquals(SourceTrustEvidence.InvalidResize, snapshot.first().evidence)
        assertEquals(SourceTrustEvidence.NoLongerAvailable, snapshot.last().evidence)
    }

    @Test
    fun foreignCancellationCannotAllocateOrOverwriteInEitherOrder() {
        val current = TransactionIdentity(1)
        val foreign = TransactionIdentity(2)
        listOf(false, true).forEach { foreignFirst ->
            val store = ControllerIngressStore()
            var allocations = 0
            fun offer(transaction: TransactionIdentity): ControllerWakeIntent {
                val accepted = store.offer(
                    ControllerIngressPayload.Cancellation(transaction),
                    currentTransaction = current,
                ) ?: return ControllerWakeIntent.Ignored
                allocations += 1
                return store.accept(accepted, IngressSequence(allocations.toLong()))
            }

            if (foreignFirst) {
                assertEquals(ControllerWakeIntent.Ignored, offer(foreign))
                assertEquals(ControllerWakeIntent.Schedule, offer(current))
            } else {
                assertEquals(ControllerWakeIntent.Schedule, offer(current))
                assertEquals(ControllerWakeIntent.Ignored, offer(foreign))
            }
            assertEquals(1, allocations)
            val cancellation = store.snapshot().single() as ControllerIngress.Cancellation
            assertEquals(current, cancellation.transaction)
        }
    }

    @Test
    fun offerCannotBeInstalledIntoAnotherStore() {
        val owner = ControllerIngressStore()
        val foreign = ControllerIngressStore()
        val offer = owner.offer(ControllerIngressPayload.Visibility(true))!!
        assertEquals(
            ControllerWakeIntent.Ignored,
            foreign.accept(offer, IngressSequence(1)),
        )
        assertTrue(foreign.snapshot().isEmpty())
        assertEquals(ControllerWakeIntent.Schedule, owner.accept(offer, IngressSequence(1)))
    }

    @Test
    fun pauseResumeCannotEraseDebtAndPredrainCyclesCoalesceToNewestPause() {
        val store = ControllerIngressStore()
        store.accept(ControllerIngressPayload.Pause(true), 1)
        store.accept(ControllerIngressPayload.Pause(false), 2)
        store.accept(ControllerIngressPayload.Pause(true), 3)
        store.accept(ControllerIngressPayload.Pause(false), 4)

        val pause = store.snapshot().single() as ControllerIngress.Pause
        assertEquals(PauseEvidence(false, IngressSequence(3)), pause.evidence)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun pausePhysicalStateSurvivesDrainWhileDebtIsConsumedOnce() {
        val store = ControllerIngressStore()
        store.accept(ControllerIngressPayload.Pause(true), 1)

        val paused = store.snapshot().single() as ControllerIngress.Pause
        assertEquals(PauseEvidence(true, IngressSequence(1)), paused.evidence)
        assertEquals(null, store.offer(ControllerIngressPayload.Pause(true)))

        store.accept(ControllerIngressPayload.Pause(false), 2)
        val resumed = store.snapshot().single() as ControllerIngress.Pause
        assertEquals(PauseEvidence(false, null), resumed.evidence)
    }

    @Test
    fun acceptedOfferIsStoreBoundAndOneShot() {
        val store = ControllerIngressStore()
        val offer = store.offer(ControllerIngressPayload.Visibility(true))!!

        assertEquals(ControllerWakeIntent.Schedule, store.accept(offer, IngressSequence(1)))
        assertEquals(ControllerWakeIntent.Ignored, store.accept(offer, IngressSequence(2)))
        assertEquals(listOf(1L), store.snapshot().map { it.sequence.value })
    }

    private fun ControllerIngressStore.accept(
        payload: ControllerIngressPayload,
        sequence: Long,
        currentTransaction: TransactionIdentity? = null,
    ): ControllerWakeIntent {
        val offer = offer(payload, currentTransaction) ?: return ControllerWakeIntent.Ignored
        return accept(offer, IngressSequence(sequence))
    }

    private data class IngressAt(val payload: ControllerIngressPayload, val sequence: Long)
}
