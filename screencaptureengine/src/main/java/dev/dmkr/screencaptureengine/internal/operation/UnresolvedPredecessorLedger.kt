@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.operation

internal sealed interface OperationStartRegistrationFact {
    data object Registered : OperationStartRegistrationFact

    data class BlockedByPredecessor(
        val predecessor: WorkIdentity,
        val unresolved: Boolean,
        val returned: Boolean,
    ) : OperationStartRegistrationFact
}

internal sealed interface WaitingOperationTimeoutFact {
    data class UnresolvedPredecessorRecorded(
        val waiting: WorkIdentity,
        val predecessor: WorkIdentity,
    ) : WaitingOperationTimeoutFact

    data class PredecessorAlreadyUnresolved(
        val waiting: WorkIdentity,
        val predecessor: WorkIdentity,
    ) : WaitingOperationTimeoutFact

    data class PredecessorAlreadyReturned(
        val waiting: WorkIdentity,
        val predecessor: WorkIdentity,
    ) : WaitingOperationTimeoutFact

    data class PredecessorMismatch(
        val waiting: WorkIdentity,
        val expectedPredecessor: WorkIdentity,
        val actualPredecessor: WorkIdentity?,
    ) : WaitingOperationTimeoutFact
}

internal sealed interface PredecessorReturnFact {
    data class Recorded(
        val predecessor: WorkIdentity,
        val wasUnresolved: Boolean,
    ) : PredecessorReturnFact

    data class PredecessorMismatch(
        val expectedPredecessor: WorkIdentity,
        val actualPredecessor: WorkIdentity?,
    ) : PredecessorReturnFact

    data class AlreadyRecorded(
        val predecessor: WorkIdentity,
        val wasUnresolved: Boolean,
    ) : PredecessorReturnFact
}

internal sealed interface PredecessorReleaseFact {
    data class Released(
        val predecessor: WorkIdentity,
        val wasUnresolved: Boolean,
    ) : PredecessorReleaseFact

    data class NotReturned(
        val predecessor: WorkIdentity,
    ) : PredecessorReleaseFact

    data class PredecessorMismatch(
        val expectedPredecessor: WorkIdentity,
        val actualPredecessor: WorkIdentity?,
    ) : PredecessorReleaseFact
}

/**
 * Mechanical occupancy ledger for a serial facility.
 *
 * A timed-out waiter can mark the current started predecessor unresolved. Even after that
 * predecessor returns, the lane remains occupied until its owner proves the separate reuse barriers
 * and explicitly releases it. The ledger does not decide whether release, quarantine, or poison is
 * appropriate.
 */
internal class UnresolvedPredecessorLedger {
    private val lock = Any()
    private var predecessor: Predecessor? = null

    internal fun registerStarted(identity: WorkIdentity): OperationStartRegistrationFact =
        synchronized(lock) {
            val current = predecessor
            if (current == null) {
                predecessor = Predecessor(identity = identity)
                OperationStartRegistrationFact.Registered
            } else {
                OperationStartRegistrationFact.BlockedByPredecessor(
                    predecessor = current.identity,
                    unresolved = current.unresolved,
                    returned = current.returned,
                )
            }
        }

    internal fun markWaitingStartTimedOut(
        waiting: WorkIdentity,
        expectedPredecessor: WorkIdentity,
    ): WaitingOperationTimeoutFact {
        require(waiting != expectedPredecessor) { "A waiting operation cannot be its own predecessor." }
        return synchronized(lock) {
            val current = predecessor
            when {
                current?.identity != expectedPredecessor -> WaitingOperationTimeoutFact.PredecessorMismatch(
                    waiting = waiting,
                    expectedPredecessor = expectedPredecessor,
                    actualPredecessor = current?.identity,
                )

                current.returned -> WaitingOperationTimeoutFact.PredecessorAlreadyReturned(
                    waiting = waiting,
                    predecessor = current.identity,
                )

                current.unresolved -> WaitingOperationTimeoutFact.PredecessorAlreadyUnresolved(
                    waiting = waiting,
                    predecessor = current.identity,
                )

                else -> {
                    current.unresolved = true
                    WaitingOperationTimeoutFact.UnresolvedPredecessorRecorded(
                        waiting = waiting,
                        predecessor = current.identity,
                    )
                }
            }
        }
    }

    internal fun recordReturned(identity: WorkIdentity): PredecessorReturnFact =
        synchronized(lock) {
            val current = predecessor
            when {
                current?.identity != identity -> PredecessorReturnFact.PredecessorMismatch(
                    expectedPredecessor = identity,
                    actualPredecessor = current?.identity,
                )

                current.returned -> PredecessorReturnFact.AlreadyRecorded(
                    predecessor = identity,
                    wasUnresolved = current.unresolved,
                )

                else -> {
                    current.returned = true
                    PredecessorReturnFact.Recorded(
                        predecessor = identity,
                        wasUnresolved = current.unresolved,
                    )
                }
            }
        }

    internal fun releaseReturned(identity: WorkIdentity): PredecessorReleaseFact =
        synchronized(lock) {
            val current = predecessor
            when {
                current?.identity != identity -> PredecessorReleaseFact.PredecessorMismatch(
                    expectedPredecessor = identity,
                    actualPredecessor = current?.identity,
                )

                !current.returned -> PredecessorReleaseFact.NotReturned(predecessor = identity)
                else -> {
                    predecessor = null
                    PredecessorReleaseFact.Released(
                        predecessor = identity,
                        wasUnresolved = current.unresolved,
                    )
                }
            }
        }

    private data class Predecessor(
        val identity: WorkIdentity,
        var unresolved: Boolean = false,
        var returned: Boolean = false,
    )
}
