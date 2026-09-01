package io.screenstream.capture.internal.encoding

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

internal class NativeJpegProductionLifecycleTest {
    // Verification: ENC-04
    @Test
    fun adoptedSegmentWithUnsafeEvidenceAbortsWithoutPayloadAndReusesExactCarrier() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val nativeJpeg = AdoptedSegmentUnsafeEvidenceFacade()
            val productionFactory = RecordingNativeProductionFactory()
            val owner = EncodingOwner(
                workerDispatcher = dispatcher,
                clock = ElapsedRealtimeClock { 0L },
                nativeJpeg = nativeJpeg,
                productionFactory = productionFactory,
            )
            var unsettledInput: EncodingInput? = null
            try {
                val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
                reconcileReady(owner, dispatcher, layout)
                val returned = AtomicReference<EncodingResult?>()
                unsettledInput = requireInput(owner) { result ->
                    check(returned.compareAndSet(null, result))
                }
                val exactCarrier = unsettledInput.carrier
                val exactWritableView = unsettledInput.writableView
                fillOpaqueRgba(unsettledInput)

                assertSame(EncodingInputSettlement.Accepted, unsettledInput.encode(jpegQuality = 80))
                unsettledInput = null
                enterOne(dispatcher)

                val failed = returned.get() as? EncodingResult.Failed
                    ?: error("Unsafe Native evidence did not return an owner failure")
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertNull(failed.cause)
                productionFactory.assertAbortedWithoutPayload()
                nativeJpeg.assertOneCompressionWithOneAdoptedSegment()

                unsettledInput = requireInput(owner) { fail("discarded successor returned a production result") }
                assertSame(exactCarrier, unsettledInput.carrier)
                assertSame(exactWritableView, unsettledInput.writableView)
                assertSame(EncodingInputSettlement.Settled, unsettledInput.discard())
                unsettledInput = null
            } finally {
                unsettledInput?.discard()
                owner.retire()
                drainAcceptedWork(dispatcher)
            }
            nativeJpeg.assertCarrierFreedExactlyOnce()
        }
    }

    private fun reconcileReady(
        owner: EncodingOwner,
        dispatcher: ControlledNonInlineDispatcher,
        layout: Rgba8888Layout,
    ) {
        val returned = AtomicReference<EncodingReconcileResult?>()
        assertSame(
            EncodingReconcileSubmission.Accepted,
            owner.reconcile(layout, JpegBackendPolicy.Auto) { result ->
                check(returned.compareAndSet(null, result))
            },
        )
        enterOne(dispatcher)
        assertSame(EncodingReconcileResult.Ready, returned.get())
    }

    private fun requireInput(
        owner: EncodingOwner,
        returnPort: EncodingProductionReturnPort,
    ): EncodingInput {
        val acquired = owner.acquireInput(returnPort)
        assertTrue(acquired is EncodingInput)
        return acquired as EncodingInput
    }

    private fun fillOpaqueRgba(input: EncodingInput) {
        for (pixelOffset in 0 until input.byteCount step Rgba8888Layout.BYTES_PER_PIXEL) {
            input.writableView.put(pixelOffset, 0x20.toByte())
            input.writableView.put(pixelOffset + 1, 0x70.toByte())
            input.writableView.put(pixelOffset + 2, 0xB0.toByte())
            input.writableView.put(pixelOffset + 3, 0xFF.toByte())
        }
        assertEquals(0, input.writableView.position())
    }

    private fun enterOne(dispatcher: ControlledNonInlineDispatcher) {
        val task = dispatcher.enterNext() ?: error("expected accepted Encoding work")
        task.awaitSuccessfulCompletion()
    }

    private fun drainAcceptedWork(dispatcher: ControlledNonInlineDispatcher) {
        while (true) {
            val task = dispatcher.enterNext() ?: return
            task.awaitSuccessfulCompletion()
        }
    }

    private class RecordingNativeProductionFactory : EncodingProductionFactory {
        private var transaction: NativeEncodedTransaction? = null

        override fun createNativeTransaction(): NativeEncodedTransaction {
            check(transaction == null)
            return NativeEncodedTransaction().also { transaction = it }
        }

        override fun createFrameworkTransaction(): FrameworkEncodedTransaction =
            throw AssertionError("Native production test created a Framework transaction")

        override fun createNativeProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: NativeEncodedTransaction,
            jpegQuality: Int,
            healthCell: NativeHealthCell,
            nativeJpeg: NativeJpegFacade,
        ): NativeJpegProduction? = DefaultEncodingProductionFactory.createNativeProduction(
            runtime = runtime,
            expectedInput = expectedInput,
            transaction = transaction,
            jpegQuality = jpegQuality,
            healthCell = healthCell,
            nativeJpeg = nativeJpeg,
        )

        override fun createFrameworkProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: FrameworkEncodedTransaction,
            jpegQuality: Int,
        ): FrameworkJpegProduction =
            throw AssertionError("Native production test created Framework production")

        fun assertAbortedWithoutPayload() {
            val exact = checkNotNull(transaction)
            assertSame(ManagedEncodedTransaction.State.Aborted, exact.state)
            assertNull(exact.committedPayload)
        }
    }

    private class AdoptedSegmentUnsafeEvidenceFacade : NativeJpegFacade {
        private var carrier: ByteBuffer? = null
        private var freeCount: Int = 0
        private var compressionCount: Int = 0
        private var adoptedSegmentCount: Int = 0

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.Availability.Available

        override fun hasWeakCompressor(): Boolean = true

        override fun newResultBlock(): ByteBuffer = NativeJpegProcess.newResultBlock()

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            check(carrier == null)
            return ByteBuffer.allocateDirect(Math.toIntExact(carrierByteCount)).also { carrier = it }
        }

        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            check(carrier === carrierBuffer)
            check(freeCount == 0)
            freeCount += 1
            carrier = null
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
            check(carrier === carrierBuffer)
            check(NativeJpegProcess.hasExactResultShape(resultBlock))
            compressionCount += 1
            val segment = ByteBuffer.allocateDirect(3).apply {
                put(1.toByte())
                put(2.toByte())
                put(3.toByte())
                flip()
            }
            sink.adoptSegment(segment, segment.remaining())
            adoptedSegmentCount += 1
        }

        fun assertOneCompressionWithOneAdoptedSegment() {
            assertEquals(1, compressionCount)
            assertEquals(1, adoptedSegmentCount)
        }

        fun assertCarrierFreedExactlyOnce() {
            assertEquals(1, freeCount)
            assertNull(carrier)
        }
    }
}
