package info.dvkr.screenstream;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.google.firebase.crash.FirebaseCrash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class ImageGenerator {
    private final Object lock = new Object();
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private volatile boolean isThreadRunning;

    private HandlerThread imageThread;
    private Handler imageHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Bitmap reusableBitmap;
    private ByteArrayOutputStream jpegOutputStream;

    private final String defaultText1;
    private final String defaultText2;
    private final String defaultText3;

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

                image = imageReader.acquireLatestImage();
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
                bitmapClean.compress(Bitmap.CompressFormat.JPEG, ApplicationContext.getApplicationSettings().getJpegQuality(), jpegOutputStream);
                bitmapClean.recycle();
                jpegByteArray = jpegOutputStream.toByteArray();

                if (jpegByteArray != null) {
                    ApplicationContext.getJPEGQueue().add(jpegByteArray);
                    jpegByteArray = null;
                }
            }
        }
    }

    ImageGenerator(final String defaultText1, final String defaultText2, final String defaultText3) {
        this.defaultText1 = defaultText1;
        this.defaultText2 = defaultText2;
        this.defaultText3 = defaultText3;
    }

    void start() {
        synchronized (lock) {
            if (isThreadRunning) return;

            imageThread = new HandlerThread("Image capture thread", Process.THREAD_PRIORITY_MORE_FAVORABLE);
            imageThread.start();
            imageReader = ImageReader.newInstance(ApplicationContext.getScreenSize().x, ApplicationContext.getScreenSize().y, PixelFormat.RGBX_8888, 2);
            imageHandler = new Handler(imageThread.getLooper());
            jpegOutputStream = new ByteArrayOutputStream();
            imageReader.setOnImageAvailableListener(new ImageAvailableListener(), imageHandler);
            virtualDisplay = ApplicationContext.getMediaProjection().createVirtualDisplay(
                    "Screen Stream Virtual Display",
                    ApplicationContext.getScreenSize().x,
                    ApplicationContext.getScreenSize().y,
                    ApplicationContext.getScreenDensity(),
                    VIRTUAL_DISPLAY_FLAGS,
                    imageReader.getSurface(),
                    null, imageHandler);

            isThreadRunning = true;
//            Log.d(TAG, "Image generator started.");
        }
    }

    void stop() {
        synchronized (lock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;

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
//            Log.d(TAG, "Image generator stopped.");
        }
    }

    void addDefaultScreen() {
        ApplicationContext.getJPEGQueue().clear();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = Bitmap.createBitmap(ApplicationContext.getScreenSize().x, ApplicationContext.getScreenSize().y, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(bitmap);
                canvas.drawRGB(255, 255, 255);

                final Rect bounds = new Rect();
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                int textSize = (int) (12 * ApplicationContext.getScale());
                paint.setTextSize(textSize);
                paint.setColor(Color.BLACK);
                paint.getTextBounds(defaultText1, 0, defaultText1.length(), bounds);
                int x = (bitmap.getWidth() - bounds.width()) / 2;
                int y = (bitmap.getHeight() + bounds.height()) / 2 - 2 * textSize;
                canvas.drawText(defaultText1, x, y, paint);

                textSize = (int) (16 * ApplicationContext.getScale());
                paint.setTextSize(textSize);
                paint.setColor(Color.rgb(153, 50, 0));
                paint.getTextBounds(defaultText2.toUpperCase(), 0, defaultText2.length(), bounds);
                x = (bitmap.getWidth() - bounds.width()) / 2;
                y = (bitmap.getHeight() + bounds.height()) / 2;
                canvas.drawText(defaultText2.toUpperCase(), x, y, paint);

                textSize = (int) (12 * ApplicationContext.getScale());
                paint.setTextSize(textSize);
                paint.setColor(Color.BLACK);
                paint.getTextBounds(defaultText3, 0, defaultText3.length(), bounds);
                x = (bitmap.getWidth() - bounds.width()) / 2;
                y = (bitmap.getHeight() + bounds.height()) / 2 + 2 * textSize;
                canvas.drawText(defaultText3, x, y, paint);

                byte[] jpegByteArray = null;
                try (final ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream()) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, ApplicationContext.getApplicationSettings().getJpegQuality(), jpegOutputStream);
                    jpegByteArray = jpegOutputStream.toByteArray();
                } catch (IOException e) {
                    FirebaseCrash.report(e);
                }
                bitmap.recycle();
                if (jpegByteArray != null) ApplicationContext.getJPEGQueue().add(jpegByteArray);
            }
        }, 500);

    }

}

