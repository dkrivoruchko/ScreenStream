package info.dvkr.screenstream.ads

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

private val BannerPadding = PaddingValues(top = 12.dp, bottom = 8.dp)

internal class AnchoredAdaptiveBannerState(
    val activity: Activity,
    val adMob: AdMob,
    val adUnitId: String,
    val adSize: AdSize,
    private val fitsAvailableWidth: State<Boolean>,
) {
    var initialLoadFailed by mutableStateOf(false)
    val reservedHeight: Dp = adSize.height.dp + BannerPadding.calculateTopPadding() + BannerPadding.calculateBottomPadding()
    val canAttachAd: Boolean get() = adMob.availability == AdMob.Availability.READY && fitsAvailableWidth.value
}

@Composable
internal fun rememberAnchoredAdaptiveBannerState(paneWidth: Dp): AnchoredAdaptiveBannerState? {
    val activity = LocalActivity.current ?: return null
    val configuration = LocalConfiguration.current
    // The SDK also bounds the request against its application display metrics.
    val displayMetrics = activity.applicationContext.resources.displayMetrics
    val displayWidthDp = (displayMetrics.widthPixels / displayMetrics.density).toInt()
    val availableWidthDp = minOf(paneWidth.value.toInt(), displayWidthDp)
    val adMob = koinInject<AdMob>()
    val adUnitId = remember(adMob) { adMob.getAdUnitId(0, "AnchoredAdaptiveBanner") }
    if (availableWidthDp <= 0 || adUnitId == null) return null

    val requestedWidthDp by produceState(initialValue = availableWidthDp, availableWidthDp) {
        if (value != availableWidthDp) {
            delay(750.milliseconds)
            value = availableWidthDp
        }
    }
    val adSize = remember(activity, requestedWidthDp, configuration) {
        AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, requestedWidthDp)
    }
    val fitsAvailableWidth = rememberUpdatedState(availableWidthDp >= requestedWidthDp)
    val state = remember(activity, adMob, adUnitId, adSize) {
        AnchoredAdaptiveBannerState(activity, adMob, adUnitId, adSize, fitsAvailableWidth)
    }
    return when (adMob.availability) {
        AdMob.Availability.PENDING -> state
        AdMob.Availability.READY -> state.takeUnless { it.initialLoadFailed }
        AdMob.Availability.UNAVAILABLE -> null
    }
}

@Composable
internal fun AnchoredAdaptiveBanner(state: AnchoredAdaptiveBannerState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BannerPadding)
            .height(state.adSize.height.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.canAttachAd) key(state) {
            val adUnitId = state.adUnitId
            val log: Logger = koinInject(parametersHolder = remember(state.activity) { parametersOf(state.activity) })
            val released = remember { AtomicBoolean(false) }

            AndroidView(
                factory = {
                    log.d("AnchoredAdaptiveBanner", "factory: $adUnitId")
                    val adView = AdView(state.activity)

                    fun bindCallbacks(ad: BannerAd) {
                        ad.adEventCallback = object : BannerAdEventCallback {
                            override fun onAdImpression() {
                                if (released.get()) return
                                log.d("AnchoredAdaptiveBanner", "onAdImpression: $adUnitId")
                            }

                            override fun onAdClicked() {
                                if (released.get()) return
                                log.d("AnchoredAdaptiveBanner", "onAdClicked: $adUnitId")
                            }
                        }
                        ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                            override fun onAdRefreshed() {
                                if (released.get()) return
                                log.d("AnchoredAdaptiveBanner", "onAdRefreshed: $adUnitId")
                            }

                            override fun onAdFailedToRefresh(adError: LoadAdError) {
                                if (released.get()) return
                                log.w("AnchoredAdaptiveBanner", "onAdFailedToRefresh: $adUnitId $adError")
                            }
                        }
                    }

                    val retainedAd = state.adMob.takeRetainedBanner(
                        state.activity, "AnchoredAdaptiveBanner", state.adSize.width, state.adSize,
                    )
                    if (retainedAd != null) {
                        log.d("AnchoredAdaptiveBanner", "registerRetained: $adUnitId")
                        bindCallbacks(retainedAd)
                        adView.registerBannerAd(retainedAd, state.activity)
                    } else {
                        adView.loadAd(
                            BannerAdRequest.Builder(adUnitId, state.adSize).build(),
                            object : AdLoadCallback<BannerAd> {
                                override fun onAdLoaded(ad: BannerAd) {
                                    state.activity.runOnUiThread {
                                        if (adView.getBannerAd() !== ad) return@runOnUiThread
                                        if (released.get()) {
                                            // The SDK can still attach an already-dispatched success after release.
                                            adView.destroy()
                                        } else {
                                            log.d("AnchoredAdaptiveBanner", "onAdLoaded: $adUnitId")
                                            bindCallbacks(ad)
                                        }
                                    }
                                }

                                override fun onAdFailedToLoad(adError: LoadAdError) {
                                    state.activity.runOnUiThread {
                                        if (released.get()) return@runOnUiThread
                                        log.w("AnchoredAdaptiveBanner", "onAdFailedToLoad: $adUnitId $adError")
                                        if (adView.getBannerAd() == null) {
                                            state.initialLoadFailed = true
                                        }
                                    }
                                }
                            },
                        )
                    }

                    adView
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { adView ->
                    log.d("AnchoredAdaptiveBanner", "onRelease: $adUnitId, view=${System.identityHashCode(adView)}")
                    released.set(true)
                    val bannerAd = adView.unregisterBannerAd()
                    bannerAd?.adEventCallback = null
                    bannerAd?.bannerAdRefreshCallback = null
                    val retained = bannerAd != null && state.adMob.retainBanner(
                        state.activity, "AnchoredAdaptiveBanner", state.adSize.width, state.adSize, bannerAd,
                    )
                    adView.destroy()
                    if (retained.not()) bannerAd?.destroy()
                },
            )
        }
    }
}
