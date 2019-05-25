package info.dvkr.screenstream.ui.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationMenuView
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.logging.sendLogsInEmail
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.android.synthetic.main.activity_app.*

class AppActivity : BaseActivity() {

    companion object {
        fun getStartIntent(context: Context): Intent =
            Intent(context.applicationContext, AppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var isStreamingBefore: Boolean = true
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
        setContentView(R.layout.activity_app)

        // TODO Fix for https://github.com/material-components/material-components-android/issues/139
        // https://issuetracker.google.com/issues/115754572
        with(bottom_navigation_activity_app.getChildAt(0) as BottomNavigationMenuView) {
            for (index in 0 until childCount)
                (getChildAt(index) as BottomNavigationItemView).findViewById<TextView>(R.id.largeLabel)
                    .setPadding(0, 0, 0, 0)
        }

        with(findNavController(R.id.fr_activity_app_nav_host_fragment)) {
            bottom_navigation_activity_app.setupWithNavController(this)
            addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.nav_exitFragment) IntentAction.Exit.sendToAppService(this@AppActivity)
            }
        }
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

    private fun setLogging(loggingOn: Boolean) {
        ll_activity_app_logs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        v_activity_app_logs.visibility = if (loggingOn) View.VISIBLE else View.GONE
        b_activity_app_send_logs.setOnClickListener {
            MaterialDialog(this).show {
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
                neutralButton(R.string.app_activity_send_logs_dialog_neutral) { settings.loggingOn = false }
                setActionButtonEnabled(WhichButton.NEGATIVE, false)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                lastServiceMessage != serviceMessage || return
                XLog.d(this@AppActivity.getLog("onServiceMessage", "Message: $serviceMessage"))

                bottom_navigation_activity_app.menu.findItem(R.id.menu_fab).title =
                    if (serviceMessage.isStreaming) getString(R.string.bottom_menu_stop)
                    else getString(R.string.bottom_menu_start)

                with(fab_activity_app_start_stop) {
                    visibility = View.VISIBLE
                    isEnabled = serviceMessage.isBusy.not()

                    if (serviceMessage.isStreaming) {
                        setImageResource(R.drawable.ic_fab_stop_24dp)
                        setOnClickListener { IntentAction.StopStream.sendToAppService(this@AppActivity) }
                    } else {
                        setImageResource(R.drawable.ic_fab_start_24dp)
                        setOnClickListener { IntentAction.StartStream.sendToAppService(this@AppActivity) }
                    }
                }

                if (serviceMessage.isStreaming != lastServiceMessage?.isStreaming) {
                    ObjectAnimator.ofPropertyValuesHolder(fab_activity_app_start_stop, scaleX, scaleY, alpha).apply {
                        interpolator = OvershootInterpolator()
                        duration = 750
                    }.start()
                }

                lastServiceMessage = serviceMessage

                // MinimizeOnStream
                if (settings.minimizeOnStream && isStreamingBefore.not() && serviceMessage.isStreaming)
                    try {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (ex: ActivityNotFoundException) {
                        XLog.e(getLog("onServiceMessage"), ex)
                    }

                isStreamingBefore = serviceMessage.isStreaming
            }

            else -> super.onServiceMessage(serviceMessage)
        }
    }
}