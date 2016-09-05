package info.dvkr.screenstream.data.local;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Random;

import info.dvkr.screenstream.R;

public final class PreferencesHelper {
    private static final String DEFAULT_PIN = "0000";
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_JPEG_QUALITY = "80";
    private static final String DEFAULT_CLIENT_TIMEOUT = "3000";

    private final Context mContext;
    private final SharedPreferences mSharedPreferences;
    private boolean mMinimizeOnStream;
    private boolean mStopOnSleep;
    private boolean mEnablePin;
    private boolean mHidePinOnStart;
    private boolean mNewPinOnAppStart;
    private boolean mAutoChangePin;
    private String mCurrentPin;
    private volatile int mSeverPort;
    private volatile int mJpegQuality;
    private volatile int mClientTimeout;

    public PreferencesHelper(final Context context) {
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        updateSettings();
    }

    //TODO Replace with Observable
    public void updateSettings() {
        mMinimizeOnStream = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_minimize_on_stream), true);
        mStopOnSleep = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_stop_on_sleep), false);
        mEnablePin = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_enable_pin), false);
        mHidePinOnStart = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_hide_pin_on_start), true);
        mNewPinOnAppStart = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_new_pin_on_app_start), true);
        mAutoChangePin = mSharedPreferences.getBoolean(mContext.getString(R.string.pref_key_auto_change_pin), false);
        mCurrentPin = mSharedPreferences.getString(mContext.getString(R.string.pref_key_set_pin), DEFAULT_PIN);
        mSeverPort = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_server_port), DEFAULT_SERVER_PORT));
        mJpegQuality = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_jpeg_quality), DEFAULT_JPEG_QUALITY));
        mClientTimeout = Integer.parseInt(mSharedPreferences.getString(mContext.getString(R.string.pref_key_client_con_timeout), DEFAULT_CLIENT_TIMEOUT));
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

    public boolean isEnablePin() {
        return mEnablePin;
    }

    public boolean isHidePinOnStart() {
        return mHidePinOnStart;
    }

    public boolean isNewPinOnAppStart() {
        return mNewPinOnAppStart;
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

    public int getJpegQuality() {
        return mJpegQuality;
    }

    public int getClientTimeout() {
        return mClientTimeout;
    }

    private static String getRandomPin() {
        final Random random = new Random(System.currentTimeMillis());
        return "" + random.nextInt(10) + random.nextInt(10) + random.nextInt(10) + random.nextInt(10);
    }
}
