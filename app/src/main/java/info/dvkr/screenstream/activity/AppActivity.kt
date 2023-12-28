package info.dvkr.screenstream.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.flowWithLifecycle
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
import info.dvkr.screenstream.common.AppEvent
import info.dvkr.screenstream.common.AppStateFlowProvider
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModulesManager
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.databinding.ActivityAppBinding
import info.dvkr.screenstream.logging.sendLogsInEmail
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.coroutines.cancellation.CancellationException

public class AppActivity : NotificationPermissionActivity(R.layout.activity_app) {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, AppActivity::class.java)
    }

    private val binding by viewBinding { activity -> ActivityAppBinding.bind(activity.findViewById(R.id.container)) }

    private val appStateFlowProvider: AppStateFlowProvider by inject(mode = LazyThreadSafetyMode.NONE)
    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)

    private var lastIsStreaming: Boolean = false

    private val baseApp by lazy(LazyThreadSafetyMode.NONE) { application as BaseApp }
    private val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f)
    private val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f)
    private val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BaseApp.LOGGING_ON_KEY) setLogging()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appSettings.nightModeFlow.onEach { AppCompatDelegate.setDefaultNightMode(it) }.launchIn(lifecycleScope)
        super.onCreate(savedInstanceState)

        setLogging()

        baseApp.sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)

        appStateFlowProvider.appStateFlow.onEach { state ->
            XLog.d(getLog("onCreate", "streamingModulesManager.stateFlow.onEach: $state"))

            binding.bottomNavigationActivityApp.menu.findItem(R.id.menu_fab).title =
                if (state.isStreaming) getString(R.string.app_bottom_menu_stop) else getString(R.string.app_bottom_menu_start)

            with(binding.fabActivityAppStartStop) {
                visibility = View.VISIBLE
                isEnabled = state.isBusy.not()
                backgroundTintList = ContextCompat.getColorStateList(
                    this@AppActivity, if (state.isBusy) R.color.colorIconDisabled else R.color.colorAccent
                )

                if (state.isStreaming) {
                    setImageResource(R.drawable.ic_fab_stop_24dp)
                    setOnClickListener { streamingModulesManager.sendEvent(AppEvent.StopStream); isEnabled = false }
                    contentDescription = getString(R.string.app_bottom_menu_stop)
                } else {
                    setImageResource(R.drawable.ic_fab_start_24dp)
                    setOnClickListener { streamingModulesManager.sendEvent(AppEvent.StartStream); isEnabled = false }
                    contentDescription = getString(R.string.app_bottom_menu_start)
                }
            }

            if (state.isStreaming != lastIsStreaming) {
                lastIsStreaming = state.isStreaming
                ObjectAnimator.ofPropertyValuesHolder(binding.fabActivityAppStartStop, scaleX, scaleY, alpha)
                    .apply { interpolator = OvershootInterpolator(); duration = 750 }.start()
            }

        }.launchIn(lifecycleScope)

        streamingModulesManager.selectedModuleIdFlow
            .onEach { moduleId ->
                XLog.i(this@AppActivity.getLog("streamingModuleFlow.onEach:", "$moduleId"))
                streamingModulesManager.startModule(moduleId, this)
            }
            .catch {
                if (it is IllegalStateException) XLog.i(this@AppActivity.getLog("streamingModuleFlow.catch: ${it.message}"), it)
                else throw it
            }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) XLog.i(this@AppActivity.getLog("streamingModuleFlow.onCompletion"))
                else XLog.e(this@AppActivity.getLog("streamingModuleFlow.onCompletion: ${cause.message}"), cause)
            }
            .flowWithLifecycle(lifecycle)
            .launchIn(lifecycleScope)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        with(findNavController(R.id.fr_activity_app_nav_host_fragment)) {
            binding.bottomNavigationActivityApp.setupWithNavController(this)
            addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.nav_exitFragment) lifecycleScope.launch {
                    streamingModulesManager.stopModule()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        (application as BaseApp).sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    @SuppressLint("CheckResult")
    private fun setLogging() {
        binding.llActivityAppLogs.isVisible = baseApp.isLoggingOn
        binding.vActivityAppLogs.isVisible = baseApp.isLoggingOn
        if (baseApp.isLoggingOn)
            binding.bActivityAppSendLogs.setOnClickListener {
                MaterialDialog(this, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    lifecycleOwner(this@AppActivity)
                    title(R.string.app_activity_logs_send_dialog_title)
                    icon(R.drawable.ic_about_feedback_24dp)
                    message(R.string.app_activity_logs_send_dialog_message)
                    input(
                        waitForPositiveButton = false,
                        allowEmpty = true,
                        inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    ) { dialog, text -> dialog.setActionButtonEnabled(WhichButton.NEGATIVE, text.isNotBlank()) }
                    positiveButton(android.R.string.cancel)
                    negativeButton(android.R.string.ok) { sendLogsInEmail(applicationContext, getInputField().text.toString()) }
                    @Suppress("DEPRECATION")
                    neutralButton(R.string.app_activity_logs_send_dialog_neutral) { baseApp.isLoggingOn = false }
                    setActionButtonEnabled(WhichButton.NEGATIVE, false)
                }
            }
        else
            binding.bActivityAppSendLogs.setOnClickListener(null)
    }
}