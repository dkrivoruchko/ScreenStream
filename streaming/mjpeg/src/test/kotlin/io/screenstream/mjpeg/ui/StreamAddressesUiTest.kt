package io.screenstream.mjpeg.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.main.StreamAddressesContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class StreamAddressesUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actionsUseAppliedUrlWhileDisplayingPlainEndpointAndFallBackWhenItIsAbsent() {
        val row = mutableStateOf(address(accessUrl = SecretValue("$PLAIN_URL?token=$ENCODED_TOKEN")))
        val clipboard = RecordingClipboard()
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                intents += Intent(intent)
            }
        }
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard, LocalContext provides context) {
                    StreamAddressesContent(listOf(row.value), null, false, true, {}, {}, {})
                }
            }
        }

        compose.onNodeWithText(PLAIN_URL).assertIsDisplayed()
        compose.onNodeWithText("$PLAIN_URL?token=$ENCODED_TOKEN").assertDoesNotExist()
        assertOpenShareAndCopy("$PLAIN_URL?token=$ENCODED_TOKEN", intents, clipboard)
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg?token=$ENCODED_TOKEN")
        copyMenuLink(R.string.mjpeg_address_copy_mjpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/mjpeg?token=$ENCODED_TOKEN")

        compose.runOnIdle { row.value = row.value.copy(accessUrl = null) }
        assertOpenShareAndCopy(PLAIN_URL, intents, clipboard)
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg")
    }

    @Test
    fun retryUsesSuppliedServerIdentityAndOnlyGrantDenialUsesThePermissionCapability() {
        val firstId = Uuid.random()
        val replacementId = Uuid.random()
        val row = mutableStateOf(address(serverId = firstId, status = UiController.ServerStatus.Failed(UiController.ServerFailureReason.AddressInUse)))
        val retries = mutableListOf<Uuid>()
        val permissions = RecordingRuntimePermissions()
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalRuntimePermissions provides permissions) {
                    StreamAddressesContent(listOf(row.value), null, false, true, {}, retries::add, {})
                }
            }
        }

        retry().performClick()
        compose.runOnIdle {
            assertEquals(listOf(firstId), retries)
            row.value = row.value.copy(
                serverId = replacementId,
                status = UiController.ServerStatus.Failed(UiController.ServerFailureReason.PermissionDenied),
            )
        }
        retry().performClick()
        compose.onNodeWithText(text(R.string.mjpeg_server_permission_denied)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(firstId, replacementId), retries)
            assertTrue(permissions.requests.isEmpty())
            row.value = row.value.copy(serverId = null)
        }

        retry().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_request)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(RuntimePermission.LocalNetwork), permissions.requests)
            permissions.localNetworkState.value = PermissionState.Requesting
        }
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_request)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(firstId, replacementId), retries)
            assertEquals(1, permissions.requests.size)
            permissions.localNetworkState.value = PermissionState.SettingsSuggested
        }
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_open_settings)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(RuntimePermission.LocalNetwork, RuntimePermission.LocalNetwork), permissions.requests) }
    }

    private fun assertOpenShareAndCopy(expectedUrl: String, intents: MutableList<Intent>, clipboard: RecordingClipboard) {
        compose.onNodeWithText(PLAIN_URL).performClick()
        compose.runOnIdle {
            val open = intents.removeFirst()
            assertEquals(Intent.ACTION_VIEW, open.action)
            assertEquals(expectedUrl, open.dataString)
            assertTrue(open.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intents.isEmpty())
        }
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_share_description, PLAIN_URL)).performClick()
        compose.runOnIdle {
            val chooser = intents.removeFirst()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val share = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/plain", share.type)
            assertEquals(expectedUrl, share.getStringExtra(Intent.EXTRA_TEXT))
            assertTrue(intents.isEmpty())
        }
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_description, PLAIN_URL)).performClick()
        assertClipboard(clipboard, expectedUrl)
    }

    private fun copyMenuLink(labelRes: Int) {
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_options_description, PLAIN_URL)).performClick()
        compose.onNodeWithText(text(labelRes)).performClick()
    }

    private fun assertClipboard(clipboard: RecordingClipboard, expectedUrl: String) {
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.entry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals(expectedUrl, clip.getItemAt(0).text.toString())
            assertEquals(text(R.string.mjpeg_address_copy_url), clip.description.label.toString())
            assertTrue(checkNotNull(clip.description.extras).getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }

    private fun retry() = compose.onNodeWithContentDescription(text(R.string.mjpeg_server_retry_description, PLAIN_URL))
    private fun text(resource: Int, vararg arguments: Any): String = compose.activity.getString(resource, *arguments)

    private fun address(
        accessUrl: SecretValue? = null,
        serverId: Uuid? = Uuid.random(),
        status: UiController.ServerStatus = UiController.ServerStatus.Listening,
    ) = UiController.AddressServer(
        addressId = 1,
        serverId = serverId,
        interfaceName = "wlan0",
        interfaceType = InterfaceType.Wifi,
        family = AddressFamily.Ipv4,
        isPrivate = true,
        address = "192.168.1.10",
        port = 9000,
        status = status,
        accessUrl = accessUrl,
        isLoopback = false,
        isLinkLocal = false,
    )

    private companion object {
        const val PLAIN_URL = "http://192.168.1.10:9000/"
        const val ENCODED_TOKEN = "a%20b%2Bc%2F%26%3F%3D%23%25"
    }
}
