package io.screenstream.mjpeg.http.web

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable browser facts; credential generation fences delivery without entering the wire protocol. */
internal data class WebState(
    val generation: Long,
    val accessOpen: Boolean,
    val notice: String?,
    val hasImage: Boolean,
    val display: WebPageSettings,
) {
    fun encodeJson(): JsonObject = buildJsonObject {
        put("access", if (accessOpen) "open" else "authorized")
        put("notice", notice)
        put("hasImage", hasImage)
        put("display", buildJsonObject {
            put("background", display.background)
            put("title", display.title)
            put("titleEnabled", display.titleEnabled)
            put("titleLayout", display.titleLayout.encodeLayoutJson())
            put("controlsLayout", display.controlsLayout.encodeLayoutJson())
            put("retainImageOnMediaFailure", display.retainImageOnMediaFailure)
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
            Status.Stopped -> "stopped"
            Status.Suspended -> "paused"
            Status.Failed -> "failed"
            Status.Active, Status.Starting, Status.Reconfiguring, Status.Stopping -> null
        }
    }
}
