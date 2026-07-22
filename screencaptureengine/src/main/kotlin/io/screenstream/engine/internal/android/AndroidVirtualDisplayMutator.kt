package io.screenstream.engine.internal.android

import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetPortUseOutcome
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetStagedDetachPortCommittedFact
import io.screenstream.engine.internal.target.TargetStagedDetachPortRetiredFact
import io.screenstream.engine.internal.target.TargetStagedDetachPortUnusedFact
import io.screenstream.engine.internal.target.TargetStagedPortFact
import io.screenstream.engine.internal.target.TargetStagedPortPostExposedFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortCommittedFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortRetiredFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortUnusedFact
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/**
 * Serialized physical mutation surface for the sole VirtualDisplay owned by [AndroidCaptureOwner].
 * It executes immutable commands and returns mechanics; it owns no Session currentness or lifecycle policy.
 */
internal class AndroidVirtualDisplayMutator(
    private val ownership: AtomicReference<AndroidVirtualDisplayOwnership?>,
    private val lane: AndroidLaneRuntime,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
) {
    private sealed interface PreparedCutoffProof {
        class Resize(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayResizeEvidence>,
        ) : PreparedCutoffProof

        class Detach(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayDetachEvidence>,
        ) : PreparedCutoffProof

        class Attach(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayAttachEvidence>,
        ) : PreparedCutoffProof
    }

    private sealed interface MutationRoot {
        class Resize(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayResizeEvidence>,
        ) : MutationRoot {
            internal val cutoffProof = PreparedCutoffProof.Resize(operation, ticket)
        }

        class Detach(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayDetachEvidence>,
        ) : MutationRoot {
            internal val cutoffProof = PreparedCutoffProof.Detach(operation, ticket)
        }

        class Attach(
            internal val operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
            internal val ticket: AndroidPostTicket<AndroidVirtualDisplayAttachEvidence>,
        ) : MutationRoot {
            internal val cutoffProof = PreparedCutoffProof.Attach(operation, ticket)
        }
    }

    private sealed interface PreparationClaim {
        class Resize(
            internal val ownership: AndroidVirtualDisplayOwnership,
            internal val command: AndroidVirtualDisplayResizeCommand,
            internal val identity: AndroidFiniteOperationIdentity,
        ) : PreparationClaim

        class Detach(
            internal val ownership: AndroidAttachedVirtualDisplay,
            internal val target: CurrentTarget,
            internal val identity: AndroidFiniteOperationIdentity,
            internal val reconfigurationIdentity: Long,
        ) : PreparationClaim {
            private val returnedPort = AtomicReference<io.screenstream.engine.internal.target.TargetPorts.StagedAndroidDetachPort?>(null)
            internal fun root(port: io.screenstream.engine.internal.target.TargetPorts.StagedAndroidDetachPort): Boolean =
                returnedPort.compareAndSet(null, port)
            internal fun owns(port: io.screenstream.engine.internal.target.TargetPorts.StagedAndroidDetachPort): Boolean =
                returnedPort.get() === port
        }

        class Attach(
            internal val ownership: AndroidMechanicallyDetachedVirtualDisplay,
            internal val target: CurrentTarget,
            internal val identity: AndroidFiniteOperationIdentity,
            internal val reconfigurationIdentity: Long,
        ) : PreparationClaim {
            private sealed interface ReturnedPreparation {
                class Port(
                    internal val value: io.screenstream.engine.internal.target.TargetPorts.StagedAndroidSurfacePort,
                ) : ReturnedPreparation

                class Retired(
                    internal val value: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact,
                ) : ReturnedPreparation
            }

            private val returnedPreparation = AtomicReference<ReturnedPreparation?>(null)

            internal fun root(port: io.screenstream.engine.internal.target.TargetPorts.StagedAndroidSurfacePort): Boolean =
                returnedPreparation.compareAndSet(null, ReturnedPreparation.Port(port))

            internal fun root(fact: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact): Boolean =
                returnedPreparation.compareAndSet(null, ReturnedPreparation.Retired(fact))

            internal fun owns(port: io.screenstream.engine.internal.target.TargetPorts.StagedAndroidSurfacePort): Boolean =
                (returnedPreparation.get() as? ReturnedPreparation.Port)?.value === port

            internal fun owns(fact: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact): Boolean =
                (returnedPreparation.get() as? ReturnedPreparation.Retired)?.value === fact
        }
    }

    private sealed interface MutationSlot {
        object OpenEmpty : MutationSlot
        class Preparing(internal val claim: PreparationClaim) : MutationSlot
        class TerminalPreparing(internal val claim: PreparationClaim) : MutationSlot
        class CommitInFlight(internal val root: MutationRoot) : MutationSlot
        class Owned(internal val root: MutationRoot) : MutationSlot
        class Submitting(internal val root: MutationRoot) : MutationSlot
        class RetiredUnused(internal val root: MutationRoot) : MutationSlot
        object TerminalEmpty : MutationSlot
        class TerminalCommitInFlight(internal val root: MutationRoot) : MutationSlot
        class TerminalOwned(internal val root: MutationRoot) : MutationSlot
        class TerminalSubmitting(internal val root: MutationRoot) : MutationSlot
        class TerminalArbitrating(internal val root: MutationRoot) : MutationSlot
        class TerminalAwaitingPhysicalProof(internal val root: MutationRoot) : MutationSlot
        class TerminalTransferred(internal val root: MutationRoot) : MutationSlot
        class TerminalSettled(internal val root: MutationRoot) : MutationSlot
        class TerminalQuarantined(internal val root: MutationRoot) : MutationSlot
        class TerminalRetiredUnused(internal val root: MutationRoot) : MutationSlot
    }

    private val mutationSlot = AtomicReference<MutationSlot>(MutationSlot.OpenEmpty)

    internal val hasUnsettledOperation: Boolean
        get() = when (mutationSlot.get()) {
            MutationSlot.OpenEmpty,
            MutationSlot.TerminalEmpty,
                -> false
            else -> true
        }

    internal fun createResize(
        command: AndroidVirtualDisplayResizeCommand,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayResizeEvidence>? {
        val exactOwnership = ownership.get() ?: return null
        val ownedTarget = when (exactOwnership) {
            is AndroidAttachedVirtualDisplay -> exactOwnership.target
            is AndroidMechanicallyDetachedVirtualDisplay -> exactOwnership.attached.target
            is AndroidAttachmentUncertainVirtualDisplay -> exactOwnership.target
        }
        if (ownedTarget !== command.target || exactOwnership.actualLogicalTuple == command.requestedTuple) return null
        val claim = PreparationClaim.Resize(exactOwnership, command, identity)
        val preparing = MutationSlot.Preparing(claim)
        if (!mutationSlot.compareAndSet(MutationSlot.OpenEmpty, preparing)) return null
        try {
            val ownerBag = AndroidVirtualDisplayResizeOwnerBag(exactOwnership, command)
            val operation = finiteOccurrence(
                identity,
                AndroidVirtualDisplayResizeEvidence(),
                ownerBag,
            )
            val ticket = lane.ticket(
                operation,
                "Android VirtualDisplay resize rejected",
                AndroidEnteredWork {
                    try {
                        check(ownership.get() === ownerBag.ownership)
                        val previous = ownerBag.ownership.actualLogicalTuple
                        val requested = ownerBag.command.requestedTuple
                        ownerBag.ownership.virtualDisplay.resize(
                            requested.widthPx,
                            requested.heightPx,
                            requested.densityDpi,
                        )
                        check(operation.settlementGate.withLock {
                            operation.returnCell.evidence.recordAppliedTupleLocked(requested) &&
                                operation.returnCell.evidence.recordSelectedResultLocked(ownerBag.appliedResult) &&
                                ownerBag.ownership.mechanicalState.recordResizeReturnedLocked(previous, requested)
                        })
                        operation.publishNormalReturn()
                    } catch (failure: Exception) {
                        operation.publishThrownReturn(failure)
                    }
                    signalBestEffort()
                },
            )
            check(ownerBag.bindOperation(operation, ticket))
            val root = MutationRoot.Resize(operation, ticket)
            resolvePreparedRoot(preparing, claim, root)
            return operation
        } catch (raw: Throwable) {
            when (val exact = mutationSlot.get()) {
                preparing -> resolvePreparationFailure(preparing, claim)
                is MutationSlot.TerminalPreparing -> if (exact.claim === claim) {
                    resolvePreparationFailure(preparing, claim)
                }
                else -> Unit
            }
            throw raw
        }
    }

    internal fun sealTerminalCutoff(): Boolean {
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when (exact) {
                MutationSlot.OpenEmpty -> MutationSlot.TerminalEmpty
                is MutationSlot.Preparing -> MutationSlot.TerminalPreparing(exact.claim)
                is MutationSlot.CommitInFlight -> MutationSlot.TerminalCommitInFlight(exact.root)
                is MutationSlot.Owned -> MutationSlot.TerminalOwned(exact.root)
                is MutationSlot.Submitting -> MutationSlot.TerminalSubmitting(exact.root)
                is MutationSlot.RetiredUnused -> MutationSlot.TerminalRetiredUnused(exact.root)
                MutationSlot.TerminalEmpty,
                is MutationSlot.TerminalPreparing,
                is MutationSlot.TerminalCommitInFlight,
                is MutationSlot.TerminalOwned,
                is MutationSlot.TerminalSubmitting,
                is MutationSlot.TerminalArbitrating,
                is MutationSlot.TerminalAwaitingPhysicalProof,
                is MutationSlot.TerminalTransferred,
                is MutationSlot.TerminalSettled,
                is MutationSlot.TerminalQuarantined,
                is MutationSlot.TerminalRetiredUnused,
                    -> return false
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return true
        }
    }

    internal fun submitResize(operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>): Boolean {
        val owned = mutationSlot.get() as? MutationSlot.Owned ?: return false
        val root = owned.root as? MutationRoot.Resize ?: return false
        if (root.operation !== operation) return false
        val submitting = claimSubmission(owned, root) ?: return false
        return try {
            postResizeRoot(root)
        } finally {
            releaseSubmission(submitting, root)
        }
    }

    internal fun claimResizeResult(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
    ): AndroidVirtualDisplayResizeResult? = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayResizeOwnerBag ?: return@withLock null
        val evidence = operation.returnCell.evidence
        evidence.selectedResult ?: if (operation.returnCell.disposition == OperationReturnDisposition.Thrown) {
            bag.failedResult.also { check(evidence.recordSelectedResultLocked(it)) }
        } else {
            null
        }
    }

    internal fun recordResizeResultConsumed(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
        result: AndroidVirtualDisplayResizeResult.Applied,
    ): Boolean = operation.settlementGate.withLock {
        ownsOperation(operation) && operation.returnCell.evidence.recordConsumedResultLocked(result)
    }

    internal fun releaseResize(operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>): Boolean =
        releaseWhen(operation) {
            it.returnCell.evidence.consumedResult != null ||
                it.returnCell.evidence.selectedResult is AndroidVirtualDisplayResizeResult.Failed &&
                it.entryDisposition == OperationEntryDisposition.Cancelled &&
                (it.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
                    (it.ownerBag as AndroidVirtualDisplayResizeOwnerBag)
                        .postTicket.authoritativePostCutoffProof.isActivatedExact ||
                    (it.ownerBag as AndroidVirtualDisplayResizeOwnerBag).postTicket.let { ticket ->
                        it.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                            ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                            ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                            ticket.postFailureResidue != null &&
                            it.submissionFailure === ticket.postFailureResidue
                    } ||
                    (it.ownerBag as AndroidVirtualDisplayResizeOwnerBag)
                        .postTicket.returnedWithoutPlatformEntryProof.activateLocked())
        }

    internal fun prepareDetach(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
        reconfigurationIdentity: Long,
    ): AndroidVirtualDisplayDetachPreparationResult? {
        require(reconfigurationIdentity > 0L)
        val exactOwnership = ownership.get() as? AndroidAttachedVirtualDisplay ?: return null
        if (exactOwnership.target !== target) return null
        val claim = PreparationClaim.Detach(exactOwnership, target, identity, reconfigurationIdentity)
        val preparing = MutationSlot.Preparing(claim)
        if (!mutationSlot.compareAndSet(MutationSlot.OpenEmpty, preparing)) return null
        val stagedPort = try {
            target.prepareStagedDetachPort(identity.operationIdentity, reconfigurationIdentity)
        } catch (raw: Throwable) {
            resolvePreparationFailure(preparing, claim)
            throw raw
        }
        check(claim.root(stagedPort))
        check(claim.owns(stagedPort))
        val evidence = AndroidVirtualDisplayDetachEvidence()
        val bag = AndroidVirtualDisplayDetachOwnerBag(exactOwnership, target, stagedPort, evidence)
        val operation = finiteOccurrence(identity, evidence, bag)
        val binding = AndroidTargetOperationBinding.create(stagedPort.commitCorrelation, operation)
        check(bag.bindOperation(binding, operation))
        val ticket = lane.ticket(
            operation,
            "Android VirtualDisplay detach rejected",
            AndroidEnteredWork {
                try {
                    check(ownership.get() === bag.ownership)
                    bag.ownership.virtualDisplay.setSurface(null)
                    operation.publishNormalReturn()
                } catch (failure: Exception) {
                    operation.publishThrownReturn(failure)
                }
                signalBestEffort()
            },
        )
        check(bag.bindPostTicket(ticket))
        val root = MutationRoot.Detach(operation, ticket)
        resolvePreparedRoot(preparing, claim, root)
        check(target.bindAndroidTargetOperation(stagedPort, binding))
        return when (val commit = checkNotNull(target.commitStagedDetachPort(stagedPort))) {
            is TargetStagedDetachPortCommittedFact -> {
                check(commit === bag.committedFact)
                check(commitPreparedRoot(root))
                AndroidVirtualDisplayDetachPreparationResult.Ready(operation)
            }

            is TargetStagedDetachPortUnusedFact -> {
                check(operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordPostOutcomeLocked(bag.retiredUnusedOutcome) &&
                            operation.settleInertBeforeEntryLocked()
                })
                check(retireUnusedRoot(root))
                val unusedRoot = AndroidVirtualDisplayMutationUnusedRoot.Detach(
                    operation,
                    ticket,
                    bag.retiredUnusedOutcome,
                )
                AndroidVirtualDisplayDetachPreparationResult.RetiredUnused(
                    operation,
                    bag.retiredUnusedOutcome.also { check(it.targetFact === commit) },
                    unusedRoot,
                )
            }
        }
    }

    internal fun submitDetach(operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>): Boolean {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return false
        val owned = mutationSlot.get() as? MutationSlot.Owned ?: return false
        val root = owned.root as? MutationRoot.Detach ?: return false
        if (root.operation !== operation || root.ticket !== bag.postTicket) return false
        val submitting = claimSubmission(owned, root) ?: return false
        return try {
            val accepted = try {
                lane.post(root.ticket) == AndroidPostResult.Accepted
            } catch (raw: Throwable) {
                recordInitialPostOutcome(
                    operation,
                    bag.postExposedOutcome,
                    bag.definitelyUnenteredOutcome,
                    accepted = true,
                )
                throw raw
            }
            recordInitialPostOutcome(operation, bag.postExposedOutcome, bag.definitelyUnenteredOutcome, accepted)
            accepted
        } finally {
            releaseSubmission(submitting, root)
        }
    }

    internal fun claimDetachPostOutcome(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): AndroidTargetPostOutcome? = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return@withLock null
        refineDefinitelyUnentered(operation, bag.postTicket, bag.definitelyUnenteredOutcome)
        operation.returnCell.evidence.postOutcome
    }

    internal fun recordDetachPostOutcomeConsumed(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
        fact: TargetStagedPortFact,
    ): Boolean = operation.settlementGate.withLock {
        ownsOperation(operation) && operation.returnCell.evidence.recordConsumedPostFactLocked(fact)
    }

    internal fun claimDetachPlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): AndroidTargetPlatformResult? = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return@withLock null
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact
        ) {
            return@withLock null
        }
        when (operation.returnCell.disposition) {
            OperationReturnDisposition.Normal -> bag.platformResult
            OperationReturnDisposition.Thrown -> bag.settledResult
            OperationReturnDisposition.Empty -> null
        }
    }

    internal fun applyDetach(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
        receipt: TargetProducerDetachReceipt,
    ): Boolean = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact ||
            evidence.appliedTargetFact != null ||
            !matchesDetach(receipt, operation, bag.target, bag.stagedPort.port)
        ) {
            return@withLock false
        }
        if (!ownership.compareAndSet(bag.ownership, bag.detachedCandidate)) return@withLock false
        check(evidence.recordAppliedTargetFactLocked(receipt))
        bag.detachedCandidate.compactAfterApplication(operation, receipt)
    }

    internal fun applyDetachSettled(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
        fact: io.screenstream.engine.internal.target.TargetStagedDetachPortSettledFact,
    ): Boolean = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) || operation.returnCell.disposition != OperationReturnDisposition.Thrown ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact ||
            fact.operationIdentity != operation.identity || fact.targetIdentity !== bag.target.identity ||
            fact.provenance !== bag.stagedPort.port.provenance
        ) {
            return@withLock false
        }
        evidence.recordSettledTargetFactLocked(fact)
    }

    internal fun releaseDetach(operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>): Boolean =
        releaseWhen(operation) {
            val evidence = it.returnCell.evidence
            evidence.appliedTargetFact != null ||
                    evidence.settledTargetFact != null ||
                    evidence.postOutcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                    evidence.consumedPostFact is TargetStagedDetachPortRetiredFact
        }

    internal fun prepareAttach(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
        reconfigurationIdentity: Long,
    ): AndroidVirtualDisplayAttachPreparationResult? {
        require(reconfigurationIdentity > 0L)
        val prior = ownership.get() as? AndroidMechanicallyDetachedVirtualDisplay ?: return null
        val claim = PreparationClaim.Attach(prior, target, identity, reconfigurationIdentity)
        val preparing = MutationSlot.Preparing(claim)
        if (!mutationSlot.compareAndSet(MutationSlot.OpenEmpty, preparing)) return null
        val preparedPort = try {
            target.prepareStagedProducerPort(identity.operationIdentity, reconfigurationIdentity)
        } catch (raw: Throwable) {
            resolvePreparationFailure(preparing, claim)
            throw raw
        }
        if (preparedPort is io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact) {
            check(claim.root(preparedPort))
            val result = AndroidVirtualDisplayAttachPreparationResult.TargetPreparationFailed(preparedPort)
            check(claim.owns(preparedPort))
            resolvePreparationFailure(preparing, claim)
            return result
        }
        val stagedPort = preparedPort
            as? io.screenstream.engine.internal.target.TargetPorts.StagedAndroidSurfacePort
            ?: error("Target producer preparation returned an unknown result")
        check(claim.root(stagedPort))
        check(claim.owns(stagedPort))
        val evidence = AndroidVirtualDisplayAttachEvidence()
        val candidate = AndroidAttachedVirtualDisplay(prior, target, stagedPort.port, evidence)
        val bag = AndroidVirtualDisplayAttachOwnerBag(prior, target, stagedPort, candidate, evidence)
        val operation = finiteOccurrence(identity, evidence, bag)
        check(candidate.bindProducerOperation(operation))
        val binding = AndroidTargetOperationBinding.create(stagedPort.commitCorrelation, operation)
        check(bag.bindOperation(binding, operation))
        val ticket = lane.ticket(
            operation,
            "Android VirtualDisplay attachment rejected",
            AndroidEnteredWork {
                try {
                    check(ownership.get() === bag.priorOwnership)
                    check(
                        bag.stagedPort.port.withSurface { surface ->
                            bag.priorOwnership.virtualDisplay.setSurface(surface)
                        } == TargetPortUseOutcome.BodyReturned,
                    )
                    operation.publishNormalReturn()
                } catch (failure: Exception) {
                    operation.publishThrownReturn(failure)
                }
                signalBestEffort()
            },
        )
        check(bag.bindPostTicket(ticket))
        val root = MutationRoot.Attach(operation, ticket)
        resolvePreparedRoot(preparing, claim, root)
        check(target.bindAndroidTargetOperation(stagedPort, binding))
        return when (val commit = checkNotNull(target.commitStagedProducerPort(stagedPort))) {
            is TargetStagedProducerPortCommittedFact -> {
                check(commit === bag.committedFact)
                check(commitPreparedRoot(root))
                AndroidVirtualDisplayAttachPreparationResult.Ready(operation)
            }

            is TargetStagedProducerPortUnusedFact -> {
                check(operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordPostOutcomeLocked(bag.retiredUnusedOutcome) &&
                            operation.settleInertBeforeEntryLocked()
                })
                check(retireUnusedRoot(root))
                val unusedRoot = AndroidVirtualDisplayMutationUnusedRoot.Attach(
                    operation,
                    ticket,
                    bag.retiredUnusedOutcome,
                )
                AndroidVirtualDisplayAttachPreparationResult.RetiredUnused(
                    operation,
                    bag.retiredUnusedOutcome.also { check(it.targetFact === commit) },
                    unusedRoot,
                )
            }
        }
    }

    internal fun submitAttach(operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>): Boolean {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return false
        val owned = mutationSlot.get() as? MutationSlot.Owned ?: return false
        val root = owned.root as? MutationRoot.Attach ?: return false
        if (root.operation !== operation || root.ticket !== bag.postTicket) return false
        val submitting = claimSubmission(owned, root) ?: return false
        return try {
            val accepted = try {
                lane.post(root.ticket) == AndroidPostResult.Accepted
            } catch (raw: Throwable) {
                recordInitialPostOutcome(
                    operation,
                    bag.postExposedOutcome,
                    bag.definitelyUnenteredOutcome,
                    accepted = true,
                )
                throw raw
            }
            recordInitialPostOutcome(operation, bag.postExposedOutcome, bag.definitelyUnenteredOutcome, accepted)
            accepted
        } finally {
            releaseSubmission(submitting, root)
        }
    }

    internal fun claimAttachPostOutcome(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): AndroidTargetPostOutcome? = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return@withLock null
        refineDefinitelyUnentered(operation, bag.postTicket, bag.definitelyUnenteredOutcome)
        operation.returnCell.evidence.postOutcome
    }

    internal fun recordAttachPostOutcomeConsumed(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        fact: TargetStagedPortFact,
    ): Boolean = operation.settlementGate.withLock {
        ownsOperation(operation) && operation.returnCell.evidence.recordConsumedPostFactLocked(fact)
    }

    internal fun claimAttachPlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): AndroidTargetPlatformResult? = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return@withLock null
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact
        ) {
            return@withLock null
        }
        when (operation.returnCell.disposition) {
            OperationReturnDisposition.Normal -> bag.platformResult
            OperationReturnDisposition.Thrown -> bag.settledResult
            OperationReturnDisposition.Empty -> null
        }
    }

    internal fun applyAttach(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        fact: TargetProducerApplicationFact,
    ): Boolean = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact ||
            fact !is TargetProducerEvidence ||
            evidence.appliedTargetFact != null ||
            !matchesProducer(fact, operation, bag.target, bag.stagedPort.port)
        ) {
            return@withLock false
        }
        if (!ownership.compareAndSet(bag.priorOwnership, bag.applicationCandidate)) return@withLock false
        check(evidence.recordAppliedTargetFactLocked(fact))
        bag.applicationCandidate.compactAfterApplication(operation, fact)
    }

    internal fun applyAttachSettled(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        fact: io.screenstream.engine.internal.target.TargetStagedProducerPortSettledFact,
    ): Boolean = operation.settlementGate.withLock {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (!ownsOperation(operation) || operation.returnCell.disposition != OperationReturnDisposition.Thrown ||
            evidence.postOutcome !is AndroidTargetPostOutcome.PostExposed ||
            evidence.consumedPostFact !is TargetStagedPortPostExposedFact ||
            fact.operationIdentity != operation.identity || fact.targetIdentity !== bag.target.identity ||
            fact.provenance !== bag.stagedPort.port.provenance ||
            !ownership.compareAndSet(bag.priorOwnership, bag.uncertainCandidate)
        ) {
            return@withLock false
        }
        evidence.recordSettledTargetFactLocked(fact)
    }

    internal fun releaseAttach(operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>): Boolean =
        releaseWhen(operation) {
            val evidence = it.returnCell.evidence
            evidence.appliedTargetFact != null ||
                    evidence.settledTargetFact != null ||
                    evidence.postOutcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                    evidence.consumedPostFact is TargetStagedProducerPortRetiredFact
        }

    private fun <R : OperationEvidence> finiteOccurrence(
        identity: AndroidFiniteOperationIdentity,
        evidence: R,
        ownerBag: io.screenstream.engine.internal.settlement.OperationOwnerBag,
    ): OperationOccurrence<R> = OperationOccurrence(
        identity = identity.operationIdentity,
        clock = clock,
        returnCell = OperationReturnCell(evidence),
        ownerBag = ownerBag,
        deadlineIdentity = identity.deadlineIdentity,
        deadlineDurationNanos = androidEnteredOperationSafetyNanos,
        initialWakeGeneration = identity.deadlineWakeGeneration,
        timeoutCause = identity.timeoutCause,
        wakeSignal = settlementSignal,
    )

    private fun resolvePreparedRoot(
        preparing: MutationSlot.Preparing,
        claim: PreparationClaim,
        root: MutationRoot,
    ): MutationSlot {
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim -> when (root) {
                    is MutationRoot.Resize -> MutationSlot.Owned(root)
                    is MutationRoot.Detach,
                    is MutationRoot.Attach,
                        -> MutationSlot.CommitInFlight(root)
                }
                exact is MutationSlot.TerminalPreparing && exact.claim === claim ->
                    when (root) {
                        is MutationRoot.Resize -> MutationSlot.TerminalOwned(root)
                        is MutationRoot.Detach,
                        is MutationRoot.Attach,
                            -> MutationSlot.TerminalCommitInFlight(root)
                    }
                else -> error("VirtualDisplay mutation preparation lost its exact claim")
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return replacement
        }
    }

    private fun resolvePreparationFailure(
        preparing: MutationSlot.Preparing,
        claim: PreparationClaim,
    ) {
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim -> MutationSlot.OpenEmpty
                exact is MutationSlot.TerminalPreparing && exact.claim === claim -> MutationSlot.TerminalEmpty
                else -> error("VirtualDisplay mutation preparation lost its exact claim")
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun retireUnusedRoot(root: MutationRoot): Boolean {
        val openRetired = MutationSlot.RetiredUnused(root)
        val terminalRetired = MutationSlot.TerminalRetiredUnused(root)
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when (exact) {
                is MutationSlot.CommitInFlight -> openRetired.takeIf { exact.root === root } ?: return false
                is MutationSlot.TerminalCommitInFlight ->
                    terminalRetired.takeIf { exact.root === root } ?: return false
                is MutationSlot.RetiredUnused -> return exact.root === root
                is MutationSlot.TerminalRetiredUnused -> return exact.root === root
                else -> return false
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return true
        }
    }

    private fun commitPreparedRoot(root: MutationRoot): Boolean {
        val openOwned = MutationSlot.Owned(root)
        val terminalOwned = MutationSlot.TerminalOwned(root)
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when (exact) {
                is MutationSlot.CommitInFlight -> openOwned.takeIf { exact.root === root } ?: return false
                is MutationSlot.TerminalCommitInFlight ->
                    terminalOwned.takeIf { exact.root === root } ?: return false
                is MutationSlot.Owned -> return exact.root === root
                is MutationSlot.TerminalOwned -> return exact.root === root
                else -> return false
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return true
        }
    }

    private fun claimSubmission(
        owned: MutationSlot.Owned,
        root: MutationRoot,
    ): MutationSlot.Submitting? {
        if (owned.root !== root) return null
        val submitting = MutationSlot.Submitting(root)
        return submitting.takeIf { mutationSlot.compareAndSet(owned, it) }
    }

    private fun releaseSubmission(
        submitting: MutationSlot.Submitting,
        root: MutationRoot,
    ) {
        while (true) {
            val exact = mutationSlot.get()
            val replacement = when {
                exact === submitting && submitting.root === root -> MutationSlot.Owned(root)
                exact is MutationSlot.TerminalSubmitting && exact.root === root -> MutationSlot.TerminalOwned(root)
                else -> error("VirtualDisplay mutation submission lost its exact root")
            }
            if (mutationSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun postResizeRoot(
        root: MutationRoot.Resize,
    ): Boolean {
        val accepted = try {
            lane.post(root.ticket) == AndroidPostResult.Accepted
        } catch (raw: Throwable) {
            throw raw
        }
        if (!accepted) {
            root.operation.settlementGate.withLock {
                val authoritativelyRejected =
                    root.operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                        root.ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                        root.ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                        root.ticket.postFailureResidue != null &&
                        root.operation.submissionFailure === root.ticket.postFailureResidue &&
                        root.operation.entryDisposition == OperationEntryDisposition.Unentered &&
                        root.operation.settleInertBeforeEntryLocked()
                if (authoritativelyRejected || root.ticket.authoritativePostCutoffProof.activateLocked()) {
                    check(root.operation.returnCell.evidence.recordSelectedResultLocked(
                        (root.operation.ownerBag as AndroidVirtualDisplayResizeOwnerBag).failedResult,
                    ))
                }
            }
        }
        return accepted
    }

    private fun <R : OperationEvidence> recordInitialPostOutcome(
        operation: OperationOccurrence<R>,
        postExposed: AndroidTargetPostOutcome.PostExposed,
        definitelyUnentered: AndroidTargetPostOutcome.DefinitelyUnentered,
        accepted: Boolean,
    ) {
        operation.settlementGate.withLock {
            val ticket = when (val ownerBag = operation.ownerBag) {
                is AndroidVirtualDisplayAttachOwnerBag -> ownerBag.postTicket
                is AndroidVirtualDisplayDetachOwnerBag -> ownerBag.postTicket
                else -> return@withLock
            }
            val outcome = if (accepted) {
                postExposed
            } else if (operation.entryDisposition == OperationEntryDisposition.Entered ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionAmbiguousFatal != null ||
                ticket.failureExposure == AndroidPostFailureExposure.AcceptanceAmbiguous ||
                ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack
            ) {
                postExposed
            } else if (operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.settleInertBeforeEntryLocked()
            ) {
                definitelyUnentered
            } else if (ticket.authoritativePostCutoffProof.activateLocked()) {
                definitelyUnentered
            } else {
                null
            }
            when (val evidence = operation.returnCell.evidence) {
                is AndroidVirtualDisplayAttachEvidence -> outcome?.let(evidence::recordPostOutcomeLocked)
                is AndroidVirtualDisplayDetachEvidence -> outcome?.let(evidence::recordPostOutcomeLocked)
            }
        }
        signalBestEffort()
    }

    private fun <R : OperationEvidence> refineDefinitelyUnentered(
        operation: OperationOccurrence<R>,
        ticket: AndroidPostTicket<R>?,
        outcome: AndroidTargetPostOutcome.DefinitelyUnentered,
    ) {
        check(operation.settlementGate.isHeldByCurrentThread)
        val finalLaneProof = if (ticket != null &&
            ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
            operation.entryDisposition == OperationEntryDisposition.Unentered &&
            operation.returnCell.disposition == OperationReturnDisposition.Empty
        ) {
            lane.terminationReceipt?.let { receipt ->
                lane.proveFinalLaneNoEntryLocked(receipt, ticket, operation)
            }
        } else null
        val acceptedReturnedInert = operation.entryDisposition == OperationEntryDisposition.Cancelled &&
            operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
            ticket?.physicalState == AndroidPostPhysicalDisposition.Returned &&
            ticket.postFailureResidue == null && ticket.failureExposure == AndroidPostFailureExposure.None &&
            operation.submissionFailure == null && operation.submissionAmbiguousFatal == null
        if (!acceptedReturnedInert && finalLaneProof == null) return
        when (val evidence = operation.returnCell.evidence) {
            is AndroidVirtualDisplayAttachEvidence -> evidence.refinePostOutcomeToDefinitelyUnenteredLocked(outcome)
            is AndroidVirtualDisplayDetachEvidence -> evidence.refinePostOutcomeToDefinitelyUnenteredLocked(outcome)
        }
    }

    internal fun transferResizeToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Resize? {
        val exactSlot = mutationSlot.get()
        val root = when (exactSlot) {
            is MutationSlot.Owned -> exactSlot.root
            is MutationSlot.TerminalOwned -> exactSlot.root
            is MutationSlot.TerminalAwaitingPhysicalProof -> exactSlot.root
            else -> return null
        } as? MutationRoot.Resize ?: return null
        if (root.operation !== operation) return null
        return transferToTerminal(exactSlot, root, operation, (operation.ownerBag as AndroidVirtualDisplayResizeOwnerBag).terminalTransfer)
    }

    internal fun transferDetachToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Detach? {
        val exactSlot = mutationSlot.get()
        val root = when (exactSlot) {
            is MutationSlot.Owned -> exactSlot.root
            is MutationSlot.TerminalOwned -> exactSlot.root
            is MutationSlot.TerminalAwaitingPhysicalProof -> exactSlot.root
            else -> return null
        } as? MutationRoot.Detach ?: return null
        if (root.operation !== operation) return null
        return transferToTerminal(exactSlot, root, operation, (operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag).terminalTransfer)
    }

    internal fun transferAttachToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Attach? {
        val exactSlot = mutationSlot.get()
        val root = when (exactSlot) {
            is MutationSlot.Owned -> exactSlot.root
            is MutationSlot.TerminalOwned -> exactSlot.root
            is MutationSlot.TerminalAwaitingPhysicalProof -> exactSlot.root
            else -> return null
        } as? MutationRoot.Attach ?: return null
        if (root.operation !== operation) return null
        return transferToTerminal(exactSlot, root, operation, (operation.ownerBag as AndroidVirtualDisplayAttachOwnerBag).terminalTransfer)
    }

    internal fun releaseTerminalTransfer(transfer: AndroidVirtualDisplayMutationTerminalTransfer): Boolean {
        val terminalTransferred = mutationSlot.get() as? MutationSlot.TerminalTransferred ?: return false
        val exactRoot = terminalTransferred.root
        val settled = when (transfer) {
            is AndroidVirtualDisplayMutationTerminalTransfer.Resize -> {
                val root = exactRoot as? MutationRoot.Resize ?: return false
                val operation = transfer.operation
                if (root.operation !== operation || root.ticket !== transfer.ticket) return false
                operation.settlementGate.withLock {
                    val evidence = operation.returnCell.evidence
                    evidence.consumedResult != null || evidence.selectedResult is AndroidVirtualDisplayResizeResult.Failed
                }
            }

            is AndroidVirtualDisplayMutationTerminalTransfer.Detach -> {
                val root = exactRoot as? MutationRoot.Detach ?: return false
                val operation = transfer.operation
                if (root.operation !== operation || root.ticket !== transfer.ticket) return false
                operation.settlementGate.withLock {
                    val evidence = operation.returnCell.evidence
                    evidence.appliedTargetFact != null || evidence.settledTargetFact != null ||
                            evidence.postOutcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                            evidence.consumedPostFact is TargetStagedDetachPortRetiredFact ||
                            evidence.postOutcome is AndroidTargetPostOutcome.RetiredUnused &&
                            evidence.consumedPostFact is TargetStagedDetachPortUnusedFact
                }
            }

            is AndroidVirtualDisplayMutationTerminalTransfer.Attach -> {
                val root = exactRoot as? MutationRoot.Attach ?: return false
                val operation = transfer.operation
                if (root.operation !== operation || root.ticket !== transfer.ticket) return false
                operation.settlementGate.withLock {
                    val evidence = operation.returnCell.evidence
                    evidence.appliedTargetFact != null || evidence.settledTargetFact != null ||
                            evidence.postOutcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                            evidence.consumedPostFact is TargetStagedProducerPortRetiredFact ||
                            evidence.postOutcome is AndroidTargetPostOutcome.RetiredUnused &&
                            evidence.consumedPostFact is TargetStagedProducerPortUnusedFact
                }
            }
        }
        return settled && mutationSlot.compareAndSet(terminalTransferred, MutationSlot.TerminalEmpty)
    }

    internal fun releaseUnusedRoot(root: AndroidVirtualDisplayMutationUnusedRoot): Boolean {
        val exactSlot = mutationSlot.get()
        val exactRoot = when (exactSlot) {
            is MutationSlot.RetiredUnused -> exactSlot.root
            is MutationSlot.TerminalRetiredUnused -> exactSlot.root
            else -> return false
        }
        val consumed = when (root) {
            is AndroidVirtualDisplayMutationUnusedRoot.Detach -> {
                val active = exactRoot as? MutationRoot.Detach ?: return false
                if (active.operation !== root.operation || active.ticket !== root.ticket) return false
                root.operation.settlementGate.withLock {
                    root.operation.returnCell.evidence.postOutcome === root.outcome &&
                        root.operation.returnCell.evidence.consumedPostFact is TargetStagedDetachPortUnusedFact
                }
            }

            is AndroidVirtualDisplayMutationUnusedRoot.Attach -> {
                val active = exactRoot as? MutationRoot.Attach ?: return false
                if (active.operation !== root.operation || active.ticket !== root.ticket) return false
                root.operation.settlementGate.withLock {
                    root.operation.returnCell.evidence.postOutcome === root.outcome &&
                        root.operation.returnCell.evidence.consumedPostFact is TargetStagedProducerPortUnusedFact
                }
            }
        }
        val replacement = if (exactSlot is MutationSlot.TerminalRetiredUnused) {
            MutationSlot.TerminalEmpty
        } else {
            MutationSlot.OpenEmpty
        }
        return consumed && mutationSlot.compareAndSet(exactSlot, replacement)
    }

    private fun <R : OperationEvidence, T : AndroidVirtualDisplayMutationTerminalTransfer> transferToTerminal(
        exactSlot: MutationSlot,
        root: MutationRoot,
        operation: OperationOccurrence<R>,
        transfer: T,
    ): T? {
        if (exactSlot is MutationSlot.TerminalAwaitingPhysicalProof) {
            if (exactSlot.root !== root) return null
            resolveTerminalAwaitingPhysicalProof(exactSlot, root)
            return null
        }
        val terminalArbitrating = MutationSlot.TerminalArbitrating(root)
        when (exactSlot) {
            is MutationSlot.Owned -> {
                if (exactSlot.root !== root) return null
                if (!mutationSlot.compareAndSet(exactSlot, terminalArbitrating)) return null
            }
            is MutationSlot.TerminalOwned -> {
                if (exactSlot.root !== root || !mutationSlot.compareAndSet(exactSlot, terminalArbitrating)) return null
            }
            else -> return null
        }
        val localNoEntryProvenBeforeArbitration = exactTerminalNoEntryProven(root)
        val mandatoryCleanup = operation.settlementGate.withLock {
            !localNoEntryProvenBeforeArbitration &&
                operation.entryDisposition != OperationEntryDisposition.Cancelled &&
                operation.submissionDisposition != OperationSubmissionDisposition.None
        }
        val arbitration = operation.arbitrateTerminal(mandatoryCleanup = mandatoryCleanup)
        if (arbitration == OperationTerminalArbitration.CancelledUnentered) {
            if (!localNoEntryProvenBeforeArbitration && !exactTerminalNoEntryProven(root)) {
                val replacement = if (mutationRootPhysicalState(root) == AndroidPostPhysicalDisposition.Returned) {
                    MutationSlot.TerminalQuarantined(root)
                } else {
                    MutationSlot.TerminalAwaitingPhysicalProof(root)
                }
                check(mutationSlot.compareAndSet(terminalArbitrating, replacement))
                signalBestEffort()
                return null
            }
            check(recordExactLocalTerminalNoEntry(root))
            check(mutationSlot.compareAndSet(terminalArbitrating, MutationSlot.TerminalSettled(root)))
            signalBestEffort()
            return null
        }
        if (arbitration == OperationTerminalArbitration.Transferred) {
            check(mutationSlot.compareAndSet(terminalArbitrating, MutationSlot.TerminalTransferred(root)))
            return transfer
        }
        check(mutationSlot.compareAndSet(terminalArbitrating, MutationSlot.TerminalSettled(root)))
        return null
    }

    private fun resolveTerminalAwaitingPhysicalProof(
        awaiting: MutationSlot.TerminalAwaitingPhysicalProof,
        root: MutationRoot,
    ) {
        if (exactTerminalNoEntryProven(root)) {
            check(recordExactLocalTerminalNoEntry(root))
            if (mutationSlot.compareAndSet(awaiting, MutationSlot.TerminalSettled(root))) signalBestEffort()
            return
        }
        if (mutationRootPhysicalState(root) == AndroidPostPhysicalDisposition.Returned ||
            lane.terminationReceipt != null
        ) {
            if (mutationSlot.compareAndSet(awaiting, MutationSlot.TerminalQuarantined(root))) signalBestEffort()
        }
    }

    private fun exactTerminalNoEntryProven(root: MutationRoot): Boolean = when (root) {
        is MutationRoot.Resize -> exactTerminalNoEntryProven(
            root.operation,
            root.ticket,
            root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket,
        )
        is MutationRoot.Detach -> exactTerminalNoEntryProven(
            root.operation,
            root.ticket,
            root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket,
        )
        is MutationRoot.Attach -> exactTerminalNoEntryProven(
            root.operation,
            root.ticket,
            root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket,
        )
    }

    private fun <R : OperationEvidence> exactTerminalNoEntryProven(
        operation: OperationOccurrence<R>,
        ticket: AndroidPostTicket<R>,
        preparedTerminalCutoffExact: Boolean,
    ): Boolean = operation.settlementGate.withLock {
        val preparedTerminalCutoff = preparedTerminalCutoffExact &&
            operation.submissionDisposition == OperationSubmissionDisposition.None &&
            ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
            ticket.postFailureResidue == null && operation.submissionFailure == null &&
            operation.submissionAmbiguousFatal == null &&
            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
            (operation.entryDisposition == OperationEntryDisposition.Cancelled ||
                operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.settleInertBeforeEntryLocked())
        if (preparedTerminalCutoff) return@withLock true
        if (ticket.authoritativePostCutoffProof.activateLocked() ||
            ticket.returnedWithoutPlatformEntryProof.activateLocked()
        ) return@withLock true
        val authoritativeRejection = operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
            ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
            ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
            ticket.postFailureResidue != null && operation.submissionFailure === ticket.postFailureResidue &&
            operation.submissionAmbiguousFatal == null &&
            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
            (operation.entryDisposition == OperationEntryDisposition.Cancelled ||
                operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.settleInertBeforeEntryLocked())
        if (authoritativeRejection) return@withLock true
        val receipt = lane.terminationReceipt ?: return@withLock false
        if (operation.entryDisposition == OperationEntryDisposition.Unentered) {
            lane.proveFinalLaneNoEntryLocked(receipt, ticket, operation) != null
        } else {
            lane.proveFinalLaneNoEntryAfterCancellationLocked(receipt, ticket, operation) != null
        }
    }

    private fun recordExactLocalTerminalNoEntry(root: MutationRoot): Boolean = when (root) {
        is MutationRoot.Resize -> root.operation.settlementGate.withLock {
            check(root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket)
            val failed = (root.operation.ownerBag as AndroidVirtualDisplayResizeOwnerBag).failedResult
            root.operation.returnCell.evidence.selectedResult === failed ||
                root.operation.returnCell.evidence.recordSelectedResultLocked(failed)
        }

        is MutationRoot.Detach -> root.operation.settlementGate.withLock {
            check(root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket)
            val definitelyUnentered = (root.operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag)
                .definitelyUnenteredOutcome
            val evidence = root.operation.returnCell.evidence
            evidence.postOutcome === definitelyUnentered ||
                evidence.postOutcome is AndroidTargetPostOutcome.PostExposed &&
                evidence.refinePostOutcomeToDefinitelyUnenteredLocked(definitelyUnentered) ||
                evidence.postOutcome == null && evidence.recordPostOutcomeLocked(definitelyUnentered)
        }

        is MutationRoot.Attach -> root.operation.settlementGate.withLock {
            check(root.cutoffProof.operation === root.operation && root.cutoffProof.ticket === root.ticket)
            val definitelyUnentered = (root.operation.ownerBag as AndroidVirtualDisplayAttachOwnerBag)
                .definitelyUnenteredOutcome
            val evidence = root.operation.returnCell.evidence
            evidence.postOutcome === definitelyUnentered ||
                evidence.postOutcome is AndroidTargetPostOutcome.PostExposed &&
                evidence.refinePostOutcomeToDefinitelyUnenteredLocked(definitelyUnentered) ||
                evidence.postOutcome == null && evidence.recordPostOutcomeLocked(definitelyUnentered)
        }
    }

    private fun mutationRootPhysicalState(root: MutationRoot): AndroidPostPhysicalDisposition = when (root) {
        is MutationRoot.Resize -> root.ticket.physicalState
        is MutationRoot.Detach -> root.ticket.physicalState
        is MutationRoot.Attach -> root.ticket.physicalState
    }

    private fun ownsOperation(operation: OperationOccurrence<*>): Boolean {
        val root = when (val slot = mutationSlot.get()) {
            is MutationSlot.Owned -> slot.root
            is MutationSlot.CommitInFlight -> slot.root
            is MutationSlot.Submitting -> slot.root
            is MutationSlot.RetiredUnused -> slot.root
            is MutationSlot.TerminalOwned -> slot.root
            is MutationSlot.TerminalCommitInFlight -> slot.root
            is MutationSlot.TerminalSubmitting -> slot.root
            is MutationSlot.TerminalArbitrating -> slot.root
            is MutationSlot.TerminalAwaitingPhysicalProof -> slot.root
            is MutationSlot.TerminalTransferred -> slot.root
            is MutationSlot.TerminalSettled -> slot.root
            is MutationSlot.TerminalQuarantined -> slot.root
            is MutationSlot.TerminalRetiredUnused -> slot.root
            else -> return false
        }
        return when (root) {
            is MutationRoot.Resize -> root.operation === operation
            is MutationRoot.Detach -> root.operation === operation
            is MutationRoot.Attach -> root.operation === operation
        }
    }

    private fun matchesProducer(
        fact: TargetProducerApplicationFact,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: io.screenstream.engine.internal.target.TargetPorts.AndroidSurfacePort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation && fact.operationIdentity == operation.identity &&
            fact.operationKind == port.operationKind && fact.provenance === port.provenance

    private fun matchesDetach(
        fact: TargetProducerDetachReceipt,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: io.screenstream.engine.internal.target.TargetPorts.AndroidDetachPort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation && fact.operationIdentity == operation.identity &&
            fact.detachKind == port.detachKind && fact.provenance === port.provenance

    private fun <R : OperationEvidence> releaseWhen(
        operation: OperationOccurrence<R>,
        predicate: (OperationOccurrence<R>) -> Boolean,
    ): Boolean {
        val exactSlot = mutationSlot.get()
        val root = when (exactSlot) {
            is MutationSlot.Owned -> exactSlot.root
            is MutationSlot.TerminalSettled -> exactSlot.root
            else -> return false
        }
        val exact = when (root) {
            is MutationRoot.Resize -> root.operation === operation
            is MutationRoot.Detach -> root.operation === operation
            is MutationRoot.Attach -> root.operation === operation
        }
        if (!exact) return false
        val releasable = operation.settlementGate.withLock { predicate(operation) }
        val replacement = if (exactSlot is MutationSlot.TerminalSettled) {
            MutationSlot.TerminalEmpty
        } else {
            MutationSlot.OpenEmpty
        }
        return releasable && mutationSlot.compareAndSet(exactSlot, replacement)
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable operation cells remain authoritative.
        }
    }
}
