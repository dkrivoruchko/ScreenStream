package io.screenstream.streaming.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton
import kotlin.uuid.Uuid

/** Owns pending consent correlation and the guard around synchronous Android projection creation. */
@Singleton(binds = [ScreenCaptureAccess::class])
internal class ScreenCaptureAccessImpl(
    context: Context,
    private val requestObserver: ConsentRequestObserver,
) : ScreenCaptureAccess {
    internal class Request(val key: String, val intent: Intent?) {
        val submitted: Boolean get() = intent == null
    }

    private class ConsentGrant(
        val owner: Any,
        val attempt: StreamingModule.CaptureAttemptId,
        val job: Job,
        val consentResultIntent: Intent,
    ) : ScreenCaptureAccess.Grant {
        var consumed: Boolean = false
        var foregroundStartedAtElapsedMillis: Long? = null
    }

    private class PendingConsent(
        val attempt: StreamingModule.CaptureAttemptId,
        val job: Job,
        val waiter: CancellableContinuation<ScreenCaptureAccess.ConsentResult>,
        var request: Request,
        val prepareForeground: () -> Boolean,
    )

    private class ProjectionCreation(val attempt: StreamingModule.CaptureAttemptId) {
        var inFlight: Boolean = true
    }

    private val projectionManager = checkNotNull(context.applicationContext.getSystemService(MediaProjectionManager::class.java))
    private val gate = Any()
    private val mutableRequest = MutableStateFlow<Request?>(null)
    internal val currentRequest: StateFlow<Request?> = mutableRequest.asStateFlow()
    // Under gate: reference-unique resumed registrations, ordered by the latest eligibility report.
    // Derive selection so one host becoming inactive cannot discard another eligible host.
    private val resumedHostTokens = mutableListOf<Any>()
    private val selectedHostToken: Any? get() = resumedHostTokens.lastOrNull()
    private var pendingConsent: PendingConsent? = null
    private var projectionCreation: ProjectionCreation? = null
    private var savedConsentIntent: Intent? = null
    private var latestAttemptId: StreamingModule.CaptureAttemptId? = null

    override suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult = withContext(Dispatchers.Main.immediate) {
        attemptJob.ensureActive()
        awaitConsent(attempt, attemptJob, prepareForeground)
    }

    @MainThread
    private suspend fun awaitConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult = suspendCancellableCoroutine { waiter ->
        waiter.invokeOnCancellation {
            synchronized(gate) { if (pendingConsent?.waiter === waiter) removePending() }
        }
        var cachedGrant: ConsentGrant? = null
        val immediate: Result<ScreenCaptureAccess.ConsentResult>? = synchronized(gate) {
            when {
                !waiter.isActive || !attemptJob.isActive -> Result.failure(CancellationException("Capture attempt is no longer active"))
                pendingConsent != null || projectionCreation != null -> Result.success(notGranted(ScreenCaptureAccess.NoGrantReason.Busy))
                else -> {
                    val saved = savedConsentIntent?.takeIf { Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.TIRAMISU }
                    if (saved == null && selectedHostToken == null) {
                        Result.success(notGranted(ScreenCaptureAccess.NoGrantReason.HostUnavailable))
                    } else if (saved != null) {
                        cachedGrant = ConsentGrant(gate, attempt, attemptJob, saved)
                        savedConsentIntent = null
                        latestAttemptId = attempt
                        null
                    } else {
                        latestAttemptId = attempt
                        try {
                            val request = Request(Uuid.random().toString(), projectionManager.createScreenCaptureIntent())
                            when {
                                !waiter.isActive || !attemptJob.isActive -> Result.failure(CancellationException("Capture attempt is no longer active"))
                                else -> {
                                    pendingConsent = PendingConsent(attempt, attemptJob, waiter, request, prepareForeground)
                                    mutableRequest.value = request
                                    null
                                }
                            }
                        } catch (cause: Throwable) {
                            Result.failure(cause)
                        }
                    }
                }
            }
        }
        val cached = cachedGrant
        if (cached != null) {
            // Offer the committed take before checking cancellation, so queued analytics settlement
            // cannot discard a decision already made in this uninterrupted Main turn.
            observe { requestObserver.savedConsentTaken(attempt) }
            waiter.resumeWith(runCatching { prepareGrant(cached, waiter, prepareForeground) })
        } else if (immediate != null) {
            waiter.resumeWith(immediate)
        }
    }

    override fun createProjection(grant: ScreenCaptureAccess.Grant, retryAfterRecentForegroundStart: Boolean): MediaProjection {
        val accepted = grant as? ConsentGrant ?: error("Invalid screen capture consent")
        val creation = synchronized(gate) {
            check(accepted.owner === gate) { "Consent belongs to another coordinator" }
            accepted.job.ensureActive()
            check(!accepted.consumed) { "Consent has already been consumed" }
            check(projectionCreation == null && pendingConsent == null) { "Another capture acquisition is still pending" }
            accepted.consumed = true
            ProjectionCreation(accepted.attempt).also { projectionCreation = it }
        }
        val foregroundDelay = accepted.foregroundStartedAtElapsedMillis?.let { SystemClock.elapsedRealtime() - it }
        return try {
            val projection = try {
                projectionManager.getMediaProjection(Activity.RESULT_OK, accepted.consentResultIntent)
            } catch (cause: SecurityException) {
                if (!retryAfterRecentForegroundStart || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || foregroundDelay == null ||
                    foregroundDelay !in 0L..PROJECTION_ACQUIRE_RETRY_WINDOW_MILLIS
                ) throw cause
                accepted.job.ensureActive()
                SystemClock.sleep(PROJECTION_ACQUIRE_RETRY_DELAY_MILLIS)
                accepted.job.ensureActive()
                projectionManager.getMediaProjection(Activity.RESULT_OK, accepted.consentResultIntent)
            }
            // No cancellation check after Android returned a resource: the worker must store it.
            checkNotNull(projection)
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
            if (completed.owner !== gate) return
            if (latestAttemptId != completed.attempt || !completed.consumed) return
            latestAttemptId = null
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.TIRAMISU) {
                savedConsentIntent = completed.consentResultIntent
            }
        }
    }

    /** An unsubmitted request belongs to the selected host; selection changes abandon it rather than retarget it. */
    @MainThread
    internal fun onHostResumed(token: Any) {
        val abandoned = synchronized(gate) {
            val previous = selectedHostToken
            resumedHostTokens.removeAll { it === token }
            resumedHostTokens.add(token)
            if (previous !== token && pendingConsent?.request?.submitted == false) removePending() else null
        }
        abandoned?.let { completeWithoutGrant(it, ScreenCaptureAccess.NoGrantReason.HostUnavailable) }
    }

    @MainThread
    internal fun onHostInactive(token: Any) {
        val abandoned = synchronized(gate) {
            val previous = selectedHostToken
            resumedHostTokens.removeAll { it === token }
            if (previous !== selectedHostToken && pendingConsent?.request?.submitted == false) removePending() else null
        }
        abandoned?.let { completeWithoutGrant(it, ScreenCaptureAccess.NoGrantReason.HostUnavailable) }
    }

    @MainThread
    internal fun canSubmit(key: String, token: Any): Boolean = synchronized(gate) {
        selectedHostToken === token && pendingConsent?.let {
            it.request.key == key && !it.request.submitted && it.waiter.isActive && it.job.isActive
        } == true
    }

    @MainThread
    internal fun cancelBeforeSubmission(key: String, token: Any) {
        val declined = synchronized(gate) {
            if (!canSubmit(key, token)) return
            removePending()
        }
        declined?.let { completeWithoutGrant(it, ScreenCaptureAccess.NoGrantReason.EducationDeclined) }
    }

    /** Commit submission before observation or Android launch; recreation never launches it twice. */
    @MainThread
    internal fun submit(key: String, token: Any, educationAccepted: Boolean): Intent? {
        val submission = synchronized(gate) {
            if (!canSubmit(key, token)) return null
            val pending = checkNotNull(pendingConsent)
            val intent = checkNotNull(pending.request.intent)
            val submitted = Request(key, null)
            val accepted = pending to intent
            pending.request = submitted
            accepted
        }
        val (pending, intent) = submission
        observe { requestObserver.systemConsentSubmitted(pending.attempt, educationAccepted) }
        try {
            pending.job.ensureActive()
            return synchronized(gate) {
                if (pendingConsent !== pending || !pending.waiter.isActive) return@synchronized null
                mutableRequest.value = pending.request
                intent
            }
        } catch (cause: Throwable) {
            failPending(pending, cause)
            return null
        }
    }

    /** Successful results immediately prepare the original foreground Service, without a resume gate. */
    @MainThread
    internal fun deliver(key: String, result: ActivityResult) {
        val pending = synchronized(gate) {
            if (pendingConsent?.request?.key != key) return
            removePending()
        } ?: return
        val outcome = runCatching {
            pending.job.ensureActive()
            if (!pending.waiter.isActive) throw CancellationException("Consent request is no longer active")
            check(pending.request.submitted) { "Consent result arrived before submission" }
            if (result.resultCode != Activity.RESULT_OK) {
                notGranted(ScreenCaptureAccess.NoGrantReason.Declined)
            } else {
                val copiedConsentIntent = Intent(checkNotNull(result.data) { "Screen consent returned no result data" })
                val grant = ConsentGrant(gate, pending.attempt, pending.job, copiedConsentIntent)
                prepareGrant(grant, pending.waiter, pending.prepareForeground)
            }
        }
        pending.waiter.resumeWith(outcome)
    }

    /** Claim only a matching pending launcher failure; other failures retain their original path. */
    @MainThread
    internal fun failLaunch(key: String, cause: Throwable): Boolean {
        val failed = synchronized(gate) {
            val pending = pendingConsent ?: return false
            if (pending.request.key != key || !pending.request.submitted) return false
            removePending()
        } ?: return false
        val outcome = runCatching<ScreenCaptureAccess.ConsentResult> {
            failed.job.ensureActive()
            if (!failed.waiter.isActive) throw CancellationException("Consent request is no longer active")
            if (cause is CancellationException) throw cause
            throw ConsentLaunchException(cause)
        }
        failed.waiter.resumeWith(outcome)
        return true
    }

    private fun prepareGrant(
        grant: ConsentGrant,
        waiter: CancellableContinuation<ScreenCaptureAccess.ConsentResult>,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult {
        grant.job.ensureActive()
        if (!waiter.isActive) throw CancellationException("Consent request is no longer active")
        if (!prepareForeground()) return notGranted(ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
        grant.foregroundStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        return ScreenCaptureAccess.ConsentResult.Granted(grant)
    }

    private fun completeWithoutGrant(pending: PendingConsent, reason: ScreenCaptureAccess.NoGrantReason) {
        pending.waiter.resumeWith(runCatching {
            pending.job.ensureActive()
            if (!pending.waiter.isActive) throw CancellationException("Consent request is no longer active")
            notGranted(reason)
        })
    }

    private fun failPending(pending: PendingConsent, cause: Throwable) {
        val claimed = synchronized(gate) {
            if (pendingConsent !== pending) false else {
                removePending()
                true
            }
        }
        if (claimed) pending.waiter.resumeWith(Result.failure(cause))
    }

    private fun notGranted(reason: ScreenCaptureAccess.NoGrantReason): ScreenCaptureAccess.ConsentResult.NotGranted =
        ScreenCaptureAccess.ConsentResult.NotGranted(reason)

    private inline fun observe(callback: () -> Unit) {
        try {
            callback()
        } catch (cause: Throwable) {
            Log.e("ScreenCaptureAccess", "Consent observer failed", cause)
        }
    }

    /** Called under gate; clear correlation before resuming callers or installing another dialog. */
    private fun removePending(): PendingConsent? = pendingConsent.also {
        pendingConsent = null
        mutableRequest.value = null
    }

    private companion object {
        const val PROJECTION_ACQUIRE_RETRY_DELAY_MILLIS = 100L
        const val PROJECTION_ACQUIRE_RETRY_WINDOW_MILLIS = 1500L
    }
}
