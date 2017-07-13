package info.dvkr.screenstream.model.image


import android.content.Context
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
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.ImageGenerator
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImageGeneratorImpl(context: Context,
                         private val mediaProjection: MediaProjection,
                         eventScheduler: Scheduler,
                         private val eventBus: EventBus,
                         resizeFactor: Int,
                         jpegQuality: Int,
                         private val onNewImage: Action1<ByteArray>) : ImageGenerator {

    private val TAG = "ImageGeneratorImpl"

    companion object {
        private const val STATE_CREATED = "STATE_CREATED"
        private const val STATE_STARTED = "STATE_STARTED"
        private const val STATE_STOPPED = "STATE_STOPPED"
        private const val STATE_ERROR = "STATE_ERROR"
    }

    // Settings
    private val subscriptions = CompositeSubscription()
    private val matrix = Matrix()
    private val jpegQuality = AtomicInteger()

    // Local data
    private val imageThread: HandlerThread
    private val imageThreadHandler: Handler

    private val windowManager: WindowManager
    private var imageReader: ImageReader
    private var virtualDisplay: VirtualDisplay
    private val reusableBitmap = AtomicReference<Bitmap?>(null)
    private val resultJpegStream = ByteArrayOutputStream()

    private val lock = Any()
    private val imageReaderState = AtomicReference(STATE_CREATED)
    private val currentImageReaderListener = AtomicInteger(0)

    init {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: Start")

        matrix.postScale(resizeFactor / 100f, resizeFactor / 100f)
        this.jpegQuality.set(jpegQuality)

        subscriptions.add(eventBus.getEvent().observeOn(eventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
                is EventBus.GlobalEvent.ResizeFactor -> {
                    val scale = globalEvent.value / 100f
                    matrix.reset()
                    matrix.postScale(scale, scale)
                }

                is EventBus.GlobalEvent.JpegQuality -> this.jpegQuality.set(globalEvent.value)
            }
        })

        imageThread = HandlerThread("SSImageGenerator", Process.THREAD_PRIORITY_BACKGROUND)
        imageThread.start()
        imageThreadHandler = Handler(imageThread.looper)

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val defaultDisplay = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()
        defaultDisplay.getMetrics(displayMetrics)
        val screenSize = Point()
        defaultDisplay.getRealSize(screenSize)

        val listener = ImageAvailableListener()
        currentImageReaderListener.set(listener.hashCode())
        imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(listener, imageThreadHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                screenSize.x, screenSize.y, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        subscriptions.add(Observable.interval(250, TimeUnit.MILLISECONDS)
                .map { _ -> windowManager.defaultDisplay.rotation }
                .map { rotation -> rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 }
                .distinctUntilChanged()
                .skip(1)
                .filter { _ -> STATE_STARTED == imageReaderState.get() }
                .subscribe { _ -> restart() }
        )

        imageReaderState.set(STATE_STARTED)
        eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus(true))

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: End")
    }

    override fun stop() {
        synchronized(lock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Stop")

            if (!(STATE_STARTED == imageReaderState.get() || STATE_ERROR == imageReaderState.get()))
                throw IllegalStateException("ImageGeneratorImpl in imageReaderState: ${imageReaderState.get()}")

            subscriptions.clear()
            currentImageReaderListener.set(0)
            mediaProjection.stop()

            virtualDisplay.release()
            imageReader.close()
            reusableBitmap.get()?.recycle()
            imageThread.quit()

            imageReaderState.set(STATE_STOPPED)
            eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus(false))
        }
    }

    private fun restart() {
        synchronized(lock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: Start")

            if (STATE_STARTED != imageReaderState.get())
                throw IllegalStateException("ImageGeneratorImpl in imageReaderState: ${imageReaderState.get()}")

            virtualDisplay.release()
            imageReader.close()
            reusableBitmap.get()?.recycle()
            reusableBitmap.set(null)

            val defaultDisplay = windowManager.defaultDisplay
            val displayMetrics = DisplayMetrics()
            defaultDisplay.getMetrics(displayMetrics)
            val screenSize = Point()
            defaultDisplay.getRealSize(screenSize)

            val listener = ImageAvailableListener()
            currentImageReaderListener.set(listener.hashCode())
            imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener(listener, imageThreadHandler)

            virtualDisplay = mediaProjection.createVirtualDisplay("SSVirtualDisplay",
                    screenSize.x, screenSize.y, displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: End")
        }
    }

    // Runs on ImageGeneratorImpl Thread
    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            synchronized(lock) {
                if (STATE_STARTED != imageReaderState.get() || currentImageReaderListener.get() != this.hashCode()) return

                val image: Image?
                try {
                    image = reader.acquireLatestImage()
                } catch (ex: UnsupportedOperationException) {
                    imageReaderState.set(STATE_ERROR)
//                    mAppEvent.sendEvent(AppStatus.Event.AppStatus(ImageGenerator.IMAGE_GENERATOR_ERROR_WRONG_IMAGE_FORMAT))
                    Crashlytics.logException(ex)
                    return
                }
                if (null == image) return

                val plane = image.planes[0]
                val width = plane.rowStride / plane.pixelStride

                val cleanBitmap: Bitmap
                if (width > image.width) {
                    if (null == reusableBitmap.get()) reusableBitmap.set(Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888))
                    reusableBitmap.get()?.copyPixelsFromBuffer(plane.buffer)
                    cleanBitmap = Bitmap.createBitmap(reusableBitmap.get(), 0, 0, image.width, image.height)
                } else {
                    cleanBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    cleanBitmap.copyPixelsFromBuffer(plane.buffer)
                }

                val resizedBitmap: Bitmap
                if (matrix.isIdentity) {
                    resizedBitmap = cleanBitmap
                } else {
                    resizedBitmap = Bitmap.createBitmap(cleanBitmap, 0, 0, image.width, image.height, matrix, false)
                    cleanBitmap.recycle()
                }
                image.close()

                resultJpegStream.reset()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.get(), resultJpegStream)
                resizedBitmap.recycle()
                onNewImage.call(resultJpegStream.toByteArray())
            }
        }
    }
}