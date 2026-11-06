package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.connection.HttpPortSearch
import io.screenstream.mjpeg.ui.settings.connection.NetworkFilterSearch
import io.screenstream.mjpeg.ui.settings.images.FrameRateSearch
import io.screenstream.mjpeg.ui.settings.rememberMatchingSettings
import io.screenstream.mjpeg.ui.settings.webpage.StreamNameSearch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SettingsSearchUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun matchingUsesExtraTermsAllTokensAndWhitespaceDashCaseNormalization() {
        val query = mutableStateOf("  pOrT\u20131024\t ")
        var matchingSettings: Set<SettingSearchInfo>? = null
        compose.setContent {
            MjpegTestTheme {
                val matchingResult = rememberMatchingSettings(query.value)
                SideEffect { matchingSettings = matchingResult }
            }
        }
        compose.runOnIdle {
            assertEquals(setOf(HttpPortSearch.info), matchingSettings)
            query.value = "\tFRAMES\u2014SECOND  "
        }
        compose.runOnIdle {
            assertEquals(setOf(FrameRateSearch.info), matchingSettings)
            query.value = "frames unrelated-missing-term"
        }
        compose.runOnIdle {
            assertEquals(emptySet<SettingSearchInfo>(), matchingSettings)
            query.value = " \t\u2013\u2014 "
        }
        compose.runOnIdle { assertNull(matchingSettings) }
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishViewerTermsMatchAcrossCaseWithoutChangingIpAcronymMatching() {
        val query = mutableStateOf("izleyici")
        var matchingSettings: Set<SettingSearchInfo>? = null
        var viewerMatches: Set<SettingSearchInfo>? = null
        compose.setContent {
            MjpegTestTheme {
                val matchingResult = rememberMatchingSettings(query.value)
                SideEffect { matchingSettings = matchingResult }
            }
        }

        compose.runOnIdle {
            viewerMatches = matchingSettings
            assertTrue(checkNotNull(matchingSettings).containsAll(setOf(NetworkFilterSearch.info, HttpPortSearch.info, StreamNameSearch.info)))
            query.value = "İZLEYİCİ"
        }
        compose.runOnIdle {
            assertEquals(viewerMatches, matchingSettings)
            query.value = "ip sürümü"
        }
        compose.runOnIdle {
            assertEquals(setOf(NetworkFilterSearch.info), matchingSettings)
            query.value = "IP SÜRÜMÜ"
        }
        compose.runOnIdle { assertEquals(setOf(NetworkFilterSearch.info), matchingSettings) }
    }

    @Test
    fun searchRevealsCollapsedSettingsAndClearRestoresExpansionWithoutBlockingCapture() {
        val controller = RecordingUiController()
        controller.state.value = controller.state.value.copy(action = UiController.Action.Start(true), editPolicy = MjpegSettings.EditPolicy.All)
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        runBlocking { withTimeout(5.seconds) { settings.data.first() } }
        compose.setContent {
            MjpegTestTheme { MjpegContent(controller, settings) }
        }
        val imageGroup = text(R.string.mjpeg_pref_image_group)
        val connectionGroup = text(R.string.mjpeg_pref_connection_group)
        val expanded = compose.activity.getString(info.dvkr.screenstream.common.R.string.common_expanded)
        val collapsed = compose.activity.getString(info.dvkr.screenstream.common.R.string.common_collapsed)
        scrollTo(imageGroup)
        compose.onNodeWithText(imageGroup).performClick()
        compose.onNodeWithText(imageGroup).assert(stateDescription(expanded))
        scrollTo(connectionGroup)
        compose.onNodeWithText(connectionGroup).assert(stateDescription(collapsed))

        scrollToSearch()
        searchField().performTextReplacement("  pOrT\u20131024  ")
        assertSettingsGroups(setOf("settings-connection"))
        scrollTo(text(R.string.mjpeg_pref_connection_http_port))
        compose.onNodeWithText(text(R.string.mjpeg_pref_connection_http_port)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.mjpeg_pref_connection_http_port_label)).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
        startCaptureButton().assertIsEnabled().performClick()

        scrollToSearch()
        searchField().performTextReplacement("unrelated-missing-term")
        assertSettingsGroups(emptySet())
        scrollTo(text(R.string.mjpeg_pref_search_no_results))
        compose.onNodeWithText(text(R.string.mjpeg_pref_search_no_results)).assertIsDisplayed()
        startCaptureButton().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(UiController.Command.Start, UiController.Command.Start), controller.commands) }

        scrollToSearch()
        compose.onNodeWithContentDescription(text(R.string.mjpeg_pref_search_clear)).performClick()
        assertSettingsGroups(SETTINGS_GROUP_KEYS.toSet())
        scrollTo(imageGroup)
        compose.onNodeWithText(imageGroup).assert(stateDescription(expanded))
        scrollTo(connectionGroup)
        compose.onNodeWithText(connectionGroup).assert(stateDescription(collapsed))
        for (group in listOf(R.string.mjpeg_pref_automation_group, R.string.mjpeg_pref_web_group, R.string.mjpeg_pref_access_group)) {
            scrollTo(text(group))
            compose.onNodeWithText(text(group)).assertIsDisplayed()
        }
    }

    private fun scrollTo(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
    }

    private fun assertSettingsGroups(expected: Set<String>) {
        // The grid's key lookup covers offscreen items too; missing composed text would not.
        val indexForKey = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        compose.runOnIdle {
            for (key in SETTINGS_GROUP_KEYS) {
                assertEquals("Group membership for $key", key in expected, indexForKey(key) >= 0)
            }
        }
    }

    private fun scrollToSearch() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(text(R.string.mjpeg_pref_search_placeholder)))
    }

    private fun searchField() = compose.onNodeWithContentDescription(text(R.string.mjpeg_pref_search_placeholder))
    private fun startCaptureButton() = compose.onNodeWithText(text(R.string.mjpeg_stream_start_capture))
    private fun text(resource: Int): String = compose.activity.getString(resource)
    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private companion object {
        val SETTINGS_GROUP_KEYS = listOf("settings-image", "settings-automation", "settings-connection", "settings-webpage", "settings-access")
    }
}
