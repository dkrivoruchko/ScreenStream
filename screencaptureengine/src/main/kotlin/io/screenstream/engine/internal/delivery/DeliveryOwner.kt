package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class HandoffState {
    Prepared,
    Submitting,
    AcceptedUnentered,
    DetachedPreEntry,
    Entered,
    Resolved,
    Quarantined,
}

internal enum class DeliveryEndpointDisposition {
    Absent,
    Starting,
    Ready,
    Poisoned,
    ShutdownRequested,
    Terminated,
    Failed,
}

internal enum class DeliveryEndpointStartResult {
    Ready,
    AlreadyReady,
    Starting,
    Failed,
}

internal enum class DeliverySubmissionResult {
    Attempted,
    NotCurrent,
}

internal enum class DeliveryCommandResult {
    Applied,
    AlreadyApplied,
    NotCurrent,
}

internal enum class DeliveryTerminalTransferResult {
    Settled,
    Transferred,
    NotCurrent,
}

internal enum class DeliveryShutdownResult {
    Requested,
    AlreadyRequested,
    EndpointAbsent,
    TicketUnsettled,
    ThrownException,
}

internal enum class DeliveryShutdownDisposition {
    Empty,
    InCall,
    Returned,
    ThrownException,
    ThrownFatal,
}

internal enum class DeliverySubmissionDisposition {
    Empty,
    InCall,
    Returned,
    ThrownException,
    ThrownFatal,
}

internal enum class DeliveryEntryDisposition {
    Empty,
    Entered,
    Inert,
}

internal enum class DeliveryCallbackDisposition {
    Empty,
    Returned,
    ThrownException,
    ThrownFatal,
}

internal enum class DeliveryRunnableDisposition {
    Empty,
    Returned,
    ThrownFatal,
}

internal enum class DeliveryNoCallbackDisposition {
    Empty,
    FailedBeforeEntry,
    FailedAfterEntry,
}

internal enum class DeliveryLeaseDisposition {
    Owned,
    Releasing,
    Released,
    ReleaseConflict,
}

internal enum class DeliveryFactUse {
    Unclaimed,
    Active,
    Cleanup,
}

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
 * The aggregate implementation must validate Session, registration generation and delivery admission while
 * holding its sole Session gate. It then calls [DeliveryEntryRequest.commit] before releasing that gate. The
 * request performs only the leaf-local ticket/endpoint checks and settlement-gate entry commit.
 */
internal interface DeliveryAuthorityPort {
    fun admit(request: DeliveryEntryRequest): DeliveryEntryDisposition

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

    internal fun callback(): ((EncodedImageFrame) -> Unit)? = retainedCallback

    internal fun clearCallback(): Boolean {
        if (retainedCallback == null) return false
        retainedCallback = null
        return true
    }

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner
}

internal class DeliverySubmissionCell internal constructor() {
    internal var disposition: DeliverySubmissionDisposition = DeliverySubmissionDisposition.Empty
        private set

    internal var returnedValue: Unit? = null
        private set

    internal var throwable: Throwable? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun beginLocked(): Boolean {
        if (disposition != DeliverySubmissionDisposition.Empty) return false
        disposition = DeliverySubmissionDisposition.InCall
        return true
    }

    internal fun publishReturnedLocked(): Boolean {
        if (disposition != DeliverySubmissionDisposition.InCall) return false
        returnedValue = Unit
        disposition = DeliverySubmissionDisposition.Returned
        return true
    }

    internal fun publishThrownLocked(raw: Throwable): Boolean {
        if (disposition != DeliverySubmissionDisposition.InCall) return false
        throwable = raw
        disposition = if (raw is Exception) {
            DeliverySubmissionDisposition.ThrownException
        } else {
            DeliverySubmissionDisposition.ThrownFatal
        }
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        val incomplete = (disposition == DeliverySubmissionDisposition.Empty) ||
                (disposition == DeliverySubmissionDisposition.InCall)
        if (incomplete || use != DeliveryFactUse.Unclaimed) {
            return false
        }
        use = domain.toFactUse()
        return true
    }
}

internal class DeliveryEntryCell internal constructor() {
    internal var disposition: DeliveryEntryDisposition = DeliveryEntryDisposition.Empty
        private set

    internal var callbackThread: Thread? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishLocked(result: DeliveryEntryDisposition): Boolean {
        require(result != DeliveryEntryDisposition.Empty)
        if (disposition != DeliveryEntryDisposition.Empty) return false
        disposition = result
        return true
    }

    internal fun publishCallbackThreadLocked(thread: Thread): Boolean {
        if (disposition != DeliveryEntryDisposition.Entered || callbackThread != null) return false
        callbackThread = thread
        return true
    }

    internal fun clearCallbackThreadLocked() {
        callbackThread = null
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (disposition == DeliveryEntryDisposition.Empty || use != DeliveryFactUse.Unclaimed) return false
        use = domain.toFactUse()
        return true
    }
}

internal class DeliveryCallbackCell internal constructor() {
    internal var disposition: DeliveryCallbackDisposition = DeliveryCallbackDisposition.Empty
        private set

    internal var returnedValue: Unit? = null
        private set

    internal var throwable: Throwable? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishLocked(raw: Throwable?): Boolean {
        if (disposition != DeliveryCallbackDisposition.Empty) return false
        if (raw == null) {
            returnedValue = Unit
            disposition = DeliveryCallbackDisposition.Returned
        } else {
            throwable = raw
            disposition = if (raw is Exception) {
                DeliveryCallbackDisposition.ThrownException
            } else {
                DeliveryCallbackDisposition.ThrownFatal
            }
        }
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (disposition == DeliveryCallbackDisposition.Empty || use != DeliveryFactUse.Unclaimed) return false
        use = domain.toFactUse()
        return true
    }
}

