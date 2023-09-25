package info.dvkr.screenstream.mjpeg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

public class ColorCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint()
    private val strokePaint = Paint()
    private val borderWidth = resources.getDimension(com.afollestad.materialdialogs.color.R.dimen.color_circle_view_border)

    init {
        setWillNotDraw(false)
        fillPaint.style = Paint.Style.FILL
        fillPaint.isAntiAlias = true
        fillPaint.color = Color.DKGRAY
        strokePaint.style = Paint.Style.STROKE
        strokePaint.isAntiAlias = true
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = borderWidth
    }

    @ColorInt
    public var color: Int = Color.BLACK
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    @ColorInt
   public var border: Int = Color.DKGRAY
        set(value) {
            field = value
            strokePaint.color = value
            invalidate()
        }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit =
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)

    public override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, (width / 2f) - borderWidth, fillPaint)
        canvas.drawCircle(width / 2f, height / 2f, (width / 2f) - borderWidth, strokePaint)
    }
}