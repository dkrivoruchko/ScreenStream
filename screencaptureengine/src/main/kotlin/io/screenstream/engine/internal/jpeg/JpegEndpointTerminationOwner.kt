package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import java.util.concurrent.atomic.AtomicReference

internal class JpegEndpointAdmissionIdentity internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
    internal val endpointIdentity: JpegEndpointIdentity,
)

internal class JpegTerminalModeLatch internal constructor(
    runtimeIdentity: JpegRuntimeIdentity,
    endpointIdentity: JpegEndpointIdentity,
) {
    internal val admissionIdentity: JpegEndpointAdmissionIdentity =
        JpegEndpointAdmissionIdentity(runtimeIdentity, endpointIdentity)
}

internal sealed interface JpegOwnerRootReadiness {
    val physicalDomainIdentity: JpegPhysicalDomainIdentity
    val runtimeIdentity: JpegRuntimeIdentity
    val endpointIdentity: JpegEndpointIdentity

    internal class PhysicalDomainRootsRetained internal constructor(
        override val physicalDomainIdentity: JpegPhysicalDomainIdentity,
        override val runtimeIdentity: JpegRuntimeIdentity,
        override val endpointIdentity: JpegEndpointIdentity,
    ) : JpegOwnerRootReadiness

    internal class PhysicalDomainRootsSettled internal constructor(
        override val physicalDomainIdentity: JpegPhysicalDomainIdentity,
        override val runtimeIdentity: JpegRuntimeIdentity,
        override val endpointIdentity: JpegEndpointIdentity,
        internal val emptyTopology: JpegRuntimeTopologySnapshot,
    ) : JpegOwnerRootReadiness
}

internal class JpegPhysicalDomainIdentity internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
    internal val endpointIdentity: JpegEndpointIdentity,
) {
    private val retained = JpegOwnerRootReadiness.PhysicalDomainRootsRetained(
        physicalDomainIdentity = this,
        runtimeIdentity = runtimeIdentity,
        endpointIdentity = endpointIdentity,
    )

    internal fun retained(): JpegOwnerRootReadiness.PhysicalDomainRootsRetained = retained

    internal fun settled(
        emptyTopology: JpegRuntimeTopologySnapshot,
    ): JpegOwnerRootReadiness.PhysicalDomainRootsSettled =
        JpegOwnerRootReadiness.PhysicalDomainRootsSettled(
            physicalDomainIdentity = this,
            runtimeIdentity = runtimeIdentity,
            endpointIdentity = endpointIdentity,
            emptyTopology = emptyTopology,
        )
}

internal enum class JpegEndpointShutdownActionState {
    Prepared,
    Entered,
    Returned,
    Thrown,
}

internal enum class JpegEndpointShutdownReturn {
    Requested,
    AlreadyRequested,
}

internal sealed interface JpegEndpointShutdownActionOutcome {
    val action: JpegEndpointShutdownAction

    internal class Entered internal constructor(
        override val action: JpegEndpointShutdownAction,
    ) : JpegEndpointShutdownActionOutcome

    internal class Returned internal constructor(
        override val action: JpegEndpointShutdownAction,
        internal val fact: JpegEndpointShutdownFact,
    ) : JpegEndpointShutdownActionOutcome

    internal class Thrown internal constructor(
        override val action: JpegEndpointShutdownAction,
        internal val fact: JpegEndpointShutdownFact,
    ) : JpegEndpointShutdownActionOutcome
}

internal sealed interface JpegEndpointShutdownEligibility {
    val endpointIdentity: JpegEndpointIdentity

    internal class WaitingForPhysicalDomainRoots internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val readiness: JpegOwnerRootReadiness.PhysicalDomainRootsRetained,
    ) : JpegEndpointShutdownEligibility

    internal class WaitingForEndpointTicketSettlement internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val ticket: JpegEndpointTicketFact?,
    ) : JpegEndpointShutdownEligibility

    internal class Eligible internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val action: JpegEndpointShutdownAction,
    ) : JpegEndpointShutdownEligibility

    internal class ActionAlreadyEntered internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val outcome: JpegEndpointShutdownActionOutcome,
    ) : JpegEndpointShutdownEligibility

    internal class EndpointAlreadyTerminated internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val outcome: JpegEndpointTerminationOutcome.Terminated,
    ) : JpegEndpointShutdownEligibility
}

internal class JpegEndpointTerminationReceipt private constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val construction: JpegEndpointConstructionFact,
    internal val rawReceipt: PrivateExecutorTerminationReceipt,
) {
    internal companion object {
        internal fun create(
            endpointIdentity: JpegEndpointIdentity,
            construction: JpegEndpointConstructionFact,
            rawReceipt: PrivateExecutorTerminationReceipt,
        ): JpegEndpointTerminationReceipt =
            JpegEndpointTerminationReceipt(endpointIdentity, construction, rawReceipt)
    }
}

