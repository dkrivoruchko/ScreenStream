package info.dvkr.screenstream.rtsp.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeIntRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowMetricsCalculator
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.EncoderUtils
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.getBitRateInKbits
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.getFrameRates
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
internal fun VideoCard(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
    rtspSettings: RtspSettings = koinInject()
) {
    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_video_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        initiallyExpanded = false
    ) {
        if (rtspState.value.selectedVideoEncoder?.capabilities?.videoCapabilities == null) return@ExpandableCard

        val videoCapabilities = remember(rtspState.value.selectedVideoEncoder) {
            rtspState.value.selectedVideoEncoder?.capabilities?.videoCapabilities!!
        }

        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()

        VideoEncoder(
            isAutoSelect = rtspSettingsState.value.videoCodecAutoSelect,
            onAutoSelectChange = { scope.launch { rtspSettings.updateData { copy(videoCodecAutoSelect = videoCodecAutoSelect.not()) } } },
            selectedEncoder = rtspState.value.selectedVideoEncoder,
            availableEncoders = EncoderUtils.availableVideoEncoders,
            onCodecSelected = { scope.launch { rtspSettings.updateData { copy(videoCodec = it) } } },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )

        val context = LocalContext.current
        val screenSize = remember(context) {
            WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds.toComposeIntRect().size
        }

        val (resizeFactor, resizedWidth, resizedHeight) = remember(
            screenSize, videoCapabilities, rtspSettingsState.value.videoResizeFactor
        ) {
            videoCapabilities.adjustResizeFactor(screenSize.width, screenSize.height, rtspSettingsState.value.videoResizeFactor / 100)
        }

        val fpsRange = remember(videoCapabilities, resizedWidth, resizedHeight) {
            videoCapabilities.getFrameRates(resizedWidth, resizedHeight)
        }

        val bitrateRangeKbits = remember(videoCapabilities, resizedWidth, resizedHeight) { videoCapabilities.getBitRateInKbits() }

        ImageSize(
            screenSize = screenSize,
            resultSize = IntSize(resizedWidth, resizedHeight),
            resizeFactor = resizeFactor * 100,
            onValueChange = { newResizeFactor ->
                scope.launch {
                    val (resizeFactor, resizedWidth, resizedHeight) =
                        videoCapabilities.adjustResizeFactor(screenSize.width, screenSize.height, newResizeFactor / 100)
                    val fpsRange = videoCapabilities.getFrameRates(resizedWidth, resizedHeight)
                    rtspSettings.updateData { copy(videoResizeFactor = resizeFactor * 100, videoFps = videoFps.coerceIn(fpsRange)) }
                }
            },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )

        Fps(
            fpsRange = fpsRange,
            fps = rtspSettingsState.value.videoFps,
            onValueChange = { scope.launch { rtspSettings.updateData { copy(videoFps = it) } } },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )

        Bitrate(
            bitrateRangeKbits = bitrateRangeKbits,
            bitrateBits = rtspSettingsState.value.videoBitrateBits,
            onValueChange = { scope.launch { rtspSettings.updateData { copy(videoBitrateBits = it) } } },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun VideoEncoder(
    isAutoSelect: Boolean,
    onAutoSelectChange: (Boolean) -> Unit,
    selectedEncoder: VideoCodecInfo?,
    availableEncoders: List<VideoCodecInfo>,
    onCodecSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .conditional(enabled) { toggleable(value = isAutoSelect, onValueChange = onAutoSelectChange) }
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.rtsp_video_encoder))
            Spacer(modifier = Modifier.weight(1f))
            Row {
                Text(text = stringResource(R.string.rtsp_video_encoder_auto), modifier = Modifier.align(Alignment.CenterVertically))
                Switch(checked = isAutoSelect, enabled = enabled, onCheckedChange = null, modifier = Modifier.scale(0.7F))
            }
        }

        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .conditional(isAutoSelect.not()) { clickable(enabled = enabled) { expanded = true } }
                .alpha(if (isAutoSelect || enabled.not()) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EncoderItem(
                codecName = "${selectedEncoder?.codec?.name} ${selectedEncoder?.vendorName}",
                encoderName = "[${selectedEncoder?.name}]",
                isHardwareAccelerated = selectedEncoder?.isHardwareAccelerated == true,
                isCBRModeSupported = selectedEncoder?.isCBRModeSupported == true
            )
            Spacer(Modifier.weight(1f))
            val iconRotation = remember { Animatable(0F) }
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                contentDescription = null,
                modifier = Modifier.graphicsLayer {
                    rotationZ = iconRotation.value
                }
            )
            LaunchedEffect(expanded) { iconRotation.animateTo(targetValue = if (expanded) 180F else 0F, animationSpec = tween(500)) }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableEncoders.forEachIndexed { index, encoder ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                if (index != 0) HorizontalDivider()
                                Row {
                                    if (selectedEncoder?.name == encoder.name) {
                                        Icon(
                                            painter = painterResource(R.drawable.check_small_24px),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(start = 16.dp)
                                                .align(Alignment.CenterVertically)
                                        )
                                    }
                                    EncoderItem(
                                        codecName = "${encoder.codec.name} ${encoder.vendorName}",
                                        encoderName = "[${encoder.name}]",
                                        isHardwareAccelerated = encoder.isHardwareAccelerated,
                                        isCBRModeSupported = encoder.isCBRModeSupported,
                                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onCodecSelected(encoder.name)
                        },
                        contentPadding = PaddingValues()
                    )
                }

            }
        }
    }
}

