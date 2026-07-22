package io.screenstream.engine.internal.settlement

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.AbstractQueue
import kotlin.concurrent.withLock

internal enum class PrivateExecutorPhysicalDisposition {
    NotOnStack,
    OnStack,
    Returned,
}

internal enum class PrivateExecutorSubmissionResult {
    Accepted,
    EntryWon,
    SubmissionFailed,
    NotSubmitted,
}

internal enum class PrivateExecutorStartupDisposition {
    Constructed,
    Starting,
    Ready,
    Failed,
}

internal val PrivateExecutorSubmissionResult.isHandedOff: Boolean
    get() = this == PrivateExecutorSubmissionResult.Accepted ||
            this == PrivateExecutorSubmissionResult.EntryWon

internal class PrivateExecutorTerminationIdentity internal constructor()

internal class PrivateExecutorTerminationReceipt private constructor(
    internal val identity: PrivateExecutorTerminationIdentity,
) {
    internal companion object {
        internal fun create(identity: PrivateExecutorTerminationIdentity): PrivateExecutorTerminationReceipt =
            PrivateExecutorTerminationReceipt(identity)
    }
}

/** One physical slot shared by semantic execute admission and one reusable nonsemantic Metrics carrier. */
private class PrivateExecutorCoalescedSignalQueue(
    private val carrier: Runnable,
    private val settlementSignal: SettlementSignal,
) : AbstractQueue<Runnable>(), BlockingQueue<Runnable> {
    private val slot = AtomicReference<Runnable?>(null)
    private val waiterLock = ReentrantLock()
    private val notEmpty = waiterLock.newCondition()
    private val notFull = waiterLock.newCondition()
    private val carrierState = AtomicInteger(CARRIER_IDLE)

    override val size: Int
        get() = if (slot.get() == null) 0 else 1

    override fun remainingCapacity(): Int = if (slot.get() == null) 1 else 0

    override fun peek(): Runnable? = slot.get()

    override fun offer(element: Runnable): Boolean {
        val result = tryOffer(element)
        if (result == OFFER_FULL) return false
        completeOffer(result)
        return true
    }

    override fun put(element: Runnable) {
        var result: Int
        waiterLock.lockInterruptibly()
        try {
            while (true) {
                result = tryOffer(element)
                if (result != OFFER_FULL) break
                notFull.await()
            }
        } finally {
            waiterLock.unlock()
        }
        completeOffer(result)
    }

    override fun offer(element: Runnable, timeout: Long, unit: TimeUnit): Boolean {
        var remainingNanos = unit.toNanos(timeout)
        var result: Int
        waiterLock.lockInterruptibly()
        try {
            while (true) {
                result = tryOffer(element)
                if (result != OFFER_FULL) break
                if (remainingNanos <= 0L) return false
                remainingNanos = notFull.awaitNanos(remainingNanos)
            }
        } finally {
            waiterLock.unlock()
        }
        completeOffer(result)
        return true
    }

    override fun poll(): Runnable? = dequeue()

    override fun take(): Runnable {
        val removed: Runnable
        waiterLock.lockInterruptibly()
        try {
            while (true) {
                val current = slot.getAndSet(null)
                if (current != null) {
                    removed = current
                    break
                }
                notEmpty.await()
            }
        } finally {
            waiterLock.unlock()
        }
        completeDequeue(removed)
        return removed
    }

    override fun poll(timeout: Long, unit: TimeUnit): Runnable? {
        var remainingNanos = unit.toNanos(timeout)
        val removed: Runnable
        waiterLock.lockInterruptibly()
        try {
            while (true) {
                val current = slot.getAndSet(null)
                if (current != null) {
                    removed = current
                    break
                }
                if (remainingNanos <= 0L) return null
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
            }
        } finally {
            waiterLock.unlock()
        }
        completeDequeue(removed)
        return removed
    }

    override fun remove(element: Runnable): Boolean {
        while (true) {
            val current = slot.get() ?: return false
            if (element != current) return false
            if (!slot.compareAndSet(current, null)) continue
            completeRemoval(current)
            return true
        }
    }

    override fun clear() {
        val removed = slot.getAndSet(null) ?: return
        completeRemoval(removed)
    }

    override fun drainTo(destination: MutableCollection<in Runnable>): Int =
        drainTo(destination, Int.MAX_VALUE)

    override fun drainTo(destination: MutableCollection<in Runnable>, maxElements: Int): Int {
        require(destination !== this)
        if (maxElements <= 0) return 0
        val removed = slot.getAndSet(null) ?: return 0
        var addFailure: Throwable? = null
        try {
            destination.add(removed)
        } catch (raw: Throwable) {
            addFailure = raw
            throw raw
        } finally {
            if (addFailure == null) {
                completeRemoval(removed)
            } else {
                try {
                    completeRemoval(removed)
                } catch (_: Throwable) {
                    // Preserve the destination failure required by the drainTo contract.
                }
            }
        }
        return 1
    }

    override fun iterator(): MutableIterator<Runnable> {
        val snapshot = slot.get()
        return object : MutableIterator<Runnable> {
            private var available = snapshot != null
            private var removable = false

            override fun hasNext(): Boolean = available

            override fun next(): Runnable {
                if (!available) throw NoSuchElementException()
                available = false
                removable = true
                return checkNotNull(snapshot)
            }

            override fun remove() {
                if (!removable) throw IllegalStateException()
                removable = false
                val exact = snapshot ?: throw IllegalStateException()
                this@PrivateExecutorCoalescedSignalQueue.removeExact(exact)
            }
        }
    }

    private fun tryOffer(element: Runnable): Int {
        while (true) {
            val current = slot.get()
            when {
                current == null -> {
                    if (slot.compareAndSet(null, element)) return OFFER_ADDED
                }

                current === carrier && element !== carrier -> {
                    if (slot.compareAndSet(carrier, element)) return OFFER_DISPLACED_CARRIER
                }

                else -> return OFFER_FULL
            }
        }
    }

    private fun completeOffer(result: Int) {
        try {
            if (result == OFFER_DISPLACED_CARRIER) carrierDisplacedBySemanticAdmission()
        } finally {
            signalNotEmpty()
        }
    }

    private fun removeExact(element: Runnable): Boolean {
        if (!slot.compareAndSet(element, null)) return false
        completeRemoval(element)
        return true
    }

    private fun completeRemoval(removed: Runnable) {
        try {
            if (removed === carrier) carrierAdministrativelyRemoved() else admitReservedCarrier()
        } finally {
            signalNotFull()
        }
    }

    private fun completeDequeue(removed: Runnable) {
        try {
            if (removed === carrier) markCarrierOnStack() else admitReservedCarrier()
        } finally {
            signalNotFull()
        }
    }

    internal fun requestCarrier(): Boolean {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_CLOSED != 0) return false
            when (current and CARRIER_PHASE_MASK) {
                CARRIER_IDLE -> {
                    if (!carrierState.compareAndSet(current, CARRIER_RESERVED)) continue
                    admitReservedCarrier()
                    return true
                }

                CARRIER_RESERVED -> return true

                CARRIER_QUEUED,
                CARRIER_ON_STACK,
                CARRIER_RETURNING,
                    -> {
                        if (current and CARRIER_REARM != 0) return true
                        if (carrierState.compareAndSet(current, current or CARRIER_REARM)) return true
                    }

                else -> error("Unknown coalesced carrier phase")
            }
        }
    }

    internal fun seal() = closeCarrierAdmission()

    internal fun poison() = closeCarrierAdmission()

    internal fun markTerminated() = closeCarrierAdmission()

    internal val isQuiescent: Boolean
        get() {
            val current = carrierState.get()
            return current and CARRIER_PHASE_MASK == CARRIER_IDLE && current and CARRIER_REARM == 0
        }

    internal val mayInvokeCarrierBody: Boolean
        get() {
            val current = carrierState.get()
            return current and CARRIER_PHASE_MASK == CARRIER_ON_STACK && current and CARRIER_CLOSED == 0
        }

    internal fun carrierReturned() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_PHASE_MASK != CARRIER_ON_STACK) return
            val returning = (current and CARRIER_PHASE_MASK.inv()) or CARRIER_RETURNING
            if (carrierState.compareAndSet(current, returning)) break
        }
        finishCarrierReturn()
    }

    private fun dequeue(): Runnable? {
        val removed = slot.getAndSet(null) ?: return null
        completeDequeue(removed)
        return removed
    }

    private fun markCarrierOnStack() {
        while (true) {
            val current = carrierState.get()
            check(current and CARRIER_PHASE_MASK == CARRIER_QUEUED)
            val onStack = (current and CARRIER_PHASE_MASK.inv()) or CARRIER_ON_STACK
            if (carrierState.compareAndSet(current, onStack)) return
        }
    }

    private fun admitReservedCarrier() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_PHASE_MASK != CARRIER_RESERVED) return
            if (current and CARRIER_CLOSED != 0) {
                val idle = closedIdleState(current)
                if (carrierState.compareAndSet(current, idle)) {
                    signalClosedQuiescenceIfFirst(current)
                    return
                }
                continue
            }
            val queued = (current and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or CARRIER_QUEUED
            if (!carrierState.compareAndSet(current, queued)) continue
            if (slot.compareAndSet(null, carrier)) {
                signalNotEmpty()
                return
            }
            while (true) {
                val queuedState = carrierState.get()
                if (queuedState and CARRIER_PHASE_MASK != CARRIER_QUEUED) break
                val next = if (queuedState and CARRIER_CLOSED != 0) {
                    closedIdleState(queuedState)
                } else {
                    (queuedState and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or CARRIER_RESERVED
                }
                if (carrierState.compareAndSet(queuedState, next)) {
                    if (next and CARRIER_CLOSED != 0) {
                        signalClosedQuiescenceIfFirst(queuedState)
                        return
                    }
                    if (slot.get() == null) break
                    return
                }
            }
        }
    }

    private fun carrierDisplacedBySemanticAdmission() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_PHASE_MASK != CARRIER_QUEUED) return
            val next = if (current and CARRIER_CLOSED != 0) {
                closedIdleState(current)
            } else {
                (current and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or CARRIER_RESERVED
            }
            if (carrierState.compareAndSet(current, next)) {
                if (next and CARRIER_CLOSED != 0) {
                    signalClosedQuiescenceIfFirst(current)
                } else if (slot.get() == null) {
                    admitReservedCarrier()
                }
                return
            }
        }
    }

    private fun carrierAdministrativelyRemoved() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_PHASE_MASK != CARRIER_QUEUED) return
            val next = if (current and CARRIER_CLOSED != 0) {
                closedIdleState(current)
            } else {
                (current and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or CARRIER_IDLE
            }
            if (!carrierState.compareAndSet(current, next)) continue
            if (next and CARRIER_CLOSED != 0) signalClosedQuiescenceIfFirst(current)
            return
        }
    }

    private fun finishCarrierReturn() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_PHASE_MASK != CARRIER_RETURNING) return
            val next = when {
                current and CARRIER_CLOSED != 0 ->
                    closedIdleState(current)

                current and CARRIER_REARM != 0 ->
                    (current and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or CARRIER_RESERVED

                else -> (current and CARRIER_PHASE_MASK.inv()) or CARRIER_IDLE
            }
            if (!carrierState.compareAndSet(current, next)) continue
            if (next and CARRIER_CLOSED != 0) {
                signalClosedQuiescenceIfFirst(current)
            } else if (next and CARRIER_PHASE_MASK == CARRIER_RESERVED) {
                admitReservedCarrier()
            }
            return
        }
    }

    private fun closeCarrierAdmission() {
        while (true) {
            val current = carrierState.get()
            if (current and CARRIER_CLOSED != 0) return
            val closing = current or CARRIER_CLOSED
            val phase = current and CARRIER_PHASE_MASK
            val next = if (phase == CARRIER_IDLE || phase == CARRIER_RESERVED) {
                closedIdleState(closing)
            } else {
                closing
            }
            if (carrierState.compareAndSet(current, next)) {
                if (next and CARRIER_CLOSED_QUIESCENCE_SIGNALED != 0) {
                    signalClosedQuiescenceIfFirst(current)
                }
                return
            }
        }
    }

    private fun closedIdleState(current: Int): Int =
        (current and (CARRIER_PHASE_MASK or CARRIER_REARM).inv()) or
            CARRIER_IDLE or CARRIER_CLOSED_QUIESCENCE_SIGNALED

    private fun signalClosedQuiescenceIfFirst(previous: Int) {
        if (previous and CARRIER_CLOSED_QUIESCENCE_SIGNALED != 0) return
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Closed quiescence is durable; signalling is only a best-effort prompt to Control.
        }
    }

    private fun signalNotEmpty() {
        waiterLock.withLock {
            notEmpty.signal()
        }
    }

    private fun signalNotFull() {
        waiterLock.withLock {
            notFull.signal()
        }
    }

    private companion object {
        private const val OFFER_FULL = 0
        private const val OFFER_ADDED = 1
        private const val OFFER_DISPLACED_CARRIER = 2
        private const val CARRIER_PHASE_MASK = 0x7
        private const val CARRIER_IDLE = 0
        private const val CARRIER_RESERVED = 1
        private const val CARRIER_QUEUED = 2
        private const val CARRIER_ON_STACK = 3
        private const val CARRIER_RETURNING = 4
        private const val CARRIER_REARM = 1 shl 3
        private const val CARRIER_CLOSED = 1 shl 4
        private const val CARRIER_CLOSED_QUIESCENCE_SIGNALED = 1 shl 5
    }
}

