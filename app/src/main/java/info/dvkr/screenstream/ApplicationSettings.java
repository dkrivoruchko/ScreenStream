package info.dvkr.screenstream;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

final class ApplicationSettings {
    private static final String PIN_NOT_SET = "PIN_NOT_SET";
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_JPEG_QUALITY = "80";
    private static final String DEFAULT_CLIENT_TIMEOUT = "3000";

    private final SharedPreferences sharedPreferences;

    private boolean minimizeOnStream;
    private boolean pauseOnSleep;
    private boolean enablePin;
    private boolean newPinOnAppStart;
    private boolean autoGeneratePin;
    private String userPin = PIN_NOT_SET;
    private volatile int severPort;
    private volatile int jpegQuality;
    private volatile int clientTimeout;

    ApplicationSettings(Context context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        minimizeOnStream = sharedPreferences.getBoolean("minimize_on_stream", true);
        pauseOnSleep = sharedPreferences.getBoolean("pause_on_sleep", false);

        enablePin = sharedPreferences.getBoolean("enable_pin", false);
        newPinOnAppStart = sharedPreferences.getBoolean("pin_new_on_app_start", true);
        autoGeneratePin = sharedPreferences.getBoolean("pin_regenerate_on_start", false);
        userPin = sharedPreferences.getString("pin_manual", PIN_NOT_SET);


        severPort = Integer.parseInt(sharedPreferences.getString("port_number", DEFAULT_SERVER_PORT));
        jpegQuality = Integer.parseInt(sharedPreferences.getString("jpeg_quality", DEFAULT_JPEG_QUALITY));
        clientTimeout = Integer.parseInt(sharedPreferences.getString("client_connection_timeout", DEFAULT_CLIENT_TIMEOUT));
    }

    boolean updateSettings() {
        minimizeOnStream = sharedPreferences.getBoolean("minimize_on_stream", true);
        pauseOnSleep = sharedPreferences.getBoolean("pause_on_sleep", false);

        enablePin = sharedPreferences.getBoolean("enable_pin", false);
        newPinOnAppStart = sharedPreferences.getBoolean("pin_new_on_app_start", true);
        autoGeneratePin = sharedPreferences.getBoolean("pin_regenerate_on_start", false);
        userPin = sharedPreferences.getString("pin_manual", PIN_NOT_SET);

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

    boolean isEnablePin() {
        return enablePin;
    }

    boolean isNewPinOnAppStart() {
        return newPinOnAppStart;
    }

    boolean isAutoGeneratePin() {
        return autoGeneratePin;
    }

    String getUserPin() {
        if (!PIN_NOT_SET.equals(userPin)) return userPin;
        userPin = sharedPreferences.getString("pin_manual", PIN_NOT_SET);
        if (PIN_NOT_SET.equals(userPin)) setAndSaveUserPin(ApplicationContext.getRandomPin());
        return userPin;
    }

    void setAndSaveUserPin(final String newPin) {
        userPin = newPin;
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("pin_manual", newPin);
        editor.apply();
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
