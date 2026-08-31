package io.screenstream.capture.internal.encoding

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.QueuedNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
internal class EncodingOwnerFrameworkJpegTest {
    // Verification: ENC-01
    @Test
    fun frameworkOnlyEncodesRealFixtureAndRetiresExactReusableCarrier() {
        val dispatcher = QueuedNonInlineDispatcher()
        val clock = TwoSampleClock(startNanos = 100L, finishNanos = 137L)
        val owner = EncodingOwner(dispatcher, clock)
        var cleanupInput: EncodingInput? = null
        try {
            val reconcileResult = AtomicReference<EncodingReconcileResult?>()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(DeviceJpegFixture.layout, JpegBackendPolicy.FrameworkOnly) { result ->
                    check(reconcileResult.compareAndSet(null, result))
                },
            )
            dispatcher.runNext()
            assertSame(EncodingReconcileResult.Ready, reconcileResult.get())

            val productionResult = AtomicReference<EncodingResult?>()
            val input = owner.acquireInput { result ->
                check(productionResult.compareAndSet(null, result))
            } as? EncodingInput ?: error("Framework owner did not lend its exact input")
            cleanupInput = input
            DeviceJpegFixture.fill(input.writableView)
            assertSame(EncodingInputSettlement.Accepted, input.encode(DeviceJpegFixture.JPEG_QUALITY))
            cleanupInput = null
            dispatcher.runNext()

            val encoded = productionResult.get() as? EncodingResult.Encoded
                ?: error("Framework production did not return Encoded")
            assertEquals(37L, encoded.encodeDurationNanos)
            DeviceJpegFixture.assertPayload(encoded.payload)

            val reusable = owner.acquireInput { error("Discarded input returned a production result") }
                    as? EncodingInput ?: error("Framework owner did not relend its exact input")
            cleanupInput = reusable
            assertSame(input.carrier, reusable.carrier)
            assertSame(input.writableView, reusable.writableView)
            assertSame(EncodingInputSettlement.Settled, reusable.discard())
            cleanupInput = null
        } finally {
            cleanupInput?.discard()
            owner.retire()
            dispatcher.drain()
        }
        assertEquals(0, dispatcher.pendingCount())
        assertTrue(owner.acquireInput { error("Retired owner returned a production result") } is EncodingInputResult.Failed)
    }

    private class TwoSampleClock(
        private val startNanos: Long,
        private val finishNanos: Long,
    ) : ElapsedRealtimeClock {
        private var calls: Int = 0

        override fun nowNanos(): Long = when (calls++) {
            0 -> startNanos
            1 -> finishNanos
            else -> error("Framework production read more than two clock samples")
        }
    }
}
