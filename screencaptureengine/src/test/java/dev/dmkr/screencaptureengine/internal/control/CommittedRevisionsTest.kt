package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommittedRevisionsTest {
    @Test
    fun controllerCreationStartsEveryRevisionAtZero() {
        assertEquals(
            CommittedRevisions(
                geometryRevision = 0L,
                targetRevision = 0L,
                outputRevision = 0L,
            ),
            CommittedRevisions(),
        )
    }

    @Test
    fun eachDomainAdvancesIndependently() {
        val initial = CommittedRevisions()

        assertEquals(
            CommittedRevisions(geometryRevision = 1L),
            initial.advanceGeometry().advanced(),
        )
        assertEquals(
            CommittedRevisions(targetRevision = 1L),
            initial.advanceTarget().advanced(),
        )
        assertEquals(
            CommittedRevisions(outputRevision = 1L),
            initial.advanceOutput().advanced(),
        )
        assertEquals(CommittedRevisions(), initial)
    }

    @Test
    fun finalLongValueIsRepresentableButNoDomainCanWrap() {
        for (domain in CommittedRevisionDomain.entries) {
            val nearMaximum = when (domain) {
                CommittedRevisionDomain.Geometry -> CommittedRevisions(geometryRevision = Long.MAX_VALUE - 1L)
                CommittedRevisionDomain.Target -> CommittedRevisions(targetRevision = Long.MAX_VALUE - 1L)
                CommittedRevisionDomain.Output -> CommittedRevisions(outputRevision = Long.MAX_VALUE - 1L)
            }
            val maximum = nearMaximum.advance(domain).advanced()
            val exhausted = maximum.advance(domain) as CommittedRevisionAdvance.Exhausted

            assertEquals(Long.MAX_VALUE, maximum.value(domain))
            assertEquals(domain, exhausted.domain)
            assertEquals(maximum, exhausted.revisions)
        }
    }

    @Test
    fun injectedRevisionCannotBeNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            CommittedRevisions(outputRevision = -1L)
        }
    }

    private fun CommittedRevisions.advance(domain: CommittedRevisionDomain): CommittedRevisionAdvance = when (domain) {
        CommittedRevisionDomain.Geometry -> advanceGeometry()
        CommittedRevisionDomain.Target -> advanceTarget()
        CommittedRevisionDomain.Output -> advanceOutput()
    }

    private fun CommittedRevisions.value(domain: CommittedRevisionDomain): Long = when (domain) {
        CommittedRevisionDomain.Geometry -> geometryRevision
        CommittedRevisionDomain.Target -> targetRevision
        CommittedRevisionDomain.Output -> outputRevision
    }

    private fun CommittedRevisionAdvance.advanced(): CommittedRevisions =
        (this as CommittedRevisionAdvance.Advanced).revisions
}
