package dev.dmkr.screencaptureengine.internal.encoding.storage

import dev.dmkr.screencaptureengine.internal.policy.ScreenCaptureEnginePolicyDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SegmentedEncodedSinkTest {
    @Test
    fun invocationAndFinishBoundariesAreSingleUse() {
        val sink = sink(maxByteCount = 8)
        assertFails<IllegalStateException> { sink.maxByteCount }
        assertFails<IllegalStateException> { sink.finish() }
        sink.enterEncoderInvocation()
        assertEquals(8, sink.maxByteCount)
        assertFails<IllegalStateException> { sink.enterEncoderInvocation() }
        assertFails<IllegalStateException> { sink.finish() }
        sink.exitEncoderInvocation()
        assertFails<IllegalStateException> { sink.maxByteCount }
        assertFails<IllegalStateException> { sink.exitEncoderInvocation() }
        assertSame(SegmentedSinkFinish.Empty, sink.finish())
        assertFails<IllegalStateException> { sink.finish() }
    }

    @Test
    fun byteArrayRangeFailuresUseExactExceptionAndMutateNothing() {
        val allocations = ArrayList<Int>()
        val sink = sink(maxByteCount = 8, allocations = allocations).enterEncoderInvocation()
        val source = byteArrayOf(1, 2, 3)

        assertFails<IndexOutOfBoundsException> { sink.write(source, offset = -1, byteCount = 1) }
        assertFails<IndexOutOfBoundsException> { sink.write(source, offset = 0, byteCount = -1) }
        assertFails<IndexOutOfBoundsException> { sink.write(source, offset = Int.MAX_VALUE, byteCount = 1) }
        assertFails<IndexOutOfBoundsException> { sink.write(source, offset = 2, byteCount = 2) }

        assertEquals(0, sink.byteCount)
        assertTrue(allocations.isEmpty())
        assertNull(sink.firstRejection)
        sink.exitEncoderInvocation()
        assertSame(SegmentedSinkFinish.Empty, sink.finish())
    }

    @Test
    fun byteBufferFailuresUseExactExceptionAndPreservePosition() {
        val sink = sink(maxByteCount = 8).enterEncoderInvocation()
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3)).apply { position(1) }

        assertFails<IllegalArgumentException> { sink.write(source, byteCount = -1) }
        assertFails<BufferUnderflowException> { sink.write(source, byteCount = 3) }

        assertEquals(1, source.position())
        assertEquals(0, sink.byteCount)
        sink.exitEncoderInvocation()
        assertSame(SegmentedSinkFinish.Empty, sink.finish())
    }

    @Test
    fun writesAreConfinedToEntryThreadAndReturnBoundary() {
        val sink = sink(maxByteCount = 8)
        val bytes = byteArrayOf(1)

        assertFails<IllegalStateException> { sink.write(bytes, offset = 0, byteCount = 1) }
        sink.enterEncoderInvocation()

        val crossThreadFailure = AtomicReference<Throwable?>()
        thread(name = "wrong-encoder-thread") {
            crossThreadFailure.set(runCatching { sink.write(bytes, offset = 0, byteCount = 1) }.exceptionOrNull())
        }.join()
        assertTrue(crossThreadFailure.get() is IllegalStateException)

        assertTrue(sink.write(bytes, offset = 0, byteCount = 1))
        sink.exitEncoderInvocation()
        assertFails<IllegalStateException> { sink.write(bytes, offset = 0, byteCount = 1) }
        assertFails<IllegalStateException> { sink.byteCount }

        val payload = (sink.finish() as SegmentedSinkFinish.Completed).payload
        assertTrue(payload.release())
    }

    @Test
    fun zeroWriteAllocatesNothingUntilPositiveWriteAndStaysFalseAfterRejection() {
        val allocations = ArrayList<Int>()
        val sink = sink(maxByteCount = 3, segmentByteCount = 3, allocations = allocations).enterEncoderInvocation()

        assertTrue(sink.write(byteArrayOf(), offset = 0, byteCount = 0))
        assertTrue(sink.write(ByteBuffer.allocate(0), byteCount = 0))
        assertTrue(allocations.isEmpty())
        assertTrue(sink.write(byteArrayOf(1, 2, 3), offset = 0, byteCount = 3))
        assertEquals(listOf(3), allocations)

        val rejectedSource = ByteBuffer.wrap(byteArrayOf(4)).apply { position(0) }
        assertFalse(sink.write(rejectedSource, byteCount = 1))
        assertEquals(0, rejectedSource.position())
        assertEquals(EncodedSinkRejection.CallerCap, sink.firstRejection)
        assertFalse(sink.write(byteArrayOf(), offset = 0, byteCount = 0))
        assertFalse(sink.write(ByteBuffer.allocate(0), byteCount = 0))
        assertEquals(listOf(3), allocations)

        sink.exitEncoderInvocation()
        assertEquals(
            SegmentedSinkFinish.Rejected(EncodedSinkRejection.CallerCap),
            sink.finish(),
        )
    }

    @Test
    fun callerCapIsCheckedBeforeAnyGrowthAdmission() {
        val allocations = ArrayList<Int>()
        val sink = sink(maxByteCount = 4, segmentByteCount = 4, allocations = allocations).enterEncoderInvocation()
        assertTrue(sink.write(byteArrayOf(1, 2, 3), offset = 0, byteCount = 3))

        assertFalse(sink.write(byteArrayOf(4, 5), offset = 0, byteCount = 2))

        assertEquals(listOf(4), allocations)
        assertEquals(3, sink.byteCount)
        assertEquals(EncodedSinkRejection.CallerCap, sink.firstRejection)
        sink.exitEncoderInvocation()
        sink.finish()
    }

    @Test
    fun lateGrowthDenialRejectsWholeArrayWriteAndLatchesResourceExhausted() {
        val calls = ArrayList<Int>()
        val allocator = EncodedSegmentAllocator { size ->
            calls += size
            if (calls.size == 2) EncodedSegmentAllocation.Denied else EncodedSegmentAllocation.Granted(ByteArray(size))
        }
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = 12,
            segmentAllocator = allocator,
            releaseObserver = noOpReleaseObserver,
            segmentByteCount = 4,
        ).enterEncoderInvocation()
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertFalse(sink.write(source, offset = 0, byteCount = source.size))

        assertEquals(listOf(4, 4), calls)
        assertEquals(0, sink.byteCount)
        assertEquals(EncodedSinkRejection.ResourceExhausted, sink.firstRejection)
        assertFalse(sink.write(byteArrayOf(), offset = 0, byteCount = 0))
        sink.exitEncoderInvocation()
        assertEquals(
            SegmentedSinkFinish.Rejected(EncodedSinkRejection.ResourceExhausted),
            sink.finish(),
        )
    }

    @Test
    fun lateGrowthOomRejectsWholeBufferWriteAndPreservesPosition() {
        var allocationCount = 0
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = 12,
            segmentAllocator = { size ->
                allocationCount += 1
                if (allocationCount == 2) throw OutOfMemoryError("injected")
                EncodedSegmentAllocation.Granted(ByteArray(size))
            },
            releaseObserver = noOpReleaseObserver,
            segmentByteCount = 4,
        ).enterEncoderInvocation()
        val source = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4, 5, 6)).apply { position(1) }

        assertFalse(sink.write(source, byteCount = 6))

        assertEquals(1, source.position())
        assertEquals(0, sink.byteCount)
        assertEquals(EncodedSinkRejection.ResourceExhausted, sink.firstRejection)
        sink.exitEncoderInvocation()
        sink.finish()
    }

    @Test
    fun successfulWritesSpanSegmentsAndAdvanceBufferOnlyAfterAcceptance() {
        val allocations = ArrayList<Int>()
        val sink = sink(maxByteCount = 10, segmentByteCount = 3, allocations = allocations).enterEncoderInvocation()
        assertTrue(sink.write(byteArrayOf(1, 2), offset = 0, byteCount = 2))
        val buffer = ByteBuffer.wrap(byteArrayOf(9, 3, 4, 5, 6, 8)).apply { position(1) }

        assertTrue(sink.write(buffer, byteCount = 4))

        assertEquals(5, buffer.position())
        assertEquals(6, sink.byteCount)
        assertEquals(listOf(3, 3), allocations)
        sink.exitEncoderInvocation()
        val payload = (sink.finish() as SegmentedSinkFinish.Completed).payload
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(1)
        assertSame(LatestPayloadReplacement.Replaced, slot.replace(null, identity, payload))
        val lease = requireNotNull(slot.acquire(identity))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), lease.copyBytes())
        assertTrue(slot.retire(identity))
        assertTrue(lease.release())
    }

    @Test
    fun everyPhysicalGrowthUsesInjectedCheckAndNeverExceedsPolicySegmentSize() {
        val allocations = ArrayList<Int>()
        val sink = sink(maxByteCount = 9, segmentByteCount = 3, allocations = allocations).enterEncoderInvocation()

        assertTrue(sink.write(byteArrayOf(1, 2), offset = 0, byteCount = 2))
        assertTrue(sink.write(byteArrayOf(3), offset = 0, byteCount = 1))
        assertTrue(sink.write(byteArrayOf(4), offset = 0, byteCount = 1))
        assertTrue(sink.write(byteArrayOf(5, 6, 7, 8), offset = 0, byteCount = 4))

        assertEquals(listOf(3, 3, 3), allocations)
        assertTrue(allocations.all { it <= ScreenCaptureEnginePolicyDefaults.MAX_ENCODED_SEGMENT_BYTES })
        sink.exitEncoderInvocation()
        val payload = (sink.finish() as SegmentedSinkFinish.Completed).payload
        payload.release()
    }

    @Test
    fun firstRejectionIsVisibleToWatchdogBeforeProviderReturns() {
        val rejectionCommitted = CountDownLatch(1)
        val observationComplete = CountDownLatch(1)
        val sink = sink(maxByteCount = 1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = thread(name = "encoder-worker") {
            try {
                sink.enterEncoderInvocation()
                assertFalse(sink.write(byteArrayOf(1, 2), offset = 0, byteCount = 2))
                rejectionCommitted.countDown()
                observationComplete.await()
                sink.exitEncoderInvocation()
            } catch (throwable: Throwable) {
                workerFailure.set(throwable)
            }
        }

        rejectionCommitted.await()
        assertEquals(EncodedSinkRejection.CallerCap, sink.firstRejection)
        sink.invalidate()
        observationComplete.countDown()
        worker.join()
        assertNull(workerFailure.get())
        assertEquals(
            SegmentedSinkFinish.Rejected(EncodedSinkRejection.CallerCap),
            sink.finish(),
        )
    }

    @Test
    fun invalidationDoesNotWaitForAnInProgressGrowthAdmission() {
        val allocationEntered = CountDownLatch(1)
        val allowAllocationReturn = CountDownLatch(1)
        val invalidationReturned = CountDownLatch(1)
        val writeResult = AtomicReference<Boolean?>()
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = 4,
            segmentAllocator = { size ->
                allocationEntered.countDown()
                allowAllocationReturn.await()
                EncodedSegmentAllocation.Granted(ByteArray(size))
            },
            releaseObserver = noOpReleaseObserver,
            segmentByteCount = 4,
        )
        val workerFailure = AtomicReference<Throwable?>()
        val worker = thread(name = "encoder-worker") {
            try {
                sink.enterEncoderInvocation()
                writeResult.set(sink.write(byteArrayOf(1), offset = 0, byteCount = 1))
                sink.exitEncoderInvocation()
            } catch (throwable: Throwable) {
                workerFailure.set(throwable)
            }
        }
        allocationEntered.await()
        val invalidator = thread(name = "sink-watchdog") {
            sink.invalidate()
            invalidationReturned.countDown()
        }

        try {
            assertTrue(
                "Attempt invalidation must not wait for provider-side growth admission.",
                invalidationReturned.await(1, TimeUnit.SECONDS),
            )
        } finally {
            allowAllocationReturn.countDown()
        }
        invalidator.join()
        worker.join()

        assertNull(workerFailure.get())
        assertEquals(
            "The overlapping write linearizes at its initial admitted access.",
            true,
            writeResult.get(),
        )
        assertSame(SegmentedSinkFinish.Invalidated, sink.finish())
    }

    @Test
    fun invalidationFencesActiveAndNotStartedAttempts() {
        val entered = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val activeSink = sink(maxByteCount = 4)
        val accessFailure = AtomicReference<Throwable?>()
        val worker = thread(name = "encoder-worker") {
            activeSink.enterEncoderInvocation()
            entered.countDown()
            invalidated.await()
            accessFailure.set(
                runCatching { activeSink.write(byteArrayOf(1), offset = 0, byteCount = 1) }.exceptionOrNull(),
            )
            activeSink.exitEncoderInvocation()
        }

        entered.await()
        activeSink.invalidate()
        invalidated.countDown()
        worker.join()
        assertTrue(accessFailure.get() is IllegalStateException)
        assertSame(SegmentedSinkFinish.Invalidated, activeSink.finish())

        val notStartedSink = sink(maxByteCount = 4)
        notStartedSink.invalidate()
        assertFails<IllegalStateException> { notStartedSink.enterEncoderInvocation() }
        assertSame(SegmentedSinkFinish.Invalidated, notStartedSink.finish())
    }

    private fun sink(
        maxByteCount: Int,
        segmentByteCount: Int = 4,
        allocations: MutableList<Int> = ArrayList(),
    ): SegmentedEncodedSink = SegmentedEncodedSink(
        callerMaxByteCount = maxByteCount,
        segmentAllocator = { size ->
            allocations += size
            EncodedSegmentAllocation.Granted(ByteArray(size))
        },
        releaseObserver = noOpReleaseObserver,
        segmentByteCount = segmentByteCount,
    )

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        val throwable = runCatching(block).exceptionOrNull()
        assertTrue("Expected ${T::class.java.name}, was $throwable", throwable is T)
    }

    private companion object {
        val noOpReleaseObserver: EncodedPayloadReleaseObserver = EncodedPayloadReleaseObserver { _ -> }
    }
}
