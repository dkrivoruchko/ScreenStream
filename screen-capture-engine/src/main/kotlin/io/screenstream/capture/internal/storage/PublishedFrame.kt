package io.screenstream.capture.internal.storage

import io.screenstream.capture.ScreenCaptureEffectiveParameters

internal class PublishedFrame(
    internal val payload: ImmutableEncodedPayload,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val sequence: Long,
    internal val timestampElapsedRealtimeNanos: Long,
) {
    init {
        require(sequence > 0L)
        require(timestampElapsedRealtimeNanos >= 0L)
    }
}
