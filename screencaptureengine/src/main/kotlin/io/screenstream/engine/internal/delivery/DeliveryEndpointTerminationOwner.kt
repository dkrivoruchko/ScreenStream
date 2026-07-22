package io.screenstream.engine.internal.delivery

import java.util.concurrent.atomic.AtomicReference

internal class DeliveryEndpointProvenance internal constructor(
    internal val owner: DeliveryOwner,
    internal val endpoint: DeliveryEndpoint,
)

internal enum class DeliveryEndpointShutdownActionState {
    Prepared,
    Entered,
    Returned,
    Thrown,
}

internal sealed interface DeliveryEndpointShutdownActionOutcome {
    val action: DeliveryEndpointShutdownAction

    internal class Entered internal constructor(
        override val action: DeliveryEndpointShutdownAction,
    ) : DeliveryEndpointShutdownActionOutcome

    internal class NotEntered internal constructor(
        override val action: DeliveryEndpointShutdownAction,
        internal val blockingTicket: DeliveryTicket,
    ) : DeliveryEndpointShutdownActionOutcome

    internal class Returned internal constructor(
        override val action: DeliveryEndpointShutdownAction,
        internal val disposition: DeliveryShutdownDisposition,
    ) : DeliveryEndpointShutdownActionOutcome

    internal class Thrown internal constructor(
        override val action: DeliveryEndpointShutdownAction,
    ) : DeliveryEndpointShutdownActionOutcome {
        internal val disposition: DeliveryShutdownDisposition
            get() = action.provenance.endpoint.shutdownCell.disposition
        internal val rawThrowable: Throwable
            get() = checkNotNull(action.provenance.endpoint.shutdownCell.throwable)
    }
}

internal sealed interface DeliveryEndpointShutdownEligibility {
    internal class EndpointRootAbsent internal constructor(
        internal val owner: DeliveryOwner,
    ) : DeliveryEndpointShutdownEligibility

    internal class WaitingForHandoffSettlement internal constructor(
        internal val provenance: DeliveryEndpointProvenance,
        internal val ticket: DeliveryTicket,
    ) : DeliveryEndpointShutdownEligibility

    internal class Eligible internal constructor(
        internal val provenance: DeliveryEndpointProvenance,
        internal val action: DeliveryEndpointShutdownAction,
    ) : DeliveryEndpointShutdownEligibility

    internal class ActionAlreadyEntered internal constructor(
        internal val provenance: DeliveryEndpointProvenance,
        internal val outcome: DeliveryEndpointShutdownActionOutcome,
    ) : DeliveryEndpointShutdownEligibility
}

internal sealed interface DeliveryEndpointShutdownDemand {
    val provenance: DeliveryEndpointProvenance
    val action: DeliveryEndpointShutdownAction

    internal class NotRequested internal constructor(
        override val provenance: DeliveryEndpointProvenance,
        override val action: DeliveryEndpointShutdownAction,
    ) : DeliveryEndpointShutdownDemand

    internal class PendingTicket internal constructor(
        override val provenance: DeliveryEndpointProvenance,
        override val action: DeliveryEndpointShutdownAction,
        internal val ticket: DeliveryTicket,
    ) : DeliveryEndpointShutdownDemand

    internal class EntryClaimed internal constructor(
        override val provenance: DeliveryEndpointProvenance,
        override val action: DeliveryEndpointShutdownAction,
    ) : DeliveryEndpointShutdownDemand
}

internal sealed interface DeliveryEndpointTerminationOutcome {
    val provenance: DeliveryEndpointProvenance

    internal class Pending internal constructor(
        override val provenance: DeliveryEndpointProvenance,
        internal val shutdownOutcome: DeliveryEndpointShutdownActionOutcome?,
    ) : DeliveryEndpointTerminationOutcome

    internal class Terminated internal constructor(
        override val provenance: DeliveryEndpointProvenance,
        internal val receipt: DeliveryTerminationReceipt,
    ) : DeliveryEndpointTerminationOutcome
}

internal class DeliveryEndpointRootReleaseReceipt private constructor(
    internal val terminationReceipt: DeliveryTerminationReceipt,
) {
    internal companion object {
        internal fun create(
            terminationReceipt: DeliveryTerminationReceipt,
        ): DeliveryEndpointRootReleaseReceipt =
            DeliveryEndpointRootReleaseReceipt(terminationReceipt)
    }
}

