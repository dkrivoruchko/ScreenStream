package io.screenstream.engine.internal.android

import android.os.Handler
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
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
    private object PreparationReservation

    private val activeOperation = AtomicReference<Any?>(null)
    private val terminalTransfer = AtomicReference<AndroidVirtualDisplayMutationTerminalTransfer?>(null)

    internal val hasUnsettledOperation: Boolean
        get() = activeOperation.get() != null || terminalTransfer.get() != null

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
        val ownerBag = AndroidVirtualDisplayResizeOwnerBag(exactOwnership, command)
        val operation = finiteOccurrence(
            identity,
            AndroidVirtualDisplayResizeEvidence(),
            ownerBag,
        )
        check(ownerBag.bindOperation(operation))
        return operation.takeIf { activeOperation.compareAndSet(null, it) }
    }

    internal fun submitResize(operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>): Boolean {
        val bag = operation.ownerBag as? AndroidVirtualDisplayResizeOwnerBag ?: return false
        return submit(operation, "Android VirtualDisplay resize rejected") {
            check(ownership.get() === bag.ownership)
            val previous = bag.ownership.actualLogicalTuple
            val requested = bag.command.requestedTuple
            bag.ownership.virtualDisplay.resize(requested.widthPx, requested.heightPx, requested.densityDpi)
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordAppliedTupleLocked(requested) &&
                        operation.returnCell.evidence.recordSelectedResultLocked(bag.appliedResult) &&
                        bag.ownership.mechanicalState.recordResizeReturnedLocked(previous, requested)
            })
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
        releaseWhen(operation) { it.returnCell.evidence.consumedResult != null }

    internal fun prepareDetach(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
        reconfigurationIdentity: Long,
    ): AndroidVirtualDisplayDetachPreparationResult? {
        require(reconfigurationIdentity > 0L)
        val exactOwnership = ownership.get() as? AndroidAttachedVirtualDisplay ?: return null
        if (exactOwnership.target !== target) return null
        if (!activeOperation.compareAndSet(null, PreparationReservation)) return null
        try {
            val stagedPort = target.prepareStagedDetachPort(identity.operationIdentity, reconfigurationIdentity)
            val evidence = AndroidVirtualDisplayDetachEvidence()
            val bag = AndroidVirtualDisplayDetachOwnerBag(exactOwnership, target, stagedPort, evidence)
            val operation = finiteOccurrence(identity, evidence, bag)
            val binding = AndroidTargetOperationBinding.create(stagedPort.commitCorrelation, operation)
            check(bag.bindOperation(binding, operation))
            if (!target.bindAndroidTargetOperation(stagedPort, binding)) {
                activeOperation.compareAndSet(PreparationReservation, null)
                return null
            }
            check(activeOperation.compareAndSet(PreparationReservation, operation))
            return when (val commit = target.commitStagedDetachPort(stagedPort)) {
                is TargetStagedDetachPortCommittedFact -> {
                    check(commit === bag.committedFact)
                    AndroidVirtualDisplayDetachPreparationResult.Ready(operation)
                }

                is TargetStagedDetachPortUnusedFact -> {
                    check(operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordPostOutcomeLocked(bag.retiredUnusedOutcome)
                    })
                    val transfer = checkNotNull(transferDetachToTerminal(operation))
                    AndroidVirtualDisplayDetachPreparationResult.RetiredUnused(
                        operation,
                        bag.retiredUnusedOutcome.also { check(it.targetFact === commit) },
                        transfer,
                    )
                }

                else -> {
                    activeOperation.compareAndSet(operation, null)
                    null
                }
            }
        } catch (failure: Throwable) {
            activeOperation.compareAndSet(PreparationReservation, null)
            throw failure
        }
    }

    internal fun submitDetach(operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>): Boolean {
        val bag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return false
        val accepted = try {
            submit(
                operation,
                "Android VirtualDisplay detach rejected",
                onTicketCreated = { check(bag.bindPostTicket(it)) },
            ) {
                check(ownership.get() === bag.ownership)
                bag.ownership.virtualDisplay.setSurface(null)
            }
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
        return accepted
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
        true
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
        if (!activeOperation.compareAndSet(null, PreparationReservation)) return null
        try {
            val stagedPort = target.prepareStagedProducerPort(identity.operationIdentity, reconfigurationIdentity)
            val evidence = AndroidVirtualDisplayAttachEvidence()
            val candidate = AndroidAttachedVirtualDisplay(prior, target, stagedPort.port, evidence)
            val bag = AndroidVirtualDisplayAttachOwnerBag(prior, target, stagedPort, candidate, evidence)
            val operation = finiteOccurrence(identity, evidence, bag)
            check(candidate.bindProducerOperation(operation))
            val binding = AndroidTargetOperationBinding.create(stagedPort.commitCorrelation, operation)
            check(bag.bindOperation(binding, operation))
            if (!target.bindAndroidTargetOperation(stagedPort, binding)) {
                activeOperation.compareAndSet(PreparationReservation, null)
                return null
            }
            check(activeOperation.compareAndSet(PreparationReservation, operation))
            return when (val commit = target.commitStagedProducerPort(stagedPort)) {
                is TargetStagedProducerPortCommittedFact -> {
                    check(commit === bag.committedFact)
                    AndroidVirtualDisplayAttachPreparationResult.Ready(operation)
                }

                is TargetStagedProducerPortUnusedFact -> {
                    check(operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordPostOutcomeLocked(bag.retiredUnusedOutcome)
                    })
                    val transfer = checkNotNull(transferAttachToTerminal(operation))
                    AndroidVirtualDisplayAttachPreparationResult.RetiredUnused(
                        operation,
                        bag.retiredUnusedOutcome.also { check(it.targetFact === commit) },
                        transfer,
                    )
                }

                else -> {
                    activeOperation.compareAndSet(operation, null)
                    null
                }
            }
        } catch (failure: Throwable) {
            activeOperation.compareAndSet(PreparationReservation, null)
            throw failure
        }
    }

    internal fun submitAttach(operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>): Boolean {
        val bag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return false
        val accepted = try {
            submit(
                operation,
                "Android VirtualDisplay attachment rejected",
                onTicketCreated = { check(bag.bindPostTicket(it)) },
            ) {
                check(ownership.get() === bag.priorOwnership)
                check(
                    bag.stagedPort.port.withSurface { surface ->
                        bag.priorOwnership.virtualDisplay.setSurface(surface)
                    } == TargetPortUseOutcome.BodyReturned,
                )
            }
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
        return accepted
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
        true
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

    private fun <R : OperationEvidence> submit(
        operation: OperationOccurrence<R>,
        rejectionMessage: String,
        onTicketCreated: (AndroidPostTicket<R>) -> Unit = {},
        call: (Handler) -> Unit,
    ): Boolean {
        val ticket = lane.ticket(
            operation,
            rejectionMessage,
            AndroidEnteredWork { handler ->
                try {
                    call(handler)
                    operation.publishNormalReturn()
                } catch (failure: Exception) {
                    operation.publishThrownReturn(failure)
                }
                signalBestEffort()
            },
        )
        onTicketCreated(ticket)
        return lane.post(ticket) == AndroidPostResult.Accepted
    }

    private fun <R : OperationEvidence> recordInitialPostOutcome(
        operation: OperationOccurrence<R>,
        postExposed: AndroidTargetPostOutcome.PostExposed,
        definitelyUnentered: AndroidTargetPostOutcome.DefinitelyUnentered,
        accepted: Boolean,
    ) {
        operation.settlementGate.withLock {
            val outcome = if (accepted) {
                postExposed
            } else if (operation.entryDisposition == OperationEntryDisposition.Entered ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionAmbiguousFatal != null
            ) {
                postExposed
            } else if (operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                operation.entryDisposition == OperationEntryDisposition.Unentered
            ) {
                definitelyUnentered
            } else if (operation.submissionDisposition == OperationSubmissionDisposition.None &&
                operation.entryDisposition == OperationEntryDisposition.Unentered
            ) {
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
        ticket: AndroidPostTicket<*>?,
        outcome: AndroidTargetPostOutcome.DefinitelyUnentered,
    ) {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (lane.hasThreadReturned &&
            ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
            operation.entryDisposition == OperationEntryDisposition.Unentered &&
            operation.returnCell.disposition == OperationReturnDisposition.Empty
        ) {
            operation.settleInertBeforeEntryLocked()
        }
        if (operation.entryDisposition != OperationEntryDisposition.Cancelled ||
            ticket?.physicalState != AndroidPostPhysicalDisposition.Returned &&
            !(lane.hasThreadReturned && ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack)
        ) {
            return
        }
        when (val evidence = operation.returnCell.evidence) {
            is AndroidVirtualDisplayAttachEvidence -> evidence.refinePostOutcomeToDefinitelyUnenteredLocked(outcome)
            is AndroidVirtualDisplayDetachEvidence -> evidence.refinePostOutcomeToDefinitelyUnenteredLocked(outcome)
        }
    }

    internal fun transferResizeToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Resize? =
        transferToTerminal(operation) { (operation.ownerBag as AndroidVirtualDisplayResizeOwnerBag).terminalTransfer }

    internal fun transferDetachToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Detach? =
        transferToTerminal(operation) { (operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag).terminalTransfer }

    internal fun transferAttachToTerminal(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): AndroidVirtualDisplayMutationTerminalTransfer.Attach? =
        transferToTerminal(operation) { (operation.ownerBag as AndroidVirtualDisplayAttachOwnerBag).terminalTransfer }

    internal fun releaseTerminalTransfer(transfer: AndroidVirtualDisplayMutationTerminalTransfer): Boolean {
        if (terminalTransfer.get() !== transfer) return false
        val settled = when (transfer) {
            is AndroidVirtualDisplayMutationTerminalTransfer.Resize -> {
                val operation = transfer.operation as OperationOccurrence<AndroidVirtualDisplayResizeEvidence>
                operation.settlementGate.withLock {
                    val evidence = operation.returnCell.evidence
                    evidence.consumedResult != null || evidence.selectedResult is AndroidVirtualDisplayResizeResult.Failed
                }
            }

            is AndroidVirtualDisplayMutationTerminalTransfer.Detach -> {
                val operation = transfer.operation as OperationOccurrence<AndroidVirtualDisplayDetachEvidence>
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
                val operation = transfer.operation as OperationOccurrence<AndroidVirtualDisplayAttachEvidence>
                operation.settlementGate.withLock {
                    val evidence = operation.returnCell.evidence
                    evidence.appliedTargetFact != null || evidence.settledTargetFact != null ||
                            evidence.postOutcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                            evidence.consumedPostFact is TargetStagedProducerPortRetiredFact ||
                            evidence.postOutcome is AndroidTargetPostOutcome.RetiredUnused &&
                            evidence.consumedPostFact is TargetStagedProducerPortRetiredFact
                }
            }
        }
        return settled && terminalTransfer.compareAndSet(transfer, null)
    }

    private fun <R : OperationEvidence, T : AndroidVirtualDisplayMutationTerminalTransfer> transferToTerminal(
        operation: OperationOccurrence<R>,
        transfer: () -> T,
    ): T? {
        if (activeOperation.get() !== operation) return null
        val exactTransfer = transfer()
        if (!terminalTransfer.compareAndSet(null, exactTransfer)) return null
        if (activeOperation.compareAndSet(operation, null)) return exactTransfer
        terminalTransfer.compareAndSet(exactTransfer, null)
        return null
    }

    private fun ownsOperation(operation: OperationOccurrence<*>): Boolean =
        activeOperation.get() === operation || terminalTransfer.get()?.operation === operation

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
        val releasable = operation.settlementGate.withLock { predicate(operation) }
        return releasable && activeOperation.compareAndSet(operation, null)
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable operation cells remain authoritative.
        }
    }
}
