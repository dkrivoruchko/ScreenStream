package io.screenstream.engine.internal.delivery

import io.screenstream.engine.internal.storage.EncodedPayloadLeaseRelease

internal sealed interface DeliveryClosedResult {
    val handoff: DeliveryHandoff
    val leaseRelease: EncodedPayloadLeaseRelease

    class CallbackReturned internal constructor(
        override val handoff: DeliveryHandoff,
        override val leaseRelease: EncodedPayloadLeaseRelease,
    ) : DeliveryClosedResult {
        init {
            require(leaseRelease.names(handoff.lease))
        }
    }

    class CallbackException internal constructor(
        override val handoff: DeliveryHandoff,
        override val leaseRelease: EncodedPayloadLeaseRelease,
        internal val exception: Exception,
    ) : DeliveryClosedResult {
        init {
            require(leaseRelease.names(handoff.lease))
        }
    }

    class InternalFailure internal constructor(
        override val handoff: DeliveryHandoff,
        override val leaseRelease: EncodedPayloadLeaseRelease,
        internal val exception: Exception?,
    ) : DeliveryClosedResult {
        init {
            require(leaseRelease.names(handoff.lease))
        }
    }

    class DirectFatal internal constructor(
        override val handoff: DeliveryHandoff,
        override val leaseRelease: EncodedPayloadLeaseRelease,
        internal val fatal: Throwable,
    ) : DeliveryClosedResult {
        init {
            require(leaseRelease.names(handoff.lease))
            require(fatal !is Exception)
        }
    }

    class CutoffBeforeEntry internal constructor(
        override val handoff: DeliveryHandoff,
        override val leaseRelease: EncodedPayloadLeaseRelease,
    ) : DeliveryClosedResult {
        init {
            require(leaseRelease.names(handoff.lease))
        }
    }
}

internal enum class DeliveryInstallDisposition {
    ControlAccepted,
    DirectFatalNoContinuation,
    TerminalCleanup,
    StaleCleanup,
}

internal fun interface DeliveryControlWake {
    /** Performs the already-claimed outward Control wake with no engine gate held. */
    fun request()
}

internal class DeliveryInstallOutcome internal constructor(
    internal val disposition: DeliveryInstallDisposition,
    internal val controlWake: DeliveryControlWake?,
) {
    init {
        check((disposition == DeliveryInstallDisposition.ControlAccepted) == (controlWake != null))
    }
}

internal sealed interface DeliverySubmission {
    data object Accepted : DeliverySubmission

    /** Dispatcher submission returned exceptionally; the exact queued handoff remains capsule-rooted. */
    class SubmissionFailedRetained internal constructor(
        internal val cause: Exception,
    ) : DeliverySubmission

    /** An explicit dispatcher rejection proves that the handoff cannot enter. */
    class Rejected internal constructor(
        internal val result: DeliveryClosedResult.InternalFailure,
        internal val disposition: DeliveryInstallDisposition,
    ) : DeliverySubmission
}

/**
 * Narrow semantic seam. Every method is called by [DeliveryRole] while it holds the exact Session gate supplied
 * to that role; implementations must not retain Delivery-owned frame or lease objects.
 */
internal interface DeliverySessionBoundary {
    fun isEntryAdmittedLocked(capsule: DeliveryCapsule, handoff: DeliveryHandoff): Boolean

    fun installClosedResultLocked(
        capsule: DeliveryCapsule,
        handoff: DeliveryHandoff,
        result: DeliveryClosedResult,
    ): DeliveryInstallOutcome

    /** Called outside the Session gate after the exact fatal result has been installed. */
    fun selectDirectFatal(result: DeliveryClosedResult.DirectFatal)
}
