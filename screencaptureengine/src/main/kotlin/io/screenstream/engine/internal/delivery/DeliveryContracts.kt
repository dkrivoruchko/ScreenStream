package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import java.util.concurrent.atomic.AtomicBoolean

internal enum class HandoffState {
    Prepared,
    Submitting,
    AcceptedUnentered,
    DetachedPreEntry,
    Entered,
    Resolved,
    Quarantined,
}

/** Controller-selected origin of the immutable output offered by this handoff. */
internal enum class DeliveryOutputKind {
    Fresh,
    Repeat,
    CachedFirst,
}

internal enum class DeliveryEndpointDisposition { Absent, Starting, Ready, Poisoned, ShutdownRequested, Terminated, Failed }
internal enum class DeliveryEndpointStartResult { Ready, AlreadyReady, Starting, Failed }
internal enum class DeliverySubmissionResult { Attempted, NotCurrent }
internal enum class DeliveryCommandResult { Applied, AlreadyApplied, NotCurrent }
internal enum class DeliveryTerminalTransferResult { Settled, Transferred, NotCurrent }
internal enum class DeliveryShutdownResult { Requested, AlreadyRequested, EndpointAbsent, TicketUnsettled, ThrownException }
internal enum class DeliveryRegistrationSettlement { NotOwned, Open, Closing, Settled }
internal enum class DeliveryPreparationState { Prepared, Committing, Installed, Discarded }

internal enum class DeliveryShutdownDisposition { Empty, InCall, Returned, ThrownException, ThrownFatal }
internal enum class DeliverySubmissionDisposition { Empty, InCall, Returned, ThrownException, ThrownFatal }
internal enum class DeliveryEntryDisposition { Empty, Entered, Inert }
internal enum class DeliveryCallbackDisposition { Empty, Returned, ThrownException, ThrownFatal }
internal enum class DeliveryRunnableDisposition { Empty, Returned, ThrownFatal }
internal enum class DeliveryNoCallbackDisposition { Empty, FailedBeforeEntry, FailedAfterEntry }
internal enum class DeliveryLeaseDisposition { Owned, Releasing, Released, ReleaseConflict }

internal enum class DeliveryFailureKind {
    EndpointStartupException,
    SubmissionException,
    AdmissionPortException,
    RunnableException,
    ShutdownException,
    ShutdownFatal,
    DirectFatal,
}

/**
 * The aggregate validates the already-committed handoff identity, registration generation, and only the
 * unsubscribe/terminal cutoffs while holding its sole Session gate. A later reconfiguration pause is not a
 * losing condition: an accepted handoff is grandfathered. Delivery itself never acquires the Session gate.
 */
internal interface DeliveryAuthorityPort {
    /** Session authority must call request.commit(...) exactly once while holding its sole Session gate. */
    fun validateAcceptedEntry(request: DeliveryEntryRequest)

    fun failClosed(notice: DeliveryFailureNotice)
}

internal class DeliveryFailureNotice internal constructor(
    internal val kind: DeliveryFailureKind,
    internal val owner: DeliveryOwner,
    internal val handoff: DeliveryHandoffRecord?,
) {
    private val publicationClaimed = AtomicBoolean(false)

    internal val exactThrowable: Throwable?
        get() = when (kind) {
            DeliveryFailureKind.EndpointStartupException -> owner.endpointStartupFailure
            DeliveryFailureKind.SubmissionException -> handoff?.submissionCell?.throwable
            DeliveryFailureKind.AdmissionPortException,
            DeliveryFailureKind.RunnableException,
                -> handoff?.runnableCell?.handledException

            DeliveryFailureKind.ShutdownException,
            DeliveryFailureKind.ShutdownFatal,
                -> owner.startupEndpointRoot?.shutdownCell?.throwable

            DeliveryFailureKind.DirectFatal -> handoff?.exactFatal ?: owner.exactFatal
        }

    internal fun claimPublication(): Boolean = publicationClaimed.compareAndSet(false, true)
}

/** Physical callback retention for one aggregate-owned registration generation. */
internal class DeliveryRegistration internal constructor(
    private val owner: DeliveryOwner,
    internal val generation: Long,
    callback: (EncodedImageFrame) -> Unit,
) {
    init {
        require(generation > 0L)
    }

    private var retainedCallback: ((EncodedImageFrame) -> Unit)? = callback

    internal val hasCallback: Boolean
        get() = retainedCallback != null

    internal val isAdmissionClosed: Boolean
        get() = retainedCallback == null

    internal fun callback(): ((EncodedImageFrame) -> Unit)? = retainedCallback

    internal fun clearCallback(): Boolean {
        if (retainedCallback == null) return false
        retainedCallback = null
        return true
    }

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner
}

internal sealed interface DeliveryRegistrationPreparation {
    internal class Prepared internal constructor(
        private val owner: DeliveryOwner,
        internal val registration: DeliveryRegistration,
    ) : DeliveryRegistrationPreparation {
        private val atomicState = java.util.concurrent.atomic.AtomicReference(DeliveryPreparationState.Prepared)

        internal val state: DeliveryPreparationState
            get() = atomicState.get()

        internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner

        internal fun beginCommit(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Prepared,
            DeliveryPreparationState.Committing,
        )

        internal fun publishInstalled(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Committing,
            DeliveryPreparationState.Installed,
        )

        internal fun restorePrepared(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Committing,
            DeliveryPreparationState.Prepared,
        )

        internal fun discard(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Prepared,
            DeliveryPreparationState.Discarded,
        )
    }

    internal object Busy : DeliveryRegistrationPreparation
}
