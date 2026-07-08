package dev.dmkr.screencaptureengine.internal.runtime

import java.util.concurrent.atomic.AtomicBoolean

internal class ProjectionStopArbiter {
    private val lock = Any()
    private val rawStopObserved = AtomicBoolean(false)

    val projectionStopObserved: Boolean
        get() = rawStopObserved.get()

    fun markRawStopObserved(): Boolean =
        synchronized(lock) {
            if (rawStopObserved.get()) {
                false
            } else {
                rawStopObserved.set(true)
                true
            }
        }

    fun <T> arbitratePublicOutcome(block: (rawStopObserved: Boolean) -> T): T =
        synchronized(lock) {
            block(rawStopObserved.get())
        }
}
