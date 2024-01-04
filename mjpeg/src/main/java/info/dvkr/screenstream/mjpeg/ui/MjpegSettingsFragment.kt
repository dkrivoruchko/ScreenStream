package info.dvkr.screenstream.mjpeg.ui

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RestrictTo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.setPeekHeight
import com.afollestad.materialdialogs.color.ColorPalette
import com.afollestad.materialdialogs.color.colorChooser
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.elvishew.xlog.XLog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.mjpeg.MjpegKoinQualifier
import info.dvkr.screenstream.mjpeg.MjpegSettings
import info.dvkr.screenstream.mjpeg.MjpegStreamingModule
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.databinding.DialogMjpegSettingsCropBinding
import info.dvkr.screenstream.mjpeg.databinding.DialogMjpegSettingsResizeBinding
import info.dvkr.screenstream.mjpeg.databinding.FragmentMjpegSettingsBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class MjpegSettingsFragment : BottomSheetDialogFragment(R.layout.fragment_mjpeg_settings) {

    internal companion object {
        internal const val TAG = "info.dvkr.screenstream.mjpeg.ui.MjpegSettingsFragment"
    }

    private val binding by viewBinding { fragment -> FragmentMjpegSettingsBinding.bind(fragment.requireView()) }

    private val mjpegStreamingModule: MjpegStreamingModule by inject(named(MjpegKoinQualifier), LazyThreadSafetyMode.NONE)

    private val screenBounds: Rect by lazy { WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(requireActivity()).bounds }

    private val rotationList = listOf(
        0 to MjpegSettings.Values.ROTATION_0,
        1 to MjpegSettings.Values.ROTATION_90,
        2 to MjpegSettings.Values.ROTATION_180,
        3 to MjpegSettings.Values.ROTATION_270
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        XLog.d(getLog("onViewCreated"))

        if (runBlocking { mjpegStreamingModule.isRunning.first() }.not()) {
            dismissAllowingStateLoss()
            return
        }

        mjpegStreamingModule.isRunning.onEach { if (it.not()) dismissAllowingStateLoss() }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.bFragmentSettingsClose.setOnClickListener { dismissAllowingStateLoss() }

        val mjpegSettings = mjpegStreamingModule.mjpegSettings

        // Interface - Keep awake
        if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO")) {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.cbFragmentSettingsKeepAwake.isChecked = mjpegSettings.keepAwakeFlow.first()
            }
            binding.cbFragmentSettingsKeepAwake.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    mjpegSettings.setKeepAwake(binding.cbFragmentSettingsKeepAwake.isChecked)
                }
            }
            binding.clFragmentSettingsKeepAwake.setOnClickListener { binding.cbFragmentSettingsKeepAwake.performClick() }
        } else {
            binding.clFragmentSettingsKeepAwake.visibility = View.GONE
            binding.vFragmentSettingsKeepAwake.visibility = View.GONE
        }

        // Interface - Stop on sleep
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsStopOnSleep.isChecked = mjpegSettings.stopOnSleepFlow.first()
        }
        binding.cbFragmentSettingsStopOnSleep.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setStopOnSleep(binding.cbFragmentSettingsStopOnSleep.isChecked)
            }
        }
        binding.clFragmentSettingsStopOnSleep.setOnClickListener { binding.cbFragmentSettingsStopOnSleep.performClick() }

        // Interface - Notify slow connections
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsNotifySlowConnections.isChecked = mjpegSettings.notifySlowConnectionsFlow.first()
        }
        binding.cbFragmentSettingsNotifySlowConnections.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setNotifySlowConnections(binding.cbFragmentSettingsNotifySlowConnections.isChecked)
            }
        }
        binding.clFragmentSettingsNotifySlowConnections.setOnClickListener { binding.cbFragmentSettingsNotifySlowConnections.performClick() }

        // Interface - Web page Image buttons //TODO disable if pin enabled
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsHtmlButtons.isChecked = mjpegSettings.htmlEnableButtonsFlow.first()
        }
        binding.cbFragmentSettingsHtmlButtons.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setHtmlEnableButtons(binding.cbFragmentSettingsHtmlButtons.isChecked)
            }
        }
        binding.clFragmentSettingsHtmlButtons.setOnClickListener { binding.cbFragmentSettingsHtmlButtons.performClick() }

        // Interface - Web page show "Press START on device"
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsHtmlPressStart.isChecked = mjpegSettings.htmlShowPressStartFlow.first()
        }
        binding.cbFragmentSettingsHtmlPressStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setHtmlShowPressStart(binding.cbFragmentSettingsHtmlPressStart.isChecked)
            }
        }
        binding.clFragmentSettingsHtmlPressStart.setOnClickListener { binding.cbFragmentSettingsHtmlPressStart.performClick() }

        // Interface - Web page HTML Back color
        mjpegSettings.htmlBackColorFlow.onEach {
            binding.vFragmentSettingsHtmlBackColor.color = it
            binding.vFragmentSettingsHtmlBackColor.border = ContextCompat.getColor(requireContext(), R.color.textColorPrimary)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsHtmlBackColor.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val htmlBackColor = mjpegSettings.htmlBackColorFlow.first()
                MaterialDialog(requireActivity()).show {
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_html_back_color_title)
                    icon(R.drawable.mjpeg_ic_settings_html_back_color_24dp)
                    colorChooser(
                        colors = ColorPalette.Primary + Color.parseColor("#000000"),
                        initialSelection = htmlBackColor,
                        allowCustomArgb = true
                    ) { _, color ->
                        if (htmlBackColor != color)
                            viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setHtmlBackColor(color) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }

        // Image - VR mode
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsVrMode.isChecked = isVRModeEnabled()
        }
        binding.cbFragmentSettingsVrMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (isVRModeEnabled()) {
                    mjpegSettings.setVrMode(MjpegSettings.Default.VR_MODE_DISABLE)
                } else {
                    binding.cbFragmentSettingsVrMode.isChecked = false
                    MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                        adjustPeekHeight()
                        lifecycleOwner(viewLifecycleOwner)
                        title(R.string.mjpeg_pref_vr_mode)
                        icon(R.drawable.mjpeg_ic_settings_vr_mode_24dp)
                        listItemsSingleChoice(R.array.mjpeg_pref_vr_mode_options) { _, index, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsCropImage.isChecked = mjpegSettings.imageCropFlow.first()
        }
        binding.cbFragmentSettingsCropImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
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
                        .title(R.string.mjpeg_pref_crop)
                        .icon(R.drawable.mjpeg_ic_settings_crop_24dp)
                        .customView(R.layout.dialog_mjpeg_settings_crop, scrollable = true)
                        .positiveButton(android.R.string.ok) { dialog ->
                            DialogMjpegSettingsCropBinding.bind(dialog.getCustomView()).apply {
                                viewLifecycleOwner.lifecycleScope.launch {
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
                            DialogMjpegSettingsCropBinding.bind(getCustomView()).apply {
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
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsGrayscaleImage.isChecked = mjpegSettings.imageGrayscaleFlow.first()
        }
        binding.cbFragmentSettingsGrayscaleImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setImageGrayscale(binding.cbFragmentSettingsGrayscaleImage.isChecked)
            }
        }
        binding.clFragmentSettingsGrayscaleImage.setOnClickListener { binding.cbFragmentSettingsGrayscaleImage.performClick() }

        // Image - Resize factor
        mjpegSettings.resizeFactorFlow
            .onEach { binding.tvFragmentSettingsResizeImageValue.text = getString(R.string.mjpeg_pref_resize_value, it) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsResizeImage.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val resizeFactor = mjpegSettings.resizeFactorFlow.first()
                val resizePictureSizeString = getString(R.string.mjpeg_pref_resize_dialog_result)
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT))
                    .adjustPeekHeight()
                    .lifecycleOwner(viewLifecycleOwner)
                    .title(R.string.mjpeg_pref_resize)
                    .icon(R.drawable.mjpeg_ic_settings_resize_24dp)
                    .customView(R.layout.dialog_mjpeg_settings_resize, scrollable = true)
                    .positiveButton(android.R.string.ok) { dialog ->
                        DialogMjpegSettingsResizeBinding.bind(dialog.getCustomView()).apply {
                            val newResizeFactor = tietDialogSettingsResize.text.toString().toInt()
                            if (newResizeFactor != resizeFactor)
                                viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setResizeFactor(newResizeFactor) }
                        }
                    }
                    .negativeButton(android.R.string.cancel)
                    .apply Dialog@{
                        DialogMjpegSettingsResizeBinding.bind(getCustomView()).apply {
                            tvDialogSettingsResizeContent.text =
                                getString(R.string.mjpeg_pref_resize_dialog_text, screenBounds.width(), screenBounds.height())

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
                                runCatching { setSelection(resizeFactor.toString().length) }
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
        mjpegSettings.rotationFlow
            .onEach { binding.tvFragmentSettingsRotationValue.text = getString(R.string.mjpeg_pref_rotate_value, it) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsRotation.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val rotation = mjpegSettings.rotationFlow.first()
                val indexOld = rotationList.first { it.second == rotation }.first
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_rotate)
                    icon(R.drawable.mjpeg_ic_settings_rotation_24dp)
                    listItemsSingleChoice(R.array.mjpeg_pref_rotate_options, initialSelection = indexOld) { _, index, _ ->
                        val newRotation = rotationList.firstOrNull { item -> item.first == index }?.second
                            ?: throw IllegalArgumentException("Unknown rotation index")
                        viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setRotation(newRotation) }
                    }
                    positiveButton(android.R.string.ok)
                    negativeButton(android.R.string.cancel)
                }
            }
        }

        // Image - Max FPS
        mjpegSettings.maxFPSFlow
            .onEach { binding.tvFragmentSettingsFpsValue.text = it.toString() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsFps.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val maxFPS = mjpegSettings.maxFPSFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_fps)
                    icon(R.drawable.mjpeg_ic_settings_fps_24dp)
                    message(R.string.mjpeg_pref_fps_dialog)
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
                            viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setMaxFPS(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Image - Jpeg Quality
        mjpegSettings.jpegQualityFlow
            .onEach { binding.tvFragmentSettingsJpegQualityValue.text = it.toString() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsJpegQuality.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val jpegQuality = mjpegSettings.jpegQualityFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_jpeg_quality)
                    icon(R.drawable.mjpeg_ic_settings_high_quality_24dp)
                    message(R.string.mjpeg_pref_jpeg_quality_dialog)
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
                            viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setJpegQuality(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Security - Enable pin
        viewLifecycleOwner.lifecycleScope.launch {
            with(binding.cbFragmentSettingsEnablePin) {
                isChecked = mjpegSettings.enablePinFlow.first()
                binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
                binding.clFragmentSettingsBlockAddress.enableDisableViewWithChildren(isChecked)
                setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        mjpegSettings.setEnablePin(isChecked)
                        binding.clFragmentSettingsHidePinOnStart.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsNewPinOnAppStart.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsAutoChangePin.enableDisableViewWithChildren(isChecked)
                        binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
                        binding.clFragmentSettingsBlockAddress.enableDisableViewWithChildren(isChecked)
                    }
                }
                binding.clFragmentSettingsEnablePin.setOnClickListener { performClick() }
            }
        }

        // Security - Hide pin on start
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsHidePinOnStart.isChecked = mjpegSettings.hidePinOnStartFlow.first()
        }
        binding.cbFragmentSettingsHidePinOnStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setHidePinOnStart(binding.cbFragmentSettingsHidePinOnStart.isChecked)
            }
        }
        binding.clFragmentSettingsHidePinOnStart.setOnClickListener { binding.cbFragmentSettingsHidePinOnStart.performClick() }

        // Security - New pin on app start
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsNewPinOnAppStart.isChecked = mjpegSettings.newPinOnAppStartFlow.first()
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
        }
        binding.cbFragmentSettingsNewPinOnAppStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setNewPinOnAppStart(binding.cbFragmentSettingsNewPinOnAppStart.isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
        }
        binding.clFragmentSettingsNewPinOnAppStart.setOnClickListener { binding.cbFragmentSettingsNewPinOnAppStart.performClick() }

        // Security - Auto change pin
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsAutoChangePin.isChecked = mjpegSettings.autoChangePinFlow.first()
            binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
        }
        binding.cbFragmentSettingsAutoChangePin.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setAutoChangePin(binding.cbFragmentSettingsAutoChangePin.isChecked)
                binding.clFragmentSettingsSetPin.enableDisableViewWithChildren(canEnableSetPin())
            }
        }
        binding.clFragmentSettingsAutoChangePin.setOnClickListener { binding.cbFragmentSettingsAutoChangePin.performClick() }

        // Security - Set pin
        mjpegStreamingModule.mjpegStateFlow.filterNotNull().onEach { state ->
            binding.tvFragmentSettingsSetPinValue.text =
                if (state.isStreaming && mjpegSettings.hidePinOnStartFlow.first()) "*" else mjpegSettings.pinFlow.first()
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsSetPin.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val pin = mjpegSettings.pinFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_set_pin)
                    icon(R.drawable.mjpeg_ic_settings_key_24dp)
                    message(R.string.mjpeg_pref_set_pin_dialog)
                    input(
                        prefill = pin,
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 6,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length == 6 && text.toString().toInt() in 0..999999
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString() ?: pin
                        if (pin != newValue)
                            viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setPin(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }

        // Security - Block address
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsBlockAddress.isChecked = mjpegSettings.blockAddressFlow.first()
        }
        binding.cbFragmentSettingsBlockAddress.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setBlockAddress(binding.cbFragmentSettingsBlockAddress.isChecked)
            }
        }
        binding.clFragmentSettingsBlockAddress.setOnClickListener { binding.cbFragmentSettingsBlockAddress.performClick() }

        // Advanced - Use WiFi Only
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsUseWifiOnly.isChecked = mjpegSettings.useWiFiOnlyFlow.first()
            val enableLocalHost = mjpegSettings.enableLocalHostFlow.first()
            val localHostOnly = mjpegSettings.localHostOnlyFlow.first()
            binding.cbFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
        }
        binding.cbFragmentSettingsUseWifiOnly.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setUseWiFiOnly(binding.cbFragmentSettingsUseWifiOnly.isChecked)
            }
        }
        binding.clFragmentSettingsUseWifiOnly.setOnClickListener { binding.cbFragmentSettingsUseWifiOnly.performClick() }


        // Advanced - Enable IPv6 support
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsEnableIpv6.isChecked = mjpegSettings.enableIPv6Flow.first()
        }
        binding.cbFragmentSettingsEnableIpv6.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                mjpegSettings.setEnableIPv6(binding.cbFragmentSettingsEnableIpv6.isChecked)
            }
        }
        binding.clFragmentSettingsEnableIpv6.setOnClickListener { binding.cbFragmentSettingsEnableIpv6.performClick() }

        // Advanced - Enable Local host
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsEnableLocalhost.isChecked = mjpegSettings.enableLocalHostFlow.first()
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(binding.cbFragmentSettingsEnableLocalhost.isChecked)
        }
        binding.cbFragmentSettingsEnableLocalhost.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val enableLocalHost = binding.cbFragmentSettingsEnableLocalhost.isChecked
                mjpegSettings.setEnableLocalHost(enableLocalHost)
                val localHostOnly = mjpegSettings.localHostOnlyFlow.first()
                binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(enableLocalHost)
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
            }
        }
        binding.clFragmentSettingsEnableLocalhost.setOnClickListener { binding.cbFragmentSettingsEnableLocalhost.performClick() }

        // Advanced - Local host only
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsLocalhostOnly.isChecked = mjpegSettings.localHostOnlyFlow.first()
            binding.clFragmentSettingsLocalhostOnly.enableDisableViewWithChildren(mjpegSettings.enableLocalHostFlow.first())
        }
        binding.cbFragmentSettingsLocalhostOnly.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val localHostOnly = binding.cbFragmentSettingsLocalhostOnly.isChecked
                mjpegSettings.setLocalHostOnly(localHostOnly)
                val enableLocalHost = mjpegSettings.enableLocalHostFlow.first()
                binding.clFragmentSettingsUseWifiOnly.enableDisableViewWithChildren((enableLocalHost && localHostOnly).not())
            }
        }
        binding.clFragmentSettingsLocalhostOnly.setOnClickListener { binding.cbFragmentSettingsLocalhostOnly.performClick() }

        // Advanced - Server port
        mjpegSettings.serverPortFlow
            .onEach { binding.tvFragmentSettingsServerPortValue.text = it.toString() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.clFragmentSettingsServerPort.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val serverPort = mjpegSettings.serverPortFlow.first()
                MaterialDialog(requireActivity(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    adjustPeekHeight()
                    lifecycleOwner(viewLifecycleOwner)
                    title(R.string.mjpeg_pref_server_port)
                    icon(R.drawable.mjpeg_ic_settings_http_24dp)
                    message(R.string.mjpeg_pref_server_port_dialog)
                    input(
                        prefill = serverPort.toString(),
                        inputType = InputType.TYPE_CLASS_NUMBER,
                        maxLength = 5,
                        waitForPositiveButton = false
                    ) { dialog, text ->
                        val isValid = text.length in 4..5 && (text.toString().toIntOrNull() ?: -1) in 1025..65535
                        dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                    }
                    positiveButton(android.R.string.ok) { dialog ->
                        val newValue = dialog.getInputField().text?.toString()?.toIntOrNull() ?: serverPort
                        if (serverPort != newValue)
                            viewLifecycleOwner.lifecycleScope.launch { mjpegSettings.setServerPort(newValue) }
                    }
                    negativeButton(android.R.string.cancel)
                    getInputField().filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
                    getInputField().imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
        }
    }

    override fun onDestroyView() {
        XLog.d(getLog("onDestroyView"))
        super.onDestroyView()
    }

    private fun MaterialDialog.adjustPeekHeight(): MaterialDialog {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(requireActivity())
        val heightDp = metrics.bounds.height() / resources.displayMetrics.density
        if (heightDp < 480f) setPeekHeight(metrics.bounds.height())
        return this
    }

    private suspend fun isVRModeEnabled(): Boolean =
        mjpegStreamingModule.mjpegSettings.vrModeFlow.first() in arrayOf(MjpegSettings.Default.VR_MODE_RIGHT, MjpegSettings.Default.VR_MODE_LEFT)

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
            errorView.text = getString(R.string.mjpeg_pref_crop_dialog_warning_message)
        } else {
            dialog.setActionButtonEnabled(WhichButton.POSITIVE, false)
            errorView.text = getString(R.string.mjpeg_pref_crop_dialog_error_message)
        }
    }

    private class SimpleTextWatcher(private val afterTextChangedBlock: (s: Editable) -> Unit) : TextWatcher {
        override fun afterTextChanged(s: Editable?) = s?.let { afterTextChangedBlock(it) } as Unit
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
    }

    private fun canEnableSetPin(): Boolean =
        binding.cbFragmentSettingsEnablePin.isChecked &&
                binding.cbFragmentSettingsNewPinOnAppStart.isChecked.not() &&
                binding.cbFragmentSettingsAutoChangePin.isChecked.not()

    private fun View.enableDisableViewWithChildren(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else .5f
        if (this is ViewGroup)
            for (idx in 0 until childCount) getChildAt(idx).enableDisableViewWithChildren(enabled)
    }
}