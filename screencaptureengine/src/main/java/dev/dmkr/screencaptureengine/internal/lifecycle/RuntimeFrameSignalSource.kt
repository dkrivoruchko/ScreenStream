package dev.dmkr.screencaptureengine.internal.lifecycle

import android.graphics.SurfaceTexture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Enqueue-only bridge from `SurfaceTexture.OnFrameAvailableListener` into the runtime scheduler.
 *
 * The callback thread must not touch GL or account drops. Each enqueue replaces the previous
 * undrained signal for latest-only production; skipped intermediate platform frames are input
 * coalescing until the scheduler materializes a production attempt.
 */
internal fun interface RuntimeFrameSignalSink {
    fun enqueueFrameAvailable(generation: Long)
}

/**
 * Latest-only frame signal source drained by runtime scheduling, not a raw-frame queue.
 */
internal interface RuntimeFrameSignalSource {
    fun drainLatestFrameSignal(): RuntimeFrameSignal?
}

internal data class RuntimeFrameSignal(
    val generation: Long,
    val sequence: Long,
) {
    init {
        require(generation > 0L) { "generation must be positive, was $generation" }
        require(sequence > 0L) { "sequence must be positive, was $sequence" }
    }
}

internal class ConflatedRuntimeFrameSignalSource : RuntimeFrameSignalSink, RuntimeFrameSignalSource {
    private val nextSequence = AtomicLong()
    private val latestSignal = AtomicReference<RuntimeFrameSignal?>()

    override fun enqueueFrameAvailable(generation: Long) {
        latestSignal.set(
            RuntimeFrameSignal(
                generation = generation,
                sequence = nextSequence.incrementAndGet(),
            ),
        )
    }

    override fun drainLatestFrameSignal(): RuntimeFrameSignal? =
        latestSignal.getAndSet(null)
}

internal class RuntimeProjectionTargetFrameAvailableListener internal constructor(
    private val generation: Long,
    private val sink: RuntimeFrameSignalSink,
) : SurfaceTexture.OnFrameAvailableListener {
    init {
        require(generation > 0L) { "generation must be positive, was $generation" }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        sink.enqueueFrameAvailable(generation = generation)
    }
}
