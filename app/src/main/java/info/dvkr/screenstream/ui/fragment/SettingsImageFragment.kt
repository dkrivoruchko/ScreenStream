package info.dvkr.screenstream.ui.fragment

import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.setPeekHeight
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.databinding.DialogSettingsCropBinding
import info.dvkr.screenstream.databinding.DialogSettingsResizeBinding
import info.dvkr.screenstream.databinding.FragmentSettingsImageBinding
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject

class SettingsImageFragment : Fragment(R.layout.fragment_settings_image) {

    private val mjpegSettings: MjpegSettings by inject()
    private val screenBounds: Rect by lazy {
        WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(requireActivity()).bounds
    }

    private val rotationList = listOf(
        0 to MjpegSettings.Values.ROTATION_0,
        1 to MjpegSettings.Values.ROTATION_90,
        2 to MjpegSettings.Values.ROTATION_180,
        3 to MjpegSettings.Values.ROTATION_270
    )

    private val binding by viewBinding { fragment -> FragmentSettingsImageBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Image - VR mode
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsVrMode.isChecked = isVRModeEnabled()
        }
        binding.cbFragmentSettingsVrMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                if (isVRModeEnabled()) {
                    mjpegSettings.setVrMode(MjpegSettings.Default.VR_MODE_DISABLE)
                } else {
                    binding.cbFragmentSettingsVrMode.isChecked = false
                    MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                        adjustPeekHeight()
                        lifecycleOwner(viewLifecycleOwner)
                        title(R.string.pref_vr_mode)
                        icon(R.drawable.ic_settings_vr_mode_24dp)
                        listItemsSingleChoice(R.array.pref_vr_mode_options) { _, index, _ ->
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                                mjpegSettings.setVrMode(index + 1)
                                binding.cbFragmentSettingsVrMode.isChecked = isVRModeEnabled()
                            }
                        }
                        positiveButton(android.R.string.ok)
                        negativeButton(android.R.string.cancel)
                    }
                }
            }
        }
        binding.clFragmentSettingsVrMode.setOnClickListener { binding.cbFragmentSettingsVrMode.performClick() }

        // Image - Crop image
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsCropImage.isChecked = mjpegSettings.imageCropFlow.first()
        }
        binding.cbFragmentSettingsCropImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                if (mjpegSettings.imageCropFlow.first()) {
                    mjpegSettings.setImageCrop(false)
                } else {
                    binding.cbFragmentSettingsCropImage.isChecked = false
                    val topCrop = mjpegSettings.imageCropTopFlow.first()
                    val bottomCrop = mjpegSettings.imageCropBottomFlow.first()
                    val leftCrop = mjpegSettings.imageCropLeftFlow.first()
                    val rightCrop = mjpegSettings.imageCropRightFlow.first()
                    MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT))
                        .adjustPeekHeight()
                        .lifecycleOwner(viewLifecycleOwner)
                        .title(R.string.pref_crop)
                        .icon(R.drawable.ic_settings_crop_24dp)
                        .customView(R.layout.dialog_settings_crop, scrollable = true)
                        .positiveButton(android.R.string.ok) { dialog ->
                            DialogSettingsCropBinding.bind(dialog.getCustomView()).apply {
                                viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                                    val newTopCrop = tietDialogSettingsCropTop.text.toString().toIntOrNull() ?: topCrop
                                    val newBottomCrop = tietDialogSettingsCropBottom.text.toString().toIntOrNull() ?: bottomCrop
                                    val newLeftCrop = tietDialogSettingsCropLeft.text.toString().toIntOrNull() ?: leftCrop
                                    val newRightCrop = tietDialogSettingsCropRight.text.toString().toIntOrNull() ?: rightCrop

                                    if (newTopCrop != topCrop) mjpegSettings.setImageCropTop(newTopCrop)
                                    if (newBottomCrop != bottomCrop) mjpegSettings.setImageCropBottom(newBottomCrop)
                                    if (newLeftCrop != leftCrop) mjpegSettings.setImageCropLeft(newLeftCrop)
                                    if (newRightCrop != rightCrop) mjpegSettings.setImageCropRight(newRightCrop)

                                    val newImageCrop = newTopCrop + newBottomCrop + newLeftCrop + newRightCrop != 0
                                    binding.cbFragmentSettingsCropImage.isChecked = newImageCrop
                                    mjpegSettings.setImageCrop(newImageCrop)
                                }
                            }
                        }
                        .negativeButton(android.R.string.cancel)
                        .apply Dialog@{
                            DialogSettingsCropBinding.bind(getCustomView()).apply {
                                tietDialogSettingsCropTop.setText(topCrop.toString())
                                tietDialogSettingsCropBottom.setText(bottomCrop.toString())
                                tietDialogSettingsCropLeft.setText(leftCrop.toString())
                                tietDialogSettingsCropRight.setText(rightCrop.toString())

                                try {
                                    tietDialogSettingsCropTop.setSelection(topCrop.toString().length)
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
        }
        binding.clFragmentSettingsCropImage.setOnClickListener { binding.cbFragmentSettingsCropImage.performClick() }


        // Image - Grayscale
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            binding.cbFragmentSettingsGrayscaleImage.isChecked = mjpegSettings.imageGrayscaleFlow.first()
        }
        binding.cbFragmentSettingsGrayscaleImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                mjpegSettings.setImageGrayscale(binding.cbFragmentSettingsGrayscaleImage.isChecked)
            }
        }
        binding.clFragmentSettingsGrayscaleImage.setOnClickListener { binding.cbFragmentSettingsGrayscaleImage.performClick() }


        // Image - Resize factor
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            mjpegSettings.resizeFactorFlow
                .onEach { binding.tvFragmentSettingsResizeImageValue.text = getString(R.string.pref_resize_value, it) }
                .launchIn(this)
        }
        binding.clFragmentSettingsResizeImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val resizeFactor = mjpegSettings.resizeFactorFlow.first()
                val resizePictureSizeString = getString(R.string.pref_resize_dialog_result)
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT))
                    .adjustPeekHeight()
                    .lifecycleOwner(viewLifecycleOwner)
                    .title(R.string.pref_resize)
                    .icon(R.drawable.ic_settings_resize_24dp)
                    .customView(R.layout.dialog_settings_resize, scrollable = true)
                    .positiveButton(android.R.string.ok) { dialog ->
                        DialogSettingsResizeBinding.bind(dialog.getCustomView()).apply {
                            val newResizeFactor = tietDialogSettingsResize.text.toString().toInt()
                            if (newResizeFactor != resizeFactor)
                                viewLifecycleOwner.lifecycleScope.launchWhenCreated { mjpegSettings.setResizeFactor(newResizeFactor) }
                        }
                    }
                    .negativeButton(android.R.string.cancel)
                    .apply Dialog@{
                        DialogSettingsResizeBinding.bind(getCustomView()).apply {
                            tvDialogSettingsResizeContent.text =
                                getString(R.string.pref_resize_dialog_text, screenBounds.width(), screenBounds.height())

                            tiDialogSettingsResize.isCounterEnabled = true
                            tiDialogSettingsResize.counterMaxLength = 3

                            with(tietDialogSettingsResize) {
                                addTextChangedListener(SimpleTextWatcher { text ->
                                    val isValid = text.length in 2..3 && (text.toString().toIntOrNull() ?: -1) in 10..150
                                    this@Dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                                    val newResizeFactor = (if (isValid) text.toString().toInt() else resizeFactor) / 100f

                                    tvDialogSettingsResizeResult.text = resizePictureSizeString.format(
                                        (screenBounds.width() * newResizeFactor).toInt(),
                                        (screenBounds.height() * newResizeFactor).toInt()
                                    )
                                })
                                setText(resizeFactor.toString())
                                try {
                                    setSelection(resizeFactor.toString().length)
                                } catch (ignore: Throwable) {
                                }
                                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                            }

                            tvDialogSettingsResizeResult.text = resizePictureSizeString.format(
                                (screenBounds.width() * resizeFactor / 100f).toInt(),
                                (screenBounds.height() * resizeFactor / 100f).toInt()
                            )

                            show()
                        }
                    }
            }
        }

        // Image - Rotation
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            mjpegSettings.rotationFlow
                .onEach { binding.tvFragmentSettingsRotationValue.text = getString(R.string.pref_rotate_value, it) }
                .launchIn(this)
        }
        binding.clFragmentSettingsRotation.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val rotation = mjpegSettings.rotationFlow.first()
                val indexOld = rotationList.first { it.second == rotation }.first
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_rotate)
                    icon(R.drawable.ic_settings_rotation_24dp)
                    listItemsSingleChoice(R.array.pref_rotate_options, initialSelection = indexOld) { _, index, _ ->
                        val newRotation = rotationList.firstOrNull { item -> item.first == index }?.second
                            ?: throw IllegalArgumentException("Unknown rotation index")
                        viewLifecycleOwner.lifecycleScope.launchWhenCreated { mjpegSettings.setRotation(newRotation) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }

        // Image - Max FPS
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            mjpegSettings.maxFPSFlow.onEach { binding.tvFragmentSettingsFpsValue.text = it.toString() }.launchIn(this)
        }
        binding.clFragmentSettingsFps.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val maxFPS = mjpegSettings.maxFPSFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_fps)
                    icon(R.drawable.ic_settings_fps_24dp)
                    message(R.string.pref_fps_dialog)
                    input(
                        prefill = maxFPS.toString(),
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 2,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length in 1..2 && (text.toString().toIntOrNull() ?: -1) in 1..60
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString()?.toIntOrNull() ?: maxFPS
                        if (maxFPS != newValue)
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated { mjpegSettings.setMaxFPS(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Image - Jpeg Quality
        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            mjpegSettings.jpegQualityFlow
                .onEach { binding.tvFragmentSettingsJpegQualityValue.text = it.toString() }
                .launchIn(this)
        }
        binding.clFragmentSettingsJpegQuality.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val jpegQuality = mjpegSettings.jpegQualityFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.pref_jpeg_quality)
                    icon(R.drawable.ic_settings_high_quality_24dp)
                    message(R.string.pref_jpeg_quality_dialog)
                    input(
                        prefill = jpegQuality.toString(),
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 3,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length in 2..3 && (text.toString().toIntOrNull() ?: -1) in 10..100
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString()?.toIntOrNull() ?: jpegQuality
                        if (jpegQuality != newValue)
                            viewLifecycleOwner.lifecycleScope.launchWhenCreated { mjpegSettings.setJpegQuality(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop"))
        super.onStop()
    }

    private fun MaterialDialog.adjustPeekHeight(): MaterialDialog {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(requireActivity())
        val heightDp = metrics.bounds.height() / resources.displayMetrics.density
        if (heightDp < 480f) setPeekHeight(metrics.bounds.height())
        return this
    }

    private suspend fun isVRModeEnabled(): Boolean =
        mjpegSettings.vrModeFlow.first() in arrayOf(MjpegSettings.Default.VR_MODE_RIGHT, MjpegSettings.Default.VR_MODE_LEFT)

    private fun validateCropValues(
        dialog: MaterialDialog,
        topView: EditText, bottomView: EditText, leftView: EditText, rightView: EditText, errorView: TextView
    ) {
        val topCrop = topView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toIntOrNull() ?: -1 }
        val bottomCrop = bottomView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toIntOrNull() ?: -1 }
        val leftCrop = leftView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toIntOrNull() ?: -1 }
        val rightCrop = rightView.text.let { if (it.isNullOrBlank()) -1 else it.toString().toIntOrNull() ?: -1 }

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