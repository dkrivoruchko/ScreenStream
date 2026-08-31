package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class NativeInvocationEvidenceCellContractTest {
    // Verification: ENC-04
    @Test
    fun coherentCompleteTransferRequiresExactProducedAndAdoptedCounts() {
        val complete = NativeEncodedTransaction().apply {
            adoptNativeSegment(directBytes(1, 2, 3), 3)
            closeNativeProducer()
        }
        assertEquals(
            NativeJpegDisposition.Returned.CompleteTransfer,
            classify(complete, resultBlock(producedByteCount = 3L, wireStatus = 0L)),
        )

        val contradictory = NativeEncodedTransaction().apply {
            adoptNativeSegment(directBytes(1, 2, 3), 3)
            closeNativeProducer()
        }
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(contradictory, resultBlock(producedByteCount = 4L, wireStatus = 0L)),
        )
    }

    // Verification: ENC-04
    @Test
    fun coherentSafeRejectionAllowsNativeBytesButNoManagedAdoption() {
        val transaction = closedEmptyTransaction()

        assertEquals(
            NativeJpegDisposition.Returned.SafeCompressorRejection,
            classify(transaction, resultBlock(producedByteCount = 0L, wireStatus = 1L)),
        )
        assertEquals(
            NativeJpegDisposition.Returned.SafeCompressorRejection,
            classify(transaction, resultBlock(producedByteCount = 17L, wireStatus = 1L)),
        )
    }

    // Verification: ENC-04
    @Test
    fun safeRejectionRejectsManagedAdoptionOrThrownThrowable() {
        val adopted = NativeEncodedTransaction().apply {
            adoptNativeSegment(directBytes(4), 1)
            closeNativeProducer()
        }
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(adopted, resultBlock(producedByteCount = 1L, wireStatus = 1L)),
        )
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(
                transaction = closedEmptyTransaction(),
                block = resultBlock(producedByteCount = 0L, wireStatus = 1L),
                thrown = IllegalStateException("compressor rejected with a Java throwable"),
            ),
        )
    }

    // Verification: ENC-04
    @Test
    fun coherentNativeOutOfMemoryRequiresNoAdoptedPayload() {
        assertEquals(
            NativeJpegDisposition.Returned.RequiredResourceExhaustion,
            classify(closedEmptyTransaction(), resultBlock(producedByteCount = 0L, wireStatus = 2L)),
        )
        assertEquals(
            NativeJpegDisposition.Returned.RequiredResourceExhaustion,
            classify(closedEmptyTransaction(), resultBlock(producedByteCount = 23L, wireStatus = 2L)),
        )

        val contradictory = NativeEncodedTransaction().apply {
            adoptNativeSegment(directBytes(9), 1)
            closeNativeProducer()
        }
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(contradictory, resultBlock(producedByteCount = 1L, wireStatus = 2L)),
        )
    }

    // Verification: ENC-04
    @Test
    fun javaOutOfMemoryWithoutExactTransactionOwnedCauseRemainsUnsafe() {
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(
                transaction = closedEmptyTransaction(),
                block = resultBlock(producedByteCount = 0L, wireStatus = 4L),
                thrown = OutOfMemoryError("not owned by the transaction"),
            ),
        )
    }

    // Verification: ENC-04
    @Test
    fun internalUnknownPendingAndUntypedJavaThrowableRemainUnsafe() {
        listOf(
            resultBlock(producedByteCount = 0L, wireStatus = 3L),
            resultBlock(producedByteCount = 0L, wireStatus = 99L),
            NativeJpegProcess.newResultBlock(),
        ).forEach { block ->
            assertEquals(
                NativeJpegDisposition.Returned.UnsafeInternalFailure,
                classify(closedEmptyTransaction(), block),
            )
        }

        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            classify(
                transaction = closedEmptyTransaction(),
                block = resultBlock(producedByteCount = 0L, wireStatus = 4L),
                thrown = IllegalStateException("untyped Java failure"),
            ),
        )
    }

    // Verification: ENC-04
    @Test
    fun invalidBlockOrCarrierSettlementContradictionOutranksWireEvidence() {
        val invalidCell = NativeInvocationEvidenceCell()
        invalidCell.markCarrierEntered()
        invalidCell.recordInvocationExit(
            ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()),
            thrownThrowable = null,
        )
        invalidCell.recordCarrierSettlement(settled = true)
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            invalidCell.classifyInvocationOutcome(closedEmptyTransaction()),
        )

        val settlementCell = NativeInvocationEvidenceCell()
        settlementCell.markCarrierEntered()
        settlementCell.recordInvocationExit(
            resultBlock(producedByteCount = 0L, wireStatus = 2L),
            thrownThrowable = null,
        )
        settlementCell.recordCarrierSettlement(settled = false)
        assertEquals(
            NativeJpegDisposition.Returned.UnsafeInternalFailure,
            settlementCell.classifyInvocationOutcome(closedEmptyTransaction()),
        )
    }

    private fun classify(
        transaction: NativeEncodedTransaction,
        block: ByteBuffer,
        thrown: Throwable? = null,
    ): NativeJpegDisposition.Returned {
        val cell = NativeInvocationEvidenceCell()
        cell.markCarrierEntered()
        cell.recordInvocationExit(block, thrown)
        cell.recordCarrierSettlement(settled = true)
        return cell.classifyInvocationOutcome(transaction)
    }

    private fun closedEmptyTransaction(): NativeEncodedTransaction = NativeEncodedTransaction().apply {
        closeNativeProducer()
    }

    private fun resultBlock(producedByteCount: Long, wireStatus: Long): ByteBuffer =
        NativeJpegProcess.newResultBlock().apply {
            putLong(0, producedByteCount)
            putLong(8, wireStatus)
        }

    private fun directBytes(vararg values: Int): ByteBuffer = ByteBuffer.allocateDirect(values.size).apply {
        values.forEach { put(it.toByte()) }
        flip()
    }
}
