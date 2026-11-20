package info.dvkr.screenstream.review

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.get
import org.koin.core.annotation.Singleton
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.cancellation.CancellationException

/** Presentation belongs to the current resumed Activity, independently of process accounting. */
internal fun attachAppReview(activity: ComponentActivity) {
    val eligibility = activity.get<ReviewEligibility>()
    val log: Logger = activity.get { parametersOf(activity) }
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            eligibility.requestPending.collect { pending ->
                if (pending) eligibility.requestMutex.withLock { requestReview(activity, eligibility, log) }
            }
        }
    }
}

private suspend fun requestReview(activity: ComponentActivity, eligibility: ReviewEligibility, log: Logger) {
    // A resumed presenter may have waited for another Activity's request to finish.
    if (activity.isFinishing || activity.isDestroyed ||
        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !eligibility.canLaunchReview()
    ) return

    try {
        val reviewManager = ReviewManagerFactory.create(activity)
        val reviewInfo = reviewManager.requestReview()
        // The Activity or capture state can change while Play prepares the review flow.
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !eligibility.canLaunchReview()
        ) return

        eligibility.recordReviewRequested()
        reviewManager.launchReview(activity, reviewInfo)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        eligibility.retryReviewRequest()
        log.d("AppReview.requestReview", "Error: $failure")
    }
}

/** Process-owned accounting and preferences. Never retains an Activity, launcher, or UI callback. */
@Singleton(createdAtStart = true)
internal class ReviewEligibility(context: Context, private val manager: StreamingModuleManager) : KoinComponent {
    private val log: Logger by inject { parametersOf(this) }

