package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import kotlin.concurrent.withLock

internal fun beginIncompatibleFrameworkRecycle(
    owner: FrameworkJpegOwner,
    desiredRevision: Long,
    geometryGeneration: Long,
    lifecycleEpoch: Long,
    identity: JpegFiniteOperationIdentity,
): FrameworkBitmapRecycleOccurrence? {
    if (!owner.bitmapOwner.closeAdmissionAndCheckDrained()) return null

    val occurrence = createFrameworkRecycleOccurrence(
        owner = owner,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        origin = FrameworkBitmapRecycleOrigin.IncompatibleReplacement,
        identity = identity,
        operationIdentity = identity.operationIdentity,
    )
    if (!owner.bitmapOwner.admitRecycle(occurrence, installedOwner = true)) return null

    owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
    return occurrence
}

internal fun beginTerminalFrameworkRecycle(
    owner: FrameworkJpegOwner,
    desiredRevision: Long,
    geometryGeneration: Long,
    lifecycleEpoch: Long,
    operationIdentity: Long,
): FrameworkBitmapRecycleOccurrence? {
    if (!owner.bitmapOwner.closeAdmissionAndCheckDrained()) return null

    val occurrence = createFrameworkRecycleOccurrence(
        owner = owner,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        origin = FrameworkBitmapRecycleOrigin.TerminalRetirement,
        identity = null,
        operationIdentity = operationIdentity,
    )
    if (occurrence.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred ||
        !owner.bitmapOwner.admitRecycle(occurrence, installedOwner = true)
    ) {
        return null
    }

    owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
    return occurrence
}

internal fun settleFrameworkRecycle(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkBitmapRecycleOccurrence,
): FrameworkBitmapRecycleSettlement {
    val gate = occurrence.operation.settlementGate
    var timely = false
    var normal = false
    var exactReceipt = false
    var exactOwner: FrameworkJpegOwner? = null
    gate.withLock {
        if (occurrence.ownerBag.owner !== owner) return FrameworkBitmapRecycleSettlement.NotSettled
        val arbitration = if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
            occurrence.operation.arbitrate()
        } else {
            OperationArbitration.None
        }
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
            if (arbitration == OperationArbitration.SchedulerRejected ||
                arbitration == OperationArbitration.DeadlineGuardFailed ||
                arbitration == OperationArbitration.ExpiredEmpty ||
                unenteredFrameworkRecycleFailureLocked(occurrence)
            ) {
                return FrameworkBitmapRecycleSettlement.UnsafeResidue
            }
            return FrameworkBitmapRecycleSettlement.NotSettled
        }

        val evidence = occurrence.operation.returnCell.evidence
        timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely
        normal = occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal
        exactReceipt = evidence.receipt === evidence.normalReceipt
        exactOwner = occurrence.ownerBag.owner
    }

    if (!normal || !exactReceipt || exactOwner !== owner || !owner.bitmapOwner.completeRecycle(occurrence)) {
        return FrameworkBitmapRecycleSettlement.UnsafeResidue
    }

    return gate.withLock {
        if (occurrence.ownerBag.owner !== owner) return@withLock FrameworkBitmapRecycleSettlement.UnsafeResidue
        occurrence.ownerBag.owner = null
        if (timely && occurrence.origin == FrameworkBitmapRecycleOrigin.IncompatibleReplacement) {
            FrameworkBitmapRecycleSettlement.ReplacementAuthorized
        } else {
            FrameworkBitmapRecycleSettlement.CleanupCompleted
        }
    }
}

internal fun settleFrameworkResourceCreationWithoutReturnedBitmap(
    occurrence: FrameworkResourceCreationOccurrence,
): Boolean {
    val gate = occurrence.operation.settlementGate
    return gate.withLock {
        val candidate = occurrence.ownerBag.candidateOwner ?: return@withLock false
        if (!candidate.bitmapOwner.hasNoBitmap() || occurrence.operation.returnCell.evidence.returnedOwner != null) {
            return@withLock false
        }
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
        if (!creationWithoutReturnedBitmapSettledLocked(occurrence)) return@withLock false

        occurrence.ownerBag.candidateOwner = null
        true
    }
}

internal fun beginReturnedOwnerFrameworkRecycle(
    occurrence: FrameworkResourceCreationOccurrence,
    identity: JpegFiniteOperationIdentity,
): FrameworkBitmapRecycleOccurrence? = beginReturnedOwnerFrameworkRecycleCommon(
    occurrence = occurrence,
    identity = identity,
    operationIdentity = identity.operationIdentity,
)

internal fun beginTerminalReturnedOwnerFrameworkRecycle(
    occurrence: FrameworkResourceCreationOccurrence,
    operationIdentity: Long,
): FrameworkBitmapRecycleOccurrence? = beginReturnedOwnerFrameworkRecycleCommon(
    occurrence = occurrence,
    identity = null,
    operationIdentity = operationIdentity,
)

