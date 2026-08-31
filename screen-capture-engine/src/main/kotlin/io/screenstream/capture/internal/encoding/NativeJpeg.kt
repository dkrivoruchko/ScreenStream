package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import java.nio.ByteBuffer

internal val nativeJpegSettlementMismatch: RuntimeException =
    object : RuntimeException("Native JPEG ownership failed internally", null, false, false) {}

internal class NativeJpegProduction(
    override val runtime: EncoderRuntime,
    override val input: EncodingInput,
    internal val transaction: NativeEncodedTransaction,
    private val jpegQuality: Int,
    internal val healthCell: NativeHealthCell,
    private val nativeJpeg: NativeJpegFacade,
) : EncoderProductionTask() {
    private val invocationEvidence: NativeInvocationEvidenceCell = NativeInvocationEvidenceCell()
    internal val result: NativeJpegResult = NativeJpegResult(transaction)
    private var durationRecorded: Boolean = false
    private var encodeDurationNanos: Long = 0L
    private var durationFailed: Boolean = false
    private var resultBlockSlot: ByteBuffer? = null

    init {
        require(input.carrier === runtime.carrier)
        require(jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE)
    }

    internal val hasResultBlock: Boolean
        get() = resultBlockSlot != null

    override val hasLeafResult: Boolean
        get() = result.isRecorded

    private fun allocateResultBlockForEntry(): ByteBuffer {
        check(resultBlockSlot == null)
        return nativeJpeg.newResultBlock().also { resultBlockSlot = it }
    }

    internal fun recordSkippedBeforeEntry(): NativeJpegResult = result.recordSkippedBeforeEntry()

    override fun execute(clock: ElapsedRealtimeClock) {
        check(!result.isRecorded)
        check(executeProduction(clock) === result)
    }

    override fun skipBeforeEntry() {
        check(!result.isRecorded)
        check(runtime.skipNativeBeforeEntry(this) === result)
    }

    override fun settleNoLeafPhysical(residue: NoLeafPhysicalSettlement.Residue) {
        settleProductionCarrier(
            input = input,
            discardReady = { runtime.releaseNativeReadyBeforeEntry(this) },
            releaseEntered = { runtime.releaseNativeUseAfterReturn(this) },
            residue = residue,
        )
        residue.attempt { settleProducerTransaction(transaction) }
    }

    override fun settleDetachedLeaf(): Exception? = try {
        with(result) {
            when (requireDisposition()) {
                NativeJpegDisposition.Returned.CompleteTransfer ->
                    check(transaction.transferCommittedPayload(checkNotNull(payload)))

                NativeJpegDisposition.Returned.SafeCompressorRejection,
                NativeJpegDisposition.Returned.RequiredResourceExhaustion,
                NativeJpegDisposition.Returned.UnsafeInternalFailure,
                    -> transferCommittedPayloadIfPresent(transaction)

                NativeJpegDisposition.SkippedBeforeEntry -> Unit
            }
        }
        null
    } catch (failure: Exception) {
        failure
    }

    private fun executeProduction(clock: ElapsedRealtimeClock): NativeJpegResult {
        val carrierBuffer = try {
            runtime.enterNativeUse(this)
        } catch (_: Exception) {
            return settleBeforeNativeCallFailure(clock = clock, startedAtNanos = null)
        } ?: return settleBeforeNativeCallFailure(clock = clock, startedAtNanos = null)
        invocationEvidence.markCarrierEntered()

        val startedAtNanos = try {
            clock.nowNanos()
        } catch (_: Exception) {
            return settleBeforeNativeCallFailure(clock = clock, startedAtNanos = null)
        }

        if (!isExactNativeInput(carrierBuffer)) {
            return settleBeforeNativeCallFailure(clock = clock, startedAtNanos = startedAtNanos)
        }

        val resultBlock = try {
            allocateResultBlockForEntry()
        } catch (_: Exception) {
            return settleBeforeNativeCallFailure(clock = clock, startedAtNanos = startedAtNanos)
        }

        var thrownThrowable: Throwable? = null
        try {
            nativeJpeg.compress(
                carrierBuffer = carrierBuffer,
                pixelByteCount = runtime.layout.byteCount.toLong(),
                width = runtime.layout.widthPx,
                height = runtime.layout.heightPx,
                stride = runtime.layout.rowByteCount,
                quality = jpegQuality,
                sink = transaction.segmentSink,
                resultBlock = resultBlock,
            )
        } catch (failure: Exception) {
            thrownThrowable = failure
        } catch (failure: OutOfMemoryError) {
            if (!transaction.hasFaultedResourceExhaustionCause(failure)) throw failure
            thrownThrowable = failure
        }

        val evidence = invocationEvidence
        evidence.recordInvocationExit(resultBlock, thrownThrowable)
        val producerFailure = try {
            transaction.closeNativeProducer()
            null
        } catch (failure: Exception) {
            failure
        }
        val carrierFailure = settleCarrier()
        recordEncodeDuration(clock, startedAtNanos)

        var disposition = if ((producerFailure == null) && (carrierFailure == null)) {
            evidence.classifyInvocationOutcome(transaction)
        } else {
            NativeJpegDisposition.Returned.UnsafeInternalFailure
        }
        if (durationFailed) {
            disposition = NativeJpegDisposition.Returned.UnsafeInternalFailure
        }

        disposition = settleReturnedDisposition(disposition)

        val payload = if (disposition == NativeJpegDisposition.Returned.CompleteTransfer) {
            checkNotNull(transaction.committedPayload)
        } else {
            null
        }
        val recorded = result.recordReturned(
            disposition = disposition,
            resultBlock = resultBlockSlot,
            encodeDurationNanos = encodeDurationNanos,
            payload = payload,
        )
        check(recorded === result)
        return recorded
    }

    private fun settleCarrier(): Exception? {
        val evidence = invocationEvidence
        val settled = try {
            when (evidence.carrierSettlementState) {
                NativeInvocationEvidenceCell.CarrierSettlementState.NotEntered -> runtime.releaseNativeReadyBeforeEntry(this)
                NativeInvocationEvidenceCell.CarrierSettlementState.Entered -> runtime.releaseNativeUseAfterReturn(this)
                NativeInvocationEvidenceCell.CarrierSettlementState.Settled -> return null
                NativeInvocationEvidenceCell.CarrierSettlementState.SettlementFailed ->
                    return evidence.carrierSettlementFailure ?: nativeJpegSettlementMismatch
            }
        } catch (failure: Exception) {
            evidence.recordCarrierSettlementFailure(failure)
            return failure
        }
        evidence.recordCarrierSettlement(settled)
        return if (settled) null else nativeJpegSettlementMismatch
    }

    private fun settleBeforeNativeCallFailure(clock: ElapsedRealtimeClock, startedAtNanos: Long?): NativeJpegResult {
        settleCarrier()
        startedAtNanos?.let { recordEncodeDuration(clock, it) }
        abortTransaction()

        val disposition = NativeJpegDisposition.Returned.UnsafeInternalFailure
        val recorded = result.recordReturned(
            disposition = disposition,
            resultBlock = resultBlockSlot,
            encodeDurationNanos = encodeDurationNanos,
            payload = null,
        )
        check(recorded === result)
        return recorded
    }

    private fun settleReturnedDisposition(disposition: NativeJpegDisposition.Returned): NativeJpegDisposition.Returned {
        if (disposition != NativeJpegDisposition.Returned.CompleteTransfer) {
            val abortFailure = abortTransaction()
            if (abortFailure != null) {
                return NativeJpegDisposition.Returned.UnsafeInternalFailure
            }
            return disposition
        }

        val committed = try {
            transaction.commit()
        } catch (_: Exception) {
            false
        }
        if (committed) {
            return NativeJpegDisposition.Returned.CompleteTransfer
        }

        val abortFailure = abortTransaction()
        if (abortFailure != null) {
            return NativeJpegDisposition.Returned.UnsafeInternalFailure
        }

        val resourceExhausted = transaction.failureKind == ManagedEncodedTransaction.FailureKind.ResourceExhausted
        return if (resourceExhausted) {
            NativeJpegDisposition.Returned.RequiredResourceExhaustion
        } else {
            NativeJpegDisposition.Returned.UnsafeInternalFailure
        }
    }

    private fun abortTransaction(): Exception? = try {
        when (transaction.state) {
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.ProducerClosed,
            ManagedEncodedTransaction.State.Faulted,
                -> if (!transaction.abort()) return nativeJpegSettlementMismatch

            ManagedEncodedTransaction.State.Aborted -> Unit
            ManagedEncodedTransaction.State.Committed -> return nativeJpegSettlementMismatch
        }
        null
    } catch (failure: Exception) {
        failure
    }

    private fun recordEncodeDuration(clock: ElapsedRealtimeClock, startedAtNanos: Long) {
        check(!durationRecorded)
        durationRecorded = true
        try {
            encodeDurationNanos = checkedJpegEncodeDurationNanos(startedAtNanos, clock.nowNanos())
        } catch (_: Exception) {
            durationFailed = true
        }
    }

    private fun isExactNativeInput(carrierBuffer: ByteBuffer): Boolean {
        val layout = runtime.layout
        return ((runtime.backendState is EncoderBackendState.NativeOnNativeCarrier) &&
                (healthCell.state == NativeHealthCell.State.Enabled) &&
                (carrierBuffer.isExactWritableRgbaCarrier(layout.byteCount)) &&
                (jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE))
    }
}

