package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.elvishew.xlog.XLog
import io.screenstream.capture.FrameRate
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.images.FrameRateSearch
import io.screenstream.mjpeg.ui.settings.images.ImageSettingsGroup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class FrameRateSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var originalLocale: Locale

    @Before
    fun initializeLoggerAndLocale() {
        XLog.init(Int.MAX_VALUE)
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun localizedIntervalDraftSurvivesModeSwitchesAndSavesTheSelectedRate() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()

        selectMode(R.string.mjpeg_pref_image_rate_fps)
        field(R.string.mjpeg_pref_image_rate_fps_label).performTextReplacement("17")
        selectMode(R.string.mjpeg_pref_image_rate_interval)
        field(R.string.mjpeg_pref_image_rate_interval_label).performTextReplacement("1,25")
        field(R.string.mjpeg_pref_image_rate_interval_label).performTextReplacement("3600,0000000000000001")
        field(R.string.mjpeg_pref_image_rate_interval_label).assertTextContains("1,25")
        selectMode(R.string.mjpeg_pref_image_rate_fps)
        field(R.string.mjpeg_pref_image_rate_fps_label).assertTextContains("17")
        selectMode(R.string.mjpeg_pref_image_rate_interval)
        field(R.string.mjpeg_pref_image_rate_interval_label).assertTextContains("1,25")
        closeEditor()

        val expected = initial.copy(image = initial.image.copy(frameRate = FrameRate.SamplingInterval(1250.milliseconds)))
        assertEquals(expected, compose.awaitSavedMjpegSettings(settings))
        openEditor()
        field(R.string.mjpeg_pref_image_rate_interval_label).assertTextContains("1,25")
        closeEditor()
    }

    @Test
    fun emptyActiveIntervalKeepsSavedRateButInvalidDormantDraftAllowsAuto() {
        val settings = newSettings(FrameRate.MaxFps(17))
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()

        selectMode(R.string.mjpeg_pref_image_rate_interval)
        field(R.string.mjpeg_pref_image_rate_interval_label).performTextReplacement("")
        field(R.string.mjpeg_pref_image_rate_interval_label).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, text(R.string.mjpeg_pref_image_rate_interval_error)),
        )
        closeEditor()
        assertEquals(initial, compose.awaitSavedMjpegSettings(settings))

        openEditor()
        field(R.string.mjpeg_pref_image_rate_fps_label).assertTextContains("17")
        selectMode(R.string.mjpeg_pref_image_rate_interval)
        field(R.string.mjpeg_pref_image_rate_interval_label).performTextReplacement("")
        val automatic = compose.activity.getString(R.string.mjpeg_pref_default_option, text(R.string.mjpeg_pref_value_automatic))
        compose.onNodeWithText(automatic).performScrollTo().performClick()
        closeEditor()

        val expected = initial.copy(image = initial.image.copy(frameRate = FrameRate.Auto))
        assertEquals(expected, compose.awaitSavedMjpegSettings(settings))
    }

    private fun newSettings(rate: FrameRate = FrameRate.Auto): MjpegSettings {
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData { copy(image = image.copy(frameRate = rate)) }
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
                    visibleSettings = setOf(FrameRateSearch.info),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
    }

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_image_rate)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun selectMode(resource: Int) = compose.onNodeWithText(text(resource)).performScrollTo().performClick()
    private fun field(resource: Int) = compose.onNodeWithText(text(resource))
    private fun text(resource: Int): String = compose.activity.getString(resource)
}
