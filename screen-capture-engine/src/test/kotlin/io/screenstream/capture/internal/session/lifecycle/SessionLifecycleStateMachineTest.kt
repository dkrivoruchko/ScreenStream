package io.screenstream.capture.internal.session.lifecycle

import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureProblem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionLifecycleStateMachineTest {
    // Verification: API-03
    @Test
    fun sameInstanceRejectsSecondStartWithoutReplacingFirstDeadline() {
        val lifecycle = SessionLifecycle()
        val firstDeadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

        assertThrows(IllegalStateException::class.java) {
            lifecycle.acceptStart(acceptedStartNanos = 30L, deadlineNanos = 40L)
        }

        assertSame(firstDeadline, lifecycle.startupDeadlineCandidate)
    }

    // Verification: SES-01
    @Test
    fun firstActiveIsBlockedUntilBothBootstrapFactsAreRecorded() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

        assertSame(SessionLifecycle.BootstrapFactResult.Recorded, lifecycle.recordBootstrapWorkerAccepted())
        assertFalse(lifecycle.activePublicationMayProceed(first = true))
        assertSame(
            SessionLifecycle.ActiveReservation.Stale,
            lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L),
        )

        assertSame(SessionLifecycle.BootstrapFactResult.Ready, lifecycle.recordFirstControlPostAccepted())
        assertTrue(lifecycle.activePublicationMayProceed(first = true))
        assertTrue(
            lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L) is
                    SessionLifecycle.ActiveReservation.Reserved,
        )
    }

    // Verification: SES-01
    @Test
    fun bootstrapRequiresBothNormalTrueFactsInEitherOrder() {
        val workerFirst = SessionLifecycle()
        workerFirst.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

        assertSame(SessionLifecycle.BootstrapFactResult.Recorded, workerFirst.recordBootstrapWorkerAccepted())
        assertFalse(workerFirst.bootstrapReady)
        assertSame(SessionLifecycle.BootstrapFactResult.Ready, workerFirst.recordFirstControlPostAccepted())
        assertTrue(workerFirst.bootstrapReady)
        assertSame(SessionLifecycle.BootstrapFactResult.Stale, workerFirst.recordBootstrapWorkerAccepted())

        val postFirst = SessionLifecycle()
        postFirst.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

        assertSame(SessionLifecycle.BootstrapFactResult.Recorded, postFirst.recordFirstControlPostAccepted())
        assertSame(SessionLifecycle.BootstrapFactResult.Ready, postFirst.recordBootstrapWorkerAccepted())
        assertTrue(postFirst.bootstrapReady)
    }

    // Verification: SES-01
    @Test
    fun startupDeadlineIsStrictAndRejectsRegressedClock() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

        assertSame(SessionLifecycle.StartupCheck.ClockRegressed, lifecycle.checkStartupDeadline(deadline, 9L))
        assertSame(SessionLifecycle.StartupCheck.Eligible, lifecycle.checkStartupDeadline(deadline, 19L))
        assertSame(SessionLifecycle.StartupCheck.DeadlineExpired, lifecycle.checkStartupDeadline(deadline, 20L))
        assertSame(SessionLifecycle.StartupCheck.Stale, lifecycle.checkStartupDeadline(deadline, 19L))
    }

    // Verification: SES-01
    @Test
    fun failedPostAssignmentCheckKeepsStartPendingUntilLaterActiveSettlement() = runTest {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        lifecycle.recordBootstrapWorkerAccepted()
        lifecycle.recordFirstControlPostAccepted()

        val firstReservation = lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L)
        assertTrue(firstReservation is SessionLifecycle.ActiveReservation.Reserved)
        val firstToken = (firstReservation as SessionLifecycle.ActiveReservation.Reserved).token

        assertNull(lifecycle.settleActive(firstToken, canContinueOrdinaryWork = false))
        assertFalse(lifecycle.canRunProduction(sessionReady = true, revisionCurrent = true))
        assertTrue(lifecycle.activePublicationMayProceed(first = false))

        val successor = lifecycle.reserveActive(first = false, expectedDeadline = null, sampledNanos = null)
        assertTrue(successor is SessionLifecycle.ActiveReservation.Reserved)
        val successorToken = (successor as SessionLifecycle.ActiveReservation.Reserved).token
        val settlement = lifecycle.settleActive(successorToken, canContinueOrdinaryWork = true)

        assertTrue(lifecycle.canRunProduction(sessionReady = true, revisionCurrent = true))
        settlement ?: error("successful successor Active did not settle start")
        settlement.complete()
        lifecycle.startWaiter.awaitCompletion()
    }

    // Verification: SES-02
    @Test
    fun activeReservedBeforeTerminalOfferIsSettledWithoutOrdinaryContinuation() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        lifecycle.recordBootstrapWorkerAccepted()
        lifecycle.recordFirstControlPostAccepted()
        val reserved = lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L)
                as SessionLifecycle.ActiveReservation.Reserved

        val offer = lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)

        assertTrue((offer as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
        assertFalse(lifecycle.activePublicationMayProceed(first = false))
        assertSame(
            SessionLifecycle.ActiveReservation.Stale,
            lifecycle.reserveActive(first = false, expectedDeadline = null, sampledNanos = null),
        )
        assertNull(lifecycle.settleActive(reserved.token, canContinueOrdinaryWork = false))
        assertFalse(lifecycle.canRunProduction(sessionReady = true, revisionCurrent = true))
        val preparation = lifecycle.prepareTerminal() ?: error("terminal contender was not prepared")
        assertTrue(lifecycle.isTerminalPreparationCurrent(preparation))
        lifecycle.commitTerminal(preparation)
    }

    // Verification: SES-02
    @Test
    fun terminalOfferBeforeActiveReservationMakesThePreparedStartupEvidenceStale() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        lifecycle.recordBootstrapWorkerAccepted()
        lifecycle.recordFirstControlPostAccepted()

        lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)

        assertFalse(lifecycle.activePublicationMayProceed(first = true))
        assertSame(
            SessionLifecycle.ActiveReservation.Stale,
            lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L),
        )
        assertSame(SessionLifecycle.StartupCheck.Stale, lifecycle.checkStartupDeadline(deadline, sampledNanos = 19L))
    }

    // Verification: SES-02
    @Test
    fun higherPriorityTerminalOffersInvalidateOlderPreparations() = runTest {
        val lifecycle = SessionLifecycle()
        lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        lifecycle.offerTerminal(
            SessionLifecycle.TerminalDecision.Failed(
                ScreenCaptureProblem.InvalidRequest,
                IllegalStateException("superseded failure"),
            ),
        )
        val failedPreparation = lifecycle.prepareTerminal() ?: error("failure contender was not prepared")

        val requestedOffer = lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)
        val requestedPreparation = lifecycle.prepareTerminal() ?: error("requested contender was not prepared")

        assertFalse((requestedOffer as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
        assertFalse(lifecycle.isTerminalPreparationCurrent(failedPreparation))
        assertThrows(IllegalStateException::class.java) { lifecycle.commitTerminal(failedPreparation) }
        assertSame(SessionLifecycle.TerminalDecision.Requested, requestedPreparation.decision)
        assertTrue(lifecycle.isTerminalPreparationCurrent(requestedPreparation))

        val projectionOffer = lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped)
        val projectionPreparation = lifecycle.prepareTerminal() ?: error("projection contender was not prepared")

        assertFalse((projectionOffer as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
        assertFalse(lifecycle.isTerminalPreparationCurrent(requestedPreparation))
        assertThrows(IllegalStateException::class.java) { lifecycle.commitTerminal(requestedPreparation) }
        assertSame(SessionLifecycle.TerminalDecision.ProjectionStopped, projectionPreparation.decision)
        assertTrue(lifecycle.isTerminalPreparationCurrent(projectionPreparation))

        lifecycle.commitTerminal(projectionPreparation)

        checkNotNull(projectionPreparation.startSettlement).complete()
        assertStartFails(lifecycle, ScreenCaptureProblem.CaptureUnavailable)
    }

    // Verification: SES-02
    @Test
    fun terminalOffersKeepHighestPriorityDecision() {
        val failedLifecycle = SessionLifecycle()
        val firstFailure = SessionLifecycle.TerminalDecision.Failed(
            ScreenCaptureProblem.InvalidRequest,
            IllegalStateException("first failure"),
        )
        assertTrue(
            (failedLifecycle.offerTerminal(firstFailure) as SessionLifecycle.TerminalOffer.Accepted)
                .closesOrdinaryAdmission,
        )
        val failedPreparation = failedLifecycle.prepareTerminal() ?: error("failure contender was not prepared")
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            failedLifecycle.offerTerminal(
                SessionLifecycle.TerminalDecision.Failed(
                    ScreenCaptureProblem.ResourceExhausted,
                    IllegalArgumentException("duplicate failure"),
                ),
            ),
        )
        assertSame(firstFailure, failedPreparation.decision)
        assertTrue(failedLifecycle.isTerminalPreparationCurrent(failedPreparation))

        val requestedLifecycle = SessionLifecycle()
        requestedLifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)
        val requestedPreparation = requestedLifecycle.prepareTerminal() ?: error("requested contender was not prepared")
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            requestedLifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested),
        )
        assertTrue(requestedLifecycle.isTerminalPreparationCurrent(requestedPreparation))

        val projectionStoppedLifecycle = SessionLifecycle()
        projectionStoppedLifecycle.offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped)
        val projectionStoppedPreparation =
            projectionStoppedLifecycle.prepareTerminal() ?: error("projection contender was not prepared")
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            projectionStoppedLifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested),
        )
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            projectionStoppedLifecycle.offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped),
        )
        assertSame(
            SessionLifecycle.TerminalDecision.ProjectionStopped,
            projectionStoppedPreparation.decision,
        )
        assertTrue(projectionStoppedLifecycle.isTerminalPreparationCurrent(projectionStoppedPreparation))
    }

    // Verification: SES-02
    @Test
    fun lateBootstrapFactsCannotReopenActive() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        assertSame(SessionLifecycle.BootstrapFactResult.Recorded, lifecycle.recordBootstrapWorkerAccepted())
        lifecycle.offerTerminal(
            SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, IllegalStateException("bootstrap")),
        )
        lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)
        lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped)

        assertSame(SessionLifecycle.BootstrapFactResult.Stale, lifecycle.recordFirstControlPostAccepted())
        assertSame(SessionLifecycle.BootstrapFactResult.Stale, lifecycle.recordBootstrapWorkerAccepted())
        assertFalse(lifecycle.bootstrapReady)
        assertFalse(lifecycle.activePublicationMayProceed(first = true))
        assertSame(
            SessionLifecycle.ActiveReservation.Stale,
            lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L),
        )
    }

    // Verification: API-03
    // Verification: SES-02
    @Test
    fun incumbentChallengerMatrixKeepsPriorityAndSettlesNonFailureWinnersAsCaptureUnavailable() = runTest {
        val invalidRequestFailure = SessionLifecycle.TerminalDecision.Failed(
            ScreenCaptureProblem.InvalidRequest,
            IllegalStateException("first invalid-request failure"),
        )
        val resourceExhaustedFailure = SessionLifecycle.TerminalDecision.Failed(
            ScreenCaptureProblem.ResourceExhausted,
            IllegalArgumentException("first resource-exhausted failure"),
        )
        val cases = listOf(
            TerminalContest(
                incumbent = invalidRequestFailure,
                challenger = SessionLifecycle.TerminalDecision.Requested,
                winner = SessionLifecycle.TerminalDecision.Requested,
                challengerAccepted = true,
            ),
            TerminalContest(
                incumbent = resourceExhaustedFailure,
                challenger = SessionLifecycle.TerminalDecision.ProjectionStopped,
                winner = SessionLifecycle.TerminalDecision.ProjectionStopped,
                challengerAccepted = true,
            ),
            TerminalContest(
                incumbent = SessionLifecycle.TerminalDecision.Requested,
                challenger = invalidRequestFailure,
                winner = SessionLifecycle.TerminalDecision.Requested,
                challengerAccepted = false,
            ),
            TerminalContest(
                incumbent = SessionLifecycle.TerminalDecision.ProjectionStopped,
                challenger = resourceExhaustedFailure,
                winner = SessionLifecycle.TerminalDecision.ProjectionStopped,
                challengerAccepted = false,
            ),
        )

        cases.forEach { contest ->
            val lifecycle = SessionLifecycle()
            lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)

            val incumbentOffer = lifecycle.offerTerminal(contest.incumbent)
            assertTrue((incumbentOffer as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
            assertFalse(lifecycle.isOrdinaryAdmissionOpen)
            val incumbentPreparation = lifecycle.prepareTerminal()
                ?: error("terminal incumbent was not prepared")
            assertSame(contest.incumbent, incumbentPreparation.decision)
            (contest.incumbent as? SessionLifecycle.TerminalDecision.Failed)?.let { firstFailure ->
                val preparedFailure = incumbentPreparation.decision as SessionLifecycle.TerminalDecision.Failed
                assertSame(firstFailure.problem, preparedFailure.problem)
                assertSame(firstFailure.cause, preparedFailure.cause)
            }

            val challengerOffer = lifecycle.offerTerminal(contest.challenger)
            if (contest.challengerAccepted) {
                assertFalse((challengerOffer as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
                assertFalse(lifecycle.isTerminalPreparationCurrent(incumbentPreparation))
            } else {
                assertSame(SessionLifecycle.TerminalOffer.Rejected, challengerOffer)
                assertTrue(lifecycle.isTerminalPreparationCurrent(incumbentPreparation))
            }

            val winnerPreparation = lifecycle.prepareTerminal()
                ?: error("terminal winner was not prepared")
            assertSame(contest.winner, winnerPreparation.decision)
            assertTrue(lifecycle.isTerminalPreparationCurrent(winnerPreparation))
            lifecycle.commitTerminal(winnerPreparation)

            assertClaimIsIrreversible(lifecycle)
            checkNotNull(winnerPreparation.startSettlement).complete()
            assertStartFails(lifecycle, ScreenCaptureProblem.CaptureUnavailable)
        }
    }

    // Verification: API-03
    // Verification: SES-02
    @Test
    fun failureClaimsTerminalAndSettlesStartWithStableProblem() = runTest {
        val lifecycle = SessionLifecycle()
        lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        val failedDecision = SessionLifecycle.TerminalDecision.Failed(
            ScreenCaptureProblem.InternalFailure,
            IllegalStateException("winning failure"),
        )

        val failed = lifecycle.offerTerminal(failedDecision)
        assertTrue((failed as SessionLifecycle.TerminalOffer.Accepted).closesOrdinaryAdmission)
        assertFalse(lifecycle.isOrdinaryAdmissionOpen)
        val preparation = lifecycle.prepareTerminal() ?: error("failure contender was not prepared")
        assertTrue(preparation.decision is SessionLifecycle.TerminalDecision.Failed)
        assertSame(
            ScreenCaptureProblem.InternalFailure,
            (preparation.decision as SessionLifecycle.TerminalDecision.Failed).problem,
        )
        assertTrue(lifecycle.isTerminalPreparationCurrent(preparation))

        lifecycle.commitTerminal(preparation)

        assertClaimIsIrreversible(lifecycle)
        checkNotNull(preparation.startSettlement).complete()
        assertStartFails(lifecycle, ScreenCaptureProblem.InternalFailure)
    }

    // Verification: SES-02
    @Test
    fun terminalPreparationDirectlyCarriesNullAfterStartSettlementWasIssued() {
        val lifecycle = SessionLifecycle()
        val deadline = lifecycle.acceptStart(acceptedStartNanos = 10L, deadlineNanos = 20L)
        lifecycle.recordBootstrapWorkerAccepted()
        lifecycle.recordFirstControlPostAccepted()
        val reservation = lifecycle.reserveActive(first = true, expectedDeadline = deadline, sampledNanos = 19L)
                as SessionLifecycle.ActiveReservation.Reserved
        checkNotNull(lifecycle.settleActive(reservation.token, canContinueOrdinaryWork = true))
        lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested)

        val preparation = lifecycle.prepareTerminal() ?: error("terminal contender was not prepared")

        assertNull(preparation.startSettlement)
        assertTrue(lifecycle.isTerminalPreparationCurrent(preparation))
        lifecycle.commitTerminal(preparation)
        assertTrue(lifecycle.isTerminal)
    }

    private fun assertClaimIsIrreversible(lifecycle: SessionLifecycle) {
        assertTrue(lifecycle.isTerminal)
        assertFalse(lifecycle.isTerminalPending)
        assertFalse(lifecycle.isOrdinaryAdmissionOpen)
        assertNull(lifecycle.prepareTerminal())
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InvalidRequest, null)),
        )
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.Requested),
        )
        assertSame(
            SessionLifecycle.TerminalOffer.Rejected,
            lifecycle.offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped),
        )
    }

    private class TerminalContest(
        val incumbent: SessionLifecycle.TerminalDecision,
        val challenger: SessionLifecycle.TerminalDecision,
        val winner: SessionLifecycle.TerminalDecision,
        val challengerAccepted: Boolean,
    )

    private suspend fun assertStartFails(
        lifecycle: SessionLifecycle,
        expectedProblem: ScreenCaptureProblem,
    ) {
        try {
            lifecycle.startWaiter.awaitCompletion()
            error("terminal start unexpectedly succeeded")
        } catch (failure: ScreenCaptureException) {
            assertSame(expectedProblem, failure.problem)
        }
    }
}