internal sealed interface JpegEndpointTerminationOutcome {
    val endpointIdentity: JpegEndpointIdentity

    internal class Pending internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val shutdownOutcome: JpegEndpointShutdownActionOutcome?,
    ) : JpegEndpointTerminationOutcome

    internal class Terminated internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val receipt: JpegEndpointTerminationReceipt,
    ) : JpegEndpointTerminationOutcome
}

internal class JpegEndpointRootReleaseReceipt private constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val terminationReceipt: JpegEndpointTerminationReceipt,
) {
    internal companion object {
        internal fun create(
            endpointIdentity: JpegEndpointIdentity,
            terminationReceipt: JpegEndpointTerminationReceipt,
        ): JpegEndpointRootReleaseReceipt =
            JpegEndpointRootReleaseReceipt(endpointIdentity, terminationReceipt)
    }
}

internal sealed interface JpegEndpointRootSettlement {
    val endpointIdentity: JpegEndpointIdentity

    internal class Retained internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val terminationOutcome: JpegEndpointTerminationOutcome,
    ) : JpegEndpointRootSettlement

    internal class Released internal constructor(
        override val endpointIdentity: JpegEndpointIdentity,
        internal val receipt: JpegEndpointRootReleaseReceipt,
    ) : JpegEndpointRootSettlement
}

/**
 * Exact one-shot JPEG shutdown entry. Entered-without-return is retained as nonreturn evidence and supplies no
 * endpoint termination or physical JPEG-resource receipt.
 */
internal class JpegEndpointShutdownAction internal constructor(
    private val terminationOwner: JpegEndpointTerminationOwner,
) {
    private val actionState = AtomicReference(JpegEndpointShutdownActionState.Prepared)
    private val returnedOutcome = AtomicReference<JpegEndpointShutdownActionOutcome?>(null)
    internal val enteredOutcome: JpegEndpointShutdownActionOutcome.Entered =
        JpegEndpointShutdownActionOutcome.Entered(this)
    private lateinit var returnedOutcomeValue: JpegEndpointShutdownActionOutcome.Returned
    private lateinit var thrownOutcomeValue: JpegEndpointShutdownActionOutcome.Thrown

    internal val endpointIdentity: JpegEndpointIdentity
        get() = terminationOwner.endpointIdentity

    internal val state: JpegEndpointShutdownActionState
        get() = actionState.get()

    internal val outcome: JpegEndpointShutdownActionOutcome?
        get() = returnedOutcome.get()

    internal fun claimEntry(): Boolean = actionState.compareAndSet(
        JpegEndpointShutdownActionState.Prepared,
        JpegEndpointShutdownActionState.Entered,
    )

    internal fun performEnteredCall(): JpegEndpointShutdownActionOutcome {
        check(actionState.get() == JpegEndpointShutdownActionState.Entered)
        return try {
            val fact = terminationOwner.requestExactShutdown(this)
            val returned = returnedOutcomeValue
            check(returned.fact === fact)
            check(returnedOutcome.compareAndSet(null, returned))
            actionState.set(JpegEndpointShutdownActionState.Returned)
            returned
        } catch (raw: Throwable) {
            val fact = checkNotNull(terminationOwner.shutdownFact)
            check(fact.throwable === raw)
            val thrown = thrownOutcomeValue
            check(thrown.fact === fact)
            check(returnedOutcome.compareAndSet(null, thrown))
            actionState.set(JpegEndpointShutdownActionState.Thrown)
            throw raw
        }
    }

    internal fun bindFact(fact: JpegEndpointShutdownFact) {
        check(!::returnedOutcomeValue.isInitialized && fact.action === this)
        returnedOutcomeValue = JpegEndpointShutdownActionOutcome.Returned(this, fact)
        thrownOutcomeValue = JpegEndpointShutdownActionOutcome.Thrown(this, fact)
    }
}

/**
 * Owns only JPEG endpoint shutdown/termination/root release. Bitmap, carrier, transaction, native-call and lease
 * settlement stay with their physical owners and are never inferred from endpoint termination.
 */
