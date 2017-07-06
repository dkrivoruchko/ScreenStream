package info.dvkr.screenstream.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v4.content.ContextCompat
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
import com.jakewharton.rxbinding.view.RxView
import com.jrummyapps.android.colorpicker.ColorPickerDialog
import com.jrummyapps.android.colorpicker.ColorPickerDialogListener
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.presenter.SettingsActivityPresenter
import kotlinx.android.synthetic.main.activity_settings.*
import rx.Observable
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

    private val mHtmlBackColorSubject = PublishSubject.create<Int>()
    private val mResizeFactorSubject = PublishSubject.create<Int>()
    private val mJpegQualitySubject = PublishSubject.create<Int>()
    private val mSetPinSubject = PublishSubject.create<String>()
    private val mServerPortSubject = PublishSubject.create<Int>()

    @Inject internal lateinit var mPresenter: SettingsActivityPresenter

    private var mHtmlBackColor: Int = 0
    private var mResizeFactor: Int = 0
    private var mJpegQuality: Int = 0
    private var mCurrentPin: String = "0000"
    private var mServerPort: Int = 0

    private var mDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        setContentView(R.layout.activity_settings)
        mPresenter.attach(this)

        // Interface - Minimize on stream
        clMinimizeOnStream.setOnClickListener { _ -> checkBoxMinimizeOnStream.performClick() }

        // Interface - Stop on sleep
        clStopOnSleep.setOnClickListener { _ -> checkBoxStopOnSleep.performClick() }

        // Interface - Start on boot
        clStartOnBoot.setOnClickListener { _ -> checkBoxStartOnBoot.performClick() }

        // Interface - HTML MJPEG check
        clMjpegCheck.setOnClickListener { _ -> checkBoxMjpegCheck.performClick() }

        // Interface - HTML Back color
        clHtmlBackColor.setOnClickListener { _ ->
            mHtmlBackColor = (viewHtmlBackColor.background as ColorDrawable).color
            ColorPickerDialog.newBuilder()
                    .setColor(mHtmlBackColor)
                    .setDialogTitle(R.string.pref_html_back_color_title)
                    .setShowAlphaSlider(false)
                    .show(this@SettingsActivity)
        }

        // Image - Resize factor
        clResizeImage.setOnClickListener { _ ->
            mDialog = getEditTextDialog(
                    R.string.pref_resize,
                    R.drawable.ic_pref_resize_black_24dp,
                    R.string.pref_resize_dialog_text,
                    2, 3,
                    10, 150,
                    Integer.toString(mResizeFactor),
                    Action1 { newValue -> mResizeFactorSubject.onNext(Integer.parseInt(newValue)) }
            )
            mDialog?.show()
        }

        // Image - Jpeg Quality
        clJpegQuality.setOnClickListener { _ ->
            mDialog = getEditTextDialog(
                    R.string.pref_jpeg_quality,
                    R.drawable.ic_pref_high_quality_black_24dp,
                    R.string.pref_jpeg_quality_dialog,
                    2, 3,
                    10, 100,
                    Integer.toString(mJpegQuality),
                    Action1 { newValue -> mJpegQualitySubject.onNext(Integer.parseInt(newValue)) }
            )
            mDialog?.show()
        }

        // Security - Enable pin
        clEnablePin.setOnClickListener { _ -> checkBoxEnablePin.performClick() }

        // Security - Hide pin on start
        clHidePinOnStart.setOnClickListener { _ -> checkBoxHidePinOnStart.performClick() }

        // Security - New pin on app start
        clNewPinOnAppStart.setOnClickListener { _ -> checkBoxNewPinOnAppStart.performClick() }

        // Security - Auto change pin
        clAutoChangePin.setOnClickListener { _ -> checkBoxAutoChangePin.performClick() }

        // Security - Set pin
        clSetPin.setOnClickListener { _ ->
            mDialog = getEditTextDialog(
                    R.string.pref_set_pin,
                    R.drawable.ic_pref_key_black_24dp,
                    R.string.pref_set_pin_dialog,
                    4, 4,
                    0, 9999,
                    mCurrentPin,
                    Action1 { newValue -> mSetPinSubject.onNext(newValue) }
            )
            mDialog?.show()
        }

        // Advanced - Server port
        clServerPort.setOnClickListener { _ ->
            mDialog = getEditTextDialog(
                    R.string.pref_server_port,
                    R.drawable.ic_pref_http_black_24dp,
                    R.string.pref_server_port_dialog,
                    4, 6,
                    1025, 65535,
                    Integer.toString(mServerPort),
                    Action1 { newValue -> mServerPortSubject.onNext(Integer.parseInt(newValue)) }
            )
            mDialog?.show()
        }

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        mDialog?.let { if (it.isShowing) it.dismiss() }
        mPresenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        super.onDestroy()
    }

    override fun onMinimizeOnStream(): Observable<Boolean> {
        return RxView.clicks(checkBoxMinimizeOnStream).map { _ -> checkBoxMinimizeOnStream.isChecked }
    }

    override fun setMinimizeOnStream(value: Boolean) {
        checkBoxMinimizeOnStream.isChecked = value
    }

    override fun onStopOnSleep(): Observable<Boolean> {
        return RxView.clicks(checkBoxStopOnSleep).map { _ -> checkBoxStopOnSleep.isChecked }
    }

    override fun setStopOnSleep(value: Boolean) {
        checkBoxStopOnSleep.isChecked = value
    }

    override fun onStartOnBoot(): Observable<Boolean> {
        return RxView.clicks(checkBoxStartOnBoot).map { _ -> checkBoxStartOnBoot.isChecked }
    }

    override fun setStartOnBoot(value: Boolean) {
        checkBoxStartOnBoot.isChecked = value
    }

    override fun onDisableMjpegCheck(): Observable<Boolean> {
        return RxView.clicks(checkBoxMjpegCheck).map { _ -> checkBoxMjpegCheck.isChecked }
    }

    override fun setDisableMjpegCheck(value: Boolean) {
        checkBoxMjpegCheck.isChecked = value
    }

    override fun onHtmlBackColor(): Observable<Int> = mHtmlBackColorSubject.asObservable()

    override fun setHtmlBackColor(value: Int) {
        viewHtmlBackColor.setBackgroundColor(value)
    }

    override fun onResizeFactor(): Observable<Int> = mResizeFactorSubject.asObservable()

    override fun setResizeFactor(value: Int) {
        mResizeFactor = value
        textViewResizeImageValue.text = "$value%"
        if (value > 100) {
            textViewResizeImageValue.setTextColor(ContextCompat.getColor(applicationContext, R.color.colorAccent))
            textViewResizeImageValue.setTypeface(textViewResizeImageValue.typeface, Typeface.BOLD)
        } else {
            textViewResizeImageValue.setTextColor(ContextCompat.getColor(applicationContext, R.color.textColorPrefValue))
            textViewResizeImageValue.typeface = Typeface.DEFAULT
        }
    }

    override fun onJpegQuality(): Observable<Int> = mJpegQualitySubject.asObservable()

    override fun setJpegQuality(value: Int) {
        mJpegQuality = value
        textViewJpegQualityValue.text = Integer.toString(value)
    }

    override fun onEnablePin(): Observable<Boolean> {
        return RxView.clicks(checkBoxEnablePin).map { _ ->
            val checked = checkBoxEnablePin.isChecked
            enableDisableView(clHidePinOnStart, checked)
            enableDisableView(clNewPinOnAppStart, checked)
            enableDisableView(clAutoChangePin, checked)
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            checked
        }
    }

    override fun setEnablePin(value: Boolean) {
        checkBoxEnablePin.isChecked = value
        enableDisableView(clHidePinOnStart, value)
        enableDisableView(clNewPinOnAppStart, value)
        enableDisableView(clAutoChangePin, value)
        enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
    }

    override fun onHidePinOnStart(): Observable<Boolean> {
        return RxView.clicks(checkBoxHidePinOnStart).map { _ -> checkBoxHidePinOnStart.isChecked }
    }

    override fun setHidePinOnStart(value: Boolean) {
        checkBoxHidePinOnStart.isChecked = value
    }

    override fun onNewPinOnAppStart(): Observable<Boolean> {
        return RxView.clicks(checkBoxNewPinOnAppStart).map { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            checkBoxNewPinOnAppStart.isChecked
        }
    }

    override fun setNewPinOnAppStart(value: Boolean) {
        checkBoxNewPinOnAppStart.isChecked = value
        enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
    }

    override fun onAutoChangePin(): Observable<Boolean> {
        return RxView.clicks(checkBoxAutoChangePin).map { _ ->
            enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
            checkBoxAutoChangePin.isChecked
        }
    }

    override fun setAutoChangePin(value: Boolean) {
        checkBoxAutoChangePin.isChecked = value
        enableDisableView(clSetPin, checkBoxEnablePin.isChecked && !checkBoxNewPinOnAppStart.isChecked && !checkBoxAutoChangePin.isChecked)
    }

    override fun onSetPin(): Observable<String> = mSetPinSubject.asObservable()

    override fun setSetPin(value: String) {
        mCurrentPin = value
    }

    override fun onServerPort(): Observable<Int> = mServerPortSubject.asObservable()

    override fun setServerPort(value: Int) {
        mServerPort = value
        textViewServerPortValue.text = Integer.toString(value)
    }

    override fun showMessage(message: SettingsActivityView.Message) {
        when (message) {
            is SettingsActivityView.Message.ErrorServerPortBusy -> {
                mDialog = AlertDialog.Builder(this)
                        .setIcon(R.drawable.ic_message_error_24dp)
                        .setTitle(R.string.pref_error_dialog_title)
                        .setMessage(R.string.pref_error_dialog_message_port)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                mDialog?.show()
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
        if (mHtmlBackColor == color) return
        viewHtmlBackColor.setBackgroundColor(color)
        mHtmlBackColorSubject.onNext(color)
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