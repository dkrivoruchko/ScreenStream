package info.dvkr.screenstream.webrtc.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.view.Surface
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils

internal class ScreenCapturerAndroid(
    private val surfaceTextureHelper: SurfaceTextureHelper,
    private val mediaProjectionCallback: MediaProjection.Callback,
    private val callbackHandler: Handler,
    private val onCaptureFatal: (Throwable) -> Unit
) {

    private var mediaProjection: MediaProjection? = null
    private var capturerObserver: CapturerObserver? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isDisposed: Boolean = false
    private var callbackRegistrationAttempted: Boolean = false
    private var observerStartAttempted: Boolean = false
    private var width: Int = 0
    private var height: Int = 0
    private var captureSurface: Surface? = null

    internal var isFrameDeliveryStopped: Boolean = false
        private set

    @Synchronized
    internal fun startCapture(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        capturerObserver: CapturerObserver,
        isStartupStillValid: () -> Boolean
    ): Boolean {
        checkNotDisposed()
        if (!isStartupStillValid()) {
            XLog.i(getLog("startCapture", "Startup already invalidated. Ignoring capture start."))
            return false
        }
        this.mediaProjection = mediaProjection
        this.capturerObserver = capturerObserver
        this.width = width
        this.height = height

        // Capture operations are owned by this handler; callbacks must not run on the helper
        // while the owner is waiting for a synchronous helper operation to finish.
        callbackRegistrationAttempted = true
        mediaProjection.registerCallback(mediaProjectionCallback, callbackHandler)
        surfaceTextureHelper.setTextureSize(width, height)
        captureSurface?.release()
        captureSurface = Surface(surfaceTextureHelper.surfaceTexture)
        virtualDisplay = createVirtualDisplay()
        if (virtualDisplay == null || !isStartupStillValid()) {
            val reason = if (virtualDisplay == null) "virtualDisplay is null" else "startup invalidated"
            XLog.i(getLog("startCapture", "$reason. Capture start failed."))
            return false
        }
        observerStartAttempted = true
        capturerObserver.onCapturerStarted(true)
        surfaceTextureHelper.startListening { frame -> capturerObserver.onFrameCaptured(frame) }
        return true
    }

    @Synchronized
    internal fun stopCapture(): Unit = dispose()

    @Synchronized
    internal fun changeCaptureFormat(width: Int, height: Int) {
        checkNotDisposed()
        if (width <= 0 || height <= 0) {
            XLog.e(
                getLog("changeCaptureFormat", "Invalid size: $width x $height. Ignoring."),
                IllegalArgumentException("Invalid capture size: $width x $height")
            )
            return
        }
        val sizeChanged = this.width != width || this.height != height
        if (!sizeChanged) {
            XLog.v(getLog("changeCaptureFormat", "Same size. Ignoring reconfigure"))
            return
        }
        this.width = width
        this.height = height
        if (virtualDisplay != null) {
            ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
                surfaceTextureHelper.setTextureSize(width, height)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    virtualDisplay!!.resize(width, height, 400)
                    val newSurface = Surface(surfaceTextureHelper.surfaceTexture)
                    try {
                        virtualDisplay!!.surface = newSurface
                    } catch (cause: Throwable) {
                        runCatching { newSurface.release() }.onFailure { cleanupFailure ->
                            if (cleanupFailure !== cause) cause.addSuppressed(cleanupFailure)
                        }
                        throw cause
                    }
                    val oldSurface = captureSurface
                    captureSurface = newSurface
                    oldSurface?.release()
                } else {
                    val oldDisplay = virtualDisplay
                    virtualDisplay = null
                    oldDisplay?.release()
                    virtualDisplay = createVirtualDisplay()
                    if (virtualDisplay == null) {
                        val cause = IllegalStateException("changeCaptureFormat: virtualDisplay recreation failed")
                        XLog.e(getLog("changeCaptureFormat", cause.message), cause)
                        onCaptureFatal(cause)
                        return@invokeAtFrontUninterruptibly
                    }
                }
            }
        }
    }

    @Synchronized
    internal fun dispose() {
        if (isDisposed) return
        isDisposed = true

        var failure: Throwable? = null
        fun recordFailure(cause: Throwable) {
            val firstFailure = failure
            if (firstFailure == null) failure = cause
            else if (firstFailure !== cause) runCatching { firstFailure.addSuppressed(cause) }
        }
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (cause: Throwable) {
                recordFailure(cause)
            }
        }

        // ThreadUtils only catches Exception; no Throwable may escape this helper runnable.
        cleanup {
            ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
                try {
                    cleanup {
                        surfaceTextureHelper.stopListening()
                        isFrameDeliveryStopped = true
                    }
                    if (observerStartAttempted) {
                        observerStartAttempted = false
                        cleanup { capturerObserver?.onCapturerStopped() }
                    }

                    val display = virtualDisplay
                    virtualDisplay = null
                    cleanup { display?.release() }

                    val projection = mediaProjection
                    mediaProjection = null
                    if (callbackRegistrationAttempted) {
                        callbackRegistrationAttempted = false
                        cleanup { projection?.unregisterCallback(mediaProjectionCallback) }
                    }

                    val surface = captureSurface
                    captureSurface = null
                    cleanup { surface?.release() }
                    cleanup {
                        surfaceTextureHelper.dispose()
                        isFrameDeliveryStopped = true
                    }
                    if (isFrameDeliveryStopped) capturerObserver = null
                } catch (cause: Throwable) {
                    recordFailure(cause)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun checkNotDisposed() {
        if (isDisposed) throw RuntimeException("Capturer is disposed.")
    }

    private fun createVirtualDisplay(): VirtualDisplay? = mediaProjection?.createVirtualDisplay(
        "WebRTC_ScreenCapture", width, height, 400,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        captureSurface ?: Surface(surfaceTextureHelper.surfaceTexture).also { captureSurface = it }, null, null
    )
}
