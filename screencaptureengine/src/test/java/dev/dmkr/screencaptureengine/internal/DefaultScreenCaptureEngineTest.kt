package dev.dmkr.screencaptureengine.internal

import android.content.Context
import android.content.ContextWrapper
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureEngines
import dev.dmkr.screencaptureengine.ScreenCaptureOutputState
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSession
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStopReason
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoderProvider
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparationResult
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparer
import dev.dmkr.screencaptureengine.internal.encoding.provider.ProviderPreparationContext
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupFallbackCompletion
import dev.dmkr.screencaptureengine.internal.gl.StartupCleanupScheduler
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCallbackRegistration
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionHandle
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationFailure
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.TestPreparedRenderingPipelineResource
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.TestRenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.testRenderTransformPackage
import dev.dmkr.screencaptureengine.internal.startup.FakeProjectionHandle
import dev.dmkr.screencaptureengine.internal.startup.ScreenCaptureStartupTransaction
import dev.dmkr.screencaptureengine.internal.startup.TargetCreation
import dev.dmkr.screencaptureengine.internal.startup.TestMetricsProvider
import dev.dmkr.screencaptureengine.internal.startup.TestRuntime
import dev.dmkr.screencaptureengine.internal.startup.expectStartException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultScreenCaptureEngineTest {
    @Test
    fun publicFactoryRetainsApplicationContextAndDefersStartupWork() {
        val application = RuntimeEnvironment.getApplication()
        val callerContext = ContextWrapper(application)
        val engine = ScreenCaptureEngines.create(callerContext) as DefaultScreenCaptureEngine

        assertSame(application, engine.applicationContextForTesting)
        assertFalse(engine.isProviderPreparationContextCreatedForTesting)
        val harness = DefaultEngineHarness(context = callerContext)
        assertTrue(harness.runtimes.isEmpty())
        assertTrue(harness.preparers.isEmpty())
        assertTrue(harness.providerContexts.isEmpty())
    }

    @Test
    fun startSessionOrchestratesInitialActiveSession() = runTest {
        val harness = DefaultEngineHarness()

        val session = harness.startSession()
        val running = session.state.value as ScreenCaptureSessionState.Running

        assertTrue(running.output is ScreenCaptureOutputState.Active)
        assertEquals(1, harness.runtimes.single().virtualDisplayCreateCount)
        assertEquals(1, harness.preparers.single().requests.size)
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        session.close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun callerSuppliedMetricsAreObservedPerSession() = runTest {
        val harness = DefaultEngineHarness()
        val firstMetricsProvider = TestMetricsProvider(CaptureMetrics(widthPx = 320, heightPx = 240, densityDpi = 160))
        val secondMetricsProvider = TestMetricsProvider(CaptureMetrics(widthPx = 640, heightPx = 360, densityDpi = 320))

        val firstSession = harness.startSession(metricsProvider = firstMetricsProvider)

        assertEquals(TargetCreation(width = 320, height = 240, densityDpi = 160), harness.runtimes[0].targetOwner.createdTargets.single())

        firstSession.close()
        runCurrent()

        assertEquals(1, firstMetricsProvider.attachmentDisposeCount)

        val secondSession = harness.startSession(metricsProvider = secondMetricsProvider)

        assertEquals(TargetCreation(width = 640, height = 360, densityDpi = 320), harness.runtimes[1].targetOwner.createdTargets.single())

        secondSession.close()
        runCurrent()

        assertEquals(1, secondMetricsProvider.attachmentDisposeCount)
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun secondStartWhileStartupInProgressRejectsBeforeSecondStartupWork() = runTest {
        val harness = DefaultEngineHarness()
        val firstCallbackRegisterEntered = CountDownLatch(1)
        val releaseFirstCallbackRegister = CountDownLatch(1)
        harness.beforeCallbackRegister = {
            firstCallbackRegisterEntered.countDown()
            check(releaseFirstCallbackRegister.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release callback registration."
            }
        }
        val firstSession = async(Dispatchers.Default) { harness.startSession() }
        assertTrue(firstCallbackRegisterEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        try {
            val exception = expectStartException { harness.startSession() }

            assertEquals(ScreenCaptureProblemKind.EngineSessionAlreadyActive, exception.problem.kind)
            assertFalse(exception.requiresFreshProjection)
            assertEquals(1, harness.runtimes.size)
            assertEquals(0, harness.preparers.size)
            assertEquals(0, harness.runtimes.single().events.count { it == "callback.register" })
            assertEquals(0, harness.runtimes.single().virtualDisplayCreateCount)
        } finally {
            releaseFirstCallbackRegister.countDown()
        }

        firstSession.await().close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun providerPreparationContextCloseHookFailsWhileStartupInProgress() = runTest {
        val harness = DefaultEngineHarness()
        val firstPreparationEntered = CompletableDeferred<Unit>()
        val releaseFirstPreparation = CompletableDeferred<Unit>()
        harness.enqueuePreparerConfiguration {
            beforeReturn = {
                firstPreparationEntered.complete(Unit)
                releaseFirstPreparation.await()
            }
        }
        val firstSession = async { harness.startSession() }
        firstPreparationEntered.await()
        val providerContext = harness.providerContexts.single()

        val failure = runCatching {
            harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("no startup or active session"))
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)
        assertSame(providerContext, harness.providerContexts.single())

        releaseFirstPreparation.complete(Unit)
        firstSession.await().close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun startupFailureReleasesSlotForNextStart() = runTest {
        val harness = DefaultEngineHarness()
        harness.enqueuePreparerConfiguration {
            preparationFailure = RenderingPipelinePreparationFailure(
                kind = ScreenCaptureProblemKind.EncoderUnavailable,
                message = "test preparation failure",
            )
        }

        val exception = expectStartException { harness.startSession() }
        val session = harness.startSession()

        assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, exception.problem.kind)
        assertRunningActive(session)

        session.close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun callerCancellationBeforeCommitReleasesSlotForNextStart() = runTest {
        val harness = DefaultEngineHarness()
        val firstPreparationEntered = CompletableDeferred<Unit>()
        harness.enqueuePreparerConfiguration {
            beforeReturn = {
                firstPreparationEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val cancelledStartup = async { harness.startSession() }
        firstPreparationEntered.await()

        cancelledStartup.cancel()
        runCurrent()
        val session = harness.startSession()

        assertTrue(cancelledStartup.isCancelled)
        assertRunningActive(session)

        session.close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    @Test
    fun returnedTerminalSessionReleasesSlotForSequentialStartBeforeHeavyCleanupRuns() = runTest {
        val harness = DefaultEngineHarness(runCleanupSynchronously = false)
        val firstSession = harness.startSession()
        val firstContext = harness.providerContexts.single()

        firstSession.close()
        val firstTerminal = firstSession.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, firstTerminal.reason)
        harness.awaitPendingCleanup("owner stop cleanup scheduled")
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)
        assertEquals(0, harness.preparers.single().preparedResources.single().closeCount)
        assertEquals(0, harness.runtimes.single().virtualDisplayOwner.closeCount)
        assertEquals(0, harness.runtimes.single().targetOwner.closeCount)

        val secondSession = harness.startSession()

        assertRunningActive(secondSession)
        assertSame(firstContext, harness.providerContexts.last())
        assertEquals(2, harness.runtimes.size)
        assertEquals(2, harness.preparers.size)

        secondSession.close()
        runCurrent()
        harness.runScheduledCleanup()
        eventually("provider context idle shutdown after cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun providerContextSurvivesTerminalReleaseUntilCurrentCleanupIsScheduled() = runTest {
        val firstPreparedEncoder = CloseTrackingImageEncoder()
        val secondPreparedEncoder = CloseTrackingImageEncoder()
        val thirdPreparedEncoder = CloseTrackingImageEncoder()
        val harness = DefaultEngineHarness(
            runCleanupSynchronously = false,
            renderingPipelinePreparerFactory = { providerContext ->
                ProviderContextBackedPipelinePreparer(providerContext)
            },
        )
        val firstSession = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider().apply {
                    encoderFactory = { firstPreparedEncoder }
                },
            ),
        )
        val providerContext = harness.providerContexts.single()

        firstSession.close()
        val secondSession = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider().apply {
                    encoderFactory = { secondPreparedEncoder }
                },
            ),
        )

        harness.runScheduledCleanup()
        firstPreparedEncoder.awaitClose("first prepared encoder cleanup while second session is active")
        assertSame(providerContext, harness.providerContexts.last())
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        secondSession.close()

        harness.awaitPendingCleanup("second terminal cleanup scheduled")
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        val thirdSession = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider().apply {
                    encoderFactory = { thirdPreparedEncoder }
                },
            ),
        )

        assertRunningActive(thirdSession)
        assertSame(providerContext, harness.providerContexts.last())

        thirdSession.close()
        harness.runScheduledCleanup()
        secondPreparedEncoder.awaitClose("second prepared encoder cleanup")
        thirdPreparedEncoder.awaitClose("third prepared encoder cleanup")
        eventually("provider context idle shutdown after terminal cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun externalProjectionStopTerminalReleasesSlotForSequentialStartBeforeCleanupRuns() = runTest {
        val harness = DefaultEngineHarness(runCleanupSynchronously = false)
        val firstSession = harness.startSession()

        harness.runtimes.single().callbackRegistration.emitStop()
        eventually("external projection stop terminal") {
            val state = firstSession.state.value
            state is ScreenCaptureSessionState.Stopped &&
                    state.reason == ScreenCaptureStopReason.CaptureEnded &&
                    state.problem?.kind == ScreenCaptureProblemKind.ProjectionInvalidOrStopped
        }
        harness.awaitPendingCleanup("external projection stop cleanup scheduled")
        assertEquals(0, harness.preparers.single().preparedResources.single().closeCount)
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        val secondSession = harness.startSession()

        assertRunningActive(secondSession)
        assertEquals(2, harness.runtimes.size)
        assertEquals(2, harness.preparers.size)

        secondSession.close()
        runCurrent()
        harness.runScheduledCleanup()
        eventually("provider context idle shutdown after cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun runtimeFailureTerminalReleasesSlotForSequentialStart() = runTest {
        val harness = DefaultEngineHarness(runCleanupSynchronously = false)
        val firstSession = harness.startSession()

        harness.runtimes.single().targetOwner.emitRuntimeFrameAvailable()
        eventually("runtime failure terminal") {
            val state = firstSession.state.value
            state is ScreenCaptureSessionState.Failed &&
                    state.problem.kind == ScreenCaptureProblemKind.GlResourceFailure
        }

        val secondSession = harness.startSession()

        assertRunningActive(secondSession)
        assertEquals(2, harness.runtimes.size)
        assertEquals(2, harness.preparers.size)

        secondSession.close()
        runCurrent()
        harness.runScheduledCleanup()
        eventually("provider context idle shutdown after cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun providerPreparationContextIdleShutsDownAfterTerminalCleanupAndIsReplaced() = runTest {
        val harness = DefaultEngineHarness()
        val firstSession = harness.startSession()
        val firstContext = harness.providerContexts.single()

        firstSession.close()
        runCurrent()
        eventually("provider context idle shutdown") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }

        val lateEncoder = CloseTrackingImageEncoder()
        firstContext.closeEncoderAsync(lateEncoder)
        lateEncoder.awaitClose("late encoder close through provider context")

        val secondSession = harness.startSession()

        assertTrue(firstContext !== harness.providerContexts.last())
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        secondSession.close()
        runCurrent()
        eventually("replacement provider context idle shutdown") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun providerContextBackedPreparerClosesPreparedEncoderThroughProviderContext() = runTest {
        val provider = FakeImageEncoderProvider()
        val preparedEncoder = CloseTrackingImageEncoder()
        provider.encoderFactory = { preparedEncoder }
        val harness = DefaultEngineHarness(
            renderingPipelinePreparerFactory = { providerContext ->
                ProviderContextBackedPipelinePreparer(providerContext)
            },
        )

        val session = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )

        assertEquals(1, provider.createRequests.size)
        assertEquals(0, preparedEncoder.closeCount.get())
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        session.close()
        runCurrent()

        preparedEncoder.awaitClose("prepared encoder cleanup owned by ProviderPreparationContext")
        eventually("provider context idle shutdown after prepared encoder cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun cleanupSchedulerSubmissionFailureFallsBackAndAllowsProviderContextReplacement() = runTest {
        val schedulerFailure = RejectedExecutionException("test cleanup scheduler rejection")
        val firstPreparedEncoder = CloseTrackingImageEncoder()
        val firstProvider = FakeImageEncoderProvider().apply {
            encoderFactory = { firstPreparedEncoder }
        }
        val harness = DefaultEngineHarness(
            cleanupSchedulerFailure = schedulerFailure,
            renderingPipelinePreparerFactory = { providerContext ->
                ProviderContextBackedPipelinePreparer(providerContext)
            },
        )
        val firstSession = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(encoderProvider = firstProvider),
        )
        val firstRuntime = harness.runtimes.single()
        val firstContext = harness.providerContexts.single()
        firstRuntime.failOnCleanupFailure = false

        firstSession.close()
        runCurrent()

        assertEquals(listOf(schedulerFailure), firstRuntime.cleanupFailures)
        firstPreparedEncoder.awaitClose("prepared encoder close through fallback cleanup")
        eventually("provider context idle shutdown after scheduler fallback cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }

        val secondPreparedEncoder = CloseTrackingImageEncoder()
        val secondProvider = FakeImageEncoderProvider().apply {
            encoderFactory = { secondPreparedEncoder }
        }
        val secondSession = harness.startSessionOnBackgroundDispatcher(
            initialParameters = ScreenCaptureParameters(encoderProvider = secondProvider),
        )

        assertTrue(firstContext !== harness.providerContexts.last())
        assertRunningActive(secondSession)

        harness.runtimes.last().failOnCleanupFailure = false
        secondSession.close()
        runCurrent()
        secondPreparedEncoder.awaitClose("replacement prepared encoder close through fallback cleanup")
        eventually("replacement provider context idle shutdown after scheduler fallback cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun providerPreparationContextDoesNotIdleShutdownWhileCleanupIsPending() = runTest {
        val harness = DefaultEngineHarness(runCleanupSynchronously = false)
        val session = harness.startSession()
        val firstContext = harness.providerContexts.single()

        session.close()
        harness.awaitPendingCleanup("terminal cleanup scheduled")
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)
        assertSame(firstContext, harness.providerContexts.single())
        val failure = runCatching {
            harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("provider and cleanup work are idle"))
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        harness.runScheduledCleanup()
        eventually("provider context idle shutdown after pending cleanup") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun providerPreparationContextDoesNotIdleShutdownWhileStartupSlotIsActive() = runTest {
        val releaseProviderWorker = CountDownLatch(1)
        val harness = DefaultEngineHarness()
        harness.enqueuePreparerConfiguration {
            beforeReturn = {
                check(releaseProviderWorker.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release provider preparation."
                }
            }
        }
        val startup = async(Dispatchers.Default) { harness.startSession() }
        eventually("provider context exists during pending provider work") {
            harness.engine.isProviderPreparationContextCreatedForTesting
        }

        val failure = runCatching {
            harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("no startup or active session"))
        assertTrue(harness.engine.isProviderPreparationContextCreatedForTesting)

        releaseProviderWorker.countDown()
        startup.await().close()
        runCurrent()
        eventually("provider context idle shutdown after provider work") {
            !harness.engine.isProviderPreparationContextCreatedForTesting
        }
    }

    @Test
    fun setParametersRejectsWithUnavailableProblemAndDoesNotMutateState() = runTest {
        val harness = DefaultEngineHarness()
        val session = harness.startSession()
        val stateBeforeUpdate = session.state.value
        val prepareCountBeforeUpdate = harness.preparers.single().requests.size

        val rejected = session.setParameters(
            ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5)),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
        assertEquals("Runtime parameter updates are not available for this engine session.", rejected.problem.message)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(prepareCountBeforeUpdate, harness.preparers.single().requests.size)

        session.close()
        runCurrent()
        harness.engine.closeProviderPreparationContextIfNoSessionSlotForTesting()
    }

    private fun assertRunningActive(session: ScreenCaptureSession) {
        val running = session.state.value as ScreenCaptureSessionState.Running
        assertTrue(running.output is ScreenCaptureOutputState.Active)
    }

    private fun eventually(description: String, timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadlineNanos) {
            if (condition()) return
            Thread.sleep(10L)
        }
        if (!condition()) {
            throw AssertionError("$description was not observed within ${timeoutMillis}ms")
        }
    }
}

private class DefaultEngineHarness(
    private val apiLevel: Int = 33,
    private val runCleanupSynchronously: Boolean = true,
    private val cleanupSchedulerFailure: Throwable? = null,
    context: Context = RuntimeEnvironment.getApplication(),
    private val renderingPipelinePreparerFactory: ((ProviderPreparationContext) -> RenderingPipelinePreparer)? = null,
) {
    val runtimes = mutableListOf<TestRuntime>()
    val preparers = mutableListOf<TestRenderingPipelinePreparer>()
    val providerContexts = mutableListOf<ProviderPreparationContext>()
    var beforeCallbackRegister: (() -> Unit)? = null
    private val preparerConfigurations = ArrayDeque<TestRenderingPipelinePreparer.() -> Unit>()
    private val cleanupLock = ReentrantLock()
    private val cleanupScheduled = cleanupLock.newCondition()
    private val scheduledCleanupBlocks = ArrayDeque<() -> Unit>()
    private var elapsedRealtimeNanos = 0L

    val engine = DefaultScreenCaptureEngine(
        context = context,
        startupCleanupScheduler = { block ->
            cleanupSchedulerFailure?.let { throw it }
            if (runCleanupSynchronously) {
                block()
            } else {
                cleanupLock.withLock {
                    scheduledCleanupBlocks += block
                    cleanupScheduled.signalAll()
                }
            }
        },
        startupTransactionFactory = {
            TestRuntime(apiLevel = apiLevel).also(runtimes::add).toStartupTransaction(
                apiLevel = apiLevel,
                beforeCallbackRegister = { beforeCallbackRegister?.invoke() },
                cleanupScheduler = it,
            )
        },
        renderingPipelinePreparerFactory = { providerContext ->
            providerContexts += providerContext
            renderingPipelinePreparerFactory?.invoke(providerContext)
                ?: TestRenderingPipelinePreparer().also { preparer ->
                    preparerConfigurations.removeFirstOrNull()?.invoke(preparer)
                    preparers += preparer
                }
        },
        elapsedRealtimeNanos = {
            elapsedRealtimeNanos += 1_000_000L
            elapsedRealtimeNanos
        },
    )

    fun enqueuePreparerConfiguration(configuration: TestRenderingPipelinePreparer.() -> Unit) {
        preparerConfigurations += configuration
    }

    fun awaitPendingCleanup(description: String) {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        cleanupLock.withLock {
            while (scheduledCleanupBlocks.isEmpty()) {
                if (remainingNanos <= 0L) {
                    throw AssertionError("$description was not observed within ${TIMEOUT_MILLIS}ms")
                }
                remainingNanos = cleanupScheduled.awaitNanos(remainingNanos)
            }
        }
    }

    fun runScheduledCleanup() {
        while (true) {
            val block = cleanupLock.withLock {
                scheduledCleanupBlocks.removeFirstOrNull()
            } ?: return
            block()
        }
    }

    suspend fun startSession(
        metricsProvider: TestMetricsProvider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440)),
        projection: ProjectionHandle = FakeProjectionHandle(),
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): ScreenCaptureSession =
        engine.startSession(
            config = ScreenCaptureConfig(metricsProvider = metricsProvider),
            projection = projection,
            initialParameters = initialParameters,
        )

    suspend fun startSessionOnBackgroundDispatcher(
        metricsProvider: TestMetricsProvider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440)),
        projection: ProjectionHandle = FakeProjectionHandle(),
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): ScreenCaptureSession =
        withContext(Dispatchers.Default) {
            startSession(
                metricsProvider = metricsProvider,
                projection = projection,
                initialParameters = initialParameters,
            )
        }
}

