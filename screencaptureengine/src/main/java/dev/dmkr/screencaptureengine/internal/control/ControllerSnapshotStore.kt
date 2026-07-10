@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.internal.encoding.LiveProviderDescriptorLedger
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorForkResult
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRecordResult
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorReserveResult
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRetentionRole
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorRetentionToken
import dev.dmkr.screencaptureengine.internal.encoding.ProviderDescriptorSnapshot

/** Opaque controller-local authority for the single prepared candidate. */
internal sealed interface ControllerCandidateOwnership

/** Store-minted candidate authority. Callers cannot pair a raw capability with another candidate. */
internal sealed interface ControllerCandidatePrevalidation {
    val session: SessionIdentity
    val candidate: ControllerCandidateSnapshot
}

/** Store-minted proof for the one current candidate and its exact logical work origin. */
internal sealed interface ControllerCurrentCandidatePrevalidation : ControllerCandidatePrevalidation {
    val origin: ControllerFactOrigin
}

internal enum class CandidateDispositionTrigger {
    Cancellation,
    Supersession,
    GeometryPreemption,
    PausePreemption,
    ReadyPermitRejected,
    RetargetStartTimedOut,
    LateAcknowledgedSupersession,
    PreparationFailure,
    PreparationStartedTimeout,
    PrePublicRetirement,
}

/** Mechanical ownership action selected by control policy before entering the store. */
internal sealed interface CandidateDispositionAction {
    data object ReleaseUnstarted : CandidateDispositionAction

    class RetireReturned(
        val returnedEncoderNeedsCleanup: Boolean,
    ) : CandidateDispositionAction

    data object RetainAwaitingLateReturn : CandidateDispositionAction
    data object QuarantineStartedTimeout : CandidateDispositionAction
}

/** Replay-safe authority for one exact losing-candidate ownership action. */
internal sealed interface ControllerCandidateDispositionPrevalidation : ControllerCurrentCandidatePrevalidation {
    val triggerSequence: IngressSequence?
    val trigger: CandidateDispositionTrigger
    val action: CandidateDispositionAction
}

/** Store-minted candidate disposition bound to one exact accepted terminal record. */
internal sealed interface ControllerTerminalCandidateDispositionPrevalidation : ControllerCandidatePrevalidation {
    val terminal: ControllerIngress.Terminal
    val action: CandidateDispositionAction
}

/** Store-minted proof that the complete candidate can commit against the exact current target. */
internal sealed interface ControllerCandidateCommitPrevalidation : ControllerCandidatePrevalidation {
    val target: ControllerTargetSnapshot
    val owner: CompleteOwnerIdentity
    val effective: ControllerEffectiveSnapshot
}

/** Opaque controller-local authority for one detached cleanup record. */
internal sealed interface ControllerCleanupOwnership

/** Opaque controller-local identity of one unresolved preparation quarantine. */
internal sealed interface ControllerPreparationQuarantineOwnership

internal sealed interface CandidateOwnershipAdmission {
    data class Admitted(val ownership: ControllerCandidateOwnership) : CandidateOwnershipAdmission
    data object CandidateOccupied : CandidateOwnershipAdmission
    data object CleanupCapacityExceeded : CandidateOwnershipAdmission
    data object DescriptorCapacityExceeded : CandidateOwnershipAdmission
    data object EnginePoisoned : CandidateOwnershipAdmission
    data object Terminal : CandidateOwnershipAdmission
}

internal sealed interface CandidateCommitDisposition {
    data class Committed(
        val retiredOwner: ControllerCleanupOwnership?,
    ) : CandidateCommitDisposition

    data object InvalidOwnership : CandidateCommitDisposition
    data object DescriptorNotAccepted : CandidateCommitDisposition
    data object SnapshotMismatch : CandidateCommitDisposition
}

internal sealed interface QuarantineReturnDisposition {
    data object ReturnedWithoutEncoder : QuarantineReturnDisposition
    data class CleanupRequired(val cleanup: ControllerCleanupOwnership) : QuarantineReturnDisposition
    data object InvalidOwnership : QuarantineReturnDisposition
}

internal sealed interface CleanupTransitionDisposition {
    data object Advanced : CleanupTransitionDisposition
    data object Released : CleanupTransitionDisposition
    data object InvalidOwnership : CleanupTransitionDisposition
}

internal sealed interface ActiveOwnerReturnDisposition {
    data class CleanupRequired(val cleanup: ControllerCleanupOwnership) : ActiveOwnerReturnDisposition
    data object InvalidOwnership : ActiveOwnerReturnDisposition
}

