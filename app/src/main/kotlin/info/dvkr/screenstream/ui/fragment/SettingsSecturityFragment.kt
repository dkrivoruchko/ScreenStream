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
import kotlinx.android.synthetic.main.fragment_settings_security.*
import org.koin.android.ext.android.inject

class SettingsSecturityFragment : Fragment() {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.PIN ->
                tv_fragment_settings_set_pin_value.text = settings.pin

            else -> Unit
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_security, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Security - Enable pin
        with(cb_fragment_settings_enable_pin) {
            isChecked = settings.enablePin
            enableDisableViewWithChildren(cl_fragment_settings_hide_pin_on_start, settings.enablePin)
            enableDisableViewWithChildren(cl_fragment_settings_new_pin_on_app_start, settings.enablePin)
            enableDisableViewWithChildren(cl_fragment_settings_auto_change_pin, settings.enablePin)
            enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            setOnClickListener {
                if (isChecked) settings.pin = String(settings.pin.toCharArray()) // Workaround for BinaryPreferences IPC
                settings.enablePin = isChecked
                enableDisableViewWithChildren(cl_fragment_settings_hide_pin_on_start, isChecked)
                enableDisableViewWithChildren(cl_fragment_settings_new_pin_on_app_start, isChecked)
                enableDisableViewWithChildren(cl_fragment_settings_auto_change_pin, isChecked)
                enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            }
            cl_fragment_settings_enable_pin.setOnClickListener { performClick() }
        }

        // Security - Hide pin on start
        with(cb_fragment_settings_hide_pin_on_start) {
            isChecked = settings.hidePinOnStart
            setOnClickListener { settings.hidePinOnStart = isChecked }
            cl_fragment_settings_hide_pin_on_start.setOnClickListener { performClick() }
        }

        // Security - New pin on app start
        with(cb_fragment_settings_new_pin_on_app_start) {
            isChecked = settings.newPinOnAppStart
            enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            setOnClickListener {
                settings.newPinOnAppStart = isChecked
                enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            }
            cl_fragment_settings_new_pin_on_app_start.setOnClickListener { performClick() }
        }

        // Security - Auto change pin
        with(cb_fragment_settings_auto_change_pin) {
            isChecked = settings.autoChangePin
            enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            setOnClickListener {
                settings.autoChangePin = isChecked
                enableDisableViewWithChildren(cl_fragment_settings_set_pin, canEnableSetPin())
            }
            cl_fragment_settings_auto_change_pin.setOnClickListener { performClick() }
        }

        // Security - Set pin
        tv_fragment_settings_set_pin_value.text = settings.pin
        cl_fragment_settings_set_pin.setOnClickListener {
            MaterialDialog(requireActivity()).show {
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
        tv_fragment_settings_set_pin_value.text = settings.pin
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        settings.unregisterChangeListener(settingsListener)
        super.onStop()
    }

    private fun canEnableSetPin(): Boolean =
        cb_fragment_settings_enable_pin.isChecked &&
                cb_fragment_settings_new_pin_on_app_start.isChecked.not() &&
                cb_fragment_settings_auto_change_pin.isChecked.not()

    private fun enableDisableViewWithChildren(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .5f
        if (view is ViewGroup)
            for (idx in 0 until view.childCount) enableDisableViewWithChildren(view.getChildAt(idx), enabled)
    }
}