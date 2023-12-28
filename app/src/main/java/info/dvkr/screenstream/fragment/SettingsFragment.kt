package info.dvkr.screenstream.fragment

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.setPeekHeight
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.NotificationHelper
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.databinding.FragmentSettingsBinding
import info.dvkr.screenstream.logging.cleanLogFiles
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

public class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
    private val notificationHelper: NotificationHelper by inject(mode = LazyThreadSafetyMode.NONE)

    private val nightModeList = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        listOf(
            0 to AppCompatDelegate.MODE_NIGHT_YES,
            1 to AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
            2 to AppCompatDelegate.MODE_NIGHT_NO
        )
    else listOf(
        0 to AppCompatDelegate.MODE_NIGHT_YES,
        1 to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        2 to AppCompatDelegate.MODE_NIGHT_NO
    )

    private val nightModeOptions by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            resources.getStringArray(R.array.app_pref_night_mode_options_api21_28).asList()
        else
            resources.getStringArray(R.array.app_pref_night_mode_options_api29).asList()
    }

    private val binding by viewBinding { fragment -> FragmentSettingsBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.clFragmentSettingsLocale.setOnClickListener {
                startActivityCatching(
                    Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                        .setData(Uri.fromParts("package", requireContext().packageName, null))
                )
            }
        } else { //TODO Create locale selection UI
            binding.clFragmentSettingsLocale.visibility = View.GONE
            binding.vFragmentSettingsLocale.visibility = View.GONE
        }

        // Night mode
        appSettings.nightModeFlow.onEach { mode ->
            val index = nightModeList.first { it.second == mode }.first
            binding.tvFragmentSettingsNightModeSummary.text = nightModeOptions[index]
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsNightMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val nightMode = appSettings.nightModeFlow.first()
                val indexOld = nightModeList.first { it.second == nightMode }.first
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.app_pref_night_mode)
                    icon(R.drawable.ic_settings_night_mode_24dp)
                    listItemsSingleChoice(items = nightModeOptions, initialSelection = indexOld) { _, index, _ ->
                        val newNightMode = nightModeList.firstOrNull { item -> item.first == index }?.second
                            ?: throw IllegalArgumentException("Unknown night mode index")
                        viewLifecycleOwner.lifecycleScope.launch { appSettings.setNightMode(newNightMode) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }

        // Device notification settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.clFragmentSettingsNotification.setOnClickListener {
                startActivityCatching(notificationHelper.getNotificationSettingsIntent(requireContext()))
            }
        } else {
            binding.clFragmentSettingsNotification.visibility = View.GONE
        }

        // Enable application logs
        viewLifecycleOwner.lifecycleScope.launch {
            val loggingVisible = (requireActivity().application as BaseApp).isLoggingOn
            binding.vFragmentSettingsLogging.isVisible = loggingVisible
            binding.clFragmentSettingsLogging.isVisible = loggingVisible
            binding.cbFragmentSettingsLogging.isChecked = loggingVisible
        }
        binding.cbFragmentSettingsLogging.setOnClickListener {
            (requireActivity().application as BaseApp).isLoggingOn = binding.cbFragmentSettingsLogging.isChecked
            if (binding.cbFragmentSettingsLogging.isChecked.not()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { cleanLogFiles(requireContext().applicationContext) }
                }
            }
        }
        binding.clFragmentSettingsLogging.setOnClickListener { binding.cbFragmentSettingsLogging.performClick() }
    }

    private fun MaterialDialog.adjustPeekHeight(): MaterialDialog {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(requireActivity())
        val heightDp = metrics.bounds.height() / resources.displayMetrics.density
        if (heightDp < 480f) setPeekHeight(metrics.bounds.height())
        return this
    }

    private fun startActivityCatching(intent: Intent) =
        runCatching { startActivity(intent) }
            .onFailure { XLog.e(getLog("startActivityCatching", intent.toString()), it) }
}