package info.dvkr.screenstream.mjpeg.ui.main

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.MjpegState
import java.text.NumberFormat
import kotlin.math.max

@Composable
internal fun TrafficCard(
    mjpegState: State<MjpegState>,
    modifier: Modifier = Modifier,
) {
    ExpandableCard(
        headerContent = {
            val currentTraffic = remember { derivedStateOf { mjpegState.value.traffic.lastOrNull()?.MBytes ?: 0F } }
            val locale = AppCompatDelegate.getApplicationLocales().get(0)
            Text(
                text = stringResource(id = R.string.mjpeg_stream_current_traffic, currentTraffic.value)
                    .stylePlaceholder(String.format(locale, "%1$,.2f", currentTraffic.value), SpanStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.align(Alignment.Center)
            )
        },
        modifier = modifier
    ) {
        val numberFormat = remember {
            NumberFormat.getInstance().apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
                minimumIntegerDigits = 1
            }
        }

        TrafficGraph(
            points = remember { derivedStateOf { mjpegState.value.traffic } },
            yLabel = { value ->
                Text(
                    text = numberFormat.format(value),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    maxLines = 1
                )
            },
            contentDescription = stringResource(id = R.string.mjpeg_stream_traffic_graph),
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )
    }
}

@Composable
private fun TrafficGraph(
    points: State<List<MjpegState.TrafficPoint>>,
    yLabel: @Composable (Float) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    yMarksCount: Int = 6
) {
    if (points.value.isEmpty()) return

    val maxY = remember { mutableFloatStateOf(1F) }
    val newMaxY = points.value.maxByOrNull { it.MBytes }?.MBytes ?: 1F
    val currentMaxY = max(maxY.floatValue, newMaxY)

    LaunchedEffect(currentMaxY) { maxY.floatValue = currentMaxY }

    val yMarkLineColor = LocalContentColor.current.copy(alpha = .5F)
    val lineColor = MaterialTheme.colorScheme.primary

    Row(modifier = modifier.padding(8.dp)) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(end = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            repeat(yMarksCount + 1) { i -> yLabel.invoke(currentMaxY / yMarksCount * (yMarksCount - i)) }
        }

        Spacer(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .drawWithCache {
                    val yMarkLinePathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f)
                    val gradientBrush = Brush.verticalGradient(listOf(lineColor.copy(alpha = .6F), lineColor.copy(alpha = 0F)))

                    val xStepPx = size.width / (points.value.size - 1)
                    val yStepPx = size.height / currentMaxY

                    val pointsPath = Path().apply {
                        moveTo(0F, size.height)
                        points.value.forEachIndexed { i, (_, y) -> lineTo(xStepPx * i, yStepPx * (currentMaxY - y)) }
                        lineTo(size.width, size.height)
                    }

                    val linePath = Path().apply {
                        moveTo(0F, yStepPx * (currentMaxY - points.value.first().MBytes))
                        points.value.forEachIndexed { i, (_, y) -> lineTo(xStepPx * i, yStepPx * (currentMaxY - y)) }
                    }

                    onDrawBehind {
                        val yMarkLineStepPx = size.height / yMarksCount
                        repeat(yMarksCount + 1) { i ->
                            drawLine(
                                color = yMarkLineColor,
                                start = Offset(0F, yMarkLineStepPx * i),
                                end = Offset(size.width, yMarkLineStepPx * i),
                                pathEffect = yMarkLinePathEffect
                            )
                        }

                        drawPath(path = pointsPath, brush = gradientBrush)

                        drawPath(path = linePath, color = lineColor, style = Stroke(width = 4F))
                    }
                }
        )
    }
}