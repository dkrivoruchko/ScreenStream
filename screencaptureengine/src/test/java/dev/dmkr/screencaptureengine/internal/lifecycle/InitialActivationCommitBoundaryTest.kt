package dev.dmkr.screencaptureengine.internal.lifecycle

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureEventType
import dev.dmkr.screencaptureengine.ScreenCaptureOutputState
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSession
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStopReason
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoder
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoderProvider
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparationResult
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparer
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImmediateProviderEncoderCleanup
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.encoding.provider.ProviderPreparationContext
import dev.dmkr.screencaptureengine.internal.gl.RuntimeGles20Api
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2DynamicOesMatrixUniformSlot
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2OesMatrixCompositionRule
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2RenderingProgramAttributeLocations
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2RenderingProgramBindingMetadata
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2RenderingProgramUniformLocations
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2RenderingShaderVariant
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanDynamicOesMatrixSlot
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanEncoderInputNormalizationStrategy
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanEs2ProgramBinding
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanEs2ShaderVariant
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanFloatRect
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanLogicalToCaptureTargetMapping
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanMatrixValueShape
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanOesCompositionRule
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanReadbackRowOrder
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanReadbackShape
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderMatrix4
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackage
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderViewport
import dev.dmkr.screencaptureengine.internal.rendering.es2.PreparedEs2RenderingReadbackGlObjects
import dev.dmkr.screencaptureengine.internal.rendering.es2.PreparedEs2RenderingReadbackResources
import dev.dmkr.screencaptureengine.internal.rendering.es2.RuntimeEs2FrameRenderer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.TestPreparedRenderingPipelineResource
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.TestRenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.startup.TestRuntime
import dev.dmkr.screencaptureengine.internal.startup.expectStartException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class InitialActivationCommitBoundaryTest {
    @Test
    fun activeRuntimeTransferMakesInitialOwnerInertAndMovesCleanupOwnership() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()

        fixture.initialOwner.close()
        fixture.initialOwner.onProjectionStopped()
        runCurrent()

        assertEquals(0, fixture.runtime.callbackRegistration.closeCount)
        assertEquals(1, fixture.runtime.metricsProvider.activeCollectorCount)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(0, fixture.runtime.projection.stopCount)

        activeOwner.close()
        runCurrent()

        assertEquals(1, fixture.runtime.callbackRegistration.closeCount)
        assertEquals(0, fixture.runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, fixture.runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, fixture.runtime.projection.stopCount)
    }

    @Test
    fun initialActiveCommitCreatesSessionCoreWithRunningActiveStateAndRejectingSetParameters() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitVisibility(false)

        val session = activeOwner.commitInitialActiveSession()
        val running = session.state.value as ScreenCaptureSessionState.Running
        val active = running.output as ScreenCaptureOutputState.Active
        val rejected = session.setParameters(ScreenCaptureParameters.defaults()) as ScreenCaptureParameterUpdateResult.Rejected
        session.close()
        runCurrent()

        assertEquals(false, running.capturedContentVisible)
        assertEquals(fixture.preparedPlan.outputPlan.toEffectiveParameters(fixture.encoder.info), active.effectiveParameters)
        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
        assertSame(session, activeOwner.sessionForTesting)
    }

    @Test
    fun runtimeFrameLoopAcceptsAndCoalescesFrameSignalsBeforeCommitWithoutConsuming() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()

        activeOwner.recordSourceFrameAvailable(generation = 1L)
        activeOwner.recordSourceFrameAvailable(generation = 2L)
        val beforeCommit = activeOwner.runtimeFrameLoopSnapshot()
        val session = activeOwner.commitInitialActiveSession()
        val afterCommit = activeOwner.runtimeFrameLoopSnapshot()
        session.close()
        runCurrent()

        assertFalse(beforeCommit.committed)
        assertTrue(beforeCommit.sourceFrameSignalPending)
        assertEquals(2L, beforeCommit.latestSourceFrameGeneration)
        assertEquals(0L, beforeCommit.admittedProductionAttempts)
        assertTrue(afterCommit.committed)
        assertEquals(0L, afterCommit.admittedProductionAttempts)
    }

    @Test
    fun projectionStopBeforeCommitCheckpointIsStartupFailureAndClosesActiveResources() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitStop()

        val exception = expectStartException {
            activeOwner.commitInitialActiveSession()
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, fixture.runtime.callbackRegistration.closeCount)
        assertEquals(1, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
    }

    @Test
    fun projectionStopAfterCommitCheckpointReturnsInitialActiveSessionThenQueuesTerminalHandling() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            config = fixture.config,
            commitBoundary = InitialActivationCommitBoundary {
                fixture.runtime.callbackRegistration.emitStop()
            },
        )

        val session = activeOwner.commitInitialActiveSession()
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val terminalState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertTrue(terminalState is ScreenCaptureSessionState.Stopped)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (terminalState as ScreenCaptureSessionState.Stopped).reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(1, fixture.runtime.callbackRegistration.closeCount)
        assertEquals(1, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
    }

    @Test
    fun projectionStopAfterCommitCheckpointDoesNotRunInlineOnDirectDispatcherAndPreservesVisibility() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            config = fixture.config,
            commitBoundary = InitialActivationCommitBoundary {
                fixture.runtime.callbackRegistration.emitStop()
            },
        )
        fixture.runtime.callbackRegistration.emitVisibility(false)

        val session = withContext(DirectCoroutineDispatcher) {
            activeOwner.commitInitialActiveSession()
        }
        val initialState = session.state.value
        val callbackCloseCountBeforeDrain = fixture.runtime.callbackRegistration.closeCount
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val terminalState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        val running = initialState as ScreenCaptureSessionState.Running
        assertTrue(running.output is ScreenCaptureOutputState.Active)
        assertEquals(false, running.capturedContentVisible)
        assertEquals(0, callbackCloseCountBeforeDrain)
        assertTrue(terminalState is ScreenCaptureSessionState.Stopped)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (terminalState as ScreenCaptureSessionState.Stopped).reason)
    }

    @Test
    fun callerCancellationAndProjectionStopAfterFinalCheckpointStillReturnCommittedSessionWithoutInlineWork() = runTest {
        var callerJob: Job? = null
        val fixture = prepareInitialRuntimeOwnerForProduction(
            beforeCommitBoundary = { runtime ->
                callerJob?.cancel(CancellationException("caller cancelled after final checkpoint"))
                runtime.callbackRegistration.emitStop()
            },
        )
        val activeOwner = fixture.activeOwner
        var returnedSession: ScreenCaptureSession? = null
        var returnedState: ScreenCaptureSessionState? = null
        var callbackCloseCountAtReturn = -1
        var targetCloseCountAtReturn = -1
        var encoderCloseCountAtReturn = -1
        var projectionStopCountAtReturn = -1
        var updateTexImageCountAtReturn = -1
        var encodeCountAtReturn = -1

        val commitJob = launch(start = CoroutineStart.UNDISPATCHED) {
            callerJob = currentCoroutineContext()[Job]
            val session = activeOwner.commitInitialActiveSession()
            returnedSession = session
            returnedState = session.state.value
            callbackCloseCountAtReturn = fixture.runtime.callbackRegistration.closeCount
            targetCloseCountAtReturn = fixture.runtime.targetOwner.closeCount
            encoderCloseCountAtReturn = fixture.encoder.closeCount
            projectionStopCountAtReturn = fixture.runtime.projection.stopCount
            updateTexImageCountAtReturn = fixture.runtime.targetOwner.runtimeUpdateTexImageCount
            encodeCountAtReturn = fixture.encoder.encodeCount
        }
        commitJob.join()
        val session = returnedSession ?: throw AssertionError("Expected committed session to be returned")
        val initialState = returnedState

        eventually("post-checkpoint projection stop terminal drain") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertTrue(commitJob.isCancelled)
        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(0, callbackCloseCountAtReturn)
        assertEquals(0, targetCloseCountAtReturn)
        assertEquals(0, encoderCloseCountAtReturn)
        assertEquals(0, projectionStopCountAtReturn)
        assertEquals(0, updateTexImageCountAtReturn)
        assertEquals(0, encodeCountAtReturn)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(0L, session.stats.value.framesPublished)
    }

    @Test
    fun pendingResizeIsDrainedAfterActiveCommitAndSuspendsBeforeFirstRender() = runTest {
        val fixture = prepareInitialRuntimeOwner(apiLevel = 34)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitResize(width = 640, height = 360)

        val beforeCommit = activeOwner.pendingSignalsSnapshot()
        val session = activeOwner.commitInitialActiveSession()
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val suspendedState = session.state.value
        val afterCommit = activeOwner.pendingSignalsSnapshot()
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertTrue(suspendedState is ScreenCaptureSessionState.Running)
        val suspended = (suspendedState as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Suspended
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, suspended.problem.kind)
        assertEquals(640, suspended.currentCaptureGeometry.widthPx)
        assertEquals(360, suspended.currentCaptureGeometry.heightPx)
        assertEquals("pre-commit pending resize width", 640, beforeCommit.pendingCaptureGeometry?.widthPx)
        assertEquals("pre-commit pending resize height", 360, beforeCommit.pendingCaptureGeometry?.heightPx)
        assertEquals("post-commit resize drained", null, afterCommit.pendingCapturedContentResize)
        assertEquals("post-commit geometry drained", null, afterCommit.pendingCaptureGeometry)
    }

    @Test
    fun pendingResizeDoesNotSuspendInlineOnUnconfinedDispatcher() = runTest {
        val fixture = prepareInitialRuntimeOwner(apiLevel = 34)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitResize(width = 640, height = 360)

        val session = withContext(UnconfinedTestDispatcher(testScheduler)) {
            activeOwner.commitInitialActiveSession()
        }
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val suspendedState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertTrue(suspendedState is ScreenCaptureSessionState.Running)
        assertTrue((suspendedState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)
    }

    @Test
    fun projectionStopBeforePostCommitGeometryDrainPreemptsSuspension() = runTest {
        val fixture = prepareInitialRuntimeOwner(apiLevel = 34)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitResize(width = 640, height = 360)

        val session = activeOwner.commitInitialActiveSession()
        val initialState = session.state.value
        fixture.runtime.callbackRegistration.emitStop()
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val terminalState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertTrue(terminalState is ScreenCaptureSessionState.Stopped)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (terminalState as ScreenCaptureSessionState.Stopped).reason)
    }

    @Test
    fun pendingDensityIsDrainedAfterActiveCommitAndSuspendsBeforeFirstRender() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        runCurrent()

        val beforeCommit = activeOwner.pendingSignalsSnapshot()
        val session = activeOwner.commitInitialActiveSession()
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val suspendedState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertTrue(suspendedState is ScreenCaptureSessionState.Running)
        val suspended = (suspendedState as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Suspended
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, suspended.problem.kind)
        assertEquals(1080, beforeCommit.pendingCaptureGeometry?.widthPx)
        assertEquals(1920, beforeCommit.pendingCaptureGeometry?.heightPx)
        assertEquals(560, beforeCommit.pendingCaptureGeometry?.densityDpi)
        assertEquals(1080, suspended.currentCaptureGeometry.widthPx)
        assertEquals(1920, suspended.currentCaptureGeometry.heightPx)
        assertEquals(560, suspended.currentCaptureGeometry.densityDpi)
    }

    @Test
    fun preCommitFrameCallbackAfterSinkInstallAutomaticallySchedulesPostCommitProduction() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            beforeCommitBoundary = { runtime ->
                runtime.targetOwner.emitRuntimeFrameAvailable()
            },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))

        val session = activeOwner.commitInitialActiveSession()
        val afterCommit = activeOwner.runtimeFrameLoopSnapshot()
        val updateCountBeforeTick = fixture.runtime.targetOwner.runtimeUpdateTexImageCount
        val encodeCountBeforeTick = fixture.encoder.encodeCount
        eventually("automatic pre-commit frame production") {
            session.stats.value.framesPublished == 1L
        }
        session.close()
        runCurrent()

        assertEquals(1, fixture.runtime.targetOwner.runtimeFrameSignalInstallCount)
        assertTrue(afterCommit.sourceFrameSignalPending)
        assertEquals(0, updateCountBeforeTick)
        assertEquals(0, encodeCountBeforeTick)
        assertEquals(1, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(1, fixture.runtime.targetOwner.runtimeGetTransformMatrixCount)
        assertEquals(1, fixture.runtime.targetOwner.runtimeTimestampReadCount)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1L, session.stats.value.framesEncoded)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(3, session.stats.value.lastEncodedByteCount)
        assertEquals(1L, activeOwner.runtimeFrameLoopSnapshot().admittedProductionAttempts)
    }

    @Test
    fun stopBeforeProductionAttemptMaterializationDefersHeavyCloseUntilTickExits() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        var targetCloseCountInsideGap = -1
        activeOwner.setBeforeProductionAttemptMaterializationForTesting {
            session.stop()
            targetCloseCountInsideGap = fixture.runtime.targetOwner.closeCount
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        runCurrent()

        assertEquals(0, targetCloseCountInsideGap)
        assertEquals(RuntimeFrameProductionTickResult.NotMaterialized, tick)
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.encoder.encodeCount)
    }

    @Test
    fun runtimeFrameAvailableAutomaticallySchedulesProductionAfterCommit() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()

        eventually("automatic runtime production") {
            session.stats.value.framesPublished == 1L
        }
        session.close()
        runCurrent()

        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1L, activeOwner.runtimeFrameLoopSnapshot().admittedProductionAttempts)
    }

    @Test
    fun publishedFrameTimestampUsesPublicationElapsedRealtimeNotSourceTimestamp() = runTest {
        var nowNanos = 10_000L
        val fixture = prepareInitialRuntimeOwnerForProduction(elapsedRealtimeNanos = { nowNanos })
        fixture.runtime.targetOwner.runtimeTimestampNanos = 123L
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        var deliveredTimestamp: Long? = null
        val subscription = session.onFrame { frame ->
            deliveredTimestamp = frame.timestampElapsedRealtimeNanos
        }

        nowNanos = 999_000L
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        eventually("published frame delivery timestamp") {
            deliveredTimestamp != null
        }
        subscription.cancel()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertEquals(1, fixture.runtime.targetOwner.runtimeTimestampReadCount)
        assertEquals(123L, fixture.runtime.targetOwner.runtimeTimestampNanos)
        assertEquals(999_000L, deliveredTimestamp)
    }

    @Test
    fun coalescedPostCommitFrameCallbacksUseLatestSignalWithoutDropAccounting() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        activeOwner.recordSourceFrameAvailableWithoutRuntimeWakeForTesting(generation = 1L)
        activeOwner.recordSourceFrameAvailableWithoutRuntimeWakeForTesting(generation = 1L)
        val tick = activeOwner.drainRuntimeProductionTick()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1L, activeOwner.runtimeFrameLoopSnapshot().admittedProductionAttempts)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun postCommitProjectionStopAutomaticallyDrainsToTerminalState() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            config = fixture.config,
            commitBoundary = InitialActivationCommitBoundary {
                fixture.runtime.callbackRegistration.emitStop()
            },
        )

        val session = activeOwner.commitInitialActiveSession()
        eventually("automatic projection-stop terminal drain") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        session.close()
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
    }

    @Test
    fun postCommitVisibilityCallbackUpdatesCoreWithoutSuspendingOutput() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.callbackRegistration.emitVisibility(false)

        eventually("post-commit visibility update") {
            (session.state.value as? ScreenCaptureSessionState.Running)?.capturedContentVisible == false
        }
        val running = session.state.value as ScreenCaptureSessionState.Running
        session.close()
        runCurrent()

        assertTrue(running.output is ScreenCaptureOutputState.Active)
    }

    @Test
    fun projectionStopObservedBeforeOwnerStopWinsAsCaptureEnded() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.callbackRegistration.emitStop()
        session.stop()

        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
    }

    @Test
    fun runtimeProductionCountsReadbackBusyDropForMaterializedFrame() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        val tick = try {
            activeOwner.forceReadbackBusyForTesting(true)
            fixture.recordRuntimeFrameAvailableWithoutWake()
            activeOwner.drainRuntimeProductionTick()
        } finally {
            activeOwner.forceReadbackBusyForTesting(false)
        }
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.ReadbackBusyDrop, tick)
        assertEquals(1L, session.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(0L, session.stats.value.framesEncoded)
    }

    @Test
    fun runtimeProductionCountsEncoderBusyDropBeforeGlConsumption() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        activeOwner.forceEncoderBusyForTesting(true)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        activeOwner.forceEncoderBusyForTesting(false)
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.EncoderBusyDrop, tick)
        assertEquals(1L, session.stats.value.droppedFrames.byEncoderBusy)
        assertEquals(0, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(0, fixture.encoder.encodeCount)
    }

    @Test
    fun rawProjectionStopBeforeProductionAttemptMaterializationStopsWithoutDrop() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        activeOwner.setBeforeProductionAttemptMaterializationForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.NotMaterialized, tick)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(0L, session.stats.value.droppedFrames.total)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(0, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(0, fixture.encoder.encodeCount)
    }

    @Test
    fun rawProjectionStopBeforeRuntimeListenerDeliveryClassifiesBusyDropsAsStale() = runTest {
        val readbackFixture = prepareInitialRuntimeOwnerForProduction()
        val readbackOwner = readbackFixture.activeOwner
        readbackOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val readbackSession = readbackOwner.commitInitialActiveSession()
        val readbackTick = try {
            readbackOwner.forceReadbackBusyForTesting(true)
            readbackOwner.setAfterProductionAttemptMaterializedForTesting {
                readbackFixture.runtime.callbackRegistration.emitStopRawOnly()
            }

            readbackFixture.recordRuntimeFrameAvailableWithoutWake()
            readbackOwner.drainRuntimeProductionTick()
        } finally {
            readbackOwner.forceReadbackBusyForTesting(false)
        }
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, readbackTick)
        assertEquals(1L, readbackSession.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, readbackSession.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (readbackSession.state.value as ScreenCaptureSessionState.Stopped).reason)

        val encoderFixture = prepareInitialRuntimeOwnerForProduction()
        val encoderOwner = encoderFixture.activeOwner
        encoderOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val encoderSession = encoderOwner.commitInitialActiveSession()
        encoderOwner.forceEncoderBusyForTesting(true)
        encoderOwner.setAfterProductionAttemptMaterializedForTesting {
            encoderFixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        encoderFixture.recordRuntimeFrameAvailableWithoutWake()
        val encoderTick = encoderOwner.drainRuntimeProductionTick()
        encoderOwner.forceEncoderBusyForTesting(false)
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, encoderTick)
        assertEquals(1L, encoderSession.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, encoderSession.stats.value.droppedFrames.byEncoderBusy)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (encoderSession.state.value as ScreenCaptureSessionState.Stopped).reason)
    }

    @Test
    fun periodicRefreshBeforeFirstSourceFrameIsNoOpWithoutDropAccounting() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 1_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        activeOwner.recordPeriodicRefreshWakeForTesting()
        val tick = activeOwner.drainRuntimeProductionTick()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.PeriodicRefreshNoSourceFrame, tick)
        assertEquals(0, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(0, fixture.encoder.encodeCount)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun periodicRefreshTimerBeforeFirstSourceFrameIsNoOpWithoutDropAccounting() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 1_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.overridePeriodicRefreshDelayForTesting(delayMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        eventually("periodic refresh no-source timer wake") {
            activeOwner.periodicRefreshNoSourceWakeCountForTesting() == 1L
        }
        session.close()
        runCurrent()

        assertEquals(0, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(0, fixture.encoder.encodeCount)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun periodicRefreshPublishesLatestEncodedFrameAfterFirstSourceFrameWithoutReEncoding() = runTest {
        var nowNanos = 0L
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 1_000L)),
            elapsedRealtimeNanos = { nowNanos },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.overridePeriodicRefreshDelayForTesting(delayMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        val deliveredFrames = Collections.synchronizedList(mutableListOf<DeliveredFrame>())
        val subscription = session.onFrame { frame ->
            deliveredFrames += DeliveredFrame(
                sequence = frame.sequence,
                timestampElapsedRealtimeNanos = frame.timestampElapsedRealtimeNanos,
                bytes = frame.copyBytes(),
            )
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        nowNanos += 50_000_000L
        eventually("periodic refresh publication") {
            session.stats.value.framesPublished == 2L
        }
        eventually("periodic refresh delivery") {
            deliveredFrames.size == 2
        }
        subscription.cancel()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1L, session.stats.value.framesEncoded)
        assertEquals(2L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
        val firstFrame = deliveredFrames[0]
        val refreshFrame = deliveredFrames[1]
        assertEquals(1L, firstFrame.sequence)
        assertEquals(2L, refreshFrame.sequence)
        assertEquals(0L, firstFrame.timestampElapsedRealtimeNanos)
        assertEquals(50_000_000L, refreshFrame.timestampElapsedRealtimeNanos)
        assertTrue(firstFrame.bytes.contentEquals(refreshFrame.bytes))
    }

    @Test
    fun periodicRefreshDelayedTaskAndRetainedFrameAreClearedOnStop() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        assertTrue(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertTrue(activeOwner.hasScheduledPeriodicRefreshForTesting())
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertFalse(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertFalse(activeOwner.hasScheduledPeriodicRefreshForTesting())
        assertEquals(1L, session.stats.value.framesPublished)
    }

    @Test
    fun trimMemoryClearsRetainedPeriodicRefreshFrame() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        assertTrue(activeOwner.hasPeriodicRefreshFrameForTesting())
        session.trimMemory(80)

        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertFalse(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertFalse(activeOwner.hasScheduledPeriodicRefreshForTesting())
    }

    @Test
    fun outputSuspensionClearsRetainedPeriodicRefreshFrameAndScheduledTask() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        assertTrue(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertTrue(activeOwner.hasScheduledPeriodicRefreshForTesting())
        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        runCurrent()
        assertEquals(560, activeOwner.pendingSignalsSnapshot().pendingCaptureGeometry?.densityDpi)
        activeOwner.drainRuntimeProductionTick()
        val running = session.state.value as ScreenCaptureSessionState.Running

        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertTrue(running.output is ScreenCaptureOutputState.Suspended)
        assertFalse(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertFalse(activeOwner.hasScheduledPeriodicRefreshForTesting())
    }

    @Test
    fun maxFpsPacingRecordsFrameRatePolicyDropForTooSoonSourceFrame() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.MaxFps(1)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val secondTick = activeOwner.drainRuntimeProductionTick()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(RuntimeFrameProductionTickResult.FrameRatePolicyDrop, secondTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byFrameRatePolicy)
    }

    @Test
    fun pendingGeometrySuspensionPreventsMaxFpsFrameRateDropAccounting() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.MaxFps(1)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        val textureUpdatesBeforeSuspensionTick = fixture.runtime.targetOwner.runtimeUpdateTexImageCount
        val encodeCountBeforeSuspensionTick = fixture.encoder.encodeCount
        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        runCurrent()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val suspensionTick = activeOwner.drainRuntimeProductionTick()
        val running = session.state.value as ScreenCaptureSessionState.Running
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(RuntimeFrameProductionTickResult.NotMaterialized, suspensionTick)
        assertTrue(running.output is ScreenCaptureOutputState.Suspended)
        assertEquals(textureUpdatesBeforeSuspensionTick, fixture.runtime.targetOwner.runtimeUpdateTexImageCount)
        assertEquals(encodeCountBeforeSuspensionTick, fixture.encoder.encodeCount)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.byFrameRatePolicy)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun rawProjectionStopBeforeFrameRateDropPreventsFrameRateAccountingAndStopsSession() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.MaxFps(1)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        activeOwner.setBeforeFrameRatePolicyDropForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val secondTick = activeOwner.drainRuntimeProductionTick()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, secondTick)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.byFrameRatePolicy)
        assertEquals(0L, session.stats.value.droppedFrames.byStaleGeneration)
    }

    @Test
    fun maxFpsAfterIntervalAdmitsAndReusesEncoderLane() = runTest {
        var nowNanos = 1_000L
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.MaxFps(1)),
            elapsedRealtimeNanos = { nowNanos },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        nowNanos += 1_000_000_000L
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val secondTick = activeOwner.drainRuntimeProductionTick()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(RuntimeFrameProductionTickResult.Published, secondTick)
        assertEquals(2, fixture.encoder.encodeCount)
        assertEquals(1, fixture.encoder.encodeThreadIds.toSet().size)
        assertTrue(fixture.encoder.encodeThreadNames.toSet().single().contains("ScreenCaptureRuntimeEncoder"))
        assertEquals(2L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.byFrameRatePolicy)
    }

    @Test
    fun stopDuringReadbackFencesBeforeEncodeAndDefersHeavyCloseUntilReadbackReturns() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val readPixelsEntered = CountDownLatch(1)
        val releaseReadPixels = CountDownLatch(1)
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(
            RuntimeEs2FrameRenderer(
                BlockingReadPixelsRuntimeGles20Api(
                    readPixelsEntered = readPixelsEntered,
                    releaseReadPixels = releaseReadPixels,
                ),
            ),
        )
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("readPixels entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        session.stop()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        val encodeCountWhileStopped = fixture.encoder.encodeCount
        val targetCloseCountWhileReadbackBlocked = fixture.runtime.targetOwner.closeCount
        releaseReadPixels.countDown()

        eventually("deferred readback cleanup") {
            fixture.runtime.targetOwner.closeCount == 1
        }
        runCurrent()

        assertEquals(ScreenCaptureStopReason.OwnerStop, terminalState.reason)
        assertEquals(0, encodeCountWhileStopped)
        assertEquals(0, targetCloseCountWhileReadbackBlocked)
        assertEquals(0, fixture.encoder.encodeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
    }

    @Test
    fun terminalCleanupFenceSurvivesUntilDeferredProviderBackedCleanupIsScheduled() = runTest {
        val terminalCleanupFences = AtomicInteger(0)
        val encoder = CloseTrackingImageEncoder()
        val fixture = prepareProviderBackedInitialRuntimeOwnerForProduction(
            encoder = encoder,
            terminalCleanupFenceFactory = {
                terminalCleanupFences.incrementAndGet()
                AutoCloseable { terminalCleanupFences.decrementAndGet() }
            },
        )
        val readPixelsEntered = CountDownLatch(1)
        val releaseReadPixels = CountDownLatch(1)
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(
            RuntimeEs2FrameRenderer(
                BlockingReadPixelsRuntimeGles20Api(
                    readPixelsEntered = readPixelsEntered,
                    releaseReadPixels = releaseReadPixels,
                ),
            ),
        )
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("readPixels entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        session.stop()
        session.close()
        fixture.runtime.runScheduledCleanup()

        assertEquals(1, terminalCleanupFences.get())
        assertEquals(0, encoder.closeCount.get())
        assertEquals(0, fixture.runtime.targetOwner.closeCount)

        releaseReadPixels.countDown()

        eventually("terminal cleanup fence released after deferred cleanup was scheduled") {
            terminalCleanupFences.get() == 0
        }
        assertEquals(0, encoder.closeCount.get())
        assertEquals(0, fixture.runtime.targetOwner.closeCount)

        fixture.runtime.runScheduledCleanup()

        encoder.awaitClose("provider-backed deferred encoder cleanup")
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
        eventually("provider context idle after provider-backed deferred cleanup") {
            fixture.providerContext.closeIfIdle()
        }
    }

    @Test
    fun projectionStopDuringReadbackFencesPublicationAndCaptureEndedWins() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val readPixelsEntered = CountDownLatch(1)
        val releaseReadPixels = CountDownLatch(1)
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(
            RuntimeEs2FrameRenderer(
                BlockingReadPixelsRuntimeGles20Api(
                    readPixelsEntered = readPixelsEntered,
                    releaseReadPixels = releaseReadPixels,
                ),
            ),
        )
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("readPixels entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        fixture.runtime.callbackRegistration.emitStop()
        releaseReadPixels.countDown()

        eventually("projection stop during readback terminal cleanup") {
            fixture.runtime.targetOwner.closeCount == 1
        }
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(0, fixture.encoder.encodeCount)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
    }

    @Test
    fun projectionStopDuringEncodeFencesLateSuccessAndCaptureEndedWins() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release fake encode."
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        fixture.runtime.callbackRegistration.emitStop()
        releaseEncode.countDown()

        eventually("projection stop during encode terminal cleanup") {
            fixture.encoder.closeCount == 1 && fixture.runtime.targetOwner.closeCount == 1
        }
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
    }

    @Test
    fun projectionStopAfterFinalPrePublicationCheckPreventsEncodedSuccessPublication() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        val events = Collections.synchronizedList(mutableListOf<ScreenCaptureEventType>())
        val eventCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { event -> events += event.type }
        }
        var deliveredFrameCount = 0
        val subscription = session.onFrame {
            deliveredFrameCount++
        }
        activeOwner.setBeforeFinalEncodedPublicationForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        subscription.cancel()
        eventCollector.cancel()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, tick)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedDeliveries.total)
        assertEquals(0, deliveredFrameCount)
        assertTrue(events.contains(ScreenCaptureEventType.SessionStopped))
    }

    @Test
    fun encodedPublicationBeforeRawProjectionStopRemainsDeliveredAndNotStale() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        val deliveredFrames = Collections.synchronizedList(mutableListOf<DeliveredFrame>())
        val subscription = session.onFrame { frame ->
            deliveredFrames += DeliveredFrame(
                sequence = frame.sequence,
                timestampElapsedRealtimeNanos = frame.timestampElapsedRealtimeNanos,
                bytes = frame.copyBytes(),
            )
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        eventually("encoded publication delivery before raw stop") {
            deliveredFrames.size == 1
        }
        fixture.runtime.callbackRegistration.emitStop()
        activeOwner.drainQueuedRuntimeTerminalSignals()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        subscription.cancel()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertEquals(1, deliveredFrames.size)
        assertEquals(1L, deliveredFrames.single().sequence)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedDeliveries.total)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
    }

    @Test
    fun ownerStopCommittedBeforePlatformRawEchoRemainsOwnerStop() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        session.stop()
        fixture.runtime.callbackRegistration.emitStopRawOnly()
        fixture.runtime.callbackRegistration.deliverPendingStopToListener()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureStopReason.OwnerStop, terminalState.reason)
        assertEquals(null, terminalState.problem)
    }

    @Test
    fun projectionStopAfterFinalPreRefreshCheckPreventsPeriodicRefreshPublication() = runTest {
        var nowNanos = 0L
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(intervalMillis = 1_000L)),
            elapsedRealtimeNanos = { nowNanos },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.overridePeriodicRefreshDelayForTesting(delayMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        activeOwner.setBeforeFinalPeriodicRefreshPublicationForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }
        nowNanos += 50_000_000L
        activeOwner.recordPeriodicRefreshWakeForTesting()
        val refreshTick = activeOwner.drainRuntimeProductionTick()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, refreshTick)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(1L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun projectionStopObservedDuringOwnerStopPreCommitWinsCaptureEnded() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        activeOwner.setBeforeOwnerStopTerminalCommitForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        session.stop()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
    }

    @Test
    fun rawProjectionStopBeforeRuntimeListenerDeliveryClassifiesProviderDropsAsStale() = runTest {
        val failedFixture = prepareInitialRuntimeOwnerForProduction()
        failedFixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        failedFixture.activeOwner.setBeforeEncodeNonSuccessDropForTesting {
            failedFixture.runtime.callbackRegistration.emitStopRawOnly()
        }
        val failedTick = produceOneFrameWithoutClosing(failedFixture)

        val threwFixture = prepareInitialRuntimeOwnerForProduction()
        threwFixture.encoder.encodeFailure = IllegalStateException("provider threw")
        threwFixture.encoder.onEncodeEntered = {
            threwFixture.runtime.callbackRegistration.emitStopRawOnly()
        }
        val threwTick = produceOneFrameWithoutClosing(threwFixture)

        val capFixture = prepareInitialRuntimeOwnerForProduction(configMaxEncodedBytes = 1_024)
        capFixture.encoder.encodedBytes = ByteArray(1_025) { 7 }
        capFixture.activeOwner.setBeforeEncodeNonSuccessDropForTesting {
            capFixture.runtime.callbackRegistration.emitStopRawOnly()
        }
        val capTick = produceOneFrameWithoutClosing(capFixture)

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, failedTick)
        assertTrue(failedFixture.session.state.value is ScreenCaptureSessionState.Stopped)
        assertEquals(0L, failedFixture.session.stats.value.framesEncoded)
        assertEquals(0L, failedFixture.session.stats.value.framesPublished)
        assertEquals(1L, failedFixture.session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, failedFixture.session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(0L, failedFixture.session.stats.value.droppedDeliveries.total)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (failedFixture.session.state.value as ScreenCaptureSessionState.Stopped).reason)
        assertEquals(
            ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
            (failedFixture.session.state.value as ScreenCaptureSessionState.Stopped).problem?.kind,
        )

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, threwTick)
        assertTrue(threwFixture.session.state.value is ScreenCaptureSessionState.Stopped)
        assertEquals(0L, threwFixture.session.stats.value.framesEncoded)
        assertEquals(0L, threwFixture.session.stats.value.framesPublished)
        assertEquals(1L, threwFixture.session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, threwFixture.session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(0L, threwFixture.session.stats.value.droppedDeliveries.total)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (threwFixture.session.state.value as ScreenCaptureSessionState.Stopped).reason)
        assertEquals(
            ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
            (threwFixture.session.state.value as ScreenCaptureSessionState.Stopped).problem?.kind,
        )

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, capTick)
        assertTrue(capFixture.session.state.value is ScreenCaptureSessionState.Stopped)
        assertEquals(0L, capFixture.session.stats.value.framesEncoded)
        assertEquals(0L, capFixture.session.stats.value.framesPublished)
        assertEquals(1L, capFixture.session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, capFixture.session.stats.value.droppedFrames.byEncodedSizeLimit)
        assertEquals(0L, capFixture.session.stats.value.droppedDeliveries.total)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (capFixture.session.state.value as ScreenCaptureSessionState.Stopped).reason)
        assertEquals(
            ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
            (capFixture.session.state.value as ScreenCaptureSessionState.Stopped).problem?.kind,
        )

        failedFixture.session.close()
        threwFixture.session.close()
        capFixture.session.close()
        runCurrent()
    }

    @Test
    fun runtimeEncodeProviderFailedThrowAndSinkCapRejectionDoNotPublishEncodedSuccess() = runTest {
        val failedFixture = prepareInitialRuntimeOwnerForProduction()
        failedFixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val failedTick = produceOneFrame(failedFixture)

        val threwFixture = prepareInitialRuntimeOwnerForProduction()
        threwFixture.encoder.encodeFailure = IllegalStateException("provider threw")
        val threwTick = produceOneFrame(threwFixture)

        val capFixture = prepareInitialRuntimeOwnerForProduction(configMaxEncodedBytes = 1_024)
        capFixture.encoder.encodedBytes = ByteArray(1_025) { 7 }
        val capTick = produceOneFrame(capFixture)

        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, failedTick)
        assertEquals(0L, failedFixture.session.stats.value.framesEncoded)
        assertEquals(1L, failedFixture.session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(RuntimeFrameProductionTickResult.EncodeThrewDrop, threwTick)
        assertEquals(0L, threwFixture.session.stats.value.framesEncoded)
        assertEquals(1L, threwFixture.session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(RuntimeFrameProductionTickResult.EncodedSizeLimitDrop, capTick)
        assertEquals(0L, capFixture.session.stats.value.framesEncoded)
        assertEquals(1L, capFixture.session.stats.value.droppedFrames.byEncodedSizeLimit)
    }

    @Test
    fun rawProjectionStopBeforeRuntimeGlFailureTerminalCommitStopsInsteadOfFailing() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(ThrowingReadPixelsRuntimeGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        activeOwner.setBeforeRuntimeFailureTerminalCommitForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tick = activeOwner.drainRuntimeProductionTick()
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.GlFailed, tick)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(1L, session.stats.value.droppedFrames.byTransientFailure)
    }

    @Test
    fun rawProjectionStopBeforeEncoderTimeoutTerminalCommitStopsInsteadOfFailing() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            var released = false
            while (!released) {
                try {
                    released = releaseEncode.await(10L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Keep simulating an encoder that ignores interruption until the test releases it.
                }
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.overrideEncoderOperationTimeoutForTesting(timeoutMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        activeOwner.setBeforeRuntimeFailureTerminalCommitForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        eventually("runtime encode timeout resolved as projection stop") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        val terminalState = session.state.value as ScreenCaptureSessionState.Stopped
        releaseEncode.countDown()
        eventually("deferred encode timeout cleanup after projection stop") {
            fixture.encoder.closeCount == 1 && fixture.runtime.targetOwner.closeCount == 1
        }
        fixture.runtime.runScheduledCleanup()
        runCurrent()

        assertEquals(ScreenCaptureStopReason.CaptureEnded, terminalState.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, terminalState.problem?.kind)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byTransientFailure)
    }

    @Test
    fun realWatchdogTimeoutClassifiesGlFailureAndReleasesLateSuccessfulReadbackLease() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(runCleanupSynchronously = false)
        fixture.runtime.targetOwner.suspendRuntimeAccessUntilCancelledWithLateResult = true
        val activeOwner = fixture.activeOwner
        activeOwner.overrideGlOperationTimeoutForTesting(timeoutMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()

        eventually("runtime GL timeout failure") {
            session.state.value is ScreenCaptureSessionState.Failed
        }
        val failedState = session.state.value as ScreenCaptureSessionState.Failed
        fixture.runtime.runScheduledCleanup()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failedState.problem.kind)
        assertEquals(1, activeOwner.lateRenderReadbackLeaseReleaseCountForTesting())
        assertEquals(1, fixture.runtime.targetOwner.abandonGlLaneCount)
        assertEquals(1, fixture.runtime.targetOwner.closeCount)
    }

    @Test
    fun watchdogTimeoutWithoutLateGlResultClosesNonGlResourcesAndQuarantinesGlResources() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(runCleanupSynchronously = false)
        fixture.runtime.targetOwner.suspendRuntimeAccessUntilCancelledWithoutLateResult = true
        val activeOwner = fixture.activeOwner
        activeOwner.overrideGlOperationTimeoutForTesting(timeoutMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        eventually("runtime GL timeout failure") {
            session.state.value is ScreenCaptureSessionState.Failed
        }
        val failedState = session.state.value as ScreenCaptureSessionState.Failed
        fixture.runtime.runScheduledCleanup()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failedState.problem.kind)
        assertEquals(1, fixture.runtime.targetOwner.abandonGlLaneCount)
        assertEquals(1, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
    }

    @Test
    fun encodeTimeoutQuarantinesResourcesUntilStuckEncoderReturns() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            var released = false
            while (!released) {
                try {
                    released = releaseEncode.await(10L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Keep simulating an encoder that ignores interruption until the test releases it.
                }
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.overrideEncoderOperationTimeoutForTesting(timeoutMillis = 50L)
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val readbackBorrowedByEncoder = AtomicBoolean(false)
        activeOwner.setReadbackBorrowedByEncoderObserverForTesting { isBorrowed ->
            readbackBorrowedByEncoder.set(isBorrowed)
        }
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        eventually("runtime encode timeout") {
            session.state.value is ScreenCaptureSessionState.Failed
        }
        eventually("non-encoder encode timeout cleanup") {
            fixture.runtime.virtualDisplayOwner.closeCount == 1 && fixture.runtime.targetOwner.closeCount == 1
        }
        val failedState = session.state.value as ScreenCaptureSessionState.Failed
        val encoderCloseCountWhileStuck = fixture.encoder.closeCount
        val targetCloseCountWhileStuck = fixture.runtime.targetOwner.closeCount
        val virtualDisplayCloseCountWhileStuck = fixture.runtime.virtualDisplayOwner.closeCount
        val readbackBorrowedByEncoderWhileStuck = readbackBorrowedByEncoder.get()
        releaseEncode.countDown()

        eventually("deferred encode timeout cleanup") {
            fixture.encoder.closeCount == 1 && fixture.runtime.targetOwner.closeCount == 1
        }
        fixture.runtime.runScheduledCleanup()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.EncodeRepeatedFailure, failedState.problem.kind)
        assertEquals(0, encoderCloseCountWhileStuck)
        assertEquals(1, targetCloseCountWhileStuck)
        assertEquals(1, virtualDisplayCloseCountWhileStuck)
        assertTrue(readbackBorrowedByEncoderWhileStuck)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byTransientFailure)
    }

    @Test
    fun runtimeTrimMemoryShrinksEncodedScratchStateAfterSuccessfulEncode() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        fixture.encoder.encodedBytes = ByteArray(300) { 1 }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        fixture.recordRuntimeFrameAvailableWithoutWake()

        val tick = activeOwner.drainRuntimeProductionTick()
        val byteCountBeforeTrim = activeOwner.encodedScratchByteCountForTesting()
        session.trimMemory(80)
        val byteCountAfterTrim = activeOwner.encodedScratchByteCountForTesting()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertEquals(300, byteCountBeforeTrim)
        assertEquals(0, byteCountAfterTrim)
    }

    @Test
    fun stopAfterSuccessfulEncodeReleasesEncodedScratchStorage() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        fixture.encoder.encodedBytes = ByteArray(300) { 1 }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        fixture.recordRuntimeFrameAvailableWithoutWake()

        val tick = activeOwner.drainRuntimeProductionTick()
        val byteCountBeforeStop = activeOwner.encodedScratchByteCountForTesting()
        session.close()
        runCurrent()

        assertEquals(RuntimeFrameProductionTickResult.Published, tick)
        assertEquals(300, byteCountBeforeStop)
        assertEquals(null, activeOwner.encodedScratchByteCountForTesting())
    }

    @Test
    fun stopDuringActiveEncodeDefersEncoderCloseAndFencesLateSuccess() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release fake encode."
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        session.stop()
        val encoderCloseCountWhileEncoding = fixture.encoder.closeCount
        val targetCloseCountWhileEncoding = fixture.runtime.targetOwner.closeCount
        releaseEncode.countDown()

        eventually("deferred encode cleanup") {
            fixture.encoder.closeCount == 1 && fixture.runtime.targetOwner.closeCount == 1
        }
        runCurrent()

        assertEquals(0, encoderCloseCountWhileEncoding)
        assertEquals(0, targetCloseCountWhileEncoding)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
    }

    @Test
    fun trimMemoryDuringActiveEncodeIsAppliedAfterEncodeCompletes() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.encodedBytes = ByteArray(300) { 1 }
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release fake encode."
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.targetOwner.emitRuntimeFrameAvailable()
        assertTrue("encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        session.trimMemory(80)
        releaseEncode.countDown()

        eventually("pending trim applied") {
            activeOwner.encodedScratchByteCountForTesting() == 0
        }
        session.close()
        runCurrent()

        assertEquals(1L, session.stats.value.framesPublished)
    }

    private suspend fun TestScope.prepareInitialRuntimeOwner(apiLevel: Int = 33): InitialActivationFixture {
        val runtime = TestRuntime(apiLevel = apiLevel)
        val config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider)
        val startupResources = if (apiLevel >= 34) {
            val pendingStartup = async { runtime.start() }
            runCurrent()
            runtime.callbackRegistration.emitResize(width = 720, height = 1280)
            pendingStartup.await()
        } else {
            runtime.start()
        }
        val preActiveOwner = startupResources.transferToPreActiveRuntimeOwner()
        val preparedPlan = preActiveOwner.prepareInitialActivePlan(config)
        val preparer = TestRenderingPipelinePreparer()
        val preparedResources = preActiveOwner.prepareInitialRenderingPipeline(
            preparedPlan = preparedPlan,
            preparer = preparer,
        )
        val initialOwner = preActiveOwner.transferToInitialRuntimeResourceOwner(
            preparedPlan = preparedPlan,
            preparedResources = preparedResources,
        )
        return InitialActivationFixture(
            runtime = runtime,
            config = config,
            preparedPlan = preparedPlan,
            initialOwner = initialOwner,
            readbackResources = preparer.preparedResources.single(),
            encoder = preparer.preparedEncoders.single(),
        )
    }

    private suspend fun prepareInitialRuntimeOwnerForProduction(
        configMaxEncodedBytes: Int = 4_194_304,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
        beforeCommitBoundary: (TestRuntime) -> Unit = {},
        runCleanupSynchronously: Boolean = true,
        elapsedRealtimeNanos: () -> Long = { 1_000L },
        terminalCleanupFenceFactory: () -> AutoCloseable = { AutoCloseable {} },
    ): ProductionActivationFixture {
        val runtime = TestRuntime(apiLevel = 33, runCleanupSynchronously = runCleanupSynchronously)
        val config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider, maxEncodedBytes = configMaxEncodedBytes)
        val startupResources = runtime.start()
        val preActiveOwner = startupResources.transferToPreActiveRuntimeOwner()
        val preparedPlan = preActiveOwner.prepareInitialActivePlan(config, initialParameters = initialParameters)
        val preparer = RuntimeProductionRenderingPipelinePreparer()
        val preparedResources = preActiveOwner.prepareInitialRenderingPipeline(preparedPlan = preparedPlan, preparer = preparer)
        val initialOwner = preActiveOwner.transferToInitialRuntimeResourceOwner(
            preparedPlan = preparedPlan,
            preparedResources = preparedResources,
        )
        val activeOwner = initialOwner.transferToActiveRuntimeOwner(
            config = config,
            commitBoundary = InitialActivationCommitBoundary {
                beforeCommitBoundary(runtime)
            },
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            terminalCleanupFenceFactory = terminalCleanupFenceFactory,
        )
        return ProductionActivationFixture(
            runtime = runtime,
            activeOwner = activeOwner,
            encoder = preparer.encoders.single(),
        )
    }

    private suspend fun prepareProviderBackedInitialRuntimeOwnerForProduction(
        encoder: CloseTrackingImageEncoder,
        terminalCleanupFenceFactory: () -> AutoCloseable = { AutoCloseable {} },
    ): ProviderBackedProductionActivationFixture {
        val runtime = TestRuntime(apiLevel = 33, runCleanupSynchronously = false)
        val config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider)
        val startupResources = runtime.start()
        val preActiveOwner = startupResources.transferToPreActiveRuntimeOwner()
        val preparedPlan = preActiveOwner.prepareInitialActivePlan(config)
        val providerContext = ProviderPreparationContext()
        val preparer = RuntimeProductionRenderingPipelinePreparer(
            providerEncoderResources = { request ->
                prepareProviderBackedEncoderResources(
                    providerContext = providerContext,
                    request = request,
                    encoder = encoder,
                )
            },
        )
        val preparedResources = withContext(Dispatchers.Default) {
            preActiveOwner.prepareInitialRenderingPipeline(preparedPlan = preparedPlan, preparer = preparer)
        }
        val initialOwner = preActiveOwner.transferToInitialRuntimeResourceOwner(
            preparedPlan = preparedPlan,
            preparedResources = preparedResources,
        )
        val activeOwner = initialOwner.transferToActiveRuntimeOwner(
            config = config,
            elapsedRealtimeNanos = { 1_000L },
            terminalCleanupFenceFactory = terminalCleanupFenceFactory,
        )
        return ProviderBackedProductionActivationFixture(
            runtime = runtime,
            activeOwner = activeOwner,
            providerContext = providerContext,
        )
    }

    private suspend fun prepareProviderBackedEncoderResources(
        providerContext: ProviderPreparationContext,
        request: RenderingPipelinePrepareRequest,
        encoder: CloseTrackingImageEncoder,
    ): PreparedImageEncoderResources {
        val provider = FakeImageEncoderProvider().apply {
            encoderFactory = { encoder }
        }
        return when (
            val encoderResult = ImageEncoderPreparer(providerContext).prepare(
                token = request.planPreparationToken,
                provider = provider,
                request = request.outputPlan.encoderRequest,
            )
        ) {
            is ImageEncoderPreparationResult.Success -> encoderResult.preparedEncoder
            is ImageEncoderPreparationResult.Failure -> throw AssertionError(
                "Provider-backed encoder preparation failed: ${encoderResult.message}",
                encoderResult.cause,
            )
        }
    }

    private suspend fun TestScope.produceOneFrame(fixture: ProductionActivationFixture): RuntimeFrameProductionTickResult {
        val tick = produceOneFrameWithoutClosing(fixture)
        fixture.session.close()
        runCurrent()
        return tick
    }

    private suspend fun produceOneFrameWithoutClosing(fixture: ProductionActivationFixture): RuntimeFrameProductionTickResult {
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        fixture.session = activeOwner.commitInitialActiveSession()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        return activeOwner.drainRuntimeProductionTick()
    }

    private fun ProductionActivationFixture.recordRuntimeFrameAvailableWithoutWake() {
        activeOwner.recordSourceFrameAvailableWithoutRuntimeWakeForTesting(
            generation = runtime.targetOwner.createdHandles.single().generation,
        )
    }

    /**
     * Polls only integration boundaries that intentionally use real executors in this test suite:
     * runtime scheduler turns, watchdog timers, encoder-lane timeout/cleanup, and callback delivery.
     * Pure state-machine races use direct drains, latches, or test hooks instead.
     */
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

    private class DeliveredFrame(
        val sequence: Long,
        val timestampElapsedRealtimeNanos: Long,
        val bytes: ByteArray,
    )

    private class InitialActivationFixture(
        val runtime: TestRuntime,
        val config: ScreenCaptureConfig,
        val preparedPlan: PreActiveInitialRuntimePlan,
        val initialOwner: InitialRuntimeResourceOwner,
        val readbackResources: TestPreparedRenderingPipelineResource,
        val encoder: FakeImageEncoder,
    ) {
        fun transferToActiveRuntimeOwner(
            config: ScreenCaptureConfig = this.config,
            commitBoundary: InitialActivationCommitBoundary = InitialActivationCommitBoundary(),
        ): ActiveRuntimeOwner =
            initialOwner.transferToActiveRuntimeOwner(
                config = config,
                commitBoundary = commitBoundary,
                elapsedRealtimeNanos = { 1_000L },
            )
    }

    private class ProductionActivationFixture(
        val runtime: TestRuntime,
        val activeOwner: ActiveRuntimeOwner,
        val encoder: FakeImageEncoder,
    ) {
        lateinit var session: ScreenCaptureSession
    }

    private class ProviderBackedProductionActivationFixture(
        val runtime: TestRuntime,
        val activeOwner: ActiveRuntimeOwner,
        val providerContext: ProviderPreparationContext,
    )

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

    private inner class RuntimeProductionRenderingPipelinePreparer(
        private val providerEncoderResources: (suspend (RenderingPipelinePrepareRequest) -> PreparedImageEncoderResources)? = null,
    ) : RenderingPipelinePreparer {
        val encoders = mutableListOf<FakeImageEncoder>()

        override suspend fun prepareInitialRenderingPipeline(request: RenderingPipelinePrepareRequest): RenderingPipelinePreparationResult {
            val outputPlan = request.outputPlan
            val readback = PreparedEs2RenderingReadbackResources(
                retirementLane = { _, _ -> true },
                glObjects = PreparedEs2RenderingReadbackGlObjects(
                    outputTextureId = 1,
                    outputFramebufferId = 2,
                    outputRenderbufferId = 0,
                    programId = 10,
                    vertexShaderId = 11,
                    fragmentShaderId = 12,
                    programBinding = Es2RenderingProgramBindingMetadata(
                        programId = 10,
                        shaderVariant = Es2RenderingShaderVariant.OriginalExternalOes,
                        attributeLocations = Es2RenderingProgramAttributeLocations(position = 1, textureCoordinate = 2),
                        uniformLocations = Es2RenderingProgramUniformLocations(externalOesTextureSampler = 3, textureMatrix = 4),
                        dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                            uniformName = "uTexMatrix",
                            location = 4,
                            matrixElementCount = 16,
                            compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
                        ),
                    ),
                ),
                width = outputPlan.finalImageSize.width,
                height = outputPlan.finalImageSize.height,
                rowStrideBytes = outputPlan.rowStrideBytes,
                readbackBuffer = ByteBuffer.allocateDirect(outputPlan.rgbaByteCount.toInt()),
            )
            val encoderResources = providerEncoderResources?.invoke(request) ?: run {
                val encoder = FakeImageEncoder()
                encoders += encoder
                PreparedImageEncoderResources(
                    encoder = encoder,
                    info = encoder.info,
                    request = outputPlan.encoderRequest,
                    cleanup = ImmediateProviderEncoderCleanup,
                )
            }
            return RenderingPipelinePreparationResult.Success(
                PreparedRenderingPipelineComponents(
                    readbackResources = readback,
                    renderTransformPackage = runtimeProductionTransformPackage(request),
                    encoderResources = encoderResources,
                ),
            )
        }
    }

    private fun runtimeProductionTransformPackage(request: RenderingPipelinePrepareRequest): FirstPlanRenderTransformPackage {
        val outputPlan = request.outputPlan
        return FirstPlanRenderTransformPackage(
            projectionTargetGeneration = request.projectionTarget.generation,
            logicalContentSize = Size(outputPlan.captureGeometry.widthPx, outputPlan.captureGeometry.heightPx),
            captureTargetSize = Size(outputPlan.captureTarget.width, outputPlan.captureTarget.height),
            appliedSourceRect = outputPlan.appliedSourceRect,
            logicalToCaptureTargetMapping = FirstPlanLogicalToCaptureTargetMapping(
                scaleX = 1.0f,
                scaleY = 1.0f,
                sourceRectInCaptureTargetPixels = FirstPlanFloatRect(0.0f, 0.0f, 1.0f, 1.0f),
                sourceRectInCaptureTargetNormalized = FirstPlanFloatRect(0.0f, 0.0f, 1.0f, 1.0f),
            ),
            outputViewport = FirstPlanRenderViewport(0, 0, outputPlan.finalImageSize.width, outputPlan.finalImageSize.height),
            sourceTransformMatrix = FirstPlanRenderMatrix4(identityMatrix4()),
            colorMode = ColorMode.Original,
            programBinding = FirstPlanEs2ProgramBinding(
                shaderVariant = FirstPlanEs2ShaderVariant.OriginalColor,
                programId = 10,
                positionAttributeLocation = 1,
                textureCoordinateAttributeLocation = 2,
                textureSamplerUniformLocation = 3,
                textureMatrixUniformLocation = 4,
            ),
            dynamicOesMatrixSlot = FirstPlanDynamicOesMatrixSlot(
                uniformName = "uTexMatrix",
                uniformLocation = 4,
                valueShape = FirstPlanMatrixValueShape.Mat4ColumnMajorFloatArray,
            ),
            oesCompositionRule = FirstPlanOesCompositionRule.DynamicOesMatrixAfterStaticPlanTransform,
            encoderInputNormalizationStrategy = FirstPlanEncoderInputNormalizationStrategy.RenderSpaceVerticalInversion,
            readbackShape = FirstPlanReadbackShape(
                width = outputPlan.encoderRequest.width,
                height = outputPlan.encoderRequest.height,
                rowStrideBytes = outputPlan.encoderRequest.rowStrideBytes,
                byteCount = outputPlan.rgbaByteCount,
                inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
                readbackMode = ReadbackMode.Es2,
                rowOrder = FirstPlanReadbackRowOrder.TopToBottom,
            ),
        )
    }

    private class RuntimeProductionGles20Api : RuntimeGles20Api {
        override fun getIntegerv(pname: Int, params: IntArray, offset: Int) {
            when (pname) {
                GLES20.GL_ACTIVE_TEXTURE -> params[offset] = GLES20.GL_TEXTURE0
                GLES20.GL_TEXTURE_BINDING_2D -> params[offset] = 0
                GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES -> params[offset] = 0
                GLES20.GL_ARRAY_BUFFER_BINDING -> params[offset] = 0
                GLES20.GL_FRAMEBUFFER_BINDING -> params[offset] = 0
                GLES20.GL_CURRENT_PROGRAM -> params[offset] = 0
                GLES20.GL_PACK_ALIGNMENT -> params[offset] = 4
                GLES20.GL_VIEWPORT -> {
                    params[offset] = 0
                    params[offset + 1] = 0
                    params[offset + 2] = 1
                    params[offset + 3] = 1
                }

                else -> error("Unexpected getIntegerv pname $pname.")
            }
        }

        override fun activeTexture(texture: Int) = Unit
        override fun bindTexture(target: Int, texture: Int) = Unit
        override fun bindBuffer(target: Int, buffer: Int) = Unit
        override fun useProgram(program: Int) = Unit
        override fun bindFramebuffer(target: Int, framebuffer: Int) = Unit
        override fun viewport(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer) = Unit
        override fun getVertexAttribiv(index: Int, pname: Int, params: IntArray, offset: Int) {
            params[offset] = 0
        }

        override fun enableVertexAttribArray(index: Int) = Unit
        override fun disableVertexAttribArray(index: Int) = Unit
        override fun uniform1i(location: Int, value: Int) = Unit
        override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) = Unit
        override fun pixelStorei(pname: Int, param: Int) = Unit
        override fun drawArrays(mode: Int, first: Int, count: Int) = Unit
        override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
            (pixels as ByteBuffer).put(0, 42)
        }
    }

    private class BlockingReadPixelsRuntimeGles20Api(
        private val readPixelsEntered: CountDownLatch,
        private val releaseReadPixels: CountDownLatch,
    ) : RuntimeGles20Api by RuntimeProductionGles20Api() {
        override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
            readPixelsEntered.countDown()
            check(releaseReadPixels.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release fake readPixels."
            }
            (pixels as ByteBuffer).put(0, 24)
        }
    }

    private class ThrowingReadPixelsRuntimeGles20Api : RuntimeGles20Api by RuntimeProductionGles20Api() {
        override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
            throw IllegalStateException("synthetic readPixels failure")
        }
    }

    private fun identityMatrix4(): FloatArray =
        floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )

    private companion object {
        const val TIMEOUT_MILLIS: Long = 2_000L
    }

    private object DirectCoroutineDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }
}
