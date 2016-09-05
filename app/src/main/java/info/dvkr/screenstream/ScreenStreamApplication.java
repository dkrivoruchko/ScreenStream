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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

import info.dvkr.screenstream.data.HttpServer;
import info.dvkr.screenstream.data.ImageToClientStreamer;
import info.dvkr.screenstream.data.local.PreferencesHelper;
import info.dvkr.screenstream.service.ForegroundService;
import info.dvkr.screenstream.viewModel.MainActivityViewModel;


public class ScreenStreamApplication extends Application {
    private static ScreenStreamApplication sAppInstance;

    private MainActivityViewModel mMainActivityViewModel;
    private final AppState mAppState = new AppState();
    private PreferencesHelper mPreferencesHelper;
    private WindowManager mWindowManager;
    private int mDensityDpi;
    private String mIndexHtmlPage;
    private String mPinRequestHtmlPage;
    private byte[] mIconBytes;

    public class AppState {
        public final ConcurrentLinkedDeque<byte[]> mJPEGQueue = new ConcurrentLinkedDeque<>();
        public final ConcurrentLinkedQueue<ImageToClientStreamer> mImageToClientStreamerQueue = new ConcurrentLinkedQueue<>();
        public volatile boolean isStreamRunning;
        public volatile int mHttpServerStatus = HttpServer.SERVER_STATUS_UNKNOWN;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sAppInstance = this;

        mMainActivityViewModel = new MainActivityViewModel(this);

        mPreferencesHelper = new PreferencesHelper(this);
        if (mPreferencesHelper.isEnablePin() && mPreferencesHelper.isNewPinOnAppStart()) {
            mPreferencesHelper.generateAndSaveNewPin();
        }

        mMainActivityViewModel.setServerAddress(getServerAddress());
        mMainActivityViewModel.setPinEnabled(mPreferencesHelper.isEnablePin());
        mMainActivityViewModel.setPinAutoHide(mPreferencesHelper.isHidePinOnStart());
        mMainActivityViewModel.setStreamPin(mPreferencesHelper.getCurrentPin());
        mMainActivityViewModel.setWiFiConnected(isWiFiConnected());

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mDensityDpi = getDensityDpi();
        mIndexHtmlPage = getHtml("index.html")
                .replaceFirst("MSG_NO_MJPEG_SUPPORT", getString(R.string.html_no_mjpeg_support));
        mPinRequestHtmlPage = getHtml("pinrequest.html")
                .replaceFirst("stream_require_pin", getString(R.string.html_stream_require_pin))
                .replaceFirst("enter_pin", getString(R.string.html_enter_pin))
                .replaceFirst("four_digits", getString(R.string.html_four_digits))
                .replaceFirst("submit_text", getString(R.string.html_submit_text));

        setFavicon();

        startService(ForegroundService.getStartIntent(this));
    }

    public static MainActivityViewModel getMainActivityViewModel() {
        return sAppInstance.mMainActivityViewModel;
    }

    public static AppState getAppState() {
        return sAppInstance.mAppState;
    }

    public static PreferencesHelper getAppSettings() {
        return sAppInstance.mPreferencesHelper;
    }

    public static int getScreenDensity() {
        return sAppInstance.mDensityDpi;
    }

    public static WindowManager getWindowsManager() {
        return sAppInstance.mWindowManager;
    }

    public static float getScale() {
        return sAppInstance.getResources().getDisplayMetrics().density;
    }

    public static Point getScreenSize() {
        final Point screenSize = new Point();
        sAppInstance.mWindowManager.getDefaultDisplay().getRealSize(screenSize);
        return screenSize;
    }

    public static void setIsStreamRunning(final boolean isRunning) {
        sAppInstance.mAppState.isStreamRunning = isRunning;
        getMainActivityViewModel().setStreaming(isRunning);
    }

    public static String getIndexHtmlPage(final String streamAddress) {
        return sAppInstance.mIndexHtmlPage.replaceFirst("SCREEN_STREAM_ADDRESS", streamAddress);
    }

    public static String getPinRequestHtmlPage(final boolean isError) {
        final String errorString = (isError) ? sAppInstance.getString(R.string.html_wrong_pin) : "&nbsp";
        return sAppInstance.mPinRequestHtmlPage.replaceFirst("wrong_pin", errorString);
    }

    public static byte[] getIconBytes() {
        return sAppInstance.mIconBytes;
    }

    public static String getServerAddress() {
        return "http://" + sAppInstance.getIpAddress() + ":" + sAppInstance.mPreferencesHelper.getSeverPort();
    }

    public static boolean isWiFiConnected() {
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

