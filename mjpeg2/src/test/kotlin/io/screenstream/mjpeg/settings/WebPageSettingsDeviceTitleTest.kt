package io.screenstream.mjpeg.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class WebPageSettingsDeviceTitleTest {
    // Cases: MJPEG-SET-04
    @Test
    fun titleTrimsNamesAvoidsRepeatedManufacturerAndFallsBackForBlankMetadata() {
        for ((manufacturer, model, expected) in listOf(
            Triple(" ACME ", " acme Tablet ", "acme Tablet"),
            Triple(" Acme ", " Model One ", "Acme Model One"),
            Triple("", " Model One ", "Model One"),
            Triple("Acme", "", "Acme"),
            Triple("  ", " \t ", "Android device"),
        )) {
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
            assertEquals(expected, WebPageSettings().title)
        }
    }

    // Cases: MJPEG-SET-04
    @Test
    fun defaultTitleIsExplicitlyEncodedAndEmptySavedTitleIsKept() {
        ShadowBuild.setManufacturer("Acme")
        ShadowBuild.setModel("Tablet")
        val encoded = Json.encodeToString(WebPageSettings())
        assertEquals("Acme Tablet", Json.parseToJsonElement(encoded).jsonObject.getValue("title").jsonPrimitive.content)
        ShadowBuild.setManufacturer("Later")
        ShadowBuild.setModel("Device")
        assertEquals("Acme Tablet", Json.decodeFromString<WebPageSettings>(encoded).title)
        assertEquals("", Json.decodeFromString<WebPageSettings>("""{"title":""}""").title)
        assertEquals(false, Json.decodeFromString<WebPageSettings>("""{"title":"Saved","retainImageOnReconnect":false}""").retainImageOnMediaFailure)
    }
}
