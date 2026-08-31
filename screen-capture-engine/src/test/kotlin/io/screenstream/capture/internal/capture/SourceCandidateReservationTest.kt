package io.screenstream.capture.internal.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SourceCandidateReservationTest {
    // Verification: CAP-02
    @Test
    fun availabilityIsConflatedAndSettlementControlsTheNextReservation() {
        val candidate = SourceCandidate()

        assertFalse(candidate.reserve())
        assertTrue(candidate.markAvailable())
        assertFalse(candidate.markAvailable())
        assertTrue(candidate.reserve())
        assertFalse(candidate.reserve())
        assertFalse(candidate.markAvailable())

        candidate.settle(sourceConsumed = false)

        assertTrue(candidate.reserve())
        candidate.settle(sourceConsumed = true)
        assertFalse(candidate.reserve())

        assertTrue(candidate.markAvailable())
        assertTrue(candidate.reserve())
    }
}
