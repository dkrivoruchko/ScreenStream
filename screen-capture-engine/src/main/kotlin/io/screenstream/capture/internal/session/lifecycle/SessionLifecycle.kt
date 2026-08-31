package io.screenstream.capture.internal.session.lifecycle

import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureProblem
import kotlinx.coroutines.CompletableDeferred

/**
 * Exclusive semantic owner of start admission, startup eligibility, pause state, terminal priority, and start
 * settlement for one session.
 *
 * The coordinator serializes access to this owner. Reservation objects are provisional evidence: public assignment
 * and subsequent settlement must revalidate the exact reservation. Terminal selection closes ordinary admission
 * before claim, while claim permanently freezes the chosen outcome and the remaining start settlement.
 */
internal class SessionLifecycle {
    internal sealed interface StartupCheck {
        data object Eligible : StartupCheck
        data object Stale : StartupCheck
        data object ClockRegressed : StartupCheck
        data object DeadlineExpired : StartupCheck
    }

    internal sealed interface ActiveReservation {
        class Reserved(internal val token: ActiveToken) : ActiveReservation
        data object Stale : ActiveReservation
        data object ClockRegressed : ActiveReservation
        data object DeadlineExpired : ActiveReservation
    }

    internal sealed interface TerminalOffer {
        class Accepted(internal val closesOrdinaryAdmission: Boolean) : TerminalOffer
        data object Rejected : TerminalOffer
    }

    internal class TerminalPreparation(internal val decision: TerminalDecision, internal val startSettlement: StartSettlement?)

