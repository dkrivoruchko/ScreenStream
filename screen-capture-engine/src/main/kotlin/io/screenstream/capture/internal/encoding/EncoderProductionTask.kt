package io.screenstream.capture.internal.encoding

import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock

internal sealed class EncoderProductionTask {
    internal sealed interface NoLeafPhysicalSettlement {
        data object Pending : NoLeafPhysicalSettlement
        data object Settled : NoLeafPhysicalSettlement

        class Residue : NoLeafPhysicalSettlement {
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

    private var physicalSettlementSlot: NoLeafPhysicalSettlement = NoLeafPhysicalSettlement.Pending

    internal abstract val runtime: EncoderRuntime
    internal abstract val input: EncodingInput
    internal abstract val hasLeafResult: Boolean

    internal abstract fun execute(clock: ElapsedRealtimeClock)
    internal open fun skipBeforeEntry() = Unit
    protected abstract fun settleNoLeafPhysical(residue: NoLeafPhysicalSettlement.Residue)
    internal open fun settleDetachedLeaf(): Exception? = null

    internal fun settlePhysical(): Exception? {
        if (physicalSettlementSlot == NoLeafPhysicalSettlement.Pending) {
            val residue = NoLeafPhysicalSettlement.Residue()
            settleNoLeafPhysical(residue)
            physicalSettlementSlot = if (residue.hasFailure) residue else NoLeafPhysicalSettlement.Settled
        }
        return (physicalSettlementSlot as? NoLeafPhysicalSettlement.Residue)?.cause
    }

    protected inline fun settleProductionCarrier(
        input: EncodingInput,
        crossinline discardReady: () -> Boolean,
        crossinline releaseEntered: () -> Boolean,
        residue: NoLeafPhysicalSettlement.Residue,
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
