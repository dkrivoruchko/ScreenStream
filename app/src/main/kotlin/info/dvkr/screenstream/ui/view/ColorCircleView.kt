package info.dvkr.screenstream.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import com.afollestad.materialdialogs.utils.MDUtil.dimenPx
import info.dvkr.screenstream.R

class ColorCircleView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val fillPaint = Paint()
    private val strokePaint = Paint()
    private val borderWidth = dimenPx(R.dimen.color_circle_view_border)

    init {
        setWillNotDraw(false)
        fillPaint.style = Paint.Style.FILL
        fillPaint.isAntiAlias = true
        fillPaint.color = Color.DKGRAY
        strokePaint.style = Paint.Style.STROKE
        strokePaint.isAntiAlias = true
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = borderWidth.toFloat()
    }

    @ColorInt var color: Int = Color.BLACK
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    @ColorInt var border: Int = Color.DKGRAY
        set(value) {
            field = value
            strokePaint.color = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(measuredWidth / 2f, measuredHeight / 2f, (measuredWidth / 2f) - borderWidth, fillPaint)
        canvas.drawCircle(measuredWidth / 2f, measuredHeight / 2f, (measuredWidth / 2f) - borderWidth, strokePaint)
    }
}