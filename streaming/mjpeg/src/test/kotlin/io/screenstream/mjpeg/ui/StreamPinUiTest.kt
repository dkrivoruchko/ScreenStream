package io.screenstream.mjpeg.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.main.StreamPinContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val hiddenDescription = compose.activity.getString(R.string.mjpeg_stream_pin_hidden)
        val revealDescription = compose.activity.getString(R.string.mjpeg_pin_show_description)
        val hideDescription = compose.activity.getString(R.string.mjpeg_pin_hide_description)
        val copyLabel = compose.activity.getString(R.string.mjpeg_stream_pin_copy)
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    StreamPinContent(pin.value, hideOnCapture.value, onShowMessage = {})
                }
            }
        }

        compose.onNodeWithContentDescription(hiddenDescription).assertIsDisplayed()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertDoesNotExist()
        compose.onNodeWithText("123456").assertDoesNotExist()
        compose.onNodeWithContentDescription(revealDescription).performClick()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertIsDisplayed()
        compose.onNodeWithContentDescription(hideDescription).assertIsDisplayed()

        compose.runOnIdle { hideOnCapture.value = true }
        compose.onNodeWithContentDescription(hiddenDescription).assertIsDisplayed()
        compose.onNodeWithContentDescription(revealDescription).performClick()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertIsDisplayed()
        compose.runOnIdle { pin.value = SecretValue("654321") }
        compose.onNodeWithContentDescription(hiddenDescription).assertIsDisplayed()
        compose.onNodeWithContentDescription("1 2 3 4 5 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("6 5 4 3 2 1").assertDoesNotExist()

        compose.onNodeWithText(copyLabel).performClick()
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.clipEntry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals("654321", clip.getItemAt(0).text.toString())
            assertEquals(copyLabel, clip.description.label.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }

    @Test
    @Config(sdk = [32, 33])
    fun successfulPinCopyShowsManualFeedbackOnlyBeforeAndroid13() {
        val clipboard = RecordingClipboard()
        val messages = mutableListOf<String>()
        val copyLabel = compose.activity.getString(R.string.mjpeg_stream_pin_copy)
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    StreamPinContent(SecretValue("000042"), false, messages::add)
                }
            }
        }

        compose.onNodeWithText(copyLabel).performClick()
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.clipEntry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals("000042", clip.getItemAt(0).text.toString())
            assertEquals(copyLabel, clip.description.label.toString())
            assertTrue(checkNotNull(clip.description.extras).getBoolean("android.content.extra.IS_SENSITIVE"))
            val expected = if (Build.VERSION.SDK_INT < 33) {
                listOf(compose.activity.getString(R.string.mjpeg_stream_pin_copied))
            } else {
                emptyList()
            }
            assertEquals(expected, messages)
        }
    }
}
