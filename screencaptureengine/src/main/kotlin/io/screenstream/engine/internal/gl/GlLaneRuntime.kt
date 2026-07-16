package io.screenstream.engine.internal.gl

import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val glEnteredOperationSafetyNanos: Long = 10_000_000_000L

internal enum class GlSchedulerCallFact {
    Detached,
    Prepared,
    Calling,
    Accepted,
    Rejected,
    Cancelled,
}

internal enum class GlLaneEntryFact {
    Unentered,
    Entered,
    Cancelled,
}

internal enum class GlLaneReturnFact {
    Empty,
    Returned,
}

internal enum class GlLaneArbitrationFact {
    Unclaimed,
    Production,
    Cleanup,
    Rejected,
}

internal enum class GlLaneCleanupFact {
    Inapplicable,
    Eligible,
    Complete,
    Residue,
}

internal enum class GlLaneFutureCallFact {
    Possible,
    Impossible,
}

internal enum class GlLaneReservationFact {
    None,
    Held,
    Released,
}

internal class GlLaneTicket internal constructor() {
    internal var epoch: Long = 0L
    internal var schedulerCall: GlSchedulerCallFact = GlSchedulerCallFact.Detached
    internal var entry: GlLaneEntryFact = GlLaneEntryFact.Unentered
    internal var returned: GlLaneReturnFact = GlLaneReturnFact.Empty
    internal var arbitration: GlLaneArbitrationFact = GlLaneArbitrationFact.Unclaimed
    internal var cleanup: GlLaneCleanupFact = GlLaneCleanupFact.Inapplicable
    internal var futureCall: GlLaneFutureCallFact = GlLaneFutureCallFact.Possible
    internal var reservation: GlLaneReservationFact = GlLaneReservationFact.None
    internal var dependencyHeld: Boolean = false
}

internal class GlOrderlyShutdownCapability internal constructor(
    private val owner: GlPipelineOwner,
) : GlPipelineOwner.OrderlyShutdownCommand {
    private var claimed: Boolean = false

    internal fun claimFor(expectedOwner: GlPipelineOwner): Boolean =
        owner.glGate.withLock {
            if (owner !== expectedOwner || claimed) return@withLock false
            claimed = true
            true
        }
}

