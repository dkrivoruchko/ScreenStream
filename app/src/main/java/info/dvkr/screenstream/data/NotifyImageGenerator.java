package info.dvkr.screenstream.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.support.annotation.Nullable;

import com.google.firebase.crash.FirebaseCrash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import info.dvkr.screenstream.R;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppPreference;

public final class NotifyImageGenerator {
    private final Context mContext;
    private int mCurrentScreenSizeX;
    private byte[] mCurrentDefaultScreen;

    public NotifyImageGenerator(final Context context) {
        mContext = context;
    }

    public void addDefaultScreen() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mCurrentScreenSizeX != getAppData().getScreenSize().x) {
                    mCurrentDefaultScreen = null;
                }
                if (mCurrentDefaultScreen == null) {
                    mCurrentDefaultScreen = generateImage(mContext.getString(R.string.image_generator_press),
                            mContext.getString(R.string.main_activity_start_stream).toUpperCase(),
                            mContext.getString(R.string.image_generator_on_device));
                    mCurrentScreenSizeX = getAppData().getScreenSize().x;
                }
                if (mCurrentDefaultScreen != null) {
                    getAppData().getImageQueue().add(mCurrentDefaultScreen);
                }
            }
        }, 500);
    }

    public byte[] getClientNotifyImage(final String reason) {
        if (BusMessages.MESSAGE_ACTION_HTTP_RESTART.equals(reason))
            return generateImage(mContext.getString(R.string.image_generator_settings_changed),
                    "", mContext.getString(R.string.image_generator_go_to_new_address));

        if (BusMessages.MESSAGE_ACTION_PIN_UPDATE.equals(reason))
            return generateImage(mContext.getString(R.string.image_generator_settings_changed),
                    "", mContext.getString(R.string.image_generator_reload_this_page));
        return null;
    }


    @Nullable
    private static byte[] generateImage(final String text1, final String text2, final String text3) {
        final Bitmap bitmap = Bitmap.createBitmap(getAppData().getScreenSize().x,
                getAppData().getScreenSize().y,
                Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(bitmap);
        canvas.drawRGB(255, 255, 255);

        int textSize, x, y;
        final Rect bounds = new Rect();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (!"".equals(text1)) {
            textSize = (int) (12 * getAppData().getDisplayScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.BLACK);
            paint.getTextBounds(text1, 0, text1.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2 - 2 * textSize;
            canvas.drawText(text1, x, y, paint);
        }

        if (!"".equals(text2)) {
            textSize = (int) (16 * getAppData().getDisplayScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.rgb(153, 50, 0));
            paint.getTextBounds(text2, 0, text2.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2;
            canvas.drawText(text2.toUpperCase(), x, y, paint);
        }

        if (!"".equals(text3)) {
            textSize = (int) (12 * getAppData().getDisplayScale());
            paint.setTextSize(textSize);
            paint.setColor(Color.BLACK);
            paint.getTextBounds(text3, 0, text3.length(), bounds);
            x = (bitmap.getWidth() - bounds.width()) / 2;
            y = (bitmap.getHeight() + bounds.height()) / 2 + 2 * textSize;
            canvas.drawText(text3, x, y, paint);
        }

        byte[] jpegByteArray = null;
        try (final ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, getAppPreference().getJpegQuality(), jpegOutputStream);
            jpegByteArray = jpegOutputStream.toByteArray();
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
        bitmap.recycle();
        return jpegByteArray;
    }
}