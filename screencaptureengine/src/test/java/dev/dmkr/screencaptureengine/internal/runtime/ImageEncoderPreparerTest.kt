package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ImageEncoderUnavailableException
import dev.dmkr.screencaptureengine.JpegImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

class ImageEncoderPreparerTest {
    @Test
    fun prepareReturnsValidatedEncoderAndDoesNotCallEncode() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        val providerContext = ProviderPreparationContext()
        val encoder = CloseTrackingImageEncoder()
        val provider = SimpleImageEncoderProvider().apply {
            encoderFactory = { encoder }
        }

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Success)
            val success = result as ImageEncoderPreparationResult.Success
            assertSame(encoder, success.preparedEncoder.encoder)
            assertEquals(encoder.info, success.preparedEncoder.info)
            assertEquals(testEncoderRequest(), success.preparedEncoder.request)
            assertEquals(0, encoder.encodeCount.get())
            success.preparedEncoder.close()
            success.preparedEncoder.close()
            assertTrue(encoder.awaitClose())
            assertEquals(1, encoder.closeCount.get())
            val closeThreadName = encoder.closeThreadNames.single()
            assertNotEquals(callerThreadName, closeThreadName)
            assertTrue(closeThreadName.contains("provider-cleanup"))
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun providerUnavailableAndProviderThrowMapToEncoderUnavailable() = runBlocking {
        val failures = listOf(
            ImageEncoderUnavailableException("unavailable"),
            IllegalStateException("boom"),
        )

        failures.forEach { providerFailure ->
            val providerContext = ProviderPreparationContext()
            val provider = SimpleImageEncoderProvider().apply {
                createFailure = providerFailure
            }

            try {
                val result = ImageEncoderPreparer(providerContext).prepare(
                    token = providerContext.newToken(),
                    provider = provider,
                    request = testEncoderRequest(),
                )

                assertTrue(result is ImageEncoderPreparationResult.Failure)
                val failure = result as ImageEncoderPreparationResult.Failure
                assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
                assertSame(providerFailure, failure.cause)
            } finally {
                providerContext.close()
            }
        }
    }

    @Test
    fun builtInJpegImpossibleRawSpanMapsToEncoderUnavailable() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val request = jpegRequest(
            width = 1,
            height = 2,
            rowStrideBytes = Int.MAX_VALUE,
        )

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = JpegImageEncoderProvider(),
                request = request,
            )

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.cause is ImageEncoderUnavailableException)
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun unmarkedAllocationLikeProviderFailuresMapToEncoderUnavailable() = runBlocking {
        val failures = listOf(
            OutOfMemoryError("ordinary provider allocation"),
            ImageEncoderUnavailableException(
                message = "ordinary unavailable",
                cause = OutOfMemoryError("ordinary provider allocation"),
            ),
        )

        failures.forEach { providerFailure ->
            val providerContext = ProviderPreparationContext()
            val provider = SimpleImageEncoderProvider().apply {
                createFailure = providerFailure
            }

            try {
                val result = ImageEncoderPreparer(providerContext).prepare(
                    token = providerContext.newToken(),
                    provider = provider,
                    request = testEncoderRequest(),
                )

                assertTrue(result is ImageEncoderPreparationResult.Failure)
                val failure = result as ImageEncoderPreparationResult.Failure
                assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
                assertSame(providerFailure, failure.cause)
            } finally {
                providerContext.close()
            }
        }
    }

    @Test
    fun invalidReturnedEncoderIsClosedOnProviderCleanupAndMapsToValidationFailed() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val invalidEncoder = CloseTrackingImageEncoder(
            info = ImageEncoderInfo(
                providerId = "different-provider",
                outputFormat = EncodedImageFormats.Jpeg,
                backendName = "fake",
            ),
        )
        val provider = SimpleImageEncoderProvider().apply {
            encoderFactory = { invalidEncoder }
        }

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderValidationFailed, failure.kind)
            assertTrue(invalidEncoder.awaitClose())
            assertEquals(1, invalidEncoder.closeCount.get())
            assertEquals(0, invalidEncoder.encodeCount.get())
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun encoderInfoValidationRunsOnIsolatedProviderWorkerNotCallerThread() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        val providerContext = ProviderPreparationContext()
        val encoder = InfoThreadTrackingImageEncoder()
        val provider = SimpleImageEncoderProvider().apply {
            encoderFactory = { encoder }
        }

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Success)
            val infoThreadName = encoder.infoThreadNames.single()
            assertNotEquals(callerThreadName, infoThreadName)
            assertTrue(infoThreadName.contains("provider-prep"))
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun timeoutCancelsBestEffortAndDoesNotWaitForBlockingProviderWorker() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )

        try {
            val elapsedMs = measureTimeMillis {
                val result = ImageEncoderPreparer(providerContext, timeoutMs = 50L).prepare(
                    token = providerContext.newToken(),
                    provider = provider,
                    request = testEncoderRequest(),
                )

                assertTrue(result is ImageEncoderPreparationResult.Failure)
                assertEquals(
                    ScreenCaptureProblemKind.EncoderUnavailable,
                    (result as ImageEncoderPreparationResult.Failure).kind,
                )
            }

            assertTrue(provider.awaitStarted())
            assertTrue("prepare waited for blocked provider worker for $elapsedMs ms", elapsedMs < 1_000L)
            assertTrue(provider.wasInterrupted.get())
        } finally {
            releaseProvider.countDown()
            encoder.awaitClose()
            providerContext.close()
        }
    }

    @Test
    fun tokenCancelReturnsWithoutWaitingForBlockingProviderWorker() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            val elapsedMs = measureTimeMillis {
                token.cancel()
                val result = pending.await()

                assertTrue(result is ImageEncoderPreparationResult.Failure)
                assertEquals(
                    ScreenCaptureProblemKind.EncoderUnavailable,
                    (result as ImageEncoderPreparationResult.Failure).kind,
                )
            }

            assertTrue("token cancel waited for blocked provider worker for $elapsedMs ms", elapsedMs < 1_000L)
            assertTrue(provider.wasInterrupted.get())
        } finally {
            releaseProvider.countDown()
            encoder.awaitClose()
            providerContext.close()
        }
    }

    @Test
    fun planPreparationTokenCancelReturnsWithoutWaitingForBlockingProviderWorker() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )
        val token = PlanPreparationToken(
            ownerToken = Any(),
            planToken = 1L,
            projectionTargetGeneration = 1L,
        )

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            val elapsedMs = measureTimeMillis {
                token.invalidate()
                val result = pending.await()

                assertTrue(result is ImageEncoderPreparationResult.Failure)
                assertEquals(
                    ScreenCaptureProblemKind.EncoderUnavailable,
                    (result as ImageEncoderPreparationResult.Failure).kind,
                )
            }

            assertTrue("token cancel waited for blocked provider worker for $elapsedMs ms", elapsedMs < 1_000L)
            assertTrue(provider.wasInterrupted.get())
        } finally {
            releaseProvider.countDown()
            encoder.awaitClose()
            providerContext.close()
        }
    }

    @Test
    fun contextCloseReturnsAndLateSuccessStillClosesOnProviderCleanup() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )

        val pending = async(Dispatchers.Default) {
            ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )
        }
        assertTrue(provider.awaitStarted())

        val elapsedMs = measureTimeMillis {
            providerContext.close()
            assertTrue(pending.await() is ImageEncoderPreparationResult.Failure)
        }
        releaseProvider.countDown()

        assertTrue("context close waited for blocked provider worker for $elapsedMs ms", elapsedMs < 1_000L)
        assertTrue(encoder.awaitClose())
        assertEquals(1, encoder.closeCount.get())
    }

    @Test
    fun contextCloseFencesCompletedSuccessBeforeCallerClaimAndClosesOnce() = runBlocking {
        val beforeClaimEntered = CountDownLatch(1)
        val allowClaimToContinue = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            beforeClaim = {
                beforeClaimEntered.countDown()
                allowClaimToContinue.await()
            },
        )
        val encoder = CloseTrackingImageEncoder()
        val provider = SimpleImageEncoderProvider().apply {
            encoderFactory = { encoder }
        }

        val pending = async(Dispatchers.Default) {
            ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )
        }
        assertTrue(beforeClaimEntered.await(1, TimeUnit.SECONDS))

        providerContext.close()
        allowClaimToContinue.countDown()

        val result = pending.await()
        assertTrue(result is ImageEncoderPreparationResult.Failure)
        assertTrue(encoder.awaitClose())
        assertEquals(1, encoder.closeCount.get())
    }

    @Test
    fun staleSuccessAfterCancelCompletesPromptlyAndClosesEncoderOnce() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val result = withTimeout(1_000L) { pending.await() }

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("stale"))
            assertTrue(encoder.awaitClose())
            assertEquals(1, encoder.closeCount.get())
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun staleProviderFailureAfterCancelCompletesPromptly() = runBlocking {
        val providerFailure = IllegalStateException("provider failed after stale")
        val diagnostic = AtomicReference<Throwable?>()
        val diagnosticLatch = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            lateFailureDiagnostics = {
                diagnostic.set(it)
                diagnosticLatch.countDown()
            },
        )
        val releaseProvider = CountDownLatch(1)
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Failure(providerFailure),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val result = withTimeout(1_000L) { pending.await() }

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("stale"))
            assertTrue(diagnosticLatch.await(1, TimeUnit.SECONDS))
            assertSame(providerFailure, diagnostic.get())
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun staleProviderFailureCompletesPromptlyWhenDiagnosticsThrows() = runBlocking {
        val providerFailure = IllegalStateException("provider failed after stale")
        val diagnosticCount = AtomicInteger()
        val diagnosticLatch = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            lateFailureDiagnostics = {
                diagnosticCount.incrementAndGet()
                diagnosticLatch.countDown()
                throw IllegalStateException("diagnostics failed")
            },
        )
        val releaseProvider = CountDownLatch(1)
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Failure(providerFailure),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val result = withTimeout(1_000L) { pending.await() }

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("stale"))
            assertTrue(diagnosticLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, diagnosticCount.get())
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun blockingLateFailureDiagnosticsDoesNotPinProviderAdmissionSlot() = runBlocking {
        val providerFailure = IllegalStateException("provider failed after stale")
        val diagnosticEntered = CountDownLatch(1)
        val releaseDiagnostic = CountDownLatch(1)
        val diagnosticThread = AtomicReference<Thread?>()
        val providerContext = ProviderPreparationContext(
            maxProviderWorkers = 1,
            lateFailureDiagnostics = {
                diagnosticThread.set(Thread.currentThread())
                diagnosticEntered.countDown()
                releaseDiagnostic.await()
            },
        )
        val releaseProvider = CountDownLatch(1)
        val firstProvider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Failure(providerFailure),
        )
        val token = providerContext.newToken()

        try {
            val first = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = firstProvider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(firstProvider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val staleResult = withTimeout(1_000L) { first.await() }
            assertTrue(staleResult is ImageEncoderPreparationResult.Failure)
            assertTrue(diagnosticEntered.await(1, TimeUnit.SECONDS))
            val providerThread = firstProvider.createThread.get()
            assertTrue(providerThread != null)
            providerThread?.join(1_000L)
            assertTrue(providerThread?.isAlive == false)
            assertNotEquals(providerThread, diagnosticThread.get())

            val secondResult = withTimeout(1_000L) {
                ImageEncoderPreparer(providerContext, timeoutMs = 1_000L).prepare(
                    token = providerContext.newToken(),
                    provider = SimpleImageEncoderProvider(),
                    request = testEncoderRequest(),
                )
            }

            assertTrue(secondResult is ImageEncoderPreparationResult.Success)
            (secondResult as ImageEncoderPreparationResult.Success).preparedEncoder.close()
        } finally {
            releaseProvider.countDown()
            releaseDiagnostic.countDown()
            providerContext.close()
        }
    }

    @Test
    fun staleValidationFailureAfterCancelCompletesPromptlyAndClosesEncoderOnce() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val invalidEncoder = CloseTrackingImageEncoder(
            info = ImageEncoderInfo(
                providerId = "different-provider",
                outputFormat = EncodedImageFormats.Jpeg,
                backendName = "fake",
            ),
        )
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(invalidEncoder),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val result = withTimeout(1_000L) { pending.await() }

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("stale"))
            assertTrue(invalidEncoder.awaitClose())
            assertEquals(1, invalidEncoder.closeCount.get())
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun staleValidationFailureCompletesPromptlyWhenDiagnosticsThrowsAndClosesEncoderOnce() = runBlocking {
        val infoFailure = IllegalStateException("info failed after stale")
        val diagnosticCount = AtomicInteger()
        val diagnosticLatch = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            lateFailureDiagnostics = {
                diagnosticCount.incrementAndGet()
                diagnosticLatch.countDown()
                throw IllegalStateException("diagnostics failed")
            },
        )
        val releaseProvider = CountDownLatch(1)
        val invalidEncoder = ThrowingInfoImageEncoder(infoFailure)
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(invalidEncoder),
        )
        val token = providerContext.newToken()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(provider.awaitStarted())

            token.cancelForRaceTest()
            releaseProvider.countDown()

            val result = withTimeout(1_000L) { pending.await() }

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("stale"))
            assertTrue(invalidEncoder.awaitClose())
            assertEquals(1, invalidEncoder.closeCount.get())
            assertTrue(diagnosticLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, diagnosticCount.get())
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun cancelBeforeWorkerSetsStartedKeepsAdmissionSlotUntilWorkerReturns() = runBlocking {
        val beforeWorkerStartEntered = CountDownLatch(1)
        val allowWorkerToContinue = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            maxProviderWorkers = 1,
            beforeProviderWorkerStart = {
                beforeWorkerStartEntered.countDown()
                while (true) {
                    try {
                        allowWorkerToContinue.await()
                        break
                    } catch (_: InterruptedException) {
                    }
                }
            },
        )
        val token = providerContext.newToken()
        val encoder = CloseTrackingImageEncoder()
        val provider = SimpleImageEncoderProvider().apply {
            encoderFactory = { encoder }
        }

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = provider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(beforeWorkerStartEntered.await(1, TimeUnit.SECONDS))

            token.cancel()
            assertTrue(pending.await() is ImageEncoderPreparationResult.Failure)

            val rejected = ImageEncoderPreparer(providerContext, timeoutMs = 50L).prepare(
                token = providerContext.newToken(),
                provider = SimpleImageEncoderProvider(),
                request = testEncoderRequest(),
            )

            assertTrue(rejected is ImageEncoderPreparationResult.Failure)
            val failure = rejected as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("admission exhausted"))

            allowWorkerToContinue.countDown()
            assertTrue(encoder.awaitClose())
        } finally {
            allowWorkerToContinue.countDown()
            providerContext.close()
        }
    }

    @Test
    fun cancelBeforeFutureTaskRunsReleasesAdmissionSlot() = runBlocking {
        val firstWorkerCreated = CountDownLatch(1)
        val allowFirstWorkerToRun = CountDownLatch(1)
        val shouldBlockWorker = AtomicBoolean(true)
        val workerThreadFactory = ThreadFactory { runnable ->
            Thread(
                {
                    if (shouldBlockWorker.getAndSet(false)) {
                        firstWorkerCreated.countDown()
                        allowFirstWorkerToRun.await()
                    }
                    runnable.run()
                },
                "test-provider-prep-worker",
            ).apply { isDaemon = true }
        }
        val providerContext = ProviderPreparationContext(
            maxProviderWorkers = 1,
            workerThreadFactory = workerThreadFactory,
        )
        val token = providerContext.newToken()
        val firstProvider = SimpleImageEncoderProvider()

        try {
            val pending = async(Dispatchers.Default) {
                ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                    token = token,
                    provider = firstProvider,
                    request = testEncoderRequest(),
                )
            }
            assertTrue(firstWorkerCreated.await(1, TimeUnit.SECONDS))

            token.cancel()
            assertTrue(pending.await() is ImageEncoderPreparationResult.Failure)

            val second = ImageEncoderPreparer(providerContext, timeoutMs = 1_000L).prepare(
                token = providerContext.newToken(),
                provider = SimpleImageEncoderProvider(),
                request = testEncoderRequest(),
            )

            assertTrue(second is ImageEncoderPreparationResult.Success)
            assertEquals(emptyList<ImageEncoderRequest>(), firstProvider.createRequests)
        } finally {
            allowFirstWorkerToRun.countDown()
            providerContext.close()
        }
    }

    @Test
    fun cancelAfterTaskMarkedSubmittedBeforeFutureStoredReleasesAdmissionSlotWhenTaskNeverRuns() = runBlocking {
        val firstWorkerCreated = CountDownLatch(1)
        val allowFirstWorkerToRun = CountDownLatch(1)
        val shouldBlockWorker = AtomicBoolean(true)
        val tokenReference = AtomicReference<ProviderPreparationToken>()
        val workerThreadFactory = ThreadFactory { runnable ->
            Thread(
                {
                    if (shouldBlockWorker.getAndSet(false)) {
                        firstWorkerCreated.countDown()
                        allowFirstWorkerToRun.await()
                    }
                    runnable.run()
                },
                "test-provider-prep-worker",
            ).apply { isDaemon = true }
        }
        val providerContext = ProviderPreparationContext(
            maxProviderWorkers = 1,
            workerThreadFactory = workerThreadFactory,
            beforeExecutorExecute = {
                tokenReference.get().cancel()
            },
        )
        val token = providerContext.newToken()
        tokenReference.set(token)
        val firstProvider = SimpleImageEncoderProvider()

        try {
            val first = ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                token = token,
                provider = firstProvider,
                request = testEncoderRequest(),
            )
            assertTrue(first is ImageEncoderPreparationResult.Failure)
            shouldBlockWorker.set(false)

            val second = ImageEncoderPreparer(providerContext, timeoutMs = 1_000L).prepare(
                token = providerContext.newToken(),
                provider = SimpleImageEncoderProvider(),
                request = testEncoderRequest(),
            )

            assertTrue(second is ImageEncoderPreparationResult.Success)
            assertEquals(emptyList<ImageEncoderRequest>(), firstProvider.createRequests)
        } finally {
            allowFirstWorkerToRun.countDown()
            providerContext.close()
        }
    }

    @Test
    fun lateSuccessAfterTimeoutIsClosedOnProviderCleanup() = runBlocking {
        val providerContext = ProviderPreparationContext()
        val releaseProvider = CountDownLatch(1)
        val encoder = CloseTrackingImageEncoder()
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(encoder),
        )

        try {
            val result = ImageEncoderPreparer(providerContext, timeoutMs = 50L).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            releaseProvider.countDown()

            assertTrue(encoder.awaitClose())
            assertEquals(1, encoder.closeCount.get())
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun lateFailureAfterTimeoutIsDiagnosticOnly() = runBlocking {
        val lateFailure = IllegalStateException("late")
        val diagnostic = AtomicReference<Throwable?>()
        val diagnosticLatch = CountDownLatch(1)
        val providerContext = ProviderPreparationContext(
            lateFailureDiagnostics = {
                diagnostic.set(it)
                diagnosticLatch.countDown()
            },
        )
        val releaseProvider = CountDownLatch(1)
        val provider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Failure(lateFailure),
        )

        try {
            val result = ImageEncoderPreparer(providerContext, timeoutMs = 50L).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Failure)
            val failure = result as ImageEncoderPreparationResult.Failure
            assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
            assertTrue(failure.message.contains("timed out"))

            releaseProvider.countDown()

            assertTrue(diagnosticLatch.await(1, TimeUnit.SECONDS))
            assertSame(lateFailure, diagnostic.get())
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun admissionExhaustionMapsToEncoderUnavailable() = runBlocking {
        val providerContext = ProviderPreparationContext(maxProviderWorkers = 2)
        val releaseProvider = CountDownLatch(1)
        val firstProvider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(CloseTrackingImageEncoder()),
        )
        val secondProvider = BlockingImageEncoderProvider(
            releaseProvider = releaseProvider,
            result = BlockingProviderResult.Success(CloseTrackingImageEncoder()),
        )

        try {
            coroutineScope {
                val first = async(Dispatchers.Default) {
                    ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                        token = providerContext.newToken(),
                        provider = firstProvider,
                        request = testEncoderRequest(),
                    )
                }
                val second = async(Dispatchers.Default) {
                    ImageEncoderPreparer(providerContext, timeoutMs = 5_000L).prepare(
                        token = providerContext.newToken(),
                        provider = secondProvider,
                        request = testEncoderRequest(),
                    )
                }

                assertTrue(firstProvider.awaitStarted())
                assertTrue(secondProvider.awaitStarted())

                val rejected = ImageEncoderPreparer(providerContext, timeoutMs = 50L).prepare(
                    token = providerContext.newToken(),
                    provider = SimpleImageEncoderProvider(),
                    request = testEncoderRequest(),
                )

                assertTrue(rejected is ImageEncoderPreparationResult.Failure)
                val failure = rejected as ImageEncoderPreparationResult.Failure
                assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, failure.kind)
                assertTrue(failure.message.contains("admission exhausted"))

                releaseProvider.countDown()

                assertTrue(first.await() is ImageEncoderPreparationResult.Success)
                assertTrue(second.await() is ImageEncoderPreparationResult.Success)
            }
        } finally {
            releaseProvider.countDown()
            providerContext.close()
        }
    }

    @Test
    fun providerCreateRunsOnIsolatedProviderWorkerNotCallerThread() = runBlocking {
        val callerThreadName = Thread.currentThread().name
        val providerContext = ProviderPreparationContext()
        val provider = SimpleImageEncoderProvider()

        try {
            val result = ImageEncoderPreparer(providerContext).prepare(
                token = providerContext.newToken(),
                provider = provider,
                request = testEncoderRequest(),
            )

            assertTrue(result is ImageEncoderPreparationResult.Success)
            val workerThreadName = provider.createThreadNames.single()
            assertNotEquals(callerThreadName, workerThreadName)
            assertTrue(workerThreadName.contains("provider-prep"))
        } finally {
            providerContext.close()
        }
    }

    private fun testEncoderRequest(): ImageEncoderRequest =
        ImageEncoderRequest(
            width = 16,
            height = 8,
            rowStrideBytes = 64,
            maxEncodedBytes = 1_024,
            inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
        )

    private fun jpegRequest(
        width: Int,
        height: Int,
        rowStrideBytes: Int,
    ): ImageEncoderRequest =
        ImageEncoderRequest(
            width = width,
            height = height,
            rowStrideBytes = rowStrideBytes,
            maxEncodedBytes = 1_024,
            inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
        )

    private fun ProviderPreparationToken.cancelForRaceTest() {
        cancel()
    }
}

