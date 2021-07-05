package info.dvkr.screenstream.ui.fragment

import android.util.DisplayMetrics
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import info.dvkr.screenstream.BuildConfig

abstract class AdFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    private var adView: AdView? = null

    fun loadAd(adViewContainer: FrameLayout) {
        MobileAds.initialize(requireContext()) {}
        adView = AdView(requireContext())
        adViewContainer.addView(adView)
        adViewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val outMetrics =
                        DisplayMetrics().also { requireActivity().windowManager.defaultDisplay.getMetrics(it) }
                    var adWidthPixels = adViewContainer.width.toFloat()
                    if (adWidthPixels == 0f) adWidthPixels = outMetrics.widthPixels.toFloat()
                    val adWidth = (adWidthPixels / outMetrics.density).toInt()
                    val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(requireContext(), adWidth)

                    adView!!.adUnitId =
                        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else BuildConfig.AD_UNIT_ID
                    adView!!.adSize = adSize
                    adViewContainer.minimumHeight = adSize.getHeightInPixels(requireContext())
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

    override fun onDestroyView() {
        adView?.destroy()
        super.onDestroyView()
    }
}