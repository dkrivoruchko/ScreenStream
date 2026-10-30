package io.screenstream.mjpeg.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import info.dvkr.screenstream.common.ui.ExpandableCard
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.ui.UiController
import io.screenstream.mjpeg.ui.settings.SettingEditor
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.DurationUnit

@Composable
internal fun StreamStatisticsContent(
    statistics: UiController.Statistics,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    var showEngineStatistics by retain { mutableStateOf(false) }
    val integerFormat = statisticsNumberFormat(0)
    val decimalFormat = statisticsNumberFormat(1)
    val unavailable = stringResource(R.string.mjpeg_stats_unavailable)
    val colors = MaterialTheme.colorScheme
    val connectedClients = integerFormat.format(statistics.clients.count { it.state == HttpDelivery.ClientInfo.State.Active })
    val currentFrameRate = statistics.currentFps?.let {
        stringResource(R.string.mjpeg_stats_fps_value, integerFormat.format(it))
    } ?: unavailable
    val outgoingTraffic = statistics.outgoingBytesPerSecond?.let {
        stringResource(R.string.mjpeg_stats_mbps_value, decimalFormat.format(it * 8 / 1_000_000))
    } ?: unavailable

    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        contentModifier = Modifier
            .fillMaxWidth()
            .background(ListItemDefaults.containerColor),
        headerContent = {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatisticsIcon(colors.onSurface)
                Row(
                    Modifier
                        .weight(1F)
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.mjpeg_stats_title), style = MaterialTheme.typography.titleMedium)
                    if (!expanded) {
                        val frameRateNumber = integerFormat.format(statistics.currentFps ?: 0.0)
                        val trafficNumber = decimalFormat.format((statistics.outgoingBytesPerSecond ?: 0.0) * 8 / 1_000_000)
                        val frameRate = stringResource(R.string.mjpeg_stats_fps_value, frameRateNumber)
                        val traffic = stringResource(R.string.mjpeg_stats_mbps_value, trafficNumber)
                        val summary = "$frameRate · $connectedClients · $traffic"
                        val styledSummary = buildAnnotatedString {
                            append(summary)
                            var searchStart = 0
                            for (number in listOf(frameRateNumber, connectedClients, trafficNumber)) {
                                val numberStart = summary.indexOf(number, searchStart)
                                if (numberStart >= 0) {
                                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), numberStart, numberStart + number.length)
                                    searchStart = numberStart + number.length
                                }
                            }
                        }
                        Box(Modifier.weight(1F), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                styledSummary,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        StatisticsChart(
            label = stringResource(R.string.mjpeg_stats_frame_rate),
            currentValue = currentFrameRate,
            valueNumberFormat = integerFormat,
            formatValue = { stringResource(R.string.mjpeg_stats_fps_value, it) },
            history = statistics.history,
            color = colors.primary,
            value = { it.fps },
        )
        Spacer(Modifier.height(16.dp))
        StatisticsChart(
            label = stringResource(R.string.mjpeg_stats_outgoing_traffic),
            currentValue = outgoingTraffic,
            valueNumberFormat = decimalFormat,
            formatValue = { stringResource(R.string.mjpeg_stats_mbps_value, it) },
            history = statistics.history,
            color = colors.tertiary,
            value = { it.outgoingBytesPerSecond?.times(8)?.div(1_000_000) },
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            val (connected, disconnected) = statistics.clients.partition { it.state == HttpDelivery.ClientInfo.State.Active }
            Text(
                stringResource(R.string.mjpeg_stats_connected_clients_count, integerFormat.format(connected.size)),
                style = MaterialTheme.typography.titleSmall,
            )
            if (connected.isEmpty()) {
                Text(
                    stringResource(R.string.mjpeg_stats_no_clients),
                    Modifier.padding(vertical = 8.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for ((index, client) in connected.withIndex()) key(client.id) {
                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                StatisticsClient(client, statistics.clientBytesPerSecond[client.id], decimalFormat)
            }
            if (disconnected.isNotEmpty()) {
                Text(
                    stringResource(R.string.mjpeg_stats_recently_disconnected),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                for ((index, client) in disconnected.withIndex()) key(client.id) {
                    if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                    StatisticsClient(client, null, decimalFormat)
                }
            }
            if (statistics.clients.isNotEmpty()) {
                HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center
        ) {
            TextButton(
                onClick = { showEngineStatistics = true },
                enabled = statistics.engineStats != null,
            ) {
                Icon(painterResource(R.drawable.mjpeg_icon_info), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mjpeg_stats_engine_title))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showEngineStatistics && statistics.engineStats != null) {
        EngineStatisticsEditor(statistics.engineStats, integerFormat, decimalFormat) { showEngineStatistics = false }
    }
}

@Composable
private fun StatisticsRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            Modifier.weight(1F),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            modifier = Modifier.widthIn(max = 160.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatisticsChart(
    label: String,
    currentValue: String,
    valueNumberFormat: NumberFormat,
    formatValue: @Composable (String) -> String,
    history: List<UiController.HistoryPoint>,
    color: Color,
    value: (UiController.HistoryPoint) -> Double?,
) {
    var recordedMinimum: Double? = null
    var observedMaximum = 0.0
    for (point in history) {
        val sampleValue = value(point) ?: continue
        recordedMinimum = minOf(recordedMinimum ?: sampleValue, sampleValue)
        observedMaximum = maxOf(observedMaximum, sampleValue)
    }
    val magnitude = 10.0.pow(floor(log10(if (observedMaximum > 0) observedMaximum.coerceAtLeast(0.01) else 1.0)))
    val maximum = maxOf(magnitude, ceil(observedMaximum / magnitude) * magnitude)
    val numberFormat = statisticsNumberFormat(if (maximum >= 10) 0 else if (maximum >= 1) 1 else 3)
    val intervalMillis = UiController.Statistics.HISTORY_INTERVAL.inWholeMilliseconds
    val historyMillis = UiController.Statistics.HISTORY_DURATION.inWholeMilliseconds
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35F)
    val title = stringResource(R.string.mjpeg_stats_chart_title, label, currentValue)
    val rangeNumberFormat = if (numberFormat.maximumFractionDigits > valueNumberFormat.maximumFractionDigits) numberFormat else valueNumberFormat
    val accessibleTitle = recordedMinimum?.let { minimum ->
        stringResource(
            R.string.mjpeg_stats_chart_recorded_range,
            title,
            rangeNumberFormat.format(minimum),
            formatValue(rangeNumberFormat.format(observedMaximum)),
        )
    } ?: title
    val styledTitle = buildAnnotatedString {
        append(title)
        val valueStart = title.indexOf(currentValue)
        if (valueStart >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), valueStart, valueStart + currentValue.length)
    }
    Text(
        styledTitle,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics { text = AnnotatedString(accessibleTitle) },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Normal,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp)
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .height(100.dp)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            for (tick in listOf(maximum, maximum / 2, 0.0)) {
                Text(numberFormat.format(tick), color = axisColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Column(Modifier.weight(1F)) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clipToBounds()
                    .drawWithCache {
                        val top = 6.dp.toPx()
                        val bottom = size.height - top
                        val gradient = Brush.verticalGradient(
                            0.05F to color.copy(alpha = 0.32F),
                            0.95F to color.copy(alpha = 0.04F),
                            startY = top,
                            endY = bottom,
                        )
                        val stroke = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val strokePath = Path()
                        val areaPath = Path()
                        onDrawBehind {
                            strokePath.rewind()
                            repeat(3) { index ->
                                val y = top + index / 2F * (bottom - top)
                                drawLine(gridColor, Offset(0F, y), Offset(size.width, y), strokeWidth = 0.75.dp.toPx())
                            }
                            val latestTimeMillis = history.lastOrNull()?.atElapsedRealtimeMillis ?: 0L
                            var previousPosition = Offset(0F, bottom)
                            var previousTimeMillis: Long? = null
                            strokePath.moveTo(0F, bottom)
                            for (point in history) {
                                val sampleValue = value(point) ?: 0.0
                                val position = Offset(
                                    ((point.atElapsedRealtimeMillis - latestTimeMillis + historyMillis).toFloat() / historyMillis * size.width).coerceIn(
                                        0F,
                                        size.width
                                    ),
                                    top + (1 - sampleValue / maximum).toFloat() * (bottom - top),
                                )
                                val previousTime = previousTimeMillis
                                if (previousTime == null || point.atElapsedRealtimeMillis / intervalMillis - previousTime / intervalMillis > 1L) {
                                    // Unknown intervals stay at zero instead of bridging measured values.
                                    strokePath.lineTo(previousPosition.x, bottom)
                                    strokePath.lineTo(position.x, bottom)
                                    strokePath.lineTo(position.x, position.y)
                                } else {
                                    // Horizontal endpoint tangents keep the curve within the two measured values.
                                    val middleX = (previousPosition.x + position.x) / 2
                                    strokePath.cubicTo(middleX, previousPosition.y, middleX, position.y, position.x, position.y)
                                }
                                previousPosition = position
                                previousTimeMillis = point.atElapsedRealtimeMillis
                            }
                            if (history.isEmpty()) strokePath.lineTo(size.width, bottom)
                            areaPath.rewind()
                            areaPath.addPath(strokePath)
                            areaPath.lineTo(size.width, bottom)
                            areaPath.lineTo(0F, bottom)
                            areaPath.close()
                            drawPath(areaPath, gradient)
                            drawPath(strokePath, color, style = stroke)
                        }
                    })
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp), horizontalArrangement = Arrangement.Absolute.SpaceBetween
            ) {
                Text(stringResource(R.string.mjpeg_stats_chart_start), color = axisColor, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.mjpeg_stats_chart_middle), color = axisColor, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.mjpeg_stats_chart_now), color = axisColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatisticsClient(client: HttpDelivery.ClientInfo, bytesPerSecond: Double?, numberFormat: NumberFormat) {
    val connected = client.state == HttpDelivery.ClientInfo.State.Active
    val colors = MaterialTheme.colorScheme
    val sentValue = numberFormat.format(client.completedJpegBytes / 1_000_000.0)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            Modifier
                .weight(1F)
                .width(IntrinsicSize.Max)
        ) {
            Text(
                if (':' in client.ip) "[${client.ip}]:${client.remotePort}" else "${client.ip}:${client.remotePort}",
                color = if (connected) colors.onSurface else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (connected && client.isSlow) {
                val warning = if (colors.surface.luminance() < 0.5F) Color(0xFFFFCF76) else Color(0xFF815500)
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.mjpeg_icon_warning), contentDescription = null, tint = warning, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.mjpeg_stats_slow_reader), color = warning, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val sent = stringResource(R.string.mjpeg_stats_sent_value, sentValue)
        if (connected) {
            val speedValue = bytesPerSecond?.let { numberFormat.format(it * 8 / 1_000_000) }
                ?: stringResource(R.string.mjpeg_stats_unavailable)
            val speed = if (bytesPerSecond != null) stringResource(R.string.mjpeg_stats_mbps_value, speedValue) else speedValue
            val current = stringResource(R.string.mjpeg_stats_client_current_value, speed)
            ClientMetric("$sent · $current", sentValue, speedValue)
        } else ClientMetric(sent, sentValue)
    }
}

@Composable
private fun ClientMetric(text: String, vararg values: String) {
    val valueStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
    )
    val styledText = buildAnnotatedString {
        append(text)
        var searchStart = 0
        for (value in values) {
            val valueStart = text.indexOf(value, searchStart)
            if (valueStart >= 0) {
                addStyle(valueStyle, valueStart, valueStart + value.length)
                searchStart = valueStart + value.length
            }
        }
    }
    Text(
        styledText,
        Modifier.width(IntrinsicSize.Max),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun StatisticsIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val strokeWidth = 3.dp.toPx()
        drawLine(color, Offset(size.width * 0.2F, size.height), Offset(size.width * 0.2F, size.height * (1 - 0.45F)), strokeWidth)
        drawLine(color, Offset(size.width * 0.5F, size.height), Offset(size.width * 0.5F, size.height * (1 - 0.85F)), strokeWidth)
        drawLine(color, Offset(size.width * 0.8F, size.height), Offset(size.width * 0.8F, size.height * (1 - 0.65F)), strokeWidth)
    }
}

@Composable
private fun EngineStatisticsEditor(stats: ScreenCaptureStats, integerFormat: NumberFormat, decimalFormat: NumberFormat, onClose: () -> Unit) {
    SettingEditor(title = stringResource(R.string.mjpeg_stats_engine_title), onDismiss = onClose) {
        Column {
            Text(
                stringResource(R.string.mjpeg_stats_frames),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            StatisticsRow(stringResource(R.string.mjpeg_stats_encoded), integerFormat.format(stats.encodedFrameCount))
            StatisticsRow(stringResource(R.string.mjpeg_stats_produced), integerFormat.format(stats.producedFrameCount))
            StatisticsRow(stringResource(R.string.mjpeg_stats_mean_fps), decimalFormat.format(stats.averageProducedFps))
            Text(
                stringResource(R.string.mjpeg_stats_processing),
                Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            StatisticsRow(
                stringResource(R.string.mjpeg_stats_mean_read),
                stringResource(R.string.mjpeg_stats_ms_value, decimalFormat.format(stats.averageReadbackDuration.toDouble(DurationUnit.MILLISECONDS))),
            )
            StatisticsRow(
                stringResource(R.string.mjpeg_stats_mean_encode),
                stringResource(R.string.mjpeg_stats_ms_value, decimalFormat.format(stats.averageEncodingDuration.toDouble(DurationUnit.MILLISECONDS))),
            )
            StatisticsRow(
                stringResource(R.string.mjpeg_stats_last_jpeg),
                stringResource(R.string.mjpeg_stats_kb_value, decimalFormat.format(stats.lastEncodedByteCount / 1_000.0)),
            )
            StatisticsRow(
                stringResource(R.string.mjpeg_stats_mean_jpeg),
                stringResource(R.string.mjpeg_stats_kb_value, decimalFormat.format(stats.averageEncodedByteCount / 1_000.0)),
            )
            Text(
                stringResource(R.string.mjpeg_stats_skipped_frames),
                Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            StatisticsRow(stringResource(R.string.mjpeg_stats_production_stale), integerFormat.format(stats.frameProductionDrops.byStaleWork))
            StatisticsRow(stringResource(R.string.mjpeg_stats_production_failed), integerFormat.format(stats.frameProductionDrops.byFailure))
            StatisticsRow(stringResource(R.string.mjpeg_stats_production_total), integerFormat.format(stats.frameProductionDrops.total))
            StatisticsRow(stringResource(R.string.mjpeg_stats_delivery_busy), integerFormat.format(stats.droppedDeliveries.byConsumerBusy))
            StatisticsRow(stringResource(R.string.mjpeg_stats_delivery_errors), integerFormat.format(stats.droppedDeliveries.byCallbackFailure))
            StatisticsRow(stringResource(R.string.mjpeg_stats_delivery_total), integerFormat.format(stats.droppedDeliveries.total))
        }
    }
}

@Composable
private fun statisticsNumberFormat(fractionDigits: Int): NumberFormat {
    val configuration = LocalConfiguration.current
    return remember(configuration, fractionDigits) {
        val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = fractionDigits }
    }
}
