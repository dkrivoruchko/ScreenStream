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
    private static ForegroundService sServiceInstance;

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

    static final String ACTION_DEFAULT = "info.dvkr.screenstream.action.ACTION_DEFAULT";
    static final String EXTRA_SERVICE_MESSAGE = "info.dvkr.screenstream.extras.EXTRA_SERVICE_MESSAGE";
    static final String PERMISSION_RECEIVE_BROADCAST = "info.dvkr.screenstream.RECEIVE_BROADCAST";

    private final String ACTION_NOTIFY_START_STREAM = "info.dvkr.screenstream.action.ACTION_NOTIFY_START_STREAM";
    private final String ACTION_NOTIFY_STOP_STREAM = "info.dvkr.screenstream.action.ACTION_NOTIFY_STOP_STREAM";
    private final String ACTION_NOTIFY_CLOSE_APP = "info.dvkr.screenstream.action.ACTION_NOTIFY_CLOSE_APP";

    private MediaProjectionManager sProjectionManager;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mProjectionCallback;
    private HttpServer mHttpServer;
    private ImageGenerator mImageGenerator;
    private ForegroundTaskHandler mForegroundServiceTaskHandler;
    private BroadcastReceiver mLocalNotificationReceiver;
    private BroadcastReceiver mBroadcastReceiver;
    private ConcurrentLinkedQueue<Integer> mServiceMessages = new ConcurrentLinkedQueue<>();

    @Override
    public void onCreate() {
        sServiceInstance = this;

        sProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                serviceStopStreaming();
            }
        };
        mHttpServer = new HttpServer();
        mImageGenerator = new ImageGenerator();
        mImageGenerator.addDefaultScreen(getApplicationContext());

        // Starting thread Handler
        final HandlerThread looperThread =
                new HandlerThread(ForegroundService.class.getSimpleName(), Process.THREAD_PRIORITY_MORE_FAVORABLE);
        looperThread.start();
        mForegroundServiceTaskHandler = new ForegroundTaskHandler(looperThread.getLooper());

        //Local notifications
        final IntentFilter localNotificationIntentFilter = new IntentFilter();
        localNotificationIntentFilter.addAction(ACTION_NOTIFY_START_STREAM);
        localNotificationIntentFilter.addAction(ACTION_NOTIFY_STOP_STREAM);
        localNotificationIntentFilter.addAction(ACTION_NOTIFY_CLOSE_APP);

        mLocalNotificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                switch (action) {
                    case ACTION_NOTIFY_START_STREAM:
                        relayMessageViaActivity(SERVICE_MESSAGE_START_STREAMING);
                        break;
                    case ACTION_NOTIFY_STOP_STREAM:
                        relayMessageViaActivity(SERVICE_MESSAGE_STOP_STREAMING);
                        break;
                    case ACTION_NOTIFY_CLOSE_APP:
                        relayMessageViaActivity(SERVICE_MESSAGE_EXIT);
                        break;
                }
            }
        };

        registerReceiver(mLocalNotificationReceiver, localNotificationIntentFilter);

        // Registering receiver for screen off messages and WiFi changes
        final IntentFilter screenOnOffAndWiFiFilter = new IntentFilter();
        screenOnOffAndWiFiFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenOnOffAndWiFiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    if (getAppSettings().isStopOnSleep() && getAppState().isStreamRunning)
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

        registerReceiver(mBroadcastReceiver, screenOnOffAndWiFiFilter);
        httpServerStartAndCheck();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int messageFromActivity = intent.getIntExtra(EXTRA_SERVICE_MESSAGE, SERVICE_MESSAGE_EMPTY);
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

    @Override
    public void onDestroy() {
        mHttpServer.stop(HttpServer.SERVER_STOP, null);
        stopForeground(true);
        unregisterReceiver(mBroadcastReceiver);
        unregisterReceiver(mLocalNotificationReceiver);
        if (mediaProjection != null) mediaProjection.unregisterCallback(mProjectionCallback);
        mForegroundServiceTaskHandler.getLooper().quit();
    }

    static Intent getStartIntent(final Context context) {
        return new Intent(context, ForegroundService.class)
                .putExtra(EXTRA_SERVICE_MESSAGE, SERVICE_MESSAGE_PREPARE_STREAMING);
    }

    static void setMediaProjection(final MediaProjection mediaProjection) {
        sServiceInstance.mediaProjection = mediaProjection;
    }

    @Nullable
    static MediaProjectionManager getProjectionManager() {
        return sServiceInstance == null ? null : sServiceInstance.sProjectionManager;
    }

    @Nullable
    static MediaProjection getMediaProjection() {
        return sServiceInstance == null ? null : sServiceInstance.mediaProjection;
    }

    @Nullable
    static ImageGenerator getImageGenerator() {
        return sServiceInstance == null ? null : sServiceInstance.mImageGenerator;
    }

    @Nullable
    static ConcurrentLinkedQueue<Integer> getServiceMessages() {
        return sServiceInstance == null ? null : sServiceInstance.mServiceMessages;
    }

    static void errorInImageGenerator() {
        if (sServiceInstance != null)
            sServiceInstance.relayMessageViaActivity(SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR);
    }

    private void serviceStartStreaming() {
        stopForeground(true);
        mForegroundServiceTaskHandler.obtainMessage(ForegroundTaskHandler.HANDLER_START_STREAMING).sendToTarget();
        startForeground(120, getNotificationStop());
        if (mediaProjection != null) mediaProjection.registerCallback(mProjectionCallback, null);
    }

    private void serviceStopStreaming() {
        stopForeground(true);
        mForegroundServiceTaskHandler.obtainMessage(ForegroundTaskHandler.HANDLER_STOP_STREAMING).sendToTarget();
        startForeground(110, getNotificationStart());
        if (mediaProjection != null) mediaProjection.unregisterCallback(mProjectionCallback);
        mImageGenerator.addDefaultScreen(getApplicationContext());
    }

    private void serviceRestartHTTP() {
        mHttpServer.stop(HttpServer.SERVER_SETTINGS_RESTART,
                NotifyImageGenerator.getClientNotifyImage(getApplicationContext(), HttpServer.SERVER_SETTINGS_RESTART));
        mImageGenerator.addDefaultScreen(getApplicationContext());
        httpServerStartAndCheck();
    }

    private void serviceUpdatePinStatus() {
        mHttpServer.stop(HttpServer.SERVER_PIN_RESTART,
                NotifyImageGenerator.getClientNotifyImage(getApplicationContext(), HttpServer.SERVER_PIN_RESTART));
        mImageGenerator.addDefaultScreen(getApplicationContext());
        httpServerStartAndCheck();
    }

    private void relayMessageViaActivity(final int message) {
        if (message == SERVICE_MESSAGE_EMPTY) return;
        mServiceMessages.add(message);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sendBroadcast(new Intent(ACTION_DEFAULT)
                .putExtra(EXTRA_SERVICE_MESSAGE, SERVICE_MESSAGE_HAS_NEW), PERMISSION_RECEIVE_BROADCAST);
    }

    private void httpServerStartAndCheck() {
        AppContext.getAppState().mHttpServerStatus = mHttpServer.start();
        if (AppContext.getAppState().mHttpServerStatus == HttpServer.SERVER_ERROR_PORT_IN_USE) {
            getAppViewState().httpServerError.set(true);
            relayMessageViaActivity(SERVICE_MESSAGE_HTTP_PORT_IN_USE);
        } else {
            getAppViewState().httpServerError.set(false);
            relayMessageViaActivity(SERVICE_MESSAGE_HTTP_OK);
        }
    }


    private Notification getNotificationStart() {
        final Intent mainActivityIntent =
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final PendingIntent pendingMainActivityIntent =
                PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder startNotificationBuilder = new NotificationCompat.Builder(this);
        startNotificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        startNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        startNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        startNotificationBuilder.setContentTitle(getString(R.string.ready_to_stream));
        startNotificationBuilder.setContentText(getString(R.string.press_start));
        startNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        startNotificationBuilder.addAction(R.drawable.ic_play_arrow_24dp,
                getString(R.string.start).toUpperCase(),
                PendingIntent.getBroadcast(this, 0, new Intent(ACTION_NOTIFY_START_STREAM), 0));
        startNotificationBuilder.addAction(R.drawable.ic_clear_24dp,
                getString(R.string.exit).toUpperCase(),
                PendingIntent.getBroadcast(this, 0, new Intent(ACTION_NOTIFY_CLOSE_APP), 0));
        startNotificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX);
        return startNotificationBuilder.build();
    }

    private Notification getNotificationStop() {
        final Intent mainActivityIntent =
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final PendingIntent pendingMainActivityIntent =
                PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder stopNotificationBuilder = new NotificationCompat.Builder(this);
        stopNotificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        stopNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        stopNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        stopNotificationBuilder.setContentTitle(getString(R.string.stream));
        stopNotificationBuilder.setContentText(getString(R.string.go_to) + getServerAddress());
        stopNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        stopNotificationBuilder.addAction(R.drawable.ic_stop_24dp,
                getString(R.string.stop).toUpperCase(),
                PendingIntent.getBroadcast(this, 0, new Intent(ACTION_NOTIFY_STOP_STREAM), 0));
        stopNotificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX);
        return stopNotificationBuilder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}