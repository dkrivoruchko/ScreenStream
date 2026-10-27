package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import io.screenstream.streaming.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val imageSettingSearchEntries: List<SettingSearchInfo> = listOf(
    CaptureAreaSearch.info,
    CropSearch.info,
    SizeSearch.info,
    RotationSearch.info,
    MirrorSearch.info,
    GrayscaleSearch.info,
    JpegQualitySearch.info,
    FrameRateSearch.info,
    JpegBackendSearch.info,
)

@Composable
internal fun ImageSettingsGroup(
    imageSettings: ImageSettings,
    mjpegSettings: MjpegSettings,
    editPolicy: MjpegSettings.EditPolicy,
    onSaveError: (String) -> Unit,
    visibleSettings: Set<SettingSearchInfo>?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val saveErrorText = stringResource(R.string.mjpeg_pref_image_save_error)
    val currentEditPolicy by rememberUpdatedState(editPolicy)
    val coroutineScope = rememberCoroutineScope()

    fun updateImage(transform: ImageSettings.() -> ImageSettings) {
        // Enter the save transaction before this group can leave composition.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mjpegSettings.updateData {
                    val proposed = copy(image = image.transform())
                    // The UI policy is advisory; the controller independently freezes running setup.
                    if (currentEditPolicy.allows(this, proposed)) proposed else this
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mjpegSettings.logE("Image.save", "Could not save image settings", failure)
                onSaveError(saveErrorText)
            }
        }
    }

    SettingsGroupCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        searching = visibleSettings != null,
        group = SettingSearchGroup.Image,
        iconRes = R.drawable.mjpeg_icon_image,
        modifier = Modifier.fillMaxWidth(),
    ) {
        imageSettingSearchEntries
            .filter { visibleSettings == null || it in visibleSettings }
            .forEachIndexed { index, searchInfo ->
                key(searchInfo) {
                    if (index > 0) HorizontalDivider()
                    when (searchInfo) {
                        CaptureAreaSearch.info -> CaptureAreaSettingItem(imageSettings.sourceRegion, ::updateImage)
                        CropSearch.info -> CropSettingItem(imageSettings.cropEnabled, imageSettings.cropInsets, ::updateImage)
                        SizeSearch.info -> SizeSettingItem(imageSettings, ::updateImage)
                        RotationSearch.info -> RotationSettingItem(imageSettings.rotation, ::updateImage)
                        MirrorSearch.info -> MirrorSettingItem(imageSettings.mirror, ::updateImage)
                        GrayscaleSearch.info -> GrayscaleSettingItem(imageSettings.colorMode, ::updateImage)
                        JpegQualitySearch.info -> JpegQualitySettingItem(imageSettings.jpegQuality, ::updateImage)
                        FrameRateSearch.info -> FrameRateSettingItem(imageSettings.frameRate, ::updateImage)
                        JpegBackendSearch.info -> JpegBackendSettingItem(imageSettings.jpegBackendPolicy, editPolicy.canEditStreamSetup, ::updateImage)
                        else -> Unit
                    }
                }
            }
    }
}
