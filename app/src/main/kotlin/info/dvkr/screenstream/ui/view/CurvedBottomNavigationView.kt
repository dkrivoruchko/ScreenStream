package info.dvkr.screenstream.ui.view

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import info.dvkr.screenstream.R


class CurvedBottomNavigationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : BottomNavigationView(context, attrs, defStyleAttr) {

    private val path: Path = Path()
    private var fabRadius: Int = resources.getDimensionPixelSize(R.dimen.fab_size_normal) / 2
    private var backgroundShapeColor: Int =
        ContextCompat.getColor(getContext(), R.color.colorNavigationBackground)

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val firstCurveStartPoint = Point().apply { set(w / 2 - (fabRadius * 1.5).toInt(), 0) }
        val secondCurveEndPoint = Point().apply { set(w / 2 + (fabRadius * 1.5).toInt(), 0) }

        val firstCurveEndPoint = Point().apply { set(w / 2, fabRadius) }
        val secondCurveStartPoint = Point().apply { set(w / 2, fabRadius) }

        val firstCurveControlPoint1 = Point().apply {
            set(firstCurveStartPoint.x + fabRadius / 2, firstCurveStartPoint.y)
        }
        val firstCurveControlPoint2 = Point().apply {
            set(firstCurveEndPoint.x - fabRadius, firstCurveEndPoint.y)
        }

        val secondCurveControlPoint1 = Point().apply {
            set(secondCurveStartPoint.x + fabRadius, secondCurveStartPoint.y)
        }
        val secondCurveControlPoint2 = Point().apply {
            set(secondCurveEndPoint.x - fabRadius / 2, secondCurveEndPoint.y)
        }

        path.apply {
            reset()
            moveTo(0f, 0f)
            lineTo(firstCurveStartPoint.x.toFloat(), firstCurveStartPoint.y.toFloat())
            cubicTo(
                firstCurveControlPoint1.x.toFloat(), firstCurveControlPoint1.y.toFloat(),
                firstCurveControlPoint2.x.toFloat(), firstCurveControlPoint2.y.toFloat(),
                firstCurveEndPoint.x.toFloat(), firstCurveEndPoint.y.toFloat()
            )
            cubicTo(
                secondCurveControlPoint1.x.toFloat(), secondCurveControlPoint1.y.toFloat(),
                secondCurveControlPoint2.x.toFloat(), secondCurveControlPoint2.y.toFloat(),
                secondCurveEndPoint.x.toFloat(), secondCurveEndPoint.y.toFloat()
            )
            lineTo(w.toFloat(), 0f)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }

        val shape = PathShape(path, w.toFloat(), h.toFloat())
        background = ShapeDrawable(shape).apply {
            colorFilter = PorterDuffColorFilter(backgroundShapeColor, PorterDuff.Mode.SRC_IN)
        }
    }
}