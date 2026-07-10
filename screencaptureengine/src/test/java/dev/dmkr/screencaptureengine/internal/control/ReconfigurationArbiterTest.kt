package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconfigurationArbiterTest {
    @Test
    fun principalUsesExactKindStageThenEarliestLiveSequence() {
        val platform = ReconfigurationReasonSpec.PlatformAssignment
        val rendering = ReconfigurationReasonSpec.RenderingOwner
        val arbiter = ReconfigurationArbiter()
            .add(ReasonToken(1), rendering, IngressSequence(1))
            .add(ReasonToken(2), platform, IngressSequence(2))
        assertEquals(platform, arbiter.principal?.spec)

        val first = ReconfigurationReasonSpec.TargetAcquisition
        val second = ReconfigurationReasonSpec.FreshTarget
        val tie = ReconfigurationArbiter()
            .add(ReasonToken(3), second, IngressSequence(4))
            .add(ReasonToken(4), first, IngressSequence(3))
        assertEquals(first, tie.principal?.spec)

        val byStage = ReconfigurationArbiter()
            .add(ReasonToken(5), ReconfigurationReasonSpec.PlatformPause, IngressSequence(1))
            .add(ReasonToken(6), ReconfigurationReasonSpec.TargetAcquisition, IngressSequence(2))
        assertEquals(ReconfigurationReasonSpec.TargetAcquisition, byStage.principal?.spec)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ReconfigurationReasonKind.entries.map { it.priority })
        assertEquals((0..8).toList(), ReconfigurationStage.entries.map { it.priority })
    }

    @Test
    fun equivalentAddCoalescesAndExactTokenClearProtectsReplacement() {
        val evidence = ReconfigurationReasonSpec.SourceNotReady
        val first = ReconfigurationArbiter().add(ReasonToken(1), evidence, IngressSequence(1))
        assertSame(first, first.add(ReasonToken(2), evidence, IngressSequence(2)))
        val replacementEvidence = ReconfigurationReasonSpec.SourceUnavailable
        val replacement = first.add(ReasonToken(2), replacementEvidence, IngressSequence(2))
        assertSame(replacement, replacement.clear(evidence.key, ReasonToken(1)))
        val cleared = replacement.clear(evidence.key, ReasonToken(2))
        assertTrue(cleared.reasons.isEmpty())
    }

    @Test
    fun activeAndPendingReconfigurationAreIdentityFenced() {
        val reconfiguration = ReconfigurationIdentity(1)
        val candidate = CandidateIdentity(1)
        val tx = TransactionIdentity(1)
        val pending = PendingReconfiguration(DesiredSnapshotIdentity(1), GeometrySnapshot(1, 2, 3), IngressSequence(1))
        val origin = ControllerFactOrigin.Reconfiguration(reconfiguration, candidate)
        val arbiter = ReconfigurationArbiter().begin(reconfiguration, candidate, tx).replacePending(pending)
        assertEquals(reconfiguration.value, arbiter.activeReconfiguration?.value)
        assertEquals(candidate.value, arbiter.activeCandidate?.value)
        assertEquals(tx.value, arbiter.associatedParameterTransaction?.value)
        assertEquals(pending, arbiter.pending)
        assertTrue(arbiter.owns(origin))
        assertSame(
            arbiter,
            arbiter.finish(ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(2), candidate)),
        )
        assertSame(
            arbiter,
            arbiter.finish(ControllerFactOrigin.Reconfiguration(reconfiguration, CandidateIdentity(2))),
        )
        val finished = arbiter.finish(origin)
        assertEquals(null, finished.activeReconfiguration)
        assertEquals(null, finished.activeCandidate)
    }

    @Test
    fun activeReconfigurationAndCandidateFenceAreIndivisible() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconfigurationArbiter(activeReconfiguration = ReconfigurationIdentity(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconfigurationArbiter(activeCandidate = CandidateIdentity(1))
        }
    }

    @Test
    fun pendingConsumptionRequiresTheExactDesiredGeometryAndAcceptedSequence() {
        val pending = PendingReconfiguration(
            DesiredSnapshotIdentity(1),
            GeometrySnapshot(2, 3, 4),
            IngressSequence(5),
        )
        val origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(6), CandidateIdentity(7))
        val arbiter = ReconfigurationArbiter()
            .begin(origin.reconfiguration, origin.candidate)
            .add(ReasonToken(8), ReconfigurationReasonSpec.Convergence, IngressSequence(9))
            .replacePending(pending)

        listOf(
            pending.copy(desired = DesiredSnapshotIdentity(2)),
            pending.copy(geometry = GeometrySnapshot(3, 3, 4)),
            pending.copy(acceptedAt = IngressSequence(6)),
        ).forEach { stale -> assertSame(arbiter, arbiter.consumePending(stale)) }

        val consumed = arbiter.consumePending(pending)
        assertEquals(null, consumed.pending)
        assertTrue(consumed.owns(origin))
        assertEquals(arbiter.reasons, consumed.reasons)
        assertSame(consumed, consumed.consumePending(pending))
    }

    @Test
    fun everyClosedSpecParticipatesInTheExactComparatorAndMalformedMapsReject() {
        val arbiter = ReconfigurationReasonSpec.entries.foldIndexed(ReconfigurationArbiter()) { index, value, spec ->
            value.add(ReasonToken(index + 1L), spec, IngressSequence(index + 1L))
        }
        assertEquals(ReconfigurationReasonSpec.SourceUnavailable, arbiter.principal?.spec)

        val malformed = mapOf(
            ReconfigurationReasonKey.EncodingOwner to ReconfigurationReason(
                ReasonToken(1),
                ReconfigurationReasonSpec.RenderingOwner,
                IngressSequence(1),
            ),
        )
        assertTrue(runCatching { ReconfigurationArbiter(reasons = malformed) }.isFailure)
    }

    @Test
    fun everyClosedSpecPairUsesKindThenStageThenEarliestLiveSequence() {
        ReconfigurationReasonSpec.entries.forEach { first ->
            ReconfigurationReasonSpec.entries.forEach { second ->
                val arbiter = ReconfigurationArbiter()
                    .add(ReasonToken(1), first, IngressSequence(1))
                    .add(ReasonToken(2), second, IngressSequence(2))
                val expected = if (first.key == second.key) {
                    if (first == second) first else second
                } else {
                    listOf(first to 1L, second to 2L).minWith(
                        compareBy(
                            { (spec, _) -> spec.kind.priority },
                            { (spec, _) -> spec.stage.priority },
                            { (_, sequence) -> sequence },
                        ),
                    ).first
                }

                assertEquals("$first versus $second", expected, arbiter.principal?.spec)
            }
        }
    }

    @Test
    fun reasonSlotsStayBoundedToOneClosedReasonPerKey() {
        val arbiter = ReconfigurationReasonSpec.entries.foldIndexed(ReconfigurationArbiter()) { index, value, spec ->
            value.add(ReasonToken(index + 1L), spec, IngressSequence(index + 1L))
        }

        assertEquals(ReconfigurationReasonKey.entries.size, arbiter.reasons.size)
        assertEquals(ReconfigurationReasonKey.entries.toSet(), arbiter.reasons.keys)
    }

    @Test
    fun reasonSlotsOwnAnImmutableDefensiveCopy() {
        val original = ReconfigurationReason(
            ReasonToken(1),
            ReconfigurationReasonSpec.RenderingOwner,
            IngressSequence(1),
        )
        val callerOwned = mutableMapOf(original.spec.key to original)
        val arbiter = ReconfigurationArbiter(reasons = callerOwned)

        callerOwned.clear()
        assertEquals(mapOf(original.spec.key to original), arbiter.reasons)

        @Suppress("UNCHECKED_CAST")
        val exported = arbiter.reasons as MutableMap<ReconfigurationReasonKey, ReconfigurationReason>
        assertThrows(UnsupportedOperationException::class.java) {
            exported.clear()
        }
        assertEquals(mapOf(original.spec.key to original), arbiter.reasons)
    }
}
