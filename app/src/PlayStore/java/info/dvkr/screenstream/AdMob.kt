package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elvishew.xlog.XLog
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public class AdMob(private val context: Context) {

    internal enum class Availability { PENDING, READY, UNAVAILABLE }

    private companion object {
        private const val ADMOB_APP_ID_META_DATA_NAME = "com.google.android.gms.ads.APPLICATION_ID"
        private const val TEST_DEVICE_HASHED_ID = "203640674D72D8AD3E73BDFC4AD236B2"
        private val RETAINED_BANNER_TTL = 60.seconds
    }

    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    private val adUnitIds: List<String> by lazy {
        runCatching {
            val jsonArray = JSONArray(BuildConfig.AD_UNIT_IDS)
            List(jsonArray.length()) { index -> jsonArray.getString(index).trim() }.filter { it.isNotBlank() }
        }.getOrElse { cause ->
            XLog.w(getLog("adUnitIds", "Invalid AD_UNIT_IDS: ${cause.message}"))
            emptyList()
        }
    }

    private val retainedBanners: MutableMap<String, RetainedBanner> = mutableMapOf()

    internal var availability: Availability by mutableStateOf(Availability.PENDING)
        private set

    private val isConsentRequestInProgress = AtomicBoolean(false)

    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    private val isDebuggable: Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val requestConfiguration: RequestConfiguration? =
        if (isDebuggable) {
            RequestConfiguration.Builder().setTestDeviceIds(listOf(TEST_DEVICE_HASHED_ID)).build()
        } else {
            null
        }

    private val consentRequestParameters = if (isDebuggable) {
        ConsentRequestParameters.Builder()
            .setConsentDebugSettings(
                ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
                    .addTestDeviceHashedId(TEST_DEVICE_HASHED_ID)
                    .build()
            )
            .build()
    } else {
        ConsentRequestParameters.Builder().build()
    }

    public var isPrivacyOptionsRequired: Boolean by mutableStateOf(consentInformation.privacyOptionsRequirementStatus == REQUIRED)
        private set

    public fun showPrivacyOptionsForm(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                XLog.w(getLog("showPrivacyOptionsForm", "Error: ${formError.errorCode} ${formError.message}"))
            }
            updatePrivacyOptionsRequirementStatus()
        }
    }

    public fun init(activity: Activity) {
        XLog.d(getLog("init", "${activity.hashCode()}"))

        if (availability == Availability.READY) return
        if (isConsentRequestInProgress.compareAndSet(false, true).not()) {
            XLog.d(getLog("init", "Pending consent request. Ignoring"))
            return
        }
        availability = Availability.PENDING

        consentInformation.requestConsentInfoUpdate(
            activity,
            consentRequestParameters,
            {
                updatePrivacyOptionsRequirementStatus()
                if (activity.isFinishing.not() && activity.isDestroyed.not()) {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError -> completeConsentRequest(formError) }
                } else {
                    XLog.w(getLog("init", "Activity is finishing or destroyed. Skipping consent form"))
                    completeConsentRequest()
                }
            },
            { formError -> completeConsentRequest(formError) }
        )
    }

    private fun completeConsentRequest(error: FormError? = null) {
        isConsentRequestInProgress.set(false)
        updatePrivacyOptionsRequirementStatus()
        initializeMobileAds(error)
    }

    private fun updatePrivacyOptionsRequirementStatus() {
        CoroutineScope(Dispatchers.Main).launch {
            isPrivacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus == REQUIRED
        }
    }

    private fun initializeMobileAds(error: FormError? = null) {
        if (availability == Availability.READY) {
            XLog.d(getLog("initializeMobileAds", "Already initialized. Ignoring"))
            return
        }

        if (error != null) {
            XLog.w(getLog("initializeMobileAds", "Error: ${error.errorCode} ${error.message}"))
        }

        if (consentInformation.canRequestAds().not()) {
            availability = Availability.UNAVAILABLE
            return
        }

        XLog.d(getLog("initializeMobileAds"))
        if (isMobileAdsInitializeCalled.compareAndSet(false, true).not()) {
            XLog.d(getLog("initializeMobileAds", "Pending initialization. Ignoring"))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val appId = runCatching {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString(ADMOB_APP_ID_META_DATA_NAME)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            }.getOrElse { cause ->
                XLog.w(getLog("initializeMobileAds", "Failed to read AdMob app id: ${cause.message}"))
                null
            } ?: run {
                XLog.w(getLog("initializeMobileAds", "Missing AdMob app id"))
                isMobileAdsInitializeCalled.set(false)
                CoroutineScope(Dispatchers.Main).launch { availability = Availability.UNAVAILABLE }
                return@launch
            }

            runCatching {
                val initializationConfig = InitializationConfig.Builder(appId)
                    .apply { requestConfiguration?.let(::setRequestConfiguration) }
                    .build()
                MobileAds.initialize(context, initializationConfig) {
                    CoroutineScope(Dispatchers.Main).launch {
                        XLog.d(this@AdMob.getLog("initializeMobileAds", "Done"))
                        availability = Availability.READY
                    }
                }
            }.onFailure { cause ->
                isMobileAdsInitializeCalled.set(false)
                XLog.e(this@AdMob.getLog("initializeMobileAds", "Failed: ${cause.message}"), cause)
                CoroutineScope(Dispatchers.Main).launch { availability = Availability.UNAVAILABLE }
            }
        }
    }

    internal fun getAdUnitId(index: Int, placement: String): String? {
        val ids = adUnitIds
        if (ids.isEmpty()) {
            XLog.w(getLog("getAdUnitId", "Missing ad unit ids"))
            return null
        }
        if (index !in ids.indices) XLog.w(getLog("getAdUnitId", "Missing ad unit id[$index] for $placement. Using first id"))
        return ids.getOrNull(index) ?: ids.first()
    }

    internal fun takeRetainedBanner(activity: Activity, placement: String, adWidth: Int, adSize: AdSize): BannerAd? {
        val now = SystemClock.elapsedRealtime()
        evictExpiredRetainedBanners(now)

        val retainedBanner = retainedBanners.remove(placement) ?: return null
        return if (retainedBanner.activityId == System.identityHashCode(activity) &&
            retainedBanner.adWidth == adWidth &&
            retainedBanner.adSize == adSize &&
            retainedBanner.expiresAtMillis > now
        ) {
            retainedBanner.cleanupJob?.cancel()
            XLog.d(getLog("takeRetainedBanner", placement))
            retainedBanner.bannerAd
        } else {
            retainedBanner.cleanupJob?.cancel()
            retainedBanner.bannerAd.destroy()
            null
        }
    }

    internal fun retainBanner(activity: Activity, placement: String, adWidth: Int, adSize: AdSize, bannerAd: BannerAd): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false

        val now = SystemClock.elapsedRealtime()
        evictExpiredRetainedBanners(now)

        retainedBanners.remove(placement)?.let {
            it.cleanupJob?.cancel()
            it.bannerAd.destroy()
        }

        val retainedBanner =
            RetainedBanner(System.identityHashCode(activity), adWidth, adSize, bannerAd, now + RETAINED_BANNER_TTL.inWholeMilliseconds)
        retainedBanners[placement] = retainedBanner
        retainedBanner.cleanupJob = CoroutineScope(Dispatchers.Main).launch {
            delay(RETAINED_BANNER_TTL)
            if (retainedBanners[placement] === retainedBanner) {
                retainedBanners.remove(placement)
                retainedBanner.bannerAd.destroy()
            }
        }
        XLog.d(getLog("retainBanner", placement))
        return true
    }

    public fun onActivityDestroyed(activity: Activity) {
        val activityId = System.identityHashCode(activity)
        val iterator = retainedBanners.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.activityId == activityId) {
                entry.value.cleanupJob?.cancel()
                entry.value.bannerAd.destroy()
                iterator.remove()
            }
        }
    }

    private fun evictExpiredRetainedBanners(now: Long) {
        val iterator = retainedBanners.iterator()
        while (iterator.hasNext()) {
            val retainedBanner = iterator.next().value
            if (retainedBanner.expiresAtMillis <= now) {
                retainedBanner.cleanupJob?.cancel()
                retainedBanner.bannerAd.destroy()
                iterator.remove()
            }
        }
    }

    private data class RetainedBanner(
        val activityId: Int,
        val adWidth: Int,
        val adSize: AdSize,
        val bannerAd: BannerAd,
        val expiresAtMillis: Long,
        var cleanupJob: Job? = null
    )
}

