package info.dvkr.screenstream.common.module

import android.app.Activity
import android.app.ForegroundServiceTypeException
import android.app.ServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog

public class ProjectionCoordinator(
    private val tag: String,
    private val projectionManager: MediaProjectionManager,
    private val callbackHandler: Handler,
    private val startForeground: (Int) -> Unit,
    private val onProjectionStopped: (generation: Long) -> Unit
) {

    private companion object {
        private const val PROJECTION_ACQUIRE_RETRY_DELAY_MS = 100L
        private const val PROJECTION_ACQUIRE_RETRY_WINDOW_MS = 1500L
    }

    public enum class CachedIntentAction {
        KEEP,
        INVALIDATE
    }

    public sealed interface StartResult {
        public val cause: Throwable?
            get() = null
        public val cachedIntentAction: CachedIntentAction
            get() = CachedIntentAction.KEEP

        public data object Busy : StartResult
        public data class Started(val generation: Long, val mediaProjection: MediaProjection, val audioCaptureAllowed: Boolean) : StartResult
        public data class Interrupted(
            override val cause: Throwable,
            override val cachedIntentAction: CachedIntentAction = CachedIntentAction.INVALIDATE
        ) : StartResult

        public data class Blocked(
            override val cause: Throwable,
            override val cachedIntentAction: CachedIntentAction = CachedIntentAction.KEEP
        ) : StartResult

        public data class Fatal(
            override val cause: Throwable,
            override val cachedIntentAction: CachedIntentAction = CachedIntentAction.KEEP
        ) : StartResult
    }

    private class BusyStartException : IllegalStateException("Projection start busy")

    private data class PendingStart(
        val generation: Long,
        val requiresAudioForegroundService: Boolean,
        val foregroundStartedAtMs: Long = 0L
    )

    private data class ActiveSession(val generation: Long, val mediaProjection: MediaProjection, val callback: MediaProjection.Callback)

    private val lock = Any()

    private var generationCounter: Long = 0L
    private var pendingStart: PendingStart? = null
    private var activeSession: ActiveSession? = null

    private fun clearStartingState() {
        pendingStart = null
    }

    private fun getInitialForegroundServiceType(requiresAudioForegroundService: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requiresAudioForegroundService) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }

    public fun startForegroundForProjection(requiresAudioForegroundService: Boolean): Throwable? {
        val pending = synchronized(lock) {
            if (pendingStart != null || activeSession != null) {
                XLog.d(getLog("startForegroundForProjection[$tag]", "Busy. starting=${pendingStart != null}, active=${activeSession != null}"))
                return BusyStartException()
            }
            PendingStart(
                generation = generationCounter + 1,
                requiresAudioForegroundService = requiresAudioForegroundService
            ).also {
                generationCounter = it.generation
                pendingStart = it
                XLog.d(getLog("startForegroundForProjection[$tag]", "Starting generation=${it.generation}, requiresAudioFgs=${it.requiresAudioForegroundService}"))
            }
        }

        try {
            startForeground(getInitialForegroundServiceType(pending.requiresAudioForegroundService))
            val startedAt = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (pendingStart?.generation == pending.generation) pendingStart = pending.copy(foregroundStartedAtMs = startedAt)
            }
            XLog.i(getLog("startForegroundForProjection[$tag]", "Started. generation=${pending.generation}, requiresAudioFgs=${pending.requiresAudioForegroundService}"))
            return null
        } catch (cause: Throwable) {
            synchronized(lock) { clearStartingState() }
            XLog.w(getLog("startForegroundForProjection[$tag]", "Failed. generation=${pending.generation}, cause=${cause.javaClass.simpleName}: ${cause.message}"))
            return cause
        }
    }

    public fun asForegroundStartResult(cause: Throwable): StartResult = when {
        cause is BusyStartException -> StartResult.Busy
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cause is ForegroundServiceTypeException -> {
            XLog.i(getLog("asForegroundStartResult[$tag]", "Fatal foreground start on Android 14+. cause=${cause.javaClass.simpleName}: ${cause.message}"))
            StartResult.Fatal(cause)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cause is ServiceStartNotAllowedException -> StartResult.Blocked(cause)
        else -> StartResult.Fatal(cause)
    }

    public fun startProjection(
        permissionIntent: Intent,
        buildPipeline: (generation: Long, mediaProjection: MediaProjection, audioCaptureAllowed: Boolean, isStartupStillValid: () -> Boolean) -> Boolean
    ): StartResult {
        val pending = synchronized(lock) {
            when {
                activeSession != null -> return StartResult.Busy
                pendingStart == null -> return StartResult.Fatal(IllegalStateException("startProjection called without foreground start"))
                else -> requireNotNull(pendingStart)
            }
        }
        val generation = pending.generation
        val requiresAudioForegroundService = pending.requiresAudioForegroundService
        val foregroundStartDelay =
            if (pending.foregroundStartedAtMs > 0L) SystemClock.elapsedRealtime() - pending.foregroundStartedAtMs else -1L

        XLog.d(getLog("startProjection[$tag]", "Starting generation=$generation, requiresAudioFgs=$requiresAudioForegroundService, afterForeground=${foregroundStartDelay}ms"))

        var mediaProjection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null

        try {
            val projectionStartTime = SystemClock.elapsedRealtime()
            var projectionRetried = false
            val projection = try {
                projectionManager.getMediaProjection(Activity.RESULT_OK, permissionIntent)
            } catch (cause: SecurityException) {
                val shouldRetry = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && foregroundStartDelay in 0..PROJECTION_ACQUIRE_RETRY_WINDOW_MS
                if (!shouldRetry) {
                    synchronized(lock) { clearStartingState() }
                    return StartResult.Fatal(cause, CachedIntentAction.INVALIDATE)
                }
                projectionRetried = true
                XLog.w(getLog("startProjection[$tag]", "Projection acquisition failed on first attempt. Retrying in ${PROJECTION_ACQUIRE_RETRY_DELAY_MS}ms. generation=$generation afterForeground=${foregroundStartDelay}ms cause=${cause.javaClass.simpleName}: ${cause.message}"))
                SystemClock.sleep(PROJECTION_ACQUIRE_RETRY_DELAY_MS)
                try {
                    projectionManager.getMediaProjection(Activity.RESULT_OK, permissionIntent)
                } catch (retryCause: SecurityException) {
                    synchronized(lock) { clearStartingState() }
                    return StartResult.Fatal(retryCause, CachedIntentAction.INVALIDATE)
                }
            }
            if (projection == null) {
                val cause = IllegalStateException("MediaProjectionManager.getMediaProjection returned null")
                XLog.e(getLog("startProjection[$tag]", "Projection acquisition failed. generation=$generation, cause=${cause.message}"))
                synchronized(lock) { clearStartingState() }
                return StartResult.Fatal(cause, CachedIntentAction.INVALIDATE)
            }
            mediaProjection = projection
            if (projectionRetried) {
                XLog.i(getLog("startProjection[$tag]", "Projection acquisition recovered after retry. generation=$generation, elapsed=${SystemClock.elapsedRealtime() - projectionStartTime}ms"))
            }

            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    val shouldNotify = synchronized(lock) {
                        val session = activeSession
                        when {
                            session != null && session.generation == generation && session.mediaProjection === projection -> {
                                activeSession = null
                                XLog.i(getLog("MediaProjection.Callback[$tag]", "onStop. g=$generation, state=active"))
                                true
                            }

                            session == null && pendingStart?.generation == generation -> {
                                clearStartingState()
                                XLog.i(getLog("MediaProjection.Callback[$tag]", "onStop. g=$generation, state=startup"))
                                false
                            }

                            else -> {
                                XLog.i(getLog("MediaProjection.Callback[$tag]", "onStop stale. g=$generation"))
                                false
                            }
                        }
                    }
                    if (shouldNotify) onProjectionStopped(generation)
                }
            }
            projection.registerCallback(callback, callbackHandler)

            if (requiresAudioForegroundService) XLog.i(getLog("startProjection[$tag]", "Audio FGS ok. g=$generation"))

            val isStartupStillValid = {
                synchronized(lock) { pendingStart?.generation == generation }
            }

            val pipelineStarted = runCatching {
                buildPipeline(generation, projection, requiresAudioForegroundService, isStartupStillValid)
            }.onFailure { cause ->
                XLog.e(getLog("startProjection[$tag]", "buildPipeline failed. generation=$generation, cause=${cause.javaClass.simpleName}: ${cause.message}"))
            }.getOrElse {
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) { clearStartingState() }
                return StartResult.Fatal(it, CachedIntentAction.INVALIDATE)
            }
            if (!pipelineStarted) {
                val startupInterrupted = synchronized(lock) { pendingStart?.generation != generation }
                val cause = if (startupInterrupted) IllegalStateException("Projection stopped during startup. generation=$generation")
                else IllegalStateException("buildPipeline returned false")
                XLog.w(getLog("startProjection[$tag]", "Pipeline was not started. generation=$generation, cause=${cause.message}"))
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) { clearStartingState() }
                return if (startupInterrupted) StartResult.Interrupted(cause) else StartResult.Fatal(cause, CachedIntentAction.INVALIDATE)
            }

            val started = synchronized(lock) {
                if (pendingStart?.generation != generation) {
                    false
                } else {
                    activeSession = ActiveSession(generation = generation, mediaProjection = projection, callback = callback)
                    clearStartingState()
                    true
                }
            }
            if (!started) {
                val cause = IllegalStateException("Projection stopped during startup. generation=$generation")
                XLog.i(getLog("startProjection[$tag]", "Interrupted. g=$generation, cause=${cause.message}"))
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) { clearStartingState() }
                return StartResult.Interrupted(cause)
            }

            XLog.i(getLog("startProjection[$tag]", "Started. g=$generation, audioFgs=$requiresAudioForegroundService"))
            return StartResult.Started(generation, projection, requiresAudioForegroundService)
        } catch (cause: Throwable) {
            XLog.e(getLog("startProjection[$tag]", "Unexpected failure. generation=$generation, cause=${cause.javaClass.simpleName}: ${cause.message}"))
            callback?.let { runCatching { mediaProjection?.unregisterCallback(it) } }
            runCatching { mediaProjection?.stop() }
            synchronized(lock) { clearStartingState() }
            val cachedIntentAction = if (mediaProjection != null) CachedIntentAction.INVALIDATE else CachedIntentAction.KEEP
            return StartResult.Fatal(cause, cachedIntentAction)
        }
    }

    public fun stop() {
        val session = synchronized(lock) {
            val current = activeSession
            activeSession = null
            clearStartingState()
            current
        }
        if (session == null) {
            XLog.d(getLog("stop[$tag]", "No active session"))
            return
        }

        XLog.i(getLog("stop[$tag]", "Stopping generation=${session.generation}"))
        runCatching { session.mediaProjection.unregisterCallback(session.callback) }.onFailure {
            XLog.w(getLog("stop[$tag]", "unregisterCallback failed. generation=${session.generation}"), it)
        }
        runCatching { session.mediaProjection.stop() }.onFailure {
            XLog.w(getLog("stop[$tag]", "mediaProjection.stop failed. generation=${session.generation}"), it)
        }
    }

    public fun getActiveGeneration(): Long? = synchronized(lock) { activeSession?.generation }
}
