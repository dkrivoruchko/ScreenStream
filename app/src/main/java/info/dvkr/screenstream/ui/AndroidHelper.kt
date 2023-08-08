package info.dvkr.screenstream.ui

import android.view.View
import android.view.ViewGroup

fun View.enableDisableViewWithChildren(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else .5f
    if (this is ViewGroup)
        for (idx in 0 until childCount) getChildAt(idx).enableDisableViewWithChildren(enabled)
}