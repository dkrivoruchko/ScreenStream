package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

internal class NativeMallocCarrierLifecycleTest {
    // Verification: ENC-09
    @Test
    fun allocationOutOfMemoryIsResourceExhaustedWithoutResidue() {
        val failure = OutOfMemoryError("Injected Native carrier allocation exhaustion")
        val facade = RecordingNativeJpegFacade(allocation = { throw failure })
        val candidate = NativeMallocCarrier(layout(), facade)

        val creation = candidate.allocateIntoPendingOwner() as NativeMallocCarrier.Creation.Failed

        assertSame(ScreenCaptureProblem.ResourceExhausted, creation.problem)
        assertSame(failure, creation.cause)
        assertNull(creation.retainedCarrier)
        facade.assertAllocationCount(1)
        facade.assertFreeCount(0)
    }

    // Verification: ENC-09
    @Test
    fun allocationExceptionIsInternalFailureWithoutResidue() {
        val failure = IllegalStateException("Injected Native carrier allocation failure")
        val facade = RecordingNativeJpegFacade(allocation = { throw failure })
        val candidate = NativeMallocCarrier(layout(), facade)

        val creation = candidate.allocateIntoPendingOwner() as NativeMallocCarrier.Creation.Failed

        assertSame(ScreenCaptureProblem.InternalFailure, creation.problem)
        assertSame(failure, creation.cause)
        assertNull(creation.retainedCarrier)
        facade.assertAllocationCount(1)
        facade.assertFreeCount(0)
    }

    // Verification: ENC-09
    @Test
    fun malformedReturnedDirectRangeIsRetainedThenFreedExactlyOnce() {
        val layout = layout()
        val malformed = ByteBuffer.allocateDirect(layout.byteCount + 1)
        val facade = RecordingNativeJpegFacade(allocation = { malformed })
        val candidate = NativeMallocCarrier(layout, facade)

        val creation = candidate.allocateIntoPendingOwner() as NativeMallocCarrier.Creation.Failed

        assertSame(ScreenCaptureProblem.InternalFailure, creation.problem)
        assertSame(candidate, creation.retainedCarrier)
        assertSame(EncodingRetirement.Closed, candidate.retireIfIdle())
        facade.assertAllocationCount(1)
        facade.assertFreedExactlyOnce(malformed)
    }

    // Verification: ENC-09
    @Test
    fun freeExceptionRetainsExactStableCauseAfterOneAttempt() {
        val failure = IllegalStateException("Injected Native carrier free failure")
        val facade = RecordingNativeJpegFacade(
            allocation = { byteCount -> ByteBuffer.allocateDirect(Math.toIntExact(byteCount)) },
            free = { throw failure },
        )
        val carrier = createCarrier(facade)

        val first = carrier.retireIfIdle() as EncodingRetirement.Retained
        val repeated = carrier.retireIfIdle() as EncodingRetirement.Retained

        assertSame(failure, first.cause)
        assertSame(failure, repeated.cause)
        assertFalse(carrier.isIdle)
        facade.assertFreeCount(1)
    }

    // Verification: ENC-09
    @Test
    fun nonExceptionFreeEscapeQuarantinesBeforeCallAndIsNeverRetried() {
        val failure = object : Throwable("Injected non-Exception Native carrier free escape") {}
        val facade = RecordingNativeJpegFacade(
            allocation = { byteCount -> ByteBuffer.allocateDirect(Math.toIntExact(byteCount)) },
            free = { throw failure },
        )
        val carrier = createCarrier(facade)

        assertSame(failure, assertThrows(Throwable::class.java) { carrier.retireIfIdle() })
        val repeated = carrier.retireIfIdle() as EncodingRetirement.Retained

        assertNull(repeated.cause)
        assertFalse(carrier.isIdle)
        facade.assertFreeCount(1)
    }

    private fun createCarrier(facade: RecordingNativeJpegFacade): NativeMallocCarrier {
        val candidate = NativeMallocCarrier(layout(), facade)
        val creation = candidate.allocateIntoPendingOwner() as NativeMallocCarrier.Creation.Created
        assertSame(candidate, creation.carrier)
        return creation.carrier
    }

    private fun layout(): Rgba8888Layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)

    private class RecordingNativeJpegFacade(
        private val allocation: (Long) -> ByteBuffer,
        private val free: (ByteBuffer) -> Unit = {},
    ) : NativeJpegFacade {
        private val allocationAttempts = ArrayList<Long>()
        private val freeAttempts = ArrayList<ByteBuffer>()

        override fun resolveAvailability(): NativeJpegProcess.Availability =
            throw AssertionError("Carrier lifecycle resolved process availability")

        override fun hasWeakCompressor(): Boolean =
            throw AssertionError("Carrier lifecycle queried compressor capability")

        override fun newResultBlock(): ByteBuffer =
            throw AssertionError("Carrier lifecycle allocated a Native result block")

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            allocationAttempts += carrierByteCount
            return allocation(carrierByteCount)
        }

        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            freeAttempts += carrierBuffer
            free(carrierBuffer)
        }

        override fun compress(
            carrierBuffer: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            quality: Int,
            sink: NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Unit = throw AssertionError("Carrier lifecycle entered Native compression")

        fun assertAllocationCount(expected: Int) {
            assertEquals(expected, allocationAttempts.size)
        }

        fun assertFreeCount(expected: Int) {
            assertEquals(expected, freeAttempts.size)
        }

        fun assertFreedExactlyOnce(expected: ByteBuffer) {
            assertEquals(1, freeAttempts.size)
            assertSame(expected, freeAttempts.single())
        }
    }
}
