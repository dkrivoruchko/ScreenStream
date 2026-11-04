package io.screenstream.mjpeg.http

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal class ControlledHttpListener(
    parentJob: Job,
    override val host: String,
    override val port: Int,
) : HttpListener {
    private val engineJob = SupervisorJob(parentJob)
    val startEntered = CompletableDeferred<Unit>()
    val cleanupEntered = CompletableDeferred<Unit>()
    val cleanupFinished = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()
    var cleanupFinishedAtElapsedRealtimeMillis = 0L
        private set
    var startGate: CompletableDeferred<Unit>? = null
    var cleanupGate: CompletableDeferred<Unit>? = null
    var startFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var terminationFailure: Throwable? = null

    override suspend fun startListening() {
        startEntered.complete(Unit)
        startGate?.await()
        startFailure?.let { throw it }
    }

    override suspend fun awaitTermination() {
        ended.await()
        terminationFailure?.let { throw it }
    }

    override fun requestStop() {
        ended.complete(Unit)
        engineJob.cancel()
    }

    override suspend fun stopAndAwaitCleanup() {
        requestStop()
        cleanupEntered.complete(Unit)
        engineJob.join()
        cleanupGate?.await()
        cleanupFailure?.let { throw it }
        cleanupFinishedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        cleanupFinished.complete(Unit)
    }
}
