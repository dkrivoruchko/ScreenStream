package info.dvkr.screenstream.data.image


import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import info.dvkr.screenstream.data.image.ImageGenerator.ImageGeneratorEvent
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImageGeneratorImpl(
    private val display: Display,
    private val mediaProjection: MediaProjection,
    private val action: (ImageGeneratorEvent) -> Unit
) : ImageGenerator {

    companion object {
        private const val STATE_INIT = "STATE_INIT"
        private const val STATE_STARTED = "STATE_STARTED"
        private const val STATE_STOPPED = "STATE_STOPPED"
        private const val STATE_ERROR = "STATE_ERROR"
    }

    private val resizeMatrix = AtomicReference<Matrix>(Matrix().also { it.postScale(.5f, .5f) })
    private val jpegQuality = AtomicInteger(80)

    private val state: AtomicReference<String> = AtomicReference(STATE_INIT)
    private lateinit var rotationDetector: Deferred<Unit>
    private val imageThread: HandlerThread by lazy {
        HandlerThread("ImageGenerator", THREAD_PRIORITY_BACKGROUND)
    }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }
    private val imageListener: AtomicReference<ImageListener?> = AtomicReference()
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    init {
        Timber.i("[${Utils.getLogPrefix(this)}] Init")
        imageThread.start()
    }

    override fun setImageResizeFactor(factor: Int) {
        Timber.i("[${Utils.getLogPrefix(this)}] setImageResizeFactor: $factor")
        factor in 1..150 ||
                throw IllegalArgumentException("Resize factor has to be in range [1..150]")

        val scale = factor / 100f
        resizeMatrix.set(Matrix().also { it.postScale(scale, scale) })
    }

    override fun setImageJpegQuality(quality: Int) {
        Timber.i("[${Utils.getLogPrefix(this)}] setImageJpegQuality: $quality")
        quality in 1..100 ||
                throw IllegalArgumentException("JPEG quality has to be in range [1..100]")

        jpegQuality.set(quality)
    }

    @Synchronized
    override fun start() {
        Timber.i("[${Utils.getLogPrefix(this)}] Start")
        state.get() == STATE_INIT ||
                throw IllegalStateException("Can't start ImageGenerator in state $state")

        startDisplayCapture()

        if (state.get() == STATE_STARTED)
            rotationDetector = async {
                val rotation = display.rotation
                var previous = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
                var current: Boolean
                Timber.i("[${Utils.getLogPrefix(this)}] Rotation detector started")
                while (true) {
                    delay(250, TimeUnit.MILLISECONDS)
                    val oldRotation = display.rotation
                    current = oldRotation == Surface.ROTATION_0 || oldRotation ==
                            Surface.ROTATION_180
                    if (previous != current) {
                        Timber.i("[${Utils.getLogPrefix(this)}] Rotation detected")
                        previous = current
                        if (state.get() == STATE_STARTED) restart()
                    }
                }
            }
    }

    @Synchronized
    override fun stop() {
        Timber.i("[${Utils.getLogPrefix(this)}] Stop")
        state.get() == STATE_STARTED || state.get() == STATE_ERROR ||
                throw IllegalStateException("Can't stop ImageGenerator in state $state")

        state.set(STATE_STOPPED)
        if (::rotationDetector.isInitialized) rotationDetector.cancel()
        stopDisplayCapture()
        imageThread.quit()
    }

    private fun startDisplayCapture() {
        val screenSize = Point().also { display.getRealSize(it) }
        imageListener.set(ImageListener())
        imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
            .apply { setOnImageAvailableListener(imageListener.get(), imageThreadHandler) }

        try {
            val densityDpi = DisplayMetrics().also { display.getMetrics(it) }.densityDpi
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStreamVirtualDisplay",
                screenSize.x, screenSize.y, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                imageReader?.surface, null, imageThreadHandler
            )
            state.set(STATE_STARTED)
        } catch (ex: SecurityException) {
            state.set(STATE_ERROR)
            action(ImageGeneratorEvent.OnError(ex))
        }
    }

    private fun stopDisplayCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        imageListener.get()?.close()
    }

    @Synchronized
    private fun restart() {
        Timber.i("[${Utils.getLogPrefix(this)}] Restart: Start")
        state.get() == STATE_STARTED ||
                throw IllegalStateException("Can't restart ImageGenerator in state $state")

        stopDisplayCapture()
        startDisplayCapture()

        Timber.i("[${Utils.getLogPrefix(this)}] Restart: End")
    }

    // Runs on ImageGenerator Thread
    private inner class ImageListener : ImageReader.OnImageAvailableListener {
        private var reusableBitmap: Bitmap? = null
        private val resultJpegStream = ByteArrayOutputStream()

        internal fun close() {
            reusableBitmap?.recycle()
        }

        override fun onImageAvailable(reader: ImageReader) {
            synchronized(this@ImageGeneratorImpl) {
                if (state.get() != STATE_STARTED || this != imageListener.get()) return

                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                } catch (ex: UnsupportedOperationException) {
                    state.set(STATE_ERROR)
                    action(ImageGeneratorEvent.OnError(ex))
                    return
                } catch (ignore: IllegalStateException) {
                }

                if (image != null) {
                    val plane = image.planes[0]
                    val width = plane.rowStride / plane.pixelStride

                    val cleanBitmap: Bitmap
                    if (width > image.width) {
                        if (null == reusableBitmap) reusableBitmap =
                                Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)

                        reusableBitmap?.copyPixelsFromBuffer(plane.buffer)
                        cleanBitmap =
                                Bitmap.createBitmap(reusableBitmap, 0, 0, image.width, image.height)
                    } else {
                        cleanBitmap =
                                Bitmap.createBitmap(
                                    image.width,
                                    image.height,
                                    Bitmap.Config.ARGB_8888
                                )
                        cleanBitmap.copyPixelsFromBuffer(plane.buffer)
                    }

                    val resizedBitmap: Bitmap
                    if (resizeMatrix.get().isIdentity) {
                        resizedBitmap = cleanBitmap
                    } else {
                        resizedBitmap = Bitmap.createBitmap(
                            cleanBitmap, 0, 0,
                            image.width, image.height, resizeMatrix.get(), false
                        )
                        cleanBitmap.recycle()
                    }
                    image.close()

                    resultJpegStream.reset()
                    resizedBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        jpegQuality.get(),
                        resultJpegStream
                    )
                    resizedBitmap.recycle()

                    action(ImageGeneratorEvent.OnJpegImage(resultJpegStream.toByteArray()))
                }
            }
        }
    }
}