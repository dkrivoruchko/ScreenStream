package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.preferencesDataStoreFile
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.seedSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SettingsSaveFailureUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedEditorSaveShowsTheScreenSnackbarAndPreservesSavedBytesAndData() {
        val context = IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder())
        runBlocking {
            withTimeout(5.seconds) { seedSettings(context, """{"web":{"title":"Before failure"}}""") }
        }
        val preferencesFile = context.preferencesDataStoreFile("mjpeg")
        val savedBytes = preferencesFile.readBytes()
        // Existing persistence tests exercise this real DataStore temporary-file fault.
        val blockedTemporaryFile = File(preferencesFile.absolutePath + ".tmp")
        check(blockedTemporaryFile.mkdir())
        File(blockedTemporaryFile, "blocker").writeText("Fixture storage fault")
        val settings = MjpegSettings(context)
        val initial = runBlocking { withTimeout(5.seconds) { settings.data.first() } }
        val controller = RecordingUiController()
        controller.state.value = controller.state.value.copy(editPolicy = MjpegSettings.EditPolicy.All)
        compose.setContent {
            MjpegTestTheme { MjpegContent(controller, settings) }
        }

        val searchDescription = text(R.string.mjpeg_pref_search_placeholder)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(searchDescription))
        compose.onNodeWithContentDescription(searchDescription).performTextReplacement(text(R.string.mjpeg_pref_web_stream_name))
        val nameItem = hasText(text(R.string.mjpeg_pref_web_stream_name)) and !hasSetTextAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(nameItem)
        compose.onNode(nameItem).performClick()
        compose.onNodeWithText(text(R.string.mjpeg_pref_web_stream_name_label)).performTextReplacement("Must not be saved")
        compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()

        val saveErrorText = text(R.string.mjpeg_pref_web_save_error)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(saveErrorText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(saveErrorText).assertIsDisplayed()
        val savedSettings = compose.awaitSavedMjpegSettings(settings)
        assertEquals(initial, savedSettings)
        assertArrayEquals(savedBytes, preferencesFile.readBytes())
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)
}
