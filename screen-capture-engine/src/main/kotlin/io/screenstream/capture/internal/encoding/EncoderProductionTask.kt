package io.screenstream.capture.internal.encoding

import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock

internal sealed class EncoderProductionTask {
    internal sealed interface ResourceCleanupState {
        data object Pending : ResourceCleanupState
        data object Settled : ResourceCleanupState

        class Residue : ResourceCleanupState {
            private var ordinaryCauseSlot: Exception? = null

            internal val cause: Exception
                get() = checkNotNull(ordinaryCauseSlot)

            internal val hasFailure: Boolean
                get() = ordinaryCauseSlot != null

            internal inline fun attempt(crossinline block: () -> Throwable?) {
                try {
                    when (val cause = block()) {
                        null -> Unit
                        is Exception -> record(cause)
                        else -> throw cause
                    }
                } catch (failure: Exception) {
                    record(failure)
                }
            }

            private fun record(failure: Exception) {
                if (ordinaryCauseSlot == null) ordinaryCauseSlot = failure
            }

        }
    }

    private var resourceCleanupState: ResourceCleanupState = ResourceCleanupState.Pending

    internal abstract val runtime: EncoderRuntime
    internal abstract val input: EncodingInput
    internal abstract val hasRecordedResult: Boolean

    internal abstract fun execute(clock: ElapsedRealtimeClock)
    internal open fun skipBeforeEntry() = Unit
    protected abstract fun cleanupResources(residue: ResourceCleanupState.Residue)

    /**
     * Detaches any committed payload from its transaction without copying or publishing it. A successful recorded
     * result retains the same payload.
     */
    internal open fun detachResultPayload(): Exception? = null

    internal fun settleResources(): Exception? {
        if (resourceCleanupState == ResourceCleanupState.Pending) {
            val residue = ResourceCleanupState.Residue()
            cleanupResources(residue)
            resourceCleanupState = if (residue.hasFailure) residue else ResourceCleanupState.Settled
        }
        return (resourceCleanupState as? ResourceCleanupState.Residue)?.cause
    }

    protected inline fun settleProductionCarrier(
        input: EncodingInput,
        crossinline discardReady: () -> Boolean,
        crossinline releaseEntered: () -> Boolean,
        residue: ResourceCleanupState.Residue,
    ) {
        residue.attempt {
            val released = when {
                input.carrier.ownsReadyLoan(input) -> discardReady()
                input.carrier.isIdle -> true
                else -> releaseEntered()
            }
            encoderCleanupMismatch.takeUnless { released }
        }
        residue.attempt {
            encoderCleanupMismatch.takeUnless { input.carrier.isIdle }
        }
    }

    protected fun transferCommittedPayloadIfPresent(transaction: ManagedEncodedTransaction) {
        if (transaction.state != ManagedEncodedTransaction.State.Committed) return
        val payload = transaction.committedPayload ?: return
        check(transaction.transferCommittedPayload(payload))
    }

    protected fun settleProducerTransaction(transaction: ManagedEncodedTransaction): Throwable? {
        when (transaction.state) {
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.ProducerClosed,
            ManagedEncodedTransaction.State.Faulted,
                -> if (!transaction.abort()) return encoderCleanupMismatch

            ManagedEncodedTransaction.State.Aborted -> Unit
            ManagedEncodedTransaction.State.Committed -> transaction.committedPayload?.let { payload ->
                if (!transaction.transferCommittedPayload(payload)) return encoderCleanupMismatch
            }
        }
        return if (
            (transaction.state == ManagedEncodedTransaction.State.Aborted) ||
            ((transaction.state == ManagedEncodedTransaction.State.Committed) && (transaction.committedPayload == null))
        ) {
            null
        } else {
            encoderCleanupMismatch
        }
    }
}
