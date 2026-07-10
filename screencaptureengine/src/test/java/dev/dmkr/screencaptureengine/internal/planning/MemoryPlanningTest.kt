package dev.dmkr.screencaptureengine.internal.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigInteger

class MemoryPlanningTest {
    @Test
    fun coexistenceMatchesIndependentBigIntegerOracleAndDetectsOverflow() {
        val footprints = listOf(
            MemoryFootprint(10L, 30L),
            MemoryFootprint(20L, 50L),
            MemoryFootprint(30L, 70L),
        )
        val expectedManaged = footprints.sumBig(MemoryFootprint::managedHeapBytes).longValueExact()
        val expectedTotal = footprints.sumBig(MemoryFootprint::totalEstimatedBytes).longValueExact()

        assertEquals(
            MemoryFootprintFact.Value(MemoryFootprint(expectedManaged, expectedTotal)),
            MemoryPlanning.coexistence(footprints),
        )
        assertSame(
            MemoryFootprintFact.Overflow,
            MemoryPlanning.coexistence(
                listOf(MemoryFootprint(Long.MAX_VALUE, Long.MAX_VALUE), MemoryFootprint(1L, 1L)),
            ),
        )
    }

    @Test
    fun replacementPeakSubtractsOnlyProvenReleasedResources() {
        val result = MemoryPlanning.replacementPeak(
            previous = MemoryFootprint(managedHeapBytes = 100L, totalEstimatedBytes = 300L),
            candidate = MemoryFootprint(managedHeapBytes = 70L, totalEstimatedBytes = 200L),
            provenReleasedBeforeAllocation = MemoryFootprint(managedHeapBytes = 40L, totalEstimatedBytes = 120L),
        ) as ReplacementMemoryFact.Value

        assertEquals(MemoryFootprint(60L, 180L), result.estimate.retainedPrevious)
        assertEquals(MemoryFootprint(130L, 380L), result.estimate.peak)
        assertSame(
            ReplacementMemoryFact.InvalidInput,
            MemoryPlanning.replacementPeak(
                previous = MemoryFootprint(1L, 2L),
                candidate = MemoryFootprint(1L, 2L),
                provenReleasedBeforeAllocation = MemoryFootprint(2L, 2L),
            ),
        )
    }

    @Test
    fun headroomUsesClampToZeroAndRejectsMissingOrStaleEvidence() {
        assertEquals(
            MemoryHeadroomFact.Value(MemoryHeadroom(javaBytes = 0L, systemBytes = 0L, lowMemory = false)),
            MemoryPlanning.headroom(evidence(maxJava = 100L, usedJava = 120L, available = 40L, threshold = 50L)),
        )
        assertSame(MemoryHeadroomFact.InvalidEvidence, MemoryPlanning.headroom(evidence(isCurrent = false)))
        assertSame(MemoryHeadroomFact.InvalidEvidence, MemoryPlanning.headroom(evidence(available = -1L)))
    }

    @Test
    fun admissionChecksLowMemoryThenManagedAndSystemHeadroom() {
        val allocation = MemoryFootprint(managedHeapBytes = 40L, totalEstimatedBytes = 90L)
        assertEquals(
            MemoryAdmissionFact.Admitted(allocation, MemoryHeadroom(50L, 100L, false)),
            MemoryPlanning.admit(allocation, evidence()),
        )
        assertDenied(MemoryAdmissionDenial.LowMemory, allocation, evidence(lowMemory = true))
        assertDenied(MemoryAdmissionDenial.JavaHeadroom, allocation, evidence(usedJava = 70L))
        assertDenied(MemoryAdmissionDenial.SystemHeadroom, allocation, evidence(available = 100L, threshold = 20L))
        assertSame(MemoryAdmissionFact.InvalidEvidence, MemoryPlanning.admit(allocation, evidence(isCurrent = false)))
    }

    @Test
    fun footprintAcceptsArbitraryRepresentableValuesWithoutPercentageOrPixelCaps() {
        assertEquals(
            MemoryFootprintFact.Value(MemoryFootprint(Long.MAX_VALUE - 1L, Long.MAX_VALUE)),
            MemoryPlanning.footprint(Long.MAX_VALUE - 1L, Long.MAX_VALUE),
        )
        assertSame(MemoryFootprintFact.InvalidInput, MemoryPlanning.footprint(2L, 1L))
    }

    private fun assertDenied(
        reason: MemoryAdmissionDenial,
        allocation: MemoryFootprint,
        evidence: MemoryRuntimeEvidence,
    ) {
        assertEquals(reason, (MemoryPlanning.admit(allocation, evidence) as MemoryAdmissionFact.Denied).reason)
    }

    private fun evidence(
        maxJava: Long = 100L,
        usedJava: Long = 50L,
        available: Long = 120L,
        threshold: Long = 20L,
        lowMemory: Boolean = false,
        isCurrent: Boolean = true,
    ): MemoryRuntimeEvidence = MemoryRuntimeEvidence(
        maxJavaHeapBytes = maxJava,
        usedJavaHeapBytes = usedJava,
        systemAvailableBytes = available,
        systemThresholdBytes = threshold,
        lowMemory = lowMemory,
        isCurrent = isCurrent,
    )

    private fun List<MemoryFootprint>.sumBig(selector: (MemoryFootprint) -> Long): BigInteger =
        fold(BigInteger.ZERO) { total, footprint -> total + selector(footprint).toBigInteger() }
}
