package io.screenstream.mjpeg.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.settings.access.PinSettingItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PinSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun invalidDraftDoesNotCommitAndManualLeadingZerosSurviveSavedPinUpdates() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        pinField().performTextReplacement("123")
        compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_error)).assertIsDisplayed()
        closeEditor()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }

        openEditor()
        revealEditor()
        pinField().performTextReplacement("000042")
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("222222")) }
        pinField().assertTextContains("000042")
        closeEditor()
        compose.runOnIdle {
            assertEquals(1, fixture.transforms.size)
            assertEquals("000042", fixture.access.value.pin?.value)
        }
    }

    @Test
    fun emittedManualTransformCannotOverwriteALaterAutomaticPolicy() {
        val fixture = PinFixture(applyImmediately = false)
        render(fixture)
        openEditor()
        pinField().performTextReplacement("000007")
        closeEditor()
        compose.runOnIdle {
            assertEquals(1, fixture.transforms.size)
            val latest = fixture.access.value.copy(pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = SecretValue("765432"))
            assertEquals(latest, fixture.transforms.single().invoke(latest))
        }
    }

    @Test
    fun automaticPolicyIsReadOnlyButRegenerationRemainsAvailable() {
        val fixture = PinFixture(initialPolicy = AccessSettings.PinPolicy.NewOnModuleStart)
        render(fixture)
        openEditor()
        pinField().assert(!hasSetTextAction())
        regenerateButton().assertIsEnabled().performClick()
        revealEditor()
        compose.runOnIdle {
            assertEquals(1, fixture.transforms.size)
            assertNotNull(fixture.access.value.pin)
            val latest = AccessSettings(
                pinEnabled = true,
                pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart,
                pin = null,
                hidePinOnCaptureStart = false,
                limitWrongPins = false,
                blockMinutes = 7,
            )
            val generated = fixture.transforms.single().invoke(latest)
            val generatedPin = assertNotNull(generated.pin)
            assertEquals(latest.copy(pin = generatedPin), generated)
        }
        pinField().assertTextContains(checkNotNull(fixture.access.value.pin).value)
        closeEditor()
        compose.runOnIdle { assertEquals(1, fixture.transforms.size) }
    }

    @Test
    fun selectionOnlyChangesKeepFollowingTheSavedPin() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performSemanticsAction(SemanticsActions.SetSelection) { select ->
            assertTrue(select(1, 3, false))
        }
        pinField().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 3)))
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("987654")) }
        pinField().assertTextContains("987654")
        closeEditor()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }
    }

    @Test
    fun regenerationResumesFollowingSavedPinAndSetupLockRejectsTheNextDraft() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performTextReplacement("000123")
        regenerateButton().performClick()
        compose.runOnIdle { assertEquals(1, fixture.transforms.size) }
        pinField().assertTextContains(checkNotNull(fixture.access.value.pin).value)
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("987654")) }
        pinField().assertTextContains("987654")

        pinField().performTextReplacement("000999")
        compose.runOnIdle { fixture.setupEditable.value = false }
        pinField().assert(!hasSetTextAction())
        regenerateButton().assertIsNotEnabled()
        closeEditor()
        compose.runOnIdle {
            assertEquals(1, fixture.transforms.size)
            assertEquals("987654", fixture.access.value.pin?.value)
        }
    }

    private fun render(fixture: PinFixture) {
        compose.setContent {
            val access = fixture.access.value
            MjpegTestTheme {
                PinSettingItem(access.pin, access.pinPolicy, fixture.setupEditable.value, fixture::onChange)
            }
        }
    }

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun pinField() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_label))
    private fun regenerateButton() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_regenerate))
    private fun text(resource: Int): String = compose.activity.getString(resource)

    private fun revealEditor() {
        compose.onNode(
            hasContentDescription(text(R.string.mjpeg_pin_show_description)) and
                    hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)),
            useUnmergedTree = true,
        ).performClick()
    }

    private class PinFixture(
        initialPolicy: AccessSettings.PinPolicy = AccessSettings.PinPolicy.Permanent,
        private val applyImmediately: Boolean = true,
    ) {
        val access = mutableStateOf(AccessSettings(pinPolicy = initialPolicy, pin = SecretValue("123456")))
        val setupEditable = mutableStateOf(true)
        val transforms = mutableListOf<AccessSettings.() -> AccessSettings>()

        fun onChange(transform: AccessSettings.() -> AccessSettings) {
            transforms += transform
            if (applyImmediately) access.value = transform(access.value)
        }
    }
}
