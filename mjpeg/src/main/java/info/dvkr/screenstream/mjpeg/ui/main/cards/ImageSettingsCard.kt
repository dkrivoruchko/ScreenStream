package info.dvkr.screenstream.mjpeg.ui.main.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.MjpegSettingModal
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.CropImageEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.CropImageRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.FlipEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.FlipRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.GrayscaleRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.JpegQualityEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.JpegQualityRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.MaxFpsEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.MaxFpsRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.ResizeImageEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.ResizeImageRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.RotationEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.RotationRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.VrModeEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.image.VrModeRow

@Composable
internal fun ImageSettingsCard(
    settings: MjpegSettings.Data,
    updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    var selectedSheet by rememberSaveable { mutableStateOf<ImageSettingSheet?>(null) }
    val expanded = rememberSaveable { mutableStateOf(false) }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.mjpeg_pref_settings_image),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        VrModeRow(
            vrMode = settings.vrMode,
            onDetailShow = { selectedSheet = ImageSettingSheet.VrMode },
            onValueChange = { value -> updateSettings { copy(vrMode = value) } }
        )
        HorizontalDivider()

        CropImageRow(
            imageCrop = settings.imageCrop,
            onDetailShow = { selectedSheet = ImageSettingSheet.Crop },
            onValueChange = { value -> updateSettings { copy(imageCrop = value) } }
        )
        HorizontalDivider()

        GrayscaleRow(settings.imageGrayscale) { value ->
            updateSettings { copy(imageGrayscale = value) }
        }
        HorizontalDivider()

        ResizeImageRow(
            resizeFactor = settings.resizeFactor,
            width = settings.resolutionWidth,
            height = settings.resolutionHeight,
        ) { selectedSheet = ImageSettingSheet.Resize }
        HorizontalDivider()

        RotationRow(settings.rotation) { selectedSheet = ImageSettingSheet.Rotation }
        HorizontalDivider()

        FlipRow(
            flipMode = settings.flip,
            onDetailShow = { selectedSheet = ImageSettingSheet.Flip },
            onValueChange = { value -> updateSettings { copy(flip = value) } }
        )
        HorizontalDivider()

        MaxFpsRow(settings.maxFPS) { selectedSheet = ImageSettingSheet.MaxFps }
        HorizontalDivider()

        JpegQualityRow(settings.jpegQuality) { selectedSheet = ImageSettingSheet.JpegQuality }

        selectedSheet?.let { sheet ->
            MjpegSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                when (sheet) {
                    ImageSettingSheet.VrMode -> VrModeEditor(settings.vrMode) { value ->
                        if (settings.vrMode != value) updateSettings { copy(vrMode = value) }
                    }

                    ImageSettingSheet.Crop -> CropImageEditor(
                        imageCropTop = settings.imageCropTop,
                        imageCropBottom = settings.imageCropBottom,
                        imageCropLeft = settings.imageCropLeft,
                        imageCropRight = settings.imageCropRight,
                        onNewValueTop = { value -> if (settings.imageCropTop != value) updateSettings { copy(imageCropTop = value) } },
                        onNewValueBottom = { value -> if (settings.imageCropBottom != value) updateSettings { copy(imageCropBottom = value) } },
                        onNewValueLeft = { value -> if (settings.imageCropLeft != value) updateSettings { copy(imageCropLeft = value) } },
                        onNewValueRight = { value -> if (settings.imageCropRight != value) updateSettings { copy(imageCropRight = value) } }
                    )

                    ImageSettingSheet.Resize -> ResizeImageEditor(
                        resizeFactor = settings.resizeFactor,
                        resolutionWidth = settings.resolutionWidth,
                        resolutionHeight = settings.resolutionHeight,
                        stretch = settings.resolutionStretch,
                        onNewResize = { value -> if (settings.resizeFactor != value) updateSettings { copy(resizeFactor = value) } },
                        onNewWidth = { value -> if (settings.resolutionWidth != value) updateSettings { copy(resolutionWidth = value) } },
                        onNewHeight = { value -> if (settings.resolutionHeight != value) updateSettings { copy(resolutionHeight = value) } },
                        onStretchChange = { value -> if (settings.resolutionStretch != value) updateSettings { copy(resolutionStretch = value) } }
                    )

                    ImageSettingSheet.Rotation -> RotationEditor(settings.rotation) { value ->
                        if (settings.rotation != value) updateSettings { copy(rotation = value) }
                    }

                    ImageSettingSheet.Flip -> FlipEditor(settings.flip) { value ->
                        if (settings.flip != value) updateSettings { copy(flip = value) }
                    }

                    ImageSettingSheet.MaxFps -> MaxFpsEditor(settings.maxFPS) { value ->
                        if (settings.maxFPS != value) updateSettings { copy(maxFPS = value) }
                    }

                    ImageSettingSheet.JpegQuality -> JpegQualityEditor(settings.jpegQuality) { value ->
                        if (settings.jpegQuality != value) updateSettings { copy(jpegQuality = value) }
                    }
                }
            }
        }
    }
}

private enum class ImageSettingSheet(@get:StringRes val titleRes: Int) {
    VrMode(R.string.mjpeg_pref_vr_mode),
    Crop(R.string.mjpeg_pref_crop),
    Resize(R.string.mjpeg_pref_resize),
    Rotation(R.string.mjpeg_pref_rotate),
    Flip(R.string.mjpeg_pref_flip),
    MaxFps(R.string.mjpeg_pref_fps),
    JpegQuality(R.string.mjpeg_pref_jpeg_quality)
}
