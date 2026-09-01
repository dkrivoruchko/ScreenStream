package io.screenstream.capture.internal.encoding

import io.mockk.every
import io.mockk.mockk
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class EncodingOwnerAutoSelectionTest {
    // Verification: ENC-02
    // Audit item: P6-01
    @Test
    fun autoSelectionHasExactlyFourHostOwnerSemanticArrangements() {
        exerciseFrameworkSelection(
            facade = HostSelectionNativeJpegFacade(
                availability = NativeJpegProcess.Availability.CleanUnavailable,
                capability = { throw AssertionError("Clean-unavailable selection queried Native capability") },
            ),
            expectedCapabilityChecks = 0,
        )

        exerciseFailedSelection(
            facade = HostSelectionNativeJpegFacade(
                availability = NativeJpegProcess.Availability.Poisoned,
                capability = { throw AssertionError("Poisoned selection queried Native capability") },
            ),
            expectedCapabilityChecks = 0,
            expectedCause = null,
        )

        val capabilityFailure = IllegalStateException("Injected Native capability-check failure")
        exerciseFailedSelection(
            facade = HostSelectionNativeJpegFacade(
                availability = NativeJpegProcess.Availability.Available,
                capability = { throw capabilityFailure },
            ),
            expectedCapabilityChecks = 1,
            expectedCause = capabilityFailure,
        )

        exerciseFrameworkSelection(
            facade = HostSelectionNativeJpegFacade(
                availability = NativeJpegProcess.Availability.Available,
                capability = { false },
            ),
            expectedCapabilityChecks = 1,
        )
    }

    // Verification: ENC-02
    // Audit item: P6-02
    @Test
    fun returnedNativeFailureKeepsNativeReadyForSuccessor() {
        val cases = listOf(
            ReturnedNativeFailureCase(
                name = "required resource exhaustion",
                disposition = NativeJpegDisposition.Returned.RequiredResourceExhaustion,
                expectedProblem = ScreenCaptureProblem.ResourceExhausted,
            ),
            ReturnedNativeFailureCase(
                name = "unsafe internal failure",
                disposition = NativeJpegDisposition.Returned.UnsafeInternalFailure,
                expectedProblem = ScreenCaptureProblem.InternalFailure,
            ),
        )

        cases.forEach { case ->
            ControlledNonInlineDispatcher().use { dispatcher ->
                val nativeJpeg = NativeCarrierOnlyJpegFacade()
                val productionFactory = ClassifiedNativeFailureProductionFactory(case.disposition)
                val owner = EncodingOwner(
                    workerDispatcher = dispatcher,
                    clock = ElapsedRealtimeClock { 0L },
                    nativeJpeg = nativeJpeg,
                    productionFactory = productionFactory,
                )
                try {
                    val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
                    assertSame(EncodingReconcileResult.Ready, reconcile(owner, dispatcher, layout))

                    repeat(2) { productionIndex ->
                        val returned = AtomicReference<EncodingResult?>()
                        val input = requireInput(owner) { result ->
                            check(returned.compareAndSet(null, result))
                        }
                        fillOpaqueRgba(input)

                        assertSame(EncodingInputSettlement.Accepted, input.encode(jpegQuality = 80))
                        enterOne(dispatcher)

                        val failure = returned.get() as? EncodingResult.Failed
                            ?: error("${case.name} did not return an owner failure")
                        assertSame(case.name, case.expectedProblem, failure.problem)
                        assertNull(case.name, failure.cause)
                        productionFactory.assertEnteredAndAbortedCount(productionIndex + 1)
                        nativeJpeg.assertNoCompression()
                    }
                } finally {
                    owner.retire()
                    drainAcceptedWork(dispatcher)
                }
                nativeJpeg.assertCarrierFreedExactlyOnce()
            }
        }
    }

    private fun exerciseFrameworkSelection(
        facade: HostSelectionNativeJpegFacade,
        expectedCapabilityChecks: Int,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = EncodingOwner(dispatcher, ElapsedRealtimeClock { 0L }, facade)
            try {
                val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
                assertSame(EncodingReconcileResult.Ready, reconcile(owner, dispatcher, layout))
                val returned = AtomicReference<EncodingResult?>()
                val input = requireInput(owner) { result ->
                    check(returned.compareAndSet(null, result))
                }
                fillOpaqueRgba(input)

                assertSame(EncodingInputSettlement.Accepted, input.encode(jpegQuality = 80))
                enterOne(dispatcher)

                val encoded = returned.get() as? EncodingResult.Encoded
                    ?: error("Auto Framework selection did not produce encoded output")
                assertTrue(encoded.payload.byteCount > 0)
                facade.assertOnlyExpectedCapabilityChecks(expectedCapabilityChecks)
            } finally {
                owner.retire()
                drainAcceptedWork(dispatcher)
            }
            facade.assertOnlyExpectedCapabilityChecks(expectedCapabilityChecks)
        }
    }

    private fun exerciseFailedSelection(
        facade: HostSelectionNativeJpegFacade,
        expectedCapabilityChecks: Int,
        expectedCause: Throwable?,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = EncodingOwner(dispatcher, ElapsedRealtimeClock { 0L }, facade)
            try {
                val result = reconcile(owner, dispatcher, Rgba8888Layout.create(widthPx = 2, heightPx = 2))
                val failed = result as? EncodingReconcileResult.Failed
                    ?: error("Auto selection unexpectedly became ready")
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertSame(expectedCause, failed.cause)
                facade.assertOnlyExpectedCapabilityChecks(expectedCapabilityChecks)
            } finally {
                owner.retire()
                drainAcceptedWork(dispatcher)
            }
        }
    }

    private fun reconcile(
        owner: EncodingOwner,
        dispatcher: ControlledNonInlineDispatcher,
        layout: Rgba8888Layout,
    ): EncodingReconcileResult {
        val returned = AtomicReference<EncodingReconcileResult?>()
        assertSame(
            EncodingReconcileSubmission.Accepted,
            owner.reconcile(layout, JpegBackendPolicy.Auto) { result ->
                check(returned.compareAndSet(null, result))
            },
        )
        enterOne(dispatcher)
        return checkNotNull(returned.get())
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

    private class HostSelectionNativeJpegFacade(
        private val availability: NativeJpegProcess.Availability,
        private val capability: () -> Boolean,
    ) : NativeJpegFacade {
        private var capabilityCheckCount: Int = 0
        private var allocationCount: Int = 0
        private var compressionCount: Int = 0

        override fun resolveAvailability(): NativeJpegProcess.Availability = availability

        override fun hasWeakCompressor(): Boolean {
            capabilityCheckCount += 1
            return capability()
        }

        override fun newResultBlock(): ByteBuffer =
            throw AssertionError("Framework selection allocated a Native result block")

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            allocationCount += 1
            throw AssertionError("Framework selection allocated a Native carrier")
        }

        override fun freeCarrier(carrierBuffer: ByteBuffer): Unit =
            throw AssertionError("Framework selection freed a Native carrier")

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
            compressionCount += 1
            throw AssertionError("Framework selection entered Native compression")
        }

        fun assertOnlyExpectedCapabilityChecks(expectedCapabilityChecks: Int) {
            assertEquals(expectedCapabilityChecks, capabilityCheckCount)
            assertEquals(0, allocationCount)
            assertEquals(0, compressionCount)
        }
    }

    private class NativeCarrierOnlyJpegFacade : NativeJpegFacade {
        private var carrier: ByteBuffer? = null
        private var freeCount: Int = 0
        private var compressionCount: Int = 0

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.Availability.Available

        override fun hasWeakCompressor(): Boolean = true

        override fun newResultBlock(): ByteBuffer =
            throw AssertionError("Classified production allocated a Native result block")

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
            compressionCount += 1
            throw AssertionError("Classified production entered Native compression")
        }

        fun assertNoCompression() {
            assertEquals(0, compressionCount)
        }

        fun assertCarrierFreedExactlyOnce() {
            assertEquals(1, freeCount)
            assertNull(carrier)
        }
    }

    private class ClassifiedNativeFailureProductionFactory(
        private val disposition: NativeJpegDisposition.Returned,
    ) : EncodingProductionFactory {
        private val transactions = ArrayList<NativeEncodedTransaction>()
        private val results = ArrayList<NativeJpegResult>()
        private var nativeEntryCount: Int = 0

        override fun createNativeTransaction(): NativeEncodedTransaction = NativeEncodedTransaction()

        override fun createFrameworkTransaction(): FrameworkEncodedTransaction =
            throw AssertionError("Native owner arrangement created a Framework transaction")

        override fun createNativeProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: NativeEncodedTransaction,
            jpegQuality: Int,
            healthCell: NativeHealthCell,
            nativeJpeg: NativeJpegFacade,
        ): NativeJpegProduction {
            val result = NativeJpegResult(transaction)
            val production = mockk<NativeJpegProduction>()
            every { production.runtime } returns runtime
            every { production.input } returns expectedInput
            every { production.transaction } returns transaction
            every { production.healthCell } returns healthCell
            every { production.result } returns result
            every { production.hasLeafResult } returns true
            every { production.execute(any()) } answers {
                val enteredCarrier = checkNotNull(runtime.enterNativeUse(production))
                check(enteredCarrier === expectedInput.writableView)
                nativeEntryCount += 1
                check(runtime.releaseNativeUseAfterReturn(production))
                check(transaction.abort())
                result.recordReturned(
                    disposition = disposition,
                    resultBlock = null,
                    encodeDurationNanos = 0L,
                    payload = null,
                )
            }
            every { production.settleDetachedLeaf() } returns null
            transactions += transaction
            results += result
            return production
        }

        override fun createFrameworkProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: FrameworkEncodedTransaction,
            jpegQuality: Int,
        ): FrameworkJpegProduction =
            throw AssertionError("Native owner arrangement created Framework production")

        fun assertEnteredAndAbortedCount(expected: Int) {
            assertEquals(expected, nativeEntryCount)
            assertEquals(expected, transactions.size)
            assertEquals(expected, results.size)
            transactions.forEach { transaction ->
                assertSame(ManagedEncodedTransaction.State.Aborted, transaction.state)
                assertNull(transaction.committedPayload)
            }
            results.forEach { result ->
                assertSame(disposition, result.requireDisposition())
                assertNull(result.payload)
            }
        }
    }

    private class ReturnedNativeFailureCase(
        val name: String,
        val disposition: NativeJpegDisposition.Returned,
        val expectedProblem: ScreenCaptureProblem,
    )
}
