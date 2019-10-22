package info.dvkr.screenstream.ui.fragment

import android.graphics.Point
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.android.synthetic.main.dialog_settings_crop.view.*
import kotlinx.android.synthetic.main.dialog_settings_resize.view.*
import kotlinx.android.synthetic.main.fragment_settings_image.*
import org.koin.android.ext.android.inject

class SettingsImageFragment : Fragment() {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.RESIZE_FACTOR ->
                tv_fragment_settings_resize_image_value.text =
                    getString(R.string.pref_resize_value, settings.resizeFactor)

            Settings.Key.ROTATION ->
                tv_fragment_settings_rotation_value.text = getString(R.string.pref_rotate_value, settings.rotation)

            Settings.Key.MAX_FPS ->
                tv_fragment_settings_fps_value.text = settings.maxFPS.toString()

            Settings.Key.JPEG_QUALITY ->
                tv_fragment_settings_jpeg_quality_value.text = settings.jpegQuality.toString()

            else -> Unit
        }
    }

    private val screenSize: Point by lazy {
        Point().apply {
            ContextCompat.getSystemService(requireContext(), WindowManager::class.java)
                ?.defaultDisplay?.getRealSize(this)
        }
    }

    private val rotationList = listOf(
        0 to Settings.Values.ROTATION_0,
        1 to Settings.Values.ROTATION_90,
        2 to Settings.Values.ROTATION_180,
        3 to Settings.Values.ROTATION_270
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_image, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Image - VR mode
        with(cb_fragment_settings_vr_mode) {
            isChecked = isVRModeEnabled()
            setOnClickListener {
                if (isVRModeEnabled()) settings.vrMode = Settings.Default.VR_MODE_DISABLE
                else {
                    isChecked = false
                    MaterialDialog(requireActivity()).show {
                        lifecycleOwner(viewLifecycleOwner)
                        title(R.string.pref_vr_mode)
                        icon(R.drawable.ic_settings_vr_mode_24dp)
                        listItemsSingleChoice(R.array.pref_vr_mode_options) { _, index, _ ->
                            settings.vrMode = index + 1
                            isChecked = isVRModeEnabled()
                        }
                        positiveButton(android.R.string.ok)
                        negativeButton(android.R.string.cancel)
                    }
                }
            }
            cl_fragment_settings_vr_mode.setOnClickListener { performClick() }
        }

        // Image - Crop image
        with(cb_fragment_settings_crop_image) {
            isChecked = settings.imageCrop
            setOnClickListener {
                if (settings.imageCrop) settings.imageCrop = false
                else {
                    isChecked = false
                    MaterialDialog(requireActivity())
                        .lifecycleOwner(viewLifecycleOwner)
                        .title(R.string.pref_crop)
                        .icon(R.drawable.ic_settings_crop_24dp)
                        .customView(R.layout.dialog_settings_crop, scrollable = true)
                        .positiveButton(android.R.string.ok) { dialog ->
                            dialog.getCustomView().apply DialogView@{
                                val newTopCrop = tiet_dialog_settings_crop_top.text.toString().toInt()
                                if (newTopCrop != settings.imageCropTop) settings.imageCropTop = newTopCrop
                                val newBottomCrop = tiet_dialog_settings_crop_bottom.text.toString().toInt()
                                if (newBottomCrop != settings.imageCropBottom) settings.imageCropBottom = newBottomCrop
                                val newLeftCrop = tiet_dialog_settings_crop_left.text.toString().toInt()
                                if (newLeftCrop != settings.imageCropLeft) settings.imageCropLeft = newLeftCrop
                                val newRightCrop = tiet_dialog_settings_crop_right.text.toString().toInt()
                                if (newRightCrop != settings.imageCropRight) settings.imageCropRight = newRightCrop

                                settings.imageCrop = newTopCrop + newBottomCrop + newLeftCrop + newRightCrop != 0
                                cb_fragment_settings_crop_image.isChecked = settings.imageCrop
                            }
                        }
                        .negativeButton(android.R.string.cancel)
                        .apply Dialog@{
                            getCustomView().apply DialogView@{
                                tiet_dialog_settings_crop_top.setText(settings.imageCropTop.toString())
                                tiet_dialog_settings_crop_bottom.setText(settings.imageCropBottom.toString())
                                tiet_dialog_settings_crop_left.setText(settings.imageCropLeft.toString())
                                tiet_dialog_settings_crop_right.setText(settings.imageCropRight.toString())

                                tiet_dialog_settings_crop_top.setSelection(settings.imageCropTop.toString().length)

                                val validateTextWatcher = SimpleTextWatcher {
                                    validateCropValues(
                                        this@Dialog,
                                        tiet_dialog_settings_crop_top,
                                        tiet_dialog_settings_crop_bottom,
                                        tiet_dialog_settings_crop_left,
                                        tiet_dialog_settings_crop_right,
                                        tv_dialog_settings_crop_error_message
                                    )
                                }

                                tiet_dialog_settings_crop_top.addTextChangedListener(validateTextWatcher)
                                tiet_dialog_settings_crop_bottom.addTextChangedListener(validateTextWatcher)
                                tiet_dialog_settings_crop_left.addTextChangedListener(validateTextWatcher)
                                tiet_dialog_settings_crop_right.addTextChangedListener(validateTextWatcher)
                            }

                            show()
                        }
                }
            }
            cl_fragment_settings_crop_image.setOnClickListener { performClick() }
        }

        // Image - Resize factor
        tv_fragment_settings_resize_image_value.text = getString(R.string.pref_resize_value, settings.resizeFactor)
        val resizePictureSizeString = getString(R.string.pref_resize_dialog_result)
        cl_fragment_settings_resize_image.setOnClickListener {
            MaterialDialog(requireActivity())
                .lifecycleOwner(viewLifecycleOwner)
                .title(R.string.pref_resize)
                .icon(R.drawable.ic_settings_resize_24dp)
                .customView(R.layout.dialog_settings_resize, scrollable = true)
                .positiveButton(android.R.string.ok) { dialog ->
                    dialog.getCustomView().apply DialogView@{
                        val newResizeFactor = tiet_dialog_settings_resize.text.toString().toInt()
                        if (newResizeFactor != settings.resizeFactor) settings.resizeFactor = newResizeFactor
                    }
                }
                .negativeButton(android.R.string.cancel)
                .apply Dialog@{
                    getCustomView().apply DialogView@{
                        tv_dialog_settings_resize_content.text =
                            getString(R.string.pref_resize_dialog_text, screenSize.x, screenSize.y)

                        ti_dialog_settings_resize.isCounterEnabled = true
                        ti_dialog_settings_resize.counterMaxLength = 3

                        with(tiet_dialog_settings_resize) {
                            addTextChangedListener(SimpleTextWatcher { text ->
                                val isValid = text.length in 2..3 && text.toString().toInt() in 10..150
                                this@Dialog.setActionButtonEnabled(
                                    WhichButton.POSITIVE, isValid
                                )
                                val newResizeFactor =
                                    (if (isValid) text.toString().toInt() else settings.resizeFactor) / 100f
                                this@DialogView.tv_dialog_settings_resize_result.text = resizePictureSizeString.format(
                                    (screenSize.x * newResizeFactor).toInt(), (screenSize.y * newResizeFactor).toInt()
                                )
                            })
                            setText(settings.resizeFactor.toString())
                            setSelection(settings.resizeFactor.toString().length)
                            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                        }

                        tv_dialog_settings_resize_result.text = resizePictureSizeString.format(
                            (screenSize.x * settings.resizeFactor / 100f).toInt(),
                            (screenSize.y * settings.resizeFactor / 100f).toInt()
                        )

                        show()
                    }
                }
        }

        // Image - Rotation
        tv_fragment_settings_rotation_value.text = getString(R.string.pref_rotate_value, settings.rotation)
        cl_fragment_settings_rotation.setOnClickListener {
            val indexOld = rotationList.first { it.second == settings.rotation }.first
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_rotate)
                icon(R.drawable.ic_settings_rotation_24dp)
                listItemsSingleChoice(R.array.pref_rotate_options, initialSelection = indexOld) { _, index, _ ->
                    settings.rotation = rotationList.firstOrNull { item -> item.first == index }?.second
                        ?: throw IllegalArgumentException("Unknown rotation index")
                }
                positiveButton(android.R.string.ok)
                negativeButton(android.R.string.cancel)
            }
        }

        // Image - Max FPS
        tv_fragment_settings_fps_value.text = settings.maxFPS.toString()
        cl_fragment_settings_fps.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_fps)
                icon(R.drawable.ic_settings_fps_24dp)
                message(R.string.pref_fps_dialog)
                input(
                    prefill = settings.maxFPS.toString(),
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    maxLength = 2,
                    waitForPositiveButton = false
                ) { dialog, text ->
                    val isValid = text.length in 1..2 && text.toString().toInt() in 1..30
                    dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                }
                positiveButton(android.R.string.ok) { dialog ->
                    val newValue = dialog.getInputField().text?.toString()?.toInt() ?: settings.maxFPS
                    if (settings.maxFPS != newValue) settings.maxFPS = newValue
                }
                negativeButton(android.R.string.cancel)
                getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
                getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
        }


        // Image - Jpeg Quality
        tv_fragment_settings_jpeg_quality_value.text = settings.jpegQuality.toString()
        cl_fragment_settings_jpeg_quality.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.pref_jpeg_quality)
                icon(R.drawable.ic_settings_high_quality_24dp)
                message(R.string.pref_jpeg_quality_dialog)
                input(
                    prefill = settings.jpegQuality.toString(),
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    maxLength = 3,
                    waitForPositiveButton = false
                ) { dialog, text ->
                    val isValid = text.length in 2..3 && text.toString().toInt() in 10..100
                    dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                }
                positiveButton(android.R.string.ok) { dialog ->
                    val newValue = dialog.getInputField().text?.toString()?.toInt() ?: settings.jpegQuality
                    if (settings.jpegQuality != newValue) settings.jpegQuality = newValue
                }
                negativeButton(android.R.string.cancel)
                getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
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

    private fun isVRModeEnabled(): Boolean =
        settings.vrMode in arrayOf(Settings.Default.VR_MODE_RIGHT, Settings.Default.VR_MODE_LEFT)

    private fun validateCropValues(
        dialog: MaterialDialog,
        topView: EditText, bottomView: EditText, leftView: EditText, rightView: EditText, errorView: TextView
    ) {
        val topCrop = topView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toInt() }
        val bottomCrop = bottomView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toInt() }
        val leftCrop = leftView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toInt() }
        val rightCrop = rightView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toInt() }

        if (topCrop >= 0 && bottomCrop >= 0 && leftCrop >= 0 && rightCrop >= 0) {
            dialog.setActionButtonEnabled(WhichButton.POSITIVE, true)
            errorView.text = getString(R.string.pref_crop_dialog_warning_message)
        } else {
            dialog.setActionButtonEnabled(WhichButton.POSITIVE, false)
            errorView.text = getString(R.string.pref_crop_dialog_error_message)
        }
    }

    private class SimpleTextWatcher(private val afterTextChangedBlock: (s: Editable) -> Unit) : TextWatcher {
        override fun afterTextChanged(s: Editable?) = s?.let { afterTextChangedBlock(it) } as Unit
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
    }
}