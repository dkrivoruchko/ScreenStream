package info.dvkr.screenstream.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.jrummyapps.android.colorpicker.ColorPickerDialog
import com.jrummyapps.android.colorpicker.ColorPickerDialogListener
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.presenter.SettingsActivityPresenter
import kotlinx.android.synthetic.main.activity_settings.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.functions.Action1
import rx.subjects.PublishSubject
import javax.inject.Inject


class SettingsActivity : BaseActivity(), SettingsActivityView, ColorPickerDialogListener {
    private val TAG = "SettingsActivity"

    companion object {
        fun getStartIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }

    @Inject internal lateinit var presenter: SettingsActivityPresenter

    private val fromEvents = PublishSubject.create<SettingsActivityView.FromEvent>()

    private var htmlBackColor: Int = 0
    private var resizeFactor: Int = 0
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        setContentView(R.layout.activity_settings)
        presenter.attach(this)

        // Interface - Minimize on stream
        clMinimizeOnStream.setOnClickListener { _ -> checkBoxMinimizeOnStream.performClick() }
        checkBoxMinimizeOnStream.setOnClickListener { _ ->
            fromEvents.onNext(SettingsActivityView.FromEvent.MinimizeOnStream(checkBoxMinimizeOnStream.isChecked))
        }

        // Interface - Stop on sleep
        clStopOnSleep.setOnClickListener { _ -> checkBoxStopOnSleep.performClick() }
        checkBoxStopOnSleep.setOnClickListener { _ ->
            fromEvents.onNext(SettingsActivityView.FromEvent.StopOnSleep(checkBoxStopOnSleep.isChecked))
        }

        // Interface - StartService on boot
        clStartOnBoot.setOnClickListener { _ -> checkBoxStartOnBoot.performClick() }
        checkBoxStartOnBoot.setOnClickListener { _ ->
            fromEvents.onNext(SettingsActivityView.FromEvent.StartOnBoot(checkBoxStartOnBoot.isChecked))
        }

        // Interface - HTML MJPEG check
        clMjpegCheck.setOnClickListener { _ -> checkBoxMjpegCheck.performClick() }
        checkBoxMjpegCheck.setOnClickListener { _ ->
            fromEvents.onNext(SettingsActivityView.FromEvent.DisableMjpegCheck(checkBoxMjpegCheck.isChecked))
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
                    Action1 { newValue -> fromEvents.onNext(SettingsActivityView.FromEvent.ResizeFactor(Integer.parseInt(newValue))) }
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
                    Action1 { newValue -> fromEvents.onNext(SettingsActivityView.FromEvent.JpegQuality(Integer.parseInt(newValue))) }
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
            fromEvents.onNext(SettingsActivityView.FromEvent.EnablePin(checked))
        }

        // Security - Hide pin on start
        clHidePinOnStart.setOnClickListener { _ -> checkBoxHidePinOnStart.performClick() }
        checkBoxHidePinOnStart.setOnClickListener { _ ->
            fromEvents.onNext(SettingsActivityView.FromEvent.HidePinOnStart(checkBoxHidePinOnStart.isChecked))
        }


        // Security - New pin on app start
        clNewPinOnAppStart.setOnClickListener { _ -> checkBoxNewPinOnAppStart.performClick() }
        checkBoxNewPinOnAppStart.setOnClickListener { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            fromEvents.onNext(SettingsActivityView.FromEvent.NewPinOnAppStart(checkBoxNewPinOnAppStart.isChecked))
        }


