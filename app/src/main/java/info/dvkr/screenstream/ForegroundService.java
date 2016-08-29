package info.dvkr.screenstream;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import java.util.concurrent.ConcurrentLinkedQueue;

import static info.dvkr.screenstream.AppContext.getAppSettings;
import static info.dvkr.screenstream.AppContext.getAppState;
import static info.dvkr.screenstream.AppContext.getAppViewState;
import static info.dvkr.screenstream.AppContext.getServerAddress;
import static info.dvkr.screenstream.AppContext.isWiFiConnected;

public final class ForegroundService extends Service {
    private static ForegroundService foregroundService;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private HTTPServer httpServer;
    private ImageGenerator imageGenerator;
    private ForegroundTaskHandler foregroundServiceTaskHandler;

    // Fields for broadcast
    static final String SERVICE_ACTION = "info.dvkr.screenstream.ForegroundService.SERVICE_ACTION";
    static final String SERVICE_PERMISSION = "info.dvkr.screenstream.RECEIVE_BROADCAST";
    static final String SERVICE_MESSAGE = "SERVICE_MESSAGE";
    static final int SERVICE_MESSAGE_EMPTY = 0;
    static final int SERVICE_MESSAGE_HAS_NEW = 1;
    static final int SERVICE_MESSAGE_PREPARE_STREAMING = 110;
    static final int SERVICE_MESSAGE_START_STREAMING = 111;
    static final int SERVICE_MESSAGE_STOP_STREAMING = 112;
    static final int SERVICE_MESSAGE_RESTART_HTTP = 3000;
    static final int SERVICE_MESSAGE_HTTP_PORT_IN_USE = 3001;
    static final int SERVICE_MESSAGE_HTTP_OK = 3002;
    static final int SERVICE_MESSAGE_UPDATE_PIN_STATUS = 2000;
    static final int SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR = 4000;
    static final int SERVICE_MESSAGE_EXIT = 9000;

    private ConcurrentLinkedQueue<Integer> serviceMessages = new ConcurrentLinkedQueue<>();

    // Fields for notifications
    private BroadcastReceiver localNotificationReceiver;
    private final String KEY_START = "info.dvkr.screenstream.ForegroundService.startStream";
    private final Intent startStreamIntent = new Intent(KEY_START);
    private final String KEY_STOP = "info.dvkr.screenstream.ForegroundService.stopStream";
    private final Intent stopStreamIntent = new Intent(KEY_STOP);
    private final String KEY_CLOSE = "info.dvkr.screenstream.ForegroundService.closeService";
    private final Intent closeIntent = new Intent(KEY_CLOSE);

    private BroadcastReceiver broadcastReceiver;

    static void setMediaProjection(final MediaProjection mediaProjection) {
        foregroundService.mediaProjection = mediaProjection;
    }

    @Nullable
    static MediaProjectionManager getProjectionManager() {
        return foregroundService == null ? null : foregroundService.projectionManager;
    }

    @Nullable
    static MediaProjection getMediaProjection() {
        return foregroundService == null ? null : foregroundService.mediaProjection;
    }

    @Nullable
    static ImageGenerator getImageGenerator() {
        return foregroundService == null ? null : foregroundService.imageGenerator;
    }

    @Nullable
    static ConcurrentLinkedQueue<Integer> getServiceMessages() {
        return foregroundService == null ? null : foregroundService.serviceMessages;
    }

    static void errorInImageGenerator() {
        if (foregroundService != null)
            foregroundService.relayMessageViaActivity(SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR);
    }

    @Override
    public void onCreate() {
        foregroundService = this;

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                serviceStopStreaming();
            }
        };
        httpServer = new HTTPServer();
        imageGenerator = new ImageGenerator();
        imageGenerator.addDefaultScreen(getApplicationContext());

        // Starting thread Handler
        final HandlerThread looperThread = new HandlerThread(ForegroundService.class.getSimpleName(), Process.THREAD_PRIORITY_MORE_FAVORABLE);
        looperThread.start();
        foregroundServiceTaskHandler = new ForegroundTaskHandler(looperThread.getLooper());

        //Local notifications
        final IntentFilter localNotificationIntentFilter = new IntentFilter();
        localNotificationIntentFilter.addAction(KEY_START);
        localNotificationIntentFilter.addAction(KEY_STOP);
        localNotificationIntentFilter.addAction(KEY_CLOSE);

        localNotificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                switch (action) {
                    case KEY_START:
                        relayMessageViaActivity(SERVICE_MESSAGE_START_STREAMING);
                        break;
                    case KEY_STOP:
                        relayMessageViaActivity(SERVICE_MESSAGE_STOP_STREAMING);
                        break;
                    case KEY_CLOSE:
                        relayMessageViaActivity(SERVICE_MESSAGE_EXIT);
                        break;
                }
            }
        };

        registerReceiver(localNotificationReceiver, localNotificationIntentFilter);

        // Registering receiver for screen off messages and WiFi changes
        final IntentFilter screenOnOffAndWiFiFilter = new IntentFilter();
        screenOnOffAndWiFiFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenOnOffAndWiFiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    if (getAppSettings().isPauseOnSleep() && getAppState().isStreamRunning)
                        relayMessageViaActivity(SERVICE_MESSAGE_STOP_STREAMING);
                }

                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    if (getAppViewState().wifiConnected.get() != isWiFiConnected()) {
                        getAppViewState().serverAddress.set(getServerAddress());
                        getAppViewState().wifiConnected.set(isWiFiConnected());

                        if ((!getAppViewState().wifiConnected.get()) && getAppState().isStreamRunning)
                            relayMessageViaActivity(SERVICE_MESSAGE_STOP_STREAMING);
                    }
                }
            }
        };

        registerReceiver(broadcastReceiver, screenOnOffAndWiFiFilter);
        httpServerStartAndCheck();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int messageFromActivity = intent.getIntExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_EMPTY);
