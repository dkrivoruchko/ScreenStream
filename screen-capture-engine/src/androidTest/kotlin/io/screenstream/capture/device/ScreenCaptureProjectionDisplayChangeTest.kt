package io.screenstream.capture.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureParameters
import org.junit.Test
import org.junit.runner.RunWith

/** Actual phone-display changes; parameter-only histories and the opted-in emulator size lab are separate. */
@RunWith(AndroidJUnit4::class)
internal class ScreenCaptureProjectionDisplayChangeTest {
    // Verification: IMG-02
    @Test
    fun rotationAtFullScalePreservesMapping() = ProjectionCaptureFixture.history(
        "actual-rotation-full", listOf("initial", "rotated", "restored"),
    ) {
        val full = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        start(full)
        checkpoint("initial", full)
        rotateDisplayAndCheckpoint("rotated", full)
        restoreDisplayRotationAndCheckpoint("restored", full)
    }

    // Verification: IMG-02
    @Test
    fun rotationAtDefaultScalePreservesMapping() = ProjectionCaptureFixture.history(
        "actual-rotation-default", listOf("initial", "rotated", "restored"),
    ) {
        start()
        checkpoint("initial", ScreenCaptureParameters())
        rotateDisplayAndCheckpoint("rotated", ScreenCaptureParameters())
        restoreDisplayRotationAndCheckpoint("restored", ScreenCaptureParameters())
    }
}
