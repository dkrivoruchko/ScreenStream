package info.dvkr.screenstream.rtsp.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EncoderItem(
    codecName: String,
    encoderName: String,
    isHardwareAccelerated: Boolean,
    isCBRModeSupported: Boolean,
    hwColor: Color = MaterialTheme.colorScheme.primary.let { if (isHardwareAccelerated.not()) it.copy(alpha = 0.5f) else it },
    cbrColor: Color = MaterialTheme.colorScheme.primary.let { if (isCBRModeSupported.not()) it.copy(alpha = 0.5f) else it },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = codecName)
            Box(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .wrapContentSize()
            ) {
                Text(
                    text = "HW",
                    modifier = Modifier
                        .border(width = 1.dp, color = hwColor, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = hwColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                if (isHardwareAccelerated.not()) {
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawLine(
                            color = hwColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .wrapContentSize()
            ) {
                Text(
                    text = "CBR",
                    modifier = Modifier
                        .border(width = 1.dp, color = cbrColor, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = cbrColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                if (isCBRModeSupported.not()) {
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawLine(
                            color = cbrColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
        }
        Text(text = encoderName, fontSize = 14.sp)
    }
}