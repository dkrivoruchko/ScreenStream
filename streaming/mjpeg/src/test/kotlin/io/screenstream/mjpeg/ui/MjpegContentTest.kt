package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class MjpegContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun captureButtonReflectsAvailabilityAndSendsTheDisplayedAttempt() {
        val controller = RecordingUiController()
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        val start = compose.activity.getString(R.string.mjpeg_stream_start_capture)
        val stop = compose.activity.getString(R.string.mjpeg_stream_stop_capture)
        compose.setContent {
            MjpegTestTheme { MjpegContent(controller, settings) }
        }

        compose.onNodeWithText(start).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList(), controller.commands) }

        compose.runOnIdle { controller.state.value = controller.state.value.copy(action = UiController.Action.Start(true)) }
        compose.onNodeWithText(start).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf<UiController.Command>(UiController.Command.Start), controller.commands) }

        compose.runOnIdle { controller.state.value = controller.state.value.copy(action = UiController.Action.Busy) }
        val busyIndicator = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onNode(busyIndicator).assertExists()
        compose.onNode(hasClickAction() and (busyIndicator or hasAnyDescendant(busyIndicator))).assertIsNotEnabled()
        compose.onNodeWithText(start).assertDoesNotExist()
        compose.onNodeWithText(stop).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf<UiController.Command>(UiController.Command.Start), controller.commands) }

        val attempt = StreamingModule.CaptureAttemptId(controller.instanceId)
        compose.runOnIdle { controller.state.value = controller.state.value.copy(action = UiController.Action.Stop(attempt)) }
        compose.onNodeWithText(stop).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(UiController.Command.Start, UiController.Command.Stop(attempt)), controller.commands)
        }
    }
}
