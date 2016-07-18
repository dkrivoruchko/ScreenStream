package info.dvkr.screenstream;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

final class ApplicationSettings {
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_JPEG_QUALITY = "80";
    private static final String DEFAULT_CLIENT_TIMEOUT = "3000";

    private final SharedPreferences sharedPreferences;

    private boolean minimizeOnStream;
    private boolean pauseOnSleep;
    private volatile int severPort;
    private volatile int jpegQuality;
    private volatile int clientTimeout;

    ApplicationSettings(Context context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        minimizeOnStream = sharedPreferences.getBoolean("minimize_on_stream", true);
        pauseOnSleep = sharedPreferences.getBoolean("pause_on_sleep", false);
        severPort = Integer.parseInt(sharedPreferences.getString("port_number", DEFAULT_SERVER_PORT));
        jpegQuality = Integer.parseInt(sharedPreferences.getString("jpeg_quality", DEFAULT_JPEG_QUALITY));
        clientTimeout = Integer.parseInt(sharedPreferences.getString("client_connection_timeout", DEFAULT_CLIENT_TIMEOUT));
    }

    boolean updateSettings() {
        minimizeOnStream = sharedPreferences.getBoolean("minimize_on_stream", true);
        pauseOnSleep = sharedPreferences.getBoolean("pause_on_sleep", false);

        jpegQuality = Integer.parseInt(sharedPreferences.getString("jpeg_quality", DEFAULT_JPEG_QUALITY));
        clientTimeout = Integer.parseInt(sharedPreferences.getString("client_connection_timeout", DEFAULT_CLIENT_TIMEOUT));

        final int newSeverPort = Integer.parseInt(sharedPreferences.getString("port_number", DEFAULT_SERVER_PORT));
        if (newSeverPort != severPort) {
            severPort = newSeverPort;
            return true;
        }

        return false;
    }

    boolean isMinimizeOnStream() {
        return minimizeOnStream;
    }

    boolean isPauseOnSleep() {
        return pauseOnSleep;
    }

    int getSeverPort() {
        return severPort;
    }

    int getJpegQuality() {
        return jpegQuality;
    }

    int getClientTimeout() {
        return clientTimeout;
    }
}