    private companion object {
        private const val PREFERENCES_NAME = "play_review_v2.xml"
        private const val LAST_REVIEW_REQUEST_TIME = "LAST_REVIEW_REQUEST_TIME"
        private const val SUCCESSFUL_SESSIONS_COUNT = "SUCCESSFUL_SESSIONS_COUNT"
        private const val TOTAL_USEFUL_STREAMING_MS = "TOTAL_USEFUL_STREAMING_MS"
        private const val PENDING_REVIEW_REQUEST = "PENDING_REVIEW_REQUEST"
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val policy = ReviewEligibilityPolicy(
        ReviewEligibilityPolicy.History(
            lastReviewRequestMillis = preferences.getLong(LAST_REVIEW_REQUEST_TIME, 0),
            successfulSessions = preferences.getInt(SUCCESSFUL_SESSIONS_COUNT, 0),
            totalUsefulStreamingMs = preferences.getLong(TOTAL_USEFUL_STREAMING_MS, 0),
            pendingReviewRequest = preferences.getBoolean(PENDING_REVIEW_REQUEST, false),
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableRequestPending = MutableStateFlow(false)
    val requestPending: StateFlow<Boolean> = mutableRequestPending.asStateFlow()

    // Other resumed presenters wait cancellably, without requiring a new pending-state emission.
    val requestMutex: Mutex = Mutex()

    init {
        manager.state.onEach { state ->
            updateHistory { policy.observe(state, SystemClock.uptimeMillis(), System.currentTimeMillis()) }
        }.catch { failure ->
            log.e("observe", "Review eligibility observation failed", failure)
        }.launchIn(scope)
    }

    // These methods and the observer are confined to Main, including across Activity recreation.
    fun canLaunchReview(): Boolean = isCaptureKnownIdle() && policy.isRequestDue(System.currentTimeMillis())

    fun recordReviewRequested() {
        updateHistory { policy.recordReviewRequested(System.currentTimeMillis()) }
    }

    fun retryReviewRequest() {
        updateHistory { policy.retryReviewRequest() }
    }

    private fun isCaptureKnownIdle(): Boolean = when (val state = manager.state.value) {
        is StreamingModuleManager.State.Running -> !state.status.isStreaming && state.status.captureAttempt == null
        StreamingModuleManager.State.NoModule -> true
        else -> false
    }

    private inline fun updateHistory(update: () -> Unit) {
        val before = policy.history
        update()
        val history = policy.history
        if (history != before) {
            preferences.edit {
                putLong(LAST_REVIEW_REQUEST_TIME, history.lastReviewRequestMillis)
                putInt(SUCCESSFUL_SESSIONS_COUNT, history.successfulSessions)
                putLong(TOTAL_USEFUL_STREAMING_MS, history.totalUsefulStreamingMs)
                putBoolean(PENDING_REVIEW_REQUEST, history.pendingReviewRequest)
            }
        }
        mutableRequestPending.value = history.pendingReviewRequest && isCaptureKnownIdle()
    }
}

/**
 * Best-effort review eligibility from manager snapshots, not a lossless session ledger.
 * Unknown health discards the open useful segment but retains capture identity; recovery therefore
 * cannot split one capture into two successful sessions. Closed useful segments remain credited.
 */
private class ReviewEligibilityPolicy(initialHistory: History) {
    data class History(
        val lastReviewRequestMillis: Long = 0,
        val successfulSessions: Int = 0,
        val totalUsefulStreamingMs: Long = 0,
        val pendingReviewRequest: Boolean = false,
    )

    private companion object {
        private const val REVIEW_REQUEST_COOLDOWN_MS = 30L * 24L * 60L * 60L * 1000L
        private const val MIN_SUCCESSFUL_SESSIONS_COUNT = 3
        private const val MIN_TOTAL_USEFUL_STREAMING_MS = 20L * 60L * 1000L
        private const val MIN_SUCCESSFUL_SESSION_USEFUL_MS = 60L * 1000L
    }

    var history: History = initialHistory
        private set

    private var captureAttempt: StreamingModule.CaptureAttemptId? = null
    private var usefulMs: Long = 0
    private var usefulSegmentStartUptimeMs: Long? = null

    fun observe(state: StreamingModuleManager.State, nowUptimeMs: Long, nowMillis: Long) {
        val finishedUsefulMs = when (state) {
            is StreamingModuleManager.State.Running -> observeRunning(state, nowUptimeMs)
            is StreamingModuleManager.State.Unresponsive -> {
                // lastStatus is not evidence that capture or a consumer is still active.
                usefulSegmentStartUptimeMs = null
                if (captureAttempt?.instanceId != state.instanceId) finishSession() else null
            }

            StreamingModuleManager.State.Switching,
            StreamingModuleManager.State.Exiting -> {
                closeUsefulSegment(nowUptimeMs)
                finishSession()
            }

            StreamingModuleManager.State.NoModule,
            is StreamingModuleManager.State.Failed -> {
                // An uncertain closure is not evidence that the open useful interval continued.
                finishSession()
            }
        }
        if (finishedUsefulMs != null) recordFinishedSession(finishedUsefulMs, nowMillis)
    }

    fun isRequestDue(nowMillis: Long): Boolean {
        val lastRequest = history.lastReviewRequestMillis
        val cooldownActive = lastRequest > 0 && nowMillis - lastRequest < REVIEW_REQUEST_COOLDOWN_MS
        return history.pendingReviewRequest && !cooldownActive
    }

    fun recordReviewRequested(nowMillis: Long) {
        history = history.copy(lastReviewRequestMillis = nowMillis, pendingReviewRequest = false)
    }

    fun retryReviewRequest() {
        history = history.copy(pendingReviewRequest = true)
    }

    private fun observeRunning(state: StreamingModuleManager.State.Running, nowUptimeMs: Long): Long? {
        val status = state.status
        val nextAttempt = status.captureAttempt
        if (status.isStreaming && (nextAttempt == null || nextAttempt.instanceId != state.instanceId)) {
            usefulSegmentStartUptimeMs = null
            return null
        }

        val previousAttempt = captureAttempt
        val finishedUsefulMs = if (previousAttempt != null && (!status.isStreaming || nextAttempt != previousAttempt)) {
            val observedStop = !status.isStreaming && state.instanceId == previousAttempt.instanceId &&
                    (nextAttempt == null || nextAttempt == previousAttempt)
            if (observedStop) closeUsefulSegment(nowUptimeMs)
            finishSession()
        } else {
            null
        }

        if (status.isStreaming) {
            captureAttempt = nextAttempt
            if (status.hasConsumer) {
                if (usefulSegmentStartUptimeMs == null) usefulSegmentStartUptimeMs = nowUptimeMs
            } else {
                closeUsefulSegment(nowUptimeMs)
            }
        }
        return finishedUsefulMs
    }

    private fun closeUsefulSegment(nowUptimeMs: Long) {
        val startedAt = usefulSegmentStartUptimeMs ?: return
        usefulMs += (nowUptimeMs - startedAt).coerceAtLeast(0)
        usefulSegmentStartUptimeMs = null
    }

    private fun finishSession(): Long? {
        val finishedUsefulMs = if (captureAttempt != null) usefulMs else null
        captureAttempt = null
        usefulMs = 0
        usefulSegmentStartUptimeMs = null
        return finishedUsefulMs
    }

    private fun recordFinishedSession(finishedUsefulMs: Long, nowMillis: Long) {
        if (finishedUsefulMs < MIN_SUCCESSFUL_SESSION_USEFUL_MS) return
        history = history.copy(
            successfulSessions = history.successfulSessions + 1,
            totalUsefulStreamingMs = history.totalUsefulStreamingMs + finishedUsefulMs,
        )
        if (history.successfulSessions < MIN_SUCCESSFUL_SESSIONS_COUNT ||
            history.totalUsefulStreamingMs < MIN_TOTAL_USEFUL_STREAMING_MS
        ) return

        val lastRequest = history.lastReviewRequestMillis
        if (lastRequest <= 0 || nowMillis - lastRequest >= REVIEW_REQUEST_COOLDOWN_MS) retryReviewRequest()
    }
}