internal class DeliveryRunnableCell internal constructor() {
    internal var disposition: DeliveryRunnableDisposition = DeliveryRunnableDisposition.Empty
        private set

    internal var returnedValue: Unit? = null
        private set

    internal var throwable: Throwable? = null
        private set

    internal var handledException: Exception? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishReturnedLocked(handledFailure: Exception? = null): Boolean {
        if (disposition != DeliveryRunnableDisposition.Empty) return false
        handledException = handledFailure
        returnedValue = Unit
        disposition = DeliveryRunnableDisposition.Returned
        return true
    }

    internal fun publishFatalLocked(raw: Throwable): Boolean {
        require(raw !is Exception)
        if (disposition != DeliveryRunnableDisposition.Empty) return false
        throwable = raw
        disposition = DeliveryRunnableDisposition.ThrownFatal
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (disposition == DeliveryRunnableDisposition.Empty || use != DeliveryFactUse.Unclaimed) return false
        use = domain.toFactUse()
        return true
    }
}

internal class DeliveryNoCallbackCell internal constructor() {
    internal var disposition: DeliveryNoCallbackDisposition = DeliveryNoCallbackDisposition.Empty
        private set

    internal var throwable: Throwable? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishLocked(
        afterEntry: Boolean,
        raw: Throwable,
    ): Boolean {
        if (disposition != DeliveryNoCallbackDisposition.Empty) return false
        throwable = raw
        disposition = if (afterEntry) {
            DeliveryNoCallbackDisposition.FailedAfterEntry
        } else {
            DeliveryNoCallbackDisposition.FailedBeforeEntry
        }
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (disposition == DeliveryNoCallbackDisposition.Empty || use != DeliveryFactUse.Unclaimed) return false
        use = domain.toFactUse()
        return true
    }
}

internal class DeliveryShutdownCell internal constructor() {
    private val atomicDisposition = AtomicReference(DeliveryShutdownDisposition.Empty)

    internal var returnedValue: Unit? = null
        private set

    internal var throwable: Throwable? = null
        private set

    internal val disposition: DeliveryShutdownDisposition
        get() = atomicDisposition.get()

    internal fun begin(): Boolean = atomicDisposition.compareAndSet(
        DeliveryShutdownDisposition.Empty,
        DeliveryShutdownDisposition.InCall,
    )

    internal fun publishReturned() {
        returnedValue = Unit
        check(
            atomicDisposition.compareAndSet(
                DeliveryShutdownDisposition.InCall,
                DeliveryShutdownDisposition.Returned,
            ),
        )
    }

    internal fun publishThrown(raw: Throwable) {
        throwable = raw
        val terminal = if (raw is Exception) {
            DeliveryShutdownDisposition.ThrownException
        } else {
            DeliveryShutdownDisposition.ThrownFatal
        }
        check(atomicDisposition.compareAndSet(DeliveryShutdownDisposition.InCall, terminal))
    }
}

internal class DeliveryLeaseSlot internal constructor(
    lease: EncodedStorageOwner.EncodedPayloadLease,
) {
    internal var lease: EncodedStorageOwner.EncodedPayloadLease? = lease
        private set

    internal var disposition: DeliveryLeaseDisposition = DeliveryLeaseDisposition.Owned
        private set

    internal fun claimReleaseLocked(): EncodedStorageOwner.EncodedPayloadLease? {
        if (disposition != DeliveryLeaseDisposition.Owned) return null
        val current = lease ?: return null
        disposition = DeliveryLeaseDisposition.Releasing
        return current
    }

    internal fun publishReleaseLocked(released: Boolean): Boolean {
        if (disposition != DeliveryLeaseDisposition.Releasing) return false
        disposition = if (released) DeliveryLeaseDisposition.Released else DeliveryLeaseDisposition.ReleaseConflict
        return true
    }

    internal fun clearConsumedLocked(expectedLease: EncodedStorageOwner.EncodedPayloadLease): Boolean {
        if (disposition != DeliveryLeaseDisposition.Released &&
            disposition != DeliveryLeaseDisposition.ReleaseConflict ||
            lease !== expectedLease
        ) {
            return false
        }
        lease = null
        return true
    }
}

internal class BorrowedEncodedImageFrame internal constructor(
    lease: EncodedStorageOwner.EncodedPayloadLease,
    private val settlementGate: ReentrantLock,
) : EncodedImageFrame {
    private var retainedLease: EncodedStorageOwner.EncodedPayloadLease? = lease
    private var callbackThread: Thread? = null
    private var authorityOpen: Boolean = false

    private val wrongThreadFailure = IllegalStateException("EncodedImageFrame is valid only on its callback thread.")
    private val closedAuthorityFailure = IllegalStateException("EncodedImageFrame is valid only during its callback body.")

    override val byteCount: Int
        get() = checkedLease().byteCount

    override val imageSize: ImageSize
        get() = checkedLease().imageSize

    override val sequence: Long
        get() = checkedLease().sequence

    override val timestampElapsedRealtimeNanos: Long
        get() = checkedLease().timestampElapsedRealtimeNanos

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int =
        checkedLease().copyTo(destination, destinationOffset)

    override fun copyBytes(): ByteArray = checkedLease().copyBytes()

    internal fun openLocked(thread: Thread): Boolean {
        if (authorityOpen || retainedLease == null) return false
        callbackThread = thread
        authorityOpen = true
        return true
    }

    internal fun closeLocked() {
        authorityOpen = false
        callbackThread = null
        retainedLease = null
    }

    private fun checkedLease(): EncodedStorageOwner.EncodedPayloadLease = settlementGate.withLock {
        if (Thread.currentThread() !== callbackThread) throw wrongThreadFailure
        if (!authorityOpen) throw closedAuthorityFailure
        retainedLease ?: throw closedAuthorityFailure
    }
}

