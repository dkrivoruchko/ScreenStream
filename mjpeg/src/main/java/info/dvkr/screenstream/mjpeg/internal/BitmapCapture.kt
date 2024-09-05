package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

// https://developer.android.com/media/grow/media-projection
internal class BitmapCapture(
    private val serviceContext: Context,
    private val mjpegSettings: MjpegSettings,
    private val mediaProjection: MediaProjection,
    private val bitmapStateFlow: MutableStateFlow<Bitmap>,
    private val onError: (MjpegError) -> Unit
) {
    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    @Volatile private var state: State = State.INIT

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    private val imageThread: HandlerThread by lazy { HandlerThread("BitmapCapture", Process.THREAD_PRIORITY_BACKGROUND) }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }

    private fun getDensity(): Int = serviceContext.resources.configuration.densityDpi
    private fun getScreenSize(): Rect = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds

    private var imageListener: ImageListener? = null
    private var imageReader: ImageReader? = null
    private var lastImageMillis: Long = 0L
    private var virtualDisplay: VirtualDisplay? = null

    private val matrix = AtomicReference(Matrix())
    private val resizeFactor = AtomicInteger(MjpegSettings.Values.RESIZE_DISABLED)
    private val rotation = AtomicInteger(MjpegSettings.Values.ROTATION_0)
    private val maxFPS = AtomicInteger(MjpegSettings.Default.MAX_FPS)
    private val vrMode = AtomicInteger(MjpegSettings.Default.VR_MODE_DISABLE)
    private val imageCrop = AtomicBoolean(MjpegSettings.Default.IMAGE_CROP)
    private val imageCropTop = AtomicInteger(MjpegSettings.Default.IMAGE_CROP_TOP)
    private val imageCropBottom = AtomicInteger(MjpegSettings.Default.IMAGE_CROP_BOTTOM)
    private val imageCropLeft = AtomicInteger(MjpegSettings.Default.IMAGE_CROP_LEFT)
    private val imageCropRight = AtomicInteger(MjpegSettings.Default.IMAGE_CROP_RIGHT)
    private val imageGrayscale = AtomicBoolean(MjpegSettings.Default.IMAGE_GRAYSCALE)

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        XLog.d(getLog("init"))

        mjpegSettings.data.listenForChange(coroutineScope) { data ->
            if (resizeFactor.getAndSet(data.resizeFactor) != data.resizeFactor) updateMatrix()
            if (rotation.getAndSet(data.rotation) != data.rotation) updateMatrix()
            maxFPS.set(data.maxFPS)
            vrMode.set(data.vrMode)
            imageCrop.set(data.imageCrop)
            imageCropTop.set(data.imageCropTop)
            imageCropBottom.set(data.imageCropBottom)
            imageCropLeft.set(data.imageCropLeft)
            imageCropRight.set(data.imageCropRight)
            imageGrayscale.set(data.imageGrayscale)
        }

        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        check(state in requireStates) { "BitmapCapture in state [$state] expected ${requireStates.contentToString()}" }
    }

    @Synchronized
    private fun updateMatrix() {
        XLog.d(getLog("updateMatrix"))
        val newMatrix = Matrix()

        if (resizeFactor.get() != MjpegSettings.Values.RESIZE_DISABLED)
            newMatrix.postScale(resizeFactor.get() / 100f, resizeFactor.get() / 100f)

        if (rotation.get() != MjpegSettings.Values.ROTATION_0)
            newMatrix.postRotate(rotation.get().toFloat())

        matrix.set(newMatrix)
    }

    @Synchronized
    internal fun start(): Boolean {
        XLog.d(getLog("start"))
        requireState(State.INIT)
        return startDisplayCapture()
    }

    @Synchronized
    internal fun destroy() {
        XLog.d(getLog("destroy"))
        if (state == State.DESTROYED) {
            XLog.w(getLog("destroy", "Already destroyed"))
            return
        }
        coroutineScope.cancel()
        requireState(State.STARTED, State.ERROR)
        state = State.DESTROYED
        stopDisplayCapture()
        imageThread.quit()
    }

    @SuppressLint("WrongConstant")
    private fun startDisplayCapture(): Boolean {
        val screenSize = getScreenSize()

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(screenSize.width(), screenSize.height(), PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStreamVirtualDisplay", screenSize.width(), screenSize.height(), getDensity(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                imageReader!!.surface, null, imageThreadHandler
            )
            state = State.STARTED
        } catch (ex: SecurityException) {
            state = State.ERROR
            XLog.w(getLog("startDisplayCapture", ex.toString()), ex)
            onError(MjpegError.CastSecurityException)
        }

        currentWidth = screenSize.width()
        currentHeight = screenSize.height()

        return state == State.STARTED
    }

    private fun stopDisplayCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
        imageReader?.close()
        imageReader = null
    }

    @Synchronized
    internal fun resize() {
        val screenSize = getScreenSize()
        resize(screenSize.width(), screenSize.height())
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

        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
        imageReader?.close()
        imageReader = null

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }
        try {
            virtualDisplay?.resize(width, height, getDensity())
            virtualDisplay?.surface = imageReader!!.surface
        } catch (ex: SecurityException) {
            state = State.ERROR
            XLog.w(getLog("resize", ex.toString()), ex)
            onError(MjpegError.CastSecurityException)
        }

        currentWidth = width
        currentHeight = height

        XLog.d(getLog("resize", "End"))
    }

    private inner class ImageListener : ImageReader.OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != imageListener) return

                try {
                    reader.acquireLatestImage()?.let { image ->
                        val now = System.currentTimeMillis()
                        if (maxFPS.get() > 0) {
                            if ((now - lastImageMillis) < (1000 / maxFPS.get())) {
                                image.close()
                                return
                            }
                        } else {
                            if ((now - lastImageMillis) < (1000 * abs(maxFPS.get()))) {
                                image.close()
                                return
                            }
                        }
                        lastImageMillis = now

                        val cleanBitmap = getCleanBitmap(image)
                        val croppedBitmap = getCroppedBitmap(cleanBitmap)
                        val grayScaleBitmap = getGrayScaleBitmap(croppedBitmap)
                        val resizedBitmap = getResizedAndRotatedBitmap(grayScaleBitmap)

                        image.close()
                        bitmapStateFlow.tryEmit(resizedBitmap)
                    }
                } catch (throwable: Throwable) {
                    XLog.e(this@BitmapCapture.getLog("outBitmapChannel"), throwable)
                    state = State.ERROR
                    onError(MjpegError.BitmapCaptureException(throwable))
                }
            }
        }

        private lateinit var reusableBitmap: Bitmap

        private fun getCleanBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val width = plane.rowStride / plane.pixelStride
            val cleanBitmap: Bitmap

            if (width > image.width) {
                if (::reusableBitmap.isInitialized.not())
                    reusableBitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)

                reusableBitmap.copyPixelsFromBuffer(plane.buffer)
                cleanBitmap = Bitmap.createBitmap(reusableBitmap, 0, 0, image.width, image.height)
            } else {
                cleanBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                cleanBitmap.copyPixelsFromBuffer(plane.buffer)
            }
            return cleanBitmap
        }
    }

    private fun getCroppedBitmap(bitmap: Bitmap): Bitmap {
        if (vrMode.get() <= MjpegSettings.Default.VR_MODE_DISABLE && imageCrop.get().not()) return bitmap

        val imageLeft = if (vrMode.get() == MjpegSettings.Default.VR_MODE_RIGHT) bitmap.width / 2 else 0
        val imageRight = if (vrMode.get() == MjpegSettings.Default.VR_MODE_LEFT) bitmap.width / 2 else bitmap.width

        val imageCropLeft = if (imageCrop.get()) this.imageCropLeft.get() else 0
        val imageCropRight = if (imageCrop.get()) this.imageCropRight.get() else 0
        val imageCropTop = if (imageCrop.get()) this.imageCropTop.get() else 0
        val imageCropBottom = if (imageCrop.get()) this.imageCropBottom.get() else 0

        if (imageLeft + imageRight - imageCropLeft - imageCropRight <= 0 || bitmap.height - imageCropTop - imageCropBottom <= 0) return bitmap

        return try {
            Bitmap.createBitmap(
                bitmap, imageLeft + imageCropLeft, imageCropTop,
                imageRight - imageLeft - imageCropLeft - imageCropRight,
                bitmap.height - imageCropTop - imageCropBottom
            )
        } catch (ex: IllegalArgumentException) {
            XLog.w(this@BitmapCapture.getLog("getCroppedBitmap", ex.toString()), ex)
            bitmap
        }
    }

    private fun getResizedAndRotatedBitmap(bitmap: Bitmap): Bitmap {
        if (matrix.get().isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix.get(), false)
    }

    private fun getGrayScaleBitmap(bitmap: Bitmap): Bitmap {
        if (imageGrayscale.get() == MjpegSettings.Default.IMAGE_GRAYSCALE) return bitmap
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        val bmpGrayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(bmpGrayscale).apply { drawBitmap(bitmap, 0f, 0f, paint) }
        return bmpGrayscale
    }
}