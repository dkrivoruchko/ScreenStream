package info.dvkr.screenstream.ui.view

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import info.dvkr.screenstream.R
import java.text.NumberFormat
import kotlin.math.max


class TrafficGraph @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class DataPoint(val x: Long, val y: Float, var positionX: Float = 0f, var positionY: Float = 0f) {
        companion object {
            fun fromPair(point: Pair<Long, Float>) = DataPoint(point.first, point.second)
        }
    }

    companion object {
        private const val Y_MARKS_COUNT: Int = 6
        private const val MINIMUM_FOR_MAX_Y_MARK = 1f

        private val MARK_FORMATTER = NumberFormat.getInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            minimumIntegerDigits = 1
        }
    }

    init {
        setWillNotDraw(false)
    }

    private var dataPoints: Array<DataPoint> = emptyArray()
    private var pxPerYUnit: Float = 0f
    private var maxY: Float = MINIMUM_FOR_MAX_Y_MARK
    private var zeroX: Float = 0f
    private var zeroY: Float = 0f
    private var previousDataPoint: DataPoint? = null
    private val yMarks: Array<Pair<Float, String>> = Array(Y_MARKS_COUNT) { i ->
        val mark = (MINIMUM_FOR_MAX_Y_MARK / Y_MARKS_COUNT) * i
        Pair(mark, MARK_FORMATTER.format(mark))
    }

    private val gradientPath = Path()
    private val gradientColors = intArrayOf(
        ContextCompat.getColor(context, R.color.colorGraphGradientStart),
        ContextCompat.getColor(context, R.color.colorGraphGradientEnd)
    )
    private val gradientPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val guidelinePath = Path()
    private val guidelinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.fragment_stream_color_graph_guideline_width)
        color = ContextCompat.getColor(context, R.color.colorGraphGuideline)
        val dashLength = resources.getDimension(R.dimen.fragment_stream_color_graph_guideline_dash_length)
        pathEffect = DashPathEffect(floatArrayOf(dashLength, dashLength), 0f)
        isDither = true
    }

    private val dataPointsPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.fragment_stream_color_graph_line_width)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.colorAccent)
        isAntiAlias = true
    }

    private val textPadding = resources.getDimension(R.dimen.fragment_stream_color_graph_mark_text_padding)
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = resources.getDimension(R.dimen.fragment_stream_color_graph_mark_text_size)
        color = ContextCompat.getColor(context, R.color.textColorPrimary)
    }

    fun setDataPoints(points: List<Pair<Long, Float>>) {
        dataPoints = points.sortedBy { it.first }.map { DataPoint.fromPair(it) }.toTypedArray()

        calculatePositions()

        gradientPaint.shader =
                LinearGradient(0f, paddingTop.toFloat(), 0f, zeroY, gradientColors, null, Shader.TileMode.CLAMP)

        invalidate()
    }

    private fun calculatePositions() {
        val currentMaxY = (dataPoints.maxByOrNull { it.y }?.y ?: MINIMUM_FOR_MAX_Y_MARK) * 1.1f
        maxY = max(maxY, currentMaxY)
        pxPerYUnit = (height - paddingTop - paddingBottom) / maxY
        zeroY = maxY * pxPerYUnit + paddingTop

        val markStepY = maxY / Y_MARKS_COUNT
        repeat(Y_MARKS_COUNT) { i ->
            val mark = markStepY * i
            yMarks[i] = Pair(mark, MARK_FORMATTER.format(mark))
        }

        zeroX = (paddingStart + textPaint.measureText(yMarks.last().second) + 2 * textPadding)
        val pxPerXStep = (width - zeroX - paddingEnd) / (dataPoints.size - 1)

        dataPoints.withIndex().forEach { (i, point) ->
            point.positionX = zeroX + pxPerXStep * i
            point.positionY = zeroY - point.y * pxPerYUnit
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Draw gradient
        if (dataPoints.isNotEmpty()) {
            gradientPath.reset()
            gradientPath.moveTo(zeroX, zeroY)
            dataPoints.forEach { gradientPath.lineTo(it.positionX, it.positionY) }
            gradientPath.lineTo(dataPoints.last().positionX, zeroY)
            gradientPath.lineTo(zeroX, zeroY)
            canvas.drawPath(gradientPath, gradientPaint)
        }

        // Draw Y marks lines
        guidelinePath.reset()
        yMarks.forEach { yMark ->
            guidelinePath.moveTo(zeroX, zeroY - yMark.first * pxPerYUnit)
            guidelinePath.lineTo((width - paddingEnd).toFloat(), zeroY - yMark.first * pxPerYUnit)
        }
        canvas.drawPath(guidelinePath, guidelinePaint)

        // Draw lines
        previousDataPoint = null
        dataPoints.forEach { dataPoint ->
            previousDataPoint?.let {
                canvas.drawLine(it.positionX, it.positionY, dataPoint.positionX, dataPoint.positionY, dataPointsPaint)
            }
            previousDataPoint = dataPoint
        }

        // Draw Y marks
        yMarks.forEach { mark ->
            canvas.drawText(
                mark.second,
                zeroX - textPaint.measureText(mark.second) - textPadding,
                zeroY - mark.first * pxPerYUnit + textPaint.textSize / 4f,
                textPaint
            )
        }
    }
}