internal class DeliveryTerminationReceipt private constructor(
    internal val endpoint: DeliveryEndpoint,
) {
    internal companion object {
        internal fun create(endpoint: DeliveryEndpoint): DeliveryTerminationReceipt =
            DeliveryTerminationReceipt(endpoint)
    }
}

internal class DeliveryTicket internal constructor(
    internal val endpoint: DeliveryEndpoint,
    internal val identity: Long,
) {
    init {
        require(identity > 0L)
    }

    private val installedHandoff = AtomicReference<DeliveryHandoffRecord?>(null)

    internal val handoff: DeliveryHandoffRecord?
        get() = installedHandoff.get()

    internal fun install(record: DeliveryHandoffRecord): Boolean = installedHandoff.compareAndSet(null, record)
}

internal class DeliveryEndpoint internal constructor(
    internal val owner: DeliveryOwner,
    threadName: String,
    private val signal: SettlementSignal,
) {
    private class OwnedExecutor(
        endpoint: DeliveryEndpoint,
        threadFactory: ThreadFactory,
    ) : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.NANOSECONDS,
        ArrayBlockingQueue(1),
        threadFactory,
        AbortPolicy(),
    ) {
        private val retainedEndpoint = endpoint

        override fun terminated() {
            retainedEndpoint.publishTerminated()
        }
    }

    private val workerSequence = AtomicLong(0L)
    private val poisoned = AtomicBoolean(false)
    private val termination = DeliveryTerminationReceipt.create(this)
    private val publishedTermination = AtomicReference<DeliveryTerminationReceipt?>(null)
    internal val shutdownCell = DeliveryShutdownCell()
    private val executor: OwnedExecutor

    init {
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${workerSequence.incrementAndGet()}").apply {
                isDaemon = false
            }
        }
        executor = OwnedExecutor(this, threadFactory)
    }

    internal val isPoisoned: Boolean
        get() = poisoned.get()

    internal val isShutdownRequested: Boolean
        get() = shutdownCell.disposition != DeliveryShutdownDisposition.Empty

    internal val terminationReceipt: DeliveryTerminationReceipt?
        get() = publishedTermination.get()

    internal fun prestart(): Int = executor.prestartAllCoreThreads()

    internal fun execute(runnable: Runnable) {
        executor.execute(runnable)
    }

    internal fun poison() {
        poisoned.set(true)
    }

    internal fun requestShutdown(): DeliveryShutdownDisposition {
        if (!shutdownCell.begin()) return shutdownCell.disposition
        try {
            executor.shutdown()
        } catch (raw: Throwable) {
            shutdownCell.publishThrown(raw)
            throw raw
        }
        shutdownCell.publishReturned()
        return DeliveryShutdownDisposition.Returned
    }

    internal fun accepts(receipt: DeliveryTerminationReceipt): Boolean =
        receipt === termination && receipt.endpoint === this

    private fun publishTerminated() {
        if (!publishedTermination.compareAndSet(null, termination)) return
        owner.publishEndpointTerminated(this)
        if (shutdownCell.disposition == DeliveryShutdownDisposition.InCall) return
        try {
            signal.signal()
        } catch (_: Throwable) {
            // The release-published receipt remains authoritative.
        }
    }
}

internal class DeliveryEntryRequest internal constructor(
    private val owner: DeliveryOwner,
    internal val handoff: DeliveryHandoffRecord,
    internal val registration: DeliveryRegistration,
    internal val registrationGeneration: Long,
    internal val ticket: DeliveryTicket,
    internal val endpoint: DeliveryEndpoint,
) {
    /** Called exactly once by the aggregate admission port while its Session gate is held. */
    internal fun commit(aggregateAdmitted: Boolean): DeliveryEntryDisposition =
        owner.commitEntry(this, aggregateAdmitted)
}

internal class DeliveryTerminalResidue internal constructor(
    internal val handoff: DeliveryHandoffRecord,
    internal val endpoint: DeliveryEndpoint,
    internal val ticket: DeliveryTicket,
    internal val submissionCell: DeliverySubmissionCell,
    internal val entryCell: DeliveryEntryCell,
    internal val callbackCell: DeliveryCallbackCell,
    internal val runnableCell: DeliveryRunnableCell,
    internal val noCallbackCell: DeliveryNoCallbackCell,
    internal val shutdownCell: DeliveryShutdownCell,
    internal val leaseSlot: DeliveryLeaseSlot,
    internal val borrowedFrame: BorrowedEncodedImageFrame,
    internal val runnable: Runnable,
)

