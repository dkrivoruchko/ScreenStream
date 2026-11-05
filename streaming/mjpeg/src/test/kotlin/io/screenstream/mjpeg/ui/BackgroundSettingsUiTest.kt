package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.webpage.BackgroundSearch
import io.screenstream.mjpeg.ui.settings.webpage.WebPageSettingsGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BackgroundSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    @Test
    fun hexSliderAndPaletteStaySynchronizedAndSaveTheCanonicalColor() {
        val settings = newSettings()
        val initial = settled(settings)
        render(settings, initial)
        openEditor()
        hex().performTextReplacement("1122aa")
        assertPreview("#1122AA")
        assertChannels(17, 34, 170)

        channel(R.string.mjpeg_pref_web_background_red).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(187f))
        }
        hex().assertTextContains("BB22AA")
        assertPreview("#BB22AA")
        assertChannels(187, 34, 170)

        val palette = compose.onNodeWithContentDescription(text(R.string.mjpeg_pref_web_background_color_option, "#03A9F4"))
        palette.performScrollTo().performClick().assertIsSelected()
        hex().assertTextContains("03A9F4")
        assertPreview("#03A9F4")
        assertChannels(3, 169, 244)
        closeEditor()

        assertEquals(initial.copy(web = initial.web.copy(background = "#03A9F4")), settled(settings))
    }

    @Test
    fun invalidHexKeepsTheLastValidPreviewAndDoesNotCommitOnDismissal() {
        val settings = newSettings()
        val initial = settled(settings)
        render(settings, initial)
        openEditor()
        hex().performTextReplacement("aabbcc")
        assertPreview("#AABBCC")
        assertChannels(170, 187, 204)
        hex().performTextReplacement("xyz")
        compose.onNodeWithText(text(R.string.mjpeg_pref_web_background_hex_error)).assertIsDisplayed()
        assertPreview("#AABBCC")
        assertChannels(170, 187, 204)
        closeEditor()

        assertEquals(initial, settled(settings))
    }

    private fun render(settings: MjpegSettings, initial: MjpegSettings.Data) {
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                WebPageSettingsGroup(
                    webPageSettings = data.web,
                    mjpegSettings = settings,
                    onSaveError = { error(it) },
                    visibleSettings = setOf(BackgroundSearch.info),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
    }

    private fun assertPreview(color: String) {
        compose.onNodeWithContentDescription(text(R.string.mjpeg_pref_web_background_color_preview, color)).assertExists()
    }

    private fun assertChannels(red: Int, green: Int, blue: Int) {
        for ((resource, expected) in listOf(
            R.string.mjpeg_pref_web_background_red to red,
            R.string.mjpeg_pref_web_background_green to green,
            R.string.mjpeg_pref_web_background_blue to blue,
        )) {
            channel(resource).assert(SemanticsMatcher("Channel progress is $expected") { node ->
                node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == expected.toFloat()
            })
        }
    }

    private fun newSettings(): MjpegSettings =
        MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))

    private fun settled(settings: MjpegSettings): MjpegSettings.Data = compose.settledMjpegSettings(settings)

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_web_background)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun hex() = compose.onNodeWithText(text(R.string.mjpeg_pref_web_background_hex_label))
    private fun channel(resource: Int) = compose.onNodeWithContentDescription(text(resource))
    private fun text(resource: Int, vararg arguments: Any): String = compose.activity.getString(resource, *arguments)
}
