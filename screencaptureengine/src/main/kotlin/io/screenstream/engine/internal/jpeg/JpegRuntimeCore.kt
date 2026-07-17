package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence

internal const val jpegEnteredOperationSafetyNanos: Long = 15_000_000_000L

internal enum class NativeJpegHealth {
    Enabled,
    Disabled,
}

internal class JpegFiniteOperationIdentity internal constructor(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val initialWakeGeneration: Long,
    internal val timeoutCause: Throwable,
)

internal class JpegIoOperation<E : OperationEvidence> internal constructor(
    internal val occurrence: OperationOccurrence<E>,
    internal val runnable: Runnable,
)