internal class GlLaneRuntime internal constructor(
    private val glGate: ReentrantLock,
    private val settlementSignal: SettlementSignal,
    threadName: String,
) {
    private class TerminationReceipt private constructor() {
        companion object {
            val instance: TerminationReceipt = TerminationReceipt()
        }
    }

    private class PipelineExecutor(
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
        override fun terminated() = onTerminated()
    }

    private val fatalError: AtomicReference<Error?> = AtomicReference(null)
    private val terminationReceipt: AtomicReference<TerminationReceipt?> = AtomicReference(null)
    private val workerNumber: AtomicLong = AtomicLong(0L)
    private val executor: PipelineExecutor

    private var admissionOpen: Boolean = true
    private var shutdownEpoch: Long = 1L
    private var dependencies: Int = 0
    private var renderReservation: GlLaneTicket? = null
    private var teardownReservation: GlLaneTicket? = null
    private var shutdownClaimed: Boolean = false
    private var shutdownRequested: Boolean = false

    init {
        require(threadName.isNotBlank())
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${workerNumber.incrementAndGet()}").apply {
                isDaemon = false
            }
        }
        executor = PipelineExecutor(threadFactory) {
            terminationReceipt.lazySet(TerminationReceipt.instance)
            settlementSignal.signal()
        }
        check(executor.prestartCoreThread()) { "GL worker did not prestart" }
    }

    internal val observedFatalError: Error?
        get() = fatalError.get()

    internal val isExecutorTerminated: Boolean
        get() = terminationReceipt.get() === TerminationReceipt.instance

    internal fun checkFatal() {
        fatalError.get()?.let { throw it }
    }

    internal fun checkFatalLocked() {
        check(glGate.isHeldByCurrentThread)
        fatalError.get()?.let { throw it }
    }

    internal fun checkFatalForPrepared(ticket: GlLaneTicket) {
        var fatal: Error? = null
        glGate.withLock {
            fatal = fatalError.get()
            if (fatal != null && ticket.schedulerCall == GlSchedulerCallFact.Prepared) {
                cancelWithoutExecutorCallLocked(ticket)
            }
        }
        fatal?.let { throw it }
    }

    internal fun publishFatal(error: Error) {
        var signal = false
        glGate.withLock {
            if (fatalError.get() == null) {
                fatalError.set(error)
                signal = true
            }
        }
        if (signal) {
            try {
                settlementSignal.signal()
            } catch (_: Throwable) {
                // The first fatal remains authoritative.
            }
        }
    }

    internal inline fun <T> submissionBoundary(block: () -> T): T =
        try {
            block()
        } catch (error: Error) {
            publishFatal(error)
            throw error
        }

    internal fun fatalBoundary(body: () -> Unit): Runnable = Runnable {
        try {
            checkFatal()
            body()
        } catch (error: Error) {
            publishFatal(error)
            throw error
        }
    }

    internal inline fun <T> outward(block: () -> T): T {
        checkFatal()
        val result = block()
        checkFatal()
        return result
    }

    internal inline fun <T> outwardAdopt(adopt: (T) -> Unit, block: () -> T): T {
        checkFatal()
        val result = block()
        adopt(result)
        checkFatal()
        return result
    }

    internal fun issue(ticket: GlLaneTicket, reserveRender: Boolean = false): Boolean = glGate.withLock { issueLocked(ticket, reserveRender) }

    internal fun issueLocked(ticket: GlLaneTicket, reserveRender: Boolean = false): Boolean {
        check(glGate.isHeldByCurrentThread)
        fatalError.get()?.let { throw it }
        if (!admissionOpen || ticket.schedulerCall != GlSchedulerCallFact.Detached ||
            teardownReservation != null || reserveRender && renderReservation != null || dependencies == Int.MAX_VALUE
        ) {
            return false
        }
        ticket.epoch = shutdownEpoch
        ticket.schedulerCall = GlSchedulerCallFact.Prepared
        ticket.reservation = if (reserveRender) GlLaneReservationFact.Held else GlLaneReservationFact.None
        ticket.dependencyHeld = true
        dependencies += 1
        if (reserveRender) renderReservation = ticket
        return true
    }

    internal fun beginSubmission(ticket: GlLaneTicket): Boolean {
        var fatal: Error? = null
        val begun = glGate.withLock {
            fatal = fatalError.get()
            if (fatal != null) {
                if (ticket.schedulerCall == GlSchedulerCallFact.Prepared) {
                    cancelWithoutExecutorCallLocked(ticket)
                }
                return@withLock false
            }
            if (!admissionOpen || ticket.epoch != shutdownEpoch || ticket.schedulerCall != GlSchedulerCallFact.Prepared ||
                ticket.futureCall != GlLaneFutureCallFact.Possible
            ) {
                return@withLock false
            }
            ticket.schedulerCall = GlSchedulerCallFact.Calling
            true
        }
        fatal?.let { throw it }
        return begun
    }

    internal fun accepted(ticket: GlLaneTicket) = glGate.withLock {
        check(ticket.schedulerCall == GlSchedulerCallFact.Calling)
        ticket.schedulerCall = GlSchedulerCallFact.Accepted
        reduceLocked(ticket)
    }

    internal fun rejected(ticket: GlLaneTicket, retainDependency: Boolean = false) =
        glGate.withLock {
            check(ticket.schedulerCall == GlSchedulerCallFact.Calling)
            ticket.schedulerCall = GlSchedulerCallFact.Rejected
            ticket.arbitration = GlLaneArbitrationFact.Rejected
            if (ticket.entry == GlLaneEntryFact.Unentered) ticket.entry = GlLaneEntryFact.Cancelled
            releaseReservationLocked(ticket)
            if (retainDependency) {
                ticket.cleanup = GlLaneCleanupFact.Residue
            } else {
                ticket.futureCall = GlLaneFutureCallFact.Impossible
                ticket.cleanup = GlLaneCleanupFact.Complete
            }
            reduceLocked(ticket)
        }

    internal fun cancelWithoutExecutorCall(ticket: GlLaneTicket) = glGate.withLock { cancelWithoutExecutorCallLocked(ticket) }

    internal fun returnWithoutOccurrenceEntry(ticket: GlLaneTicket) {
        glGate.withLock {
            check(ticket.dependencyHeld)
            check(ticket.entry == GlLaneEntryFact.Entered)
            check(ticket.returned == GlLaneReturnFact.Empty)
            ticket.returned = GlLaneReturnFact.Returned
            ticket.futureCall = GlLaneFutureCallFact.Impossible
            ticket.cleanup = GlLaneCleanupFact.Complete
            releaseReservationLocked(ticket)
            reduceLocked(ticket)
        }
        settlementSignal.signal()
    }

    internal fun enter(ticket: GlLaneTicket): Boolean = glGate.withLock {
        if (ticket.epoch != shutdownEpoch ||
            ticket.schedulerCall != GlSchedulerCallFact.Calling && ticket.schedulerCall != GlSchedulerCallFact.Accepted ||
            ticket.entry != GlLaneEntryFact.Unentered
        ) {
            return@withLock false
        }
        ticket.entry = GlLaneEntryFact.Entered
        true
    }

    internal fun returned(ticket: GlLaneTicket, cleanupDependent: Boolean = false) = glGate.withLock {
        check(ticket.entry == GlLaneEntryFact.Entered)
        check(ticket.returned == GlLaneReturnFact.Empty)
        ticket.returned = GlLaneReturnFact.Returned
        ticket.cleanup = if (cleanupDependent) GlLaneCleanupFact.Eligible else GlLaneCleanupFact.Inapplicable
        reduceLocked(ticket)
    }

    internal fun noFurtherCall(ticket: GlLaneTicket) = glGate.withLock {
        if (!ticket.dependencyHeld) return@withLock
        ticket.futureCall = GlLaneFutureCallFact.Impossible
        ticket.cleanup = GlLaneCleanupFact.Complete
        reduceLocked(ticket)
    }

    internal fun recordArbitration(ticket: GlLaneTicket, use: OperationReturnUse) = glGate.withLock {
        if (ticket.arbitration != GlLaneArbitrationFact.Unclaimed) return@withLock
        ticket.arbitration = when (use) {
            OperationReturnUse.Unclaimed -> GlLaneArbitrationFact.Unclaimed
            OperationReturnUse.Timely -> GlLaneArbitrationFact.Production
            OperationReturnUse.Cleanup -> GlLaneArbitrationFact.Cleanup
        }
        reduceLocked(ticket)
    }

    internal fun releaseRenderReservationLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (renderReservation !== ticket || ticket.reservation != GlLaneReservationFact.Held) {
            return false
        }
        releaseReservationLocked(ticket)
        reduceLocked(ticket)
        return true
    }

    internal fun reserveTeardown(ticket: GlLaneTicket, allowDormantRender: Boolean): Boolean =
        glGate.withLock { reserveTeardownLocked(ticket, allowDormantRender) }

    internal fun issueTeardownLocked(ticket: GlLaneTicket, allowDormantRender: Boolean): Boolean {
        check(glGate.isHeldByCurrentThread)
        if (!issueLocked(ticket)) return false
        if (reserveTeardownLocked(ticket, allowDormantRender)) return true
        cancelWithoutExecutorCallLocked(ticket)
        return false
    }

    internal fun issueTeardown(ticket: GlLaneTicket, allowDormantRender: Boolean): Boolean =
        glGate.withLock { issueTeardownLocked(ticket, allowDormantRender) }

    internal fun completeTeardownReservationLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (teardownReservation !== ticket) return false
        teardownReservation = null
        return true
    }

    internal fun completeTeardownReservation(ticket: GlLaneTicket): Boolean = glGate.withLock { completeTeardownReservationLocked(ticket) }

    internal fun cancelUnusedTeardown(ticket: GlLaneTicket): Boolean = glGate.withLock {
        if (teardownReservation !== ticket) return@withLock false
        teardownReservation = null
        cancelWithoutExecutorCallLocked(ticket)
        true
    }

    internal fun ownsTeardownReservation(ticket: GlLaneTicket): Boolean = glGate.withLock { teardownReservation === ticket }

    internal fun ownsTeardownReservationLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        return teardownReservation === ticket
    }

    internal fun canInverseUnenteredRenderLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        return renderReservation !== ticket &&
                ticket.reservation == GlLaneReservationFact.Released &&
                (ticket.entry == GlLaneEntryFact.Cancelled ||
                        ticket.entry == GlLaneEntryFact.Entered && ticket.returned == GlLaneReturnFact.Returned) &&
                (ticket.schedulerCall == GlSchedulerCallFact.Cancelled ||
                        ticket.schedulerCall == GlSchedulerCallFact.Rejected ||
                        ticket.schedulerCall == GlSchedulerCallFact.Accepted)
    }

    internal fun consumeRenderReservationAfterNamespaceLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        checkFatalLocked()
        if (!canConsumeRenderReservationAfterNamespaceLocked(ticket)) return false
        releaseReservationLocked(ticket)
        ticket.cleanup = GlLaneCleanupFact.Complete
        reduceLocked(ticket)
        return true
    }

    internal fun canConsumeRenderReservationAfterNamespaceLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        return renderReservation === ticket && ticket.reservation == GlLaneReservationFact.Held && ticket.futureCall == GlLaneFutureCallFact.Impossible
    }

    internal fun canReleaseCancelledDependenciesAfterFatal(ticket: GlLaneTicket, error: Error): Boolean = glGate.withLock {
        fatalError.get() === error &&
                ticket.schedulerCall == GlSchedulerCallFact.Cancelled &&
                ticket.entry == GlLaneEntryFact.Cancelled &&
                ticket.futureCall == GlLaneFutureCallFact.Impossible &&
                !ticket.dependencyHeld &&
                ticket.reservation != GlLaneReservationFact.Held &&
                renderReservation !== ticket &&
                teardownReservation !== ticket
    }

    internal fun isTerminalNoExecutorSubmissionLocked(ticket: GlLaneTicket): Boolean {
        check(glGate.isHeldByCurrentThread)
        return (ticket.schedulerCall == GlSchedulerCallFact.Cancelled ||
                ticket.schedulerCall == GlSchedulerCallFact.Rejected) &&
                ticket.entry == GlLaneEntryFact.Cancelled &&
                ticket.returned == GlLaneReturnFact.Empty &&
                ticket.cleanup == GlLaneCleanupFact.Complete &&
                ticket.futureCall == GlLaneFutureCallFact.Impossible &&
                !ticket.dependencyHeld &&
                ticket.reservation != GlLaneReservationFact.Held &&
                renderReservation !== ticket &&
                teardownReservation !== ticket
    }

    internal fun submit(
        ticket: GlLaneTicket,
        occurrence: OperationOccurrence<*>,
        runnable: Runnable,
        rejectionRetainsDependency: Boolean = false,
    ): Boolean = submissionBoundary {
        if (!beginSubmission(ticket)) return@submissionBoundary false
        if (!occurrence.beginSubmission()) {
            cancelWithoutExecutorCall(ticket)
            return@submissionBoundary false
        }
        checkFatal()
        val rejected = try {
            executor.execute(runnable)
            null
        } catch (rejection: RejectedExecutionException) {
            rejection
        }
        if (rejected != null) {
            occurrence.publishSubmissionRejected(rejected)
            rejected(ticket, rejectionRetainsDependency)
            checkFatal()
            settlementSignal.signal()
            checkFatal()
            return@submissionBoundary false
        }
        occurrence.publishSubmissionAccepted()
        accepted(ticket)
        checkFatal()
        settlementSignal.signal()
        checkFatal()
        true
    }

    internal fun enter(ticket: GlLaneTicket, occurrence: OperationOccurrence<*>): Boolean {
        checkFatal()
        if (!enter(ticket)) return false
        checkFatal()
        val entry = occurrence.tryEnter()
        checkFatal()
        if (entry != OperationEntryResult.Entered) returnWithoutOccurrenceEntry(ticket)
        checkFatal()
        if (entry == OperationEntryResult.Entered) occurrence.requestDeadlineWake()
        checkFatal()
        if (entry != OperationEntryResult.NotCurrent) {
            settlementSignal.signal()
            checkFatal()
        }
        return entry == OperationEntryResult.Entered
    }

    internal fun finishReturn(ticket: GlLaneTicket, cleanupDependent: Boolean = false, signal: Boolean = true) {
        checkFatal()
        returned(ticket, cleanupDependent)
        checkFatal()
        if (signal) settlementSignal.signal()
    }

    internal fun retireReturned(ticket: GlLaneTicket) {
        checkFatal()
        noFurtherCall(ticket)
        checkFatal()
        settlementSignal.signal()
    }

    internal fun retireMechanicallyComplete(ticket: GlLaneTicket, occurrence: OperationOccurrence<*>, rejectionRetainsDependency: Boolean = false) {
        var use = OperationReturnUse.Unclaimed
        var complete = false
        occurrence.settlementGate.withLock {
            use = occurrence.returnCell.use
            complete = when {
                occurrence.returnCell.disposition != OperationReturnDisposition.Empty -> true
                occurrence.entryDisposition != OperationEntryDisposition.Unentered -> false
                occurrence.disposition == OperationDisposition.SchedulerRejected -> !rejectionRetainsDependency
                occurrence.disposition == OperationDisposition.DeadlineGuardFailed -> true
                occurrence.disposition == OperationDisposition.Cancelled -> true
                else -> false
            }
        }
        recordArbitration(ticket, use)
        if (complete) retireReturned(ticket)
    }

    internal fun prepareShutdownLocked(): Boolean {
        check(glGate.isHeldByCurrentThread)
        if (shutdownClaimed || !admissionOpen || shutdownEpoch == Long.MAX_VALUE ||
            dependencies != 0 || renderReservation != null || teardownReservation != null || fatalError.get() != null
        ) {
            return false
        }
        admissionOpen = false
        shutdownEpoch += 1L
        shutdownClaimed = true
        return true
    }

    internal fun requestShutdown(): Boolean {
        val request = glGate.withLock {
            if (!shutdownClaimed || shutdownRequested) return@withLock false
            shutdownRequested = true
            true
        }
        if (!request) return false
        executor.shutdown()
        return true
    }

    internal fun execute(runnable: Runnable): RejectedExecutionException? {
        checkFatal()
        return try {
            executor.execute(runnable)
            null
        } catch (rejection: RejectedExecutionException) {
            rejection
        } catch (error: Error) {
            publishFatal(error)
            throw error
        }
    }

    private fun releaseReservationLocked(ticket: GlLaneTicket) {
        if (ticket.reservation != GlLaneReservationFact.Held) return
        check(renderReservation === ticket)
        renderReservation = null
        ticket.reservation = GlLaneReservationFact.Released
    }

    private fun cancelWithoutExecutorCallLocked(ticket: GlLaneTicket) {
        check(glGate.isHeldByCurrentThread)
        if (!ticket.dependencyHeld) return
        check(ticket.schedulerCall == GlSchedulerCallFact.Prepared || ticket.schedulerCall == GlSchedulerCallFact.Calling)
        check(ticket.entry == GlLaneEntryFact.Unentered)
        ticket.schedulerCall = GlSchedulerCallFact.Cancelled
        ticket.entry = GlLaneEntryFact.Cancelled
        ticket.futureCall = GlLaneFutureCallFact.Impossible
        ticket.cleanup = GlLaneCleanupFact.Complete
        releaseReservationLocked(ticket)
        reduceLocked(ticket)
    }

    private fun reserveTeardownLocked(ticket: GlLaneTicket, allowDormantRender: Boolean): Boolean {
        check(glGate.isHeldByCurrentThread)
        fatalError.get()?.let { throw it }
        if (teardownReservation === ticket) return true
        if (teardownReservation != null || !ticket.dependencyHeld ||
            ticket.schedulerCall != GlSchedulerCallFact.Prepared ||
            ticket.futureCall != GlLaneFutureCallFact.Possible
        ) {
            return false
        }
        val render = renderReservation
        if (render == null) {
            if (dependencies != 1) return false
        } else {
            if (!allowDormantRender || dependencies != 2 ||
                render.schedulerCall != GlSchedulerCallFact.Accepted ||
                render.entry != GlLaneEntryFact.Entered ||
                render.returned != GlLaneReturnFact.Returned ||
                render.arbitration == GlLaneArbitrationFact.Unclaimed
            ) {
                return false
            }
            render.futureCall = GlLaneFutureCallFact.Impossible
            render.cleanup = GlLaneCleanupFact.Complete
            reduceLocked(render)
        }
        teardownReservation = ticket
        return true
    }

    private fun reduceLocked(ticket: GlLaneTicket) {
        if (!ticket.dependencyHeld ||
            ticket.futureCall != GlLaneFutureCallFact.Impossible ||
            ticket.schedulerCall == GlSchedulerCallFact.Calling ||
            ticket.reservation == GlLaneReservationFact.Held
        ) {
            return
        }
        if (ticket.schedulerCall == GlSchedulerCallFact.Accepted) {
            when (ticket.entry) {
                GlLaneEntryFact.Unentered -> return
                GlLaneEntryFact.Entered -> if (ticket.returned != GlLaneReturnFact.Returned) return
                GlLaneEntryFact.Cancelled -> Unit
            }
        }
        check(dependencies > 0)
        ticket.dependencyHeld = false
        dependencies -= 1
    }
}
