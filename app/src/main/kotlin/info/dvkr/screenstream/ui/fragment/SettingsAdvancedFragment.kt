package info.dvkr.screenstream.ui.fragment

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.logging.cleanLogFiles
import kotlinx.android.synthetic.main.fragment_settings_advanced.*
import org.koin.android.ext.android.inject

class SettingsAdvancedFragment : Fragment() {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.SERVER_PORT ->
                tv_fragment_settings_server_port_value.text = settings.severPort.toString()

            else -> Unit
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_advanced, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Advanced - Use WiFi Only
        with(cb_fragment_settings_use_wifi_only) {
            isChecked = settings.useWiFiOnly
            setOnClickListener { settings.useWiFiOnly = isChecked }
            cl_fragment_settings_use_wifi_only.setOnClickListener { performClick() }
        }

        // Advanced - Enable IPv6 support
        with(cb_fragment_settings_enable_ipv6) {
            isChecked = settings.enableIPv6
            setOnClickListener { settings.enableIPv6 = isChecked }
            cl_fragment_settings_enable_ipv6.setOnClickListener { performClick() }
        }

        // Advanced - Enable Local host
        with(cb_fragment_settings_enable_localhost) {
            isChecked = settings.enableLocalHost
            setOnClickListener { settings.enableLocalHost = isChecked }
            cl_fragment_settings_enable_localhost.setOnClickListener { performClick() }
        }

        // Advanced - Server port
        tv_fragment_settings_server_port_value.text = settings.severPort.toString()
        cl_fragment_settings_server_port.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_server_port)
                icon(R.drawable.ic_settings_http_24dp)
                message(R.string.pref_server_port_dialog)
                input(
                    prefill = settings.severPort.toString(),
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    maxLength = 5,
                    waitForPositiveButton = false
                ) { dialog, text ->
                    val isValid = text.length in 4..5 && text.toString().toInt() in 1025..65535
                    dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                }
                positiveButton(android.R.string.ok) { dialog ->
                    val newValue = dialog.getInputField().text?.toString()?.toInt() ?: settings.severPort
                    if (settings.severPort != newValue) settings.severPort = newValue
                }
                negativeButton(android.R.string.cancel)
                getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
                getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
        }

        // Advanced - Enable application logs
        with(cb_fragment_settings_logging) {
            isChecked = settings.loggingOn
            setOnClickListener {
                settings.loggingOn = isChecked
                if (settings.loggingOn.not()) cleanLogFiles(requireContext().applicationContext)
            }
            cl_fragment_settings_logging.setOnClickListener { performClick() }
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