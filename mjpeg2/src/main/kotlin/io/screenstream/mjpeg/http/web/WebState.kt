package io.screenstream.mjpeg.http.web

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.media.JpegStore.ImageKind
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable browser facts; credential generation fences delivery without entering the wire protocol. */
internal data class WebState(
    val credentialGeneration: Long,
    val accessOpen: Boolean,
    val notice: String?,
    val imageKind: ImageKind,
    val display: WebPageSettings,
) {
    fun encodeJson(): JsonObject = buildJsonObject {
        put("access", if (accessOpen) "open" else "authorized")
        put("notice", notice)
        put("hasImage", imageKind != ImageKind.None)
        put(
            "imageKind", when (imageKind) {
                ImageKind.None -> "none"
                ImageKind.Logo -> "logo"
                ImageKind.Live -> "live"
                ImageKind.Retained -> "retained"
            }
        )
        put("display", buildJsonObject {
            put("background", display.background)
            put("title", display.title)
            put("titleEnabled", display.titleEnabled)
            put("titleLayout", display.titleLayout.encodeLayoutJson())
            put("controlsLayout", display.controlsLayout.encodeLayoutJson())
        })
    }

    private fun WebPageSettings.BlockLayout.encodeLayoutJson(): JsonObject = buildJsonObject {
        put("position", if (position == WebPageSettings.Edge.Top) "top" else "bottom")
        put("presentation", if (presentation == WebPageSettings.Presentation.Overlay) "overlay" else "bar")
        put("autoHide", autoHide)
    }

    companion object {
        fun noticeFor(status: Status): String? = when (status) {
            Status.WaitingForPermission -> "permission_required"
            Status.Stopping, Status.Stopped -> "stopped"
            Status.Suspended -> "paused"
            Status.Failed -> "failed"
            Status.Active, Status.Starting, Status.Reconfiguring -> null
        }
    }
}
