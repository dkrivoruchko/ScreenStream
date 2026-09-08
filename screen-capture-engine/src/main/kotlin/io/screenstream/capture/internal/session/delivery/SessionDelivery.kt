package io.screenstream.capture.internal.session.delivery

import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.internal.delivery.DeliveryCutoff
import io.screenstream.capture.internal.delivery.DeliveryHandoffCompletion
import io.screenstream.capture.internal.delivery.DeliveryHandoffToken
import io.screenstream.capture.internal.storage.PublishedFrame
import kotlinx.coroutines.CompletableDeferred

/**
 * Exclusive semantic owner of consumer registration, cached-first eligibility, outstanding offers, unregister
 * waiters, and terminal registration settlement for one session.
 *
 * This owner never invokes application callbacks or accesses frame bytes; physical handoff belongs to
 * `DeliveryOwner`. Offer and cutoff results are evidence about one exact registration and handoff, not callback-return,
 * task-release, or physical-cleanup receipts. A matching `CutoffBeforeEntry` may nevertheless complete semantic
 * unregister. Outside terminal claim, only semantic completion of unregister removes the registration and permits a
 * replacement; terminal claim logically detaches it and forbids replacement without claiming any physical receipt.
 */
internal class SessionDelivery {
    internal sealed interface RegistrationResult {
        class Accepted(internal val registration: Registration) : RegistrationResult
        data object Occupied : RegistrationResult
        data object Terminal : RegistrationResult
        data object IdExhausted : RegistrationResult
    }

    internal class Registration(internal val id: Long, internal val waiter: RegistrationWaiter) : DeliveryHandoffCompletion {
        private val completionGate = Any()
        private var admissionToken: DeliveryHandoffToken? = null
        private var offerReturned = false
        private var offerAccepted = false
        private var cutoffState = CutoffState.None
        private var closeRequested = false
        private var detachEligible = false
        private var callbackSafe = false
        private var semanticDetached = false
        private var completionClaimed = false
        private var beginUnregisterAction: (suspend () -> Unit)? = null
        private var detachAction: ((DeliveryHandoffToken) -> Unit)? = null
        private var detachClaimed = false

        internal fun installBeginUnregisterAction(action: suspend () -> Unit) = synchronized(completionGate) {
            beginUnregisterAction = action
        }

        internal suspend fun awaitUnregister() {
            val action = synchronized(completionGate) { beginUnregisterAction }
            action?.invoke()
            waiter.awaitCompletion()
        }

        internal fun installDetachAction(action: (DeliveryHandoffToken) -> Unit) = synchronized(completionGate) {
            detachAction = action
        }

        internal fun markCloseRequested() = synchronized(completionGate) {
            closeRequested = true
        }

        internal fun markDetachEligible() = synchronized(completionGate) {
            detachEligible = true
        }

        internal fun reserveAdmission(token: DeliveryHandoffToken) = synchronized(completionGate) {
            check(admissionToken == null || callbackSafe)
            admissionToken = token
            offerReturned = false
            offerAccepted = false
            callbackSafe = false
            cutoffState = CutoffState.None
        }

        internal fun recordOfferReturned(token: DeliveryHandoffToken, accepted: Boolean): Boolean = synchronized(completionGate) {
            if (admissionToken !== token) return@synchronized false
            offerReturned = true
            offerAccepted = accepted
            if (!accepted) {
                callbackSafe = true
                return@synchronized false
            }
            if (cutoffState == CutoffState.AwaitingOfferReturn) {
                cutoffState = CutoffState.SuccessorCalling
                return@synchronized true
            }
            false
        }

        internal fun beginCutoff(token: DeliveryHandoffToken) = synchronized(completionGate) {
            if (admissionToken !== token) return@synchronized
            cutoffState = when (cutoffState) {
                CutoffState.None -> CutoffState.FirstCalling
                CutoffState.AwaitingOfferReturn -> CutoffState.SuccessorCalling
                else -> cutoffState
            }
        }

