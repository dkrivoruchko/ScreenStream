package info.dvkr.screenstream.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView

class ExpansionLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onExpansionChanged(expansionLayout: ExpansionLayout, expanded: Boolean)
    }

    interface IndicatorListener {
        fun onStartedExpand(expansionLayout: ExpansionLayout, willExpand: Boolean)
    }

    private val indicatorListeners: MutableList<IndicatorListener> = mutableListOf()
    private val listeners: MutableList<Listener> = mutableListOf()
    var isExpanded = false
        private set
    private var animator: Animator? = null

    init {
        requestDisallowInterceptTouchEvent(true)
    }

    fun addListener(listener: Listener?) {
        if (listener != null && listeners.contains(listener).not()) listeners.add(listener)
    }

    fun removeListener(listener: Listener?) {
        listener?.let { listeners.remove(it) }
    }

    fun addIndicatorListener(listener: IndicatorListener?) {
        if (listener != null && indicatorListeners.contains(listener).not()) indicatorListeners.add(listener)
    }

    fun removeIndicatorListener(listener: IndicatorListener?) {
        listener?.let { indicatorListeners.remove(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isExpanded.not()) setHeight(0f)
    }

    override fun addView(child: View) {
        if (childCount > 0) throw IllegalStateException("ExpansionLayout can host only one direct child")
        super.addView(child)
        onViewAdded()
    }

    override fun addView(child: View, index: Int) {
        if (childCount > 0) throw IllegalStateException("ExpansionLayout can host only one direct child")
        super.addView(child, index)
        onViewAdded()
    }

    override fun addView(child: View, params: ViewGroup.LayoutParams) {
        if (childCount > 0) throw IllegalStateException("ExpansionLayout can host only one direct child")
        super.addView(child, params)
        onViewAdded()
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (childCount > 0) throw IllegalStateException("ExpansionLayout can host only one direct child")
        super.addView(child, index, params)
        onViewAdded()
    }

    private fun onViewAdded() {
        if (childCount != 0) {
            val childView = getChildAt(0)
            childView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    childView.viewTreeObserver.removeOnPreDrawListener(this)
                    //now we have a size
                    if (isExpanded) expand(false)
                    childView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                        if (isExpanded && animator == null) post { setHeight((bottom - top).toFloat()) }
                    }
                    return true
                }
            })
        }
    }

    fun collapse(animated: Boolean) {
        if (isEnabled.not() || isExpanded.not()) return

        pingIndicatorListeners(false)
        if (animated) {
            val valueAnimator = ValueAnimator.ofFloat(height.toFloat(), 0f)
            valueAnimator.addUpdateListener { animator -> setHeight(animator.animatedValue as Float) }
            valueAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    animator = null
                    pingListeners()
                }
            })
            isExpanded = false
            animator = valueAnimator
            valueAnimator.start()
        } else {
            setHeight(0f)
            isExpanded = false
            pingListeners()
        }
    }

    private fun pingIndicatorListeners(willBeExpanded: Boolean) {
        indicatorListeners.forEach { indicatorListener -> indicatorListener.onStartedExpand(this, willBeExpanded) }
    }

    private fun pingListeners() {
        listeners.forEach { listener -> listener.onExpansionChanged(this, isExpanded) }
    }

    fun expand(animated: Boolean) {
        if (isEnabled.not() || isExpanded) return

        pingIndicatorListeners(true)
        if (animated) {
            val valueAnimator = ValueAnimator.ofFloat(0f, getChildAt(0).height.toFloat())
            valueAnimator.addUpdateListener { animator -> setHeight(animator.animatedValue as Float) }
            valueAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    animator = null
                    pingListeners()
                }
            })
            isExpanded = true
            animator = valueAnimator
            valueAnimator.start()
        } else {
            setHeight(getChildAt(0).height.toFloat())
            isExpanded = true
            pingListeners()
        }
    }

    private fun setHeight(height: Float) {
        val layoutParams = layoutParams
        if (layoutParams != null) {
            layoutParams.height = height.toInt()
            setLayoutParams(layoutParams)
        }
    }

    fun toggle(animated: Boolean) {
        if (isExpanded) {
            collapse(animated)
        } else {
            expand(animated)
        }
    }
}