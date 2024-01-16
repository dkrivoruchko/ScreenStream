package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class BitmapCapture(
    private val context: Context,
    private val mjpegSettings: MjpegSettings,
    private val mediaProjection: MediaProjection,
    private val bitmapStateFlow: MutableStateFlow<Bitmap>,
    private val onError: (MjpegError) -> Unit
) {
    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    @Volatile
    private var state: State = State.INIT

    private val imageThread: HandlerThread by lazy { HandlerThread("BitmapCapture", Process.THREAD_PRIORITY_BACKGROUND) }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }
    private val display: Display by lazy {
        ContextCompat.getSystemService(context, DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun windowContext(): Context = context.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)

    @Suppress("DEPRECATION")
    private fun getDensityDpiCompat(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            DisplayMetrics().also { display.getMetrics(it) }.densityDpi
        } else {
            windowContext().resources.configuration.densityDpi
        }

    @Suppress("DEPRECATION")
    private fun getScreenSizeCompatResized(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Point().also { display.getRealSize(it) }
        } else {
            val bounds = windowContext().getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        }.let { point ->
            if (resizeFactor.get() < MjpegSettings.Values.RESIZE_DISABLED) {
                val scale = resizeFactor.get() / 100f
                Pair((point.x * scale).toInt(), (point.y * scale).toInt())
            } else {
                Pair(point.x, point.y)
            }
        }

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

        mjpegSettings.resizeFactorFlow.listenForChange(coroutineScope) { if (resizeFactor.getAndSet(it) != it) updateMatrix() }
        mjpegSettings.rotationFlow.listenForChange(coroutineScope) { if (rotation.getAndSet(it) != it) updateMatrix() }
        mjpegSettings.maxFPSFlow.listenForChange(coroutineScope) { maxFPS.set(it) }
        mjpegSettings.vrModeFlow.listenForChange(coroutineScope) { vrMode.set(it) }
        mjpegSettings.imageCropFlow.listenForChange(coroutineScope) { imageCrop.set(it) }
        mjpegSettings.imageCropTopFlow.listenForChange(coroutineScope) { imageCropTop.set(it) }
        mjpegSettings.imageCropBottomFlow.listenForChange(coroutineScope) { imageCropBottom.set(it) }
        mjpegSettings.imageCropLeftFlow.listenForChange(coroutineScope) { imageCropLeft.set(it) }
        mjpegSettings.imageCropRightFlow.listenForChange(coroutineScope) { imageCropRight.set(it) }
        mjpegSettings.imageGrayscaleFlow.listenForChange(coroutineScope) { imageGrayscale.set(it) }

        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        check(state in requireStates) { "BitmapCapture in state [$state] expected ${requireStates.contentToString()}" }
    }

    @Synchronized
    private fun updateMatrix() {
        XLog.d(getLog("updateMatrix"))
        val newMatrix = Matrix()

        if (resizeFactor.get() > MjpegSettings.Values.RESIZE_DISABLED)
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
        val (screenSizeX, screenSizeY) = getScreenSizeCompatResized()
        val densityDpi = getDensityDpiCompat()

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(screenSizeX, screenSizeY, PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStreamVirtualDisplay", screenSizeX, screenSizeY, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, imageReader!!.surface, null, imageThreadHandler
            )
            state = State.STARTED
        } catch (ex: SecurityException) {
            state = State.ERROR
            XLog.w(getLog("startDisplayCapture", ex.toString()), ex)
            onError(MjpegError.CastSecurityException)
        }

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
        XLog.d(getLog("resize", "Start"))

        if (state != State.STARTED) {
            XLog.d(getLog("resize", "Ignored"))
            return
        }

        imageReader?.surface?.release() // For some reason imageReader.close() does not release surface
        imageReader?.close()
        imageReader = null

        val (screenSizeX, screenSizeY) = getScreenSizeCompatResized()
        val densityDpi = getDensityDpiCompat()

        imageListener = ImageListener()
        imageReader = ImageReader.newInstance(screenSizeX, screenSizeY, PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }
        try {
            virtualDisplay?.resize(screenSizeX, screenSizeY, densityDpi)
            virtualDisplay?.surface = imageReader!!.surface
        } catch (ex: SecurityException) {
            state = State.ERROR
            XLog.w(getLog("resize", ex.toString()), ex)
            onError(MjpegError.CastSecurityException)
        }

        XLog.d(getLog("resize", "End"))
    }

    private inner class ImageListener : ImageReader.OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != imageListener) return

                try {
                    reader.acquireLatestImage()?.let { image ->
                        val now = System.currentTimeMillis()
                        if ((now - lastImageMillis) < (1000 / maxFPS.get())) {
                            image.close()
                            return
                        }
                        lastImageMillis = now

                        val cleanBitmap = getCleanBitmap(image)
                        val croppedBitmap = getCroppedBitmap(cleanBitmap)
                        val grayScaleBitmap = getGrayScaleBitmap(croppedBitmap)
                        val upsizedBitmap = getUpsizedAndRotatedBitmap(grayScaleBitmap)

                        image.close()
                        bitmapStateFlow.tryEmit(upsizedBitmap)
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
        if (vrMode.get() == MjpegSettings.Default.VR_MODE_DISABLE && imageCrop.get().not()) return bitmap

        var imageLeft = 0
        var imageRight = bitmap.width

        when (vrMode.get()) {
            MjpegSettings.Default.VR_MODE_LEFT -> imageRight = bitmap.width / 2
            MjpegSettings.Default.VR_MODE_RIGHT -> imageLeft = bitmap.width / 2
        }

        var imageCropLeftResult = 0
        var imageCropRightResult = 0
        var imageCropTopResult = 0
        var imageCropBottomResult = 0
        if (imageCrop.get())
            when {
                resizeFactor.get() < MjpegSettings.Values.RESIZE_DISABLED -> {
                    val scale = resizeFactor.get() / 100f
                    imageCropLeftResult = (imageCropLeft.get() * scale).toInt()
                    imageCropRightResult = (imageCropRight.get() * scale).toInt()
                    imageCropTopResult = (imageCropTop.get() * scale).toInt()
                    imageCropBottomResult = (imageCropBottom.get() * scale).toInt()
                }

                else -> {
                    imageCropLeftResult = imageCropLeft.get()
                    imageCropRightResult = imageCropRight.get()
                    imageCropTopResult = imageCropTop.get()
                    imageCropBottomResult = imageCropBottom.get()
                }
            }

        if (imageLeft + imageRight - imageCropLeftResult - imageCropRightResult <= 0 || bitmap.height - imageCropTopResult - imageCropBottomResult <= 0)
            return bitmap

        return try {
            Bitmap.createBitmap(
                bitmap, imageLeft + imageCropLeftResult, imageCropTopResult,
                imageRight - imageLeft - imageCropLeftResult - imageCropRightResult,
                bitmap.height - imageCropTopResult - imageCropBottomResult
            )
        } catch (ex: IllegalArgumentException) {
            XLog.w(this@BitmapCapture.getLog("getCroppedBitmap", ex.toString()), ex)
            bitmap
        }
    }

    private fun getUpsizedAndRotatedBitmap(bitmap: Bitmap): Bitmap {
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