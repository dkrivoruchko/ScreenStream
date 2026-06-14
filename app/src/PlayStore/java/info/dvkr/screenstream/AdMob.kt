package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public class AdMob(private val context: Context) {

    private companion object {
        private const val ADMOB_APP_ID_META_DATA_NAME = "com.google.android.gms.ads.APPLICATION_ID"
        private const val TEST_DEVICE_HASHED_ID = "203640674D72D8AD3E73BDFC4AD236B2"
        private val AD_UNIT_REQUEST_INTERVAL = 62.seconds
    }

    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    private val adUnitPool: AdUnitPool by lazy { AdUnitPool(BuildConfig.AD_UNIT_IDS, AD_UNIT_REQUEST_INTERVAL) }

    public var initialized: Boolean by mutableStateOf(false)
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

        if (initialized) return
        if (isConsentRequestInProgress.compareAndSet(false, true).not()) {
            XLog.d(getLog("init", "Pending consent request. Ignoring"))
            initializeMobileAds()
            return
        }

        consentInformation.requestConsentInfoUpdate(
            activity,
            consentRequestParameters,
            {
                updatePrivacyOptionsRequirementStatus()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    isConsentRequestInProgress.set(false)
                    updatePrivacyOptionsRequirementStatus()
                    initializeMobileAds(formError)
                }
            },
            { formError ->
                isConsentRequestInProgress.set(false)
                updatePrivacyOptionsRequirementStatus()
                initializeMobileAds(formError)
            }
        )

        initializeMobileAds()
    }

    private fun updatePrivacyOptionsRequirementStatus() {
        CoroutineScope(Dispatchers.Main).launch {
            isPrivacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus == REQUIRED
        }
    }

    private fun initializeMobileAds(error: FormError? = null) {
        if (initialized) {
            XLog.d(getLog("initializeMobileAds", "Already initialized. Ignoring"))
            return
        }

        if (error != null) {
            XLog.w(getLog("initializeMobileAds", "Error: ${error.errorCode} ${error.message}"))
        }

        if (consentInformation.canRequestAds().not()) return

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
                return@launch
            }

            runCatching {
                val initializationConfig = InitializationConfig.Builder(appId)
                    .apply { requestConfiguration?.let(::setRequestConfiguration) }
                    .build()
                MobileAds.initialize(context, initializationConfig) {
                    CoroutineScope(Dispatchers.Main).launch {
                        XLog.d(this@AdMob.getLog("initializeMobileAds", "Done"))
                        initialized = true
                    }
                }
            }.onFailure { cause ->
                isMobileAdsInitializeCalled.set(false)
                XLog.e(this@AdMob.getLog("initializeMobileAds", "Failed: ${cause.message}"), cause)
            }
        }
    }

    internal suspend fun acquireAdUnitId(): String? = adUnitPool.acquire()

    internal suspend fun releaseAdUnit(adUnitId: String) = adUnitPool.release(adUnitId)

    private class AdUnitPool(adUnitIds: String, requestInterval: Duration) {
        private data class State(val id: String, var inUseCount: Int = 0, var lastRequestAtMillis: Long)
        private sealed interface AcquireResult {
            data class Acquired(val id: String) : AcquireResult
            data class Wait(val duration: Duration) : AcquireResult
            data object MissingIds : AcquireResult
        }

        private val mutex = Mutex()
        private val requestIntervalMillis = requestInterval.inWholeMilliseconds
        private val comparator = compareBy<State> { it.inUseCount }.thenBy { it.lastRequestAtMillis }
        private val states = parseAdUnitIds(adUnitIds).map { State(id = it, lastRequestAtMillis = -requestIntervalMillis) }

        suspend fun acquire(): String? {
            while (true) {
                when (val result = mutex.withLock { tryAcquire(SystemClock.elapsedRealtime()) }) {
                    is AcquireResult.Acquired -> return result.id
                    is AcquireResult.Wait -> {
                        XLog.d(getLog("AdUnitPool.acquire", "Waiting ${result.duration.inWholeMilliseconds}ms"))
                        delay(result.duration)
                    }

                    AcquireResult.MissingIds -> {
                        XLog.w(getLog("AdUnitPool.acquire", "Missing ad unit ids"))
                        return null
                    }
                }
            }
        }

        suspend fun release(adUnitId: String) {
            mutex.withLock {
                states.firstOrNull { it.id == adUnitId }?.let { state ->
                    state.inUseCount = (state.inUseCount - 1).coerceAtLeast(0)
                    XLog.d(getLog("AdUnitPool.release", state.toString()))
                } ?: XLog.w(getLog("AdUnitPool.release", "Unknown ad unit id: $adUnitId"))
            }
        }

        private fun tryAcquire(now: Long): AcquireResult {
            val state = states
                .asSequence()
                .filter { now - it.lastRequestAtMillis >= requestIntervalMillis }
                .minWithOrNull(comparator)

            if (state != null) {
                state.inUseCount += 1
                state.lastRequestAtMillis = now
                XLog.d(getLog("AdUnitPool.acquire", state.toString()))
                return AcquireResult.Acquired(state.id)
            }

            return states
                .minOfOrNull { requestIntervalMillis - (now - it.lastRequestAtMillis) }
                ?.coerceAtLeast(1L)
                ?.milliseconds
                ?.let(AcquireResult::Wait)
                ?: AcquireResult.MissingIds
        }

        private fun parseAdUnitIds(adUnitIds: String): List<String> =
            runCatching {
                val jsonArray = JSONArray(adUnitIds)
                List(jsonArray.length()) { index -> jsonArray.getString(index).trim() }.filter { it.isNotBlank() }
            }.getOrElse { cause ->
                XLog.w(getLog("AdUnitPool", "Invalid AD_UNIT_IDS: ${cause.message}"))
                emptyList()
            }
    }
}

