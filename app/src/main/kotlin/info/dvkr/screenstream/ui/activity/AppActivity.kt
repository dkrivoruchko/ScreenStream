package info.dvkr.screenstream.ui.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
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
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.databinding.ActivityAppBinding
import info.dvkr.screenstream.logging.sendLogsInEmail
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.viewBinding

class AppActivity : PermissionActivity(R.layout.activity_app) {

    companion object {
        fun getAppActivityIntent(context: Context): Intent =
            Intent(context.applicationContext, AppActivity::class.java)

        fun getStartIntent(context: Context): Intent =
            getAppActivityIntent(context)
    }

    private val binding by viewBinding { activity -> ActivityAppBinding.bind(activity.findViewById(R.id.container)) }

    private val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f)
    private val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f)
    private val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
    private var lastServiceMessage: ServiceMessage.ServiceState? = null
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key == Settings.Key.LOGGING_ON) setLogging(settings.loggingOn)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        routeIntentAction(intent)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        with(findNavController(R.id.fr_activity_app_nav_host_fragment)) {
            binding.bottomNavigationActivityApp.setupWithNavController(this)
            addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.nav_exitFragment) IntentAction.Exit.sendToAppService(this@AppActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        routeIntentAction(intent)
    }

    override fun onStart() {
        super.onStart()
        settings.registerChangeListener(settingsListener)
        setLogging(settings.loggingOn)
    }

    override fun onStop() {
        settings.unregisterChangeListener(settingsListener)
        super.onStop()
    }

    private fun routeIntentAction(intent: Intent?) {
        val intentAction = IntentAction.fromIntent(intent)
        intentAction != null || return
        XLog.d(getLog("routeIntentAction", "IntentAction: $intentAction"))

        when (intentAction) {
            IntentAction.StartStream -> IntentAction.StartStream.sendToAppService(this)
        }
    }

    private fun setLogging(loggingOn: Boolean) {
        binding.llActivityAppLogs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        binding.vActivityAppLogs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        binding.bActivityAppSendLogs.setOnClickListener {
            MaterialDialog(this, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                lifecycleOwner(this@AppActivity)
                title(R.string.app_activity_send_logs_dialog_title)
                icon(R.drawable.ic_about_feedback_24dp)
                message(R.string.app_activity_send_logs_dialog_message)
                input(
                    waitForPositiveButton = false, allowEmpty = true, inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                ) { dialog, text -> dialog.setActionButtonEnabled(WhichButton.NEGATIVE, text.isNotBlank()) }
                positiveButton(android.R.string.cancel)
                negativeButton(android.R.string.yes) {
                    sendLogsInEmail(applicationContext, getInputField().text.toString())
                }
                @Suppress("DEPRECATION")
                neutralButton(R.string.app_activity_send_logs_dialog_neutral) { settings.loggingOn = false }
                setActionButtonEnabled(WhichButton.NEGATIVE, false)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                lastServiceMessage != serviceMessage || return
                XLog.d(this@AppActivity.getLog("onServiceMessage", "$serviceMessage"))

                binding.bottomNavigationActivityApp.menu.findItem(R.id.menu_fab).title =
                    if (serviceMessage.isStreaming) getString(R.string.bottom_menu_stop)
                    else getString(R.string.bottom_menu_start)

                with(binding.fabActivityAppStartStop) {
                    visibility = View.VISIBLE
                    if (serviceMessage.isBusy) {
                        isEnabled = false
                        backgroundTintList =
                            ContextCompat.getColorStateList(this@AppActivity, R.color.colorIconDisabled)
                    } else {
                        isEnabled = true
                        backgroundTintList = ContextCompat.getColorStateList(this@AppActivity, R.color.colorAccent)
                    }


                    if (serviceMessage.isStreaming) {
                        setImageResource(R.drawable.ic_fab_stop_24dp)
                        setOnClickListener { IntentAction.StopStream.sendToAppService(this@AppActivity) }
                    } else {
                        setImageResource(R.drawable.ic_fab_start_24dp)
                        setOnClickListener { IntentAction.StartStream.sendToAppService(this@AppActivity) }
                    }
                }

                if (serviceMessage.isStreaming != lastServiceMessage?.isStreaming) {
                    ObjectAnimator.ofPropertyValuesHolder(binding.fabActivityAppStartStop, scaleX, scaleY, alpha)
                        .apply {
                            interpolator = OvershootInterpolator()
                            duration = 750
                        }.start()
                }

                lastServiceMessage = serviceMessage
            }
        }
    }
}