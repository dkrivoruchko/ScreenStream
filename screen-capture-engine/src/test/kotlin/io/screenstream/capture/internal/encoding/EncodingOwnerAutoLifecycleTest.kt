package io.screenstream.capture.internal.encoding

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class EncodingOwnerAutoLifecycleTest {
    // Verification: ENC-02
    @Test
    fun autoSafeRejectionRequiresReconciliationBeforeFrameworkFallback() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val nativeJpeg = SafeRejectingNativeJpegFacade()
            val owner = EncodingOwner(dispatcher, ElapsedRealtimeClock { 0L }, nativeJpeg)
            var unsettledInput: EncodingInput? = null

            try {
                val nativeLayout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
                reconcileReady(owner, dispatcher, nativeLayout)
                val nativeResult = AtomicReference<EncodingResult?>()
                unsettledInput = requireInput(owner) { result ->
                    check(nativeResult.compareAndSet(null, result))
                }
                fillOpaqueRgba(unsettledInput)

                assertSame(EncodingInputSettlement.Accepted, unsettledInput.encode(jpegQuality = 80))
                unsettledInput = null
                enterOne(dispatcher)

                assertSame(EncodingResult.ReadinessChanged, nativeResult.get())
                nativeJpeg.assertOneCompressionEffect()
                assertInputFailedInternal(owner.acquireInput { fail("unready owner returned a production result") })

                val frameworkLayout = Rgba8888Layout.create(widthPx = 3, heightPx = 2)
                reconcileReady(owner, dispatcher, frameworkLayout)
                nativeJpeg.assertOneCarrierAllocation()
                val frameworkResult = AtomicReference<EncodingResult?>()
                unsettledInput = requireInput(owner) { result ->
                    check(frameworkResult.compareAndSet(null, result))
                }
                fillOpaqueRgba(unsettledInput)

                assertSame(EncodingInputSettlement.Accepted, unsettledInput.encode(jpegQuality = 80))
                unsettledInput = null
                enterOne(dispatcher)

                val encoded = frameworkResult.get() as? EncodingResult.Encoded
                    ?: error("later reconciled frame did not use Framework encoding")
                assertTrue(encoded.payload.byteCount > 0)
                nativeJpeg.assertOneCompressionEffect()

                owner.retire()
                enterOne(dispatcher)
                nativeJpeg.assertEveryCarrierFreedExactlyOnce()
            } finally {
                unsettledInput?.discard()
                owner.retire()
                drainAcceptedWork(dispatcher)
            }
        }
    }

    // Verification: ENC-02
    // Verification: ENC-09
    @Test
    fun retirementWaitsForNativeCarrierLoanAndFreesItOnce() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val nativeJpeg = SafeRejectingNativeJpegFacade()
            val owner = EncodingOwner(dispatcher, ElapsedRealtimeClock { 0L }, nativeJpeg)
            var unsettledInput: EncodingInput? = null

            try {
                val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
                reconcileReady(owner, dispatcher, layout)
                unsettledInput = requireInput(owner) { fail("discarded input returned a production result") }
                nativeJpeg.assertAllocatedCarrier(unsettledInput.writableView)

                owner.retire()
                drainAcceptedWork(dispatcher)
                nativeJpeg.assertNoFreeEffect()

                assertSame(EncodingInputSettlement.Settled, unsettledInput.discard())
                unsettledInput = null
                enterOne(dispatcher)

                nativeJpeg.assertEveryCarrierFreedExactlyOnce()
                assertInputFailedInternal(owner.acquireInput { fail("retired owner returned a production result") })
                owner.retire()
                drainAcceptedWork(dispatcher)
                nativeJpeg.assertEveryCarrierFreedExactlyOnce()
            } finally {
                unsettledInput?.discard()
                owner.retire()
                drainAcceptedWork(dispatcher)
            }
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

    private fun assertInputFailedInternal(result: EncodingInputResult) {
        assertTrue(result is EncodingInputResult.Failed)
        assertSame(ScreenCaptureProblem.InternalFailure, (result as EncodingInputResult.Failed).problem)
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

    private class SafeRejectingNativeJpegFacade : NativeJpegFacade {
        private val allocatedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
        private val successfullyFreedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
        private val freeAttempts = ArrayList<ByteBuffer>()
        private var compressionEffectCount: Int = 0

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.Availability.Available

        override fun hasWeakCompressor(): Boolean = true

        override fun newResultBlock(): ByteBuffer = NativeJpegProcess.newResultBlock()

        @Synchronized
        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            val carrier = ByteBuffer.allocateDirect(Math.toIntExact(carrierByteCount))
            check(allocatedCarriers.add(carrier))
            return carrier
        }

        @Synchronized
        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            freeAttempts += carrierBuffer
            check(allocatedCarriers.contains(carrierBuffer))
            check(successfullyFreedCarriers.add(carrierBuffer))
        }

        @Synchronized
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
            check(allocatedCarriers.contains(carrierBuffer))
            check(!successfullyFreedCarriers.contains(carrierBuffer))
            check(NativeJpegProcess.hasExactResultShape(resultBlock))
            check(resultBlock.getLong(0) == NativeJpegProcess.NATIVE_RESULT_PENDING)
            check(resultBlock.getLong(8) == NativeJpegProcess.NATIVE_RESULT_PENDING)
            compressionEffectCount += 1
            resultBlock.putLong(0, 0L)
            resultBlock.putLong(8, 1L)
        }

        @Synchronized
        fun assertAllocatedCarrier(candidate: ByteBuffer) {
            assertTrue(allocatedCarriers.contains(candidate))
            assertFalse(successfullyFreedCarriers.contains(candidate))
        }

        @Synchronized
        fun assertOneCompressionEffect() {
            assertEquals(1, compressionEffectCount)
        }

        @Synchronized
        fun assertOneCarrierAllocation() {
            assertEquals(1, allocatedCarriers.size)
        }

        @Synchronized
        fun assertNoFreeEffect() {
            assertTrue(freeAttempts.isEmpty())
            assertTrue(successfullyFreedCarriers.isEmpty())
        }

        @Synchronized
        fun assertEveryCarrierFreedExactlyOnce() {
            assertTrue(allocatedCarriers.isNotEmpty())
            assertEquals(allocatedCarriers.size, freeAttempts.size)
            assertEquals(allocatedCarriers.size, successfullyFreedCarriers.size)
            for (carrier in allocatedCarriers) {
                assertEquals(1, freeAttempts.count { attempted -> attempted === carrier })
                assertTrue(successfullyFreedCarriers.contains(carrier))
            }
        }
    }
}
