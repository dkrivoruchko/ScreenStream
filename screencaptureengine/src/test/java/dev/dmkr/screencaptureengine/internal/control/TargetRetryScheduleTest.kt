package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRetryScheduleTest {
    @Test
    fun epochOffersExactlyThreeTotalSlotsAtZeroOneHundredAndFiveHundredMillis() {
        val origin = 1_000_000_000L
        val schedule = TargetRetrySchedule(epochOriginNanos = origin)

        assertEquals(MonotonicDeadline.Finite(origin), schedule.nextEligibility)
        val first = schedule.tryAdmit(origin)!!
        assertEquals(1, first.slotNumber)
        assertEquals(2, schedule.remainingAdmissionCount)
        assertNull(schedule.tryAdmit(origin + 1L))

        assertEquals(
            TargetRetryAdvance.NextSlot(MonotonicDeadline.Finite(origin + 100L * NANOS_PER_MILLISECOND)),
            schedule.recordQualifyingTransientReturn(first),
        )
        assertNull(schedule.tryAdmit(origin + 100L * NANOS_PER_MILLISECOND - 1L))
        val second = schedule.tryAdmit(origin + 100L * NANOS_PER_MILLISECOND)!!
        assertEquals(2, second.slotNumber)

        assertEquals(
            TargetRetryAdvance.NextSlot(MonotonicDeadline.Finite(origin + 500L * NANOS_PER_MILLISECOND)),
            schedule.recordQualifyingTransientReturn(second),
        )
        val third = schedule.tryAdmit(origin + 500L * NANOS_PER_MILLISECOND)!!
        assertEquals(3, third.slotNumber)
        assertEquals(0, schedule.remainingAdmissionCount)
        assertSame(TargetRetryAdvance.Exhausted, schedule.recordQualifyingTransientReturn(third))
        assertNull(schedule.nextEligibility)
        assertNull(schedule.tryAdmit(Long.MAX_VALUE))
    }

    @Test
    fun overdueSlotsRemainAnchoredToOneEpochAndCanStartImmediatelyAfterCleanup() {
        val origin = 5_000L
        val overdueNow = origin + 600L * NANOS_PER_MILLISECOND
        val schedule = TargetRetrySchedule(origin)

        val first = schedule.tryAdmit(origin)!!
        schedule.recordQualifyingTransientReturn(first)
        val second = schedule.tryAdmit(overdueNow)!!
        schedule.recordQualifyingTransientReturn(second)
        val third = schedule.tryAdmit(overdueNow)!!

        assertEquals(2, second.slotNumber)
        assertEquals(MonotonicDeadline.Finite(origin + 100L * NANOS_PER_MILLISECOND), second.eligibleAt)
        assertEquals(3, third.slotNumber)
        assertEquals(MonotonicDeadline.Finite(origin + 500L * NANOS_PER_MILLISECOND), third.eligibleAt)
    }

    @Test
    fun admissionConsumesSlotAndOnlyQualifyingReturnUnlocksAnother() {
        val schedule = TargetRetrySchedule(epochOriginNanos = 0L)
        val first = schedule.tryAdmit(0L)!!

        assertEquals(2, schedule.remainingAdmissionCount)
        assertTrue(schedule.recordNonQualifyingCompletion(first))
        assertEquals(0, schedule.remainingAdmissionCount)
        assertNull(schedule.nextEligibility)
        assertNull(schedule.tryAdmit(Long.MAX_VALUE))
        assertFalse(schedule.recordNonQualifyingCompletion(first))
        assertSame(TargetRetryAdvance.Stale, schedule.recordQualifyingTransientReturn(first))
    }

    @Test
    fun admissionFromAnotherEpochCannotAdvanceThisSchedule() {
        val firstSchedule = TargetRetrySchedule(epochOriginNanos = 0L)
        val secondSchedule = TargetRetrySchedule(epochOriginNanos = 0L)
        val foreignAdmission = firstSchedule.tryAdmit(0L)!!

        assertSame(TargetRetryAdvance.Stale, secondSchedule.recordQualifyingTransientReturn(foreignAdmission))
        assertEquals(MonotonicDeadline.Finite(0L), secondSchedule.nextEligibility)
    }

    @Test
    fun overflowingEpochOffsetBecomesTaggedInfiniteEligibility() {
        val origin = Long.MAX_VALUE - 50L * NANOS_PER_MILLISECOND
        val schedule = TargetRetrySchedule(epochOriginNanos = origin)
        val first = schedule.tryAdmit(origin)!!

        assertEquals(
            TargetRetryAdvance.NextSlot(MonotonicDeadline.InfiniteFuture),
            schedule.recordQualifyingTransientReturn(first),
        )
        assertSame(MonotonicDeadline.InfiniteFuture, schedule.nextEligibility)
        assertNull(schedule.tryAdmit(Long.MAX_VALUE))
    }
}

private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
