package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.connection.ConnectionSettingsGroup
import io.screenstream.mjpeg.ui.settings.connection.HttpPortSearch
import org.junit.Assert.assertEquals
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
internal class ConnectionSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    @Test
    fun portDismissalSavesOnlyValidValuesWhileSetupRemainsEditable() {
        val settings = MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()))
        val initial = compose.settledMjpegSettings(settings)
        val policy = mutableStateOf(MjpegSettings.EditPolicy.All)
        val failures = mutableListOf<String>()
        val title = compose.activity.getString(R.string.mjpeg_pref_connection_http_port)
        val label = compose.activity.getString(R.string.mjpeg_pref_connection_http_port_label)
        val close = compose.activity.getString(R.string.mjpeg_action_close)
        val invalid = compose.activity.getString(R.string.mjpeg_pref_connection_http_port_error)
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                ConnectionSettingsGroup(
                    networkSettings = data.network,
                    mjpegSettings = settings,
                    editPolicy = policy.value,
                    onSaveError = failures::add,
                    visibleSettings = setOf(HttpPortSearch.info),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }

        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(label).performTextReplacement("80")
        compose.onNodeWithText(invalid).assertIsDisplayed()
        compose.onNodeWithContentDescription(close).performClick()
        assertEquals(initial.network.httpPort, compose.settledMjpegSettings(settings).network.httpPort)

        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(label).performTextReplacement("9090")
        compose.onNodeWithContentDescription(close).performClick()
        assertEquals(9090, compose.settledMjpegSettings(settings).network.httpPort)
        compose.onNodeWithText("9090").assertIsDisplayed()

        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(label).performTextReplacement("9191")
        compose.runOnIdle { policy.value = MjpegSettings.EditPolicy.LiveOnly }
        compose.onNodeWithText(label).assertIsNotEnabled()
        compose.onNodeWithContentDescription(close).performClick()
        assertEquals(9090, compose.settledMjpegSettings(settings).network.httpPort)
        compose.runOnIdle { assertEquals(emptyList<String>(), failures) }
    }
}
