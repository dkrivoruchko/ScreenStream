package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.encoding.DescriptorSyntax
import dev.dmkr.screencaptureengine.internal.encoding.EncodedFormatDescriptorSnapshot
import dev.dmkr.screencaptureengine.internal.encoding.LiveProviderDescriptorLedger
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRecordResult
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRetentionRole
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanFact
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanner
import dev.dmkr.screencaptureengine.internal.planning.PositiveRatio
import dev.dmkr.screencaptureengine.internal.planning.SamplingDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerSnapshotStoreTest {
    @Test
    fun everyStoreBoundPreparedWrapperEntersACompleteTurnThroughItsOnlyRawLeaf() {
        val provider = StoreProvider()
        val initialFixture = Fixture()
        val initialOwnership = initialFixture.begin(provider, id = 1)
        initialFixture.acknowledgeCandidateTarget(1)
        assertRecorded(initialFixture.store.recordCandidateDescriptor(initialOwnership, descriptor("JPEG")))
        val initialEffective = effective(1, "JPEG")
        val initialTicket = requireNotNull(
            initialFixture.store.prevalidateCandidateCommit(
                initialOwnership,
                SessionIdentity(1),
                CompleteOwnerIdentity(1),
                initialEffective,
            ),
        )
        val initialFact = ControllerDirectFact.InitialActiveReady(
            SessionIdentity(1),
            IngressSequence(1),
            ControllerFactOrigin.Startup(CandidateIdentity(1)),
            DesiredSnapshotIdentity(1),
            TargetIdentity(1),
            CompleteOwnerIdentity(1),
            EffectiveSnapshotIdentity(1),
        )
        val initial = PrevalidatedInitialActive(initialFact, initialTicket.target, initialTicket.effective)
        val recoveryOwnerFact = ControllerDirectFact.CompleteOwnerReady(
            SessionIdentity(1),
            IngressSequence(2),
            ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(1)),
            CompleteOwnerIdentity(1),
            EffectiveSnapshotIdentity(1),
        )
        val recoveryOwner = PrevalidatedRecoveryOwnerCommit(
            recoveryOwnerFact,
            initialEffective,
            EffectiveSnapshotIdentity(2),
        )

        val candidateFixture = Fixture()
        candidateFixture.install(provider, id = 1, format = "JPEG")
        val candidateOwnership = candidateFixture.begin(provider, id = 2)
        val preparing = ParameterTransaction(
            TransactionIdentity(2),
            DesiredSnapshotIdentity(2),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(2),
            PreparedParameterCandidateClass.SameTargetReplacement,
        )
        val candidateFact = ParameterTransactionFact.CandidatePrepared(
            preparing.identity,
            preparing.candidate,
            CompleteOwnerIdentity(2),
        )
        val candidateDirect = parameterDirect(preparing, candidateFact, sequence = 3)
        val candidatePrepared = PrevalidatedCandidatePrepared(
            candidateDirect,
            preparing,
            candidateFact,
        )
        val failedFact = ParameterTransactionFact.CandidatePreparationFailed(
            preparing.identity,
            preparing.candidate,
            ParameterPreparationRejectionEvidence.EncoderSetupFailed,
            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
        )
        val candidateFailed = PrevalidatedCandidatePreparationFailed(
            parameterDirect(preparing, failedFact, sequence = 4),
            preparing,
            failedFact,
        )
        val timeoutFact = ParameterTransactionFact.CandidatePreparationStartedTimedOut(
            preparing.identity,
            preparing.candidate,
        )
        val candidateTimeout = PrevalidatedCandidatePreparationStartedTimeout(
            parameterDirect(preparing, timeoutFact, sequence = 5),
            preparing,
            timeoutFact,
        )
        assertRecorded(candidateFixture.store.recordCandidateDescriptor(candidateOwnership, descriptor("JPEG")))
        val ownerEffective = effective(2, "JPEG", targetId = 1)
        val ownerTicket = requireNotNull(
            candidateFixture.store.prevalidateCandidateCommit(
                candidateOwnership,
                SessionIdentity(1),
                CompleteOwnerIdentity(2),
                ownerEffective,
            ),
        )
        val candidateReady = (preparing.accept(candidateFact) as ParameterTransition.Advanced).transaction
        val ownerFact = ParameterTransactionFact.OwnerCommitReady(
            preparing.identity,
            preparing.candidate,
            CompleteOwnerIdentity(2),
            ownerEffective.identity,
        )
        val ownerCommit = PrevalidatedOwnerCommit(
            parameterDirect(candidateReady, ownerFact, sequence = 6),
            candidateReady,
            ownerFact,
            ownerTicket.target,
            ownerTicket.effective,
        )

        val frameOutput = output(NormalizedFrameRate.MaxFps(30))
        val frameDesired = desired(provider, id = 3, output = frameOutput)
        val frameEffective = effective(3, "JPEG", output = frameOutput, ownerId = 1, targetId = 1, desiredId = 3)
        val frameTransaction = ParameterTransaction(
            TransactionIdentity(3),
            frameDesired.identity,
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(3),
            PreparedParameterCandidateClass.FrameRateOnly,
        )
        val frameFact = ParameterTransactionFact.FrameRateCommitReady(
            frameTransaction.identity,
            frameDesired.identity,
            frameEffective.identity,
            FramePacingResetFact.MaxFps(30, 1),
        )
        val frameCommit = PrevalidatedFrameRateCommit(
            parameterDirect(frameTransaction, frameFact, sequence = 7),
            SessionIdentity(1),
            frameTransaction,
            frameFact,
            frameDesired.output,
            frameEffective,
        )

        val targetFixture = Fixture()
        targetFixture.install(provider, id = 1, format = "JPEG")
        targetFixture.begin(provider, id = 4)
        val targetTransaction = ParameterTransaction(
            TransactionIdentity(4),
            DesiredSnapshotIdentity(4),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(4),
            PreparedParameterCandidateClass.TargetReplan,
            ParameterTransactionStage.RetargetStarted,
            CompleteOwnerIdentity(4),
            TargetIdentity(4),
        )
        val targetFact = ParameterTransactionFact.TargetAcknowledged(targetTransaction.identity, TargetIdentity(4))
        val targetDirect = parameterDirect(targetTransaction, targetFact, sequence = 8)
        val targetTicket = requireNotNull(
            targetFixture.store.prevalidateTargetAcknowledgement(SessionIdentity(1), targetDirect.origin, target(4)),
        )
        val parameterTarget = PrevalidatedParameterTargetAcknowledgement(
            targetDirect,
            targetTransaction,
            targetFact,
            targetTicket.target,
        )
        val recoveryTargetFact = ControllerDirectFact.TargetAcknowledged(
            SessionIdentity(1),
            IngressSequence(9),
            ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(4), CandidateIdentity(4)),
            TargetIdentity(4),
        )
        val recoveryTargetTicket = requireNotNull(
            targetFixture.store.prevalidateTargetAcknowledgement(
                SessionIdentity(1),
                recoveryTargetFact.origin,
                target(4),
            ),
        )
        val recoveryTarget = PrevalidatedRecoveryTargetAcknowledgement(recoveryTargetFact, recoveryTargetTicket.target)
        val recoveryFailureFact = ControllerDirectFact.RecoveryCandidatePreparationFailed(
            SessionIdentity(1),
            IngressSequence(10),
            ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(5), CandidateIdentity(5)),
            RecoveryCandidatePreparationFailureEvidence.EncoderSetupFailed,
            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
        )
        val recoveryFailure = PrevalidatedRecoveryCandidatePreparationFailed(recoveryFailureFact)
        val noOpFact = ControllerDirectFact.NormalizedNoOpReady(
            SessionIdentity(1),
            IngressSequence(11),
            TransactionIdentity(5),
            DesiredSnapshotIdentity(1),
        )
        val noOp = NormalizedNoOpReady(noOpFact, output())

        val matrix = listOf(
            "noOp" to noOp,
            "initial" to initial,
            "frame" to frameCommit,
            "candidatePrepared" to candidatePrepared,
            "candidateFailed" to candidateFailed,
            "parameterTarget" to parameterTarget,
            "ownerCommit" to ownerCommit,
            "recoveryOwner" to recoveryOwner,
            "recoveryTarget" to recoveryTarget,
            "candidateTimeout" to candidateTimeout,
            "recoveryFailure" to recoveryFailure,
        )
        matrix.forEach { (_, prepared) -> assertSame(prepared, ControllerPreparedTurn(listOf(prepared))[0]) }
        assertEquals(
            setOf(
                "noOp",
                "initial",
                "frame",
                "candidatePrepared",
                "candidateFailed",
                "parameterTarget",
                "ownerCommit",
                "recoveryOwner",
                "recoveryTarget",
                "candidateTimeout",
                "recoveryFailure",
            ),
            matrix.map { it.first }.toSet(),
        )
    }

    @Test
    fun prevalidatedReducerInputsRetainFullValuesExactCapabilitiesAndStructuralFences() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        val ownership = fixture.begin(provider, id = 2)
        val preparing = ParameterTransaction(
            identity = TransactionIdentity(2),
            desired = DesiredSnapshotIdentity(2),
            previousEffective = EffectiveSnapshotIdentity(1),
            candidate = CandidateIdentity(2),
            candidateClass = PreparedParameterCandidateClass.SameTargetReplacement,
        )
        val preparedFact = ParameterTransactionFact.CandidatePrepared(
            transaction = preparing.identity,
            candidate = preparing.candidate,
            owner = CompleteOwnerIdentity(2),
        )
        val preparedDirect = parameterDirect(preparing, preparedFact, sequence = 1)
        val prepared = PrevalidatedCandidatePrepared(preparedDirect, preparing, preparedFact)
        val failedFact = ParameterTransactionFact.CandidatePreparationFailed(
            preparing.identity,
            preparing.candidate,
            ParameterPreparationRejectionEvidence.EncoderSetupFailed,
            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
        )
        val failedDirect = parameterDirect(preparing, failedFact, sequence = 2)
        val failed = PrevalidatedCandidatePreparationFailed(
            failedDirect,
            preparing,
            failedFact,
        )
        assertSame(prepared, ControllerPreparedTurn(listOf(prepared))[0])
        assertSame(failed, ControllerPreparedTurn(listOf(failed))[0])
        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedCandidatePrepared(
                preparedDirect.copy(fact = preparedFact.copy()),
                preparing,
                preparedFact,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedCandidatePreparationFailed(
                failedDirect.copy(fact = failedFact.copy()),
                preparing,
                failedFact,
            )
        }
        assertSame(failedDirect, failed.turnFact)

        val candidateReady = (preparing.accept(preparedFact) as ParameterTransition.Advanced).transaction
        val effective = effective(2, "JPEG", targetId = 1)
        assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("JPEG")))
        val commitTicket = requireNotNull(
            fixture.store.prevalidateCandidateCommit(ownership, SessionIdentity(1), CompleteOwnerIdentity(2), effective),
        )
        val ownerFact = ParameterTransactionFact.OwnerCommitReady(
            transaction = preparing.identity,
            candidate = preparing.candidate,
            owner = CompleteOwnerIdentity(2),
            effective = effective.identity,
        )
        val ownerDirect = parameterDirect(candidateReady, ownerFact, sequence = 3)
        val ownerReady = PrevalidatedOwnerCommit(
            ownerDirect,
            candidateReady,
            ownerFact,
            commitTicket.target,
            commitTicket.effective,
        )
        assertSame(ownerReady, ControllerPreparedTurn(listOf(ownerReady))[0])
        assertSame(effective, ownerReady.effective)

        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedOwnerCommit(
                ownerDirect.copy(fact = ownerFact.copy()),
                candidateReady,
                ownerFact,
                commitTicket.target,
                commitTicket.effective,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedOwnerCommit(
                ownerDirect.copy(fact = ownerFact.copy(effective = EffectiveSnapshotIdentity(1))),
                candidateReady,
                ownerFact.copy(effective = EffectiveSnapshotIdentity(1)),
                commitTicket.target,
                commitTicket.effective,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val mismatched = preparedFact.copy(candidate = CandidateIdentity(3))
            PrevalidatedCandidatePrepared(
                parameterDirect(preparing, mismatched, sequence = 4),
                preparing,
                mismatched,
            )
        }
    }

    @Test
    fun fixedTurnFactsCoverNoOpFrameTargetTerminalCandidateAndPrePublicRetirement() {
        val fixture = Fixture()
        val provider = StoreProvider()
        val ownership = fixture.begin(provider, id = 1)
        val desired = desired(provider, id = 1)
        val initialEffective = effective(id = 1, format = "JPEG")
        fixture.acknowledgeCandidateTarget(1)
        assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("JPEG")))
        val commitTicket = requireNotNull(
            fixture.store.prevalidateCandidateCommit(
                ownership,
                SessionIdentity(1),
                CompleteOwnerIdentity(1),
                initialEffective,
            ),
        )
        val candidate = commitTicket.candidate
        val initialFact = ControllerDirectFact.InitialActiveReady(
            session = SessionIdentity(1),
            sequence = IngressSequence(1),
            origin = ControllerFactOrigin.Startup(candidate.identity),
            desired = desired.identity,
            target = TargetIdentity(1),
            owner = CompleteOwnerIdentity(1),
            effective = initialEffective.identity,
        )
        val initial = PrevalidatedInitialActive(
            initialFact,
            commitTicket.target,
            commitTicket.effective,
        )
        val recoveryFact = ControllerDirectFact.CompleteOwnerReady(
            session = SessionIdentity(1),
            sequence = IngressSequence(2),
            origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), candidate.identity),
            owner = CompleteOwnerIdentity(1),
            effective = initialEffective.identity,
        )
        val recovery = PrevalidatedRecoveryOwnerCommit(
            recoveryFact,
            initialEffective,
            EffectiveSnapshotIdentity(2),
        )
        val frameOutput = output(NormalizedFrameRate.MaxFps(30))
        val frameDesired = desired(provider, id = 2, output = frameOutput)
        val frameEffective = effective(
            id = 2,
            format = "JPEG",
            output = frameOutput,
            ownerId = 1,
            targetId = 1,
            desiredId = 2,
        )
        val frameTransaction = ParameterTransaction(
            identity = TransactionIdentity(1),
            desired = frameDesired.identity,
            previousEffective = EffectiveSnapshotIdentity(1),
            candidate = CandidateIdentity(1),
            candidateClass = PreparedParameterCandidateClass.FrameRateOnly,
        )
        val frameFact = ParameterTransactionFact.FrameRateCommitReady(
            frameTransaction.identity,
            frameDesired.identity,
            frameEffective.identity,
            FramePacingResetFact.MaxFps(fps = 30, commitSampleNanos = 1L),
        )
        val frameDirect = parameterDirect(frameTransaction, frameFact, sequence = 3)
        val frame = PrevalidatedFrameRateCommit(
            frameDirect,
            SessionIdentity(1),
            frameTransaction,
            frameFact,
            frameDesired.output,
            frameEffective,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedFrameRateCommit(
                frameDirect.copy(fact = frameFact.copy()),
                SessionIdentity(1),
                frameTransaction,
                frameFact,
                frameDesired.output,
                frameEffective,
            )
        }
        val targetTransaction = ParameterTransaction(
            identity = TransactionIdentity(2),
            desired = DesiredSnapshotIdentity(2),
            previousEffective = EffectiveSnapshotIdentity(1),
            candidate = CandidateIdentity(2),
            candidateClass = PreparedParameterCandidateClass.TargetReplan,
            stage = ParameterTransactionStage.RetargetStarted,
            candidateOwner = CompleteOwnerIdentity(2),
            candidateTarget = TargetIdentity(2),
        )
        val targetFact = ParameterTransactionFact.TargetAcknowledged(targetTransaction.identity, TargetIdentity(2))
        val targetFixture = Fixture()
        targetFixture.install(provider, id = 1, format = "JPEG")
        targetFixture.begin(provider, id = 2)
        val targetDirect = parameterDirect(targetTransaction, targetFact, sequence = 4)
        val targetPrevalidation = requireNotNull(
            targetFixture.store.prevalidateTargetAcknowledgement(
                SessionIdentity(1),
                targetDirect.origin,
                target(2),
            ),
        )
        val targetReady = PrevalidatedParameterTargetAcknowledgement(
            targetDirect,
            targetTransaction,
            targetFact,
            targetPrevalidation.target,
        )
        listOf(initial, recovery, frame, targetReady).forEach {
            assertSame(it, ControllerPreparedTurn(listOf(it))[0])
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrevalidatedParameterTargetAcknowledgement(
                targetDirect.copy(fact = targetFact.copy()),
                targetTransaction,
                targetFact,
                targetPrevalidation.target,
            )
        }

        val terminal = ControllerIngress.Terminal(
            IngressSequence(5),
            terminalCauseForTest(TerminalEvidence.OwnerStopped),
        )
        val facts = ControllerPreparedTurn(listOf(PreparedTerminalFact(terminal), frame))

        assertSame(frame, facts[1])
        assertSame(initialEffective, recovery.effective)
        assertEquals(TargetIdentity(2), targetReady.target.identity)
        val noOpFact = ControllerDirectFact.NormalizedNoOpReady(
            SessionIdentity(1),
            IngressSequence(6),
            TransactionIdentity(3),
            desired.identity,
        )
        val noOp = NormalizedNoOpReady(noOpFact, desired.output)
        assertEquals(DesiredSnapshotIdentity(1), noOp.desired)
        assertSame(noOp, ControllerPreparedTurn(listOf(noOp))[0])
        assertEquals(2, facts.size)
    }

    @Test
    fun storeMintedCommitTicketRejectsCrossStoreUseAndBindsTheDirectSession() {
        val provider = StoreProvider()
        val first = Fixture()
        val second = Fixture()
        val firstOwnership = first.begin(provider, id = 1)
        val secondOwnership = second.begin(provider, id = 1)
        listOf(first to firstOwnership, second to secondOwnership).forEach { (fixture, ownership) ->
            fixture.acknowledgeCandidateTarget(1)
            val store = fixture.store
            assertRecorded(store.recordCandidateDescriptor(ownership, descriptor("JPEG")))
        }
        val ticket = requireNotNull(
            first.store.prevalidateCandidateCommit(
                firstOwnership,
                SessionIdentity(1),
                CompleteOwnerIdentity(1),
                effective(1, "JPEG"),
            ),
        )

        assertSame(CandidateCommitDisposition.InvalidOwnership, second.store.commitCandidate(ticket))
        assertTrue(first.store.commitCandidate(ticket) is CandidateCommitDisposition.Committed)
        assertSame(CandidateCommitDisposition.InvalidOwnership, first.store.commitCandidate(ticket))

        val third = Fixture()
        val ownership = third.begin(provider, id = 2)
        val basic = requireNotNull(third.store.prevalidateCandidate(ownership, SessionIdentity(1)))
        assertEquals(SessionIdentity(1), basic.session)
        val transaction = ParameterTransaction(
            TransactionIdentity(2),
            DesiredSnapshotIdentity(2),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(2),
            PreparedParameterCandidateClass.SameTargetReplacement,
        )
        val fact = ParameterTransactionFact.CandidatePrepared(
            transaction.identity,
            transaction.candidate,
            CompleteOwnerIdentity(2),
        )
        val prepared = PrevalidatedCandidatePrepared(
            parameterDirect(transaction, fact, sequence = 1).copy(session = SessionIdentity(2)),
            transaction,
            fact,
        )
        assertEquals(SessionIdentity(2), (prepared.turnFact as ControllerDirectFact.Parameter).session)
    }

    @Test
    fun storeMintedCommitTicketRechecksSnapshotAtExecution() {
        val fixture = Fixture()
        val ownership = fixture.begin(StoreProvider(), id = 1)
        fixture.acknowledgeCandidateTarget(1)
        assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("JPEG")))
        val ticket = requireNotNull(
            fixture.prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(1),
                effective(1, "JPEG"),
            ),
        )

        fixture.store.abandonPhysicalTarget(TargetIdentity(1))

        assertSame(CandidateCommitDisposition.SnapshotMismatch, fixture.store.commitCandidate(ticket))
        assertEquals(1L, fixture.store.view().candidateIdentity?.value)
        assertNull(fixture.store.view().currentCompleteOwner)
    }

    @Test
    fun candidateCommitTicketRejectsDistinctSameIdentityTargetAcknowledgedAfterAbandonment() {
        val fixture = Fixture()
        val ownership = fixture.begin(StoreProvider(), id = 1)
        fixture.acknowledgeCandidateTarget(1)
        assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("JPEG")))
        val effective = effective(1, "JPEG")
        val stale = requireNotNull(
            fixture.prevalidateCommit(ownership, CompleteOwnerIdentity(1), effective),
        )

        assertTrue(fixture.store.abandonPhysicalTarget(TargetIdentity(1)))
        val distinctSameIdentity = target(1)
        assertFalse(distinctSameIdentity === stale.target)
        fixture.acknowledgeCandidateTarget(1, distinctSameIdentity)

        assertSame(CandidateCommitDisposition.SnapshotMismatch, fixture.store.commitCandidate(stale))
        val current = requireNotNull(
            fixture.prevalidateCommit(ownership, CompleteOwnerIdentity(1), effective),
        )
        assertTrue(fixture.store.commitCandidate(current) is CandidateCommitDisposition.Committed)
    }

    @Test
    fun prePublicRetirementMatrixAndTurnFamiliesRejectEveryIllegalCombination() {
        listOf(
            PrePublicStartOutcome.Failure(PrePublicStartFailureEvidence.PlatformFailure),
            PrePublicStartOutcome.CallerCancellation,
        ).forEach { outcome ->
            PrePublicProjectionFreshness.entries.forEach { freshness ->
                val legal = when (outcome) {
                    is PrePublicStartOutcome.Failure ->
                        freshness != PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness

                    PrePublicStartOutcome.CallerCancellation -> freshness in setOf(
                        PrePublicProjectionFreshness.ReusableBeforeAttachment,
                        PrePublicProjectionFreshness.DiscardConsumed,
                        PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness,
                    )
                }
                if (legal) {
                    PrePublicStartRetirement(freshness, outcome)
                } else {
                    assertThrows(IllegalArgumentException::class.java) {
                        PrePublicStartRetirement(freshness, outcome)
                    }
                }
            }
        }

        val retirement = PrePublicStartRetirement(
            PrePublicProjectionFreshness.ReusableAfterDetachAcknowledged,
            PrePublicStartOutcome.Failure(PrePublicStartFailureEvidence.PlatformFailure),
        )
        val terminal = ControllerIngress.Terminal(
            IngressSequence(1),
            terminalCauseForTest(TerminalEvidence.OwnerStopped),
        )
        ControllerPreparedTurn(
            listOf(
                PreparedTerminalFact(terminal),
                PreparedPrePublicFact(
                    ControllerDirectFact.PrePublicRetirement(SessionIdentity(1), IngressSequence(2), retirement),
                ),
            ),
        )
    }

    @Test
    fun periodicFrameCommitBindsPreviousEffectiveAndCurrentTargetSourceFence() {
        val provider = StoreProvider()
        val periodicOutput = output(NormalizedFrameRate.PeriodicRefresh(1_000L))
        val periodicDesired = desired(provider, id = 2, output = periodicOutput)
        val periodicEffective = effective(
            id = 2,
            format = "JPEG",
            output = periodicOutput,
            ownerId = 1,
            targetId = 1,
            desiredId = 2,
        )
        val transaction = ParameterTransaction(
            TransactionIdentity(2),
            periodicDesired.identity,
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(2),
            PreparedParameterCandidateClass.FrameRateOnly,
        )

        fun prevalidated(source: PeriodicRefreshSourceFence): PrevalidatedFrameRateCommit {
            val fact = ParameterTransactionFact.FrameRateCommitReady(
                transaction.identity,
                periodicDesired.identity,
                periodicEffective.identity,
                FramePacingResetFact.PeriodicRefresh(1_000L, source, commitSampleNanos = 5L),
            )
            return PrevalidatedFrameRateCommit(
                parameterDirect(transaction, fact, sequence = 1),
                SessionIdentity(1),
                transaction,
                fact,
                periodicDesired.output,
                periodicEffective,
            )
        }

        prevalidated(
            PeriodicRefreshSourceFence.NotAcquired(
                EffectiveSnapshotIdentity(1),
                previousOutputRevision = 1L,
                TargetIdentity(1),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            prevalidated(
                PeriodicRefreshSourceFence.NotAcquired(
                    EffectiveSnapshotIdentity(3),
                    previousOutputRevision = 1L,
                    TargetIdentity(1),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            prevalidated(
                PeriodicRefreshSourceFence.Acquired(
                    EffectiveSnapshotIdentity(1),
                    previousOutputRevision = 1L,
                    TargetIdentity(3),
                ),
            )
        }
    }

    @Test
    fun initialCommitKeepsIndependentDesiredAndActiveRetentions() {
        val fixture = Fixture()
        val provider = StoreProvider()
        val admitted = fixture.begin(provider, id = 1)
        fixture.acknowledgeCandidateTarget(1)
        assertRecorded(fixture.store.recordCandidateDescriptor(admitted, descriptor("JPEG")))

        assertEquals(
            CandidateCommitDisposition.Committed(retiredOwner = null),
            fixture.commitPrevalidated(admitted, CompleteOwnerIdentity(1), effective(1, "JPEG")),
        )
        val view = fixture.store.view()
        assertNull(view.provisionalDesiredIdentity)
        assertEquals(1L, view.currentDesiredIdentity?.value)
        assertEquals(1L, view.lastEffective?.identity?.value)
        assertEquals(1L, view.currentCompleteOwner?.value)
        assertEquals(1L, view.physicalCurrentTarget?.identity?.value)
        assertEquals(
            mapOf(
                ProviderDescriptorRetentionRole.Desired to 1,
                ProviderDescriptorRetentionRole.Active to 1,
            ),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
    }

    @Test
    fun differentProviderCommitAtomicallyInstallsNewOwnershipAndRetiresOld() {
        val fixture = Fixture()
        val oldProvider = StoreProvider()
        fixture.install(oldProvider, id = 1, format = "JPEG")
        val newProvider = StoreProvider()
        val admitted = fixture.begin(newProvider, id = 2)
        assertRecorded(fixture.store.recordCandidateDescriptor(admitted, descriptor("PNG")))

        val committed = fixture.commitPrevalidated(
            admitted,
            CompleteOwnerIdentity(2),
            effective(2, "PNG", targetId = 1),
        ) as CandidateCommitDisposition.Committed

        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Retiring to 1),
            fixture.ledger.snapshot(oldProvider)?.retentionCounts,
        )
        assertEquals(
            mapOf(
                ProviderDescriptorRetentionRole.Desired to 1,
                ProviderDescriptorRetentionRole.Active to 1,
            ),
            fixture.ledger.snapshot(newProvider)?.retentionCounts,
        )
        val cleanup = checkNotNull(committed.retiredOwner)
        assertSame(CleanupTransitionDisposition.Advanced, fixture.store.beginExclusiveCleanup(cleanup))
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(oldProvider))
        assertSame(CleanupTransitionDisposition.InvalidOwnership, fixture.store.retireCleanup(cleanup))
    }

    @Test
    fun sameProviderCommitNeverCreatesDescriptorGap() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        val admitted = fixture.begin(provider, id = 2)
        assertRecorded(fixture.store.recordCandidateDescriptor(admitted, descriptor("JPEG")))

        val cleanup = checkNotNull(
            (fixture.commitPrevalidated(
                admitted,
                CompleteOwnerIdentity(2),
                effective(2, "JPEG", targetId = 1),
            ) as CandidateCommitDisposition.Committed).retiredOwner,
        )
        assertEquals(
            mapOf(
                ProviderDescriptorRetentionRole.Desired to 1,
                ProviderDescriptorRetentionRole.Active to 1,
                ProviderDescriptorRetentionRole.Retiring to 1,
            ),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(CleanupTransitionDisposition.Advanced, fixture.store.beginExclusiveCleanup(cleanup))
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertEquals(2, fixture.ledger.snapshot(provider)?.liveReferenceCount)
    }

    @Test
    fun commitRequiresAcceptedDescriptorObservationFromThisCandidateToken() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        val admitted = fixture.begin(provider, id = 2)

        assertNull(
            fixture.prevalidateCommit(
                admitted,
                CompleteOwnerIdentity(2),
                effective(2, "JPEG", targetId = 1),
            ),
        )
        assertEquals(1L, fixture.store.view().currentCompleteOwner?.value)
        assertRecorded(fixture.store.recordCandidateDescriptor(admitted, descriptor("JPEG")))
        assertTrue(
            fixture.commitPrevalidated(
                admitted,
                CompleteOwnerIdentity(2),
                effective(2, "JPEG", targetId = 1),
            ) is CandidateCommitDisposition.Committed,
        )
    }

    @Test
    fun invalidCandidateDescriptorCannotCommitEvenWhenItsCopiedFormatMatches() {
        val fixture = Fixture()
        val provider = StoreProvider()
        val admitted = fixture.begin(provider, id = 1)
        fixture.acknowledgeCandidateTarget(1)
        val invalid = DescriptorSyntax.copySnapshot("provider", "JPEG", "IMAGE/JPEG")

        assertEquals(
            ProviderDescriptorRecordResult.InvalidDescriptor(invalid),
            fixture.store.recordCandidateDescriptor(admitted, invalid),
        )
        assertNull(
            fixture.prevalidateCommit(
                admitted,
                CompleteOwnerIdentity(1),
                effective(1, "JPEG", mimeType = "IMAGE/JPEG"),
            ),
        )
        assertSame(
            CandidateDispositionOutcome.Released,
            fixture.dispositionCandidate(1, CandidateDispositionAction.ReleaseUnstarted),
        )
        assertNull(fixture.ledger.snapshot(provider))
    }

    @Test
    fun candidateCommitRejectsDescriptorViolationAndAcceptedFormatMismatch() {
        val sharedFixture = Fixture()
        val sharedProvider = StoreProvider()
        sharedFixture.install(sharedProvider, id = 1, format = "JPEG")
        val violatingCandidate = sharedFixture.begin(sharedProvider, id = 2)
        assertTrue(
            sharedFixture.store.recordCandidateDescriptor(violatingCandidate, descriptor("PNG"))
                    is ProviderDescriptorRecordResult.DescriptorViolation,
        )
        assertNull(
            sharedFixture.prevalidateCommit(
                violatingCandidate,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", targetId = 1),
            ),
        )
        assertSame(
            CandidateDispositionOutcome.Released,
            sharedFixture.dispositionCandidate(2, CandidateDispositionAction.ReleaseUnstarted),
        )

        val copiedFormatFixture = Fixture()
        val copiedFormatProvider = StoreProvider()
        val acceptedCandidate = copiedFormatFixture.begin(copiedFormatProvider, id = 1)
        copiedFormatFixture.acknowledgeCandidateTarget(1)
        assertRecorded(copiedFormatFixture.store.recordCandidateDescriptor(acceptedCandidate, descriptor("JPEG")))
        assertNull(
            copiedFormatFixture.prevalidateCommit(
                acceptedCandidate,
                CompleteOwnerIdentity(1),
                effective(1, "PNG"),
            ),
        )
        assertSame(
            CandidateDispositionOutcome.Released,
            copiedFormatFixture.dispositionCandidate(1, CandidateDispositionAction.ReleaseUnstarted),
        )
        assertNull(copiedFormatFixture.ledger.snapshot(copiedFormatProvider))
    }

    @Test
    fun candidateCommitPrevalidationRejectsEverySnapshotMismatchAndReusedFence() {
        fun verify(block: Fixture.(ControllerCandidateOwnership) -> ControllerCandidateCommitPrevalidation?) {
            val fixture = Fixture()
            fixture.install(StoreProvider(), id = 1, format = "JPEG")
            val ownership = fixture.begin(StoreProvider(), id = 2)
            assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("PNG")))
            assertNull(fixture.block(ownership))
        }

        verify { ownership ->
            store.abandonPhysicalTarget(TargetIdentity(1))
            prevalidateCommit(ownership, CompleteOwnerIdentity(2), effective(2, "PNG", targetId = 1))
        }
        verify { ownership ->
            prevalidateCommit(ownership, CompleteOwnerIdentity(2), effective(2, "PNG", targetId = 2))
        }
        verify { ownership ->
            prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", desiredId = 3, targetId = 1),
            )
        }
        verify { ownership ->
            prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", output = output(NormalizedFrameRate.MaxFps(30)), targetId = 1),
            )
        }
        verify { ownership ->
            prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", geometry = geometry.copy(densityDpi = 321), targetId = 1),
            )
        }
        verify { ownership ->
            prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", plan = plan.copy(rowStrideBytes = plan.rowStrideBytes + 4), targetId = 1),
            )
        }
        verify { ownership ->
            prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", ownerId = 3, targetId = 1),
            )
        }
        verify { ownership ->
            prevalidateCommit(ownership, CompleteOwnerIdentity(1), effective(2, "PNG", ownerId = 1, targetId = 1))
        }
        verify { ownership ->
            prevalidateCommit(ownership, CompleteOwnerIdentity(2), effective(1, "PNG", desiredId = 2, targetId = 1))
        }
        verify { ownership ->
            store.quarantineCurrentOwner(CompleteOwnerIdentity(1))
            prevalidateCommit(ownership, CompleteOwnerIdentity(2), effective(2, "PNG", targetId = 1))
        }
        listOf(
            target(2).copy(
                geometry = geometry.copy(width = 101),
                samplingCapacity = SamplingDemand(PositiveRatio(100, 101), PositiveRatio(1, 1)),
            ),
            target(2).copy(geometry = geometry.copy(densityDpi = 321)),
            target(2).copy(wholeGeometryMappingValidated = false),
            target(2).copy(health = TargetHealthEvidence.Poisoned),
            target(2).copy(
                samplingCapacity = SamplingDemand(PositiveRatio(1, 3), PositiveRatio(1, 3)),
            ),
        ).forEach { mismatchedTarget ->
            val fixture = Fixture()
            val oldProvider = StoreProvider()
            val candidateProvider = StoreProvider()
            fixture.install(oldProvider, id = 1, format = "JPEG")
            val oldEffective = fixture.store.view().lastEffective
            val oldLedgerBefore = fixture.ledger.snapshot(oldProvider)
            val ownership = fixture.begin(candidateProvider, id = 2)
            assertRecorded(fixture.store.recordCandidateDescriptor(ownership, descriptor("PNG")))
            val candidateLedgerBefore = fixture.ledger.snapshot(candidateProvider)
            assertNull(
                fixture.store.prevalidateTargetAcknowledgement(
                    SessionIdentity(1),
                    ControllerFactOrigin.Startup(CandidateIdentity(2)),
                    mismatchedTarget,
                ),
            )

            assertNull(
                fixture.prevalidateCommit(
                    ownership,
                    CompleteOwnerIdentity(2),
                    effective(2, "PNG", targetId = 2),
                ),
            )
            assertSame(oldEffective, fixture.store.view().lastEffective)
            assertEquals(1L, fixture.store.view().currentDesiredIdentity?.value)
            assertEquals(1L, fixture.store.view().currentCompleteOwner?.value)
            assertEquals(2L, fixture.store.view().candidateIdentity?.value)
            assertEquals(oldLedgerBefore, fixture.ledger.snapshot(oldProvider))
            assertEquals(candidateLedgerBefore, fixture.ledger.snapshot(candidateProvider))
            assertSame(
                CandidateDispositionOutcome.Released,
                fixture.dispositionCandidate(2, CandidateDispositionAction.ReleaseUnstarted),
            )
            assertNull(fixture.ledger.snapshot(candidateProvider))
        }

        val duplicateDesired = Fixture()
        duplicateDesired.install(StoreProvider(), id = 1, format = "JPEG")
        val provider = StoreProvider()
        val ownership = (duplicateDesired.store.beginCandidate(
            candidate(provider, id = 2, desiredId = 1),
        ) as CandidateOwnershipAdmission.Admitted).ownership
        assertRecorded(duplicateDesired.store.recordCandidateDescriptor(ownership, descriptor("PNG")))
        assertNull(
            duplicateDesired.prevalidateCommit(
                ownership,
                CompleteOwnerIdentity(2),
                effective(2, "PNG", desiredId = 1, targetId = 1),
            ),
        )
    }

    @Test
    fun noOpAndFrameRateOnlyRetainAllLedgerOwnership() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        val before = fixture.ledger.snapshot(provider)
        val beforeView = fixture.store.view()

        assertTrue(fixture.store.retainNormalizedNoOp(DesiredSnapshotIdentity(1)))
        val disguisedNoOp = desired(provider, id = 2)
        assertFalse(
            fixture.store.commitFrameRateOnly(
                disguisedNoOp,
                effective(2, "JPEG", disguisedNoOp.output, ownerId = 1, targetId = 1),
            ),
        )
        assertEquals(beforeView.currentDesiredIdentity, fixture.store.view().currentDesiredIdentity)
        assertSame(beforeView.lastEffective, fixture.store.view().lastEffective)
        assertEquals(before, fixture.ledger.snapshot(provider))

        val frameChangeWithReusedEffectiveFence = desired(
            provider,
            id = 3,
            output = output(frameRate = NormalizedFrameRate.MaxFps(30)),
        )
        assertFalse(
            fixture.store.commitFrameRateOnly(
                frameChangeWithReusedEffectiveFence,
                effective(1, "JPEG", frameChangeWithReusedEffectiveFence.output, ownerId = 1, targetId = 1),
            ),
        )
        assertEquals(before, fixture.ledger.snapshot(provider))

        val targetAdvancedFixture = Fixture()
        targetAdvancedFixture.install(provider, id = 1, format = "JPEG")
        val targetAdvancedLedgerBefore = targetAdvancedFixture.ledger.snapshot(provider)
        targetAdvancedFixture.begin(provider, id = 2)
        targetAdvancedFixture.acknowledgeCandidateTarget(2)
        assertSame(
            CandidateDispositionOutcome.Released,
            targetAdvancedFixture.dispositionCandidate(2, CandidateDispositionAction.ReleaseUnstarted),
        )
        val targetAdvancedBefore = targetAdvancedFixture.store.view()
        val targetAdvancedDesired = desired(
            provider,
            id = 4,
            output = output(frameRate = NormalizedFrameRate.MaxFps(30)),
        )
        assertFalse(
            targetAdvancedFixture.store.commitFrameRateOnly(
                targetAdvancedDesired,
                effective(4, "JPEG", targetAdvancedDesired.output, ownerId = 1, targetId = 2),
            ),
        )
        assertSame(targetAdvancedBefore.lastEffective, targetAdvancedFixture.store.view().lastEffective)
        assertEquals(
            targetAdvancedBefore.currentDesiredIdentity,
            targetAdvancedFixture.store.view().currentDesiredIdentity,
        )
        assertEquals(targetAdvancedBefore.currentCompleteOwner, targetAdvancedFixture.store.view().currentCompleteOwner)
        assertEquals(
            targetAdvancedBefore.physicalCurrentTarget,
            targetAdvancedFixture.store.view().physicalCurrentTarget,
        )
        assertEquals(targetAdvancedLedgerBefore, targetAdvancedFixture.ledger.snapshot(provider))

        val desired = desired(provider, id = 4, output = output(frameRate = NormalizedFrameRate.MaxFps(30)))
        assertTrue(
            fixture.store.commitFrameRateOnly(
                desired,
                effective(4, "JPEG", desired.output, ownerId = 1, targetId = 1),
            ),
        )

        assertEquals(before, fixture.ledger.snapshot(provider))
        assertEquals(4L, fixture.store.view().currentDesiredIdentity?.value)
        assertEquals(1L, fixture.store.view().currentCompleteOwner?.value)
        assertFalse(fixture.store.retainNormalizedNoOp(DesiredSnapshotIdentity(1)))
    }

    @Test
    fun unstartedAndReturnedCandidateRollbackHaveExactReleaseDispositions() {
        val fixture = Fixture()
        val firstProvider = StoreProvider()
        fixture.begin(firstProvider, id = 1)
        assertSame(
            CandidateDispositionOutcome.Released,
            fixture.dispositionCandidate(1, CandidateDispositionAction.ReleaseUnstarted),
        )
        assertNull(fixture.ledger.snapshot(firstProvider))

        val secondProvider = StoreProvider()
        fixture.begin(secondProvider, id = 2)
        val cleanup = (fixture.dispositionCandidate(
            2,
            CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = true),
            CandidateDispositionTrigger.PreparationFailure,
        ) as CandidateDispositionOutcome.CleanupRequired).cleanup
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Cleanup to 1),
            fixture.ledger.snapshot(secondProvider)?.retentionCounts,
        )
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(secondProvider))
    }

    @Test
    fun staleStartedCandidateRetainsLateIdentityUntilReturnAndClose() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.begin(provider, id = 1)

        val cleanup = (fixture.dispositionCandidate(
            1,
            CandidateDispositionAction.RetainAwaitingLateReturn,
        ) as CandidateDispositionOutcome.LateReturnRetained).cleanup
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Late to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(
            CleanupTransitionDisposition.Advanced,
            fixture.store.latePreparationReturned(cleanup, returnedEncoderNeedsCleanup = true),
        )
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Cleanup to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(provider))
    }

    @Test
    fun staleLateReturnWithoutEncoderReleasesExactlyOnce() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.begin(provider, id = 1)
        val cleanup = (fixture.dispositionCandidate(
            1,
            CandidateDispositionAction.RetainAwaitingLateReturn,
        ) as CandidateDispositionOutcome.LateReturnRetained).cleanup

        assertSame(
            CleanupTransitionDisposition.Released,
            fixture.store.latePreparationReturned(cleanup, returnedEncoderNeedsCleanup = false),
        )
        assertSame(
            CleanupTransitionDisposition.InvalidOwnership,
            fixture.store.latePreparationReturned(cleanup, returnedEncoderNeedsCleanup = false),
        )
        assertNull(fixture.ledger.snapshot(provider))
    }

    @Test
    fun timedOutPreparationKeepsStickyPoisonButLateEncoderUsesSameTokenUntilCleanup() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.begin(provider, id = 1)
        val quarantine = (fixture.dispositionCandidate(
            1,
            CandidateDispositionAction.QuarantineStartedTimeout,
            CandidateDispositionTrigger.PreparationStartedTimeout,
        ) as CandidateDispositionOutcome.Quarantined).quarantine

        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Quarantine to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        val cleanup = (fixture.store.preparationQuarantineReturned(
            quarantine,
            returnedEncoderNeedsCleanup = true,
        ) as QuarantineReturnDisposition.CleanupRequired).cleanup
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Cleanup to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertFalse(fixture.store.view().preparationQuarantined)
        assertTrue(fixture.store.view().providerPoisoned)
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(provider))
        assertSame(
            QuarantineReturnDisposition.InvalidOwnership,
            fixture.store.preparationQuarantineReturned(quarantine, returnedEncoderNeedsCleanup = true),
        )
        assertSame(
            CandidateOwnershipAdmission.EnginePoisoned,
            fixture.store.beginCandidate(candidate(StoreProvider(), id = 2)),
        )
    }

    @Test
    fun preparationQuarantineRejectsForeignAndStaleCapabilitiesWithoutMovingItsToken() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.begin(provider, id = 1)
        val quarantine = (fixture.dispositionCandidate(
            1,
            CandidateDispositionAction.QuarantineStartedTimeout,
            CandidateDispositionTrigger.PreparationStartedTimeout,
        ) as CandidateDispositionOutcome.Quarantined).quarantine
        val foreignFixture = Fixture()
        foreignFixture.begin(StoreProvider(), id = 2)
        val foreignQuarantine = (foreignFixture.dispositionCandidate(
            2,
            CandidateDispositionAction.QuarantineStartedTimeout,
            CandidateDispositionTrigger.PreparationStartedTimeout,
        ) as CandidateDispositionOutcome.Quarantined).quarantine

        assertSame(
            QuarantineReturnDisposition.InvalidOwnership,
            fixture.store.preparationQuarantineReturned(foreignQuarantine, returnedEncoderNeedsCleanup = true),
        )
        assertSame(
            QuarantineReturnDisposition.ReturnedWithoutEncoder,
            foreignFixture.store.preparationQuarantineReturned(
                foreignQuarantine,
                returnedEncoderNeedsCleanup = false,
            ),
        )
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Quarantine to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(
            QuarantineReturnDisposition.ReturnedWithoutEncoder,
            fixture.store.preparationQuarantineReturned(quarantine, returnedEncoderNeedsCleanup = false),
        )
        assertSame(
            QuarantineReturnDisposition.InvalidOwnership,
            fixture.store.preparationQuarantineReturned(quarantine, returnedEncoderNeedsCleanup = false),
        )
        assertNull(fixture.ledger.snapshot(provider))
    }

    @Test
    fun timedOutPreparationWithoutEncoderReleasesTokenButKeepsStickyPoison() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.begin(provider, id = 1)
        val quarantine = (fixture.dispositionCandidate(
            1,
            CandidateDispositionAction.QuarantineStartedTimeout,
            CandidateDispositionTrigger.PreparationStartedTimeout,
        ) as CandidateDispositionOutcome.Quarantined).quarantine

        assertSame(
            QuarantineReturnDisposition.ReturnedWithoutEncoder,
            fixture.store.preparationQuarantineReturned(quarantine, returnedEncoderNeedsCleanup = false),
        )
        assertNull(fixture.ledger.snapshot(provider))
        assertEquals(0, fixture.store.view().cleanupRecordCount)
        assertFalse(fixture.store.view().preparationQuarantined)
        assertTrue(fixture.store.view().providerPoisoned)
        assertSame(
            QuarantineReturnDisposition.InvalidOwnership,
            fixture.store.preparationQuarantineReturned(quarantine, returnedEncoderNeedsCleanup = false),
        )
        assertSame(
            CandidateOwnershipAdmission.EnginePoisoned,
            fixture.store.beginCandidate(candidate(StoreProvider(), id = 2)),
        )
    }

    @Test
    fun twoOrdinaryCleanupRecordsLeaveThirdSlotForTerminalOwner() {
        val fixture = Fixture()
        val first = StoreProvider()
        fixture.install(first, id = 1, format = "F1")
        val secondCleanup = fixture.replace(StoreProvider(), id = 2, format = "F2")
        val thirdCleanup = fixture.replace(StoreProvider(), id = 3, format = "F3")
        assertEquals(2, fixture.store.view().cleanupRecordCount)
        assertSame(
            CandidateOwnershipAdmission.CleanupCapacityExceeded,
            fixture.store.beginCandidate(candidate(StoreProvider(), id = 4)),
        )

        val terminalCleanup = checkNotNull(
            (fixture.store.commitTerminal() as TerminalOwnershipDisposition.Retired).currentOwnerCleanup,
        )
        assertEquals(3, fixture.store.view().cleanupRecordCount)
        listOf(secondCleanup, thirdCleanup, terminalCleanup).forEach { cleanup ->
            assertSame(CleanupTransitionDisposition.Advanced, fixture.store.beginExclusiveCleanup(cleanup))
            assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        }
        assertEquals(0, fixture.store.view().cleanupRecordCount)
        assertTrue(fixture.store.view().terminal)
    }

    @Test
    fun terminalRequiresCandidateDispositionAndKeepsProviderFreeEffectiveUntilReleased() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        val candidateProvider = StoreProvider()
        fixture.begin(candidateProvider, id = 2)
        assertSame(TerminalOwnershipDisposition.CandidateStillOwned, fixture.store.commitTerminal())
        assertSame(
            CandidateDispositionOutcome.Released,
            fixture.dispositionCandidate(2, CandidateDispositionAction.ReleaseUnstarted),
        )

        val cleanup = checkNotNull(
            (fixture.store.commitTerminal() as TerminalOwnershipDisposition.Retired).currentOwnerCleanup,
        )
        val terminalView = fixture.store.view()
        assertNull(terminalView.currentDesiredIdentity)
        assertNull(terminalView.currentCompleteOwner)
        assertNull(terminalView.physicalCurrentTarget)
        assertEquals(1L, terminalView.lastEffective?.identity?.value)
        assertSame(TerminalOwnershipDisposition.AlreadyTerminal, fixture.store.commitTerminal())
        assertSame(CleanupTransitionDisposition.Advanced, fixture.store.beginExclusiveCleanup(cleanup))
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(provider))
        fixture.store.releaseLastEffective()
        assertNull(fixture.store.view().lastEffective)
    }

    @Test
    fun lastEffectiveCannotBeReleasedBeforeTerminal() {
        val fixture = Fixture()
        fixture.install(StoreProvider(), id = 1, format = "JPEG")

        assertThrows(IllegalStateException::class.java) {
            fixture.store.releaseLastEffective()
        }
        assertEquals(1L, fixture.store.view().lastEffective?.identity?.value)
    }

    @Test
    fun physicalTargetCanAdvanceIndependentlyOfLastEffective() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")
        fixture.begin(provider, id = 2)
        fixture.acknowledgeCandidateTarget(2)
        assertSame(
            CandidateDispositionOutcome.Released,
            fixture.dispositionCandidate(2, CandidateDispositionAction.ReleaseUnstarted),
        )
        val view = fixture.store.view()
        assertEquals(1L, view.lastEffective?.targetIdentity?.value)
        assertEquals(2L, view.physicalCurrentTarget?.identity?.value)
        assertTrue(fixture.store.abandonPhysicalTarget(TargetIdentity(2)))
        assertFalse(fixture.store.abandonPhysicalTarget(TargetIdentity(2)))
    }

    @Test
    fun activeEncoderTimeoutUsesReservedTerminalCleanupAfterLateReturn() {
        val fixture = Fixture()
        val provider = StoreProvider()
        fixture.install(provider, id = 1, format = "JPEG")

        assertFalse(fixture.store.quarantineCurrentOwner(CompleteOwnerIdentity(2)))
        assertTrue(fixture.store.quarantineCurrentOwner(CompleteOwnerIdentity(1)))
        assertFalse(fixture.store.quarantineCurrentOwner(CompleteOwnerIdentity(1)))
        assertSame(
            ActiveOwnerReturnDisposition.InvalidOwnership,
            fixture.store.activeOwnerQuarantineReturned(CompleteOwnerIdentity(2)),
        )
        assertSame(
            ActiveOwnerReturnDisposition.InvalidOwnership,
            fixture.store.activeOwnerQuarantineReturned(CompleteOwnerIdentity(1)),
        )
        assertEquals(
            mapOf(
                ProviderDescriptorRetentionRole.Desired to 1,
                ProviderDescriptorRetentionRole.Quarantine to 1,
            ),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertEquals(
            TerminalOwnershipDisposition.Retired(currentOwnerCleanup = null),
            fixture.store.commitTerminal(),
        )
        val view = fixture.store.view()
        assertTrue(view.activeOwnerQuarantined)
        assertTrue(view.providerPoisoned)
        assertNull(view.currentCompleteOwner)
        assertEquals(0, view.cleanupRecordCount)
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Quarantine to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(
            CandidateOwnershipAdmission.Terminal,
            fixture.store.beginCandidate(candidate(StoreProvider(), id = 2)),
        )

        val cleanup = (fixture.store.activeOwnerQuarantineReturned(
            CompleteOwnerIdentity(1),
        ) as ActiveOwnerReturnDisposition.CleanupRequired).cleanup
        assertFalse(fixture.store.view().activeOwnerQuarantined)
        assertEquals(1, fixture.store.view().cleanupRecordCount)
        assertEquals(
            mapOf(ProviderDescriptorRetentionRole.Cleanup to 1),
            fixture.ledger.snapshot(provider)?.retentionCounts,
        )
        assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
        assertNull(fixture.ledger.snapshot(provider))
        assertTrue(fixture.store.view().providerPoisoned)
        assertSame(
            ActiveOwnerReturnDisposition.InvalidOwnership,
            fixture.store.activeOwnerQuarantineReturned(CompleteOwnerIdentity(1)),
        )
    }

    @Test
    fun currentCandidateRemintBindsSessionCandidateAndFullOriginWithoutLeakingCapability() {
        val provider = StoreProvider()
        val first = Fixture()
        val second = Fixture()
        first.begin(provider, id = 7)
        second.begin(provider, id = 7)
        val origin = ControllerFactOrigin.Parameter(TransactionIdentity(11), CandidateIdentity(7))

        val ticket = requireNotNull(first.store.prevalidateCurrentCandidate(SessionIdentity(3), origin))
        assertEquals(SessionIdentity(3), ticket.session)
        assertEquals(CandidateIdentity(7), ticket.candidate.identity)
        assertSame(origin, ticket.origin)
        assertNull(
            first.store.prevalidateCurrentCandidate(
                SessionIdentity(3),
                ControllerFactOrigin.Parameter(TransactionIdentity(11), CandidateIdentity(8)),
            ),
        )

        val disposition = requireNotNull(
            first.store.prevalidateCandidateDisposition(
                SessionIdentity(3),
                origin,
                IngressSequence(1),
                CandidateDispositionTrigger.Cancellation,
                CandidateDispositionAction.ReleaseUnstarted,
            ),
        )
        assertSame(CandidateDispositionOutcome.InvalidAuthority, second.store.dispositionCandidate(disposition))
        assertSame(CandidateDispositionOutcome.Released, first.store.dispositionCandidate(disposition))
        assertSame(CandidateDispositionOutcome.InvalidAuthority, first.store.dispositionCandidate(disposition))
        assertNull(first.ledger.snapshot(provider))

        val prePublic = Fixture()
        prePublic.begin(provider, id = 9)
        val startup = ControllerFactOrigin.Startup(CandidateIdentity(9))
        assertNull(
            prePublic.store.prevalidateCandidateDisposition(
                SessionIdentity(3),
                startup,
                IngressSequence(2),
                CandidateDispositionTrigger.PrePublicRetirement,
                CandidateDispositionAction.ReleaseUnstarted,
            ),
        )
        val prePublicTicket = requireNotNull(
            prePublic.store.prevalidateCandidateDisposition(
                SessionIdentity(3),
                startup,
                null,
                CandidateDispositionTrigger.PrePublicRetirement,
                CandidateDispositionAction.ReleaseUnstarted,
            ),
        )
        assertNull(prePublicTicket.triggerSequence)
        assertSame(CandidateDispositionOutcome.Released, prePublic.store.dispositionCandidate(prePublicTicket))

    }

    @Test
    fun candidateDispositionTriggersHaveClosedActionsAndExposeCapabilitiesOnlyAfterMutation() {
        val requiredCleanupTriggers = listOf(
            CandidateDispositionTrigger.ReadyPermitRejected,
            CandidateDispositionTrigger.RetargetStartTimedOut,
            CandidateDispositionTrigger.LateAcknowledgedSupersession,
        )
        requiredCleanupTriggers.forEachIndexed { index, trigger ->
            val fixture = Fixture()
            val provider = StoreProvider()
            val candidateId = index + 1L
            fixture.begin(provider, candidateId)
            val origin = ControllerFactOrigin.Parameter(TransactionIdentity(candidateId), CandidateIdentity(candidateId))
            assertNull(
                fixture.store.prevalidateCandidateDisposition(
                    SessionIdentity(1),
                    origin,
                    IngressSequence(1),
                    trigger,
                    CandidateDispositionAction.ReleaseUnstarted,
                ),
            )
            val ticket = requireNotNull(
                fixture.store.prevalidateCandidateDisposition(
                    SessionIdentity(1),
                    origin,
                    IngressSequence(1),
                    trigger,
                    CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = true),
                ),
            )
            val cleanup = (fixture.store.dispositionCandidate(ticket) as CandidateDispositionOutcome.CleanupRequired)
                .cleanup
            assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(cleanup))
            assertNull(fixture.ledger.snapshot(provider))
        }

        val preparation = Fixture()
        val preparationProvider = StoreProvider()
        preparation.begin(preparationProvider, 10)
        val preparationOrigin = ControllerFactOrigin.Startup(CandidateIdentity(10))
        listOf(
            CandidateDispositionAction.ReleaseUnstarted,
            CandidateDispositionAction.RetainAwaitingLateReturn,
            CandidateDispositionAction.QuarantineStartedTimeout,
        ).forEach { illegalAction ->
            assertNull(
                preparation.store.prevalidateCandidateDisposition(
                    SessionIdentity(1),
                    preparationOrigin,
                    IngressSequence(1),
                    CandidateDispositionTrigger.PreparationFailure,
                    illegalAction,
                ),
            )
        }
        val preparationTicket = requireNotNull(
            preparation.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                preparationOrigin,
                IngressSequence(1),
                CandidateDispositionTrigger.PreparationFailure,
                CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = false),
            ),
        )
        assertSame(CandidateDispositionOutcome.Released, preparation.store.dispositionCandidate(preparationTicket))
        assertNull(preparation.ledger.snapshot(preparationProvider))

        val late = Fixture()
        val lateProvider = StoreProvider()
        late.begin(lateProvider, 11)
        val lateTicket = requireNotNull(
            late.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(11)),
                IngressSequence(1),
                CandidateDispositionTrigger.GeometryPreemption,
                CandidateDispositionAction.RetainAwaitingLateReturn,
            ),
        )
        val lateCleanup = (late.store.dispositionCandidate(lateTicket) as CandidateDispositionOutcome.LateReturnRetained)
            .cleanup
        assertSame(
            CleanupTransitionDisposition.Released,
            late.store.latePreparationReturned(lateCleanup, returnedEncoderNeedsCleanup = false),
        )

        val quarantine = Fixture()
        val quarantineProvider = StoreProvider()
        quarantine.begin(quarantineProvider, 12)
        val quarantineTicket = requireNotNull(
            quarantine.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                ControllerFactOrigin.Startup(CandidateIdentity(12)),
                IngressSequence(1),
                CandidateDispositionTrigger.PreparationStartedTimeout,
                CandidateDispositionAction.QuarantineStartedTimeout,
            ),
        )
        assertNull(
            quarantine.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                ControllerFactOrigin.Startup(CandidateIdentity(12)),
                IngressSequence(1),
                CandidateDispositionTrigger.PreparationStartedTimeout,
                CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = true),
            ),
        )
        val quarantineOwnership =
            (quarantine.store.dispositionCandidate(quarantineTicket) as CandidateDispositionOutcome.Quarantined)
                .quarantine
        assertTrue(quarantine.store.view().preparationQuarantined)
        assertTrue(quarantine.store.view().providerPoisoned)
        assertSame(
            QuarantineReturnDisposition.ReturnedWithoutEncoder,
            quarantine.store.preparationQuarantineReturned(quarantineOwnership, returnedEncoderNeedsCleanup = false),
        )
        assertTrue(quarantine.store.view().providerPoisoned)
    }

    @Test
    fun recoveryReturnedFailureAndParameterStartedTimeoutConsumeOnlyTheirExactStoreActions() {
        val provider = StoreProvider()
        val returned = Fixture()
        returned.begin(provider, id = 30)
        val returnedOrigin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(30), CandidateIdentity(30))
        val returnedFact = ControllerDirectFact.RecoveryCandidatePreparationFailed(
            SessionIdentity(1),
            IngressSequence(1),
            returnedOrigin,
            RecoveryCandidatePreparationFailureEvidence.EncoderSetupFailed,
            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
        )
        val returnedTicket = requireNotNull(
            returned.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                returnedOrigin,
                returnedFact.sequence,
                CandidateDispositionTrigger.PreparationFailure,
                CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = false),
            ),
        )
        val preparedReturned = PrevalidatedRecoveryCandidatePreparationFailed(returnedFact)
        assertSame(returnedFact, preparedReturned.turnFact)
        assertSame(preparedReturned, ControllerPreparedTurn(listOf(preparedReturned))[0])
        assertSame(CandidateDispositionOutcome.Released, returned.store.dispositionCandidate(returnedTicket))

        val timeout = Fixture()
        timeout.begin(provider, id = 31)
        val timeoutTransaction = ParameterTransaction(
            TransactionIdentity(31),
            DesiredSnapshotIdentity(31),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(31),
            PreparedParameterCandidateClass.SameTargetReplacement,
        )
        val timeoutFact = ParameterTransactionFact.CandidatePreparationStartedTimedOut(
            timeoutTransaction.identity,
            timeoutTransaction.candidate,
        )
        val timeoutDirect = parameterDirect(timeoutTransaction, timeoutFact, sequence = 2)
        val timeoutTicket = requireNotNull(
            timeout.store.prevalidateCandidateDisposition(
                SessionIdentity(1),
                timeoutDirect.origin,
                timeoutDirect.sequence,
                CandidateDispositionTrigger.PreparationStartedTimeout,
                CandidateDispositionAction.QuarantineStartedTimeout,
            ),
        )
        val preparedTimeout = PrevalidatedCandidatePreparationStartedTimeout(
            timeoutDirect,
            timeoutTransaction,
            timeoutFact,
        )
        assertSame(timeoutDirect, preparedTimeout.turnFact)
        assertSame(preparedTimeout, ControllerPreparedTurn(listOf(preparedTimeout))[0])
        assertTrue(timeout.store.dispositionCandidate(timeoutTicket) is CandidateDispositionOutcome.Quarantined)
    }

    @Test
    fun targetAcknowledgementTicketBindsCandidateCurrentTargetGeometryMappingAndStore() {
        val provider = StoreProvider()
        val fixture = Fixture()
        fixture.install(provider, id = 1, format = "JPEG")
        fixture.begin(provider, id = 2)
        val origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(9), CandidateIdentity(2))
        val replacement = target(2)
        val ticket = requireNotNull(
            fixture.store.prevalidateTargetAcknowledgement(SessionIdentity(4), origin, replacement),
        )
        assertEquals(SessionIdentity(4), ticket.session)
        assertSame(origin, ticket.origin)
        assertEquals(geometry, ticket.geometry)
        assertEquals(TargetIdentity(1).value, ticket.previousTarget?.identity?.value)
        assertSame(replacement, ticket.target)
        val acknowledgementFact = ControllerDirectFact.TargetAcknowledged(
            session = SessionIdentity(4),
            sequence = IngressSequence(1),
            origin = origin,
            target = replacement.identity,
        )
        val preparedAcknowledgement = PrevalidatedRecoveryTargetAcknowledgement(acknowledgementFact, ticket.target)
        assertSame(ticket.target, preparedAcknowledgement.target)
        assertSame(preparedAcknowledgement, ControllerPreparedTurn(listOf(preparedAcknowledgement))[0])

        listOf(
            replacement.copy(assignment = TargetAssignmentEvidence.NotAcknowledged),
            replacement.copy(wholeGeometryMappingValidated = false),
            replacement.copy(
                geometry = geometry.copy(width = geometry.width + 1),
                targetSize = Size(geometry.width, geometry.height),
                samplingCapacity = SamplingDemand(
                    PositiveRatio(geometry.width, geometry.width + 1),
                    PositiveRatio(1, 1),
                ),
            ),
            replacement.copy(
                samplingCapacity = SamplingDemand(PositiveRatio(1, 4), PositiveRatio(1, 4)),
            ),
        ).forEach { invalid ->
            assertNull(fixture.store.prevalidateTargetAcknowledgement(SessionIdentity(4), origin, invalid))
        }

        val foreign = Fixture()
        foreign.begin(provider, id = 2)
        assertSame(TargetAcknowledgementDisposition.InvalidAuthority, foreign.store.acknowledgePhysicalTarget(ticket))
        val acknowledged = fixture.store.acknowledgePhysicalTarget(ticket) as TargetAcknowledgementDisposition.Acknowledged
        assertEquals(TargetIdentity(1).value, acknowledged.previousTarget?.identity?.value)
        assertEquals(TargetIdentity(2).value, fixture.store.view().physicalCurrentTarget?.identity?.value)
        assertSame(TargetAcknowledgementDisposition.SnapshotMismatch, fixture.store.acknowledgePhysicalTarget(ticket))

        val parameter = Fixture()
        parameter.install(provider, id = 10, format = "JPEG")
        parameter.begin(provider, id = 11)
        val parameterOrigin = ControllerFactOrigin.Parameter(TransactionIdentity(12), CandidateIdentity(11))
        assertNull(
            parameter.store.prevalidateTargetAcknowledgement(
                SessionIdentity(13),
                ControllerFactOrigin.Parameter(TransactionIdentity(12), CandidateIdentity(12)),
                target(11),
            ),
        )
        val parameterTicket = requireNotNull(
            parameter.store.prevalidateTargetAcknowledgement(SessionIdentity(13), parameterOrigin, target(11)),
        )
        assertEquals(parameterOrigin.transaction, (parameterTicket.origin as ControllerFactOrigin.Parameter).transaction)
        assertEquals(CandidateIdentity(11), parameterTicket.candidate.identity)
        assertEquals(TargetIdentity(10).value, parameterTicket.previousTarget?.identity?.value)
        assertTrue(
            parameter.store.acknowledgePhysicalTarget(parameterTicket) is TargetAcknowledgementDisposition.Acknowledged,
        )
    }

    @Test
    fun terminalCandidateDispositionTicketBindsAcceptedTerminalSessionCandidateStoreAndReplay() {
        val provider = StoreProvider()
        val first = Fixture()
        val second = Fixture()
        first.begin(provider, id = 21)
        second.begin(provider, id = 21)
        val terminal = ControllerIngress.Terminal(
            IngressSequence(31),
            terminalCauseForTest(TerminalEvidence.StartedGlTimeout),
        )
        val ticket = requireNotNull(
            first.store.prevalidateTerminalCandidateDisposition(
                SessionIdentity(41),
                CandidateIdentity(21),
                terminal,
                CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = true),
            ),
        )
        assertEquals(SessionIdentity(41), ticket.session)
        assertEquals(CandidateIdentity(21), ticket.candidate.identity)
        assertSame(terminal, ticket.terminal)
        assertEquals(IngressSequence(31), ticket.terminal.sequence)
        assertEquals(TerminalEvidence.StartedGlTimeout, ticket.terminal.evidence)
        assertSame(terminal, PreparedTerminalFact(terminal).turnFact)
        assertSame(CandidateDispositionOutcome.InvalidAuthority, second.store.dispositionCandidate(ticket))
        val cleanup = (first.store.dispositionCandidate(ticket) as CandidateDispositionOutcome.CleanupRequired).cleanup
        assertSame(CandidateDispositionOutcome.InvalidAuthority, first.store.dispositionCandidate(ticket))
        assertSame(CleanupTransitionDisposition.Released, first.store.retireCleanup(cleanup))
        assertNull(first.ledger.snapshot(provider))
        assertEquals(CandidateIdentity(21).value, second.store.view().candidateIdentity?.value)

        assertNull(
            second.store.prevalidateTerminalCandidateDisposition(
                SessionIdentity(41),
                CandidateIdentity(22),
                terminal,
                CandidateDispositionAction.ReleaseUnstarted,
            ),
        )

        listOf(
            CandidateDispositionAction.ReleaseUnstarted,
            CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = false),
            CandidateDispositionAction.RetireReturned(returnedEncoderNeedsCleanup = true),
            CandidateDispositionAction.RetainAwaitingLateReturn,
        ).forEachIndexed { index, action ->
            val fixture = Fixture()
            val actionProvider = StoreProvider()
            val candidate = CandidateIdentity(index + 50L)
            fixture.begin(actionProvider, candidate.value)
            val actionTicket = requireNotNull(
                fixture.store.prevalidateTerminalCandidateDisposition(
                    SessionIdentity(1),
                    candidate,
                    ControllerIngress.Terminal(
                        IngressSequence(index + 60L),
                        terminalCauseForTest(TerminalEvidence.OwnerStopped, index + 50L),
                    ),
                    action,
                ),
            )
            when (val outcome = fixture.store.dispositionCandidate(actionTicket)) {
                CandidateDispositionOutcome.Released -> assertTrue(
                    action === CandidateDispositionAction.ReleaseUnstarted ||
                            action is CandidateDispositionAction.RetireReturned &&
                            !action.returnedEncoderNeedsCleanup,
                )

                is CandidateDispositionOutcome.CleanupRequired -> {
                    assertTrue(action is CandidateDispositionAction.RetireReturned)
                    assertSame(CleanupTransitionDisposition.Released, fixture.store.retireCleanup(outcome.cleanup))
                }

                is CandidateDispositionOutcome.LateReturnRetained -> {
                    assertSame(CandidateDispositionAction.RetainAwaitingLateReturn, action)
                    assertSame(
                        CleanupTransitionDisposition.Released,
                        fixture.store.latePreparationReturned(
                            outcome.cleanup,
                            returnedEncoderNeedsCleanup = false,
                        ),
                    )
                }

                is CandidateDispositionOutcome.Quarantined -> {
                    assertSame(CandidateDispositionAction.QuarantineStartedTimeout, action)
                    assertSame(
                        QuarantineReturnDisposition.ReturnedWithoutEncoder,
                        fixture.store.preparationQuarantineReturned(
                            outcome.quarantine,
                            returnedEncoderNeedsCleanup = false,
                        ),
                    )
                }

                CandidateDispositionOutcome.InvalidAuthority -> error("Fresh terminal ticket was rejected.")
            }
            assertNull(fixture.ledger.snapshot(actionProvider))
        }
    }

    @Test
    fun targetAcknowledgementRejectsChangedPhysicalFenceAndTerminalWithoutMutation() {
        val provider = StoreProvider()
        val stale = Fixture()
        stale.install(provider, id = 1, format = "JPEG")
        stale.begin(provider, id = 2)
        val origin = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(2), CandidateIdentity(2))
        val ticket = requireNotNull(stale.store.prevalidateTargetAcknowledgement(SessionIdentity(1), origin, target(2)))
        val winningTicket = requireNotNull(
            stale.store.prevalidateTargetAcknowledgement(SessionIdentity(1), origin, target(3)),
        )
        assertTrue(stale.store.acknowledgePhysicalTarget(winningTicket) is TargetAcknowledgementDisposition.Acknowledged)
        assertSame(TargetAcknowledgementDisposition.SnapshotMismatch, stale.store.acknowledgePhysicalTarget(ticket))
        assertEquals(TargetIdentity(3).value, stale.store.view().physicalCurrentTarget?.identity?.value)

        val terminal = Fixture()
        terminal.begin(provider, id = 4)
        val terminalTicket = requireNotNull(
            terminal.store.prevalidateTargetAcknowledgement(
                SessionIdentity(1),
                ControllerFactOrigin.Startup(CandidateIdentity(4)),
                target(4),
            ),
        )
        assertTrue(
            terminal.store.dispositionCandidate(
                requireNotNull(
                    terminal.store.prevalidateCandidateDisposition(
                        SessionIdentity(1),
                        ControllerFactOrigin.Startup(CandidateIdentity(4)),
                        IngressSequence(1),
                        CandidateDispositionTrigger.Cancellation,
                        CandidateDispositionAction.ReleaseUnstarted,
                    ),
                ),
            ) is CandidateDispositionOutcome.Released,
        )
        assertTrue(terminal.store.commitTerminal() is TerminalOwnershipDisposition.Retired)
        assertSame(TargetAcknowledgementDisposition.Terminal, terminal.store.acknowledgePhysicalTarget(terminalTicket))
        assertNull(terminal.store.view().physicalCurrentTarget)
    }

    private class Fixture {
        val ledger = LiveProviderDescriptorLedger()
        val store = ControllerSnapshotStore(ledger)

        fun begin(provider: ImageEncoderProvider, id: Long): ControllerCandidateOwnership =
            (store.beginCandidate(candidate(provider, id)) as CandidateOwnershipAdmission.Admitted).ownership

        fun acknowledgeCandidateTarget(
            candidateId: Long,
            acknowledgedTarget: ControllerTargetSnapshot = target(candidateId),
            origin: ControllerFactOrigin = ControllerFactOrigin.Startup(CandidateIdentity(candidateId)),
        ) {
            val ticket = requireNotNull(
                store.prevalidateTargetAcknowledgement(SessionIdentity(1), origin, acknowledgedTarget),
            )
            assertTrue(store.acknowledgePhysicalTarget(ticket) is TargetAcknowledgementDisposition.Acknowledged)
        }

        fun dispositionCandidate(
            candidateId: Long,
            action: CandidateDispositionAction,
            trigger: CandidateDispositionTrigger = CandidateDispositionTrigger.Cancellation,
        ): CandidateDispositionOutcome {
            val ticket = requireNotNull(
                store.prevalidateCandidateDisposition(
                    SessionIdentity(1),
                    ControllerFactOrigin.Startup(CandidateIdentity(candidateId)),
                    IngressSequence(candidateId + 100L),
                    trigger,
                    action,
                ),
            )
            return store.dispositionCandidate(ticket)
        }

        fun prevalidateCommit(
            ownership: ControllerCandidateOwnership,
            owner: CompleteOwnerIdentity,
            effective: ControllerEffectiveSnapshot,
        ): ControllerCandidateCommitPrevalidation? = store.prevalidateCandidateCommit(
            ownership,
            SessionIdentity(1),
            owner,
            effective,
        )

        fun commitPrevalidated(
            ownership: ControllerCandidateOwnership,
            owner: CompleteOwnerIdentity,
            effective: ControllerEffectiveSnapshot,
        ): CandidateCommitDisposition = store.commitCandidate(
            requireNotNull(prevalidateCommit(ownership, owner, effective)),
        )

        fun install(provider: ImageEncoderProvider, id: Long, format: String) {
            val ownership = begin(provider, id)
            acknowledgeCandidateTarget(id)
            assertRecorded(store.recordCandidateDescriptor(ownership, descriptor(format)))
            assertTrue(
                commitPrevalidated(ownership, CompleteOwnerIdentity(id), effective(id, format))
                        is CandidateCommitDisposition.Committed,
            )
        }

        fun replace(provider: ImageEncoderProvider, id: Long, format: String): ControllerCleanupOwnership {
            val ownership = begin(provider, id)
            acknowledgeCandidateTarget(id)
            assertRecorded(store.recordCandidateDescriptor(ownership, descriptor(format)))
            return checkNotNull(
                (commitPrevalidated(
                    ownership,
                    CompleteOwnerIdentity(id),
                    effective(id, format),
                ) as CandidateCommitDisposition.Committed).retiredOwner,
            )
        }
    }

    private companion object {
        val geometry = GeometrySnapshot(width = 100, height = 80, densityDpi = 320)
        val plan: BaselineOutputPlan = (BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = Size(100, 80),
            sourceRegion = dev.dmkr.screencaptureengine.SourceRegion.Full,
            crop = dev.dmkr.screencaptureengine.CropInsetsPx.Zero,
            rotation = dev.dmkr.screencaptureengine.Rotation.Degrees0,
            factor = 0.5,
        ) as BaselineOutputPlanFact.Planned).plan

        fun parameterDirect(
            transaction: ParameterTransaction,
            fact: ParameterTransactionFact.Direct,
            sequence: Long,
        ) = ControllerDirectFact.Parameter(
            session = SessionIdentity(1),
            sequence = IngressSequence(sequence),
            origin = ControllerFactOrigin.Parameter(transaction.identity, transaction.candidate),
            fact = fact,
        )

        fun output(frameRate: NormalizedFrameRate = NormalizedFrameRate.Auto) = NormalizedOutputValues(
            sourceRegion = NormalizedSourceRegion.Full,
            crop = NormalizedCrop(0, 0, 0, 0),
            outputSize = NormalizedOutputSize.ScaleFactor(0.5),
            rotation = NormalizedRotation.Degrees0,
            mirror = NormalizedMirror.None,
            colorMode = NormalizedColorMode.Original,
            frameRate = frameRate,
        )

        fun desired(provider: ImageEncoderProvider, id: Long, output: NormalizedOutputValues = output()) =
            ControllerDesiredSnapshot(DesiredSnapshotIdentity(id), output, ControllerProviderReference(provider))

        fun candidate(
            provider: ImageEncoderProvider,
            id: Long,
            desiredId: Long = id,
        ) = ControllerCandidateSnapshot(
            identity = CandidateIdentity(id),
            desired = desired(provider, desiredId),
            geometry = geometry,
            plan = plan,
        )

        fun effective(
            id: Long,
            format: String,
            output: NormalizedOutputValues = output(),
            ownerId: Long = id,
            targetId: Long = id,
            mimeType: String = "image/test",
            desiredId: Long = id,
            geometry: GeometrySnapshot = Companion.geometry,
            plan: BaselineOutputPlan = Companion.plan,
        ) =
            ControllerEffectiveSnapshot(
                identity = EffectiveSnapshotIdentity(id),
                desiredIdentity = DesiredSnapshotIdentity(desiredId),
                targetIdentity = TargetIdentity(targetId),
                completeOwnerIdentity = CompleteOwnerIdentity(ownerId),
                output = output,
                geometry = geometry,
                plan = plan,
                encodedFormat = EncodedFormatDescriptorSnapshot.copy(format, mimeType),
            )

        fun target(id: Long) = ControllerTargetSnapshot(
            identity = TargetIdentity(id),
            geometry = geometry,
            targetSize = Size(100, 80),
            samplingCapacity = SamplingDemand(PositiveRatio(1, 1), PositiveRatio(1, 1)),
            assignment = TargetAssignmentEvidence.Acknowledged,
            health = TargetHealthEvidence.Validated,
            wholeGeometryMappingValidated = true,
        )

        fun descriptor(format: String) = DescriptorSyntax.copySnapshot("provider", format, "image/test")

        fun assertRecorded(result: ProviderDescriptorRecordResult) {
            assertTrue(result is ProviderDescriptorRecordResult.Recorded)
        }
    }
}

private class StoreProvider : ImageEncoderProvider {
    override val id: String = "provider"
    override val outputFormat: EncodedImageFormat = EncodedImageFormat("TEST", "image/test")
    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder = error("Not called")
}