internal sealed interface DeliveryEndpointRootSettlement {
    internal class Retained internal constructor(
        internal val provenance: DeliveryEndpointProvenance,
        internal val terminationOutcome: DeliveryEndpointTerminationOutcome,
    ) : DeliveryEndpointRootSettlement

    internal class Released internal constructor(
        internal val receipt: DeliveryEndpointRootReleaseReceipt,
    ) : DeliveryEndpointRootSettlement
}

/**
 * Exact one-shot shutdown entry. The Entered state is retained indefinitely if shutdown does not return; it is
 * not callback, lease, Runnable-return, endpoint-termination, or release evidence.
 */
internal class DeliveryEndpointShutdownAction internal constructor(
    private val terminationOwner: DeliveryEndpointTerminationOwner,
) {
    private val actionState = AtomicReference(DeliveryEndpointShutdownActionState.Prepared)
    private val returnedOutcome = AtomicReference<DeliveryEndpointShutdownActionOutcome?>(null)
    internal val enteredOutcome: DeliveryEndpointShutdownActionOutcome.Entered =
        DeliveryEndpointShutdownActionOutcome.Entered(this)
    private val returnedOutcomeValue: DeliveryEndpointShutdownActionOutcome.Returned =
        DeliveryEndpointShutdownActionOutcome.Returned(this, DeliveryShutdownDisposition.Returned)
    private val thrownOutcomeValue: DeliveryEndpointShutdownActionOutcome.Thrown =
        DeliveryEndpointShutdownActionOutcome.Thrown(this)

    internal val provenance: DeliveryEndpointProvenance
        get() = terminationOwner.provenance

    internal val state: DeliveryEndpointShutdownActionState
        get() = actionState.get()

    internal val outcome: DeliveryEndpointShutdownActionOutcome?
        get() = returnedOutcome.get()

    internal fun enter(): DeliveryEndpointShutdownActionOutcome =
        terminationOwner.enterExactShutdown(this)

    internal fun claimEntry(): Boolean = actionState.compareAndSet(
        DeliveryEndpointShutdownActionState.Prepared,
        DeliveryEndpointShutdownActionState.Entered,
    )

    internal fun performEnteredCall(): DeliveryEndpointShutdownActionOutcome {
        check(actionState.get() == DeliveryEndpointShutdownActionState.Entered)
        return try {
            val disposition = terminationOwner.requestExactShutdown(this)
            val returned = returnedOutcomeValue
            check(returned.disposition == disposition)
            check(returnedOutcome.compareAndSet(null, returned))
            actionState.set(DeliveryEndpointShutdownActionState.Returned)
            returned
        } catch (raw: Throwable) {
            val disposition = provenance.endpoint.shutdownCell.disposition
            check(
                disposition == DeliveryShutdownDisposition.ThrownException ||
                        disposition == DeliveryShutdownDisposition.ThrownFatal,
            )
            check(provenance.endpoint.shutdownCell.throwable === raw)
            val thrown = thrownOutcomeValue
            check(thrown.disposition == disposition && thrown.rawThrowable === raw)
            check(returnedOutcome.compareAndSet(null, thrown))
            actionState.set(DeliveryEndpointShutdownActionState.Thrown)
            throw raw
        }
    }
}

/**
 * Delivery-owned endpoint lifecycle. Endpoint-root release is intentionally narrower than handoff/callback,
 * borrowed-frame, encoded-lease, or Storage settlement and cannot settle or quarantine any of those resources.
 */
