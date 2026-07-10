package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterTransactionTest {
    @Test
    fun targetReplanAcceptsOnlyExactOrderedTargetFencedGraph() {
        val tx = TransactionIdentity(1)
        val candidate = CandidateIdentity(1)
        val owner = CompleteOwnerIdentity(1)
        val target = TargetIdentity(1)
        var current = ParameterTransaction(
            tx,
            DesiredSnapshotIdentity(1),
            EffectiveSnapshotIdentity(1),
            candidate,
            PreparedParameterCandidateClass.TargetReplan,
        )
        current = current.advanced(ParameterTransactionFact.CandidatePrepared(tx, candidate, owner, target))
        current = current.advanced(ParameterTransactionFact.ReadyPermitAcquired(tx))
        current = current.commitSuspension().advanced()
        current = current.beginDrain().advanced()
        current = current.advanced(ParameterTransactionFact.DrainCompleted(tx, EffectiveSnapshotIdentity(1)))
        current = current.advanced(ParameterTransactionFact.RetargetStarted(tx, target))
        current = current.advanced(ParameterTransactionFact.TargetAcknowledged(tx, target))
        current = current.beginConvergence().advanced()
        current = current.advanced(
            ParameterTransactionFact.OwnerCommitReady(tx, candidate, owner, EffectiveSnapshotIdentity(2)),
        )
        assertEquals(ParameterTransactionStage.Completed, current.stage)
    }

    @Test
    fun everyFactIsIllegalFromEveryWrongStageAndStaleIdentityNeverMutates() {
        val tx = TransactionIdentity(1)
        val candidate = CandidateIdentity(1)
        val owner = CompleteOwnerIdentity(1)
        val target = TargetIdentity(1)
        val facts = listOf<ParameterTransactionFact>(
            ParameterTransactionFact.CandidatePrepared(tx, candidate, owner, target),
            ParameterTransactionFact.ReadyPermitAcquired(tx),
            ParameterTransactionFact.DrainCompleted(tx, EffectiveSnapshotIdentity(1)),
            ParameterTransactionFact.RetargetStarted(tx, target),
            ParameterTransactionFact.TargetAcknowledged(tx, target),
            ParameterTransactionFact.OwnerCommitReady(tx, candidate, owner, EffectiveSnapshotIdentity(2)),
            candidatePreparationFailed(tx),
            ParameterTransactionFact.CandidatePreparationStartedTimedOut(tx, candidate),
            ParameterTransactionFact.ReadyPermitRejected(tx),
            ParameterTransactionFact.RetargetStartTimedOut(tx),
        )
        targetStates().forEach { (stage, transaction) ->
            facts.forEach { fact ->
                val result = transaction.accept(fact)
                if (stage == legalFrom[fact::class]) {
                    assertEquals(ParameterTransition.Advanced::class, result::class)
                } else {
                    assertSame(ParameterTransition.Illegal, result)
                }
            }
            assertSame(
                ParameterTransition.Stale,
                transaction.accept(ParameterTransactionFact.Cancelled(TransactionIdentity(2))),
            )
        }
    }

    @Test
    fun cancellationBoundariesRollbackRestoreOrDetachWaiter() {
        val tx = TransactionIdentity(1)
        fun at(stage: ParameterTransactionStage) = targetStates().getValue(stage)
        val cancelledBeforeSuspension = at(ParameterTransactionStage.CandidateReady)
            .accept(ParameterTransactionFact.Cancelled(tx)) as ParameterTransition.Advanced
        assertEquals(ParameterTransactionStage.Completed, cancelledBeforeSuspension.transaction.stage)
        val restoring = at(ParameterTransactionStage.Suspended)
            .accept(ParameterTransactionFact.Cancelled(tx)) as ParameterTransition.Advanced
        assertEquals(ParameterTransactionStage.Restoring, restoring.transaction.stage)
        assertEquals(false, restoring.transaction.waiterAttached)
        assertTrue(
            at(ParameterTransactionStage.RetargetStarted)
                .accept(ParameterTransactionFact.Cancelled(tx)) is ParameterTransition.WaiterDetached,
        )

        targetStates().forEach { (stage, transaction) ->
            val transition = transaction.accept(ParameterTransactionFact.Cancelled(tx))
            when (stage) {
                ParameterTransactionStage.Preparing,
                ParameterTransactionStage.CandidateReady,
                ParameterTransactionStage.ReadyPermit,
                    -> {
                    val completed = (transition as ParameterTransition.Advanced).transaction
                    assertEquals(ParameterTransactionStage.Completed, completed.stage)
                    assertEquals(false, completed.waiterAttached)
                }

                ParameterTransactionStage.Suspended,
                ParameterTransactionStage.Draining,
                ParameterTransactionStage.RetargetArmed,
                    -> {
                    val restoringTransaction = (transition as ParameterTransition.Advanced).transaction
                    assertEquals(ParameterTransactionStage.Restoring, restoringTransaction.stage)
                    assertEquals(false, restoringTransaction.waiterAttached)
                }

                ParameterTransactionStage.RetargetStarted,
                ParameterTransactionStage.TargetAcknowledged,
                ParameterTransactionStage.Converging,
                ParameterTransactionStage.Restoring,
                    -> assertTrue(transition is ParameterTransition.WaiterDetached)

                ParameterTransactionStage.Completed -> error("Completed is not a live target state.")
            }
        }
    }

    @Test
    fun reducerOwnedTransitionsCannotArriveAsExternalFactsAndAdvanceOnlyAtExactStages() {
        val states = targetStates()

        assertEquals(
            ParameterTransactionStage.Suspended,
            states.getValue(ParameterTransactionStage.ReadyPermit).commitSuspension().advanced().stage,
        )
        assertEquals(
            ParameterTransactionStage.Draining,
            states.getValue(ParameterTransactionStage.Suspended).beginDrain().advanced().stage,
        )
        assertEquals(
            ParameterTransactionStage.Converging,
            states.getValue(ParameterTransactionStage.TargetAcknowledged).beginConvergence().advanced().stage,
        )
        assertEquals(
            ParameterTransactionStage.Completed,
            states.getValue(ParameterTransactionStage.Restoring).commitRestoration().advanced().stage,
        )
        assertSame(
            ParameterTransition.Illegal,
            states.getValue(ParameterTransactionStage.Preparing).commitSuspension(),
        )
        assertSame(
            ParameterTransition.Stale,
            states.getValue(ParameterTransactionStage.Preparing)
                .invalidateForTerminal()
                .advanced()
                .invalidateForTerminal(),
        )
    }

    @Test
    fun sameTargetReplacementHasNoReadyPermitOrTargetStages() {
        val tx = TransactionIdentity(1)
        val owner = CompleteOwnerIdentity(1)
        val initial = ParameterTransaction(
            tx,
            DesiredSnapshotIdentity(1),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(1),
            PreparedParameterCandidateClass.SameTargetReplacement,
        )
        val ready = (initial.accept(
            ParameterTransactionFact.CandidatePrepared(tx, CandidateIdentity(1), owner),
        ) as ParameterTransition.Advanced).transaction
        assertSame(ParameterTransition.Illegal, ready.accept(ParameterTransactionFact.ReadyPermitAcquired(tx)))
        assertSame(ParameterTransition.Illegal, ready.accept(ParameterTransactionFact.ReadyPermitRejected(tx)))
        val completed = ready.advanced(
            ParameterTransactionFact.OwnerCommitReady(tx, CandidateIdentity(1), owner, EffectiveSnapshotIdentity(2)),
        )
        assertEquals(ParameterTransactionStage.Completed, completed.stage)
        assertSame(
            ParameterTransition.Illegal,
            initial.accept(
                ParameterTransactionFact.CandidatePrepared(
                    tx,
                    CandidateIdentity(1),
                    owner,
                    TargetIdentity(1),
                ),
            ),
        )
        assertSame(
            ParameterTransition.Stale,
            initial.accept(
                ParameterTransactionFact.CandidatePrepared(
                    tx,
                    CandidateIdentity(2),
                    owner,
                ),
            ),
        )
    }

    @Test
    fun detachedWaiterIsOneShotAndWrongTargetIsRejected() {
        val tx = TransactionIdentity(1)
        val started = ParameterTransaction(
            tx,
            DesiredSnapshotIdentity(1),
            EffectiveSnapshotIdentity(1),
            CandidateIdentity(1),
            PreparedParameterCandidateClass.TargetReplan,
            stage = ParameterTransactionStage.RetargetStarted,
            candidateOwner = CompleteOwnerIdentity(1),
            candidateTarget = TargetIdentity(1),
        )
        assertSame(
            ParameterTransition.Stale,
            started.accept(ParameterTransactionFact.TargetAcknowledged(tx, TargetIdentity(2))),
        )
        val detached = (started.accept(ParameterTransactionFact.Cancelled(tx)) as ParameterTransition.WaiterDetached).transaction
        assertSame(ParameterTransition.Stale, detached.accept(ParameterTransactionFact.Cancelled(tx)))
        assertSame(
            ParameterTransition.Stale,
            targetStates().getValue(ParameterTransactionStage.Draining).accept(
                ParameterTransactionFact.DrainCompleted(tx, EffectiveSnapshotIdentity(2)),
            ),
        )
        assertSame(
            ParameterTransition.Stale,
            targetStates().getValue(ParameterTransactionStage.Converging).accept(
                ParameterTransactionFact.OwnerCommitReady(
                    tx,
                    CandidateIdentity(1),
                    CompleteOwnerIdentity(2),
                    EffectiveSnapshotIdentity(2),
                ),
            ),
        )
    }

    @Test
    fun supersededStartedMutationStillAllowsOneWaiterDetachment() {
        val tx = TransactionIdentity(1)
        val started = targetStates().getValue(ParameterTransactionStage.RetargetStarted)
        val superseded = (started.accept(
            ParameterTransactionFact.Superseded(tx),
        ) as ParameterTransition.Superseded).transaction

        val detached = (superseded.accept(
            ParameterTransactionFact.Cancelled(tx),
        ) as ParameterTransition.WaiterDetached).transaction
        assertSame(ParameterTransition.Stale, detached.accept(ParameterTransactionFact.Cancelled(tx)))
        val completed = detached.accept(
            ParameterTransactionFact.TargetAcknowledged(tx, TargetIdentity(1)),
        ) as ParameterTransition.Superseded
        assertEquals(ParameterTransactionStage.Completed, completed.transaction.stage)
        assertEquals(false, completed.transaction.waiterAttached)
    }

    @Test
    fun rejectionTimeoutSupersessionAndTerminalBoundariesAreClosed() {
        val tx = TransactionIdentity(1)
        fun at(stage: ParameterTransactionStage) = targetStates().getValue(stage)
        assertEquals(
            ParameterTransactionStage.Completed,
            (at(ParameterTransactionStage.Preparing)
                .accept(candidatePreparationFailed(tx)) as ParameterTransition.Advanced)
                .transaction.stage,
        )
        assertEquals(
            ParameterTransactionStage.Completed,
            (at(ParameterTransactionStage.CandidateReady)
                .accept(ParameterTransactionFact.ReadyPermitRejected(tx)) as ParameterTransition.Advanced)
                .transaction.stage,
        )
        assertEquals(
            ParameterTransactionStage.Restoring,
            (at(ParameterTransactionStage.RetargetArmed)
                .accept(ParameterTransactionFact.RetargetStartTimedOut(tx)) as ParameterTransition.Advanced)
                .transaction.stage,
        )
        ParameterTransactionStage.entries.filter { it != ParameterTransactionStage.Completed }.forEach { stage ->
            val original = at(stage)
            val superseded = original.accept(ParameterTransactionFact.Superseded(tx))
            when (stage) {
                ParameterTransactionStage.Preparing,
                ParameterTransactionStage.CandidateReady,
                ParameterTransactionStage.ReadyPermit,
                    -> assertEquals(
                    ParameterTransactionStage.Completed,
                    (superseded as ParameterTransition.Advanced).transaction.stage,
                )

                ParameterTransactionStage.Suspended,
                ParameterTransactionStage.Draining,
                ParameterTransactionStage.RetargetArmed,
                ParameterTransactionStage.RetargetStarted,
                ParameterTransactionStage.TargetAcknowledged,
                ParameterTransactionStage.Converging,
                ParameterTransactionStage.Restoring,
                    -> {
                    val retained = (superseded as ParameterTransition.Superseded).transaction
                    val expectedStage = if (stage == ParameterTransactionStage.RetargetStarted) {
                        ParameterTransactionStage.RetargetStarted
                    } else {
                        ParameterTransactionStage.Completed
                    }
                    assertEquals(expectedStage, retained.stage)
                    assertEquals(true, retained.waiterAttached)
                    if (stage == ParameterTransactionStage.RetargetStarted) {
                        val acknowledged = retained.accept(
                            ParameterTransactionFact.TargetAcknowledged(tx, TargetIdentity(1)),
                        ) as ParameterTransition.Superseded
                        assertEquals(ParameterTransactionStage.Completed, acknowledged.transaction.stage)
                    }
                }

                ParameterTransactionStage.Completed -> error("Completed is not a live target state.")
            }
            val terminal = original.invalidateForTerminal() as ParameterTransition.Advanced
            assertEquals(ParameterTransactionStage.Completed, terminal.transaction.stage)
            assertEquals(true, terminal.transaction.waiterAttached)
        }
    }

    @Test
    fun frameRateOnlyRequiresTheExactDesiredFenceAndAFreshEffectiveIdentityWithoutAPreparedOwner() {
        val tx = TransactionIdentity(1)
        val effective = EffectiveSnapshotIdentity(1)
        val transaction = ParameterTransaction(
            identity = tx,
            desired = DesiredSnapshotIdentity(1),
            previousEffective = effective,
            candidate = CandidateIdentity(1),
            candidateClass = PreparedParameterCandidateClass.FrameRateOnly,
        )

        assertSame(
            ParameterTransition.Stale,
            transaction.accept(
                ParameterTransactionFact.FrameRateCommitReady(
                    tx,
                    DesiredSnapshotIdentity(2),
                    EffectiveSnapshotIdentity(2),
                    FramePacingResetFact.Auto(0L),
                ),
            ),
        )
        assertSame(
            ParameterTransition.Illegal,
            transaction.accept(
                ParameterTransactionFact.CandidatePrepared(
                    tx,
                    CandidateIdentity(1),
                    CompleteOwnerIdentity(1),
                ),
            ),
        )
        assertSame(
            ParameterTransition.Illegal,
            transaction.accept(candidatePreparationFailed(tx)),
        )
        assertSame(
            ParameterTransition.Stale,
            transaction.accept(
                ParameterTransactionFact.FrameRateCommitReady(
                    tx,
                    DesiredSnapshotIdentity(1),
                    effective,
                    FramePacingResetFact.Auto(0L),
                ),
            ),
        )
        val completed = transaction.accept(
            ParameterTransactionFact.FrameRateCommitReady(
                tx,
                DesiredSnapshotIdentity(1),
                EffectiveSnapshotIdentity(2),
                FramePacingResetFact.Auto(0L),
            ),
        ) as ParameterTransition.Advanced
        assertEquals(ParameterTransactionStage.Completed, completed.transaction.stage)
        assertEquals(null, completed.transaction.candidateOwner)
    }

    @Test
    fun everyPreparationRejectionEvidenceIsAcceptedOnlyForTheCurrentPreparingCandidate() {
        val transaction = targetStates().getValue(ParameterTransactionStage.Preparing)
        assertEquals(
            listOf(
                ParameterPreparationRejectionEvidence.RequestedPlanInvalid,
                ParameterPreparationRejectionEvidence.RenderingSetupFailed,
                ParameterPreparationRejectionEvidence.EncoderSetupFailed,
                ParameterPreparationRejectionEvidence.ConcreteResourceExhaustion,
            ),
            ParameterPreparationRejectionEvidence.entries,
        )

        ParameterPreparationRejectionEvidence.entries.forEach { evidence ->
            listOf(
                PreparedParameterCandidateClass.SameTargetReplacement,
                PreparedParameterCandidateClass.TargetReplan,
            ).forEach { candidateClass ->
                val preparing = preparingTransaction(candidateClass)
                val rejected = preparing.accept(
                    ParameterTransactionFact.CandidatePreparationFailed(
                        preparing.identity,
                        preparing.candidate,
                        evidence,
                        ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                    ),
                ) as ParameterTransition.Advanced
                assertEquals(ParameterTransactionStage.Completed, rejected.transaction.stage)
                assertSame(
                    ParameterTransition.Stale,
                    preparing.accept(
                        ParameterTransactionFact.CandidatePreparationFailed(
                            preparing.identity,
                            CandidateIdentity(2),
                            evidence,
                            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                        ),
                    ),
                )
            }
            assertSame(
                ParameterTransition.Illegal,
                preparingTransaction(PreparedParameterCandidateClass.FrameRateOnly).accept(
                    ParameterTransactionFact.CandidatePreparationFailed(
                        transaction.identity,
                        transaction.candidate,
                        evidence,
                        ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                    ),
                ),
            )

            targetStates().filterKeys { it != ParameterTransactionStage.Preparing }.values.forEach { otherStage ->
                assertSame(
                    ParameterTransition.Illegal,
                    otherStage.accept(
                        ParameterTransactionFact.CandidatePreparationFailed(
                            otherStage.identity,
                            otherStage.candidate,
                            evidence,
                            ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                        ),
                    ),
                )
            }
            assertSame(
                ParameterTransition.Stale,
                transaction.accept(
                    ParameterTransactionFact.CandidatePreparationFailed(
                        TransactionIdentity(2),
                        transaction.candidate,
                        evidence,
                        ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                    ),
                ),
            )
        }
    }

    @Test
    fun preparationStartedTimeoutIsDistinctAndFencedToTheCurrentCandidate() {
        listOf(
            PreparedParameterCandidateClass.SameTargetReplacement,
            PreparedParameterCandidateClass.TargetReplan,
        ).forEach { candidateClass ->
            val preparing = preparingTransaction(candidateClass)
            val timeout = ParameterTransactionFact.CandidatePreparationStartedTimedOut(
                preparing.identity,
                preparing.candidate,
            )
            val completed = preparing.accept(timeout) as ParameterTransition.Advanced
            assertEquals(ParameterTransactionStage.Completed, completed.transaction.stage)
            assertSame(
                ParameterTransition.Stale,
                preparing.accept(timeout.copy(candidate = CandidateIdentity(2))),
            )
        }
        val frame = preparingTransaction(PreparedParameterCandidateClass.FrameRateOnly)
        assertSame(
            ParameterTransition.Illegal,
            frame.accept(ParameterTransactionFact.CandidatePreparationStartedTimedOut(frame.identity, frame.candidate)),
        )
    }

    @Test
    fun rejectionCancellationAndTerminalFactsHaveClosedFirstWinnerBoundaries() {
        val transaction = targetStates().getValue(ParameterTransactionStage.Preparing)

        ParameterPreparationRejectionEvidence.entries.forEach { evidence ->
            val rejected = (transaction.accept(
                ParameterTransactionFact.CandidatePreparationFailed(
                    transaction.identity,
                    transaction.candidate,
                    evidence,
                    ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                ),
            ) as ParameterTransition.Advanced).transaction
            assertSame(
                ParameterTransition.Stale,
                rejected.accept(ParameterTransactionFact.Cancelled(transaction.identity)),
            )
            assertSame(
                ParameterTransition.Stale,
                rejected.invalidateForTerminal(),
            )
        }

        val cancelled = (transaction.accept(
            ParameterTransactionFact.Cancelled(transaction.identity),
        ) as ParameterTransition.Advanced).transaction
        val terminal = transaction.invalidateForTerminal().advanced()
        ParameterPreparationRejectionEvidence.entries.forEach { evidence ->
            assertSame(
                ParameterTransition.Stale,
                cancelled.accept(
                    ParameterTransactionFact.CandidatePreparationFailed(
                        transaction.identity,
                        transaction.candidate,
                        evidence,
                        ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                    ),
                ),
            )
            assertSame(
                ParameterTransition.Stale,
                terminal.accept(
                    ParameterTransactionFact.CandidatePreparationFailed(
                        transaction.identity,
                        transaction.candidate,
                        evidence,
                        ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                    ),
                ),
            )
        }
    }

    @Test
    fun preparationSuccessFailureAndSupersessionHaveExactFirstWinnerBoundaries() {
        ParameterPreparationRejectionEvidence.entries.forEach { evidence ->
            listOf(
                PreparedParameterCandidateClass.SameTargetReplacement,
                PreparedParameterCandidateClass.TargetReplan,
            ).forEach { candidateClass ->
                val preparing = preparingTransaction(candidateClass)
                val owner = CompleteOwnerIdentity(1)
                val target = if (candidateClass == PreparedParameterCandidateClass.TargetReplan) {
                    TargetIdentity(1)
                } else {
                    null
                }
                val preparedFact = ParameterTransactionFact.CandidatePrepared(
                    preparing.identity,
                    preparing.candidate,
                    owner,
                    target,
                )
                val failedFact = ParameterTransactionFact.CandidatePreparationFailed(
                    preparing.identity,
                    preparing.candidate,
                    evidence,
                    ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
                )

                val prepared = (preparing.accept(preparedFact) as ParameterTransition.Advanced).transaction
                assertSame(ParameterTransition.Illegal, prepared.accept(failedFact))

                val failed = (preparing.accept(failedFact) as ParameterTransition.Advanced).transaction
                assertSame(ParameterTransition.Stale, failed.accept(preparedFact))

                val superseded = (preparing.accept(
                    ParameterTransactionFact.Superseded(preparing.identity),
                ) as ParameterTransition.Advanced).transaction
                assertSame(ParameterTransition.Stale, superseded.accept(failedFact))
                assertSame(
                    ParameterTransition.Stale,
                    failed.accept(ParameterTransactionFact.Superseded(preparing.identity)),
                )
            }
        }
    }

    @Test
    fun structurallyUnreachableStageShapesAreRejectedAtConstruction() {
        val tx = TransactionIdentity(1)
        val desired = DesiredSnapshotIdentity(1)
        val candidate = CandidateIdentity(1)

        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.SameTargetReplacement,
                stage = ParameterTransactionStage.Suspended,
                candidateOwner = CompleteOwnerIdentity(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.TargetReplan,
                stage = ParameterTransactionStage.CandidateReady,
                candidateOwner = CompleteOwnerIdentity(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.TargetReplan,
                superseded = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.TargetReplan,
                waiterAttached = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.TargetReplan,
                stage = ParameterTransactionStage.Completed,
                waiterAttached = false,
                superseded = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterTransaction(
                tx,
                desired,
                EffectiveSnapshotIdentity(1),
                candidate,
                PreparedParameterCandidateClass.SameTargetReplacement,
                stage = ParameterTransactionStage.Completed,
                candidateOwner = CompleteOwnerIdentity(1),
                waiterAttached = false,
                superseded = true,
            )
        }
    }

    private companion object {
        val legalFrom = mapOf(
            ParameterTransactionFact.CandidatePrepared::class to ParameterTransactionStage.Preparing,
            ParameterTransactionFact.ReadyPermitAcquired::class to ParameterTransactionStage.CandidateReady,
            ParameterTransactionFact.DrainCompleted::class to ParameterTransactionStage.Draining,
            ParameterTransactionFact.RetargetStarted::class to ParameterTransactionStage.RetargetArmed,
            ParameterTransactionFact.TargetAcknowledged::class to ParameterTransactionStage.RetargetStarted,
            ParameterTransactionFact.OwnerCommitReady::class to ParameterTransactionStage.Converging,
            ParameterTransactionFact.CandidatePreparationFailed::class to ParameterTransactionStage.Preparing,
            ParameterTransactionFact.CandidatePreparationStartedTimedOut::class to ParameterTransactionStage.Preparing,
            ParameterTransactionFact.ReadyPermitRejected::class to ParameterTransactionStage.CandidateReady,
            ParameterTransactionFact.RetargetStartTimedOut::class to ParameterTransactionStage.RetargetArmed,
        )

        fun targetStates(): Map<ParameterTransactionStage, ParameterTransaction> {
            val tx = TransactionIdentity(1)
            val candidate = CandidateIdentity(1)
            val owner = CompleteOwnerIdentity(1)
            val target = TargetIdentity(1)
            val states = linkedMapOf<ParameterTransactionStage, ParameterTransaction>()
            var current = ParameterTransaction(
                identity = tx,
                desired = DesiredSnapshotIdentity(1),
                previousEffective = EffectiveSnapshotIdentity(1),
                candidate = candidate,
                candidateClass = PreparedParameterCandidateClass.TargetReplan,
            )
            states[current.stage] = current
            current = current.advanced(ParameterTransactionFact.CandidatePrepared(tx, candidate, owner, target))
            states[current.stage] = current
            current = current.advanced(ParameterTransactionFact.ReadyPermitAcquired(tx))
            states[current.stage] = current
            current = current.commitSuspension().advanced()
            states[current.stage] = current
            current = current.beginDrain().advanced()
            states[current.stage] = current
            current = current.advanced(ParameterTransactionFact.DrainCompleted(tx, EffectiveSnapshotIdentity(1)))
            states[current.stage] = current
            states[ParameterTransactionStage.Restoring] = (
                    current.accept(ParameterTransactionFact.RetargetStartTimedOut(tx)) as ParameterTransition.Advanced
                    ).transaction
            listOf<ParameterTransactionFact>(
                ParameterTransactionFact.RetargetStarted(tx, target),
                ParameterTransactionFact.TargetAcknowledged(tx, target),
            ).forEach { fact ->
                current = (current.accept(fact) as ParameterTransition.Advanced).transaction
                states[current.stage] = current
            }
            current = current.beginConvergence().advanced()
            states[current.stage] = current
            return states
        }

        private fun ParameterTransaction.advanced(fact: ParameterTransactionFact): ParameterTransaction =
            (accept(fact) as ParameterTransition.Advanced).transaction

        private fun ParameterTransition.advanced(): ParameterTransaction =
            (this as ParameterTransition.Advanced).transaction

        fun candidatePreparationFailed(
            transaction: TransactionIdentity,
        ) = ParameterTransactionFact.CandidatePreparationFailed(
            transaction = transaction,
            candidate = CandidateIdentity(1),
            evidence = ParameterPreparationRejectionEvidence.RequestedPlanInvalid,
            ownership = ReturnedCandidatePreparationOwnership.NoEncoderToCleanup,
        )

        fun preparingTransaction(candidateClass: PreparedParameterCandidateClass) = ParameterTransaction(
            identity = TransactionIdentity(1),
            desired = DesiredSnapshotIdentity(1),
            previousEffective = EffectiveSnapshotIdentity(1),
            candidate = CandidateIdentity(1),
            candidateClass = candidateClass,
        )
    }
}