        internal fun recordCutoffResult(token: DeliveryHandoffToken, result: DeliveryCutoff): Boolean = synchronized(completionGate) {
            if (admissionToken !== token) return@synchronized false
            when (result) {
                DeliveryCutoff.NoHandoff -> when {
                    cutoffState == CutoffState.SuccessorCalling -> cutoffState = CutoffState.Effective
                    cutoffState == CutoffState.Effective -> Unit
                    offerAccepted -> {
                        cutoffState = CutoffState.SuccessorCalling
                        return@synchronized true
                    }

                    else -> cutoffState = CutoffState.AwaitingOfferReturn
                }

                DeliveryCutoff.CutoffBeforeEntry -> {
                    cutoffState = CutoffState.Effective
                    callbackSafe = true
                }

                DeliveryCutoff.Entered -> cutoffState = CutoffState.Effective
            }
            false
        }

        internal fun recordCallbackReturned(token: DeliveryHandoffToken) {
            var detach: ((DeliveryHandoffToken) -> Unit)? = null
            synchronized(completionGate) {
                if (admissionToken !== token) return
                callbackSafe = true
                if (closeRequested && detachEligible && !semanticDetached && !detachClaimed) {
                    detachClaimed = true
                    detach = detachAction
                }
            }
            detach?.invoke(token)
            completeIfReady()
        }

        internal fun recordCutoffBeforeEntry(token: DeliveryHandoffToken) {
            var detach: ((DeliveryHandoffToken) -> Unit)? = null
            synchronized(completionGate) {
                if (admissionToken !== token) return
                callbackSafe = true
                if (closeRequested && detachEligible && !semanticDetached && !detachClaimed) {
                    detachClaimed = true
                    detach = detachAction
                }
            }
            detach?.invoke(token)
            completeIfReady()
        }

        internal fun recordCallbackReturnedForSettlement(token: DeliveryHandoffToken) = synchronized(completionGate) {
            if (admissionToken === token) callbackSafe = true
        }

        internal fun acknowledgeSemanticDetach(): Boolean {
            synchronized(completionGate) {
                semanticDetached = true
            }
            return synchronized(completionGate) { canCompleteLocked() }
        }

        internal fun markNoOfferSafe() {
            synchronized(completionGate) {
                callbackSafe = true
            }
        }

        internal fun isCallbackSafe(): Boolean = synchronized(completionGate) { callbackSafe }

        internal fun isDetachEligible(): Boolean = synchronized(completionGate) { detachEligible }

        internal fun isSemanticDetached(): Boolean = synchronized(completionGate) { semanticDetached }

        internal fun admissionTokenForCompletion(): DeliveryHandoffToken? = synchronized(completionGate) { admissionToken }

        internal fun matchesAdmission(token: DeliveryHandoffToken): Boolean = synchronized(completionGate) {
            admissionToken === token
        }

        private fun canCompleteLocked(): Boolean = semanticDetached && callbackSafe && !completionClaimed

        internal fun completeIfReady(): Boolean {
            val shouldComplete = synchronized(completionGate) {
                if (!canCompleteLocked()) return@synchronized false
                completionClaimed = true
                beginUnregisterAction = null
                detachAction = null
                true
            }
            if (shouldComplete) waiter.complete()
            return shouldComplete
        }

        override fun callbackReturned(token: DeliveryHandoffToken) = recordCallbackReturned(token)

        override fun cutoffBeforeEntry(token: DeliveryHandoffToken) = recordCutoffBeforeEntry(token)
    }

    internal class RegistrationWaiter(private val completion: CompletableDeferred<Unit>) {
        internal suspend fun awaitCompletion() {
            completion.await()
        }

        internal fun complete() {
            completion.complete(Unit)
        }
    }

    internal class RegistrationSettlement private constructor(private val registration: Registration) {
        internal fun complete() = registration.completeIfReady()

        internal companion object {
            internal fun succeeded(registration: Registration): RegistrationSettlement =
                RegistrationSettlement(registration)
        }
    }

    internal sealed interface FreshOffer {
        data object NotAvailable : FreshOffer
        data object ConsumerBusy : FreshOffer
        class Prepared(internal val offer: Offer) : FreshOffer
    }

    internal class CachedFirstCheck(internal val registration: Registration)

    internal sealed interface CachedFirstOffer {
        data object Stale : CachedFirstOffer
        data object Skipped : CachedFirstOffer
        data object ConsumerBusy : CachedFirstOffer
        class Prepared(internal val offer: Offer) : CachedFirstOffer
    }

