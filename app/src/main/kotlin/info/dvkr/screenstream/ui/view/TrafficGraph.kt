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
            fun fromPair(point: Pair<Long, Long>) = DataPoint(point.first, point.second.bytesToMbit())

            private fun Long.bytesToMbit() = (this * 8).toFloat() / 1024 / 1024
        }
    }

    companion object {
        private const val Y_MARKS_COUNT: Int = 7
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
    private var maxY: Float = MINIMUM_FOR_MAX_Y_MARK
    private val yMarks: Array<Float> = Array(Y_MARKS_COUNT) { i -> (MINIMUM_FOR_MAX_Y_MARK / Y_MARKS_COUNT) * i }

    private var zeroX: Float = 0f
    private var zeroY: Float = 0f
    private var pxPerYUnit: Float = 0f

    private fun preparePaints() {
        gradientPaint.shader =
                LinearGradient(0f, paddingTop.toFloat(), 0f, zeroY, gradientColors, null, Shader.TileMode.CLAMP)
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

    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = resources.getDimension(R.dimen.fragment_stream_color_graph_mark_text_size)
        color = ContextCompat.getColor(context, R.color.textColorPrimary)
    }

    fun setDataPoints(points: List<Pair<Long, Long>>) {
        dataPoints = points.map { DataPoint.fromPair(it) }.toTypedArray()

        calculatePositions()
        preparePaints()
        invalidate()
    }

    private val textPadding = resources.getDimension(R.dimen.fragment_stream_color_graph_mark_text_padding)

    fun addDataPoint(point: Pair<Long, Long>) {
        dataPoints = dataPoints
            .sliceArray(1 until dataPoints.size)
            .plusElement(DataPoint.fromPair(point))

        calculatePositions()
        preparePaints()
        invalidate()
    }

    private fun calculatePositions() {
        val currentMaxY = (dataPoints.maxBy { it.y }?.y ?: MINIMUM_FOR_MAX_Y_MARK) * 1.1f
        maxY = max(maxY, currentMaxY)
        pxPerYUnit = (height - paddingTop - paddingBottom) / maxY
        zeroY = maxY * pxPerYUnit + paddingTop

        zeroX = (paddingStart + textPaint.measureText(MARK_FORMATTER.format(maxY)) + 2 * textPadding)
        val pxPerXStep = (width - zeroX - paddingEnd) / (dataPoints.size - 1)

        dataPoints.withIndex().forEach { (i, point) ->
            point.positionX = zeroX + pxPerXStep * i
            point.positionY = zeroY - point.y * pxPerYUnit
        }

        val markStepY = maxY / Y_MARKS_COUNT
        repeat(Y_MARKS_COUNT) { i -> yMarks[i] = markStepY * i }
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

        // Draw Y marks line
        guidelinePath.reset()
        yMarks.forEach { yMark ->
            guidelinePath.moveTo(zeroX, zeroY - yMark * pxPerYUnit)
            guidelinePath.lineTo((width - paddingEnd).toFloat(), zeroY - yMark * pxPerYUnit)
        }
        canvas.drawPath(guidelinePath, guidelinePaint)

        // Draw lines
        var previousDataPoint: DataPoint? = null
        dataPoints.forEach { dataPoint ->
            previousDataPoint?.let {
                canvas.drawLine(it.positionX, it.positionY, dataPoint.positionX, dataPoint.positionY, dataPointsPaint)
            }
            previousDataPoint = dataPoint
        }

        // Draw Y marks
        yMarks.forEach { yMark ->
            val text = MARK_FORMATTER.format(yMark)
            canvas.drawText(
                text,
                zeroX - textPaint.measureText(text) - textPadding,
                zeroY - yMark * pxPerYUnit,
                textPaint
            )
        }
    }
}