@Composable
public fun AnchoredAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        logTag = "AnchoredAdaptiveBanner",
        adUnitIndex = 0,
        adSizeProvider = AdSize::getLargeAnchoredAdaptiveBannerAdSize,
        reservedHeightProvider = { it.height },
        visiblePadding = PaddingValues(bottom = 8.dp),
        modifier = modifier
    )
}

@Composable
public fun InlineAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        logTag = "InlineAdaptiveBanner",
        adUnitIndex = 1,
        adSizeProvider = { _, width -> AdSize.getInlineAdaptiveBannerAdSize(width, 60) },
        reservedHeightProvider = { 60 },
        visiblePadding = PaddingValues(vertical = 16.dp),
        modifier = modifier
    )
}

@Composable
private fun AdaptiveBannerContent(
    logTag: String,
    adUnitIndex: Int,
    adSizeProvider: (Context, Int) -> AdSize,
    reservedHeightProvider: (AdSize) -> Int,
    visiblePadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val adMob = koinInject<AdMob>()

    BoxWithConstraints(modifier = modifier) {
        val activity = LocalActivity.current ?: return@BoxWithConstraints
        val measuredAdWidth = maxWidth.value.toInt()
        if (measuredAdWidth <= 0) return@BoxWithConstraints

        val adWidth by produceState(initialValue = measuredAdWidth, measuredAdWidth) {
            if (value != measuredAdWidth) {
                delay(750.milliseconds)
                value = measuredAdWidth
            }
        }
        val adUnitId = remember(adMob, adUnitIndex, logTag) { adMob.getAdUnitId(adUnitIndex, logTag) } ?: return@BoxWithConstraints
        val adSize = remember(activity, adWidth, adSizeProvider) { adSizeProvider(activity, adWidth) }
        val reservedHeight = remember(adSize, reservedHeightProvider) { reservedHeightProvider(adSize) }
        var initialLoadFailed by remember(adUnitId, activity, adWidth, adSize) { mutableStateOf(false) }
        val slotVisible = when (adMob.availability) {
            AdMob.Availability.PENDING -> true
            AdMob.Availability.READY -> initialLoadFailed.not()
            AdMob.Availability.UNAVAILABLE -> false
        }

        if (slotVisible) {
            Box(modifier = Modifier.fillMaxWidth().padding(visiblePadding)) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(reservedHeight.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (adMob.availability == AdMob.Availability.READY && measuredAdWidth >= adWidth) {
                        key(adUnitId, activity, adWidth, adSize) {
                            val retainedBannerAd = remember(adMob, activity, logTag, adWidth, adSize) {
                                adMob.takeRetainedBanner(activity, logTag, adWidth, adSize)
                            }
                            val scope = rememberCoroutineScope()
                            val released = remember { AtomicBoolean(false) }

                            AndroidView(
                                factory = {
                                    XLog.d(getLog(logTag, "factory: $adUnitId"))
                                    val adView = AdView(activity)

                                    fun bindCallbacks(ad: BannerAd) {
                                        ad.adEventCallback = object : BannerAdEventCallback {
                                            override fun onAdImpression() {
                                                if (released.get()) return
                                                XLog.d(getLog(logTag, "onAdImpression: $adUnitId"))
                                            }

                                            override fun onAdClicked() {
                                                if (released.get()) return
                                                XLog.d(getLog(logTag, "onAdClicked: $adUnitId"))
                                            }
                                        }
                                        ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                                            override fun onAdRefreshed() {
                                                if (released.get()) return
                                                XLog.d(getLog(logTag, "onAdRefreshed: $adUnitId"))
                                            }

                                            override fun onAdFailedToRefresh(adError: LoadAdError) {
                                                if (released.get()) return
                                                XLog.w(getLog(logTag, "onAdFailedToRefresh: $adUnitId $adError"))
                                            }
                                        }
                                    }

                                    if (retainedBannerAd != null) {
                                        XLog.d(getLog(logTag, "registerRetained: $adUnitId"))
                                        bindCallbacks(retainedBannerAd)
                                        adView.registerBannerAd(retainedBannerAd, activity)
                                    } else {
                                        adView.loadAd(
                                            BannerAdRequest.Builder(adUnitId, adSize).build(),
                                            object : AdLoadCallback<BannerAd> {
                                                override fun onAdLoaded(ad: BannerAd) {
                                                    if (released.get()) return
                                                    XLog.d(getLog(logTag, "onAdLoaded: $adUnitId"))
                                                    bindCallbacks(ad)
                                                }

                                                override fun onAdFailedToLoad(adError: LoadAdError) {
                                                    if (released.get()) return
                                                    XLog.w(getLog(logTag, "onAdFailedToLoad: $adUnitId $adError"))
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
                                    XLog.d(adView.getLog(logTag, "onRelease: $adUnitId"))
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
