package info.dvkr.screenstream.rtsp.internal.rtsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal class BitrateCalculator(
    private val scope: CoroutineScope,
    private val onBitrate: suspend (Long) -> Unit
) {
    private val totalBytes = AtomicLong(0)
    private var startNs: Long = 0L
    private var job: Job? = null

    private fun reset() {
        startNs = System.nanoTime()
        totalBytes.set(0)
    }

    fun start() {
        if (job?.isActive == true) return
        reset()
        job = scope.launch {
            while (isActive) {
                delay(1000)
                val currentBytes = totalBytes.getAndSet(0)
                val elapsedNs = System.nanoTime() - startNs
                if (elapsedNs <= 0) {
                    reset()
                    continue
                }
                val bitsPerSecond = currentBytes * 8_000_000_000L / elapsedNs
                onBitrate(bitsPerSecond)
                reset()
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    fun addBytes(size: Int) {
        if (job?.isActive == true) {
            totalBytes.addAndGet(size.toLong())
        }
    }
}