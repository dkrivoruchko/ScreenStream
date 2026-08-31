package io.screenstream.capture.internal.encoding

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class EncodingOwnerPreSubmissionFailureTest {
    // Verification: ENC-03
    @Test
    fun frameworkTransactionConstructionExhaustionSettlesReadyInputWithoutSubmission() {
        exerciseContainedConstructionFailure(
            policy = JpegBackendPolicy.FrameworkOnly,
            failurePoint = FailurePoint.FrameworkTransaction,
            failure = OutOfMemoryError("Framework transaction allocation exhausted"),
            expectedProblem = ScreenCaptureProblem.ResourceExhausted,
        )
    }

    // Verification: ENC-03
    @Test
    fun nativeTransactionConstructionExhaustionSettlesReadyInputWithoutSubmission() {
        exerciseContainedConstructionFailure(
            policy = JpegBackendPolicy.Auto,
            failurePoint = FailurePoint.NativeTransaction,
            failure = OutOfMemoryError("Native transaction allocation exhausted"),
            expectedProblem = ScreenCaptureProblem.ResourceExhausted,
        )
    }

    // Verification: ENC-03
    @Test
    fun frameworkAndNativeProductionConstructionExceptionsFailInternallyAfterInputSettlement() {
        exerciseContainedConstructionFailure(
            policy = JpegBackendPolicy.FrameworkOnly,
            failurePoint = FailurePoint.FrameworkProduction,
            failure = IllegalStateException("Framework production construction failed"),
            expectedProblem = ScreenCaptureProblem.InternalFailure,
        )
        exerciseContainedConstructionFailure(
            policy = JpegBackendPolicy.Auto,
            failurePoint = FailurePoint.NativeProduction,
            failure = IllegalStateException("Native production construction failed"),
            expectedProblem = ScreenCaptureProblem.InternalFailure,
        )
    }

    // Verification: ENC-03
    @Test
    fun frameworkProductionConstructionOutOfMemoryEscapesWithoutTypedSettlement() {
        val failure = OutOfMemoryError("Framework production allocation exhausted")
        exerciseEscapingConstructionFailure(
            policy = JpegBackendPolicy.FrameworkOnly,
            failurePoint = FailurePoint.FrameworkProduction,
            failure = failure,
            assertEscape = { action ->
                assertSame(failure, assertThrows(OutOfMemoryError::class.java) { action() })
            },
        )
    }

    // Verification: ENC-03
    @Test
    fun nativeProductionConstructionThrowableEscapesWithoutTypedSettlement() {
        val failure = object : Throwable("Native production construction failed") {}
        exerciseEscapingConstructionFailure(
            policy = JpegBackendPolicy.Auto,
            failurePoint = FailurePoint.NativeProduction,
            failure = failure,
            assertEscape = { action ->
                assertSame(failure, assertThrows(Throwable::class.java) { action() })
            },
        )
    }

    private fun exerciseContainedConstructionFailure(
        policy: JpegBackendPolicy,
        failurePoint: FailurePoint,
        failure: Throwable,
        expectedProblem: ScreenCaptureProblem,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = createOwner(
                dispatcher = dispatcher,
                policy = policy,
                productionFactory = FaultInjectingProductionFactory(failurePoint, failure),
            )
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            reconcileReady(owner, dispatcher, layout, policy)
            val returned = AtomicReference<EncodingResult?>()
            val input = requireInput(owner) { result ->
                check(returned.compareAndSet(null, result))
            }
            fillOpaqueRgba(input)

            val settlement = input.encode(jpegQuality = 80)

            assertFailedSettlement(settlement, expectedProblem, failure)
            assertNull(returned.get())
            val successor = requireInput(owner) { fail("settled input returned a production callback") }
            assertSame(EncodingInputSettlement.Settled, successor.discard())
            assertNull(returned.get())
            owner.retire()
            enterOne(dispatcher)
            assertNull(returned.get())
        }
    }

    private fun exerciseEscapingConstructionFailure(
        policy: JpegBackendPolicy,
        failurePoint: FailurePoint,
        failure: Throwable,
        assertEscape: (() -> Unit) -> Unit,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val owner = createOwner(
                dispatcher = dispatcher,
                policy = policy,
                productionFactory = FaultInjectingProductionFactory(failurePoint, failure),
            )
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            reconcileReady(owner, dispatcher, layout, policy)
            val returned = AtomicReference<EncodingResult?>()
            val input = requireInput(owner) { result ->
                check(returned.compareAndSet(null, result))
            }
            fillOpaqueRgba(input)

            try {
                assertEscape { input.encode(jpegQuality = 80) }
                assertNull(returned.get())
                assertInputFailedInternal(
                    owner.acquireInput { fail("unsettled owner returned a production callback") },
                )
                assertFailedSettlement(
                    input.discard(),
                    expectedProblem = ScreenCaptureProblem.InternalFailure,
                    expectedCause = null,
                )
                assertNull(returned.get())
            } finally {
                owner.retire()
            }
        }
    }

    private fun createOwner(
        dispatcher: ControlledNonInlineDispatcher,
        policy: JpegBackendPolicy,
        productionFactory: EncodingProductionFactory,
    ): EncodingOwner = when (policy) {
        JpegBackendPolicy.FrameworkOnly -> EncodingOwner(
            workerDispatcher = dispatcher,
            clock = ElapsedRealtimeClock { 0L },
            productionFactory = productionFactory,
        )

        JpegBackendPolicy.Auto -> EncodingOwner(
            workerDispatcher = dispatcher,
            clock = ElapsedRealtimeClock { 0L },
            nativeJpeg = AvailableNativeJpegFacade(),
            productionFactory = productionFactory,
        )
    }

    private fun reconcileReady(
        owner: EncodingOwner,
        dispatcher: ControlledNonInlineDispatcher,
        layout: Rgba8888Layout,
        policy: JpegBackendPolicy,
    ) {
        val returned = AtomicReference<EncodingReconcileResult?>()
        assertSame(
            EncodingReconcileSubmission.Accepted,
            owner.reconcile(layout, policy) { result ->
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
    }

    private fun assertFailedSettlement(
        settlement: EncodingInputSettlement,
        expectedProblem: ScreenCaptureProblem,
        expectedCause: Throwable?,
    ) {
        assertTrue(settlement is EncodingInputSettlement.Failed)
        settlement as EncodingInputSettlement.Failed
        assertSame(expectedProblem, settlement.problem)
        assertSame(expectedCause, settlement.cause)
    }

    private fun assertInputFailedInternal(result: EncodingInputResult) {
        assertTrue(result is EncodingInputResult.Failed)
        result as EncodingInputResult.Failed
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertNull(result.cause)
    }

    private fun enterOne(dispatcher: ControlledNonInlineDispatcher) {
        val task = dispatcher.enterNext() ?: error("expected accepted Encoding work")
        task.awaitSuccessfulCompletion()
    }

    private enum class FailurePoint {
        NativeTransaction,
        FrameworkTransaction,
        NativeProduction,
        FrameworkProduction,
    }

    private class FaultInjectingProductionFactory(
        private val failurePoint: FailurePoint,
        private val failure: Throwable,
    ) : EncodingProductionFactory {
        override fun createNativeTransaction(): NativeEncodedTransaction {
            throwIfSelected(FailurePoint.NativeTransaction)
            return DefaultEncodingProductionFactory.createNativeTransaction()
        }

        override fun createFrameworkTransaction(): FrameworkEncodedTransaction {
            throwIfSelected(FailurePoint.FrameworkTransaction)
            return DefaultEncodingProductionFactory.createFrameworkTransaction()
        }

        override fun createNativeProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: NativeEncodedTransaction,
            jpegQuality: Int,
            healthCell: NativeHealthCell,
            nativeJpeg: NativeJpegFacade,
        ): NativeJpegProduction? {
            throwIfSelected(FailurePoint.NativeProduction)
            return DefaultEncodingProductionFactory.createNativeProduction(
                runtime = runtime,
                expectedInput = expectedInput,
                transaction = transaction,
                jpegQuality = jpegQuality,
                healthCell = healthCell,
                nativeJpeg = nativeJpeg,
            )
        }

        override fun createFrameworkProduction(
            runtime: EncoderRuntime,
            expectedInput: EncodingInput,
            transaction: FrameworkEncodedTransaction,
            jpegQuality: Int,
        ): FrameworkJpegProduction? {
            throwIfSelected(FailurePoint.FrameworkProduction)
            return DefaultEncodingProductionFactory.createFrameworkProduction(
                runtime = runtime,
                expectedInput = expectedInput,
                transaction = transaction,
                jpegQuality = jpegQuality,
            )
        }

        private fun throwIfSelected(candidate: FailurePoint) {
            if (candidate == failurePoint) throw failure
        }
    }

    private class AvailableNativeJpegFacade : NativeJpegFacade {
        private var ownedCarrier: ByteBuffer? = null

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.Availability.Available

        override fun hasWeakCompressor(): Boolean = true

        override fun newResultBlock(): ByteBuffer = NativeJpegProcess.newResultBlock()

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer {
            check(ownedCarrier == null)
            val carrier = ByteBuffer.allocateDirect(Math.toIntExact(carrierByteCount))
            ownedCarrier = carrier
            return carrier
        }

        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            check(ownedCarrier === carrierBuffer)
            ownedCarrier = null
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
        ): Unit = throw AssertionError("Native compression unexpectedly entered")
    }
}
