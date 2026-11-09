package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.images.ImageSettingsGroup
import io.screenstream.mjpeg.ui.settings.images.SizeSearch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SizeSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun modeSwitchesKeepIndependentDraftsAndCommitOnlyTheSelectedModeIntoLatestSettings() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()

        selectMode(R.string.mjpeg_pref_image_size_target)
        field(R.string.mjpeg_pref_image_size_width_label).performTextReplacement("640")
        field(R.string.mjpeg_pref_image_size_height_label).performTextReplacement("360")
        compose.onNodeWithText(text(R.string.mjpeg_pref_image_size_stretch)).performScrollTo().performClick()
        selectMode(R.string.mjpeg_pref_image_size_scale)
        field(R.string.mjpeg_pref_image_size_scale_label).performTextReplacement("62.5")
        selectMode(R.string.mjpeg_pref_image_size_target)
        field(R.string.mjpeg_pref_image_size_width_label).assertTextContains("640")
        field(R.string.mjpeg_pref_image_size_height_label).assertTextContains("360")
        compose.onNodeWithText(text(R.string.mjpeg_pref_image_size_stretch)).assertIsOff()

        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(image = image.copy(jpegQuality = 43, rotation = Rotation.Degrees90)) }
            }
        }
        selectMode(R.string.mjpeg_pref_image_size_scale)
        field(R.string.mjpeg_pref_image_size_scale_label).assertTextContains("62.5")
        closeEditor()

        val expected = initial.copy(image = initial.image.copy(scalePercent = 62.5, jpegQuality = 43, rotation = Rotation.Degrees90))
        assertEquals(expected, compose.awaitSavedMjpegSettings(settings))
    }

    @Test
    fun partialTargetDoesNotSaveAndACompleteTargetKeepsTheDormantScale() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()
        selectMode(R.string.mjpeg_pref_image_size_target)
        field(R.string.mjpeg_pref_image_size_width_label).performTextReplacement("")
        field(R.string.mjpeg_pref_image_size_height_label).performTextReplacement("360")
        field(R.string.mjpeg_pref_image_size_width_label).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, text(R.string.mjpeg_pref_image_size_dimension_error)),
        )
        closeEditor()
        assertEquals(initial, compose.awaitSavedMjpegSettings(settings))

        openEditor()
        field(R.string.mjpeg_pref_image_size_scale_label).assertTextContains("75")
        field(R.string.mjpeg_pref_image_size_scale_label).performTextReplacement("99")
        selectMode(R.string.mjpeg_pref_image_size_target)
        field(R.string.mjpeg_pref_image_size_width_label).assertTextContains("320")
        field(R.string.mjpeg_pref_image_size_height_label).assertTextContains("240")
        field(R.string.mjpeg_pref_image_size_width_label).performTextReplacement("640")
        field(R.string.mjpeg_pref_image_size_height_label).performTextReplacement("360")
        closeEditor()

        val expected = initial.copy(
            image = initial.image.copy(
                outputSelection = ImageSettings.OutputSelection.TargetSize,
                targetSize = OutputSize.TargetSize(640, 360, OutputSize.ContentMode.Stretch),
            ),
        )
        assertEquals(expected, compose.awaitSavedMjpegSettings(settings))
    }

    private fun newSettings(): MjpegSettings {
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData {
                    copy(
                        image = image.copy(
                            outputSelection = ImageSettings.OutputSelection.Scale,
                            scalePercent = 75.0,
                            targetSize = OutputSize.TargetSize(320, 240, OutputSize.ContentMode.Stretch),
                        ),
                    )
                }
            }
        }
        return settings
    }

    private fun render(settings: MjpegSettings, initial: MjpegSettings.Data) {
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                ImageSettingsGroup(
                    imageSettings = data.image,
                    mjpegSettings = settings,
                    editPolicy = MjpegSettings.EditPolicy.All,
                    onSaveError = { error(it) },
                    visibleSettings = setOf(SizeSearch.info),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
    }

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_image_size)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun selectMode(resource: Int) = compose.onNodeWithText(text(resource)).performScrollTo().performClick()
    private fun field(resource: Int) = compose.onNodeWithText(text(resource))
    private fun text(resource: Int): String = compose.activity.getString(resource)
}
