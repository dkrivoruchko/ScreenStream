package info.dvkr.screenstream.view;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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

import com.google.firebase.crash.FirebaseCrash;
import com.squareup.otto.Subscribe;

import info.dvkr.screenstream.R;
import info.dvkr.screenstream.data.BusMessages;
import info.dvkr.screenstream.databinding.ActivityMainBinding;
import info.dvkr.screenstream.service.ForegroundService;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppPreference;
import static info.dvkr.screenstream.ScreenStreamApplication.getMainActivityViewModel;
import static info.dvkr.screenstream.service.ForegroundService.getProjectionManager;
import static info.dvkr.screenstream.service.ForegroundService.setMediaProjection;


public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1;
    private static final int REQUEST_CODE_SETTINGS = 2;

    private Snackbar mPortInUseSnackbar;
    private Menu mMainMenu;

    public static Intent getStartIntent(Context context) {
        return new Intent(context, MainActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActivityMainBinding activityMainBinding =
                DataBindingUtil.setContentView(this, R.layout.activity_main);
        activityMainBinding.setViewModel(getMainActivityViewModel());

        getMainActivityViewModel().setServerAddress(getAppData().getServerAddress());
        getMainActivityViewModel().setWiFiConnected(getAppData().isWiFiConnected());

        mPortInUseSnackbar = Snackbar.make(activityMainBinding.layoutMainView,
                R.string.main_activity_snackbar_port_in_use,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.main_activity_menu_settings, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onOptionsItemSelected(mMainMenu.findItem(R.id.menu_settings));
                    }
                })
                .setActionTextColor(Color.GREEN);

        ((TextView) mPortInUseSnackbar.getView().findViewById(android.support.design.R.id.snackbar_text))
                .setTextColor(Color.RED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getAppData().getMessagesBus().register(this);

        if (getAppData().isStreamStartRequested()) {
            getAppData().getMessagesBus().post(BusMessages.MESSAGE_ACTION_STREAMING_TRY_START);
        }

        if (BusMessages.MESSAGE_STATUS_HTTP_ERROR_PORT_IN_USE.equals(getAppData().getHttpServerStatus())) {
            if (!mPortInUseSnackbar.isShown()) mPortInUseSnackbar.show();
        } else {
            if (mPortInUseSnackbar.isShown()) mPortInUseSnackbar.dismiss();
        }
        getAppData().setActivityRunning(true);
    }


    @Subscribe
    public void onBusEvent(String busMessage) {
        switch (busMessage) {
            case BusMessages.MESSAGE_ACTION_STREAMING_TRY_START:
                getAppData().setStreamStartRequested(false);
                if (!getAppData().isWiFiConnected() || getAppData().isStreamRunning()) return;
                if (!BusMessages.MESSAGE_STATUS_HTTP_OK.equals(getAppData().getHttpServerStatus()))
                    return;
                final MediaProjectionManager projectionManager = getProjectionManager();
                if (projectionManager != null) {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
                }
                break;
            case BusMessages.MESSAGE_STATUS_HTTP_ERROR_PORT_IN_USE:
                if (!mPortInUseSnackbar.isShown()) mPortInUseSnackbar.show();
                break;
            case BusMessages.MESSAGE_STATUS_HTTP_OK:
                if (mPortInUseSnackbar.isShown()) mPortInUseSnackbar.dismiss();
                break;
            case BusMessages.MESSAGE_STATUS_IMAGE_GENERATOR_ERROR:
                getAppData().getMessagesBus().post(BusMessages.MESSAGE_ACTION_STREAMING_STOP);
                if (!isFinishing())
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.main_activity_error_title))
                            .setMessage(getString(R.string.main_activity_error_msg_unknown_format))
                            .setIcon(R.drawable.ic_main_activity_error_24dp)
                            .setPositiveButton(R.string.main_activity_error_action_ok, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                }
                            })
                            .show();
                break;
        }
    }

    @Override
    protected void onStop() {
        getAppData().getMessagesBus().unregister(this);
        getAppData().setActivityRunning(false);
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        mMainMenu = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                startActivityForResult(SettingsActivity.getStartIntent(this), REQUEST_CODE_SETTINGS);
                return true;
            case R.id.menu_exit:
                ForegroundService.stopService();
                System.exit(0);
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
                    Toast.makeText(this, getString(R.string.main_activity_toast_cast_permission_deny), Toast.LENGTH_SHORT).show();
                    return;
                }
                final MediaProjectionManager projectionManager = getProjectionManager();
                if (projectionManager == null) return;
                final MediaProjection mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                if (mediaProjection == null) return;
                setMediaProjection(mediaProjection);

                getAppData().getMessagesBus().post(BusMessages.MESSAGE_ACTION_STREAMING_START);

                if (getAppPreference().isMinimizeOnStream()) {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }

                break;
            case REQUEST_CODE_SETTINGS:
                getAppPreference().updatePreference();
                break;
            default:
                FirebaseCrash.log("Unknown request code: " + requestCode);
        }
    }
}