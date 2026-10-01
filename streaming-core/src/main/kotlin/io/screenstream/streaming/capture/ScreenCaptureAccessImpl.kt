package io.screenstream.streaming.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.annotation.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

/** Owns pending consent correlation and the guard around synchronous Android projection creation. */
@Singleton(binds = [ScreenCaptureAccess::class])
internal class ScreenCaptureAccessImpl(context: Context) : ScreenCaptureAccess {
    internal class Request(val key: String, val intent: Intent?) {
        val submitted: Boolean get() = intent == null
    }

    private class ConsentGrant(
        val attempt: StreamingModule.CaptureAttemptId,
        val isCurrent: () -> Boolean,
        val data: Intent,
    ) : ScreenCaptureAccess.Grant {
        var consumed: Boolean = false
    }

    private class PendingConsent(
        val attempt: StreamingModule.CaptureAttemptId,
        val isCurrent: () -> Boolean,
        val waiter: CancellableContinuation<ScreenCaptureAccess.Grant?>,
        var request: Request,
    )

    private class ProjectionCreation(val attempt: StreamingModule.CaptureAttemptId) {
        var inFlight: Boolean = true
    }

    private val projectionManager = checkNotNull(context.applicationContext.getSystemService(MediaProjectionManager::class.java))
    private val gate = Any()
    private val mutableRequest = MutableStateFlow<Request?>(null)
    internal val currentRequest: StateFlow<Request?> = mutableRequest.asStateFlow()
    private var resumedHostToken: Any? = null
    private var pendingConsent: PendingConsent? = null
    private var projectionCreation: ProjectionCreation? = null
    private var savedIntent: Intent? = null
    private var latestAttemptId: StreamingModule.CaptureAttemptId? = null

