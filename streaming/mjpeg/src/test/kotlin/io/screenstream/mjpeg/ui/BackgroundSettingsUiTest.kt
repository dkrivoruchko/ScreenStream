package io.screenstream.mjpeg.ui

import android.app.Application
import android.content.pm.ApplicationInfo
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.MjpegKoinTestRule
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.webpage.BackgroundSearch
import io.screenstream.mjpeg.ui.settings.webpage.WebPageSettingsGroup
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BackgroundSettingsUiTest {
    @get:Rule(order = Int.MIN_VALUE)
    val koin = MjpegKoinTestRule()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun hexSliderAndPaletteStaySynchronizedAndSaveTheCanonicalColor() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()
        hexField().performTextReplacement("1122aa")
        assertPreview("#1122AA")
        assertChannels(17, 34, 170)

        channelSlider(R.string.mjpeg_pref_web_background_red).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(187f))
        }
        hexField().assertTextContains("BB22AA")
        assertPreview("#BB22AA")
        assertChannels(187, 34, 170)

        val palette = compose.onNodeWithContentDescription(text(R.string.mjpeg_pref_web_background_color_option, "#03A9F4"))
        palette.performScrollTo().performClick().assertIsSelected()
        hexField().assertTextContains("03A9F4")
        assertPreview("#03A9F4")
        assertChannels(3, 169, 244)
        closeEditor()

        assertEquals(initial.copy(web = initial.web.copy(background = "#03A9F4")), compose.awaitSavedMjpegSettings(settings))
    }

    @Test
    fun invalidHexKeepsTheLastValidPreviewAndDoesNotCommitOnDismissal() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()
        hexField().performTextReplacement("aabbcc")
        assertPreview("#AABBCC")
        assertChannels(170, 187, 204)
        hexField().performTextReplacement("xyz")
        compose.onNodeWithText(text(R.string.mjpeg_pref_web_background_hex_error)).assertIsDisplayed()
        assertPreview("#AABBCC")
        assertChannels(170, 187, 204)
        closeEditor()

        assertEquals(initial, compose.awaitSavedMjpegSettings(settings))
    }

    @Test
    @Config(application = RtlTestApplication::class, qualifiers = "ar-ldrtl")
    fun channelLabelsAreOnTheRightInRtl() {
        val settings = newSettings()
        val initial = compose.awaitSavedMjpegSettings(settings)
        render(settings, initial)
        openEditor()

        val channelLabels = listOf(
            R.string.mjpeg_pref_web_background_red,
            R.string.mjpeg_pref_web_background_green,
            R.string.mjpeg_pref_web_background_blue,
        )
        for (resource in channelLabels) {
            val slider = channelSlider(resource).performScrollTo()
            val label = compose.onNodeWithText(text(resource))
            val sliderBounds = slider.getUnclippedBoundsInRoot()
            val labelBounds = label.getUnclippedBoundsInRoot()
            assertEquals(LayoutDirection.Rtl, label.fetchSemanticsNode().layoutInfo.layoutDirection, "Editor label layout direction")
            val sliderCenterX = (sliderBounds.left + sliderBounds.right) / 2f
            val labelCenterX = (labelBounds.left + labelBounds.right) / 2f
            assertTrue(
                labelCenterX > sliderCenterX,
                "${text(resource)} label $labelBounds should be to the right of its slider $sliderBounds",
            )
        }
    }

    private fun render(settings: MjpegSettings, initial: MjpegSettings.Data) {
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                WebPageSettingsGroup(
                    webPageSettings = data.web,
                    mjpegSettings = settings,
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
            channelSlider(resource).assert(SemanticsMatcher("Channel progress is $expected") { node ->
                node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == expected.toFloat()
            })
        }
    }

    private fun newSettings(): MjpegSettings =
        MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_web_background)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun hexField() = compose.onNodeWithText(text(R.string.mjpeg_pref_web_background_hex_label))
    private fun channelSlider(resource: Int) = compose.onNodeWithContentDescription(text(resource))
    private fun text(resource: Int, vararg arguments: Any): String = compose.activity.getString(resource, *arguments)
}

internal class RtlTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The library test manifest does not inherit the app's supportsRtl setting.
        applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    }
}
