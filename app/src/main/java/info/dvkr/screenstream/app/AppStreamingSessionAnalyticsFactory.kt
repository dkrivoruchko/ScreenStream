package info.dvkr.screenstream.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import io.screenstream.streaming.capture.ConsentRequestObserver
import io.screenstream.streaming.module.StreamingModule
import org.koin.core.annotation.Singleton
import java.util.concurrent.atomic.AtomicBoolean

/** Correlates only pending consent calls; original module adapters retain started recordings. */
@Singleton(binds = [StreamingSessionAnalyticsFactory::class, ConsentRequestObserver::class], createdAtStart = true)
public class AppStreamingSessionAnalyticsFactory(
    private val analytics: StreamingAnalytics,
) : StreamingSessionAnalyticsFactory, ConsentRequestObserver {
    private val gate: Any = Any()
    private val pending: MutableMap<SessionKey, PendingSession> = HashMap()
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    override fun beginConsent(key: SessionKey, streamMode: StreamMode, entryPoint: EntryPoint): StreamingSessionAnalytics {
        val session = PendingSession(key, StreamingSessionAnalyticsTracker(analytics, streamMode, entryPoint))
        val attached = synchronized(gate) {
            if (key in pending) false else {
                pending[key] = session
                true
            }
        }
        if (!attached) {
            Log.e("StreamingAnalytics", "Duplicate consent analytics binding: $key")
            return NoOpSession
        }
        return session
    }

    override fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup) {
        StreamingSessionAnalyticsTracker(analytics, streamMode, entryPoint).apply {
            consentAdmitted(usedCachedPermission = false, permissionEducationShown = false)
            startFailed(startFailGroup)
            consentCallFinished()
        }
    }

    @MainThread
    override fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId) {
        observeConsent(attempt, usedCachedPermission = true, educationAccepted = false)
    }

    @MainThread
    override fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean) {
        observeConsent(attempt, usedCachedPermission = false, educationAccepted = educationAccepted)
    }

    private fun observeConsent(
        attempt: StreamingModule.CaptureAttemptId,
        usedCachedPermission: Boolean,
        educationAccepted: Boolean,
    ) {
        try {
            val key = SessionKey(attempt.instanceId.moduleId.value, attempt.instanceId.uuid, attempt.uuid)
            val session = synchronized(gate) { pending[key] } ?: return
            session.tracker.consentAdmitted(usedCachedPermission, educationAccepted)
        } catch (failure: Throwable) {
            Log.e("StreamingAnalytics", "Consent analytics observation failed", failure)
        }
    }

    private fun remove(session: PendingSession): Boolean = synchronized(gate) {
        if (pending[session.key] !== session) false else {
            pending.remove(session.key)
            true
        }
    }

    private inner class PendingSession(
        val key: SessionKey,
        val tracker: StreamingSessionAnalyticsTracker,
    ) : StreamingSessionAnalytics by tracker {
        private val settlementPosted: AtomicBoolean = AtomicBoolean(false)

        override fun consentCallFinished() {
            if (!settlementPosted.compareAndSet(false, true)) return
            try {
                // Always queue: an in-flight Main submit must finish its outside-lock observation.
                if (mainHandler.post { if (remove(this)) tracker.consentCallFinished() }) return
                remove(this)
                Log.e("StreamingAnalytics", "Main looper rejected consent analytics settlement")
            } catch (failure: Throwable) {
                remove(this)
                Log.e("StreamingAnalytics", "Could not schedule consent analytics settlement", failure)
            }
        }
    }

    private object NoOpSession : StreamingSessionAnalytics {
        override fun started(activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = Unit
        override fun startFailed(startFailGroup: StartFailGroup): Unit = Unit
        override fun startAborted(): Unit = Unit
        override fun consumersChanged(activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = Unit
        override fun ended(stopReasonRaw: String?, activeConsumers: Int, atElapsedRealtimeMs: Long): Unit = Unit
        override fun consentCallFinished(): Unit = Unit
    }
}
