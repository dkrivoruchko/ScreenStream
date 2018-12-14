package info.dvkr.screenstream.data.image

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BitmapCapture constructor(
    private val display: Display,
    private val settingsReadOnly: SettingsReadOnly,
    private val mediaProjection: MediaProjection,
    private val outBitmapChannel: SendChannel<Bitmap>,
    onError: (AppError) -> Unit
) : AbstractImageHandler(onError) {

    private enum class State {
        INIT, STARTED, STOPPED, ERROR
    }

    private var state: State = State.INIT

    private val imageThread: HandlerThread by lazy {
        HandlerThread("BitmapCapture", Process.THREAD_PRIORITY_BACKGROUND)
    }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }

    private var imageListener: ImageListener? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var rotationDetector: Deferred<Unit>

    private val resizeMatrix = AtomicReference<Matrix>(Matrix())
    private val resizeFactor = AtomicInteger(100)

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key == Settings.Key.RESIZE_FACTOR) {
                Timber.tag(this@BitmapCapture.getTag("onSettingsChanged"))
                    .d("resizeFactor: ${settingsReadOnly.resizeFactor}")
                setResizeFactor(settingsReadOnly.resizeFactor)
            }
        }
    }

    init {
        Timber.tag(getTag("Init")).d("Invoked")
        settingsReadOnly.registerChangeListener(settingsListener)
        setResizeFactor(settingsReadOnly.resizeFactor)
        imageThread.start()
    }

    private fun requireState(vararg requireStates: State) {
        state in requireStates ||
                throw IllegalStateException("BitmapCapture in state [$state] expected ${requireStates.contentToString()}")
    }

    @Synchronized
    private fun setResizeFactor(newResizeFactor: Int) {
        if (newResizeFactor != resizeFactor.get()) {
            resizeFactor.set(newResizeFactor)
            val scale = newResizeFactor / 100f
            resizeMatrix.set(Matrix().also { it.postScale(scale, scale) })
            if (state == State.STARTED) restart()
        }
    }

    @Synchronized
    override fun start() {
        Timber.tag(getTag("Start")).d("Invoked")
        requireState(State.INIT)

        startDisplayCapture()

        if (state == State.STARTED)
            launch {
                val rotation = display.rotation
                var previous = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
                var current: Boolean

                Timber.tag(this@BitmapCapture.getTag("Start")).d("Rotation detector started")

                while (isActive) {
                    delay(250)
                    val newRotation = display.rotation
                    current = newRotation == Surface.ROTATION_0 || newRotation == Surface.ROTATION_180
                    if (previous != current) {
                        Timber.tag(this@BitmapCapture.getTag("Start")).d("Rotation detected")
                        previous = current
                        if (state == State.STARTED) restart()
                    }
                }
            }
    }

    @Synchronized
    override fun stop() {
        Timber.tag(getTag("Stop")).d("Invoked")
        requireState(State.STARTED, State.ERROR)

        state = State.STOPPED
        settingsReadOnly.unregisterChangeListener(settingsListener)

        super.stop()

        if (::rotationDetector.isInitialized) rotationDetector.cancel()
        stopDisplayCapture()
        imageThread.quit()
    }

    private fun startDisplayCapture() {
        val screenSize = Point().also { display.getRealSize(it) }
        imageListener = ImageListener()

        val screenSizeX: Int
        val screenSizeY: Int
        if (resizeFactor.get() < 100) {
            val scale = resizeFactor.get() / 100f
            screenSizeX = (screenSize.x * scale).toInt()
            screenSizeY = (screenSize.y * scale).toInt()
        } else {
            screenSizeX = screenSize.x
            screenSizeY = screenSize.y
        }

        imageReader = ImageReader.newInstance(screenSizeX, screenSizeY, PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener, imageThreadHandler) }

        try {
            val densityDpi = DisplayMetrics().also { display.getMetrics(it) }.densityDpi
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStreamVirtualDisplay", screenSizeX, screenSizeY, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, imageReader?.surface, null, imageThreadHandler
            )
            state = State.STARTED
        } catch (ex: SecurityException) {
            state = State.ERROR
            Timber.tag(this.getTag("startDisplayCapture")).e(ex)
            onError(FixableError.CastSecurityException)
        }
    }

    private fun stopDisplayCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        imageListener?.close()
    }

    @Synchronized
    private fun restart() {
        Timber.tag(getTag("Restart")).d("Start")
        requireState(State.STARTED)

        stopDisplayCapture()
        startDisplayCapture()

        Timber.tag(getTag("Restart")).d("End")
    }

    // Runs on imageThread
    private inner class ImageListener : ImageReader.OnImageAvailableListener {
        private lateinit var reusableBitmap: Bitmap

        internal fun close() {
            if (::reusableBitmap.isInitialized) reusableBitmap.recycle()
        }

        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@BitmapCapture) {
                if (state != State.STARTED || this != imageListener) return

                try {
                    reader.acquireLatestImage()?.let { image ->
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

                        val resizedBitmap: Bitmap
                        if (resizeFactor.get() > 100) {
                            resizedBitmap = Bitmap.createBitmap(
                                cleanBitmap, 0, 0, image.width, image.height, resizeMatrix.get(), false
                            )
                            cleanBitmap.recycle()
                        } else {
                            resizedBitmap = cleanBitmap
                        }

                        image.close()
                        if (outBitmapChannel.isClosedForSend.not()) outBitmapChannel.offer(resizedBitmap)

                    }
                } catch (ex: UnsupportedOperationException) {
                    Timber.tag(this@BitmapCapture.getTag("outBitmapChannel")).e(ex)
                    state = State.ERROR
                    onError(FatalError.BitmapFormatException)
                } catch (throwable: Throwable) {
                    Timber.tag(this@BitmapCapture.getTag("outBitmapChannel")).e(throwable)
                    state = State.ERROR
                    onError(FatalError.BitmapCaptureException)
                }
            }
        }
    }
}