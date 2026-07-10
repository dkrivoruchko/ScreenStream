@file:Suppress("unused") // Dormant until the v41 clean-spine cutover.

package dev.dmkr.screencaptureengine.internal.planning

internal data class MemoryFootprint(
    val managedHeapBytes: Long,
    val totalEstimatedBytes: Long,
) {
    init {
        require(managedHeapBytes >= 0L)
        require(totalEstimatedBytes >= managedHeapBytes)
    }
}

internal data class ReplacementMemoryEstimate(
    val retainedPrevious: MemoryFootprint,
    val candidate: MemoryFootprint,
    val peak: MemoryFootprint,
)

internal data class MemoryRuntimeEvidence(
    val maxJavaHeapBytes: Long,
    val usedJavaHeapBytes: Long,
    val systemAvailableBytes: Long,
    val systemThresholdBytes: Long,
    val lowMemory: Boolean,
    val isCurrent: Boolean,
)

internal data class MemoryHeadroom(
    val javaBytes: Long,
    val systemBytes: Long,
    val lowMemory: Boolean,
)

internal sealed interface MemoryFootprintFact {
    data class Value(val footprint: MemoryFootprint) : MemoryFootprintFact
    data object InvalidInput : MemoryFootprintFact
    data object Overflow : MemoryFootprintFact
}

internal sealed interface ReplacementMemoryFact {
    data class Value(val estimate: ReplacementMemoryEstimate) : ReplacementMemoryFact
    data object InvalidInput : ReplacementMemoryFact
    data object Overflow : ReplacementMemoryFact
}

internal sealed interface MemoryHeadroomFact {
    data class Value(val headroom: MemoryHeadroom) : MemoryHeadroomFact
    data object InvalidEvidence : MemoryHeadroomFact
}

internal sealed interface MemoryAdmissionFact {
    data class Admitted(
        val allocation: MemoryFootprint,
        val headroom: MemoryHeadroom,
    ) : MemoryAdmissionFact

    data class Denied(
        val reason: MemoryAdmissionDenial,
        val allocation: MemoryFootprint,
        val headroom: MemoryHeadroom,
    ) : MemoryAdmissionFact

    data object InvalidEvidence : MemoryAdmissionFact
}

internal enum class MemoryAdmissionDenial {
    LowMemory,
    JavaHeadroom,
    SystemHeadroom,
}

internal object MemoryPlanning {
    internal fun footprint(managedHeapBytes: Long, totalEstimatedBytes: Long): MemoryFootprintFact = when {
        managedHeapBytes !in 0L..totalEstimatedBytes -> MemoryFootprintFact.InvalidInput
        else -> MemoryFootprintFact.Value(MemoryFootprint(managedHeapBytes, totalEstimatedBytes))
    }

    internal fun coexistence(footprints: Iterable<MemoryFootprint>): MemoryFootprintFact {
        val managed = CheckedArithmetic.sumNonNegative(footprints.map(MemoryFootprint::managedHeapBytes))
        val total = CheckedArithmetic.sumNonNegative(footprints.map(MemoryFootprint::totalEstimatedBytes))
        return combine(managed, total)
    }

    internal fun replacementPeak(
        previous: MemoryFootprint,
        candidate: MemoryFootprint,
        provenReleasedBeforeAllocation: MemoryFootprint,
    ): ReplacementMemoryFact {
        val retainedManaged = CheckedArithmetic.subtractNonNegative(
            previous.managedHeapBytes,
            provenReleasedBeforeAllocation.managedHeapBytes,
        )
        val retainedTotal = CheckedArithmetic.subtractNonNegative(
            previous.totalEstimatedBytes,
            provenReleasedBeforeAllocation.totalEstimatedBytes,
        )
        val retained = combine(retainedManaged, retainedTotal)
        if (retained !is MemoryFootprintFact.Value) {
            return when (retained) {
                MemoryFootprintFact.InvalidInput -> ReplacementMemoryFact.InvalidInput
                MemoryFootprintFact.Overflow -> ReplacementMemoryFact.Overflow
                is MemoryFootprintFact.Value -> error("unreachable")
            }
        }
        return when (val peak = coexistence(listOf(retained.footprint, candidate))) {
            is MemoryFootprintFact.Value -> ReplacementMemoryFact.Value(
                ReplacementMemoryEstimate(retained.footprint, candidate, peak.footprint),
            )

            MemoryFootprintFact.InvalidInput -> ReplacementMemoryFact.InvalidInput
            MemoryFootprintFact.Overflow -> ReplacementMemoryFact.Overflow
        }
    }

    internal fun headroom(evidence: MemoryRuntimeEvidence): MemoryHeadroomFact {
        if (
            !evidence.isCurrent || evidence.maxJavaHeapBytes < 0L || evidence.usedJavaHeapBytes < 0L ||
            evidence.systemAvailableBytes < 0L || evidence.systemThresholdBytes < 0L
        ) {
            return MemoryHeadroomFact.InvalidEvidence
        }
        val java = CheckedArithmetic.nonNegativeDifference(evidence.maxJavaHeapBytes, evidence.usedJavaHeapBytes)
        val system = CheckedArithmetic.nonNegativeDifference(evidence.systemAvailableBytes, evidence.systemThresholdBytes)
        if (java !is CheckedLongFact.Value || system !is CheckedLongFact.Value) {
            return MemoryHeadroomFact.InvalidEvidence
        }
        return MemoryHeadroomFact.Value(MemoryHeadroom(java.value, system.value, evidence.lowMemory))
    }

    internal fun admit(
        allocation: MemoryFootprint,
        evidence: MemoryRuntimeEvidence,
    ): MemoryAdmissionFact {
        val headroom = (headroom(evidence) as? MemoryHeadroomFact.Value)?.headroom
            ?: return MemoryAdmissionFact.InvalidEvidence
        val reason = when {
            headroom.lowMemory -> MemoryAdmissionDenial.LowMemory
            allocation.managedHeapBytes > headroom.javaBytes -> MemoryAdmissionDenial.JavaHeadroom
            allocation.totalEstimatedBytes > headroom.systemBytes -> MemoryAdmissionDenial.SystemHeadroom
            else -> return MemoryAdmissionFact.Admitted(allocation, headroom)
        }
        return MemoryAdmissionFact.Denied(reason, allocation, headroom)
    }

    private fun combine(managed: CheckedLongFact, total: CheckedLongFact): MemoryFootprintFact = when {
        managed === CheckedLongFact.InvalidInput || total === CheckedLongFact.InvalidInput -> MemoryFootprintFact.InvalidInput
        managed === CheckedLongFact.Overflow || total === CheckedLongFact.Overflow -> MemoryFootprintFact.Overflow
        managed is CheckedLongFact.Value && total is CheckedLongFact.Value && total.value >= managed.value ->
            MemoryFootprintFact.Value(MemoryFootprint(managed.value, total.value))

        else -> MemoryFootprintFact.InvalidInput
    }
}