        // Security - Auto change pin
        clAutoChangePin.setOnClickListener { _ -> checkBoxAutoChangePin.performClick() }
        checkBoxAutoChangePin.setOnClickListener { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            fromEvents.onNext(SettingsActivityView.FromEvent.AutoChangePin(checkBoxAutoChangePin.isChecked))
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
                    Action1 { newValue -> fromEvents.onNext(SettingsActivityView.FromEvent.SetPin(newValue)) }
            )
            dialog?.show()
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
                    Action1 { newValue -> fromEvents.onNext(SettingsActivityView.FromEvent.ServerPort(Integer.parseInt(newValue))) }
            )
            dialog?.show()
        }

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        dialog?.let { if (it.isShowing) it.dismiss() }
        presenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        super.onDestroy()
    }

    override fun fromEvent(): Observable<SettingsActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: SettingsActivityView.ToEvent) {
        Observable.just(toEvent).observeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}]: ${event.javaClass.simpleName}")

            when (event) {
                is SettingsActivityView.ToEvent.MinimizeOnStream -> checkBoxMinimizeOnStream.isChecked = event.value
                is SettingsActivityView.ToEvent.StopOnSleep -> checkBoxStopOnSleep.isChecked = event.value
                is SettingsActivityView.ToEvent.StartOnBoot -> checkBoxStartOnBoot.isChecked = event.value
                is SettingsActivityView.ToEvent.DisableMjpegCheck -> checkBoxMjpegCheck.isChecked = event.value
                is SettingsActivityView.ToEvent.HtmlBackColor -> viewHtmlBackColor.setBackgroundColor(event.value)

                is SettingsActivityView.ToEvent.ResizeFactor -> {
                    resizeFactor = event.value
                    textViewResizeImageValue.text = "$resizeFactor%"
                }

                is SettingsActivityView.ToEvent.JpegQuality -> textViewJpegQualityValue.text = Integer.toString(event.value)

                is SettingsActivityView.ToEvent.EnablePin -> {
                    checkBoxEnablePin.isChecked = event.value
                    enableDisableView(clHidePinOnStart, event.value)
                    enableDisableView(clNewPinOnAppStart, event.value)
                    enableDisableView(clAutoChangePin, event.value)
                    enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
                }

                is SettingsActivityView.ToEvent.HidePinOnStart -> checkBoxHidePinOnStart.isChecked = event.value

                is SettingsActivityView.ToEvent.NewPinOnAppStart -> {
                    checkBoxNewPinOnAppStart.isChecked = event.value
                    enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
                }

                is SettingsActivityView.ToEvent.AutoChangePin -> {
                    checkBoxAutoChangePin.isChecked = event.value
                    enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
                }

                is SettingsActivityView.ToEvent.SetPin -> textViewSetPinValue.text = event.value
                is SettingsActivityView.ToEvent.ServerPort -> textViewServerPortValue.text = Integer.toString(event.value)

                is SettingsActivityView.ToEvent.ErrorServerPortBusy -> {
                    dialog = AlertDialog.Builder(this)
                            .setIcon(R.drawable.ic_message_error_24dp)
                            .setTitle(R.string.pref_error_dialog_title)
                            .setMessage(R.string.pref_error_dialog_message_port)
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                    dialog?.show()
                }
            }
        }
    }

    // Private methods
    private fun getEditTextDialog(@StringRes title: Int,
                                  @DrawableRes icon: Int,
                                  @StringRes content: Int,
                                  minLength: Int, maxLength: Int,
                                  minValue: Int, maxValue: Int,
                                  currentValue: String,
                                  action: Action1<String>): Dialog {
        val layoutInflater = LayoutInflater.from(this)
        val dialogView = layoutInflater.inflate(R.layout.settings_edittext_dialog, null)
        val textViewContent = dialogView.findViewById(R.id.textView_settings_editText_content) as TextView
        textViewContent.setText(content)

        val editTextValue = dialogView.findViewById(R.id.editText_settings_editText_value) as EditText
        editTextValue.setText(currentValue)
        editTextValue.setSelection(currentValue.length)
        editTextValue.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))

        val alertDialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .setIcon(icon)
                .setTitle(title)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val stringValue = editTextValue.text.toString()
                    if (stringValue.length < minLength || stringValue.length > maxLength) return@setPositiveButton
                    if (currentValue == stringValue) return@setPositiveButton
                    val newValue = Integer.parseInt(stringValue)
                    if (newValue < minValue || newValue > maxValue) return@setPositiveButton
                    action.call(stringValue)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        alertDialog.setOnShowListener { _ ->
            val okButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            editTextValue.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    if (s.length < minLength || s.length > maxLength) {
                        okButton.isEnabled = false
                        return
                    }

                    val newValue = Integer.parseInt(s.toString())
                    if (newValue < minValue || newValue > maxValue) {
                        okButton.isEnabled = false
                        return
                    }

                    okButton.isEnabled = true
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
        fromEvents.onNext(SettingsActivityView.FromEvent.HtmlBackColor(color))
    }

    override fun onDialogDismissed(dialogId: Int) {}

    private fun enableDisableView(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .5f
        if (view is ViewGroup)
            for (idx in 0..view.childCount - 1)
                enableDisableView(view.getChildAt(idx), enabled)
    }
}