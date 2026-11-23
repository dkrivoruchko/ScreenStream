package io.screenstream.mjpeg.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.foundation.qr.generateQRBitmap
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.main.StreamAddressesContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class StreamAddressesUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actionsUseAppliedUrlAndCopyMenuClosesWhenItFallsBackToThePlainEndpoint() {
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
                    StreamAddressesContent(
                        addressServers = listOf(row.value),
                        appliedPin = null,
                        hidePinOnCapture = false,
                        expanded = true,
                        onExpandedChange = {},
                        onRetry = {},
                    )
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

        val jpegMenuItem = compose.onNodeWithText(text(R.string.mjpeg_address_copy_jpeg))
        copyOptionsButton().performClick()
        jpegMenuItem.assertIsDisplayed()
        compose.runOnIdle { row.value = row.value.copy(accessUrl = null) }
        jpegMenuItem.assertDoesNotExist()
        assertOpenShareAndCopy(ENDPOINT_URL, intents, clipboard)
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg")
    }

    @Test
    fun openingAndSharingFailuresShowShortToasts() {
        var launchFailure: RuntimeException = ActivityNotFoundException()
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                throw launchFailure
            }
        }
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalContext provides context) {
                    StreamAddressesContent(
                        addressServers = listOf(address()),
                        appliedPin = null,
                        hidePinOnCapture = false,
                        expanded = true,
                        onExpandedChange = {},
                        onRetry = {},
                    )
                }
            }
        }

        fun assertFailure(action: SemanticsNodeInteraction, failure: RuntimeException, messageRes: Int) {
            compose.runOnIdle {
                launchFailure = failure
                ShadowToast.reset()
            }
            action.performClick()
            compose.runOnIdle {
                assertEquals(1, ShadowToast.shownToastCount())
                assertEquals(text(messageRes), ShadowToast.getTextOfLatestToast())
                assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
            }
        }

        val open = compose.onNodeWithText(ENDPOINT_URL)
        val share = compose.onNodeWithContentDescription(text(R.string.mjpeg_address_share_description, ENDPOINT_URL))
        assertFailure(open, ActivityNotFoundException(), R.string.mjpeg_address_error_browser_not_found)
        assertFailure(open, SecurityException(), R.string.mjpeg_address_error_open)
        assertFailure(share, ActivityNotFoundException(), R.string.mjpeg_address_error_share)
        assertFailure(share, SecurityException(), R.string.mjpeg_address_error_share)
    }

    @Test
    fun qrDialogClosesOnTokenRotationAndReopensWithTheCurrentUrl() {
        val row = mutableStateOf(address(accessUrl = SecretValue("$ENDPOINT_URL?token=$ENCODED_TOKEN")))
        compose.setContent {
            MjpegTestTheme {
                StreamAddressesContent(
                    addressServers = listOf(row.value),
                    appliedPin = null,
                    hidePinOnCapture = false,
                    expanded = true,
                    onExpandedChange = {},
                    onRetry = {},
                )
            }
        }

        val qrButton = compose.onNodeWithContentDescription(text(R.string.mjpeg_address_qr_code_description, ENDPOINT_URL))
        val qrImageMatcher = hasContentDescription(text(R.string.mjpeg_address_qr_image_description))
        val qrImage = compose.onNode(qrImageMatcher, useUnmergedTree = true)
        qrButton.performClick()
        compose.waitUntilExactlyOneExists(qrImageMatcher, timeoutMillis = 5_000, useUnmergedTree = true)
        qrImage.assertIsDisplayed()

        val currentUrl = "$ENDPOINT_URL?token=rotated%2Btoken"
        compose.runOnIdle { row.value = row.value.copy(accessUrl = SecretValue(currentUrl)) }
        compose.onNode(isDialog()).assertDoesNotExist()

        qrButton.performClick()
        compose.waitUntilExactlyOneExists(qrImageMatcher, timeoutMillis = 5_000, useUnmergedTree = true)
        val actual = qrImage.captureToImage(timeoutMillis = 5_000).asAndroidBitmap()
        val expected = currentUrl.generateQRBitmap(actual.width)
        // Keep the comparison inside the dialog's rounded corners.
        val inset = actual.width / 8
        val size = actual.width - 2 * inset
        assertTrue(
            Bitmap.createBitmap(expected, inset, inset, size, size).sameAs(Bitmap.createBitmap(actual, inset, inset, size, size)),
            "The reopened dialog should render the QR code for the current access URL",
        )
    }

    @Test
    @Config(sdk = [32, 33])
    fun successfulLinkCopiesShowShortToastsOnlyBeforeAndroid13() {
        val clipboard = RecordingClipboard()
        val showsCopyToast = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        compose.setContent {
            MjpegTestTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    StreamAddressesContent(
                        addressServers = listOf(address()),
                        appliedPin = null,
                        hidePinOnCapture = false,
                        expanded = true,
                        onExpandedChange = {},
                        onRetry = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_description, ENDPOINT_URL)).performClick()
        assertClipboard(clipboard, ENDPOINT_URL)
        compose.runOnIdle {
            assertEquals(if (showsCopyToast) 1 else 0, ShadowToast.shownToastCount())
            if (showsCopyToast) assertCopyToast()
        }
        copyMenuLink(R.string.mjpeg_address_copy_jpeg)
        assertClipboard(clipboard, "http://192.168.1.10:9000/jpeg")
        compose.runOnIdle {
            assertEquals(if (showsCopyToast) 2 else 0, ShadowToast.shownToastCount())
            if (showsCopyToast) assertCopyToast()
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
                    StreamAddressesContent(
                        addressServers = listOf(row.value),
                        appliedPin = null,
                        hidePinOnCapture = false,
                        expanded = true,
                        onExpandedChange = {},
                        onRetry = retries::add,
                    )
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
        copyOptionsButton().performClick()
        compose.onNodeWithText(text(labelRes)).performClick()
    }

    private fun copyOptionsButton() = compose.onNodeWithContentDescription(text(R.string.mjpeg_address_copy_options_description, ENDPOINT_URL))

    private fun assertClipboard(clipboard: RecordingClipboard, expectedUrl: String) {
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.clipEntry).clipData
            assertEquals(1, clip.itemCount)
            assertEquals(expectedUrl, clip.getItemAt(0).text.toString())
            assertEquals(text(R.string.mjpeg_address_copy_url), clip.description.label.toString())
            assertTrue(checkNotNull(clip.description.extras).getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }

    private fun assertCopyToast() {
        assertEquals(text(R.string.mjpeg_address_copied), ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
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
