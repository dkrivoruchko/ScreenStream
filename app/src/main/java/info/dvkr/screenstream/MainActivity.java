package info.dvkr.screenstream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.firebase.crash.FirebaseCrash;

import info.dvkr.screenstream.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1;
    private static final int SETTINGS_REQUEST_CODE = 2;

    private MediaProjection.Callback projectionCallback;
    private BroadcastReceiver broadcastReceiverFromService;
    private Snackbar portInUseSnackbar;
    private Menu mainMenu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setAppState(AppContext.getAppState());

        if (!AppContext.isForegroundServiceRunning()) {
            final Intent foregroundService = new Intent(this, ForegroundService.class);
            foregroundService.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_PREPARE_STREAMING);
            startService(foregroundService);
        }

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
                    Log.wtf(">>>>>>>>> serviceMessage", "" + serviceMessage);

                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_EMPTY) return;

                    // Service ask to get new message
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HAS_NEW)
                        updateServiceStatus(ForegroundService.SERVICE_MESSAGE_GET_CURRENT);

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

                    // Service ask notify HTTP Server port in use
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HTTP_PORT_IN_USE) {
                        if (!portInUseSnackbar.isShown()) portInUseSnackbar.show();
                        AppContext.getAppState().httpServerError.set(true);
                    }

                    // Service ask notify HTTP Server ok
                    if (serviceMessage == ForegroundService.SERVICE_MESSAGE_HTTP_OK) {
                        if (portInUseSnackbar.isShown()) portInUseSnackbar.dismiss();
                        AppContext.getAppState().httpServerError.set(false);
                    }

                }
            }
        };

        registerReceiver(broadcastReceiverFromService, new IntentFilter(ForegroundService.SERVICE_ACTION), ForegroundService.SERVICE_PERMISSION, null);
    }

    public void onToggleButtonClick(View v) {
        if (AppContext.isStreamRunning()) {
            stopStreaming();
        } else {
            ((ToggleButton) v).setChecked(false);
            tryStartStreaming();
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        updateServiceStatus(ForegroundService.SERVICE_MESSAGE_GET_CURRENT);
        if (ForegroundService.getHttpServerStatus() == HTTPServer.SERVER_ERROR_PORT_IN_USE) {
            portInUseSnackbar.show();
            AppContext.getAppState().httpServerError.set(true);
        }
    }

    @Override
    protected void onDestroy() {
        final MediaProjection mediaProjection = AppContext.getMediaProjection();
        if (mediaProjection != null)
            mediaProjection.unregisterCallback(projectionCallback);

        unregisterReceiver(broadcastReceiverFromService);
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SCREEN_CAPTURE_REQUEST_CODE:
                if (resultCode != RESULT_OK) {
                    Toast.makeText(this, getResources().getString(R.string.cast_permission_deny), Toast.LENGTH_SHORT).show();
                    return;
                }
                startStreaming(resultCode, data);
                break;
            case SETTINGS_REQUEST_CODE:
                final boolean isServerPortChanged = AppContext.getAppSettings().updateSettings();
                if (isServerPortChanged) {
                    AppContext.getAppState().serverAddress.set(AppContext.getServerAddress());

                    final Intent restartHTTP = new Intent(this, ForegroundService.class);
                    restartHTTP.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_RESTART_HTTP);
                    startService(restartHTTP);
                }
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

    private void updatePinStatus(final boolean isServerPortChanged) {
        AppContext.getAppState().pinAutoHide.set(AppContext.getAppSettings().isPinAutoHide());

        final boolean newIsPinEnabled = AppContext.getAppSettings().isEnablePin();
        final String newPin = AppContext.getAppSettings().getUserPin();

        if (newIsPinEnabled != AppContext.getAppState().pinEnabled.get() || !newPin.equals(AppContext.getAppState().streamPin.get())) {
            AppContext.getAppState().pinEnabled.set(newIsPinEnabled);
            AppContext.getAppState().streamPin.set(newPin);

            if (!isServerPortChanged) {
                final Intent updatePinStatus = new Intent(this, ForegroundService.class);
                updatePinStatus.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_UPDATE_PIN_STATUS);
                startService(updatePinStatus);
            }
        }
    }

    private void updateServiceStatus(final int message) {
        final Intent getStatus = new Intent(this, ForegroundService.class);
        getStatus.putExtra(ForegroundService.SERVICE_MESSAGE, message);
        startService(getStatus);
    }

    private void tryStartStreaming() {
        if (!AppContext.isWiFiConnected()) return;
        if (ForegroundService.getHttpServerStatus() != HTTPServer.SERVER_OK) return;
        if (AppContext.isStreamRunning()) return;
        startActivityForResult(AppContext.getProjectionManager().createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
    }

    private void startStreaming(final int resultCode, final Intent data) {
        AppContext.setMediaProjection(resultCode, data);
        final MediaProjection mediaProjection = AppContext.getMediaProjection();
        if (mediaProjection == null) return;
        mediaProjection.registerCallback(projectionCallback, null);

        final Intent startStreaming = new Intent(this, ForegroundService.class);
        startStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_START_STREAMING);
        startService(startStreaming);

        if (AppContext.getAppSettings().isMinimizeOnStream()) {
            final Intent minimiseMyself = new Intent(Intent.ACTION_MAIN);
            minimiseMyself.addCategory(Intent.CATEGORY_HOME);
            minimiseMyself.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(minimiseMyself);
        }
    }

    private void stopStreaming() {
        if (!AppContext.isStreamRunning()) return;
        final MediaProjection mediaProjection = AppContext.getMediaProjection();
        if (mediaProjection == null) return;
        mediaProjection.unregisterCallback(projectionCallback);

        final Intent stopStreaming = new Intent(this, ForegroundService.class);
        stopStreaming.putExtra(ForegroundService.SERVICE_MESSAGE, ForegroundService.SERVICE_MESSAGE_STOP_STREAMING);
        startService(stopStreaming);

        if (AppContext.getAppSettings().isAutoChangePin())
            AppContext.getAppSettings().generateAndSaveNewPin();

        updatePinStatus(false);
    }
}