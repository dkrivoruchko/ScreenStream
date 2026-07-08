package dev.dmkr.screencaptureengine.internal.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlLaneAbandonmentTest {
    @OptIn(BlockingProjectionTargetGlAccess::class)
    @Test
    fun abandonedLaneRejectsNewBlockingAndSuspendWork() = runTest {
        val owner = GlLaneContextOwner("test-gl-abandoned-rejects")

        owner.abandonGlLane()

        val blockingFailure = assertThrows(IllegalStateException::class.java) {
            owner.executeCurrentBlocking {
                throw AssertionError("Abandoned GL lane should not execute blocking work.")
            }
        }
        val suspendFailure = expectIllegalState {
            owner.executeCurrent {
                throw AssertionError("Abandoned GL lane should not execute suspend work.")
            }
        }

        assertEquals("GL lane is abandoned.", blockingFailure.message)
        assertEquals("GL lane is abandoned.", suspendFailure.message)
        assertFalse(
            owner.retireGlResources("test abandoned retirement") {
                throw AssertionError("Abandoned GL lane should not accept retirement work.")
            },
        )
        owner.close()
    }

    @Test
    fun closedLaneRejectsNewRetirementWork() {
        val owner = GlLaneContextOwner("test-gl-closed-retirement-rejects")

        owner.close()

        assertFalse(
            owner.retireGlResources("test closed retirement") {
                throw AssertionError("Closed GL lane should not accept retirement work.")
            },
        )
    }

    @Test
    fun closeAfterAbandonDoesNotWaitForBusyGlThread() {
        val owner = GlLaneContextOwner("test-gl-abandoned-close")
        val entered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val closed = CountDownLatch(1)
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before abandon close") {
                entered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        owner.abandonGlLane()
        val closeThread = Thread {
            owner.close()
            closed.countDown()
        }
        closeThread.start()

        try {
            assertTrue(closed.await(500, TimeUnit.MILLISECONDS))
        } finally {
            releaseBusyWork.countDown()
            closeThread.joinOrFail("close after abandon")
            owner.setGlRetirementRunnerForTest(null)
        }
    }


    @Test
    fun abandonWhileCloseIsWaitingForPostedGlCleanupReturnsPromptly() {
        val owner = GlLaneContextOwner("test-gl-close-then-abandon")
        val busyWorkEntered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before close abandon") {
                busyWorkEntered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(busyWorkEntered.await(5, TimeUnit.SECONDS))

        val closeThread = Thread {
            closeStarted.countDown()
            owner.close()
            closed.countDown()
        }
        closeThread.start()

        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))

            owner.abandonGlLane()

            assertTrue(closed.await(500, TimeUnit.MILLISECONDS))
        } finally {
            releaseBusyWork.countDown()
            closeThread.joinOrFail("close waiting for posted cleanup")
            owner.setGlRetirementRunnerForTest(null)
        }
    }

    @Test
    fun queuedRetirementWorkIsDroppedByAbandonWithoutBlockingCaller() {
        val owner = GlLaneContextOwner("test-gl-retirement-abandon")
        val busyWorkEntered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val retirementReturned = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val retirementResult = AtomicReference<Boolean?>()
        val retirementBlockRan = AtomicBoolean()
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before queued abandon") {
                busyWorkEntered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(busyWorkEntered.await(5, TimeUnit.SECONDS))

        val retirementThread = Thread {
            retirementResult.set(
                owner.retireGlResources("test queued retirement") {
                    retirementBlockRan.set(true)
                },
            )
            retirementReturned.countDown()
        }.apply { start() }

        try {
            assertTrue(retirementReturned.await(500, TimeUnit.MILLISECONDS))
            assertEquals(true, retirementResult.get())
            assertFalse(retirementBlockRan.get())

            owner.abandonGlLane()

            assertFalse(retirementBlockRan.get())

            val closeThread = Thread {
                owner.close()
                closeReturned.countDown()
            }.apply { start() }
            try {
                assertTrue(closeReturned.await(500, TimeUnit.MILLISECONDS))
            } finally {
                closeThread.joinOrFail("close after queued retirement abandon")
            }
        } finally {
            releaseBusyWork.countDown()
            retirementThread.joinOrFail("queued retirement caller")
            owner.setGlRetirementRunnerForTest(null)
        }

        assertFalse(retirementBlockRan.get())
    }

    @Test
    fun acceptedQueuedRetirementRunsDuringOrdinaryCloseDrain() {
        val owner = GlLaneContextOwner("test-gl-retirement-close-drain")
        val busyWorkEntered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val releaseQueuedRetirement = CountDownLatch(1)
        val retirementReturned = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val queuedRetirementStarted = CountDownLatch(1)
        val retirementResult = AtomicReference<Boolean?>()
        val retirementBlockRan = AtomicBoolean()
        val retirementTimedOut = AtomicBoolean()
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before close drain") {
                busyWorkEntered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(busyWorkEntered.await(5, TimeUnit.SECONDS))

        val retirementThread = Thread {
            retirementResult.set(
                owner.retireGlResources("test queued retirement close drain") {
                    retirementBlockRan.set(true)
                    queuedRetirementStarted.countDown()
                    if (!releaseQueuedRetirement.await(5, TimeUnit.SECONDS)) {
                        retirementTimedOut.set(true)
                    }
                },
            )
            retirementReturned.countDown()
        }.apply { start() }

        try {
            assertTrue(retirementReturned.await(500, TimeUnit.MILLISECONDS))
            assertEquals(true, retirementResult.get())
            assertFalse(retirementBlockRan.get())

            val closeThread = Thread {
                owner.close()
                closeReturned.countDown()
            }.apply { start() }
            try {
                assertTrue(
                    waitUntilRetirementWorkIsRejected(owner, "test close drain started probe"),
                )
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))

                releaseBusyWork.countDown()

                assertTrue(queuedRetirementStarted.await(5, TimeUnit.SECONDS))
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))

                releaseQueuedRetirement.countDown()

                assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
                assertTrue(retirementBlockRan.get())
                assertFalse(retirementTimedOut.get())
            } finally {
                releaseBusyWork.countDown()
                releaseQueuedRetirement.countDown()
                closeThread.joinOrFail("close draining queued retirement")
            }
        } finally {
            releaseBusyWork.countDown()
            releaseQueuedRetirement.countDown()
            retirementThread.joinOrFail("queued retirement caller")
            owner.setGlRetirementRunnerForTest(null)
        }
    }

    @Test
    fun retirementFromGlThreadIsQueuedAndDoesNotRunInline() {
        val owner = GlLaneContextOwner("test-gl-retirement-on-gl-thread")
        val releaseGlCallback = CountDownLatch(1)
        val retirementReturned = CountDownLatch(1)
        val outerGlWorkReturned = CountDownLatch(1)
        val queuedRetirementRan = CountDownLatch(1)
        val retirementResult = AtomicReference<Boolean?>()
        val retirementBlockRan = AtomicBoolean()
        val inlineBlockRanAtReturn = AtomicBoolean()
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test outer GL-thread retirement") {
                retirementResult.set(
                    owner.retireGlResources("test GL-thread retirement") {
                        retirementBlockRan.set(true)
                        queuedRetirementRan.countDown()
                    },
                )
                inlineBlockRanAtReturn.set(retirementBlockRan.get())
                retirementReturned.countDown()
                check(releaseGlCallback.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release outer GL callback."
                }
                outerGlWorkReturned.countDown()
            },
        )

        try {
            assertTrue(retirementReturned.await(500, TimeUnit.MILLISECONDS))
            assertEquals(true, retirementResult.get())
            assertFalse(inlineBlockRanAtReturn.get())
            assertFalse(retirementBlockRan.get())

            releaseGlCallback.countDown()

            assertTrue(outerGlWorkReturned.await(5, TimeUnit.SECONDS))
            assertTrue(queuedRetirementRan.await(5, TimeUnit.SECONDS))
            assertTrue(retirementBlockRan.get())
        } finally {
            releaseGlCallback.countDown()
            owner.setGlRetirementRunnerForTest(null)
            owner.close()
        }
    }

    @Test
    fun acceptedRetirementFailureIsReportedWithoutThrowingCaller() {
        val reportedFailure = AtomicReference<Throwable?>()
        val reported = CountDownLatch(1)
        val owner = GlLaneContextOwner(
            threadName = "test-gl-retirement-failure-diagnostics",
            glRetirementFailureSink = { failure ->
                reportedFailure.set(failure)
                reported.countDown()
            },
        )
        val cleanupFailure = IllegalStateException("delete failed")
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        try {
            assertTrue(
                owner.retireGlResources("failing cleanup") {
                    throw cleanupFailure
                },
            )

            assertTrue(reported.await(5, TimeUnit.SECONDS))
            val failure = reportedFailure.get()
            assertEquals("GL retirement failed for failing cleanup.", failure?.message)
            assertSame(cleanupFailure, failure?.cause)
        } finally {
            owner.setGlRetirementRunnerForTest(null)
            owner.close()
        }
    }

    @Test
    fun retirementFailureSinkThrowingSameFailureDoesNotStopLaterRetirement() {
        val reportedFailure = AtomicReference<Throwable?>()
        val reported = CountDownLatch(1)
        val laterRetirementRan = CountDownLatch(1)
        val cleanupFailure = IllegalStateException("delete failed")
        val owner = GlLaneContextOwner(
            threadName = "test-gl-retirement-failure-sink-failure",
            glRetirementFailureSink = { failure ->
                reportedFailure.set(failure)
                reported.countDown()
                throw failure
            },
        )
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        try {
            assertTrue(
                owner.retireGlResources("self-suppression cleanup") {
                    throw cleanupFailure
                },
            )
            assertTrue(
                owner.retireGlResources("later cleanup") {
                    laterRetirementRan.countDown()
                },
            )

            assertTrue(reported.await(5, TimeUnit.SECONDS))
            assertTrue(laterRetirementRan.await(5, TimeUnit.SECONDS))
            val failure = reportedFailure.get()
            assertEquals("GL retirement failed for self-suppression cleanup.", failure?.message)
            assertSame(cleanupFailure, failure?.cause)
            assertEquals(0, failure?.suppressed?.size)
        } finally {
            owner.setGlRetirementRunnerForTest(null)
            owner.close()
        }
    }

    @Test
    fun queuedSuspendWorkFailsPromptlyWhenGlLaneIsAbandoned() {
        val owner = GlLaneContextOwner("test-gl-suspend-abandon")
        val busyWorkEntered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val suspendPosted = CountDownLatch(1)
        val suspendCompleted = CountDownLatch(1)
        val suspendBlockRan = AtomicBoolean()
        val suspendFailure = AtomicReference<Throwable?>()
        owner.setSuspendedCallPostObserverForTest {
            suspendPosted.countDown()
        }
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before suspend abandon") {
                busyWorkEntered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(busyWorkEntered.await(5, TimeUnit.SECONDS))

        val suspendThread = Thread {
            runBlocking {
                suspendFailure.set(
                    runCatching {
                        owner.executeCurrent {
                            suspendBlockRan.set(true)
                        }
                    }.exceptionOrNull(),
                )
            }
            suspendCompleted.countDown()
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(suspendPosted.await(5, TimeUnit.SECONDS))
            assertEquals(1, owner.abandonWaiterCountForTest())

            owner.abandonGlLane()

            assertTrue(suspendCompleted.await(500, TimeUnit.MILLISECONDS))
            val failure = suspendFailure.get()
            assertTrue(failure is IllegalStateException)
            assertEquals("GL lane is abandoned.", failure?.message)
            assertEquals(0, owner.abandonWaiterCountForTest())
            assertFalse(suspendBlockRan.get())
        } finally {
            owner.setSuspendedCallPostObserverForTest(null)
            releaseBusyWork.countDown()
            suspendThread.joinOrFail("queued suspend work")
            owner.setGlRetirementRunnerForTest(null)
        }
    }

    @Test
    fun queuedSuspendWorkCancelledBeforeAbandonRemainsCancelledAndDoesNotRun() = runTest {
        val owner = GlLaneContextOwner("test-gl-suspend-cancel-then-abandon")
        val busyWorkEntered = CountDownLatch(1)
        val releaseBusyWork = CountDownLatch(1)
        val suspendPosted = CountDownLatch(1)
        val suspendBlockRan = AtomicBoolean()
        owner.setSuspendedCallPostObserverForTest {
            suspendPosted.countDown()
        }
        owner.setGlRetirementRunnerForTest { _, block ->
            block(NoOpGlLaneScope)
        }

        assertTrue(
            owner.retireGlResources("test busy retirement before suspend cancel") {
                busyWorkEntered.countDown()
                check(releaseBusyWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release busy GL work."
                }
            },
        )
        assertTrue(busyWorkEntered.await(5, TimeUnit.SECONDS))

        val suspendJob = launch(Dispatchers.Default) {
            owner.executeCurrent {
                suspendBlockRan.set(true)
            }
        }

        try {
            assertTrue(suspendPosted.await(5, TimeUnit.SECONDS))
            assertEquals(1, owner.abandonWaiterCountForTest())

            suspendJob.cancel()
            suspendJob.join()

            assertTrue(suspendJob.isCancelled)
            assertEquals(0, owner.abandonWaiterCountForTest())
            assertFalse(suspendBlockRan.get())

            owner.abandonGlLane()

            assertTrue(suspendJob.isCancelled)
            assertEquals(0, owner.abandonWaiterCountForTest())
            assertFalse(suspendBlockRan.get())
        } finally {
            owner.setSuspendedCallPostObserverForTest(null)
            releaseBusyWork.countDown()
            suspendJob.cancel()
            suspendJob.join()
            owner.setGlRetirementRunnerForTest(null)
        }

        assertFalse(suspendBlockRan.get())
        assertTrue(suspendJob.isCancelled)
    }

    @Test
    fun projectionTargetOwnerAbandonWhileCloseIsWaitingForActiveWorkReturnsPromptly() {
        val glLane = CleanupBlockingGlLane()
        val owner = ProjectionTargetOwner(glLane)
        val creationFailure = AtomicReference<Throwable?>()
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        glLane.blockExecuteCurrentBlocking.set(true)

        val createThread = Thread {
            creationFailure.set(
                runCatching {
                    owner.createTarget(width = 16, height = 16, densityDpi = 320)
                }.exceptionOrNull(),
            )
        }.apply { start() }
        assertTrue(glLane.executeCurrentBlockingEntered.await(5, TimeUnit.SECONDS))

        val closeThread = Thread {
            closeStarted.countDown()
            owner.close()
            closed.countDown()
        }
        closeThread.start()

        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))

            owner.abandonGlLane()

            assertTrue(closed.await(500, TimeUnit.MILLISECONDS))
            assertTrue(glLane.closeCalled.get())
        } finally {
            glLane.releaseExecuteCurrentBlocking.countDown()
            createThread.joinOrFail("projection target creation")
            closeThread.joinOrFail("projection target close")
        }
        assertTrue(creationFailure.get() is IllegalStateException)
    }

    @Test
    fun projectionTargetOwnerCloseAfterAbandonDoesNotDispatchGlCleanup() {
        val glLane = CleanupBlockingGlLane()
        val owner = ProjectionTargetOwner(glLane)
        val closed = CountDownLatch(1)

        owner.abandonGlLane()
        val closeThread = Thread {
            owner.close()
            closed.countDown()
        }
        closeThread.start()

        try {
            assertTrue(closed.await(500, TimeUnit.MILLISECONDS))
            assertFalse(glLane.cleanupDispatched.get())
            assertTrue(glLane.closeCalled.get())
        } finally {
            glLane.releaseCleanup.countDown()
            closeThread.joinOrFail("projection target close after abandon")
        }
    }

    private suspend fun expectIllegalState(block: suspend () -> Unit): IllegalStateException =
        try {
            block()
            throw AssertionError("Expected IllegalStateException")
        } catch (exception: IllegalStateException) {
            exception
        }

    private class CleanupBlockingGlLane : ProjectionTargetGlLane, GlLaneAbandonment {
        val cleanupDispatched = AtomicBoolean()
        val closeCalled = AtomicBoolean()
        val releaseCleanup = CountDownLatch(1)
        val blockExecuteCurrentBlocking = AtomicBoolean()
        val executeCurrentBlockingEntered = CountDownLatch(1)
        val releaseExecuteCurrentBlocking = CountDownLatch(1)
        override val isGlLaneAbandoned: Boolean
            get() = abandoned.get()

        private val abandoned = AtomicBoolean()
        private val scope = Scope()

        override fun isOnGlThread(): Boolean = false

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun <T> executeCurrentBlocking(block: GlLaneScope.() -> T): T {
            if (blockExecuteCurrentBlocking.get()) {
                executeCurrentBlockingEntered.countDown()
                check(releaseExecuteCurrentBlocking.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release blocking GL execution."
                }
            }
            return block(scope)
        }

        override suspend fun <T> executeCurrent(
            onCancellation: (T) -> Unit,
            block: GlLaneScope.() -> T,
        ): T =
            suspendCancellableCoroutine { continuation ->
                continuation.resume(block(scope)) { _, rejectedResult, _ ->
                    onCancellation(rejectedResult)
                }
            }

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun executeCurrentIfCreatedBlocking(block: GlLaneScope.() -> Unit) {
            cleanupDispatched.set(true)
            check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release projection target cleanup."
            }
            block(scope)
        }

        override fun abandonGlLane() {
            abandoned.set(true)
        }

        override fun close() {
            closeCalled.set(true)
        }

        private class Scope : GlLaneScope {
            override fun targetSizeLimits(): ProjectionTargetSizeLimits =
                ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

            override fun checkCurrentContext(operation: String) = Unit

            override fun checkGl(operation: String) = Unit
        }
    }

    private object NoOpGlLaneScope : GlLaneScope {
        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

        override fun checkCurrentContext(operation: String) = Unit

        override fun checkGl(operation: String) = Unit
    }

    private fun waitUntilRetirementWorkIsRejected(
        owner: GlResourceRetirementLane,
        label: String,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (!owner.retireGlResources(label) { }) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun Thread.joinOrFail(description: String) {
        join(5_000L)
        assertFalse("$description thread did not finish", isAlive)
    }
}
