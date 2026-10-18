package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingDropdownMenu
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchOptionText
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object JpegBackendSearch {
    val options = mapOf(
        JpegBackendPolicy.Auto to SettingSearchOptionText(
            R.string.mjpeg_pref_value_automatic,
            R.string.mjpeg_pref_image_encoder_auto_description
        ),
        JpegBackendPolicy.FrameworkOnly to SettingSearchOptionText(
            R.string.mjpeg_pref_image_encoder_system,
            R.string.mjpeg_pref_image_encoder_system_description
        ),
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_encoder,
        summaryRes = R.string.mjpeg_pref_image_encoder_summary,
        extraTermResIds = options.values.flatMap { listOf(it.labelRes, it.descriptionRes) },
    )
}

@Composable
internal fun JpegBackendSettingItem(
    backendPolicy: JpegBackendPolicy,
    canEditStreamSetup: Boolean,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    var menuExpanded by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor || menuExpanded)
    SettingsItem(
        title = stringResource(JpegBackendSearch.info.titleRes),
        summary = stringResource(JpegBackendSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_developer_board,
        onClick = { if (canEditStreamSetup) menuExpanded = true else showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Box {
                Text(
                    stringResource(JpegBackendSearch.options.getValue(backendPolicy).labelRes),
                    Modifier.widthIn(max = 128.dp),
                    color = if (canEditStreamSetup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingDropdownMenu(
                    expanded = menuExpanded,
                    selectedValue = backendPolicy,
                    options = JpegBackendPolicy.entries,
                    optionLabel = { option ->
                        val label = stringResource(JpegBackendSearch.options.getValue(option).labelRes)
                        if (option == JpegBackendPolicy.Auto) stringResource(R.string.mjpeg_pref_default_option, label) else label
                    },
                    enabled = canEditStreamSetup,
                    optionSupportingText = { option ->
                        val descriptionRes = if (!canEditStreamSetup && backendPolicy == option) {
                            R.string.mjpeg_pref_image_encoder_locked
                        } else JpegBackendSearch.options.getValue(option).descriptionRes
                        Text(stringResource(descriptionRes))
                    },
                    onValueSelected = { selected -> if (selected != backendPolicy) onImageChange { copy(jpegBackendPolicy = selected) } },
                    onDismissRequest = {
                        menuExpanded = false
                        openerFocus.markDismissed()
                    },
                )
            }
        },
    )
    if (showEditor) {
        SettingEditor(
            title = stringResource(JpegBackendSearch.info.titleRes),
            description = stringResource(JpegBackendSearch.info.summaryRes),
            onDismiss = {
                showEditor = false
                openerFocus.markDismissed()
            },
        ) {
            Column(Modifier.selectableGroup()) {
                for (option in JpegBackendPolicy.entries) {
                    val optionText = JpegBackendSearch.options.getValue(option)
                    ListItem(
                        selected = backendPolicy == option,
                        onClick = {},
                        enabled = false,
                        verticalAlignment = Alignment.CenterVertically,
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                        supportingContent = { Text(stringResource(optionText.descriptionRes), style = MaterialTheme.typography.bodySmall) },
                        content = {
                            val label = stringResource(optionText.labelRes)
                            Text(if (option == JpegBackendPolicy.Auto) stringResource(R.string.mjpeg_pref_default_option, label) else label)
                        },
                    )
                }
            }
            Text(stringResource(R.string.mjpeg_pref_image_encoder_locked), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
