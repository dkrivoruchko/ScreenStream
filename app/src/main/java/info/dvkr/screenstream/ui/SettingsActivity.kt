package info.dvkr.screenstream.ui

import android.app.Dialog
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.jakewharton.rxrelay.PublishRelay
import com.jrummyapps.android.colorpicker.ColorPickerDialog
import com.jrummyapps.android.colorpicker.ColorPickerDialogListener
import com.tapadoo.alerter.Alerter
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.presenter.PresenterFactory
import info.dvkr.screenstream.data.presenter.settings.SettingsPresenter
import info.dvkr.screenstream.data.presenter.settings.SettingsView
import kotlinx.android.synthetic.main.activity_settings.*
import kotlinx.android.synthetic.main.settings_edittext_dialog.view.*
import org.koin.android.ext.android.inject
import rx.Observable
import rx.functions.Action1
import timber.log.Timber


class SettingsActivity : AppCompatActivity(), SettingsView, ColorPickerDialogListener {

    companion object {
        fun getStartIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    private val presenterFactory: PresenterFactory by inject()
    private val presenter: SettingsPresenter by lazy {
        ViewModelProviders.of(this, presenterFactory).get(SettingsPresenter::class.java)
    }

    private val fromEvents = PublishRelay.create<SettingsView.FromEvent>()

    private var htmlBackColor: Int = 0
    private val screenSize = Point()
    private var resizeFactor: Int = 0
    private var dialog: Dialog? = null

    override fun fromEvent(): Observable<SettingsView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: SettingsView.ToEvent) = runOnUiThread {
        Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] toEvent: $toEvent")

        when (toEvent) {
            is SettingsView.ToEvent.MinimizeOnStream -> checkBoxMinimizeOnStream.isChecked = toEvent.value
            is SettingsView.ToEvent.StopOnSleep -> checkBoxStopOnSleep.isChecked = toEvent.value
            is SettingsView.ToEvent.StartOnBoot -> checkBoxStartOnBoot.isChecked = toEvent.value
            is SettingsView.ToEvent.DisableMjpegCheck -> checkBoxMjpegCheck.isChecked = toEvent.value
            is SettingsView.ToEvent.HtmlBackColor -> viewHtmlBackColor.setBackgroundColor(toEvent.value)

            is SettingsView.ToEvent.ResizeFactor -> {
                resizeFactor = toEvent.value
                textViewResizeImageValue.text = "$resizeFactor%"
            }

            is SettingsView.ToEvent.JpegQuality -> textViewJpegQualityValue.text = Integer.toString(toEvent.value)

            is SettingsView.ToEvent.EnablePin -> {
                checkBoxEnablePin.isChecked = toEvent.value
                enableDisableView(clHidePinOnStart, toEvent.value)
                enableDisableView(clNewPinOnAppStart, toEvent.value)
                enableDisableView(clAutoChangePin, toEvent.value)
                enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            }

            is SettingsView.ToEvent.HidePinOnStart -> checkBoxHidePinOnStart.isChecked = toEvent.value

            is SettingsView.ToEvent.NewPinOnAppStart -> {
                checkBoxNewPinOnAppStart.isChecked = toEvent.value
                enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            }

            is SettingsView.ToEvent.AutoChangePin -> {
                checkBoxAutoChangePin.isChecked = toEvent.value
                enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            }

            is SettingsView.ToEvent.SetPin -> textViewSetPinValue.text = toEvent.value
            is SettingsView.ToEvent.UseWiFiOnly -> checkBoxUseWifiOnly.isChecked = toEvent.value
            is SettingsView.ToEvent.ServerPort -> textViewServerPortValue.text = Integer.toString(toEvent.value)

            is SettingsView.ToEvent.ErrorServerPortBusy -> {
                Alerter.create(this)
                        .setTitle(R.string.pref_alert_error_title)
                        .setText(R.string.pref_alert_error_message)
                        .setBackgroundColorRes(R.color.colorAccent)
                        .setDuration(5000)
                        .enableProgress(true)
                        .enableSwipeToDismiss()
                        .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onCreate")

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val defaultDisplay = windowManager.defaultDisplay
        defaultDisplay.getRealSize(screenSize)

        setContentView(R.layout.activity_settings)

        presenter.attach(this)

        // Interface - Minimize on stream
        clMinimizeOnStream.setOnClickListener { _ -> checkBoxMinimizeOnStream.performClick() }
        checkBoxMinimizeOnStream.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.MinimizeOnStream(checkBoxMinimizeOnStream.isChecked))
        }

