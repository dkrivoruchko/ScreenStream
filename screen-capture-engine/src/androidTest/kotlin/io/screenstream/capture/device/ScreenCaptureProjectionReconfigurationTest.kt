package io.screenstream.capture.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.SourceRegion
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ScreenCaptureProjectionReconfigurationTest {
    // Verification: IMG-02
    @Test
    fun regionAndCropRoundTripsPreserveMapping() = ProjectionCaptureFixture.history(
        "fixed-output-restriction-crop", listOf("full-before", "right-half", "full-middle", "asymmetric-crop", "full-after"),
    ) {
        val full = explicitHalfSizeStretchParameters()
        val cropped = full.copy(crop = CropInsetsPx(display.width / 8, display.height / 10, display.width / 5, display.height / 7))
        start(full)
        checkpoint("full-before", full)
        updateAndCheckpoint("right-half", full.copy(sourceRegion = SourceRegion.RightHalf))
        updateAndCheckpoint("full-middle", full)
        updateAndCheckpoint("asymmetric-crop", cropped)
        updateAndCheckpoint("full-after", full)
    }

    // Verification: IMG-02
    // Verification: SES-03
    @Test
    fun invalidCropSuspendsAndRecoveryProducesFreshContent() = ProjectionCaptureFixture.history(
        "invalid-crop-recovery", listOf("valid-before", "invalid-crop", "recovered"),
    ) {
        val full = explicitHalfSizeStretchParameters()
        start(full)
        checkpoint("valid-before", full)
        invalidCropAndRecover("invalid-crop", full.copy(crop = CropInsetsPx(Int.MAX_VALUE, 0, 0, 0)), full)
    }
}
