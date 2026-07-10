package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPreparedTurnTest {
    @Test
    fun completeTurnOwnsTheExactMaximumAndRetainsEveryTerminal() {
        val mutable = completeTurnFacts().toMutableList()
        val turn = ControllerPreparedTurn(mutable)
        mutable.clear()

        assertEquals(26, turn.size)
        assertTrue((0 until turn.size - 1).all { turn[it].precedes(turn[it + 1]) })
        val terminals = (0 until turn.size)
            .map(turn::get)
            .filterIsInstance<PreparedTerminalFact>()
        assertEquals(TerminalEvidence.entries.toSet(), terminals.map { it.turnFact.evidence }.toSet())
        assertTrue(terminals.all { it.turnFact.cause.fence.session == SessionIdentity(1) })
        assertTrue(terminals.all { it.turnFact.cause.fence.revisions == CommittedRevisions(1, 1, 1) })
        assertTrue(terminals.any { it.turnFact.evidence == TerminalEvidence.StartedEncoderStall })
        assertTrue(terminals.any { it.turnFact.evidence == TerminalEvidence.OwnerStopped })
        assertTrue(
            terminals.indexOfFirst { it.turnFact.evidence == TerminalEvidence.OwnerStopped } <
                    terminals.indexOfFirst { it.turnFact.evidence == TerminalEvidence.StartedEncoderStall },
        )
    }

    @Test
    fun completeTurnRejectsEmptyUnsortedDuplicateSequenceDuplicateSlotAndOverboundInputs() {
        assertThrows(IllegalArgumentException::class.java) { ControllerPreparedTurn(emptyList()) }

        val complete = completeTurnFacts()
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(complete.toMutableList().apply { add(0, removeAt(lastIndex)) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                listOf(
                    PreparedCancellationFact(
                        ControllerIngress.Cancellation(IngressSequence(1), TransactionIdentity(1)),
                    ),
                    PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(1), true)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                listOf(
                    PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(1), true)),
                    PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(2), false)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                complete + PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(27), false)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                listOf(
                    PreparedTerminalFact(
                        ControllerIngress.Terminal(
                            IngressSequence(1),
                            terminalCauseForTest(TerminalEvidence.OwnerStopped, 1),
                        ),
                    ),
                    PreparedPrePublicFact(
                        ControllerDirectFact.PrePublicRetirement(
                            SessionIdentity(2),
                            IngressSequence(2),
                            PrePublicStartRetirement(
                                PrePublicProjectionFreshness.ReusableBeforeAttachment,
                                PrePublicStartOutcome.CallerCancellation,
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun parameterProgressWrapperAcceptsExactlyItsSixRawLeavesAndRejectsTheOtherSix() {
        val transaction = TransactionIdentity(1)
        val candidate = CandidateIdentity(1)
        val origin = ControllerFactOrigin.Parameter(transaction, candidate)
        val accepted = listOf<ParameterTransactionFact.Direct>(
            ParameterTransactionFact.ReadyPermitAcquired(transaction),
            ParameterTransactionFact.DrainCompleted(transaction, EffectiveSnapshotIdentity(1)),
            ParameterTransactionFact.RetargetStarted(transaction, TargetIdentity(1)),
            ParameterTransactionFact.ReadyPermitRejected(transaction),
            ParameterTransactionFact.RetargetStartTimedOut(transaction),
            ParameterTransactionFact.Superseded(transaction),
        )
        accepted.forEachIndexed { index, fact ->
            val direct = ControllerDirectFact.Parameter(SessionIdentity(1), IngressSequence(index + 1L), origin, fact)
            val prepared = PreparedParameterProgressFact(direct)
            assertSame(prepared, ControllerPreparedTurn(listOf(prepared))[0])
        }

        val rejected = listOf<ParameterTransactionFact.Direct>(
            ParameterTransactionFact.CandidatePrepared(transaction, candidate, CompleteOwnerIdentity(1)),
            ParameterTransactionFact.FrameRateCommitReady(
                transaction,
                DesiredSnapshotIdentity(1),
                EffectiveSnapshotIdentity(2),
                FramePacingResetFact.Auto(0),
            ),
            ParameterTransactionFact.TargetAcknowledged(transaction, TargetIdentity(1)),
            ParameterTransactionFact.OwnerCommitReady(
                transaction,
                candidate,
                CompleteOwnerIdentity(1),
                EffectiveSnapshotIdentity(2),
            ),
            ParameterTransactionFact.CandidatePreparationFailed(
                transaction,
                candidate,
                ParameterPreparationRejectionEvidence.EncoderSetupFailed,
                ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
            ),
            ParameterTransactionFact.CandidatePreparationStartedTimedOut(transaction, candidate),
        )
        rejected.forEachIndexed { index, fact ->
            val direct = ControllerDirectFact.Parameter(SessionIdentity(1), IngressSequence(index + 20L), origin, fact)
            assertThrows(IllegalArgumentException::class.java) { PreparedParameterProgressFact(direct) }
        }
    }

    @Test
    fun everyNonStoreBoundRawFamilyEntersOnlyItsExactPreparedWrapper() {
        val reconfiguration = ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(1))
        val parameter = ControllerFactOrigin.Parameter(TransactionIdentity(1), CandidateIdentity(1))
        val prepared = listOf<ControllerPreparedFact>(
            PreparedTerminalFact(
                ControllerIngress.Terminal(IngressSequence(1), terminalCauseForTest(TerminalEvidence.OwnerStopped)),
            ),
            PreparedSourceFact.Metrics(
                ControllerIngress.Metrics(IngressSequence(2), MetricsEvidence(1, 1, 1)),
                ReasonToken(1),
            ),
            PreparedSourceFact.CapturedResize(
                ControllerIngress.CapturedResize(IngressSequence(3), CapturedResizeEvidence(1, 1)),
                ReasonToken(2),
            ),
            PreparedSourceFact.SourceTrust(
                ControllerIngress.SourceTrust(IngressSequence(4), SourceTrustEvidence.Invalid),
                ReasonToken(3),
            ),
            PreparedSourceFact.Pause(
                ControllerIngress.Pause(IngressSequence(5), PauseEvidence(true, IngressSequence(5))),
                ReasonToken(4),
            ),
            PreparedCancellationFact(ControllerIngress.Cancellation(IngressSequence(6), TransactionIdentity(1))),
            PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(7), true)),
            PreparedPrePublicFact(
                ControllerDirectFact.PrePublicRetirement(
                    SessionIdentity(1),
                    IngressSequence(8),
                    PrePublicStartRetirement(
                        PrePublicProjectionFreshness.ReusableBeforeAttachment,
                        PrePublicStartOutcome.CallerCancellation,
                    ),
                ),
            ),
            PreparedParameterAdmissionFact(
                ControllerDirectFact.ParameterAdmitted(
                    SessionIdentity(1),
                    IngressSequence(9),
                    parameter,
                    DesiredSnapshotIdentity(1),
                    EffectiveSnapshotIdentity(1),
                    PreparedParameterCandidateClass.SameTargetReplacement,
                ),
            ),
            PreparedArbiterReasonAddedFact(
                ControllerDirectFact.ArbiterReasonAdded(
                    SessionIdentity(1),
                    IngressSequence(10),
                    reconfiguration,
                    ReasonToken(5),
                    ReconfigurationReasonSpec.Convergence,
                    ArbiterFenceEvidence.None,
                ),
            ),
            PreparedArbiterReasonClearedFact(
                ControllerDirectFact.ArbiterReasonCleared(
                    SessionIdentity(1),
                    IngressSequence(11),
                    reconfiguration,
                    ReasonToken(5),
                    ReconfigurationReasonKey.Convergence,
                ),
            ),
        )
        prepared.forEach { assertSame(it, ControllerPreparedTurn(listOf(it))[0]) }
    }

    @Test
    fun reconfigurationStartIdentityIsOneTurnGlobalImmutableAndReplayable() {
        val budget = ControllerReconfigurationStartIdentity(ReconfigurationIdentity(7), CandidateIdentity(8))
        val turn = ControllerPreparedTurn(
            listOf(
                PreparedSourceFact.Metrics(
                    ControllerIngress.Metrics(IngressSequence(1), MetricsEvidence(10, 20, 30)),
                    ReasonToken(1),
                ),
                PreparedSourceFact.CapturedResize(
                    ControllerIngress.CapturedResize(IngressSequence(2), CapturedResizeEvidence(10, 20)),
                    ReasonToken(2),
                ),
            ),
            reconfigurationStart = budget,
        )

        assertSame(budget, turn.reconfigurationStart)
        assertEquals(ReconfigurationIdentity(7), budget.reconfiguration)
        assertEquals(CandidateIdentity(8), budget.candidate)
        assertEquals(ReconfigurationIdentity(7), turn.reconfigurationStart?.reconfiguration)
        assertEquals(CandidateIdentity(8), turn.reconfigurationStart?.candidate)
        fun foldOnce(): List<ControllerReconfigurationStartIdentity> {
            var remaining = turn.reconfigurationStart
            return buildList {
                repeat(turn.size) { index ->
                    if (turn[index] is PreparedSourceFact && remaining != null) {
                        add(requireNotNull(remaining))
                        remaining = null
                    }
                }
            }
        }
        val firstFold = foldOnce()
        val secondFold = foldOnce()
        assertEquals(1, firstFold.size)
        assertEquals(firstFold.map { it.reconfiguration }, secondFold.map { it.reconfiguration })
        assertEquals(firstFold.map { it.candidate }, secondFold.map { it.candidate })

        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                listOf(
                    PreparedSourceFact.Metrics(
                        ControllerIngress.Metrics(IngressSequence(3), MetricsEvidence(1, 1, 1)),
                        ReasonToken(3),
                    ),
                    PreparedSourceFact.CapturedResize(
                        ControllerIngress.CapturedResize(IngressSequence(4), CapturedResizeEvidence(1, 1)),
                        ReasonToken(3),
                    ),
                ),
            )
        }
    }

    @Test
    fun prePublicRecordCoexistsWithProjectionStopAndLosingUnsafeGlAftermath() {
        listOf(
            PrePublicStartOutcome.CallerCancellation,
            PrePublicStartOutcome.Failure(PrePublicStartFailureEvidence.PlatformFailure),
        ).forEach { outcome ->
            val retirement = PrePublicStartRetirement(PrePublicProjectionFreshness.DiscardConsumed, outcome)
            val projection = PreparedTerminalFact(
                ControllerIngress.Terminal(
                    IngressSequence(1),
                    terminalCauseForTest(TerminalEvidence.ProjectionStopped),
                ),
            )
            val gl = PreparedTerminalFact(
                ControllerIngress.Terminal(
                    IngressSequence(2),
                    terminalCauseForTest(TerminalEvidence.StartedGlTimeout),
                ),
            )
            val start = PreparedPrePublicFact(
                ControllerDirectFact.PrePublicRetirement(SessionIdentity(1), IngressSequence(3), retirement),
            )
            val prepared = ControllerPreparedTurn(listOf(projection, gl, start))

            assertSame(projection, prepared[0])
            assertSame(gl, prepared[1])
            assertSame(retirement, (prepared[2] as PreparedPrePublicFact).turnFact.retirement)

            val maximal = completeTurnFacts().map { fact ->
                if (fact is PreparedParameterAdmissionFact) {
                    PreparedPrePublicFact(
                        ControllerDirectFact.PrePublicRetirement(SessionIdentity(1), fact.sequence, retirement),
                    )
                } else {
                    fact
                }
            }
            assertEquals(26, ControllerPreparedTurn(maximal).size)
        }
    }

    @Test
    fun ownerStopWinnerRetainsExactProductionGlTimeoutAftermathAndAllGlTaskContextsAreDistinct() {
        val fence = ControllerTerminalFence(
            SessionIdentity(1),
            CommittedRevisions(1, 1, 1),
            ControllerCancellationMarkerRevision(1),
        )
        val production = ControllerGlTaskIdentity.Production(
            CompleteOwnerIdentity(2),
            TargetIdentity(3),
            ControllerProductionAttemptIdentity(4),
            ControllerOperationIdentity(5),
            ControllerResourceBagIdentity(6),
        )
        val ownerStop = PreparedTerminalFact(
            ControllerIngress.Terminal(IngressSequence(1), ControllerTerminalCause.OwnerStopped(fence)),
        )
        val timeout = PreparedTerminalFact(
            ControllerIngress.Terminal(
                IngressSequence(2),
                ControllerTerminalCause.StartedGlTimeout(fence, production),
            ),
        )
        val turn = ControllerPreparedTurn(listOf(ownerStop, timeout))

        assertSame(ownerStop, turn[0])
        val losing = (turn[1] as PreparedTerminalFact).turnFact.cause as ControllerTerminalCause.StartedGlTimeout
        val exact = losing.task as ControllerGlTaskIdentity.Production
        assertEquals(CompleteOwnerIdentity(2), exact.owner)
        assertEquals(ControllerProductionAttemptIdentity(4), exact.attempt)
        assertEquals(ControllerOperationIdentity(5), exact.operation)
        assertEquals(ControllerResourceBagIdentity(6), exact.resourceBag)

        val operation = ControllerOperationIdentity(10)
        val bag = ControllerResourceBagIdentity(11)
        val contexts = listOf<ControllerGlTaskIdentity>(
            ControllerGlTaskIdentity.Bootstrap(
                ControllerFactOrigin.Startup(CandidateIdentity(1)),
                operation,
                bag,
            ),
            ControllerGlTaskIdentity.TargetCreate(
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(1)),
                TargetIdentity(1),
                operation,
                bag,
            ),
            ControllerGlTaskIdentity.PipelinePrepare(
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(1)),
                operation,
                bag,
            ),
            ControllerGlTaskIdentity.TargetProbe(TargetIdentity(1), operation, bag),
            production,
            ControllerGlTaskIdentity.PboProgress(
                CompleteOwnerIdentity(2),
                TargetIdentity(3),
                ControllerProductionAttemptIdentity(4),
                operation,
                bag,
            ),
            ControllerGlTaskIdentity.TargetDestroy(TargetIdentity(1), operation, bag),
        )
        assertTrue(contexts[0] is ControllerGlTaskIdentity.Bootstrap)
        assertTrue(contexts[1] is ControllerGlTaskIdentity.TargetCreate)
        assertTrue(contexts[2] is ControllerGlTaskIdentity.PipelinePrepare)
        assertTrue(contexts[3] is ControllerGlTaskIdentity.TargetProbe)
        assertTrue(contexts[4] is ControllerGlTaskIdentity.Production)
        assertTrue(contexts[5] is ControllerGlTaskIdentity.PboProgress)
        assertTrue(contexts[6] is ControllerGlTaskIdentity.TargetDestroy)
    }

    @Test
    fun terminalPayloadSeparatesStartedRetargetAndPoisonedPreparationFromCurrentRequiredCandidate() {
        val retargetCause = terminalCauseForTest(TerminalEvidence.StartedPlatformTimeout) as
                ControllerTerminalCause.StartedPlatformTimeout
        val retarget = retargetCause.platform as ControllerPlatformOperationIdentity.Retarget
        assertEquals(retarget.candidate, retarget.origin.candidate)
        assertTrue(retarget.previousTarget != retarget.candidateTarget)
        val superseded = ControllerPlatformOperationIdentity.Retarget(
            ControllerOperationIdentity(30),
            ControllerFactOrigin.Parameter(TransactionIdentity(31), CandidateIdentity(32)),
            CandidateIdentity(32),
            TargetIdentity(33),
            TargetIdentity(34),
            ControllerResourceBagIdentity(35),
        )
        assertEquals(TransactionIdentity(31), (superseded.origin as ControllerFactOrigin.Parameter).transaction)
        assertEquals(CandidateIdentity(32), superseded.candidate)

        val poison = terminalCauseForTest(TerminalEvidence.PoisonedProviderPreparationRequired) as
                ControllerTerminalCause.PoisonedProviderPreparationRequired
        assertTrue(poison.poisoned.origin.candidate != poison.requiredOrigin.candidate)
        assertEquals(ControllerCancellationMarkerRevision(1), poison.fence.cancellationMarker)

        val operation = ControllerOperationIdentity(20)
        val bag = ControllerResourceBagIdentity(21)
        val ownerships = listOf<ControllerProviderOwnershipIdentity>(
            ControllerProviderOwnershipIdentity.CandidatePreparation(
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(1), CandidateIdentity(1)),
                operation,
                bag,
            ),
            ControllerProviderOwnershipIdentity.ActiveEncode(
                CompleteOwnerIdentity(1),
                ControllerProductionAttemptIdentity(1),
                operation,
                bag,
            ),
            ControllerProviderOwnershipIdentity.RetiredCleanup(CompleteOwnerIdentity(1), operation, bag),
        )
        assertTrue(ownerships[0] is ControllerProviderOwnershipIdentity.CandidatePreparation)
        assertTrue(ownerships[1] is ControllerProviderOwnershipIdentity.ActiveEncode)
        assertTrue(ownerships[2] is ControllerProviderOwnershipIdentity.RetiredCleanup)

        val attachment = ControllerCallbackAttachmentIdentity(1)
        val platform = listOf<ControllerPlatformOperationIdentity>(
            ControllerPlatformOperationIdentity.DeviceMemorySample(operation),
            ControllerPlatformOperationIdentity.CallbackAttach(operation, attachment),
            ControllerPlatformOperationIdentity.CallbackDetach(operation, attachment),
            ControllerPlatformOperationIdentity.Create(
                operation,
                CandidateIdentity(1),
                TargetIdentity(1),
                bag,
            ),
            retarget,
            ControllerPlatformOperationIdentity.TerminalCleanup.WithoutTarget(operation, attachment),
            ControllerPlatformOperationIdentity.TerminalCleanup.WithTarget(
                operation,
                attachment,
                TargetIdentity(1),
                bag,
            ),
        )
        assertTrue(platform[0] is ControllerPlatformOperationIdentity.DeviceMemorySample)
        assertTrue(platform[1] is ControllerPlatformOperationIdentity.CallbackAttach)
        assertTrue(platform[2] is ControllerPlatformOperationIdentity.CallbackDetach)
        assertTrue(platform[3] is ControllerPlatformOperationIdentity.Create)
        assertTrue(platform[4] is ControllerPlatformOperationIdentity.Retarget)
        assertTrue(platform[5] is ControllerPlatformOperationIdentity.TerminalCleanup.WithoutTarget)
        assertTrue(platform[6] is ControllerPlatformOperationIdentity.TerminalCleanup.WithTarget)
    }

    private fun completeTurnFacts(): List<ControllerPreparedFact> {
        val facts = mutableListOf<ControllerPreparedFact>()
        TerminalEvidence.entries.forEachIndexed { index, evidence ->
            facts += PreparedTerminalFact(
                ControllerIngress.Terminal(IngressSequence(index + 1L), terminalCauseForTest(evidence, index + 1L)),
            )
        }
        facts += PreparedSourceFact.Metrics(
            ControllerIngress.Metrics(IngressSequence(19), MetricsEvidence(100, 80, 320)),
            ReasonToken(1),
        )
        facts += PreparedSourceFact.CapturedResize(
            ControllerIngress.CapturedResize(IngressSequence(20), CapturedResizeEvidence(100, 80)),
            ReasonToken(2),
        )
        facts += PreparedSourceFact.SourceTrust(
            ControllerIngress.SourceTrust(IngressSequence(21), SourceTrustEvidence.Invalid),
            ReasonToken(3),
        )
        facts += PreparedSourceFact.SourceTrust(
            ControllerIngress.SourceTrust(IngressSequence(22), SourceTrustEvidence.InvalidResize),
            ReasonToken(4),
        )
        facts += PreparedSourceFact.Pause(
            ControllerIngress.Pause(IngressSequence(23), PauseEvidence(true, IngressSequence(23))),
            ReasonToken(5),
        )
        facts += PreparedCancellationFact(
            ControllerIngress.Cancellation(IngressSequence(24), TransactionIdentity(1)),
        )
        facts += PreparedParameterAdmissionFact(
            ControllerDirectFact.ParameterAdmitted(
                SessionIdentity(1),
                IngressSequence(25),
                ControllerFactOrigin.Parameter(TransactionIdentity(1), CandidateIdentity(1)),
                DesiredSnapshotIdentity(1),
                EffectiveSnapshotIdentity(1),
                PreparedParameterCandidateClass.SameTargetReplacement,
            ),
        )
        facts += PreparedVisibilityFact(ControllerIngress.Visibility(IngressSequence(26), true))
        return facts.sortedWith(compareBy({ it.priority }, { it.sequence.value }))
    }

    private fun ControllerPreparedFact.precedes(other: ControllerPreparedFact): Boolean =
        priority < other.priority || priority == other.priority && sequence.value < other.sequence.value
}

