package io.screenstream.capture.testutil

import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import java.util.concurrent.atomic.AtomicLong

internal class MutableElapsedRealtimeClock(
    initialNanos: Long = 0L,
) : ElapsedRealtimeClock {
    private val valueNanos = AtomicLong(initialNanos)

    override fun nowNanos(): Long = valueNanos.get()

    internal fun setNanos(value: Long) {
        valueNanos.set(value)
    }

    internal fun advanceBy(deltaNanos: Long): Long = valueNanos.updateAndGet { current ->
        Math.addExact(current, deltaNanos)
    }
}
