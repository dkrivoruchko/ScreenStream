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

    fun start() {
        if (job?.isActive == true) return
        startNs = System.nanoTime()
        totalBytes.set(0)
        job = scope.launch {
            while (isActive) {
                delay(1000)
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - startNs
                val currentBytes = totalBytes.getAndSet(0)
                startNs = nowNs
                if (elapsedNs <= 0) {
                    continue
                }
                val bitsPerSecond = currentBytes * 8_000_000_000L / elapsedNs
                onBitrate(bitsPerSecond)
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
