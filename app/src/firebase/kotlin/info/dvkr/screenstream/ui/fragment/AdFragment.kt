package info.dvkr.screenstream.ui.fragment

import android.util.Log
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.ui.activity.AdActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

public abstract class AdFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    private var adView: AdView? = null
    private lateinit var adSize: AdSize
    private val ads: MutableMap<String, Long> by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity().application as BaseApp).lastAdLoadTimeMap.apply {
            putIfAbsent(BuildConfig.AD_UNIT_ID_A, 0)
            putIfAbsent(BuildConfig.AD_UNIT_ID_B, 0)
            putIfAbsent(BuildConfig.AD_UNIT_ID_C, 0)
        }
    }

    public fun loadAdOnViewCreated(adViewContainer: FrameLayout) {
        viewLifecycleOwner.lifecycleScope.launch {
            val showADs = (requireActivity() as AdActivity).adsInitializedDeferred.await()
            when {
                showADs.not() -> Log.i("loadAdOnViewCreated", "showADs: $showADs")
                ::adSize.isInitialized -> loadAd(adViewContainer)
                else -> calculateADsSize(adViewContainer)
            }
        }
    }

    private fun calculateADsSize(adViewContainer: FrameLayout) {
        adViewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    adViewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    var adWidthPixels = adViewContainer.width.toFloat()
                    activity?.let {
                        if (adWidthPixels == 0f) adWidthPixels = WindowMetricsCalculator.getOrCreate()
                            .computeCurrentWindowMetrics(it).bounds.width().toFloat()
                    } ?: return // Not attached to an activity.

                    val adWidth = (adWidthPixels / resources.displayMetrics.density).toInt()
                    adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(requireActivity(), adWidth)
                    if (view != null) loadAd(adViewContainer)
                }
            }
        )
        adViewContainer.requestLayout()
    }

    private fun loadAd(adViewContainer: FrameLayout) {
        adViewContainer.minimumHeight = adSize.getHeightInPixels(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            val currentAd =
                ads.filter { it.value + 61_000 - System.currentTimeMillis() <= 0 }.entries.firstOrNull() ?: ads.minByOrNull { it.value }!!
            while (currentAd.value + 61_000 - System.currentTimeMillis() > 0) delay(100)
            adView = AdView(requireActivity()).also { adView ->
                adView.adUnitId = currentAd.key
                adView.setAdSize(adSize)
                adViewContainer.addView(adView)
                adView.adListener = object : AdListener() {
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        XLog.e(this@AdFragment.getLog("onAdFailedToLoad", adError.toString()), adError.cause)
                    }
                }
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