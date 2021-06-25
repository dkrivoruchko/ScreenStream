package info.dvkr.screenstream.ui.activity

import android.util.DisplayMetrics
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.google.android.gms.ads.*
import info.dvkr.screenstream.BuildConfig


abstract class AdActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    private var adView: AdView? = null

    fun loadAd(adViewContainer: FrameLayout) {
        MobileAds.initialize(this) {}
        adView = AdView(this)
        adViewContainer.addView(adView)
        adViewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val outMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
                    var adWidthPixels = adViewContainer.width.toFloat()
                    if (adWidthPixels == 0f) adWidthPixels = outMetrics.widthPixels.toFloat()
                    val adWidth = (adWidthPixels / outMetrics.density).toInt()
                    val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this@AdActivity, adWidth)

                    adView!!.adUnitId = if (BuildConfig.DEBUG) BuildConfig.AD_UNIT_ID_TEST else BuildConfig.AD_UNIT_ID
                    adView!!.adSize = adSize
                    adViewContainer.minimumHeight = adSize.getHeightInPixels(this@AdActivity)
                    adView!!.loadAd(AdRequest.Builder().build())

                    adViewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        )
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
    }

    override fun onDestroy() {
        adView?.destroy()
        super.onDestroy()
    }
}