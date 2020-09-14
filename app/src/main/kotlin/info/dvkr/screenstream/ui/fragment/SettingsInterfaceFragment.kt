package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.color.ColorPalette
import com.afollestad.materialdialogs.color.colorChooser
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.databinding.FragmentSettingsInterfaceBinding
import info.dvkr.screenstream.service.helper.NotificationHelper
import info.dvkr.screenstream.ui.viewBinding
import org.koin.android.ext.android.inject

class SettingsInterfaceFragment : Fragment(R.layout.fragment_settings_interface) {

    private val notificationHelper: NotificationHelper by inject()
    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.NIGHT_MODE -> {
                val index = nightModeList.first { it.second == settings.nightMode }.first
                binding.tvFragmentSettingsNightModeSummary.text = nightModeOptions[index]
            }
            Settings.Key.HTML_BACK_COLOR -> {
                binding.vFragmentSettingsHtmlBackColor.color = settings.htmlBackColor
            }
            else -> Unit
        }
    }

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

        // Interface - Night mode
        val index = nightModeList.first { it.second == settings.nightMode }.first
        binding.tvFragmentSettingsNightModeSummary.text = nightModeOptions[index]
        binding.clFragmentSettingsNightMode.setOnClickListener {
            val indexOld = nightModeList.first { it.second == settings.nightMode }.first
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_night_mode)
                icon(R.drawable.ic_settings_night_mode_24dp)
                listItemsSingleChoice(items = nightModeOptions, initialSelection = indexOld) { _, index, _ ->
                    settings.nightMode = nightModeList.firstOrNull { item -> item.first == index }?.second
                        ?: throw IllegalArgumentException("Unknown night mode index")
                }
                positiveButton(android.R.string.ok)
                negativeButton(android.R.string.cancel)
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

        // Interface - Stop on sleep
        with(binding.cbFragmentSettingsStopOnSleep) {
            isChecked = settings.stopOnSleep
            setOnClickListener { settings.stopOnSleep = isChecked }
            binding.clFragmentSettingsStopOnSleep.setOnClickListener { performClick() }
        }

        // Interface - StartService on boot
        with(binding.cbFragmentSettingsStartOnBoot) {
            isChecked = settings.startOnBoot
            setOnClickListener { settings.startOnBoot = isChecked }
            binding.clFragmentSettingsStartOnBoot.setOnClickListener { performClick() }
        }

        // Interface - Auto start stop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.clFragmentSettingsAutoStartStop.visibility = View.GONE
            binding.vFragmentSettingsAutoStartStop.visibility = View.GONE
        } else {
            with(binding.cbFragmentSettingsAutoStartStop) {
                isChecked = settings.autoStartStop
                setOnClickListener { settings.autoStartStop = isChecked }
                binding.clFragmentSettingsAutoStartStop.setOnClickListener { performClick() }
            }
        }

        // Interface - Notify slow connections
        with(binding.cbFragmentSettingsNotifySlowConnections) {
            isChecked = settings.notifySlowConnections
            setOnClickListener { settings.notifySlowConnections = isChecked }
            binding.clFragmentSettingsNotifySlowConnections.setOnClickListener { performClick() }
        }

        // Interface - Web page Image buttons
        with(binding.cbFragmentSettingsHtmlButtons) {
            isChecked = settings.htmlEnableButtons
            setOnClickListener { settings.htmlEnableButtons = isChecked }
            binding.clFragmentSettingsHtmlButtons.setOnClickListener { performClick() }
        }

        // Interface - Web page HTML Back color
        binding.vFragmentSettingsHtmlBackColor.color = settings.htmlBackColor
        binding.vFragmentSettingsHtmlBackColor.border =
            ContextCompat.getColor(requireContext(), R.color.textColorPrimary)
        binding.clFragmentSettingsHtmlBackColor.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_html_back_color_title)
                icon(R.drawable.ic_settings_html_back_color_24dp)
                colorChooser(
                    colors = ColorPalette.Primary + Color.parseColor("#000000"),
                    initialSelection = settings.htmlBackColor,
                    allowCustomArgb = true
                ) { _, color -> if (settings.htmlBackColor != color) settings.htmlBackColor = color }
                positiveButton(android.R.string.ok)
                negativeButton(android.R.string.cancel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settings.registerChangeListener(settingsListener)
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        settings.unregisterChangeListener(settingsListener)
        super.onStop()
    }
}