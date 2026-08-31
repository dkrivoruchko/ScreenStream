package io.screenstream.capture.internal.runtime

import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class SerialTaskSlotContractTest {
    // Verification: RUN-01
    @Test
    fun rejectionAndThrownExceptionReleaseTheExactAttempt() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val slot = SerialTaskSlot(dispatcher)
            dispatcher.enqueueReject()
            val rejected = slot.trySubmit(task = { fail("rejected task entered") }) as SerialTaskSlot.Submission.Rejected
            assertNull(rejected.cause)

            val failure = IllegalStateException("dispatch failed")
            dispatcher.enqueueThrow(failure)
            val thrown = slot.trySubmit(task = { fail("thrown task entered") }) as SerialTaskSlot.Submission.Rejected
            assertSame(failure, thrown.cause)

            assertSame(SerialTaskSlot.Submission.Accepted, slot.trySubmit(task = { }))
            val accepted = dispatcher.enterNext() ?: error("accepted task was not retained")
            accepted.awaitSuccessfulCompletion()
        }
    }

    // Verification: RUN-01
    @Test
    fun acceptedLaterEntryKeepsOneOccupancyUntilActualRelease() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val slot = SerialTaskSlot(dispatcher)
            val ran = AtomicBoolean(false)
            val releaseCount = AtomicInteger(0)

            assertSame(
                SerialTaskSlot.Submission.Accepted,
                slot.trySubmit(task = { ran.set(true) }, afterTaskReleased = releaseCount::incrementAndGet),
            )
            assertSame(
                SerialTaskSlot.Submission.Occupied,
                slot.trySubmit(task = { fail("second task entered while first was pending") }),
            )
            assertTrue(dispatcher.submissions().size == 1)
            assertFalse(ran.get())
            assertTrue(releaseCount.get() == 0)

            val accepted = dispatcher.enterNext() ?: error("accepted task was not retained")
            accepted.awaitSuccessfulCompletion()
            assertTrue(ran.get())
            assertTrue(releaseCount.get() == 1)

            assertSame(SerialTaskSlot.Submission.Accepted, slot.trySubmit(task = { }))
            val successor = dispatcher.enterNext() ?: error("successor was not retained")
            successor.awaitSuccessfulCompletion()
        }
    }

    // Verification: RUN-01
    @Test
    fun acceptedWithoutEntryRetainsTheExactOccupancyAndRunsNoReleaseHook() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val slot = SerialTaskSlot(dispatcher)
            val ran = AtomicBoolean(false)
            val releaseCount = AtomicInteger(0)

            assertSame(
                SerialTaskSlot.Submission.Accepted,
                slot.trySubmit(task = { ran.set(true) }, afterTaskReleased = releaseCount::incrementAndGet),
            )
            assertTrue(dispatcher.pendingCount() == 1)
            assertSame(
                SerialTaskSlot.Submission.Occupied,
                slot.trySubmit(task = { fail("successor entered while accepted task had not entered") }),
            )
            assertFalse(ran.get())
            assertTrue(releaseCount.get() == 0)
        }
    }

    // Verification: RUN-01
    @Test
    fun sameThreadReentrantEntryIsAContractViolationAndRunsNoTask() {
        var ran = false
        val dispatcher = NonInlineDispatcher { task ->
            task.run()
            true
        }
        val slot = SerialTaskSlot(dispatcher)

        assertThrows(IllegalStateException::class.java) {
            slot.trySubmit(task = { ran = true })
        }
        assertFalse(ran)
    }

    // Verification: RUN-01
    @Test
    fun uncontainedErrorPreservesIdentityWithoutRelease() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val slot = SerialTaskSlot(dispatcher)
            val failure = AssertionError("uncontained")
            val releaseCount = AtomicInteger(0)

            assertSame(
                SerialTaskSlot.Submission.Accepted,
                slot.trySubmit(task = { throw failure }, afterTaskReleased = releaseCount::incrementAndGet),
            )
            val accepted = dispatcher.enterNext() ?: error("accepted task was not retained")
            assertSame(failure, accepted.awaitCompletion())
            assertTrue(releaseCount.get() == 0)
            assertSame(
                SerialTaskSlot.Submission.Occupied,
                slot.trySubmit(task = { fail("successor entered after uncontained failure") }),
            )
        }
    }
}