private fun createFrameworkRecycleOccurrence(
    owner: FrameworkJpegOwner,
    desiredRevision: Long,
    geometryGeneration: Long,
    lifecycleEpoch: Long,
    origin: FrameworkBitmapRecycleOrigin,
    identity: JpegFiniteOperationIdentity?,
    operationIdentity: Long,
): FrameworkBitmapRecycleOccurrence = FrameworkBitmapRecycleOccurrence.create(
    desiredRevision = desiredRevision,
    geometryGeneration = geometryGeneration,
    lifecycleEpoch = lifecycleEpoch,
    origin = origin,
    owner = owner,
    identity = identity,
    operationIdentity = operationIdentity,
    clock = owner.clock,
    signal = owner.jpegRuntimeOwner.jpegIoSettlementSignal,
    work = { occurrence -> executeFrameworkRecycle(owner, occurrence) },
)

private fun executeFrameworkRecycle(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkBitmapRecycleOccurrence,
) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
        return
    }
    owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()

    val bitmap = owner.bitmapOwner.bitmapForRecycle(occurrence)
    if (bitmap == null) {
        if (occurrence.operation.publishThrownReturn(FrameworkJpegOwner.BITMAP_RECYCLE_OWNER_MISSING)) {
            owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
        }
        return
    }

    val published = try {
        bitmap.recycle()
        occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
        occurrence.operation.publishNormalReturn()
    } catch (failure: Throwable) {
        occurrence.operation.publishThrownReturn(failure)
    }
    if (published) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
}

private fun unenteredFrameworkRecycleFailureLocked(occurrence: FrameworkBitmapRecycleOccurrence): Boolean {
    val operation = occurrence.operation
    check(operation.settlementGate.isHeldByCurrentThread)
    val submissionRejected = operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
            (operation.disposition == OperationDisposition.SchedulerRejected ||
                    operation.domain == OperationDomain.Cleanup)
    return operation.entryDisposition == OperationEntryDisposition.Unentered &&
            (submissionRejected || operation.disposition == OperationDisposition.DeadlineGuardFailed) &&
            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
            operation.returnCell.use == OperationReturnUse.Unclaimed
}

private fun beginReturnedOwnerFrameworkRecycleCommon(
    occurrence: FrameworkResourceCreationOccurrence,
    identity: JpegFiniteOperationIdentity?,
    operationIdentity: Long,
): FrameworkBitmapRecycleOccurrence? {
    val sourceGate = occurrence.operation.settlementGate
    var owner: FrameworkJpegOwner? = null
    var recycle: FrameworkBitmapRecycleOccurrence? = null
    sourceGate.withLock {
        val candidate = occurrence.ownerBag.candidateOwner ?: return null
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
        if (
            occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed ||
            occurrence.operation.returnCell.evidence.returnedOwner !== candidate.bitmapOwner || !candidate.bitmapOwner.ownsBitmapForCleanup()
        ) {
            return null
        }

        val candidateRecycle = createFrameworkRecycleOccurrence(
            owner = candidate,
            desiredRevision = occurrence.desiredRevision,
            geometryGeneration = occurrence.geometryGeneration,
            lifecycleEpoch = occurrence.lifecycleEpoch,
            origin = FrameworkBitmapRecycleOrigin.ReturnedOwnerCleanup,
            identity = identity,
            operationIdentity = operationIdentity,
        )
        if (
            identity == null &&
            candidateRecycle.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred
        ) {
            return null
        }
        if (!candidate.bitmapOwner.admitRecycle(candidateRecycle, installedOwner = false)) return null

        owner = candidate
        recycle = candidateRecycle
        occurrence.ownerBag.candidateOwner = null
    }

    val exactOwner = checkNotNull(owner)
    val exactRecycle = checkNotNull(recycle)
    exactOwner.jpegRuntimeOwner.submitJpegIoOperation(exactRecycle.ioOperation)
    return exactRecycle
}

private fun creationWithoutReturnedBitmapSettledLocked(occurrence: FrameworkResourceCreationOccurrence): Boolean {
    val operation = occurrence.operation
    check(operation.settlementGate.isHeldByCurrentThread)
    if (operation.returnCell.disposition == OperationReturnDisposition.Thrown && operation.returnCell.use != OperationReturnUse.Unclaimed) {
        return true
    }
    if (operation.returnCell.disposition == OperationReturnDisposition.Normal) return false

    val submissionResolved = operation.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
            operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
            operation.submissionDisposition == OperationSubmissionDisposition.Rejected
    val terminalizedWithoutEntry = operation.domain == OperationDomain.Cleanup &&
            operation.entryDisposition == OperationEntryDisposition.Cancelled && submissionResolved &&
            (operation.disposition == OperationDisposition.Cancelled ||
                    operation.disposition == OperationDisposition.SchedulerRejected ||
                    operation.disposition == OperationDisposition.DeadlineGuardFailed)
    val schedulerRejectedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
            operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
            operation.disposition == OperationDisposition.SchedulerRejected
    val deadlineGuardFailedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
            operation.disposition == OperationDisposition.DeadlineGuardFailed
    return operation.returnCell.use == OperationReturnUse.Unclaimed &&
            (terminalizedWithoutEntry || schedulerRejectedWithoutEntry || deadlineGuardFailedWithoutEntry)
}
