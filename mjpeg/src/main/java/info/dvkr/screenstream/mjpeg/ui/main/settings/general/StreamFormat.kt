package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow

@Composable
internal fun StreamFormatRow(
    streamFormat: Int,
    onDetailShow: () -> Unit
) {
    val options = streamFormatOptions()
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.video_settings_24px,
        title = stringResource(R.string.mjpeg_pref_stream_format),
        summary = stringResource(R.string.mjpeg_pref_stream_format_summary),
        valueText = options[streamFormat.toOptionIndex()],
        onClick = onDetailShow
    )
}

@Composable
internal fun StreamFormatEditor(
    streamFormat: Int,
    onValueChange: (Int) -> Unit
) {
    val options = streamFormatOptions()
    SelectionEditor(
        options = options,
        selectedIndex = streamFormat.toOptionIndex(),
        onValueChange = { index -> onValueChange(index.toStreamFormat()) },
        description = stringResource(R.string.mjpeg_pref_stream_format_text)
    )
}

@Composable
private fun streamFormatOptions(): List<String> = listOf(
    stringResource(R.string.mjpeg_pref_stream_format_mjpeg),
    stringResource(R.string.mjpeg_pref_stream_format_mp4)
)

private fun Int.toOptionIndex(): Int = when (this) {
    MjpegSettings.Values.STREAM_FORMAT_MP4 -> 1
    else -> 0
}

private fun Int.toStreamFormat(): Int = when (this) {
    1 -> MjpegSettings.Values.STREAM_FORMAT_MP4
    else -> MjpegSettings.Values.STREAM_FORMAT_MJPEG
}
