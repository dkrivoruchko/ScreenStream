package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.color.ColorPalette
import com.afollestad.materialdialogs.color.colorChooser
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.helper.NotificationHelper
import kotlinx.android.synthetic.main.fragment_settings_interface.*
import org.koin.android.ext.android.inject

class SettingsInterfaceFragment : Fragment() {

    private val notificationHelper: NotificationHelper by inject()
    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.NIGHT_MODE -> {
                val index = nightModeList.first { it.second == settings.nightMode }.first
                tv_fragment_settings_night_mode_summary.text = nightModeOptions[index]
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_interface, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Interface - Night mode
        val index = nightModeList.first { it.second == settings.nightMode }.first
        tv_fragment_settings_night_mode_summary.text = nightModeOptions[index]
        cl_fragment_settings_night_mode.setOnClickListener {
            val indexOld = nightModeList.first { it.second == settings.nightMode }.first
            MaterialDialog(requireActivity()).show {
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
            cl_fragment_settings_notification.setOnClickListener {
                try {
                    startActivity(notificationHelper.getNotificationSettingsIntent())
                } catch (ignore: ActivityNotFoundException) {
                }
            }
        } else {
            cl_fragment_settings_notification.visibility = View.GONE
            v_fragment_settings_notification.visibility = View.GONE
        }

        // Interface - Minimize on stream
        with(cb_fragment_settings_minimize_on_stream) {
            isChecked = settings.minimizeOnStream
            setOnClickListener { settings.minimizeOnStream = isChecked }
            cl_fragment_settings_minimize_on_stream.setOnClickListener { performClick() }
        }

        // Interface - Stop on sleep
        with(cb_fragment_settings_stop_on_sleep) {
            isChecked = settings.stopOnSleep
            setOnClickListener { settings.stopOnSleep = isChecked }
            cl_fragment_settings_stop_on_sleep.setOnClickListener { performClick() }
        }

        // Interface - StartService on boot
        with(cb_fragment_settings_start_on_boot) {
            isChecked = settings.startOnBoot
            setOnClickListener { settings.startOnBoot = isChecked }
            cl_fragment_settings_start_on_boot.setOnClickListener { performClick() }
        }

        // Interface - Auto start stop
        with(cb_fragment_settings_auto_start_stop) {
            isChecked = settings.autoStartStop
            setOnClickListener { settings.autoStartStop = isChecked }
            cl_fragment_settings_auto_start_stop.setOnClickListener { performClick() }
        }

        // Interface - Notify slow connections
        with(cb_fragment_settings_notify_slow_connections) {
            isChecked = settings.notifySlowConnections
            setOnClickListener { settings.notifySlowConnections = isChecked }
            cl_fragment_settings_notify_slow_connections.setOnClickListener { performClick() }
        }

        // Interface - Web page Image buttons
        with(cb_fragment_settings_html_buttons) {
            isChecked = settings.htmlEnableButtons
            setOnClickListener { settings.htmlEnableButtons = isChecked }
            cl_fragment_settings_html_buttons.setOnClickListener { performClick() }
        }

        // Interface - Web page HTML Back color
        v_fragment_settings_html_back_color.color = settings.htmlBackColor
        v_fragment_settings_html_back_color.border = ContextCompat.getColor(requireContext(), R.color.textColorPrimary)
        cl_fragment_settings_html_back_color.setOnClickListener {
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