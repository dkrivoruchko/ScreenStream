package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.VideoCapture
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow

private val CODECS = listOf(
    MjpegSettings.Values.VIDEO_CODEC_MJPEG,
    MjpegSettings.Values.VIDEO_CODEC_H264,
    MjpegSettings.Values.VIDEO_CODEC_H265,
    MjpegSettings.Values.VIDEO_CODEC_AV1
)

internal val VIDEO_BITRATES_KBPS: List<Int> = listOf(2_000, 4_000, 8_000, 12_000, 20_000, 40_000)

@Composable
internal fun VideoCodecRow(videoCodec: Int, onDetailShow: () -> Unit) {
    val options = stringArrayResource(R.array.mjpeg_pref_video_codec_options)
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.burst_mode_24px,
        title = stringResource(R.string.mjpeg_pref_video_codec),
        summary = stringResource(R.string.mjpeg_pref_video_codec_summary),
        valueText = options.getOrElse(CODECS.indexOf(videoCodec).coerceAtLeast(0)) { options[0] },
        onClick = onDetailShow
    )
}

@Composable
internal fun VideoCodecEditor(videoCodec: Int, onValueChange: (Int) -> Unit) {
    val names = stringArrayResource(R.array.mjpeg_pref_video_codec_options)
    val unsupported = stringResource(R.string.mjpeg_pref_video_codec_unsupported)
    val supported = remember { CODECS.map { it == MjpegSettings.Values.VIDEO_CODEC_MJPEG || VideoCapture.isSupported(it) } }
    SelectionEditor(
        options = CODECS.indices.map { i -> if (supported[i]) names[i] else "${names[i]} $unsupported" },
        selectedIndex = CODECS.indexOf(videoCodec).coerceAtLeast(0),
        onValueChange = { index -> if (supported[index]) onValueChange(CODECS[index]) },
        description = stringResource(R.string.mjpeg_pref_video_codec_description)
    )
}

@Composable
internal fun VideoBitrateRow(videoBitrateKbps: Int, enabled: Boolean, onDetailShow: () -> Unit) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.burst_mode_24px,
        title = stringResource(R.string.mjpeg_pref_video_bitrate),
        summary = stringResource(R.string.mjpeg_pref_video_bitrate_summary),
        valueText = "${videoBitrateKbps / 1000} Mbps",
        onClick = onDetailShow
    )
}

@Composable
internal fun VideoBitrateEditor(videoBitrateKbps: Int, onValueChange: (Int) -> Unit) {
    SelectionEditor(
        options = VIDEO_BITRATES_KBPS.map { "${it / 1000} Mbps" },
        selectedIndex = VIDEO_BITRATES_KBPS.indexOf(videoBitrateKbps).coerceAtLeast(0),
        onValueChange = { index -> onValueChange(VIDEO_BITRATES_KBPS[index]) }
    )
}
