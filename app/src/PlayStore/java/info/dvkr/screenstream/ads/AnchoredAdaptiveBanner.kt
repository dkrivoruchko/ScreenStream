package info.dvkr.screenstream.ads

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import info.dvkr.screenstream.ads.AdMob.Availability.READY
import info.dvkr.screenstream.ads.AdMob.Availability.UNAVAILABLE
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
    val unitId: String,
    val size: AdSize,
    private val fitsWidth: State<Boolean>,
) {
    val reservedHeight: Dp = size.height.dp + BannerPadding.calculateTopPadding() + BannerPadding.calculateBottomPadding()
    val canAttach: Boolean get() = adMob.availability == READY && fitsWidth.value
}

@Composable
internal fun rememberAnchoredAdaptiveBannerState(
    paneWidth: Dp,
    activity: Activity? = LocalActivity.current,
): AnchoredAdaptiveBannerState? {
    if (activity == null) return null
    val configuration = LocalConfiguration.current
    // The SDK also bounds the request against its application display metrics.
    val metrics = activity.applicationContext.resources.displayMetrics
    val widthDp = minOf(paneWidth.value.toInt(), (metrics.widthPixels / metrics.density).toInt())
    val adMob = koinInject<AdMob>()
    val unitId = remember(adMob) { adMob.bannerUnitId() }
    if (widthDp <= 0 || unitId == null) return null

    val requestedWidthDp by produceState(initialValue = widthDp, widthDp) {
        if (value != widthDp) {
            delay(750.milliseconds)
            value = widthDp
        }
    }
    val size = remember(activity, requestedWidthDp, configuration) {
        AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, requestedWidthDp)
    }
    val fitsWidth = rememberUpdatedState(widthDp >= requestedWidthDp)
    val state = remember(activity, adMob, unitId, size) {
        AnchoredAdaptiveBannerState(activity, adMob, unitId, size, fitsWidth)
    }
    return if (adMob.availability == UNAVAILABLE) null else state
}

@Composable
internal fun AnchoredAdaptiveBanner(state: AnchoredAdaptiveBannerState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BannerPadding)
            .height(state.size.height.dp),
        contentAlignment = Alignment.Center,
    ) {
        val revision = state.adMob.bannerRevision
        if (state.canAttach) key(state, revision) {
            val activity = state.activity
            val unitId = state.unitId
            val log: Logger = koinInject(parametersHolder = remember(activity) { parametersOf(activity) })
            val released = remember { AtomicBoolean(false) }

            AndroidView(
                factory = {
                    log.d("AnchoredAdaptiveBanner", "factory: $unitId")
                    val view = AdView(activity)
                    if (!state.adMob.canUseBanner(revision)) return@AndroidView view

                    val refreshCallback = object : BannerAdRefreshCallback {
                        override fun onAdFailedToRefresh(adError: LoadAdError) {
                            if (released.get()) return
                            log.w("AnchoredAdaptiveBanner", "onAdFailedToRefresh: $unitId $adError")
                        }
                    }

                    val retainedAd = state.adMob.takeCachedBanner(activity, state.size)
                    if (retainedAd != null) {
                        log.d("AnchoredAdaptiveBanner", "registerRetained: $unitId")
                        retainedAd.bannerAdRefreshCallback = refreshCallback
                        view.registerBannerAd(retainedAd, activity)
                        return@AndroidView view
                    }

                    view.loadAd(
                        BannerAdRequest.Builder(unitId, state.size).build(),
                        object : AdLoadCallback<BannerAd> {
                            override fun onAdLoaded(ad: BannerAd) {
                                activity.runOnUiThread {
                                    if (view.getBannerAd() !== ad) return@runOnUiThread
                                    if (released.get()) {
                                        // The SDK can still attach an already-dispatched success after release.
                                        view.destroy()
                                    } else if (!state.adMob.canUseBanner(revision)) {
                                        view.unregisterBannerAd()?.destroy()
                                    } else {
                                        log.d("AnchoredAdaptiveBanner", "onAdLoaded: $unitId")
                                        ad.bannerAdRefreshCallback = refreshCallback
                                    }
                                }
                            }

                            override fun onAdFailedToLoad(adError: LoadAdError) {
                                activity.runOnUiThread {
                                    if (released.get()) return@runOnUiThread
                                    log.w("AnchoredAdaptiveBanner", "onAdFailedToLoad: $unitId $adError")
                                }
                            }
                        },
                    )
                    view
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    log.d("AnchoredAdaptiveBanner", "onRelease: $unitId, view=${System.identityHashCode(view)}")
                    released.set(true)
                    val ad = view.unregisterBannerAd()
                    ad?.bannerAdRefreshCallback = null
                    val retained = ad != null && state.adMob.retainBanner(activity, state.size, ad, revision)
                    view.destroy()
                    if (!retained) ad?.destroy()
                },
            )
        }
    }
}
