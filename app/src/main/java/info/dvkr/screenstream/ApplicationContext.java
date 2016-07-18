package info.dvkr.screenstream;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedTransferQueue;


public class ApplicationContext extends Application {
    private static ApplicationContext instance;

    private ApplicationSettings applicationSettings;
    private WindowManager windowManager;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private int densityDPI;
    private String indexHtmlPage;
    private byte[] iconBytes;

    private final LinkedTransferQueue<byte[]> JPEGQueue = new LinkedTransferQueue<>();
    private final ConcurrentLinkedQueue<Client> clientQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean isStreamRunning;
    private volatile boolean isForegroundServiceRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        applicationSettings = new ApplicationSettings(this);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        densityDPI = getDensityDPI();
        indexHtmlPage = getIndexHTML();
        setFavicon();
    }

    static ApplicationSettings getApplicationSettings() {
        return instance.applicationSettings;
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
    }

    static boolean isForegroundServiceRunning() {
        return instance.isForegroundServiceRunning;
    }

    static void setIsForegroundServiceRunning(final boolean isRunning) {
        instance.isForegroundServiceRunning = isRunning;
    }

    static String getIndexHtmlPage() {
        return instance.indexHtmlPage;
    }

    static byte[] getIconBytes() {
        return instance.iconBytes;
    }

    static String getServerAddress() {
        return "http://" + instance.getIPAddress() + ":" + instance.applicationSettings.getSeverPort();
    }

    static LinkedTransferQueue<byte[]> getJPEGQueue() {
        return instance.JPEGQueue;
    }

    static ConcurrentLinkedQueue<Client> getClientQueue() {
        return instance.clientQueue;
    }

    static boolean isWiFIConnected() {
        final WifiManager wifi = (WifiManager) instance.getSystemService(Context.WIFI_SERVICE);
        return wifi.getConnectionInfo().getNetworkId() != -1;
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

    private String getIndexHTML() {
        final StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(getAssets().open("index.html"), "UTF-8")
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

