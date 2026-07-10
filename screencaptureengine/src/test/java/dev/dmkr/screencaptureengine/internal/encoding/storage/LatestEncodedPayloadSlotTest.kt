package dev.dmkr.screencaptureengine.internal.encoding.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LatestEncodedPayloadSlotTest {
    @Test
    fun acquireReplaceAndRetireAreIdentityMatched() {
        val slot = LatestEncodedPayloadSlot()
        val firstIdentity = EncodedPayloadIdentity(1)
        val secondIdentity = EncodedPayloadIdentity(2)
        val first = payloadOf(byteArrayOf(1))

        assertSame(LatestPayloadReplacement.Replaced, slot.replace(null, firstIdentity, first))
        assertTrue(requireNotNull(slot.acquire(firstIdentity)).release())
        assertNull(slot.acquire(secondIdentity))
        assertFalse(slot.retire(secondIdentity))

        val mismatchPayload = payloadOf(byteArrayOf(2))
        assertEquals(
            LatestPayloadReplacement.IdentityMismatch(firstIdentity),
            slot.replace(null, secondIdentity, mismatchPayload),
        )
        assertTrue(mismatchPayload.release())
        assertTrue(slot.retire(firstIdentity))
        assertNull(slot.acquire(firstIdentity))
    }

    @Test
    fun oldLeaseKeepsSegmentsAliveAcrossReplacementAndRetirement() {
        val firstReleaseCount = AtomicInteger()
        val secondReleaseCount = AtomicInteger()
        val firstReleasedAllocation = AtomicInteger(-1)
        val secondReleasedAllocation = AtomicInteger(-1)
        val slot = LatestEncodedPayloadSlot()
        val firstIdentity = EncodedPayloadIdentity(1)
        val secondIdentity = EncodedPayloadIdentity(2)
        slot.replace(
            null,
            firstIdentity,
            payloadOf(byteArrayOf(1, 2, 3), firstReleaseCount, firstReleasedAllocation),
        )
        val firstLease = requireNotNull(slot.acquire(firstIdentity))

        assertEquals(
            LatestPayloadReplacement.Replaced,
            slot.replace(
                firstIdentity,
                secondIdentity,
                payloadOf(byteArrayOf(4, 5), secondReleaseCount, secondReleasedAllocation),
            ),
        )
        assertEquals(0, firstReleaseCount.get())
        assertArrayEquals(byteArrayOf(1, 2, 3), firstLease.copyBytes())
        val secondLease = requireNotNull(slot.acquire(secondIdentity))
        assertTrue(slot.retire(secondIdentity))
        assertEquals(0, secondReleaseCount.get())

        assertTrue(firstLease.release())
        assertEquals(1, firstReleaseCount.get())
        assertEquals(3, firstReleasedAllocation.get())
        assertTrue(secondLease.release())
        assertEquals(1, secondReleaseCount.get())
        assertEquals(2, secondReleasedAllocation.get())
    }

    @Test
    fun concurrentReplacementFromSameExpectedIdentityHasOneWinner() {
        val slot = LatestEncodedPayloadSlot()
        val initialIdentity = EncodedPayloadIdentity(1)
        slot.replace(null, initialIdentity, payloadOf(byteArrayOf(0)))
        val firstPayload = payloadOf(byteArrayOf(1))
        val secondPayload = payloadOf(byteArrayOf(2))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<ReplacementAttempt>()
        val workers = listOf(
            EncodedPayloadIdentity(2) to firstPayload,
            EncodedPayloadIdentity(3) to secondPayload,
        ).map { (identity, payload) ->
            thread(name = "payload-replace-${identity.value}") {
                ready.countDown()
                start.await()
                results += ReplacementAttempt(identity, payload, slot.replace(initialIdentity, identity, payload))
            }
        }

        ready.await()
        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(1, results.count { it.result === LatestPayloadReplacement.Replaced })
        assertEquals(1, results.count { it.result is LatestPayloadReplacement.IdentityMismatch })
        results.single { it.result is LatestPayloadReplacement.IdentityMismatch }.payload.release()
        val winnerIdentity = results.single { it.result === LatestPayloadReplacement.Replaced }.identity
        assertTrue(requireNotNull(slot.acquire(winnerIdentity)).release())
        assertTrue(slot.retire(winnerIdentity))
    }

    @Test
    fun concurrentAcquireAndRetireEitherReturnsValidLeaseOrNothing() {
        repeat(100) { iteration ->
            val releaseCount = AtomicInteger()
            val slot = LatestEncodedPayloadSlot()
            val identity = EncodedPayloadIdentity(iteration.toLong() + 1L)
            slot.replace(null, identity, payloadOf(byteArrayOf(1, 2), releaseCount))
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val acquired = AtomicReference<ImmutableEncodedPayloadLease?>()
            val acquireThread = thread {
                ready.countDown()
                start.await()
                acquired.set(slot.acquire(identity))
            }
            val retireThread = thread {
                ready.countDown()
                start.await()
                slot.retire(identity)
            }

            ready.await()
            start.countDown()
            acquireThread.join()
            retireThread.join()

            val lease = acquired.get()
            if (lease != null) {
                assertArrayEquals(byteArrayOf(1, 2), lease.copyBytes())
                assertEquals(0, releaseCount.get())
                assertTrue(lease.release())
            }
            assertEquals(1, releaseCount.get())
            assertNull(slot.acquire(identity))
        }
    }

    @Test
    fun concurrentLeaseReleaseTriggersOnePhysicalRelease() {
        val releaseCount = AtomicInteger()
        val releasedAllocation = AtomicInteger(-1)
        val slot = LatestEncodedPayloadSlot()
        val identity = EncodedPayloadIdentity(1)
        slot.replace(null, identity, payloadOf(byteArrayOf(1, 2, 3), releaseCount, releasedAllocation))
        val leases = List(16) { requireNotNull(slot.acquire(identity)) }
        assertTrue(slot.retire(identity))
        val ready = CountDownLatch(leases.size)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = leases.mapIndexed { index, lease ->
            thread(name = "payload-release-$index") {
                try {
                    ready.countDown()
                    start.await()
                    assertTrue(lease.release())
                } catch (throwable: Throwable) {
                    failures += throwable
                }
            }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, releaseCount.get())
        assertEquals(3, releasedAllocation.get())
        assertTrue(failures.toString(), failures.isEmpty())
        assertTrue(leases.none { it.release() })
    }

    private fun payloadOf(
        bytes: ByteArray,
        releaseCount: AtomicInteger = AtomicInteger(),
        releasedAllocation: AtomicInteger = AtomicInteger(-1),
    ): ImmutableEncodedPayload {
        val sink = SegmentedEncodedSink(
            callerMaxByteCount = bytes.size,
            segmentAllocator = { size -> EncodedSegmentAllocation.Granted(ByteArray(size)) },
            segmentByteCount = 1,
            releaseObserver = EncodedPayloadReleaseObserver { allocatedByteCount ->
                releasedAllocation.set(allocatedByteCount)
                releaseCount.incrementAndGet()
            },
        ).enterEncoderInvocation()
        assertTrue(sink.write(bytes, offset = 0, byteCount = bytes.size))
        sink.exitEncoderInvocation()
        return (sink.finish() as SegmentedSinkFinish.Completed).payload
    }

    private data class ReplacementAttempt(
        val identity: EncodedPayloadIdentity,
        val payload: ImmutableEncodedPayload,
        val result: LatestPayloadReplacement,
    )
}
