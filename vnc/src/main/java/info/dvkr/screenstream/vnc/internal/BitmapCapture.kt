package info.dvkr.screenstream.vnc.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.core.graphics.createBitmap
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.vnc.ui.VncError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

internal class BitmapCapture(
    private val serviceContext: Context,
    private val maxFps: () -> Int,
    private val mediaProjection: MediaProjection,
    private val bitmapStateFlow: MutableStateFlow<Bitmap>,
    private val onError: (VncError) -> Unit
) {
    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    private var state = State.INIT

    private var currentWidth = 0
    private var currentHeight = 0

    private val imageThread: HandlerThread by lazy { HandlerThread("BitmapCapture", Process.THREAD_PRIORITY_BACKGROUND) }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }

    @Volatile
    private var imageListener: ImageListener? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var reusableBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null

    private var lastImageMillis = 0L

    private var paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        XLog.d(getLog("init"))
        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        check(state in requireStates) { "BitmapCapture in state [$state] expected ${requireStates.contentToString()}" }
    }

    @Synchronized
    internal fun start(isStartupStillValid: () -> Boolean): Boolean {
        XLog.d(getLog("start"))
        requireState(State.INIT)

        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
        currentWidth = bounds.width()
        currentHeight = bounds.height()

        val newImageListener = ImageListener()
        imageListener = newImageListener
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(newImageListener, imageThreadHandler)
        }

        if (!isStartupStillValid()) {
            XLog.i(getLog("start", "Startup invalidated before virtual display creation."))
            state = State.ERROR
            safeRelease()
            return false
        }

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VncBitmapCaptureVirtualDisplay",
                currentWidth,
                currentHeight,
                serviceContext.resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                imageThreadHandler
            )
            if (!isStartupStillValid()) {
                XLog.i(getLog("start", "Startup invalidated after virtual display creation."))
                state = State.ERROR
                safeRelease()
                return false
            }
            if (virtualDisplay == null) {
                XLog.i(getLog("start", "virtualDisplay is null. Capture start failed."))
                state = State.ERROR
                safeRelease()
            } else {
                state = State.STARTED
            }
        } catch (ex: SecurityException) {
            XLog.w(getLog("start", ex.toString()), ex)
            state = State.ERROR
            onError(VncError.CastSecurityException())
            safeRelease()
        }

        return state == State.STARTED
    }

    @Synchronized
    internal fun destroy() {
        XLog.d(getLog("destroy"))
        if (state == State.DESTROYED) {
            XLog.w(getLog("destroy", "Already destroyed"))
            return
        }
        requireState(State.STARTED, State.ERROR)
        state = State.DESTROYED

        safeRelease()
        imageThread.quitSafely()
    }

    @Synchronized
    internal fun resize() {
        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
        resize(bounds.width(), bounds.height())
    }

    @Synchronized
    internal fun resize(width: Int, height: Int) {
        XLog.d(getLog("resize", "Start (width: $width, height: $height)"))

        if (state != State.STARTED) {
            XLog.d(getLog("resize", "Ignored"))
            return
        }

        if (currentWidth == width && currentHeight == height) {
            XLog.i(getLog("resize", "Same width and height. Ignored"))
            return
        }

        currentWidth = width
        currentHeight = height
        lastImageMillis = 0L

        imageReader?.close()

        val newImageListener = ImageListener()
        imageListener = newImageListener
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(newImageListener, imageThreadHandler)
        }

        try {
            virtualDisplay?.surface = null
            virtualDisplay?.resize(width, height, serviceContext.resources.configuration.densityDpi)
            virtualDisplay?.surface = imageReader!!.surface
        } catch (ex: SecurityException) {
            XLog.w(getLog("resize", ex.toString()), ex)
            state = State.ERROR
            onError(VncError.CastSecurityException())
            safeRelease()
        }

        reusableBitmap = null
        outputBitmap = null

        XLog.d(getLog("resize", "End"))
    }

    private fun safeRelease() {
        imageListener = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        reusableBitmap = null
        outputBitmap = null
    }

    private inner class ImageListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != imageListener) return

                var image: Image? = null
                try {
                    image = reader.acquireLatestImage() ?: return

                    val minTimeBetweenFramesMillis = 1000L / maxFps().coerceIn(1, 60)
                    val now = System.currentTimeMillis()
                    if (now < lastImageMillis + minTimeBetweenFramesMillis) {
                        image.close()
                        return
                    }
                    lastImageMillis = now

                    val bitmap = transformImageToBitmap(image)
                    bitmapStateFlow.tryEmit(bitmap)

                } catch (throwable: Throwable) {
                    XLog.e(getLog("onImageAvailable"), throwable)
                    state = State.ERROR
                    onError(VncError.BitmapCaptureException(throwable))
                    safeRelease()
                } finally {
                    image?.close()
                }
            }
        }
    }

    private fun transformImageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val fullWidth = image.width
        val fullHeight = image.height

        val planeWidth = plane.rowStride / plane.pixelStride
        if (reusableBitmap == null || reusableBitmap!!.width != planeWidth || reusableBitmap!!.height != fullHeight) {
            reusableBitmap = createBitmap(planeWidth, fullHeight, Bitmap.Config.ARGB_8888)
        }
        reusableBitmap!!.copyPixelsFromBuffer(plane.buffer)

        val tmpBitmap = if (planeWidth > fullWidth) {
            Bitmap.createBitmap(reusableBitmap!!, 0, 0, fullWidth, fullHeight)
        } else {
            reusableBitmap!!
        }

        if (outputBitmap == null || outputBitmap!!.width != fullWidth || outputBitmap!!.height != fullHeight) {
            outputBitmap?.recycle()
            outputBitmap = createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(outputBitmap!!)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(tmpBitmap, 0f, 0f, paint)

        return outputBitmap!!.copy(outputBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
    }
}
