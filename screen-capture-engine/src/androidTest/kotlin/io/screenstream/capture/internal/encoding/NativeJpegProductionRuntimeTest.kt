package io.screenstream.capture.internal.encoding

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.QueuedNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
internal class NativeJpegProductionRuntimeTest {
    // Verification: ENC-02
    @Test
    fun belowApi30LoadsDsoReportsWeakCompressorUnavailableAndAutoUsesFramework() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        assertSame(NativeJpegProcess.Availability.Available, NativeJpegProcess.resolveAvailability())
        assertFalse(NativeJpegProcess.hasWeakCompressor())
        val preparation = EncoderRuntime.prepareBackend(
            backendPolicy = JpegBackendPolicy.Auto,
            existingHealthCell = null,
            nativeJpeg = NativeJpegProcess,
        ) as? EncoderBackendPreparation.NativeCarrier
            ?: error("Auto did not retain the packaged native carrier capability")
        assertSame(NativeHealthCell.State.Disabled, preparation.nativeHealthCell.state)

        val dispatcher = QueuedNonInlineDispatcher()
        val owner = EncodingOwner(dispatcher, TwoSampleClock())
        var cleanupInput: EncodingInput? = null
        try {
            val reconcileResult = AtomicReference<EncodingReconcileResult?>()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(DeviceJpegFixture.layout, JpegBackendPolicy.Auto) { result ->
                    check(reconcileResult.compareAndSet(null, result))
                },
            )
            dispatcher.runNext()
            assertSame(EncodingReconcileResult.Ready, reconcileResult.get())