    override suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        isCurrent: () -> Boolean,
    ): ScreenCaptureAccess.Grant? = suspendCancellableCoroutine { waiter ->
        // Register in the caller context before installation: already-cancelled callers cannot
        // leave a dialog installed, and no dispatcher boundary carries a resource-bearing result.
        waiter.invokeOnCancellation {
            synchronized(gate) { if (pendingConsent?.waiter === waiter) removePending() }
        }
        val immediate: Result<ScreenCaptureAccess.Grant?>? = synchronized(gate) {
            when {
                !waiter.isActive -> null
                !isCurrent() -> Result.failure(CancellationException("Capture attempt is no longer current"))
                pendingConsent != null || projectionCreation != null -> Result.success(null)
                else -> {
                    val saved = savedIntent?.takeIf { Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.TIRAMISU }
                    if (saved == null && resumedHostToken == null) {
                        Result.success(null)
                    } else {
                        savedIntent = null
                        latestAttemptId = attempt
                        try {
                            if (saved != null) Result.success(ConsentGrant(attempt, isCurrent, saved))
                            else {
                                val request = Request(Uuid.random().toString(), projectionManager.createScreenCaptureIntent())
                                when {
                                    !waiter.isActive -> null
                                    !isCurrent() -> Result.failure(CancellationException("Capture attempt is no longer current"))
                                    else -> {
                                        pendingConsent = PendingConsent(attempt, isCurrent, waiter, request)
                                        mutableRequest.value = request
                                        null
                                    }
                                }
                            }
                        } catch (cause: Throwable) {
                            Result.failure(cause)
                        }
                    }
                }
            }
        }
        if (immediate != null) waiter.resumeWith(immediate)
    }

    override fun createProjection(grant: ScreenCaptureAccess.Grant): MediaProjection {
        val accepted = grant as? ConsentGrant ?: error("Invalid screen capture consent")
        val creation = synchronized(gate) {
            check(accepted.isCurrent()) { "Capture attempt is no longer current" }
            check(!accepted.consumed) { "Consent has already been consumed" }
            check(projectionCreation == null && pendingConsent == null) { "Another capture acquisition is still pending" }
            accepted.consumed = true
            ProjectionCreation(accepted.attempt).also { projectionCreation = it }
        }
        return try {
            checkNotNull(projectionManager.getMediaProjection(Activity.RESULT_OK, accepted.data))
        } finally {
            synchronized(gate) { creation.inFlight = false }
        }
    }

    override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            val creation = projectionCreation ?: return
            if (creation.attempt == attempt && !creation.inFlight) projectionCreation = null
        }
    }

    override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) {
        val completed = grant as? ConsentGrant ?: return
        synchronized(gate) {
            if (latestAttemptId != completed.attempt || !completed.consumed) return
            latestAttemptId = null
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.TIRAMISU) {
                savedIntent = completed.data
            }
        }
    }

    /** Marks the current Activity host; only unsubmitted requests lose authority on replacement. */
    @MainThread
    internal fun onHostResumed(token: Any) {
        val abandoned = synchronized(gate) {
            val previous = resumedHostToken
            resumedHostToken = token
            if (previous != null && previous !== token && pendingConsent?.request?.submitted == false) removePending() else null
        }
        abandoned?.waiter?.cancel(CancellationException("Consent host changed before submission"))
    }

    @MainThread
    internal fun onHostInactive(token: Any) {
        val abandoned = synchronized(gate) {
            if (resumedHostToken !== token) return
            resumedHostToken = null
            if (pendingConsent?.request?.submitted == false) removePending() else null
        }
        abandoned?.waiter?.cancel(CancellationException("Consent host inactive before submission"))
    }

    /** Whether the exact resumed host can confirm education or submit this pending request. */
    @MainThread
    internal fun canSubmit(key: String, token: Any): Boolean = synchronized(gate) {
        resumedHostToken === token && pendingConsent?.let {
            it.request.key == key && !it.request.submitted && it.isCurrent()
        } == true
    }

    /** Declines education before submission, consuming the request before resuming its caller. */
    @MainThread
    internal fun cancelBeforeSubmission(key: String, token: Any) {
        val declined = synchronized(gate) {
            if (!canSubmit(key, token)) return
            removePending()
        }
        declined?.waiter?.resume(null)
    }

    /** Marks submission before Android launch so Activity recreation never launches it again. */
    @MainThread
    internal fun submit(key: String, token: Any): Intent? = synchronized(gate) {
        if (!canSubmit(key, token)) return null
        val pending = checkNotNull(pendingConsent)
        checkNotNull(pending.request.intent).also {
            pending.request = Request(key, null)
            mutableRequest.value = pending.request
        }
    }

    /** Consumes only the matching result; the defensive grant copy owns no Android resource. */
    @MainThread
    internal fun deliver(key: String, result: ActivityResult) {
        val pending = synchronized(gate) {
            if (pendingConsent?.request?.key != key) return
            removePending()
        } ?: return
        val outcome = runCatching {
            when {
                !pending.isCurrent() -> throw CancellationException("Capture attempt is no longer current")
                !pending.request.submitted -> error("Consent result arrived before submission")
                result.resultCode != Activity.RESULT_OK -> null
                else -> {
                    val copied = Intent(checkNotNull(result.data) { "Screen consent returned no result data" })
                    ConsentGrant(pending.attempt, pending.isCurrent, copied)
                }
            }
        }
        pending.waiter.resumeWith(outcome)
    }

    /** Consumes a failed Android launch; stale launch errors cannot affect a newer request. */
    @MainThread
    internal fun failLaunch(key: String, cause: Throwable): Boolean {
        val failed = synchronized(gate) {
            val pending = pendingConsent ?: return false
            if (pending.request.key != key || !pending.request.submitted) return false
            removePending()
        }
        failed?.waiter?.resumeWithException(cause)
        return true
    }

    /** Called under gate; clear correlation before any caller resumes or a new dialog is installed. */
    private fun removePending(): PendingConsent? = pendingConsent.also {
        pendingConsent = null
        mutableRequest.value = null
    }
}