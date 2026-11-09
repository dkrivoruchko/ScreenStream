package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class NativeJpegProcessWireProtocolTest {
    // Verification: ENC-04
    @Test
    fun newResultBlockHasFrozenManagedShapeAndPendingWords() {
        val block = NativeJpegProcess.newResultBlock()

        assertTrue(block.isDirect)
        assertFalse(block.isReadOnly)
        assertEquals(16, block.capacity())
        assertEquals(16, block.limit())
        assertEquals(0, block.position())
        assertEquals(ByteOrder.nativeOrder(), block.order())
        assertTrue(NativeJpegProcess.hasExactResultShape(block))
        assertEquals(NativeJpegProcess.NATIVE_RESULT_PENDING, NativeJpegProcess.readProducedByteCount(block))
        assertEquals(NativeJpegProcess.NATIVE_RESULT_PENDING, block.getLong(8))
        assertEquals(NativeJpegProcess.NativeWireStatus.Unknown, NativeJpegProcess.readNativeWireStatus(block))
    }

    // Verification: ENC-04
    @Test
    fun managedDecoderMapsStatusesZeroThroughFourAndUnknown() {
        val expected = listOf(
            0L to NativeJpegProcess.NativeWireStatus.NativeTransferComplete,
            1L to NativeJpegProcess.NativeWireStatus.SafeCompressorRejection,
            2L to NativeJpegProcess.NativeWireStatus.NativeOutOfMemory,
            3L to NativeJpegProcess.NativeWireStatus.InternalFailure,
            4L to NativeJpegProcess.NativeWireStatus.JavaThrowable,
            5L to NativeJpegProcess.NativeWireStatus.Unknown,
            -1L to NativeJpegProcess.NativeWireStatus.Unknown,
        )

        expected.forEach { (wire, status) ->
            val block = NativeJpegProcess.newResultBlock()
            block.putLong(0, 123L)
            block.putLong(8, wire)
            assertEquals(123L, NativeJpegProcess.readProducedByteCount(block))
            assertEquals(status, NativeJpegProcess.readNativeWireStatus(block))
        }
    }

    // Verification: ENC-04
    @Test
    fun shapeRejectsHeapReadOnlyWrongCapacityLimitAndByteOrder() {
        assertFalse(NativeJpegProcess.hasExactResultShape(ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())))
        assertFalse(NativeJpegProcess.hasExactResultShape(ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())))

        val readOnly = NativeJpegProcess.newResultBlock().asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        assertFalse(NativeJpegProcess.hasExactResultShape(readOnly))

        val wrongLimit = NativeJpegProcess.newResultBlock().apply { limit(8) }
        assertFalse(NativeJpegProcess.hasExactResultShape(wrongLimit))

        val oppositeOrder = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val wrongOrder = NativeJpegProcess.newResultBlock().order(oppositeOrder)
        assertFalse(NativeJpegProcess.hasExactResultShape(wrongOrder))
    }
}
