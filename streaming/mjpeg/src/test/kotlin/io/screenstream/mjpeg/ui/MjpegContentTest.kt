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
    fun collapsedStatisticsHeaderLabelsTheConnectionCountAndStillExpands() {
        val controller = RecordingUiController()
        val resources = compose.activity.resources
        val header = hasText(resources.getString(R.string.mjpeg_stats_title)) and hasClickAction()
        val connectionCount = resources.getString(R.string.mjpeg_stats_connected_clients_count, "0")

        setStatisticsContent(controller)
        compose.onNode(
            header and hasText(connectionCount, substring = true) and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, resources.getString(R.string.mjpeg_collapsed)),
        ).performClick()
        compose.onNode(
            header and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, resources.getString(R.string.mjpeg_expanded)),
        ).assertExists()
    }

    @Test
    fun expandedStatisticsDistinguishesUnrecordedTrafficFromMeasuredZero() {
        val controller = RecordingUiController()
        val statistics = UiController.Statistics(
            currentFps = 18.0,
            history = listOf(
                UiController.HistoryPoint(atElapsedRealtimeMillis = 1000, fps = 12.0, outgoingBytesPerSecond = null),
                UiController.HistoryPoint(atElapsedRealtimeMillis = 2000, fps = 18.0, outgoingBytesPerSecond = null),
            ),
        )
        controller.state.value = controller.state.value.copy(statistics = statistics)
        val resources = compose.activity.resources
        val unavailableTraffic = resources.getString(
            R.string.mjpeg_stats_chart_title,
            resources.getString(R.string.mjpeg_stats_outgoing_traffic),
            resources.getString(R.string.mjpeg_stats_unavailable),
        )
        val trafficWithRecordedZero = resources.getString(
            R.string.mjpeg_stats_chart_recorded_range,
            unavailableTraffic,
            "0",
            resources.getString(R.string.mjpeg_stats_mbps_value, "0"),
        )
        val currentFps = resources.getString(R.string.mjpeg_stats_fps_value, "18")
        val frameRateWithRange = resources.getString(
            R.string.mjpeg_stats_chart_recorded_range,
            resources.getString(R.string.mjpeg_stats_chart_title, resources.getString(R.string.mjpeg_stats_frame_rate), currentFps),
            "12",
            currentFps,
        )

        setStatisticsContent(controller)
        compose.onNode(hasText(resources.getString(R.string.mjpeg_stats_title)) and hasClickAction()).performClick()
        compose.onNodeWithText(unavailableTraffic).assertExists()
        compose.onNodeWithText(frameRateWithRange).assertExists()

        compose.runOnIdle {
            val history = statistics.history + listOf(
                UiController.HistoryPoint(atElapsedRealtimeMillis = 3000, fps = 18.0, outgoingBytesPerSecond = 0.0),
                UiController.HistoryPoint(atElapsedRealtimeMillis = 4000, fps = 18.0, outgoingBytesPerSecond = null),
            )
            controller.state.value = controller.state.value.copy(statistics = statistics.copy(history = history))
        }
        compose.onNodeWithText(trafficWithRecordedZero).assertExists()
        compose.onNodeWithText(frameRateWithRange).assertExists()
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
            override fun ContentDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
                TestMjpegModalHost.ContentDialog(onDismissRequest) {
                    Box(Modifier.testTag(destinationTag)) { content() }
                }
            }

            @Composable
            override fun Modal(title: String, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
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

    private fun setStatisticsContent(controller: UiController) {
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        compose.setContent {
            MjpegTestTheme {
                MjpegContent(controller, settings) { ui ->
                    ui.sections.single { it.id == "statistics" }.content()
                }
            }
        }
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
