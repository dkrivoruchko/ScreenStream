package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.graphics.*
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
import kotlin.math.abs
import kotlin.math.max

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
    private var paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        XLog.d(getLog("init"))

        mjpegSettings.data.listenForChange(coroutineScope) { data ->
            imageOptions = ImageOptions(
                vrMode = data.vrMode,
                grayscale = data.imageGrayscale,
                resizeFactor = data.resizeFactor,
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

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(imageListener, imageThreadHandler)
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

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(imageListener, imageThreadHandler)
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
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
        imageReader?.close()
        imageReader = null
        reusableBitmap = null
        outputBitmap = null
        imageListener = null
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

        // Reuse or create a large ARGB_8888 bitmap for the raw copy
        val planeWidth = plane.rowStride / plane.pixelStride
        val planeHeight = fullHeight
        if (reusableBitmap == null || reusableBitmap!!.width != planeWidth || reusableBitmap!!.height != planeHeight) {
            reusableBitmap = Bitmap.createBitmap(planeWidth, planeHeight, Bitmap.Config.ARGB_8888)
        }
        reusableBitmap!!.copyPixelsFromBuffer(plane.buffer)

        // If planeWidth > actual width, make a "clean" sub-bitmap
        val tmpBitmap = if (planeWidth > fullWidth) {
            Bitmap.createBitmap(reusableBitmap!!, 0, 0, fullWidth, planeHeight)
        } else {
            reusableBitmap!!
        }

        // Determine VR boundaries
        val vrLeft = if (imageOptions.vrMode == MjpegSettings.Default.VR_MODE_RIGHT) fullWidth / 2 else 0
        val vrRight = if (imageOptions.vrMode == MjpegSettings.Default.VR_MODE_LEFT) fullWidth / 2 else fullWidth

        // Determine user crop
        var left = (vrLeft + imageOptions.cropLeft).coerceIn(0, vrRight)
        var right = (vrRight - imageOptions.cropRight).coerceIn(left, fullWidth)
        var top = imageOptions.cropTop.coerceIn(0, fullHeight)
        var bottom = (fullHeight - imageOptions.cropBottom).coerceIn(top, fullHeight)

        var cropW = right - left
        var cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) { // Fallback to full region if invalid
            left = 0; top = 0; right = fullWidth; bottom = fullHeight
            cropW = fullWidth; cropH = fullHeight
        }

        if (transformMatrixDirty) {
            transformMatrix.reset()
            transformMatrix.postTranslate(-left.toFloat(), -top.toFloat()) // Move to (0,0)

            // Handle rotation if not 0
            if (imageOptions.rotationDegrees != MjpegSettings.Values.ROTATION_0) {
                transformMatrix.postRotate(imageOptions.rotationDegrees.toFloat())
                when (imageOptions.rotationDegrees) {
                    MjpegSettings.Values.ROTATION_90 -> transformMatrix.postTranslate(cropH.toFloat(), 0f)
                    MjpegSettings.Values.ROTATION_180 -> transformMatrix.postTranslate(cropW.toFloat(), cropH.toFloat())
                    MjpegSettings.Values.ROTATION_270 -> transformMatrix.postTranslate(0f, cropW.toFloat())
                }
            }

            // Handle resize factor
            if (imageOptions.resizeFactor != MjpegSettings.Values.RESIZE_DISABLED) {
                val scale = imageOptions.resizeFactor / 100f
                transformMatrix.postScale(scale, scale)
            }

            // Update paint's colorFilter if needed
            paint.colorFilter = if (imageOptions.grayscale) {
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            } else {
                null
            }

            transformMatrixDirty = false
        }

        // Determine final scaled size
        val rotate90or270 = imageOptions.rotationDegrees == MjpegSettings.Values.ROTATION_90 ||
                imageOptions.rotationDegrees == MjpegSettings.Values.ROTATION_270
        val finalW = if (rotate90or270) cropH else cropW
        val finalH = if (rotate90or270) cropW else cropH

        val scale = if (imageOptions.resizeFactor == MjpegSettings.Values.RESIZE_DISABLED) 1f
        else imageOptions.resizeFactor / 100f

        val scaledW = max(1, (finalW * scale).toInt())
        val scaledH = max(1, (finalH * scale).toInt())

        if (outputBitmap == null || outputBitmap!!.width != scaledW || outputBitmap!!.height != scaledH) {
            outputBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        }

        val skipTransform = transformMatrix.isIdentity && paint.colorFilter == null
        return if (skipTransform) {
            tmpBitmap.copy(tmpBitmap.config!!, false)
        } else {
            val canvas = Canvas(outputBitmap!!)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(tmpBitmap, transformMatrix, paint)

            outputBitmap!!.copy(outputBitmap!!.config!!, false)
        }
    }
}