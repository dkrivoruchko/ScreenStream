package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDirectFactTest {
    @Test
    fun terminalSourcesDeriveExactWinnerDomainAndPriority() {
        val expected = mapOf(
            TerminalEvidence.ProjectionStopped to terminal(
                TerminalWinnerClass.CaptureEnded,
                TerminalFailureDomain.ProjectionUnavailable,
                1,
            ),
            TerminalEvidence.DisplayStopped to terminal(
                TerminalWinnerClass.CaptureEnded,
                TerminalFailureDomain.ProjectionUnavailable,
                1,
            ),
            TerminalEvidence.OwnerStopped to terminal(TerminalWinnerClass.OwnerStop, TerminalFailureDomain.None, 2),
            TerminalEvidence.StartedEncoderStall to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.EncodingFailure,
                3,
            ),
            TerminalEvidence.PoisonedProviderPreparationRequired to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.EncodingFailure,
                3,
            ),
            TerminalEvidence.StartedGlTimeout to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.RenderingFailure,
                3,
            ),
            TerminalEvidence.StartedPlatformTimeout to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.PlatformFailure,
                3,
            ),
            TerminalEvidence.ListenerRetirementUnprovable to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.InternalFailure,
                3,
            ),
            TerminalEvidence.MetricsCollectionTerminated to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.PlatformFailure,
                3,
            ),
            TerminalEvidence.UnsafePlatformBinding to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.PlatformFailure,
                3,
            ),
            TerminalEvidence.UnsafeRenderingState to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.RenderingFailure,
                3,
            ),
            TerminalEvidence.UnsafeGlOutOfMemory to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.ResourceExhausted,
                3,
            ),
            TerminalEvidence.UnsafeProviderRetainedOwnership to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.EncodingFailure,
                3,
            ),
            TerminalEvidence.InternalControllerInvariant to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.InternalFailure,
                3,
            ),
            TerminalEvidence.PlatformRecoveryExhausted to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.PlatformFailure,
                3,
            ),
            TerminalEvidence.RenderingRecoveryExhausted to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.RenderingFailure,
                3,
            ),
            TerminalEvidence.EncodingRecoveryExhausted to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.EncodingFailure,
                3,
            ),
            TerminalEvidence.ResourceRecoveryExhausted to terminal(
                TerminalWinnerClass.Failed,
                TerminalFailureDomain.ResourceExhausted,
                3,
            ),
        )

        assertEquals(TerminalEvidence.entries.toSet(), expected.keys)
        TerminalEvidence.entries.forEach { evidence ->
            assertEquals(expected.getValue(evidence), terminal(evidence.winnerClass, evidence.failureDomain, evidence.priority))
        }
        assertFalse(TerminalEvidence.entries.any { it.name == "RecoveryExhausted" })
    }

    @Test
    fun everyClosedTerminalSourceRetainsOneIndependentSlot() {
        val ingress = ControllerIngressStore()
        TerminalEvidence.entries.forEachIndexed { index, evidence ->
            ingress.accept(ControllerIngressPayload.Terminal(terminalCauseForTest(evidence, index + 1L)), index + 1L)
            assertEquals(null, ingress.offer(ControllerIngressPayload.Terminal(terminalCauseForTest(evidence, 100L))))
        }

        val terminalFacts = ingress.snapshot().filterIsInstance<ControllerIngress.Terminal>()
        assertEquals(TerminalEvidence.entries.size, terminalFacts.size)
        assertEquals(TerminalEvidence.entries.toSet(), terminalFacts.map { it.evidence }.toSet())
    }

    @Test
    fun directVariantsRetainSessionSequenceOriginAndIdentityFences() {
        val startup = ControllerFactOrigin.Startup(CandidateIdentity(3))
        val parameter = ControllerFactOrigin.Parameter(TransactionIdentity(4), CandidateIdentity(5))
        val reconfiguration = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(6), CandidateIdentity(7))
        val facts = listOf(
            ControllerDirectFact.NormalizedNoOpReady(
                session = SessionIdentity(1),
                sequence = IngressSequence(8),
                call = TransactionIdentity(9),
                desired = DesiredSnapshotIdentity(10),
            ),
            ControllerDirectFact.ParameterAdmitted(
                session = SessionIdentity(1),
                sequence = IngressSequence(9),
                origin = parameter,
                desired = DesiredSnapshotIdentity(10),
                previousEffective = EffectiveSnapshotIdentity(11),
                candidateClass = PreparedParameterCandidateClass.TargetReplan,
            ),
            ControllerDirectFact.InitialActiveReady(
                session = SessionIdentity(1),
                sequence = IngressSequence(10),
                origin = startup,
                desired = DesiredSnapshotIdentity(11),
                target = TargetIdentity(12),
                owner = CompleteOwnerIdentity(13),
                effective = EffectiveSnapshotIdentity(14),
            ),
            ControllerDirectFact.Parameter(
                session = SessionIdentity(1),
                sequence = IngressSequence(15),
                origin = parameter,
                fact = ParameterTransactionFact.CandidatePrepared(
                    transaction = parameter.transaction,
                    candidate = parameter.candidate,
                    owner = CompleteOwnerIdentity(16),
                ),
            ),
            ControllerDirectFact.ArbiterReasonAdded(
                SessionIdentity(1),
                IngressSequence(17),
                reconfiguration,
                ReasonToken(18),
                ReconfigurationReasonSpec.RenderingOwner,
                ArbiterFenceEvidence.OutputFreshness,
            ),
            ControllerDirectFact.ArbiterReasonCleared(
                SessionIdentity(1),
                IngressSequence(19),
                reconfiguration,
                ReasonToken(20),
                ReconfigurationReasonKey.RenderingOwner,
            ),
            ControllerDirectFact.TargetAcknowledged(
                SessionIdentity(1),
                IngressSequence(21),
                reconfiguration,
                TargetIdentity(22),
            ),
            ControllerDirectFact.CompleteOwnerReady(
                SessionIdentity(1),
                IngressSequence(23),
                reconfiguration,
                CompleteOwnerIdentity(24),
                EffectiveSnapshotIdentity(25),
            ),
        )

        assertEquals(List(facts.size) { SessionIdentity(1) }, facts.map { it.session })
        assertEquals(listOf(5, 5, 5, 5, 6, 6, 6, 6), facts.map { it.priority })
        assertEquals(listOf(5L, 3L, 5L, 7L, 7L, 7L, 7L), facts.mapNotNull { fact -> fact.originCandidate()?.value })
        assertEquals(
            ArbiterFenceEvidence.OutputFreshness,
            (facts[4] as ControllerDirectFact.ArbiterReasonAdded).fence,
        )
        assertEquals(setOf(ArbiterFenceEvidence.None, ArbiterFenceEvidence.OutputFreshness), ArbiterFenceEvidence.entries.toSet())
    }

    @Test
    fun nonNoOpParameterAdmissionIsSequencedAndCarriesEveryTransactionFence() {
        PreparedParameterCandidateClass.entries.forEachIndexed { index, candidateClass ->
            val origin = ControllerFactOrigin.Parameter(TransactionIdentity(index + 1L), CandidateIdentity(index + 4L))
            val fact = ControllerDirectFact.ParameterAdmitted(
                session = SessionIdentity(8),
                sequence = IngressSequence(index + 9L),
                origin = origin,
                desired = DesiredSnapshotIdentity(index + 12L),
                previousEffective = EffectiveSnapshotIdentity(index + 15L),
                candidateClass = candidateClass,
            )

            assertEquals(5, fact.priority)
            assertEquals(origin, fact.origin)
            val transaction = ParameterTransaction.admit(fact)
            assertEquals(origin.transaction, transaction.identity)
            assertEquals(origin.candidate, transaction.candidate)
            assertEquals(fact.desired, transaction.desired)
            assertEquals(fact.previousEffective, transaction.previousEffective)
            assertEquals(candidateClass, transaction.candidateClass)
        }
    }

    @Test
    fun pacingResetFactsAreClosedAndRejectInvalidPolicyInputs() {
        assertEquals(
            setOf(
                FramePacingResetFact.Auto::class,
                FramePacingResetFact.MaxFps::class,
                FramePacingResetFact.PeriodicRefresh::class,
            ),
            setOf(
                FramePacingResetFact.Auto(0L)::class,
                FramePacingResetFact.MaxFps(60, 1L)::class,
                FramePacingResetFact.PeriodicRefresh(
                    1_000L,
                    PeriodicRefreshSourceFence.Acquired(
                        EffectiveSnapshotIdentity(1),
                        1L,
                        TargetIdentity(1),
                    ),
                    2L,
                )::class,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { FramePacingResetFact.Auto(-1L) }
        assertThrows(IllegalArgumentException::class.java) { FramePacingResetFact.MaxFps(0, 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            FramePacingResetFact.PeriodicRefresh(
                0L,
                PeriodicRefreshSourceFence.NotAcquired(
                    EffectiveSnapshotIdentity(1),
                    1L,
                    TargetIdentity(1),
                ),
                0L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeriodicRefreshSourceFence.Acquired(EffectiveSnapshotIdentity(1), 0L, TargetIdentity(1))
        }
    }

    @Test
    fun automaticReturnedPreparationFailureIsAnOrderedRecoveryFact() {
        val origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(2))
        RecoveryCandidatePreparationFailureEvidence.entries.forEach { evidence ->
            ReturnedCandidatePreparationOwnership.entries.forEach { ownership ->
                val fact = ControllerDirectFact.RecoveryCandidatePreparationFailed(
                    SessionIdentity(3),
                    IngressSequence(4),
                    origin,
                    evidence,
                    ownership,
                )
                assertEquals(6, fact.priority)
                assertEquals(origin, fact.origin)
            }
        }
    }

    @Test
    fun parameterFactRejectsAConflictingTransactionOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerDirectFact.Parameter(
                session = SessionIdentity(1),
                sequence = IngressSequence(2),
                origin = ControllerFactOrigin.Parameter(TransactionIdentity(3), CandidateIdentity(4)),
                fact = ParameterTransactionFact.CandidatePrepared(
                    transaction = TransactionIdentity(5),
                    candidate = CandidateIdentity(4),
                    owner = CompleteOwnerIdentity(6),
                ),
            )
        }
    }

    @Test
    fun parameterFactRejectsAConflictingCandidateOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerDirectFact.Parameter(
                session = SessionIdentity(1),
                sequence = IngressSequence(2),
                origin = ControllerFactOrigin.Parameter(TransactionIdentity(3), CandidateIdentity(4)),
                fact = ParameterTransactionFact.CandidatePreparationFailed(
                    transaction = TransactionIdentity(3),
                    candidate = CandidateIdentity(5),
                    evidence = ParameterPreparationRejectionEvidence.ConcreteResourceExhaustion,
                    ownership = ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                ),
            )
        }
    }

    @Test
    fun parameterTargetAndOwnerFactsUseOnlyTheOrderedParameterRoute() {
        val origin = ControllerFactOrigin.Parameter(TransactionIdentity(1), CandidateIdentity(2))
        val target = ControllerDirectFact.Parameter(
            session = SessionIdentity(3),
            sequence = IngressSequence(4),
            origin = origin,
            fact = ParameterTransactionFact.TargetAcknowledged(origin.transaction, TargetIdentity(5)),
        )
        val owner = ControllerDirectFact.Parameter(
            session = SessionIdentity(3),
            sequence = IngressSequence(6),
            origin = origin,
            fact = ParameterTransactionFact.OwnerCommitReady(
                origin.transaction,
                origin.candidate,
                CompleteOwnerIdentity(7),
                EffectiveSnapshotIdentity(8),
            ),
        )

        assertEquals(listOf(5, 5), listOf(target.priority, owner.priority))
        assertEquals(
            listOf(origin.candidate, origin.candidate),
            listOf(target.origin.candidate, owner.origin.candidate),
        )
    }

    @Test
    fun callerCancellationIsIngressOnlyAndCannotEnterTheDirectParameterRoute() {
        val cancellation: ParameterTransactionFact = ParameterTransactionFact.Cancelled(TransactionIdentity(1))
        val ingress = ControllerIngressPayload.Cancellation(TransactionIdentity(1))

        assertEquals(TransactionIdentity(1), cancellation.transaction)
        assertEquals(TransactionIdentity(1), ingress.transaction)
    }

    @Test
    fun startupOwnerAndEffectiveCommitRemainIndivisibleWhileStartupTargetAcknowledgementIsSeparate() {
        val startup = ControllerFactOrigin.Startup(CandidateIdentity(1))
        val active = ControllerDirectFact.InitialActiveReady(
            session = SessionIdentity(2),
            sequence = IngressSequence(3),
            origin = startup,
            desired = DesiredSnapshotIdentity(4),
            target = TargetIdentity(5),
            owner = CompleteOwnerIdentity(6),
            effective = EffectiveSnapshotIdentity(7),
        )
        val target = ControllerDirectFact.TargetAcknowledged(
            session = SessionIdentity(2),
            sequence = IngressSequence(8),
            origin = startup,
            target = TargetIdentity(5),
        )

        assertEquals(startup, active.origin)
        assertEquals(CompleteOwnerIdentity(6), active.owner)
        assertEquals(EffectiveSnapshotIdentity(7), active.effective)
        assertEquals(startup, target.origin)
    }

    @Test
    fun finiteMergeOrdersPendingTerminalSourceCancellationDirectAndVisibility() {
        val ingress = ControllerIngressStore()
        ingress.accept(ControllerIngressPayload.Visibility(true), 1)
        ingress.accept(ControllerIngressPayload.Cancellation(TransactionIdentity(2)), 8)
        ingress.accept(ControllerIngressPayload.Metrics(MetricsEvidence(3, 4, 5)), 9)
        ingress.accept(ControllerIngressPayload.Terminal(terminalCauseForTest(TerminalEvidence.OwnerStopped)), 10)
        val direct = parameterDirect(sequence = 7)

        val merged = ControllerTurnFactMerge.drain(ingress, direct)

        assertEquals(listOf(2, 4, 5, 5, 7), merged.map { it.priority })
        assertEquals(listOf(10L, 9L, 7L, 8L, 1L), merged.map { it.sequence.value })
        assertTrue(ingress.snapshot().isEmpty())
    }

    @Test
    fun finiteMergeCannotExceedEveryFixedIngressSlotPlusOneDirectFact() {
        val ingress = ControllerIngressStore()
        var sequence = 1L
        TerminalEvidence.entries.forEach { evidence ->
            ingress.accept(ControllerIngressPayload.Terminal(terminalCauseForTest(evidence, sequence)), sequence++)
        }
        listOf(
            ControllerIngressPayload.Metrics(MetricsEvidence(1, 2, 3)),
            ControllerIngressPayload.CapturedResize(CapturedResizeEvidence(4, 5)),
            ControllerIngressPayload.SourceTrust(SourceTrustEvidence.NotReady),
            ControllerIngressPayload.SourceTrust(SourceTrustEvidence.InvalidResize),
            ControllerIngressPayload.Pause(true),
            ControllerIngressPayload.Cancellation(TransactionIdentity(6)),
            ControllerIngressPayload.Visibility(true),
        ).forEach { payload -> ingress.accept(payload, sequence++) }

        val merged = ControllerTurnFactMerge.drain(ingress, parameterDirect(sequence))

        assertEquals(TerminalEvidence.entries.size + 8, merged.size)
        assertTrue(ingress.snapshot().isEmpty())
    }

    @Test
    fun equalSequenceAndPriorityKeepPendingIngressBeforeDirectWithoutConflation() {
        val ingress = ControllerIngressStore()
        ingress.accept(ControllerIngressPayload.Cancellation(TransactionIdentity(1)), 5)
        val direct = parameterDirect(sequence = 5)

        val merged = ControllerTurnFactMerge.drain(ingress, direct)

        assertEquals(2, merged.size)
        assertTrue(merged[0] is ControllerIngress.Cancellation)
        assertTrue(merged[1] === direct)
    }

    @Test
    fun normalizedNoOpIsAnOrderedCurrentCallFactAndLosesToSameTurnTerminalAndSource() {
        val ingress = ControllerIngressStore()
        ingress.accept(ControllerIngressPayload.Terminal(terminalCauseForTest(TerminalEvidence.OwnerStopped)), 7)
        ingress.accept(ControllerIngressPayload.Metrics(MetricsEvidence(1, 2, 3)), 8)
        ingress.accept(ControllerIngressPayload.Cancellation(TransactionIdentity(4)), 10)
        val direct = ControllerDirectFact.NormalizedNoOpReady(
            session = SessionIdentity(5),
            sequence = IngressSequence(9),
            call = TransactionIdentity(4),
            desired = DesiredSnapshotIdentity(6),
        )

        val merged = ControllerTurnFactMerge.drain(ingress, direct)

        assertEquals(listOf(2, 4, 5, 5), merged.map { it.priority })
        assertTrue(merged[2] === direct)
        assertEquals(TransactionIdentity(4), direct.call)
    }

    @Test
    fun recoveryDirectFactFollowsCancellationAndPrecedesVisibility() {
        val ingress = ControllerIngressStore()
        ingress.accept(ControllerIngressPayload.Visibility(false), 1)
        ingress.accept(ControllerIngressPayload.Cancellation(TransactionIdentity(2)), 9)
        val origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(3), CandidateIdentity(4))
        val direct = ControllerDirectFact.TargetAcknowledged(
            session = SessionIdentity(5),
            sequence = IngressSequence(6),
            origin = origin,
            target = TargetIdentity(7),
        )

        val merged = ControllerTurnFactMerge.drain(ingress, direct)

        assertEquals(listOf(5, 6, 7), merged.map { it.priority })
        assertTrue(merged[1] === direct)
    }

    @Test
    fun mandatoryRecoveryPreparationTimeoutTerminalBeatsSameTurnGeometry() {
        val ingress = ControllerIngressStore()
        ingress.accept(
            ControllerIngressPayload.Terminal(
                terminalCauseForTest(TerminalEvidence.PoisonedProviderPreparationRequired),
            ),
            2,
        )
        ingress.accept(ControllerIngressPayload.Metrics(MetricsEvidence(3, 4, 5)), 1)

        val merged = ControllerTurnFactMerge.drain(ingress)

        assertEquals(listOf(3, 4), merged.map { it.priority })
        assertEquals(
            TerminalEvidence.PoisonedProviderPreparationRequired,
            (merged.first() as ControllerIngress.Terminal).evidence,
        )
    }

    @Test
    fun directFactNeverOccupiesOrConflatesAnIngressSlot() {
        val ingress = ControllerIngressStore()
        val first = parameterDirect(sequence = 1)
        assertEquals(listOf(first), ControllerTurnFactMerge.drain(ingress, first))
        assertTrue(ingress.snapshot().isEmpty())

        val second = parameterDirect(sequence = 2)
        assertEquals(listOf(second), ControllerTurnFactMerge.drain(ingress, second))
        assertTrue(first !== second)
    }

    private fun parameterDirect(sequence: Long): ControllerDirectFact.Parameter {
        val origin = ControllerFactOrigin.Parameter(TransactionIdentity(1), CandidateIdentity(2))
        return ControllerDirectFact.Parameter(
            session = SessionIdentity(3),
            sequence = IngressSequence(sequence),
            origin = origin,
            fact = ParameterTransactionFact.CandidatePrepared(
                transaction = origin.transaction,
                candidate = origin.candidate,
                owner = CompleteOwnerIdentity(4),
            ),
        )
    }

    private fun ControllerDirectFact.originCandidate(): CandidateIdentity? = when (this) {
        is ControllerDirectFact.NormalizedNoOpReady -> null
        is ControllerDirectFact.PrePublicRetirement -> null
        is ControllerDirectFact.ParameterAdmitted -> origin.candidate
        is ControllerDirectFact.InitialActiveReady -> origin.candidate
        is ControllerDirectFact.Parameter -> origin.candidate
        is ControllerDirectFact.ArbiterReasonAdded -> origin.candidate
        is ControllerDirectFact.ArbiterReasonCleared -> origin.candidate
        is ControllerDirectFact.TargetAcknowledged -> origin.candidate
        is ControllerDirectFact.CompleteOwnerReady -> origin.candidate
        is ControllerDirectFact.RecoveryCandidatePreparationFailed -> origin.candidate
    }

    private fun ControllerIngressStore.accept(payload: ControllerIngressPayload, sequence: Long) {
        val currentTransaction = (payload as? ControllerIngressPayload.Cancellation)?.transaction
        val offer = requireNotNull(offer(payload, currentTransaction))
        assertTrue(accept(offer, IngressSequence(sequence)) != ControllerWakeIntent.Ignored)
    }

    private data class ExpectedTerminal(
        val winner: TerminalWinnerClass,
        val domain: TerminalFailureDomain,
        val priority: Int,
    )

    private fun terminal(
        winner: TerminalWinnerClass,
        domain: TerminalFailureDomain,
        priority: Int,
    ) = ExpectedTerminal(winner, domain, priority)
}
