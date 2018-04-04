package info.dvkr.screenstream.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.jrummyapps.android.colorpicker.ColorPickerDialog
import com.jrummyapps.android.colorpicker.ColorPickerDialogListener
import com.tapadoo.alerter.Alerter
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.presenter.BaseView
import info.dvkr.screenstream.data.presenter.settings.SettingsPresenter
import info.dvkr.screenstream.data.presenter.settings.SettingsView
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.android.synthetic.main.activity_settings.*
import kotlinx.android.synthetic.main.settings_edittext_dialog.view.*
import org.koin.android.architecture.ext.getViewModel
import timber.log.Timber


class SettingsActivity : BaseActivity(),
    SettingsView,
    ColorPickerDialogListener,
    BaseDialog.DialogCallback {

    companion object {
        private const val DIALOG_RESIZE_FACTOR_TAG = "DIALOG_RESIZE_FACTOR_TAG"
        private const val DIALOG_JPEG_QUALITY_TAG = "DIALOG_JPEG_QUALITY_TAG"
        private const val DIALOG_SET_PIN_TAG = "DIALOG_SET_PIN_TAG"
        private const val DIALOG_SERVER_PORT_TAG = "DIALOG_SERVER_PORT_TAG"


        fun getStartIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    private val presenter: SettingsPresenter by lazy { getViewModel<SettingsPresenter>() }

    private var htmlBackColor: Int = 0
    private var resizeFactor: Int = 0

    override fun toEvent(toEvent: BaseView.BaseToEvent) = runOnUiThread {
        Timber.d("[${Utils.getLogPrefix(this)}] toEvent: ${toEvent.javaClass.simpleName}")

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
                enableDisableView(
                    clSetPin,
                    checkBoxEnablePin.isChecked &&
                            !checkBoxNewPinOnAppStart.isChecked &&
                            !checkBoxAutoChangePin.isChecked
                )
            }

            is SettingsView.ToEvent.HidePinOnStart -> checkBoxHidePinOnStart.isChecked = toEvent.value

            is SettingsView.ToEvent.NewPinOnAppStart -> {
                checkBoxNewPinOnAppStart.isChecked = toEvent.value
                enableDisableView(
                    clSetPin,
                    checkBoxEnablePin.isChecked &&
                            !checkBoxNewPinOnAppStart.isChecked &&
                            !checkBoxAutoChangePin.isChecked
                )
            }

            is SettingsView.ToEvent.AutoChangePin -> {
                checkBoxAutoChangePin.isChecked = toEvent.value
                enableDisableView(
                    clSetPin,
                    checkBoxEnablePin.isChecked &&
                            !checkBoxNewPinOnAppStart.isChecked &&
                            !checkBoxAutoChangePin.isChecked
                )
            }

            is SettingsView.ToEvent.SetPin -> textViewSetPinValue.text = toEvent.value
            is SettingsView.ToEvent.UseWiFiOnly -> checkBoxUseWifiOnly.isChecked = toEvent.value
            is SettingsView.ToEvent.ServerPort -> textViewServerPortValue.text = Integer.toString(toEvent.value)

            SettingsView.ToEvent.ErrorServerPortBusy -> {
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
        setContentView(R.layout.activity_settings)

        presenter.attach(this)

        // Interface - Minimize on stream
        clMinimizeOnStream.setOnClickListener { _ -> checkBoxMinimizeOnStream.performClick() }
        checkBoxMinimizeOnStream.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.MinimizeOnStream(checkBoxMinimizeOnStream.isChecked))
        }

        // Interface - Stop on sleep
        clStopOnSleep.setOnClickListener { _ -> checkBoxStopOnSleep.performClick() }
        checkBoxStopOnSleep.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.StopOnSleep(checkBoxStopOnSleep.isChecked))
        }

        // Interface - StartService on boot
        clStartOnBoot.setOnClickListener { _ -> checkBoxStartOnBoot.performClick() }
        checkBoxStartOnBoot.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.StartOnBoot(checkBoxStartOnBoot.isChecked))
        }

        // Interface - HTML MJPEG check
        clMjpegCheck.setOnClickListener { _ -> checkBoxMjpegCheck.performClick() }
        checkBoxMjpegCheck.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.DisableMjpegCheck(checkBoxMjpegCheck.isChecked))
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
            EditTextDialog.newInstance(
                this@SettingsActivity,
                DIALOG_RESIZE_FACTOR_TAG,
                R.string.pref_resize,
                R.drawable.ic_pref_resize_black_24dp,
                R.string.pref_resize_dialog_text,
                2, 3,
                10, 150,
                Integer.toString(resizeFactor),
                true,
                R.string.pref_resize_dialog_result
            ).show(fragmentManager, DIALOG_RESIZE_FACTOR_TAG)
        }

        // Image - Jpeg Quality
        clJpegQuality.setOnClickListener { _ ->
            EditTextDialog.newInstance(
                this@SettingsActivity,
                DIALOG_JPEG_QUALITY_TAG,
                R.string.pref_jpeg_quality,
                R.drawable.ic_pref_high_quality_black_24dp,
                R.string.pref_jpeg_quality_dialog,
                2, 3,
                10, 100,
                textViewJpegQualityValue.text.toString()
            ).show(fragmentManager, DIALOG_JPEG_QUALITY_TAG)
        }

        // Security - Enable pin
        clEnablePin.setOnClickListener { _ -> checkBoxEnablePin.performClick() }
        checkBoxEnablePin.setOnClickListener { _ ->
            val checked = checkBoxEnablePin.isChecked
            enableDisableView(clHidePinOnStart, checked)
            enableDisableView(clNewPinOnAppStart, checked)
            enableDisableView(clAutoChangePin, checked)
            enableDisableView(
                clSetPin,
                checkBoxEnablePin.isChecked &&
                        !checkBoxNewPinOnAppStart.isChecked &&
                        !checkBoxAutoChangePin.isChecked
            )
            presenter.offer(SettingsView.FromEvent.EnablePin(checked))
        }

        // Security - Hide pin on start
        clHidePinOnStart.setOnClickListener { _ -> checkBoxHidePinOnStart.performClick() }
        checkBoxHidePinOnStart.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.HidePinOnStart(checkBoxHidePinOnStart.isChecked))
        }

        // Security - New pin on app start
        clNewPinOnAppStart.setOnClickListener { _ -> checkBoxNewPinOnAppStart.performClick() }
        checkBoxNewPinOnAppStart.setOnClickListener { _ ->
            enableDisableView(
                clSetPin,
                checkBoxEnablePin.isChecked &&
                        !checkBoxNewPinOnAppStart.isChecked &&
                        !checkBoxAutoChangePin.isChecked
            )
            presenter.offer(SettingsView.FromEvent.NewPinOnAppStart(checkBoxNewPinOnAppStart.isChecked))
        }

        // Security - Auto change pin
        clAutoChangePin.setOnClickListener { _ -> checkBoxAutoChangePin.performClick() }
        checkBoxAutoChangePin.setOnClickListener { _ ->
            enableDisableView(
                clSetPin,
                checkBoxEnablePin.isChecked &&
                        !checkBoxNewPinOnAppStart.isChecked &&
                        !checkBoxAutoChangePin.isChecked
            )
            presenter.offer(SettingsView.FromEvent.AutoChangePin(checkBoxAutoChangePin.isChecked))
        }

        // Security - Set pin
        clSetPin.setOnClickListener { _ ->
            EditTextDialog.newInstance(
                this@SettingsActivity,
                DIALOG_SET_PIN_TAG,
                R.string.pref_set_pin,
                R.drawable.ic_pref_key_black_24dp,
                R.string.pref_set_pin_dialog,
                4, 4,
                0, 9999,
                textViewSetPinValue.text.toString()
            ).show(fragmentManager, DIALOG_SET_PIN_TAG)
        }

        // Advanced - Use WiFi Only
        clUseWifiOnly.setOnClickListener { _ -> checkBoxUseWifiOnly.performClick() }
        checkBoxUseWifiOnly.setOnClickListener { _ ->
            presenter.offer(SettingsView.FromEvent.UseWiFiOnly(checkBoxUseWifiOnly.isChecked))
        }

        // Advanced - Server port
        clServerPort.setOnClickListener { _ ->
            EditTextDialog.newInstance(
                this@SettingsActivity,
                DIALOG_SERVER_PORT_TAG,
                R.string.pref_server_port,
                R.drawable.ic_pref_http_black_24dp,
                R.string.pref_server_port_dialog,
                4, 6,
                1025, 65535,
                textViewServerPortValue.text.toString()
            ).show(fragmentManager, DIALOG_SERVER_PORT_TAG)
        }
    }

    override fun onDialogResult(result: BaseDialog.DialogCallback.Result) {
        result as BaseDialog.DialogCallback.Result.Positive
        when (result.dialogTag) {
            DIALOG_RESIZE_FACTOR_TAG ->
                presenter.offer(SettingsView.FromEvent.ResizeFactor(Integer.parseInt(result.data)))

            DIALOG_JPEG_QUALITY_TAG ->
                presenter.offer(SettingsView.FromEvent.JpegQuality(Integer.parseInt(result.data)))

            DIALOG_SET_PIN_TAG ->
                presenter.offer(SettingsView.FromEvent.SetPin(result.data))

            DIALOG_SERVER_PORT_TAG ->
                presenter.offer(SettingsView.FromEvent.ServerPort(Integer.parseInt(result.data)))

            else -> Unit
        }
    }

    override fun onDestroy() {
        presenter.detach()
        super.onDestroy()
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (htmlBackColor == color) return
        viewHtmlBackColor.setBackgroundColor(color)
        presenter.offer(SettingsView.FromEvent.HtmlBackColor(color))
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

class EditTextDialog : BaseDialog() {
    companion object {
        internal const val TITLE_ICON = "TITLE_ICON"
        internal const val MIN_LENGTH = "MIN_LENGTH"
        internal const val MAX_LENGTH = "MAX_LENGTH"
        internal const val MIN_VALUE = "MIN_VALUE"
        internal const val MAX_VALUE = "MAX_VALUE"
        internal const val CURRENT_VALUE = "CURRENT_VALUE"
        internal const val RESIZE_IMAGE_DIALOG = "RESIZE_IMAGE_DIALOG"
        internal const val RESIZE_IMAGE_TEXT = "RESIZE_IMAGE_TEXT"

        fun newInstance(
            context: Context,
            dialogTag: String,
            @StringRes titleResId: Int = 0,
            @DrawableRes titleIconResId: Int = 0,
            @StringRes messageResId: Int = 0,
            minLength: Int, maxLength: Int,
            minValue: Int, maxValue: Int,
            currentValue: String = "",
            resizeImageDialog: Boolean = false,
            @StringRes resizeImageResultTextResId: Int = 0
        ) =
            newInstance(
                dialogTag = dialogTag,
                titleText = if (titleResId != 0) context.getString(titleResId) else "",
                titleIconResId = if (titleIconResId != 0) titleIconResId else 0,
                messageText = if (messageResId != 0) context.getString(messageResId) else "",
                minLength = if (minLength > 0) minLength else -1,
                maxLength = if (maxLength > 0) maxLength else -1,
                minValue = if (minValue >= 0) minValue else -1,
                maxValue = if (maxValue > 0) maxValue else -1,
                currentValue = if (currentValue.isNotBlank()) currentValue else "",
                resizeImageDialog = resizeImageDialog,
                resizeImageResultText = if (resizeImageResultTextResId != 0) context.getString(
                    resizeImageResultTextResId
                ) else ""
            )

        private fun newInstance(
            dialogTag: String,
            titleText: String = "",
            titleIconResId: Int = 0,
            messageText: String = "",
            minLength: Int, maxLength: Int,
            minValue: Int, maxValue: Int,
            currentValue: String = "",
            resizeImageDialog: Boolean = false,
            resizeImageResultText: String = ""
        ) =
            EditTextDialog().apply {
                arguments = Bundle().apply {
                    putString(DIALOG_TAG, dialogTag)
                    putString(TITLE_TEXT, titleText)
                    putInt(TITLE_ICON, titleIconResId)
                    putString(MESSAGE_TEXT, messageText)
                    putInt(MIN_LENGTH, minLength)
                    putInt(MAX_LENGTH, maxLength)
                    putInt(MIN_VALUE, minValue)
                    putInt(MAX_VALUE, maxValue)
                    putString(CURRENT_VALUE, currentValue)
                    putBoolean(RESIZE_IMAGE_DIALOG, resizeImageDialog)
                    putString(RESIZE_IMAGE_TEXT, resizeImageResultText)
                }
            }
    }


    private val screenSize: Point  by lazy {
        val screenSize = Point()
        val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.apply { getRealSize(screenSize) }
        screenSize
    }

    override fun onStart() {
        super.onStart()
        val positiveButton = (dialog as AlertDialog).getButton(Dialog.BUTTON_POSITIVE)
        val editTextValue = dialog.findViewById<EditText>(R.id.editTextSettingsEditTextValue)
        val textViewResult = dialog.findViewById<TextView>(R.id.textViewSettingsEditTextResult)

        arguments?.apply {
            positiveButton.isEnabled = editTextValue.text.length in getInt(MIN_LENGTH)..getInt(MAX_LENGTH) &&
                    Integer.parseInt(editTextValue.text.toString()) in getInt(MIN_VALUE)..getInt(MAX_VALUE)

            if (getBoolean(RESIZE_IMAGE_DIALOG)) {
                val newResizeFactor =
                    if (positiveButton.isEnabled) Integer.parseInt(editTextValue.text.toString()) / 100f
                    else Integer.parseInt(getString(CURRENT_VALUE)) / 100f
                getString(RESIZE_IMAGE_TEXT)?.let {
                    textViewResult.text = it
                        .format((screenSize.x * newResizeFactor).toInt(), (screenSize.y * newResizeFactor).toInt())
                }
            }

            editTextValue?.addTextChangedListener(SimpleTextWatcher { s ->
                positiveButton.isEnabled = s.length in getInt(MIN_LENGTH)..getInt(MAX_LENGTH) &&
                        Integer.parseInt(s.toString()) in getInt(MIN_VALUE)..getInt(MAX_VALUE)

                if (getBoolean(RESIZE_IMAGE_DIALOG)) {
                    val newResizeFactor = if (positiveButton.isEnabled) Integer.parseInt(s.toString()) / 100f
                    else Integer.parseInt(getString(CURRENT_VALUE)) / 100f
                    getString(RESIZE_IMAGE_TEXT)?.let {
                        textViewResult.text = it
                            .format((screenSize.x * newResizeFactor).toInt(), (screenSize.y * newResizeFactor).toInt())
                    }
                }
            })
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(activity ?: throw IllegalStateException("Activity is null")).apply {
            arguments?.apply {
                isCancelable = false

                val dialogTag = getString(DIALOG_TAG) ?: throw IllegalStateException("Tag not set")
                val minLength = getInt(MIN_LENGTH)
                if (minLength <= 0) throw IllegalStateException("minLength <= 0")
                val maxLength = getInt(MAX_LENGTH)
                if (maxLength <= 0) throw IllegalStateException("maxLength <= 0")
                val minValue = getInt(MIN_VALUE)
                if (minValue < 0) throw IllegalStateException("minValue < 0")
                val maxValue = getInt(MAX_VALUE)
                if (maxValue <= 0) throw IllegalStateException("maxValue <= 0")
                val currentValue = getString(CURRENT_VALUE) ?: ""

                getString(TITLE_TEXT)?.let { setTitle(it) }
                getInt(TITLE_ICON).let { if (it > 0) setIcon(it) }

                val dialogView = LayoutInflater.from(activity)
                    .inflate(R.layout.settings_edittext_dialog, null, false)
                with(dialogView) {
                    if (getBoolean(RESIZE_IMAGE_DIALOG)) {
                        getString(MESSAGE_TEXT)?.let {
                            textViewSettingsEditTextContent.text = it.format(screenSize.x, screenSize.y)
                        }
                    } else {
                        getString(MESSAGE_TEXT)?.let { textViewSettingsEditTextContent.text = it }
                        textViewSettingsEditTextResult.visibility = View.GONE
                    }

                    editTextSettingsEditTextValue.setText(currentValue)
                    editTextSettingsEditTextValue.setSelection(currentValue.length)
                    editTextSettingsEditTextValue.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
                }
                setView(dialogView)

                setPositiveButton(android.R.string.ok, { _, _ ->
                    val newStringValue = dialogView.editTextSettingsEditTextValue.text.toString()
                    if (newStringValue.length in minLength..maxLength &&
                        currentValue != newStringValue &&
                        Integer.parseInt(newStringValue) in minValue..maxValue
                    )
                        sendDialogResult(DialogCallback.Result.Positive(dialogTag, newStringValue))
                })

                setNegativeButton(android.R.string.cancel, null)
            }
        }.create().apply { setCanceledOnTouchOutside(false) }
    }

    class SimpleTextWatcher(private val afterTextChangedBlock: (s: Editable) -> Unit) : TextWatcher {
        override fun afterTextChanged(s: Editable?) = s?.let { afterTextChangedBlock(it) } as Unit
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
    }
}