package io.screenstream.mjpeg.device

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.os.ConfigurationCompat
import io.screenstream.mjpeg.R
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.abs

/** Reads native statistics by their localized labels; no controller or Engine state is supplied. */
internal class MjpegStatisticsUi(private val compose: ComposeTestRule, private val context: Context) {
    data class FrameTotals(val produced: Long, val encoded: Long)

    private val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    private val integerFormat = NumberFormat.getIntegerInstance(locale)
    private val numberFormat = NumberFormat.getNumberInstance(locale)
    private val frameRateLabel = context.getString(R.string.mjpeg_stats_frame_rate)
    private val numberMarker = "__current_number__"
    private val chartTemplate = context.getString(
        R.string.mjpeg_stats_chart_title,
        frameRateLabel,
        context.getString(R.string.mjpeg_stats_fps_value, numberMarker),
    )
    private val chartPrefix = chartTemplate.substringBefore(numberMarker)
    private val chartSuffix = chartTemplate.substringAfter(numberMarker)
    private val fpsChartMatcher = SemanticsMatcher("localized frame-rate chart title") { node ->
        node.singleText()?.startsWith(chartPrefix) == true
    }

    fun expand() {
        val title = context.getString(R.string.mjpeg_stats_title)
        scrollGridTo(hasText(title, substring = true))
        compose.onNodeWithText(title, substring = true).performClick()
    }

    /** The expanded chart distinguishes unavailable from numeric zero; the collapsed header does not. */
    fun readCurrentFps(): Double? {
        scrollGridTo(fpsChartMatcher)
        val title = checkNotNull(compose.onNode(fpsChartMatcher).assertIsDisplayed().fetchSemanticsNode().singleText())
        val unavailable = context.getString(
            R.string.mjpeg_stats_chart_title, frameRateLabel, context.getString(R.string.mjpeg_stats_unavailable),
        )
        if (title.startsWith(unavailable)) return null
        val position = ParsePosition(chartPrefix.length)
        val value = checkNotNull(numberFormat.parse(title, position)) { "No current numeric FPS in: $title" }.toDouble()
        check(title.substring(position.index).startsWith(chartSuffix)) { "Invalid current FPS unit in: $title" }
        check(value.isFinite() && value >= 0.0) { "Invalid current FPS in: $title" }
        // Only the current value is parsed; numeric recorded-history ranges cannot satisfy this oracle.
        return value
    }

    fun readFrameTotals(): FrameTotals {
        val title = context.getString(R.string.mjpeg_stats_engine_title)
        scrollGridTo(hasText(title))
        compose.onNodeWithText(title).performClick()
        try {
            return FrameTotals(produced = readCountBesideLabel(R.string.mjpeg_stats_produced), encoded = readCountBesideLabel(R.string.mjpeg_stats_encoded))
        } finally {
            compose.onNodeWithContentDescription(context.getString(R.string.mjpeg_action_close)).performClick()
        }
    }

    private fun readCountBesideLabel(labelResource: Int): Long {
        val label = compose.onNodeWithText(context.getString(labelResource), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        // StatisticsRow exposes separate Text nodes. Its top-aligned label and value share a row;
        // match that visible pairing within the same semantics root, including RTL layouts.
        val values = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { node ->
                node.id != label.id && node.semanticsRootId() == label.semanticsRootId() &&
                        abs(node.boundsInRoot.top - label.boundsInRoot.top) < 1f &&
                        (node.boundsInRoot.left >= label.boundsInRoot.right || node.boundsInRoot.right <= label.boundsInRoot.left)
            }.mapNotNull { node ->
                val text = node.singleText() ?: return@mapNotNull null
                val position = ParsePosition(0)
                integerFormat.parse(text, position)?.takeIf { position.index == text.length }?.toLong()
            }
        check(values.size == 1 && values.single() >= 0L) {
            "Expected one nonnegative count beside ${context.getString(labelResource)}; found $values"
        }
        return values.single()
    }

    private fun scrollGridTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    private fun SemanticsNode.singleText(): String? = config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text

    private fun SemanticsNode.semanticsRootId(): Int = generateSequence(this) { it.parent }.last().id
}
