package info.dvkr.screenstream.data.image

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.android.grafika.gles.EglCore
import com.android.grafika.gles.FullFrameRect
import com.android.grafika.gles.OffscreenSurface
import com.android.grafika.gles.Texture2dProgram
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BitmapCapture(
    private val context: Context,
    private val settingsReadOnly: SettingsReadOnly,
    private val mediaProjection: MediaProjection,
    private val bitmapStateFlow: MutableStateFlow<Bitmap>,
    private val onError: (AppError) -> Unit
) {
    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    @Volatile
    private var state: State = State.INIT

    private val imageThread: HandlerThread by lazy {
        HandlerThread("BitmapCapture", Process.THREAD_PRIORITY_BACKGROUND)
    }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }

    private val display: Display by lazy {
        ContextCompat.getSystemService(context, DisplayManager::class.java)!!
            .getDisplay(Display.DEFAULT_DISPLAY)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun windowContext(): Context = context.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)

    private fun getDensityDpiCompat(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { display.getMetrics(it) }.densityDpi
        } else {
            windowContext().resources.configuration.densityDpi
        }

    private fun getScreenSizeCompat(): Point =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            Point().also { display.getRealSize(it) }
        } else {
            val bounds = windowContext().getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        }

    private var imageListener: ImageListener? = null
    private var imageReader: ImageReader? = null
    private var lastImageMillis: Long = 0L
    private var virtualDisplay: VirtualDisplay? = null

    private var fallback: Boolean = false
    private var fallbackFrameListener: FallbackFrameListener? = null
    private var mEglCore: EglCore? = null
    private var mProducerSide: Surface? = null
    private var mTexture: SurfaceTexture? = null
    private var mTextureId = 0
    private var mConsumerSide: OffscreenSurface? = null
    private var mScreen: FullFrameRect? = null
    private var mBuf: ByteBuffer? = null

    private val matrix = AtomicReference(Matrix())
    private val resizeFactor = AtomicInteger(Settings.Values.RESIZE_DISABLED)
    private val rotation = AtomicInteger(Settings.Values.ROTATION_0)

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            when (key) {
                Settings.Key.RESIZE_FACTOR, Settings.Key.ROTATION ->
                    setMatrix(settingsReadOnly.resizeFactor, settingsReadOnly.rotation)
            }
        }
    }

    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            XLog.d(this@BitmapCapture.getLog("ComponentCallbacks", "Configuration changed"))
            if (state == State.STARTED) restart()
        }

        override fun onLowMemory() = Unit
    }

    init {
        XLog.d(getLog("init"))
        settingsReadOnly.registerChangeListener(settingsListener)
        setMatrix(settingsReadOnly.resizeFactor, settingsReadOnly.rotation)
        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        state in requireStates ||
                throw IllegalStateException("BitmapCapture in state [$state] expected ${requireStates.contentToString()}")
    }

    @Synchronized
    private fun setMatrix(newResizeFactor: Int, newRotation: Int) {
        (newResizeFactor != resizeFactor.get() || newRotation != rotation.get()) || return
        resizeFactor.set(newResizeFactor)
        rotation.set(newRotation)

        val newMatrix = Matrix()

        if (newResizeFactor > Settings.Values.RESIZE_DISABLED)
            newMatrix.postScale(newResizeFactor / 100f, newResizeFactor / 100f)

        if (newRotation != Settings.Values.ROTATION_0)
            newMatrix.postRotate(newRotation.toFloat())

        matrix.set(newMatrix)
        if (state == State.STARTED) restart()
    }

    @Synchronized
    fun start() {
        XLog.d(getLog("start"))
        requireState(State.INIT)
        startDisplayCapture()
    }

    @Synchronized
    fun destroy() {
        XLog.d(getLog("destroy"))
        if (state == State.DESTROYED) {
            XLog.w(getLog("destroy", "Already destroyed"))
            return
        }
        requireState(State.STARTED, State.ERROR)
        state = State.DESTROYED
        stopDisplayCapture()
        imageThread.quit()
    }

    @SuppressLint("WrongConstant")
    private fun startDisplayCapture() {
        val screenSize = getScreenSizeCompat()

        val screenSizeX: Int
        val screenSizeY: Int
        if (resizeFactor.get() < Settings.Values.RESIZE_DISABLED) {
            val scale = resizeFactor.get() / 100f
            screenSizeX = (screenSize.x * scale).toInt()
            screenSizeY = (screenSize.y * scale).toInt()
        } else {
            screenSizeX = screenSize.x
            screenSizeY = screenSize.y
        }

        if (fallback.not()) {
            imageListener = ImageListener(settingsReadOnly)
            imageReader = ImageReader.newInstance(screenSizeX, screenSizeY, PixelFormat.RGBA_8888, 2)
                .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }
        } else {
            try {
                mEglCore = EglCore(null, EglCore.FLAG_TRY_GLES3 or EglCore.FLAG_RECORDABLE)
                mConsumerSide = OffscreenSurface(mEglCore, screenSizeX, screenSizeY)
                mConsumerSide!!.makeCurrent()

                fallbackFrameListener = FallbackFrameListener(settingsReadOnly, screenSizeX, screenSizeY)
                mBuf = ByteBuffer.allocate(screenSizeX * screenSizeY * 4).apply { order(ByteOrder.nativeOrder()) }
                mScreen = FullFrameRect(Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT))
                mTextureId = mScreen!!.createTextureObject()
                mTexture = SurfaceTexture(mTextureId, false).apply {
                    setDefaultBufferSize(screenSizeX, screenSizeY)
                    setOnFrameAvailableListener(fallbackFrameListener, imageThreadHandler)
                }
                mProducerSide = Surface(mTexture)

                mEglCore!!.makeNothingCurrent()
            } catch (cause: Throwable) {
                XLog.w(this@BitmapCapture.getLog("startDisplayCapture", cause.toString()))
                state = State.ERROR
                onError(FatalError.BitmapFormatException)
            }
        }

        try {
            val densityDpi = getDensityDpiCompat()
            virtualDisplay = if (fallback.not()) {
                mediaProjection.createVirtualDisplay(
                    "ScreenStreamVirtualDisplay", screenSizeX, screenSizeY, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, imageReader?.surface, null, imageThreadHandler
                )
            } else {
                mediaProjection.createVirtualDisplay(
                    "ScreenStreamVirtualDisplay", screenSizeX, screenSizeY, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, mProducerSide, null, imageThreadHandler
                )
            }
            state = State.STARTED
        } catch (ex: SecurityException) {
            state = State.ERROR
            XLog.w(getLog("startDisplayCapture", ex.toString()))
            onError(FixableError.CastSecurityException)
        }

        if (state == State.STARTED)
            context.registerComponentCallbacks(componentCallback)
    }

    private fun stopDisplayCapture() {
        context.unregisterComponentCallbacks(componentCallback)

        virtualDisplay?.release()
        imageReader?.close()

        mProducerSide?.release()
        mTexture?.release()
        mConsumerSide?.release()
        mEglCore?.release()
    }

    @Synchronized
    private fun restart() {
        XLog.d(getLog("restart", "Start"))
        if (state != State.STARTED) {
            XLog.d(getLog("restart", "Ignored"))
        } else {
            stopDisplayCapture()
            startDisplayCapture()
            XLog.d(getLog("restart", "End"))
        }
    }

    /** https://stackoverflow.com/a/34741581 **/
    private inner class FallbackFrameListener(
        private val settingsReadOnly: SettingsReadOnly, private val width: Int, private val height: Int
    ) : SurfaceTexture.OnFrameAvailableListener {
        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != fallbackFrameListener) return

                mConsumerSide!!.makeCurrent()
                mTexture!!.updateTexImage()

                val now = System.currentTimeMillis()
                ((now - lastImageMillis) >= (1000 / settingsReadOnly.maxFPS)) || return
                lastImageMillis = now

                FloatArray(16).let { matrix ->
                    mTexture!!.getTransformMatrix(matrix)
                    mScreen!!.drawFrame(mTextureId, matrix)
                }

                mConsumerSide!!.swapBuffers()

                mBuf!!.rewind()
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mBuf)
                mBuf!!.rewind()
                val cleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                cleanBitmap.copyPixelsFromBuffer(mBuf)

                val croppedBitmap = getCroppedBitmap(cleanBitmap)
                val grayScaleBitmap = getGrayScaleBitmap(croppedBitmap)
                val upsizedBitmap = getUpsizedAndRotatedBitmap(grayScaleBitmap)

                bitmapStateFlow.tryEmit(upsizedBitmap)
            }
        }
    }

    private inner class ImageListener(
        private val settingsReadOnly: SettingsReadOnly,
    ) : ImageReader.OnImageAvailableListener {

        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != imageListener || fallback) return

                try {
                    reader.acquireLatestImage()?.let { image ->
                        val now = System.currentTimeMillis()
                        if ((now - lastImageMillis) < (1000 / settingsReadOnly.maxFPS)) {
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
                } catch (ex: UnsupportedOperationException) {
                    XLog.d("unsupported image format, switching to fallback image reader")
                    fallback = true
                    restart()
                } catch (throwable: Throwable) {
                    XLog.e(this@BitmapCapture.getLog("outBitmapChannel"), throwable)
                    state = State.ERROR
                    onError(FatalError.BitmapCaptureException)
                }
            }
        }

        private lateinit var reusableBitmap: Bitmap

        private fun getCleanBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val width = plane.rowStride / plane.pixelStride
            val cleanBitmap: Bitmap

            if (width > image.width) {
                if (::reusableBitmap.isInitialized.not()) {
                    reusableBitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
                }

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
        if (settingsReadOnly.vrMode == Settings.Default.VR_MODE_DISABLE && settingsReadOnly.imageCrop.not())
            return bitmap

        var imageLeft: Int = 0
        var imageRight: Int = bitmap.width

        when (settingsReadOnly.vrMode) {
            Settings.Default.VR_MODE_LEFT -> imageRight = bitmap.width / 2
            Settings.Default.VR_MODE_RIGHT -> imageLeft = bitmap.width / 2
        }

        var imageCropLeft: Int = 0
        var imageCropRight = 0
        var imageCropTop: Int = 0
        var imageCropBottom: Int = 0
        if (settingsReadOnly.imageCrop)
            when {
                resizeFactor.get() < Settings.Values.RESIZE_DISABLED -> {
                    val scale = resizeFactor.get() / 100f
                    imageCropLeft = (settingsReadOnly.imageCropLeft * scale).toInt()
                    imageCropRight = (settingsReadOnly.imageCropRight * scale).toInt()
                    imageCropTop = (settingsReadOnly.imageCropTop * scale).toInt()
                    imageCropBottom = (settingsReadOnly.imageCropBottom * scale).toInt()
                }
                else -> {
                    imageCropLeft = settingsReadOnly.imageCropLeft
                    imageCropRight = settingsReadOnly.imageCropRight
                    imageCropTop = settingsReadOnly.imageCropTop
                    imageCropBottom = settingsReadOnly.imageCropBottom
                }
            }

        if (imageLeft + imageRight - imageCropLeft - imageCropRight <= 0 ||
            bitmap.height - imageCropTop - imageCropBottom <= 0
        )
            return bitmap

        return try {
            Bitmap.createBitmap(
                bitmap, imageLeft + imageCropLeft, imageCropTop,
                imageRight - imageLeft - imageCropLeft - imageCropRight,
                bitmap.height - imageCropTop - imageCropBottom
            )
        } catch (ex: IllegalArgumentException) {
            XLog.w(this@BitmapCapture.getLog("getCroppedBitmap", ex.toString()))
            bitmap
        }
    }

    private fun getUpsizedAndRotatedBitmap(bitmap: Bitmap): Bitmap {
        if (matrix.get().isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix.get(), false)
    }

    private fun getGrayScaleBitmap(bitmap: Bitmap): Bitmap {
        if (settingsReadOnly.imageGrayscale == Settings.Default.IMAGE_GRAYSCALE) return bitmap

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }

        val bmpGrayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(bmpGrayscale).apply { drawBitmap(bitmap, 0f, 0f, paint) }

        return bmpGrayscale
    }
}