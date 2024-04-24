package info.dvkr.screenstream.webrtc.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Surface
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

internal class ScreenCapturerAndroid(
    applicationContext: Context,
    private val mediaProjectionPermissionResultData: Intent,
    private val surfaceTextureHelper: SurfaceTextureHelper,
    private val capturerObserver: CapturerObserver,
    private val mediaProjectionCallback: MediaProjection.Callback
) : VideoCapturer, VideoSink {
    private val mediaProjectionManager = applicationContext.getSystemService(MediaProjectionManager::class.java)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var numCapturedFrames: Long = 0
    private var isDisposed: Boolean = false
    private var width: Int = 0
    private var height: Int = 0

    @Synchronized
    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, applicationContext: Context, capturerObserver: CapturerObserver) {
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, ignoredFramerate: Int) {
        checkNotDisposed()
        this.width = width
        this.height = height
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, this.mediaProjectionPermissionResultData).apply {
            registerCallback(mediaProjectionCallback, surfaceTextureHelper.handler)
        }
        surfaceTextureHelper.setTextureSize(width, height)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WebRTC_ScreenCapture", width, height, 400,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            Surface(surfaceTextureHelper.surfaceTexture), null, null
        )
        capturerObserver.onCapturerStarted(true)
        surfaceTextureHelper.startListening(this)
    }

    @Synchronized
    override fun stopCapture() {
        checkNotDisposed()
        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.handler) {
            surfaceTextureHelper.stopListening()
            surfaceTextureHelper.dispose()
            capturerObserver.onCapturerStopped()

            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        }
    }

    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, ignoredFramerate: Int) {
        checkNotDisposed()
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
                    virtualDisplay?.resize(width, height, 400)
                } else {
                    virtualDisplay?.release()
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "WebRTC_ScreenCapture", width, height, 400,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                        Surface(surfaceTextureHelper.surfaceTexture), null, null
                    )
                }
            }
        }
    }

    @Synchronized
    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean = true

    override fun onFrame(frame: VideoFrame?) {
        ++numCapturedFrames
        capturerObserver.onFrameCaptured(frame)
    }

    fun getNumCapturedFrames(): Long = numCapturedFrames

    private fun checkNotDisposed() {
        if (isDisposed) throw RuntimeException("Capturer is disposed.")
    }
}