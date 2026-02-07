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
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog

public class ProjectionCoordinator(
    private val tag: String,
    private val projectionManager: MediaProjectionManager,
    private val callbackHandler: Handler,
    private val startForeground: (Int) -> Unit,
    private val onProjectionStopped: (generation: Long) -> Unit
) {

    public sealed interface StartResult {
        public val cause: Throwable?
            get() = null

        public data object Busy : StartResult
        public data class Started(val generation: Long, val mediaProjection: MediaProjection, val microphoneEnabled: Boolean) : StartResult
        public data class Blocked(override val cause: Throwable) : StartResult
        public data class Fatal(override val cause: Throwable) : StartResult
    }

    private data class ActiveSession(val generation: Long, val mediaProjection: MediaProjection, val callback: MediaProjection.Callback)

    private val lock = Any()

    private var generationCounter: Long = 0L
    private var isStarting: Boolean = false
    private var startingGeneration: Long? = null
    private var activeSession: ActiveSession? = null

    public fun start(
        permissionIntent: Intent,
        wantsMicrophone: Boolean,
        buildPipeline: (generation: Long, mediaProjection: MediaProjection, microphoneEnabled: Boolean) -> Boolean
    ): StartResult {
        synchronized(lock) {
            if (isStarting || activeSession != null) {
                XLog.d(getLog("start[$tag]", "Busy. isStarting=$isStarting, active=${activeSession != null}"))
                return StartResult.Busy
            }
            isStarting = true
            startingGeneration = null
        }

        val generation = synchronized(lock) {
            generationCounter += 1
            generationCounter
        }
        synchronized(lock) { startingGeneration = generation }

        XLog.d(getLog("start[$tag]", "Starting generation=$generation, wantsMicrophone=$wantsMicrophone"))

        var mediaProjection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null

        try {
            runCatching {
                startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }.onFailure { cause ->
                XLog.w(getLog("start[$tag]", "Projection FGS start failed. generation=$generation"), cause)
                val result = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cause is ForegroundServiceTypeException ->
                        StartResult.Fatal(cause)

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cause is ServiceStartNotAllowedException ->
                        StartResult.Blocked(cause)

                    else -> StartResult.Fatal(cause)
                }
                synchronized(lock) {
                    isStarting = false
                    startingGeneration = null
                }
                return result
            }

            val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, permissionIntent)
            if (projection == null) {
                val cause = IllegalStateException("MediaProjectionManager.getMediaProjection returned null")
                XLog.e(getLog("start[$tag]", "Projection acquisition failed. generation=$generation"), cause)
                synchronized(lock) {
                    isStarting = false
                    startingGeneration = null
                }
                return StartResult.Fatal(cause)
            }
            mediaProjection = projection

            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    val shouldHandle = synchronized(lock) {
                        val session = activeSession
                        when {
                            session != null && session.generation == generation && session.mediaProjection === projection -> {
                                activeSession = null
                                true
                            }

                            session == null && isStarting && startingGeneration == generation -> {
                                isStarting = false
                                startingGeneration = null
                                true
                            }

                            else -> false
                        }
                    }
                    if (!shouldHandle) {
                        XLog.i(getLog("MediaProjection.Callback[$tag]", "Stale callback ignored. generation=$generation"))
                        return
                    }
                    XLog.i(getLog("MediaProjection.Callback[$tag]", "onStop. generation=$generation"))
                    onProjectionStopped(generation)
                }
            }
            projection.registerCallback(callback, callbackHandler)

            var microphoneEnabled = false
            if (wantsMicrophone) {
                runCatching {
                    startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }.onSuccess {
                    microphoneEnabled = true
                    XLog.i(getLog("start[$tag]", "Microphone FGS upgrade succeeded. generation=$generation"))
                }.onFailure { cause ->
                    XLog.w(getLog("start[$tag]", "Microphone FGS upgrade failed. Continuing video-only. generation=$generation"), cause)
                }
            }

            val pipelineStarted = runCatching {
                buildPipeline(generation, projection, microphoneEnabled)
            }.onFailure { cause ->
                XLog.e(getLog("start[$tag]", "buildPipeline failed. generation=$generation"), cause)
            }.getOrElse {
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) {
                    isStarting = false
                    startingGeneration = null
                }
                return StartResult.Fatal(it)
            }
            if (!pipelineStarted) {
                val cause = IllegalStateException("buildPipeline returned false")
                XLog.w(getLog("start[$tag]", "Pipeline was not started. generation=$generation"), cause)
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) {
                    isStarting = false
                    startingGeneration = null
                }
                return StartResult.Fatal(cause)
            }

            val startupInterrupted = synchronized(lock) { isStarting.not() || startingGeneration != generation }
            if (startupInterrupted) {
                val cause = IllegalStateException("Projection stopped during startup. generation=$generation")
                XLog.w(getLog("start[$tag]", "Start interrupted"), cause)
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                synchronized(lock) {
                    isStarting = false
                    startingGeneration = null
                }
                return StartResult.Fatal(cause)
            }

            synchronized(lock) {
                activeSession = ActiveSession(generation = generation, mediaProjection = projection, callback = callback)
                isStarting = false
                startingGeneration = null
            }
            XLog.i(getLog("start[$tag]", "Started generation=$generation, microphoneEnabled=$microphoneEnabled"))
            return StartResult.Started(generation = generation, mediaProjection = projection, microphoneEnabled = microphoneEnabled)
        } catch (cause: Throwable) {
            XLog.e(getLog("start[$tag]", "Unexpected start failure. generation=$generation"), cause)
            callback?.let { runCatching { mediaProjection?.unregisterCallback(it) } }
            runCatching { mediaProjection?.stop() }
            synchronized(lock) {
                isStarting = false
                startingGeneration = null
            }
            return StartResult.Fatal(cause)
        }
    }

    public fun stop() {
        val session = synchronized(lock) {
            val current = activeSession
            activeSession = null
            isStarting = false
            startingGeneration = null
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
