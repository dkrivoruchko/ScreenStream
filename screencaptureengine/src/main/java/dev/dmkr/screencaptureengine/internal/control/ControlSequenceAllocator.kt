@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.control

internal sealed interface ControlSequenceAllocation {
    data class Value(
        val sequence: Long,
    ) : ControlSequenceAllocation

    class Exhausted private constructor(
        val sequence: Long,
    ) : ControlSequenceAllocation {
        companion object {
            val fact = Exhausted(sequence = Long.MAX_VALUE)
        }
    }

    data object Unavailable : ControlSequenceAllocation
}

/** Allocates one nonwrapping controller sequence domain starting at one. */
internal class ControlSequenceAllocator(
    initialNextSequence: Long = 1L,
) {
    private val lock = Any()
    private var nextSequence = initialNextSequence
    private var exhaustionEmitted = false

    init {
        require(initialNextSequence in 1L..Long.MAX_VALUE) {
            "Initial controller sequence must be in 1..Long.MAX_VALUE."
        }
    }

    internal fun allocate(): ControlSequenceAllocation =
        synchronized(lock) {
            when {
                exhaustionEmitted -> ControlSequenceAllocation.Unavailable
                nextSequence == Long.MAX_VALUE -> {
                    exhaustionEmitted = true
                    ControlSequenceAllocation.Exhausted.fact
                }

                else -> ControlSequenceAllocation.Value(sequence = nextSequence++)
            }
        }
}
