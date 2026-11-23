package info.dvkr.screenstream.ads

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import info.dvkr.screenstream.BuildConfig
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.koin.android.ext.android.get
import org.koin.core.annotation.Singleton
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Ad retention belongs to this Activity and is independent of Play updates. */
internal fun attachAdMob(activity: ComponentActivity) {
    val adMob = activity.get<AdMob>()
    activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            adMob.onActivityDestroyed(activity)
        }
    })
    adMob.init(activity)
}

@Singleton
public class AdMob(private val context: Context) : KoinComponent {

    private val log: Logger by inject { parametersOf(this) }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            log.w("adUnitIds", "Invalid AD_UNIT_IDS: ${cause.message}")
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

    public fun showPrivacyOptionsForm(activity: Activity, onFailure: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                log.w("showPrivacyOptionsForm", "Error: ${formError.errorCode} ${formError.message}")
            }
            updatePrivacyOptionsRequirementStatus()
            if (formError != null) onFailure()
        }
    }

    public fun init(activity: Activity) {
        log.d("init", "${activity.hashCode()}")

        if (availability == Availability.READY) return
        if (isConsentRequestInProgress.compareAndSet(false, true).not()) {
            log.d("init", "Pending consent request. Ignoring")
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
                    log.w("init", "Activity is finishing or destroyed. Skipping consent form")
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
        scope.launch {
            isPrivacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus == REQUIRED
        }
    }

    private fun initializeMobileAds(error: FormError? = null) {
        if (availability == Availability.READY) {
            log.d("initializeMobileAds", "Already initialized. Ignoring")
            return
        }

        if (error != null) {
            log.w("initializeMobileAds", "Error: ${error.errorCode} ${error.message}")
        }

        if (consentInformation.canRequestAds().not()) {
            availability = Availability.UNAVAILABLE
            return
        }

        log.d("initializeMobileAds")
        if (isMobileAdsInitializeCalled.compareAndSet(false, true).not()) {
            log.d("initializeMobileAds", "Pending initialization. Ignoring")
            return
        }

        scope.launch(Dispatchers.IO) {
            val appId = runCatching {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString(ADMOB_APP_ID_META_DATA_NAME)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            }.getOrElse { cause ->
                log.w("initializeMobileAds", "Failed to read AdMob app id: ${cause.message}")
                null
            } ?: run {
                log.w("initializeMobileAds", "Missing AdMob app id")
                isMobileAdsInitializeCalled.set(false)
                scope.launch { availability = Availability.UNAVAILABLE }
                return@launch
            }

            runCatching {
                val initializationConfig = InitializationConfig.Builder(appId)
                    .apply { requestConfiguration?.let(::setRequestConfiguration) }
                    .build()
                MobileAds.initialize(context, initializationConfig) {
                    scope.launch {
                        log.d("initializeMobileAds", "Done")
                        availability = Availability.READY
                    }
                }
            }.onFailure { cause ->
                isMobileAdsInitializeCalled.set(false)
                log.e("initializeMobileAds", "Failed: ${cause.message}", cause)
                scope.launch { availability = Availability.UNAVAILABLE }
            }
        }
    }

    internal fun getAdUnitId(index: Int, placement: String): String? {
        val ids = adUnitIds
        if (ids.isEmpty()) {
            log.w("getAdUnitId", "Missing ad unit ids")
            return null
        }
        if (index !in ids.indices) log.w("getAdUnitId", "Missing ad unit id[$index] for $placement. Using first id")
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
            log.d("takeRetainedBanner", placement)
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
        retainedBanner.cleanupJob = scope.launch {
            delay(RETAINED_BANNER_TTL)
            if (retainedBanners[placement] === retainedBanner) {
                retainedBanners.remove(placement)
                retainedBanner.bannerAd.destroy()
            }
        }
        log.d("retainBanner", placement)
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
