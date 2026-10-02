package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit wire mapping keeps persisted Android enum names and access secrets out of browser state. */
internal class WebPresentation {
    private var capture = HttpDelivery.WebCaptureState.Stopped
    private var issue: HttpDelivery.WebCaptureIssue? = null
    private var display = WebPageSettings()

    fun update(capture: HttpDelivery.WebCaptureState, issue: HttpDelivery.WebCaptureIssue?, display: WebPageSettings): Boolean {
        if (this.capture == capture && this.issue == issue && this.display == display) return false
        this.capture = capture
        this.issue = issue
        this.display = display
        return true
    }

    /** Caller copies a coherent snapshot under the gate; encoding/sending occur outside it. */
    fun snapshot(open: Boolean, page: String, imageKind: HttpDelivery.ImageKind?): JsonObject = buildJsonObject {
        put("access", if (open) "open" else "authorized")
        put("s", page)
        put("capture", when (capture) {
            HttpDelivery.WebCaptureState.Stopped -> "stopped"
            HttpDelivery.WebCaptureState.WaitingForPermission -> "waiting_for_permission"
            HttpDelivery.WebCaptureState.Starting -> "starting"
            HttpDelivery.WebCaptureState.Active -> "active"
            HttpDelivery.WebCaptureState.Reconfiguring -> "reconfiguring"
            HttpDelivery.WebCaptureState.Suspended -> "suspended"
            HttpDelivery.WebCaptureState.Stopping -> "stopping"
            HttpDelivery.WebCaptureState.Failed -> "failed"
        })
        put("imageAvailable", imageKind != null)
        put("imageKind", when (imageKind) {
            HttpDelivery.ImageKind.StreamFrame -> "stream"
            HttpDelivery.ImageKind.Placeholder -> "placeholder"
            null -> null
        })
        put("display", buildJsonObject {
            put("background", display.background)
            put("title", display.title)
            put("titleEnabled", display.titleEnabled)
            put("titleLayout", display.titleLayout.toWireLayout())
            put("controlsLayout", display.controlsLayout.toWireLayout())
            put("retainImageOnReconnect", display.retainImageOnReconnect)
        })
        issue?.let {
            put("issue", buildJsonObject {
                put("code", when (it) {
                    HttpDelivery.WebCaptureIssue.CaptureFailed -> "capture_failed"
                    HttpDelivery.WebCaptureIssue.CleanupFailed -> "cleanup_failed"
                })
                put("action", "check_device")
            })
        }
    }

    private fun WebPageSettings.BlockLayout.toWireLayout(): JsonObject = buildJsonObject {
        put("position", if (position == WebPageSettings.Edge.Top) "top" else "bottom")
        put("presentation", if (presentation == WebPageSettings.Presentation.Overlay) "overlay" else "bar")
        put("autoHide", autoHide)
    }
}
