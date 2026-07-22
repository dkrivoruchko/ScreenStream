package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
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
    if (!owner.hasCompleteResources()) return null
    val occurrence = createFrameworkRecycleOccurrence(
        owner = owner,
        clockOwner = owner,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        origin = FrameworkBitmapRecycleOrigin.IncompatibleReplacement,
        identity = identity,
        operationIdentity = identity.operationIdentity,
    )
    if (!owner.reserveRecycle(occurrence)) return null
    val handedOff = owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.executorOperation)
    if (!handedOff && occurrence.operation.submissionDisposition == OperationSubmissionDisposition.None) {
        val unwound = occurrence.operation.settlementGate.withLock {
            if (occurrence.ownerBag.owner !== owner || !owner.ownsRecycle(occurrence)) return@withLock false
            occurrence.ownerBag.owner = null
            owner.clearRecycle(occurrence)
        }
        if (!unwound) throw FrameworkJpegOwner.BITMAP_RECYCLE_ADMISSION_UNWIND_FAILED
        return null
    }
    return occurrence
}

internal fun beginTerminalFrameworkRecycle(
    owner: FrameworkJpegOwner,
    desiredRevision: Long,
    geometryGeneration: Long,
    lifecycleEpoch: Long,
    operationIdentity: Long,
): FrameworkBitmapRecycleOccurrence? {
    if (!owner.hasCompleteResources()) return null
    val occurrence = createFrameworkRecycleOccurrence(
        owner = owner,
        clockOwner = owner,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        origin = FrameworkBitmapRecycleOrigin.TerminalRetirement,
        identity = null,
        operationIdentity = operationIdentity,
    )
    if (occurrence.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred) {
        return null
    }

    if (!owner.reserveRecycle(occurrence)) return null
    val handedOff = owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.executorOperation)
    if (!handedOff && occurrence.operation.submissionDisposition == OperationSubmissionDisposition.None) {
        return occurrence
    }
    return occurrence
}

internal fun settleFrameworkRecycle(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkBitmapRecycleOccurrence,
): FrameworkBitmapRecycleSettlement {
    if (!owner.jpegRuntimeOwner.releaseJpegIoOperation(occurrence)) {
        return FrameworkBitmapRecycleSettlement.NotSettled
    }
    val gate = occurrence.operation.settlementGate
    return gate.withLock {
        if (!owner.ownsRecycle(occurrence) || occurrence.ownerBag.owner !== owner) {
            return FrameworkBitmapRecycleSettlement.NotSettled
        }
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
                return@withLock FrameworkBitmapRecycleSettlement.UnsafeResidue
            }
            return@withLock FrameworkBitmapRecycleSettlement.NotSettled
        }

        val evidence = occurrence.operation.returnCell.evidence
        val timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely
        if (occurrence.operation.returnCell.disposition != OperationReturnDisposition.Normal || evidence.receipt !== evidence.normalReceipt) {
            return@withLock FrameworkBitmapRecycleSettlement.UnsafeResidue
        }
        if (!owner.clearRecycledReferences()) return@withLock FrameworkBitmapRecycleSettlement.UnsafeResidue
        occurrence.ownerBag.owner = null
        check(owner.clearRecycle(occurrence))
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
    val runtimeOwner = gate.withLock { occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner } ?: return false
    if (!runtimeOwner.releaseJpegIoOperation(occurrence)) return false
    return gate.withLock {
        val candidate = occurrence.ownerBag.candidateOwner ?: return@withLock false
        if (!candidate.ownsCreation(occurrence)) return@withLock false
        if (!candidate.hasNoBitmap() || occurrence.operation.returnCell.evidence.returnedOwner != null) {
            return@withLock false
        }
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
        if (!creationWithoutReturnedBitmapSettledLocked(occurrence)) return@withLock false

        if (!occurrence.clearSettledReferencesLocked(candidate)) return@withLock false
        check(candidate.clearCreation(occurrence))
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
    owner: FrameworkJpegOwner?,
    clockOwner: FrameworkJpegOwner,
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
    clock = clockOwner.clock,
    signal = clockOwner.jpegRuntimeOwner.jpegIoSettlementSignal,
    endpoint = clockOwner.jpegRuntimeOwner.jpegExecutorEndpoint,
    work = ::executeFrameworkRecycle,
)

private fun executeFrameworkRecycle(
    occurrence: FrameworkBitmapRecycleOccurrence,
) {
    val owner = occurrence.operation.settlementGate.withLock { occurrence.ownerBag.owner }
    if (owner == null) return

    val bitmap = owner.bitmapForRecycle()
    if (bitmap == null) {
        if (occurrence.operation.publishThrownReturn(FrameworkJpegOwner.BITMAP_RECYCLE_OWNER_MISSING)) {
            owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
        }
        return
    }

    val published = try {
        bitmap.recycle()
        occurrence.operation.settlementGate.withLock {
            occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
            occurrence.operation.publishNormalReturn()
        }
    } catch (failure: Exception) {
        occurrence.operation.publishThrownReturn(failure)
    } catch (fatal: Throwable) {
        publishRecycleFatalAndRethrow(occurrence, owner, fatal)
    }
    if (published) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
}

private fun publishRecycleFatalAndRethrow(
    occurrence: FrameworkBitmapRecycleOccurrence,
    owner: FrameworkJpegOwner,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) {
        owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    }
    throw fatal
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
    val runtimeOwner = occurrence.operation.settlementGate.withLock {
        occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner
    } ?: return null
    if (!runtimeOwner.releaseJpegIoOperation(occurrence)) return null
    val sourceGate = occurrence.operation.settlementGate
    val owner = sourceGate.withLock {
        val candidate = occurrence.ownerBag.candidateOwner ?: return null
        if (!candidate.ownsCreation(occurrence)) return null
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
        if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed ||
            occurrence.operation.returnCell.evidence.returnedOwner !== candidate || !candidate.ownsBitmapForCleanup()
        ) {
            return null
        }
        candidate
    }

    val recycle = createFrameworkRecycleOccurrence(
        owner = null,
        clockOwner = owner,
        desiredRevision = occurrence.desiredRevision,
        geometryGeneration = occurrence.geometryGeneration,
        lifecycleEpoch = occurrence.lifecycleEpoch,
        origin = FrameworkBitmapRecycleOrigin.ReturnedOwnerCleanup,
        identity = identity,
        operationIdentity = operationIdentity,
    )
    if (identity == null &&
        recycle.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred
    ) {
        return null
    }
    if (!owner.reserveCreationRecycle(occurrence, recycle)) return null

    val transferred = sourceGate.withLock {
        if (occurrence.ownerBag.candidateOwner !== owner ||
            occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed ||
            occurrence.operation.returnCell.evidence.returnedOwner !== owner || !owner.ownsBitmapForCleanup() ||
            recycle.ownerBag.owner != null
        ) {
            return@withLock false
        }
        recycle.ownerBag.owner = owner
        if (!occurrence.clearSettledReferencesLocked(owner)) {
            recycle.ownerBag.owner = null
            return@withLock false
        }
        check(owner.clearCreation(occurrence))
        true
    }
    if (!transferred) {
        check(owner.clearRecycle(recycle))
        return null
    }

    val handedOff = owner.jpegRuntimeOwner.submitJpegIoOperation(recycle.executorOperation)
    if (!handedOff && recycle.operation.submissionDisposition == OperationSubmissionDisposition.None) {
        return recycle
    }
    return recycle
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
