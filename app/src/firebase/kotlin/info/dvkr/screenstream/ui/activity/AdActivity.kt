package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.annotation.LayoutRes
import com.elvishew.xlog.XLog
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CompletableDeferred

public abstract class AdActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    internal val isConsentEnabled
        get() = ::consentForm.isInitialized && consentInformation.consentStatus in listOf(
            ConsentInformation.ConsentStatus.REQUIRED, ConsentInformation.ConsentStatus.OBTAINED
        )

    internal val canShowADsDeferred = CompletableDeferred<Boolean>()

    private val consentInformation by lazy(LazyThreadSafetyMode.NONE) { UserMessagingPlatform.getConsentInformation(this) }
    private lateinit var consentForm: ConsentForm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val params = if (BuildConfig.DEBUG) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(listOf("5AB91FDDF0C332D6E9CAE926E585FBD3")).build())

            val debugSettings = ConsentDebugSettings.Builder(this)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA)
                .addTestDeviceHashedId("5AB91FDDF0C332D6E9CAE926E585FBD3")
                .build()

            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .setConsentDebugSettings(debugSettings)
                .build()
        } else {
            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
        }

        consentInformation.requestConsentInfoUpdate(this, params,
            {
                checkIfCanShowADs()
                if (consentInformation.isConsentFormAvailable) loadForm()
            }, { error ->
                XLog.e(getLog("requestConsentInfoUpdate", "error: ${error.message}"))
                canShowADsDeferred.complete(false)
            }
        )
    }

    private fun loadForm() {
        UserMessagingPlatform.loadConsentForm(this,
            {
                consentForm = it
                if (consentInformation.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) showConsentForm()
            },
            { error ->
                XLog.e(getLog("loadConsentForm", "error: ${error.message}"))
                canShowADsDeferred.complete(false)
            }
        )
    }

    private fun checkIfCanShowADs() {
        when (consentInformation.consentStatus) {
            ConsentInformation.ConsentStatus.NOT_REQUIRED, ConsentInformation.ConsentStatus.OBTAINED -> {
                MobileAds.initialize(this)
                canShowADsDeferred.complete(true)
            }
        }
    }

    internal fun showConsentForm() {
        if (::consentForm.isInitialized) consentForm.show(this) { checkIfCanShowADs(); loadForm() }
        else XLog.e(getLog("showConsentForm", "Form not available"), IllegalStateException("showConsentForm: Form not available"))
    }
}