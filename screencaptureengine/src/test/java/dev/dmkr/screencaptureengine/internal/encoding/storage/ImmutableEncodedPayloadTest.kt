package dev.dmkr.screencaptureengine.internal.encoding.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ImmutableEncodedPayloadTest {
    @Test
    fun finishTransfersOrderedImmutableSegmentsWithoutDependingOnSource() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val releaseCount = AtomicInteger()
        val payload = payloadOf(source, segmentByteCount = 2, releaseCount = releaseCount)
        source.fill(99)
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(1)
        slot.replace(null, identity, payload)
        val lease = requireNotNull(slot.acquire(identity))

        assertEquals(5, lease.byteCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), lease.copyBytes())
        val destination = ByteArray(8) { -1 }
        assertEquals(5, lease.copyTo(destination, destinationOffset = 2))
        assertArrayEquals(byteArrayOf(-1, -1, 1, 2, 3, 4, 5, -1), destination)

        assertTrue(slot.retire(identity))
        assertEquals(0, releaseCount.get())
        assertTrue(lease.release())
        assertEquals(1, releaseCount.get())
    }

    @Test
    fun invalidCopyRangeCopiesNothing() {
        val payload = payloadOf(byteArrayOf(1, 2, 3), segmentByteCount = 2)
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(1)
        slot.replace(null, identity, payload)
        val lease = requireNotNull(slot.acquire(identity))
        val destination = byteArrayOf(9, 9, 9, 9)

        assertFails<IndexOutOfBoundsException> { lease.copyTo(destination, destinationOffset = -1) }
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), destination)
        assertFails<IndexOutOfBoundsException> { lease.copyTo(destination, destinationOffset = 2) }
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), destination)

        slot.retire(identity)
        lease.release()
    }

    @Test
    fun untransferredOwnerAndLeaseReleaseAreExactlyOnce() {
        val directReleaseCount = AtomicInteger()
        val untransferred = payloadOf(byteArrayOf(1), releaseCount = directReleaseCount)
        assertTrue(untransferred.release())
        assertFalse(untransferred.release())
        assertEquals(1, directReleaseCount.get())
        assertFails<IllegalStateException> { untransferred.byteCount }
        assertFails<IllegalStateException> { untransferred.allocatedByteCount }

        val leaseReleaseCount = AtomicInteger()
        val payload = payloadOf(byteArrayOf(2), releaseCount = leaseReleaseCount)
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(2)
        slot.replace(null, identity, payload)
        val lease = requireNotNull(slot.acquire(identity))
        assertTrue(slot.retire(identity))
        assertEquals(0, leaseReleaseCount.get())
        assertTrue(lease.release())
        assertFalse(lease.release())
        assertEquals(1, leaseReleaseCount.get())
        assertFails<IllegalStateException> { lease.byteCount }
        assertFails<IllegalStateException> { lease.allocatedByteCount }
        assertFails<IllegalStateException> { lease.copyBytes() }
    }

    @Test
    fun movedPayloadCannotBeReleasedOrTransferredAgain() {
        val payload = payloadOf(byteArrayOf(1, 2))
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(1)
        assertSame(LatestPayloadReplacement.Replaced, slot.replace(null, identity, payload))

        assertFalse(payload.release())
        assertFails<IllegalStateException> { payload.byteCount }
        assertFails<IllegalStateException> { payload.allocatedByteCount }
        assertFails<IllegalStateException> {
            LatestEncodedPayloadSlot().replace(null, EncodedPayloadIdentity(2), payload)
        }
        assertTrue(slot.retire(identity))
    }

    @Test
    fun retainedAllocationChargeIsDistinctFromLogicalBytesAndLivesThroughTheFinalLease() {
        val releasedAllocation = AtomicInteger(-1)
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = 8,
            segmentAllocator = { size -> EncodedSegmentAllocation.Granted(ByteArray(size)) },
            releaseObserver = { allocatedByteCount ->
                releasedAllocation.set(allocatedByteCount)
            },
            segmentByteCount = 4,
        ).enterEncoderInvocation()
        assertTrue(sink.write(byteArrayOf(7), offset = 0, byteCount = 1))
        sink.exitEncoderInvocation()
        val payload = (sink.finish() as SegmentedSinkFinish.Completed).payload

        assertEquals(1, payload.byteCount)
        assertEquals(4, payload.allocatedByteCount)
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(3)
        assertSame(LatestPayloadReplacement.Replaced, slot.replace(null, identity, payload))
        val lease = requireNotNull(slot.acquire(identity))
        assertEquals(1, lease.byteCount)
        assertEquals(4, lease.allocatedByteCount)

        assertTrue(slot.retire(identity))
        assertEquals(-1, releasedAllocation.get())
        assertTrue(lease.release())
        assertEquals(4, releasedAllocation.get())
    }

    private fun payloadOf(
        bytes: ByteArray,
        segmentByteCount: Int = 4,
        releaseCount: AtomicInteger = AtomicInteger(),
    ): ImmutableEncodedPayload {
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = bytes.size,
            segmentAllocator = { size -> EncodedSegmentAllocation.Granted(ByteArray(size)) },
            segmentByteCount = segmentByteCount,
            releaseObserver = EncodedPayloadReleaseObserver { _ -> releaseCount.incrementAndGet() },
        ).enterEncoderInvocation()
        assertTrue(sink.write(bytes, offset = 0, byteCount = bytes.size))
        sink.exitEncoderInvocation()
        return (sink.finish() as SegmentedSinkFinish.Completed).payload
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        val throwable = runCatching(block).exceptionOrNull()
        assertTrue("Expected ${T::class.java.name}, was $throwable", throwable is T)
    }
}
