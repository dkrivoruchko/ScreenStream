package info.dvkr.screenstream.ui.fragment

import android.graphics.Color.parseColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.color.ColorPalette
import com.afollestad.materialdialogs.color.colorChooser
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.android.synthetic.main.fragment_settings_web_page.*
import org.koin.android.ext.android.inject

class SettingsWebPageFragment : Fragment() {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.HTML_BACK_COLOR ->
                v_fragment_settings_html_back_color.color = settings.htmlBackColor

            else -> Unit
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_web_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Web page - Image buttons
        with(cb_fragment_settings_html_buttons) {
            isChecked = settings.htmlEnableButtons
            setOnClickListener { settings.htmlEnableButtons = isChecked }
            cl_fragment_settings_html_buttons.setOnClickListener { performClick() }
        }

        // Web page - HTML Back color
        v_fragment_settings_html_back_color.color = settings.htmlBackColor
        v_fragment_settings_html_back_color.border = ContextCompat.getColor(requireContext(), R.color.textColorPrimary)
        cl_fragment_settings_html_back_color.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_html_back_color_title)
                icon(R.drawable.ic_settings_html_back_color_24dp)
                colorChooser(
                    colors = ColorPalette.Primary + parseColor("#000000"),
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