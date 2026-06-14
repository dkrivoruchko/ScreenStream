package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.mp4.Mp4VideoEncoderInfo
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow
import kotlin.math.roundToInt

@Composable
internal fun VideoEncoderRow(
    autoSelect: Boolean,
    selectedEncoder: Mp4VideoEncoderInfo?,
    enabled: Boolean,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.video_settings_24px,
        title = stringResource(R.string.mjpeg_video_encoder),
        summary = selectedEncoder?.let { "[${it.name}]" } ?: stringResource(R.string.mjpeg_video_encoder_summary),
        valueText = if (autoSelect) stringResource(R.string.mjpeg_video_encoder_auto) else selectedEncoder?.vendorName,
        onClick = onDetailShow
    )
}

@Composable
internal fun VideoEncoderEditor(
    autoSelect: Boolean,
    selectedEncoderName: String,
    availableEncoders: List<Mp4VideoEncoderInfo>,
    onAutoSelect: () -> Unit,
    onEncoderSelected: (String) -> Unit
) {
    val selectedIndex = if (autoSelect) {
        0
    } else {
        availableEncoders.indexOfFirst { it.name == selectedEncoderName }.takeIf { it >= 0 }?.plus(1) ?: 0
    }

    SelectionEditor(
        options = listOf(stringResource(R.string.mjpeg_video_encoder_auto)) + availableEncoders.map { encoder ->
            "${encoder.vendorName} H.264 [${encoder.name}]"
        },
        selectedIndex = selectedIndex,
        description = stringResource(R.string.mjpeg_video_encoder_summary),
        onValueChange = { index ->
            if (index == 0) onAutoSelect()
            else availableEncoders.getOrNull(index - 1)?.let { onEncoderSelected(it.name) }
        }
    )
}

@Composable
internal fun VideoBitrateRow(
    bitrateBits: Int,
    enabled: Boolean,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.high_quality_24px,
        title = stringResource(R.string.mjpeg_video_bitrate_title),
        summary = stringResource(R.string.mjpeg_video_bitrate_summary),
        valueText = (bitrateBits / 1000).toKOrMBitString(),
        onClick = onDetailShow
    )
}

@Composable
internal fun VideoBitrateEditor(
    bitrateRangeKbits: ClosedRange<Int>,
    bitrateBits: Int,
    onValueChange: (Int) -> Unit
) {
    SettingEditorLayout {
        var isDragging by rememberSaveable { mutableStateOf(false) }
        var sliderPosition by rememberSaveable { mutableFloatStateOf((bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()) }

        LaunchedEffect(bitrateBits, bitrateRangeKbits) {
            if (!isDragging) {
                sliderPosition = (bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()
            }
        }

        Text(
            text = stringResource(R.string.mjpeg_video_bitrate, sliderPosition.roundToInt().toKOrMBitString()),
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = bitrateRangeKbits.start.toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Slider(
                value = sliderPosition,
                onValueChange = {
                    isDragging = true
                    sliderPosition = it
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                valueRange = bitrateRangeKbits.start.toFloat()..bitrateRangeKbits.endInclusive.toFloat(),
                onValueChangeFinished = {
                    isDragging = false
                    onValueChange(sliderPosition.roundToInt() * 1000)
                }
            )
            Text(
                text = bitrateRangeKbits.endInclusive.toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun Int.toKOrMBitString(): String =
    if (this >= 1000) stringResource(R.string.mjpeg_video_bitrate_mbit, this / 1000f)
    else stringResource(R.string.mjpeg_video_bitrate_kbit, this)
