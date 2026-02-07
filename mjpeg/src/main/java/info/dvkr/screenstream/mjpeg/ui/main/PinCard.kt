package info.dvkr.screenstream.mjpeg.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
        ) {
            if (mjpegState.value.pin.enablePin) {
                if (mjpegState.value.isStreaming) {
                    if (mjpegState.value.pin.hidePinOnStream) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed = interactionSource.collectIsPressedAsState()
                        val pinText = if (isPressed.value) mjpegState.value.pin.pin else "*"
                        val text = stringResource(id = R.string.mjpeg_stream_pin, pinText)
                            .stylePlaceholder(pinText, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold))

                        Text(text = text, modifier = Modifier.align(Alignment.Center))
                        IconButton(
                            onClick = { },
                            modifier = Modifier.align(Alignment.CenterEnd),
                            interactionSource = interactionSource
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.visibility_24px),
                                contentDescription = stringResource(id = R.string.mjpeg_stream_description_show_pin)
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(id = R.string.mjpeg_stream_pin, mjpegState.value.pin.pin)
                                .stylePlaceholder(
                                    mjpegState.value.pin.pin,
                                    SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)
                                ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(id = R.string.mjpeg_stream_pin, mjpegState.value.pin.pin)
                            .stylePlaceholder(
                                mjpegState.value.pin.pin,
                                SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)
                            ),
                        modifier = Modifier.align(Alignment.Center)
                    )
                    IconButton(
                        onClick = onCreateNewPin,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px),
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
