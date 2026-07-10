@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.operation

@JvmInline
internal value class WorkIdentity(
    val value: Long,
) {
    init {
        require(value > 0L) { "Work identity must be positive." }
    }
}

@JvmInline
internal value class OperationCancellationMarker(
    val value: Long,
) {
    init {
        require(value > 0L) { "Operation cancellation marker must be positive." }
    }
}

internal sealed interface WorkIdentityAllocation {
    data class Allocated(
        val identity: WorkIdentity,
    ) : WorkIdentityAllocation

    data object Exhausted : WorkIdentityAllocation
}

/** Allocates positive operation identities without wrapping or reserving a public sequence value. */
internal class WorkIdentityAllocator(
    initialNextIdentity: Long = 1L,
) {
    private val lock = Any()
    private var nextIdentity: Long? = initialNextIdentity

    init {
        require(initialNextIdentity > 0L) { "Initial work identity must be positive." }
    }

    internal fun allocate(): WorkIdentityAllocation =
        synchronized(lock) {
            val value = nextIdentity ?: return@synchronized WorkIdentityAllocation.Exhausted
            nextIdentity = if (value == Long.MAX_VALUE) null else value + 1L
            WorkIdentityAllocation.Allocated(WorkIdentity(value))
        }
}
