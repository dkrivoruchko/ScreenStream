package info.dvkr.screenstream.ui.fragment

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.databinding.FragmentSettingsAdvancedBinding
import info.dvkr.screenstream.logging.cleanLogFiles
import info.dvkr.screenstream.ui.enableDisableViewWithChildren
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class SettingsAdvancedFragment : Fragment(R.layout.fragment_settings_advanced) {

    private val settings: Settings by inject()
    private val binding by viewBinding { fragment -> FragmentSettingsAdvancedBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Advanced - Use WiFi Only
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsUseWifiOnly.isChecked = settings.useWiFiOnlyFlow.first()
            val enableLocalHost = settings.enableLocalHostFlow.first()
            val localHostOnly = settings.localHostOnlyFlow.first()
            binding.cbFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
        }
        binding.cbFragmentSettingsUseWifiOnly.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setUseWiFiOnly(binding.cbFragmentSettingsUseWifiOnly.isChecked)
            }
        }
        binding.clFragmentSettingsUseWifiOnly.setOnClickListener { binding.cbFragmentSettingsUseWifiOnly.performClick() }


        // Advanced - Enable IPv6 support
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsEnableIpv6.isChecked = settings.enableIPv6Flow.first()
        }
        binding.cbFragmentSettingsEnableIpv6.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setEnableIPv6(binding.cbFragmentSettingsEnableIpv6.isChecked)
            }
        }
        binding.clFragmentSettingsEnableIpv6.setOnClickListener { binding.cbFragmentSettingsEnableIpv6.performClick() }

        // Advanced - Enable Local host
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsEnableLocalhost.isChecked = settings.enableLocalHostFlow.first()
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(binding.cbFragmentSettingsEnableLocalhost.isChecked) //TODO
        }
        binding.cbFragmentSettingsEnableLocalhost.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val enableLocalHost = binding.cbFragmentSettingsEnableLocalhost.isChecked
                settings.setEnableLocalHost(enableLocalHost)
                val localHostOnly = settings.localHostOnlyFlow.first()
                binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(enableLocalHost)
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
            }
        }
        binding.clFragmentSettingsEnableLocalhost.setOnClickListener { binding.cbFragmentSettingsEnableLocalhost.performClick() }

        // Advanced - Local host only
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsLocalhostOnly.isChecked = settings.localHostOnlyFlow.first()
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(settings.enableLocalHostFlow.first())
        }
        binding.cbFragmentSettingsLocalhostOnly.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val localHostOnly = binding.cbFragmentSettingsLocalhostOnly.isChecked
                settings.setLocalHostOnly(localHostOnly)
                val enableLocalHost = settings.enableLocalHostFlow.first()
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
            }
        }
        binding.clFragmentSettingsLocalhostOnly.setOnClickListener { binding.cbFragmentSettingsLocalhostOnly.performClick() }

        // Advanced - Server port
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            settings.serverPortFlow
                .onEach { binding.tvFragmentSettingsServerPortValue.text = it.toString() }
                .launchIn(this)
        }
        binding.clFragmentSettingsServerPort.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val serverPort = settings.serverPortFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_server_port)
                    icon(R.drawable.ic_settings_http_24dp)
                    message(R.string.pref_server_port_dialog)
                    input(
                        prefill = serverPort.toString(),
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 5,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length in 4..5 && text.toString().toInt() in 1025..65535
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString()?.toInt() ?: serverPort
                        if (serverPort != newValue)
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated { settings.setServerPort(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Advanced - Enable application logs
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            val loggingVisible = settings.loggingVisibleFlow.first()
            binding.vFragmentSettingsLogging.visibility = if (loggingVisible) View.VISIBLE else View.GONE
            binding.clFragmentSettingsLogging.visibility = if (loggingVisible) View.VISIBLE else View.GONE
            binding.cbFragmentSettingsLogging.isChecked = (requireActivity().application as BaseApp).isLoggingOn
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

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        super.onStop()
    }
}