package io.screenstream.mjpeg.ui

import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
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
        val row = mutableStateOf(address(accessUrl = SecretValue("$ENDPOINT_URL?token=$ENCODED_TOKEN")))
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

        compose.onNodeWithText(ENDPOINT_URL).assertIsDisplayed()
        compose.onNodeWithText("$ENDPOINT_URL?token=$ENCODED_TOKEN").assertDoesNotExist()
        assertOpenShareAndCopy("$ENDPOINT_URL?token=$ENCODED_TOKEN", intents, clipboard)
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg?token=$ENCODED_TOKEN")
        copyMenuLink(R.string.mjpeg_address_copy_mjpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/mjpeg?token=$ENCODED_TOKEN")

        compose.runOnIdle { row.value = row.value.copy(accessUrl = null) }
        assertOpenShareAndCopy(ENDPOINT_URL, intents, clipboard)
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg")
    }

    @Test
    @Config(sdk = [32, 33])
    fun successfulLinkCopiesShowManualFeedbackOnlyBeforeAndroid13() {
        val clipboard = RecordingClipboard()
        val messages = mutableListOf<String>()
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    StreamAddressesContent(listOf(address()), null, false, true, {}, {}, messages::add)
                }
            }
        }

        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_description, ENDPOINT_URL)).performClick()
        assertClipboard(clipboard, ENDPOINT_URL)
        compose.runOnIdle {
            assertEquals(if (Build.VERSION.SDK_INT < 33) listOf(text(R.string.mjpeg_address_copied)) else emptyList<String>(), messages)
        }
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg")
        compose.runOnIdle {
            assertEquals(if (Build.VERSION.SDK_INT < 33) List(2) { text(R.string.mjpeg_address_copied) } else emptyList<String>(), messages)
        }
    }

    @Test
    fun retryUsesSuppliedServerIdentityAndOnlyGrantDenialUsesThePermissionCapability() {
        val initialServerId = Uuid.random()
        val replacementServerId = Uuid.random()
        val row =
            mutableStateOf(address(serverId = initialServerId, status = UiController.ServerStatus.Failed(UiController.ServerFailureReason.AddressUnavailable)))
        val retries = mutableListOf<Uuid>()
        val permissions = RecordingRuntimePermissions()
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalRuntimePermissions provides permissions) {
                    StreamAddressesContent(listOf(row.value), null, false, true, {}, retries::add, {})
                }
            }
        }

        retryButton().performClick()
        compose.runOnIdle {
            assertEquals(listOf(initialServerId), retries)
            row.value = row.value.copy(
                serverId = replacementServerId,
                status = UiController.ServerStatus.Failed(UiController.ServerFailureReason.PermissionDenied),
            )
        }
        retryButton().performClick()
        compose.onNodeWithText(text(R.string.mjpeg_server_permission_denied)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(initialServerId, replacementServerId), retries)
            assertTrue(permissions.requests.isEmpty())
            row.value = row.value.copy(serverId = null)
        }

        retryButton().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_request)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(RuntimePermission.LocalNetwork), permissions.requests)
            permissions.localNetworkState.value = PermissionState.Requesting
        }
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_request)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(initialServerId, replacementServerId), retries)
            assertEquals(1, permissions.requests.size)
            permissions.localNetworkState.value = PermissionState.SettingsSuggested
        }
        compose.onNodeWithText(text(R.string.mjpeg_local_network_permission_open_settings)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(RuntimePermission.LocalNetwork, RuntimePermission.LocalNetwork), permissions.requests) }
    }

    private fun assertOpenShareAndCopy(expectedUrl: String, intents: MutableList<Intent>, clipboard: RecordingClipboard) {
        compose.onNodeWithText(ENDPOINT_URL).performClick()
        compose.runOnIdle {
            val openIntent = intents.removeFirst()
            assertEquals(Intent.ACTION_VIEW, openIntent.action)
            assertEquals(expectedUrl, openIntent.dataString)
            assertTrue(openIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intents.isEmpty())
        }
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_share_description, ENDPOINT_URL)).performClick()
        compose.runOnIdle {
            val chooserIntent = intents.removeFirst()
            assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
            val shareIntent = checkNotNull(chooserIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, shareIntent.action)
            assertEquals("text/plain", shareIntent.type)
            assertEquals(expectedUrl, shareIntent.getStringExtra(Intent.EXTRA_TEXT))
            assertTrue(intents.isEmpty())
        }
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_description, ENDPOINT_URL)).performClick()
        assertClipboard(clipboard, expectedUrl)
    }

    private fun copyMenuLink(labelRes: Int) {
        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_options_description, ENDPOINT_URL)).performClick()
        compose.onNodeWithText(text(labelRes)).performClick()
    }

    private fun assertClipboard(clipboard: RecordingClipboard, expectedUrl: String) {
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.clipEntry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals(expectedUrl, clip.getItemAt(0).text.toString())
            assertEquals(text(R.string.mjpeg_address_copy_url), clip.description.label.toString())
            assertTrue(checkNotNull(clip.description.extras).getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }

    private fun retryButton() = compose.onNodeWithContentDescription(text(R.string.mjpeg_server_retry_description, ENDPOINT_URL))
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
        const val ENDPOINT_URL = "http://192.168.1.10:9000/"
        const val ENCODED_TOKEN = "a%20b%2Bc%2F%26%3F%3D%23%25"
    }
}