        // Interface - Stop on sleep
        clStopOnSleep.setOnClickListener { _ -> checkBoxStopOnSleep.performClick() }
        checkBoxStopOnSleep.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.StopOnSleep(checkBoxStopOnSleep.isChecked))
        }

        // Interface - StartService on boot
        clStartOnBoot.setOnClickListener { _ -> checkBoxStartOnBoot.performClick() }
        checkBoxStartOnBoot.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.StartOnBoot(checkBoxStartOnBoot.isChecked))
        }

        // Interface - HTML MJPEG check
        clMjpegCheck.setOnClickListener { _ -> checkBoxMjpegCheck.performClick() }
        checkBoxMjpegCheck.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.DisableMjpegCheck(checkBoxMjpegCheck.isChecked))
        }

        // Interface - HTML Back color
        clHtmlBackColor.setOnClickListener { _ ->
            htmlBackColor = (viewHtmlBackColor.background as ColorDrawable).color
            ColorPickerDialog.newBuilder()
                    .setColor(htmlBackColor)
                    .setDialogTitle(R.string.pref_html_back_color_title)
                    .setShowAlphaSlider(false)
                    .show(this@SettingsActivity)
        }

        // Image - Resize factor
        clResizeImage.setOnClickListener { _ ->
            dialog = getEditTextDialog(
                    R.string.pref_resize,
                    R.drawable.ic_pref_resize_black_24dp,
                    R.string.pref_resize_dialog_text,
                    2, 3,
                    10, 150,
                    Integer.toString(resizeFactor),
                    Action1 { newValue -> fromEvents.call(SettingsView.FromEvent.ResizeFactor(Integer.parseInt(newValue))) },
                    true,
                    R.string.pref_resize_dialog_result
            )
            dialog?.show()
        }

        // Image - Jpeg Quality
        clJpegQuality.setOnClickListener { _ ->
            dialog = getEditTextDialog(
                    R.string.pref_jpeg_quality,
                    R.drawable.ic_pref_high_quality_black_24dp,
                    R.string.pref_jpeg_quality_dialog,
                    2, 3,
                    10, 100,
                    textViewJpegQualityValue.text.toString(),
                    Action1 { newValue -> fromEvents.call(SettingsView.FromEvent.JpegQuality(Integer.parseInt(newValue))) }
            )
            dialog?.show()
        }

        // Security - Enable pin
        clEnablePin.setOnClickListener { _ -> checkBoxEnablePin.performClick() }
        checkBoxEnablePin.setOnClickListener { _ ->
            val checked = checkBoxEnablePin.isChecked
            enableDisableView(clHidePinOnStart, checked)
            enableDisableView(clNewPinOnAppStart, checked)
            enableDisableView(clAutoChangePin, checked)
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            fromEvents.call(SettingsView.FromEvent.EnablePin(checked))
        }

        // Security - Hide pin on start
        clHidePinOnStart.setOnClickListener { _ -> checkBoxHidePinOnStart.performClick() }
        checkBoxHidePinOnStart.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.HidePinOnStart(checkBoxHidePinOnStart.isChecked))
        }

        // Security - New pin on app start
        clNewPinOnAppStart.setOnClickListener { _ -> checkBoxNewPinOnAppStart.performClick() }
        checkBoxNewPinOnAppStart.setOnClickListener { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            fromEvents.call(SettingsView.FromEvent.NewPinOnAppStart(checkBoxNewPinOnAppStart.isChecked))
        }

        // Security - Auto change pin
        clAutoChangePin.setOnClickListener { _ -> checkBoxAutoChangePin.performClick() }
        checkBoxAutoChangePin.setOnClickListener { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            fromEvents.call(SettingsView.FromEvent.AutoChangePin(checkBoxAutoChangePin.isChecked))
        }

        // Security - Set pin
        clSetPin.setOnClickListener { _ ->
            dialog = getEditTextDialog(
                    R.string.pref_set_pin,
                    R.drawable.ic_pref_key_black_24dp,
                    R.string.pref_set_pin_dialog,
                    4, 4,
                    0, 9999,
                    textViewSetPinValue.text.toString(),
                    Action1 { newValue -> fromEvents.call(SettingsView.FromEvent.SetPin(newValue)) }
            )
            dialog?.show()
        }

        // Advanced - Use WiFi Only
        clUseWifiOnly.setOnClickListener { _ -> checkBoxUseWifiOnly.performClick() }
        checkBoxUseWifiOnly.setOnClickListener { _ ->
            fromEvents.call(SettingsView.FromEvent.UseWiFiOnly(checkBoxUseWifiOnly.isChecked))
        }

        // Advanced - Server port
        clServerPort.setOnClickListener { _ ->
            dialog = getEditTextDialog(
                    R.string.pref_server_port,
                    R.drawable.ic_pref_http_black_24dp,
                    R.string.pref_server_port_dialog,
                    4, 6,
                    1025, 65535,
                    textViewServerPortValue.text.toString(),
                    Action1 { newValue -> fromEvents.call(SettingsView.FromEvent.ServerPort(Integer.parseInt(newValue))) }
            )
            dialog?.show()
        }
    }

    override fun onDestroy() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onDestroy")
        dialog?.let { if (it.isShowing) it.dismiss() }
        presenter.detach()
        super.onDestroy()
    }

    // Private methods
    private fun getEditTextDialog(@StringRes title: Int,
                                  @DrawableRes icon: Int,
                                  @StringRes content: Int,
                                  minLength: Int, maxLength: Int,
                                  minValue: Int, maxValue: Int,
                                  currentValue: String,
                                  action: Action1<String>,
                                  resizeImageDialog: Boolean = false,
                                  @StringRes resizeImageResultText: Int = 0): Dialog {
        val layoutInflater = LayoutInflater.from(this)
        val dialogView = layoutInflater.inflate(R.layout.settings_edittext_dialog, null, false)
        with(dialogView) {
            if (resizeImageDialog) {
                textViewSettingsEditTextContent.text = getString(content).format(screenSize.x, screenSize.y)
                val resizeFactor = Integer.parseInt(currentValue) / 100f
                textViewSettingsEditTextResult.text = getString(resizeImageResultText)
                        .format((screenSize.x * resizeFactor).toInt(), (screenSize.y * resizeFactor).toInt())
            } else {
                textViewSettingsEditTextContent.text = getString(content)
                textViewSettingsEditTextResult.visibility = View.GONE
            }
            editTextSettingsEditTextValue.setText(currentValue)
            editTextSettingsEditTextValue.setSelection(currentValue.length)
            editTextSettingsEditTextValue.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        }

        val alertDialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .setIcon(icon)
                .setTitle(title)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newStringValue = dialogView.editTextSettingsEditTextValue.text.toString()
                    if (newStringValue.length in minLength..maxLength &&
                            currentValue != newStringValue &&
                            Integer.parseInt(newStringValue) in minValue..maxValue)
                        action.call(newStringValue)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        alertDialog.setOnShowListener { _ ->
            val okButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            dialogView.editTextSettingsEditTextValue.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    okButton.isEnabled = s.length in minLength..maxLength && Integer.parseInt(s.toString()) in minValue..maxValue
                    if (resizeImageDialog) {
                        val newResizeFactor = if (okButton.isEnabled) Integer.parseInt(s.toString()) / 100f
                        else Integer.parseInt(currentValue) / 100f
                        dialogView.textViewSettingsEditTextResult.text = getString(resizeImageResultText)
                                .format((screenSize.x * newResizeFactor).toInt(), (screenSize.y * newResizeFactor).toInt())
                    }
                }

                override fun afterTextChanged(s: Editable) {
                }
            })
        }
        return alertDialog
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (htmlBackColor == color) return
        viewHtmlBackColor.setBackgroundColor(color)
        fromEvents.call(SettingsView.FromEvent.HtmlBackColor(color))
    }

    override fun onDialogDismissed(dialogId: Int) {}

    private fun enableDisableView(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .5f
        if (view is ViewGroup)
            for (idx in 0 until view.childCount)
                enableDisableView(view.getChildAt(idx), enabled)
    }
}