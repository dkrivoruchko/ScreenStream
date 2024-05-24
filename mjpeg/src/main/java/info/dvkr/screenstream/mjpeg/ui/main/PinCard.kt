package info.dvkr.screenstream.mjpeg.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.MjpegState

@Composable
internal fun PinCard(
    mjpegState: State<MjpegState>,
    onCreateNewPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Box(
            modifier = Modifier.padding(8.dp).fillMaxWidth().minimumInteractiveComponentSize()
        ) {
            val isStreaming = remember { derivedStateOf { mjpegState.value.isStreaming } }
            val pinState = remember { derivedStateOf { mjpegState.value.pin } }

            if (pinState.value.enablePin) {
                if (isStreaming.value) {
                    if (pinState.value.hidePinOnStream) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed = interactionSource.collectIsPressedAsState()
                        val pinText = if (isPressed.value) pinState.value.pin else "*"
                        val text = stringResource(id = R.string.mjpeg_stream_pin, pinText)
                            .stylePlaceholder(pinText, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold))

                        Text(text = text, modifier = Modifier.align(Alignment.Center))
                        IconButton(
                            onClick = { },
                            modifier = Modifier.align(Alignment.CenterEnd),
                            interactionSource = interactionSource
                        ) {
                            Icon(
                                imageVector = Icon_Visibility,
                                contentDescription = stringResource(id = R.string.mjpeg_stream_description_show_pin)
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(id = R.string.mjpeg_stream_pin, pinState.value.pin)
                                .stylePlaceholder(pinState.value.pin, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(id = R.string.mjpeg_stream_pin, pinState.value.pin)
                            .stylePlaceholder(pinState.value.pin, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                        modifier = Modifier.align(Alignment.Center)
                    )
                    IconButton(
                        onClick = onCreateNewPin,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = Icon_Refresh,
                            contentDescription = stringResource(id = R.string.mjpeg_stream_description_create_pin)
                        )
                    }
                }
            } else {
                Text(text = stringResource(id = R.string.mjpeg_stream_pin_disabled), modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}


private val Icon_Visibility: ImageVector = materialIcon(name = "Outlined.Visibility") {
    materialPath {
        moveTo(12.0f, 6.0f)
        curveToRelative(3.79f, 0.0f, 7.17f, 2.13f, 8.82f, 5.5f)
        curveTo(19.17f, 14.87f, 15.79f, 17.0f, 12.0f, 17.0f)
        reflectiveCurveToRelative(-7.17f, -2.13f, -8.82f, -5.5f)
        curveTo(4.83f, 8.13f, 8.21f, 6.0f, 12.0f, 6.0f)
        moveToRelative(0.0f, -2.0f)
        curveTo(7.0f, 4.0f, 2.73f, 7.11f, 1.0f, 11.5f)
        curveTo(2.73f, 15.89f, 7.0f, 19.0f, 12.0f, 19.0f)
        reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
        curveTo(21.27f, 7.11f, 17.0f, 4.0f, 12.0f, 4.0f)
        close()
        moveTo(12.0f, 9.0f)
        curveToRelative(1.38f, 0.0f, 2.5f, 1.12f, 2.5f, 2.5f)
        reflectiveCurveTo(13.38f, 14.0f, 12.0f, 14.0f)
        reflectiveCurveToRelative(-2.5f, -1.12f, -2.5f, -2.5f)
        reflectiveCurveTo(10.62f, 9.0f, 12.0f, 9.0f)
        moveToRelative(0.0f, -2.0f)
        curveToRelative(-2.48f, 0.0f, -4.5f, 2.02f, -4.5f, 4.5f)
        reflectiveCurveTo(9.52f, 16.0f, 12.0f, 16.0f)
        reflectiveCurveToRelative(4.5f, -2.02f, 4.5f, -4.5f)
        reflectiveCurveTo(14.48f, 7.0f, 12.0f, 7.0f)
        close()
    }
}

private val Icon_Refresh: ImageVector = materialIcon(name = "Filled.Refresh") {
    materialPath {
        moveTo(17.65f, 6.35f)
        curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
        curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
        reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
        curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
        horizontalLineToRelative(-2.08f)
        curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
        curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
        reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
        curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
        lineTo(13.0f, 11.0f)
        horizontalLineToRelative(7.0f)
        verticalLineTo(4.0f)
        lineToRelative(-2.35f, 2.35f)
        close()
    }
}