internal sealed interface NativeJpegDisposition {
    enum class Returned : NativeJpegDisposition { CompleteTransfer, SafeCompressorRejection, RequiredResourceExhaustion, UnsafeInternalFailure, }

    data object SkippedBeforeEntry : NativeJpegDisposition
}

internal class NativeJpegResult(internal val transaction: NativeEncodedTransaction) {
    private var dispositionSlot: NativeJpegDisposition? = null

    internal val isRecorded: Boolean
        get() = dispositionSlot != null

    internal var encodeDurationNanos: Long = 0L
        private set

    internal var payload: ImmutableEncodedPayload? = null
        private set

    internal fun requireDisposition(): NativeJpegDisposition = checkNotNull(dispositionSlot)

    internal fun recordReturned(
        disposition: NativeJpegDisposition.Returned,
        resultBlock: ByteBuffer?,
        encodeDurationNanos: Long,
        payload: ImmutableEncodedPayload?,
    ): NativeJpegResult {
        check(dispositionSlot == null)
        require(encodeDurationNanos >= 0L)
        when (disposition) {
            NativeJpegDisposition.Returned.CompleteTransfer -> {
                check(resultBlock != null)
                check(transaction.state == ManagedEncodedTransaction.State.Committed)
                check(transaction.committedPayload === payload)
            }

            NativeJpegDisposition.Returned.SafeCompressorRejection -> {
                check(resultBlock != null && payload == null)
                check(transaction.state == ManagedEncodedTransaction.State.Aborted)
            }

            NativeJpegDisposition.Returned.RequiredResourceExhaustion -> {
                check(payload == null)
                check(transaction.state == ManagedEncodedTransaction.State.Aborted)
            }

            NativeJpegDisposition.Returned.UnsafeInternalFailure -> check(payload == null)
        }
        this.encodeDurationNanos = encodeDurationNanos
        this.payload = payload
        dispositionSlot = disposition
        return this
    }

    internal fun recordSkippedBeforeEntry(): NativeJpegResult {
        check(dispositionSlot == null)
        check(transaction.state == ManagedEncodedTransaction.State.Aborted)
        dispositionSlot = NativeJpegDisposition.SkippedBeforeEntry
        return this
    }
}