internal class DeliveryHandoffRecord internal constructor(
    private val owner: DeliveryOwner,
    internal val registration: DeliveryRegistration,
    internal val identity: Long,
    internal val ticket: DeliveryTicket,
    internal val callback: (EncodedImageFrame) -> Unit,
    lease: EncodedStorageOwner.EncodedPayloadLease,
) {
    init {
        require(identity > 0L)
    }

    internal val settlementGate: ReentrantLock = ReentrantLock(false)
    internal val submissionCell = DeliverySubmissionCell()
    internal val entryCell = DeliveryEntryCell()
    internal val callbackCell = DeliveryCallbackCell()
    internal val runnableCell = DeliveryRunnableCell()
    internal val noCallbackCell = DeliveryNoCallbackCell()
    internal val leaseSlot = DeliveryLeaseSlot(lease)
    internal val borrowedFrame = BorrowedEncodedImageFrame(lease, settlementGate)
    internal val entryRequest = DeliveryEntryRequest(
        owner = owner,
        handoff = this,
        registration = registration,
        registrationGeneration = registration.generation,
        ticket = ticket,
        endpoint = ticket.endpoint,
    )
    internal val submissionExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.SubmissionException,
        owner = owner,
        handoff = this,
    )
    internal val admissionExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.AdmissionPortException,
        owner = owner,
        handoff = this,
    )
    internal val runnableExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.RunnableException,
        owner = owner,
        handoff = this,
    )
    internal val directFatalNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.DirectFatal,
        owner = owner,
        handoff = this,
    )
    internal val runnable: Runnable = Runnable { owner.runHandoffRunnable(this) }
    internal val terminalResidue = DeliveryTerminalResidue(
        handoff = this,
        endpoint = ticket.endpoint,
        ticket = ticket,
        submissionCell = submissionCell,
        entryCell = entryCell,
        callbackCell = callbackCell,
        runnableCell = runnableCell,
        noCallbackCell = noCallbackCell,
        shutdownCell = ticket.endpoint.shutdownCell,
        leaseSlot = leaseSlot,
        borrowedFrame = borrowedFrame,
        runnable = runnable,
    )

    internal var state: HandoffState = HandoffState.Prepared
    internal var domain: OperationDomain = OperationDomain.Active
        private set
    internal var admissionOpen: Boolean = true
    internal var callbackInvocationStarted: Boolean = false
    internal var exactFatal: Throwable? = null

    internal fun transferToCleanupLocked() {
        check(settlementGate.isHeldByCurrentThread)
        domain = OperationDomain.Cleanup
    }

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner
}

internal sealed interface DeliveryHandoffPreparation {
    class Prepared internal constructor(
        internal val handoff: DeliveryHandoffRecord,
    ) : DeliveryHandoffPreparation

    object EndpointUnavailable : DeliveryHandoffPreparation

    object Busy : DeliveryHandoffPreparation
}

