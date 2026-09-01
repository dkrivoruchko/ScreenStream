package io.screenstream.capture.internal.encoding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.FrameworkBitmapCompressionFixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class EncodingOwnerFrameworkLifecycleTest {
    // Verification: ENC-05
    @Test
    fun reconcileDispatchRejectionIsCallbackFree() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
            )
            val rejectedPort = RecordingReconcilePort()
            dispatcher.enqueueReject()

            val rejected = owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, rejectedPort)

            assertReconcileRejected(rejected, expectedCause = null)
            rejectedPort.assertCallbackFree()
            reconcileReady(owner, dispatcher, layout)
            rejectedPort.assertCallbackFree()
            retireReadyOwner(owner, dispatcher)
        }
    }

    // Verification: ENC-05
    @Test
    fun reconcileDispatchExceptionPreservesCause() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
            )
            val failure = IllegalStateException("reconcile dispatch failed")
            val rejectedPort = RecordingReconcilePort()
            dispatcher.enqueueThrow(failure)

            val rejected = owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, rejectedPort)

            assertReconcileRejected(rejected, expectedCause = failure)
            rejectedPort.assertCallbackFree()
            reconcileReady(owner, dispatcher, layout)
            rejectedPort.assertCallbackFree()
            retireReadyOwner(owner, dispatcher)
        }
    }

    // Verification: ENC-05
    @Test
    fun equalFrameworkReconcileReturnsReadyTwiceAndPreservesSettledCarrierIdentity() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
            )
            val firstPort = RecordingReconcilePort()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, firstPort),
            )
            enterOne(dispatcher)
            firstPort.assertReturnedExactlyOnce(EncodingReconcileResult.Ready)

            val firstInput = requireInput(owner) { fail("discarded first input returned a production result") }
            assertExactCarrier(firstInput, layout)
            assertSame(EncodingInputSettlement.Settled, firstInput.discard())

            val secondPort = RecordingReconcilePort()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, secondPort),
            )
            enterOne(dispatcher)
            secondPort.assertReturnedExactlyOnce(EncodingReconcileResult.Ready)

            val secondInput = requireInput(owner) { fail("discarded second input returned a production result") }
            assertSame(firstInput.carrier, secondInput.carrier)
            assertSame(firstInput.writableView, secondInput.writableView)
            assertExactCarrier(secondInput, layout)
            assertSame(EncodingInputSettlement.Settled, secondInput.discard())
            retireReadyOwner(owner, dispatcher)
        }
    }

    // Verification: ENC-05
    // Verification: ENC-06
    @Test
    fun invalidBitmapReconcileRetainsRootsUntilRetirementAndThenRecovers() {
        val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
        val invalidBitmap = spyk(Bitmap.createBitmap(layout.widthPx + 1, layout.heightPx, Bitmap.Config.ARGB_8888))
        val validBitmap = spyk(Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888))
        var staticMockInstalled = false
        try {
            mockkStatic(Bitmap::class)
            staticMockInstalled = true
            val allocationIndex = AtomicInteger()
            every { Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888) } answers {
                when (allocationIndex.getAndIncrement()) {
                    0 -> invalidBitmap
                    1 -> validBitmap
                    else -> throw AssertionError("Framework recovery allocated an unexpected Bitmap")
                }
            }

            ControlledNonInlineDispatcher().use { dispatcher ->
                val owner = EncodingOwner(
                    dispatcher,
                    clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
                )
                val failedPort = RecordingReconcilePort()
                assertSame(
                    EncodingReconcileSubmission.Accepted,
                    owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, failedPort),
                )
                enterOne(dispatcher)

                val failed = failedPort.returnedExactlyOnce() as? EncodingReconcileResult.Failed
                    ?: error("Invalid Bitmap reconcile did not fail")
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertTrue(failed.cause is IllegalStateException)
                assertInputFailedInternal(owner.acquireInput { fail("invalid Bitmap owner exposed an input") })
                assertFalse(invalidBitmap.isRecycled)
                verify(exactly = 0) { invalidBitmap.recycle() }

                val recoveredPort = RecordingReconcilePort()
                assertSame(
                    EncodingReconcileSubmission.Accepted,
                    owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, recoveredPort),
                )
                enterOne(dispatcher)

                recoveredPort.assertReturnedExactlyOnce(EncodingReconcileResult.Ready)
                assertEquals(2, allocationIndex.get())
                assertTrue(invalidBitmap.isRecycled)
                verify(exactly = 1) { invalidBitmap.recycle() }
                val recoveredInput = requireInput(owner) { fail("discarded recovered input returned a production result") }
                assertExactCarrier(recoveredInput, layout)
                assertSame(EncodingInputSettlement.Settled, recoveredInput.discard())

                retireReadyOwner(owner, dispatcher)
                assertTrue(validBitmap.isRecycled)
                verify(exactly = 1) {
                    invalidBitmap.recycle()
                    validBitmap.recycle()
                }
            }
        } finally {
            if (staticMockInstalled) unmockkStatic(Bitmap::class)
            if (!invalidBitmap.isRecycled) invalidBitmap.recycle()
            if (!validBitmap.isRecycled) validBitmap.recycle()
        }
    }

    // Verification: ENC-06
    @Test
    fun bitmapRecycleExceptionBlocksShapeReplacementAndIsNotRetried() {
        val initialLayout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
        val replacementLayout = Rgba8888Layout.create(widthPx = 3, heightPx = 2)
        val bitmap = spyk(Bitmap.createBitmap(initialLayout.widthPx, initialLayout.heightPx, Bitmap.Config.ARGB_8888))
        val recycleFailure = IllegalStateException("Bitmap recycle failed")
        var staticMockInstalled = false
        try {
            every { bitmap.recycle() } throws recycleFailure
            mockkStatic(Bitmap::class)
            staticMockInstalled = true
            every {
                Bitmap.createBitmap(initialLayout.widthPx, initialLayout.heightPx, Bitmap.Config.ARGB_8888)
            } returns bitmap

            ControlledNonInlineDispatcher().use { dispatcher ->
                val owner = EncodingOwner(
                    dispatcher,
                    clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
                )
                reconcileReady(owner, dispatcher, initialLayout)
                val replacementPort = RecordingReconcilePort()
                assertSame(
                    EncodingReconcileSubmission.Accepted,
                    owner.reconcile(replacementLayout, JpegBackendPolicy.FrameworkOnly, replacementPort),
                )
                enterOne(dispatcher)

                val failed = replacementPort.returnedExactlyOnce() as? EncodingReconcileResult.Failed
                    ?: error("Failed Bitmap retirement did not fail replacement reconcile")
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertSame(recycleFailure, failed.cause)
                assertInputFailedInternal(owner.acquireInput { fail("failed replacement exposed an input") })
                verify(exactly = 1) {
                    Bitmap.createBitmap(any(), any(), Bitmap.Config.ARGB_8888)
                    bitmap.recycle()
                }

                owner.retire()
                enterOne(dispatcher)
                assertRetired(owner)
                verify(exactly = 1) {
                    Bitmap.createBitmap(any(), any(), Bitmap.Config.ARGB_8888)
                    bitmap.recycle()
                }
            }
        } finally {
            if (staticMockInstalled) unmockkStatic(Bitmap::class)
            clearMocks(bitmap, answers = true)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // Verification: ENC-07
    // Verification: FWK-01
    @Test
    fun swallowedCompressionStreamFaultIsInternalAndAbortsBeforeCarrierRecovery() {
        val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
        val bitmap = spyk(Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888))
        val partialBytes = byteArrayOf(0x50, 0x41, 0x52, 0x54)
        val recoveredBytes = byteArrayOf(0x4A, 0x50, 0x45, 0x47)
        val compressionAttempt = AtomicInteger()
        var staticMockInstalled = false
        try {
            every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
                val output = thirdArg<OutputStream>()
                if (compressionAttempt.getAndIncrement() == 0) {
                    output.write(partialBytes)
                    try {
                        output.write(partialBytes, -1, 1)
                    } catch (_: Exception) {
                    }
                    false
                } else {
                    output.write(recoveredBytes)
                    true
                }
            }
            mockkStatic(Bitmap::class)
            staticMockInstalled = true
            every { Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888) } returns bitmap

            ControlledNonInlineDispatcher().use { dispatcher ->
                val productionFactory = RecordingFrameworkProductionFactory()
                val owner = EncodingOwner(
                    dispatcher,
                    clock = ThreeSampleClock(firstStartNanos = 10L, secondStartNanos = 20L, secondFinishNanos = 31L),
                    nativeJpeg = FailFastNativeJpegFacade,
                    productionFactory = productionFactory,
                )
                reconcileReady(owner, dispatcher, layout)
                val failedPort = RecordingProductionPort()
                val failedInput = requireInput(owner, failedPort)
                fillOpaqueRgba(failedInput)
                assertSame(EncodingInputSettlement.Accepted, failedInput.encode(jpegQuality = 80))
                enterOne(dispatcher)

                val failed = failedPort.returnedExactlyOnce() as? EncodingResult.Failed
                    ?: error("Faulted Framework transaction was treated as a benign frame rejection")
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertNull(failed.cause)
                val aborted = checkNotNull(productionFactory.firstTransaction)
                assertEquals(partialBytes.size, aborted.byteCount)
                assertSame(ManagedEncodedTransaction.State.Aborted, aborted.state)
                assertNull(aborted.committedPayload)

                val recoveredPort = RecordingProductionPort()
                val recoveredInput = requireInput(owner, recoveredPort)
                assertSame(failedInput.carrier, recoveredInput.carrier)
                assertSame(failedInput.writableView, recoveredInput.writableView)
                fillOpaqueRgba(recoveredInput)
                assertSame(EncodingInputSettlement.Accepted, recoveredInput.encode(jpegQuality = 80))
                enterOne(dispatcher)

                val encoded = recoveredPort.returnedExactlyOnce() as? EncodingResult.Encoded
                    ?: error("Framework owner did not recover after the faulted transaction")
                assertArrayEquals(recoveredBytes, encoded.payload.toByteArray())
                assertEquals(11L, encoded.encodeDurationNanos)
                assertEquals(2, compressionAttempt.get())
                retireReadyOwner(owner, dispatcher)
            }
        } finally {
            if (staticMockInstalled) unmockkStatic(Bitmap::class)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // Verification: ENC-05
    @Test
    fun unenteredReconcileIsCutOffOnRetire() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("reconcile read the production clock") },
            )
            val acceptedPort = RecordingReconcilePort()
            assertSame(
                EncodingReconcileSubmission.Accepted,
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, acceptedPort),
            )

            acceptedPort.assertCallbackFree()
            assertEquals(1, dispatcher.pendingCount())
            val successorPort = RecordingReconcilePort()
            assertReconcileRejected(
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, successorPort),
                expectedCause = null,
            )
            successorPort.assertCallbackFree()
            assertInputFailedInternal(owner.acquireInput { fail("unavailable input returned a production result") })

            owner.retire()
            acceptedPort.assertCallbackFree()
            assertEquals(1, dispatcher.pendingCount())
            enterOne(dispatcher)

            acceptedPort.assertReturnedExactlyOnce(EncodingReconcileResult.CutoffInert)
            assertNull(dispatcher.enterNext())
            assertReconcileRejected(
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, successorPort),
                expectedCause = null,
            )
            successorPort.assertCallbackFree()
            assertInputFailedInternal(owner.acquireInput { fail("retired owner returned a production result") })
        }
    }

    // Verification: ENC-01
    // Verification: ENC-02
    @Test
    @Config(
        manifest = Config.NONE,
        sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.O, Build.VERSION_CODES.BAKLAVA],
    )
    fun frameworkEncodingProducesReusableJpeg() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 3, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = TwoSampleClock(startNanos = 100L, finishNanos = 137L),
                nativeJpeg = FailFastNativeJpegFacade,
            )
            reconcileReady(owner, dispatcher, layout)
            val returned = AtomicReference<EncodingResult?>()
            val input = requireInput(owner) { result ->
                check(returned.compareAndSet(null, result))
            }
            assertExactCarrier(input, layout)
            fillOpaqueRgba(input)

            assertSame(EncodingInputSettlement.Accepted, input.encode(jpegQuality = 80))
            enterOne(dispatcher)

            val encoded = returned.get() as? EncodingResult.Encoded
                ?: error("Framework production did not return Encoded")
            assertEquals(37L, encoded.encodeDurationNanos)
            assertTrue(encoded.payload.byteCount > 0)
            val jpeg = encoded.payload.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: error("Robolectric BitmapFactory did not decode the Framework JPEG")
            try {
                assertEquals(layout.widthPx, decoded.width)
                assertEquals(layout.heightPx, decoded.height)
            } finally {
                decoded.recycle()
            }

            val reusable = requireInput(owner) { fail("discarded reusable input returned a production result") }
            assertSame(input.carrier, reusable.carrier)
            assertSame(input.writableView, reusable.writableView)
            assertExactCarrier(reusable, layout)
            assertSame(EncodingInputSettlement.Settled, reusable.discard())
            owner.retire()
            enterOne(dispatcher)
            assertRetired(owner)
        }
    }

    // Verification: ENC-01
    // Verification: FWK-01
    @Test
    fun frameworkCompressionRejectionAbortsPartialPayloadAndKeepsOwnerReusable() {
        val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
        FrameworkBitmapCompressionFixture(layout.widthPx, layout.heightPx).use { bitmapFixture ->
            ControlledNonInlineDispatcher().use { dispatcher ->
                val productionFactory = RecordingFrameworkProductionFactory()
                val owner = EncodingOwner(
                    dispatcher,
                    clock = ThreeSampleClock(firstStartNanos = 10L, secondStartNanos = 20L, secondFinishNanos = 31L),
                    nativeJpeg = FailFastNativeJpegFacade,
                    productionFactory = productionFactory,
                )
                reconcileReady(owner, dispatcher, layout)
                val rejectedResult = AtomicReference<EncodingResult?>()
                val rejectedInput = requireInput(owner) { result ->
                    check(rejectedResult.compareAndSet(null, result))
                }
                fillOpaqueRgba(rejectedInput)

                assertSame(EncodingInputSettlement.Accepted, rejectedInput.encode(jpegQuality = 80))
                enterOne(dispatcher)

                assertSame(EncodingResult.FrameFailed, rejectedResult.get())
                assertEquals(1, bitmapFixture.compressionAttemptCount)
                val rejectedTransaction = checkNotNull(productionFactory.firstTransaction)
                assertEquals(bitmapFixture.partialBytes.size, rejectedTransaction.byteCount)
                assertSame(ManagedEncodedTransaction.State.Aborted, rejectedTransaction.state)
                assertNull(rejectedTransaction.committedPayload)

                val recoveredResult = AtomicReference<EncodingResult?>()
                val recoveredInput = requireInput(owner) { result ->
                    check(recoveredResult.compareAndSet(null, result))
                }
                assertSame(rejectedInput.carrier, recoveredInput.carrier)
                assertSame(rejectedInput.writableView, recoveredInput.writableView)
                fillOpaqueRgba(recoveredInput)
                assertSame(EncodingInputSettlement.Accepted, recoveredInput.encode(jpegQuality = 80))
                enterOne(dispatcher)

                val encoded = recoveredResult.get() as? EncodingResult.Encoded
                    ?: error("Fresh Framework production did not recover with Encoded")
                assertEquals(2, bitmapFixture.compressionAttemptCount)
                assertEquals(11L, encoded.encodeDurationNanos)
                val encodedBytes = encoded.payload.toByteArray()
                assertArrayEquals(bitmapFixture.successfulJpegBytes, encodedBytes)

                retireReadyOwner(owner, dispatcher)
            }
        }
    }

    // Verification: ENC-05
    @Test
    fun retiringBeforeProductionEntryReturnsCutoff() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("cutoff production read the clock") },
            )
            reconcileReady(owner, dispatcher, layout)
            val returned = RecordingProductionPort()
            val input = requireInput(owner, returned)
            assertExactCarrier(input, layout)
            fillOpaqueRgba(input)
            assertSame(EncodingInputSettlement.Accepted, input.encode(jpegQuality = 80))
            returned.assertCallbackFree()

            owner.retire()
            returned.assertCallbackFree()
            assertInputFailedInternal(owner.acquireInput { fail("cutoff owner returned a production result") })
            val successorPort = RecordingReconcilePort()
            assertReconcileRejected(
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, successorPort),
                expectedCause = null,
            )
            successorPort.assertCallbackFree()
            enterOne(dispatcher)

            returned.assertReturnedExactlyOnce(EncodingResult.CutoffInert)
            assertInputFailedInternal(owner.acquireInput { fail("cutoff owner returned a production result") })
            assertReconcileRejected(
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, successorPort),
                expectedCause = null,
            )
            enterOne(dispatcher)
            returned.assertReturnedExactlyOnce(EncodingResult.CutoffInert)
            successorPort.assertCallbackFree()
            assertRetired(owner)
            assertReconcileRejected(
                owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly, successorPort),
                expectedCause = null,
            )
            successorPort.assertCallbackFree()
        }
    }

    // Verification: ENC-05
    @Test
    fun productionDispatchRejectionReusesCarrier() {
        exerciseFrameworkProductionSubmissionFailure(expectedCause = null) { dispatcher ->
            dispatcher.enqueueReject()
        }
    }

    // Verification: ENC-05
    @Test
    fun productionDispatchExceptionPreservesCauseAndReusesCarrier() {
        val failure = IllegalStateException("production dispatch failed")
        exerciseFrameworkProductionSubmissionFailure(expectedCause = failure) { dispatcher ->
            dispatcher.enqueueThrow(failure)
        }
    }

    private fun exerciseFrameworkProductionSubmissionFailure(
        expectedCause: Throwable?,
        arrangeFailure: (ControlledNonInlineDispatcher) -> Unit,
    ) {
        ControlledNonInlineDispatcher().use { dispatcher ->
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val owner = EncodingOwner(
                dispatcher,
                clock = ElapsedRealtimeClock { throw AssertionError("rejected production read the clock") },
            )
            reconcileReady(owner, dispatcher, layout)
            val returnPort = RecordingProductionPort()
            val input = requireInput(owner, returnPort)
            assertExactCarrier(input, layout)
            fillOpaqueRgba(input)
            arrangeFailure(dispatcher)

            val settlement = input.encode(jpegQuality = 80)

            assertProductionFailedInternal(settlement, expectedCause)
            returnPort.assertCallbackFree()
            assertEquals(0, dispatcher.pendingCount())
            val reusable = requireInput(owner, returnPort)
            assertSame(input.carrier, reusable.carrier)
            assertSame(input.writableView, reusable.writableView)
            assertExactCarrier(reusable, layout)
            assertSame(EncodingInputSettlement.Settled, reusable.discard())
            returnPort.assertCallbackFree()
            retireReadyOwner(owner, dispatcher)
            returnPort.assertCallbackFree()
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
            owner.reconcile(layout, JpegBackendPolicy.FrameworkOnly) { result ->
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

    private fun assertExactCarrier(input: EncodingInput, layout: Rgba8888Layout) {
        assertEquals(layout.byteCount, input.byteCount)
        assertTrue(input.writableView.isDirect)
        assertFalse(input.writableView.isReadOnly)
        assertEquals(0, input.writableView.position())
        assertEquals(layout.byteCount, input.writableView.limit())
        assertEquals(layout.byteCount, input.writableView.capacity())
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

    private fun assertRetired(owner: EncodingOwner) {
        val result = owner.acquireInput { fail("retired owner returned a production result") }
        assertInputFailedInternal(result)
    }

    private fun retireReadyOwner(owner: EncodingOwner, dispatcher: ControlledNonInlineDispatcher) {
        owner.retire()
        enterOne(dispatcher)
        assertRetired(owner)
    }

    private fun assertReconcileRejected(submission: EncodingReconcileSubmission, expectedCause: Throwable?) {
        assertTrue(submission is EncodingReconcileSubmission.Rejected)
        assertSame(expectedCause, (submission as EncodingReconcileSubmission.Rejected).cause)
    }

    private fun assertInputFailedInternal(result: EncodingInputResult) {
        assertTrue(result is EncodingInputResult.Failed)
        assertSame(ScreenCaptureProblem.InternalFailure, (result as EncodingInputResult.Failed).problem)
    }

    private fun assertProductionFailedInternal(settlement: EncodingInputSettlement, expectedCause: Throwable?) {
        assertTrue(settlement is EncodingInputSettlement.Failed)
        settlement as EncodingInputSettlement.Failed
        assertSame(ScreenCaptureProblem.InternalFailure, settlement.problem)
        assertSame(expectedCause, settlement.cause)
    }

    private class RecordingReconcilePort : EncodingReconcileReturnPort {
        private val callbackCount = AtomicInteger()
        private val returned = AtomicReference<EncodingReconcileResult?>()

        override fun onReturned(result: EncodingReconcileResult) {
            check(returned.compareAndSet(null, result))
            callbackCount.incrementAndGet()
        }

        fun assertCallbackFree() {
            assertEquals(0, callbackCount.get())
            assertNull(returned.get())
        }

        fun assertReturnedExactlyOnce(expected: EncodingReconcileResult) {
            assertEquals(1, callbackCount.get())
            assertSame(expected, returned.get())
        }

        fun returnedExactlyOnce(): EncodingReconcileResult {
            assertEquals(1, callbackCount.get())
            return checkNotNull(returned.get())
        }
    }

    private class RecordingProductionPort : EncodingProductionReturnPort {
        private val callbackCount = AtomicInteger()
        private val returned = AtomicReference<EncodingResult?>()

        override fun onReturned(result: EncodingResult) {
            check(returned.compareAndSet(null, result))
            callbackCount.incrementAndGet()
        }

        fun assertCallbackFree() {
            assertEquals(0, callbackCount.get())
            assertNull(returned.get())
        }

        fun assertReturnedExactlyOnce(expected: EncodingResult) {
            assertEquals(1, callbackCount.get())
            assertSame(expected, returned.get())
        }

        fun returnedExactlyOnce(): EncodingResult {
            assertEquals(1, callbackCount.get())
            return checkNotNull(returned.get())
        }
    }

    private class TwoSampleClock(
        private val startNanos: Long,
        private val finishNanos: Long,
    ) : ElapsedRealtimeClock {
        private val sampleIndex = AtomicInteger()

        override fun nowNanos(): Long = when (sampleIndex.getAndIncrement()) {
            0 -> startNanos
            1 -> finishNanos
            else -> throw AssertionError("Framework production sampled an unexpected clock value")
        }
    }

    private class ThreeSampleClock(
        private val firstStartNanos: Long,
        private val secondStartNanos: Long,
        private val secondFinishNanos: Long,
    ) : ElapsedRealtimeClock {
        private val sampleIndex = AtomicInteger()

        override fun nowNanos(): Long = when (sampleIndex.getAndIncrement()) {
            0 -> firstStartNanos
            1 -> secondStartNanos
            2 -> secondFinishNanos
            else -> throw AssertionError("Framework productions sampled an unexpected clock value")
        }
    }

    private class RecordingFrameworkProductionFactory : EncodingProductionFactory by DefaultEncodingProductionFactory {
        var firstTransaction: FrameworkEncodedTransaction? = null
            private set

        override fun createFrameworkTransaction(): FrameworkEncodedTransaction = FrameworkEncodedTransaction().also { transaction ->
            if (firstTransaction == null) firstTransaction = transaction
        }
    }

    private object FailFastNativeJpegFacade : NativeJpegFacade {
        override fun resolveAvailability(): NativeJpegProcess.Availability = failNativeAccess("resolveAvailability")

        override fun hasWeakCompressor(): Boolean = failNativeAccess("hasWeakCompressor")

        override fun newResultBlock(): ByteBuffer = failNativeAccess("newResultBlock")

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer = failNativeAccess("allocateCarrier")

        override fun freeCarrier(carrierBuffer: ByteBuffer): Unit = failNativeAccess("freeCarrier")

        override fun compress(
            carrierBuffer: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            quality: Int,
            sink: NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Unit = failNativeAccess("compress")

        private fun failNativeAccess(method: String): Nothing =
            throw AssertionError("FrameworkOnly crossed NativeJpegFacade.$method")
    }
}
