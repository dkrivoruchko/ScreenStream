package info.dvkr.screenstream.data.image


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
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ImageGeneratorImpl(context: Context,
                         private val mediaProjection: MediaProjection,
                         eventScheduler: Scheduler,
                         private val eventBus: EventBus,
                         private val globalStatus: GlobalStatus,
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
    @Volatile private var jpegQuality = 80

    // Local data
    private val lock = Any()
    private val imageThread: HandlerThread
    private val imageThreadHandler: Handler
    private val windowManager: WindowManager
    private var imageReader: ImageReader
    private var virtualDisplay: VirtualDisplay?
    @Volatile private var reusableBitmap: Bitmap? = null
    private val resultJpegStream = ByteArrayOutputStream()
    @Volatile private var imageReaderState = STATE_CREATED
    @Volatile private var imageListener = 0

    init {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: Start")

        matrix.postScale(resizeFactor / 100f, resizeFactor / 100f)
        this.jpegQuality = jpegQuality

        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.ResizeFactor || it is EventBus.GlobalEvent.JpegQuality
        }.subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
                is EventBus.GlobalEvent.ResizeFactor -> {
                    val scale = globalEvent.value / 100f
                    matrix.reset()
                    matrix.postScale(scale, scale)
                }

                is EventBus.GlobalEvent.JpegQuality -> this.jpegQuality = globalEvent.value
            }
        }.also { subscriptions.add(it) }

        imageThread = HandlerThread("SSImageGenerator", Process.THREAD_PRIORITY_BACKGROUND)
        imageThread.start()
        imageThreadHandler = Handler(imageThread.looper)

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val defaultDisplay = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()
        defaultDisplay.getMetrics(displayMetrics)
        val screenSize = Point()
        defaultDisplay.getRealSize(screenSize)

        val listener = ImageListener()
        imageListener = listener.hashCode()
        imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(listener, imageThreadHandler)

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                    screenSize.x, screenSize.y, displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

            Observable.interval(250, TimeUnit.MILLISECONDS, eventScheduler)
                    .map { _ -> windowManager.defaultDisplay.rotation }
                    .map { rotation -> rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 }
                    .distinctUntilChanged()
                    .skip(1)
                    .filter { _ -> STATE_STARTED == imageReaderState }
                    .subscribe { _ -> restart() }
                    .also { subscriptions.add(it) }

            imageReaderState = STATE_STARTED
            globalStatus.isStreamRunning.set(true)
            eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: End")
            Crashlytics.log(1, TAG, "Init")
        } catch (ex: SecurityException) {
            imageReaderState = STATE_ERROR
            eventBus.sendEvent(EventBus.GlobalEvent.Error(ex))
            virtualDisplay = null
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, "Thread [${Thread.currentThread().name}] $ex")
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Stop")
            Crashlytics.log(1, TAG, "Stop")

            if (!(STATE_STARTED == imageReaderState || STATE_ERROR == imageReaderState))
                throw IllegalStateException("ImageGeneratorImpl in imageReaderState: $imageReaderState")

            subscriptions.clear()
            imageListener = 0
            mediaProjection.stop()

            virtualDisplay?.release()
            imageReader.close()
            reusableBitmap?.recycle()
            imageThread.quit()

            imageReaderState = STATE_STOPPED
            globalStatus.isStreamRunning.set(false)
            eventBus.sendEvent(EventBus.GlobalEvent.StreamStatus())
        }
    }

    private fun restart() {
        synchronized(lock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: Start")
            Crashlytics.log(1, TAG, "Restart")

            if (STATE_STARTED != imageReaderState)
                throw IllegalStateException("ImageGeneratorImpl in imageReaderState: $imageReaderState")

            virtualDisplay?.release()
            imageReader.close()
            reusableBitmap?.recycle()
            reusableBitmap = null

            val defaultDisplay = windowManager.defaultDisplay
            val displayMetrics = DisplayMetrics()
            defaultDisplay.getMetrics(displayMetrics)
            val screenSize = Point()
            defaultDisplay.getRealSize(screenSize)

            val listener = ImageListener()
            imageListener = listener.hashCode()
            imageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener(listener, imageThreadHandler)

            try {
                virtualDisplay = mediaProjection.createVirtualDisplay("SSVirtualDisplay",
                        screenSize.x, screenSize.y, displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

                if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: End")
            } catch (ex: SecurityException) {
                imageReaderState = STATE_ERROR
                eventBus.sendEvent(EventBus.GlobalEvent.Error(ex))
                virtualDisplay = null
                if (BuildConfig.DEBUG_MODE) Log.e(TAG, "Thread [${Thread.currentThread().name}] $ex")
            }
        }
    }

    // Runs on SSImageGenerator Thread
    private inner class ImageListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            synchronized(lock) {
                if (STATE_STARTED != imageReaderState || imageListener != this.hashCode()) return

                val image: Image?
                try {
                    image = reader.acquireLatestImage()
                } catch (exception: UnsupportedOperationException) {
                    imageReaderState = STATE_ERROR
                    eventBus.sendEvent(EventBus.GlobalEvent.Error(exception))
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
                if (matrix.isIdentity) {
                    resizedBitmap = cleanBitmap
                } else {
                    resizedBitmap = Bitmap.createBitmap(cleanBitmap, 0, 0, image.width, image.height, matrix, false)
                    cleanBitmap.recycle()
                }
                image.close()

                resultJpegStream.reset()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, resultJpegStream)
                resizedBitmap.recycle()
                onNewImage.call(resultJpegStream.toByteArray())
            }
        }
    }
}