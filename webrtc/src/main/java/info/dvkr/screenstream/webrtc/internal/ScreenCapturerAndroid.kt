package info.dvkr.screenstream.webrtc.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils

internal class ScreenCapturerAndroid(
    private val surfaceTextureHelper: SurfaceTextureHelper,
    private val mediaProjectionCallback: MediaProjection.Callback,
    private val onCaptureFatal: (Throwable) -> Unit
) {

    private var mediaProjection: MediaProjection? = null
    private lateinit var capturerObserver: CapturerObserver
    private var virtualDisplay: VirtualDisplay? = null
    private var isDisposed: Boolean = false
    private var width: Int = 0
    private var height: Int = 0
    private var captureSurface: Surface? = null

    @Synchronized
    internal fun startCapture(mediaProjection: MediaProjection, width: Int, height: Int, capturerObserver: CapturerObserver): Boolean {
        checkNotDisposed()
        this.mediaProjection = mediaProjection
        this.capturerObserver = capturerObserver
        this.width = width
        this.height = height

        mediaProjection.registerCallback(mediaProjectionCallback, surfaceTextureHelper.handler)
        surfaceTextureHelper.setTextureSize(width, height)
        captureSurface?.release()
        captureSurface = Surface(surfaceTextureHelper.surfaceTexture)
        virtualDisplay = createVirtualDisplay()
        if (virtualDisplay == null) {
            XLog.i(getLog("startCapture", "virtualDisplay is null. Stopping projection."))
            mediaProjectionCallback.onStop()
            mediaProjection.unregisterCallback(mediaProjectionCallback)
            this.mediaProjection = null
            captureSurface?.release()
            captureSurface = null
            surfaceTextureHelper.dispose()
            return false
        }
        capturerObserver.onCapturerStarted(true)
        surfaceTextureHelper.startListening { frame -> capturerObserver.onFrameCaptured(frame) }
        return true
    }

    @Synchronized
    internal fun stopCapture() {
        checkNotDisposed()
        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
            surfaceTextureHelper.stopListening()
            surfaceTextureHelper.dispose()
            capturerObserver.onCapturerStopped()

            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection = null
            captureSurface?.release()
            captureSurface = null
        }
    }

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
                    virtualDisplay!!.surface = newSurface
                    captureSurface?.release()
                    captureSurface = newSurface
                } else {
                    virtualDisplay!!.release()
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
        isDisposed = true
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
