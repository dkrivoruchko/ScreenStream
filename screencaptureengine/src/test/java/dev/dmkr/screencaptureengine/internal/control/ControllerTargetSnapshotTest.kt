package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanFact
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanner
import dev.dmkr.screencaptureengine.internal.planning.PositiveRatio
import dev.dmkr.screencaptureengine.internal.planning.SamplingDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ControllerTargetSnapshotTest {
    @Test
    fun exactRationalBoundaryRetainsEqualAndBelowButReplansOneAbove() {
        val target = target(logicalWidth = 100, logicalHeight = 80, targetWidth = 50, targetHeight = 40)

        assertSame(TargetRetention.Retain, target.retentionFor(target.geometry, demand(1, 2, 1, 2), false))
        assertSame(TargetRetention.Retain, target.retentionFor(target.geometry, demand(49, 100, 39, 80), false))
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.HorizontalDemandExceeded),
            target.retentionFor(target.geometry, demand(51, 100, 1, 2), false),
        )
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.VerticalDemandExceeded),
            target.retentionFor(target.geometry, demand(1, 2, 41, 80), false),
        )
    }

    @Test
    fun plannerRotationAndCropDemandAreComparedOnTheCorrectPhysicalAxes() {
        val plan = BaselineOutputPlanner.planTargetSize(
            logicalCaptureSize = Size(100, 80),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx(left = 10, top = 5, right = 10, bottom = 5),
            rotation = Rotation.Degrees90,
            targetWidth = 35,
            targetHeight = 40,
            contentMode = ContentMode.Stretch,
        ) as BaselineOutputPlanFact.Planned
        assertEquals(PositiveRatio(40, 80), plan.plan.samplingDemand.horizontal)
        assertEquals(PositiveRatio(35, 70), plan.plan.samplingDemand.vertical)

        val exact = target(100, 80, 50, 40)
        assertSame(TargetRetention.Retain, exact.retentionFor(exact.geometry, plan.plan.samplingDemand, false))

        val shortVertical = target(100, 80, 50, 39)
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.VerticalDemandExceeded),
            shortVertical.retentionFor(shortVertical.geometry, plan.plan.samplingDemand, false),
        )
    }

    @Test
    fun fullSizeTargetIsNeverReplacedMerelyToShrink() {
        val full = target(101, 77, 101, 77)
        assertSame(TargetRetention.Retain, full.retentionFor(full.geometry, demand(1, 10, 1, 10), false))
        assertSame(TargetRetention.Retain, full.retentionFor(full.geometry, demand(1, 1, 1, 1), false))
        assertSame(TargetRetention.Retain, full.retentionFor(full.geometry, demand(2, 1, 3, 1), false))

        val half = target(100, 80, 50, 40)
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.HorizontalDemandExceeded),
            half.retentionFor(half.geometry, demand(2, 1, 3, 1), false),
        )
    }

    @Test
    fun validatedContentRectangleCapacityCanBeLowerThanSurfaceDimensions() {
        val surface = target(100, 80, 100, 80).copy(
            samplingCapacity = SamplingDemand(PositiveRatio(1, 2), PositiveRatio(3, 4)),
        )

        assertSame(TargetRetention.Retain, surface.retentionFor(surface.geometry, demand(1, 2, 3, 4), false))
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.HorizontalDemandExceeded),
            surface.retentionFor(surface.geometry, demand(51, 100, 3, 4), false),
        )
    }

    @Test
    fun unprovenHealthIsRetainableButAssignmentUncertaintyAndPoisonAreNot() {
        val unproven = target(100, 80, 100, 80, health = TargetHealthEvidence.Unproven)
        assertSame(TargetRetention.Retain, unproven.retentionFor(unproven.geometry, demand(1, 1, 1, 1), false))

        val uncertain = target(
            100,
            80,
            100,
            80,
            assignment = TargetAssignmentEvidence.Uncertain,
        )
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.AssignmentUncertain),
            uncertain.retentionFor(uncertain.geometry, demand(1, 1, 1, 1), false),
        )

        val unacknowledged = target(
            100,
            80,
            100,
            80,
            assignment = TargetAssignmentEvidence.NotAcknowledged,
        )
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.AssignmentNotAcknowledged),
            unacknowledged.retentionFor(unacknowledged.geometry, demand(1, 1, 1, 1), false),
        )

        val poisoned = target(100, 80, 100, 80, health = TargetHealthEvidence.Poisoned)
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.TargetPoisoned),
            poisoned.retentionFor(poisoned.geometry, demand(1, 1, 1, 1), false),
        )
    }

    @Test
    fun geometryDensityMappingAndFreshnessEachRequireReplacement() {
        val current = target(100, 80, 100, 80)
        val demand = demand(1, 1, 1, 1)

        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.GeometryOrDensityChanged),
            current.retentionFor(current.geometry.copy(densityDpi = 321), demand, false),
        )
        val mappingUnknown = current.copy(wholeGeometryMappingValidated = false)
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.WholeGeometryMappingUnproven),
            mappingUnknown.retentionFor(mappingUnknown.geometry, demand, false),
        )
        assertEquals(
            TargetRetention.Replace(TargetReplacementReason.FreshIdentityRequired),
            current.retentionFor(current.geometry, demand, true),
        )
    }

    private fun target(
        logicalWidth: Int,
        logicalHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        assignment: TargetAssignmentEvidence = TargetAssignmentEvidence.Acknowledged,
        health: TargetHealthEvidence = TargetHealthEvidence.Validated,
    ): ControllerTargetSnapshot = ControllerTargetSnapshot(
        identity = TargetIdentity(1),
        geometry = GeometrySnapshot(logicalWidth, logicalHeight, 320),
        targetSize = Size(targetWidth, targetHeight),
        samplingCapacity = SamplingDemand(
            horizontal = PositiveRatio(targetWidth, logicalWidth),
            vertical = PositiveRatio(targetHeight, logicalHeight),
        ),
        assignment = assignment,
        health = health,
        wholeGeometryMappingValidated = true,
    )

    private fun demand(hn: Int, hd: Int, vn: Int, vd: Int): SamplingDemand = SamplingDemand(
        horizontal = PositiveRatio(hn, hd),
        vertical = PositiveRatio(vn, vd),
    )
}
