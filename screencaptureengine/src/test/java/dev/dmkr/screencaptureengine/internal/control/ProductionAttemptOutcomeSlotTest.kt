package dev.dmkr.screencaptureengine.internal.control

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAttemptOutcomeSlotTest {
    @Test
    fun slotStartsEmpty() {
        assertNull(ProductionAttemptOutcomeSlot().outcome)
    }

    @Test
    fun firstOutcomeWinsEveryOrderedPairAndLateOutcomeCannotReplaceIt() {
        outcomes.forEach { first ->
            outcomes.forEach { late ->
                val slot = ProductionAttemptOutcomeSlot()

                assertTrue("First outcome $first was rejected", slot.tryCommit(first))
                assertFalse("Late outcome $late replaced $first", slot.tryCommit(late))
                assertEquals(first, slot.outcome)
            }
        }
    }

    @Test
    fun concurrentCommitStillAcceptsExactlyOneOutcome() {
        val slot = ProductionAttemptOutcomeSlot()
        val release = CountDownLatch(1)
        val acceptedCount = AtomicInteger()
        val threads = listOf(
            Thread {
                release.await()
                if (slot.tryCommit(ProductionAttemptOutcome.Failure)) acceptedCount.incrementAndGet()
            },
            Thread {
                release.await()
                if (slot.tryCommit(ProductionAttemptOutcome.StaleWork)) acceptedCount.incrementAndGet()
            },
        )

        threads.forEach(Thread::start)
        release.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, acceptedCount.get())
        assertTrue(slot.outcome == ProductionAttemptOutcome.Failure || slot.outcome == ProductionAttemptOutcome.StaleWork)
    }

    private companion object {
        val outcomes = listOf(
            ProductionAttemptOutcome.Completed(published = true),
            ProductionAttemptOutcome.Completed(published = false),
            ProductionAttemptOutcome.RateLimited,
            ProductionAttemptOutcome.PipelineBusy,
            ProductionAttemptOutcome.StaleWork,
            ProductionAttemptOutcome.Failure,
            ProductionAttemptOutcome.EncodedSizeLimit,
        )
    }
}
