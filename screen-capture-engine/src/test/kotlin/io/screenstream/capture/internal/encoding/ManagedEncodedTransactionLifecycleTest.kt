package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

internal class ManagedEncodedTransactionLifecycleTest {
    // Verification: ENC-01
    // Verification: ENC-07
    @Test
    fun frameworkCloseDoesNotCommitAndCommitTransfersOneImmutablePayload() {
        val transaction = FrameworkEncodedTransaction()
        val output = transaction.outputStream
        output.write(byteArrayOf(1, 2, 3), 0, 3)
        output.write(4)
        output.write(byteArrayOf(9), 0, 0)

        output.close()

        assertEquals(ManagedEncodedTransaction.State.ProducerClosed, transaction.state)
        assertEquals(4, transaction.byteCount)
        assertNull(transaction.committedPayload)
        assertTrue(transaction.commit())

        val payload = transaction.committedPayload ?: error("commit did not expose payload")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), payload.toByteArray())
        assertTrue(transaction.transferCommittedPayload(payload))
        assertFalse(transaction.transferCommittedPayload(payload))
        assertNull(transaction.committedPayload)
    }

    // Verification: ENC-07
    @Test
    fun writeAfterProducerClosedFaultsAndCannotCommitPayload() {
        val transaction = FrameworkEncodedTransaction()
        val output = transaction.outputStream
        output.write(1)
        output.close()

        assertEquals(ManagedEncodedTransaction.State.ProducerClosed, transaction.state)
        assertThrows(RuntimeException::class.java) {
            output.write(2)
        }

        assertEquals(ManagedEncodedTransaction.State.Faulted, transaction.state)
        assertEquals(ManagedEncodedTransaction.FailureKind.InternalFailure, transaction.failureKind)
        assertFalse(transaction.commit())
        assertNull(transaction.committedPayload)
        assertTrue(transaction.abort())
        assertEquals(ManagedEncodedTransaction.State.Aborted, transaction.state)
    }

    // Verification: ENC-07
    @Test
    fun openCommitAndInvalidWriteFaultWithoutPublishingPartialBytes() {
        val open = FrameworkEncodedTransaction()
        assertFalse(open.commit())
        assertEquals(ManagedEncodedTransaction.State.Faulted, open.state)
        assertEquals(ManagedEncodedTransaction.FailureKind.InternalFailure, open.failureKind)
        assertNull(open.committedPayload)
        assertTrue(open.abort())
        assertFalse(open.abort())

        val invalid = FrameworkEncodedTransaction()
        invalid.outputStream.write(byteArrayOf(7, 8))
        assertThrows(RuntimeException::class.java) {
            invalid.outputStream.write(byteArrayOf(9), -1, 1)
        }
        assertEquals(ManagedEncodedTransaction.State.Faulted, invalid.state)
        assertEquals(ManagedEncodedTransaction.FailureKind.InternalFailure, invalid.failureKind)
        assertFalse(invalid.commit())
        assertNull(invalid.committedPayload)
        assertTrue(invalid.abort())
        assertEquals(ManagedEncodedTransaction.State.Aborted, invalid.state)
    }

    // Verification: STO-01
    // Verification: ENC-07
    @Test
    fun nativeSegmentsAreCopiedInOrderAndCommittedOnce() {
        val transaction = NativeEncodedTransaction()
        val first = directBytes(1, 2)
        val second = directBytes(3, 4, 5)

        transaction.adoptNativeSegment(first, 2)
        transaction.adoptNativeSegment(second, 3)
        assertEquals(first.limit(), first.position())
        assertEquals(second.limit(), second.position())
        transaction.closeNativeProducer()

        assertTrue(transaction.commit())
        val payload = transaction.committedPayload ?: error("native commit did not expose payload")
        assertSame(payload, transaction.committedPayload)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), payload.toByteArray())
        assertTrue(transaction.transferCommittedPayload(payload))
        assertFalse(transaction.transferCommittedPayload(payload))
    }

    // Verification: ENC-07
    @Test
    fun abortDropsTentativeNativeBytesAndCannotPublishThem() {
        val transaction = NativeEncodedTransaction()
        transaction.adoptNativeSegment(directBytes(6, 7, 8), 3)
        transaction.closeNativeProducer()

        assertTrue(transaction.abort())
        assertEquals(ManagedEncodedTransaction.State.Aborted, transaction.state)
        assertNull(transaction.committedPayload)
        assertFalse(transaction.abort())
    }

    private fun directBytes(vararg values: Int): ByteBuffer = ByteBuffer.allocateDirect(values.size).apply {
        values.forEach { put(it.toByte()) }
        flip()
    }
}
