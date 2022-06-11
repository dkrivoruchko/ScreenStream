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
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.databinding.FragmentSettingsSecurityBinding
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.ServiceActivity
import info.dvkr.screenstream.ui.enableDisableViewWithChildren
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject

class SettingsSecurityFragment : Fragment(R.layout.fragment_settings_security) {

    private val settings: Settings by inject()
    private val binding by viewBinding { fragment -> FragmentSettingsSecurityBinding.bind(fragment.requireView()) }
    private var isStreaming: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as ServiceActivity).getServiceMessageLiveData()
            .observe(viewLifecycleOwner) { serviceMessage ->
                if (serviceMessage is ServiceMessage.ServiceState) {
                    viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                        isStreaming = serviceMessage.isStreaming
                        binding.tvFragmentSettingsSetPinValue.text =
                            if (isStreaming && settings.hidePinOnStartFlow.first()) "*" else settings.pinFlow.first()
                    }
                }
            }

        IntentAction.GetServiceState.sendToAppService(requireContext())

        // Security - Enable pin
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            with(binding.cbFragmentSettingsEnablePin) {
                isChecked = settings.enablePinFlow.first()
                binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
                binding.clFragmentSettingsBlockAddress.enableDisableViewWithChildren(isChecked)
                setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                        settings.setEnablePin(isChecked)
                        binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
                        binding.clFragmentSettingsBlockAddress.enableDisableViewWithChildren(isChecked)
                    }
                }
                binding.clFragmentSettingsEnablePin.setOnClickListener { performClick() }
            }
        }

        // Security - Hide pin on start
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsHidePinOnStart.isChecked = settings.hidePinOnStartFlow.first()
        }
        binding.cbFragmentSettingsHidePinOnStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setHidePinOnStart(binding.cbFragmentSettingsHidePinOnStart.isChecked)
            }
        }
        binding.clFragmentSettingsHidePinOnStart.setOnClickListener { binding.cbFragmentSettingsHidePinOnStart.performClick() }

        // Security - New pin on app start
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsNewPinOnAppStart.isChecked = settings.newPinOnAppStartFlow.first()
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
        }
        binding.cbFragmentSettingsNewPinOnAppStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setNewPinOnAppStart(binding.cbFragmentSettingsNewPinOnAppStart.isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
        }
        binding.clFragmentSettingsNewPinOnAppStart.setOnClickListener { binding.cbFragmentSettingsNewPinOnAppStart.performClick() }

        // Security - Auto change pin
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsAutoChangePin.isChecked = settings.autoChangePinFlow.first()
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
        }
        binding.cbFragmentSettingsAutoChangePin.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setAutoChangePin(binding.cbFragmentSettingsAutoChangePin.isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
        }
        binding.clFragmentSettingsAutoChangePin.setOnClickListener { binding.cbFragmentSettingsAutoChangePin.performClick() }

        // Security - Set pin
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            settings.pinFlow
                .onEach {
                    binding.tvFragmentSettingsSetPinValue.text =
                        if (isStreaming && settings.hidePinOnStartFlow.first()) "*" else it
                }
                .launchIn(this)
        }
        binding.clFragmentSettingsSetPin.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val pin = settings.pinFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_set_pin)
                    icon(R.drawable.ic_settings_key_24dp)
                    message(R.string.pref_set_pin_dialog)
                    input(
                        prefill = pin,
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 6,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length in 4..6 && text.toString().toInt() in 0..999999
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString() ?: pin
                        if (pin != newValue)
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated { settings.setPin(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Security - Block address
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsBlockAddress.isChecked = settings.blockAddressFlow.first()
        }
        binding.cbFragmentSettingsBlockAddress.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                settings.setBlockAddress(binding.cbFragmentSettingsBlockAddress.isChecked)
            }
        }
        binding.clFragmentSettingsBlockAddress.setOnClickListener { binding.cbFragmentSettingsBlockAddress.performClick() }
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        super.onStop()
    }

    private fun canEnableSetPin(): Boolean =
        binding.cbFragmentSettingsEnablePin.isChecked &&
                binding.cbFragmentSettingsNewPinOnAppStart.isChecked.not() &&
                binding.cbFragmentSettingsAutoChangePin.isChecked.not()
}