internal class JpegEndpointTerminationOwner internal constructor(
    private val lane: JpegLaneOwner,
) {
    internal val endpointIdentity: JpegEndpointIdentity
        get() = lane.endpointIdentity
    internal val construction: JpegEndpointConstructionFact
        get() = lane.constructionFact
    internal val shutdownAction: JpegEndpointShutdownAction = JpegEndpointShutdownAction(this)
    private val preparedShutdownFact =
        JpegEndpointShutdownFact(endpointIdentity, shutdownAction)

    private val shutdownFactSlot = AtomicReference<JpegEndpointShutdownFact?>(null)
    private val terminationReceiptSlot = AtomicReference<JpegEndpointTerminationReceipt?>(null)
    private val rootReleaseSlot = AtomicReference<JpegEndpointRootReleaseReceipt?>(null)

    init {
        shutdownAction.bindFact(preparedShutdownFact)
    }

    internal val shutdownFact: JpegEndpointShutdownFact?
        get() = shutdownFactSlot.get()
    internal val isEndpointRootReleased: Boolean
        get() = rootReleaseSlot.get() != null

    internal fun shutdownEligibility(
        readiness: JpegOwnerRootReadiness,
    ): JpegEndpointShutdownEligibility {
        if (readiness is JpegOwnerRootReadiness.PhysicalDomainRootsRetained) {
            val observed = shutdownAction.outcome
            if (shutdownAction.state != JpegEndpointShutdownActionState.Prepared) {
                return JpegEndpointShutdownEligibility.ActionAlreadyEntered(
                    endpointIdentity,
                    observed ?: shutdownAction.enteredOutcome,
                )
            }
            return JpegEndpointShutdownEligibility.WaitingForPhysicalDomainRoots(endpointIdentity, readiness)
        }
        val termination = terminationOutcome()
        if (termination is JpegEndpointTerminationOutcome.Terminated) {
            return JpegEndpointShutdownEligibility.EndpointAlreadyTerminated(endpointIdentity, termination)
        }
        val observed = shutdownAction.outcome
        if (shutdownAction.state != JpegEndpointShutdownActionState.Prepared) {
            return JpegEndpointShutdownEligibility.ActionAlreadyEntered(
                endpointIdentity,
                observed ?: shutdownAction.enteredOutcome,
            )
        }
        if (!lane.mechanicallyIdle) {
            return JpegEndpointShutdownEligibility.WaitingForEndpointTicketSettlement(
                endpointIdentity,
                lane.currentTicketFact(),
            )
        }
        return JpegEndpointShutdownEligibility.Eligible(endpointIdentity, shutdownAction)
    }

    internal fun terminationOutcome(): JpegEndpointTerminationOutcome {
        val existing = terminationReceiptSlot.get()
        if (existing != null) return JpegEndpointTerminationOutcome.Terminated(endpointIdentity, existing)
        val raw = lane.rawTerminationReceipt
            ?: return JpegEndpointTerminationOutcome.Pending(endpointIdentity, shutdownAction.outcome)
        if (!lane.acceptsTerminationReceipt(raw)) {
            return JpegEndpointTerminationOutcome.Pending(endpointIdentity, shutdownAction.outcome)
        }
        val candidate = JpegEndpointTerminationReceipt.create(endpointIdentity, construction, raw)
        terminationReceiptSlot.compareAndSet(null, candidate)
        return JpegEndpointTerminationOutcome.Terminated(
            endpointIdentity,
            checkNotNull(terminationReceiptSlot.get()),
        )
    }

    internal fun typedReceiptFor(
        rawReceipt: PrivateExecutorTerminationReceipt,
    ): JpegEndpointTerminationReceipt? {
        val outcome = terminationOutcome() as? JpegEndpointTerminationOutcome.Terminated ?: return null
        return outcome.receipt.takeIf { it.rawReceipt === rawReceipt }
    }

    internal fun releaseEndpointRoot(
        receipt: JpegEndpointTerminationReceipt,
    ): JpegEndpointRootSettlement {
        val existing = rootReleaseSlot.get()
        if (existing != null) {
            return if (existing.terminationReceipt === receipt) {
                JpegEndpointRootSettlement.Released(endpointIdentity, existing)
            } else {
                JpegEndpointRootSettlement.Retained(endpointIdentity, terminationOutcome())
            }
        }
        if (receipt.endpointIdentity !== endpointIdentity || receipt.construction !== construction ||
            !lane.acceptsTerminationReceipt(receipt.rawReceipt) || lane.rawTerminationReceipt !== receipt.rawReceipt
        ) {
            return JpegEndpointRootSettlement.Retained(endpointIdentity, terminationOutcome())
        }
        val release = JpegEndpointRootReleaseReceipt.create(endpointIdentity, receipt)
        rootReleaseSlot.compareAndSet(null, release)
        return JpegEndpointRootSettlement.Released(endpointIdentity, checkNotNull(rootReleaseSlot.get()))
    }

    internal fun rootSettlement(): JpegEndpointRootSettlement =
        rootReleaseSlot.get()?.let { JpegEndpointRootSettlement.Released(endpointIdentity, it) }
            ?: JpegEndpointRootSettlement.Retained(endpointIdentity, terminationOutcome())

    internal fun requestExactShutdown(action: JpegEndpointShutdownAction): JpegEndpointShutdownFact {
        check(action === shutdownAction && action.endpointIdentity === endpointIdentity)
        return lane.performExactShutdown(action)
    }

    internal fun beginShutdownFact(action: JpegEndpointShutdownAction): JpegEndpointShutdownFact {
        check(action === shutdownAction && preparedShutdownFact.action === action)
        check(preparedShutdownFact.begin())
        check(shutdownFactSlot.compareAndSet(null, preparedShutdownFact))
        return preparedShutdownFact
    }
}
