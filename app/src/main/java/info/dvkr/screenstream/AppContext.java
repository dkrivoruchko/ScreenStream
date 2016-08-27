package info.dvkr.screenstream;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.support.annotation.Nullable;
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


public class AppContext extends Application {
    private static AppContext instance;

    private final AppState appState = new AppState();
    private AppSettings appSettings;
    private WindowManager windowManager;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private int densityDPI;
    private String indexHTMLPage;
    private String pinRequestHTMLPage;
    private byte[] iconBytes;

    private final ConcurrentLinkedDeque<byte[]> JPEGQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedQueue<Client> clientQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean isStreamRunning;
    private volatile boolean isForegroundServiceRunning;


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        appSettings = new AppSettings(this);
        if (appSettings.isEnablePin() && appSettings.isNewPinOnAppStart()) {
            appSettings.generateAndSaveNewPin();
        }

        appState.serverAddress.set(getServerAddress());
        appState.pinEnabled.set(appSettings.isEnablePin());
        appState.pinAutoHide.set(appSettings.isPinAutoHide());
        appState.streamPin.set(appSettings.getUserPin());
        appState.wifiConnected.set(isWiFiConnected());

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        densityDPI = getDensityDPI();
        indexHTMLPage = getHTML("index.html");
        pinRequestHTMLPage = getHTML("pinrequest.html");
        pinRequestHTMLPage = pinRequestHTMLPage
                .replaceFirst("stream_require_pin", getResources().getString(R.string.stream_require_pin))
                .replaceFirst("enter_pin", getResources().getString(R.string.html_enter_pin))
                .replaceFirst("four_digits", getResources().getString(R.string.four_digits))
                .replaceFirst("submit_text", getResources().getString(R.string.submit_text));

        setFavicon();
    }

    static AppState getAppState() {
        return instance.appState;
    }

    static AppSettings getAppSettings() {
        return instance.appSettings;
    }

    static WindowManager getWindowsManager() {
        return instance.windowManager;
    }

    static MediaProjectionManager getProjectionManager() {
        return instance.projectionManager;
    }

    static void setMediaProjection(final int resultCode, final Intent data) {
        instance.mediaProjection = instance.projectionManager.getMediaProjection(resultCode, data);
    }

    @Nullable
    static MediaProjection getMediaProjection() {
        return instance.mediaProjection;
    }

    static int getScreenDensity() {
        return instance.densityDPI;
    }

    static float getScale() {
        return instance.getResources().getDisplayMetrics().density;
    }

    static Point getScreenSize() {
        final Point screenSize = new Point();
        instance.windowManager.getDefaultDisplay().getRealSize(screenSize);
        return screenSize;
    }

    static boolean isStreamRunning() {
        return instance.isStreamRunning;
    }

    static void setIsStreamRunning(final boolean isRunning) {
        instance.isStreamRunning = isRunning;
        getAppState().streaming.set(isRunning);
    }

    static boolean isForegroundServiceRunning() {
        return instance.isForegroundServiceRunning;
    }

    static void setIsForegroundServiceRunning(final boolean isRunning) {
        instance.isForegroundServiceRunning = isRunning;
    }

    static String getIndexHTMLPage(final String streamAddress) {
        return instance.indexHTMLPage.replaceFirst("SCREEN_STREAM_ADDRESS", streamAddress);
    }

    static String getPinRequestHTMLPage(final boolean isError) {
        final String errorString = (isError) ? instance.getResources().getString(R.string.wrong_pin) : "&nbsp";
        return instance.pinRequestHTMLPage.replaceFirst("wrong_pin", errorString);
    }

    static byte[] getIconBytes() {
        return instance.iconBytes;
    }

    static String getServerAddress() {
        return "http://" + instance.getIPAddress() + ":" + instance.appSettings.getSeverPort();
    }

    static ConcurrentLinkedDeque<byte[]> getJPEGQueue() {
        return instance.JPEGQueue;
    }

    static ConcurrentLinkedQueue<Client> getClientQueue() {
        return instance.clientQueue;
    }

    static boolean isWiFiConnected() {
        final WifiManager wifi = (WifiManager) instance.getSystemService(Context.WIFI_SERVICE);
        return wifi.getConnectionInfo().getIpAddress() > 0;
    }

    // Private methods
    private String getIPAddress() {
        final int ipInt = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        return String.format(Locale.US, "%d.%d.%d.%d", (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
    }

    private int getDensityDPI() {
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.densityDpi;
    }

    private String getHTML(final String fileName) {
        final StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader reader =
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
        try (InputStream inputStream = getAssets().open("favicon.png")) {
            iconBytes = new byte[inputStream.available()];
            inputStream.read(iconBytes);
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
    }
}

