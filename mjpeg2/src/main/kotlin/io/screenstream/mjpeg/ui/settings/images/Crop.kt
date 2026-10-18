package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.screenstream.capture.CropInsetsPx
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingNumberField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object CropSearch {
    val enabledLabelRes = R.string.mjpeg_pref_image_crop_enabled
    val purposeRes = R.string.mjpeg_pref_image_crop_purpose
    val leftLabelRes = R.string.mjpeg_pref_image_crop_left
    val rightLabelRes = R.string.mjpeg_pref_image_crop_right
    val topLabelRes = R.string.mjpeg_pref_image_crop_top
    val bottomLabelRes = R.string.mjpeg_pref_image_crop_bottom

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_crop,
        summaryRes = R.string.mjpeg_pref_image_crop_summary,
        extraTermResIds = listOf(enabledLabelRes, purposeRes, leftLabelRes, rightLabelRes, topLabelRes, bottomLabelRes),
    )
}

@Composable
internal fun CropSettingItem(
    cropEnabled: Boolean,
    cropInsets: CropInsetsPx,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(CropSearch.info.titleRes),
        summary = stringResource(CropSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_crop,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            val switchInteractions = remember { MutableInteractionSource() }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.mjpeg_icon_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                VerticalDivider(Modifier.height(32.dp))
                val enabledLabel = stringResource(CropSearch.enabledLabelRes)
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .toggleable(
                            value = cropEnabled,
                            role = Role.Switch,
                            interactionSource = switchInteractions,
                            indication = null,
                            onValueChange = { onImageChange { copy(cropEnabled = !this.cropEnabled) } },
                        )
                        .semantics { contentDescription = enabledLabel },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SettingSwitch(
                        checked = cropEnabled,
                        interactionSource = switchInteractions,
                        compact = true,
                    )
                }
            }
        },
    )
    if (showEditor) {
        CropEditor(cropInsets, onImageChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun CropEditor(
    cropInsets: CropInsetsPx,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val initialCropInsets = retain { cropInsets }
    // Preserve in-progress edits across sheet/dialog changes and settings updates.
    var leftInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialCropInsets.left))) }
    var topInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialCropInsets.top))) }
    var rightInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialCropInsets.right))) }
    var bottomInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialCropInsets.bottom))) }

    fun cropErrorRes(text: String): Int? {
        val value = parseSettingInteger(text, numberFormat)
        return when {
            value != null && value >= 0 -> null
            text.isBlank() -> R.string.mjpeg_pref_image_crop_required
            else -> R.string.mjpeg_pref_image_crop_error
        }
    }

    fun saveCropIfChanged() {
        val leftPixels = parseSettingInteger(leftInput.text, numberFormat)?.takeIf { it >= 0 } ?: return
        val topPixels = parseSettingInteger(topInput.text, numberFormat)?.takeIf { it >= 0 } ?: return
        val rightPixels = parseSettingInteger(rightInput.text, numberFormat)?.takeIf { it >= 0 } ?: return
        val bottomPixels = parseSettingInteger(bottomInput.text, numberFormat)?.takeIf { it >= 0 } ?: return
        val crop = CropInsetsPx(leftPixels, topPixels, rightPixels, bottomPixels)
        if (crop != initialCropInsets) onImageChange { copy(cropInsets = crop) }
    }

    SettingEditor(
        title = stringResource(CropSearch.info.titleRes),
        description = stringResource(CropSearch.info.summaryRes),
        onDismiss = {
            saveCropIfChanged()
            onDismiss()
        },
    ) {
        Text(stringResource(CropSearch.purposeRes), style = MaterialTheme.typography.bodyMedium)
        SettingInfo(stringResource(R.string.mjpeg_pref_image_crop_empty_warning))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingNumberField(
                value = leftInput,
                inputLocale = inputLocale,
                label = stringResource(CropSearch.leftLabelRes),
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
                errorText = cropErrorRes(leftInput.text)?.let { stringResource(it) },
                onValueChange = { leftInput = it },
            )
            SettingNumberField(
                value = rightInput,
                inputLocale = inputLocale,
                label = stringResource(CropSearch.rightLabelRes),
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
                errorText = cropErrorRes(rightInput.text)?.let { stringResource(it) },
                onValueChange = { rightInput = it },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingNumberField(
                value = topInput,
                inputLocale = inputLocale,
                label = stringResource(CropSearch.topLabelRes),
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
                errorText = cropErrorRes(topInput.text)?.let { stringResource(it) },
                onValueChange = { topInput = it },
            )
            SettingNumberField(
                value = bottomInput,
                inputLocale = inputLocale,
                label = stringResource(CropSearch.bottomLabelRes),
                modifier = Modifier.weight(1f),
                errorText = cropErrorRes(bottomInput.text)?.let { stringResource(it) },
                onValueChange = { bottomInput = it },
            )
        }
    }
}
