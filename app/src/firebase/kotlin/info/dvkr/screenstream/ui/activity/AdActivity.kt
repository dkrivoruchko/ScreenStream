package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.annotation.LayoutRes
import com.elvishew.xlog.XLog
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CompletableDeferred

public abstract class AdActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    private val consentInformation by lazy(LazyThreadSafetyMode.NONE) { UserMessagingPlatform.getConsentInformation(this) }

    internal val adsInitializedDeferred = CompletableDeferred<Boolean>()

    internal val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val params = if (BuildConfig.DEBUG) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder().setTestDeviceIds(listOf("203640674D72D8AD3E73BDFC4AD236B2")).build()
            )

            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .setConsentDebugSettings(
                    ConsentDebugSettings.Builder(this)
                        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA)
                        .addTestDeviceHashedId("203640674D72D8AD3E73BDFC4AD236B2")
                        .build()
                )
                .build()
        } else {
            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
        }

        consentInformation.requestConsentInfoUpdate(this, params,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, ::consentGatheringComplete) },
            { requestConsentError -> consentGatheringComplete(requestConsentError) }
        )

        if (consentInformation.canRequestAds()) initADs()
    }

    private fun consentGatheringComplete(formError: FormError?) {
        if (formError != null) {
            adsInitializedDeferred.complete(false)
            XLog.w(getLog("consentGatheringComplete"), IllegalStateException("consentGatheringComplete: ${formError.errorCode} ${formError.message}"))
        }

        if (consentInformation.canRequestAds()) initADs()
    }

    private fun initADs() {
        XLog.d(getLog("initADs"))

        MobileAds.initialize(this)
        adsInitializedDeferred.complete(true)
    }

    internal fun showPrivacyOptionsForm() {
        UserMessagingPlatform.showPrivacyOptionsForm(this) { formError ->
            if (formError != null) {
                XLog.w(getLog("showPrivacyOptionsForm"), IllegalStateException("showPrivacyOptionsForm: ${formError.errorCode} ${formError.message}"))
            }
        }
    }
}