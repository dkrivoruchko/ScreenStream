package info.dvkr.screenstream.data;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import com.google.firebase.crash.FirebaseCrash;

import org.greenrobot.eventbus.EventBus;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import info.dvkr.screenstream.data.local.PreferencesHelper;
import info.dvkr.screenstream.service.ForegroundService;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppPreference;

public final class ImageGenerator {
    private static final String TAG = ImageGenerator.class.getSimpleName();
    private final Object mLock = new Object();
    private final AtomicBoolean isPrepared = new AtomicBoolean();

    private HandlerThread mImageThread;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private Bitmap mReusableBitmap;

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        private final ByteArrayOutputStream mJpegOutputStream = new ByteArrayOutputStream();
        private final Matrix mMatrix;

        ImageAvailableListener(final Matrix matrix) {
            this.mMatrix = matrix;
        }

        @Override
        public void onImageAvailable(final ImageReader reader) {
            synchronized (mLock) {
//                Log.e(TAG, "ImageGenerator.onImageAvailable Tread: " + Thread.currentThread().getName());

                if (!isPrepared.get()) return;

                final Image image;
                try {
                    image = reader.acquireLatestImage();
                } catch (UnsupportedOperationException e) { // TODO use onError
                    EventBus.getDefault().postSticky(new BusMessages(BusMessages.MESSAGE_STATUS_IMAGE_GENERATOR_ERROR));
                    FirebaseCrash.report(e);
                    return;
                }
                if (image == null) return;

                final Image.Plane plane = image.getPlanes()[0];
                final int width = plane.getRowStride() / plane.getPixelStride();

                final Bitmap cleanBitmap;
                if (width > image.getWidth()) {
                    if (mReusableBitmap == null) {
                        mReusableBitmap = Bitmap.createBitmap(width, image.getHeight(), Bitmap.Config.ARGB_8888);
                    }
                    mReusableBitmap.copyPixelsFromBuffer(plane.getBuffer());
                    cleanBitmap = Bitmap.createBitmap(mReusableBitmap, 0, 0, image.getWidth(), image.getHeight());
                } else {
                    cleanBitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                    cleanBitmap.copyPixelsFromBuffer(plane.getBuffer());
                }

                final Bitmap resizedBitmap;
                if (mMatrix.isIdentity()) {
                    resizedBitmap = cleanBitmap;
                } else {
                    resizedBitmap = Bitmap.createBitmap(cleanBitmap, 0, 0, image.getWidth(), image.getHeight(), mMatrix, false);
                    cleanBitmap.recycle();
                }
                image.close();

                mJpegOutputStream.reset();
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, getAppPreference().getJpegQuality(), mJpegOutputStream);
                resizedBitmap.recycle();
                final byte[] jpegByteArray = mJpegOutputStream.toByteArray();

                if (jpegByteArray != null) { // TODO use onNext
                    if (getAppData().getImageQueue().size() > 3) {
                        getAppData().getImageQueue().pollLast();
                    }
                    getAppData().getImageQueue().add(jpegByteArray);
                }
            }
        }
    }

    public void start() throws IllegalStateException {
        synchronized (mLock) {
            Log.e(TAG, "ImageGenerator start: " + Thread.currentThread().getName());

            if (isPrepared.get())
                throw new IllegalStateException("ImageGenerator is already running");

            final MediaProjection mediaProjection = ForegroundService.getMediaProjection();
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection is null");

            final Matrix matrix = new Matrix();
            if (getAppPreference().getResizeFactor() != PreferencesHelper.DEFAULT_RESIZE_FACTOR) {
                final float scale = getAppPreference().getResizeFactor() / 10f;
                matrix.postScale(scale, scale);
            }

            mImageThread = new HandlerThread(ImageGenerator.class.getSimpleName(), Process.THREAD_PRIORITY_MORE_FAVORABLE);
            mImageThread.start();

            mImageReader = ImageReader.newInstance(getAppData().getScreenSize().x, getAppData().getScreenSize().y, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(new ImageAvailableListener(matrix), new Handler(mImageThread.getLooper()));
            mVirtualDisplay = mediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                    getAppData().getScreenSize().x, getAppData().getScreenSize().y, getAppData().getScreenDensity(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(),
                    null, null);

            isPrepared.set(true);
        }
    }

    public void stop() throws IllegalStateException {
        synchronized (mLock) {
            Log.e(TAG, "ImageGenerator stop: " + Thread.currentThread().getName());

//            final RefWatcher refWatcher = getRafWatcher();
//            refWatcher.watch(mImageReader);

            if (!isPrepared.get()) throw new IllegalStateException("ImageGenerator is not running");
            isPrepared.set(false);

            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }

            if (mImageReader != null) {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
                mImageReader = null;
            }

            if (mImageThread != null) {
                mImageThread.quit();
                mImageThread = null;
            }

            if (mReusableBitmap != null) {
                mReusableBitmap.recycle();
                mReusableBitmap = null;
            }
        }
    }
}