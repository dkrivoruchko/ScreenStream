package info.dvkr.screenstream.ui.activity

import android.widget.FrameLayout
import androidx.annotation.LayoutRes


abstract class AdActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    fun loadAd(adViewContainer: FrameLayout) {}
}