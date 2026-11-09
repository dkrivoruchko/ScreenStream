package io.screenstream.capture.internal.runtime

/** Nonnegative monotonic nanoseconds since boot, including deep sleep; unrelated to wall-clock epoch time. */
internal fun interface ElapsedRealtimeClock {
    fun nowNanos(): Long

    companion object {
        internal const val NANOS_PER_MILLISECOND: Long = 1_000_000L
        internal const val NANOS_PER_SECOND: Long = 1_000_000_000L
    }
}
