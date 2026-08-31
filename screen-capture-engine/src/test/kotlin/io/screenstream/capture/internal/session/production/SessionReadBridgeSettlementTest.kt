package io.screenstream.capture.internal.session.production

import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.encoding.CarrierDisposition
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingOwner
import io.screenstream.capture.internal.encoding.EncodingRetirement
import io.screenstream.capture.internal.encoding.ManagedDirectCarrier
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.MutableElapsedRealtimeClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionReadBridgeSettlementTest {
    // Verification: SES-06
    @Test
    fun exactReturnCanBeRecordedAndClaimedOnlyOnce() {
        Fixture().use { fixture ->
            val result = CaptureReadResult.Filled(readbackDurationNanos = 7L)
            val contradictory = CaptureReadResult.CutoffInert
            lateinit var observedBridge: SessionReadBridge
            lateinit var observedResult: CaptureReadResult
            val bridge = SessionReadBridge(fixture.record, fixture.input) { returnedBridge, returnedResult ->
                observedBridge = returnedBridge
                observedResult = returnedResult
            }

            bridge.onReadReturned(result)
            assertSame(bridge, observedBridge)
            assertSame(result, observedResult)
            assertSame(fixture.input.writableView, bridge.writableView)

            assertTrue(bridge.recordReturnLocked(result))
            assertFalse(bridge.recordReturnLocked(contradictory))
            assertThrows(IllegalStateException::class.java) { bridge.requireClaimedResult() }
            assertTrue(bridge.claimReturnedLocked())
            assertFalse(bridge.claimReturnedLocked())
            assertSame(result, bridge.requireClaimedResult())
            assertFalse(bridge.canDetachLocked())
            assertFalse(bridge.claimRejectedBeforeEntryLocked())
        }
    }

    // Verification: SES-06
    @Test
    fun rejectedBeforeEntryClaimsWithoutAResult() {
        Fixture().use { fixture ->
            val bridge = SessionReadBridge(fixture.record, fixture.input) { _, _ -> }

            assertTrue(bridge.claimRejectedBeforeEntryLocked())
            assertFalse(bridge.claimRejectedBeforeEntryLocked())
            assertFalse(bridge.recordReturnLocked(CaptureReadResult.CutoffInert))
            assertFalse(bridge.claimReturnedLocked())
            assertFalse(bridge.canDetachLocked())
            assertThrows(IllegalStateException::class.java) { bridge.requireClaimedResult() }
        }
    }

    // Verification: SES-06
    @Test
    fun detachedBridgeAllowsOneRealLateSettlement() {
        Fixture().use { fixture ->
            val bridge = SessionReadBridge(fixture.record, fixture.input) { _, _ -> }
            val result = CaptureReadResult.CutoffInert
            val contradictory = CaptureReadResult.Filled(readbackDurationNanos = 9L)

            assertTrue(bridge.canDetachLocked())
            bridge.detachLocked()
            assertTrue(bridge.isDetachedLocked())
            assertFalse(bridge.canDetachLocked())
            assertFalse(bridge.claimDetachedSettlementLocked())

            assertTrue(bridge.recordReturnLocked(result))
            assertFalse(bridge.recordReturnLocked(contradictory))
            assertFalse(bridge.claimReturnedLocked())
            assertTrue(bridge.claimDetachedSettlementLocked())
            assertFalse(bridge.claimDetachedSettlementLocked())
            assertFalse(bridge.claimRejectedBeforeEntryLocked())
            assertFalse(bridge.recordReturnLocked(contradictory))
        }
    }

    // Verification: SES-06
    @Test
    fun detachedRejectedBeforeEntrySettlementIsAlsoOnceOnly() {
        Fixture().use { fixture ->
            val bridge = SessionReadBridge(fixture.record, fixture.input) { _, _ -> }
            bridge.detachLocked()

            assertTrue(bridge.claimRejectedBeforeEntryLocked())
            assertFalse(bridge.claimRejectedBeforeEntryLocked())
            assertFalse(bridge.claimDetachedSettlementLocked())
            assertThrows(IllegalStateException::class.java) { bridge.requireClaimedResult() }
        }
    }

    private class Fixture : AutoCloseable {
        private val dispatcher = ControlledNonInlineDispatcher()
        private val carrier = ManagedDirectCarrier(Rgba8888Layout.create(2, 2)).also { candidate ->
            check(candidate.allocateIntoPendingOwner() is ManagedDirectCarrier.Creation.Created)
        }
        private val encodingOwner = EncodingOwner(dispatcher, MutableElapsedRealtimeClock())
        private val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
        val record = production.allocateRecord(configRevision = 1L, jpegQuality = 75)
        val input: EncodingInput = carrier.lend(encodingOwner) { }
            ?: error("carrier did not lend")

        override fun close() {
            try {
                check(carrier.ownsCaptureLoan(input))
                check(carrier.settle(input, CarrierDisposition.Discarded) === input)
                check(carrier.retireIfIdle() === EncodingRetirement.Closed)
            } finally {
                dispatcher.close()
            }
        }
    }
}
