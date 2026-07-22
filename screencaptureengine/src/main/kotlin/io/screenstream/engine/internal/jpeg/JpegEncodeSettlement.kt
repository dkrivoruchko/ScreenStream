package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse

internal enum class JpegEncodeBackendIdentity {
    Framework,
    Native,
}

internal enum class JpegEncodeClaimUse {
    Timely,
    Cleanup,
    NoReturn,
}

internal enum class JpegEncodeFinalizationDisposition {
    KeepCommittedUnpublished,
    RetireCommittedUnpublished,
    RetireTransaction,
}

internal enum class JpegEncodeFinalizationResult {
    Pending,
    Completed,
    ReadyForNativeHealthDecision,
    UnsafeResidue,
}

/**
 * A precreated, one-shot claim envelope. Its identity fields never change; result fields are filled exactly
 * once under the operation settlement gate before this object is returned to Session. Once published it is an
 * immutable fact. No result-path allocation is required.
 */
internal abstract class JpegEncodeClaimFact<R : Any> internal constructor(
    internal val operationIdentity: Long,
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val backendIdentity: JpegEncodeBackendIdentity,
    internal val topologyIdentity: JpegRuntimeTopologySnapshot,
    internal val productIdentity: JpegRuntimeProduct,
    internal val carrierIdentity: RgbaCarrier,
    internal val carrierLeaseIdentity: RgbaCarrierLease,
    internal val storageIdentity: EncodedStorageOwner,
    internal val transactionIdentity: EncodedStorageOwner.SegmentedTransaction,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val healthIdentity: NativeJpegHealth,
    internal val storageCommands: EncodedStorageOwner.EncodeSettlementCommands,
) {
    @Volatile
    private var published: Boolean = false
    private var resultSlot: R? = null
    private var claimUseSlot: JpegEncodeClaimUse? = null
    private var returnDispositionSlot: OperationReturnDisposition = OperationReturnDisposition.Empty
    private var settlementElapsedRealtimeNanosSlot: Long = NO_RETURN_SETTLEMENT_NANOS
    private var encodedByteCountSlot: Int = 0
    private var payloadIdentitySlot: EncodedStorageOwner.UnpublishedEncodedPayload? = null
    private var failureCauseSlot: Throwable? = null
    private var rawThrowableSlot: Throwable? = null
    private var carrierUseResolvedSlot: Boolean = false

    internal val result: R
        get() = checkNotNull(resultSlot)

    internal val claimUse: JpegEncodeClaimUse
        get() = checkNotNull(claimUseSlot)

    internal val returnDisposition: OperationReturnDisposition
        get() = returnDispositionSlot

    internal val settlementElapsedRealtimeNanos: Long
        get() = settlementElapsedRealtimeNanosSlot

    internal val encodedByteCount: Int
        get() = encodedByteCountSlot

    internal val payloadIdentity: EncodedStorageOwner.UnpublishedEncodedPayload?
        get() = payloadIdentitySlot

    internal val failureCause: Throwable?
        get() = failureCauseSlot

    internal val rawThrowable: Throwable?
        get() = rawThrowableSlot

    internal val carrierUseResolved: Boolean
        get() = carrierUseResolvedSlot

    internal val isPublished: Boolean
        get() = published

    internal fun publishLocked(
        result: R,
        returnUse: OperationReturnUse,
        returnDisposition: OperationReturnDisposition,
        settlementElapsedRealtimeNanos: Long,
        encodedByteCount: Int,
        payloadIdentity: EncodedStorageOwner.UnpublishedEncodedPayload?,
        failureCause: Throwable?,
        rawThrowable: Throwable?,
        carrierUseResolved: Boolean,
    ): Boolean {
        if (published) return false
        check(encodedByteCount >= 0)
        check(returnUse != OperationReturnUse.Unclaimed || returnDisposition == OperationReturnDisposition.Empty)
        check(
            returnDisposition == OperationReturnDisposition.Empty &&
                    settlementElapsedRealtimeNanos == NO_RETURN_SETTLEMENT_NANOS ||
                    returnDisposition != OperationReturnDisposition.Empty &&
                    settlementElapsedRealtimeNanos >= 0L,
        )
        resultSlot = result
        claimUseSlot = when (returnUse) {
            OperationReturnUse.Timely -> JpegEncodeClaimUse.Timely
            OperationReturnUse.Cleanup -> JpegEncodeClaimUse.Cleanup
            OperationReturnUse.Unclaimed -> JpegEncodeClaimUse.NoReturn
        }
        returnDispositionSlot = returnDisposition
        settlementElapsedRealtimeNanosSlot = settlementElapsedRealtimeNanos
        encodedByteCountSlot = encodedByteCount
        payloadIdentitySlot = payloadIdentity
        failureCauseSlot = failureCause
        rawThrowableSlot = rawThrowable
        carrierUseResolvedSlot = carrierUseResolved
        published = true
        return true
    }

    internal companion object {
        internal const val NO_RETURN_SETTLEMENT_NANOS: Long = Long.MIN_VALUE
    }
}

internal class JpegEncodeFinalizationReceipt internal constructor() {
    internal var result: JpegEncodeFinalizationResult = JpegEncodeFinalizationResult.Pending
        private set

    internal var carrierLeaseReleased: Boolean = false
        private set

    internal var storageRetired: Boolean = false
        private set

    internal var producerUseReleased: Boolean = false
        private set

    private var failureSlot: Throwable? = null

    internal val failure: Throwable?
        get() = failureSlot

    internal fun complete(
        result: JpegEncodeFinalizationResult,
        carrierLeaseReleased: Boolean,
        storageRetired: Boolean,
        producerUseReleased: Boolean,
        failure: Throwable?,
    ) {
        check(this.result == JpegEncodeFinalizationResult.Pending)
        check(result != JpegEncodeFinalizationResult.Pending)
        this.result = result
        this.carrierLeaseReleased = carrierLeaseReleased
        this.storageRetired = storageRetired
        this.producerUseReleased = producerUseReleased
        failureSlot = failure
    }
}
