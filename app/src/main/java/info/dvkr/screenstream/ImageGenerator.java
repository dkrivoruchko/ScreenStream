package info.dvkr.screenstream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.google.firebase.crash.FirebaseCrash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static info.dvkr.screenstream.AppContext.getAppSettings;
import static info.dvkr.screenstream.AppContext.getAppState;
import static info.dvkr.screenstream.AppContext.getScreenDensity;
import static info.dvkr.screenstream.AppContext.getScreenSize;

final class ImageGenerator {
    private final Object mLock = new Object();

    private volatile boolean isThreadRunning;

    private HandlerThread mImageThread;
    private Handler mImageHandler;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private Bitmap mReusableBitmap;
    private ByteArrayOutputStream mJpegOutputStream;

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        private Image mImage;
        private Image.Plane mPlane;
        private int mWidth;
        private Bitmap mCleanBitmap;
        private byte[] mJpegByteArray;

        @Override
        public void onImageAvailable(ImageReader reader) {
            synchronized (mLock) {
                if (!isThreadRunning) return;

                try {
                    mImage = mImageReader.acquireLatestImage();
                } catch (UnsupportedOperationException e) {
                    ForegroundService.errorInImageGenerator();
                    FirebaseCrash.report(e);
                    return;
                }

                if (mImage == null) return;

                mPlane = mImage.getPlanes()[0];
                mWidth = mPlane.getRowStride() / mPlane.getPixelStride();

                if (mWidth > mImage.getWidth()) {
                    if (mReusableBitmap == null) {
                        mReusableBitmap = Bitmap.createBitmap(mWidth, mImage.getHeight(), Bitmap.Config.ARGB_8888);
                    }
                    mReusableBitmap.copyPixelsFromBuffer(mPlane.getBuffer());
                    mCleanBitmap = Bitmap.createBitmap(mReusableBitmap, 0, 0, mImage.getWidth(), mImage.getHeight());
                } else {
                    mCleanBitmap = Bitmap.createBitmap(mWidth, mImage.getHeight(), Bitmap.Config.ARGB_8888);
                    mCleanBitmap.copyPixelsFromBuffer(mPlane.getBuffer());
                }
                mImage.close();

                mJpegOutputStream.reset();
                mCleanBitmap.compress(Bitmap.CompressFormat.JPEG, getAppSettings().getJpegQuality(), mJpegOutputStream);
                mCleanBitmap.recycle();
                mJpegByteArray = mJpegOutputStream.toByteArray();

                if (mJpegByteArray != null) {
                    if (getAppState().mJPEGQueue.size() > 6) {
                        getAppState().mJPEGQueue.pollLast();
                    }
                    getAppState().mJPEGQueue.add(mJpegByteArray);
                    mJpegByteArray = null;
                }
            }
        }
    }

    void start() {
        synchronized (mLock) {
            if (isThreadRunning) return;
            final MediaProjection mediaProjection = ForegroundService.getMediaProjection();
            if (mediaProjection == null) return;

            mImageThread = new HandlerThread(ImageGenerator.class.getSimpleName(),
                    Process.THREAD_PRIORITY_MORE_FAVORABLE);

            mImageThread.start();
            mImageReader = ImageReader.newInstance(getScreenSize().x,
                    getScreenSize().y,
                    PixelFormat.RGBA_8888, 2);

            mImageHandler = new Handler(mImageThread.getLooper());
            mJpegOutputStream = new ByteArrayOutputStream();
            mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mImageHandler);
            mVirtualDisplay = mediaProjection.createVirtualDisplay("ScreenStreamVirtualDisplay",
                    getScreenSize().x,
                    getScreenSize().y,
                    getScreenDensity(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(),
                    null, mImageHandler);

            isThreadRunning = true;
        }
    }

    void stop() {
        synchronized (mLock) {
            if (!isThreadRunning) return;

            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;

            try {
                mJpegOutputStream.close();
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }

            mVirtualDisplay.release();
            mVirtualDisplay = null;

            mImageHandler.removeCallbacksAndMessages(null);
            mImageThread.quit();
            mImageThread = null;

            if (mReusableBitmap != null) {
                mReusableBitmap.recycle();
                mReusableBitmap = null;
            }

            isThreadRunning = false;
        }
    }

    void addDefaultScreen(final Context context) {
        getAppState().mJPEGQueue.clear();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                final byte[] jpegByteArray = NotifyImageGenerator.getDefaultScreen(context);
                if (jpegByteArray != null) {
                    getAppState().mJPEGQueue.add(jpegByteArray);
                }
            }
        }, 500);

    }
}

