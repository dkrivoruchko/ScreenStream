package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan

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
