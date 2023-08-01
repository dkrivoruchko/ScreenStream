package info.dvkr.screenstream.webrtc

import android.content.Context
import com.elvishew.xlog.XLog
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.PlayIntegrityToken
import kotlinx.coroutines.tasks.await

public object StandardIntegrityManagerWrapper {

    private lateinit var standardIntegrityManager: StandardIntegrityManager
    private lateinit var integrityTokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider

    public fun initManager(serviceContext: Context) {
        XLog.d(getLog("initManager", "Invoked"))
        standardIntegrityManager = IntegrityManagerFactory.createStandard(serviceContext)
    }

    internal suspend fun prepareIntegrityToken(webRtcEnvironment: WebRtcEnvironment, forceUpdate: Boolean = false) {
        XLog.d(getLog("prepareIntegrityToken", "forceUpdate: $forceUpdate"))
        if (forceUpdate || StandardIntegrityManagerWrapper::integrityTokenProvider.isInitialized.not()) {
            integrityTokenProvider = standardIntegrityManager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(webRtcEnvironment.cloudProjectNumber)
                    .build()
            ).await()
            XLog.d(getLog("prepareIntegrityToken", "IntegrityTokenProvider updated"))
        }
    }

    internal suspend fun getPlayIntegrityToken(requestHash: String): PlayIntegrityToken {
        XLog.d(getLog("getPlayIntegrityToken", "requestHash: $requestHash"))
        return integrityTokenProvider.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()
        )
            .await()
            .token()
            .let { PlayIntegrityToken(it) }
    }
}