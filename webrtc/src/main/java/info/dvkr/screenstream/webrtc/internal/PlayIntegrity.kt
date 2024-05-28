package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import android.os.RemoteException
import androidx.annotation.AnyThread
import com.elvishew.xlog.XLog
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal class PlayIntegrity(serviceContext: Context, private val environment: WebRtcEnvironment, private val okHttpClient: OkHttpClient) {

    internal companion object {
        @JvmStatic
        private var standardIntegrityManager: StandardIntegrityManager? = null

        @JvmStatic
        private var integrityTokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    }

    init {
        XLog.d(getLog("init"))
        if (standardIntegrityManager == null) {
            standardIntegrityManager = IntegrityManagerFactory.createStandard(serviceContext.applicationContext)
        }
    }

    private val nonceRequest = Request.Builder()
        .url(environment.signalingServerNonceUrl)
        .cacheControl(CacheControl.Builder().noCache().noStore().build())
        .build()

    internal fun getNonce(callback: Result<String>.() -> Unit) {
        okHttpClient.newCall(nonceRequest).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    if (response.isSuccessful) response.use { it.body!!.string() }
                    else throw WebRtcError.NetworkError(response.code, response.message, null)
                }.onFailure {
                    XLog.w(this@PlayIntegrity.getLog("getNonce", "Read failed: ${it.message}"))
                    callback(Result.failure(if (it is WebRtcError.NetworkError) it else WebRtcError.NetworkError(0, it.message, it)))
                }.onSuccess {
                    XLog.d(this@PlayIntegrity.getLog("getNonce", "Got nonce: $it"))
                    callback(Result.success(it))
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                XLog.w(this@PlayIntegrity.getLog("getNonce", "onFailure: ${e.message}"))
                callback(Result.failure(WebRtcError.NetworkError(-1, e.message, e)))
            }
        })
    }

    internal fun getToken(nonce: String, forceUpdate: Boolean, callback: Result<PlayIntegrityToken>.() -> Unit) {
        XLog.d(getLog("getToken"))

        prepareIntegrityToken(forceUpdate) {
            onSuccess {
                integrityTokenProvider = it
                getPlayIntegrityToken(it, nonce, callback)
            }
            onFailure { callback(Result.failure(it)) }
        }
    }

    private fun prepareIntegrityToken(
        forceUpdate: Boolean,
        callback: Result<StandardIntegrityManager.StandardIntegrityTokenProvider>.() -> Unit
    ) {
        if (forceUpdate.not()) integrityTokenProvider?.let {
            XLog.d(getLog("prepareIntegrityToken", "forceUpdate: $forceUpdate. Skipping"))
            callback(Result.success(it))
            return
        }

        XLog.d(getLog("prepareIntegrityToken", "forceUpdate: $forceUpdate"))

        val prepareTokenRequest = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
            .setCloudProjectNumber(environment.cloudProjectNumber)
            .build()

        requireNotNull(standardIntegrityManager).prepareIntegrityToken(prepareTokenRequest)
            .addOnSuccessListener { tokenProvider -> // @MainThread
                XLog.d(getLog("prepareIntegrityToken", "IntegrityTokenProvider updated"))
                callback(Result.success(tokenProvider))
            }.addOnFailureListener { error -> // @MainThread
                XLog.e(getLog("prepareIntegrityToken", "Failed: ${error.message}"))
                val cause = when (error) {
                    is StandardIntegrityException -> error.toWebRtcError()
                    is RemoteException -> WebRtcError.PlayIntegrityError(StandardIntegrityErrorCode.INTERNAL_ERROR, true, error.message)
                    else -> error
                }
                callback(Result.failure(cause))
            }
    }

    @AnyThread
    private fun getPlayIntegrityToken(
        tokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider,
        requestHash: String,
        callback: Result<PlayIntegrityToken>.() -> Unit
    ) {
        XLog.d(getLog("getPlayIntegrityToken", "requestHash: $requestHash"))

        val tokenRequest = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()

        tokenProvider.request(tokenRequest)
            .addOnSuccessListener { response -> // @MainThread
                val integrityToken = PlayIntegrityToken(response.token())
                XLog.d(getLog("getPlayIntegrityToken", "Success: $integrityToken"))
                callback(Result.success(integrityToken))
            }.addOnFailureListener { error -> // @MainThread
                XLog.e(getLog("getPlayIntegrityToken", "Failed: ${error.message}"))
                val cause = when (error) {
                    is StandardIntegrityException -> error.toWebRtcError()
                    is RemoteException -> WebRtcError.PlayIntegrityError(StandardIntegrityErrorCode.INTERNAL_ERROR, true, error.message)
                    else -> error
                }
                callback(Result.failure(cause))
            }
    }

    private fun StandardIntegrityException.toWebRtcError(): WebRtcError = when (errorCode) {
        StandardIntegrityErrorCode.API_NOT_AVAILABLE -> // Integrity API is not available. Integrity API is not enabled, or the Play Store version might be old.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_update_play_store)

        StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND -> // No official Play Store app was found on the device.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_install_play_store)

        StandardIntegrityErrorCode.NETWORK_ERROR -> // No available network was found.
            WebRtcError.PlayIntegrityError(errorCode, true, message, R.string.webrtc_error_check_network)

        StandardIntegrityErrorCode.APP_NOT_INSTALLED ->
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> // Play services is unavailable or needs to be updated.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_install_play_service)

        StandardIntegrityErrorCode.APP_UID_MISMATCH ->
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.TOO_MANY_REQUESTS -> // The calling app is making too many requests to the API and has been throttled.
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> // Binding to the service in the Play Store has failed.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_update_play_store)

        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> // Unknown internal Google server error.
            WebRtcError.PlayIntegrityError(errorCode, true, message)

        StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> // Play Store app needs to be updated.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_update_play_store)

        StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> // Play services needs to be updated.
            WebRtcError.PlayIntegrityError(errorCode, false, message, R.string.webrtc_error_play_integrity_update_play_service)

        StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID ->
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG ->
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR ->
            WebRtcError.PlayIntegrityError(errorCode, true, message)

        StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID ->
            WebRtcError.PlayIntegrityError(errorCode, false, message)

        StandardIntegrityErrorCode.INTERNAL_ERROR -> // Unknown internal error.
            WebRtcError.PlayIntegrityError(errorCode, true, message)

        else -> WebRtcError.PlayIntegrityError(errorCode, false, message) // Unknown error code
    }
}