    internal class Offer private constructor(
        internal val registration: Registration,
        internal val handoff: DeliveryHandoffToken,
        internal val completion: DeliveryHandoffCompletion,
        internal val callback: (EncodedImageFrame) -> Unit,
        internal val frame: PublishedFrame,
    ) {
        internal companion object {
            internal fun create(
                registration: Registration,
                handoff: DeliveryHandoffToken,
                callback: (EncodedImageFrame) -> Unit,
                frame: PublishedFrame,
            ): Offer = Offer(registration, handoff, registration, callback, frame)
        }
    }

    internal sealed interface AcceptedOfferSettlement {
        data object Stale : AcceptedOfferSettlement
        data object Retained : AcceptedOfferSettlement
        data object RequestCutoff : AcceptedOfferSettlement
    }

    internal sealed interface HandoffSettlement {
        data object Stale : HandoffSettlement
        data object RegistrationRetained : HandoffSettlement
        class UnregisterCompleted(internal val settlement: RegistrationSettlement) : HandoffSettlement
    }

    internal sealed interface CutoffSettlement {
        class Handoff(internal val settlement: HandoffSettlement) : CutoffSettlement
        data object RequestSuccessor : CutoffSettlement
    }

    internal sealed interface UnregisterAction {
        val waiter: RegistrationWaiter

        class AwaitCompletion(override val waiter: RegistrationWaiter) : UnregisterAction
        class Complete(override val waiter: RegistrationWaiter, internal val settlement: RegistrationSettlement) : UnregisterAction
        class RequestCutoff(internal val registration: Registration, internal val token: DeliveryHandoffToken) : UnregisterAction {
            override val waiter: RegistrationWaiter get() = registration.waiter
        }
    }

    internal class TerminalPreparation(internal val registration: Registration?, internal val settlement: RegistrationSettlement?)

    private enum class RegistrationState { Open, Closing, TerminalPending, }

    private enum class TerminalPhase { Open, Pending, Claimed, }

    private enum class CutoffState { None, FirstCalling, AwaitingOfferReturn, SuccessorCalling, Effective, }

    private class RegistrationRecord(id: Long, callback: (EncodedImageFrame) -> Unit) {
        val completion = CompletableDeferred<Unit>()
        val registration = Registration(id, RegistrationWaiter(completion))
        var state = RegistrationState.Open
        var callback: ((EncodedImageFrame) -> Unit)? = callback
        var cachedFirstPending = true
        var offer: Offer? = null
        var offerAccepted = false
        var settlementIssued = false
        var cutoffState = CutoffState.None
    }

    private var nextRegistrationId = 0L
    private var registration: RegistrationRecord? = null
    private var terminalPhase = TerminalPhase.Open

    internal fun register(callback: (EncodedImageFrame) -> Unit): RegistrationResult {
        if (terminalPhase != TerminalPhase.Open) return RegistrationResult.Terminal
        if (registration != null) return RegistrationResult.Occupied
        if (nextRegistrationId == Long.MAX_VALUE) return RegistrationResult.IdExhausted
        val acceptedId = nextRegistrationId + 1L
        val accepted = RegistrationRecord(acceptedId, callback)
        val result = RegistrationResult.Accepted(accepted.registration)
        nextRegistrationId = acceptedId
        registration = accepted
        return result
    }

    internal fun detachAfterProof(registration: Registration, token: DeliveryHandoffToken) {
        val current = this.registration ?: return
        if ((current.registration !== registration) || (current.state != RegistrationState.Closing) || (current.offer?.handoff !== token)) return
        current.offer = null
        current.registration.acknowledgeSemanticDetach()
        current.settlementIssued = true
        this.registration = null
    }

    internal fun prepareFreshOffer(frame: PublishedFrame, isPhysicalHandoffFree: Boolean): FreshOffer {
        val current = registration ?: return FreshOffer.NotAvailable
        val callback = current.callback
        if ((current.state != RegistrationState.Open) || (callback == null)) return FreshOffer.NotAvailable
        if ((current.offer != null) || !isPhysicalHandoffFree) return FreshOffer.ConsumerBusy
        val handoff = DeliveryHandoffToken(current.registration.id)
        current.registration.reserveAdmission(handoff)
        val offer = Offer.create(current.registration, handoff, callback, frame)
        val prepared = FreshOffer.Prepared(offer)
        current.cachedFirstPending = false
        current.offerAccepted = false
        current.cutoffState = CutoffState.None
        current.offer = offer
        return prepared
    }

