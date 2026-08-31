package io.screenstream.capture.internal.runtime

internal fun interface ElapsedRealtimeClock {
    fun nowNanos(): Long

    companion object {
        internal const val NANOS_PER_MILLISECOND: Long = 1_000_000L
        internal const val NANOS_PER_SECOND: Long = 1_000_000_000L
    }
}
