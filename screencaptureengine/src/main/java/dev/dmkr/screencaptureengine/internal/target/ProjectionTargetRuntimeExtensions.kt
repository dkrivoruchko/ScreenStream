package dev.dmkr.screencaptureengine.internal.target

import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot

internal fun ProjectionTargetHandle.matches(plan: ScreenCaptureOutputPlan): Boolean =
    width == plan.captureTarget.width &&
            height == plan.captureTarget.height &&
            densityDpi == plan.captureGeometry.densityDpi

internal fun ProjectionTargetHandle.snapshot(): ProjectionTargetSnapshot =
    ProjectionTargetSnapshot(
        generation = generation,
        width = width,
        height = height,
        densityDpi = densityDpi,
        surface = surface,
    )
