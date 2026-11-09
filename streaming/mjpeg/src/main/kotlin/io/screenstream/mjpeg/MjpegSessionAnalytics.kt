package io.screenstream.mjpeg

import android.app.ForegroundServiceTypeException
import android.app.ServiceStartNotAllowedException
import android.os.Build
import android.os.SystemClock
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import io.screenstream.core.capture.ConsentLaunchException
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.logW
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** One capture's observations; never owns admission, media, or cleanup. */
internal class MjpegSessionAnalytics(
    private val factory: StreamingSessionAnalyticsFactory,
    private val attempt: StreamingModule.CaptureAttemptId,
) {
    private class StopSnapshot(val reason: MjpegCaptureSession.StopReason, val activeConsumers: Int)

    private val gate = Any()
    private var handle: StreamingSessionAnalytics? = null
    private var stopSnapshot: StopSnapshot? = null

    /** Called once inside the entered capture worker; the functional call keeps its original outcome. */
    suspend fun requestConsent(
        access: ScreenCaptureAccess,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult {
        observe {
            synchronized(gate) {
                val key = SessionKey(attempt.instanceId.moduleId.value, attempt.instanceId.uuid, attempt.uuid)
                handle = factory.beginConsent(key, StreamMode.MJPEG, EntryPoint.BUTTON)
            }
        }
        try {
            val result = access.requestConsent(attempt, attemptJob) { observeForeground(prepareForeground) }
            if (result is ScreenCaptureAccess.ConsentResult.NotGranted) {
                observe {
                    synchronized(gate) {
                        when (result.reason) {
                            ScreenCaptureAccess.NoGrantReason.Declined -> handle?.startFailed(StartFailGroup.PERMISSION_DENIED)
                            ScreenCaptureAccess.NoGrantReason.Busy -> handle?.startFailed(StartFailGroup.BUSY)
                            ScreenCaptureAccess.NoGrantReason.ForegroundRejected,
                            ScreenCaptureAccess.NoGrantReason.EducationDeclined,
                            ScreenCaptureAccess.NoGrantReason.HostUnavailable -> handle?.startAborted()
                        }
                    }
                }
            }
            return result
        } catch (cause: Throwable) {
            observe {
                synchronized(gate) {
                    if (cause is CancellationException) {
                        handle?.startAborted()
                    } else {
                        val group = if (cause is ConsentLaunchException) StartFailGroup.PERMISSION_DENIED else StartFailGroup.UNKNOWN
                        handle?.startFailed(group)
                    }
                }
            }
            throw cause
        } finally {
            observe { synchronized(gate) { handle?.consentCallFinished() } }
        }
    }

    private fun observeForeground(call: () -> Boolean): Boolean {
        try {
            val accepted = call()
            if (!accepted) observe { synchronized(gate) { handle?.startAborted() } }
            return accepted
        } catch (cause: Throwable) {
            observe {
                synchronized(gate) {
                    if (cause is CancellationException) {
                        handle?.startAborted()
                    } else {
                        handle?.foregroundFailed(foregroundFailureGroup(cause))
                    }
                }
            }
            throw cause
        }
    }

    fun startupFinished(failure: MjpegCaptureSession.State.Failure?) = observe {
        synchronized(gate) {
            if (failure == null) {
                handle?.startAborted()
            } else {
                val group = when {
                    failure.cause is ConsentLaunchException -> StartFailGroup.PERMISSION_DENIED
                    failure.stage == "Startup.foreground" -> foregroundFailureGroup(failure.cause)
                    failure.stage == "Startup.projection" && failure.cause is SecurityException -> StartFailGroup.BLOCKED
                    failure.stage == "Startup.projection" || failure.stage.startsWith("Engine.") -> StartFailGroup.FATAL
                    else -> StartFailGroup.UNKNOWN
                }
                handle?.startFailed(group)
            }
        }
    }

    /** Called under the session gate, before HTTP sees its first terminal capture transition. */
    fun stopAccepted(reason: MjpegCaptureSession.StopReason, atElapsedRealtimeMs: Long, activeConsumers: () -> Int) = observe {
        synchronized(gate) {
            if (stopSnapshot != null) return@synchronized
            val snapshot = StopSnapshot(reason, activeConsumers())
            stopSnapshot = snapshot
            handle?.consumersChanged(snapshot.activeConsumers, atElapsedRealtimeMs)
        }
    }

    fun started(activeConsumers: () -> Int, atElapsedRealtimeMs: Long) = observe {
        synchronized(gate) {
            // A start completing after Stop begins useful time now, never at the earlier cutoff.
            handle?.started(stopSnapshot?.activeConsumers ?: activeConsumers(), atElapsedRealtimeMs)
        }
    }

    fun observeConsumers(infoProvider: () -> StateFlow<HttpDelivery.Info>, scope: CoroutineScope) {
        flow { infoProvider().collect { emit(it) } }
            .map { delivery -> delivery.clients.count { it.state == HttpDelivery.ClientInfo.State.Active } }
            .distinctUntilChanged()
            .onEach { count -> consumersChanged(count, SystemClock.elapsedRealtime()) }
            .catch { cause -> this@MjpegSessionAnalytics.logW("MjpegSessionAnalytics", "MJPEG analytics observation failed", cause) }
            .launchIn(scope)
    }

    fun consumersChanged(activeConsumers: Int, atElapsedRealtimeMs: Long) = observe {
        synchronized(gate) {
            if (stopSnapshot == null) handle?.consumersChanged(activeConsumers, atElapsedRealtimeMs)
        }
    }

    fun mediaStopped(atElapsedRealtimeMs: Long) = observe {
        synchronized(gate) {
            val snapshot = stopSnapshot
            val rawReason = when (snapshot?.reason) {
                MjpegCaptureSession.StopReason.User -> "user"
                MjpegCaptureSession.StopReason.System, MjpegCaptureSession.StopReason.Shutdown -> "system"
                MjpegCaptureSession.StopReason.Error -> "error"
                null -> null
            }
            handle?.ended(rawReason, snapshot?.activeConsumers ?: 0, atElapsedRealtimeMs)
        }
    }

    private fun foregroundFailureGroup(cause: Throwable): StartFailGroup = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cause is ForegroundServiceTypeException -> StartFailGroup.FATAL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cause is ServiceStartNotAllowedException -> StartFailGroup.BLOCKED
        else -> StartFailGroup.FATAL
    }

    private inline fun observe(action: () -> Unit) {
        try {
            action()
        } catch (cause: Throwable) {
            logW("MjpegSessionAnalytics", "MJPEG analytics observation failed", cause)
        }
    }
}
