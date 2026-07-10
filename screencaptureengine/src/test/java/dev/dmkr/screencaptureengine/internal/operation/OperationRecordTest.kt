package dev.dmkr.screencaptureengine.internal.operation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRecordTest {
    @Test
    fun cancellationCommittedBeforeStartPreventsStartedLatchCrossing() {
        val record = OperationRecord<String>(operationIdentity)
        val cancellationCommitted = CountDownLatch(1)
        val cancellationFact = AtomicReference<OperationCancellationFact>()
        val startFact = AtomicReference<OperationStartFact>()

        val cancellationThread = Thread {
            cancellationFact.set(record.markCancellation(cancellationMarker))
            cancellationCommitted.countDown()
        }
        val startThread = Thread {
            cancellationCommitted.await()
            startFact.set(record.tryStart(startedAtNanos = 10L))
        }

        cancellationThread.start()
        startThread.start()
        cancellationThread.join()
        startThread.join()

        assertEquals(OperationCancellationFact.MarkedBeforeReturn, cancellationFact.get())
        assertEquals(OperationStartFact.CancelledBeforeStart(cancellationMarker), startFact.get())
        assertEquals(OperationPhase.Queued, record.snapshot().phase)
        assertNull(record.snapshot().completion.completion)
    }

    @Test
    fun startThenCancellationThenReturnRecordsExactOrderingAcrossThreads() {
        val record = OperationRecord<String>(operationIdentity)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val completionFact = AtomicReference<OperationReturnRecordingFact<String>>()

        val startThread = Thread {
            record.tryStart(startedAtNanos = 20L)
            started.countDown()
        }
        val cancellationThread = Thread {
            started.await()
            record.markCancellation(cancellationMarker)
            cancelled.countDown()
        }
        val returnThread = Thread {
            cancelled.await()
            completionFact.set(record.recordReturned(value = "returned", completedAtNanos = 30L))
        }

        startThread.start()
        cancellationThread.start()
        returnThread.start()
        startThread.join()
        cancellationThread.join()
        returnThread.join()

        val fact = OperationReturnFact(OperationReturnKind.Returned, "returned", 30L, true)
        assertEquals(
            OperationReturnRecordingFact.Recorded(
                fact = fact,
                completionDecision = OperationCompletionDecision.Completed(fact),
            ),
            completionFact.get(),
        )
        assertEquals(OperationPhase.Returned, record.snapshot().phase)
        assertEquals(fact, record.snapshot().returnFact)
        assertEquals(true, record.snapshot().cancellationMarkedBeforeReturn)
        assertEquals(OperationCompletion.Returned(fact), record.snapshot().completion.completion)
    }

    @Test
    fun returnCommittedBeforeCancellationIsProviderOutcomeFirst() {
        val record = OperationRecord<String>(operationIdentity)
        val returned = CountDownLatch(1)
        val cancellationFact = AtomicReference<OperationCancellationFact>()
        assertEquals(OperationStartFact.Started(20L), record.tryStart(startedAtNanos = 20L))

        val returnThread = Thread {
            record.recordReturned(value = "returned", completedAtNanos = 30L)
            returned.countDown()
        }
        val cancellationThread = Thread {
            returned.await()
            cancellationFact.set(record.markCancellation(cancellationMarker))
        }

        returnThread.start()
        cancellationThread.start()
        returnThread.join()
        cancellationThread.join()

        assertEquals(OperationCancellationFact.MarkedAfterReturn, cancellationFact.get())
        assertEquals(false, record.snapshot().cancellationMarkedBeforeReturn)
        assertEquals(
            OperationCancellationFact.AlreadyMarked(cancellationMarker, beforeReturn = false),
            record.markCancellation(OperationCancellationMarker(10L)),
        )
    }

    @Test
    fun workerReturnBeforeTimeoutAcceptanceMakesTimeoutObserveRetainedFact() {
        val record = OperationRecord<String>(operationIdentity)
        val returnRecorded = CountDownLatch(1)
        val timeoutFact = AtomicReference<OperationTimeoutFenceFact<String>>()
        record.tryStart(startedAtNanos = 10L)

        val worker = Thread {
            record.recordReturned(value = "returned", completedAtNanos = 20L)
            returnRecorded.countDown()
        }
        val watchdog = Thread {
            returnRecorded.await()
            timeoutFact.set(record.fenceOperationTimeout())
        }

        worker.start()
        watchdog.start()
        worker.join()
        watchdog.join()

        val fact = OperationReturnFact(OperationReturnKind.Returned, "returned", 20L, false)
        assertEquals(
            OperationTimeoutFenceFact.Decided(
                OperationCompletionDecision.AlreadyCompleted(
                    completion = OperationCompletion.Returned(fact),
                    retainedReturnFact = fact,
                ),
            ),
            timeoutFact.get(),
        )
        assertEquals(OperationCleanupClaim.Claimed(fact), record.claimCleanup())
        assertEquals(OperationCleanupClaim.AlreadyClaimed(fact), record.claimCleanup())
    }

    @Test
    fun timeoutBeforeWorkerReturnRetainsOwnershipUntilLateNormalReturn() {
        val record = OperationRecord<String>(operationIdentity)
        val timeoutAccepted = CountDownLatch(1)
        val allowWorkerReturn = CountDownLatch(1)
        val timeoutFact = AtomicReference<OperationTimeoutFenceFact<String>>()
        val lateReturn = AtomicReference<OperationReturnRecordingFact<String>>()
        record.tryStart(startedAtNanos = 10L)

        val watchdog = Thread {
            timeoutFact.set(record.fenceOperationTimeout())
            timeoutAccepted.countDown()
        }
        val worker = Thread {
            timeoutAccepted.await()
            allowWorkerReturn.await()
            lateReturn.set(record.recordReturned(value = "returned", completedAtNanos = 20L))
        }

        watchdog.start()
        worker.start()
        watchdog.join()
        assertEquals(
            OperationTimeoutFenceFact.Decided<String>(OperationCompletionDecision.TimedOut),
            timeoutFact.get(),
        )
        assertEquals(OperationCleanupClaim.AwaitingReturn, record.claimCleanup())
        allowWorkerReturn.countDown()
        worker.join()

        val fact = OperationReturnFact(OperationReturnKind.Returned, "returned", 20L, false)
        assertEquals(
            OperationReturnRecordingFact.Recorded(
                fact = fact,
                completionDecision = OperationCompletionDecision.LateReturn(fact),
            ),
            lateReturn.get(),
        )
        assertEquals(OperationCompletion.TimedOut, record.snapshot().completion.completion)
        assertEquals(fact, record.snapshot().completion.retainedReturnFact)
        assertEquals(OperationCleanupClaim.Claimed(fact), record.claimCleanup())
    }

    @Test
    fun timeoutBeforeWorkerExceptionRetainsFailedFactAndOneCleanupClaim() {
        val record = OperationRecord<Throwable>(operationIdentity)
        val failure = IllegalStateException("failure")
        record.tryStart(startedAtNanos = 10L)
        assertEquals(
            OperationTimeoutFenceFact.Decided<Throwable>(OperationCompletionDecision.TimedOut),
            record.fenceOperationTimeout(),
        )
        assertEquals(
            OperationCancellationFact.MarkedBeforeReturn,
            record.markCancellation(cancellationMarker),
        )

        val fact = OperationReturnFact(OperationReturnKind.Failed, failure, 40L, true)
        assertEquals(
            OperationReturnRecordingFact.Recorded(
                fact = fact,
                completionDecision = OperationCompletionDecision.LateReturn(fact),
            ),
            record.recordFailed(value = failure, completedAtNanos = 40L),
        )
        assertEquals(OperationPhase.Failed, record.snapshot().phase)
        assertSame(failure, record.snapshot().returnFact?.value)
        assertEquals(OperationCleanupClaim.Claimed(fact), record.claimCleanup())
        assertEquals(OperationCleanupClaim.AlreadyClaimed(fact), record.claimCleanup())
    }

    @Test
    fun duplicateReturnCannotReplaceRetainedFactOrCompletion() {
        val record = OperationRecord<String>(operationIdentity)
        record.tryStart(startedAtNanos = 10L)
        val first = OperationReturnFact(OperationReturnKind.Returned, "returned", 20L, false)

        assertEquals(
            OperationReturnRecordingFact.Recorded(first, OperationCompletionDecision.Completed(first)),
            record.recordReturned(value = "returned", completedAtNanos = 20L),
        )
        assertEquals(
            OperationReturnRecordingFact.AlreadyRecorded(first),
            record.recordFailed(value = "replacement", completedAtNanos = 30L),
        )
        assertEquals(first, record.snapshot().returnFact)
        assertEquals(OperationPhase.Returned, record.snapshot().phase)
    }

    @Test
    fun queuedStartTimeoutPermanentlyPreventsLateWorkerEntryWithoutCancellationMarker() {
        val record = OperationRecord<String>(operationIdentity)

        assertEquals(OperationStartTimeoutFact.TimedOutBeforeStart, record.fenceStartTimeout())
        assertEquals(OperationStartTimeoutFact.AlreadyTimedOut, record.fenceStartTimeout())
        assertEquals(OperationStartFact.NotQueued(OperationPhase.StartTimedOut), record.tryStart(10L))
        assertEquals(
            OperationReturnRecordingFact.NotStarted,
            record.recordReturned(value = "returned", completedAtNanos = 0L),
        )
        assertEquals(
            OperationTimeoutFenceFact.NotStarted(OperationPhase.StartTimedOut),
            record.fenceOperationTimeout(),
        )
        assertEquals(OperationCleanupClaim.AwaitingReturn, record.claimCleanup())
        assertNull(record.snapshot().returnFact)
        assertNull(record.snapshot().cancellationMarker)
        assertEquals(OperationPhase.StartTimedOut, record.snapshot().phase)
        assertFalse(record.snapshot().completion.cleanupClaimed)
    }

    @Test
    fun startedLatchWinsStartTimeoutAndLeavesStartedOperationDeadlineSeparate() {
        val record = OperationRecord<String>(operationIdentity)

        assertEquals(OperationStartFact.Started(10L), record.tryStart(10L))
        assertEquals(OperationStartTimeoutFact.AlreadyStarted(10L), record.fenceStartTimeout())
        assertNull(record.snapshot().completion.completion)
        assertNull(record.snapshot().cancellationMarker)
        assertEquals(
            OperationTimeoutFenceFact.Decided<String>(OperationCompletionDecision.TimedOut),
            record.fenceOperationTimeout(),
        )
    }

    @Test
    fun completedRecordReportsExactFactToLateStartTimeout() {
        val record = OperationRecord<String>(operationIdentity)
        record.tryStart(10L)
        record.recordReturned("returned", 20L)
        val fact = OperationReturnFact(OperationReturnKind.Returned, "returned", 20L, false)

        assertEquals(OperationStartTimeoutFact.AlreadyCompleted(fact), record.fenceStartTimeout())
    }

    @Test
    fun cancellationBeforeStartOwnsBoundaryWithoutBecomingStartTimeout() {
        val record = OperationRecord<String>(operationIdentity)
        record.markCancellation(cancellationMarker)

        assertEquals(
            OperationStartTimeoutFact.CancelledBeforeStart(cancellationMarker),
            record.fenceStartTimeout(),
        )
        assertEquals(
            OperationStartFact.CancelledBeforeStart(cancellationMarker),
            record.tryStart(10L),
        )
        assertEquals(OperationPhase.Queued, record.snapshot().phase)
    }

    @Test
    fun simultaneousStartAndStartTimeoutHaveOneExactWinnerAndNeverPermitLateEntry() {
        repeat(200) {
            val record = OperationRecord<String>(operationIdentity)
            val release = CountDownLatch(1)
            val externalEntryCount = AtomicInteger()
            val startFact = AtomicReference<OperationStartFact>()
            val timeoutFact = AtomicReference<OperationStartTimeoutFact<String>>()
            val worker = Thread {
                release.await()
                val fact = record.tryStart(10L)
                startFact.set(fact)
                if (fact is OperationStartFact.Started) {
                    externalEntryCount.incrementAndGet()
                }
            }
            val watchdog = Thread {
                release.await()
                timeoutFact.set(record.fenceStartTimeout())
            }

            worker.start()
            watchdog.start()
            release.countDown()
            worker.join()
            watchdog.join()

            when (timeoutFact.get()) {
                OperationStartTimeoutFact.TimedOutBeforeStart -> {
                    assertEquals(OperationStartFact.NotQueued(OperationPhase.StartTimedOut), startFact.get())
                    assertEquals(0, externalEntryCount.get())
                    assertEquals(OperationStartFact.NotQueued(OperationPhase.StartTimedOut), record.tryStart(20L))
                }

                OperationStartTimeoutFact.AlreadyStarted(10L) -> {
                    assertEquals(OperationStartFact.Started(10L), startFact.get())
                    assertEquals(1, externalEntryCount.get())
                }

                else -> error("Unexpected start-timeout fact: ${timeoutFact.get()}")
            }
            assertNull(record.snapshot().cancellationMarker)
        }
    }

    @Test
    fun simultaneousReturnAndOperationTimeoutNeverLoseTheReturnFact() {
        repeat(100) { iteration ->
            val record = OperationRecord<Int>(operationIdentity)
            record.tryStart(10L)
            val release = CountDownLatch(1)
            val returnDecision = AtomicReference<OperationReturnRecordingFact<Int>>()
            val timeoutDecision = AtomicReference<OperationTimeoutFenceFact<Int>>()
            val worker = Thread {
                release.await()
                returnDecision.set(record.recordReturned(iteration, 20L))
            }
            val watchdog = Thread {
                release.await()
                timeoutDecision.set(record.fenceOperationTimeout())
            }

            worker.start()
            watchdog.start()
            release.countDown()
            worker.join()
            watchdog.join()

            val snapshot = record.snapshot()
            assertEquals(iteration, snapshot.returnFact?.value)
            when (snapshot.completion.completion) {
                OperationCompletion.TimedOut -> {
                    val recorded = returnDecision.get()
                    assertTrue(
                        recorded is OperationReturnRecordingFact.Recorded<*> &&
                            recorded.completionDecision is OperationCompletionDecision.LateReturn<*>,
                    )
                }

                is OperationCompletion.Returned<*> -> {
                    val recorded = returnDecision.get()
                    val timeout = timeoutDecision.get()
                    assertTrue(
                        recorded is OperationReturnRecordingFact.Recorded<*> &&
                            recorded.completionDecision is OperationCompletionDecision.Completed<*>,
                    )
                    assertTrue(
                        timeout is OperationTimeoutFenceFact.Decided<*> &&
                            timeout.decision is OperationCompletionDecision.AlreadyCompleted<*>,
                    )
                }

                null -> error("Missing operation completion")
            }
        }
    }

    @Test
    fun concurrentOperationTimeoutFencesHaveExactlyOneWinner() {
        val record = OperationRecord<String>(operationIdentity)
        record.tryStart(10L)
        val release = CountDownLatch(1)
        val first = AtomicReference<OperationTimeoutFenceFact<String>>()
        val second = AtomicReference<OperationTimeoutFenceFact<String>>()
        val firstThread = Thread {
            release.await()
            first.set(record.fenceOperationTimeout())
        }
        val secondThread = Thread {
            release.await()
            second.set(record.fenceOperationTimeout())
        }

        firstThread.start()
        secondThread.start()
        release.countDown()
        firstThread.join()
        secondThread.join()

        val expected: Set<OperationTimeoutFenceFact<String>> = setOf(
            OperationTimeoutFenceFact.Decided(OperationCompletionDecision.TimedOut),
            OperationTimeoutFenceFact.Decided(
                OperationCompletionDecision.AlreadyCompleted(
                    completion = OperationCompletion.TimedOut,
                    retainedReturnFact = null,
                ),
            ),
        )
        assertEquals(
            expected,
            setOf(first.get(), second.get()),
        )
    }

    @Test
    fun concurrentCleanupClaimsHaveExactlyOneWinnerAfterReturn() {
        val record = OperationRecord<String>(operationIdentity)
        record.tryStart(10L)
        record.recordReturned("returned", 20L)
        val release = CountDownLatch(1)
        val first = AtomicReference<OperationCleanupClaim<OperationReturnFact<String>>>()
        val second = AtomicReference<OperationCleanupClaim<OperationReturnFact<String>>>()
        val firstThread = Thread {
            release.await()
            first.set(record.claimCleanup())
        }
        val secondThread = Thread {
            release.await()
            second.set(record.claimCleanup())
        }

        firstThread.start()
        secondThread.start()
        release.countDown()
        firstThread.join()
        secondThread.join()

        val fact = OperationReturnFact(OperationReturnKind.Returned, "returned", 20L, false)
        assertEquals(
            setOf(OperationCleanupClaim.Claimed(fact), OperationCleanupClaim.AlreadyClaimed(fact)),
            setOf(first.get(), second.get()),
        )
    }

    @Test
    fun timestampsAndTypedMarkersRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { OperationCancellationMarker(-1L) }

        val record = OperationRecord<String>(operationIdentity)
        assertThrows(IllegalArgumentException::class.java) { record.tryStart(startedAtNanos = -1L) }
        record.tryStart(startedAtNanos = 10L)
        assertThrows(IllegalArgumentException::class.java) {
            record.recordReturned(value = "returned", completedAtNanos = 9L)
        }

        assertEquals(OperationPhase.Started, record.snapshot().phase)
        assertNull(record.snapshot().returnFact)
        assertNull(record.snapshot().completion.completion)
    }

    private companion object {
        val operationIdentity = WorkIdentity(1L)
        val cancellationMarker = OperationCancellationMarker(9L)
    }
}
