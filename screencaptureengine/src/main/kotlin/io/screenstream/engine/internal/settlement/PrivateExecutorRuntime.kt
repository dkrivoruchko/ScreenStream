package io.screenstream.engine.internal.settlement

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

internal class PrivateExecutorTerminationReceipt private constructor(
    internal val endpoint: PrivateExecutorRuntime,
) {
    internal companion object {
        internal fun create(endpoint: PrivateExecutorRuntime): PrivateExecutorTerminationReceipt =
            PrivateExecutorTerminationReceipt(endpoint)
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

    private val workerSequence = AtomicLong(0L)
    private val activeOperation = AtomicReference<PrivateExecutorOperation<*>?>(null)
    private val startupDisposition = AtomicReference(PrivateExecutorStartupDisposition.Constructed)
    private val startupFailure = AtomicReference<Throwable?>(null)
    private val poisoned = AtomicBoolean(false)
    private val shutdownRequested = AtomicBoolean(false)
    private val ownedTerminationReceipt: PrivateExecutorTerminationReceipt =
        PrivateExecutorTerminationReceipt.create(this)
    private val publishedTerminationReceipt = AtomicReference<PrivateExecutorTerminationReceipt?>(null)
    private val fatalSlot = DirectFatalSlot()
    private val executor: OwnedExecutor

    init {
        require(threadName.isNotBlank())
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${workerSequence.incrementAndGet()}").apply {
                isDaemon = false
            }
        }
        executor = OwnedExecutor(threadFactory) {
            if (publishedTerminationReceipt.compareAndSet(null, ownedTerminationReceipt)) {
                signalBestEffort()
            }
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
            startupDisposition.set(PrivateExecutorStartupDisposition.Failed)
            requestShutdownAfterStartupFailure()
            signalBestEffort()
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return PrivateExecutorStartupDisposition.Failed
        }
        poisoned.set(true)
        startupDisposition.set(PrivateExecutorStartupDisposition.Failed)
        requestShutdownAfterStartupFailure()
        signalBestEffort()
        return PrivateExecutorStartupDisposition.Failed
    }

    internal val observedFatal: Throwable?
        get() = fatalSlot.current

    internal val isPoisoned: Boolean
        get() = poisoned.get()

    internal val isTerminated: Boolean
        get() = publishedTerminationReceipt.get() === ownedTerminationReceipt

    internal val terminationReceipt: PrivateExecutorTerminationReceipt?
        get() = publishedTerminationReceipt.get()

    internal fun acceptsTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        receipt === ownedTerminationReceipt && receipt.endpoint === this

    internal val hasUnsettledOperation: Boolean
        get() = activeOperation.get() != null

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
