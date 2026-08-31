package io.screenstream.capture.internal.encoding

internal val encoderCleanupMismatch: RuntimeException =
    object : RuntimeException("Encoding cleanup did not settle exactly", null, false, false) {}

internal fun checkedJpegEncodeDurationNanos(startedAtNanos: Long, finishedAtNanos: Long): Long {
    require(startedAtNanos >= 0L)
    require(finishedAtNanos >= startedAtNanos)
    return Math.subtractExact(finishedAtNanos, startedAtNanos)
}
