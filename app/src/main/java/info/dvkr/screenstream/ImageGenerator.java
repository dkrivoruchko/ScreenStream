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

final class ImageGenerator {
    private final Object lock = new Object();

    private volatile boolean isThreadRunning;

    private HandlerThread imageThread;
    private Handler imageHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Bitmap reusableBitmap;
    private ByteArrayOutputStream jpegOutputStream;

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        private Image image;
        private Image.Plane plane;
        private int width;

        private Bitmap bitmapClean;
        private byte[] jpegByteArray;

        @Override
        public void onImageAvailable(ImageReader reader) {
            synchronized (lock) {
                if (!isThreadRunning) return;

                try {
                    image = imageReader.acquireLatestImage();
                } catch (UnsupportedOperationException e) {
                    ForegroundService.errorInImageGenerator();
                    FirebaseCrash.report(e);
                    return;
                }

                if (image == null) return;

                plane = image.getPlanes()[0];
                width = plane.getRowStride() / plane.getPixelStride();

                if (width > image.getWidth()) {
                    if (reusableBitmap == null)
                        reusableBitmap = Bitmap.createBitmap(width, image.getHeight(), Bitmap.Config.ARGB_8888);
                    reusableBitmap.copyPixelsFromBuffer(plane.getBuffer());
                    bitmapClean = Bitmap.createBitmap(reusableBitmap, 0, 0, image.getWidth(), image.getHeight());
                } else {
                    bitmapClean = Bitmap.createBitmap(width, image.getHeight(), Bitmap.Config.ARGB_8888);
                    bitmapClean.copyPixelsFromBuffer(plane.getBuffer());
                }
                image.close();

                jpegOutputStream.reset();
                bitmapClean.compress(Bitmap.CompressFormat.JPEG, AppContext.getAppSettings().getJpegQuality(), jpegOutputStream);
                bitmapClean.recycle();
                jpegByteArray = jpegOutputStream.toByteArray();

                if (jpegByteArray != null) {
                    if (AppContext.getJPEGQueue().size() > 6) {
                        AppContext.getJPEGQueue().pollLast();
                    }
                    AppContext.getJPEGQueue().add(jpegByteArray);
                    jpegByteArray = null;
                }
            }
        }
    }

    void start() {
        synchronized (lock) {
            if (isThreadRunning) return;
            final MediaProjection mediaProjection = AppContext.getMediaProjection();
            if (mediaProjection == null) return;

            imageThread = new HandlerThread("Image capture thread", Process.THREAD_PRIORITY_MORE_FAVORABLE);
            imageThread.start();
            imageReader = ImageReader.newInstance(AppContext.getScreenSize().x, AppContext.getScreenSize().y, PixelFormat.RGBA_8888, 2);
            imageHandler = new Handler(imageThread.getLooper());
            jpegOutputStream = new ByteArrayOutputStream();
            imageReader.setOnImageAvailableListener(new ImageAvailableListener(), imageHandler);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "Screen Stream Virtual Display",
                    AppContext.getScreenSize().x,
                    AppContext.getScreenSize().y,
                    AppContext.getScreenDensity(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, imageHandler);

            isThreadRunning = true;
        }
    }

    void stop() {
        synchronized (lock) {
            if (!isThreadRunning) return;

            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;

            try {
                jpegOutputStream.close();
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }

            virtualDisplay.release();
            virtualDisplay = null;

            imageHandler.removeCallbacksAndMessages(null);
            imageThread.quit();
            imageThread = null;

            if (reusableBitmap != null) {
                reusableBitmap.recycle();
                reusableBitmap = null;
            }

            isThreadRunning = false;
        }
    }

    void addDefaultScreen(final Context context) {
        AppContext.getJPEGQueue().clear();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                final byte[] jpegByteArray = NotifyImageGenerator.getDefaultScreen(context);
                if (jpegByteArray != null) {
                    AppContext.getJPEGQueue().add(jpegByteArray);
                }
            }
        }, 500);

    }


}

