package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.LocalMjpegSnackbarHostState
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSliderThumb
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import kotlin.math.roundToInt

internal object JpegQualitySearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_quality,
        summaryRes = R.string.mjpeg_pref_image_quality_summary,
    )
}

@Composable
internal fun JpegQualitySettingItem(
    jpegQuality: Int,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    val numberFormat = rememberSettingNumberFormat()
    var showEditor by retain { mutableStateOf(false) }
    var initialQuality by retain { mutableIntStateOf(jpegQuality) }
    var draftQuality by retain { mutableIntStateOf(jpegQuality) }
    val sliderState = remember { SliderState(draftQuality.toFloat(), steps = 99, trackRange = 0f..100f) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(JpegQualitySearch.info.titleRes),
        summary = stringResource(JpegQualitySearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_high_quality,
        onClick = {
            initialQuality = jpegQuality
            draftQuality = jpegQuality
            sliderState.value = jpegQuality.toFloat()
            showEditor = true
        },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Box {
                Text(
                    numberFormat.format(jpegQuality),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropdownMenu(
                    expanded = showEditor,
                    onDismissRequest = {
                        if (!showEditor) return@DropdownMenu
                        val quality = draftQuality
                        if (quality != initialQuality) onImageChange { copy(jpegQuality = quality) }
                        showEditor = false
                        openerFocus.markDismissed()
                    },
                    modifier = Modifier.width(320.dp),
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    val interactions = remember { MutableInteractionSource() }
                    val focused by interactions.collectIsFocusedAsState()
                    val sliderFocusRequester = remember { FocusRequester() }
                    val qualityLabel = stringResource(JpegQualitySearch.info.titleRes)
                    LaunchedEffect(Unit) { sliderFocusRequester.requestFocus() }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.mjpeg_pref_image_quality_popup_description, numberFormat.format(80)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            state = sliderState,
                            interactionSource = interactions,
                            thumb = {
                                SettingSliderThumb(
                                    valueText = numberFormat.format(sliderState.value.roundToInt()),
                                    interactionSource = interactions,
                                    isPersistent = focused,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            track = { SliderDefaults.Track(sliderState = it, drawTick = { _, _ -> }) },
                            onValueChange = { value ->
                                draftQuality = value.roundToInt().coerceIn(0, 100)
                                sliderState.value = draftQuality.toFloat()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp)
                                .systemGestureExclusion()
                                .focusRequester(sliderFocusRequester)
                                .semantics { contentDescription = qualityLabel },
                        )
                        SnackbarHost(LocalMjpegSnackbarHostState.current)
                    }
                }
            }
        },
    )
}
