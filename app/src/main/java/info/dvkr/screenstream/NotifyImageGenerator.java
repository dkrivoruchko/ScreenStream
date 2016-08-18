package info.dvkr.screenstream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.google.firebase.crash.FirebaseCrash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class NotifyImageGenerator {
    private static int currentScreenSizeX;
    private static byte[] currentDefaultScreen;

    static byte[] getDefaultScreen(final Context context) {
        if (currentScreenSizeX != ApplicationContext.getScreenSize().x) currentDefaultScreen = null;
        if (currentDefaultScreen != null) return currentDefaultScreen;

        currentDefaultScreen = generateImage(context.getResources().getString(R.string.press),
                context.getResources().getString(R.string.start_stream).toUpperCase(),
                context.getResources().getString(R.string.on_device));

        currentScreenSizeX = ApplicationContext.getScreenSize().x;
        return currentDefaultScreen;
    }


    static byte[] getClientNotifyImage(final Context context, final int reason) {
        if (reason == HTTPServer.SERVER_SETTINGS_RESTART)
            return generateImage(context.getResources().getString(R.string.settings_changed), "", context.getResources().getString(R.string.go_to_new_address));
        if (reason == HTTPServer.SERVER_PIN_RESTART)
            return generateImage(context.getResources().getString(R.string.settings_changed), "", context.getResources().getString(R.string.reload_this_page));
        return null;
    }


    private static byte[] generateImage(final String text1, final String text2, final String text3) {
        final Bitmap bitmap = Bitmap.createBitmap(ApplicationContext.getScreenSize().x, ApplicationContext.getScreenSize().y, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawRGB(255, 255, 255);

        int textSize, x, y;
        final Rect bounds = new Rect();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (!"".equals(text1)) {
            textSize = (int) (12 * ApplicationContext.getScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.BLACK);
            paint.getTextBounds(text1, 0, text1.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2 - 2 * textSize;
            canvas.drawText(text1, x, y, paint);
        }

        if (!"".equals(text2)) {
            textSize = (int) (16 * ApplicationContext.getScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.rgb(153, 50, 0));
            paint.getTextBounds(text2, 0, text2.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2;
            canvas.drawText(text2.toUpperCase(), x, y, paint);
        }

        if (!"".equals(text3)) {
            textSize = (int) (12 * ApplicationContext.getScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.BLACK);
            paint.getTextBounds(text3, 0, text3.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2 + 2 * textSize;
            canvas.drawText(text3, x, y, paint);
        }

        byte[] jpegByteArray = null;
        try (final ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, ApplicationContext.getApplicationSettings().getJpegQuality(), jpegOutputStream);
            jpegByteArray = jpegOutputStream.toByteArray();
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
        bitmap.recycle();
        return jpegByteArray;
    }
}
