package info.dvkr.screenstream;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

public final class ForegroundService extends Service {
    private static ForegroundService foregroundService;

    // Fields for streaming
    private HTTPServer httpServer;
    private ImageGenerator imageGenerator;
    private TaskHandler foregroundServiceTaskHandler;

    // Fields for broadcast
    static final String SERVICE_ACTION = "info.dvkr.screenstream.ForegroundService.SERVICE_ACTION";

    static final String SERVICE_PERMISSION = "info.dvkr.screenstream.RECEIVE_BROADCAST";
    static final String SERVICE_MESSAGE = "SERVICE_MESSAGE";
    static final int SERVICE_MESSAGE_GET_STATUS = 1000;
    static final int SERVICE_MESSAGE_UPDATE_STATUS = 1005;
    static final int SERVICE_MESSAGE_PREPARE_STREAMING = 1010;
    static final int SERVICE_MESSAGE_START_STREAMING = 1020;
    static final int SERVICE_MESSAGE_STOP_STREAMING = 1030;
    static final int SERVICE_MESSAGE_RESTART_HTTP = 1040;
    static final int SERVICE_MESSAGE_EXIT = 1100;

    static final String SERVICE_MESSAGE_CLIENTS_COUNT = "SERVICE_MESSAGE_CLIENTS_COUNT";
    static final int SERVICE_MESSAGE_GET_CLIENT_COUNT = 1040;
    static final String SERVICE_MESSAGE_SERVER_ADDRESS = "SERVICE_MESSAGE_SERVER_ADDRESS";
    static final int SERVICE_MESSAGE_GET_SERVER_ADDRESS = 1050;

    private int currentServiceMessage;

    // Fields for notifications
    private Notification startNotification;
    private BroadcastReceiver localNotificationReceiver;
    private final String KEY_START = "info.dvkr.screenstream.ForegroundService.startStream";
    private final Intent startStreamIntent = new Intent(KEY_START);
    private final String KEY_STOP = "info.dvkr.screenstream.ForegroundService.stopStream";
    private final Intent stopStreamIntent = new Intent(KEY_STOP);
    private final String KEY_CLOSE = "info.dvkr.screenstream.ForegroundService.closeService";
    private final Intent closeIntent = new Intent(KEY_CLOSE);

    private BroadcastReceiver broadcastReceiver;