    internal fun beginCachedFirstCheck(): CachedFirstCheck? {
        val current = registration ?: return null
        if ((current.state != RegistrationState.Open) || !current.cachedFirstPending) return null
        return CachedFirstCheck(current.registration)
    }

    internal fun settleCachedFirstCheck(
        expected: CachedFirstCheck,
        frame: PublishedFrame?,
        isPhysicalHandoffFree: Boolean,
    ): CachedFirstOffer {
        val current = registration
        if ((current == null) || (expected.registration !== current.registration) || (current.state != RegistrationState.Open) || !current.cachedFirstPending) {
            return CachedFirstOffer.Stale
        }
        if (frame == null) {
            current.cachedFirstPending = false
            return CachedFirstOffer.Skipped
        }
        if ((current.offer != null) || !isPhysicalHandoffFree) {
            return CachedFirstOffer.ConsumerBusy
        }
        val callback = checkNotNull(current.callback)
        val handoff = DeliveryHandoffToken(current.registration.id)
        current.registration.reserveAdmission(handoff)
        val offer = Offer.create(current.registration, handoff, callback, frame)
        val prepared = CachedFirstOffer.Prepared(offer)
        current.cachedFirstPending = false
        current.offerAccepted = false
        current.cutoffState = CutoffState.None
        current.offer = offer
        return prepared
    }

    internal fun settleAcceptedOffer(expected: Offer): AcceptedOfferSettlement =
        settleAcceptedOffer(expected, expected.handoff)

    internal fun settleAcceptedOffer(expected: Offer, returnedToken: DeliveryHandoffToken): AcceptedOfferSettlement {
        if (returnedToken !== expected.handoff) return AcceptedOfferSettlement.Stale
        val durableRequestSuccessor = expected.registration.recordOfferReturned(expected.handoff, accepted = true)
        val current = registration
        if ((current == null) || (expected.registration !== current.registration) || (current.offer !== expected)) {
            return if (durableRequestSuccessor) AcceptedOfferSettlement.RequestCutoff else AcceptedOfferSettlement.Stale
        }
        current.offerAccepted = true
        if (current.state != RegistrationState.Closing) return AcceptedOfferSettlement.Retained
        if (!current.registration.isDetachEligible()) return AcceptedOfferSettlement.Retained
        return when (current.cutoffState) {
            CutoffState.None, CutoffState.AwaitingOfferReturn -> {
                current.cutoffState = if (current.cutoffState == CutoffState.None) {
                    CutoffState.FirstCalling
                } else {
                    CutoffState.SuccessorCalling
                }
                AcceptedOfferSettlement.RequestCutoff
            }

            CutoffState.FirstCalling, CutoffState.SuccessorCalling, CutoffState.Effective ->
                AcceptedOfferSettlement.Retained
        }
    }

    internal fun settleOfferThatDidNotStart(
        expected: Offer,
        returnedToken: DeliveryHandoffToken = expected.handoff,
    ): HandoffSettlement {
        if (returnedToken !== expected.handoff) return HandoffSettlement.Stale
        val current = registration
        if ((current == null) || (expected.registration !== current.registration) || (current.offer !== expected)) {
            if (expected.registration.matchesAdmission(expected.handoff)) {
                expected.registration.recordOfferReturned(expected.handoff, accepted = false)
                if (expected.registration.isSemanticDetached() &&
                    expected.registration.acknowledgeSemanticDetach()
                ) {
                    return HandoffSettlement.UnregisterCompleted(
                        RegistrationSettlement.succeeded(expected.registration),
                    )
                }
            }
            return HandoffSettlement.Stale
        }
        current.registration.recordOfferReturned(expected.handoff, accepted = false)
        return if (current.registration.isDetachEligible()) {
            settleHandoff(expected)
        } else {
            current.offer = null
            HandoffSettlement.RegistrationRetained
        }
    }