internal fun terminalCauseForTest(
    evidence: TerminalEvidence,
    id: Long = 1L,
    sessionId: Long = 1L,
): ControllerTerminalCause {
    val fence = ControllerTerminalFence(
        SessionIdentity(sessionId),
        CommittedRevisions(1, 1, 1),
        ControllerCancellationMarkerRevision(1),
    )
    val operation = ControllerOperationIdentity(id)
    val bag = ControllerResourceBagIdentity(id)
    val target = TargetIdentity(id)
    val candidate = CandidateIdentity(id)
    val reason = ReasonToken(id)
    return when (evidence) {
        TerminalEvidence.ProjectionStopped -> ControllerTerminalCause.ProjectionStopped(
            fence,
            ControllerCallbackAttachmentIdentity(id),
        )

        TerminalEvidence.DisplayStopped -> ControllerTerminalCause.DisplayStopped(fence, target)
        TerminalEvidence.OwnerStopped -> ControllerTerminalCause.OwnerStopped(fence)
        TerminalEvidence.StartedEncoderStall -> ControllerTerminalCause.StartedEncoderStall(
            fence,
            CompleteOwnerIdentity(id),
            operation,
            ControllerProductionAttemptIdentity(id),
            bag,
        )

        TerminalEvidence.PoisonedProviderPreparationRequired ->
            ControllerTerminalCause.PoisonedProviderPreparationRequired(
                fence,
                ControllerProviderOwnershipIdentity.CandidatePreparation(
                    ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(id), candidate),
                    operation,
                    bag,
                ),
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(id), CandidateIdentity(id + 1)),
            )

        TerminalEvidence.StartedGlTimeout -> ControllerTerminalCause.StartedGlTimeout(
            fence,
            ControllerGlTaskIdentity.Production(
                CompleteOwnerIdentity(id),
                target,
                ControllerProductionAttemptIdentity(id),
                operation,
                bag,
            ),
        )

        TerminalEvidence.StartedPlatformTimeout -> ControllerTerminalCause.StartedPlatformTimeout(
            fence,
            ControllerPlatformOperationIdentity.Retarget(
                operation,
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(id), candidate),
                candidate,
                target,
                TargetIdentity(id + 1),
                bag,
            ),
        )

        TerminalEvidence.ListenerRetirementUnprovable ->
            ControllerTerminalCause.ListenerRetirementUnprovable(fence, target, operation, bag)

        TerminalEvidence.MetricsCollectionTerminated -> ControllerTerminalCause.MetricsCollectionTerminated(
            fence,
            ControllerMetricsAttachmentIdentity(id),
            operation,
        )

        TerminalEvidence.UnsafePlatformBinding -> ControllerTerminalCause.UnsafePlatformBinding(
            fence,
            ControllerPlatformOperationIdentity.Retarget(
                operation,
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(id), candidate),
                candidate,
                target,
                TargetIdentity(id + 1),
                bag,
            ),
        )

        TerminalEvidence.UnsafeRenderingState -> ControllerTerminalCause.UnsafeRenderingState(
            fence,
            ControllerGlTaskIdentity.TargetProbe(target, operation, bag),
        )

        TerminalEvidence.UnsafeGlOutOfMemory -> ControllerTerminalCause.UnsafeGlOutOfMemory(
            fence,
            ControllerGlTaskIdentity.PipelinePrepare(
                ControllerFactOrigin.Reconfiguration(ReconfigurationIdentity(id), candidate),
                operation,
                bag,
            ),
        )

        TerminalEvidence.UnsafeProviderRetainedOwnership ->
            ControllerTerminalCause.UnsafeProviderRetainedOwnership(
                fence,
                ControllerProviderOwnershipIdentity.ActiveEncode(
                    CompleteOwnerIdentity(id),
                    ControllerProductionAttemptIdentity(id),
                    operation,
                    bag,
                ),
            )

        TerminalEvidence.InternalControllerInvariant ->
            ControllerTerminalCause.InternalControllerInvariant(fence, ControllerInvariantIdentity(id))

        TerminalEvidence.PlatformRecoveryExhausted ->
            ControllerTerminalCause.PlatformRecoveryExhausted(fence, ReconfigurationIdentity(id), reason)

        TerminalEvidence.RenderingRecoveryExhausted ->
            ControllerTerminalCause.RenderingRecoveryExhausted(fence, ReconfigurationIdentity(id), reason)

        TerminalEvidence.EncodingRecoveryExhausted ->
            ControllerTerminalCause.EncodingRecoveryExhausted(fence, ReconfigurationIdentity(id), reason)

        TerminalEvidence.ResourceRecoveryExhausted ->
            ControllerTerminalCause.ResourceRecoveryExhausted(fence, ReconfigurationIdentity(id), reason)
    }
}
