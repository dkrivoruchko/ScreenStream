package info.dvkr.screenstream.rtsp.internal

import android.app.ForegroundServiceTypeException
import android.app.ServiceStartNotAllowedException
import android.os.Build
import android.util.Log
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspError
import io.screenstream.streaming.capture.ConsentLaunchException
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellationException

/** Module-private telemetry ownership; it neither admits capture nor changes its lifetime. */
internal class RtspSessionAnalytics(private val factory: StreamingSessionAnalyticsFactory) {
    private class Session(val key: SessionKey, val streamMode: StreamMode) {
        var handle: StreamingSessionAnalytics? = null
        var consentEntered = false
        var consentFinished = false
        var started = false
        var terminated = false
        var foregroundCallInFlight = false
        var abortDeferred = false
    }

    private val gate = Any()
    private val sessions = mutableMapOf<StreamingModule.CaptureAttemptId, Session>()

    fun accepted(attempt: StreamingModule.CaptureAttemptId, mode: RtspSettings.Values.Mode) {
        synchronized(gate) {
            sessions[attempt] = Session(
                SessionKey(attempt.instanceId.moduleId.value, attempt.instanceId.uuid, attempt.uuid),
                streamMode(mode),
            )
        }
    }

    fun audioPermissionMissingBeforeConsent(mode: RtspSettings.Values.Mode) {
        observe { factory.preflightFailed(streamMode(mode), EntryPoint.BUTTON, StartFailGroup.BLOCKED) }
    }

