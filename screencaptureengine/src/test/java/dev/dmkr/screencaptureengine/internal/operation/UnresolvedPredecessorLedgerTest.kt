package dev.dmkr.screencaptureengine.internal.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnresolvedPredecessorLedgerTest {
    @Test
    fun waitingTimeoutMakesPredecessorUnresolvedUntilReturnedOwnerReleasesIt() {
        val ledger = UnresolvedPredecessorLedger()

        assertEquals(OperationStartRegistrationFact.Registered, ledger.registerStarted(predecessor))
        assertEquals(
            OperationStartRegistrationFact.BlockedByPredecessor(
                predecessor = predecessor,
                unresolved = false,
                returned = false,
            ),
            ledger.registerStarted(waiting),
        )
        assertEquals(
            WaitingOperationTimeoutFact.UnresolvedPredecessorRecorded(waiting, predecessor),
            ledger.markWaitingStartTimedOut(waiting, predecessor),
        )
        assertEquals(
            OperationStartRegistrationFact.BlockedByPredecessor(
                predecessor = predecessor,
                unresolved = true,
                returned = false,
            ),
            ledger.registerStarted(later),
        )

        assertEquals(
            PredecessorReturnFact.Recorded(predecessor, wasUnresolved = true),
            ledger.recordReturned(predecessor),
        )
        assertEquals(
            OperationStartRegistrationFact.BlockedByPredecessor(
                predecessor = predecessor,
                unresolved = true,
                returned = true,
            ),
            ledger.registerStarted(later),
        )
        assertEquals(
            PredecessorReleaseFact.Released(predecessor, wasUnresolved = true),
            ledger.releaseReturned(predecessor),
        )
        assertEquals(OperationStartRegistrationFact.Registered, ledger.registerStarted(later))
    }

    @Test
    fun duplicateAndMismatchedFactsCannotResolveOrReplacePredecessor() {
        val ledger = UnresolvedPredecessorLedger()
        ledger.registerStarted(predecessor)

        assertEquals(
            WaitingOperationTimeoutFact.PredecessorMismatch(waiting, later, predecessor),
            ledger.markWaitingStartTimedOut(waiting, expectedPredecessor = later),
        )
        ledger.markWaitingStartTimedOut(waiting, predecessor)
        assertEquals(
            WaitingOperationTimeoutFact.PredecessorAlreadyUnresolved(later, predecessor),
            ledger.markWaitingStartTimedOut(later, predecessor),
        )
        assertEquals(
            PredecessorReleaseFact.NotReturned(predecessor),
            ledger.releaseReturned(predecessor),
        )
        assertEquals(
            PredecessorReturnFact.PredecessorMismatch(later, predecessor),
            ledger.recordReturned(later),
        )

        ledger.recordReturned(predecessor)
        assertEquals(
            PredecessorReturnFact.AlreadyRecorded(predecessor, wasUnresolved = true),
            ledger.recordReturned(predecessor),
        )
        assertEquals(
            WaitingOperationTimeoutFact.PredecessorAlreadyReturned(later, predecessor),
            ledger.markWaitingStartTimedOut(later, predecessor),
        )
    }

    @Test
    fun waitingOperationCannotNameItselfAsPredecessor() {
        val ledger = UnresolvedPredecessorLedger()

        assertThrows(IllegalArgumentException::class.java) {
            ledger.markWaitingStartTimedOut(waiting, expectedPredecessor = waiting)
        }
    }

    @Test
    fun neverReturningPredecessorKeepsFacilityOccupied() {
        val ledger = UnresolvedPredecessorLedger()

        assertEquals(OperationStartRegistrationFact.Registered, ledger.registerStarted(predecessor))
        assertEquals(
            WaitingOperationTimeoutFact.UnresolvedPredecessorRecorded(waiting, predecessor),
            ledger.markWaitingStartTimedOut(waiting, predecessor),
        )

        repeat(100) {
            assertEquals(
                OperationStartRegistrationFact.BlockedByPredecessor(
                    predecessor = predecessor,
                    unresolved = true,
                    returned = false,
                ),
                ledger.registerStarted(later),
            )
            assertEquals(PredecessorReleaseFact.NotReturned(predecessor), ledger.releaseReturned(predecessor))
        }
    }

    private companion object {
        val predecessor = WorkIdentity(1L)
        val waiting = WorkIdentity(2L)
        val later = WorkIdentity(3L)
    }
}