    @Override
    public void onCreate() {
        foregroundService = this;

        httpServer = new HTTPServer();
        imageGenerator = new ImageGenerator(
                getResources().getString(R.string.press),
                getResources().getString(R.string.start_stream),
                getResources().getString(R.string.on_device)
        );

        // Starting thread Handler
        final HandlerThread looperThread = new HandlerThread("ForegroundServiceHandlerThread", Process.THREAD_PRIORITY_MORE_FAVORABLE);
        looperThread.start();
        foregroundServiceTaskHandler = new TaskHandler(looperThread.getLooper());

        //Local notifications
        startNotification = getNotificationStart();

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
                if (ApplicationContext.getApplicationSettings().isPauseOnSleep())
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF))
                        if (ApplicationContext.isStreamRunning()) {
                            currentServiceMessage = SERVICE_MESSAGE_STOP_STREAMING;
                            relayMessageViaActivity();
                        }

                if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    currentServiceMessage = SERVICE_MESSAGE_GET_STATUS;
                    sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_UPDATE_STATUS), SERVICE_PERMISSION);
                }
            }
        };

        registerReceiver(broadcastReceiver, screenOnOffFilter);

        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_UPDATE_STATUS), SERVICE_PERMISSION);

        imageGenerator.addDefaultScreen();
        httpServer.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int messageFromActivity = intent.getIntExtra(SERVICE_MESSAGE, 0);
        if (messageFromActivity == 0) return START_NOT_STICKY;

        if (messageFromActivity == SERVICE_MESSAGE_PREPARE_STREAMING) {
            startForeground(110, startNotification);
            ApplicationContext.setIsForegroundServiceRunning(true);
        }

        if (messageFromActivity == SERVICE_MESSAGE_GET_STATUS) {
            sendCurrentServiceMessage();
            sendServerAddress();
            sendClientCount();
        }

        if (messageFromActivity == SERVICE_MESSAGE_START_STREAMING) {
            stopForeground(true);
            foregroundServiceTaskHandler.obtainMessage(TaskHandler.HANDLER_START_STREAMING).sendToTarget();
            startForeground(120, getNotificationStop());
        }

        if (messageFromActivity == SERVICE_MESSAGE_STOP_STREAMING) {
            stopForeground(true);
            foregroundServiceTaskHandler.obtainMessage(TaskHandler.HANDLER_STOP_STREAMING).sendToTarget();
            startForeground(110, startNotification);

            imageGenerator.addDefaultScreen();
        }

        if (messageFromActivity == SERVICE_MESSAGE_RESTART_HTTP) {
            httpServer.stop();
            imageGenerator.addDefaultScreen();
            httpServer.start();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        httpServer.stop();
        stopForeground(true);
        unregisterReceiver(broadcastReceiver);
        unregisterReceiver(localNotificationReceiver);
        foregroundServiceTaskHandler.getLooper().quit();
    }

    private void relayMessageViaActivity() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_UPDATE_STATUS), SERVICE_PERMISSION);
    }

    // Static methods
    static ImageGenerator getImageGenerator() {
        return foregroundService.imageGenerator;
    }

    static void addClient(final Client client) {
        ApplicationContext.getClientQueue().add(client);
        foregroundService.sendClientCount();
    }

    static void removeClient(final Client client) {
        ApplicationContext.getClientQueue().remove(client);
        foregroundService.sendClientCount();
    }

    static void clearClients() {
        ApplicationContext.getClientQueue().clear();
        foregroundService.sendClientCount();
    }

    // Private methods
    private Notification getNotificationStart() {
        final Intent mainActivityIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingMainActivityIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder startNotificationBuilder = new NotificationCompat.Builder(this);
        startNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        startNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        startNotificationBuilder.setContentTitle(getResources().getString(R.string.ready_to_stream));
        startNotificationBuilder.setContentText(getResources().getString(R.string.press_start));
        startNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        startNotificationBuilder.addAction(R.drawable.ic_play_arrow_24dp, getResources().getString(R.string.start), PendingIntent.getBroadcast(this, 0, startStreamIntent, 0));
        startNotificationBuilder.addAction(R.drawable.ic_clear_24dp, getResources().getString(R.string.close), PendingIntent.getBroadcast(this, 0, closeIntent, 0));
        return startNotificationBuilder.build();
    }

    private Notification getNotificationStop() {
        final Intent mainActivityIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingMainActivityIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, 0);

        final NotificationCompat.Builder stopNotificationBuilder = new NotificationCompat.Builder(this);
        stopNotificationBuilder.setSmallIcon(R.drawable.ic_cast_http_24dp);
        stopNotificationBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        stopNotificationBuilder.setContentTitle(getResources().getString(R.string.stream));
        stopNotificationBuilder.setContentText(getResources().getString(R.string.go_to) + ApplicationContext.getServerAddress());
        stopNotificationBuilder.setContentIntent(pendingMainActivityIntent);
        stopNotificationBuilder.addAction(R.drawable.ic_stop_24dp, getResources().getString(R.string.stop), PendingIntent.getBroadcast(this, 0, stopStreamIntent, 0));
        return stopNotificationBuilder.build();
    }

    private void sendCurrentServiceMessage() {
        sendBroadcast(new Intent(SERVICE_ACTION).putExtra(SERVICE_MESSAGE, currentServiceMessage),
                SERVICE_PERMISSION);
        currentServiceMessage = 0;
    }

    private void sendClientCount() {
        sendBroadcast(new Intent(SERVICE_ACTION)
                        .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_GET_CLIENT_COUNT)
                        .putExtra(SERVICE_MESSAGE_CLIENTS_COUNT, ApplicationContext.getClientQueue().size()),
                SERVICE_PERMISSION);
    }

    private void sendServerAddress() {
        sendBroadcast(new Intent(SERVICE_ACTION)
                        .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_GET_SERVER_ADDRESS)
                        .putExtra(SERVICE_MESSAGE_SERVER_ADDRESS, ApplicationContext.getServerAddress()),
                SERVICE_PERMISSION);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}