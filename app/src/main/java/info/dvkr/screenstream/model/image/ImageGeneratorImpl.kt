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
                         private val mMediaProjection: MediaProjection,
                         mEventScheduler: Scheduler,
                         private val mEventBus: EventBus,
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
    private val mSubscriptions = CompositeSubscription()
    private val mMatrix = Matrix()
    private val mJpegQuality = AtomicInteger()

    //
    private val mImageThread: HandlerThread
    private val mImageThreadHandler: Handler

    private val mWindowManager: WindowManager
    private val mCurrentImageReaderListener = AtomicInteger()
    private var mImageReader: ImageReader
    private var mVirtualDisplay: VirtualDisplay
    private var mReusableBitmap: Bitmap? = null
    private val mResultJpegStream = ByteArrayOutputStream()

    private val mLock = Any()
    private val mState = AtomicReference(STATE_CREATED)

    init {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: Start")

        mMatrix.postScale(resizeFactor / 100f, resizeFactor / 100f)
        mJpegQuality.set(jpegQuality)

        mSubscriptions.add(mEventBus.getEvent().observeOn(mEventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: ${globalEvent.javaClass.simpleName}")

            when (globalEvent) {
                is EventBus.GlobalEvent.ResizeFactor -> {
                    val scale = globalEvent.value / 100f
                    mMatrix.reset()
                    mMatrix.postScale(scale, scale)
                }

                is EventBus.GlobalEvent.JpegQuality -> mJpegQuality.set(globalEvent.value)
            }
        })

        mImageThread = HandlerThread("SSImageGenerator", Process.THREAD_PRIORITY_BACKGROUND)
        mImageThread.start()
        mImageThreadHandler = Handler(mImageThread.looper)

        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val defaultDisplay = mWindowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()
        defaultDisplay.getMetrics(displayMetrics)
        val screenSize = Point()
        defaultDisplay.getRealSize(screenSize)

        val listener = ImageAvailableListener()
        mCurrentImageReaderListener.set(listener.hashCode())
        mImageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
        mImageReader.setOnImageAvailableListener(listener, mImageThreadHandler)

        mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                screenSize.x, screenSize.y, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mImageReader.surface, null, null)

        mSubscriptions.add(Observable.interval(250, TimeUnit.MILLISECONDS, mEventScheduler)
                .map { _ -> mWindowManager.defaultDisplay.rotation }
                .map { rotation -> rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 }
                .distinctUntilChanged()
                .skip(1)
                .filter { _ -> STATE_STARTED == mState.get() }
                .subscribe { _ -> restart() }
        )

        mEventBus.sendEvent(EventBus.GlobalEvent.StreamStatus(true))
        mState.set(STATE_STARTED)

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor: End")
    }

    override fun stop() {
        synchronized(mLock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Stop")

            if (!(STATE_STARTED == mState.get() || STATE_ERROR == mState.get()))
                throw IllegalStateException("ImageGeneratorImpl in state: ${mState.get()}")

            mSubscriptions.clear()
            mCurrentImageReaderListener.set(0)
            mMediaProjection.stop()

            mVirtualDisplay.release()
            mImageReader.close()
            mReusableBitmap?.recycle()
            mImageThread.quit()

            mEventBus.sendEvent(EventBus.GlobalEvent.StreamStatus(false))
            mState.set(STATE_STOPPED)
        }
    }

    private fun restart() {
        synchronized(mLock) {
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: Start")

            if (STATE_STARTED != mState.get())
                throw IllegalStateException("ImageGeneratorImpl in state: ${mState.get()}")

            mVirtualDisplay.release()
            mImageReader.close()
            mReusableBitmap?.recycle()
            mReusableBitmap = null

            val defaultDisplay = mWindowManager.defaultDisplay
            val displayMetrics = DisplayMetrics()
            defaultDisplay.getMetrics(displayMetrics)
            val screenSize = Point()
            defaultDisplay.getRealSize(screenSize)

            val listener = ImageAvailableListener()
            mCurrentImageReaderListener.set(listener.hashCode())
            mImageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2)
            mImageReader.setOnImageAvailableListener(listener, mImageThreadHandler)

            mVirtualDisplay = mMediaProjection.createVirtualDisplay("SSVirtualDisplay",
                    screenSize.x, screenSize.y, displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mImageReader.surface, null, null)

            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Restart: End")
        }
    }

    // Runs on ImageGeneratorImpl Thread
    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            synchronized(mLock) {
                if (STATE_STARTED != mState.get()) {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "Thread [${Thread.currentThread().name}] onImageAvailable: Error: WRONG READER STATE: ${mState.get()}")
                    return
                }

                if (mCurrentImageReaderListener.get() != this.hashCode()) {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "Thread [${Thread.currentThread().name}] onImageAvailable: Error: OLD LISTENER")
                    return
                }

                val image: Image?
                try {
                    image = reader.acquireLatestImage()
                } catch (ex: UnsupportedOperationException) {
                    mState.set(STATE_ERROR)
//                    mAppEvent.sendEvent(AppStatus.Event.AppStatus(ImageGenerator.IMAGE_GENERATOR_ERROR_WRONG_IMAGE_FORMAT))
                    Crashlytics.logException(ex)
                    return
                }
                if (null == image) return

                val plane = image.planes[0]
                val width = plane.rowStride / plane.pixelStride

                val cleanBitmap: Bitmap
                if (width > image.width) {
                    if (null == mReusableBitmap) mReusableBitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
                    mReusableBitmap?.copyPixelsFromBuffer(plane.buffer)
                    cleanBitmap = Bitmap.createBitmap(mReusableBitmap, 0, 0, image.width, image.height)
                } else {
                    cleanBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    cleanBitmap.copyPixelsFromBuffer(plane.buffer)
                }

                val resizedBitmap: Bitmap
                if (mMatrix.isIdentity) {
                    resizedBitmap = cleanBitmap
                } else {
                    resizedBitmap = Bitmap.createBitmap(cleanBitmap, 0, 0, image.width, image.height, mMatrix, false)
                    cleanBitmap.recycle()
                }
                image.close()

                mResultJpegStream.reset()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, mJpegQuality.get(), mResultJpegStream)
                resizedBitmap.recycle()
                onNewImage.call(mResultJpegStream.toByteArray())
            }
        }
    }
}