private class CloseTrackingImageEncoder(
    override val info: ImageEncoderInfo = ImageEncoderInfo(
        providerId = "fake-provider",
        outputFormat = EncodedImageFormats.Jpeg,
        backendName = "fake",
    ),
) : ImageEncoder {
    val closeCount = AtomicInteger(0)
    val encodeCount = AtomicInteger(0)
    val closeThreadNames = mutableListOf<String>()
    private val closeLatch = CountDownLatch(1)

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult {
        encodeCount.incrementAndGet()
        return ImageEncodeResult.Success
    }

    override fun close() {
        closeThreadNames += Thread.currentThread().name
        closeCount.incrementAndGet()
        closeLatch.countDown()
    }

    fun awaitClose(): Boolean = closeLatch.await(1, TimeUnit.SECONDS)
}

private class InfoThreadTrackingImageEncoder : ImageEncoder {
    val infoThreadNames = mutableListOf<String>()

    override val info: ImageEncoderInfo
        get() {
            infoThreadNames += Thread.currentThread().name
            return ImageEncoderInfo(
                providerId = "fake-provider",
                outputFormat = EncodedImageFormats.Jpeg,
                backendName = "fake",
            )
        }

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult =
        ImageEncodeResult.Success

    override fun close() = Unit
}

