package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.connection.ConnectionSettingsGroup
import io.screenstream.mjpeg.ui.settings.connection.HttpPortSearch
import io.screenstream.mjpeg.ui.settings.connection.NetworkFilterSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ConnectionSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val failures = mutableListOf<String>()

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    @Test
    fun portDismissalSavesValidValuesAndDiscardsInvalidDrafts() {
        val settings = newSettings()
        val initial = compose.settledMjpegSettings(settings)
        render(settings, mutableStateOf(MjpegSettings.EditPolicy.All))

        openPort()
        portField().performTextReplacement("80")
        compose.onNodeWithText(text(R.string.mjpeg_pref_connection_http_port_error)).assertIsDisplayed()
        closeEditor()
        assertEquals(initial, compose.settledMjpegSettings(settings))

        openPort()
        portField().performTextReplacement("9090")
        closeEditor()
        assertEquals(initial.copy(network = initial.network.copy(httpPort = 9090)), compose.settledMjpegSettings(settings))
        compose.onNodeWithText("9090").assertIsDisplayed()
        compose.runOnIdle { assertTrue(failures.isEmpty()) }
    }

    @Test
    fun lockingSetupBeforeDismissalDiscardsThePortDraft() {
        val settings = newSettings()
        val initial = compose.settledMjpegSettings(settings)
        val policy = mutableStateOf(MjpegSettings.EditPolicy.All)
        render(settings, policy)
        openPort()
        portField().performTextReplacement("9191")
        compose.runOnIdle { policy.value = MjpegSettings.EditPolicy.LiveOnly }
        portField().assertIsNotEnabled()
        closeEditor()
        assertEquals(initial, compose.settledMjpegSettings(settings))
        compose.runOnIdle { assertTrue(failures.isEmpty()) }
    }

    @Test
    fun queuedPortSaveRechecksThePolicyWhenItsTransactionExecutes() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val settings = newSettings(dispatcher)
            val initial = compose.settledMjpegSettings(settings)
            val policy = mutableStateOf(MjpegSettings.EditPolicy.All)
            render(settings, policy)
            openPort()
            portField().performTextReplacement("9191")

            val scope = CoroutineScope(Dispatchers.Default)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = scope.async {
                settings.updateData {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Held settings transaction was not released" }
                    copy(web = web.copy(title = "Unrelated queued edit"))
                }
            }
            try {
                assertTrue("Settings transaction did not enter", entered.await(5, TimeUnit.SECONDS))
                // Dismiss under All: the editor must emit the save before the policy changes.
                closeEditor()
                portField().assertDoesNotExist()
                compose.runOnIdle { policy.value = MjpegSettings.EditPolicy.LiveOnly }
                compose.waitForIdle()
                release.countDown()
                runBlocking { withTimeout(5.seconds) { blocker.await() } }

                val expected = initial.copy(web = initial.web.copy(title = "Unrelated queued edit"))
                assertEquals(expected, compose.settledMjpegSettings(settings))
                compose.runOnIdle { assertTrue(failures.isEmpty()) }
            } finally {
                release.countDown()
                try {
                    runBlocking { withTimeout(5.seconds) { blocker.join() } }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun liveOnlyFilterEditSavesWithoutChangingThePort() {
        val settings = newSettings()
        val initial = compose.settledMjpegSettings(settings)
        render(settings, mutableStateOf(MjpegSettings.EditPolicy.LiveOnly), NetworkFilterSearch.info)
        compose.onNodeWithText(text(R.string.mjpeg_pref_connection_filter)).performClick()
        compose.onNodeWithText(text(R.string.mjpeg_network_ipv6)).performScrollTo().performClick().assertIsSelected()
        closeEditor()

        val expected = initial.copy(
            network = initial.network.copy(
                filter = NetworkAddressFilter(
                    families = initial.network.filter.families + AddressFamily.Ipv6,
                    interfaceTypes = initial.network.filter.interfaceTypes,
                    categories = initial.network.filter.categories,
                ),
            ),
        )
        assertEquals(expected, compose.settledMjpegSettings(settings))
        compose.runOnIdle { assertTrue(failures.isEmpty()) }
    }

    private fun render(
        settings: MjpegSettings,
        policy: State<MjpegSettings.EditPolicy>,
        visibleSetting: SettingSearchInfo = HttpPortSearch.info,
    ) {
        val initial = compose.settledMjpegSettings(settings)
        compose.setContent {
            val data by settings.data.collectAsState(initial)
            MjpegTestTheme {
                ConnectionSettingsGroup(
                    networkSettings = data.network,
                    mjpegSettings = settings,
                    editPolicy = policy.value,
                    onSaveError = failures::add,
                    visibleSettings = setOf(visibleSetting),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
    }

    private fun newSettings(dispatcher: CoroutineDispatcher = Dispatchers.IO): MjpegSettings =
        MjpegSettings(IsolatedSettingsContext(compose.activity, temporaryFolder.newFolder()), dispatcher)

    private fun openPort() = compose.onNodeWithText(text(R.string.mjpeg_pref_connection_http_port)).performClick()
    private fun portField() = compose.onNodeWithText(text(R.string.mjpeg_pref_connection_http_port_label))
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun text(resource: Int): String = compose.activity.getString(resource)
}
