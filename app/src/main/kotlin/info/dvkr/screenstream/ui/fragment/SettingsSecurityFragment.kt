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
import info.dvkr.screenstream.databinding.FragmentSettingsSecurityBinding
import info.dvkr.screenstream.ui.enableDisableViewWithChildren
import info.dvkr.screenstream.ui.viewBinding
import org.koin.android.ext.android.inject

class SettingsSecurityFragment : Fragment(R.layout.fragment_settings_security) {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.PIN ->
                binding.tvFragmentSettingsSetPinValue.text = settings.pin

            else -> Unit
        }
    }

    private val binding by viewBinding { fragment -> FragmentSettingsSecurityBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Security - Enable pin
        with(binding.cbFragmentSettingsEnablePin) {
            isChecked = settings.enablePin
            binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(settings.enablePin)
            binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(settings.enablePin)
            binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(settings.enablePin)
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            setOnClickListener {
                if (isChecked) settings.pin = String(settings.pin.toCharArray()) // Workaround for BinaryPreferences IPC
                settings.enablePin = isChecked
                binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
            binding.clFragmentSettingsEnablePin.setOnClickListener { performClick() }
        }

        // Security - Hide pin on start
        with(binding.cbFragmentSettingsHidePinOnStart) {
            isChecked = settings.hidePinOnStart
            setOnClickListener { settings.hidePinOnStart = isChecked }
            binding.clFragmentSettingsHidePinOnStart.setOnClickListener { performClick() }
        }

        // Security - New pin on app start
        with(binding.cbFragmentSettingsNewPinOnAppStart) {
            isChecked = settings.newPinOnAppStart
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            setOnClickListener {
                settings.newPinOnAppStart = isChecked
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
            binding.clFragmentSettingsNewPinOnAppStart.setOnClickListener { performClick() }
        }

        // Security - Auto change pin
        with(binding.cbFragmentSettingsAutoChangePin) {
            isChecked = settings.autoChangePin
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            setOnClickListener {
                settings.autoChangePin = isChecked
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
            binding.clFragmentSettingsAutoChangePin.setOnClickListener { performClick() }
        }

        // Security - Set pin
        binding.tvFragmentSettingsSetPinValue.text = settings.pin
        binding.clFragmentSettingsSetPin.setOnClickListener {
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_set_pin)
                icon(R.drawable.ic_settings_key_24dp)
                message(R.string.pref_set_pin_dialog)
                input(
                    prefill = settings.pin,
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    maxLength = 4,
                    waitForPositiveButton = false
                ) { dialog, text ->
                    val isValid = text.length in 4..4 && text.toString().toInt() in 0..9999
                    dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                }
                positiveButton(android.R.string.ok) { dialog ->
                    val newValue = dialog.getInputField().text?.toString() ?: settings.pin
                    if (settings.pin != newValue) settings.pin = newValue
                }
                negativeButton(android.R.string.cancel)
                getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
                getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settings.registerChangeListener(settingsListener)
        XLog.d(getLog("onStart", "Invoked"))
        binding.tvFragmentSettingsSetPinValue.text = settings.pin
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        settings.unregisterChangeListener(settingsListener)
        super.onStop()
    }

    private fun canEnableSetPin(): Boolean =
        binding.cbFragmentSettingsEnablePin.isChecked &&
                binding.cbFragmentSettingsNewPinOnAppStart.isChecked.not() &&
                binding.cbFragmentSettingsAutoChangePin.isChecked.not()
}