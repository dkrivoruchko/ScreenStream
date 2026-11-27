package info.dvkr.screenstream.ads

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.WindowManager.BadTokenException
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnAttach
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.Lifecycle.Event.ON_PAUSE
import androidx.lifecycle.Lifecycle.Event.ON_RESUME
import androidx.lifecycle.Lifecycle.State.DESTROYED
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.koin.core.annotation.Singleton
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.seconds

@Singleton
internal class AdMob(private val context: Context) : KoinComponent {

    private val log: Logger by inject { parametersOf(this) }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class Availability { PENDING, READY, UNAVAILABLE }

    private enum class ConsentPhase { IDLE, STARTUP, PRIVACY_OPTIONS }

    private enum class SdkState { IDLE, STARTING, READY }

    private companion object {
        private const val APP_ID_METADATA_KEY = "com.google.android.gms.ads.APPLICATION_ID"
        private const val TEST_DEVICE_HASH = "203640674D72D8AD3E73BDFC4AD236B2"
        private const val BANNER_PLACEMENT = "AnchoredAdaptiveBanner"
        private val BANNER_TTL = 60.seconds
    }

    private val consent = UserMessagingPlatform.getConsentInformation(context)

    private val configuredUnitId: String? by lazy {
        runCatching {
            val ids = JSONArray(BuildConfig.AD_UNIT_IDS)
            List(ids.length()) { ids.getString(it).trim() }.firstOrNull(String::isNotBlank)
        }.getOrElse { cause ->
            log.w("adUnitIds", "Invalid AD_UNIT_IDS: ${cause.message}")
            null
        }
    }

    private var cachedBanner: CachedBanner? = null

    var availability: Availability by mutableStateOf(Availability.PENDING)
        private set

    var bannerRevision: Int by mutableIntStateOf(0)
        private set

    // Lifecycle, consent, and banner state are owned by the main thread.
    private var consentInfoUpdatePending = true
    private var consentPhase = ConsentPhase.IDLE
    private var pendingForm: ConsentForm? = null
    private var formToken: Any? = null
    private var startupFormHost: ComponentActivity? = null
    private val resumedHosts = mutableListOf<ComponentActivity>()
    private var sdkState = SdkState.IDLE
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val requestConfiguration = if (debuggable) RequestConfiguration.Builder().setTestDeviceIds(listOf(TEST_DEVICE_HASH)).build() else null
    private val consentParameters = ConsentRequestParameters.Builder().apply {
        if (debuggable) {
            setConsentDebugSettings(
                ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
                    .addTestDeviceHashedId(TEST_DEVICE_HASH)
                    .build()
            )
        }
    }.build()

    var isPrivacyOptionsRequired: Boolean by mutableStateOf(consent.privacyOptionsRequirementStatus == REQUIRED)
        private set

    @MainThread
    fun beginAppLaunch() {
        consentInfoUpdatePending = true
        if (pendingForm != null) {
            pendingForm = null
            consentPhase = ConsentPhase.IDLE
        }
        publishAvailability()
    }

    @MainThread
    fun showPrivacyOptionsForm(activity: Activity, onFailure: () -> Unit) {
        if (consentInfoUpdatePending || sdkState == SdkState.STARTING || consentPhase != ConsentPhase.IDLE || activity.isFinishing || activity.isDestroyed) {
            onFailure()
            return
        }

        consentPhase = ConsentPhase.PRIVACY_OPTIONS
        showForm(onFailure) { listener -> UserMessagingPlatform.showPrivacyOptionsForm(activity, listener) }
    }

