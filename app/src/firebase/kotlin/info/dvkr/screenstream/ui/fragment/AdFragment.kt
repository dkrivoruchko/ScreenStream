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
    private val ads: MutableMap<String, Long> by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as BaseApp).lastAdLoadTimeMap.apply {
            putIfAbsent(BuildConfig.AD_UNIT_ID_A, 0)
            putIfAbsent(BuildConfig.AD_UNIT_ID_B, 0)
            putIfAbsent(BuildConfig.AD_UNIT_ID_C, 0)
        }
    }

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
            val currentAd = ads.filter { it.value + 61_000 - System.currentTimeMillis() <= 0 }.entries.firstOrNull()
                ?: ads.minByOrNull { it.value }!!
            while (currentAd.value + 61_000 - System.currentTimeMillis() > 0) delay(100)
            MobileAds.initialize(requireActivity()) {}
            adView = AdView(requireActivity()).also { adView ->
                adViewContainer.addView(adView)
                adView.adUnitId = currentAd.key
                adView.setAdSize(adSize)
                adView.loadAd(AdRequest.Builder().build())
                ads.replace(currentAd.key, System.currentTimeMillis())
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