package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.webpage.StreamNamePlacementSearch
import io.screenstream.mjpeg.ui.settings.webpage.WebPageSettingsGroup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
internal class StreamNamePlacementSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dismissalMergesOnlyChangedDraftFieldsIntoTheLatestSavedLayouts() {
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData {
                    copy(
                        web = web.copy(
                            titleLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Top, WebPageSettings.Presentation.Overlay, true),
                            controlsLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Bottom, WebPageSettings.Presentation.Overlay, true),
                        ),
                    )
                }
            }
        }
        val initial = compose.awaitSavedMjpegSettings(settings)
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                WebPageSettingsGroup(
                    webPageSettings = data.web,
                    mjpegSettings = settings,
                    onSaveError = { error(it) },
                    visibleSettings = setOf(StreamNamePlacementSearch.info),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.mjpeg_pref_web_stream_name_placement)).performClick()
        compose.onNodeWithText(text(R.string.mjpeg_pref_web_position_bottom)).performClick()

        runBlocking {
            withTimeout(5.seconds) {
                settings.updateData {
                    copy(
                        web = web.copy(
                            titleLayout = web.titleLayout.copy(presentation = WebPageSettings.Presentation.Bar, autoHide = false),
                            controlsLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Top, WebPageSettings.Presentation.Bar, false),
                        ),
                    )
                }
            }
        }
        val latest = compose.awaitSavedMjpegSettings(settings)
        compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()

        assertEquals(
            latest.copy(web = latest.web.copy(titleLayout = latest.web.titleLayout.copy(position = WebPageSettings.Edge.Bottom))),
            compose.awaitSavedMjpegSettings(settings),
        )
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)
}
