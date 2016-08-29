package info.dvkr.screenstream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.firebase.crash.FirebaseCrash;

import java.util.concurrent.ConcurrentLinkedQueue;

import info.dvkr.screenstream.databinding.ActivityMainBinding;

import static info.dvkr.screenstream.AppContext.getAppSettings;
import static info.dvkr.screenstream.AppContext.getAppState;
import static info.dvkr.screenstream.AppContext.getAppViewState;
import static info.dvkr.screenstream.AppContext.getServerAddress;
import static info.dvkr.screenstream.AppContext.isWiFiConnected;
import static info.dvkr.screenstream.ForegroundService.SERVICE_ACTION;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_EMPTY;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_EXIT;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_HAS_NEW;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_HTTP_OK;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_HTTP_PORT_IN_USE;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_RESTART_HTTP;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_START_STREAMING;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_STOP_STREAMING;
import static info.dvkr.screenstream.ForegroundService.SERVICE_MESSAGE_UPDATE_PIN_STATUS;
import static info.dvkr.screenstream.ForegroundService.SERVICE_PERMISSION;
import static info.dvkr.screenstream.ForegroundService.getMediaProjection;
import static info.dvkr.screenstream.ForegroundService.getProjectionManager;
import static info.dvkr.screenstream.ForegroundService.getServiceMessages;
import static info.dvkr.screenstream.ForegroundService.setMediaProjection;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1;
    private static final int REQUEST_CODE_SETTINGS = 2;

    private BroadcastReceiver broadcastReceiverFromService;
    private Snackbar snackbarPortInUse;
    private Menu menuMain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding activityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        activityMainBinding.setAppState(getAppViewState());

        snackbarPortInUse = Snackbar.make(activityMainBinding.mainView, R.string.snackbar_port_in_use, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.settings, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onOptionsItemSelected(menuMain.findItem(R.id.menu_settings));
                    }
                })
                .setActionTextColor(Color.GREEN);
        ((TextView) snackbarPortInUse.getView().findViewById(android.support.design.R.id.snackbar_text)).setTextColor(Color.RED);

        broadcastReceiverFromService = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(SERVICE_ACTION) &&
                        intent.getIntExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_EMPTY) == SERVICE_MESSAGE_HAS_NEW)
                    processServiceMessages();
            }
        };
    }

    private void processServiceMessages() {
        final ConcurrentLinkedQueue<Integer> serviceMessages = getServiceMessages();
        while (serviceMessages != null && !serviceMessages.isEmpty()) {
            final int serviceMessage = serviceMessages.poll();
//            Log.wtf(">>>>>>>>>>> serviceMessage", "" + serviceMessage);
            switch (serviceMessage) {
                case SERVICE_MESSAGE_START_STREAMING:
                    tryStartStreaming();
                    break;
                case SERVICE_MESSAGE_STOP_STREAMING:
                    stopStreaming();
                    break;
                case SERVICE_MESSAGE_EXIT:
                    applicationClose();
                    break;
                case SERVICE_MESSAGE_HTTP_PORT_IN_USE:
                    if (!snackbarPortInUse.isShown()) snackbarPortInUse.show();
                    break;
                case SERVICE_MESSAGE_HTTP_OK:
                    if (snackbarPortInUse.isShown()) snackbarPortInUse.dismiss();
                    break;
                case SERVICE_MESSAGE_IMAGE_GENERATOR_ERROR:
                    stopStreaming();
                    if (!isFinishing())
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.error))
                                .setMessage(getString(R.string.unknown_format))
                                .setIcon(R.drawable.ic_warning_24dp)
                                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        dialog.dismiss();
                                    }
                                })
                                .show();

                    break;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(broadcastReceiverFromService, new IntentFilter(SERVICE_ACTION), SERVICE_PERMISSION, null);
        processServiceMessages();

        if (AppContext.getAppState().httpServerStatus == HTTPServer.SERVER_ERROR_PORT_IN_USE) {
            snackbarPortInUse.show();
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(broadcastReceiverFromService);
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings, menu);
        menuMain = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS);
                return true;
            case R.id.menu_exit:
                applicationClose();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_SCREEN_CAPTURE:
                if (resultCode != RESULT_OK) {
                    Toast.makeText(this, getString(R.string.cast_permission_deny), Toast.LENGTH_SHORT).show();
                    return;
                }
                startStreaming(resultCode, data);
                break;
            case REQUEST_CODE_SETTINGS:
                final boolean isServerPortChanged = getAppSettings().updateSettings();
                if (isServerPortChanged) {
                    getAppViewState().serverAddress.set(getServerAddress());

                    startService(new Intent(this, ForegroundService.class)
                            .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_RESTART_HTTP));
                }
                updatePinStatus(isServerPortChanged);
                break;
            default:
                FirebaseCrash.log("Unknown request code: " + requestCode);
        }
    }

    public void onToggleButtonClick(View v) {
        if (getAppState().isStreamRunning) {
            stopStreaming();
        } else {
            ((ToggleButton) v).setChecked(false);
            tryStartStreaming();
        }
    }

    private void applicationClose() {
        stopService(new Intent(MainActivity.this, ForegroundService.class));
        finish();
        System.exit(0);
    }

    private void updatePinStatus(final boolean isServerPortChanged) {
        getAppViewState().pinAutoHide.set(getAppSettings().isPinAutoHide());

        final boolean newIsPinEnabled = getAppSettings().isEnablePin();
        final String newPin = getAppSettings().getUserPin();

        if (newIsPinEnabled != getAppViewState().pinEnabled.get() || !newPin.equals(getAppViewState().streamPin.get())) {
            getAppViewState().pinEnabled.set(newIsPinEnabled);
            getAppViewState().streamPin.set(newPin);

            if (!isServerPortChanged) {
                startService(new Intent(this, ForegroundService.class)
                        .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_UPDATE_PIN_STATUS));
            }
        }
    }

    private void tryStartStreaming() {
        if (!isWiFiConnected() || getAppState().isStreamRunning) return;
        if (AppContext.getAppState().httpServerStatus != HTTPServer.SERVER_OK) return;

        final MediaProjectionManager projectionManager = getProjectionManager();
        if (projectionManager == null) return;

        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
    }

    private void startStreaming(final int resultCode, final Intent data) {
        final MediaProjectionManager projectionManager = getProjectionManager();
        if (projectionManager == null) return;
        final MediaProjection mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) return;
        setMediaProjection(mediaProjection);

        startService(new Intent(this, ForegroundService.class)
                .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_START_STREAMING));

        if (getAppSettings().isMinimizeOnStream())
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            );
    }

    private void stopStreaming() {
        if (!getAppState().isStreamRunning || getMediaProjection() == null) return;

        startService(new Intent(this, ForegroundService.class)
                .putExtra(SERVICE_MESSAGE, SERVICE_MESSAGE_STOP_STREAMING));

        if (getAppSettings().isEnablePin() && getAppSettings().isAutoChangePin())
            getAppSettings().generateAndSaveNewPin();

        updatePinStatus(false);
    }
}