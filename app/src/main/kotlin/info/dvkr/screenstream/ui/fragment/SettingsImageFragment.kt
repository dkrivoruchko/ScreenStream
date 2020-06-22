package info.dvkr.screenstream.ui.fragment

import android.graphics.Point
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
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
import info.dvkr.screenstream.databinding.DialogSettingsCropBinding
import info.dvkr.screenstream.databinding.DialogSettingsResizeBinding
import info.dvkr.screenstream.databinding.FragmentSettingsImageBinding
import info.dvkr.screenstream.ui.viewBinding
import org.koin.android.ext.android.inject

class SettingsImageFragment : Fragment(R.layout.fragment_settings_image) {

    private val settings: Settings by inject()
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) = when (key) {
            Settings.Key.RESIZE_FACTOR ->
                binding.tvFragmentSettingsResizeImageValue.text =
                    getString(R.string.pref_resize_value, settings.resizeFactor)

            Settings.Key.ROTATION ->
                binding.tvFragmentSettingsRotationValue.text = getString(R.string.pref_rotate_value, settings.rotation)

            Settings.Key.MAX_FPS ->
                binding.tvFragmentSettingsFpsValue.text = settings.maxFPS.toString()

            Settings.Key.JPEG_QUALITY ->
                binding.tvFragmentSettingsJpegQualityValue.text = settings.jpegQuality.toString()

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

    private val binding by viewBinding { fragment -> FragmentSettingsImageBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Image - VR mode
        with(binding.cbFragmentSettingsVrMode) {
            isChecked = isVRModeEnabled()
            setOnClickListener {
                if (isVRModeEnabled()) settings.vrMode = Settings.Default.VR_MODE_DISABLE
                else {
                    isChecked = false
                    MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
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
            binding.clFragmentSettingsVrMode.setOnClickListener { performClick() }
        }

        // Image - Crop image
        with(binding.cbFragmentSettingsCropImage) {
            isChecked = settings.imageCrop
            setOnClickListener {
                if (settings.imageCrop) settings.imageCrop = false
                else {
                    isChecked = false
                    MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT))
                        .lifecycleOwner(viewLifecycleOwner)
                        .title(R.string.pref_crop)
                        .icon(R.drawable.ic_settings_crop_24dp)
                        .customView(R.layout.dialog_settings_crop, scrollable = true)
                        .positiveButton(android.R.string.ok) { dialog ->
                            DialogSettingsCropBinding.bind(dialog.getCustomView()).apply {
                                val newTopCrop = tietDialogSettingsCropTop.text.toString().toInt()
                                if (newTopCrop != settings.imageCropTop) settings.imageCropTop = newTopCrop
                                val newBottomCrop = tietDialogSettingsCropBottom.text.toString().toInt()
                                if (newBottomCrop != settings.imageCropBottom) settings.imageCropBottom = newBottomCrop
                                val newLeftCrop = tietDialogSettingsCropLeft.text.toString().toInt()
                                if (newLeftCrop != settings.imageCropLeft) settings.imageCropLeft = newLeftCrop
                                val newRightCrop = tietDialogSettingsCropRight.text.toString().toInt()
                                if (newRightCrop != settings.imageCropRight) settings.imageCropRight = newRightCrop

                                settings.imageCrop = newTopCrop + newBottomCrop + newLeftCrop + newRightCrop != 0
                                binding.cbFragmentSettingsCropImage.isChecked = settings.imageCrop
                            }
                        }
                        .negativeButton(android.R.string.cancel)
                        .apply Dialog@{
                            DialogSettingsCropBinding.bind(getCustomView()).apply {
                                tietDialogSettingsCropTop.setText(settings.imageCropTop.toString())
                                tietDialogSettingsCropBottom.setText(settings.imageCropBottom.toString())
                                tietDialogSettingsCropLeft.setText(settings.imageCropLeft.toString())
                                tietDialogSettingsCropRight.setText(settings.imageCropRight.toString())

                                try {
                                    tietDialogSettingsCropTop.setSelection(settings.imageCropTop.toString().length)
                                } catch (ignore: Throwable) {
                                }

                                val validateTextWatcher = SimpleTextWatcher {
                                    validateCropValues(
                                        this@Dialog,
                                        tietDialogSettingsCropTop, tietDialogSettingsCropBottom,
                                        tietDialogSettingsCropLeft, tietDialogSettingsCropRight,
                                        tvDialogSettingsCropErrorMessage
                                    )
                                }

                                tietDialogSettingsCropTop.addTextChangedListener(validateTextWatcher)
                                tietDialogSettingsCropBottom.addTextChangedListener(validateTextWatcher)
                                tietDialogSettingsCropLeft.addTextChangedListener(validateTextWatcher)
                                tietDialogSettingsCropRight.addTextChangedListener(validateTextWatcher)
                            }

                            show()
                        }
                }
            }
            binding.clFragmentSettingsCropImage.setOnClickListener { performClick() }
        }

        // Image - Resize factor
        binding.tvFragmentSettingsResizeImageValue.text = getString(R.string.pref_resize_value, settings.resizeFactor)
        val resizePictureSizeString = getString(R.string.pref_resize_dialog_result)
        binding.clFragmentSettingsResizeImage.setOnClickListener {
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT))
                .lifecycleOwner(viewLifecycleOwner)
                .title(R.string.pref_resize)
                .icon(R.drawable.ic_settings_resize_24dp)
                .customView(R.layout.dialog_settings_resize, scrollable = true)
                .positiveButton(android.R.string.ok) { dialog ->
                    DialogSettingsResizeBinding.bind(dialog.getCustomView()).apply {
                        val newResizeFactor = tietDialogSettingsResize.text.toString().toInt()
                        if (newResizeFactor != settings.resizeFactor) settings.resizeFactor = newResizeFactor
                    }
                }
                .negativeButton(android.R.string.cancel)
                .apply Dialog@{
                    DialogSettingsResizeBinding.bind(getCustomView()).apply {
                        tvDialogSettingsResizeContent.text =
                            getString(R.string.pref_resize_dialog_text, screenSize.x, screenSize.y)

                        tiDialogSettingsResize.isCounterEnabled = true
                        tiDialogSettingsResize.counterMaxLength = 3

                        with(tietDialogSettingsResize) {
                            addTextChangedListener(SimpleTextWatcher { text ->
                                val isValid = text.length in 2..3 && text.toString().toInt() in 10..150
                                this@Dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                                val newResizeFactor =
                                    (if (isValid) text.toString().toInt() else settings.resizeFactor) / 100f

                                tvDialogSettingsResizeResult.text = resizePictureSizeString.format(
                                    (screenSize.x * newResizeFactor).toInt(),
                                    (screenSize.y * newResizeFactor).toInt()
                                )
                            })
                            setText(settings.resizeFactor.toString())
                            try {
                                setSelection(settings.resizeFactor.toString().length)
                            } catch (ignore: Throwable) {
                            }
                            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                        }

                        tvDialogSettingsResizeResult.text = resizePictureSizeString.format(
                            (screenSize.x * settings.resizeFactor / 100f).toInt(),
                            (screenSize.y * settings.resizeFactor / 100f).toInt()
                        )

                        show()
                    }
                }
        }

        // Image - Rotation
        binding.tvFragmentSettingsRotationValue.text = getString(R.string.pref_rotate_value, settings.rotation)
        binding.clFragmentSettingsRotation.setOnClickListener {
            val indexOld = rotationList.first { it.second == settings.rotation }.first
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
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
        binding.tvFragmentSettingsFpsValue.text = settings.maxFPS.toString()
        binding.clFragmentSettingsFps.setOnClickListener {
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
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
                    val isValid = text.length in 1..2 && text.toString().toInt() in 1..60
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
        binding.tvFragmentSettingsJpegQualityValue.text = settings.jpegQuality.toString()
        binding.clFragmentSettingsJpegQuality.setOnClickListener {
            MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
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