package info.dvkr.screenstream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.firebase.crash.FirebaseCrash;

public final class MainActivity extends AppCompatActivity {
    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1;
    private static final int SETTINGS_REQUEST_CODE = 2;

    private TextView clientsCount;
    private TextView severAddress;
    private ToggleButton toggleStream;
    private MediaProjection.Callback projectionCallback;
    private BroadcastReceiver broadcastReceiverFromService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clientsCount = (TextView) findViewById(R.id.clientsCount);
        severAddress = (TextView) findViewById(R.id.severAddress);

        if (!ApplicationContext.isForegroundServiceRunning()) {
            final Intent foregroundService = new Intent(this, ForegroundService.class);
            foregroundService.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_PREPARE_STREAMING);
            startService(foregroundService);
        }

        toggleStream = (ToggleButton) findViewById(R.id.toggleStream);
        toggleStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (toggleStream.isChecked()) tryStartStreaming();
                else stopStreaming();
            }
        });

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopStreaming();
            }
        };

        // Registering receiver for broadcast messages
        broadcastReceiverFromService = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(ForegroundService.SERVICE_ACTION)) {
                    final int serviceMessage = intent.getIntExtra(ForegroundService.SERVICE_MESSAGE, 0);

                    // Service ask to update status
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_UPDATE_STATUS)
                        updateServiceStatus();

                    // Service ask to start streaming
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_START_STREAMING)
                        tryStartStreaming();

                    // Service ask to stop streaming
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_STOP_STREAMING)
                        stopStreaming();

                    // Service ask to close application
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_EXIT) {
                        stopService(new Intent(MainActivity.this, ForegroundService.class));
                        finish();
                        System.exit(0);
                    }

                    // Service ask to update client count
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_GET_CLIENT_COUNT) {
                        final String clientCount = String.format(
                                getResources().getString(R.string.connected_clients),
                                intent.getIntExtra(ForegroundService.SERVICE_MESSAGE_CLIENTS_COUNT, 0)
                        );
                        clientsCount.setText(clientCount);
                    }

                    // Service ask to update server address
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_GET_SERVER_ADDRESS) {
                        if (ApplicationContext.isWiFIConnected()) {
                            severAddress.setText(intent.getStringExtra(ForegroundService.SERVICE_MESSAGE_SERVER_ADDRESS));
                            toggleStream.setEnabled(true);
                        } else {
                            severAddress.setText(getResources().getString(R.string.no_wifi_connected));
                            toggleStream.setEnabled(false);
                            stopStreaming();
                        }

                    }

                }
            }
        };

        registerReceiver(broadcastReceiverFromService, new IntentFilter(ForegroundService.SERVICE_ACTION), ForegroundService.SERVICE_PERMISSION, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateServiceStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ApplicationContext.getMediaProjection() != null)
            ApplicationContext.getMediaProjection().unregisterCallback(projectionCallback);

        unregisterReceiver(broadcastReceiverFromService);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SCREEN_CAPTURE_REQUEST_CODE:
                if (resultCode != RESULT_OK) {
                    Toast.makeText(this, getResources().getString(R.string.cast_permission_deny), Toast.LENGTH_SHORT).show();
                    toggleStream.setChecked(false);
                    return;
                }

                startStreaming(resultCode, data);
                break;
            case SETTINGS_REQUEST_CODE:
                final boolean isServerPortChanged = ApplicationContext.getApplicationSettings().updateSettings();
                if (isServerPortChanged) restartHTTPServer();
                break;
            default:
                FirebaseCrash.log("Unknown request code: " + requestCode);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                final Intent intentSettings = new Intent(this, SettingsActivity.class);
                startActivityForResult(intentSettings, SETTINGS_REQUEST_CODE);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateServiceStatus() {
        final Intent getStatus = new Intent(this, ForegroundService.class);
        getStatus.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_GET_STATUS);
        startService(getStatus);
    }

    private void tryStartStreaming() {
        if (!ApplicationContext.isWiFIConnected()) return;
        toggleStream.setChecked(true);
        if (ApplicationContext.isStreamRunning()) return;
        startActivityForResult(ApplicationContext.getProjectionManager().createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
    }

    private void startStreaming(final int resultCode, final Intent data) {
        ApplicationContext.setMediaProjection(resultCode, data);
        ApplicationContext.getMediaProjection().registerCallback(projectionCallback, null);

        final Intent startStreaming = new Intent(this, ForegroundService.class);
        startStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_START_STREAMING);
        startService(startStreaming);

        if (ApplicationContext.getApplicationSettings().isMinimizeOnStream()) {
            final Intent minimiseMyself = new Intent(Intent.ACTION_MAIN);
            minimiseMyself.addCategory(Intent.CATEGORY_HOME);
            minimiseMyself.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(minimiseMyself);
        }
    }

    private void stopStreaming() {
        toggleStream.setChecked(false);
        if (!ApplicationContext.isStreamRunning()) return;

        ApplicationContext.getMediaProjection().unregisterCallback(projectionCallback);

        final Intent stopStreaming = new Intent(this, ForegroundService.class);
        stopStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_STOP_STREAMING);
        startService(stopStreaming);
    }

    private void restartHTTPServer() {
        stopStreaming();
        final Intent resrtartHTTP = new Intent(this, ForegroundService.class);
        resrtartHTTP.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_RESTART_HTTP);
        startService(resrtartHTTP);
    }

}