internal class DeliveryEndpointTerminationOwner internal constructor(
    internal val owner: DeliveryOwner,
    internal val endpoint: DeliveryEndpoint,
) {
    internal val provenance: DeliveryEndpointProvenance =
        DeliveryEndpointProvenance(owner, endpoint)
    internal val shutdownAction: DeliveryEndpointShutdownAction = DeliveryEndpointShutdownAction(this)

    private val terminatedOutcome =
        DeliveryEndpointTerminationOutcome.Terminated(provenance, endpoint.ownedTerminationReceipt)
    private val preparedRootReleaseReceipt =
        DeliveryEndpointRootReleaseReceipt.create(endpoint.ownedTerminationReceipt)
    private val preparedReleasedSettlement =
        DeliveryEndpointRootSettlement.Released(preparedRootReleaseReceipt)
    private val rootReleaseReceipt = AtomicReference<DeliveryEndpointRootReleaseReceipt?>(null)
    private val entryClaimedDemand = DeliveryEndpointShutdownDemand.EntryClaimed(provenance, shutdownAction)
    private val shutdownDemand = AtomicReference<DeliveryEndpointShutdownDemand>(
        DeliveryEndpointShutdownDemand.NotRequested(provenance, shutdownAction),
    )

    internal fun terminationOutcome(): DeliveryEndpointTerminationOutcome {
        val receipt = endpoint.terminationReceipt
            ?: return DeliveryEndpointTerminationOutcome.Pending(provenance, shutdownAction.outcome)
        return if (endpoint.accepts(receipt)) {
            check(receipt === terminatedOutcome.receipt)
            terminatedOutcome
        } else {
            DeliveryEndpointTerminationOutcome.Pending(provenance, shutdownAction.outcome)
        }
    }

    internal fun releaseEndpointRoot(
        receipt: DeliveryTerminationReceipt,
    ): DeliveryEndpointRootSettlement {
        if (!endpoint.accepts(receipt)) {
            return DeliveryEndpointRootSettlement.Retained(provenance, terminationOutcome())
        }
        val existing = rootReleaseReceipt.get()
        if (existing != null) {
            check(existing === preparedRootReleaseReceipt)
            return if (existing.terminationReceipt === receipt) {
                preparedReleasedSettlement
            } else {
                DeliveryEndpointRootSettlement.Retained(provenance, terminationOutcome())
            }
        }
        if (endpoint.terminationReceipt !== receipt) {
            return DeliveryEndpointRootSettlement.Retained(provenance, terminationOutcome())
        }
        val release = preparedRootReleaseReceipt
        check(release.terminationReceipt === receipt)
        if (!owner.releaseExactEndpointRoot(this, receipt)) {
            return DeliveryEndpointRootSettlement.Retained(provenance, terminationOutcome())
        }
        check(rootReleaseReceipt.compareAndSet(null, release))
        owner.publishEndpointRootReleased(this, release)
        return preparedReleasedSettlement
    }

    internal fun requestExactShutdown(action: DeliveryEndpointShutdownAction): DeliveryShutdownDisposition {
        check(action === shutdownAction && action.provenance === provenance)
        return owner.performExactEndpointShutdown(this, action)
    }

    internal fun enterExactShutdown(
        action: DeliveryEndpointShutdownAction,
    ): DeliveryEndpointShutdownActionOutcome {
        check(action === shutdownAction && action.provenance === provenance)
        return owner.enterExactEndpointShutdown(this, action)
    }

    internal fun retainPendingShutdown(ticket: DeliveryTicket): DeliveryEndpointShutdownDemand {
        val current = shutdownDemand.get()
        return when (current) {
            is DeliveryEndpointShutdownDemand.NotRequested -> {
                val pending = DeliveryEndpointShutdownDemand.PendingTicket(provenance, shutdownAction, ticket)
                check(shutdownDemand.compareAndSet(current, pending))
                pending
            }

            is DeliveryEndpointShutdownDemand.PendingTicket -> {
                check(current.ticket === ticket)
                current
            }

            is DeliveryEndpointShutdownDemand.EntryClaimed -> current
        }
    }

    internal fun claimShutdownEntry(expectedRetiredTicket: DeliveryTicket? = null): Boolean {
        val current = shutdownDemand.get()
        when (current) {
            is DeliveryEndpointShutdownDemand.NotRequested -> check(expectedRetiredTicket == null)
            is DeliveryEndpointShutdownDemand.PendingTicket -> {
                check(expectedRetiredTicket == null || current.ticket === expectedRetiredTicket)
            }

            is DeliveryEndpointShutdownDemand.EntryClaimed -> return false
        }
        if (!shutdownAction.claimEntry()) return false
        check(shutdownDemand.compareAndSet(current, entryClaimedDemand))
        return true
    }

    internal fun claimPendingShutdownAfterRetirement(
        retiredTicket: DeliveryTicket,
    ): DeliveryEndpointShutdownAction? {
        val current = shutdownDemand.get() as? DeliveryEndpointShutdownDemand.PendingTicket ?: return null
        if (current.ticket !== retiredTicket) return null
        return shutdownAction.takeIf { claimShutdownEntry(retiredTicket) }
    }
}
