package io.screenstream.capture.internal.session.delivery

import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.delivery.DeliveryCutoff
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import io.screenstream.capture.internal.storage.PublishedFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

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

    // Verification: DEL-01
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

    // Verification: DEL-01
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

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun offerReturnThatDidNotStartCompletesDeferredUnregister() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val offer = freshOffer(delivery)
        val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = false)
        assertTrue(unregister is SessionDelivery.UnregisterAction.AwaitCompletion)

        val handoff = delivery.settleOfferThatDidNotStart(offer)
        assertTrue(handoff is SessionDelivery.HandoffSettlement.UnregisterCompleted)
        (handoff as SessionDelivery.HandoffSettlement.UnregisterCompleted).settlement.complete()
        runTest { unregister.waiter.awaitCompletion() }
    }

    // Verification: DEL-02
    @Test
    fun terminalStopSettlesExactWaiterAndCaughtCancellationCompletesNormally() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal(SessionDelivery.TerminalOutcome.Stopped)
            ?: error("terminal preparation was not created")
        assertSame(registration, terminal.registration)
        assertTrue(delivery.isTerminalPreparationCurrent(terminal))
        delivery.commitTerminal(terminal)
        assertSame(SessionDelivery.RegistrationResult.Terminal, delivery.register { })
        checkNotNull(terminal.settlement).complete()

        runTest {
            val terminalCancellationCaught = async {
                try {
                    registration.waiter.awaitCompletion()
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(terminalCancellationCaught.await())
        }
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
    fun terminalFailureSettlesExactWaiterWithAuthoritativeProblem() {
        val delivery = SessionDelivery()
        val registration = acceptedRegistration(delivery)
        val cause = IllegalStateException("terminal")
        delivery.closeAdmissionForTerminal()
        val terminal = delivery.prepareTerminal(
            SessionDelivery.TerminalOutcome.Failed(ScreenCaptureProblem.ResourceExhausted, cause),
        ) ?: error("terminal preparation was not created")
        delivery.commitTerminal(terminal)
        checkNotNull(terminal.settlement).complete()

        runTest {
            try {
                registration.waiter.awaitCompletion()
                fail("terminal failure completed unregister successfully")
            } catch (failure: ScreenCaptureException) {
                assertSame(ScreenCaptureProblem.ResourceExhausted, failure.problem)
            }
        }
    }

    // Verification: DEL-02
    @Test
    fun terminalPreparationDirectlyCarriesNullWithoutRegistration() {
        val delivery = SessionDelivery()
        delivery.closeAdmissionForTerminal()

        val terminal = delivery.prepareTerminal(SessionDelivery.TerminalOutcome.Stopped)
            ?: error("terminal preparation was not created")

        assertTrue(terminal.registration == null)
        assertTrue(terminal.settlement == null)
        assertTrue(delivery.isTerminalPreparationCurrent(terminal))
        delivery.commitTerminal(terminal)
        assertSame(SessionDelivery.RegistrationResult.Terminal, delivery.register { })
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
