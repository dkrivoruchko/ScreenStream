package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.MjpegKoinTestRule
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
    @get:Rule(order = Int.MIN_VALUE)
    val koin = MjpegKoinTestRule()

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
            MjpegTestTheme { MjpegContent(controller, settings, layout = ::MjpegTestLayout) }
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

    @Test
    fun portDraftSurvivesMovingTheSettingsSectionBetweenPanes() {
        val controller = RecordingUiController()
        controller.state.value = controller.state.value.copy(editPolicy = MjpegSettings.EditPolicy.All)
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        val originalPort = compose.awaitSavedMjpegSettings(settings).network.httpPort
        val secondPane = mutableStateOf(false)
        val destinationTag = "Destination pane modal"
        val destinationHost = object : ModalHost {
            @Composable
            override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
                TestMjpegModalHost.Modal(title, onDismissRequest) {
                    Box(Modifier.testTag(destinationTag)) { content() }
                }
            }
        }

        compose.setContent {
            MjpegTestTheme {
                MjpegContent(controller, settings) { ui ->
                    val section = ui.sections.firstOrNull { it.id == "settings-connection" } ?: return@MjpegContent
                    val movable = remember { movableContentOf<@Composable () -> Unit> { it() } }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopCenter)) { if (!secondPane.value) movable(section.content) }
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            ModalHost.Provide(destinationHost) {
                                if (secondPane.value) movable(section.content)
                            }
                        }
                    }
                }
            }
        }

        val field = openPortEditor()
        field.performTextReplacement("8181")
        compose.onNodeWithTag(destinationTag).assertDoesNotExist()

        compose.runOnIdle { secondPane.value = true }
        compose.onNodeWithTag(destinationTag).assertExists()
        field.assertTextContains("8181")
        assertEquals(originalPort, compose.awaitSavedMjpegSettings(settings).network.httpPort)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mjpeg_action_close)).performClick()
        assertEquals(8181, compose.awaitSavedMjpegSettings(settings).network.httpPort)
    }

    @Test
    fun removingTheSettingsSectionDoesNotCommitThePortDraft() {
        val controller = RecordingUiController()
        controller.state.value = controller.state.value.copy(editPolicy = MjpegSettings.EditPolicy.All)
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        val visible = mutableStateOf(true)
        compose.setContent {
            MjpegTestTheme {
                MjpegContent(controller, settings) { ui ->
                    if (visible.value) {
                        ui.sections.firstOrNull { it.id == "settings-connection" }?.content?.invoke()
                    }
                }
            }
        }

        val field = openPortEditor()
        val original = compose.awaitSavedMjpegSettings(settings).network.httpPort
        field.performTextReplacement("8181")
        compose.runOnIdle { visible.value = false }
        field.assertDoesNotExist()
        assertEquals(original, compose.awaitSavedMjpegSettings(settings).network.httpPort)
    }

    private fun openPortEditor(): SemanticsNodeInteraction {
        val resources = compose.activity.resources
        val group = resources.getString(R.string.mjpeg_pref_connection_group)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(group)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(group).performClick()
        compose.onNodeWithText(resources.getString(R.string.mjpeg_pref_connection_http_port)).performClick()
        return compose.onNodeWithText(resources.getString(R.string.mjpeg_pref_connection_http_port_label))
    }
}
