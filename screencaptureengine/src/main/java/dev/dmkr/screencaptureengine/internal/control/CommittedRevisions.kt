@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.control

internal enum class CommittedRevisionDomain {
    Geometry,
    Target,
    Output,
}

internal sealed interface CommittedRevisionAdvance {
    data class Advanced(
        val revisions: CommittedRevisions,
    ) : CommittedRevisionAdvance

    data class Exhausted(
        val revisions: CommittedRevisions,
        val domain: CommittedRevisionDomain,
    ) : CommittedRevisionAdvance
}

/** Immutable controller-owned `Gg`/`Gt`/`Go` snapshot with no wraparound path. */
@ConsistentCopyVisibility
internal data class CommittedRevisions internal constructor(
    val geometryRevision: Long = 0L,
    val targetRevision: Long = 0L,
    val outputRevision: Long = 0L,
) {
    init {
        require(geometryRevision >= 0L) { "Geometry revision must be non-negative." }
        require(targetRevision >= 0L) { "Target revision must be non-negative." }
        require(outputRevision >= 0L) { "Output revision must be non-negative." }
    }

    internal fun advanceGeometry(): CommittedRevisionAdvance = advance(CommittedRevisionDomain.Geometry)

    internal fun advanceTarget(): CommittedRevisionAdvance = advance(CommittedRevisionDomain.Target)

    internal fun advanceOutput(): CommittedRevisionAdvance = advance(CommittedRevisionDomain.Output)

    private fun advance(domain: CommittedRevisionDomain): CommittedRevisionAdvance {
        val current = when (domain) {
            CommittedRevisionDomain.Geometry -> geometryRevision
            CommittedRevisionDomain.Target -> targetRevision
            CommittedRevisionDomain.Output -> outputRevision
        }
        if (current == Long.MAX_VALUE) {
            return CommittedRevisionAdvance.Exhausted(revisions = this, domain = domain)
        }

        val advanced = when (domain) {
            CommittedRevisionDomain.Geometry -> copy(geometryRevision = current + 1L)
            CommittedRevisionDomain.Target -> copy(targetRevision = current + 1L)
            CommittedRevisionDomain.Output -> copy(outputRevision = current + 1L)
        }
        return CommittedRevisionAdvance.Advanced(revisions = advanced)
    }
}