internal sealed interface TerminalOwnershipDisposition {
    data class Retired(val currentOwnerCleanup: ControllerCleanupOwnership?) : TerminalOwnershipDisposition
    data object CandidateStillOwned : TerminalOwnershipDisposition
    data object AlreadyTerminal : TerminalOwnershipDisposition
}

/** Capabilities are created by the mutation and become visible only in its typed result. */
internal sealed interface CandidateDispositionOutcome {
    data object Released : CandidateDispositionOutcome
    class CleanupRequired(val cleanup: ControllerCleanupOwnership) : CandidateDispositionOutcome
    class LateReturnRetained(val cleanup: ControllerCleanupOwnership) : CandidateDispositionOutcome
    class Quarantined(val quarantine: ControllerPreparationQuarantineOwnership) : CandidateDispositionOutcome
    data object InvalidAuthority : CandidateDispositionOutcome
}

/** Full immutable proof for one startup/recovery physical-target acknowledgement. */
internal sealed interface ControllerTargetAcknowledgementPrevalidation {
    val session: SessionIdentity
    val origin: ControllerFactOrigin
    val candidate: ControllerCandidateSnapshot
    val previousTarget: ControllerTargetSnapshot?
    val target: ControllerTargetSnapshot
    val geometry: GeometrySnapshot
}

internal sealed interface TargetAcknowledgementDisposition {
    class Acknowledged(val previousTarget: ControllerTargetSnapshot?) : TargetAcknowledgementDisposition
    data object InvalidAuthority : TargetAcknowledgementDisposition
    data object SnapshotMismatch : TargetAcknowledgementDisposition
    data object Terminal : TargetAcknowledgementDisposition
}

/** Immutable diagnostic projection; move-only ledger and store capabilities are deliberately absent. */
internal data class ControllerSnapshotOwnershipView(
    val provisionalDesiredIdentity: DesiredSnapshotIdentity?,
    val currentDesiredIdentity: DesiredSnapshotIdentity?,
    val lastEffective: ControllerEffectiveSnapshot?,
    val currentCompleteOwner: CompleteOwnerIdentity?,
    val candidateIdentity: CandidateIdentity?,
    val physicalCurrentTarget: ControllerTargetSnapshot?,
    val cleanupRecordCount: Int,
    val preparationQuarantined: Boolean,
    val activeOwnerQuarantined: Boolean,
    val providerPoisoned: Boolean,
    val terminal: Boolean,
)

/**
 * Fixed-slot ownership boundary confined to the future controller gate. It has no lock of its own:
 * callers serialize every transition, while the ledger remains the sole retention authority.
 */
