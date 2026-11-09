package io.screenstream.capture.testutil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

internal class DeterministicRuntimeHarnessTest {
    // Verification: TST-01
    @Test
    fun dispatcherHoldsAcceptedTaskUntilExplicitEntryOnDedicatedWorker() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val caller = Thread.currentThread()
            val enteredThread = AtomicReference<Thread?>()
            val ran = AtomicReference(false)

            assertTrue(
                dispatcher.tryDispatch {
                    enteredThread.set(Thread.currentThread())
                    ran.set(true)
                },
            )
            assertFalse(ran.get())
            assertTrue(dispatcher.pendingCount() == 1)

            val handle = dispatcher.enterNext() ?: error("accepted task was not retained")
            assertTrue(handle.awaitEntered())
            handle.awaitSuccessfulCompletion()
            assertTrue(ran.get())
            assertTrue(enteredThread.get() !== caller)
            assertTrue(handle.enteredThread !== caller)
            assertTrue(dispatcher.pendingCount() == 0)
        }
    }

    // Verification: TST-01
    @Test
    fun dispatcherExposesAcceptedRejectedAndThrownSubmissionOutcomes() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            dispatcher.enqueueReject()
            assertFalse(dispatcher.tryDispatch { error("rejected task entered") })

            val failure = IllegalStateException("dispatch failure")
            dispatcher.enqueueThrow(failure)
            try {
                dispatcher.tryDispatch { error("thrown task entered") }
                error("dispatch did not throw")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }

            assertTrue(dispatcher.tryDispatch { })
            assertTrue(
                dispatcher.submissions().map { it.kind } == listOf(
                    DispatchAttemptKind.Rejected,
                    DispatchAttemptKind.Thrown,
                    DispatchAttemptKind.Accepted,
                ),
            )
            val accepted = dispatcher.enterNext() ?: error("accepted task was not retained")
            accepted.awaitSuccessfulCompletion()
        }
    }

    // Verification: TST-01
    @Test
    fun delayedSchedulerRetainsAcceptedNeverEntryUntilExplicitLaterEntry() {
        ManualDelayedEntryScheduler().use { scheduler ->
            var neverEntered = false
            assertTrue(scheduler.trySchedule({ neverEntered = true }, 7L))
            val retained = scheduler.scheduledTasks().single()
            assertFalse(neverEntered)
            assertTrue(retained.delayNanos == 7L)
            assertTrue(retained.state == ScheduledTaskState.Accepted)
            assertFalse(retained.completed)
            assertTrue(scheduler.pendingCount() == 1)

            var enteredLater = false
            assertTrue(scheduler.trySchedule({ enteredLater = true }, 11L))
            val later = scheduler.scheduledTasks().last()
            assertTrue(scheduler.enter(later))
            later.awaitSuccessfulCompletion()
            assertTrue(enteredLater)
            assertFalse(neverEntered)
            assertTrue(retained.state == ScheduledTaskState.Accepted)
            assertFalse(retained.completed)
            assertTrue(scheduler.pendingCount() == 1)
        }
    }

    // Verification: TST-01
    @Test
    fun delayedSchedulerExposesRejectedAndThrownSubmissionOutcomes() {
        ManualDelayedEntryScheduler().use { scheduler ->
            scheduler.enqueueReject()
            assertFalse(scheduler.trySchedule({ error("rejected task entered") }, 0L))

            val failure = IllegalArgumentException("schedule failure")
            scheduler.enqueueThrow(failure)
            try {
                scheduler.trySchedule({ error("thrown task entered") }, 1L)
                error("schedule did not throw")
            } catch (actual: IllegalArgumentException) {
                assertSame(failure, actual)
            }
            assertTrue(
                scheduler.submissions().map { it.kind } == listOf(
                    ScheduleAttemptKind.Rejected,
                    ScheduleAttemptKind.Thrown,
                ),
            )
        }
    }

    // Verification: TST-01
    @Test
    fun completionWaitPreservesExactWorkerThrowableForExplicitAssertions() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val failure = AssertionError("worker failure")
            assertTrue(dispatcher.tryDispatch { throw failure })
            val handle = dispatcher.enterNext() ?: error("accepted task was not retained")
            assertTrue(handle.awaitEntered())
            assertSame(failure, handle.awaitCompletion())
            try {
                handle.awaitSuccessfulCompletion()
                error("successful completion did not propagate worker failure")
            } catch (actual: AssertionError) {
                assertSame(failure, actual)
            }
        }
    }

    // Verification: TST-01
    @Test
    fun clockCanBeSetAndAdvancedWithoutWallClockReads() {
        val clock = MutableElapsedRealtimeClock(12L)
        assertTrue(clock.nowNanos() == 12L)
        assertTrue(clock.advanceBy(8L) == 20L)
        clock.setNanos(-4L)
        assertTrue(clock.nowNanos() == -4L)
    }
}
