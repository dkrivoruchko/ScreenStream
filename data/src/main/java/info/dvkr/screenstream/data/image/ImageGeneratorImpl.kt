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
import android.util.Log
import android.view.Display
import android.view.Surface
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.data.image.ImageGenerator.*
import rx.Observable
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImageGeneratorImpl private constructor(
        private val display: Display,
        private val mediaProjection: MediaProjection,
        private val scheduler: Scheduler,
        private val event: (event: ImageGenerator.ImageGeneratorEvent) -> Unit) : ImageGenerator {

    companion object : ImageGeneratorBuilder {
        @JvmStatic
        override fun create(display: Display,
                            mediaProjection: MediaProjection,
                            scheduler: Scheduler,
                            event: (event: ImageGeneratorEvent) -> Unit): ImageGenerator =
                ImageGeneratorImpl(display, mediaProjection, scheduler, event)

        const val STATE_INIT = "STATE_INIT"
        const val STATE_STARTED = "STATE_STARTED"
        const val STATE_STOPPED = "STATE_STOPPED"
        const val STATE_ERROR = "STATE_ERROR"
    }

    private val TAG = "ImageGeneratorImpl"

    private val resizeMatrix = AtomicReference<Matrix>(Matrix().also { it.postScale(.5f, .5f) })
    private val jpegQuality = AtomicInteger(80)

    private val state: AtomicReference<String> = AtomicReference(STATE_INIT)
    private val imageThread: HandlerThread by lazy { HandlerThread("ImageGenerator", THREAD_PRIORITY_BACKGROUND) }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }
    private val imageListener: AtomicReference<ImageListener?> = AtomicReference()
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay

    private val subscriptions = CompositeSubscription()

    init {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor")
        imageThread.start()
    }

    override fun setImageResizeFactor(factor: Int): ImageGenerator {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] setImageResizeFactor: $factor")
        if (factor !in 1..150) throw IllegalArgumentException("Resize factor has to be in range [1..150]")

        val scale = factor / 100f
        resizeMatrix.set(Matrix().also { it.postScale(scale, scale) })
        return this
    }

    override fun setImageJpegQuality(quality: Int): ImageGenerator {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] setImageJpegQuality: $quality")
        if (quality !in 1..100) throw IllegalArgumentException("JPEG quality has to be in range [1..100]")

        jpegQuality.set(quality)
        return this
    }

    @Synchronized
    override fun start() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Start")
        if (state.get() != STATE_INIT) throw IllegalStateException("Can't start ImageGenerator in state $state")

        startDisplayCapture()

        if (state.get() == STATE_STARTED)
            Observable.interval(250, TimeUnit.MILLISECONDS, scheduler)
                    .map { _ -> display.rotation }
                    .map { rotation -> rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 }
                    .distinctUntilChanged()
                    .skip(1)
                    .filter { _ -> state.get() == STATE_STARTED }
                    .subscribe { _ -> restart() }
                    .also { subscriptions.add(it) }
    }

    @Synchronized
    override fun stop() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Stop")
        if (state.get() != STATE_STARTED && state.get() != STATE_ERROR)
            throw IllegalStateException("Can't stop ImageGenerator in state $state")

        state.set(STATE_STOPPED)
        subscriptions.clear()
        stopDisplayCapture()
        imageThread.quit()
    }

    private fun sendEventOnScheduler(event: ImageGeneratorEvent) {
        Observable.just(event).observeOn(scheduler).subscribe { event(it) }
    }

    private fun startDisplayCapture() {
        val screenSize = Point().also { display.getRealSize(it) }
        imageListener.set(ImageListener())
        imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(imageListener.get(), imageThreadHandler)

        try {
            val densityDpi = DisplayMetrics().also { display.getMetrics(it) }.densityDpi
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                    screenSize.x, screenSize.y, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)
            state.set(STATE_STARTED)
        } catch (ex: SecurityException) {
            state.set(STATE_ERROR)
            sendEventOnScheduler(ImageGeneratorEvent.OnError(ex))
        }
    }

    private fun stopDisplayCapture() {
        if (::virtualDisplay.isInitialized) virtualDisplay.release()
        imageReader.close()
        imageListener.get()?.close()
    }

    @Synchronized
    private fun restart() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: Start")
        if (state.get() != STATE_STARTED) throw IllegalStateException("Can't restart ImageGenerator in state $state")

        stopDisplayCapture()
        startDisplayCapture()

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: End")
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

                val image: Image?
                try {
                    image = reader.acquireLatestImage()
                } catch (ex: UnsupportedOperationException) {
                    state.set(STATE_ERROR)
                    sendEventOnScheduler(ImageGeneratorEvent.OnError(ex))
                    return
                }
                if (null == image) return

                val plane = image.planes[0]
                val width = plane.rowStride / plane.pixelStride

                val cleanBitmap: Bitmap
                if (width > image.width) {
                    if (null == reusableBitmap) reusableBitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
                    reusableBitmap?.copyPixelsFromBuffer(plane.buffer)
                    cleanBitmap = Bitmap.createBitmap(reusableBitmap, 0, 0, image.width, image.height)
                } else {
                    cleanBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    cleanBitmap.copyPixelsFromBuffer(plane.buffer)
                }

                val resizedBitmap: Bitmap
                if (resizeMatrix.get().isIdentity) {
                    resizedBitmap = cleanBitmap
                } else {
                    resizedBitmap = Bitmap.createBitmap(cleanBitmap, 0, 0, image.width, image.height, resizeMatrix.get(), false)
                    cleanBitmap.recycle()
                }
                image.close()

                resultJpegStream.reset()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.get(), resultJpegStream)
                resizedBitmap.recycle()

                sendEventOnScheduler(ImageGeneratorEvent.OnJpegImage(resultJpegStream.toByteArray()))
            }
        }
    }
}