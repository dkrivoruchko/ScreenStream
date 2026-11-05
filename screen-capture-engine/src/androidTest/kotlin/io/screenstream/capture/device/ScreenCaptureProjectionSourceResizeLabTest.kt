package io.screenstream.capture.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An explicitly selected emulator lab, rather than an additional generic device qualification. */
@RunWith(AndroidJUnit4::class)
internal class ScreenCaptureProjectionSourceResizeLabTest {
    // Verification: IMG-02
    @Test
    fun sourceAspectShrinkRoundTripPreservesMapping() {
        val selected = InstrumentationRegistry.getArguments().getString("engine.sourceResizeLab")
        assumeTrue("Emulator source-resize lab is not selected; supply engine.sourceResizeLab=true", selected != null)
        check(selected == "true") { "Invalid explicit source-resize lab selection: $selected" }
        ProjectionCaptureFixture.history(
            "source-aspect-shrink-lab",
            listOf("lab-eligibility", "initial", "shrunk-platform", "shrunk", "restored-platform", "restored"),
        ) {
            sourceAspectResizeLab()
        }
    }
}
