package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.main.StreamPinContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class StreamPinUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pinStartsPrivateResetsRevealAndCopiesExactSensitivePayload() {
        val pin = mutableStateOf(SecretValue("123456"))
        val hideOnCapture = mutableStateOf(false)
        val clipboard = RecordingClipboard()
        val hidden = compose.activity.getString(R.string.mjpeg_stream_pin_hidden)
        val reveal = compose.activity.getString(R.string.mjpeg_pin_show_description)
        val hide = compose.activity.getString(R.string.mjpeg_pin_hide_description)
        val copy = compose.activity.getString(R.string.mjpeg_stream_pin_copy)
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    StreamPinContent(pin.value, hideOnCapture.value, onShowMessage = {})
                }
            }
        }

        compose.onNodeWithContentDescription(hidden).assertIsDisplayed()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertDoesNotExist()
        compose.onNodeWithText("123456").assertDoesNotExist()
        compose.onNodeWithContentDescription(reveal).performClick()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertIsDisplayed()
        compose.onNodeWithContentDescription(hide).assertIsDisplayed()

        compose.runOnIdle { hideOnCapture.value = true }
        compose.onNodeWithContentDescription(hidden).assertIsDisplayed()
        compose.onNodeWithContentDescription(reveal).performClick()
        compose.runOnIdle { pin.value = SecretValue("654321") }
        compose.onNodeWithContentDescription(hidden).assertIsDisplayed()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("6 5 4 3 2 1").assertDoesNotExist()

        compose.onNodeWithText(copy).performClick()
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.entry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals("654321", clip.getItemAt(0).text.toString())
            assertEquals(copy, clip.description.label.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }

    private class RecordingClipboard : Clipboard {
        var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
    }
}
