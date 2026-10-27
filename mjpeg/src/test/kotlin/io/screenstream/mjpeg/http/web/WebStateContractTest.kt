package io.screenstream.mjpeg.http.web

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

internal class WebStateContractTest {
    // Cases: MJPEG-WEB-10
    @Test
    fun authorizedSnapshotPreservesTitleTextAndPublishesOnlyBrowserFacts() {
        val title = "Quoted \"title\"\n\t\\ snow 雪 emoji 😀"
        val display = WebPageSettings(
            background = "#A1b2C3",
            title = title,
            titleEnabled = false,
            titleLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Bottom, WebPageSettings.Presentation.Bar, false),
            controlsLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Top, WebPageSettings.Presentation.Overlay, true),
            retainImageOnMediaFailure = false,
        )
        val wire = Json.parseToJsonElement(WebState(984L, false, "paused", true, display).encodeJson().toString()).jsonObject

        assertEquals(setOf("access", "notice", "hasImage", "display"), wire.keys)
        assertEquals("authorized", wire.getValue("access").jsonPrimitive.content)
        assertEquals("paused", wire.getValue("notice").jsonPrimitive.content)
        assertEquals(true, wire.getValue("hasImage").jsonPrimitive.boolean)
        assertFalse(wire.getValue("hasImage").jsonPrimitive.isString)
        val page = wire.getValue("display").jsonObject
        assertEquals(setOf("background", "title", "titleEnabled", "titleLayout", "controlsLayout", "retainImageOnMediaFailure"), page.keys)
        assertEquals("#A1b2C3", page.getValue("background").jsonPrimitive.content)
        assertEquals(title, page.getValue("title").jsonPrimitive.content)
        assertFalse(page.getValue("titleEnabled").jsonPrimitive.boolean)
        assertFalse(page.getValue("retainImageOnMediaFailure").jsonPrimitive.boolean)
        assertFalse(page.getValue("titleEnabled").jsonPrimitive.isString)
        assertFalse(page.getValue("retainImageOnMediaFailure").jsonPrimitive.isString)
        assertLayout(page.getValue("titleLayout").jsonObject, "bottom", "bar", false)
        assertLayout(page.getValue("controlsLayout").jsonObject, "top", "overlay", true)
    }

    // Cases: MJPEG-WEB-10
    @Test
    fun captureNoticesAreExplicitlyNullableAndEmptyTitleStaysEmpty() {
        val expected = mapOf(
            Status.WaitingForPermission to "permission_required",
            Status.Stopped to "stopped",
            Status.Suspended to "paused",
            Status.Failed to "failed",
            Status.Active to null,
            Status.Starting to null,
            Status.Reconfiguring to null,
            Status.Stopping to null,
        )
        expected.forEach { (status, notice) ->
            val state = WebState(1L, true, WebState.noticeFor(status), false, WebPageSettings(title = ""))
            val wire = Json.parseToJsonElement(state.encodeJson().toString()).jsonObject
            assertEquals("open", wire.getValue("access").jsonPrimitive.content)
            assertEquals(notice, WebState.noticeFor(status))
            assertEquals(if (notice == null) JsonNull else kotlinx.serialization.json.JsonPrimitive(notice), wire.getValue("notice"))
            assertFalse(wire.getValue("hasImage").jsonPrimitive.boolean)
            assertFalse(wire.getValue("hasImage").jsonPrimitive.isString)
            assertEquals("", wire.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
        }
    }

    private fun assertLayout(layout: kotlinx.serialization.json.JsonObject, position: String, presentation: String, autoHide: Boolean) {
        assertEquals(setOf("position", "presentation", "autoHide"), layout.keys)
        assertEquals(position, layout.getValue("position").jsonPrimitive.content)
        assertEquals(presentation, layout.getValue("presentation").jsonPrimitive.content)
        assertEquals(autoHide, layout.getValue("autoHide").jsonPrimitive.boolean)
        assertFalse(layout.getValue("autoHide").jsonPrimitive.isString)
    }
}
