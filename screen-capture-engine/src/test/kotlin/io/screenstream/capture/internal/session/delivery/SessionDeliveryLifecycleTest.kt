package io.screenstream.capture.internal.session.delivery

import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.delivery.DeliveryCutoff
import io.screenstream.capture.internal.delivery.DeliveryHandoffToken
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import io.screenstream.capture.internal.storage.PublishedFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionDeliveryLifecycleTest {
    // Verification: DEL-02
    @Test
    fun oneRegistrationAndCachedFirstUseExactRegistrationIdentity() {
        val delivery = SessionDelivery()
        val first = acceptedRegistration(delivery)
        assertSame(SessionDelivery.RegistrationResult.Occupied, delivery.register { })

        val check = delivery.beginCachedFirstCheck() ?: error("cached-first check was not prepared")
        assertSame(first, check.registration)
        val prepared = delivery.settleCachedFirstCheck(check, frame(), isPhysicalHandoffFree = true)
        assertTrue(prepared is SessionDelivery.CachedFirstOffer.Prepared)
        val offer = (prepared as SessionDelivery.CachedFirstOffer.Prepared).offer
        assertSame(first, offer.registration)
        assertSame(SessionDelivery.AcceptedOfferSettlement.Retained, delivery.settleAcceptedOffer(offer))
        assertTrue(delivery.beginCachedFirstCheck() == null)
        assertSame(SessionDelivery.HandoffSettlement.RegistrationRetained, delivery.settleClosedHandoff(first.id))
    }

    // Verification: DEL-02
    @Test
    fun staleCachedFirstAndRegistrationIdentityCannotAffectSuccessor() {
        val delivery = SessionDelivery()
        val first = acceptedRegistration(delivery)
        val staleCheck = delivery.beginCachedFirstCheck() ?: error("cached-first check was not prepared")
        val completed = delivery.beginUnregister(first, requestCutoffImmediately = true) as SessionDelivery.UnregisterAction.Complete
        completed.settlement.complete()
        runTest { completed.waiter.awaitCompletion() }

        val successor = acceptedRegistration(delivery)
        assertTrue(successor.id > first.id)
        assertSame(
            SessionDelivery.CachedFirstOffer.Stale,
            delivery.settleCachedFirstCheck(staleCheck, frame(), isPhysicalHandoffFree = true),
        )
        assertSame(SessionDelivery.HandoffSettlement.Stale, delivery.settleClosedHandoff(first.id))
        val staleUnregister = delivery.beginUnregister(first, requestCutoffImmediately = true)
        assertTrue(staleUnregister is SessionDelivery.UnregisterAction.AwaitCompletion)
        runTest { staleUnregister.waiter.awaitCompletion() }
    }

    // Verification: DEL-02
    @Test
    fun noHandoffWaitsForOfferReturnAndSuccessorCutoffThenExactClosed() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)

        val firstCutoff = delivery.recordCutoffResult(registration, DeliveryCutoff.NoHandoff)
        assertRetained(firstCutoff)
        assertSame(SessionDelivery.AcceptedOfferSettlement.RequestCutoff, delivery.settleAcceptedOffer(offer))
        val successorCutoff = delivery.recordCutoffResult(registration, DeliveryCutoff.NoHandoff)
        assertRetained(successorCutoff)
        assertRetained(delivery.recordCutoffResult(registration, DeliveryCutoff.NoHandoff))

        val closed = delivery.settleClosedHandoff(registration.id)
        assertTrue(closed is SessionDelivery.HandoffSettlement.UnregisterCompleted)
        val settlement = (closed as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement
        runTest {
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { unregister.waiter.awaitCompletion() }
            assertFalse(awaiting.isCompleted)
            settlement.complete()
            awaiting.await()
        }
    }

    // Verification: DEL-02
    @Test
    fun cutoffBeforeEntryCompletesUnregisterWithoutPhysicalTaskRelease() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        freshOffer(delivery)
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)

        val result = delivery.recordCutoffResult(registration, DeliveryCutoff.CutoffBeforeEntry)
        val handoff = (result as SessionDelivery.CutoffSettlement.Handoff).settlement
        assertTrue(handoff is SessionDelivery.HandoffSettlement.UnregisterCompleted)
        (handoff as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement.complete()
        runTest { unregister.waiter.awaitCompletion() }
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)
    }

    // Verification: DEL-02
    @Test
    fun enteredCutoffWaitsForExactClosedHandoff() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        assertSame(SessionDelivery.AcceptedOfferSettlement.Retained, delivery.settleAcceptedOffer(offer))
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)
        assertRetained(delivery.recordCutoffResult(registration, DeliveryCutoff.Entered))

        runTest {
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { unregister.waiter.awaitCompletion() }
            assertFalse(awaiting.isCompleted)
            val closed = delivery.settleClosedHandoff(registration.id)
            assertTrue(closed is SessionDelivery.HandoffSettlement.UnregisterCompleted)
            (closed as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement.complete()
            awaiting.await()
        }
    }

    // Verification: DEL-02
    @Test
    fun offerReturnThatDidNotStartCompletesDeferredUnregister() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = false)
        assertTrue(unregister is SessionDelivery.UnregisterAction.AwaitCompletion)

        val handoff = delivery.settleOfferThatDidNotStart(offer)
        assertSame(SessionDelivery.HandoffSettlement.RegistrationRetained, handoff)
        runTest {
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { unregister.waiter.awaitCompletion() }
            runCurrent()
            assertFalse(awaiting.isCompleted)
            val pending = delivery.claimPendingUnregisterAction()
            assertTrue(pending is SessionDelivery.UnregisterAction.Complete)
            (pending as SessionDelivery.UnregisterAction.Complete).settlement.complete()
            awaiting.await()
        }
    }

    // Verification: DEL-02
    @Test
    fun pendingUnregisterWithoutOfferClaimsOneShotCompleteAndPermitsReplacement() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val deferred = delivery.beginUnregister(registration, requestCutoffImmediately = false)
        assertTrue(deferred is SessionDelivery.UnregisterAction.AwaitCompletion)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            deferred.waiter.awaitCompletion()
        }
        runCurrent()
        assertFalse(awaiting.isCompleted)

        val claimed = delivery.claimPendingUnregisterAction()
        assertTrue(claimed is SessionDelivery.UnregisterAction.Complete)
        assertTrue(delivery.claimPendingUnregisterAction() == null)
        runCurrent()
        assertFalse(awaiting.isCompleted)

        (claimed as SessionDelivery.UnregisterAction.Complete).settlement.complete()
        awaiting.await()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)
    }

    // Verification: DEL-02
    @Test
    fun pendingUnregisterWithOfferClaimsOneShotCutoffAndSettlesFromExactCutoff() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val deferred = delivery.beginUnregister(registration, requestCutoffImmediately = false)
        assertTrue(deferred is SessionDelivery.UnregisterAction.AwaitCompletion)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            deferred.waiter.awaitCompletion()
        }
        runCurrent()
        assertFalse(awaiting.isCompleted)

        val claimed = delivery.claimPendingUnregisterAction()
        assertTrue(claimed is SessionDelivery.UnregisterAction.RequestCutoff)
        assertSame(registration, (claimed as SessionDelivery.UnregisterAction.RequestCutoff).registration)
        assertTrue(delivery.claimPendingUnregisterAction() == null)
        runCurrent()
        assertFalse(awaiting.isCompleted)

        offer.completion.cutoffBeforeEntry(offer.handoff)
        runCurrent()
        assertFalse(awaiting.isCompleted)
        val cutoff = delivery.recordCutoffResult(registration, DeliveryCutoff.CutoffBeforeEntry)
        val handoff = (cutoff as SessionDelivery.CutoffSettlement.Handoff).settlement
        assertTrue(handoff is SessionDelivery.HandoffSettlement.UnregisterCompleted)
        runCurrent()
        assertFalse(awaiting.isCompleted)
        (handoff as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement.complete()
        awaiting.await()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)

        val terminalDelivery = SessionDelivery()
        val terminalRegistration = acceptedRegistration(terminalDelivery)
        val terminalOffer = freshOffer(terminalDelivery)
        terminalDelivery.closeAdmissionForTerminal()
        val terminal = terminalDelivery.prepareTerminal() ?: error("terminal preparation was not created")
        terminalDelivery.commitTerminal(terminal)
        terminalOffer.completion.cutoffBeforeEntry(terminalOffer.handoff)
        terminalDelivery.completeTerminalRegistration(terminal)
        terminalRegistration.waiter.awaitCompletion()
        assertTrue(terminalDelivery.register { } is SessionDelivery.RegistrationResult.Terminal)
    }

    // Verification: DEL-02
    @Test
    fun callbackProofBeforeCloseIsConsumedWhenCloseBecomesEligible() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        offer.completion.callbackReturned(offer.handoff)

        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.Complete)
        (unregister as SessionDelivery.UnregisterAction.Complete).settlement.complete()
        unregister.waiter.awaitCompletion()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)
    }

    // Verification: DEL-02
    @Test
    fun callbackProofDuringDeferredCloseIsConsumedAtPendingEligibility() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val deferred = delivery.beginUnregister(registration, requestCutoffImmediately = false)
        assertTrue(deferred is SessionDelivery.UnregisterAction.AwaitCompletion)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            deferred.waiter.awaitCompletion()
        }
        runCurrent()
        assertFalse(awaiting.isCompleted)
        offer.completion.callbackReturned(offer.handoff)
        runCurrent()
        assertFalse(awaiting.isCompleted)
        assertFalse(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)

        val claimed = delivery.claimPendingUnregisterAction()
        assertTrue(claimed is SessionDelivery.UnregisterAction.Complete)
        (claimed as SessionDelivery.UnregisterAction.Complete).settlement.complete()
        awaiting.await()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)
    }

    // Verification: DEL-02
    @Test
    fun terminalDetachLateAcceptedRequestsOnlyOneExactSuccessorCutoff() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)
        assertRetained(delivery.recordCutoffResult(registration, offer.handoff, DeliveryCutoff.NoHandoff))

        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal() ?: error("terminal preparation was not created")
        delivery.commitTerminal(terminal)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            registration.waiter.awaitCompletion()
        }
        runCurrent()
        assertFalse(awaiting.isCompleted)
        delivery.completeTerminalRegistration(terminal)
        runCurrent()
        assertFalse(awaiting.isCompleted)

        assertSame(
            SessionDelivery.AcceptedOfferSettlement.Stale,
            delivery.settleAcceptedOffer(offer, DeliveryHandoffToken(registration.id)),
        )
        runCurrent()
        assertFalse(awaiting.isCompleted)
        assertSame(
            SessionDelivery.AcceptedOfferSettlement.RequestCutoff,
            delivery.settleAcceptedOffer(offer, offer.handoff),
        )
        assertSame(
            SessionDelivery.AcceptedOfferSettlement.Stale,
            delivery.settleAcceptedOffer(offer, offer.handoff),
        )
        assertRetained(delivery.recordCutoffResult(registration, offer.handoff, DeliveryCutoff.NoHandoff))
        assertRetained(delivery.recordCutoffResult(registration, offer.handoff, DeliveryCutoff.NoHandoff))
        offer.completion.cutoffBeforeEntry(offer.handoff)
        awaiting.await()
        registration.waiter.awaitCompletion()
        assertSame(SessionDelivery.HandoffSettlement.Stale, delivery.settleClosedHandoff(registration.id))
        assertTrue(delivery.claimPendingUnregisterAction() == null)
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Terminal)
    }

    // Verification: DEL-02
    @Test
    fun terminalDetachLateDefiniteNoStartForwardsExactCompletion() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal() ?: error("terminal preparation was not created")
        delivery.commitTerminal(terminal)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            registration.waiter.awaitCompletion()
        }
        runCurrent()
        assertFalse(awaiting.isCompleted)
        delivery.completeTerminalRegistration(terminal)
        runCurrent()
        assertFalse(awaiting.isCompleted)

        assertSame(
            SessionDelivery.HandoffSettlement.Stale,
            delivery.settleOfferThatDidNotStart(offer, DeliveryHandoffToken(registration.id)),
        )
        runCurrent()
        assertFalse(awaiting.isCompleted)
        val late = delivery.settleOfferThatDidNotStart(offer, offer.handoff)
        assertTrue(late is SessionDelivery.HandoffSettlement.UnregisterCompleted)
        (late as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement.complete()
        awaiting.await()
        registration.waiter.awaitCompletion()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Terminal)
    }

    // Verification: DEL-02
    @Test
    fun heldDetachThenTerminalCompletionLeavesReleasedDetachStaleAndOneShot() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val detachEntered = CountDownLatch(1)
        val releaseDetach = CountDownLatch(1)
        val detachCalls = AtomicInteger()
        val callbackFailure = AtomicReference<Throwable?>()
        registration.installDetachAction { token ->
            detachCalls.incrementAndGet()
            detachEntered.countDown()
            check(releaseDetach.await(5L, TimeUnit.SECONDS)) { "detach action was not released" }
            delivery.detachAfterProof(registration, token)
        }

        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)
        val callback = Thread {
            try {
                offer.completion.callbackReturned(offer.handoff)
            } catch (failure: Throwable) {
                callbackFailure.set(failure)
            }
        }
        try {
            callback.start()
            assertTrue(detachEntered.await(5L, TimeUnit.SECONDS))

            delivery.closeAdmissionForTerminal()
            val terminal = delivery.prepareTerminal() ?: error("terminal preparation was not created")
            delivery.commitTerminal(terminal)
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
                registration.waiter.awaitCompletion()
            }
            runCurrent()
            assertFalse(awaiting.isCompleted)
            delivery.completeTerminalRegistration(terminal)
            runCurrent()
            awaiting.await()

            releaseDetach.countDown()
            callback.join(5_000L)
            assertFalse(callback.isAlive)
            callbackFailure.get()?.let { throw it }
            awaiting.await()
            registration.waiter.awaitCompletion()
            delivery.completeTerminalRegistration(terminal)
            assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Terminal)
            assertTrue(detachCalls.get() == 1)
            delivery.detachAfterProof(registration, offer.handoff)
            assertTrue(detachCalls.get() == 1)
        } finally {
            releaseDetach.countDown()
            callback.join(5_000L)
            check(!callback.isAlive) { "held detach callback did not join" }
            callbackFailure.get()?.let { throw it }
        }
    }

    // Verification: DEL-02
    @Test
    fun missingCachedFrameSkipsOneShotCachedFirstCheck() {
        val delivery = SessionDelivery()
        acceptedRegistration(delivery)
        val check = delivery.beginCachedFirstCheck() ?: error("cached-first check was not prepared")

        assertSame(
            SessionDelivery.CachedFirstOffer.Skipped,
            delivery.settleCachedFirstCheck(check, frame = null, isPhysicalHandoffFree = true),
        )
        assertTrue(delivery.beginCachedFirstCheck() == null)
    }

    // Verification: DEL-02
    @Test
    fun terminalStopSettlesExactWaiterSuccessfully() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal()
            ?: error("terminal preparation was not created")
        assertSame(registration, terminal.registration)
        assertTrue(delivery.isTerminalPreparationCurrent(terminal))
        delivery.commitTerminal(terminal)
        assertSame(SessionDelivery.RegistrationResult.Terminal, delivery.register { })
        checkNotNull(terminal.settlement).complete()

        runTest { registration.waiter.awaitCompletion() }
    }

    // Verification: DEL-02
    @Test
    fun callerCancellationLeavesAuthoritativeRegistrationForLaterSettlement() = runTest {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            registration.waiter.awaitCompletion()
        }
        assertFalse(caller.isCompleted)

        caller.cancel()
        val callerCancellationObserved = try {
            caller.await()
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(callerCancellationObserved)
        assertSame(SessionDelivery.RegistrationResult.Occupied, delivery.register { })

        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
        assertTrue(unregister is SessionDelivery.UnregisterAction.Complete)
        (unregister as SessionDelivery.UnregisterAction.Complete).settlement.complete()
        registration.waiter.awaitCompletion()
        assertTrue(delivery.register { } is SessionDelivery.RegistrationResult.Accepted)
    }

    // Verification: DEL-02
    @Test
    fun terminalFailureLeavesRegistrationCompletionIndependent() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal() ?: error("terminal preparation was not created")
        delivery.commitTerminal(terminal)
        checkNotNull(terminal.settlement).complete()

        runTest { registration.waiter.awaitCompletion() }
    }

    private fun acceptedRegistration(delivery: SessionDelivery): SessionDelivery.Registration {
        val result = delivery.register { }
        assertTrue(result is SessionDelivery.RegistrationResult.Accepted)
        return (result as SessionDelivery.RegistrationResult.Accepted).registration
    }

    private fun freshOffer(delivery: SessionDelivery): SessionDelivery.Offer {
        val result = delivery.prepareFreshOffer(frame(), isPhysicalHandoffFree = true)
        assertTrue(result is SessionDelivery.FreshOffer.Prepared)
        return (result as SessionDelivery.FreshOffer.Prepared).offer
    }

    private fun assertRetained(result: SessionDelivery.CutoffSettlement) {
        assertTrue(result is SessionDelivery.CutoffSettlement.Handoff)
        assertSame(
            SessionDelivery.HandoffSettlement.RegistrationRetained,
            (result as SessionDelivery.CutoffSettlement.Handoff).settlement,
        )
    }

    private companion object {
        private val EFFECTIVE_PARAMETERS = ScreenCaptureEffectiveParameters.create(
            appliedParameters = ScreenCaptureParameters.DEFAULT,
            captureGeometry = CaptureGeometry.create(widthPx = 2, heightPx = 2, densityDpi = 320),
            appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 2, bottomPx = 2),
            finalImageSize = ImageSize.create(widthPx = 2, heightPx = 2),
        )

        private fun frame(): PublishedFrame = PublishedFrame(
            payload = ImmutableEncodedPayload(arrayOf(byteArrayOf(1, 2, 3)), byteCount = 3),
            effectiveParameters = EFFECTIVE_PARAMETERS,
            sequence = 1L,
            timestampElapsedRealtimeNanos = 2L,
        )
    }
}
