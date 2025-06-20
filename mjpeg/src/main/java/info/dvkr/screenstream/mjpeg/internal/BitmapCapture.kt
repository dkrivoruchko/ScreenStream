package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
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
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// https://developer.android.com/media/grow/media-projection
internal class BitmapCapture(
    private val serviceContext: Context,
    private val mjpegSettings: MjpegSettings,
    private val mediaProjection: MediaProjection,
    private val bitmapStateFlow: MutableStateFlow<Bitmap>,
    private val onError: (MjpegError) -> Unit
) {
    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    private class ImageOptions(
        val vrMode: Int = MjpegSettings.Default.VR_MODE_DISABLE,
        val grayscale: Boolean = MjpegSettings.Default.IMAGE_GRAYSCALE,
        val resizeFactor: Int = MjpegSettings.Values.RESIZE_DISABLED,
        val targetWidth: Int = MjpegSettings.Default.RESOLUTION_WIDTH,
        val targetHeight: Int = MjpegSettings.Default.RESOLUTION_HEIGHT,
        val stretch: Boolean = MjpegSettings.Default.RESOLUTION_STRETCH,
        val rotationDegrees: Int = MjpegSettings.Values.ROTATION_0,
        val maxFPS: Int = MjpegSettings.Default.MAX_FPS,
        val cropLeft: Int = MjpegSettings.Default.IMAGE_CROP_LEFT,
        val cropTop: Int = MjpegSettings.Default.IMAGE_CROP_TOP,
        val cropRight: Int = MjpegSettings.Default.IMAGE_CROP_RIGHT,
        val cropBottom: Int = MjpegSettings.Default.IMAGE_CROP_BOTTOM
    )

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

    private var imageOptions: ImageOptions = ImageOptions()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastImageMillis = 0L

    private var transformMatrix = Matrix()
    private var transformMatrixDirty = true
    private var paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        XLog.d(getLog("init"))

        mjpegSettings.data.listenForChange(coroutineScope) { data ->
            imageOptions = ImageOptions(
                vrMode = data.vrMode,
                grayscale = data.imageGrayscale,
                resizeFactor = data.resizeFactor,
                targetWidth = data.resolutionWidth,
                targetHeight = data.resolutionHeight,
                stretch = data.resolutionStretch,
                rotationDegrees = data.rotation,
                maxFPS = data.maxFPS,
                cropLeft = if (data.imageCrop == MjpegSettings.Default.IMAGE_CROP) 0 else data.imageCropLeft,
                cropTop = if (data.imageCrop == MjpegSettings.Default.IMAGE_CROP) 0 else data.imageCropTop,
                cropRight = if (data.imageCrop == MjpegSettings.Default.IMAGE_CROP) 0 else data.imageCropRight,
                cropBottom = if (data.imageCrop == MjpegSettings.Default.IMAGE_CROP) 0 else data.imageCropBottom
            )

            transformMatrixDirty = true
        }

        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        check(state in requireStates) { "BitmapCapture in state [$state] expected ${requireStates.contentToString()}" }
    }

    @Synchronized
    internal fun start(): Boolean {
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

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "BitmapCaptureVirtualDisplay",
                currentWidth,
                currentHeight,
                serviceContext.resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader!!.surface,
                null,
                imageThreadHandler
            )
            state = State.STARTED
        } catch (ex: SecurityException) {
            XLog.w(getLog("startDisplayCapture", ex.toString()), ex)
            state = State.ERROR
            onError(MjpegError.CastSecurityException)
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
        coroutineScope.cancel()
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

        virtualDisplay?.surface = null
        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
        imageReader?.close()

        val newImageListener = ImageListener()
        imageListener = newImageListener
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(newImageListener, imageThreadHandler)
        }

        try {
            virtualDisplay?.resize(width, height, serviceContext.resources.configuration.densityDpi)
            virtualDisplay?.surface = imageReader!!.surface
        } catch (ex: SecurityException) {
            XLog.w(getLog("resize", ex.toString()), ex)
            state = State.ERROR
            onError(MjpegError.CastSecurityException)
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
        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
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

                    val minTimeBetweenFramesMillis = when {
                        imageOptions.maxFPS > 0 -> 1000 / imageOptions.maxFPS.toLong()
                        imageOptions.maxFPS < 0 -> 1000 * abs(imageOptions.maxFPS.toLong()) // E-Link mode
                        else -> 0
                    }
                    val now = System.currentTimeMillis()
                    if (minTimeBetweenFramesMillis > 0 && now < lastImageMillis + minTimeBetweenFramesMillis) {
                        image.close()
                        return
                    }
                    lastImageMillis = now

                    val bitmap = transformImageToBitmap(image)
                    bitmapStateFlow.tryEmit(bitmap)

                } catch (throwable: Throwable) {
                    XLog.e(this@BitmapCapture.getLog("onImageAvailable"), throwable)
                    state = State.ERROR
                    onError(MjpegError.BitmapCaptureException(throwable))
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

        val vrLeft = if (imageOptions.vrMode == MjpegSettings.Default.VR_MODE_RIGHT) fullWidth / 2 else 0
        val vrRight = if (imageOptions.vrMode == MjpegSettings.Default.VR_MODE_LEFT) fullWidth / 2 else fullWidth

        var cropLeft = (vrLeft + imageOptions.cropLeft).coerceIn(vrLeft, vrRight)
        var cropRight = (vrRight - imageOptions.cropRight).coerceIn(cropLeft, vrRight)
        var cropTop = imageOptions.cropTop.coerceIn(0, fullHeight)
        var cropBottom = (fullHeight - imageOptions.cropBottom).coerceIn(cropTop, fullHeight)

        if (cropLeft >= cropRight || cropTop >= cropBottom) {
            cropLeft = vrLeft; cropTop = 0; cropRight = vrRight; cropBottom = fullHeight // Fallback
        }

        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop

        val (scaleX, scaleY) = when {
            imageOptions.targetWidth > 0 && imageOptions.targetHeight > 0 -> {
                if (imageOptions.stretch) {
                    imageOptions.targetWidth.toFloat() / cropWidth to imageOptions.targetHeight.toFloat() / cropHeight
                } else {
                    min(imageOptions.targetWidth.toFloat() / cropWidth, imageOptions.targetHeight.toFloat() / cropHeight).let { it to it }
                }
            }

            imageOptions.resizeFactor != 100 -> {
                (imageOptions.resizeFactor / 100f).let { it to it }
            }

            else -> 1f to 1f
        }

        val scaledWidth = max(1, (cropWidth * scaleX).toInt())
        val scaledHeight = max(1, (cropHeight * scaleY).toInt())

        val rotated = imageOptions.rotationDegrees == 90 || imageOptions.rotationDegrees == 270
        val outputWidth = if (rotated) scaledHeight else scaledWidth
        val outputHeight = if (rotated) scaledWidth else scaledHeight

        if (transformMatrixDirty) {
            transformMatrix.apply {
                reset()
                setTranslate(-cropLeft.toFloat(), -cropTop.toFloat())
                postScale(scaleX, scaleY)
                postRotate(imageOptions.rotationDegrees.toFloat())
                when (imageOptions.rotationDegrees) {
                    90 -> postTranslate(scaledHeight.toFloat(), 0f)
                    180 -> postTranslate(scaledWidth.toFloat(), scaledHeight.toFloat())
                    270 -> postTranslate(0f, scaledWidth.toFloat())
                }
            }

            paint.colorFilter = if (imageOptions.grayscale) {
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            } else null

            transformMatrixDirty = false
        }

        if (outputBitmap == null || outputBitmap!!.width != outputWidth || outputBitmap!!.height != outputHeight) {
            outputBitmap?.recycle()
            outputBitmap = createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(outputBitmap!!)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(tmpBitmap, transformMatrix, paint)

        return outputBitmap!!.copy(outputBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
    }
}