package info.dvkr.screenstream

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.elvishew.xlog.XLog
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
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
import org.koin.core.annotation.Single
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Single(createdAtStart = true)
public class AdMob(private val context: Context) {

    private data class AdUnit(val id: String, var lastUsedMillis: Long = 0, var inComposition: Boolean = false) {
        override fun toString() = "AdUnit(id=$id, lastUsedMillis=$lastUsedMillis, inComposition=$inComposition)"
    }

    private val adUnits = JSONArray(BuildConfig.AD_UNIT_IDS).let { Array(it.length()) { i -> AdUnit(it.getString(i)) } }

    public suspend fun getFreeAdUnitId(): String {
        XLog.d(this@AdMob.getLog("AdaptiveBanner.getFreeAdUnitId"))

        var availableAdUnits = adUnits.filter { it.inComposition.not() }
        while (availableAdUnits.isEmpty()) {
            delay(100)
            availableAdUnits = adUnits.filter { it.inComposition.not() }
        }
        return availableAdUnits.minByOrNull { it.lastUsedMillis }!!.apply {
            inComposition = true
            XLog.d(this@AdMob.getLog("AdaptiveBanner.getFreeAdUnitId.done", id))
        }.id
    }

    public suspend fun waitAdUnitReady(adUnitId: String): Boolean {
        XLog.d(this@AdMob.getLog("AdaptiveBanner.waitAdUnitReady", adUnitId))
        val adUnit = adUnits.first { it.id == adUnitId }
        require(adUnit.inComposition)
        while (adUnit.lastUsedMillis + 62_000 - System.currentTimeMillis() > 0) delay(100)
        XLog.d(this@AdMob.getLog("AdaptiveBanner.waitAdUnitReady.done", "$adUnit"))
        return true
    }

    public fun setAdViewLoaded(adUnitId: String) {
        XLog.d(this@AdMob.getLog("AdaptiveBanner.setAdViewLoaded", adUnitId))
        adUnits.first { it.id == adUnitId }.lastUsedMillis = System.currentTimeMillis()
    }

    public fun release(adUnitId: String) {
        XLog.d(this@AdMob.getLog("AdaptiveBanner.release", adUnitId))
        adUnits.first { it.id == adUnitId }.inComposition = false
    }

    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    public val initialized: MutableState<Boolean> = mutableStateOf(false)
    private var isMobileAdsInitializeCalled = AtomicBoolean(false)

    private val consentRequestParameters = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        val hashedId = "203640674D72D8AD3E73BDFC4AD236B2"
        MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(listOf(hashedId)).build())
        ConsentRequestParameters.Builder()
            .setConsentDebugSettings(
                ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA)
                    .addTestDeviceHashedId(hashedId)
                    .build()
            )
            .build()
    } else {
        ConsentRequestParameters.Builder().build()
    }

    public val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    public fun showPrivacyOptionsForm(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                XLog.w(getLog("showPrivacyOptionsForm", "Error: ${formError.errorCode} ${formError.message}"))
            }
        }
    }

    public fun init(activity: Activity) {
        XLog.d(getLog("init", "${activity.hashCode()}"))

        if (initialized.value) return

        consentInformation.requestConsentInfoUpdate(
            activity,
            consentRequestParameters,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initializeMobileAds(it) } },
            { initializeMobileAds(it) }
        )

        initializeMobileAds()
    }

    private fun initializeMobileAds(error: FormError? = null) {
        if (initialized.value) {
            XLog.d(getLog("initializeMobileAds", "Already initialized. Ignoring"))
            return
        }

        if (error != null) {
            XLog.w(getLog("initializeMobileAds", "Error: ${error.errorCode} ${error.message}"))
            initialized.value = false
            return
        }

        if (consentInformation.canRequestAds()) {
            XLog.d(getLog("initializeMobileAds"))
            if (isMobileAdsInitializeCalled.getAndSet(true)) {
                XLog.d(getLog("initializeMobileAds", "Pending initialization. Ignoring"))
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                MobileAds.initialize(context) {
                    CoroutineScope(Dispatchers.Main).launch {
                        XLog.d(this@AdMob.getLog("initializeMobileAds", "Done"))
                        initialized.value = true
                    }
                }
            }
        }
    }
}