    @MainThread
    fun attach(activity: ComponentActivity) {
        log.d("init", "${activity.hashCode()}")
        // Adding the observer can immediately deliver ON_RESUME.
        requestConsentInfoUpdate(activity)
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                ON_RESUME -> {
                    resumedHosts.removeAll { it === activity }
                    resumedHosts.add(activity)
                    advanceConsent()
                }

                ON_PAUSE, ON_DESTROY -> {
                    resumedHosts.removeAll { it === activity }
                    if (event == ON_DESTROY) onHostDestroyed(activity)
                }

                else -> Unit
            }
        })
        activity.window.decorView.doOnAttach { advanceConsent() }
    }

    private fun requestConsentInfoUpdate(activity: Activity) {
        if (!consentInfoUpdatePending || activity.isFinishing || activity.isDestroyed) return
        if (consentPhase != ConsentPhase.IDLE || sdkState == SdkState.STARTING) {
            log.d("init", "Pending consent request. Ignoring")
            return
        }

        consentInfoUpdatePending = false
        consentPhase = ConsentPhase.STARTUP
        publishAvailability()

        consent.requestConsentInfoUpdate(
            activity,
            consentParameters,
            {
                scope.launch(Dispatchers.Main.immediate) {
                    if (activity.isFinishing || activity.isDestroyed) {
                        log.w("init", "Activity is finishing or destroyed. Skipping consent form")
                    }
                    loadFormIfRequired()
                }
            },
            { error -> scope.launch(Dispatchers.Main.immediate) { completeConsent(error) } },
        )
        updatePrivacyOptionsRequirement()
    }

    private fun loadFormIfRequired() {
        if (consentInfoUpdatePending || consent.canRequestAds() || !consent.isConsentFormAvailable) {
            completeConsent()
            return
        }

        updatePrivacyOptionsRequirement()
        UserMessagingPlatform.loadConsentForm(
            context.applicationContext,
            { form ->
                scope.launch(Dispatchers.Main.immediate) {
                    if (consentInfoUpdatePending || consent.canRequestAds()) {
                        completeConsent()
                    } else {
                        pendingForm = form
                        advanceConsent()
                    }
                }
            },
            { error -> scope.launch(Dispatchers.Main.immediate) { completeConsent(error) } },
        )
    }

    private fun advanceConsent() {
        if (consentInfoUpdatePending) {
            currentResumedHost()?.let(::requestConsentInfoUpdate)
            return
        }
        val form = pendingForm ?: return
        val host = currentResumedHost() ?: return
        if (consent.canRequestAds()) {
            completeConsent()
            return
        }

        pendingForm = null
        startupFormHost = host
        showForm { listener -> form.show(host, listener) }
    }

    private fun currentResumedHost(): ComponentActivity? = resumedHosts.lastOrNull {
        it.lifecycle.currentState == RESUMED && !it.isFinishing && !it.isDestroyed && it.window.decorView.isAttachedToWindow
    }

    private fun onHostDestroyed(activity: ComponentActivity) {
        if (cachedBanner?.activityIdentityHash == System.identityHashCode(activity)) removeCachedBanner()?.ad?.destroy()
        if (startupFormHost !== activity) return
        startupFormHost = null
        formToken = null
        // Keep startup active until UMP has dismissed the old dialog in the destroy dispatch.
        scope.launch { loadFormIfRequired() }
    }

    private fun showForm(
        onPrivacyFailure: (() -> Unit)? = null,
        show: (ConsentForm.OnConsentFormDismissedListener) -> Unit,
    ) {
        val token = Any()
        formToken = token
        try {
            show(ConsentForm.OnConsentFormDismissedListener { formError ->
                scope.launch {
                    if (formToken !== token) return@launch
                    if (formError != null && onPrivacyFailure != null) {
                        log.w("showPrivacyOptionsForm", "Error: ${formError.errorCode} ${formError.message}")
                        completeConsent()
                        onPrivacyFailure()
                    } else completeConsent(formError)
                }
            })
        } catch (_: BadTokenException) {
            completeConsent()
            onPrivacyFailure?.invoke()
        }
    }

    private fun completeConsent(error: FormError? = null) {
        if (error != null) log.w("initializeMobileAds", "Error: ${error.errorCode} ${error.message}")
        consentPhase = ConsentPhase.IDLE
        pendingForm = null
        startupFormHost = null
        formToken = null
        updatePrivacyOptionsRequirement()
        initializeMobileAds()
        advanceConsent()
        publishAvailability()
    }

    private fun updatePrivacyOptionsRequirement() {
        isPrivacyOptionsRequired = consent.privacyOptionsRequirementStatus == REQUIRED
    }

    // Opening Privacy options does not invalidate an eligible creative. Reconcile on dismissal.
    private fun awaitingConsent(): Boolean = consentInfoUpdatePending || consentPhase == ConsentPhase.STARTUP

    @MainThread
    fun canUseBanner(revision: Int): Boolean =
        revision == bannerRevision && availability == Availability.READY && !awaitingConsent() && consent.canRequestAds()

    private fun publishAvailability() {
        val next = when {
            awaitingConsent() -> Availability.PENDING
            !consent.canRequestAds() || sdkState == SdkState.IDLE -> Availability.UNAVAILABLE
            sdkState == SdkState.READY -> Availability.READY
            else -> Availability.PENDING
        }
        // Compose can observe only the reopened gate if it changes twice in one frame.
        if (availability == Availability.READY && next != Availability.READY) bannerRevision++
        availability = next
        if (next != Availability.READY) removeCachedBanner()?.ad?.destroy()
    }

    private fun initializeMobileAds() {
        if (consentInfoUpdatePending || !consent.canRequestAds()) return
        if (sdkState == SdkState.READY) {
            log.d("initializeMobileAds", "Already initialized. Ignoring")
            return
        }
        log.d("initializeMobileAds")
        if (sdkState == SdkState.STARTING) {
            log.d("initializeMobileAds", "Pending initialization. Ignoring")
            return
        }

        // Initialization can preload ads. Queue a new UMP turn until the SDK call returns.
        sdkState = SdkState.STARTING
        scope.launch {
            val initialized = runCatching { withContext(Dispatchers.IO) { initializeSdk() } }
                .onFailure { cause -> log.e("initializeMobileAds", "Failed: ${cause.message}", cause) }
                .getOrDefault(false)
            sdkState = if (initialized) SdkState.READY else SdkState.IDLE
            if (initialized) log.d("initializeMobileAds", "Done")
            advanceConsent()
            publishAvailability()
        }
    }

    private fun initializeSdk(): Boolean {
        val appId = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(APP_ID_METADATA_KEY)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        }.onFailure { cause ->
            log.w("initializeMobileAds", "Failed to read AdMob app id: ${cause.message}")
        }.getOrNull() ?: run {
            log.w("initializeMobileAds", "Missing AdMob app id")
            return false
        }
        val configuration = InitializationConfig.Builder(appId)
            .apply { requestConfiguration?.let(::setRequestConfiguration) }
            .build()
        if (!consent.canRequestAds()) return false
        MobileAds.initialize(context, configuration)
        return true
    }

    fun bannerUnitId(): String? = configuredUnitId.also {
        if (it == null) log.w("getAdUnitId", "Missing ad unit ids")
    }

    fun takeCachedBanner(activity: Activity, size: AdSize): BannerAd? {
        val now = SystemClock.elapsedRealtime()
        val entry = removeCachedBanner() ?: return null

        if (entry.expiresAtMillis > now && entry.activityIdentityHash == System.identityHashCode(activity) && entry.size == size) {
            log.d("takeRetainedBanner", BANNER_PLACEMENT)
            return entry.ad
        }
        entry.ad.destroy()
        return null
    }

    fun retainBanner(activity: Activity, size: AdSize, ad: BannerAd, revision: Int): Boolean {
        if (!canUseBanner(revision)) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if ((activity as? LifecycleOwner)?.lifecycle?.currentState == DESTROYED) return false

        val expiresAtMillis = SystemClock.elapsedRealtime() + BANNER_TTL.inWholeMilliseconds
        removeCachedBanner()?.ad?.destroy()

        val entry = CachedBanner(System.identityHashCode(activity), size, ad, expiresAtMillis)
        cachedBanner = entry
        entry.expiryJob = scope.launch {
            delay(BANNER_TTL)
            if (cachedBanner === entry) {
                cachedBanner = null
                ad.destroy()
            }
        }
        log.d("retainBanner", BANNER_PLACEMENT)
        return true
    }

    private fun removeCachedBanner(): CachedBanner? = cachedBanner?.also {
        cachedBanner = null
        it.expiryJob?.cancel()
    }

    private class CachedBanner(
        val activityIdentityHash: Int,
        val size: AdSize,
        val ad: BannerAd,
        val expiresAtMillis: Long,
    ) {
        var expiryJob: Job? = null
    }
}
