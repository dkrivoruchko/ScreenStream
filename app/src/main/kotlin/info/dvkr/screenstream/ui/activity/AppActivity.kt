package info.dvkr.screenstream.ui.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.databinding.ActivityAppBinding
import info.dvkr.screenstream.logging.sendLogsInEmail
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.TileActionService
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.flow.first

class AppActivity : CastPermissionActivity(R.layout.activity_app) {

    companion object {
        fun getAppActivityIntent(context: Context): Intent = Intent(context, AppActivity::class.java)

        fun getStartIntent(context: Context): Intent = getAppActivityIntent(context)
    }

    private val binding by viewBinding { activity -> ActivityAppBinding.bind(activity.findViewById(R.id.container)) }

    private val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f)
    private val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f)
    private val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
    private var lastServiceMessage: ServiceMessage.ServiceState? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BaseApp.LOGGING_ON_KEY) setLogging()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLogging()
        routeIntentAction(intent)
        (application as BaseApp).sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launchWhenResumed {
                if (isNotificationPermissionGranted().not() || appSettings.addTileAsked.first()) return@launchWhenResumed
                appSettings.setAddTileAsked(true)
                TileActionService.askToAddTile(this@AppActivity)
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        with(findNavController(R.id.fr_activity_app_nav_host_fragment)) {
            binding.bottomNavigationActivityApp.setupWithNavController(this)
            addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.nav_exitFragment) {
                    IntentAction.Exit.sendToAppService(this@AppActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        routeIntentAction(intent)
    }

    override fun onDestroy() {
        (application as BaseApp).sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun routeIntentAction(intent: Intent?) {
        val intentAction = IntentAction.fromIntent(intent) ?: return
        XLog.d(getLog("routeIntentAction", "IntentAction: $intentAction"))

        if (intentAction is IntentAction.StartStream) {
            IntentAction.StartStream.sendToAppService(this)
        }
    }

    @SuppressLint("CheckResult")
    private fun setLogging() {
        val loggingOn = (application as BaseApp).isLoggingOn
        binding.llActivityAppLogs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        binding.vActivityAppLogs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        if (loggingOn)
            binding.bActivityAppSendLogs.setOnClickListener {
                MaterialDialog(this, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    lifecycleOwner(this@AppActivity)
                    title(R.string.app_activity_send_logs_dialog_title)
                    icon(R.drawable.ic_about_feedback_24dp)
                    message(R.string.app_activity_send_logs_dialog_message)
                    input(
                        waitForPositiveButton = false,
                        allowEmpty = true,
                        inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    ) { dialog, text -> dialog.setActionButtonEnabled(WhichButton.NEGATIVE, text.isNotBlank()) }
                    positiveButton(android.R.string.cancel)
                    negativeButton(android.R.string.ok) {
                        sendLogsInEmail(applicationContext, getInputField().text.toString())
                    }
                    @Suppress("DEPRECATION")
                    neutralButton(R.string.app_activity_send_logs_dialog_neutral) {
                        (application as BaseApp).isLoggingOn = false
                    }
                    setActionButtonEnabled(WhichButton.NEGATIVE, false)
                }
            }
        else
            binding.bActivityAppSendLogs.setOnClickListener(null)
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        if (serviceMessage is ServiceMessage.ServiceState) {
            lastServiceMessage != serviceMessage || return
            XLog.d(this@AppActivity.getLog("onServiceMessage", "$serviceMessage"))

            binding.bottomNavigationActivityApp.menu.findItem(R.id.menu_fab).title =
                if (serviceMessage.isStreaming) getString(R.string.bottom_menu_stop)
                else getString(R.string.bottom_menu_start)

            with(binding.fabActivityAppStartStop) {
                visibility = View.VISIBLE

                isEnabled = serviceMessage.isBusy.not()

                backgroundTintList = if (serviceMessage.isBusy) {
                    ContextCompat.getColorStateList(this@AppActivity, R.color.colorIconDisabled)
                } else {
                    ContextCompat.getColorStateList(this@AppActivity, R.color.colorAccent)
                }

                contentDescription = if (serviceMessage.isStreaming) {
                    setImageResource(R.drawable.ic_fab_stop_24dp)
                    setOnClickListener { IntentAction.StopStream.sendToAppService(this@AppActivity) }
                    getString(R.string.bottom_menu_stop)
                } else {
                    setImageResource(R.drawable.ic_fab_start_24dp)
                    setOnClickListener { IntentAction.StartStream.sendToAppService(this@AppActivity) }
                    getString(R.string.bottom_menu_start)
                }
            }

            if (serviceMessage.isStreaming != lastServiceMessage?.isStreaming) {
                ObjectAnimator.ofPropertyValuesHolder(binding.fabActivityAppStartStop, scaleX, scaleY, alpha)
                    .apply { interpolator = OvershootInterpolator(); duration = 750 }
                    .start()
            }

            lastServiceMessage = serviceMessage
        }
    }
}