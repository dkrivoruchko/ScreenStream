package info.dvkr.screenstream

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

public object AppReview {
    private const val PREFERENCES_NAME = "play_review_v2.xml"
    private const val LAST_REVIEW_REQUEST_TIME = "LAST_REVIEW_REQUEST_TIME"
    private const val SUCCESSFUL_SESSIONS_COUNT = "SUCCESSFUL_SESSIONS_COUNT"
    private const val TOTAL_USEFUL_STREAMING_MS = "TOTAL_USEFUL_STREAMING_MS"
    private const val PENDING_REVIEW_REQUEST = "PENDING_REVIEW_REQUEST"

    private const val REVIEW_REQUEST_COOLDOWN_MS: Long = 30L * 24L * 60L * 60L * 1000L
    private const val MIN_SUCCESSFUL_SESSIONS_COUNT: Int = 3
    private const val MIN_TOTAL_USEFUL_STREAMING_MS: Long = 20L * 60L * 1000L
    private const val MIN_SUCCESSFUL_SESSION_USEFUL_MS: Long = 60L * 1000L

    private data class StreamingSignal(val isStreaming: Boolean, val hasActiveConsumer: Boolean)

    private lateinit var sharedPreferences: SharedPreferences

    private var trackingJob: Job? = null
    private var observerActivity: AppCompatActivity? = null
    private val reviewRequestMutex: Mutex = Mutex()

    private var sessionStarted: Boolean = false
    private var currentSessionUsefulMs: Long = 0
    private var usefulSegmentStartElapsedMs: Long? = null

    private val resumeObserver: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            val activity = owner as? AppCompatActivity ?: return
            owner.lifecycleScope.launch {
                if (!isPendingReviewRequest()) return@launch
                requestReviewWhenPossible(activity)
            }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            val activity = owner as? AppCompatActivity ?: return
            if (observerActivity === activity) observerActivity = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    public fun startTracking(activity: AppCompatActivity, streamingModulesManager: StreamingModuleManager) {
        if (::sharedPreferences.isInitialized.not()) {
            sharedPreferences = activity.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        }

        if (observerActivity !== activity) {
            observerActivity?.lifecycle?.removeObserver(resumeObserver)
            activity.lifecycle.addObserver(resumeObserver)
            observerActivity = activity
        }

        trackingJob?.cancel()
        trackingJob = streamingModulesManager.activeModuleStateFlow
            .flatMapLatest { activeModule ->
                if (activeModule == null) flowOf(StreamingSignal(isStreaming = false, hasActiveConsumer = false))
                else combine(activeModule.isStreaming, activeModule.hasActiveConsumer) { isStreaming, hasActiveConsumer ->
                    StreamingSignal(isStreaming = isStreaming, hasActiveConsumer = hasActiveConsumer)
                }
            }
            .distinctUntilChanged()
            .onEach { signal -> onStreamingSignal(activity, signal) }
            .catch { error ->
                if (error is CancellationException) throw error
                XLog.d(activity.getLog("AppReview.startTracking", "Error: $error"))
            }
            .launchIn(activity.lifecycleScope)
    }

    private suspend fun onStreamingSignal(activity: AppCompatActivity, signal: StreamingSignal) {
        val nowElapsed = SystemClock.elapsedRealtime()

        if (sessionStarted.not() && signal.isStreaming) {
            sessionStarted = true
            currentSessionUsefulMs = 0
            usefulSegmentStartElapsedMs = if (signal.hasActiveConsumer) nowElapsed else null
            return
        }

        if (sessionStarted.not()) return

        if (signal.isStreaming) {
            if (signal.hasActiveConsumer) {
                if (usefulSegmentStartElapsedMs == null) usefulSegmentStartElapsedMs = nowElapsed
            } else {
                closeUsefulSegment(nowElapsed)
            }
            return
        }

        closeUsefulSegment(nowElapsed)
        val sessionUsefulMs = currentSessionUsefulMs
        sessionStarted = false
        currentSessionUsefulMs = 0
        usefulSegmentStartElapsedMs = null
        onSessionFinished(activity, sessionUsefulMs)
    }

    private suspend fun onSessionFinished(activity: AppCompatActivity, sessionUsefulMs: Long) {
        if (sessionUsefulMs < MIN_SUCCESSFUL_SESSION_USEFUL_MS) return

        val successfulSessions = sharedPreferences.getInt(SUCCESSFUL_SESSIONS_COUNT, 0) + 1
        val totalUsefulStreamingMs = sharedPreferences.getLong(TOTAL_USEFUL_STREAMING_MS, 0L) + sessionUsefulMs

        sharedPreferences.edit {
            putInt(SUCCESSFUL_SESSIONS_COUNT, successfulSessions)
            putLong(TOTAL_USEFUL_STREAMING_MS, totalUsefulStreamingMs)
        }

        if (successfulSessions < MIN_SUCCESSFUL_SESSIONS_COUNT) return
        if (totalUsefulStreamingMs < MIN_TOTAL_USEFUL_STREAMING_MS) return

        requestReviewWhenPossible(activity)
    }

    private suspend fun requestReview(activity: AppCompatActivity) {
        try {
            val reviewManager = ReviewManagerFactory.create(activity)
            val reviewInfo = reviewManager.requestReview()
            sharedPreferences.edit {
                putLong(LAST_REVIEW_REQUEST_TIME, System.currentTimeMillis())
                putBoolean(PENDING_REVIEW_REQUEST, false)
            }
            reviewManager.launchReview(activity, reviewInfo)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            markPendingReviewRequest()
            XLog.d(activity.getLog("AppReview.requestReview", "Error: $error"))
        }
    }

    private suspend fun requestReviewWhenPossible(activity: AppCompatActivity) {
        reviewRequestMutex.withLock {
            if (isCooldownActive()) return
            if (isActivityReadyForReview(activity).not()) {
                markPendingReviewRequest()
                return
            }
            requestReview(activity)
        }
    }

    private fun markPendingReviewRequest() = sharedPreferences.edit { putBoolean(PENDING_REVIEW_REQUEST, true) }

    private fun isPendingReviewRequest(): Boolean = sharedPreferences.getBoolean(PENDING_REVIEW_REQUEST, false)

    private fun isActivityReadyForReview(activity: AppCompatActivity): Boolean =
        !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun isCooldownActive(): Boolean {
        val now = System.currentTimeMillis()
        val lastReviewRequest = sharedPreferences.getLong(LAST_REVIEW_REQUEST_TIME, 0L)
        return lastReviewRequest > 0L && now - lastReviewRequest < REVIEW_REQUEST_COOLDOWN_MS
    }

    private fun closeUsefulSegment(nowElapsed: Long) {
        val segmentStart = usefulSegmentStartElapsedMs ?: return
        currentSessionUsefulMs += (nowElapsed - segmentStart).coerceAtLeast(0L)
        usefulSegmentStartElapsedMs = null
    }
}
