package io.screenstream.mjpeg.device

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.mjpeg.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class MjpegModalDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Cases: MJPEG-CFG-04
    @Test
    fun readyLoopbackAddressRendersQrInANativeDialogAndBackDismissesIt(): Unit = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val address = "http://127.0.0.1:${fixture.host.port}/"
            val addressesTitle = context.getString(R.string.mjpeg_address_list_title)
            val qrButton = context.getString(R.string.mjpeg_address_qr_code_description, address)
            val qrImage = hasContentDescription(context.getString(R.string.mjpeg_address_qr_image_description))
            val dialog = compose.onNode(isDialog())
            val sections = compose.onNode(hasScrollToIndexAction())
            sections.performScrollToNode(hasText(addressesTitle))
            compose.waitUntilExactlyOneExists(hasText(address), timeoutMillis = 5_000)
            sections.performScrollToNode(hasContentDescription(qrButton))
            compose.onNodeWithContentDescription(qrButton).assertIsDisplayed().performClick()
            compose.waitUntilExactlyOneExists(qrImage, timeoutMillis = 5_000, useUnmergedTree = true)
            dialog.assertIsDisplayed()
            compose.onNode(qrImage, useUnmergedTree = true).assertIsDisplayed()

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            dialog.assertDoesNotExist()
            compose.onNodeWithContentDescription(qrButton).assertIsDisplayed()
        }
    }

    // Cases: MJPEG-SET-04, MJPEG-SET-08, MJPEG-CFG-04
    @Test
    fun streamNameDraftSurvivesRecreationAndFailedSaveAnnouncesAToast() = with(MjpegDeviceTestSupport(compose)) {
        openHost().use { fixture ->
            val host = fixture.host
            val initial = runBlocking { withTimeout(5_000) { host.settings.data.first() } }
            val preferences = host.preferencesFile
            val savedBytes = preferences.readBytes()
            // A non-empty directory at the serializer's temporary path prevents the real write.
            val blockedTemporaryFile = File(preferences.absolutePath + ".tmp")
            check(blockedTemporaryFile.mkdir())
            File(blockedTemporaryFile, "blocker").writeText("Fixture storage fault")

            val webPageGroup = context.getString(R.string.mjpeg_pref_web_group)
            val title = context.getString(R.string.mjpeg_pref_web_stream_name)
            val nameItem = hasText(title) and hasClickAction()
            val sections = compose.onNode(hasScrollToIndexAction())
            sections.performScrollToNode(hasText(webPageGroup))
            compose.onNodeWithText(webPageGroup).performClick()
            sections.performScrollToNode(nameItem)
            compose.onNode(nameItem).performClick()
            compose.onNode(isDialog()).assertIsDisplayed()

            val field = compose.onNodeWithText(context.getString(R.string.mjpeg_pref_web_stream_name_label))
            val draft = "Unsaved stream name"
            field.performTextReplacement(draft)
            fixture.scenario.recreate()
            compose.onNode(isDialog()).assertIsDisplayed()
            field.assertTextContains(draft)
            assertContentEquals(savedBytes, preferences.readBytes(), "Recreation must leave the draft unsaved")

            val close = compose.onNodeWithContentDescription(context.getString(R.string.mjpeg_action_close))
            val errorMessage = context.getString(R.string.mjpeg_pref_web_save_error)
            runBlocking {
                val toast = async(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    instrumentation.uiAutomation.executeAndWaitForEvent(
                        { close.performClick() },
                        { event ->
                            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                                    event.className?.toString() == Toast::class.java.name &&
                                    event.packageName?.toString() == context.packageName &&
                                    event.text.any { it.toString() == errorMessage }
                        },
                        10_000,
                    ).recycle()
                }
                // Keep the Compose clock running so the save coroutine can resume and show the Toast.
                compose.waitUntil(12_000) { toast.isCompleted }
                toast.await()
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            assertEquals(initial, runBlocking { withTimeout(5_000) { host.settings.data.first() } })
            assertContentEquals(savedBytes, preferences.readBytes())
        }
    }
}