internal class DeliveryOwner internal constructor(
    private val authorityPort: DeliveryAuthorityPort,
    private val settlementSignal: SettlementSignal,
    private val deliveryThreadName: String = "ScreenCaptureEngine-Delivery",
) {
    private val endpointDisposition = AtomicReference(DeliveryEndpointDisposition.Absent)
    private val constructedEndpointRoot = AtomicReference<DeliveryEndpoint?>(null)
    private val readyEndpointSlot = AtomicReference<DeliveryEndpoint?>(null)
    private val activeTicket = AtomicReference<DeliveryTicket?>(null)
    private val fatalSlot = AtomicReference<Throwable?>(null)
    private val startupFailureSlot = AtomicReference<Throwable?>(null)

    private val startupExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.EndpointStartupException,
        owner = this,
        handoff = null,
    )
    private val startupFatalNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.DirectFatal,
        owner = this,
        handoff = null,
    )
    private val shutdownExceptionNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.ShutdownException,
        owner = this,
        handoff = null,
    )
    private val shutdownFatalNotice = DeliveryFailureNotice(
        kind = DeliveryFailureKind.ShutdownFatal,
        owner = this,
        handoff = null,
    )

    internal val endpointState: DeliveryEndpointDisposition
        get() = endpointDisposition.get()

    internal val endpoint: DeliveryEndpoint?
        get() = readyEndpointSlot.get()

    internal val startupEndpointRoot: DeliveryEndpoint?
        get() = constructedEndpointRoot.get()

    internal val endpointStartupFailure: Throwable?
        get() = startupFailureSlot.get()

    internal val exactFatal: Throwable?
        get() = fatalSlot.get()

    internal val currentTicket: DeliveryTicket?
        get() = activeTicket.get()

    internal val terminationReceipt: DeliveryTerminationReceipt?
        get() = constructedEndpointRoot.get()?.terminationReceipt

    internal fun prepareRegistration(
        generation: Long,
        callback: (EncodedImageFrame) -> Unit,
    ): DeliveryRegistration = DeliveryRegistration(this, generation, callback)

    internal fun startEndpoint(): DeliveryEndpointStartResult {
        when (endpointDisposition.get()) {
            DeliveryEndpointDisposition.Ready -> return DeliveryEndpointStartResult.AlreadyReady
            DeliveryEndpointDisposition.Starting -> return DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Absent -> Unit
            DeliveryEndpointDisposition.Poisoned,
            DeliveryEndpointDisposition.ShutdownRequested,
            DeliveryEndpointDisposition.Terminated,
            DeliveryEndpointDisposition.Failed,
                -> return DeliveryEndpointStartResult.Failed
        }
        if (!endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Absent,
                DeliveryEndpointDisposition.Starting,
            )
        ) {
            return startResultFromCurrentState()
        }

        var constructed: DeliveryEndpoint? = null
        try {
            constructed = DeliveryEndpoint(this, deliveryThreadName, settlementSignal)
            check(constructedEndpointRoot.compareAndSet(null, constructed))
            if (constructed.prestart() != 1) {
                startupFailureSlot.compareAndSet(null, PRESTART_DID_NOT_START)
                constructed.poison()
                endpointDisposition.compareAndSet(
                    DeliveryEndpointDisposition.Starting,
                    DeliveryEndpointDisposition.Failed,
                )
                failClosedBestEffort(startupExceptionNotice)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            val publishedReady = endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Starting,
                DeliveryEndpointDisposition.Ready,
            )
            if (!publishedReady) {
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            readyEndpointSlot.set(constructed)
            if (endpointDisposition.get() != DeliveryEndpointDisposition.Ready) {
                readyEndpointSlot.compareAndSet(constructed, null)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            signalBestEffort()
            return DeliveryEndpointStartResult.Ready
        } catch (raw: Throwable) {
            startupFailureSlot.compareAndSet(null, raw)
            constructed?.poison()
            if (raw is Exception) {
                endpointDisposition.compareAndSet(
                    DeliveryEndpointDisposition.Starting,
                    DeliveryEndpointDisposition.Failed,
                )
                failClosedBestEffort(startupExceptionNotice)
                signalBestEffort()
                return DeliveryEndpointStartResult.Failed
            }
            fatalSlot.compareAndSet(null, raw)
            endpointDisposition.compareAndSet(
                DeliveryEndpointDisposition.Starting,
                DeliveryEndpointDisposition.Poisoned,
            )
            failClosedBestEffort(startupFatalNotice)
            signalBestEffort()
            throw raw
        }
    }

    internal fun prepareHandoff(
        registration: DeliveryRegistration,
        handoffIdentity: Long,
        preparedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryHandoffPreparation {
        require(handoffIdentity > 0L)
        if (!registration.belongsTo(this) || !registration.hasCallback) {
            return DeliveryHandoffPreparation.EndpointUnavailable
        }
        val currentEndpoint = readyEndpointSlot.get()
        if (currentEndpoint == null || endpointDisposition.get() != DeliveryEndpointDisposition.Ready ||
            currentEndpoint.isPoisoned || currentEndpoint.isShutdownRequested
        ) {
            return DeliveryHandoffPreparation.EndpointUnavailable
        }

        val ticket = DeliveryTicket(currentEndpoint, handoffIdentity)
        if (!activeTicket.compareAndSet(null, ticket)) return DeliveryHandoffPreparation.Busy
        try {
            val callback = registration.callback()
            if (callback == null) {
                activeTicket.compareAndSet(ticket, null)
                return DeliveryHandoffPreparation.EndpointUnavailable
            }
            val handoff = DeliveryHandoffRecord(
                owner = this,
                registration = registration,
                identity = handoffIdentity,
                ticket = ticket,
                callback = callback,
                lease = preparedLease,
            )
            check(ticket.install(handoff))
            return DeliveryHandoffPreparation.Prepared(handoff)
        } catch (raw: Throwable) {
            activeTicket.compareAndSet(ticket, null)
            throw raw
        }
    }

    internal fun submitHandoff(handoff: DeliveryHandoffRecord): DeliverySubmissionResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket || handoff.ticket.handoff !== handoff ||
            readyEndpointSlot.get() !== handoff.ticket.endpoint
        ) {
            return DeliverySubmissionResult.NotCurrent
        }
        val begun = handoff.settlementGate.withLock {
            if (handoff.state != HandoffState.Prepared || handoff.domain != OperationDomain.Active ||
                !handoff.admissionOpen || !handoff.submissionCell.beginLocked()
            ) {
                false
            } else {
                handoff.state = HandoffState.Submitting
                true
            }
        }
        if (!begun) return DeliverySubmissionResult.NotCurrent

        try {
            handoff.ticket.endpoint.execute(handoff.runnable)
        } catch (raw: Throwable) {
            val releaseLease = handoff.settlementGate.withLock {
                handoff.submissionCell.publishThrownLocked(raw)
                if (raw is Exception && handoff.entryCell.disposition == DeliveryEntryDisposition.Empty) {
                    handoff.entryCell.publishLocked(DeliveryEntryDisposition.Inert)
                    if (handoff.state != HandoffState.Quarantined) {
                        handoff.state = HandoffState.DetachedPreEntry
                    }
                    true
                } else {
                    if (raw !is Exception && handoff.exactFatal == null) handoff.exactFatal = raw
                    false
                }
            }
            if (releaseLease) releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(handoff.submissionExceptionNotice)
                signalBestEffort()
                return DeliverySubmissionResult.Attempted
            }
            fatalSlot.compareAndSet(null, raw)
            publishEndpointPoisonedUnlessTerminated()
            failClosedBestEffort(handoff.directFatalNotice)
            signalBestEffort()
            throw (fatalSlot.get() ?: raw)
        }

        handoff.settlementGate.withLock {
            handoff.submissionCell.publishReturnedLocked()
            if (handoff.entryCell.disposition == DeliveryEntryDisposition.Empty &&
                handoff.state != HandoffState.Quarantined
            ) {
                handoff.state = HandoffState.AcceptedUnentered
            }
        }
        signalBestEffort()
        return DeliverySubmissionResult.Attempted
    }

    internal fun closeAdmission(handoff: DeliveryHandoffRecord): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        var releasePrepared = false
        val changed = handoff.settlementGate.withLock {
            if (!handoff.admissionOpen) return@withLock false
            handoff.admissionOpen = false
            if (handoff.submissionCell.disposition == DeliverySubmissionDisposition.Empty) {
                releasePrepared = true
                handoff.state = HandoffState.DetachedPreEntry
            } else if (handoff.state == HandoffState.AcceptedUnentered ||
                handoff.state == HandoffState.Submitting
            ) {
                handoff.state = HandoffState.DetachedPreEntry
            }
            true
        }
        if (releasePrepared) releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return if (changed) DeliveryCommandResult.Applied else DeliveryCommandResult.AlreadyApplied
    }

    internal fun transferForTerminal(handoff: DeliveryHandoffRecord): DeliveryTerminalTransferResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryTerminalTransferResult.NotCurrent
        }
        var releaseUnsubmitted = false
        val settled = handoff.settlementGate.withLock {
            handoff.admissionOpen = false
            handoff.transferToCleanupLocked()
            if (handoff.submissionCell.disposition == DeliverySubmissionDisposition.Empty) {
                releaseUnsubmitted = true
            }
            if (!isMechanicallySettledLocked(handoff)) handoff.state = HandoffState.Quarantined
            isMechanicallySettledLocked(handoff)
        }
        if (releaseUnsubmitted) releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return if (settled) DeliveryTerminalTransferResult.Settled else DeliveryTerminalTransferResult.Transferred
    }

    internal fun terminalResidue(handoff: DeliveryHandoffRecord): DeliveryTerminalResidue? {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) return null
        return handoff.terminalResidue
    }

    internal fun releasedLeaseForStorageConsumption(
        handoff: DeliveryHandoffRecord,
    ): EncodedStorageOwner.EncodedPayloadLease? {
        if (!handoff.belongsTo(this)) return null
        return handoff.settlementGate.withLock {
            if (handoff.leaseSlot.disposition != DeliveryLeaseDisposition.Released &&
                handoff.leaseSlot.disposition != DeliveryLeaseDisposition.ReleaseConflict
            ) {
                return@withLock null
            }
            handoff.leaseSlot.lease
        }
    }

    internal fun acknowledgeStorageLeaseConsumption(
        handoff: DeliveryHandoffRecord,
        consumedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        val applied = handoff.settlementGate.withLock {
            handoff.leaseSlot.clearConsumedLocked(consumedLease)
        }
        if (!applied) return DeliveryCommandResult.NotCurrent
        signalBestEffort()
        return DeliveryCommandResult.Applied
    }

    internal fun claimCompleteFacts(
        handoff: DeliveryHandoffRecord,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        val claimedAny = handoff.settlementGate.withLock {
            val domain = handoff.domain
            var changed = false
            changed = handoff.submissionCell.claimLocked(domain) || changed
            changed = handoff.entryCell.claimLocked(domain) || changed
            changed = handoff.callbackCell.claimLocked(domain) || changed
            changed = handoff.runnableCell.claimLocked(domain) || changed
            changed = handoff.noCallbackCell.claimLocked(domain) || changed
            changed
        }
        return if (claimedAny) DeliveryCommandResult.Applied else DeliveryCommandResult.AlreadyApplied
    }

    internal fun releaseRetainedNoCallbackAuthority(
        handoff: DeliveryHandoffRecord,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        val eligible = handoff.settlementGate.withLock {
            handoff.domain == OperationDomain.Cleanup &&
                    handoff.entryCell.disposition == DeliveryEntryDisposition.Entered &&
                    handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedAfterEntry &&
                    handoff.noCallbackCell.use != DeliveryFactUse.Unclaimed &&
                    handoff.callbackCell.disposition == DeliveryCallbackDisposition.Empty &&
                    !handoff.callbackInvocationStarted &&
                    handoff.leaseSlot.disposition == DeliveryLeaseDisposition.Owned
        }
        if (!eligible) return DeliveryCommandResult.NotCurrent
        releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
        return DeliveryCommandResult.Applied
    }

    internal fun retireSettledHandoff(handoff: DeliveryHandoffRecord): DeliveryCommandResult {
        if (!handoff.belongsTo(this) || activeTicket.get() !== handoff.ticket) {
            return DeliveryCommandResult.NotCurrent
        }
        val settled = handoff.settlementGate.withLock {
            if (!isMechanicallySettledLocked(handoff)) return@withLock false
            handoff.state = HandoffState.Resolved
            true
        }
        if (!settled || !activeTicket.compareAndSet(handoff.ticket, null)) {
            return DeliveryCommandResult.NotCurrent
        }
        signalBestEffort()
        return DeliveryCommandResult.Applied
    }

    internal fun retireRegistration(registration: DeliveryRegistration): DeliveryCommandResult {
        if (!registration.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        return if (registration.clearCallback()) {
            DeliveryCommandResult.Applied
        } else {
            DeliveryCommandResult.AlreadyApplied
        }
    }

    internal fun isCallbackThread(handoff: DeliveryHandoffRecord): Boolean {
        if (!handoff.belongsTo(this)) return false
        return handoff.settlementGate.withLock {
            handoff.entryCell.callbackThread === Thread.currentThread()
        }
    }

    internal fun requestShutdown(): DeliveryShutdownResult {
        if (activeTicket.get() != null) return DeliveryShutdownResult.TicketUnsettled
        val currentEndpoint = constructedEndpointRoot.get() ?: return DeliveryShutdownResult.EndpointAbsent
        if (currentEndpoint.shutdownCell.disposition != DeliveryShutdownDisposition.Empty) {
            return DeliveryShutdownResult.AlreadyRequested
        }
        val shutdownDisposition = try {
            currentEndpoint.requestShutdown()
        } catch (raw: Throwable) {
            currentEndpoint.poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(shutdownExceptionNotice)
                signalBestEffort()
                return DeliveryShutdownResult.ThrownException
            }
            fatalSlot.compareAndSet(null, raw)
            publishEndpointPoisonedUnlessTerminated()
            val winner = fatalSlot.get() ?: raw
            failClosedBestEffort(shutdownFatalNotice)
            signalBestEffort()
            throw winner
        }
        if (shutdownDisposition != DeliveryShutdownDisposition.Returned) {
            return DeliveryShutdownResult.AlreadyRequested
        }
        publishShutdownRequestedUnlessTerminated()
        signalBestEffort()
        return DeliveryShutdownResult.Requested
    }

    internal fun acceptsTerminationReceipt(receipt: DeliveryTerminationReceipt): Boolean =
        constructedEndpointRoot.get()?.accepts(receipt) == true

    internal fun releaseTerminatedEndpoint(
        receipt: DeliveryTerminationReceipt,
    ): DeliveryCommandResult {
        val terminatedEndpoint = constructedEndpointRoot.get() ?: return DeliveryCommandResult.NotCurrent
        if (!terminatedEndpoint.accepts(receipt) || terminatedEndpoint.terminationReceipt !== receipt) {
            return DeliveryCommandResult.NotCurrent
        }
        readyEndpointSlot.compareAndSet(terminatedEndpoint, null)
        return if (constructedEndpointRoot.compareAndSet(terminatedEndpoint, null)) {
            DeliveryCommandResult.Applied
        } else {
            DeliveryCommandResult.NotCurrent
        }
    }

    internal fun runHandoffRunnable(handoff: DeliveryHandoffRecord) {
        try {
            runHandoffBody(handoff)
        } catch (raw: Throwable) {
            val releaseUnentered = handoff.settlementGate.withLock {
                if (handoff.entryCell.disposition == DeliveryEntryDisposition.Empty) {
                    handoff.entryCell.publishLocked(DeliveryEntryDisposition.Inert)
                    if (handoff.state != HandoffState.Quarantined) {
                        handoff.state = HandoffState.DetachedPreEntry
                    }
                }
                val afterEntry = handoff.entryCell.disposition == DeliveryEntryDisposition.Entered
                if (!handoff.callbackInvocationStarted &&
                    handoff.callbackCell.disposition == DeliveryCallbackDisposition.Empty
                ) {
                    handoff.noCallbackCell.publishLocked(afterEntry, raw)
                }
                if (raw is Exception) {
                    handoff.runnableCell.publishReturnedLocked(raw)
                } else {
                    handoff.runnableCell.publishFatalLocked(raw)
                    if (handoff.exactFatal == null) handoff.exactFatal = raw
                }
                !afterEntry
            }
            if (releaseUnentered) releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            if (raw is Exception) {
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(
                    if (handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedBeforeEntry) {
                        handoff.admissionExceptionNotice
                    } else {
                        handoff.runnableExceptionNotice
                    },
                )
                signalBestEffort()
                return
            } else {
                fatalSlot.compareAndSet(null, raw)
                publishEndpointPoisonedUnlessTerminated()
                failClosedBestEffort(handoff.directFatalNotice)
            }
            signalBestEffort()
            throw (fatalSlot.get() ?: raw)
        }
        handoff.settlementGate.withLock {
            handoff.runnableCell.publishReturnedLocked()
        }
        signalBestEffort()
    }

    internal fun commitEntry(
        request: DeliveryEntryRequest,
        aggregateAdmitted: Boolean,
    ): DeliveryEntryDisposition {
        val handoff = request.handoff
        if (!handoff.belongsTo(this) || request.registration !== handoff.registration ||
            request.registrationGeneration != handoff.registration.generation || request.ticket !== handoff.ticket ||
            request.endpoint !== handoff.ticket.endpoint
        ) {
            return DeliveryEntryDisposition.Inert
        }
        return handoff.settlementGate.withLock {
            val result = if (aggregateAdmitted && activeTicket.get() === handoff.ticket &&
                readyEndpointSlot.get() === request.endpoint && endpointDisposition.get() == DeliveryEndpointDisposition.Ready &&
                !request.endpoint.isPoisoned && !request.endpoint.isShutdownRequested &&
                handoff.domain == OperationDomain.Active && handoff.admissionOpen &&
                handoff.submissionCell.disposition != DeliverySubmissionDisposition.ThrownException &&
                handoff.entryCell.disposition == DeliveryEntryDisposition.Empty
            ) {
                DeliveryEntryDisposition.Entered
            } else {
                DeliveryEntryDisposition.Inert
            }
            if (!handoff.entryCell.publishLocked(result)) return@withLock handoff.entryCell.disposition
            handoff.state = if (result == DeliveryEntryDisposition.Entered) {
                HandoffState.Entered
            } else {
                HandoffState.DetachedPreEntry
            }
            result
        }
    }

    internal fun publishEndpointTerminated(terminatedEndpoint: DeliveryEndpoint) {
        if (constructedEndpointRoot.get() !== terminatedEndpoint) return
        readyEndpointSlot.compareAndSet(terminatedEndpoint, null)
        endpointDisposition.set(DeliveryEndpointDisposition.Terminated)
    }

    private fun runHandoffBody(handoff: DeliveryHandoffRecord) {
        val admitted = authorityPort.admit(handoff.entryRequest)
        val committed = handoff.settlementGate.withLock { handoff.entryCell.disposition }
        if (admitted != committed || committed == DeliveryEntryDisposition.Empty) {
            throw ADMISSION_PORT_DID_NOT_COMMIT
        }
        signalBestEffort()
        if (committed == DeliveryEntryDisposition.Inert) {
            releaseLeaseOnce(handoff)
            return
        }

        val callbackThread = Thread.currentThread()
        val opened = handoff.settlementGate.withLock {
            handoff.entryCell.publishCallbackThreadLocked(callbackThread) &&
                    handoff.borrowedFrame.openLocked(callbackThread).also { openedAuthority ->
                        if (openedAuthority) handoff.callbackInvocationStarted = true
                    }
        }
        if (!opened) throw CALLBACK_AUTHORITY_OPEN_FAILED

        var callbackFailure: Throwable? = null
        try {
            handoff.callback(handoff.borrowedFrame)
        } catch (raw: Throwable) {
            callbackFailure = raw
        }
        handoff.settlementGate.withLock {
            handoff.callbackCell.publishLocked(callbackFailure)
            handoff.borrowedFrame.closeLocked()
            handoff.entryCell.clearCallbackThreadLocked()
            if (callbackFailure != null && callbackFailure !is Exception) {
                if (handoff.exactFatal == null) handoff.exactFatal = callbackFailure
            }
        }
        val exactFailure = callbackFailure
        if (exactFailure != null && exactFailure !is Exception) {
            releaseLeaseOnce(handoff, signalAfter = false)
            handoff.ticket.endpoint.poison()
            fatalSlot.compareAndSet(null, exactFailure)
            publishEndpointPoisonedUnlessTerminated()
            failClosedBestEffort(handoff.directFatalNotice)
            signalBestEffort()
            throw (fatalSlot.get() ?: exactFailure)
        }
        releaseLeaseOnce(handoff, signalAfter = false)
        signalBestEffort()
    }

    private fun releaseLeaseOnce(
        handoff: DeliveryHandoffRecord,
        signalAfter: Boolean = true,
    ) {
        val lease = handoff.settlementGate.withLock {
            handoff.borrowedFrame.closeLocked()
            handoff.leaseSlot.claimReleaseLocked()
        } ?: return
        val released = lease.release()
        handoff.settlementGate.withLock {
            handoff.leaseSlot.publishReleaseLocked(released)
        }
        if (signalAfter) signalBestEffort()
    }

    private fun isMechanicallySettledLocked(handoff: DeliveryHandoffRecord): Boolean {
        val submission = handoff.submissionCell.disposition
        val entry = handoff.entryCell.disposition
        val callbackSettled = entry != DeliveryEntryDisposition.Entered ||
                handoff.callbackCell.disposition != DeliveryCallbackDisposition.Empty ||
                handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.FailedAfterEntry
        val runnableSettled = when (submission) {
            DeliverySubmissionDisposition.Empty -> true
            DeliverySubmissionDisposition.ThrownException ->
                entry == DeliveryEntryDisposition.Inert ||
                        handoff.runnableCell.disposition != DeliveryRunnableDisposition.Empty

            DeliverySubmissionDisposition.Returned,
            DeliverySubmissionDisposition.ThrownFatal,
                -> handoff.runnableCell.disposition != DeliveryRunnableDisposition.Empty

            DeliverySubmissionDisposition.InCall -> false
        }
        val entrySettled = when (submission) {
            DeliverySubmissionDisposition.Empty -> true
            DeliverySubmissionDisposition.ThrownException -> entry != DeliveryEntryDisposition.Empty
            DeliverySubmissionDisposition.Returned,
            DeliverySubmissionDisposition.ThrownFatal,
            DeliverySubmissionDisposition.InCall,
                -> entry != DeliveryEntryDisposition.Empty
        }
        val leaseSettled = handoff.leaseSlot.lease == null &&
                (handoff.leaseSlot.disposition == DeliveryLeaseDisposition.Released ||
                        handoff.leaseSlot.disposition == DeliveryLeaseDisposition.ReleaseConflict)
        val submissionConsumed = submission == DeliverySubmissionDisposition.Empty ||
                handoff.submissionCell.use != DeliveryFactUse.Unclaimed
        val entryConsumed = entry == DeliveryEntryDisposition.Empty ||
                handoff.entryCell.use != DeliveryFactUse.Unclaimed
        val callbackConsumed = handoff.callbackCell.disposition == DeliveryCallbackDisposition.Empty ||
                handoff.callbackCell.use != DeliveryFactUse.Unclaimed
        val runnableConsumed = handoff.runnableCell.disposition == DeliveryRunnableDisposition.Empty ||
                handoff.runnableCell.use != DeliveryFactUse.Unclaimed
        val noCallbackConsumed = handoff.noCallbackCell.disposition == DeliveryNoCallbackDisposition.Empty ||
                handoff.noCallbackCell.use != DeliveryFactUse.Unclaimed
        return submission != DeliverySubmissionDisposition.InCall && entrySettled && callbackSettled &&
                runnableSettled && leaseSettled && submissionConsumed && entryConsumed &&
                callbackConsumed && runnableConsumed && noCallbackConsumed
    }

    private fun publishEndpointPoisonedUnlessTerminated() {
        while (true) {
            val current = endpointDisposition.get()
            if (current == DeliveryEndpointDisposition.Terminated ||
                current == DeliveryEndpointDisposition.Poisoned
            ) {
                return
            }
            if (endpointDisposition.compareAndSet(current, DeliveryEndpointDisposition.Poisoned)) return
        }
    }

    private fun publishShutdownRequestedUnlessTerminated() {
        while (true) {
            val current = endpointDisposition.get()
            if (current == DeliveryEndpointDisposition.Terminated ||
                current == DeliveryEndpointDisposition.ShutdownRequested
            ) {
                return
            }
            if (endpointDisposition.compareAndSet(current, DeliveryEndpointDisposition.ShutdownRequested)) return
        }
    }

    private fun startResultFromCurrentState(): DeliveryEndpointStartResult =
        when (endpointDisposition.get()) {
            DeliveryEndpointDisposition.Ready -> DeliveryEndpointStartResult.AlreadyReady
            DeliveryEndpointDisposition.Starting -> DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Absent -> DeliveryEndpointStartResult.Starting
            DeliveryEndpointDisposition.Poisoned,
            DeliveryEndpointDisposition.ShutdownRequested,
            DeliveryEndpointDisposition.Terminated,
            DeliveryEndpointDisposition.Failed,
                -> DeliveryEndpointStartResult.Failed
        }

    private fun failClosedBestEffort(notice: DeliveryFailureNotice) {
        if (!notice.claimPublication()) return
        try {
            authorityPort.failClosed(notice)
        } catch (_: Throwable) {
            // The durable notice and original failure remain authoritative.
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable cells remain authoritative when signalling cannot progress.
        }
    }

    private companion object {
        private val PRESTART_DID_NOT_START = IllegalStateException("Delivery executor worker did not prestart")
        private val ADMISSION_PORT_DID_NOT_COMMIT =
            IllegalStateException("Delivery admission port did not publish one exact entry disposition")
        private val CALLBACK_AUTHORITY_OPEN_FAILED =
            IllegalStateException("Delivery callback authority could not be opened")
    }
}

private fun OperationDomain.toFactUse(): DeliveryFactUse =
    if (this == OperationDomain.Active) DeliveryFactUse.Active else DeliveryFactUse.Cleanup