    /** Called only after entering the original consent coroutine, immediately before Core. */
    fun beginConsent(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || session.consentEntered) return
            session.consentEntered = true
            observe { session.handle = factory.beginConsent(session.key, session.streamMode, EntryPoint.BUTTON) }
        }
    }

    fun beginForegroundCall(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || session.started || session.handle == null) return
            session.foregroundCallInFlight = true
        }
    }

    fun finishForegroundCall(attempt: StreamingModule.CaptureAttemptId, cause: Throwable?, requiresMicrophoneType: Boolean) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (!session.foregroundCallInFlight) return
            session.foregroundCallInFlight = false
            val aborted = cause is CancellationException || (cause == null && session.abortDeferred)
            session.abortDeferred = false
            if (!session.terminated && !session.started && (cause != null || aborted)) {
                session.terminated = true
                observe {
                    if (aborted) session.handle?.startAborted()
                    else session.handle?.startFailed(foregroundFailureGroup(checkNotNull(cause), requiresMicrophoneType))
                }
            }
            if (session.consentFinished && (session.terminated || session.handle == null)) sessions.remove(attempt)
        }
    }

    /** Core facts are observed by the app directly; the module only settles this invocation. */
    fun consentCallFinished(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (!session.consentEntered || session.consentFinished) return
            session.consentFinished = true
            observe { session.handle?.consentCallFinished() }
            if (!session.foregroundCallInFlight && (session.terminated || session.handle == null)) sessions.remove(attempt)
        }
    }

    /** A Stop before coroutine entry must never register a late consent lookup. */
    fun closeUnentered(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (!session.consentEntered && !session.foregroundCallInFlight) sessions.remove(attempt)
        }
    }

    fun consentNotGranted(attempt: StreamingModule.CaptureAttemptId, reason: ScreenCaptureAccess.NoGrantReason) {
        when (reason) {
            ScreenCaptureAccess.NoGrantReason.EducationDeclined -> aborted(attempt)
            ScreenCaptureAccess.NoGrantReason.Declined -> failed(attempt, StartFailGroup.PERMISSION_DENIED)
            ScreenCaptureAccess.NoGrantReason.Busy -> failed(attempt, StartFailGroup.BUSY)
            ScreenCaptureAccess.NoGrantReason.HostUnavailable,
            ScreenCaptureAccess.NoGrantReason.ForegroundRejected -> failed(attempt, StartFailGroup.BLOCKED)
        }
    }

    fun consentFailed(attempt: StreamingModule.CaptureAttemptId, cause: Throwable) =
        failed(attempt, if (cause is ConsentLaunchException) StartFailGroup.PERMISSION_DENIED else StartFailGroup.FATAL)

    fun audioPermissionMissing(attempt: StreamingModule.CaptureAttemptId) = failed(attempt, StartFailGroup.BLOCKED)

    fun invalidClientAddress(attempt: StreamingModule.CaptureAttemptId) = failed(attempt, StartFailGroup.UNKNOWN)

    fun projectionFailed(attempt: StreamingModule.CaptureAttemptId, cause: Throwable) =
        failed(attempt, if (cause is SecurityException) StartFailGroup.BLOCKED else StartFailGroup.FATAL)

    fun workerFailed(attempt: StreamingModule.CaptureAttemptId?) {
        if (attempt != null) failed(attempt, StartFailGroup.UNKNOWN)
    }

    fun started(attempt: StreamingModule.CaptureAttemptId, activeConsumers: Int, atElapsedRealtimeMs: Long) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || session.started) return
            session.started = true
            observe { session.handle?.started(activeConsumers, atElapsedRealtimeMs) }
        }
    }

    fun consumersChanged(attempt: StreamingModule.CaptureAttemptId?, activeConsumers: Int, atElapsedRealtimeMs: Long) {
        if (attempt == null) return
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || !session.started) return
            observe { session.handle?.consumersChanged(activeConsumers, atElapsedRealtimeMs) }
        }
    }

    fun aborted(attempt: StreamingModule.CaptureAttemptId) {
        terminatePending(attempt, isAbort = true) { it.startAborted() }
    }

    fun ended(attempt: StreamingModule.CaptureAttemptId, stopReason: String?, activeConsumers: Int, atElapsedRealtimeMs: Long) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || !session.started) return
            session.terminated = true
            observe { session.handle?.ended(stopReason, activeConsumers, atElapsedRealtimeMs) }
            if (session.consentFinished && !session.foregroundCallInFlight) sessions.remove(attempt)
        }
    }

    private fun failed(attempt: StreamingModule.CaptureAttemptId, group: StartFailGroup) {
        terminatePending(attempt) { it.startFailed(group) }
    }

    private fun terminatePending(attempt: StreamingModule.CaptureAttemptId, isAbort: Boolean = false, terminal: (StreamingSessionAnalytics) -> Unit) {
        synchronized(gate) {
            val session = sessions[attempt] ?: return
            if (session.terminated || session.started) return
            if (isAbort && session.foregroundCallInFlight) {
                session.abortDeferred = true
                return
            }
            session.terminated = true
            observe { session.handle?.let(terminal) }
            if (!session.foregroundCallInFlight && (!session.consentEntered || session.consentFinished)) sessions.remove(attempt)
        }
    }

    private fun foregroundFailureGroup(cause: Throwable, requiresMicrophoneType: Boolean): StartFailGroup = when {
        cause is RtspError.AudioPermissionRequired -> StartFailGroup.BLOCKED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && requiresMicrophoneType && cause is SecurityException -> StartFailGroup.BLOCKED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cause is ForegroundServiceTypeException -> StartFailGroup.FATAL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cause is ServiceStartNotAllowedException -> StartFailGroup.BLOCKED
        else -> StartFailGroup.FATAL
    }

    private fun streamMode(mode: RtspSettings.Values.Mode): StreamMode = when (mode) {
        RtspSettings.Values.Mode.SERVER -> StreamMode.RTSP_SERVER
        RtspSettings.Values.Mode.CLIENT -> StreamMode.RTSP_CLIENT
    }

    private inline fun observe(action: () -> Unit) {
        runCatching(action).onFailure { cause ->
            runCatching { Log.w("RtspSessionAnalytics", "RTSP session analytics failed", cause) }
        }
    }
}