@Composable
public fun AnchoredAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        logTag = "AnchoredAdaptiveBanner",
        adSizeProvider = AdSize::getLargeAnchoredAdaptiveBannerAdSize,
        modifier = modifier
    )
}

@Composable
public fun InlineAdaptiveBanner(modifier: Modifier = Modifier) {
    AdaptiveBannerContent(
        logTag = "InlineAdaptiveBanner",
        adSizeProvider = AdSize::getCurrentOrientationInlineAdaptiveBannerAdSize,
        modifier = modifier
    )
}

@Composable
private fun AdaptiveBannerContent(
    logTag: String,
    adSizeProvider: (Context, Int) -> AdSize,
    modifier: Modifier = Modifier
) {
    val adMob = koinInject<AdMob>()

    if (adMob.initialized) {
        BoxWithConstraints(modifier = modifier) {
            val activity = LocalActivity.current ?: return@BoxWithConstraints
            val adWidth = maxWidth.value.toInt()
            if (adWidth <= 0) return@BoxWithConstraints

            val adSize = remember(activity, adWidth, adSizeProvider) { adSizeProvider(activity, adWidth) }
            val adUnitId = rememberAdUnitId(adMob, activity, adWidth) ?: return@BoxWithConstraints

            AdBox(adUnitId, adWidth, adSize, activity, logTag)
        }
    }
}

@Composable
private fun rememberAdUnitId(adMob: AdMob, activity: Activity, adWidth: Int): String? =
    produceState<String?>(initialValue = null, adMob, activity, adWidth) {
        val adUnitId = adMob.acquireAdUnitId() ?: return@produceState
        value = adUnitId

        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                adMob.releaseAdUnit(adUnitId)
            }
        }
    }.value

@Composable
private fun AdBox(adUnitId: String, adWidth: Int, adSize: AdSize, activity: Activity, logTag: String) {
    var isSlotVisible by remember(adUnitId, activity, adWidth) { mutableStateOf(false) }
    var slotHeight by remember(adUnitId, activity, adWidth) { mutableIntStateOf(adSize.height) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (isSlotVisible) slotHeight.dp else 0.dp)
    ) {
        key(adUnitId, activity, adWidth) {
            val scope = rememberCoroutineScope()
            val released = remember { AtomicBoolean(false) }

            AndroidView(
                factory = {
                    XLog.d(getLog(logTag, "factory: $adUnitId"))
                    val adView = AdView(activity)
                    val adRequest = BannerAdRequest.Builder(adUnitId, adSize).build()
                    adView.loadAd(
                        adRequest,
                        object : AdLoadCallback<BannerAd> {
                            override fun onAdLoaded(ad: BannerAd) {
                                if (released.get()) return
                                XLog.d(getLog(logTag, "onAdLoaded: $adUnitId"))
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
                                        scope.launch {
                                            if (released.get().not()) {
                                                slotHeight = adView.getBannerAd()?.getAdSize()?.height ?: adSize.height
                                                isSlotVisible = true
                                            }
                                        }
                                    }

                                    override fun onAdFailedToRefresh(adError: LoadAdError) {
                                        if (released.get()) return
                                        XLog.w(getLog(logTag, "onAdFailedToRefresh: $adUnitId $adError"))
                                    }
                                }
                                scope.launch {
                                    if (released.get().not()) {
                                        slotHeight = ad.getAdSize().height
                                        isSlotVisible = true
                                    }
                                }
                            }

                            override fun onAdFailedToLoad(adError: LoadAdError) {
                                if (released.get()) return
                                XLog.w(getLog(logTag, "onAdFailedToLoad: $adUnitId $adError"))
                                scope.launch {
                                    if (released.get().not() && adView.getBannerAd() == null) isSlotVisible = false
                                }
                            }
                        },
                    )
                    adView
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { adView ->
                    XLog.d(adView.getLog(logTag, "onRelease: $adUnitId"))
                    released.set(true)
                    adView.destroy()
                },
            )
        }
    }
}
