package info.dvkr.screenstream.ui.view


import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import info.dvkr.screenstream.R

class ExpansionHeader @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var headerIndicatorId = 0
    private var expansionLayoutId = 0
    private var headerIndicator: View? = null
    private var expansionLayout: ExpansionLayout? = null
    internal var indicatorAnimator: Animator? = null
    private var headerRotationCollapsed = 0
    private var headerRotationExpanded = 90
    private var expansionLayoutInitialised = false

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.ExpansionHeader).run {
                setHeaderIndicatorId(
                    getResourceId(R.styleable.ExpansionHeader_expansion_headerIndicator, headerIndicatorId)
                )
                setExpansionLayoutId(getResourceId(R.styleable.ExpansionHeader_expansion_layout, expansionLayoutId))
                recycle()
            }
        }
    }

    private fun setHeaderIndicatorId(headerIndicatorId: Int) {
        this.headerIndicatorId = headerIndicatorId
        if (headerIndicatorId != 0) {
            headerIndicator = findViewById(headerIndicatorId)
            setExpansionHeaderIndicator(headerIndicator)
        }
    }

    private fun setExpansionHeaderIndicator(headerIndicator: View?) {
        this.headerIndicator = headerIndicator

        //if not, the view will clip when rotate
        headerIndicator?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        setup()
    }

    private fun setExpansionLayout(expansionLayout: ExpansionLayout?) {
        this.expansionLayout = expansionLayout
        setup()
    }

    private fun setExpansionLayoutId(expansionLayoutId: Int) {
        this.expansionLayoutId = expansionLayoutId

        if (expansionLayoutId != 0) {
            val parent = parent
            if (parent is ViewGroup) {
                val view = parent.findViewById<View>(expansionLayoutId)
                if (view is ExpansionLayout) setExpansionLayout(view)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setHeaderIndicatorId(this.headerIndicatorId) //setup or update
        setExpansionLayoutId(this.expansionLayoutId) //setup or update
        setup()
    }

    private fun setup() {
        if (expansionLayoutInitialised.not()) {
            expansionLayout?.apply {
                addIndicatorListener(object : ExpansionLayout.IndicatorListener {
                    override fun onStartedExpand(expansionLayout: ExpansionLayout, willExpand: Boolean) {
                        onExpansionModifyView(willExpand)
                    }
                })

                this@ExpansionHeader.setOnClickListener { this.toggle(true) }

                headerIndicator?.apply {
                    rotation = (if (isExpanded) headerRotationExpanded else headerRotationCollapsed).toFloat()
                }

                expansionLayoutInitialised = true
            }
        }
    }

    //can be overriden
    protected fun onExpansionModifyView(willExpand: Boolean) {
        isSelected = willExpand
        headerIndicator?.let {
            indicatorAnimator?.cancel()

            indicatorAnimator = if (willExpand) {
                ObjectAnimator.ofFloat(it, View.ROTATION, headerRotationExpanded.toFloat())
            } else {
                ObjectAnimator.ofFloat(it, View.ROTATION, headerRotationCollapsed.toFloat())
            }

            indicatorAnimator?.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    indicatorAnimator = null
                }
            })

            indicatorAnimator?.start()
        }
    }

    fun addListener(listener: ExpansionLayout.Listener) {
        expansionLayout?.addListener(listener)
    }

    fun removeListener(listener: ExpansionLayout.Listener) {
        expansionLayout?.removeListener(listener)
    }

    override fun onSaveInstanceState(): Parcelable? = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState())

        putInt("headerIndicatorId", headerIndicatorId)
        putInt("expansionLayoutId", expansionLayoutId)
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            headerIndicatorId = state.getInt("headerIndicatorId")
            expansionLayoutId = state.getInt("expansionLayoutId")
            //setup(); will wait to onAttachToWindow

            expansionLayoutInitialised = false

            super.onRestoreInstanceState(state.getParcelable("super"))
        } else {
            super.onRestoreInstanceState(state)
        }
    }
}
