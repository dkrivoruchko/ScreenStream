package info.dvkr.screenstream.data.local;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;

import java.util.Random;

import info.dvkr.screenstream.R;
import info.dvkr.screenstream.data.BusMessages;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.ScreenStreamApplication.getMainActivityViewModel;

public final class PreferencesHelper {
    public static final int DEFAULT_RESIZE_FACTOR = 10;
    private static final String DEFAULT_PIN = "NOPIN";
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_JPEG_QUALITY = "80";
    private static final String DEFAULT_CLIENT_TIMEOUT = "3000";

    private final Context mContext;
    private final SharedPreferences mSharedPreferences;
    private boolean mMinimizeOnStream;
    private boolean mStopOnSleep;
    private boolean mDisableMJPEGCheck;
    private int mHTMLBackColor;
    private volatile int mResizeFactor;
    private volatile int mJpegQuality;
    private boolean mEnablePin;
    private boolean mHidePinOnStart;
    private boolean mNewPinOnAppStart;
    private boolean mAutoChangePin;
    private String mCurrentPin;
    private volatile int mSeverPort;
    private volatile int mClientTimeout;

    public PreferencesHelper(final Context context) {
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        readSettings();

        if (DEFAULT_PIN.equals(mCurrentPin) || (mEnablePin && mNewPinOnAppStart)) {
            generateAndSaveNewPin();
        }

        getMainActivityViewModel().setPinEnabled(mEnablePin);
        getMainActivityViewModel().setPinAutoHide(mHidePinOnStart);
        getMainActivityViewModel().setStreamPin(mCurrentPin);
    }

    private void readSettings() {
        mMinimizeOnStream = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_minimize_on_stream), true);
        mStopOnSleep = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_stop_on_sleep), false);
        mDisableMJPEGCheck = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_mjpeg_check), false);
        mHTMLBackColor = mSharedPreferences.getInt(mContext.getString(R.string.pref_key_html_back_color), 0);

        mJpegQuality = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_jpeg_quality), DEFAULT_JPEG_QUALITY));
        mResizeFactor = mSharedPreferences.getInt(mContext.getString(R.string.pref_key_resize_factor), DEFAULT_RESIZE_FACTOR);

        mEnablePin = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_enable_pin), false);
        mHidePinOnStart = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_hide_pin_on_start), true);
        mNewPinOnAppStart = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_new_pin_on_app_start), true);
        mAutoChangePin = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_auto_change_pin), false);
        mCurrentPin = mSharedPreferences.getString(mContext.getString(R.string.pref_key_set_pin), DEFAULT_PIN);

        mSeverPort = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_server_port), DEFAULT_SERVER_PORT));
        mClientTimeout = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_client_con_timeout), DEFAULT_CLIENT_TIMEOUT));
    }

    public void updatePreference() {
        final boolean oldDisableMJPEGCheck = mDisableMJPEGCheck;
        final int oldHTMLBackColork = mHTMLBackColor;
        final int oldServerPort = mSeverPort;
        final boolean oldEnablePin = mEnablePin;
        final String oldPin = mCurrentPin;
        readSettings();

        getMainActivityViewModel().setResizeFactor(mResizeFactor);
        getMainActivityViewModel().setPinEnabled(mEnablePin);
        getMainActivityViewModel().setPinAutoHide(mHidePinOnStart);
        getMainActivityViewModel().setStreamPin(mCurrentPin);

        if (oldDisableMJPEGCheck != mDisableMJPEGCheck || oldHTMLBackColork != mHTMLBackColor) {
            getAppData().initIndexHtmlPage(mContext);
        }

        if (oldServerPort != mSeverPort) {
            getMainActivityViewModel().setServerAddress(getAppData().getServerAddress());
            EventBus.getDefault().post(new BusMessages(BusMessages.MESSAGE_ACTION_HTTP_RESTART));
        } else if (oldEnablePin != mEnablePin || !oldPin.equals(mCurrentPin)) {
            EventBus.getDefault().post(new BusMessages(BusMessages.MESSAGE_ACTION_PIN_UPDATE));
        }
    }

    public void generateAndSaveNewPin() {
        mCurrentPin = getRandomPin();
        final SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(mContext.getString(R.string.pref_key_set_pin), mCurrentPin);
        editor.apply();
    }

    public boolean isMinimizeOnStream() {
        return mMinimizeOnStream;
    }

    public boolean isStopOnSleep() {
        return mStopOnSleep;
    }

    public boolean isDisableMJPEGCheck() {
        return mDisableMJPEGCheck;
    }

    public int getHTMLBackColor() {
        return mHTMLBackColor;
    }

    public int getResizeFactor() {
        return mResizeFactor;
    }

    public void setResizeFactor(final int resizeFactor) {
        mResizeFactor = resizeFactor;
        mSharedPreferences
                .edit()
                .putInt(mContext.getString(R.string.pref_key_resize_factor), mResizeFactor)
                .apply();
    }

    public int getJpegQuality() {
        return mJpegQuality;
    }

    public boolean isEnablePin() {
        return mEnablePin;
    }

    public boolean isAutoChangePin() {
        return mAutoChangePin;
    }

    public String getCurrentPin() {
        return mCurrentPin;
    }

    public int getSeverPort() {
        return mSeverPort;
    }

    public int getClientTimeout() {
        return mClientTimeout;
    }

    private static String getRandomPin() {
        final Random random = new Random(System.currentTimeMillis());
        return "" + random.nextInt(10) + random.nextInt(10) + random.nextInt(10) + random.nextInt(10);
    }
}