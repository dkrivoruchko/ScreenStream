package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
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

internal class JpegRuntimeIdentity internal constructor()

internal class JpegEndpointIdentity internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
)

internal class JpegProducerCurrentnessFact internal constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val operationIdentity: Long,
)

internal class JpegRuntimeTopologyFact internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
    internal val topologyIdentity: JpegRuntimeTopologySnapshot,
    internal val carrierMode: JpegCarrierMode?,
    internal val nativeHealth: NativeJpegHealth?,
    internal val product: JpegRuntimeProduct?,
    internal val replacementSource: JpegRuntimeProduct?,
    internal val carrierAllocation: JpegCarrierAllocationFact?,
    internal val carrierLease: JpegCarrierLeaseFact?,
)

internal class JpegEndpointConstructionFact internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
    internal val endpointIdentity: JpegEndpointIdentity,
)

internal class JpegEndpointPrestartFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val disposition: PrivateExecutorStartupDisposition,
    internal val ready: Boolean,
    internal val failure: Throwable?,
)

internal class JpegEndpointShutdownFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val returnedNormally: Boolean,
    internal val accepted: Boolean,
    internal val throwable: Throwable?,
)

internal class JpegEndpointTerminationFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val receipt: PrivateExecutorTerminationReceipt,
    internal val accepted: Boolean,
)

internal class JpegEndpointTicketFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val ticket: PrivateExecutorOperation<*>,
)

internal class JpegEndpointTerminationResidueFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val failedPrestart: JpegEndpointPrestartFact?,
    internal val unsettledTicket: JpegEndpointTicketFact?,
    internal val shutdown: JpegEndpointShutdownFact?,
    internal val termination: JpegEndpointTerminationFact?,
    internal val fatal: Throwable?,
)

internal class JpegEndpointLifecycleFact internal constructor(
    internal val construction: JpegEndpointConstructionFact,
    internal val prestart: JpegEndpointPrestartFact?,
    internal val currentTicket: JpegEndpointTicketFact?,
    internal val shutdown: JpegEndpointShutdownFact?,
    internal val termination: JpegEndpointTerminationFact?,
    internal val fatal: Throwable?,
    internal val terminationResidue: JpegEndpointTerminationResidueFact?,
)

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
