package info.dvkr.screenstream.ads

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun AnchoredAdaptiveBanner(minimumRemainingHeight: Dp, modifier: Modifier = Modifier) {
    val logTag = "AnchoredAdaptiveBanner"
    val visiblePadding = PaddingValues(top = 12.dp, bottom = 8.dp)
    val adMob = koinInject<AdMob>()

    BoxWithConstraints(modifier = modifier) {
        val activity = LocalActivity.current ?: return@BoxWithConstraints
        val log: Logger = koinInject(parametersHolder = remember(activity) { parametersOf(activity) })
        val measuredAdWidth = maxWidth.value.toInt()
        if (measuredAdWidth <= 0) return@BoxWithConstraints

        val adWidth by produceState(initialValue = measuredAdWidth, measuredAdWidth) {
            if (value != measuredAdWidth) {
                delay(750.milliseconds)
                value = measuredAdWidth
            }
        }
        val adUnitId = remember(adMob) { adMob.getAdUnitId(0, logTag) } ?: return@BoxWithConstraints
        val adSize = remember(activity, adWidth) { AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, adWidth) }
        val reservedHeight = adSize.height.dp
        val maxBannerHeight = if (minimumRemainingHeight > 0.dp && constraints.hasBoundedHeight) {
            // Keep the fit check and the layout bound on the same whole-pixel budget.
            with(LocalDensity.current) { (maxHeight - minimumRemainingHeight).coerceAtLeast(0.dp).roundToPx().toDp() }
        } else {
            maxHeight
        }
        if (reservedHeight + visiblePadding.calculateTopPadding() + visiblePadding.calculateBottomPadding() > maxBannerHeight) {
            return@BoxWithConstraints
        }
        var initialLoadFailed by remember(adUnitId, activity, adWidth, adSize) { mutableStateOf(false) }
        val slotVisible = when (adMob.availability) {
            AdMob.Availability.PENDING -> true
            AdMob.Availability.READY -> initialLoadFailed.not()
            AdMob.Availability.UNAVAILABLE -> false
        }

        if (slotVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBannerHeight)
                    .padding(visiblePadding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(reservedHeight),
                    contentAlignment = Alignment.Center
                ) {
                    if (adMob.availability == AdMob.Availability.READY && measuredAdWidth >= adWidth) {
                        key(adUnitId, activity, adWidth, adSize) {
                            val retainedBannerAd = remember(adMob, activity, adWidth, adSize) {
                                adMob.takeRetainedBanner(activity, logTag, adWidth, adSize)
                            }
                            val scope = rememberCoroutineScope()
                            val released = remember { AtomicBoolean(false) }

                            AndroidView(
                                factory = {
                                    log.d(logTag, "factory: $adUnitId")
                                    val adView = AdView(activity)

                                    fun bindCallbacks(ad: BannerAd) {
                                        ad.adEventCallback = object : BannerAdEventCallback {
                                            override fun onAdImpression() {
                                                if (released.get()) return
                                                log.d(logTag, "onAdImpression: $adUnitId")
                                            }

                                            override fun onAdClicked() {
                                                if (released.get()) return
                                                log.d(logTag, "onAdClicked: $adUnitId")
                                            }
                                        }
                                        ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                                            override fun onAdRefreshed() {
                                                if (released.get()) return
                                                log.d(logTag, "onAdRefreshed: $adUnitId")
                                            }

                                            override fun onAdFailedToRefresh(adError: LoadAdError) {
                                                if (released.get()) return
                                                log.w(logTag, "onAdFailedToRefresh: $adUnitId $adError")
                                            }
                                        }
                                    }

                                    if (retainedBannerAd != null) {
                                        log.d(logTag, "registerRetained: $adUnitId")
                                        bindCallbacks(retainedBannerAd)
                                        adView.registerBannerAd(retainedBannerAd, activity)
                                    } else {
                                        adView.loadAd(
                                            BannerAdRequest.Builder(adUnitId, adSize).build(),
                                            object : AdLoadCallback<BannerAd> {
                                                override fun onAdLoaded(ad: BannerAd) {
                                                    if (released.get()) return
                                                    log.d(logTag, "onAdLoaded: $adUnitId")
                                                    bindCallbacks(ad)
                                                }

                                                override fun onAdFailedToLoad(adError: LoadAdError) {
                                                    if (released.get()) return
                                                    log.w(logTag, "onAdFailedToLoad: $adUnitId $adError")
                                                    scope.launch {
                                                        if (released.get().not() && adView.getBannerAd() == null) {
                                                            initialLoadFailed = true
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
                                    log.d(logTag, "onRelease: $adUnitId, view=${System.identityHashCode(adView)}")
                                    released.set(true)
                                    val bannerAd = adView.unregisterBannerAd()
                                    val retained = bannerAd != null &&
                                            adMob.retainBanner(activity, logTag, adWidth, adSize, bannerAd)
                                    adView.destroy()
                                    if (retained.not()) bannerAd?.destroy()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
