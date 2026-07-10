package dev.dmkr.screencaptureengine.internal.control

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlSequenceAllocatorTest {
    @Test
    fun defaultDomainStartsAtOne() {
        val allocator = ControlSequenceAllocator()

        assertEquals(ControlSequenceAllocation.Value(1L), allocator.allocate())
        assertEquals(ControlSequenceAllocation.Value(2L), allocator.allocate())
    }

    @Test
    fun maxValueIsReservedForOneExhaustionFactAndNoLaterAllocation() {
        val allocator = ControlSequenceAllocator(initialNextSequence = Long.MAX_VALUE - 1L)

        assertEquals(ControlSequenceAllocation.Value(Long.MAX_VALUE - 1L), allocator.allocate())
        assertEquals(ControlSequenceAllocation.Exhausted.fact, allocator.allocate())
        assertEquals(Long.MAX_VALUE, ControlSequenceAllocation.Exhausted.fact.sequence)
        assertEquals(ControlSequenceAllocation.Unavailable, allocator.allocate())
        assertEquals(ControlSequenceAllocation.Unavailable, allocator.allocate())
    }

    @Test
    fun injectedSequenceMustRemainInsideTheDomain() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlSequenceAllocator(initialNextSequence = 0L)
        }
    }

    @Test
    fun concurrentBoundaryAllocationEmitsOneOrdinaryAndOneExhaustionFact() {
        val allocator = ControlSequenceAllocator(initialNextSequence = Long.MAX_VALUE - 1L)
        val release = CountDownLatch(1)
        val first = AtomicReference<ControlSequenceAllocation>()
        val second = AtomicReference<ControlSequenceAllocation>()
        val firstThread = Thread {
            release.await()
            first.set(allocator.allocate())
        }
        val secondThread = Thread {
            release.await()
            second.set(allocator.allocate())
        }

        firstThread.start()
        secondThread.start()
        release.countDown()
        firstThread.join()
        secondThread.join()

        assertEquals(
            setOf(
                ControlSequenceAllocation.Value(Long.MAX_VALUE - 1L),
                ControlSequenceAllocation.Exhausted.fact,
            ),
            setOf(first.get(), second.get()),
        )
        assertEquals(ControlSequenceAllocation.Unavailable, allocator.allocate())
    }
}
