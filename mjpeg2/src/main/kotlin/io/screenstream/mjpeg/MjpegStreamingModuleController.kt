package io.screenstream.mjpeg

import android.os.SystemClock
import android.util.Log
import io.screenstream.streaming.foreground.ForegroundControl
import io.screenstream.streaming.module.StreamingModuleApi
import io.screenstream.streaming.module.StreamingModuleHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.milliseconds

/**
 * One controller per [StreamingModuleApi.InstanceId], created by [StreamingModuleHost]'s factory
 * after the Service launch is accepted for attachment. [start] begins ordinary control work only
 * after conditional installation. Shutdown closes commands and starts independent cleanup; that
 * cleanup can outlive a coordinator wait or the Android Service lifetime. Slice A keeps capture idle.
 */
internal class MjpegStreamingModuleController(
    private val callbacks: StreamingModuleApi.Controller.Callbacks,
    private val foregroundControl: ForegroundControl,
) : StreamingModuleApi.Controller {
    private sealed interface Command {
        data object Start : Command
        data class Stop(val attempt: StreamingModuleApi.CaptureAttemptId) : Command
    }

    private sealed interface LoopEvent {
        data class CommandReceived(val command: Command) : LoopEvent
        data object Closed : LoopEvent
        data object HeartbeatDue : LoopEvent
    }

    override val instanceId: StreamingModuleApi.InstanceId = callbacks.instanceId

    private val gate: Any = Any()
    private val commands: Channel<Command> = Channel(COMMAND_CAPACITY)
    private val workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupResult: CompletableDeferred<Boolean> = CompletableDeferred()
    private var started: Boolean = false
    private var closing: Boolean = false
    private var controlJob: Job? = null

    override fun start() {
        if (!callbacks.isCurrent()) {
            requestShutdown()
            return
        }
        val job = synchronized(gate) {
            if (started || closing) return
            started = true
            workScope.launch(start = CoroutineStart.LAZY) { runControlLoop() }.also { controlJob = it }
        }
        // Shutdown can cancel this lazy job before its first instruction. The worker checks the
        // exact instance again before reporting or admitting any future resource.
        job.start()
    }

    fun requestStreamStart() {
        submit(Command.Start)
    }

    fun requestStreamStop(attempt: StreamingModuleApi.CaptureAttemptId) {
        if (attempt.instanceId == instanceId) submit(Command.Stop(attempt))
    }

    private fun submit(command: Command) {
        if (!callbacks.isCurrent()) return
        val overflow = synchronized(gate) {
            if (closing || !started) return
            commands.trySend(command).isFailure
        }
        if (overflow) {
            // A full command queue cannot silently lose a required future Stop.
            callbacks.reportFailed()
            requestShutdown()
        }
    }

    override fun requestShutdown() {
        val job = synchronized(gate) {
            if (closing) return
            closing = true
            commands.close()
            controlJob
        }
        job?.cancel()
        workScope.cancel()
        val launched = try {
            callbacks.launchCleanup {
                var completed = false
                try {
                    job?.join()
                    completed = true
                } catch (failure: Throwable) {
                    Log.e(TAG, "MJPEG2 control cleanup failed", failure)
                } finally {
                    cleanupResult.complete(completed)
                }
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "MJPEG2 cleanup could not be scheduled", failure)
            cleanupResult.complete(false)
            return
        }
        // A cancelled child can complete before entering its block. Its exact handle closes that
        // gap without cancelling or owning the module host's surviving supervisor.
        launched.invokeOnCompletion { cleanupResult.complete(false) }
    }

    override suspend fun awaitCleanup(): Boolean {
        return cleanupResult.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runControlLoop() {
        try {
            if (!callbacks.isCurrent()) return
            var nextHeartbeat = SystemClock.uptimeMillis()
            while (true) {
                if (!callbacks.isCurrent() || isClosing()) return
                val now = SystemClock.uptimeMillis()
                if (now >= nextHeartbeat) {
                    callbacks.reportRunning(
                        StreamingModuleApi.Status(
                            isStreaming = false,
                            hasConsumer = false,
                            captureAttempt = null,
                        ),
                        now,
                    )
                    nextHeartbeat = now + HEARTBEAT_MILLIS
                }

                val waitMillis = (nextHeartbeat - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val event: LoopEvent = select {
                    // Timeout first also wins a simultaneous ready command at the deadline.
                    onTimeout(waitMillis.milliseconds) { LoopEvent.HeartbeatDue }
                    commands.onReceiveCatching { result ->
                        if (result.isClosed) LoopEvent.Closed
                        else LoopEvent.CommandReceived(result.getOrThrow())
                    }
                }
                when (event) {
                    LoopEvent.Closed -> return
                    LoopEvent.HeartbeatDue -> Unit
                    // Settings, address, capture, and HTTP producers arrive in later slices.
                    is LoopEvent.CommandReceived -> when (event.command) {
                        Command.Start -> Unit
                        is Command.Stop -> Unit
                    }
                }
            }
        } catch (failure: CancellationException) {
            if (!isClosing()) markFailure(failure)
        } catch (failure: Throwable) {
            markFailure(failure)
        } finally {
            requestShutdown()
        }
    }

    private fun markFailure(failure: Throwable) {
        val report = !isClosing()
        Log.e(TAG, "MJPEG2 control loop failed", failure)
        if (report) callbacks.reportFailed()
    }

    private fun isClosing(): Boolean = synchronized(gate) { closing }

    private companion object {
        private const val TAG: String = "MjpegStreamingModuleController"
        private const val COMMAND_CAPACITY: Int = 32
        private const val HEARTBEAT_MILLIS: Long = 1_000L
    }
}
