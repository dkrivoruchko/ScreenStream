package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.setPeekHeight
import com.afollestad.materialdialogs.color.ColorPalette
import com.afollestad.materialdialogs.color.colorChooser
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.databinding.FragmentSettingsInterfaceBinding
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.service.helper.NotificationHelper
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject

class SettingsInterfaceFragment : Fragment(R.layout.fragment_settings_interface) {

    private val notificationHelper: NotificationHelper by inject()
    private val appSettings: AppSettings by inject()
    private val mjpegSettings: MjpegSettings by inject()

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

    private val nightModeOptions by lazy {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            resources.getStringArray(R.array.pref_night_mode_options_api21_28).asList()
        else
            resources.getStringArray(R.array.pref_night_mode_options_api29).asList()
    }

    private val binding by viewBinding { fragment -> FragmentSettingsInterfaceBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Interface - Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.clFragmentSettingsLocale.setOnClickListener {
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(Uri.fromParts("package", requireContext().packageName, null))
                    )
                } catch (ignore: ActivityNotFoundException) {
                }
            }
        } else {
            // TODO Maybe add for API < 33 https://developer.android.com/about/versions/13/features/app-languages
            binding.clFragmentSettingsLocale.visibility = View.GONE
            binding.vFragmentSettingsLocale.visibility = View.GONE
        }

        // Interface - Night mode
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            appSettings.nightModeFlow.onEach { mode ->
                val index = nightModeList.first { it.second == mode }.first
                binding.tvFragmentSettingsNightModeSummary.text = nightModeOptions[index]
            }.launchIn(this)
        }
        binding.clFragmentSettingsNightMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val nightMode = appSettings.nightModeFlow.first()
                val indexOld = nightModeList.first { it.second == nightMode }.first
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_night_mode)
                    icon(R.drawable.ic_settings_night_mode_24dp)
                    listItemsSingleChoice(items = nightModeOptions, initialSelection = indexOld) { _, index, _ ->
                        val newNightMode = nightModeList.firstOrNull { item -> item.first == index }?.second
                            ?: throw IllegalArgumentException("Unknown night mode index")
                        viewLifecycleOwner.lifecycleScope.launchWhenCreated { appSettings.setNightMode(newNightMode) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }

        // Interface - Device notification settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.clFragmentSettingsNotification.setOnClickListener {
                try {
                    startActivity(notificationHelper.getNotificationSettingsIntent())
                } catch (ignore: ActivityNotFoundException) {
                }
            }
        } else {
            binding.clFragmentSettingsNotification.visibility = View.GONE
            binding.vFragmentSettingsNotification.visibility = View.GONE
        }

        // Interface - Keep awake
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsKeepAwake.isChecked = appSettings.keepAwakeFlow.first()
        }
        binding.cbFragmentSettingsKeepAwake.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                appSettings.setKeepAwake(binding.cbFragmentSettingsKeepAwake.isChecked)
            }
        }
        binding.clFragmentSettingsKeepAwake.setOnClickListener { binding.cbFragmentSettingsKeepAwake.performClick() }

        // Interface - Stop on sleep
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsStopOnSleep.isChecked = appSettings.stopOnSleepFlow.first()
        }
        binding.cbFragmentSettingsStopOnSleep.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                appSettings.setStopOnSleep(binding.cbFragmentSettingsStopOnSleep.isChecked)
            }
        }
        binding.clFragmentSettingsStopOnSleep.setOnClickListener { binding.cbFragmentSettingsStopOnSleep.performClick() }


        // Interface - StartService on boot
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsStartOnBoot.isChecked = appSettings.startOnBootFlow.first()
        }
        binding.cbFragmentSettingsStartOnBoot.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                appSettings.setStartOnBoot(binding.cbFragmentSettingsStartOnBoot.isChecked)
            }
        }
        binding.clFragmentSettingsStartOnBoot.setOnClickListener { binding.cbFragmentSettingsStartOnBoot.performClick() }

        // Interface - Auto start stop
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsAutoStartStop.isChecked = appSettings.autoStartStopFlow.first()
        }
        binding.cbFragmentSettingsAutoStartStop.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                appSettings.setAutoStartStop(binding.cbFragmentSettingsAutoStartStop.isChecked)
            }
        }
        binding.clFragmentSettingsAutoStartStop.setOnClickListener { binding.cbFragmentSettingsAutoStartStop.performClick() }

        // Interface - Notify slow connections
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsNotifySlowConnections.isChecked = mjpegSettings.notifySlowConnectionsFlow.first()
        }
        binding.cbFragmentSettingsNotifySlowConnections.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                mjpegSettings.setNotifySlowConnections(binding.cbFragmentSettingsNotifySlowConnections.isChecked)
            }
        }
        binding.clFragmentSettingsNotifySlowConnections.setOnClickListener { binding.cbFragmentSettingsNotifySlowConnections.performClick() }

        // Interface - Web page Image buttons
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsHtmlButtons.isChecked = mjpegSettings.htmlEnableButtonsFlow.first()
        }
        binding.cbFragmentSettingsHtmlButtons.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                mjpegSettings.setHtmlEnableButtons(binding.cbFragmentSettingsHtmlButtons.isChecked)
            }
        }
        binding.clFragmentSettingsHtmlButtons.setOnClickListener { binding.cbFragmentSettingsHtmlButtons.performClick() }

        // Interface - Web page show "Press START on device"
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsHtmlPressStart.isChecked = mjpegSettings.htmlShowPressStartFlow.first()
        }
        binding.cbFragmentSettingsHtmlPressStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                mjpegSettings.setHtmlShowPressStart(binding.cbFragmentSettingsHtmlPressStart.isChecked)
            }
        }
        binding.clFragmentSettingsHtmlPressStart.setOnClickListener { binding.cbFragmentSettingsHtmlPressStart.performClick() }

        // Interface - Web page HTML Back color
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            mjpegSettings.htmlBackColorFlow.onEach {
                binding.vFragmentSettingsHtmlBackColor.color = it
                binding.vFragmentSettingsHtmlBackColor.border = ContextCompat.getColor(requireContext(), R.color.textColorPrimary)
            }.launchIn(this)
        }
        binding.clFragmentSettingsHtmlBackColor.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val htmlBackColor = mjpegSettings.htmlBackColorFlow.first()
                MaterialDialog(requireActivity()).show {
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_html_back_color_title)
                    icon(R.drawable.ic_settings_html_back_color_24dp)
                    colorChooser(
                        colors = ColorPalette.Primary + Color.parseColor("#000000"),
                        initialSelection = htmlBackColor,
                        allowCustomArgb = true
                    ) { _, color ->
                        if (htmlBackColor != color)
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated { mjpegSettings.setHtmlBackColor(color) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop"))
        super.onStop()
    }

    private fun MaterialDialog.adjustPeekHeight(): MaterialDialog {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(requireActivity())
        val heightDp = metrics.bounds.height() / resources.displayMetrics.density
        if (heightDp < 480f) setPeekHeight(metrics.bounds.height())
        return this
    }
}