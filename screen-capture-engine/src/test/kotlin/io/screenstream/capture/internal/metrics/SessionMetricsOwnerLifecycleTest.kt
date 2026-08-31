package io.screenstream.capture.internal.metrics

import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.runtime.SerialTaskSlot
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.DispatchAttemptKind
import io.screenstream.capture.testutil.DispatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.AutoCloseable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class SessionMetricsOwnerLifecycleTest {
    /*
     * This fixture arranges the owner-visible ordering in which a real slot release completes before the injected
     * submit call returns. It does not claim that the inner SerialTaskSlot released before its own trySubmit returned;
     * that production ordering is established from the slot source. Fixture checks arrange the schedule, while the
     * MET-01 assertions below judge only the maintained owner outcome.
     */
    // Verification: MET-01
    @Test
    fun releaseBeforeSubmitReturnPreservesPendingControlNotification() {
        ReleaseBeforeSubmitReturnTurnSubmitter().use { turnSubmitter ->
            val unusedDispatcher = NonInlineDispatcher { error("submitTurn override must bypass the worker dispatcher") }
            val attachmentCalls = AtomicInteger()
            val controlRequests = AtomicInteger()
            val owner = SessionMetricsOwner(
                workerDispatcher = unusedDispatcher,
                sourceSelection = explicit {
                    attachmentCalls.incrementAndGet()
                    RecordingHandle()
                },
                requestControlTurn = controlRequests::incrementAndGet,
                submitTurn = turnSubmitter::trySubmit,
            )

            owner.attach()

            assertEquals(1, attachmentCalls.get())
            assertEquals(1, controlRequests.get())
            val snapshot = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Live, snapshot.lifecycle)
            assertTrue(snapshot.handleAdopted)
            assertNull(snapshot.failure)
        }
    }

    // Verification: MET-01
    @Test
    fun inlineAndForeignCallbacksBeforeHandleReturnConflateToLatestSnapshot() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val first = CaptureMetrics(widthPx = 100, heightPx = 200, densityDpi = 300)
            val latest = CaptureMetrics(widthPx = 400, heightPx = 500, densityDpi = 600)
            val handle = RecordingHandle()
            val controlRequests = AtomicInteger()
            val source = CaptureMetricsSource { observer ->
                observer.onMetricsChanged(first)
                observer.onMetricsChanged(CaptureMetrics(100, 200, 300))
                val foreignCompleted = CountDownLatch(1)
                val foreignFailure = AtomicReference<Throwable?>()
                val foreign = Thread(
                    {
                        try {
                            observer.onMetricsChanged(latest)
                        } catch (failure: Throwable) {
                            foreignFailure.set(failure)
                        } finally {
                            foreignCompleted.countDown()
                        }
                    },
                    "Metrics-Test-Foreign",
                ).apply { isDaemon = true }
                foreign.start()
                try {
                    check(foreignCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "foreign Metrics callback did not complete"
                    }
                } finally {
                    foreign.interrupt()
                    foreign.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                }
                check(!foreign.isAlive) { "foreign Metrics callback did not terminate" }
                foreignFailure.get()?.let { throw it }
                handle
            }
            val owner = SessionMetricsOwner(dispatcher, explicit(source), controlRequests::incrementAndGet)

            owner.attach()
            enterOne(dispatcher)

            val snapshot = owner.readSnapshot()
            assertSame(latest, snapshot.metrics)
            assertEquals(MetricsAttachmentLifecycle.Live, snapshot.lifecycle)
            assertTrue(snapshot.handleAdopted)
            assertTrue(snapshot.isReady(requireCompletionCloseSettlement = true))
            assertEquals(1, dispatcher.pendingCount())
            assertEquals(2, dispatcher.submissions().size)

            drain(dispatcher)
            assertEquals(1, controlRequests.get())
            assertEquals(0, handle.closeCount.get())
        }
    }

    // Verification: MET-01
    @Test
    fun positiveLossRecoveryAndCompletionKeepSnapshotsFreshAndFenceLateIngress() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            lateinit var observer: CaptureMetricsSource.Observer
            val handle = RecordingHandle()
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit { attachedObserver ->
                    observer = attachedObserver
                    handle
                },
                requestControlTurn = { },
            )

            val initial = owner.readSnapshot()
            assertNull(initial.metrics)

            owner.attach()
            drain(dispatcher)
            val adoptedUnavailable = owner.readSnapshot()
            assertNotSame(initial, adoptedUnavailable)
            assertNull(adoptedUnavailable.metrics)

            val metrics = CaptureMetrics(10, 20, 30)
            observer.onMetricsChanged(metrics)
            drain(dispatcher)
            val available = owner.readSnapshot()
            assertNotSame(adoptedUnavailable, available)
            assertSame(metrics, available.metrics)

            observer.onMetricsChanged(CaptureMetrics(10, 20, 30))
            val duplicateAvailable = owner.readSnapshot()
            assertEquals(metrics, duplicateAvailable.metrics)
            assertEquals(MetricsAttachmentLifecycle.Live, duplicateAvailable.lifecycle)
            assertTrue(duplicateAvailable.handleAdopted)
            assertTrue(duplicateAvailable.isReady(requireCompletionCloseSettlement = true))
            assertNull(duplicateAvailable.failure)

            observer.onMetricsChanged(null)
            drain(dispatcher)
            val unavailable = owner.readSnapshot()
            assertNotSame(available, unavailable)
            assertNull(unavailable.metrics)
            assertFalse(unavailable.isReady(requireCompletionCloseSettlement = false))

            observer.onMetricsChanged(null)
            val duplicateUnavailable = owner.readSnapshot()
            assertNull(duplicateUnavailable.metrics)
            assertEquals(MetricsAttachmentLifecycle.Live, duplicateUnavailable.lifecycle)
            assertTrue(duplicateUnavailable.handleAdopted)
            assertFalse(duplicateUnavailable.isReady(requireCompletionCloseSettlement = false))
            assertNull(duplicateUnavailable.failure)

            val recoveredMetrics = CaptureMetrics(40, 50, 60)
            observer.onMetricsChanged(recoveredMetrics)
            drain(dispatcher)
            val recovered = owner.readSnapshot()
            assertNotSame(unavailable, recovered)
            assertSame(recoveredMetrics, recovered.metrics)
            assertTrue(recovered.isReady(requireCompletionCloseSettlement = true))

            observer.onComplete()
            val completing = owner.readSnapshot()
            assertNotSame(recovered, completing)
            assertSame(recoveredMetrics, completing.metrics)
            assertEquals(MetricsAttachmentLifecycle.Completed, completing.lifecycle)
            assertFalse(completing.completionCloseSettled)

            observer.onMetricsChanged(CaptureMetrics(70, 80, 90))
            observer.onFailure(IllegalStateException("late failure"))
            observer.onComplete()
            assertSame(completing, owner.readSnapshot())

            drain(dispatcher)
            val completed = owner.readSnapshot()
            assertNotSame(completing, completed)
            assertSame(recoveredMetrics, completed.metrics)
            assertEquals(MetricsAttachmentLifecycle.Completed, completed.lifecycle)
            assertTrue(completed.completionCloseSettled)
            assertNull(completed.failure)
            assertEquals(1, handle.closeCount.get())

            observer.onMetricsChanged(null)
            observer.onFailure(IllegalArgumentException("later failure"))
            observer.onComplete()
            assertSame(completed, owner.readSnapshot())
        }
    }

    // Verification: MET-01
    @Test
    fun enteredBlockingCompletionCloseRetainsExactHandleAndFencesLateIngress() {
        val dispatcher = ControlledNonInlineDispatcher(threadName = "Metrics-Blocking-Close")
        val closeEntered = CountDownLatch(1)
        val allowCloseReturn = CountDownLatch(1)
        val enteredHandle = AtomicReference<AutoCloseable?>()
        val closeCount = AtomicInteger()
        var closeTask: ControlledNonInlineDispatcher.TaskHandle? = null
        dispatcher.use {
            try {
                lateinit var observer: CaptureMetricsSource.Observer
                lateinit var exactHandle: AutoCloseable
                exactHandle = AutoCloseable {
                    enteredHandle.set(exactHandle)
                    closeCount.incrementAndGet()
                    closeEntered.countDown()
                    allowCloseReturn.await()
                }
                val metrics = CaptureMetrics(10, 20, 30)
                val owner = SessionMetricsOwner(
                    dispatcher,
                    explicit { attachedObserver ->
                        observer = attachedObserver
                        exactHandle
                    },
                    requestControlTurn = { },
                )

                owner.attach()
                enterOne(dispatcher)
                observer.onMetricsChanged(metrics)
                observer.onComplete()

                enterOne(dispatcher)
                closeTask = dispatcher.enterNext() ?: error("completion close task was not retained")
                assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))

                val whileCloseEntered = owner.readSnapshot()
                assertSame(exactHandle, enteredHandle.get())
                assertSame(metrics, whileCloseEntered.metrics)
                assertEquals(MetricsAttachmentLifecycle.Completed, whileCloseEntered.lifecycle)
                assertFalse(whileCloseEntered.completionCloseSettled)
                assertFalse(whileCloseEntered.isReady(requireCompletionCloseSettlement = true))
                assertTrue(whileCloseEntered.isReady(requireCompletionCloseSettlement = false))

                observer.onMetricsChanged(null)
                observer.onFailure(IllegalStateException("late failure"))
                observer.onComplete()
                assertSame(whileCloseEntered, owner.readSnapshot())
                assertEquals(1, closeCount.get())

                allowCloseReturn.countDown()
                closeTask.awaitSuccessfulCompletion()
                drain(dispatcher)

                val settled = owner.readSnapshot()
                assertNotSame(whileCloseEntered, settled)
                assertSame(metrics, settled.metrics)
                assertEquals(MetricsAttachmentLifecycle.Completed, settled.lifecycle)
                assertTrue(settled.completionCloseSettled)
                assertTrue(settled.isReady(requireCompletionCloseSettlement = true))
                assertNull(settled.failure)

                owner.retire()
                drain(dispatcher)
                assertEquals(1, closeCount.get())
            } finally {
                allowCloseReturn.countDown()
                closeTask?.awaitSuccessfulCompletion()
            }
        }
    }

    // Verification: MET-01
    @Test
    fun explicitSelectionSubscribesExactSourceOnceAndAdoptsInlineCompletionHandleOnce() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val metrics = CaptureMetrics(10, 20, 30)
            val handle = RecordingHandle()
            val subscribeCalls = AtomicInteger()
            val controlCloseCounts = ArrayList<Int>()
            val source = CaptureMetricsSource { observer ->
                subscribeCalls.incrementAndGet()
                observer.onMetricsChanged(metrics)
                observer.onComplete()
                handle
            }
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit(source),
                requestControlTurn = { controlCloseCounts += handle.closeCount.get() },
            )

            owner.attach()
            enterOne(dispatcher)

            val beforeClose = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Completed, beforeClose.lifecycle)
            assertTrue(beforeClose.handleAdopted)
            assertFalse(beforeClose.completionCloseSettled)
            assertFalse(beforeClose.isReady(requireCompletionCloseSettlement = true))
            assertTrue(beforeClose.isReady(requireCompletionCloseSettlement = false))

            drain(dispatcher)

            val afterClose = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Completed, afterClose.lifecycle)
            assertTrue(afterClose.completionCloseSettled)
            assertTrue(afterClose.isReady(requireCompletionCloseSettlement = true))
            assertEquals(1, subscribeCalls.get())
            assertEquals(1, handle.closeCount.get())
            assertEquals(listOf(0, 1), controlCloseCounts)

            owner.retire()
            owner.retire()
            drain(dispatcher)
            val secondAttachFailure = try {
                owner.attach()
                null
            } catch (failure: IllegalStateException) {
                failure
            }
            assertTrue(secondAttachFailure != null)
            assertEquals(1, subscribeCalls.get())
            assertEquals(1, handle.closeCount.get())
        }
    }

    // Verification: MET-01
    @Test
    fun failureTreatsErrorAsOpaqueDataAndClosesHandleOnce() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val failure = AssertionError("source data")
            val handle = RecordingHandle()
            val controlRequests = AtomicInteger()
            val source = CaptureMetricsSource { observer ->
                observer.onFailure(failure)
                handle
            }
            val owner = SessionMetricsOwner(dispatcher, explicit(source), controlRequests::incrementAndGet)

            owner.attach()
            enterOne(dispatcher)

            val snapshot = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Failed, snapshot.lifecycle)
            assertSame(failure, snapshot.failure)
            assertTrue(snapshot.handleAdopted)

            drain(dispatcher)
            val controlRequestsBeforeRetirement = controlRequests.get()
            owner.retire()
            drain(dispatcher)

            val retiredSnapshot = owner.readSnapshot()
            assertNotSame(snapshot, retiredSnapshot)
            assertEquals(MetricsAttachmentLifecycle.Retired, retiredSnapshot.lifecycle)
            assertNull(retiredSnapshot.failure)
            assertFalse(retiredSnapshot.isReady(requireCompletionCloseSettlement = false))
            assertEquals(1, handle.closeCount.get())
            assertEquals(controlRequestsBeforeRetirement, controlRequests.get())
        }
    }

    // Verification: MET-01
    @Test
    fun subscribeCloseDispatchAndControlExceptionsBecomeExactOwnerFailure() {
        val subscribeFailure = IllegalStateException("subscribe")
        assertAttachmentFailure(subscribeFailure) { throw subscribeFailure }

        val closeFailure = IllegalArgumentException("close")
        ControlledNonInlineDispatcher().use { closeDispatcher ->
            val handle = RecordingHandle(closeFailure)
            val owner = SessionMetricsOwner(
                closeDispatcher,
                explicit { observer ->
                    observer.onMetricsChanged(CaptureMetrics(1, 2, 3))
                    observer.onComplete()
                    handle
                },
                requestControlTurn = { },
            )
            owner.attach()
            drain(closeDispatcher)
            assertSame(closeFailure, owner.readSnapshot().failure)
            assertEquals(MetricsAttachmentLifecycle.Failed, owner.readSnapshot().lifecycle)
            assertEquals(1, handle.closeCount.get())
            owner.retire()
            drain(closeDispatcher)
            assertEquals(1, handle.closeCount.get())
        }

        val dispatchFailure = IllegalStateException("dispatch")
        ControlledNonInlineDispatcher(DispatchOutcome.Throw(dispatchFailure)).use { dispatching ->
            val subscribeCalls = AtomicInteger()
            val owner = SessionMetricsOwner(
                dispatching,
                explicit {
                    subscribeCalls.incrementAndGet()
                    RecordingHandle()
                },
                requestControlTurn = { },
            )
            owner.attach()
            assertSame(dispatchFailure, owner.readSnapshot().failure)
            assertEquals(MetricsAttachmentLifecycle.Failed, owner.readSnapshot().lifecycle)
            val secondAttachFailure = try {
                owner.attach()
                null
            } catch (failure: IllegalStateException) {
                failure
            }
            assertTrue(secondAttachFailure != null)
            assertEquals(0, subscribeCalls.get())
            assertEquals(1, dispatching.submissions().size)
            assertEquals(0, dispatching.submissions().count { it.kind == DispatchAttemptKind.Accepted })
            assertEquals(0, dispatching.pendingCount())
        }

        val controlFailure = IllegalStateException("control")
        ControlledNonInlineDispatcher().use { controlDispatcher ->
            val handle = RecordingHandle()
            val owner = SessionMetricsOwner(
                controlDispatcher,
                explicit { observer ->
                    observer.onMetricsChanged(CaptureMetrics(3, 4, 5))
                    handle
                },
                requestControlTurn = { throw controlFailure },
            )
            owner.attach()
            drain(controlDispatcher)
            assertSame(controlFailure, owner.readSnapshot().failure)
            assertEquals(MetricsAttachmentLifecycle.Failed, owner.readSnapshot().lifecycle)
            assertEquals(1, handle.closeCount.get())
        }
    }

    // Verification: MET-01
    @Test
    fun definiteDispatchRejectionFailsWithoutRetryAndNotifiesControlDirectly() {
        ControlledNonInlineDispatcher(DispatchOutcome.Reject).use { dispatcher ->
            val controlRequests = AtomicInteger()
            val subscribeCalls = AtomicInteger()
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit {
                    subscribeCalls.incrementAndGet()
                    RecordingHandle()
                },
                controlRequests::incrementAndGet,
            )

            owner.attach()

            val snapshot = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Failed, snapshot.lifecycle)
            assertTrue(snapshot.failure is IllegalStateException)
            val secondAttachFailure = try {
                owner.attach()
                null
            } catch (failure: IllegalStateException) {
                failure
            }
            assertTrue(secondAttachFailure != null)
            assertEquals(0, subscribeCalls.get())
            assertEquals(1, dispatcher.submissions().size)
            assertEquals(0, dispatcher.submissions().count { it.kind == DispatchAttemptKind.Accepted })
            assertEquals(0, dispatcher.pendingCount())
            assertEquals(1, controlRequests.get())
        }
    }

    // Verification: MET-01
    @Test
    fun laterDispatchRejectionPreservesAdoptionAndRunsNoRejectedSuccessor() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val handle = RecordingHandle()
            val controlRequests = AtomicInteger()
            val owner = SessionMetricsOwner(dispatcher, explicit { handle }, controlRequests::incrementAndGet)

            owner.attach()
            dispatcher.enqueueReject()
            enterOne(dispatcher)

            val snapshot = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Failed, snapshot.lifecycle)
            assertTrue(snapshot.handleAdopted)
            assertTrue(snapshot.failure is IllegalStateException)
            assertEquals(2, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())
            assertEquals(0, handle.closeCount.get())
            assertEquals(1, controlRequests.get())
        }
    }

    // Verification: MET-01
    @Test
    fun sameThreadEntryIsAContractViolationAndRunsNoAttachment() {
        val attachmentCalls = AtomicInteger()
        val controlRequests = AtomicInteger()
        val dispatcher = NonInlineDispatcher { task ->
            task.run()
            true
        }
        val owner = SessionMetricsOwner(
            dispatcher,
            explicit {
                attachmentCalls.incrementAndGet()
                RecordingHandle()
            },
            controlRequests::incrementAndGet,
        )

        val thrown = try {
            owner.attach()
            null
        } catch (failure: IllegalStateException) {
            failure
        }

        assertTrue(thrown != null)
        assertSame(thrown, owner.readSnapshot().failure)
        assertEquals(MetricsAttachmentLifecycle.Failed, owner.readSnapshot().lifecycle)
        assertEquals(0, attachmentCalls.get())
        assertEquals(1, controlRequests.get())
    }

    // Verification: MET-01
    @Test
    fun acceptedWithoutEntryRetainsOccupancyAndAuthorizesNoReplacement() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit { RecordingHandle() },
                requestControlTurn = { },
            )

            owner.attach()
            owner.retire()

            assertEquals(1, dispatcher.submissions().size)
            assertEquals(1, dispatcher.pendingCount())
            assertEquals(MetricsAttachmentLifecycle.Retired, owner.readSnapshot().lifecycle)
        }
    }

    // Verification: MET-01
    @Test
    fun taskErrorEscapesByIdentityAndRetainsSlotWithoutSuccessor() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val metrics = CaptureMetrics(12, 34, 56)
            val failure = AssertionError("task error")
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit { observer ->
                    observer.onMetricsChanged(metrics)
                    throw failure
                },
                requestControlTurn = { },
            )

            owner.attach()
            val attachment = dispatcher.enterNext() ?: error("attachment task was not retained")

            assertSame(failure, attachment.awaitCompletion())
            val snapshot = owner.readSnapshot()
            assertSame(metrics, snapshot.metrics)
            assertEquals(MetricsAttachmentLifecycle.Attaching, snapshot.lifecycle)
            assertNull(snapshot.failure)
            assertEquals(1, dispatcher.submissions().size)
            assertEquals(0, dispatcher.pendingCount())

            owner.retire()
            assertEquals(1, dispatcher.submissions().size)
            assertEquals(MetricsAttachmentLifecycle.Retired, owner.readSnapshot().lifecycle)
        }
    }

    // Verification: MET-01
    @Test
    fun javaNullHandleIsFailedAttachmentEvidence() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit(NullHandleCaptureMetricsSource()),
                requestControlTurn = { },
            )
            owner.attach()
            drain(dispatcher)

            val snapshot = owner.readSnapshot()
            assertEquals(MetricsAttachmentLifecycle.Failed, snapshot.lifecycle)
            assertFalse(snapshot.handleAdopted)
            assertTrue(snapshot.failure is IllegalStateException)
        }
    }

    // Verification: MET-01
    @Test
    fun retirementClosesLateHandleWithoutWake() {
        val dispatcher = ControlledNonInlineDispatcher()
        val subscribeEntered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val handle = RecordingHandle()
        val controlRequests = AtomicInteger()
        dispatcher.use {
            try {
                val source = CaptureMetricsSource {
                    subscribeEntered.countDown()
                    allowReturn.await()
                    handle
                }
                val owner = SessionMetricsOwner(dispatcher, explicit(source), controlRequests::incrementAndGet)

                owner.attach()
                val attachment = dispatcher.enterNext() ?: error("attachment task was not retained")
                assertTrue(subscribeEntered.await(5L, TimeUnit.SECONDS))

                owner.retire()
                assertEquals(MetricsAttachmentLifecycle.Retired, owner.readSnapshot().lifecycle)
                allowReturn.countDown()
                attachment.awaitSuccessfulCompletion()
                drain(dispatcher)

                val snapshot = owner.readSnapshot()
                assertEquals(MetricsAttachmentLifecycle.Retired, snapshot.lifecycle)
                assertTrue(snapshot.handleAdopted)
                assertFalse(snapshot.isReady(requireCompletionCloseSettlement = false))
                assertEquals(1, handle.closeCount.get())
                assertEquals(0, controlRequests.get())
            } finally {
                allowReturn.countDown()
            }
        }
    }

    private fun assertAttachmentFailure(expected: Exception, subscribe: () -> AutoCloseable) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = SessionMetricsOwner(
                dispatcher,
                explicit { subscribe() },
                requestControlTurn = { },
            )
            owner.attach()
            drain(dispatcher)
            assertSame(expected, owner.readSnapshot().failure)
            assertEquals(MetricsAttachmentLifecycle.Failed, owner.readSnapshot().lifecycle)
        }
    }

    private fun enterOne(dispatcher: ControlledNonInlineDispatcher) {
        val task = dispatcher.enterNext() ?: error("expected an accepted Metrics task")
        task.awaitSuccessfulCompletion()
    }

    private fun drain(dispatcher: ControlledNonInlineDispatcher) {
        while (dispatcher.pendingCount() > 0) enterOne(dispatcher)
    }

    private fun explicit(source: CaptureMetricsSource): SessionMetricsSourceSelection =
        SessionMetricsSourceSelection.Explicit(source)

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }

    private class ReleaseBeforeSubmitReturnTurnSubmitter : AutoCloseable {
        private val dispatcher = ControlledNonInlineDispatcher(threadName = "Metrics-Settlement")
        private val slot = SerialTaskSlot(dispatcher)

        fun trySubmit(
            task: () -> Unit,
            afterTaskReleased: () -> Unit,
        ): SerialTaskSlot.Submission {
            val releaseCompleted = AtomicBoolean(false)
            val submission = slot.trySubmit(task) {
                afterTaskReleased()
                releaseCompleted.set(true)
            }
            check(submission === SerialTaskSlot.Submission.Accepted) { "Metrics turn was not accepted by the real slot" }

            val retainedTask = dispatcher.enterNext() ?: error("accepted Metrics turn was not retained")
            retainedTask.awaitSuccessfulCompletion()
            check(releaseCompleted.get()) { "Metrics turn release did not complete before submit returned" }
            return submission
        }

        override fun close() {
            dispatcher.close()
        }
    }

    private class RecordingHandle(
        private val closeFailure: Exception? = null,
    ) : AutoCloseable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }
}
