package io.screenstream.capture.internal.encoding

import io.screenstream.capture.internal.storage.ImmutableEncodedPayload

internal sealed interface FrameworkJpegResult {
    enum class Problem { CompressionRejected, ResourceExhausted, InternalFailure, }

    val transaction: FrameworkEncodedTransaction

    class Success(
        override val transaction: FrameworkEncodedTransaction,
        internal val payload: ImmutableEncodedPayload,
        internal val encodeDurationNanos: Long,
    ) : FrameworkJpegResult {
        init {
            require(encodeDurationNanos >= 0L)
            require(transaction.state == ManagedEncodedTransaction.State.Committed)
            require(transaction.committedPayload === payload)
        }
    }

    class Failure(
        override val transaction: FrameworkEncodedTransaction,
        internal val jpegProblem: Problem,
        internal val cause: Throwable?,
    ) : FrameworkJpegResult {
        init {
            if (jpegProblem == Problem.CompressionRejected) require(cause == null)
            check(hasValidTransactionState())
        }

        private fun hasValidTransactionState(): Boolean = when (jpegProblem) {
            Problem.CompressionRejected -> transaction.state == ManagedEncodedTransaction.State.Aborted
            Problem.ResourceExhausted ->
                (transaction.state == ManagedEncodedTransaction.State.Aborted) ||
                        ((transaction.state == ManagedEncodedTransaction.State.Committed) &&
                                (transaction.committedPayload == null))

            Problem.InternalFailure -> true
        }
    }

    class Skipped(
        override val transaction: FrameworkEncodedTransaction,
    ) : FrameworkJpegResult {
        init {
            require(transaction.state == ManagedEncodedTransaction.State.Aborted)
        }
    }
}
