package dev.dmkr.screencaptureengine.internal.session.delivery

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageFrame
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class ScreenCaptureFrameDeliveryCoordinatorTest {
    @Test
    fun borrowedFrame_isInvalidAfterCallbackReturns() {
        val harness = CoordinatorHarness()
        val callbackErrors = CallbackErrors()
        try {
            val retainedFrame = AtomicReference<EncodedImageFrame>()
            val copiedBytes = AtomicReference<ByteArray>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    retainedFrame.set(frame)
                    copiedBytes.set(frame.copyBytes())
                    assertEquals(1L, frame.sequence)
                }
            }
            val registrationStats = harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L, bytes = byteArrayOf(10, 20, 30))
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            callbackErrors.assertNone()
            harness.stats.awaitAfter(registrationStats, "delivery completion stats") { it.activeFrameSubscriptions == 1 }
            harness.assertNoUnexpectedSignals()
            assertArrayEquals(byteArrayOf(10, 20, 30), copiedBytes.get())
            val frame = retainedFrame.get()
            assertThrows(IllegalStateException::class.java) {
                frame.copyBytes()
            }
            assertThrows(IllegalStateException::class.java) {
                frame.byteCount
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun snapshotSlotBecomesReusableAfterHeldCallbackReturns() {
        val harness = CoordinatorHarness(publishedSnapshotSlotCount = 1, slowConsumerThreshold = 1)
        val callbackErrors = CallbackErrors()
        val firstCallbackEntered = CountDownLatch(1)
        val allowFirstCallbackReturn = CountDownLatch(1)
        val firstCallbackDone = CountDownLatch(1)
        var firstCallbackThread: Thread? = null
        try {
            val firstSubscription = harness.coordinator.register { frame ->
                callbackErrors.record {
                    assertEquals(1L, frame.sequence)
                }
                firstCallbackEntered.countDown()
                try {
                    callbackErrors.record {
                        check(allowFirstCallbackReturn.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "first callback was not released"
                        }
                    }
                } finally {
                    firstCallbackDone.countDown()
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            firstCallbackThread = harness.startCallbackTask("held callback")
            assertTrue(firstCallbackEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            firstSubscription.cancel()
            harness.stats.awaitMatching("first cancellation stats") { it.activeFrameSubscriptions == 0 }

            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    deliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("second registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()

            assertEquals(DeliveryDropKind.SnapshotSlotsExhausted, harness.drops.awaitValue("snapshot-slot drop"))
            assertTrue(deliveredSequences.isEmpty())

            allowFirstCallbackReturn.countDown()
            assertTrue(firstCallbackDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            firstCallbackThread.joinOrFail("held callback")
            firstCallbackThread = null

            harness.publish(sequence = 3L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(3L, deliveredSequences.awaitValue("second callback after slot release"))
            harness.stats.awaitMatching("second delivery completion stats") {
                (it.activeFrameSubscriptions == 1) && (it.slowConsumers == 0)
            }
            assertTrue(harness.drops.isEmpty())
            callbackErrors.assertNone()
            harness.assertNoUnexpectedFailures()
        } finally {
            allowFirstCallbackReturn.countDown()
            firstCallbackThread?.joinForCleanup()
            harness.close()
        }
    }

    @Test
    fun pendingLatestSignalsAreConflatedBeforeSnapshotMaterialization() {
        val harness = CoordinatorHarness()
        val callbackErrors = CallbackErrors()
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    deliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.publish(sequence = 2L)
            assertEquals(1, harness.coordinationTaskCount)

            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(2L, deliveredSequences.awaitValue("conflated callback"))
            assertFalse(harness.lookupSequences.contains(1L))
            assertTrue(harness.lookupSequences.contains(2L))
            callbackErrors.assertNone()
            harness.assertNoUnexpectedSignals()
        } finally {
            harness.close()
        }
    }

    @Test
    fun newerFrameWhileSubscriptionHasScheduledDelivery_isDroppedAsBusyAndMarksSlowConsumer() {
        val harness = CoordinatorHarness(slowConsumerThreshold = 1)
        val callbackErrors = CallbackErrors()
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    deliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            assertEquals(1, harness.deliveryTaskCount)

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()

            assertEquals(DeliveryDropKind.SubscriptionBusy, harness.drops.awaitValue("busy drop"))
            harness.slowConsumerPressure.awaitValue("slow-consumer pressure")
            harness.stats.awaitMatching("slow-consumer stats") {
                (it.activeFrameSubscriptions == 1) && (it.slowConsumers == 1)
            }
            assertTrue(deliveredSequences.isEmpty())

            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(1L, deliveredSequences.awaitValue("original callback"))
            harness.stats.awaitMatching("slow-consumer recovery stats") {
                (it.activeFrameSubscriptions == 1) && (it.slowConsumers == 0)
            }
            assertTrue(harness.drops.isEmpty())
            callbackErrors.assertNone()
            harness.assertNoUnexpectedFailures()
        } finally {
            harness.close()
        }
    }

    @Test
    fun callbackDispatchFailureDropsFrameAndReleasesLeaseWithoutAdmission() {
        val harness = CoordinatorHarness(publishedSnapshotSlotCount = 1)
        val callbackErrors = CallbackErrors()
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    deliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            harness.rejectNextCallbackDispatch()
            harness.runDeliveryTask()

            assertEquals(DeliveryDropKind.DispatchFailed, harness.drops.awaitValue("dispatch-failed drop"))
            assertEquals(ScreenCaptureProblemKind.FrameDeliveryFailed, harness.failures.awaitValue("dispatch failure"))
            assertEquals(0, harness.callbackTaskCount)
            assertTrue(deliveredSequences.isEmpty())

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(2L, deliveredSequences.awaitValue("callback after released lease"))
            assertTrue(harness.drops.isEmpty())
            callbackErrors.assertNone()
            harness.assertNoUnexpectedFailures()
        } finally {
            harness.close()
        }
    }

    @Test
    fun callerOwnedCancellationBeforeAdmissionDropsFrameAndReleasesLease() {
        val harness = CoordinatorHarness(
            publishedSnapshotSlotCount = 1,
            frameCallbackDispatcher = CancellingBeforeStartDispatcher(),
        )
        val callbackErrors = CallbackErrors()
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    deliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(DeliveryDropKind.DispatchFailed, harness.drops.awaitValue("cancelled dispatch drop"))
            assertTrue(deliveredSequences.isEmpty())

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(2L, deliveredSequences.awaitValue("callback after cancelled dispatch"))
            assertTrue(harness.drops.isEmpty())
            assertTrue(harness.failures.isEmpty())
            callbackErrors.assertNone()
            assertTrue(harness.slowConsumerPressure.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun preAdmissionDeliveryRetiredByInvalidateDoesNotInvokeCallbackWhenDrainedLater() {
        val harness = CoordinatorHarness()
        val callbackCount = AtomicInteger()
        try {
            harness.coordinator.register {
                callbackCount.incrementAndGet()
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            assertEquals(1, harness.deliveryTaskCount)

            harness.invalidateLatest()

            assertEquals(DeliveryDropKind.StaleSession, harness.drops.awaitValue("stale pre-admission drop"))
            harness.runDeliveryTask()

            assertEquals(0, harness.callbackTaskCount)
            assertEquals(0, callbackCount.get())
            assertTrue(harness.drops.isEmpty())
            harness.assertNoUnexpectedFailures()
        } finally {
            harness.close()
        }
    }

    @Test
    fun busySubscriptionDoesNotBlockOtherSubscriberFromNewerFrame() {
        val harness = CoordinatorHarness(slowConsumerThreshold = 1)
        val callbackErrors = CallbackErrors()
        val firstCallbackEntered = CountDownLatch(1)
        val allowFirstCallbackReturn = CountDownLatch(1)
        val firstCallbackDone = CountDownLatch(1)
        var firstCallbackThread: Thread? = null
        try {
            val firstDeliveredSequences = LinkedBlockingQueue<Long>()
            val secondDeliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    firstDeliveredSequences.put(frame.sequence)
                }
                firstCallbackEntered.countDown()
                try {
                    callbackErrors.record {
                        check(allowFirstCallbackReturn.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "first callback was not released"
                        }
                    }
                } finally {
                    firstCallbackDone.countDown()
                }
            }
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    secondDeliveredSequences.put(frame.sequence)
                }
            }
            harness.stats.awaitMatching("two registration stats") { it.activeFrameSubscriptions == 2 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            assertEquals(2, harness.deliveryTaskCount)

            harness.runDeliveryTask()
            firstCallbackThread = harness.startCallbackTask("held first callback")
            assertTrue(firstCallbackEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            harness.runDeliveryTask()
            harness.runCallbackTask()
            assertEquals(1L, secondDeliveredSequences.awaitValue("second subscriber first callback"))

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()

            assertEquals(DeliveryDropKind.SubscriptionBusy, harness.drops.awaitValue("busy first subscriber drop"))
            harness.slowConsumerPressure.awaitValue("slow-consumer pressure")
            harness.stats.awaitMatching("slow first subscriber stats") {
                (it.activeFrameSubscriptions == 2) && (it.slowConsumers == 1)
            }
            assertEquals(1, harness.deliveryTaskCount)

            harness.runDeliveryTask()
            harness.runCallbackTask()
            assertEquals(2L, secondDeliveredSequences.awaitValue("second subscriber newer callback"))
            assertEquals(1L, firstDeliveredSequences.awaitValue("first subscriber original callback"))
            assertTrue(firstDeliveredSequences.isEmpty())

            allowFirstCallbackReturn.countDown()
            assertTrue(firstCallbackDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            firstCallbackThread.joinOrFail("held first callback")
            firstCallbackThread = null

            harness.stats.awaitMatching("slow first subscriber recovery stats") {
                (it.activeFrameSubscriptions == 2) && (it.slowConsumers == 0)
            }
            assertTrue(harness.drops.isEmpty())
            callbackErrors.assertNone()
            harness.assertNoUnexpectedFailures()
        } finally {
            allowFirstCallbackReturn.countDown()
            firstCallbackThread?.joinForCleanup()
            harness.close()
        }
    }

    @Test
    fun callbackThrowRecordsDropFailureAndReleasesLeaseForLaterFrame() {
        val harness = CoordinatorHarness(publishedSnapshotSlotCount = 1)
        val throwOnNextCallback = AtomicBoolean(true)
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            harness.coordinator.register { frame ->
                deliveredSequences.put(frame.sequence)
                if (throwOnNextCallback.compareAndSet(true, false)) {
                    throw IllegalStateException("intentional callback failure")
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(1L, deliveredSequences.awaitValue("throwing callback"))
            assertEquals(DeliveryDropKind.CallbackThrew, harness.drops.awaitValue("callback-threw drop"))
            assertEquals(ScreenCaptureProblemKind.FrameCallbackThrew, harness.failures.awaitValue("callback-threw failure"))

            harness.publish(sequence = 2L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            harness.runCallbackTask()

            assertEquals(2L, deliveredSequences.awaitValue("callback after released lease"))
            assertTrue(harness.drops.isEmpty())
            harness.assertNoUnexpectedFailures()
        } finally {
            harness.close()
        }
    }

    @Test
    fun admittedCallbackCanFinishAfterCloseWithoutStaleDrop() {
        val harness = CoordinatorHarness()
        val callbackErrors = CallbackErrors()
        val callbackEntered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        var callbackThread: Thread? = null
        try {
            harness.coordinator.register { frame ->
                callbackErrors.record {
                    assertEquals(1L, frame.sequence)
                }
                callbackEntered.countDown()
                try {
                    callbackErrors.record {
                        check(allowCallbackReturn.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "admitted callback was not released"
                        }
                    }
                } finally {
                    callbackDone.countDown()
                }
            }
            harness.stats.awaitMatching("registration stats") { it.activeFrameSubscriptions == 1 }

            harness.publish(sequence = 1L)
            harness.runCoordinatorTask()
            harness.runDeliveryTask()
            callbackThread = harness.startCallbackTask("admitted callback")
            assertTrue(callbackEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            harness.close()

            allowCallbackReturn.countDown()
            assertTrue(callbackDone.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            callbackThread.joinOrFail("admitted callback")
            callbackThread = null

            assertTrue(harness.drops.isEmpty())
            callbackErrors.assertNone()
            harness.assertNoUnexpectedFailures()
        } finally {
            allowCallbackReturn.countDown()
            callbackThread?.joinForCleanup()
            harness.close()
        }
    }

    private class CoordinatorHarness(
        publishedSnapshotSlotCount: Int = 4,
        slowConsumerThreshold: Int = 2,
        frameCallbackDispatcher: CoroutineDispatcher? = null,
    ) {
        private val terminal = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val latestFrame = AtomicReference<LatestEncodedFrame?>()
        private val coordinationDispatcher = ManualFrameDeliveryDispatcher()
        private val deliveryTaskDispatcher = ManualFrameDeliveryDispatcher()
        private val defaultCallbackDispatcher = ManualFrameDeliveryDispatcher()
        val drops: LinkedBlockingQueue<DeliveryDropKind> = LinkedBlockingQueue()
        val stats: LinkedBlockingQueue<StatsSnapshot> = LinkedBlockingQueue()
        val failures: LinkedBlockingQueue<ScreenCaptureProblemKind> = LinkedBlockingQueue()
        val slowConsumerPressure: LinkedBlockingQueue<String> = LinkedBlockingQueue()
        val lookupSequences: LinkedBlockingQueue<Long> = LinkedBlockingQueue()
        val coordinationTaskCount: Int
            get() = coordinationDispatcher.taskCount
        val deliveryTaskCount: Int
            get() = deliveryTaskDispatcher.taskCount
        val callbackTaskCount: Int
            get() = defaultCallbackDispatcher.taskCount
        val coordinator: ScreenCaptureFrameDeliveryCoordinator = ScreenCaptureFrameDeliveryCoordinator(
            config = ScreenCaptureConfig(
                metricsProvider = FakeMetricsProvider(),
                publishedSnapshotSlotCount = publishedSnapshotSlotCount,
                slowConsumerThreshold = slowConsumerThreshold,
                frameCallbackDispatcher = frameCallbackDispatcher,
            ),
            callbackEntryGate = Any(),
            isSessionTerminal = { terminal.get() },
            latestFrameBySequence = { sequence ->
                lookupSequences.put(sequence)
                latestFrame.get()?.takeIf { it.sequence == sequence }
            },
            onDeliveryDrop = drops::put,
            onFrameDeliveryFailure = { kind, _, _ -> failures.put(kind) },
            onSlowConsumerPressure = slowConsumerPressure::put,
            onSubscriptionStatsChanged = { activeFrameSubscriptions, slowConsumers, version ->
                stats.put(StatsSnapshot(activeFrameSubscriptions, slowConsumers, version))
            },
            coordinationDispatcher = coordinationDispatcher.dispatcher,
            deliveryTaskDispatcher = deliveryTaskDispatcher.dispatcher,
            defaultCallbackDispatcher = defaultCallbackDispatcher.dispatcher,
            watchdogSchedulingEnabled = false,
        )

        fun publish(sequence: Long, bytes: ByteArray = byteArrayOf(sequence.toByte())) {
            latestFrame.set(
                LatestEncodedFrame(
                    format = EncodedImageFormats.Jpeg,
                    bytes = bytes,
                    sequence = sequence,
                    timestampElapsedRealtimeNanos = sequence * 1_000L,
                ),
            )
            coordinator.signalLatestFramePublished(sequence)
        }

        fun invalidateLatest() {
            latestFrame.set(null)
            coordinator.invalidateLatestFromSession()
        }

        fun runCoordinatorTask() {
            coordinationDispatcher.runNext("coordination task")
        }

        fun runDeliveryTask() {
            deliveryTaskDispatcher.runNext("delivery task")
        }

        fun runCallbackTask() {
            defaultCallbackDispatcher.runNext("callback task")
        }

        fun startCallbackTask(name: String): Thread =
            defaultCallbackDispatcher.startNext(name)

        fun rejectNextCallbackDispatch() {
            defaultCallbackDispatcher.rejectNextDispatch()
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                terminal.set(true)
                latestFrame.set(null)
                coordinator.closeFromSession()
            }
        }

        fun assertNoUnexpectedSignals() {
            assertTrue(drops.isEmpty())
            assertNoUnexpectedFailures()
        }

        fun assertNoUnexpectedFailures() {
            assertTrue(failures.isEmpty())
            assertTrue(slowConsumerPressure.isEmpty())
        }
    }

    private class ManualFrameDeliveryDispatcher {
        private val closed = AtomicBoolean(false)
        private val nextDispatchFailure = AtomicReference<Throwable?>()
        private val tasks = LinkedBlockingQueue<(Boolean) -> Unit>()

        val dispatcher: ScreenCaptureFrameDeliveryDispatcher = ScreenCaptureFrameDeliveryDispatcher.Delegating(
            isCallerOwned = false,
            dispatch = ::dispatchFailure,
            closeDispatcher = ::close,
        )

        val taskCount: Int
            get() = tasks.size

        private fun dispatchFailure(block: (dispatchCancelledBeforeStart: Boolean) -> Unit): Throwable? {
            return nextDispatchFailure.getAndSet(null) ?: if (closed.get()) {
                RejectedExecutionException("Manual dispatcher is closed.")
            } else {
                tasks.put(block)
                null
            }
        }

        private fun close() {
            closed.set(true)
        }

        fun rejectNextDispatch() {
            nextDispatchFailure.set(RejectedExecutionException("Manual dispatcher rejected dispatch."))
        }

        fun runNext(description: String, dispatchCancelledBeforeStart: Boolean = false) {
            nextTask(description).invoke(dispatchCancelledBeforeStart)
        }

        fun startNext(name: String): Thread {
            val task = nextTask(name)
            return thread(name = name) {
                task.invoke(false)
            }
        }

        private fun nextTask(description: String): (Boolean) -> Unit =
            tasks.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: throw AssertionError("$description was not observed")
    }

    private class CancellingBeforeStartDispatcher : CoroutineDispatcher() {
        private val cancelNextDispatch = AtomicBoolean(true)

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (cancelNextDispatch.compareAndSet(true, false)) {
                context[Job]?.cancel()
            }
            block.run()
        }
    }

    private class CallbackErrors {
        private val errors = LinkedBlockingQueue<Throwable>()

        fun record(block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                errors.put(throwable)
            }
        }

        fun assertNone() {
            val first = errors.poll() ?: return
            while (true) {
                first.addSuppressed(errors.poll() ?: break)
            }
            throw AssertionError("Callback assertion failed.", first)
        }
    }

    private class FakeMetricsProvider : CaptureMetricsProvider {
        override val metrics: StateFlow<CaptureMetrics> = MutableStateFlow(CaptureMetrics(widthPx = 100, heightPx = 100, densityDpi = 320))
    }

    private class StatsSnapshot(
        val activeFrameSubscriptions: Int,
        val slowConsumers: Int,
        val version: Long,
    )

    private fun <T : Any> BlockingQueue<T>.awaitValue(description: String): T =
        poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: throw AssertionError("$description was not observed")

    private fun <T : Any> BlockingQueue<T>.awaitMatching(description: String, predicate: (T) -> Boolean): T {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            val value = poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
            if (predicate(value)) return value
        }
        throw AssertionError("$description was not observed")
    }

    private fun BlockingQueue<StatsSnapshot>.awaitAfter(
        previous: StatsSnapshot,
        description: String,
        predicate: (StatsSnapshot) -> Boolean,
    ): StatsSnapshot =
        awaitMatching(description) { stats ->
            (stats.version > previous.version) && predicate(stats)
        }

    private fun Thread.joinOrFail(description: String) {
        join(TIMEOUT_MILLIS)
        assertFalse("$description did not finish", isAlive)
    }

    private fun Thread.joinForCleanup() {
        // Best-effort cleanup wait; test bodies use joinOrFail when termination is part of the contract.
        join(TIMEOUT_MILLIS)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 2_000L
    }
}
