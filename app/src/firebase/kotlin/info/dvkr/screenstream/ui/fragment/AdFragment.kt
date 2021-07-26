package info.dvkr.screenstream.ui.fragment

import android.util.DisplayMetrics
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.BuildConfig
import kotlinx.coroutines.delay

abstract class AdFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    private companion object {
        private const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    }

    private var adView: AdView? = null
    private lateinit var adSize: AdSize

    fun loadAdOnViewCreated(adViewContainer: FrameLayout) {
        if (::adSize.isInitialized) loadAd(adViewContainer)
        else adViewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val outMetrics =
                        DisplayMetrics().also { requireActivity().windowManager.defaultDisplay.getMetrics(it) }
                    var adWidthPixels = adViewContainer.width.toFloat()
                    if (adWidthPixels == 0f) adWidthPixels = outMetrics.widthPixels.toFloat()
                    val adWidth = (adWidthPixels / outMetrics.density).toInt()
                    adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(requireContext(), adWidth)

                    adViewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    loadAd(adViewContainer)
                }
            }
        )
    }

    private fun loadAd(adViewContainer: FrameLayout) {
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            delay((requireActivity().application as BaseApp).lastAdLoadTime + 61_000 - System.currentTimeMillis())
            MobileAds.initialize(requireActivity()) {}
            adView = AdView(requireActivity()).also { adView ->
                adViewContainer.addView(adView)
                adViewContainer.minimumHeight = adSize.getHeightInPixels(requireContext())
                adView.adUnitId = if (BuildConfig.DEBUG) TEST_AD_UNIT_ID else BuildConfig.AD_UNIT_ID;
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