    internal fun settleClosedHandoff(registrationId: Long): HandoffSettlement {
        val current = registration ?: return HandoffSettlement.Stale
        val offer = current.offer ?: return HandoffSettlement.Stale
        if ((current.registration.id != registrationId) || (offer.registration !== current.registration)) return HandoffSettlement.Stale
        current.registration.recordCallbackReturnedForSettlement(offer.handoff)
        if (registration == null) {
            return HandoffSettlement.UnregisterCompleted(RegistrationSettlement.succeeded(current.registration))
        }
        return if (current.registration.isCallbackSafe() && current.registration.isDetachEligible()) {
            settleHandoff(offer)
        } else if (current.registration.isCallbackSafe()) {
            current.offer = null
            HandoffSettlement.RegistrationRetained
        } else {
            HandoffSettlement.RegistrationRetained
        }
    }

    internal fun beginUnregister(expected: Registration, requestCutoffImmediately: Boolean): UnregisterAction {
        val current = registration
        if ((current == null) || (current.registration !== expected) || (current.state != RegistrationState.Open)) {
            return UnregisterAction.AwaitCompletion(expected.waiter)
        }
        val offerOutstanding = current.offer != null
        current.registration.markCloseRequested()
        if (requestCutoffImmediately) current.registration.markDetachEligible()
        current.callback = null
        if (!requestCutoffImmediately) {
            current.state = RegistrationState.Closing
            return UnregisterAction.AwaitCompletion(expected.waiter)
        }
        if (offerOutstanding) {
            current.state = RegistrationState.Closing
            val offer = checkNotNull(current.offer)
            if (current.registration.isCallbackSafe()) {
                detachAfterProof(expected, offer.handoff)
                return UnregisterAction.Complete(
                    expected.waiter,
                    RegistrationSettlement.succeeded(expected),
                )
            }
            current.cutoffState = CutoffState.FirstCalling
            current.registration.beginCutoff(offer.handoff)
            return UnregisterAction.RequestCutoff(expected, offer.handoff)
        } else {
            check(!current.settlementIssued)
            current.registration.markNoOfferSafe()
            current.registration.acknowledgeSemanticDetach()
            registration = null
            current.settlementIssued = true
            return UnregisterAction.Complete(
                expected.waiter,
                RegistrationSettlement.succeeded(expected),
            )
        }
    }

    internal fun claimPendingUnregisterAction(): UnregisterAction? {
        val current = registration ?: return null
        if (current.state != RegistrationState.Closing) return null
        if (current.offer != null) {
            current.registration.markDetachEligible()
            if (current.registration.isCallbackSafe()) {
                val offer = current.offer
                checkNotNull(offer)
                detachAfterProof(current.registration, offer.handoff)
                return UnregisterAction.Complete(
                    current.registration.waiter,
                    RegistrationSettlement.succeeded(current.registration),
                )
            }
            if (current.cutoffState != CutoffState.None) return null
            current.cutoffState = CutoffState.FirstCalling
            current.registration.beginCutoff(current.offer!!.handoff)
            return UnregisterAction.RequestCutoff(current.registration, current.offer!!.handoff)
        }
        check(!current.settlementIssued)
        current.registration.markDetachEligible()
        val action = UnregisterAction.Complete(
            current.registration.waiter,
            RegistrationSettlement.succeeded(current.registration),
        )
        current.registration.markNoOfferSafe()
        current.registration.acknowledgeSemanticDetach()
        registration = null
        current.settlementIssued = true
        return action
    }

    internal fun recordCutoffResult(expected: Registration, result: DeliveryCutoff): CutoffSettlement {
        val token = expected.admissionTokenForCompletion() ?: return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        return recordCutoffResult(expected, token, result)
    }

