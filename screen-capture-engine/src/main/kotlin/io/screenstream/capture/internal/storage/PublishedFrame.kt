package io.screenstream.capture.internal.storage

import io.screenstream.capture.CaptureOutputInfo

/**
 * Immutable payload and metadata identity for one committed fresh output. Cached-first delivery preserves the original
 * frame identity and metadata.
 */
internal class PublishedFrame(
    internal val payload: ImmutableEncodedPayload,
    internal val outputInfo: CaptureOutputInfo,
    internal val sequence: Long,
    internal val outputTimestampElapsedRealtimeNanos: Long,
) {
    init {
        require(sequence > 0L)
        require(outputTimestampElapsedRealtimeNanos >= 0L)
    }
}
