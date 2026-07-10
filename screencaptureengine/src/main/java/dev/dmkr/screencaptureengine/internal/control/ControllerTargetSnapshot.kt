@file:Suppress("unused") // Dormant until controller-store integration.

package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.planning.PositiveRatio
import dev.dmkr.screencaptureengine.internal.planning.SamplingDemand

/** Pure proof retained for the projection target that is physically current. */
internal data class ControllerTargetSnapshot(
    val identity: TargetIdentity,
    val geometry: GeometrySnapshot,
    val targetSize: Size,
    val samplingCapacity: SamplingDemand,
    val assignment: TargetAssignmentEvidence,
    val health: TargetHealthEvidence,
    val wholeGeometryMappingValidated: Boolean,
) {
    init {
        require(targetSize.width <= geometry.width && targetSize.height <= geometry.height)
        require(
            samplingCapacity.horizontal.isAtMost(PositiveRatio(targetSize.width, geometry.width)) &&
                    samplingCapacity.vertical.isAtMost(PositiveRatio(targetSize.height, geometry.height)),
        )
    }

    internal fun retentionFor(
        geometry: GeometrySnapshot,
        demand: SamplingDemand,
        requiresFreshIdentity: Boolean,
    ): TargetRetention {
        val replacementReason = when {
            assignment == TargetAssignmentEvidence.Uncertain -> TargetReplacementReason.AssignmentUncertain
            assignment != TargetAssignmentEvidence.Acknowledged -> TargetReplacementReason.AssignmentNotAcknowledged
            health == TargetHealthEvidence.Poisoned -> TargetReplacementReason.TargetPoisoned
            this.geometry != geometry -> TargetReplacementReason.GeometryOrDensityChanged
            !wholeGeometryMappingValidated -> TargetReplacementReason.WholeGeometryMappingUnproven
            requiresFreshIdentity -> TargetReplacementReason.FreshIdentityRequired
            !demand.horizontal.cappedAtOne().isAtMost(samplingCapacity.horizontal) ->
                TargetReplacementReason.HorizontalDemandExceeded

            !demand.vertical.cappedAtOne().isAtMost(samplingCapacity.vertical) ->
                TargetReplacementReason.VerticalDemandExceeded

            else -> null
        }
        return replacementReason?.let(TargetRetention::Replace) ?: TargetRetention.Retain
    }
}

internal enum class TargetAssignmentEvidence {
    NotAcknowledged,
    Acknowledged,
    Uncertain,
}

internal enum class TargetHealthEvidence {
    Unproven,
    Validated,
    Poisoned,
}

internal sealed interface TargetRetention {
    data object Retain : TargetRetention
    data class Replace(val reason: TargetReplacementReason) : TargetRetention
}

internal enum class TargetReplacementReason {
    AssignmentNotAcknowledged,
    AssignmentUncertain,
    TargetPoisoned,
    GeometryOrDensityChanged,
    WholeGeometryMappingUnproven,
    FreshIdentityRequired,
    HorizontalDemandExceeded,
    VerticalDemandExceeded,
}

private fun PositiveRatio.cappedAtOne(): PositiveRatio = if (isAtMost(ONE)) this else ONE

private val ONE = PositiveRatio(1, 1)
