package io.screenstream.capture.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureParameters
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ScreenCaptureProjectionSizingTest {
    // Verification: IMG-02
    @Test
    fun defaultsPreserveWholeDisplayAndFreshContent() = ProjectionCaptureFixture.history(
        "untouched-defaults", listOf("scene-a", "scene-b"),
    ) {
        start()
        checkpoint("scene-a", ScreenCaptureParameters())
        checkpoint("scene-b", ScreenCaptureParameters())
    }

    // Verification: IMG-02
    @Test
    fun explicitHalfSizeStretchPreservesWholeDisplayAndFreshContent() = ProjectionCaptureFixture.history(
        "equal-output-full-input-control", listOf("scene-a", "scene-b"),
    ) {
        val parameters = explicitHalfSizeStretchParameters()
        start(parameters)
        checkpoint("scene-a", parameters)
        checkpoint("scene-b", parameters)
    }

    // Verification: IMG-02
    @Test
    fun liveScaleRoundTripPreservesMapping() = ProjectionCaptureFixture.history(
        "live-full-downscale-full", listOf("full-before", "half", "full-after"),
    ) {
        val full = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        start(full)
        checkpoint("full-before", full)
        updateAndCheckpoint("half", ScreenCaptureParameters())
        updateAndCheckpoint("full-after", full)
    }

    // Verification: IMG-02
    @Test
    fun liveScaleSequencePreservesWholeDisplayAndFreshContent() = ProjectionCaptureFixture.history(
        "shrink-regrowth-growth", listOf("half-before", "quarter", "three-eighths", "three-quarters", "half-after"),
    ) {
        start()
        checkpoint("half-before", ScreenCaptureParameters())
        for ((name, factor) in listOf("quarter" to 0.25, "three-eighths" to 0.375, "three-quarters" to 0.75, "half-after" to 0.5)) {
            updateAndCheckpoint(name, ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(factor)))
        }
        // These compatible histories permit retained larger Targets. They do not observe native allocation sizes.
    }
}
