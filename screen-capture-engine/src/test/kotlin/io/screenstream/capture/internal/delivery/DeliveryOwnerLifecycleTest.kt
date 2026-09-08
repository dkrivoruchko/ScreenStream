package io.screenstream.capture.internal.delivery

import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.session.delivery.SessionDelivery
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import io.screenstream.capture.internal.storage.PublishedFrame
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DeliveryOwnerLifecycleTest {
    // Verification: DEL-01
    @Test
    fun rejectedAndThrownDispatchAreReturnOnlyAndReleaseOccupancy() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val sink = RecordingFactSink()
            val owner = DeliveryOwner(dispatcher, sink)
            dispatcher.enqueueReject()
            val rejected = owner.offer(DeliveryHandoffToken(1L), { fail("rejected callback entered") }, frame())
            assertTrue(rejected is DeliveryOffer.Rejected)
            assertTrue((rejected as DeliveryOffer.Rejected).cause == null)

            val failure = IllegalStateException("dispatch failed")
            dispatcher.enqueueThrow(failure)
            val thrown = owner.offer(DeliveryHandoffToken(2L), { fail("thrown callback entered") }, frame())
            assertTrue(thrown is DeliveryOffer.Rejected)
            assertSame(failure, (thrown as DeliveryOffer.Rejected).cause)
            assertTrue(sink.facts().isEmpty())

            val accepted = owner.offer(DeliveryHandoffToken(3L), { }, frame())
            assertTrue(accepted is DeliveryOffer.Accepted)
            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            task.awaitSuccessfulCompletion()
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun cutoffBeforeEntryMakesTheAcceptedTaskInertAndReportsExactClosure() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val sink = RecordingFactSink()
            val owner = DeliveryOwner(dispatcher, sink)
            val token = DeliveryHandoffToken(7L)
            var callbackEntered = false

            val offer = owner.offer(token, { callbackEntered = true }, frame())
            assertTrue(offer is DeliveryOffer.Accepted)
            assertSame(token, (offer as DeliveryOffer.Accepted).handoff)
            assertSame(DeliveryCutoff.NoHandoff, owner.cutoff(8L))
            assertSame(DeliveryCutoff.CutoffBeforeEntry, owner.cutoff(7L))
            assertSame(DeliveryCutoff.CutoffBeforeEntry, owner.cutoff(7L))

            val task = dispatcher.enterNext() ?: error("cutoff task was not retained")
            task.awaitSuccessfulCompletion()
            assertFalse(callbackEntered)
            val closed = sink.facts().single() as DeliveryFact.Closed
            assertSame(token, closed.handoff)
            assertSame(DeliveryFact.Closed.Outcome.CutoffBeforeEntry, closed.outcome)
            assertSame(closed, sink.readyAttempts().single())
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun exactCompletionProofPrecedesPhysicalReleaseAndReportsBeforeCallbackFailure() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val completion = RecordingCompletion()
            val ownerRef = AtomicReference<DeliveryOwner>()
            val physicalOfferDuringReport = AtomicReference<DeliveryOffer>()
            val sink = RecordingFactSink(
                onOffer = {
                    assertTrue(completion.callbackReturned.get())
                    physicalOfferDuringReport.set(ownerRef.get().offer(DeliveryHandoffToken(9L), { }, frame()))
                },
            )
            val owner = DeliveryOwner(dispatcher, sink)
            ownerRef.set(owner)
            val token = DeliveryHandoffToken(8L)
            assertTrue(owner.offer(token, completion, { throw IllegalStateException("callback") }, frame()) is DeliveryOffer.Accepted)
            val task = dispatcher.enterNext() ?: error("callback task was not retained")
            task.awaitSuccessfulCompletion()
            assertTrue(completion.callbackReturned.get())
            assertSame(DeliveryOffer.Occupied, physicalOfferDuringReport.get())
        }
    }

    // Verification: STO-01
    // Verification: DEL-01
    @Test
    fun copyToCopiesExactRangeAndRejectsInvalidRangesWithoutMutation() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = DeliveryOwner(dispatcher, RecordingFactSink())

            assertTrue(
                owner.offer(
                    DeliveryHandoffToken(9L),
                    { borrowed ->
                        val destination = ByteArray(8) { 9 }
                        assertEquals(4, borrowed.copyTo(destination, destinationOffset = 2))
                        assertArrayEquals(byteArrayOf(9, 9, 1, 2, 3, 4, 9, 9), destination)

                        val negativeOffsetDestination = ByteArray(6) { 8 }
                        val negativeOffsetBefore = negativeOffsetDestination.copyOf()
                        assertThrows(IndexOutOfBoundsException::class.java) {
                            borrowed.copyTo(negativeOffsetDestination, destinationOffset = -1)
                        }
                        assertArrayEquals(negativeOffsetBefore, negativeOffsetDestination)

                        val insufficientTailDestination = ByteArray(6) { 6 }
                        val insufficientTailBefore = insufficientTailDestination.copyOf()
                        assertThrows(IndexOutOfBoundsException::class.java) {
                            borrowed.copyTo(insufficientTailDestination, destinationOffset = 3)
                        }
                        assertArrayEquals(insufficientTailBefore, insufficientTailDestination)
                    },
                    frame(),
                ) is DeliveryOffer.Accepted,
            )

            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            task.awaitSuccessfulCompletion()
        }
    }

    // Verification: DEL-01
    @Test
    fun borrowIsUsableOnlyOnTheEnteredCallbackThreadAndDuringItsBody() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val borrowMethods = listOf(
                BorrowMethod("byteCount") { it.byteCount },
                BorrowMethod("effectiveParameters") { it.effectiveParameters },
                BorrowMethod("sequence") { it.sequence },
                BorrowMethod("timestampElapsedRealtimeNanos") { it.timestampElapsedRealtimeNanos },
                BorrowMethod("copyTo") { it.copyTo(ByteArray(4)) },
                BorrowMethod("toByteArray") { it.toByteArray() },
            )
            val ownerRef = AtomicReference<DeliveryOwner>()
            val retained = AtomicReference<EncodedImageFrame?>()
            val wrongThreadFailures = AtomicReference<Map<String, Throwable?>>()
            val callbackThread = AtomicReference<Thread?>()
            val sink = RecordingFactSink(
                onStage = { _, stageIndex ->
                    if (stageIndex == 1) {
                        assertSame(callbackThread.get(), Thread.currentThread())
                        val borrowed = retained.get() ?: error("callback did not retain its borrowed frame")
                        for (method in borrowMethods) {
                            try {
                                method.access(borrowed)
                                fail("${method.label} succeeded on the callback worker after return")
                            } catch (_: IllegalStateException) {
                            }
                        }
                    }
                },
            )
            ownerRef.set(DeliveryOwner(dispatcher, sink))
            val owner = ownerRef.get()
            val token = DeliveryHandoffToken(11L)

            assertTrue(
                owner.offer(
                    token,
                    { borrowed ->
                        retained.set(borrowed)
                        callbackThread.set(Thread.currentThread())
                        assertTrue(ownerRef.get().isEnteredCallbackThread(token.registrationId))
                        borrowMethods.forEach { it.access(borrowed) }

                        val foreign = Thread(
                            {
                                wrongThreadFailures.set(
                                    borrowMethods.associate { method ->
                                        val failure = try {
                                            method.access(borrowed)
                                            null
                                        } catch (failure: Throwable) {
                                            failure
                                        }
                                        method.label to failure
                                    },
                                )
                            },
                            "ScreenCaptureEngine-Test-Foreign",
                        )
                        foreign.start()
                        foreign.join(5_000L)
                        assertFalse(foreign.isAlive)
                    },
                    frame(),
                ) is DeliveryOffer.Accepted,
            )

            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            task.awaitSuccessfulCompletion()
            assertTrue(callbackThread.get() !== Thread.currentThread())
            for (method in borrowMethods) {
                assertTrue(
                    "${method.label} did not reject access from a foreign thread while open",
                    wrongThreadFailures.get()?.get(method.label) is IllegalStateException,
                )
            }
            assertFalse(owner.isEnteredCallbackThread(token.registrationId))
        }
    }

    // Verification: DEL-01
    @Test
    fun callbackExceptionProducesOneFailureFactThenOrdinaryClosed() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val sink = RecordingFactSink()
            val owner = DeliveryOwner(dispatcher, sink)
            val token = DeliveryHandoffToken(13L)
            val failure = IllegalArgumentException("callback failed")

            assertTrue(owner.offer(token, { throw failure }, frame()) is DeliveryOffer.Accepted)
            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            task.awaitSuccessfulCompletion()

            val facts = sink.facts()
            assertTrue(facts.size == 2)
            val callbackFailure = facts[0] as DeliveryFact.CallbackFailure
            assertSame(token, callbackFailure.handoff)
            assertSame(failure, callbackFailure.exception)
            val closed = facts[1] as DeliveryFact.Closed
            assertSame(token, closed.handoff)
            assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, closed.outcome)
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun cutoffAfterEntryDoesNotInterruptCallbackAndWaitsForItsRealReturn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ControlledNonInlineDispatcher().use { dispatcher ->
            try {
                val sink = RecordingFactSink()
                val owner = DeliveryOwner(dispatcher, sink)
                val token = DeliveryHandoffToken(12L)
                val returned = AtomicBoolean(false)
                assertTrue(
                    owner.offer(
                        token,
                        {
                            entered.countDown()
                            assertTrue(release.await(5L, TimeUnit.SECONDS))
                            returned.set(true)
                        },
                        frame(),
                    ) is DeliveryOffer.Accepted,
                )

                val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
                assertTrue(entered.await(5L, TimeUnit.SECONDS))
                assertSame(DeliveryCutoff.Entered, owner.cutoff(token.registrationId))
                assertFalse(returned.get())
                release.countDown()
                task.awaitSuccessfulCompletion()
                assertTrue(returned.get())
                val closed = sink.facts().single() as DeliveryFact.Closed
                assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, closed.outcome)
            } finally {
                release.countDown()
            }
        }
    }

    // Verification: DEL-01
    @Test
    fun failureFactExceptionBecomesInternalClosedOutcome() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val reportFailure = IllegalStateException("fact transfer failed")
            val retained = AtomicReference<EncodedImageFrame?>()
            val sink = RecordingFactSink(
                offerFailure = reportFailure,
                onOffer = { fact ->
                    if (fact is DeliveryFact.CallbackFailure) {
                        assertThrows(IllegalStateException::class.java) {
                            checkNotNull(retained.get()).byteCount
                        }
                    }
                },
            )
            val owner = DeliveryOwner(dispatcher, sink)
            assertTrue(
                owner.offer(
                    DeliveryHandoffToken(17L),
                    { borrowed ->
                        retained.set(borrowed)
                        throw IllegalArgumentException("callback")
                    },
                    frame(),
                ) is DeliveryOffer.Accepted,
            )
            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            task.awaitSuccessfulCompletion()

            val closed = sink.facts().single() as DeliveryFact.Closed
            val outcome = closed.outcome as DeliveryFact.Closed.Outcome.InternalFailure
            assertSame(reportFailure, outcome.cause)
        }
    }

    // Verification: DEL-01
    @Test
    fun closedIsStagedBeforeCurrentReleaseAndReadyAfterRelease() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val stageSawOccupied = AtomicBoolean(false)
            val readySawAccepted = AtomicBoolean(false)
            val ownerRef = AtomicReference<DeliveryOwner>()
            val sink = RecordingFactSink(
                onStage = { _, stageIndex ->
                    if (stageIndex == 1) {
                        stageSawOccupied.set(
                            ownerRef.get().offer(
                                DeliveryHandoffToken(22L),
                                { },
                                frame(),
                            ) === DeliveryOffer.Occupied,
                        )
                    }
                },
            ) { _, stageIndex ->
                if (stageIndex == 1) {
                    readySawAccepted.set(
                        ownerRef.get().offer(
                            DeliveryHandoffToken(22L),
                            { },
                            frame(),
                        ) is DeliveryOffer.Accepted,
                    )
                }
            }
            val owner = DeliveryOwner(dispatcher, sink)
            ownerRef.set(owner)
            assertTrue(owner.offer(DeliveryHandoffToken(21L), { }, frame()) is DeliveryOffer.Accepted)
            val first = dispatcher.enterNext() ?: error("first callback was not retained")
            first.awaitSuccessfulCompletion()
            assertTrue(stageSawOccupied.get())
            assertTrue(readySawAccepted.get())

            val successor = dispatcher.enterNext() ?: error("successor callback was not retained")
            successor.awaitSuccessfulCompletion()
        }
    }

    // Verification: DEL-01
    @Test
    fun closedStageExceptionRetainsCurrentWithoutReadyOrRetry() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val stageFailure = IllegalStateException("closed stage failed")
            val sink = RecordingFactSink(
                onStage = { _, stageIndex ->
                    if (stageIndex == 1) throw stageFailure
                },
            )
            val owner = DeliveryOwner(dispatcher, sink)
            val token = DeliveryHandoffToken(23L)
            assertTrue(owner.offer(token, { }, frame()) is DeliveryOffer.Accepted)

            val task = dispatcher.enterNext() ?: error("callback was not retained")
            task.awaitSuccessfulCompletion()

            val closed = sink.facts().single() as DeliveryFact.Closed
            assertSame(token, closed.handoff)
            assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, closed.outcome)
            assertEquals(1, sink.stageAttemptCount())
            assertTrue(sink.readyAttempts().isEmpty())
            assertSame(
                DeliveryOffer.Occupied,
                owner.offer(DeliveryHandoffToken(24L), { fail("occupied successor entered") }, frame()),
            )
            assertEquals(1, sink.stageAttemptCount())
        }
    }

    // Verification: DEL-01
    @Test
    fun closedReadyExceptionIsContainedAfterCurrentReleaseWithoutRetry() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val ownerRef = AtomicReference<DeliveryOwner>()
            val successorOffer = AtomicReference<DeliveryOffer?>()
            val successorEntered = AtomicBoolean(false)
            val readyFailure = IllegalStateException("closed ready failed")
            val sink = RecordingFactSink(
                onReady = { _, stageIndex ->
                    if (stageIndex == 1) {
                        successorOffer.set(
                            ownerRef.get().offer(
                                DeliveryHandoffToken(26L),
                                { successorEntered.set(true) },
                                frame(),
                            ),
                        )
                        throw readyFailure
                    }
                },
            )
            val owner = DeliveryOwner(dispatcher, sink)
            ownerRef.set(owner)
            val firstToken = DeliveryHandoffToken(25L)
            assertTrue(owner.offer(firstToken, { }, frame()) is DeliveryOffer.Accepted)

            val firstTask = dispatcher.enterNext() ?: error("first callback was not retained")
            firstTask.awaitSuccessfulCompletion()

            val firstClosed = sink.facts().single() as DeliveryFact.Closed
            assertSame(firstToken, firstClosed.handoff)
            assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, firstClosed.outcome)
            assertTrue(successorOffer.get() is DeliveryOffer.Accepted)
            assertEquals(1, sink.stageAttemptCount())
            assertSame(firstClosed, sink.readyAttempts().single())

            val successorTask = dispatcher.enterNext() ?: error("successor callback was not retained")
            successorTask.awaitSuccessfulCompletion()
            assertTrue(successorEntered.get())
            assertEquals(1, sink.facts().count { it === firstClosed })
            assertEquals(1, sink.readyAttempts().count { it === firstClosed })
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun durableRegistrationCompletionSurvivesReportStageAndReadyFailures() {
        data class FailureCase(val label: String, val kind: Int)
        listOf(
            FailureCase("report", 1),
            FailureCase("stage", 2),
            FailureCase("ready", 3),
        ).forEach { failureCase ->
            ControlledNonInlineDispatcher().use { dispatcher ->
                val reportFailure = IllegalStateException("${failureCase.label} report")
                val stageFailure = IllegalStateException("${failureCase.label} stage")
                val readyFailure = IllegalStateException("${failureCase.label} ready")
                val ownerRef = AtomicReference<DeliveryOwner>()
                val successorOffer = AtomicReference<DeliveryOffer?>()
                val successorEntered = AtomicBoolean(false)
                val sink = RecordingFactSink(
                    offerFailure = reportFailure.takeIf { failureCase.kind == 1 },
                    onStage = { _, index -> if ((failureCase.kind == 2) && (index == 1)) throw stageFailure },
                    onReady = { _, index ->
                        if ((failureCase.kind == 3) && (index == 1)) {
                            successorOffer.set(
                                ownerRef.get().offer(
                                    DeliveryHandoffToken(28L),
                                    { successorEntered.set(true) },
                                    frame(),
                                ),
                            )
                            throw readyFailure
                        }
                    },
                )
                val owner = DeliveryOwner(dispatcher, sink)
                ownerRef.set(owner)
                val delivery = SessionDelivery()
                val registration = (delivery.register { } as SessionDelivery.RegistrationResult.Accepted).registration
                val offer = (delivery.prepareFreshOffer(frame(), isPhysicalHandoffFree = true)
                        as SessionDelivery.FreshOffer.Prepared).offer
                val callbackFailure = IllegalArgumentException("callback")
                assertTrue(
                    owner.offer(
                        offer.handoff,
                        offer.completion,
                        { throw callbackFailure },
                        frame(),
                    ) is DeliveryOffer.Accepted,
                )
                val task = dispatcher.enterNext() ?: error("callback was not retained")
                task.awaitSuccessfulCompletion()

                val closed = sink.facts().filterIsInstance<DeliveryFact.Closed>().single()
                when (failureCase.kind) {
                    1 -> {
                        val outcome = closed.outcome as DeliveryFact.Closed.Outcome.InternalFailure
                        assertSame(reportFailure, outcome.cause)
                    }

                    2 -> {
                        assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, closed.outcome)
                        assertSame(
                            DeliveryOffer.Occupied,
                            owner.offer(DeliveryHandoffToken(29L), { fail("stage-failure successor entered") }, frame()),
                        )
                        assertTrue(sink.readyAttempts().isEmpty())
                    }

                    3 -> {
                        assertSame(DeliveryFact.Closed.Outcome.CallbackReturned, closed.outcome)
                        assertTrue(successorOffer.get() is DeliveryOffer.Accepted)
                        val successorTask = dispatcher.enterNext() ?: error("ready-failure successor was not retained")
                        successorTask.awaitSuccessfulCompletion()
                        assertTrue(successorEntered.get())
                    }
                }
                assertEquals(1, sink.facts().count { it === closed })
                assertEquals(if (failureCase.kind == 2) 0 else 1, sink.readyAttempts().count { it === closed })

                val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
                assertTrue(unregister is SessionDelivery.UnregisterAction.Complete)
                (unregister as SessionDelivery.UnregisterAction.Complete).settlement.complete()
                runBlocking { registration.waiter.awaitCompletion() }
                assertTrue("${failureCase.label} case did not record closure", sink.facts().any { it is DeliveryFact.Closed })
            }
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun laterReportErrorPreservesReachedRegistrationProofWithoutPhysicalRelease() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val reportError = AssertionError("report escaped")
            val sink = RecordingFactSink(
                onOffer = { throw reportError },
            )
            val owner = DeliveryOwner(dispatcher, sink)
            val delivery = SessionDelivery()
            val registration = (delivery.register { } as SessionDelivery.RegistrationResult.Accepted).registration
            val offer = (delivery.prepareFreshOffer(frame(), isPhysicalHandoffFree = true)
                    as SessionDelivery.FreshOffer.Prepared).offer
            assertTrue(
                owner.offer(offer.handoff, offer.completion, { throw IllegalArgumentException("callback") }, frame())
                        is DeliveryOffer.Accepted,
            )
            val task = dispatcher.enterNext() ?: error("callback was not retained")
            assertSame(reportError, task.awaitCompletion())

            val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
            assertTrue(unregister is SessionDelivery.UnregisterAction.Complete)
            (unregister as SessionDelivery.UnregisterAction.Complete).settlement.complete()
            runBlocking { registration.waiter.awaitCompletion() }
            assertSame(DeliveryOffer.Occupied, owner.offer(DeliveryHandoffToken(99L), { }, frame()))
        }
    }

    // Verification: DEL-01
    // Verification: DEL-02
    @Test
    fun retireFencesQueuedAndFutureHandoffs() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val sink = RecordingFactSink()
            val owner = DeliveryOwner(dispatcher, sink)
            assertTrue(owner.offer(DeliveryHandoffToken(31L), { fail("retired callback entered") }, frame()) is DeliveryOffer.Accepted)
            assertSame(DeliveryCutoff.CutoffBeforeEntry, owner.retire())
            assertSame(DeliveryCutoff.CutoffBeforeEntry, owner.retire())
            assertSame(DeliveryOffer.Cutoff, owner.offer(DeliveryHandoffToken(32L), { }, frame()))

            val task = dispatcher.enterNext() ?: error("retired task was not retained")
            task.awaitSuccessfulCompletion()
        }
    }

    // Verification: DEL-01
    @Test
    fun uncontainedCallbackErrorKeepsIdentityRevokesBorrowAndDoesNotReleaseOccupancy() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val sink = RecordingFactSink()
            val owner = DeliveryOwner(dispatcher, sink)
            val delivery = SessionDelivery()
            val registration = (delivery.register { } as SessionDelivery.RegistrationResult.Accepted).registration
            val offer = (delivery.prepareFreshOffer(frame(), isPhysicalHandoffFree = true)
                    as SessionDelivery.FreshOffer.Prepared).offer
            val failure = AssertionError("uncontained callback")
            val retained = AtomicReference<EncodedImageFrame?>()
            assertTrue(
                owner.offer(
                    offer.handoff,
                    offer.completion,
                    { borrowed ->
                        retained.set(borrowed)
                        throw failure
                    },
                    frame(),
                ) is DeliveryOffer.Accepted,
            )

            val task = dispatcher.enterNext() ?: error("accepted callback was not retained")
            assertSame(failure, task.awaitCompletion())
            assertTrue(sink.facts().isEmpty())
            assertSame(DeliveryOffer.Occupied, owner.offer(DeliveryHandoffToken(42L), { }, frame()))
            val unregister = delivery.beginUnregister(registration, requestCutoffImmediately = true)
            assertTrue(unregister is SessionDelivery.UnregisterAction.RequestCutoff)
            assertSame(DeliveryCutoff.Entered, owner.cutoff(offer.handoff))
            assertTrue(
                delivery.recordCutoffResult(registration, offer.handoff, DeliveryCutoff.Entered)
                        is SessionDelivery.CutoffSettlement.Handoff,
            )
            runBlocking {
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { registration.waiter.awaitCompletion() }
                assertFalse(waiting.isCompleted)
                waiting.cancelAndJoin()
            }
            val probeFailure = AtomicReference<Throwable?>()
            assertTrue(dispatcher.tryDispatch {
                try {
                    assertSame(task.enteredThread, Thread.currentThread())
                    assertThrows(IllegalStateException::class.java) { checkNotNull(retained.get()).byteCount }
                } catch (failureOnProbe: Throwable) {
                    probeFailure.set(failureOnProbe)
                }
            })
            val probe = dispatcher.enterNext() ?: error("same-worker probe was not retained")
            probe.awaitSuccessfulCompletion()
            probeFailure.get()?.let { throw it }
        }
    }

    private class BorrowMethod(
        val label: String,
        val access: (EncodedImageFrame) -> Any?,
    )

    private class RecordingCompletion : DeliveryHandoffCompletion {
        val callbackReturned = AtomicBoolean(false)

        override fun callbackReturned(token: DeliveryHandoffToken) {
            callbackReturned.set(true)
        }

        override fun cutoffBeforeEntry(token: DeliveryHandoffToken) = Unit
    }

    private class RecordingFactSink(
        private val offerFailure: Exception? = null,
        private val onOffer: (DeliveryFact) -> Unit = {},
        private val onStage: (DeliveryFact.Closed, Int) -> Unit = { _, _ -> },
        private val onReady: (DeliveryFact.Closed, Int) -> Unit = { _, _ -> },
    ) : DeliveryFactSink {
        private val gate = Any()
        private val recorded = ArrayList<DeliveryFact>()
        private val attemptedReady = ArrayList<DeliveryFact.Closed>()
        private var stageCount = 0

        override fun offer(fact: DeliveryFact) {
            onOffer(fact)
            offerFailure?.let { throw it }
            synchronized(gate) { recorded += fact }
        }

        override fun stageClosed(fact: DeliveryFact.Closed): DeliveryClosedStage {
            val index = synchronized(gate) {
                recorded += fact
                stageCount += 1
                stageCount
            }
            onStage(fact, index)
            return DeliveryClosedStage {
                synchronized(gate) { attemptedReady += fact }
                onReady(fact, index)
            }
        }

        fun facts(): List<DeliveryFact> = synchronized(gate) { recorded.toList() }

        fun stageAttemptCount(): Int = synchronized(gate) { stageCount }

        fun readyAttempts(): List<DeliveryFact.Closed> = synchronized(gate) { attemptedReady.toList() }
    }

    private companion object {
        private val EFFECTIVE_PARAMETERS = ScreenCaptureEffectiveParameters.create(
            appliedParameters = ScreenCaptureParameters.DEFAULT,
            captureGeometry = CaptureGeometry.create(widthPx = 2, heightPx = 2, densityDpi = 320),
            appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 2, bottomPx = 2),
            finalImageSize = ImageSize.create(widthPx = 2, heightPx = 2),
        )

        private fun frame(): PublishedFrame = PublishedFrame(
            payload = ImmutableEncodedPayload(arrayOf(byteArrayOf(1, 2), byteArrayOf(3, 4)), byteCount = 4),
            effectiveParameters = EFFECTIVE_PARAMETERS,
            sequence = 3L,
            timestampElapsedRealtimeNanos = 5L,
        )
    }
}