private class ThrowingInfoImageEncoder(
    private val failure: Throwable,
) : ImageEncoder {
    val closeCount = AtomicInteger(0)
    private val closeLatch = CountDownLatch(1)

    override val info: ImageEncoderInfo
        get() = throw failure

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult =
        ImageEncodeResult.Success

    override fun close() {
        closeCount.incrementAndGet()
        closeLatch.countDown()
    }

    fun awaitClose(): Boolean = closeLatch.await(1, TimeUnit.SECONDS)
}

private class SimpleImageEncoderProvider : ImageEncoderProvider {
    override val id: String = "fake-provider"
    override val outputFormat = EncodedImageFormats.Jpeg
    val createRequests = mutableListOf<ImageEncoderRequest>()
    val createThreadNames = mutableListOf<String>()
    var createFailure: Throwable? = null
    var encoderFactory: (ImageEncoderRequest) -> ImageEncoder = { CloseTrackingImageEncoder() }

    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder {
        createRequests += request
        createThreadNames += Thread.currentThread().name
        createFailure?.let { throw it }
        return encoderFactory(request)
    }
}

private class BlockingImageEncoderProvider(
    private val releaseProvider: CountDownLatch,
    private val result: BlockingProviderResult,
) : ImageEncoderProvider {
    override val id: String = "fake-provider"
    override val outputFormat = EncodedImageFormats.Jpeg
    val wasInterrupted = AtomicBoolean(false)
    val createThread = AtomicReference<Thread?>()
    private val started = CountDownLatch(1)

    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder {
        createThread.set(Thread.currentThread())
        started.countDown()
        while (true) {
            try {
                releaseProvider.await()
                break
            } catch (_: InterruptedException) {
                wasInterrupted.set(true)
            }
        }
        return when (result) {
            is BlockingProviderResult.Success -> result.encoder
            is BlockingProviderResult.Failure -> throw result.throwable
        }
    }

    fun awaitStarted(): Boolean = started.await(1, TimeUnit.SECONDS)
}

private sealed class BlockingProviderResult private constructor() {
    class Success(val encoder: ImageEncoder) : BlockingProviderResult()

    class Failure(val throwable: Throwable) : BlockingProviderResult()
}
