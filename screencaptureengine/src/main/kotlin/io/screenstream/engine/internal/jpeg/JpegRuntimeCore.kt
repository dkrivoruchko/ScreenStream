package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import java.util.concurrent.atomic.AtomicReference

internal const val jpegEnteredOperationSafetyNanos: Long = 15_000_000_000L

internal enum class JpegCarrierMode {
    NativeMalloc,
    ManagedDirect,
}

internal enum class NativeJpegHealth {
    Enabled,
    Disabled,
}

internal interface JpegEndpointOccurrence {
    val executorOperation: PrivateExecutorOperation<*>
    var endpointReleased: Boolean
}

internal class JpegFiniteOperationIdentity internal constructor(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val initialWakeGeneration: Long,
    internal val timeoutCause: Throwable,
)

internal class JpegRuntimeTopologySnapshot internal constructor(
    internal val carrierMode: JpegCarrierMode?,
    internal val nativeHealth: NativeJpegHealth?,
    internal val product: JpegRuntimeProduct?,
    internal val lease: RgbaCarrierLease?,
    internal val replacementSource: JpegRuntimeProduct?,
) : JpegRuntimeTopologyState {
    internal companion object {
        internal val Empty: JpegRuntimeTopologySnapshot = JpegRuntimeTopologySnapshot(
            carrierMode = null,
            nativeHealth = null,
            product = null,
            lease = null,
            replacementSource = null,
        )
    }
}

internal sealed interface JpegRuntimeTopologyState

internal class JpegRuntimeTransitionClaim internal constructor(
    internal val operationIdentity: Long,
    internal val source: JpegRuntimeTopologySnapshot,
    internal val committed: JpegRuntimeTopologySnapshot,
    topologyState: AtomicReference<JpegRuntimeTopologyState>,
) {
    internal val transitioning: JpegRuntimeTransitioning = JpegRuntimeTransitioning(this)
    private val topologyState: AtomicReference<JpegRuntimeTopologyState> = topologyState

    internal fun isCurrent(): Boolean = topologyState.get() === transitioning
}

internal class JpegRuntimeTransitioning internal constructor(
    internal val claim: JpegRuntimeTransitionClaim,
) : JpegRuntimeTopologyState
