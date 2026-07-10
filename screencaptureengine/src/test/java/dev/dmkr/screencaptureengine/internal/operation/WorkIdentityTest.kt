package dev.dmkr.screencaptureengine.internal.operation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkIdentityTest {
    @Test
    fun defaultAllocatorStartsAtOne() {
        val allocator = WorkIdentityAllocator()

        assertEquals(WorkIdentityAllocation.Allocated(WorkIdentity(1L)), allocator.allocate())
        assertEquals(WorkIdentityAllocation.Allocated(WorkIdentity(2L)), allocator.allocate())
    }

    @Test
    fun maxIdentityIsAllocatedOnceAndDomainNeverWraps() {
        val allocator = WorkIdentityAllocator(initialNextIdentity = Long.MAX_VALUE - 1L)

        assertEquals(
            WorkIdentityAllocation.Allocated(WorkIdentity(Long.MAX_VALUE - 1L)),
            allocator.allocate(),
        )
        assertEquals(WorkIdentityAllocation.Allocated(WorkIdentity(Long.MAX_VALUE)), allocator.allocate())
        assertEquals(WorkIdentityAllocation.Exhausted, allocator.allocate())
        assertEquals(WorkIdentityAllocation.Exhausted, allocator.allocate())
    }

    @Test
    fun concurrentBoundaryAllocatesMaxExactlyOnce() {
        val allocator = WorkIdentityAllocator(initialNextIdentity = Long.MAX_VALUE)
        val release = CountDownLatch(1)
        val first = AtomicReference<WorkIdentityAllocation>()
        val second = AtomicReference<WorkIdentityAllocation>()
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
                WorkIdentityAllocation.Allocated(WorkIdentity(Long.MAX_VALUE)),
                WorkIdentityAllocation.Exhausted,
            ),
            setOf(first.get(), second.get()),
        )
        assertEquals(WorkIdentityAllocation.Exhausted, allocator.allocate())
    }

    @Test
    fun typedValuesRejectNonpositiveInput() {
        assertThrows(IllegalArgumentException::class.java) { WorkIdentity(0L) }
        assertThrows(IllegalArgumentException::class.java) { WorkIdentityAllocator(0L) }
        assertThrows(IllegalArgumentException::class.java) { OperationCancellationMarker(0L) }
    }
}