@Composable
private fun ImageSize(
    screenSize: IntSize,
    resultSize: IntSize,
    resizeFactor: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var counter by remember { mutableLongStateOf(0) }
        var sliderPosition by remember(resizeFactor, counter) { mutableFloatStateOf(resizeFactor) }
        var currentResultSize by remember(resultSize, counter) { mutableStateOf(resultSize) }
        val scope = rememberCoroutineScope()

        Text(text = stringResource(R.string.rtsp_video_resize_image, sliderPosition))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.rtsp_video_resize_image_10), modifier = Modifier.align(Alignment.CenterVertically))
            Slider(
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    currentResultSize = IntSize((screenSize.width * it / 100F).roundToInt(), (screenSize.height * it / 100F).roundToInt())
                },
                enabled = enabled,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                valueRange = 10F..100F,
                onValueChangeFinished = { onValueChange.invoke(sliderPosition); scope.launch { delay(250); counter = counter + 1 } }
            )
            Text(text = stringResource(R.string.rtsp_video_resize_image_100), modifier = Modifier.align(Alignment.CenterVertically))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(top = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.rtsp_video_screen_size, screenSize.width, screenSize.height),
                style = MaterialTheme.typography.bodySmall
            )

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxHeight()
            )

            Text(
                text = stringResource(R.string.rtsp_video_video_size, currentResultSize.width, currentResultSize.height),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Fps(
    fpsRange: ClosedRange<Int>,
    fps: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var sliderPosition by remember(fpsRange, fps) { mutableFloatStateOf(fps.coerceIn(fpsRange).toFloat()) }

        Text(text = stringResource(R.string.rtsp_video_frame_rate, sliderPosition.roundToInt()))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = fpsRange.start.toString(), modifier = Modifier.align(Alignment.CenterVertically))
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                enabled = enabled,
                valueRange = fpsRange.start.toFloat()..fpsRange.endInclusive.toFloat(),
                onValueChangeFinished = { onValueChange.invoke(sliderPosition.roundToInt()) }
            )
            Text(text = fpsRange.endInclusive.toString(), modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun Bitrate(
    bitrateRangeKbits: ClosedRange<Int>,
    bitrateBits: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var isDragging by remember { mutableStateOf(false) }
        var sliderPosition by remember { mutableFloatStateOf((bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()) }

        LaunchedEffect(bitrateBits, bitrateRangeKbits) {
            if (!isDragging) {
                sliderPosition = (bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()
            }
        }

        Text(
            text = stringResource(R.string.rtsp_video_bitrate, sliderPosition.roundToInt().toKOrMBitString()),
            modifier = Modifier
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
                enabled = enabled,
                valueRange = bitrateRangeKbits.start.toFloat()..bitrateRangeKbits.endInclusive.toFloat(),
                onValueChangeFinished = {
                    isDragging = false
                    onValueChange.invoke((sliderPosition * 1000).roundToInt())
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
internal fun Int.toKOrMBitString(): String =
    if (this >= 1000) stringResource(R.string.rtsp_video_bitrate_mbit, this / 1000f)
    else stringResource(R.string.rtsp_video_bitrate_kbit, this)