    internal sealed interface TerminalDecision {
        data object Requested : TerminalDecision
        data object ProjectionStopped : TerminalDecision
        class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable?) : TerminalDecision
    }

    internal class StartupDeadline(internal val acceptedStartNanos: Long, internal val deadlineNanos: Long)

    internal class ActiveToken(
        internal val isFirst: Boolean,
        internal val startSettlement: StartSettlement?,
    )

    internal class StartWaiter(private val completion: CompletableDeferred<Unit>) {
        internal suspend fun awaitCompletion() {
            completion.await()
        }
    }

    internal class StartSettlement private constructor(
        private val completion: CompletableDeferred<Unit>,
        private val outcome: Outcome,
    ) {
        private sealed interface Outcome {
            data object Succeeded : Outcome
            class Failed(val failure: ScreenCaptureException) : Outcome
        }

        internal fun complete() {
            try {
                when (val selectedOutcome = outcome) {
                    Outcome.Succeeded -> completion.complete(Unit)
                    is Outcome.Failed -> completion.completeExceptionally(selectedOutcome.failure)
                }
            } catch (_: Exception) {
            }
        }

        internal companion object {
            internal fun succeeded(completion: CompletableDeferred<Unit>): StartSettlement = StartSettlement(completion, Outcome.Succeeded)

            internal fun failed(completion: CompletableDeferred<Unit>, decision: TerminalDecision): StartSettlement {
                val failure = when (decision) {
                    TerminalDecision.Requested, TerminalDecision.ProjectionStopped ->
                        ScreenCaptureException.create(ScreenCaptureProblem.CaptureUnavailable)

                    is TerminalDecision.Failed ->
                        ScreenCaptureException.create(decision.problem, decision.cause)
                }
                return StartSettlement(completion, Outcome.Failed(failure))
            }
        }
    }

    private enum class Phase { NotStarted, Starting, Running, Terminal, }

    private enum class FirstActiveState { AwaitingAssignment, AssignedStartPending, StartEntitlementConsumed, }

    internal enum class BootstrapFactResult { Recorded, Ready, Stale, }

    private val startCompletion = CompletableDeferred<Unit>()
    internal val startWaiter: StartWaiter = StartWaiter(startCompletion)

    private var phase = Phase.NotStarted
    private var productionPaused = false
    private var firstActiveState = FirstActiveState.AwaitingAssignment
    private var startupDeadline: StartupDeadline? = null
    private var startupDeadlineExpired = false
    private var activeToken: ActiveToken? = null
    private var startSettlementIssued = false
    private var bootstrapWorkerAccepted = false
    private var firstControlPostAccepted = false

    private var terminalDecision: TerminalDecision? = null
    private var terminalClaimed = false

    internal fun acceptStart(acceptedStartNanos: Long, deadlineNanos: Long): StartupDeadline {
        check((phase == Phase.NotStarted) && (terminalDecision == null)) { "ScreenCaptureSession can be started only once" }
        require(acceptedStartNanos >= 0L)
        require(deadlineNanos > acceptedStartNanos)
        return StartupDeadline(acceptedStartNanos, deadlineNanos).also {
            startupDeadline = it
            phase = Phase.Starting
            productionPaused = true
            bootstrapWorkerAccepted = false
            firstControlPostAccepted = false
        }
    }

    internal fun recordBootstrapWorkerAccepted(): BootstrapFactResult = recordBootstrapFact(worker = true)

    internal fun recordFirstControlPostAccepted(): BootstrapFactResult = recordBootstrapFact(worker = false)

    internal val bootstrapReady: Boolean
        get() = bootstrapWorkerAccepted && firstControlPostAccepted

    internal val startupDeadlineCandidate: StartupDeadline?
        get() = startupDeadline?.takeIf {
            (firstActiveState == FirstActiveState.AwaitingAssignment) && !startupDeadlineExpired
        }

    internal fun checkStartupDeadline(expected: StartupDeadline, sampledNanos: Long): StartupCheck {
        if ((startupDeadline !== expected) || (phase != Phase.Starting) ||
            (firstActiveState != FirstActiveState.AwaitingAssignment) || startupDeadlineExpired || (terminalDecision != null)
        ) {
            return StartupCheck.Stale
        }
        if (sampledNanos < expected.acceptedStartNanos) return StartupCheck.ClockRegressed
        if (sampledNanos < expected.deadlineNanos) return StartupCheck.Eligible
        startupDeadlineExpired = true
        return StartupCheck.DeadlineExpired
    }

    internal fun reserveActive(first: Boolean, expectedDeadline: StartupDeadline?, sampledNanos: Long?): ActiveReservation {
        if (!activePublicationMayProceed(first) || (activeToken != null)) return ActiveReservation.Stale
        if (first) {
            val deadline = expectedDeadline ?: return ActiveReservation.Stale
            val sampled = sampledNanos ?: return ActiveReservation.Stale
            when (checkStartupDeadline(deadline, sampled)) {
                StartupCheck.ClockRegressed -> return ActiveReservation.ClockRegressed
                StartupCheck.DeadlineExpired -> return ActiveReservation.DeadlineExpired
                StartupCheck.Stale -> return ActiveReservation.Stale
                StartupCheck.Eligible -> Unit
            }
            startupDeadline = null
        } else if ((phase != Phase.Running) || (firstActiveState == FirstActiveState.AwaitingAssignment)) {
            return ActiveReservation.Stale
        }
        val startSettlement = if (!startSettlementIssued && (first || (firstActiveState == FirstActiveState.AssignedStartPending))) {
            StartSettlement.succeeded(startCompletion)
        } else {
            null
        }
        return ActiveReservation.Reserved(ActiveToken(first, startSettlement)).also {
            activeToken = it.token
            if (first) {
                phase = Phase.Running
                firstActiveState = FirstActiveState.AssignedStartPending
            }
        }
    }

    internal fun settleActive(expected: ActiveToken, canContinueOrdinaryWork: Boolean): StartSettlement? {
        check(activeToken === expected)
        activeToken = null
        check((phase == Phase.Running) && (firstActiveState != FirstActiveState.AwaitingAssignment))
        if (canContinueOrdinaryWork && (firstActiveState == FirstActiveState.AssignedStartPending)) {
            firstActiveState = FirstActiveState.StartEntitlementConsumed
        }
        val startSettlement = if (canContinueOrdinaryWork) expected.startSettlement else null
        if (startSettlement != null) {
            check(!startSettlementIssued)
            startSettlementIssued = true
        }
        productionPaused = !canContinueOrdinaryWork
        return startSettlement
    }

    internal val isFirstActiveRequired: Boolean
        get() = firstActiveState == FirstActiveState.AwaitingAssignment

    internal fun activePublicationMayProceed(first: Boolean): Boolean {
        if ((terminalDecision != null) || terminalClaimed || !productionPaused) return false
        return if (first) {
            (phase == Phase.Starting) && (firstActiveState == FirstActiveState.AwaitingAssignment) && bootstrapReady
        } else {
            (phase == Phase.Running) && (firstActiveState != FirstActiveState.AwaitingAssignment)
        }
    }

    internal val canUpdateParameters: Boolean
        get() = (phase == Phase.Running) && (terminalDecision == null)

    internal fun canRunProduction(sessionReady: Boolean, revisionCurrent: Boolean): Boolean =
        (phase != Phase.Terminal) && (terminalDecision == null) && !productionPaused && sessionReady && revisionCurrent

    internal fun pauseProduction() {
        productionPaused = true
    }

    internal fun offerTerminal(decision: TerminalDecision): TerminalOffer {
        if (terminalClaimed) return TerminalOffer.Rejected
        val closesOrdinaryAdmission = terminalDecision == null
        val accepted = when (terminalDecision) {
            null -> true
            is TerminalDecision.Failed -> when (decision) {
                is TerminalDecision.Failed -> false
                TerminalDecision.Requested,
                TerminalDecision.ProjectionStopped -> true
            }

            TerminalDecision.Requested -> when (decision) {
                TerminalDecision.ProjectionStopped -> true
                TerminalDecision.Requested,
                is TerminalDecision.Failed -> false
            }

            TerminalDecision.ProjectionStopped -> false
        }
        if (!accepted) return TerminalOffer.Rejected
        terminalDecision = decision
        productionPaused = true
        return TerminalOffer.Accepted(closesOrdinaryAdmission)
    }

    internal val isTerminalPending: Boolean
        get() = (terminalDecision != null) && !terminalClaimed

    internal val isOrdinaryAdmissionOpen: Boolean
        get() = terminalDecision == null

    internal fun prepareTerminal(): TerminalPreparation? {
        if (terminalClaimed) return null
        val decision = terminalDecision ?: return null
        val startSettlement = if (startSettlementIssued) {
            null
        } else {
            StartSettlement.failed(startCompletion, decision)
        }
        return TerminalPreparation(decision, startSettlement)
    }

    internal fun isTerminalPreparationCurrent(expected: TerminalPreparation): Boolean =
        !terminalClaimed && (terminalDecision === expected.decision) && ((expected.startSettlement != null) == !startSettlementIssued)

    internal fun commitTerminal(expected: TerminalPreparation) {
        check(isTerminalPreparationCurrent(expected))
        if (expected.startSettlement != null) startSettlementIssued = true
        terminalClaimed = true
        phase = Phase.Terminal
        productionPaused = true
        startupDeadline = null
        activeToken = null
    }

    internal val isTerminal: Boolean
        get() = phase == Phase.Terminal

    private fun recordBootstrapFact(worker: Boolean): BootstrapFactResult {
        if ((phase != Phase.Starting) || terminalClaimed || (terminalDecision != null)) return BootstrapFactResult.Stale
        if (worker) {
            if (bootstrapWorkerAccepted) return BootstrapFactResult.Stale
            bootstrapWorkerAccepted = true
        } else {
            if (firstControlPostAccepted) return BootstrapFactResult.Stale
            firstControlPostAccepted = true
        }
        return if (bootstrapReady) BootstrapFactResult.Ready else BootstrapFactResult.Recorded
    }
}
