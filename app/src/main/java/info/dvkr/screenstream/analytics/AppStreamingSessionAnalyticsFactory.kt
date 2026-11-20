package info.dvkr.screenstream.analytics

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.screenstream.analytics.EntryPoint
import io.screenstream.analytics.SessionKey
import io.screenstream.analytics.StartFailGroup
import io.screenstream.analytics.StreamMode
import io.screenstream.analytics.StreamingAnalytics
import io.screenstream.analytics.StreamingSessionAnalytics
import io.screenstream.analytics.StreamingSessionAnalyticsFactory
import io.screenstream.analytics.StreamingSessionAnalyticsTracker
import io.screenstream.core.capture.ConsentRequestObserver
import io.screenstream.core.module.StreamingModule
import io.screenstream.foundation.logging.Logger
import org.koin.core.annotation.Singleton
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean

/** Correlates only pending consent calls; original module adapters retain started recordings. */
@Singleton(binds = [StreamingSessionAnalyticsFactory::class, ConsentRequestObserver::class], createdAtStart = true)
public class AppStreamingSessionAnalyticsFactory(
    private val analytics: StreamingAnalytics,
) : StreamingSessionAnalyticsFactory, ConsentRequestObserver, KoinComponent {
    private val log: Logger by inject { parametersOf(this) }
    private val gate: Any = Any()
    private val pending: MutableMap<SessionKey, PendingSession> = HashMap()
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    override fun beginConsent(key: SessionKey, streamMode: StreamMode, entryPoint: EntryPoint): StreamingSessionAnalytics {
        val tracker = StreamingSessionAnalyticsTracker(analytics, streamMode, entryPoint) {
            reportFailure("Streaming analytics sink failed", it)
        }
        val session = PendingSession(key, tracker)
        val attached = synchronized(gate) {
            if (key in pending) false else {
                pending[key] = session
                true
            }
        }
        if (!attached) {
            reportFailure("Duplicate consent analytics binding")
            return NoOpSession
        }
        return session
    }

    override fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup) {
        StreamingSessionAnalyticsTracker(analytics, streamMode, entryPoint) {
            reportFailure("Streaming analytics sink failed", it)
        }.apply {
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
            reportFailure("Consent analytics observation failed", failure)
        }
    }

    private fun reportFailure(message: String, failure: Throwable? = null) {
        try {
            val detail = if (failure == null) message else "$message (${failure.javaClass.simpleName})"
            log.e("analytics", detail)
        } catch (_: Throwable) {
            // Analytics diagnostics must never affect capture or consent handling.
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
                reportFailure("Main looper rejected consent analytics settlement")
            } catch (failure: Throwable) {
                remove(this)
                reportFailure("Could not schedule consent analytics settlement", failure)
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