@Composable
public fun AdaptiveBanner(
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    adMob: AdMob = koinInject()
) {
    if (adMob.initialized.value) {
        BoxWithConstraints(modifier = modifier) {
            val context = LocalContext.current
            val adSize = remember(context, maxWidth) {
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, maxWidth.value.toInt())
            }

            val adBox = remember(adSize, collapsible) { movableContentOf { AdBox(adMob, adSize, collapsible) } }

            adBox.invoke()
        }
    }
}

@Composable
private fun AdBox(adMob: AdMob, adSize: AdSize, collapsible: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = adSize.height.dp)
    ) {
        val selectedAdUnitId = remember(adSize) { mutableStateOf("") }
        LaunchedEffect(Unit) { selectedAdUnitId.value = adMob.getFreeAdUnitId() }

        if (selectedAdUnitId.value.isNotBlank()) {
            val adUnitReady = remember { mutableStateOf(false) }
            val adUnitLoaded = remember { mutableStateOf(false) }
            val adReloadJob = remember { Job() }
            LaunchedEffect(Unit) { adUnitReady.value = adMob.waitAdUnitReady(selectedAdUnitId.value) }

            AndroidView(
                factory = { ctx ->
                    AdView(ctx).apply AdView@{
                        XLog.d(getLog("AdaptiveBanner", "factory: ${selectedAdUnitId.value}"))
                        adUnitId = selectedAdUnitId.value
                        setAdSize(adSize)
                        adListener = object : AdListener() {
                            override fun onAdFailedToLoad(adError: LoadAdError) {
                                XLog.w(getLog("onAdFailedToLoad", adError.toString()))
                            }
                        }

                        val observer = object : DefaultLifecycleObserver {
                            override fun onResume(owner: LifecycleOwner) {
                                XLog.d(this@AdView.getLog("AdaptiveBanner", "onResume: $adUnitId"))
                                this@AdView.resume()
                            }

                            override fun onPause(owner: LifecycleOwner) {
                                XLog.d(this@AdView.getLog("AdaptiveBanner", "onPause: $adUnitId"))
                                this@AdView.pause()
                            }
                        }

                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(adView: View) {
                                XLog.d(this@AdView.getLog("AdaptiveBanner", "onViewAttachedToWindow: $adUnitId"))
                                adView.findViewTreeLifecycleOwner()?.lifecycle?.addObserver(observer)
                            }

                            override fun onViewDetachedFromWindow(adView: View) {
                                XLog.d(this@AdView.getLog("AdaptiveBanner", "onViewDetachedFromWindow: $adUnitId"))
                                adView.findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(observer)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onRelease = { adView ->
                    XLog.d(adView.getLog("AdaptiveBanner", "onRelease: ${adView.adUnitId}"))
                    adReloadJob.cancel()
                    adView.destroy()
                    adMob.release(adView.adUnitId)
                    selectedAdUnitId.value = ""
                },
                update = { adView ->
                    if (adUnitReady.value && adUnitLoaded.value.not()) {
                        XLog.d(adView.getLog("AdaptiveBanner", "update: ${adView.adUnitId}"))
                        val adRequestBuilder = AdRequest.Builder()
                        if (collapsible) {
                            adRequestBuilder.addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply {
                                putString("collapsible", "top")
                                putString("collapsible_request_id", UUID.randomUUID().toString())
                            })
                        }

                        CoroutineScope(Dispatchers.Main.immediate + adReloadJob).launch {
                            repeat(Int.MAX_VALUE) { i ->
                                XLog.d(adView.getLog("AdaptiveBanner", "update ($i): ${adView.adUnitId}"))
                                adView.loadAd(adRequestBuilder.build())
                                adMob.setAdViewLoaded(adView.adUnitId)
                                adUnitLoaded.value = true
                                delay(60_000)
                            }
                        }
                    }
                }
            )
        }
    }
}