package io.screenstream.mjpeg.http.web

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.media.JpegStore.ImageKind
import io.screenstream.mjpeg.settings.WebPageSettings
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class WebStateContractTest {
    // Cases: MJPEG-WEB-10
    @Test
    fun everyStatusImageAndAccessEncodesOnlyCompleteBrowserFacts() {
        val notices = mapOf(
            Status.Stopped to "stopped", Status.Stopping to "stopped", Status.Starting to null,
            Status.Active to null, Status.Reconfiguring to null, Status.WaitingForPermission to "permission_required",
            Status.Suspended to "paused", Status.Failed to "failed",
        )
        val kinds = listOf(
            Triple(ImageKind.None, "none", false), Triple(ImageKind.Logo, "logo", true),
            Triple(ImageKind.Live, "live", true), Triple(ImageKind.Retained, "retained", true),
        )
        val display = WebPageSettings(
            background = "#A1b2C3",
            title = "Schema contract",
            titleEnabled = false,
            titleLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Bottom, WebPageSettings.Presentation.Bar, false),
            controlsLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Top, WebPageSettings.Presentation.Overlay, true),
        )
        for ((status, notice) in notices) {
            for ((kind, name, hasImage) in kinds) {
                for (open in listOf(false, true)) {
                    val wire = JSONObject(WebState(984, open, WebState.noticeFor(status), kind, display).encodeJson().toString())
                    assertEquals(setOf("access", "notice", "hasImage", "imageKind", "display"), wire.keys().asSequence().toSet())
                    assertEquals(if (open) "open" else "authorized", wire.get("access"))
                    assertEquals(notice ?: JSONObject.NULL, wire.get("notice"))
                    assertEquals(hasImage, wire.get("hasImage"))
                    assertEquals(name, wire.get("imageKind"))

                    val page = wire.getJSONObject("display")
                    assertEquals(setOf("background", "title", "titleEnabled", "titleLayout", "controlsLayout"), page.keys().asSequence().toSet())
                    assertEquals("Schema contract", page.get("title"))
                    assertEquals("#A1b2C3", page.get("background"))
                    assertEquals(false, page.get("titleEnabled"))
                    assertLayout(page.getJSONObject("titleLayout"), "bottom", "bar", false)
                    assertLayout(page.getJSONObject("controlsLayout"), "top", "overlay", true)
                }
            }
        }
    }

    // Cases: MJPEG-WEB-10
    @Test
    fun emptyAndEscapedTitlesKeepLiteralTextIndependentlyOfVisibility() {
        for (title in listOf("", "Quoted \"title\"\n\t\\ snow 雪 emoji 😀")) {
            for (enabled in listOf(false, true)) {
                val display = WebPageSettings(title = title, titleEnabled = enabled)
                val wire = JSONObject(WebState(984, true, null, ImageKind.Logo, display).encodeJson().toString())
                val page = wire.getJSONObject("display")
                assertEquals(title, page.get("title"))
                assertEquals(enabled, page.get("titleEnabled"))
            }
        }
    }

    // Cases: MJPEG-WEB-10
    @Test
    fun asymmetricLayoutsKeepTheirOwnPositionPresentationAndAutoHide() {
        val positions = listOf(WebPageSettings.Edge.Top to "top", WebPageSettings.Edge.Bottom to "bottom")
        val presentations = listOf(WebPageSettings.Presentation.Overlay to "overlay", WebPageSettings.Presentation.Bar to "bar")
        for ((position, positionName) in positions) {
            for ((presentation, presentationName) in presentations) {
                for (autoHide in listOf(false, true)) {
                    val titleLayout = WebPageSettings.BlockLayout(position, presentation, autoHide)
                    val controlsLayout = WebPageSettings.BlockLayout(
                        if (position == WebPageSettings.Edge.Top) WebPageSettings.Edge.Bottom else WebPageSettings.Edge.Top,
                        if (presentation == WebPageSettings.Presentation.Overlay) WebPageSettings.Presentation.Bar else WebPageSettings.Presentation.Overlay,
                        !autoHide,
                    )
                    val display = WebPageSettings(title = "Layout contract", titleLayout = titleLayout, controlsLayout = controlsLayout)
                    val wire = JSONObject(WebState(984, true, null, ImageKind.Logo, display).encodeJson().toString())
                    val page = wire.getJSONObject("display")
                    assertLayout(page.getJSONObject("titleLayout"), positionName, presentationName, autoHide)
                    assertLayout(
                        page.getJSONObject("controlsLayout"),
                        if (positionName == "top") "bottom" else "top",
                        if (presentationName == "overlay") "bar" else "overlay",
                        !autoHide,
                    )
                }
            }
        }
    }

    private fun assertLayout(encoded: JSONObject, position: String, presentation: String, autoHide: Boolean) {
        assertEquals(setOf("position", "presentation", "autoHide"), encoded.keys().asSequence().toSet())
        assertEquals(position, encoded.get("position"))
        assertEquals(presentation, encoded.get("presentation"))
        assertEquals(autoHide, encoded.get("autoHide"))
    }
}