    internal fun recordCutoffResult(expected: Registration, token: DeliveryHandoffToken, result: DeliveryCutoff): CutoffSettlement {
        val current = registration
        val offer = current?.offer
        if ((current != null) && (current.registration !== expected)) {
            return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        }
        if (current == null) {
            if (!expected.matchesAdmission(token)) return CutoffSettlement.Handoff(HandoffSettlement.Stale)
            val requested = expected.recordCutoffResult(token, result)
            return if (requested) CutoffSettlement.RequestSuccessor else CutoffSettlement.Handoff(HandoffSettlement.RegistrationRetained)
        }
        if ((offer == null) || (offer.handoff !== token)) return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        expected.recordCutoffResult(token, result)
        if ((current.state != RegistrationState.Closing) ||
            ((current.cutoffState != CutoffState.FirstCalling) &&
                    (current.cutoffState != CutoffState.SuccessorCalling) &&
                    !((result == DeliveryCutoff.NoHandoff) && (current.cutoffState == CutoffState.Effective)))
        ) {
            return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        }
        return when (result) {
            DeliveryCutoff.NoHandoff -> {
                when {
                    current.cutoffState == CutoffState.SuccessorCalling -> {
                        current.cutoffState = CutoffState.Effective
                        CutoffSettlement.Handoff(HandoffSettlement.RegistrationRetained)
                    }

                    current.cutoffState == CutoffState.Effective ->
                        CutoffSettlement.Handoff(HandoffSettlement.RegistrationRetained)

                    current.offerAccepted -> {
                        current.cutoffState = CutoffState.SuccessorCalling
                        CutoffSettlement.RequestSuccessor
                    }

                    else -> {
                        current.cutoffState = CutoffState.AwaitingOfferReturn
                        CutoffSettlement.Handoff(HandoffSettlement.RegistrationRetained)
                    }
                }
            }

            DeliveryCutoff.CutoffBeforeEntry -> {
                current.cutoffState = CutoffState.Effective
                CutoffSettlement.Handoff(settleHandoff(offer))
            }

            DeliveryCutoff.Entered -> {
                current.cutoffState = CutoffState.Effective
                CutoffSettlement.Handoff(HandoffSettlement.RegistrationRetained)
            }
        }
    }

    internal fun closeAdmissionForTerminal() {
        if (terminalPhase != TerminalPhase.Open) return
        terminalPhase = TerminalPhase.Pending
        val current = registration ?: return
        if ((current.state == RegistrationState.Open) || (current.state == RegistrationState.Closing)) {
            current.registration.markCloseRequested()
            current.state = RegistrationState.TerminalPending
        }
    }

    internal fun prepareTerminal(): TerminalPreparation? {
        if (terminalPhase != TerminalPhase.Pending) return null
        val current = registration
        check((current == null) || (current.state == RegistrationState.TerminalPending))
        val settlement = if ((current != null) && (current.offer == null)) {
            check(!current.settlementIssued)
            RegistrationSettlement.succeeded(current.registration)
        } else {
            null
        }
        return TerminalPreparation(registration = current?.registration, settlement = settlement)
    }

    internal fun isTerminalPreparationCurrent(expected: TerminalPreparation): Boolean {
        if ((terminalPhase != TerminalPhase.Pending) || (registration?.registration !== expected.registration)) return false
        val current = registration
        return (current == null) || ((current.state == RegistrationState.TerminalPending) && !current.settlementIssued)
    }

    internal fun commitTerminal(expected: TerminalPreparation) {
        check(isTerminalPreparationCurrent(expected))
        val current = registration
        check(terminalPhase == TerminalPhase.Pending)
        check(current?.registration === expected.registration)
        check((current == null) || (current.state == RegistrationState.TerminalPending))
        check((current == null) || !current.settlementIssued)
        check((expected.settlement == null) || (current != null))
        terminalPhase = TerminalPhase.Claimed
        if (current != null) {
            current.callback = null
            if (current.offer == null) current.registration.markNoOfferSafe()
            current.registration.acknowledgeSemanticDetach()
            current.offer = null
            current.settlementIssued = true
        }
        registration = null
    }

    internal fun completeTerminalRegistration(expected: TerminalPreparation) {
        expected.registration?.completeIfReady()
    }

    private fun settleHandoff(expected: Offer): HandoffSettlement {
        val current = registration
        if ((current == null) || (expected.registration !== current.registration) || (current.offer !== expected)) {
            return HandoffSettlement.Stale
        }
        if (current.state != RegistrationState.Closing) {
            current.offer = null
            return HandoffSettlement.RegistrationRetained
        }
        check(!current.settlementIssued)
        current.registration.acknowledgeSemanticDetach()
        val completed = HandoffSettlement.UnregisterCompleted(RegistrationSettlement.succeeded(current.registration))
        current.offer = null
        registration = null
        current.settlementIssued = true
        return completed
    }
}
