package io.screenstream.mjpeg.ui

import android.content.ClipData
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
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
import org.robolectric.shadows.DragEventBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import android.view.KeyEvent as AndroidKeyEvent

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
        pinField().performTextReplacement("12345a")
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
    fun automaticPolicyRegeneratesReadOnlyPinUsingLatestSettings() {
        val fixture = PinFixture(initialPolicy = AccessSettings.PinPolicy.NewOnModuleStart, applyImmediately = false)
        fixture.access.value = fixture.access.value.copy(pin = null)
        render(fixture)
        openEditor()
        pinField().assert(!hasSetTextAction())
        assertEditorText("", obfuscated = true)
        regenerateButton().assertIsEnabled().performClick()
        val generatedPin = compose.runOnIdle {
            val latest = fixture.access.value.copy(
                pinEnabled = true,
                hidePinOnCaptureStart = false,
                limitWrongPins = false,
                blockMinutes = 7,
            )
            val generated = fixture.transforms.single().invoke(latest)
            val pin = assertNotNull(generated.pin)
            assertEquals(latest.copy(pin = pin), generated)
            fixture.access.value = generated
            pin.value
        }
        revealEditor()
        assertEditorText(generatedPin, obfuscated = false)
        closeEditor()
        compose.runOnIdle { assertEquals(1, fixture.transforms.size) }
    }

    @Test
    fun selectionAndSameTextReplacementKeepFollowingTheSavedPin() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performSemanticsAction(SemanticsActions.SetSelection) { select ->
            assertTrue(select(1, 3, false))
        }
        assertSelection(1, 3)
        pinField().performTextReplacement("123456")
        assertSelection(6, 6)
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("987654")) }
        pinField().assertTextContains("987654")
        closeEditor()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }
    }

    @Test
    fun setupLockRejectsAnExistingManualDraft() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performTextReplacement("000999")
        assertEditorText("000999", obfuscated = false)
        compose.runOnIdle { fixture.setupEditable.value = false }
        pinField().assert(!hasSetTextAction())
        regenerateButton().assertIsNotEnabled()
        closeEditor()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }
    }

    @Test
    fun undoKeepsTheManualDraftWhenTheSavedPinChanges() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performTextReplacement("000123")
        undoPinEdit()
        assertEditorText("123456", obfuscated = false)
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("987654")) }
        assertEditorText("123456", obfuscated = false)
        closeEditor()
        compose.runOnIdle {
            assertEquals(1, fixture.transforms.size)
            assertEquals("123456", fixture.access.value.pin?.value)
        }
    }

    @Test
    fun delayedRegenerationDiscardsTheDraftAndItsUndoHistoryImmediately() {
        val fixture = PinFixture(applyImmediately = false)
        render(fixture)
        openEditor()
        revealEditor()
        pinField().performTextReplacement("000123")
        assertEditorText("000123", obfuscated = false)
        regenerateButton().performClick()
        assertEditorText("123456", obfuscated = false)
        pinField().assertIsNotFocused().performClick()
        undoPinEdit()
        assertEditorText("123456", obfuscated = false)
        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pin = SecretValue("987654")) }
        assertEditorText("987654", obfuscated = false)
        closeEditor()
        compose.runOnIdle { assertEquals(1, fixture.transforms.size) }
    }

    @Test
    fun visibilityChangesAccessibilityTextAndKeepsEditingActions() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        assertEditorText("••••••", obfuscated = true)
        pinField().assert(hasSetTextAction())
        revealEditor()
        assertEditorText("123456", obfuscated = false)
        pinField().assert(hasSetTextAction())
        toggleEditorVisibility(R.string.mjpeg_pin_hide_description)
        assertEditorText("••••••", obfuscated = true)
    }

    @Test
    fun maskedSupplementaryInputUsesAccessibleSelectionOffsets() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        pinField().performTextReplacement("12😀45")
        assertEditorText("•••••", obfuscated = true)
        assertSelection(5, 5)
        pinField().performSemanticsAction(SemanticsActions.SetSelection) { select ->
            assertTrue(select(2, 3, false))
        }
        assertSelection(2, 3)
        pinField().performSemanticsAction(SemanticsActions.SetSelection) { select ->
            assertTrue(select(3, 5, true))
        }
        assertSelection(3, 5)
        revealEditor()
        assertEditorText("12😀45", obfuscated = false)
        assertSelection(4, 6)
    }

    @Test
    fun editorDoesNotOfferThePinToAutofillOrAcceptAnAutofillValue() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        assertAutofillDisabled()
        revealEditor()
        assertAutofillDisabled()
        pinField().performSemanticsAction(SemanticsActions.OnFillData) { fill ->
            val value = assertNotNull(FillableData.createFromText("777777"))
            assertFalse(fill(value))
        }
        assertEditorText("123456", obfuscated = false)
    }

    @Test
    fun textDropOnlyEditsTheFieldWhenSetupAllowsIt() {
        val fixture = PinFixture(initialPolicy = AccessSettings.PinPolicy.NewOnModuleStart)
        render(fixture)
        openEditor()
        revealEditor()
        dropTextIntoPinField("9")
        assertEditorText("123456", obfuscated = false)

        compose.runOnIdle { fixture.access.value = fixture.access.value.copy(pinPolicy = AccessSettings.PinPolicy.Permanent) }
        dropTextIntoPinField("9")
        pinField().assertTextContains("9", substring = true)
        compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_error)).assertIsDisplayed()
    }

    @Test
    fun imeDoneAndHardwareEnterClearFocusWithoutSaving() {
        val fixture = PinFixture()
        render(fixture)
        openEditor()
        pinField().performTextReplacement("000123")
        pinField().assertIsFocused().performImeAction()
        pinField().assertIsNotFocused()
        pinField().performTextReplacement("000999")
        pinField().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        pinField().assertIsNotFocused()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }
        closeEditor()
        compose.runOnIdle { assertEquals("000999", fixture.access.value.pin?.value) }
    }

    @Test
    fun restoringCompositionClosesTheEditorAndDiscardsTheDraft() {
        val fixture = PinFixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PinFixtureContent(fixture) }
        openEditor()
        revealEditor()
        pinField().performTextReplacement("000123")
        restoration.emulateSavedInstanceStateRestore()
        pinField().assertDoesNotExist()
        openEditor()
        assertEditorText("••••••", obfuscated = true)
        revealEditor()
        assertEditorText("123456", obfuscated = false)
        closeEditor()
        compose.runOnIdle { assertTrue(fixture.transforms.isEmpty()) }
    }

    private fun render(fixture: PinFixture) {
        compose.setContent { PinFixtureContent(fixture) }
    }

    @Composable
    private fun PinFixtureContent(fixture: PinFixture) {
        val access = fixture.access.value
        MjpegTestTheme {
            PinSettingItem(access.pin, access.pinPolicy, fixture.setupEditable.value, fixture::onChange)
        }
    }

    private fun openEditor() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin)).performClick()
    private fun closeEditor() = compose.onNodeWithContentDescription(text(R.string.mjpeg_action_close)).performClick()
    private fun pinField() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_label))
    private fun regenerateButton() = compose.onNodeWithText(text(R.string.mjpeg_pref_access_pin_regenerate))
    private fun text(resource: Int): String = compose.activity.getString(resource)

    private fun assertSelection(start: Int, end: Int) {
        pinField().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(start, end)))
    }

    private fun assertAutofillDisabled() {
        val field = pinField().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
        assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.FillableData].textValue?.toString())
    }

    private fun undoPinEdit() {
        val root = checkNotNull(pinField().fetchSemanticsNode().root)
        // Robolectric's virtual key map reports printable z even with Ctrl pressed.
        val shortcut = object : AndroidKeyEvent(0, 0, ACTION_DOWN, KEYCODE_Z, 0, META_CTRL_ON) {
            override fun getUnicodeChar(): Int = 0
        }
        val release = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_Z, 0, AndroidKeyEvent.META_CTRL_ON)
        compose.runOnIdle {
            assertTrue(root.sendKeyEvent(KeyEvent(shortcut)))
            root.sendKeyEvent(KeyEvent(release))
        }
    }

    private fun dropTextIntoPinField(value: String) {
        val field = pinField().fetchSemanticsNode()
        val view = (field.root as ViewRootForTest).view
        val position = field.boundsInRoot.center
        val clip = ClipData.newPlainText("", value)
        fun event(action: Int): DragEvent = DragEventBuilder.newBuilder()
            .setAction(action)
            .setX(position.x)
            .setY(position.y)
            .setClipDescription(clip.description)
            .setClipData(clip)
            .build()

        compose.runOnIdle {
            assertTrue(view.dispatchDragEvent(event(DragEvent.ACTION_DRAG_STARTED)))
            view.dispatchDragEvent(event(DragEvent.ACTION_DRAG_LOCATION))
            assertTrue(view.dispatchDragEvent(event(DragEvent.ACTION_DROP)))
            view.dispatchDragEvent(event(DragEvent.ACTION_DRAG_ENDED))
        }
    }

    private fun assertEditorText(value: String, obfuscated: Boolean) {
        pinField()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsPasswordObfuscated, obfuscated))
    }

    private fun revealEditor() = toggleEditorVisibility(R.string.mjpeg_pin_show_description)

    private fun toggleEditorVisibility(description: Int) {
        compose.onNode(
            hasContentDescription(text(description)) and
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
