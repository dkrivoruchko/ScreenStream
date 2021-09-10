package info.dvkr.screenstream.ui.fragment

import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.BuildConfig
import kotlinx.coroutines.delay

abstract class AdFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    private var adView: AdView? = null
    private lateinit var adSize: AdSize

    fun loadAdOnViewCreated(adViewContainer: FrameLayout) {
        if (::adSize.isInitialized) loadAd(adViewContainer)
        else adViewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    var adWidthPixels = adViewContainer.width.toFloat()
                    if (adWidthPixels == 0f) adWidthPixels = WindowMetricsCalculator.getOrCreate()
                        .computeCurrentWindowMetrics(requireActivity()).bounds.width().toFloat()
                    val adWidth = (adWidthPixels / resources.displayMetrics.density).toInt()
                    adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(requireContext(), adWidth)

                    adViewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    loadAd(adViewContainer)
                }
            }
        )
    }

    private fun loadAd(adViewContainer: FrameLayout) {
        adViewContainer.minimumHeight = adSize.getHeightInPixels(requireActivity())
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            delay((requireActivity().application as BaseApp).lastAdLoadTime + 61_000 - System.currentTimeMillis())
            MobileAds.initialize(requireActivity()) {}
            adView = AdView(requireActivity()).also { adView ->
                adViewContainer.addView(adView)
                adView.adUnitId = BuildConfig.AD_UNIT_ID
                adView.adSize = adSize
                adView.loadAd(AdRequest.Builder().build())
                (requireActivity().application as BaseApp).lastAdLoadTime = System.currentTimeMillis()
            }
        }
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
        adView = null
        super.onDestroyView()
    }
}