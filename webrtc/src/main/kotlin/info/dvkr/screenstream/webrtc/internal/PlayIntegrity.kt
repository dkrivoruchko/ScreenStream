package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import com.elvishew.xlog.XLog
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcEnvironment
import info.dvkr.screenstream.webrtc.WebRtcError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

internal class PlayIntegrity(
    serviceContext: Context,
    private val environment: WebRtcEnvironment,
    private val okHttpClient: OkHttpClient
) {

    internal companion object {

        // No retry. Ask for user action
        private val USER_ACTION_INTEGRITY_ERRORS = listOf(
            IntegrityErrorCode.API_NOT_AVAILABLE, // Integrity API is not available. Integrity API is not enabled, or the Play Store version might be old.
            IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED, // Play Store app needs to be updated.
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE, // Binding to the service in the Play Store has failed.
            IntegrityErrorCode.PLAY_STORE_NOT_FOUND, // No official Play Store app was found on the device.
            IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND, // No Play Store account is found on the device.
            IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND, // Play services is unavailable or needs to be updated.
            IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED, // Play services needs to be updated.
            IntegrityErrorCode.NETWORK_ERROR, // No available network was found.
        )

        // Retry failed. Notify user
        private val USER_NOTIFY_INTEGRITY_ERRORS = listOf(
            IntegrityErrorCode.TOO_MANY_REQUESTS, // The calling app is making too many requests to the API and has been throttled.
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE, // Unknown internal Google server error.
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR, // Transient error has occurred on the client device.
            IntegrityErrorCode.INTERNAL_ERROR // Unknown internal error.
        )

        // No retry. Force app crash
        private val CRITICAL_INTEGRITY_ERRORS = listOf(
            IntegrityErrorCode.NONCE_TOO_SHORT,
            IntegrityErrorCode.NONCE_TOO_LONG,
            IntegrityErrorCode.NONCE_IS_NOT_BASE64,
            IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            IntegrityErrorCode.APP_NOT_INSTALLED,
            IntegrityErrorCode.APP_UID_MISMATCH
        )

        // App will retry
        private val RETRY_INTEGRITY_ERRORS = listOf(
            IntegrityErrorCode.NETWORK_ERROR,
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR
        )

        private fun IntegrityServiceException.toWebRtcError(): WebRtcError = when (errorCode) {
            in USER_ACTION_INTEGRITY_ERRORS -> {
                val id = when (errorCode) {
                    IntegrityErrorCode.API_NOT_AVAILABLE,
                    IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
                    IntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> R.string.webrtc_error_play_integrity_update_play_store

                    IntegrityErrorCode.PLAY_STORE_NOT_FOUND -> R.string.webrtc_error_play_integrity_install_play_store
                    IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND -> R.string.webrtc_error_play_integrity_signin_play_store
                    IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> R.string.webrtc_error_play_integrity_install_play_service
                    IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> R.string.webrtc_error_play_integrity_update_play_service
                    IntegrityErrorCode.NETWORK_ERROR -> R.string.webrtc_error_check_network
                    else -> throw this // Intentionally crash the app
                }
                WebRtcError.PlayIntegrityUserActionError(errorCode, message, id)
            }

            in USER_NOTIFY_INTEGRITY_ERRORS -> WebRtcError.PlayIntegrityUserNotifyError(errorCode, message)
            in CRITICAL_INTEGRITY_ERRORS -> throw this // Intentionally crash the app
            else -> WebRtcError.PlayIntegrityUserNotifyError(errorCode, message)
        }
    }

    private val playIntegrityManager = IntegrityManagerFactory.create(serviceContext)
    private val nonceRequest = Request.Builder()
        .url(environment.signalingServerNonceUrl)
        .cacheControl(CacheControl.Builder().noCache().noStore().build())
        .build()

    @Throws(WebRtcError::class)
    internal suspend fun getTokenWithRetries(): String =
        withRetries(3, 5000L, 2.0) { getToken(okHttpClient, it) }

    @Throws(WebRtcError::class)
    private suspend fun getToken(okHttpClient: OkHttpClient, attempt: Int): String {
        XLog.d(getLog("getToken", "Attempt: $attempt"))

        val nonce = withRetries(3, 2000L, 1.02) { getNonce(okHttpClient, it) }

        val tokenRequest = IntegrityTokenRequest.builder()
            .setCloudProjectNumber(environment.cloudProjectNumber)
            .setNonce(nonce)
            .build()

        return playIntegrityManager.requestIntegrityToken(tokenRequest).await().token().also {
            XLog.d(getLog("getToken", "Attempt: $attempt. Got token"))
        }
    }

    @Throws(WebRtcError.NetworkError::class)
    private suspend fun getNonce(okHttpClient: OkHttpClient, attempt: Int): String = runCatching {
        XLog.d(getLog("getNonce", "Attempt: $attempt"))
        okHttpClient.newCall(nonceRequest).await().use { response ->
            if (response.isSuccessful.not()) throw WebRtcError.NetworkError(response.code, response.message, null)
            response.body!!.string().also { nonce -> XLog.d(getLog("getNonce", "Attempt: $attempt. Got nonce: $nonce")) }
        }
    }.getOrElse { throw if (it is CancellationException) it else WebRtcError.NetworkError(0, it.message, it) }

    private suspend inline fun <T> withRetries(
        maxRetries: Int, startDelayMs: Long, expBackoffBase: Double, noinline operation: suspend (Int) -> T
    ): T =
        try {
            operation(0)
        } catch (cause: Throwable) {
            XLog.d(getLog("withRetries", "Attempt: 0"), cause)
            if (cause is CancellationException || maxRetries <= 0) throw cause
            if (cause is WebRtcError.NetworkError && (cause.code == 0 || cause.code in 500..599)) throw cause
            if (cause is IntegrityServiceException && cause.errorCode !in RETRY_INTEGRITY_ERRORS) throw cause.toWebRtcError()
            retryWithAttempt(1, maxRetries, startDelayMs, expBackoffBase, operation)
        }

    private suspend fun <T> retryWithAttempt(
        attempt: Int, maxRetries: Int, startDelayMs: Long, expBackoffBase: Double, operation: suspend (Int) -> T
    ): T =
        try {
            delay(startDelayMs * (expBackoffBase.pow(attempt - 1).toLong()))
            operation(attempt)
        } catch (cause: Throwable) {
            XLog.d(getLog("retryWithAttempt", "Attempt: $attempt"), cause)
            if (cause is CancellationException || attempt >= maxRetries) throw cause
            if (cause is WebRtcError.NetworkError && (cause.code == 0 || cause.code in 500..599)) throw cause
            if (cause is IntegrityServiceException && cause.errorCode !in RETRY_INTEGRITY_ERRORS) throw cause.toWebRtcError()
            retryWithAttempt(attempt + 1, maxRetries, startDelayMs, expBackoffBase, operation)
        }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })

        continuation.invokeOnCancellation { runCatching { cancel() } }
    }
}