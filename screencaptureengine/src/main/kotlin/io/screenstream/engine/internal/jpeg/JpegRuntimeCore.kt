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

internal class JpegEndpointConstructionFact internal constructor(
    internal val runtimeIdentity: JpegRuntimeIdentity,
    internal val endpointIdentity: JpegEndpointIdentity,
)

internal enum class JpegEndpointShutdownDisposition {
    Prepared,
    InCall,
    Requested,
    AlreadyRequested,
    Thrown,
}

internal class JpegEndpointShutdownFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val action: JpegEndpointShutdownAction,
) {
    private val dispositionSlot = AtomicReference(JpegEndpointShutdownDisposition.Prepared)
    private val throwableSlot = AtomicReference<Throwable?>(null)

    internal val disposition: JpegEndpointShutdownDisposition
        get() = dispositionSlot.get()
    internal val result: JpegEndpointShutdownReturn?
        get() = when (disposition) {
            JpegEndpointShutdownDisposition.Requested -> JpegEndpointShutdownReturn.Requested
            JpegEndpointShutdownDisposition.AlreadyRequested -> JpegEndpointShutdownReturn.AlreadyRequested
            JpegEndpointShutdownDisposition.Prepared,
            JpegEndpointShutdownDisposition.InCall,
            JpegEndpointShutdownDisposition.Thrown,
                -> null
        }
    internal val throwable: Throwable?
        get() = throwableSlot.get()

    internal fun begin(): Boolean = dispositionSlot.compareAndSet(
        JpegEndpointShutdownDisposition.Prepared,
        JpegEndpointShutdownDisposition.InCall,
    )

    internal fun publishReturned(accepted: Boolean) {
        val result = if (accepted) {
            JpegEndpointShutdownDisposition.Requested
        } else {
            JpegEndpointShutdownDisposition.AlreadyRequested
        }
        check(dispositionSlot.compareAndSet(JpegEndpointShutdownDisposition.InCall, result))
    }

    internal fun publishThrown(raw: Throwable) {
        check(throwableSlot.compareAndSet(null, raw))
        check(
            dispositionSlot.compareAndSet(
                JpegEndpointShutdownDisposition.InCall,
                JpegEndpointShutdownDisposition.Thrown,
            ),
        )
    }
}

internal class JpegEndpointTicketFact internal constructor(
    internal val endpointIdentity: JpegEndpointIdentity,
    internal val ticket: PrivateExecutorOperation<*>,
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
