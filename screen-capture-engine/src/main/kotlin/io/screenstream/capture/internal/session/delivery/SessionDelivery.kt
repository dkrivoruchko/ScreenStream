package io.screenstream.capture.internal.session.delivery

import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.delivery.DeliveryCutoff
import io.screenstream.capture.internal.storage.PublishedFrame
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CancellationException

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

    internal class Registration(internal val id: Long, internal val waiter: RegistrationWaiter)

    internal class RegistrationWaiter(private val completion: CompletableDeferred<Unit>) {
        internal suspend fun awaitCompletion() {
            completion.await()
        }
    }

    internal class RegistrationSettlement private constructor(
        private val completion: CompletableDeferred<Unit>,
        private val outcome: Outcome,
    ) {
        private sealed interface Outcome {
            data object Succeeded : Outcome
            data object Stopped : Outcome
            class Failed(val failure: ScreenCaptureException) : Outcome
        }

        internal fun complete() {
            when (val selectedOutcome = outcome) {
                Outcome.Succeeded -> completion.complete(Unit)
                is Outcome.Failed -> completion.completeExceptionally(selectedOutcome.failure)
                Outcome.Stopped -> completion.completeExceptionally(CancellationException("Session stopped"))
            }
        }

        internal companion object {
            internal fun succeeded(completion: CompletableDeferred<Unit>): RegistrationSettlement =
                RegistrationSettlement(completion, Outcome.Succeeded)

            internal fun terminal(completion: CompletableDeferred<Unit>, outcome: TerminalOutcome): RegistrationSettlement {
                val settlementOutcome = when (outcome) {
                    TerminalOutcome.Stopped -> Outcome.Stopped
                    is TerminalOutcome.Failed -> Outcome.Failed(ScreenCaptureException.create(outcome.problem, outcome.cause))
                }
                return RegistrationSettlement(completion, settlementOutcome)
            }
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
        internal val callback: (EncodedImageFrame) -> Unit,
        internal val frame: PublishedFrame,
    ) {
        internal companion object {
            internal fun create(registration: Registration, callback: (EncodedImageFrame) -> Unit, frame: PublishedFrame): Offer =
                Offer(registration, callback, frame)
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
        class RequestCutoff(internal val registration: Registration) : UnregisterAction {
            override val waiter: RegistrationWaiter get() = registration.waiter
        }
    }

    internal sealed interface TerminalOutcome {
        data object Stopped : TerminalOutcome
        class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable?) : TerminalOutcome
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

    internal fun prepareFreshOffer(frame: PublishedFrame, isPhysicalHandoffFree: Boolean): FreshOffer {
        val current = registration ?: return FreshOffer.NotAvailable
        val callback = current.callback
        if ((current.state != RegistrationState.Open) || (callback == null)) return FreshOffer.NotAvailable
        if ((current.offer != null) || !isPhysicalHandoffFree) return FreshOffer.ConsumerBusy
        val offer = Offer.create(current.registration, callback, frame)
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
            current.cachedFirstPending = false
            return CachedFirstOffer.ConsumerBusy
        }
        val callback = checkNotNull(current.callback)
        val offer = Offer.create(current.registration, callback, frame)
        val prepared = CachedFirstOffer.Prepared(offer)
        current.cachedFirstPending = false
        current.offerAccepted = false
        current.cutoffState = CutoffState.None
        current.offer = offer
        return prepared
    }

    internal fun settleAcceptedOffer(expected: Offer): AcceptedOfferSettlement {
        val current = registration
        if ((current == null) || (expected.registration !== current.registration) || (current.offer !== expected)) {
            return AcceptedOfferSettlement.Stale
        }
        current.offerAccepted = true
        if (current.state != RegistrationState.Closing) return AcceptedOfferSettlement.Retained
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

    internal fun settleOfferThatDidNotStart(expected: Offer): HandoffSettlement = settleHandoff(expected)

    internal fun settleClosedHandoff(registrationId: Long): HandoffSettlement {
        val current = registration ?: return HandoffSettlement.Stale
        val offer = current.offer ?: return HandoffSettlement.Stale
        if ((current.registration.id != registrationId) || (offer.registration !== current.registration)) return HandoffSettlement.Stale
        return settleHandoff(offer)
    }

    internal fun beginUnregister(expected: Registration, requestCutoffImmediately: Boolean): UnregisterAction {
        val current = registration
        if ((current == null) || (current.registration !== expected) || (current.state != RegistrationState.Open)) {
            return UnregisterAction.AwaitCompletion(expected.waiter)
        }
        val offerOutstanding = current.offer != null
        val action = if (!requestCutoffImmediately) {
            UnregisterAction.AwaitCompletion(expected.waiter)
        } else if (offerOutstanding) {
            current.cutoffState = CutoffState.FirstCalling
            UnregisterAction.RequestCutoff(expected)
        } else {
            check(!current.settlementIssued)
            UnregisterAction.Complete(expected.waiter, RegistrationSettlement.succeeded(current.completion))
        }
        current.callback = null
        if (!requestCutoffImmediately || offerOutstanding) {
            current.state = RegistrationState.Closing
        } else {
            registration = null
            current.settlementIssued = true
        }
        return action
    }

    internal fun claimPendingUnregisterAction(): UnregisterAction? {
        val current = registration ?: return null
        if (current.state != RegistrationState.Closing) return null
        if (current.offer != null) {
            if (current.cutoffState != CutoffState.None) return null
            current.cutoffState = CutoffState.FirstCalling
            return UnregisterAction.RequestCutoff(current.registration)
        }
        check(!current.settlementIssued)
        val action = UnregisterAction.Complete(
            current.registration.waiter,
            RegistrationSettlement.succeeded(current.completion),
        )
        registration = null
        current.settlementIssued = true
        return action
    }

    internal fun recordCutoffResult(expected: Registration, result: DeliveryCutoff): CutoffSettlement {
        val current = registration
        if ((current == null) || (current.registration !== expected)) {
            return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        }
        val offer = current.offer ?: return CutoffSettlement.Handoff(HandoffSettlement.Stale)
        if ((current.state != RegistrationState.Closing) ||
            ((current.cutoffState != CutoffState.FirstCalling) && (current.cutoffState != CutoffState.SuccessorCalling))
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
            current.state = RegistrationState.TerminalPending
        }
    }

    internal fun prepareTerminal(outcome: TerminalOutcome): TerminalPreparation? {
        if (terminalPhase != TerminalPhase.Pending) return null
        val current = registration
        check((current == null) || (current.state == RegistrationState.TerminalPending))
        val settlement = if (current != null) {
            check(!current.settlementIssued)
            RegistrationSettlement.terminal(current.completion, outcome)
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
        check((expected.settlement != null) == (current != null))
        terminalPhase = TerminalPhase.Claimed
        if (current != null) {
            current.callback = null
            current.offer = null
            current.settlementIssued = true
        }
        registration = null
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
        val completed = HandoffSettlement.UnregisterCompleted(RegistrationSettlement.succeeded(current.completion))
        current.offer = null
        registration = null
        current.settlementIssued = true
        return completed
    }
}