private class ProviderContextBackedPipelinePreparer(
    providerContext: ProviderPreparationContext,
) : RenderingPipelinePreparer {
    private val encoderPreparer = ImageEncoderPreparer(providerContext)

    override suspend fun prepareInitialRenderingPipeline(
        request: RenderingPipelinePrepareRequest,
    ): RenderingPipelinePreparationResult =
        when (
            val encoderResult = encoderPreparer.prepare(
                token = request.planPreparationToken,
                provider = request.encoderProvider,
                request = request.outputPlan.encoderRequest,
            )
        ) {
            is ImageEncoderPreparationResult.Failure -> RenderingPipelinePreparationResult.Failure(
                RenderingPipelinePreparationFailure(
                    kind = encoderResult.kind,
                    message = encoderResult.message,
                    cause = encoderResult.cause,
                ),
            )

            is ImageEncoderPreparationResult.Success -> RenderingPipelinePreparationResult.Success(
                PreparedRenderingPipelineComponents(
                    readbackResources = TestPreparedRenderingPipelineResource(),
                    renderTransformPackage = testRenderTransformPackage(request),
                    encoderResources = encoderResult.preparedEncoder,
                ),
            )
        }
}

private class CloseTrackingImageEncoder(
    override val info: ImageEncoderInfo = ImageEncoderInfo(
        providerId = "fake-provider",
        outputFormat = EncodedImageFormats.Jpeg,
        backendName = "close-tracking",
    ),
) : ImageEncoder {
    val closeCount = AtomicInteger(0)
    private val closed = CountDownLatch(1)

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult =
        ImageEncodeResult.Success

    override fun close() {
        closeCount.incrementAndGet()
        closed.countDown()
    }

    fun awaitClose(description: String) {
        assertTrue(description, closed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals(description, 1, closeCount.get())
    }
}

private fun TestRuntime.toStartupTransaction(
    apiLevel: Int,
    beforeCallbackRegister: (() -> Unit)? = null,
    cleanupScheduler: StartupCleanupScheduler = StartupCleanupScheduler { block -> block() },
): ScreenCaptureStartupTransaction {
    val delegatedFallbackCompletions = AtomicInteger(0)
    val forwardingCleanupScheduler = object : StartupCleanupScheduler, StartupCleanupFallbackCompletion {
        override fun schedule(block: () -> Unit) {
            cleanupSchedulerFailure?.let { throw it }
            try {
                cleanupScheduler.schedule(block)
            } catch (cause: Throwable) {
                delegatedFallbackCompletions.incrementAndGet()
                throw cause
            }
        }

        override fun onStartupCleanupFallbackComplete() {
            if (delegatedFallbackCompletions.getAndUpdate { count -> if (count > 0) count - 1 else count } <= 0) return
            (cleanupScheduler as? StartupCleanupFallbackCompletion)?.onStartupCleanupFallbackComplete()
        }
    }
    return ScreenCaptureStartupTransaction(
        apiLevel = apiLevel,
        callbackAdapterFactory = { listener, synchronousEventObserver ->
            callbackRegistration.listener = listener
            callbackRegistration.synchronousEventObserver = synchronousEventObserver
            if (beforeCallbackRegister == null) {
                callbackRegistration
            } else {
                object : ProjectionCallbackRegistration by callbackRegistration {
                    override fun register(projection: ProjectionHandle) {
                        beforeCallbackRegister()
                        callbackRegistration.register(projection)
                    }
                }
            }
        },
        projectionTargetOwnerFactory = {
            targetOwner
        },
        virtualDisplayFactory = { _, _, target, _ ->
            events += "virtualDisplay.create"
            virtualDisplayCreateCount++
            virtualDisplayCreateFailure?.let { throw it }
            virtualDisplayOwner.bindTarget(target)
            virtualDisplayOwner
        },
        cleanupScheduler = forwardingCleanupScheduler,
        cleanupFailureSink = {
            cleanupFailures += it
            if (failOnCleanupFailure) throw AssertionError("Unexpected cleanup failure", it)
        },
    )
}

private const val TIMEOUT_MILLIS = 2_000L
