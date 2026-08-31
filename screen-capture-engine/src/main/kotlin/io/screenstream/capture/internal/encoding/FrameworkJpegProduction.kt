package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock

internal class FrameworkJpegProduction(
    override val runtime: EncoderRuntime,
    override val input: EncodingInput,
    internal val transaction: FrameworkEncodedTransaction,
    private val jpegQuality: Int,
) : EncoderProductionTask() {
    internal var result: FrameworkJpegResult? = null
        private set

    override val hasLeafResult: Boolean
        get() = result != null

    init {
        require(jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE)
        require(input.carrier === runtime.carrier)
    }

    override fun execute(clock: ElapsedRealtimeClock) {
        check(result == null)
        result = executeProduction(clock)
    }

    override fun skipBeforeEntry() {
        check(result == null)
        result = checkNotNull(runtime.skipFrameworkBeforeEntry(this))
    }

    override fun settleNoLeafPhysical(residue: NoLeafPhysicalSettlement.Residue) {
        residue.attempt {
            val owner = runtime.requireBitmapOwner()
            if (owner.isInUse && !owner.finishUse()) encoderCleanupMismatch else null
        }
        settleProductionCarrier(
            input = input,
            discardReady = { runtime.releaseReadyAfterRejectedAdmission(this) },
            releaseEntered = { runtime.releaseFrameworkUseAfterReturn(this) },
            residue = residue,
        )
        residue.attempt { settleProducerTransaction(transaction) }
    }

    override fun settleDetachedLeaf(): Exception? = try {
        when (val jpeg = checkNotNull(result)) {
            is FrameworkJpegResult.Success -> check(jpeg.transaction.transferCommittedPayload(jpeg.payload))
            is FrameworkJpegResult.Failure -> transferCommittedPayloadIfPresent(jpeg.transaction)
            is FrameworkJpegResult.Skipped -> Unit
        }
        null
    } catch (failure: Exception) {
        failure
    }

    private fun executeProduction(clock: ElapsedRealtimeClock): FrameworkJpegResult {
        val carrierBuffer = try {
            runtime.enterFrameworkUse(this)
        } catch (failure: Exception) {
            val resourceSettlementFailure = settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted = false, carrierEntered = false)
            return settleFrameworkFailure(fallbackCause = failure, primaryFailure = failure, cleanupFailure = resourceSettlementFailure)
        } ?: run {
            val cause = IllegalStateException("Framework production did not own its exact ready carrier")
            val resourceSettlementFailure = settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted = false, carrierEntered = false)
            return settleFrameworkFailure(fallbackCause = cause, primaryFailure = cause, cleanupFailure = resourceSettlementFailure)
        }
        val bitmapOwner = try {
            runtime.requireBitmapOwner()
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = false, cause = failure)
        }
        val bitmapUseAcquired = try {
            bitmapOwner.beginUse()
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = false, cause = failure)
        }
        if (!bitmapUseAcquired) {
            return settleEnteredFrameworkFailure(
                bitmapUseStarted = false,
                cause = IllegalStateException("Framework Bitmap was not available for the admitted production"),
            )
        }

        val startedAtNanos = try {
            clock.nowNanos()
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }
        try {
            bitmapOwner.transferExactRgba(carrierBuffer)
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }

        val compressed = try {
            bitmapOwner.compressOnce(jpegQuality, transaction)
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        } catch (failure: OutOfMemoryError) {
            if (!transaction.hasFaultedResourceExhaustionCause(failure)) throw failure
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }

        try {
            transaction.outputStream.close()
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }

        if (transaction.state == ManagedEncodedTransaction.State.Faulted) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = null)
        }
        if (!compressed) return settleCompressionRejected()

        val finishedAtNanos = try {
            clock.nowNanos()
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }
        val durationNanos = try {
            checkedJpegEncodeDurationNanos(startedAtNanos, finishedAtNanos)
        } catch (failure: Exception) {
            return settleEnteredFrameworkFailure(bitmapUseStarted = true, cause = failure)
        }

        val resourceSettlementFailure = settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted = true, carrierEntered = true)
        resourceSettlementFailure?.let {
            return settleFrameworkFailure(fallbackCause = it, primaryFailure = null, cleanupFailure = it)
        }

        val committed = try {
            transaction.commit()
        } catch (failure: Exception) {
            return settleFrameworkFailure(fallbackCause = failure, primaryFailure = failure, cleanupFailure = null)
        }
        if (!committed) {
            return settleFrameworkFailure(fallbackCause = null, primaryFailure = null, cleanupFailure = null)
        }
        val payload = transaction.committedPayload
            ?: return FrameworkJpegResult.Failure(
                transaction = transaction,
                jpegProblem = FrameworkJpegResult.Problem.InternalFailure,
                cause = IllegalStateException("Committed Framework transaction had no payload"),
            )
        return FrameworkJpegResult.Success(transaction, payload, durationNanos)
    }

    private fun settleEnteredFrameworkFailure(bitmapUseStarted: Boolean, cause: Throwable?): FrameworkJpegResult.Failure {
        val resourceSettlementFailure = settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted = bitmapUseStarted, carrierEntered = true)
        return settleFrameworkFailure(fallbackCause = cause ?: resourceSettlementFailure, primaryFailure = cause, cleanupFailure = resourceSettlementFailure)
    }

    private fun settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted: Boolean, carrierEntered: Boolean): Exception? {
        var ordinaryFailure: Exception? = null
        if (bitmapUseStarted) {
            try {
                if (!runtime.requireBitmapOwner().finishUse()) {
                    ordinaryFailure = encoderCleanupMismatch
                }
            } catch (failure: Exception) {
                ordinaryFailure = failure
            }
        }
        try {
            val released = if (carrierEntered) {
                runtime.releaseFrameworkUseAfterReturn(this)
            } else {
                runtime.releaseReadyAfterRejectedAdmission(this)
            }
            if ((!released) && (ordinaryFailure == null)) {
                ordinaryFailure = encoderCleanupMismatch
            }
        } catch (failure: Exception) {
            if (ordinaryFailure == null) ordinaryFailure = failure
        }
        return ordinaryFailure
    }

    private fun settleCompressionRejected(): FrameworkJpegResult.Failure {
        val resourceSettlementFailure = settleFrameworkResourcesAfterOrdinaryReturn(bitmapUseStarted = true, carrierEntered = true)
        resourceSettlementFailure?.let {
            return settleFrameworkFailure(fallbackCause = it, primaryFailure = null, cleanupFailure = it)
        }
        val abortFailure = abortFrameworkTransactionAfterOrdinaryReturn()
        abortFailure?.let {
            return FrameworkJpegResult.Failure(transaction = transaction, jpegProblem = FrameworkJpegResult.Problem.InternalFailure, cause = it)
        }
        return FrameworkJpegResult.Failure(transaction = transaction, jpegProblem = FrameworkJpegResult.Problem.CompressionRejected, cause = null)
    }

    private fun settleFrameworkFailure(fallbackCause: Throwable?, primaryFailure: Throwable?, cleanupFailure: Throwable?): FrameworkJpegResult.Failure {
        val failureKind = transaction.failureKind
        val abortFailure = abortFrameworkTransactionAfterOrdinaryReturn()
        val cause: Throwable?
        val jpegProblem: FrameworkJpegResult.Problem
        when {
            abortFailure != null -> {
                cause = primaryFailure ?: fallbackCause ?: cleanupFailure ?: abortFailure
                jpegProblem = FrameworkJpegResult.Problem.InternalFailure
            }

            cleanupFailure != null -> {
                cause = primaryFailure ?: fallbackCause ?: cleanupFailure
                jpegProblem = FrameworkJpegResult.Problem.InternalFailure
            }

            failureKind == ManagedEncodedTransaction.FailureKind.ResourceExhausted -> {
                cause = primaryFailure ?: fallbackCause
                jpegProblem = FrameworkJpegResult.Problem.ResourceExhausted
            }

            else -> {
                cause = fallbackCause
                jpegProblem = FrameworkJpegResult.Problem.InternalFailure
            }
        }
        return FrameworkJpegResult.Failure(transaction = transaction, jpegProblem = jpegProblem, cause = cause)
    }

    private fun abortFrameworkTransactionAfterOrdinaryReturn(): Exception? = try {
        when (transaction.state) {
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.ProducerClosed,
            ManagedEncodedTransaction.State.Faulted,
                -> if (transaction.abort()) null else encoderCleanupMismatch

            ManagedEncodedTransaction.State.Aborted -> null
            ManagedEncodedTransaction.State.Committed -> encoderCleanupMismatch
        }
    } catch (failure: Exception) {
        failure
    }
}
