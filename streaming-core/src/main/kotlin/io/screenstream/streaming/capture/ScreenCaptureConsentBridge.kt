package io.screenstream.streaming.capture

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.core.annotation.Singleton

/**
 * The process-local consent request observed by the Compose host. A submitted request survives
 * Activity recreation in the same process; process death drops its callback and consent authority.
 * All calls and callbacks are confined to Main.
 */
@Singleton
internal class ScreenCaptureConsentBridge {
    internal class Request(
        val key: String,
        val intent: Intent?,
        val submitted: Boolean,
        val onResult: (Result<ActivityResult>) -> Unit,
    )

    internal var currentRequest: Request? by mutableStateOf(null)
        private set

    private var hostToken: Any? = null
    private var hostResumed: Boolean = false

    @MainThread
    internal fun onHostResumed(token: Any) {
        if (hostToken != null && hostToken !== token) {
            failPending(IllegalStateException("Consent host changed before submission"))
        }
        hostToken = token
        hostResumed = true
    }

    @MainThread
    internal fun onHostPaused(token: Any) {
        if (hostToken !== token) return
        hostResumed = false
        failPending(IllegalStateException("Consent host paused before submission"))
    }

    @MainThread
    internal fun unmountHost(token: Any) {
        if (hostToken !== token) return
        hostToken = null
        hostResumed = false
        failPending(IllegalStateException("Consent host disposed before submission"))
    }

    /**
     * Returns true when the resumed host accepted this request for future Compose submission; the
     * host may still fail before launch. The sole caller replaces a previous request only after
     * that operation has lost callback authority and owns rollback.
     */
    @MainThread
    internal fun requestConsent(
        key: String,
        intent: Intent,
        onResult: (Result<ActivityResult>) -> Unit,
    ): Boolean {
        if (hostToken == null || !hostResumed) return false
        require(key.isNotBlank() && currentRequest?.key != key) { "Consent request key must be unique and nonblank" }
        currentRequest = Request(key, intent, submitted = false, onResult = onResult)
        return true
    }

    /** Marks submission before launch; a submitted request is never launched again after rotation. */
    @MainThread
    internal fun submit(key: String, token: Any): Intent? {
        val requestRecord = currentRequest?.takeIf { it.key == key && !it.submitted } ?: return null
        if (hostToken !== token) return null
        if (!hostResumed) {
            currentRequest = null
            requestRecord.onResult(Result.failure(IllegalStateException("Consent host left before submission")))
            return null
        }
        val intent = checkNotNull(requestRecord.intent)
        currentRequest = Request(requestRecord.key, intent = null, submitted = true, onResult = requestRecord.onResult)
        return intent
    }

    /** Consumes correlation before invoking the acquisition callback, including inline replay. */
    @MainThread
    internal fun deliver(key: String, result: ActivityResult) {
        val requestRecord = currentRequest?.takeIf { it.key == key } ?: return
        currentRequest = null
        if (requestRecord.submitted) {
            requestRecord.onResult(Result.success(result))
        } else {
            requestRecord.onResult(Result.failure(IllegalStateException("Consent result arrived before submission")))
        }
    }

    /** Removes this request before reporting a launch failure; any late result has no consumer. */
    @MainThread
    internal fun failLaunch(key: String, cause: Throwable): Boolean {
        val requestRecord = currentRequest?.takeIf { it.key == key && it.submitted } ?: return false
        currentRequest = null
        requestRecord.onResult(Result.failure(cause))
        return true
    }

    /** Revokes consumer authority for this exact request, including a submitted one. */
    @MainThread
    internal fun abandon(key: String) {
        if (currentRequest?.key == key) currentRequest = null
    }

    private fun failPending(cause: Throwable) {
        val pending = currentRequest?.takeUnless(Request::submitted) ?: return
        currentRequest = null
        pending.onResult(Result.failure(cause))
    }
}
