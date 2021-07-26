package info.dvkr.screenstream.ui.fragment

import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment


abstract class AdFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    fun loadAdOnViewCreated(adViewContainer: FrameLayout) {
        adViewContainer.visibility = View.GONE
    }
}