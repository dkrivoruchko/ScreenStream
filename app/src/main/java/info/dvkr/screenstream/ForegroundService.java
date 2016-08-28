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

public final class ForegroundService extends Service {
    private static ForegroundService foregroundService;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private HTTPServer httpServer;
    private ImageGenerator imageGenerator;
    private ForegroundTaskHandler foregroundServiceTaskHandler;

    private int httpServerStatus = HTTPServer.SERVER_STATUS_UNKNOWN;

    // Fields for broadcast
    static final String SERVICE_ACTION = "info.dvkr.screenstream.ForegroundService.SERVICE_ACTION";
    static final String SERVICE_PERMISSION = "info.dvkr.screenstream.RECEIVE_BROADCAST";
    static final String SERVICE_MESSAGE = "SERVICE_MESSAGE";
    static final int SERVICE_MESSAGE_EMPTY = 0;
    static final int SERVICE_MESSAGE_HAS_NEW = 1;
    static final int SERVICE_MESSAGE_GET_CURRENT = 11;
    static final int SERVICE_MESSAGE_PREPARE_STREAMING = 110;
    static final int SERVICE_MESSAGE_START_STREAMING = 111;
    static final int SERVICE_MESSAGE_STOP_STREAMING = 112;
    static final int SERVICE_MESSAGE_RESTART_HTTP = 3000;
    static final int SERVICE_MESSAGE_HTTP_PORT_IN_USE = 3001;
    static final int SERVICE_MESSAGE_HTTP_OK = 3002;
    static final int SERVICE_MESSAGE_UPDATE_PIN_STATUS = 2000;
    static final int SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR = 4000;
    static final int SERVICE_MESSAGE_EXIT = 9000;

    private int currentServiceMessage;

    // Fields for notifications
    private BroadcastReceiver localNotificationReceiver;
    private final String KEY_START = "info.dvkr.screenstream.ForegroundService.startStream";
    private final Intent startStreamIntent = new Intent(KEY_START);
    private final String KEY_STOP = "info.dvkr.screenstream.ForegroundService.stopStream";
    private final Intent stopStreamIntent = new Intent(KEY_STOP);
    private final String KEY_CLOSE = "info.dvkr.screenstream.ForegroundService.closeService";
    private final Intent closeIntent = new Intent(KEY_CLOSE);

    private BroadcastReceiver broadcastReceiver;

    static MediaProjectionManager getProjectionManager() {
        return foregroundService.projectionManager;
    }

    static void setMediaProjection(final MediaProjection mediaProjection) {
        foregroundService.mediaProjection = mediaProjection;
    }

    @Nullable
    static MediaProjection getMediaProjection() {
        return foregroundService.mediaProjection;
    }

    static ImageGenerator getImageGenerator() {
        return foregroundService.imageGenerator;
    }

    static int getHttpServerStatus() {
        if (foregroundService == null) return HTTPServer.SERVER_STATUS_UNKNOWN;
        return foregroundService.httpServerStatus;
    }

    static void errorInImageGenerator() {
        foregroundService.currentServiceMessage = SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR;
        foregroundService.relayMessageViaActivity();
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
                if (intent.getAction().equals(KEY_START)) {
                    currentServiceMessage = SERVICE_MESSAGE_START_STREAMING;
                    relayMessageViaActivity();
                }

                if (intent.getAction().equals(KEY_STOP)) {
                    currentServiceMessage = SERVICE_MESSAGE_STOP_STREAMING;
                    relayMessageViaActivity();
                }

                if (intent.getAction().equals(KEY_CLOSE)) {
                    currentServiceMessage = SERVICE_MESSAGE_EXIT;
                    relayMessageViaActivity();
                }
            }
        };

        registerReceiver(localNotificationReceiver, localNotificationIntentFilter);

        // Registering receiver for screen off messages
        final IntentFilter screenOnOffFilter = new IntentFilter();
        screenOnOffFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenOnOffFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AppContext.getAppSettings().isPauseOnSleep())
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF))
                        if (AppContext.isStreamRunning()) {
                            currentServiceMessage = SERVICE_MESSAGE_STOP_STREAMING;
                            relayMessageViaActivity();
                        }

                if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    if (AppContext.getAppState().wifiConnected.get() != AppContext.isWiFiConnected()) {
                        AppContext.getAppState().serverAddress.set(AppContext.getServerAddress());
                        AppContext.getAppState().wifiConnected.set(AppContext.isWiFiConnected());

                        if ((!AppContext.getAppState().wifiConnected.get()) && AppContext.isStreamRunning()) {
                            currentServiceMessage = SERVICE_MESSAGE_STOP_STREAMING;
                            relayMessageViaActivity();
                        }
                    }
                }
            }
        };

        registerReceiver(broadcastReceiver, screenOnOffFilter);
        imageGenerator.addDefaultScreen(getApplicationContext());
        httpServerStartAndCheck();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int messageFromActivity = intent.getIntExtra(SERVICE_MESSAGE, 0);

        switch (messageFromActivity) {
            case SERVICE_MESSAGE_PREPARE_STREAMING:
                startForeground(110, getNotificationStart());
                break;
            case SERVICE_MESSAGE_GET_CURRENT:
                serviceGetCurrentMessage();
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

    private void serviceGetCurrentMessage() {
        if (currentServiceMessage == SERVICE_MESSAGE_EMPTY) return;
        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, currentServiceMessage), SERVICE_PERMISSION);
        currentServiceMessage = SERVICE_MESSAGE_EMPTY;
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

    private void relayMessageViaActivity() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_HAS_NEW), SERVICE_PERMISSION);
    }

    private void httpServerStartAndCheck() {
        httpServerStatus = httpServer.start();
        if (httpServerStatus == HTTPServer.SERVER_ERROR_PORT_IN_USE) {
            currentServiceMessage = SERVICE_MESSAGE_HTTP_PORT_IN_USE;
            AppContext.getAppState().httpServerError.set(true);
        } else {
            currentServiceMessage = SERVICE_MESSAGE_HTTP_OK;
            AppContext.getAppState().httpServerError.set(false);
        }
        relayMessageViaActivity();
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
        stopNotificationBuilder.setContentText(getString(R.string.go_to) + AppContext.getServerAddress());
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