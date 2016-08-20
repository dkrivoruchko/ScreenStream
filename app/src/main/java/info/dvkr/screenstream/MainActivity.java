package info.dvkr.screenstream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
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
    private TextView pinNumber;
    private ToggleButton toggleStream;
    private MediaProjection.Callback projectionCallback;
    private BroadcastReceiver broadcastReceiverFromService;
    private Snackbar portInUseSnackbar;
    private Menu mainMenu;

    private String currentPin;
    private boolean isPinEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clientsCount = (TextView) findViewById(R.id.clientsCount);
        severAddress = (TextView) findViewById(R.id.severAddress);
        pinNumber = (TextView) findViewById(R.id.pinNumber);

        isPinEnabled = ApplicationContext.getApplicationSettings().isEnablePin();
        currentPin = ApplicationContext.getApplicationSettings().getUserPin();
        updatePinUI();

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

        portInUseSnackbar = Snackbar.make(findViewById(R.id.mainView), R.string.snackbar_port_in_use, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.settings, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onOptionsItemSelected(mainMenu.findItem(R.id.menu_settings));
                    }
                })
                .setActionTextColor(Color.GREEN);
        ((TextView) portInUseSnackbar.getView().findViewById(android.support.design.R.id.snackbar_text)).setTextColor(Color.RED);


        // Registering receiver for broadcast messages
        broadcastReceiverFromService = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(ForegroundService.SERVICE_ACTION)) {
                    final int serviceMessage = intent.getIntExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_EMPTY);
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_EMPTY) return;

                    // Service ask to get new message
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HAS_NEW)
                        updateServiceStatus(ForegroundService.SERVICE_MESSAGE_GET_CURRENT);

                    // Service ask to update status
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_UPDATE_STATUS)
                        updateServiceStatus(ForegroundService.SERVICE_MESSAGE_GET_STATUS);

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
                        if (ApplicationContext.isWiFiConnected()) {
                            severAddress.setText(intent.getStringExtra(ForegroundService.SERVICE_MESSAGE_SERVER_ADDRESS));
                        } else {
                            severAddress.setText(getResources().getString(R.string.no_wifi_connected));
                            stopStreaming();
                        }
                        toggleStreamEnabler();
                    }

                    // Service ask notify HTTP Server port in use
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HTTP_PORT_IN_USE) {
                        if (!portInUseSnackbar.isShown()) portInUseSnackbar.show();
                        toggleStreamEnabler();
                    }

                    // Service ask notify HTTP Server ok
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HTTP_OK) {
                        if (portInUseSnackbar.isShown()) portInUseSnackbar.dismiss();
                        toggleStreamEnabler();
                    }

                }
            }
        };

        registerReceiver(broadcastReceiverFromService, new IntentFilter(ForegroundService.SERVICE_ACTION), ForegroundService.SERVICE_PERMISSION, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateServiceStatus(ForegroundService.SERVICE_MESSAGE_GET_STATUS);
        if (ForegroundService.getHttpServerStatus() == HTTPServer.SERVER_ERROR_PORT_IN_USE) {
            portInUseSnackbar.show();
            toggleStreamEnabler();
        }
    }

    @Override
    protected void onDestroy() {
        if (ApplicationContext.getMediaProjection() != null)
            ApplicationContext.getMediaProjection().unregisterCallback(projectionCallback);

        unregisterReceiver(broadcastReceiverFromService);
        super.onDestroy();
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
                updatePinStatus(isServerPortChanged);
                break;
            default:
                FirebaseCrash.log("Unknown request code: " + requestCode);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings, menu);
        mainMenu = menu;
        return super.onCreateOptionsMenu(menu);
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

    private void toggleStreamEnabler() {
        final boolean wifiStatus = ApplicationContext.isWiFiConnected();
        final boolean httpStatus = ForegroundService.getHttpServerStatus() == HTTPServer.SERVER_OK;
        toggleStream.setEnabled(wifiStatus && httpStatus);
    }

    private void updatePinUI() {
        if (isPinEnabled) {
            if (ApplicationContext.getApplicationSettings().isPinAutoHide() && ApplicationContext.isStreamRunning()) {
                pinNumber.setText("****");
            } else {
                pinNumber.setText(currentPin);
            }
            pinNumber.setTextColor(ContextCompat.getColor(this, R.color.colorAccent));
            pinNumber.setTypeface(null, Typeface.BOLD);
            findViewById(R.id.pinName).setVisibility(View.VISIBLE);
        } else

        {
            pinNumber.setText(getResources().getString(R.string.pin_disabled));
            pinNumber.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            pinNumber.setTypeface(null, Typeface.NORMAL);
            findViewById(R.id.pinName).setVisibility(View.GONE);
        }

    }

    private void updatePinStatus(final boolean isServerPortChanged) {
        final boolean newIsPinEnabled = ApplicationContext.getApplicationSettings().isEnablePin();
        final String newPin = ApplicationContext.getApplicationSettings().getUserPin();

        if (newIsPinEnabled != isPinEnabled || !newPin.equals(currentPin)) {
            isPinEnabled = newIsPinEnabled;
            currentPin = newPin;

            if (!isServerPortChanged) {
                final Intent updatePinStatus = new Intent(this, ForegroundService.class);
                updatePinStatus.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_UPDATE_PIN_STATUS);
                startService(updatePinStatus);
            }

            updatePinUI();
        }
    }

    private void updateServiceStatus(final int message) {
        final Intent getStatus = new Intent(this, ForegroundService.class);
        getStatus.putExtra(ForegroundService.SERVICE_MESSAGE, message);
        startService(getStatus);
    }

    private void tryStartStreaming() {
        if (!ApplicationContext.isWiFiConnected()) return;
        if (ForegroundService.getHttpServerStatus() != HTTPServer.SERVER_OK) return;
        toggleStream.setChecked(true);
        if (ApplicationContext.isStreamRunning()) return;
        startActivityForResult(ApplicationContext.getProjectionManager().createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
    }

    private void startStreaming(final int resultCode, final Intent data) {
        ApplicationContext.setMediaProjection(resultCode, data);
        final MediaProjection mediaProjection = ApplicationContext.getMediaProjection();
        if (mediaProjection == null) return;
        mediaProjection.registerCallback(projectionCallback, null);

        final Intent startStreaming = new Intent(this, ForegroundService.class);
        startStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_START_STREAMING);
        startService(startStreaming);

        if (ApplicationContext.getApplicationSettings().isPinAutoHide()) {
            pinNumber.setText("****");
        }

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

        final MediaProjection mediaProjection = ApplicationContext.getMediaProjection();
        if (mediaProjection == null) return;
        mediaProjection.unregisterCallback(projectionCallback);

        final Intent stopStreaming = new Intent(this, ForegroundService.class);
        stopStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_STOP_STREAMING);
        startService(stopStreaming);

        if (ApplicationContext.getApplicationSettings().isPinAutoHide()) {
            pinNumber.setText(currentPin);
        }

        if (ApplicationContext.getApplicationSettings().isAutoGeneratePin()) {
            ApplicationContext.getApplicationSettings().setAndSaveUserPin(ApplicationContext.getRandomPin());
            updatePinStatus(true);
        }
    }

    private void restartHTTPServer() {
        stopStreaming();
        final Intent restartHTTP = new Intent(this, ForegroundService.class);
        restartHTTP.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_RESTART_HTTP);
        startService(restartHTTP);
    }
}