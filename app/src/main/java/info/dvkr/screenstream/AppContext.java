package info.dvkr.screenstream;

import android.app.Application;
import android.content.Context;
import android.graphics.Point;
import android.net.wifi.WifiManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;


public class AppContext extends Application {
    private static AppContext sAppInstance;

    private final AppViewState mAppViewState = new AppViewState();
    private final AppState mAppState = new AppState();
    private AppSettings mAppSettings;
    private WindowManager mWindowManager;
    private int mDensityDpi;
    private String mIndexHtmlPage;
    private String mPinRequestHtmlPage;
    private byte[] mIconBytes;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppInstance = this;

        mAppSettings = new AppSettings(this);
        if (mAppSettings.isEnablePin() && mAppSettings.isNewPinOnAppStart()) {
            mAppSettings.generateAndSaveNewPin();
        }

        mAppViewState.serverAddress.set(getServerAddress());
        mAppViewState.pinEnabled.set(mAppSettings.isEnablePin());
        mAppViewState.pinAutoHide.set(mAppSettings.isHidePinOnStart());
        mAppViewState.streamPin.set(mAppSettings.getCurrentPin());
        mAppViewState.wifiConnected.set(isWiFiConnected());

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mDensityDpi = getDensityDpi();
        mIndexHtmlPage = getHtml("index.html");
        mPinRequestHtmlPage = getHtml("pinrequest.html");
        mPinRequestHtmlPage = mPinRequestHtmlPage
                .replaceFirst("stream_require_pin", getString(R.string.stream_require_pin))
                .replaceFirst("enter_pin", getString(R.string.html_enter_pin))
                .replaceFirst("four_digits", getString(R.string.four_digits))
                .replaceFirst("submit_text", getString(R.string.submit_text));

        setFavicon();

        startService(ForegroundService.getStartIntent(this));
    }

    static AppViewState getAppViewState() {
        return sAppInstance.mAppViewState;
    }

    static AppState getAppState() {
        return sAppInstance.mAppState;
    }

    static AppSettings getAppSettings() {
        return sAppInstance.mAppSettings;
    }

    static int getScreenDensity() {
        return sAppInstance.mDensityDpi;
    }

    static WindowManager getWindowsManager() {
        return sAppInstance.mWindowManager;
    }

    static float getScale() {
        return sAppInstance.getResources().getDisplayMetrics().density;
    }

    static Point getScreenSize() {
        final Point screenSize = new Point();
        sAppInstance.mWindowManager.getDefaultDisplay().getRealSize(screenSize);
        return screenSize;
    }

    static void setIsStreamRunning(final boolean isRunning) {
        sAppInstance.mAppState.isStreamRunning = isRunning;
        getAppViewState().streaming.set(isRunning);
    }

    static String getIndexHtmlPage(final String streamAddress) {
        return sAppInstance.mIndexHtmlPage.replaceFirst("SCREEN_STREAM_ADDRESS", streamAddress);
    }

    static String getPinRequestHtmlPage(final boolean isError) {
        final String errorString = (isError) ? sAppInstance.getString(R.string.wrong_pin) : "&nbsp";
        return sAppInstance.mPinRequestHtmlPage.replaceFirst("wrong_pin", errorString);
    }

    static byte[] getIconBytes() {
        return sAppInstance.mIconBytes;
    }

    static String getServerAddress() {
        return "http://" + sAppInstance.getIpAddress() + ":" + sAppInstance.mAppSettings.getSeverPort();
    }

    static boolean isWiFiConnected() {
        final WifiManager wifiManager = (WifiManager) sAppInstance.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.getConnectionInfo().getIpAddress() > 0;
    }

    private String getIpAddress() {
        final int ipInt = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .getConnectionInfo()
                .getIpAddress();
        return String.format(Locale.US,
                "%d.%d.%d.%d",
                (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
    }

    private int getDensityDpi() {
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.densityDpi;
    }

    private String getHtml(final String fileName) {
        final StringBuilder sb = new StringBuilder();
        String line;
        try (final BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(getAssets().open(fileName), "UTF-8")
                     )) {
            while ((line = reader.readLine()) != null) sb.append(line.toCharArray());
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
        final String html = sb.toString();
        sb.setLength(0);
        return html;
    }

    private void setFavicon() {
        try (final InputStream inputStream = getAssets().open("favicon.png")) {
            mIconBytes = new byte[inputStream.available()];
            int count = inputStream.read(mIconBytes);
            if (count != 353) throw new IOException();
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
    }
}

