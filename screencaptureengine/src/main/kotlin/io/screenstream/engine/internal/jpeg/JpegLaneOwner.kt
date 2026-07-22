package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.isHandedOff
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private enum class JpegTicketReservation {
    Existing,
    Reserved,
    Rejected,
}

private sealed interface JpegEndpointAdmissionState {
    object Open : JpegEndpointAdmissionState

    class TerminalCleanupOnly(
        val latch: JpegTerminalModeLatch,
    ) : JpegEndpointAdmissionState

    class Closed(
        val latch: JpegTerminalModeLatch,
    ) : JpegEndpointAdmissionState
}

/** Owns only the single JPEG execute endpoint and its mechanical lifecycle. */
internal class JpegLaneOwner internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val constructionFact: JpegEndpointConstructionFact,
    private val physicalDomainIdentity: JpegPhysicalDomainIdentity,
    internal val settlementSignal: SettlementSignal,
) {
    internal val endpoint: PrivateExecutorRuntime =
        PrivateExecutorRuntime("ScreenCapture-JPEG", settlementSignal)

    private val admissionGate = ReentrantLock()
    private var currentTicket: PrivateExecutorOperation<*>? = null
    internal val endpointTerminationOwner: JpegEndpointTerminationOwner = JpegEndpointTerminationOwner(this)
    private val terminalModeLatch = JpegTerminalModeLatch(
        constructionFact.runtimeIdentity,
        endpointIdentity,
    )
    private val terminalCleanupAdmission =
        JpegEndpointAdmissionState.TerminalCleanupOnly(terminalModeLatch)
    private val closedAdmission = JpegEndpointAdmissionState.Closed(terminalModeLatch)
    private var admissionState: JpegEndpointAdmissionState = JpegEndpointAdmissionState.Open

    internal val fatal: Throwable?
        get() = endpoint.observedFatal

    internal val terminationReceipt: PrivateExecutorTerminationReceipt?
        get() = endpoint.terminationReceipt.takeIf { endpointTerminationOwner.isEndpointRootReleased }

    internal val rawTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = endpoint.terminationReceipt

    internal val mechanicallyIdle: Boolean
        get() = admissionGate.withLock { currentTicket == null && !endpoint.hasUnsettledOperation }

    internal fun prestart(): PrivateExecutorStartupDisposition = endpoint.prestart()

    internal fun submit(operation: PrivateExecutorOperation<*>): Boolean {
        val reservation = admissionGate.withLock {
            if (operation.endpoint !== endpoint) return@withLock JpegTicketReservation.Rejected
            val tracked = currentTicket
            if (tracked === operation) return@withLock JpegTicketReservation.Existing
            if (tracked != null) return@withLock JpegTicketReservation.Rejected
            when (admissionState) {
                JpegEndpointAdmissionState.Open -> Unit
                is JpegEndpointAdmissionState.TerminalCleanupOnly -> {
                    if (operation.occurrence.domain != OperationDomain.Cleanup) {
                        return@withLock JpegTicketReservation.Rejected
                    }
                }

                is JpegEndpointAdmissionState.Closed -> return@withLock JpegTicketReservation.Rejected
            }
            currentTicket = operation
            JpegTicketReservation.Reserved
        }
        when (reservation) {
            JpegTicketReservation.Existing -> return true
            JpegTicketReservation.Rejected -> return false
            JpegTicketReservation.Reserved -> Unit
        }

        val handedOff = endpoint.submit(operation).isHandedOff
        if (!handedOff) {
            admissionGate.withLock {
                if (currentTicket === operation) currentTicket = null
            }
        }
        return handedOff
    }

    internal fun release(occurrence: JpegEndpointOccurrence): Boolean {
        if (occurrence.endpointReleased) return true
        val tracked = admissionGate.withLock { currentTicket }
        if (tracked != null && tracked !== occurrence.executorOperation) return false
        if (!endpoint.releaseSettledOperation(occurrence.executorOperation)) return false
        admissionGate.withLock {
            if (currentTicket === occurrence.executorOperation) currentTicket = null
        }
        occurrence.endpointReleased = true
        return true
    }

    internal fun currentTicketFact(): JpegEndpointTicketFact? =
        admissionGate.withLock { currentTicket?.let { JpegEndpointTicketFact(endpointIdentity, it) } }

    internal fun latchTerminalMode(): JpegTerminalModeLatch = admissionGate.withLock {
        when (val current = admissionState) {
            JpegEndpointAdmissionState.Open -> {
                admissionState = terminalCleanupAdmission
                terminalModeLatch
            }

            is JpegEndpointAdmissionState.TerminalCleanupOnly -> current.latch
            is JpegEndpointAdmissionState.Closed -> current.latch
        }
    }

    internal fun requestShutdown(
        terminalLatch: JpegTerminalModeLatch,
        readiness: JpegOwnerRootReadiness,
    ): Boolean {
        validateReadiness(readiness)
        var claimedAction: JpegEndpointShutdownAction? = null
        val eligibility = admissionGate.withLock {
            validateTerminalLatchLocked(terminalLatch)
            val evaluated = endpointTerminationOwner.shutdownEligibility(readiness)
            if (evaluated is JpegEndpointShutdownEligibility.Eligible) {
                check(admissionState is JpegEndpointAdmissionState.TerminalCleanupOnly)
                check(evaluated.action.claimEntry())
                admissionState = closedAdmission
                claimedAction = evaluated.action
            } else if (evaluated is JpegEndpointShutdownEligibility.EndpointAlreadyTerminated ||
                evaluated is JpegEndpointShutdownEligibility.ActionAlreadyEntered
            ) {
                if (admissionState is JpegEndpointAdmissionState.TerminalCleanupOnly) {
                    admissionState = closedAdmission
                }
            }
            evaluated
        }
        return when (eligibility) {
            is JpegEndpointShutdownEligibility.Eligible ->
                shutdownReturn(checkNotNull(claimedAction).performEnteredCall())

            is JpegEndpointShutdownEligibility.ActionAlreadyEntered ->
                shutdownReturn(eligibility.outcome)

            is JpegEndpointShutdownEligibility.EndpointAlreadyTerminated -> {
                endpointTerminationOwner.releaseEndpointRoot(eligibility.outcome.receipt)
                false
            }

            is JpegEndpointShutdownEligibility.WaitingForEndpointTicketSettlement,
            is JpegEndpointShutdownEligibility.WaitingForPhysicalDomainRoots,
                -> false
        }
    }

    internal fun performExactShutdown(action: JpegEndpointShutdownAction): JpegEndpointShutdownFact {
        check(action === endpointTerminationOwner.shutdownAction && action.endpointIdentity === endpointIdentity)
        val fact = endpointTerminationOwner.beginShutdownFact(action)
        return try {
            val accepted = endpoint.requestShutdown()
            fact.publishReturned(accepted)
            fact
        } catch (throwable: Throwable) {
            fact.publishThrown(throwable)
            throw throwable
        }
    }

    internal fun acceptsTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        endpoint.acceptsTerminationReceipt(receipt)

    internal fun cleanupComplete(
        readiness: JpegOwnerRootReadiness,
        receipt: PrivateExecutorTerminationReceipt,
    ): JpegEndpointRootSettlement {
        validateReadiness(readiness)
        val currentAdmission = admissionGate.withLock { admissionState }
        if (currentAdmission !is JpegEndpointAdmissionState.Closed || currentAdmission !== closedAdmission ||
            readiness !is JpegOwnerRootReadiness.PhysicalDomainRootsSettled || !mechanicallyIdle
        ) {
            return endpointTerminationOwner.rootSettlement()
        }
        val typedReceipt = endpointTerminationOwner.typedReceiptFor(receipt)
            ?: return endpointTerminationOwner.rootSettlement()
        return endpointTerminationOwner.releaseEndpointRoot(typedReceipt)
    }

    private fun validateReadiness(readiness: JpegOwnerRootReadiness) {
        check(
            readiness.physicalDomainIdentity === physicalDomainIdentity &&
                    readiness.runtimeIdentity === constructionFact.runtimeIdentity &&
                    readiness.runtimeIdentity === endpointIdentity.runtimeIdentity &&
                    readiness.endpointIdentity === endpointIdentity,
        )
        if (readiness is JpegOwnerRootReadiness.PhysicalDomainRootsSettled) {
            check(
                readiness.emptyTopology.product == null && readiness.emptyTopology.lease == null &&
                        readiness.emptyTopology.replacementSource == null,
            )
        }
    }

    private fun validateTerminalLatchLocked(latch: JpegTerminalModeLatch) {
        check(admissionGate.isHeldByCurrentThread)
        val identity = latch.admissionIdentity
        check(
            latch === terminalModeLatch && identity.runtimeIdentity === constructionFact.runtimeIdentity &&
                    identity.endpointIdentity === endpointIdentity && when (val current = admissionState) {
                        JpegEndpointAdmissionState.Open -> false
                        is JpegEndpointAdmissionState.TerminalCleanupOnly -> current.latch === latch
                        is JpegEndpointAdmissionState.Closed -> current.latch === latch
                    },
        )
    }

    private fun shutdownReturn(outcome: JpegEndpointShutdownActionOutcome): Boolean = when (outcome) {
        is JpegEndpointShutdownActionOutcome.Entered -> false
        is JpegEndpointShutdownActionOutcome.Returned ->
            outcome.fact.result == JpegEndpointShutdownReturn.Requested
        is JpegEndpointShutdownActionOutcome.Thrown -> throw checkNotNull(outcome.fact.throwable)
    }
}