//        Log.wtf(">>>>>>>>>>> messageFromActivity", "" + messageFromActivity);
        switch (messageFromActivity) {
            case SERVICE_MESSAGE_PREPARE_STREAMING:
                startForeground(110, getNotificationStart());
                break;
            case SERVICE_MESSAGE_START_STREAMING:
                serviceStartStreaming();
                break;
            case SERVICE_MESSAGE_STOP_STREAMING:
                serviceStopStreaming();
                break;
            case SERVICE_MESSAGE_RESTART_HTTP:
                serviceRestartHTTP();
                break;
            case SERVICE_MESSAGE_UPDATE_PIN_STATUS:
                serviceUpdatePinStatus();
                break;
        }
        return START_NOT_STICKY;
    }

    private void serviceStartStreaming() {
        stopForeground(true);
        foregroundServiceTaskHandler.obtainMessage(ForegroundTaskHandler.HANDLER_START_STREAMING).sendToTarget();
        startForeground(120, getNotificationStop());
        if (mediaProjection != null) mediaProjection.registerCallback(projectionCallback, null);
    }

    private void serviceStopStreaming() {
        stopForeground(true);
        foregroundServiceTaskHandler.obtainMessage(ForegroundTaskHandler.HANDLER_STOP_STREAMING).sendToTarget();
        startForeground(110, getNotificationStart());
        if (mediaProjection != null) mediaProjection.unregisterCallback(projectionCallback);
        imageGenerator.addDefaultScreen(getApplicationContext());
    }

    private void serviceRestartHTTP() {
        httpServer.stop(HTTPServer.SERVER_SETTINGS_RESTART, NotifyImageGenerator.getClientNotifyImage(getApplicationContext(), HTTPServer.SERVER_SETTINGS_RESTART));
        imageGenerator.addDefaultScreen(getApplicationContext());
        httpServerStartAndCheck();
    }

    private void serviceUpdatePinStatus() {
        httpServer.stop(HTTPServer.SERVER_PIN_RESTART, NotifyImageGenerator.getClientNotifyImage(getApplicationContext(), HTTPServer.SERVER_PIN_RESTART));
        imageGenerator.addDefaultScreen(getApplicationContext());
        httpServerStartAndCheck();
    }

    private void relayMessageViaActivity(final int message) {
        if (message == SERVICE_MESSAGE_EMPTY) return;
        serviceMessages.add(message);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_HAS_NEW), SERVICE_PERMISSION);
    }

    private void httpServerStartAndCheck() {
        AppContext.getAppState().httpServerStatus = httpServer.start();
        if (AppContext.getAppState().httpServerStatus == HTTPServer.SERVER_ERROR_PORT_IN_USE) {
            getAppViewState().httpServerError.set(true);
            relayMessageViaActivity(SERVICE_MESSAGE_HTTP_PORT_IN_USE);
        } else {
            getAppViewState().httpServerError.set(false);
            relayMessageViaActivity(SERVICE_MESSAGE_HTTP_OK);
        }
    }

    @Override
    public void onDestroy() {
        httpServer.stop(HTTPServer.SERVER_STOP, null);
        stopForeground(true);
        unregisterReceiver(broadcastReceiver);
        unregisterReceiver(localNotificationReceiver);
        if (mediaProjection != null) mediaProjection.unregisterCallback(projectionCallback);
        foregroundServiceTaskHandler.getLooper().quit();
    }

    private Notification getNotificationStart() {
        final Intent mainActivityIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingMainActivityIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder startNotificationBuilder = new NotificationCompat.Builder(this);
        startNotificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        startNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        startNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        startNotificationBuilder.setContentTitle(getString(R.string.ready_to_stream));
        startNotificationBuilder.setContentText(getString(R.string.press_start));
        startNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        startNotificationBuilder.addAction(R.drawable.ic_play_arrow_24dp, getString(R.string.start).toUpperCase(), PendingIntent.getBroadcast(this, 0, startStreamIntent, 0));
        startNotificationBuilder.addAction(R.drawable.ic_clear_24dp, getString(R.string.exit).toUpperCase(), PendingIntent.getBroadcast(this, 0, closeIntent, 0));
        startNotificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX);
        return startNotificationBuilder.build();
    }

    private Notification getNotificationStop() {
        final Intent mainActivityIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingMainActivityIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder stopNotificationBuilder = new NotificationCompat.Builder(this);
        stopNotificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        stopNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        stopNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        stopNotificationBuilder.setContentTitle(getString(R.string.stream));
        stopNotificationBuilder.setContentText(getString(R.string.go_to) + getServerAddress());
        stopNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        stopNotificationBuilder.addAction(R.drawable.ic_stop_24dp, getString(R.string.stop).toUpperCase(), PendingIntent.getBroadcast(this, 0, stopStreamIntent, 0));
        stopNotificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX);
        return stopNotificationBuilder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}