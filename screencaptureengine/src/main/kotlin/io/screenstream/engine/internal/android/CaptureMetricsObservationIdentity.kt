package io.screenstream.engine.internal.android

/** Session transfers this observation-local namespace once; mechanics alone advances its ordinal. */
internal class CaptureMetricsObservationIdentity internal constructor(
    internal val observationIdentity: Long,
) {
    init {
        require(observationIdentity > 0L)
    }
}

internal class CaptureMetricsRefreshOperationIdentity internal constructor(
    internal val observationIdentity: CaptureMetricsObservationIdentity,
    internal val localOrdinal: Long,
)

internal class CaptureMetricsOperationIdentityAllocator internal constructor(
    private val namespace: CaptureMetricsObservationIdentity,
    private val reserved: Set<Long>,
) {
    private var nextOrdinal = 1L

    internal fun nextRefreshIdentity(): CaptureMetricsRefreshOperationIdentity? {
        while (true) {
            val ordinal = nextOrdinal
            if (ordinal <= 0L || ordinal == Long.MAX_VALUE) return null
            nextOrdinal = ordinal + 1L
            if (ordinal !in reserved) {
                return CaptureMetricsRefreshOperationIdentity(namespace, ordinal)
            }
        }
    }
}
