package io.screenstream.capture.internal.encoding

import java.nio.ByteBuffer

/**
 * Collects the independent evidence required to classify one returned Native JPEG invocation.
 *
 * Wire status, produced-byte shape, Java throwable propagation, transaction state, and exact carrier settlement must
 * agree before a safe outcome is returned. Missing, contradictory, malformed, or partially settled evidence is an
 * unsafe internal failure; no single status code or throwable class is sufficient on its own.
 */
internal class NativeInvocationEvidenceCell {
    internal enum class CarrierSettlementState { NotEntered, Entered, Settled, SettlementFailed, }
    private enum class WireState { Pending, Readable, Invalid, }

    private var invocationExitRecorded: Boolean = false
    private var thrownThrowable: Throwable? = null
    private var wireState: WireState = WireState.Pending
    private var producedByteCountSlot: Long = NativeJpegProcess.NATIVE_RESULT_PENDING
    private var wireStatus: NativeJpegProcess.NativeWireStatus = NativeJpegProcess.NativeWireStatus.Unknown
    internal var carrierSettlementState: CarrierSettlementState = CarrierSettlementState.NotEntered
        private set

    internal var carrierSettlementFailure: Exception? = null
        private set

    internal fun markCarrierEntered() {
        check(carrierSettlementState == CarrierSettlementState.NotEntered)
        carrierSettlementState = CarrierSettlementState.Entered
    }

    internal fun recordInvocationExit(resultBlock: ByteBuffer, thrownThrowable: Throwable?) {
        check(!invocationExitRecorded)
        check(carrierSettlementState == CarrierSettlementState.Entered)
        invocationExitRecorded = true
        this.thrownThrowable = thrownThrowable
        wireState = try {
            if (!NativeJpegProcess.hasExactResultShape(resultBlock)) {
                WireState.Invalid
            } else {
                producedByteCountSlot = NativeJpegProcess.readProducedByteCount(resultBlock)
                wireStatus = NativeJpegProcess.readNativeWireStatus(resultBlock)
                WireState.Readable
            }
        } catch (_: Exception) {
            WireState.Invalid
        }
    }

    internal fun recordCarrierSettlement(settled: Boolean) {
        check((carrierSettlementState == CarrierSettlementState.NotEntered) || (carrierSettlementState == CarrierSettlementState.Entered))
        if (settled) {
            carrierSettlementState = CarrierSettlementState.Settled
        } else {
            carrierSettlementFailure = nativeJpegSettlementMismatch
            carrierSettlementState = CarrierSettlementState.SettlementFailed
        }
    }

    internal fun recordCarrierSettlementFailure(failure: Exception) {
        check((carrierSettlementState == CarrierSettlementState.NotEntered) || (carrierSettlementState == CarrierSettlementState.Entered))
        carrierSettlementFailure = failure
        carrierSettlementState = CarrierSettlementState.SettlementFailed
    }

    private fun thrownThrowableMatchesStorageExhaustion(transaction: NativeEncodedTransaction): Boolean {
        check(invocationExitRecorded)
        val thrownOutOfMemory = thrownThrowable as? OutOfMemoryError ?: return false
        return transaction.hasFaultedResourceExhaustionCause(thrownOutOfMemory)
    }

    internal fun classifyInvocationOutcome(transaction: NativeEncodedTransaction): NativeJpegDisposition.Returned {
        check(invocationExitRecorded)
        if (wireState != WireState.Readable || carrierSettlementState != CarrierSettlementState.Settled) {
            return NativeJpegDisposition.Returned.UnsafeInternalFailure
        }

        val produced = producedByteCountSlot
        val adopted = transaction.byteCount
        if (produced !in 0L..Int.MAX_VALUE.toLong() || adopted < 0 || adopted.toLong() > produced) {
            return NativeJpegDisposition.Returned.UnsafeInternalFailure
        }

        val storageFailure = transaction.failureKind
        val producerCoherent = when (transaction.state) {
            ManagedEncodedTransaction.State.ProducerClosed -> storageFailure == null
            ManagedEncodedTransaction.State.Faulted -> storageFailure != null
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.Committed,
            ManagedEncodedTransaction.State.Aborted,
                -> false
        }
        if (!producerCoherent) return NativeJpegDisposition.Returned.UnsafeInternalFailure

        val throwable = thrownThrowable
        val thrownStorageOutOfMemory = thrownThrowableMatchesStorageExhaustion(transaction)
        return when (wireStatus) {
            NativeJpegProcess.NativeWireStatus.NativeTransferComplete ->
                if (throwable == null && storageFailure == null && produced > 0L && adopted.toLong() == produced) {
                    NativeJpegDisposition.Returned.CompleteTransfer
                } else {
                    NativeJpegDisposition.Returned.UnsafeInternalFailure
                }

            NativeJpegProcess.NativeWireStatus.SafeCompressorRejection ->
                if (throwable == null && storageFailure == null && adopted == 0) {
                    NativeJpegDisposition.Returned.SafeCompressorRejection
                } else {
                    NativeJpegDisposition.Returned.UnsafeInternalFailure
                }

            NativeJpegProcess.NativeWireStatus.NativeOutOfMemory ->
                if (throwable == null && storageFailure == null && adopted == 0) {
                    NativeJpegDisposition.Returned.RequiredResourceExhaustion
                } else {
                    NativeJpegDisposition.Returned.UnsafeInternalFailure
                }

            NativeJpegProcess.NativeWireStatus.JavaThrowable ->
                if (throwable != null && storageFailure == ManagedEncodedTransaction.FailureKind.ResourceExhausted &&
                    (throwable is Exception || thrownStorageOutOfMemory)
                ) {
                    NativeJpegDisposition.Returned.RequiredResourceExhaustion
                } else {
                    NativeJpegDisposition.Returned.UnsafeInternalFailure
                }

            NativeJpegProcess.NativeWireStatus.InternalFailure,
            NativeJpegProcess.NativeWireStatus.Unknown,
                -> NativeJpegDisposition.Returned.UnsafeInternalFailure
        }
    }
}
