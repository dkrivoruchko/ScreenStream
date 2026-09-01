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
    @Test
    fun enteredWrapperCannotRunBodyWhenDispatchLaterRejectsOrThrows() {
        exerciseEnteredWrapperRejection(dispatchFailure = null)
        exerciseEnteredWrapperRejection(dispatchFailure = IllegalStateException("dispatch failed after wrapper entry"))
    }

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

    private fun exerciseEnteredWrapperRejection(dispatchFailure: Exception?) {
        val dispatcher = EnteredBeforeRejectingDispatcher(dispatchFailure)
        val slot = SerialTaskSlot(dispatcher)
        val rejectedBodyEntered = AtomicBoolean(false)

        val rejected = slot.trySubmit(task = { rejectedBodyEntered.set(true) }) as SerialTaskSlot.Submission.Rejected
        dispatcher.awaitRejectedWrapperCompletion()

        assertFalse(rejectedBodyEntered.get())
        if (dispatchFailure == null) {
            assertNull(rejected.cause)
        } else {
            assertSame(dispatchFailure, rejected.cause)
        }

        val successorEntered = AtomicBoolean(false)
        assertSame(
            SerialTaskSlot.Submission.Accepted,
            slot.trySubmit(task = { successorEntered.set(true) }),
        )
        dispatcher.enterAcceptedAndAwaitCompletion()
        assertTrue(successorEntered.get())
    }

    private class EnteredBeforeRejectingDispatcher(
        private val dispatchFailure: Exception?,
    ) : NonInlineDispatcher {
        private var dispatchCount = 0
        private var rejectedWrapperThread: Thread? = null
        private var acceptedTask: Runnable? = null

        override fun tryDispatch(task: Runnable): Boolean {
            if (dispatchCount++ == 0) {
                val thread = Thread(task, "SerialTaskSlot-Rejected-Wrapper").apply { isDaemon = true }
                rejectedWrapperThread = thread
                thread.start()
                awaitWrapperPending(thread)
                dispatchFailure?.let { throw it }
                return false
            }
            check(acceptedTask == null) { "Successor task was already retained" }
            acceptedTask = task
            return true
        }

        fun awaitRejectedWrapperCompletion() {
            boundedJoin(checkNotNull(rejectedWrapperThread), "rejected wrapper")
        }

        fun enterAcceptedAndAwaitCompletion() {
            val task = checkNotNull(acceptedTask)
            acceptedTask = null
            val thread = Thread(task, "SerialTaskSlot-Accepted-Successor").apply { isDaemon = true }
            thread.start()
            boundedJoin(thread, "accepted successor")
        }

        private fun awaitWrapperPending(thread: Thread) {
            val deadlineNanos = System.nanoTime() + THREAD_TIMEOUT_NANOS
            while (thread.state != Thread.State.WAITING) {
                if (!thread.isAlive) throw AssertionError("Rejected wrapper returned before dispatch resolution")
                if (System.nanoTime() >= deadlineNanos) {
                    throw AssertionError("Rejected wrapper did not await dispatch resolution")
                }
                Thread.yield()
            }
        }

        private fun boundedJoin(thread: Thread, description: String) {
            try {
                thread.join(THREAD_TIMEOUT_MILLIS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted while joining $description", failure)
            }
            if (thread.isAlive) throw AssertionError("$description did not complete before the bounded join expired")
        }
    }

    private companion object {
        private const val THREAD_TIMEOUT_MILLIS = 5_000L
        private const val THREAD_TIMEOUT_NANOS = 5_000_000_000L
    }
}