internal class PrivateExecutorOperation<R : OperationEvidence> internal constructor(
    internal val endpoint: PrivateExecutorRuntime,
    internal val occurrence: OperationOccurrence<R>,
    enteredWork: Runnable,
) {
    private val physicalDisposition = AtomicReference(PrivateExecutorPhysicalDisposition.NotOnStack)

    internal val outerRunnable: Runnable = Runnable {
        endpoint.runOperation(this, enteredWork)
    }

    internal val physicalState: PrivateExecutorPhysicalDisposition
        get() = physicalDisposition.get()

    internal fun markOnStack(): Boolean =
        physicalDisposition.compareAndSet(
            PrivateExecutorPhysicalDisposition.NotOnStack,
            PrivateExecutorPhysicalDisposition.OnStack,
        )

    internal fun markReturned(): Boolean =
        physicalDisposition.compareAndSet(
            PrivateExecutorPhysicalDisposition.OnStack,
            PrivateExecutorPhysicalDisposition.Returned,
        )
}

internal class PrivateExecutorRuntime(
    threadName: String,
    private val settlementSignal: SettlementSignal,
    enableCoalescedSignalCarrier: Boolean = false,
) {
    private class OwnedExecutor(
        threadFactory: ThreadFactory,
        private val onTerminated: () -> Unit,
    ) : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.NANOSECONDS,
        ArrayBlockingQueue(1),
        threadFactory,
        AbortPolicy(),
    ) {
        override fun terminated() {
            onTerminated()
        }
    }

    private class CoalescedSignalOwnedExecutor(
        private val coalescedQueue: PrivateExecutorCoalescedSignalQueue,
        private val carrier: Runnable,
        threadFactory: ThreadFactory,
        private val onTerminated: () -> Unit,
    ) : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.NANOSECONDS,
        coalescedQueue,
        threadFactory,
        AbortPolicy(),
    ) {
        override fun afterExecute(runnable: Runnable, throwable: Throwable?) {
            if (runnable === carrier) coalescedQueue.carrierReturned()
        }

        override fun terminated() {
            onTerminated()
        }
    }

    private val workerSequence = AtomicLong(0L)
    private val activeOperation = AtomicReference<PrivateExecutorOperation<*>?>(null)
    private val startupDisposition = AtomicReference(PrivateExecutorStartupDisposition.Constructed)
    private val startupFailure = AtomicReference<Throwable?>(null)
    private val poisoned = AtomicBoolean(false)
    private val shutdownRequested = AtomicBoolean(false)
    private val terminationIdentity = PrivateExecutorTerminationIdentity()
    private val ownedTerminationReceipt: PrivateExecutorTerminationReceipt =
        PrivateExecutorTerminationReceipt.create(terminationIdentity)
    private val publishedTerminationReceipt = AtomicReference<PrivateExecutorTerminationReceipt?>(null)
    private val fatalSlot = DirectFatalSlot()
    private val coalescedSignalFailure: AtomicReference<Throwable?>? =
        if (enableCoalescedSignalCarrier) AtomicReference(null) else null
    private val coalescedSignalCarrier: Runnable? =
        if (enableCoalescedSignalCarrier) Runnable { runCoalescedSignalCarrier() } else null
    private val coalescedSignalQueue: PrivateExecutorCoalescedSignalQueue? =
        if (coalescedSignalCarrier == null) {
            null
        } else {
            PrivateExecutorCoalescedSignalQueue(coalescedSignalCarrier, settlementSignal)
        }
    private val executor: ThreadPoolExecutor

    init {
        require(threadName.isNotBlank())
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${workerSequence.incrementAndGet()}").apply {
                isDaemon = false
            }
        }
        val onTerminated = {
            coalescedSignalQueue?.markTerminated()
            if (publishedTerminationReceipt.compareAndSet(null, ownedTerminationReceipt)) {
                signalBestEffort()
            }
        }
        executor = if (coalescedSignalQueue == null || coalescedSignalCarrier == null) {
            OwnedExecutor(threadFactory, onTerminated)
        } else {
            CoalescedSignalOwnedExecutor(
                coalescedQueue = coalescedSignalQueue,
                carrier = coalescedSignalCarrier,
                threadFactory = threadFactory,
                onTerminated = onTerminated,
            )
        }
    }

    internal val startupState: PrivateExecutorStartupDisposition
        get() = startupDisposition.get()

    internal val observedStartupFailure: Throwable?
        get() = startupFailure.get()

    internal fun prestart(): PrivateExecutorStartupDisposition {
        if (!startupDisposition.compareAndSet(
                PrivateExecutorStartupDisposition.Constructed,
                PrivateExecutorStartupDisposition.Starting,
            )
        ) {
            return startupDisposition.get()
        }
        try {
            val started = executor.prestartAllCoreThreads()
            if (started == 1) {
                startupDisposition.set(PrivateExecutorStartupDisposition.Ready)
                signalBestEffort()
                return PrivateExecutorStartupDisposition.Ready
            }
            startupFailure.compareAndSet(null, PRESTART_DID_NOT_START)
        } catch (raw: Throwable) {
            startupFailure.compareAndSet(null, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) fatalSlot.publish(raw)
            poisoned.set(true)
            coalescedSignalQueue?.poison()
            startupDisposition.set(PrivateExecutorStartupDisposition.Failed)
            requestShutdownAfterStartupFailure()
            signalBestEffort()
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return PrivateExecutorStartupDisposition.Failed
        }
        poisoned.set(true)
        coalescedSignalQueue?.poison()
        startupDisposition.set(PrivateExecutorStartupDisposition.Failed)
        requestShutdownAfterStartupFailure()
        signalBestEffort()
        return PrivateExecutorStartupDisposition.Failed
    }

    internal val observedFatal: Throwable?
        get() = fatalSlot.current

    internal val observedCoalescedSignalFailure: Throwable?
        get() = coalescedSignalFailure?.get()

    internal val isPoisoned: Boolean
        get() = poisoned.get()

    internal val isTerminated: Boolean
        get() = publishedTerminationReceipt.get() === ownedTerminationReceipt

    internal val terminationReceipt: PrivateExecutorTerminationReceipt?
        get() = publishedTerminationReceipt.get()

    internal fun acceptsTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        receipt === ownedTerminationReceipt && receipt.identity === terminationIdentity

    internal val hasUnsettledOperation: Boolean
        get() = activeOperation.get() != null

    internal val isCoalescedSignalCarrierQuiescent: Boolean
        get() = coalescedSignalQueue?.isQuiescent ?: true

    internal fun requestCoalescedSignal(): Boolean =
        coalescedSignalQueue?.requestCarrier() ?: false

    internal fun sealCoalescedSignalCarrier() {
        coalescedSignalQueue?.seal()
    }

    internal fun <R : OperationEvidence> operation(
        occurrence: OperationOccurrence<R>,
        enteredWork: Runnable,
    ): PrivateExecutorOperation<R> = PrivateExecutorOperation(
        endpoint = this,
        occurrence = occurrence,
        enteredWork = enteredWork,
    )

    internal fun submit(operation: PrivateExecutorOperation<*>): PrivateExecutorSubmissionResult {
        if (operation.endpoint !== this || startupDisposition.get() != PrivateExecutorStartupDisposition.Ready ||
            poisoned.get() || shutdownRequested.get()
        ) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        if (!activeOperation.compareAndSet(null, operation)) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val begun = operation.occurrence.settlementGate.withLock {
            operation.occurrence.beginSubmissionLocked()
        }
        if (!begun) {
            activeOperation.compareAndSet(operation, null)
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        return try {
            executor.execute(operation.outerRunnable)
            operation.occurrence.settlementGate.withLock {
                operation.occurrence.publishSubmissionAcceptedLocked()
            }
            signalBestEffort()
            PrivateExecutorSubmissionResult.Accepted
        } catch (raw: Throwable) {
            if (FatalThrowablePolicy.isDirectFatal(raw)) {
                fatalSlot.publish(raw)
                poisoned.set(true)
                coalescedSignalQueue?.poison()
                try {
                    operation.occurrence.settlementGate.withLock {
                        operation.occurrence.publishSubmissionAmbiguousFatal(raw)
                    }
                } catch (_: Throwable) {
                    // The exact direct fatal remains authoritative over failed best-effort settlement.
                }
                signalBestEffort()
                FatalThrowablePolicy.rethrow(raw)
            }
            poisoned.set(true)
            coalescedSignalQueue?.poison()
            val exception = raw as Exception
            val rejection = operation.occurrence.settlementGate.withLock {
                operation.occurrence.publishSubmissionFailedLocked(exception)
            }
            signalBestEffort()
            if (rejection == OperationSubmissionRejectionResult.EntryWon) {
                PrivateExecutorSubmissionResult.EntryWon
            } else {
                PrivateExecutorSubmissionResult.SubmissionFailed
            }
        }
    }

    internal fun releaseSettledOperation(operation: PrivateExecutorOperation<*>): Boolean {
        if (operation.endpoint !== this) {
            return false
        }
        val submissionSettled = operation.occurrence.settlementGate.withLock {
            val submissionComplete =
                operation.occurrence.submissionDisposition != OperationSubmissionDisposition.None &&
                        operation.occurrence.submissionDisposition != OperationSubmissionDisposition.Submitting
            val enteredWorkComplete =
                operation.occurrence.entryDisposition != OperationEntryDisposition.Entered ||
                        operation.occurrence.returnCell.disposition != OperationReturnDisposition.Empty
            val physicalComplete = when (operation.occurrence.submissionDisposition) {
                OperationSubmissionDisposition.Rejected,
                OperationSubmissionDisposition.Cancelled,
                    -> operation.physicalState == PrivateExecutorPhysicalDisposition.Returned ||
                        operation.physicalState == PrivateExecutorPhysicalDisposition.NotOnStack && isTerminated

                OperationSubmissionDisposition.Accepted ->
                    operation.physicalState == PrivateExecutorPhysicalDisposition.Returned

                OperationSubmissionDisposition.None,
                OperationSubmissionDisposition.Submitting,
                    -> false
            }
            submissionComplete && enteredWorkComplete && physicalComplete
        }
        if (!submissionSettled) return false
        return activeOperation.compareAndSet(operation, null)
    }

    internal fun requestShutdown(): Boolean {
        if (!shutdownRequested.compareAndSet(false, true)) return false
        coalescedSignalQueue?.seal()
        executor.shutdown()
        return true
    }

    private fun requestShutdownAfterStartupFailure() {
        if (shutdownRequested.compareAndSet(false, true)) executor.shutdown()
    }

    internal fun runOperation(operation: PrivateExecutorOperation<*>, enteredWork: Runnable) {
        if (operation.endpoint !== this || !operation.markOnStack()) return
        try {
            val entryResult = operation.occurrence.settlementGate.withLock {
                if (activeOperation.get() !== operation || poisoned.get()) {
                    operation.occurrence.settleInertBeforeEntryLocked()
                    null
                } else {
                    operation.occurrence.tryEnterLocked()
                }
            }
            when (entryResult) {
                OperationEntryResult.Entered -> {
                    signalBestEffort()
                    enteredWork.run()
                }

                OperationEntryResult.InvalidDeadline,
                OperationEntryResult.NotCurrent,
                null,
                    -> signalBestEffort()
            }
        } catch (raw: Throwable) {
            if (raw is Exception) {
                operation.occurrence.publishThrownReturn(raw)
                signalBestEffort()
            } else {
                fatalSlot.publish(raw)
                poisoned.set(true)
                coalescedSignalQueue?.poison()
                try {
                    operation.occurrence.settlementGate.withLock {
                        operation.occurrence.publishDirectFatalReturnLocked(raw)
                    }
                } catch (_: Throwable) {
                    // The exact direct fatal remains authoritative over failed best-effort settlement.
                }
                signalBestEffort()
                FatalThrowablePolicy.rethrow(raw)
            }
        } finally {
            operation.markReturned()
            signalBestEffort()
        }
    }

    private fun runCoalescedSignalCarrier() {
        val queue = coalescedSignalQueue ?: return
        if (!queue.mayInvokeCarrierBody) return
        try {
            settlementSignal.signal()
        } catch (raw: Throwable) {
            coalescedSignalFailure?.compareAndSet(null, raw)
            poisoned.set(true)
            queue.poison()
            if (FatalThrowablePolicy.isDirectFatal(raw)) {
                fatalSlot.publish(raw)
                FatalThrowablePolicy.rethrow(raw)
            }
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
        private val PRESTART_DID_NOT_START = IllegalStateException("Private executor worker did not prestart")
    }
}
