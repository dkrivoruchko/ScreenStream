package io.screenstream.capture.internal.session

import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.internal.delivery.DeliveryClosedStage
import io.screenstream.capture.internal.delivery.DeliveryCutoff
import io.screenstream.capture.internal.delivery.DeliveryFact
import io.screenstream.capture.internal.delivery.DeliveryFactSink
import io.screenstream.capture.internal.delivery.DeliveryHandoffToken
import io.screenstream.capture.internal.delivery.DeliveryOffer
import io.screenstream.capture.internal.delivery.DeliveryOwner
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.storage.PublishedFrame

/**
 * Correlates one delivery offer with facts carrying its pre-minted handoff-token identity.
 *
 * It rejects stale, duplicate, and mismatched facts but owns neither callback registration policy
 * nor their session meaning. A `Closed` fact is staged before becoming consumable so Delivery can clear the exact
 * physical `current` before the coordinator may settle the semantic handoff.
 */
internal class SessionDeliveryLink(
    private val coordinator: SessionCoordinator,
    workerDispatcher: NonInlineDispatcher,
) {
    internal class OfferRequest(
        internal val handoff: DeliveryHandoffToken,
        internal val callback: (EncodedImageFrame) -> Unit,
        internal val frame: PublishedFrame,
    )

    internal enum class FactAdmission { Recorded, Duplicate, Mismatch, Stale, }

    private enum class FactPhase { Open, CallbackFailureSeen, ClosedSeen, }

    private enum class ClosedPhase { Empty, Staged, Ready, }

    private val owner = DeliveryOwner(
        workerDispatcher,
        object : DeliveryFactSink {
            override fun offer(fact: DeliveryFact) = coordinator.onDeliveryFact(fact)

            override fun stageClosed(fact: DeliveryFact.Closed): DeliveryClosedStage = coordinator.stageDeliveryClosed(fact)
        },
    )
    private var pendingOffer: OfferRequest? = null
    private var handoff: DeliveryHandoffToken? = null
    private var callbackFailureFact: DeliveryFact.CallbackFailure? = null
    private var closedFact: DeliveryFact.Closed? = null
    private var closedPhase = ClosedPhase.Empty
    private var factPhase = FactPhase.Open
    private var terminalFrozen = false

    internal fun prepareOfferLocked(
        registrationId: Long,
        callback: (EncodedImageFrame) -> Unit,
        frame: PublishedFrame,
    ): OfferRequest {
        check(!terminalFrozen)
        check((factPhase == FactPhase.Open) && (pendingOffer == null) && (handoff == null) && (callbackFailureFact == null) && (closedFact == null))
        val token = DeliveryHandoffToken(registrationId)
        return OfferRequest(token, callback, frame).also {
            pendingOffer = it
            handoff = token
        }
    }

    internal fun executeOffer(request: OfferRequest): DeliveryOffer =
        owner.offer(request.handoff, request.callback, request.frame)

    internal fun recordOfferReturnedLocked(request: OfferRequest, result: DeliveryOffer): Boolean {
        val expected = request.handoff
        if ((pendingOffer !== request) || (handoff !== expected)) return false
        return when (result) {
            is DeliveryOffer.Accepted -> {
                if (result.handoff !== expected) return false
                pendingOffer = null
                true
            }

            is DeliveryOffer.Rejected -> {
                if (result.handoff !== expected) return false
                pendingOffer = null
                handoff = null
                true
            }

            DeliveryOffer.Occupied, DeliveryOffer.Cutoff -> {
                pendingOffer = null
                handoff = null
                true
            }
        }
    }

    internal fun recordFactLocked(fact: DeliveryFact): FactAdmission {
        val exact = handoff ?: return FactAdmission.Stale
        if (fact.handoff.registrationId < exact.registrationId) return FactAdmission.Stale
        if (fact.handoff.registrationId > exact.registrationId) return FactAdmission.Mismatch
        if (fact.handoff !== exact) return FactAdmission.Mismatch
        return when (factPhase) {
            FactPhase.Open -> when (fact) {
                is DeliveryFact.CallbackFailure -> {
                    callbackFailureFact = fact
                    factPhase = FactPhase.CallbackFailureSeen
                    FactAdmission.Recorded
                }

                is DeliveryFact.Closed -> {
                    closedFact = fact
                    closedPhase = ClosedPhase.Staged
                    factPhase = FactPhase.ClosedSeen
                    FactAdmission.Recorded
                }
            }

            FactPhase.CallbackFailureSeen -> when (fact) {
                is DeliveryFact.CallbackFailure -> FactAdmission.Duplicate
                is DeliveryFact.Closed -> {
                    closedFact = fact
                    closedPhase = ClosedPhase.Staged
                    factPhase = FactPhase.ClosedSeen
                    FactAdmission.Recorded
                }
            }

            FactPhase.ClosedSeen -> when (fact) {
                is DeliveryFact.CallbackFailure -> FactAdmission.Mismatch
                is DeliveryFact.Closed -> FactAdmission.Duplicate
            }
        }
    }

    internal fun takeCallbackFailureLocked(): DeliveryFact.CallbackFailure? = callbackFailureFact.also { callbackFailureFact = null }

    internal fun takeClosedLocked(): DeliveryFact.Closed? {
        if (terminalFrozen || closedPhase != ClosedPhase.Ready) return null
        closedPhase = ClosedPhase.Empty
        return closedFact.also { closedFact = null }
    }

    internal fun markClosedReadyLocked(expected: DeliveryFact.Closed): Boolean {
        if (closedFact !== expected || closedPhase != ClosedPhase.Staged) return false
        closedPhase = ClosedPhase.Ready
        return true
    }

    internal fun currentHandoffLocked(): DeliveryHandoffToken? = handoff

    internal fun hasPendingOfferLocked(): Boolean = pendingOffer != null

    internal fun clearHandoffLocked(expected: DeliveryHandoffToken): Boolean {
        if (handoff !== expected || callbackFailureFact != null || closedFact != null) return false
        handoff = null
        pendingOffer = null
        factPhase = FactPhase.Open
        return true
    }

    internal fun freezeTerminalLocked() {
        check(callbackFailureFact == null && closedPhase != ClosedPhase.Ready)
        pendingOffer = null
        handoff = null
        factPhase = FactPhase.Open
        closedFact = null
        closedPhase = ClosedPhase.Empty
        terminalFrozen = true
    }

    internal fun executeCutoff(registrationId: Long): DeliveryCutoff = owner.cutoff(registrationId)

    internal fun isEnteredCallbackThread(registrationId: Long): Boolean = owner.isEnteredCallbackThread(registrationId)

    internal fun executeTerminalEntryFence() {
        owner.retire()
    }
}
