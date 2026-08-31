package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.MutableElapsedRealtimeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ManagedDirectCarrierLifecycleTest {
    // Verification: ENC-06
    @Test
    fun directAllocationClassifiesExhaustionAndInternalFailureWithoutResidue() {
        fun assertFailure(failure: Throwable, expectedProblem: ScreenCaptureProblem) {
            val candidate = ManagedDirectCarrier(Rgba8888Layout.create(widthPx = 2, heightPx = 2))

            val creation = candidate.allocateIntoPendingOwner { throw failure } as ManagedDirectCarrier.Creation.Failed

            assertSame(expectedProblem, creation.problem)
            assertSame(failure, creation.cause)
            assertNull(creation.retainedCarrier)
        }

        assertFailure(OutOfMemoryError("direct allocation exhausted"), ScreenCaptureProblem.ResourceExhausted)
        assertFailure(IllegalStateException("direct allocation failed"), ScreenCaptureProblem.InternalFailure)
    }

    // Verification: ENC-01
    @Test
    fun exactDirectRangeHasOneLinearLoanAndRejectsStaleOrDuplicateSettlement() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 3, heightPx = 2)
            val carrier = createCarrier(layout)
            val owner = EncodingOwner(dispatcher, MutableElapsedRealtimeClock())
            val returnPort = EncodingProductionReturnPort { }

            val first = carrier.lend(owner, returnPort) ?: error("carrier did not lend")
            assertSame(carrier, first.carrier)
            assertSame(returnPort, first.returnPort)
            assertEquals(layout.byteCount, first.byteCount)
            assertTrue(first.writableView.isDirect)
            assertFalse(first.writableView.isReadOnly)
            assertEquals(0, first.writableView.position())
            assertEquals(layout.byteCount, first.writableView.limit())
            assertEquals(layout.byteCount, first.writableView.capacity())
            assertTrue(carrier.ownsCaptureLoan(first))
            assertNull(carrier.lend(owner, returnPort))

            assertSame(first, carrier.settle(first, CarrierDisposition.Filled))
            assertFalse(carrier.ownsCaptureLoan(first))
            assertTrue(carrier.ownsReadyLoan(first))
            assertNull(carrier.settle(first, CarrierDisposition.Filled))

            assertSame(first.writableView, carrier.enterEncoding(first))
            assertNull(carrier.enterEncoding(first))
            assertTrue(carrier.releaseAfterEncodingReturn(first))
            assertFalse(carrier.releaseAfterEncodingReturn(first))
            assertTrue(carrier.isIdle)

            val successor = carrier.lend(owner, returnPort) ?: error("successor loan was not available")
            assertTrue(successor !== first)
            assertNull(carrier.settle(first, CarrierDisposition.Discarded))
            assertTrue(carrier.ownsCaptureLoan(successor))
            assertSame(successor, carrier.settle(successor, CarrierDisposition.Discarded))
            assertTrue(carrier.isIdle)

            assertSame(EncodingRetirement.Closed, carrier.retireIfIdle())
            assertFalse(carrier.isIdle)
            assertNull(carrier.lend(owner, returnPort))
        }
    }

    // Verification: ENC-01
    @Test
    fun retirementRetainsLiveLoanUntilItsRealSettlement() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val carrier = createCarrier(Rgba8888Layout.create(1, 1))
            val owner = EncodingOwner(dispatcher, MutableElapsedRealtimeClock())
            val input = carrier.lend(owner) { } ?: error("carrier did not lend")

            val retained = carrier.retireIfIdle() as EncodingRetirement.Retained
            assertNull(retained.cause)
            assertTrue(carrier.ownsCaptureLoan(input))

            assertSame(input, carrier.settle(input, CarrierDisposition.Discarded))
            assertSame(EncodingRetirement.Closed, carrier.retireIfIdle())
            assertNull(carrier.settle(input, CarrierDisposition.Discarded))
        }
    }

    // Verification: ENC-01
    @Test
    fun managedRuntimeRetainsExactLayoutAndCarrierAndCandidateAllocatesOnce() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val carrier = ManagedDirectCarrier(layout)
            val creation = EncoderRuntime.allocateManagedRuntime(layout, carrier) as EncoderRuntimeCreation.Created

            val backend = creation.runtime.backendState as EncoderBackendState.Framework
            assertSame(layout, creation.runtime.layout)
            assertSame(carrier, backend.carrier)
            assertSame(carrier, creation.runtime.carrier)
            assertThrows(IllegalStateException::class.java) { carrier.allocateIntoPendingOwner() }

            val owner = EncodingOwner(dispatcher, MutableElapsedRealtimeClock())
            val input = carrier.lend(owner) { } ?: error("carrier did not lend")
            val retained = creation.runtime.retireCarrier() as EncodingRetirement.Retained
            assertNull(retained.cause)
            assertTrue(carrier.ownsCaptureLoan(input))

            assertSame(input, carrier.settle(input, CarrierDisposition.Discarded))
            assertSame(EncodingRetirement.Closed, creation.runtime.retireCarrier())
            assertFalse(carrier.isIdle)
        }
    }

    private fun createCarrier(layout: Rgba8888Layout): ManagedDirectCarrier {
        val candidate = ManagedDirectCarrier(layout)
        val created = candidate.allocateIntoPendingOwner() as ManagedDirectCarrier.Creation.Created
        assertSame(candidate, created.carrier)
        return candidate
    }
}
