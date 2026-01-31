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
import java.util.concurrent.CountDownLatch

internal class ScreenCapturerAndroid(
    private val surfaceTextureHelper: SurfaceTextureHelper,
    private val mediaProjectionCallback: MediaProjection.Callback
) {

    private var mediaProjection: MediaProjection? = null
    private lateinit var capturerObserver: CapturerObserver
    private var virtualDisplay: VirtualDisplay? = null
    private var isDisposed: Boolean = false
    private var width: Int = 0
    private var height: Int = 0

    @Synchronized
    internal fun startCapture(mediaProjection: MediaProjection, width: Int, height: Int, capturerObserver: CapturerObserver): Boolean {
        checkNotDisposed()
        this.mediaProjection = mediaProjection
        this.capturerObserver = capturerObserver
        this.width = width
        this.height = height

        mediaProjection.registerCallback(mediaProjectionCallback, surfaceTextureHelper.handler)
        surfaceTextureHelper.setTextureSize(width, height)
        virtualDisplay = createVirtualDisplay()
        if (virtualDisplay == null) {
            XLog.i(getLog("startCapture", "virtualDisplay is null. Stopping projection."))
            mediaProjectionCallback.onStop()
            mediaProjection.unregisterCallback(mediaProjectionCallback)
            mediaProjection.stop()
            this.mediaProjection = null
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
        val latch = CountDownLatch(1)
        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
            try {
                surfaceTextureHelper.stopListening()
                surfaceTextureHelper.dispose()
                capturerObserver.onCapturerStopped()

                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection?.unregisterCallback(mediaProjectionCallback)
                mediaProjection?.stop()
                mediaProjection = null
            } finally {
                latch.countDown()
            }
        }
        latch.await()
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
        if (this.width == width && this.height == height) {
            XLog.i(getLog("changeCaptureFormat", "Ignoring"))
            return
        }
        this.width = width
        this.height = height
        if (virtualDisplay != null) {
            ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
                surfaceTextureHelper.setTextureSize(width, height)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    virtualDisplay!!.resize(width, height, 400)
                } else {
                    virtualDisplay!!.release()
                    virtualDisplay = createVirtualDisplay()
                    if (virtualDisplay == null) {
                        XLog.i(getLog("changeCaptureFormat", "virtualDisplay is null. Stopping projection."))
                        mediaProjectionCallback.onStop()
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
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
        Surface(surfaceTextureHelper.surfaceTexture), null, null
    )
}