package info.dvkr.screenstream.ui.fragment

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
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
import info.dvkr.screenstream.databinding.FragmentSettingsAdvancedBinding
import info.dvkr.screenstream.logging.cleanLogFiles
import info.dvkr.screenstream.ui.enableDisableViewWithChildren
import info.dvkr.screenstream.ui.viewBinding
import org.koin.android.ext.android.inject

class SettingsAdvancedFragment : Fragment(R.layout.fragment_settings_advanced) {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.SERVER_PORT ->
                binding.tvFragmentSettingsServerPortValue.text = settings.severPort.toString()

            else -> Unit
        }
    }

    private val binding by viewBinding { fragment -> FragmentSettingsAdvancedBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Advanced - Use WiFi Only
        with(binding.cbFragmentSettingsUseWifiOnly) {
            isChecked = settings.useWiFiOnly
            binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren(
                (settings.enableLocalHost && settings.localHostOnly).not()
            )
            setOnClickListener { settings.useWiFiOnly = isChecked }
            binding.clFragmentSettingsUseWifiOnly.setOnClickListener { performClick() }
        }

        // Advanced - Enable IPv6 support
        with(binding.cbFragmentSettingsEnableIpv6) {
            isChecked = settings.enableIPv6
            setOnClickListener { settings.enableIPv6 = isChecked }
            binding.clFragmentSettingsEnableIpv6.setOnClickListener { performClick() }
        }

        // Advanced - Enable Local host
        with(binding.cbFragmentSettingsEnableLocalhost) {
            isChecked = settings.enableLocalHost
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(settings.enableLocalHost)
            setOnClickListener {
                settings.enableLocalHost = isChecked
                binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(settings.enableLocalHost)
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren(
                    (settings.enableLocalHost && settings.localHostOnly).not()
                )
            }
            binding.clFragmentSettingsEnableLocalhost.setOnClickListener { performClick() }
        }

        // Advanced - Local host only
        with(binding.cbFragmentSettingsLocalhostOnly) {
            isChecked = settings.localHostOnly
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(settings.enableLocalHost)
            setOnClickListener {
                settings.localHostOnly = isChecked
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren(
                    (settings.enableLocalHost && settings.localHostOnly).not()
                )
            }
            binding.clFragmentSettingsLocalhostOnly.setOnClickListener { performClick() }
        }

        // Advanced - Server port
        binding.tvFragmentSettingsServerPortValue.text = settings.severPort.toString()
        binding.clFragmentSettingsServerPort.setOnClickListener {
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
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
        binding.vFragmentSettingsLogging.visibility = if (settings.loggingVisible) View.VISIBLE else View.GONE
        binding.clFragmentSettingsLogging.visibility = if (settings.loggingVisible) View.VISIBLE else View.GONE
        with(binding.cbFragmentSettingsLogging) {
            isChecked = settings.loggingOn
            setOnClickListener {
                settings.loggingOn = isChecked
                if (settings.loggingOn.not()) cleanLogFiles(requireContext().applicationContext)
            }
            binding.clFragmentSettingsLogging.setOnClickListener { performClick() }
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