            val productionResult = AtomicReference<EncodingResult?>()
            val input = owner.acquireInput { result ->
                check(productionResult.compareAndSet(null, result))
            } as? EncodingInput ?: error("Auto owner did not lend an input")
            cleanupInput = input
            DeviceJpegFixture.fill(input.writableView)
            assertSame(EncodingInputSettlement.Accepted, input.encode(DeviceJpegFixture.JPEG_QUALITY))
            cleanupInput = null
            dispatcher.runNext()
            val encoded = productionResult.get() as? EncodingResult.Encoded
                ?: error("API ${Build.VERSION.SDK_INT} Auto production did not use its Framework fallback")
            DeviceJpegFixture.assertPayload(encoded.payload)
        } finally {
            cleanupInput?.discard()
            owner.retire()
            dispatcher.drain()
        }
        assertEquals(0, dispatcher.pendingCount())
    }

    // Verification: ENC-02
    // Verification: ENC-04
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun api30PlusAutoOwnerEncodesRealFixtureThroughNativeAndRetiresExactCarrier() {
        val dispatcher = QueuedNonInlineDispatcher()
        val nativeBoundary = TrackingNativeJpegFacade()
        val owner = EncodingOwner(
            dispatcher,
            clock = ElapsedRealtimeClock { 0L },
            nativeJpeg = nativeBoundary,
        )
        var cleanupInput: EncodingInput? = null
        try {
            val reconcileResult = AtomicReference<EncodingReconcileResult?>()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(DeviceJpegFixture.layout, JpegBackendPolicy.Auto) { result ->
                    check(reconcileResult.compareAndSet(null, result))
                },
            )
            dispatcher.runNext()
            assertSame(EncodingReconcileResult.Ready, reconcileResult.get())

            val productionResult = AtomicReference<EncodingResult?>()
            val input = owner.acquireInput { result ->
                check(productionResult.compareAndSet(null, result))
            } as? EncodingInput ?: error("API 30+ Auto owner did not lend an input")
            cleanupInput = input
            DeviceJpegFixture.fill(input.writableView)
            assertSame(EncodingInputSettlement.Accepted, input.encode(DeviceJpegFixture.JPEG_QUALITY))
            cleanupInput = null
            dispatcher.runNext()

            val encoded = productionResult.get() as? EncodingResult.Encoded
                ?: error("API ${Build.VERSION.SDK_INT} Auto owner did not return a Native-backed JPEG")
            val producedByteCount = nativeBoundary.requireCompleteTransferByteCount()
            assertEquals(producedByteCount.toInt(), encoded.payload.byteCount)
            DeviceJpegFixture.assertPayload(encoded.payload)

            val reusable = owner.acquireInput { error("Discarded Native input returned a production result") }
                    as? EncodingInput ?: error("API 30+ Auto owner did not relend its exact input")
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
        nativeBoundary.assertExactCarrierRetired()
        assertTrue(owner.acquireInput { error("Retired Native owner returned a production result") } is EncodingInputResult.Failed)
    }

    // Direct registered-JNI and transaction-boundary evidence, not EncodingOwner backend-selection evidence.
    // Verification: ENC-04
    @Test
    fun api30PlusDirectJniFacadeTransfersAndSettlesProductionTransaction() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertSame(NativeJpegProcess.Availability.Available, NativeJpegProcess.resolveAvailability())
        assertTrue(NativeJpegProcess.hasWeakCompressor())

        var carrier: ByteBuffer? = null
        val transaction = NativeEncodedTransaction()
        try {
            val allocatedCarrier = NativeJpegProcess.allocateCarrier(DeviceJpegFixture.layout.byteCount.toLong())
            carrier = allocatedCarrier
            allocatedCarrier.clear()
            DeviceJpegFixture.fill(allocatedCarrier)
            val resultBlock = NativeJpegProcess.newResultBlock()
            assertTrue(NativeJpegProcess.hasExactResultShape(resultBlock))
            assertEquals(NativeJpegProcess.NATIVE_RESULT_PENDING, NativeJpegProcess.readProducedByteCount(resultBlock))
            assertSame(NativeJpegProcess.NativeWireStatus.Unknown, NativeJpegProcess.readNativeWireStatus(resultBlock))

            NativeJpegProcess.compress(
                carrierBuffer = allocatedCarrier,
                pixelByteCount = DeviceJpegFixture.layout.byteCount.toLong(),
                width = DeviceJpegFixture.layout.widthPx,
                height = DeviceJpegFixture.layout.heightPx,
                stride = DeviceJpegFixture.layout.rowByteCount,
                quality = DeviceJpegFixture.JPEG_QUALITY,
                sink = transaction.segmentSink,
                resultBlock = resultBlock,
            )

            val producedByteCount = NativeJpegProcess.readProducedByteCount(resultBlock)
            val wireStatus = NativeJpegProcess.readNativeWireStatus(resultBlock)
            assertSame(NativeJpegProcess.NativeWireStatus.NativeTransferComplete, wireStatus)
            assertTrue(producedByteCount > 0L)
            assertTrue(producedByteCount <= Int.MAX_VALUE.toLong())
            assertEquals(producedByteCount.toInt(), transaction.byteCount)
            assertSame(ManagedEncodedTransaction.State.Open, transaction.state)

            transaction.closeNativeProducer()
            assertSame(ManagedEncodedTransaction.State.ProducerClosed, transaction.state)
            assertTrue(transaction.commit())
            val payload = checkNotNull(transaction.committedPayload)
            assertEquals(producedByteCount.toInt(), payload.byteCount)
            DeviceJpegFixture.assertPayload(payload)
            assertTrue(transaction.transferCommittedPayload(payload))
            assertNull(transaction.committedPayload)
        } finally {
            when (transaction.state) {
                ManagedEncodedTransaction.State.Open,
                ManagedEncodedTransaction.State.ProducerClosed,
                ManagedEncodedTransaction.State.Faulted,
                    -> transaction.abort()

                ManagedEncodedTransaction.State.Committed -> {
                    transaction.committedPayload?.let(transaction::transferCommittedPayload)
                }

                ManagedEncodedTransaction.State.Aborted -> Unit
            }
            carrier?.let(NativeJpegProcess::freeCarrier)
        }
        assertSame(ManagedEncodedTransaction.State.Committed, transaction.state)
        assertNull(transaction.committedPayload)
    }

    private class TwoSampleClock : ElapsedRealtimeClock {
        private var calls: Int = 0

        override fun nowNanos(): Long = when (calls++) {
            0 -> 100L
            1 -> 137L
            else -> error("Auto production read more than two clock samples")
        }
    }

    private class TrackingNativeJpegFacade : NativeJpegFacade {
        private var outstandingCarrier: ByteBuffer? = null
        private var compressedCarrier: ByteBuffer? = null
        private var freedCarrier: ByteBuffer? = null
        private var producedByteCount: Long? = null
        private var wireStatus: NativeJpegProcess.NativeWireStatus? = null

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.resolveAvailability()

        override fun hasWeakCompressor(): Boolean = NativeJpegProcess.hasWeakCompressor()

        override fun newResultBlock(): ByteBuffer = NativeJpegProcess.newResultBlock()

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            check(outstandingCarrier == null)
            return NativeJpegProcess.allocateCarrier(carrierByteCount).also { returned ->
                outstandingCarrier = returned
            }
        }

        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            check(carrierBuffer === outstandingCarrier)
            NativeJpegProcess.freeCarrier(carrierBuffer)
            freedCarrier = carrierBuffer
            outstandingCarrier = null
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
        ) {
            check(carrierBuffer === outstandingCarrier)
            NativeJpegProcess.compress(
                carrierBuffer = carrierBuffer,
                pixelByteCount = pixelByteCount,
                width = width,
                height = height,
                stride = stride,
                quality = quality,
                sink = sink,
                resultBlock = resultBlock,
            )
            compressedCarrier = carrierBuffer
            producedByteCount = NativeJpegProcess.readProducedByteCount(resultBlock)
            wireStatus = NativeJpegProcess.readNativeWireStatus(resultBlock)
        }

        fun requireCompleteTransferByteCount(): Long {
            assertSame(outstandingCarrier, compressedCarrier)
            assertSame(NativeJpegProcess.NativeWireStatus.NativeTransferComplete, wireStatus)
            val exactProducedByteCount = checkNotNull(producedByteCount)
            assertTrue(exactProducedByteCount > 0L)
            assertTrue(exactProducedByteCount <= Int.MAX_VALUE.toLong())
            return exactProducedByteCount
        }

        fun assertExactCarrierRetired() {
            assertNull(outstandingCarrier)
            assertSame(compressedCarrier, freedCarrier)
        }
    }
}
