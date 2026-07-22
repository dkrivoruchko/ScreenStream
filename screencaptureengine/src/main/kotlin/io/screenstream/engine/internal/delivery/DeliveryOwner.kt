package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DeliveryOwner internal constructor(
    private val authorityPort: DeliveryAuthorityPort,
    private val settlementSignal: SettlementSignal,
    private val deliveryThreadName: String = "ScreenCaptureEngine-Delivery",
) {
    private val endpointDisposition = AtomicReference(DeliveryEndpointDisposition.Absent)
    private val endpointTerminationGate = ReentrantLock()
    private val constructedEndpointRoot = AtomicReference<DeliveryEndpoint?>(null)
    private val endpointTerminationOwner = AtomicReference<DeliveryEndpointTerminationOwner?>(null)
    private val releasedEndpointRoot = AtomicReference<DeliveryEndpointRootReleaseReceipt?>(null)
    private val readyEndpointSlot = AtomicReference<DeliveryEndpoint?>(null)
    private val activeRegistration = AtomicReference<DeliveryRegistration?>(null)
    private val activeTicket = AtomicReference<DeliveryTicket?>(null)
    private val fatalSlot = AtomicReference<Throwable?>(null)
    private val startupFailureSlot = AtomicReference<Throwable?>(null)

    private val startupExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.EndpointStartupException,
        owner = this,
        handoff = null,
    )
    private val startupFatalNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.DirectFatal,
        owner = this,
        handoff = null,
    )
    private val shutdownExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.ShutdownException,
        owner = this,
        handoff = null,
    )
    private val shutdownFatalNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.ShutdownFatal,
        owner = this,
        handoff = null,
    )

    internal val startupEndpointRoot: DeliveryEndpoint?
        get() = constructedEndpointRoot.get()

    internal val endpointStartupFailure: Throwable?
        get() = startupFailureSlot.get()

    internal val exactFatal: Throwable?
        get() = fatalSlot.get()

    internal val terminationReceipt: DeliveryTerminationReceipt?
        get() = releasedEndpointRoot.get()?.terminationReceipt

    internal fun prepareRegistration(
        generation: Long,
        callback: (EncodedImageFrame) -> Unit,
    ): DeliveryRegistrationPreparation {
        if (activeRegistration.get() != null) return DeliveryRegistrationPreparation.Busy
        val registration = DeliveryRegistration(this, generation, callback)
        return DeliveryRegistrationPreparation.Prepared(this, registration)
    }

    /** Allocation-free physical install after the aggregate has committed registration admission. */
    internal fun commitPreparedRegistration(
        preparation: DeliveryRegistrationPreparation.Prepared,
    ): DeliveryCommandResult {
        val registration = preparation.registration
        if (!preparation.belongsTo(this) || !registration.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        if (preparation.state == DeliveryPreparationState.Installed && activeRegistration.get() === registration) {
            return DeliveryCommandResult.AlreadyApplied
        }
        if (!preparation.beginCommit()) return DeliveryCommandResult.NotCurrent
        if (!activeRegistration.compareAndSet(null, registration)) {
            check(preparation.restorePrepared())
            return DeliveryCommandResult.NotCurrent
        }
        check(preparation.publishInstalled())
        return DeliveryCommandResult.Applied
    }

    /** Closes the exact never-installed preparation; it cannot affect an installed registration. */
    internal fun discardPreparedRegistration(
        preparation: DeliveryRegistrationPreparation.Prepared,
    ): DeliveryCommandResult {
        if (!preparation.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        if (!preparation.discard()) return DeliveryCommandResult.NotCurrent
        preparation.registration.clearCallback()
        return DeliveryCommandResult.Applied
    }

    internal fun startEndpoint(): DeliveryEndpointStartResult {
        when (endpointDisposition.get()) {
            DeliveryEndpointDisposition.Ready -> return DeliveryEndpointStartResult.AlreadyReady
            DeliveryEndpointDisposition.Starting -> return DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Absent -> Unit
            DeliveryEndpointDisposition.Poisoned,
            DeliveryEndpointDisposition.ShutdownRequested,
            DeliveryEndpointDisposition.Terminated,
            DeliveryEndpointDisposition.Failed,
                -> return DeliveryEndpointStartResult.Failed
        }
        if (!endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Absent,
                DeliveryEndpointDisposition.Starting,
            )
        ) {
            return startResultFromCurrentState()
        }

        var constructed: DeliveryEndpoint? = null
        try {
            constructed = DeliveryEndpoint(this, deliveryThreadName, settlementSignal)
            val terminationOwner = DeliveryEndpointTerminationOwner(this, constructed)
            check(constructedEndpointRoot.compareAndSet(null, constructed))
            check(endpointTerminationOwner.compareAndSet(null, terminationOwner))
            if (constructed.prestart() != 1) {
                startupFailureSlot.compareAndSet(null, PRESTART_DID_NOT_START)
                constructed.poison()
                endpointDisposition.compareAndSet(
                    DeliveryEndpointDisposition.Starting,
                    DeliveryEndpointDisposition.Failed,
                )
                failClosedBestEffort(startupExceptionNotice)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            val publishedReady = endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Starting,
                DeliveryEndpointDisposition.Ready,
            )
            if (!publishedReady) {
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            readyEndpointSlot.set(constructed)
            if (endpointDisposition.get() != DeliveryEndpointDisposition.Ready) {
                readyEndpointSlot.compareAndSet(constructed, null)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            signalBestEffort()
            return DeliveryEndpointStartResult.Ready
        } catch (raw: Throwable) {
            startupFailureSlot.compareAndSet(null, raw)
            constructed?.poison()
            if (raw is Exception) {
                endpointDisposition.compareAndSet(
                    DeliveryEndpointDisposition.Starting,
                    DeliveryEndpointDisposition.Failed,
                )
                failClosedBestEffort(startupExceptionNotice)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            fatalSlot.compareAndSet(null, raw)
            endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Starting,
                DeliveryEndpointDisposition.Poisoned,
            )
            failClosedBestEffort(startupFatalNotice)
            signalBestEffort()
            throw raw
        }
    }

    internal fun prepareHandoff(
        registration: DeliveryRegistration,
        handoffIdentity: Long,
        outputKind: DeliveryOutputKind,
        preparedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryHandoffPreparation {
        require(handoffIdentity > 0L)
        if (!registration.belongsTo(this) || activeRegistration.get() !== registration ||
            !registration.hasCallback || preparedLease.isReleased
        ) {
            return DeliveryHandoffPreparation.EndpointUnavailable
        }
        val currentEndpoint = readyEndpointSlot.get()
        if (currentEndpoint == null || endpointDisposition.get() != DeliveryEndpointDisposition.Ready ||
            currentEndpoint.isPoisoned || currentEndpoint.isShutdownRequested
        ) {
            return DeliveryHandoffPreparation.EndpointUnavailable
        }

        if (activeTicket.get() != null) return DeliveryHandoffPreparation.Busy
        val callback = registration.callback() ?: return DeliveryHandoffPreparation.EndpointUnavailable
        val ticket = DeliveryTicket(currentEndpoint, handoffIdentity)
        val handoff = DeliveryHandoffRecord(
            owner = this,
            registration = registration,
            identity = handoffIdentity,
            outputKind = outputKind,
            ticket = ticket,
            callback = callback,
            lease = preparedLease,
        )
        return DeliveryHandoffPreparation.Prepared(this, handoff)
    }

    /** Allocation-free physical install after the aggregate has committed this exact handoff admission. */
    internal fun commitPreparedHandoff(
        preparation: DeliveryHandoffPreparation.Prepared,
    ): DeliveryCommandResult {
        val handoff = preparation.handoff
        val registration = handoff.registration
        val endpoint = handoff.ticket.endpoint
        if (!preparation.belongsTo(this) || !handoff.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        if (preparation.state == DeliveryPreparationState.Installed && activeTicket.get() === handoff.ticket) {
            return DeliveryCommandResult.AlreadyApplied
        }
        if (activeRegistration.get() !== registration || registration.callback() !== handoff.callback ||
            readyEndpointSlot.get() !== endpoint || endpointDisposition.get() != DeliveryEndpointDisposition.Ready ||
            endpoint.isPoisoned || endpoint.isShutdownRequested || preparedLeaseUnavailable(handoff)
        ) {
            return DeliveryCommandResult.NotCurrent
        }
        if (!preparation.beginCommit()) return DeliveryCommandResult.NotCurrent
        val installed = endpointTerminationGate.withLock {
            val terminationOwner = endpointTerminationOwner.get()
            if (terminationOwner == null || terminationOwner.endpoint !== endpoint ||
                terminationOwner.shutdownAction.state != DeliveryEndpointShutdownActionState.Prepared ||
                endpoint.isShutdownRequested || !activeTicket.compareAndSet(null, handoff.ticket)
            ) {
                false
            } else {
                check(handoff.ticket.install(handoff))
                check(preparation.publishInstalled())
                true
            }
        }
        if (!installed) {
            check(preparation.restorePrepared())
            return DeliveryCommandResult.NotCurrent
        }
        return DeliveryCommandResult.Applied
    }

    /** Releases and returns the exact never-installed handoff lease to its storage-consumption path. */
    internal fun discardPreparedHandoff(
        preparation: DeliveryHandoffPreparation.Prepared,
    ): EncodedStorageOwner.EncodedPayloadLease? {
        if (!preparation.belongsTo(this) || !preparation.discard()) return null
        val handoff = preparation.handoff
        val lease = handoff.settlementGate.withLock {
            handoff.borrowedAuthority.closeAndDetachLocked()
            handoff.leaseSlot.claimReleaseLocked()
        } ?: return null
        val released = lease.release()
        handoff.settlementGate.withLock {
            check(handoff.leaseSlot.publishReleaseLocked(released))
        }
        signalBestEffort()
        return lease
    }

    internal fun submitHandoff(handoff: DeliveryHandoffRecord): DeliverySubmissionResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket || handoff.ticket.handoff !== handoff ||
            readyEndpointSlot.get() !== handoff.ticket.endpoint
        ) {
            return DeliverySubmissionResult.NotCurrent
        }
        val begun = handoff.settlementGate.withLock {
            if (handoff.state != HandoffState.Prepared || handoff.domain != OperationDomain.Active ||
                !handoff.admissionOpen || !handoff.submissionCell.beginLocked()
            ) {
                false
            } else {
                handoff.state = HandoffState.Submitting
                true
            }
        }
        if (!begun) return DeliverySubmissionResult.NotCurrent

        try {
            handoff.ticket.endpoint.execute(handoff.runnable)
        } catch (raw: Throwable) {
            val releaseLease = handoff.settlementGate.withLock {
                handoff.submissionCell.publishThrownLocked(raw)
                if (raw is Exception && handoff.entryCell.disposition == DeliveryEntryDisposition.Empty) {
                    handoff.entryCell.publishLocked(DeliveryEntryDisposition.Inert)
                    if (handoff.state != HandoffState.Quarantined) {
                        handoff.state = HandoffState.DetachedPreEntry
                    }
                    true
                } else {
                    if (raw !is Exception && handoff.exactFatal == null) handoff.exactFatal = raw
                    false
                }
            }
            if (releaseLease) releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(handoff.submissionExceptionNotice)
                signalBestEffort()
                return DeliverySubmissionResult.Attempted
            }
            fatalSlot.compareAndSet(null, raw)
            publishEndpointPoisonedUnlessTerminated()
            failClosedBestEffort(handoff.directFatalNotice)
            signalBestEffort()
            throw raw
        }

        handoff.settlementGate.withLock {
            handoff.submissionCell.publishReturnedLocked()
            if (handoff.entryCell.disposition == DeliveryEntryDisposition.Empty &&
                handoff.state != HandoffState.Quarantined
            ) {
                handoff.state = HandoffState.AcceptedUnentered
            }
        }
        signalBestEffort()
        return DeliverySubmissionResult.Attempted
    }

    /**
     * Revokes an already-accepted handoff only for unsubscribe or terminal cutoff. Reconfiguration pause must
     * never call this: a queued accepted handoff is grandfathered and remains eligible to enter.
     */
    internal fun revokeHandoffAdmission(handoff: DeliveryHandoffRecord): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        var releasePrepared = false
        val changed = handoff.settlementGate.withLock {
            if (!handoff.admissionOpen) return@withLock false
            handoff.admissionOpen = false
            if (handoff.submissionCell.disposition == DeliverySubmissionDisposition.Empty) {
                releasePrepared = true
                handoff.state = HandoffState.DetachedPreEntry
            } else if (handoff.state == HandoffState.AcceptedUnentered ||
                handoff.state == HandoffState.Submitting
            ) {
                handoff.state = HandoffState.DetachedPreEntry
            }
            true
        }
        if (releasePrepared) releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return if (changed) DeliveryCommandResult.Applied else DeliveryCommandResult.AlreadyApplied
    }

    internal fun transferForTerminal(handoff: DeliveryHandoffRecord): DeliveryTerminalTransferResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryTerminalTransferResult.NotCurrent
        }
        var releaseUnsubmitted = false
        val settled = handoff.settlementGate.withLock {
            handoff.admissionOpen = false
            handoff.transferToCleanupLocked()
            if (handoff.submissionCell.disposition == DeliverySubmissionDisposition.Empty) {
                releaseUnsubmitted = true
            }
            if (!isMechanicallySettledLocked(handoff)) handoff.state = HandoffState.Quarantined
            isMechanicallySettledLocked(handoff)
        }
        if (releaseUnsubmitted) releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return if (settled) DeliveryTerminalTransferResult.Settled else DeliveryTerminalTransferResult.Transferred
    }

    internal fun terminalResidue(handoff: DeliveryHandoffRecord): DeliveryTerminalResidue? {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) return null
        return handoff.terminalResidue
    }

    internal fun releasedLeaseForStorageConsumption(
        handoff: DeliveryHandoffRecord,
    ): EncodedStorageOwner.EncodedPayloadLease? {
        if (!handoff.belongsTo(this)) return null
        return handoff.settlementGate.withLock {
            if ((handoff.leaseSlot.disposition != DeliveryLeaseDisposition.Released &&
                        handoff.leaseSlot.disposition != DeliveryLeaseDisposition.ReleaseConflict) ||
                handoff.leaseSlot.lease == null
            ) {
                return@withLock null
            }
            handoff.leaseSlot.lease
        }
    }

    internal fun acknowledgeStorageLeaseConsumption(
        handoff: DeliveryHandoffRecord,
        consumedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        val applied = handoff.settlementGate.withLock {
            handoff.leaseSlot.clearConsumedLocked(consumedLease)
        }
        if (!applied) return DeliveryCommandResult.NotCurrent
        signalBestEffort()
        return DeliveryCommandResult.Applied
    }

    internal fun releaseRetainedNoCallbackAuthority(
        handoff: DeliveryHandoffRecord,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        val eligible = handoff.settlementGate.withLock {
            handoff.domain == OperationDomain.Cleanup &&
                    handoff.entryCell.disposition == DeliveryEntryDisposition.Entered &&
                    handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedAfterEntry &&
                    handoff.callbackCell.disposition == DeliveryCallbackDisposition.Empty &&
                    !handoff.callbackInvocationStarted &&
                    handoff.leaseSlot.disposition == DeliveryLeaseDisposition.Owned
        }
        if (!eligible) return DeliveryCommandResult.NotCurrent
        releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return DeliveryCommandResult.Applied
    }

    internal fun retireSettledHandoff(handoff: DeliveryHandoffRecord): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        val settled = handoff.settlementGate.withLock {
            if (!isMechanicallySettledLocked(handoff)) return@withLock false
            handoff.state = HandoffState.Resolved
            true
        }
        var pendingShutdownAction: DeliveryEndpointShutdownAction? = null
        val retired = settled && endpointTerminationGate.withLock {
            if (!activeTicket.compareAndSet(handoff.ticket, null)) {
                false
            } else {
                pendingShutdownAction = endpointTerminationOwner.get()
                    ?.claimPendingShutdownAfterRetirement(handoff.ticket)
                true
            }
        }
        if (!retired) {
            return DeliveryCommandResult.NotCurrent
        }
        signalBestEffort()
        pendingShutdownAction?.let(::performClaimedPendingShutdown)
        return DeliveryCommandResult.Applied
    }

    /** Immediately closes future handoff creation for the registration. Existing records retain their callback. */
    internal fun closeRegistrationAdmission(registration: DeliveryRegistration): DeliveryCommandResult {
        if (!registration.belongsTo(this) || activeRegistration.get() !== registration) {
            return DeliveryCommandResult.NotCurrent
        }
        return if (registration.clearCallback()) {
            DeliveryCommandResult.Applied
        } else {
            DeliveryCommandResult.AlreadyApplied
        }
    }

    /** Mechanical input for the aggregate's shared unsubscribe result; this method never completes a waiter. */
    internal fun registrationSettlement(registration: DeliveryRegistration): DeliveryRegistrationSettlement {
        if (!registration.belongsTo(this) || activeRegistration.get() !== registration) {
            return DeliveryRegistrationSettlement.NotOwned
        }
        if (!registration.isAdmissionClosed) return DeliveryRegistrationSettlement.Open
        return if (activeTicket.get()?.handoff?.registration === registration) {
            DeliveryRegistrationSettlement.Closing
        } else {
            DeliveryRegistrationSettlement.Settled
        }
    }

    /** Clears replacement exclusion only after exact successful mechanical unsubscribe settlement. */
    internal fun retireSettledRegistration(registration: DeliveryRegistration): DeliveryCommandResult {
        if (registrationSettlement(registration) != DeliveryRegistrationSettlement.Settled) {
            return DeliveryCommandResult.NotCurrent
        }
        return if (activeRegistration.compareAndSet(registration, null)) {
            DeliveryCommandResult.Applied
        } else {
            DeliveryCommandResult.NotCurrent
        }
    }

    internal fun isCallbackThread(handoff: DeliveryHandoffRecord): Boolean {
        if (!handoff.belongsTo(this)) return false
        return handoff.settlementGate.withLock {
            handoff.entryCell.callbackThread === Thread.currentThread()
        }
    }

    internal fun requestShutdown(): DeliveryShutdownResult {
        return when (val eligibility = requestedShutdownEligibility()) {
            is DeliveryEndpointShutdownEligibility.EndpointRootAbsent -> DeliveryShutdownResult.EndpointAbsent
            is DeliveryEndpointShutdownEligibility.WaitingForHandoffSettlement ->
                DeliveryShutdownResult.TicketUnsettled

            is DeliveryEndpointShutdownEligibility.Eligible ->
                enterShutdown(eligibility.action)

            is DeliveryEndpointShutdownEligibility.ActionAlreadyEntered ->
                shutdownResult(eligibility.outcome, repeated = true)
        }
    }

    private fun requestedShutdownEligibility(): DeliveryEndpointShutdownEligibility {
        val terminationOwner = endpointTerminationOwner.get()
            ?: return DeliveryEndpointShutdownEligibility.EndpointRootAbsent(this)
        return evaluateEndpointShutdownEligibility(terminationOwner, retainPendingDemand = true)
    }

    private fun evaluateEndpointShutdownEligibility(
        terminationOwner: DeliveryEndpointTerminationOwner,
        retainPendingDemand: Boolean = false,
    ): DeliveryEndpointShutdownEligibility = endpointTerminationGate.withLock {
        if (endpointTerminationOwner.get() !== terminationOwner) {
            return@withLock DeliveryEndpointShutdownEligibility.EndpointRootAbsent(this)
        }
        val action = terminationOwner.shutdownAction
        val observed = action.outcome
        if (action.state != DeliveryEndpointShutdownActionState.Prepared) {
            return@withLock DeliveryEndpointShutdownEligibility.ActionAlreadyEntered(
                terminationOwner.provenance,
                observed ?: action.enteredOutcome,
            )
        }
        val ticket = activeTicket.get()
        if (ticket != null) {
            if (retainPendingDemand) terminationOwner.retainPendingShutdown(ticket)
            DeliveryEndpointShutdownEligibility.WaitingForHandoffSettlement(
                terminationOwner.provenance,
                ticket,
            )
        } else {
            DeliveryEndpointShutdownEligibility.Eligible(terminationOwner.provenance, action)
        }
    }

    internal fun enterExactEndpointShutdown(
        terminationOwner: DeliveryEndpointTerminationOwner,
        action: DeliveryEndpointShutdownAction,
    ): DeliveryEndpointShutdownActionOutcome {
        val immediate = endpointTerminationGate.withLock {
            check(
                endpointTerminationOwner.get() === terminationOwner &&
                        terminationOwner.shutdownAction === action,
            )
            val ticket = activeTicket.get()
            if (ticket != null) {
                terminationOwner.retainPendingShutdown(ticket)
                return@withLock DeliveryEndpointShutdownActionOutcome.NotEntered(action, ticket)
            }
            if (!terminationOwner.claimShutdownEntry()) {
                return@withLock action.outcome ?: action.enteredOutcome
            }
            null
        }
        return immediate ?: action.performEnteredCall()
    }

    private fun performClaimedPendingShutdown(action: DeliveryEndpointShutdownAction) {
        try {
            action.performEnteredCall()
        } catch (raw: Throwable) {
            if (raw !is Exception) throw raw
        }
    }

    internal fun performExactEndpointShutdown(
        terminationOwner: DeliveryEndpointTerminationOwner,
        action: DeliveryEndpointShutdownAction,
    ): DeliveryShutdownDisposition {
        val currentEndpoint = constructedEndpointRoot.get()
        check(
            endpointTerminationOwner.get() === terminationOwner &&
                    terminationOwner.endpoint === currentEndpoint &&
                    terminationOwner.shutdownAction === action,
        )
        try {
            val shutdownDisposition = checkNotNull(currentEndpoint).requestShutdown()
            check(shutdownDisposition == DeliveryShutdownDisposition.Returned)
            publishShutdownRequestedUnlessTerminated()
            signalBestEffort()
            return shutdownDisposition
        } catch (raw: Throwable) {
            checkNotNull(currentEndpoint).poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(shutdownExceptionNotice)
                signalBestEffort()
                throw raw
            }
            fatalSlot.compareAndSet(null, raw)
            publishEndpointPoisonedUnlessTerminated()
            failClosedBestEffort(shutdownFatalNotice)
            signalBestEffort()
            throw raw
        }
    }

    internal fun acceptsTerminationReceipt(receipt: DeliveryTerminationReceipt): Boolean =
        constructedEndpointRoot.get()?.accepts(receipt) == true ||
                releasedEndpointRoot.get()?.terminationReceipt === receipt

    internal fun releaseTerminatedEndpoint(
        receipt: DeliveryTerminationReceipt,
    ): DeliveryCommandResult {
        val terminationOwner = endpointTerminationOwner.get()
        if (terminationOwner == null) {
            return if (releasedEndpointRoot.get()?.terminationReceipt === receipt) {
                DeliveryCommandResult.AlreadyApplied
            } else {
                DeliveryCommandResult.NotCurrent
            }
        }
        return when (terminationOwner.releaseEndpointRoot(receipt)) {
            is DeliveryEndpointRootSettlement.Released -> DeliveryCommandResult.Applied
            is DeliveryEndpointRootSettlement.Retained -> DeliveryCommandResult.NotCurrent
        }
    }

    internal fun releaseExactEndpointRoot(
        terminationOwner: DeliveryEndpointTerminationOwner,
        receipt: DeliveryTerminationReceipt,
    ): Boolean {
        val terminatedEndpoint = constructedEndpointRoot.get() ?: return false
        if (endpointTerminationOwner.get() !== terminationOwner || terminationOwner.endpoint !== terminatedEndpoint ||
            !terminatedEndpoint.accepts(receipt) || terminatedEndpoint.terminationReceipt !== receipt
        ) {
            return false
        }
        readyEndpointSlot.compareAndSet(terminatedEndpoint, null)
        if (!constructedEndpointRoot.compareAndSet(terminatedEndpoint, null)) return false
        check(endpointTerminationOwner.compareAndSet(terminationOwner, null))
        return true
    }

    internal fun publishEndpointRootReleased(
        terminationOwner: DeliveryEndpointTerminationOwner,
        receipt: DeliveryEndpointRootReleaseReceipt,
    ) {
        check(
            terminationOwner.owner === this &&
                    receipt.terminationReceipt === terminationOwner.endpoint.ownedTerminationReceipt &&
                    receipt.terminationReceipt.identity === terminationOwner.endpoint.terminationIdentity,
        )
        check(releasedEndpointRoot.compareAndSet(null, receipt))
    }

    internal fun runHandoffRunnable(handoff: DeliveryHandoffRecord) {
        try {
            runHandoffBody(handoff)
        } catch (raw: Throwable) {
            val releaseUnentered = handoff.settlementGate.withLock {
                if (handoff.entryCell.disposition == DeliveryEntryDisposition.Empty) {
                    handoff.entryCell.publishLocked(DeliveryEntryDisposition.Inert)
                    if (handoff.state != HandoffState.Quarantined) {
                        handoff.state = HandoffState.DetachedPreEntry
                    }
                }
                val afterEntry = handoff.entryCell.disposition == DeliveryEntryDisposition.Entered
                if (!handoff.callbackInvocationStarted &&
                    handoff.callbackCell.disposition == DeliveryCallbackDisposition.Empty
                ) {
                    handoff.noCallbackCell.publishLocked(afterEntry, raw)
                }
                if (raw is Exception) {
                    handoff.runnableCell.publishReturnedLocked(raw)
                } else {
                    handoff.runnableCell.publishFatalLocked(raw)
                    if (handoff.exactFatal == null) handoff.exactFatal = raw
                }
                !afterEntry
            }
            if (releaseUnentered) releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(
                    if (handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedBeforeEntry) {
                        handoff.admissionExceptionNotice
                    } else {
                        handoff.runnableExceptionNotice
                    },
                )
                signalBestEffort()
                return
            } else {
                fatalSlot.compareAndSet(null, raw)
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(handoff.directFatalNotice)
            }
            signalBestEffort()
            throw raw
        }
        handoff.settlementGate.withLock {
            handoff.runnableCell.publishReturnedLocked()
        }
        signalBestEffort()
    }

    internal fun commitEntry(
        request: DeliveryEntryRequest,
        acceptedHandoffStillAdmitted: Boolean,
    ): DeliveryEntryDisposition {
        val handoff = request.handoff
        if (!handoff.belongsTo(this) || request.registration !== handoff.registration ||
            request.registrationGeneration != handoff.registration.generation || request.ticket !== handoff.ticket ||
            request.endpoint !== handoff.ticket.endpoint
        ) {
            return DeliveryEntryDisposition.Inert
        }
        return handoff.settlementGate.withLock {
            val result = if (acceptedHandoffStillAdmitted && activeTicket.get() === handoff.ticket &&
                readyEndpointSlot.get() === request.endpoint && endpointDisposition.get() == DeliveryEndpointDisposition.Ready &&
                !request.endpoint.isPoisoned && !request.endpoint.isShutdownRequested &&
                handoff.domain == OperationDomain.Active && handoff.admissionOpen &&
                handoff.submissionCell.disposition != DeliverySubmissionDisposition.ThrownException &&
                handoff.entryCell.disposition == DeliveryEntryDisposition.Empty
            ) {
                DeliveryEntryDisposition.Entered
            } else {
                DeliveryEntryDisposition.Inert
            }
            if (!handoff.entryCell.publishLocked(result)) return@withLock handoff.entryCell.disposition
            handoff.state = if (result == DeliveryEntryDisposition.Entered) {
                HandoffState.Entered
            } else {
                HandoffState.DetachedPreEntry
            }
            result
        }
    }

    internal fun publishEndpointTerminated(terminatedEndpoint: DeliveryEndpoint) {
        if (constructedEndpointRoot.get() !== terminatedEndpoint) return
        readyEndpointSlot.compareAndSet(terminatedEndpoint, null)
        endpointDisposition.set(DeliveryEndpointDisposition.Terminated)
        val terminationOwner = endpointTerminationOwner.get() ?: return
        if (terminationOwner.endpoint !== terminatedEndpoint) return
        val receipt = terminatedEndpoint.terminationReceipt ?: return
        terminationOwner.releaseEndpointRoot(receipt)
    }

    private fun enterShutdown(action: DeliveryEndpointShutdownAction): DeliveryShutdownResult = try {
        shutdownResult(action.enter(), repeated = false)
    } catch (raw: Throwable) {
        if (raw is Exception) DeliveryShutdownResult.ThrownException else throw raw
    }

    private fun shutdownResult(
        outcome: DeliveryEndpointShutdownActionOutcome,
        repeated: Boolean,
    ): DeliveryShutdownResult =
        when (outcome) {
            is DeliveryEndpointShutdownActionOutcome.Entered -> DeliveryShutdownResult.AlreadyRequested
            is DeliveryEndpointShutdownActionOutcome.NotEntered -> DeliveryShutdownResult.TicketUnsettled
            is DeliveryEndpointShutdownActionOutcome.Returned -> when (outcome.disposition) {
                DeliveryShutdownDisposition.Returned -> if (repeated) {
                    DeliveryShutdownResult.AlreadyRequested
                } else {
                    DeliveryShutdownResult.Requested
                }
                DeliveryShutdownDisposition.Empty,
                DeliveryShutdownDisposition.InCall,
                    -> DeliveryShutdownResult.AlreadyRequested

                DeliveryShutdownDisposition.ThrownException -> DeliveryShutdownResult.ThrownException
                DeliveryShutdownDisposition.ThrownFatal -> throw checkNotNull(outcome.action.provenance.endpoint.shutdownCell.throwable)
            }

            is DeliveryEndpointShutdownActionOutcome.Thrown -> {
                if (outcome.rawThrowable is Exception) DeliveryShutdownResult.ThrownException else throw outcome.rawThrowable
            }
        }

    private fun runHandoffBody(handoff: DeliveryHandoffRecord) {
        authorityPort.validateAcceptedEntry(handoff.entryRequest)
        val committed = handoff.settlementGate.withLock { handoff.entryCell.disposition }
        if (committed == DeliveryEntryDisposition.Empty) {
            throw ADMISSION_PORT_DID_NOT_COMMIT
        }
        signalBestEffort()
        if (committed == DeliveryEntryDisposition.Inert) {
            releaseLeaseOnce(handoff)
            return
        }

        val callbackThread = Thread.currentThread()
        val opened = handoff.settlementGate.withLock {
            handoff.entryCell.publishCallbackThreadLocked(callbackThread) &&
                    handoff.borrowedAuthority.openLocked(callbackThread).also { openedAuthority ->
                        if (openedAuthority) handoff.callbackInvocationStarted = true
                    }
        }
        if (!opened) throw CALLBACK_AUTHORITY_OPEN_FAILED

        var callbackFailure: Throwable? = null
        try {
            handoff.callback(handoff.borrowedAuthority.frame)
        } catch (raw: Throwable) {
            callbackFailure = raw
        }
        handoff.settlementGate.withLock {
            handoff.callbackCell.publishLocked(callbackFailure)
            handoff.borrowedAuthority.closeAndDetachLocked()
            handoff.entryCell.clearCallbackThreadLocked()
            if (callbackFailure != null && callbackFailure !is Exception) {
                if (handoff.exactFatal == null) handoff.exactFatal = callbackFailure
            }
        }
        val exactFailure = callbackFailure
        if (exactFailure != null && exactFailure !is Exception) {
            releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            fatalSlot.compareAndSet(null, exactFailure)
            publishEndpointPoisonedUnlessTerminated()
            failClosedBestEffort(handoff.directFatalNotice)
            signalBestEffort()
            throw exactFailure
        }
        releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
    }

    private fun releaseLeaseOnce(
        handoff: DeliveryHandoffRecord,
        signalAfter: Boolean = true,
    ) {
        val lease = handoff.settlementGate.withLock {
            handoff.borrowedAuthority.closeAndDetachLocked()
            handoff.leaseSlot.claimReleaseLocked()
        } ?: return
        val released = lease.release()
        handoff.settlementGate.withLock {
            handoff.leaseSlot.publishReleaseLocked(released)
        }
        if (signalAfter) signalBestEffort()
    }

    private fun preparedLeaseUnavailable(handoff: DeliveryHandoffRecord): Boolean =
        handoff.settlementGate.withLock {
            handoff.leaseSlot.disposition != DeliveryLeaseDisposition.Owned ||
                    handoff.leaseSlot.lease?.isReleased != false
        }

    private fun isMechanicallySettledLocked(handoff: DeliveryHandoffRecord): Boolean {
        val submission = handoff.submissionCell.disposition
        val entry = handoff.entryCell.disposition
        val callbackSettled = entry != DeliveryEntryDisposition.Entered ||
                handoff.callbackCell.disposition != DeliveryCallbackDisposition.Empty ||
                handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedAfterEntry
        val runnableSettled = when (submission) {
            DeliverySubmissionDisposition.Empty -> true
            DeliverySubmissionDisposition.ThrownException ->
                entry == DeliveryEntryDisposition.Inert ||
                        handoff.runnableCell.disposition != DeliveryRunnableDisposition.Empty

            DeliverySubmissionDisposition.Returned,
            DeliverySubmissionDisposition.ThrownFatal,
                -> handoff.runnableCell.disposition != DeliveryRunnableDisposition.Empty

            DeliverySubmissionDisposition.InCall -> false
        }
        val entrySettled = when (submission) {
            DeliverySubmissionDisposition.Empty -> true
            DeliverySubmissionDisposition.ThrownException -> entry != DeliveryEntryDisposition.Empty
            DeliverySubmissionDisposition.Returned,
            DeliverySubmissionDisposition.ThrownFatal,
            DeliverySubmissionDisposition.InCall,
                -> entry != DeliveryEntryDisposition.Empty
        }
        val leaseSettled = handoff.leaseSlot.lease == null &&
                (handoff.leaseSlot.disposition == DeliveryLeaseDisposition.Released ||
                        handoff.leaseSlot.disposition == DeliveryLeaseDisposition.ReleaseConflict)
        return submission != DeliverySubmissionDisposition.InCall && entrySettled && callbackSettled &&
                runnableSettled && leaseSettled
    }

    private fun publishEndpointPoisonedUnlessTerminated() {
        while (true) {
            val current = endpointDisposition.get()
            if (current == DeliveryEndpointDisposition.Terminated ||
                current == DeliveryEndpointDisposition.Poisoned
            ) {
                return
            }
            if (endpointDisposition.compareAndSet(current, DeliveryEndpointDisposition.Poisoned)) return
        }
    }

    private fun publishShutdownRequestedUnlessTerminated() {
        while (true) {
            val current = endpointDisposition.get()
            if (current == DeliveryEndpointDisposition.Terminated ||
                current == DeliveryEndpointDisposition.ShutdownRequested
            ) {
                return
            }
            if (endpointDisposition.compareAndSet(current, DeliveryEndpointDisposition.ShutdownRequested)) return
        }
    }

    private fun startResultFromCurrentState(): DeliveryEndpointStartResult =
        when (endpointDisposition.get()) {
            DeliveryEndpointDisposition.Ready -> DeliveryEndpointStartResult.AlreadyReady
            DeliveryEndpointDisposition.Starting -> DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Absent -> DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Poisoned,
            DeliveryEndpointDisposition.ShutdownRequested,
            DeliveryEndpointDisposition.Terminated,
            DeliveryEndpointDisposition.Failed,
                -> DeliveryEndpointStartResult.Failed
        }

    private fun failClosedBestEffort(notice: DeliveryFailureNotice) {
        if (!notice.claimPublication()) return
        try {
            authorityPort.failClosed(notice)
        } catch (_: Throwable) {
            // The durable notice and original failure remain authoritative.
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable cells remain authoritative when signalling cannot progress.
        }
    }

    private companion object {
        private val PRESTART_DID_NOT_START = IllegalStateException("Delivery executor worker did not prestart")
        private val ADMISSION_PORT_DID_NOT_COMMIT =
            IllegalStateException("Delivery admission port did not publish one exact entry disposition")
        private val CALLBACK_AUTHORITY_OPEN_FAILED =
            IllegalStateException("Delivery callback authority could not be opened")
    }
}