internal class ControllerSnapshotStore(
    private val ledger: LiveProviderDescriptorLedger,
) {
    private val identity = Any()
    private var provisionalDesired: DesiredRecord? = null
    private var currentDesired: DesiredRecord? = null
    private var lastEffective: ControllerEffectiveSnapshot? = null
    private var currentOwner: OwnerRecord? = null
    private var candidate: CandidateRecord? = null
    private var physicalCurrentTarget: ControllerTargetSnapshot? = null
    private var firstCleanup: CleanupSlot? = null
    private var secondCleanup: CleanupSlot? = null
    private var terminalCleanup: CleanupSlot? = null
    private var preparationQuarantine: QuarantineRecord? = null
    private var providerPoisoned = false
    private var terminal = false

    internal fun view(): ControllerSnapshotOwnershipView = ControllerSnapshotOwnershipView(
        provisionalDesiredIdentity = provisionalDesired?.snapshot?.identity,
        currentDesiredIdentity = currentDesired?.snapshot?.identity,
        lastEffective = lastEffective,
        currentCompleteOwner = currentOwner?.takeIf { !it.quarantined }?.identity,
        candidateIdentity = candidate?.snapshot?.identity,
        physicalCurrentTarget = physicalCurrentTarget,
        cleanupRecordCount = cleanupRecordCount(),
        preparationQuarantined = preparationQuarantine != null,
        activeOwnerQuarantined = currentOwner?.quarantined == true,
        providerPoisoned = providerPoisoned,
        terminal = terminal,
    )

    internal fun beginCandidate(snapshot: ControllerCandidateSnapshot): CandidateOwnershipAdmission {
        if (terminal) return CandidateOwnershipAdmission.Terminal
        if (providerPoisoned) {
            return CandidateOwnershipAdmission.EnginePoisoned
        }
        if (candidate != null || provisionalDesired != null) return CandidateOwnershipAdmission.CandidateOccupied
        val cleanupIndex = reserveOrdinaryCleanupSlot() ?: return CandidateOwnershipAdmission.CleanupCapacityExceeded
        val desiredReservation = ledger.reserve(
            providerIdentity = snapshot.desired.providerReference.provider,
            role = ProviderDescriptorRetentionRole.Desired,
        )
        if (desiredReservation !is ProviderDescriptorReserveResult.Reserved) {
            clearReservedCleanup(cleanupIndex)
            return CandidateOwnershipAdmission.DescriptorCapacityExceeded
        }
        val candidateReservation = ledger.fork(
            source = desiredReservation.token,
            role = ProviderDescriptorRetentionRole.Candidate,
        )
        check(candidateReservation is ProviderDescriptorForkResult.Forked)

        val ownership = CandidateCapability()
        provisionalDesired = DesiredRecord(snapshot.desired, desiredReservation.token)
        candidate = CandidateRecord(
            ownership = ownership,
            snapshot = snapshot,
            token = candidateReservation.token,
            cleanupIndex = cleanupIndex,
        )
        return CandidateOwnershipAdmission.Admitted(ownership)
    }

    internal fun recordCandidateDescriptor(
        ownership: ControllerCandidateOwnership,
        descriptor: ProviderDescriptorSnapshot,
    ): ProviderDescriptorRecordResult {
        val record = ownedCandidate(ownership) ?: return ProviderDescriptorRecordResult.InvalidToken
        return ledger.recordDescriptor(record.token, descriptor).also { result ->
            if (result is ProviderDescriptorRecordResult.Recorded) record.descriptorAccepted = true
        }
    }

    internal fun prevalidateCandidate(
        ownership: ControllerCandidateOwnership,
        session: SessionIdentity,
    ): ControllerCandidatePrevalidation? = ownedCandidate(ownership)?.let { record ->
        CandidatePrevalidationTicket(identity, record.ownership, session, record.snapshot)
    }

    /** Remints narrow authority without exposing the store's capability or accepting a foreign candidate. */
    internal fun prevalidateCurrentCandidate(
        session: SessionIdentity,
        origin: ControllerFactOrigin,
    ): ControllerCurrentCandidatePrevalidation? {
        val record = candidate?.takeIf { it.snapshot.identity == origin.candidate } ?: return null
        return CurrentCandidatePrevalidationTicket(identity, record.ownership, session, record.snapshot, origin)
    }

    internal fun prevalidateCandidateDisposition(
        session: SessionIdentity,
        origin: ControllerFactOrigin,
        triggerSequence: IngressSequence?,
        trigger: CandidateDispositionTrigger,
        action: CandidateDispositionAction,
    ): ControllerCandidateDispositionPrevalidation? {
        if ((trigger == CandidateDispositionTrigger.PrePublicRetirement) != (triggerSequence == null)) return null
        if (!trigger.accepts(action)) return null
        val record = candidate?.takeIf { it.snapshot.identity == origin.candidate } ?: return null
        return CandidateDispositionPrevalidationTicket(
            identity,
            record.ownership,
            session,
            record.snapshot,
            origin,
            triggerSequence,
            trigger,
            action,
        )
    }

    internal fun dispositionCandidate(
        prevalidation: ControllerCandidateDispositionPrevalidation,
    ): CandidateDispositionOutcome {
        val ticket = prevalidation as? CandidateDispositionPrevalidationTicket
            ?: return CandidateDispositionOutcome.InvalidAuthority
        if (ticket.storeIdentity !== identity || !ticket.trigger.accepts(ticket.action)) {
            return CandidateDispositionOutcome.InvalidAuthority
        }
        val record = ownedCandidate(ticket.ownership)
            ?.takeIf { it.snapshot === ticket.candidate }
            ?: return CandidateDispositionOutcome.InvalidAuthority
        return dispositionCandidate(record, ticket.action)
    }

    internal fun prevalidateTerminalCandidateDisposition(
        session: SessionIdentity,
        candidate: CandidateIdentity,
        terminal: ControllerIngress.Terminal,
        action: CandidateDispositionAction,
    ): ControllerTerminalCandidateDispositionPrevalidation? {
        val record = this.candidate?.takeIf { it.snapshot.identity == candidate } ?: return null
        return TerminalCandidateDispositionPrevalidationTicket(
            storeIdentity = identity,
            ownership = record.ownership,
            session = session,
            candidate = record.snapshot,
            terminal = terminal,
            action = action,
        )
    }

    internal fun dispositionCandidate(
        prevalidation: ControllerTerminalCandidateDispositionPrevalidation,
    ): CandidateDispositionOutcome {
        val ticket = prevalidation as? TerminalCandidateDispositionPrevalidationTicket
            ?: return CandidateDispositionOutcome.InvalidAuthority
        if (ticket.storeIdentity !== identity) return CandidateDispositionOutcome.InvalidAuthority
        val record = ownedCandidate(ticket.ownership)
            ?.takeIf { it.snapshot === ticket.candidate }
            ?: return CandidateDispositionOutcome.InvalidAuthority
        return dispositionCandidate(record, ticket.action)
    }

    private fun dispositionCandidate(
        record: CandidateRecord,
        action: CandidateDispositionAction,
    ): CandidateDispositionOutcome = when (action) {
        CandidateDispositionAction.ReleaseUnstarted -> {
            releaseCandidate(record)
            CandidateDispositionOutcome.Released
        }

        is CandidateDispositionAction.RetireReturned -> {
            if (!action.returnedEncoderNeedsCleanup) {
                releaseCandidate(record)
                CandidateDispositionOutcome.Released
            } else {
                releaseProvisionalDesired()
                check(ledger.rekey(record.token, ProviderDescriptorRetentionRole.Cleanup))
                val cleanup = occupyReservedCleanup(
                    record.cleanupIndex,
                    record.token,
                    CleanupPhase.ExclusiveCleanup,
                )
                candidate = null
                CandidateDispositionOutcome.CleanupRequired(cleanup)
            }
        }

        CandidateDispositionAction.RetainAwaitingLateReturn -> {
            releaseProvisionalDesired()
            check(ledger.rekey(record.token, ProviderDescriptorRetentionRole.Late))
            val cleanup = occupyReservedCleanup(
                record.cleanupIndex,
                record.token,
                CleanupPhase.AwaitingLateReturn,
            )
            candidate = null
            CandidateDispositionOutcome.LateReturnRetained(cleanup)
        }

        CandidateDispositionAction.QuarantineStartedTimeout -> {
            check(preparationQuarantine == null)
            releaseProvisionalDesired()
            check(ledger.rekey(record.token, ProviderDescriptorRetentionRole.Quarantine))
            providerPoisoned = true
            val quarantine = QuarantineCapability()
            preparationQuarantine = QuarantineRecord(quarantine, record.token, record.cleanupIndex)
            candidate = null
            CandidateDispositionOutcome.Quarantined(quarantine)
        }
    }

    internal fun prevalidateTargetAcknowledgement(
        session: SessionIdentity,
        origin: ControllerFactOrigin,
        target: ControllerTargetSnapshot,
    ): ControllerTargetAcknowledgementPrevalidation? {
        val record = candidate?.takeIf { it.snapshot.identity == origin.candidate } ?: return null
        if (target.identity == physicalCurrentTarget?.identity || !record.acceptsTarget(target)) return null
        return TargetAcknowledgementPrevalidationTicket(
            storeIdentity = identity,
            ownership = record.ownership,
            session = session,
            origin = origin,
            candidate = record.snapshot,
            previousTarget = physicalCurrentTarget,
            target = target,
            geometry = record.snapshot.geometry,
        )
    }

    internal fun acknowledgePhysicalTarget(
        prevalidation: ControllerTargetAcknowledgementPrevalidation,
    ): TargetAcknowledgementDisposition {
        val ticket = prevalidation as? TargetAcknowledgementPrevalidationTicket
            ?: return TargetAcknowledgementDisposition.InvalidAuthority
        if (ticket.storeIdentity !== identity) return TargetAcknowledgementDisposition.InvalidAuthority
        if (terminal) return TargetAcknowledgementDisposition.Terminal
        val record = ownedCandidate(ticket.ownership)
            ?.takeIf { it.snapshot === ticket.candidate }
            ?: return TargetAcknowledgementDisposition.InvalidAuthority
        if (
            physicalCurrentTarget !== ticket.previousTarget ||
            ticket.target.identity == physicalCurrentTarget?.identity ||
            !record.acceptsTarget(ticket.target)
        ) {
            return TargetAcknowledgementDisposition.SnapshotMismatch
        }
        physicalCurrentTarget = ticket.target
        return TargetAcknowledgementDisposition.Acknowledged(ticket.previousTarget)
    }

    /**
     * Proves the complete semantic/store relation before reduction. The later commit rechecks the
     * same fixed facts under the same controller gate and rejects a ticket minted by another store.
     */
    internal fun prevalidateCandidateCommit(
        ownership: ControllerCandidateOwnership,
        session: SessionIdentity,
        ownerIdentity: CompleteOwnerIdentity,
        effective: ControllerEffectiveSnapshot,
    ): ControllerCandidateCommitPrevalidation? {
        val candidateRecord = ownedCandidate(ownership) ?: return null
        val desiredRecord = provisionalDesired ?: return null
        val target = physicalCurrentTarget ?: return null
        if (
            currentOwner?.quarantined == true ||
            desiredRecord.snapshot.identity == currentDesired?.snapshot?.identity ||
            ownerIdentity == currentOwner?.identity ||
            effective.identity == lastEffective?.identity ||
            !candidateRecord.matches(ownerIdentity, effective, target) ||
            !candidateRecord.descriptorAccepted
        ) {
            return null
        }
        val descriptor = ledger.snapshot(candidateRecord.snapshot.desired.providerReference.provider)
        if (descriptor?.violated != false || descriptor.descriptor?.outputFormat != effective.encodedFormat) return null
        return CandidateCommitPrevalidationTicket(
            storeIdentity = identity,
            ownership = candidateRecord.ownership,
            session = session,
            candidate = candidateRecord.snapshot,
            target = target,
            owner = ownerIdentity,
            effective = effective,
        )
    }

    internal fun commitCandidate(
        prevalidation: ControllerCandidateCommitPrevalidation,
    ): CandidateCommitDisposition {
        val ticket = prevalidation as? CandidateCommitPrevalidationTicket
            ?: return CandidateCommitDisposition.InvalidOwnership
        if (ticket.storeIdentity !== identity) return CandidateCommitDisposition.InvalidOwnership
        return commitCandidatePrevalidated(ticket.ownership, ticket.target, ticket.owner, ticket.effective)
    }

    private fun commitCandidatePrevalidated(
        ownership: ControllerCandidateOwnership,
        expectedTarget: ControllerTargetSnapshot,
        ownerIdentity: CompleteOwnerIdentity,
        effective: ControllerEffectiveSnapshot,
    ): CandidateCommitDisposition {
        val candidateRecord = ownedCandidate(ownership) ?: return CandidateCommitDisposition.InvalidOwnership
        val desiredRecord = checkNotNull(provisionalDesired)
        val target = physicalCurrentTarget
        if (
            physicalCurrentTarget !== expectedTarget ||
            currentOwner?.quarantined == true ||
            desiredRecord.snapshot.identity == currentDesired?.snapshot?.identity ||
            ownerIdentity == currentOwner?.identity ||
            effective.identity == lastEffective?.identity ||
            !candidateRecord.matches(ownerIdentity, effective, target)
        ) {
            return CandidateCommitDisposition.SnapshotMismatch
        }
        if (!candidateRecord.descriptorAccepted) return CandidateCommitDisposition.DescriptorNotAccepted
        val descriptor = ledger.snapshot(candidateRecord.snapshot.desired.providerReference.provider)
        if (
            descriptor?.violated != false ||
            descriptor.descriptor?.outputFormat != effective.encodedFormat
        ) {
            return CandidateCommitDisposition.DescriptorNotAccepted
        }

        val previousDesired = currentDesired
        if (previousDesired != null) {
            check(ledger.handoff(previousDesired.token, desiredRecord.token, ProviderDescriptorRetentionRole.Desired))
        }
        check(ledger.rekey(candidateRecord.token, ProviderDescriptorRetentionRole.Active))

        val retiredOwner = currentOwner?.let { oldOwner ->
            check(ledger.rekey(oldOwner.token, ProviderDescriptorRetentionRole.Retiring))
            occupyReservedCleanup(candidateRecord.cleanupIndex, oldOwner.token, CleanupPhase.RetiringOwner)
        } ?: run {
            clearReservedCleanup(candidateRecord.cleanupIndex)
            null
        }

        currentDesired = desiredRecord
        currentOwner = OwnerRecord(ownerIdentity, candidateRecord.token)
        lastEffective = effective
        provisionalDesired = null
        candidate = null
        return CandidateCommitDisposition.Committed(retiredOwner)
    }

    /** Exact no-op: no snapshot, identity, provider, ledger, or owner mutation. */
    internal fun retainNormalizedNoOp(expectedDesired: DesiredSnapshotIdentity): Boolean =
        !terminal && candidate == null && currentDesired?.snapshot?.identity == expectedDesired

    /** Frame-rate-only commit retains the existing desired/owner ledger capabilities. */
    internal fun commitFrameRateOnly(
        desired: ControllerDesiredSnapshot,
        effective: ControllerEffectiveSnapshot,
    ): Boolean {
        if (terminal || candidate != null || provisionalDesired != null) return false
        val oldDesired = currentDesired ?: return false
        val oldEffective = lastEffective ?: return false
        val owner = currentOwner ?: return false
        if (owner.quarantined) return false
        val target = physicalCurrentTarget ?: return false
        if (
            oldEffective.desiredIdentity != oldDesired.snapshot.identity ||
            oldEffective.completeOwnerIdentity != owner.identity ||
            oldEffective.targetIdentity != target.identity ||
            !oldDesired.snapshot.hasSamePlanAndProvider(desired.output, desired.providerReference.provider) ||
            oldDesired.snapshot.output.frameRate == desired.output.frameRate ||
            desired.identity == oldDesired.snapshot.identity ||
            effective.identity == oldEffective.identity ||
            effective.desiredIdentity != desired.identity ||
            effective.completeOwnerIdentity != owner.identity ||
            effective.targetIdentity != target.identity ||
            effective.output != desired.output ||
            effective.geometry != oldEffective.geometry ||
            effective.plan != oldEffective.plan ||
            effective.encodedFormat != oldEffective.encodedFormat
        ) {
            return false
        }
        currentDesired = DesiredRecord(desired, oldDesired.token)
        lastEffective = effective
        return true
    }

    internal fun preparationQuarantineReturned(
        ownership: ControllerPreparationQuarantineOwnership,
        returnedEncoderNeedsCleanup: Boolean,
    ): QuarantineReturnDisposition {
        val quarantine = preparationQuarantine
        if (quarantine?.ownership !== ownership) {
            return QuarantineReturnDisposition.InvalidOwnership
        }
        if (!returnedEncoderNeedsCleanup) {
            clearReservedCleanup(quarantine.cleanupIndex)
            check(ledger.release(quarantine.token))
            preparationQuarantine = null
            return QuarantineReturnDisposition.ReturnedWithoutEncoder
        }
        check(ledger.rekey(quarantine.token, ProviderDescriptorRetentionRole.Cleanup))
        val cleanup = occupyReservedCleanup(
            quarantine.cleanupIndex,
            quarantine.token,
            CleanupPhase.ExclusiveCleanup,
        )
        preparationQuarantine = null
        return QuarantineReturnDisposition.CleanupRequired(cleanup)
    }

    internal fun latePreparationReturned(
        ownership: ControllerCleanupOwnership,
        returnedEncoderNeedsCleanup: Boolean,
    ): CleanupTransitionDisposition {
        val slot = cleanupSlot(ownership) ?: return CleanupTransitionDisposition.InvalidOwnership
        if (slot.phase != CleanupPhase.AwaitingLateReturn) return CleanupTransitionDisposition.InvalidOwnership
        return if (returnedEncoderNeedsCleanup) {
            check(ledger.rekey(checkNotNull(slot.token), ProviderDescriptorRetentionRole.Cleanup))
            slot.phase = CleanupPhase.ExclusiveCleanup
            CleanupTransitionDisposition.Advanced
        } else {
            releaseCleanup(slot)
            CleanupTransitionDisposition.Released
        }
    }

    internal fun beginExclusiveCleanup(
        ownership: ControllerCleanupOwnership,
    ): CleanupTransitionDisposition {
        val slot = cleanupSlot(ownership) ?: return CleanupTransitionDisposition.InvalidOwnership
        if (slot.phase != CleanupPhase.RetiringOwner) return CleanupTransitionDisposition.InvalidOwnership
        check(ledger.rekey(checkNotNull(slot.token), ProviderDescriptorRetentionRole.Cleanup))
        slot.phase = CleanupPhase.ExclusiveCleanup
        return CleanupTransitionDisposition.Advanced
    }

    internal fun retireCleanup(
        ownership: ControllerCleanupOwnership,
    ): CleanupTransitionDisposition {
        val slot = cleanupSlot(ownership) ?: return CleanupTransitionDisposition.InvalidOwnership
        if (slot.phase != CleanupPhase.ExclusiveCleanup) return CleanupTransitionDisposition.InvalidOwnership
        releaseCleanup(slot)
        return CleanupTransitionDisposition.Released
    }

    internal fun commitTerminal(): TerminalOwnershipDisposition {
        if (terminal) return TerminalOwnershipDisposition.AlreadyTerminal
        if (candidate != null || provisionalDesired != null) return TerminalOwnershipDisposition.CandidateStillOwned
        val cleanup = currentOwner?.takeIf { !it.quarantined }?.let { owner ->
            check(terminalCleanup == null)
            check(ledger.rekey(owner.token, ProviderDescriptorRetentionRole.Retiring))
            val ownership = CleanupCapability()
            terminalCleanup = CleanupSlot(ownership, owner.token, CleanupPhase.RetiringOwner)
            ownership
        }
        currentDesired?.let { check(ledger.release(it.token)) }
        currentDesired = null
        if (currentOwner?.quarantined != true) currentOwner = null
        physicalCurrentTarget = null
        terminal = true
        return TerminalOwnershipDisposition.Retired(cleanup)
    }

    internal fun quarantineCurrentOwner(expectedIdentity: CompleteOwnerIdentity): Boolean {
        val owner = currentOwner ?: return false
        if (owner.identity != expectedIdentity || owner.quarantined) return false
        check(ledger.rekey(owner.token, ProviderDescriptorRetentionRole.Quarantine))
        owner.quarantined = true
        providerPoisoned = true
        return true
    }

    internal fun activeOwnerQuarantineReturned(
        expectedIdentity: CompleteOwnerIdentity,
    ): ActiveOwnerReturnDisposition {
        val owner = currentOwner
        if (!terminal || owner?.identity != expectedIdentity || !owner.quarantined || terminalCleanup != null) {
            return ActiveOwnerReturnDisposition.InvalidOwnership
        }
        check(ledger.rekey(owner.token, ProviderDescriptorRetentionRole.Cleanup))
        val ownership = CleanupCapability()
        terminalCleanup = CleanupSlot(ownership, owner.token, CleanupPhase.ExclusiveCleanup)
        currentOwner = null
        return ActiveOwnerReturnDisposition.CleanupRequired(ownership)
    }

    internal fun abandonPhysicalTarget(expectedIdentity: TargetIdentity): Boolean {
        if (physicalCurrentTarget?.identity != expectedIdentity) return false
        physicalCurrentTarget = null
        return true
    }

    internal fun releaseLastEffective() {
        check(terminal)
        lastEffective = null
    }

    private fun ownedCandidate(ownership: ControllerCandidateOwnership): CandidateRecord? =
        candidate?.takeIf { it.ownership === ownership }

    private fun releaseCandidate(record: CandidateRecord) {
        releaseProvisionalDesired()
        check(ledger.release(record.token))
        clearReservedCleanup(record.cleanupIndex)
        candidate = null
    }

    private fun releaseProvisionalDesired() {
        val record = checkNotNull(provisionalDesired)
        check(ledger.release(record.token))
        provisionalDesired = null
    }

    private fun reserveOrdinaryCleanupSlot(): CleanupIndex? = when {
        firstCleanup == null -> CleanupIndex.First.also {
            firstCleanup = CleanupSlot(CleanupCapability(), null, CleanupPhase.Reserved)
        }

        secondCleanup == null -> CleanupIndex.Second.also {
            secondCleanup = CleanupSlot(CleanupCapability(), null, CleanupPhase.Reserved)
        }

        else -> null
    }

    private fun occupyReservedCleanup(
        index: CleanupIndex,
        token: ProviderDescriptorRetentionToken,
        phase: CleanupPhase,
    ): ControllerCleanupOwnership {
        val slot = slot(index)
        check(slot?.phase == CleanupPhase.Reserved && slot.token == null)
        slot.token = token
        slot.phase = phase
        return slot.ownership
    }

    private fun clearReservedCleanup(index: CleanupIndex) {
        val slot = slot(index)
        check(slot?.phase == CleanupPhase.Reserved && slot.token == null)
        clearSlot(index)
    }

    private fun cleanupSlot(ownership: ControllerCleanupOwnership): CleanupSlot? = when {
        firstCleanup?.ownership === ownership -> firstCleanup
        secondCleanup?.ownership === ownership -> secondCleanup
        terminalCleanup?.ownership === ownership -> terminalCleanup
        else -> null
    }

    private fun releaseCleanup(slot: CleanupSlot) {
        check(ledger.release(checkNotNull(slot.token)))
        when (slot) {
            firstCleanup -> firstCleanup = null
            secondCleanup -> secondCleanup = null
            terminalCleanup -> terminalCleanup = null
            else -> error("Cleanup slot is not owned by this store")
        }
    }

    private fun slot(index: CleanupIndex): CleanupSlot? = when (index) {
        CleanupIndex.First -> firstCleanup
        CleanupIndex.Second -> secondCleanup
    }

    private fun clearSlot(index: CleanupIndex) {
        when (index) {
            CleanupIndex.First -> firstCleanup = null
            CleanupIndex.Second -> secondCleanup = null
        }
    }

    private fun cleanupRecordCount(): Int =
        (if (firstCleanup == null) 0 else 1) +
                (if (secondCleanup == null) 0 else 1) +
                (if (terminalCleanup == null) 0 else 1)

    private class CandidateCapability : ControllerCandidateOwnership
    private class CleanupCapability : ControllerCleanupOwnership
    private class QuarantineCapability : ControllerPreparationQuarantineOwnership

    private class CandidatePrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val candidate: ControllerCandidateSnapshot,
    ) : ControllerCandidatePrevalidation

    private class CurrentCandidatePrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val candidate: ControllerCandidateSnapshot,
        override val origin: ControllerFactOrigin,
    ) : ControllerCurrentCandidatePrevalidation

    private class CandidateDispositionPrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val candidate: ControllerCandidateSnapshot,
        override val origin: ControllerFactOrigin,
        override val triggerSequence: IngressSequence?,
        override val trigger: CandidateDispositionTrigger,
        override val action: CandidateDispositionAction,
    ) : ControllerCandidateDispositionPrevalidation

    private class TerminalCandidateDispositionPrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val candidate: ControllerCandidateSnapshot,
        override val terminal: ControllerIngress.Terminal,
        override val action: CandidateDispositionAction,
    ) : ControllerTerminalCandidateDispositionPrevalidation

    private class CandidateCommitPrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val candidate: ControllerCandidateSnapshot,
        override val target: ControllerTargetSnapshot,
        override val owner: CompleteOwnerIdentity,
        override val effective: ControllerEffectiveSnapshot,
    ) : ControllerCandidateCommitPrevalidation

    private class TargetAcknowledgementPrevalidationTicket(
        val storeIdentity: Any,
        val ownership: ControllerCandidateOwnership,
        override val session: SessionIdentity,
        override val origin: ControllerFactOrigin,
        override val candidate: ControllerCandidateSnapshot,
        override val previousTarget: ControllerTargetSnapshot?,
        override val target: ControllerTargetSnapshot,
        override val geometry: GeometrySnapshot,
    ) : ControllerTargetAcknowledgementPrevalidation

    private class DesiredRecord(
        val snapshot: ControllerDesiredSnapshot,
        val token: ProviderDescriptorRetentionToken,
    )

    private class OwnerRecord(
        val identity: CompleteOwnerIdentity,
        val token: ProviderDescriptorRetentionToken,
    ) {
        var quarantined: Boolean = false
    }

    private class CandidateRecord(
        val ownership: ControllerCandidateOwnership,
        val snapshot: ControllerCandidateSnapshot,
        val token: ProviderDescriptorRetentionToken,
        val cleanupIndex: CleanupIndex,
    ) {
        var descriptorAccepted: Boolean = false

        fun acceptsTarget(target: ControllerTargetSnapshot): Boolean =
            target.assignment == TargetAssignmentEvidence.Acknowledged &&
                    target.wholeGeometryMappingValidated &&
                    target.geometry == snapshot.geometry &&
                    target.retentionFor(
                        geometry = snapshot.geometry,
                        demand = snapshot.plan.samplingDemand,
                        requiresFreshIdentity = false,
                    ) == TargetRetention.Retain

        fun matches(
            ownerIdentity: CompleteOwnerIdentity,
            effective: ControllerEffectiveSnapshot,
            target: ControllerTargetSnapshot?,
        ): Boolean = target != null &&
                effective.desiredIdentity == snapshot.desired.identity &&
                effective.completeOwnerIdentity == ownerIdentity &&
                effective.targetIdentity == target.identity &&
                target.retentionFor(
                    geometry = snapshot.geometry,
                    demand = snapshot.plan.samplingDemand,
                    requiresFreshIdentity = false,
                ) == TargetRetention.Retain &&
                effective.output == snapshot.desired.output &&
                effective.geometry == snapshot.geometry &&
                effective.plan == snapshot.plan
    }

    private class CleanupSlot(
        val ownership: ControllerCleanupOwnership,
        var token: ProviderDescriptorRetentionToken?,
        var phase: CleanupPhase,
    )

    private class QuarantineRecord(
        val ownership: ControllerPreparationQuarantineOwnership,
        val token: ProviderDescriptorRetentionToken,
        val cleanupIndex: CleanupIndex,
    )

    private enum class CleanupIndex { First, Second }
    private enum class CleanupPhase { Reserved, RetiringOwner, AwaitingLateReturn, ExclusiveCleanup }
}

private fun CandidateDispositionTrigger.accepts(action: CandidateDispositionAction): Boolean = when (this) {
    CandidateDispositionTrigger.ReadyPermitRejected,
    CandidateDispositionTrigger.RetargetStartTimedOut,
    CandidateDispositionTrigger.LateAcknowledgedSupersession,
        -> action is CandidateDispositionAction.RetireReturned && action.returnedEncoderNeedsCleanup

    CandidateDispositionTrigger.PreparationFailure -> action is CandidateDispositionAction.RetireReturned

    CandidateDispositionTrigger.PreparationStartedTimeout ->
        action === CandidateDispositionAction.QuarantineStartedTimeout

    CandidateDispositionTrigger.PrePublicRetirement -> true

    CandidateDispositionTrigger.Cancellation,
    CandidateDispositionTrigger.Supersession,
    CandidateDispositionTrigger.GeometryPreemption,
    CandidateDispositionTrigger.PausePreemption,
        -> action !== CandidateDispositionAction.QuarantineStartedTimeout
}
