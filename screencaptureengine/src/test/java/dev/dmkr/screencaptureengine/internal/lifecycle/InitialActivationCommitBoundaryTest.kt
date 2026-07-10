package dev.dmkr.screencaptureengine.internal.lifecycle

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
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
import dev.dmkr.screencaptureengine.internal.rendering.es2.ImageEncoderPrepareOperation
import dev.dmkr.screencaptureengine.internal.rendering.es2.PreparedEs2RenderingReadbackGlObjects
import dev.dmkr.screencaptureengine.internal.rendering.es2.PreparedEs2RenderingReadbackResources
import dev.dmkr.screencaptureengine.internal.rendering.es2.RuntimeEs2FrameRenderer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationFailure
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
import org.junit.Assert.assertNotSame
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
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
    fun initialActiveCommitCreatesSessionCoreWithRunningActiveStateAndAppliesNormalizedNoOp() = runTest {
        val initialParameters = ScreenCaptureParameters(encoderProvider = FakeImageEncoderProvider())
        val fixture = prepareInitialRuntimeOwner(initialParameters = initialParameters)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitVisibility(false)

        val session = activeOwner.commitInitialActiveSession()
        val running = session.state.value as ScreenCaptureSessionState.Running
        val active = running.output as ScreenCaptureOutputState.Active
        val stateBeforeUpdate = session.state.value
        val statsBeforeUpdate = session.stats.value
        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())
        val result = session.setParameters(initialParameters)
        val stateAfterUpdate = session.state.value
        val statsAfterUpdate = session.stats.value
        val generationAfterUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())

        assertEquals(false, running.capturedContentVisible)
        assertEquals(fixture.preparedPlan.outputPlan.toEffectiveParameters(fixture.encoder.info), active.effectiveParameters)
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(stateBeforeUpdate, stateAfterUpdate)
        assertEquals(statsBeforeUpdate, statsAfterUpdate)
        assertEquals(generationBeforeUpdate, generationAfterUpdate)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertSame(session, activeOwner.sessionForTesting)
        session.close()
        runCurrent()
    }

    @Test
    fun normalizedNoOpWithSameProviderInstanceAppliesWithoutMutatingRuntimeState() = runTest {
        val provider = FakeImageEncoderProvider()
        val initialParameters = ScreenCaptureParameters(encoderProvider = provider)
        val fixture = prepareInitialRuntimeOwner(initialParameters = initialParameters)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()
        val events = Collections.synchronizedList(mutableListOf<ScreenCaptureEventType>())
        val eventCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { event -> events += event.type }
        }
        val stateBeforeUpdate = session.state.value
        val statsBeforeUpdate = session.stats.value
        val loopBeforeUpdate = activeOwner.runtimeFrameLoopSnapshot()
        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())

        val result = session.setParameters(initialParameters)
        runCurrent()

        eventCollector.cancel()
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(statsBeforeUpdate, session.stats.value)
        assertRuntimeFrameLoopSnapshotEquals(loopBeforeUpdate, activeOwner.runtimeFrameLoopSnapshot())
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(emptyList<ScreenCaptureEventType>(), events.toList())
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun normalizedNoOpComparesFrameRateAutoAfterEffectiveResolution() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(30),
                encoderProvider = provider,
            ),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()
        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())

        val result = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.Auto,
                encoderProvider = provider,
            ),
        )

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(
            FrameRate.MaxFps(30),
            ((session.state.value as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Active).effectiveParameters.frameRate
        )
        session.close()
        runCurrent()
    }

    @Test
    fun equalButDistinctProviderInstanceIsProviderOnlyAndAppliesWithRuntimeEncoderPreparation() = runTest {
        val initialProvider = EqualFakeImageEncoderProvider(equalityKey = "same")
        val requestedProvider = EqualFakeImageEncoderProvider(equalityKey = "same")
        val preparedRuntimeEncoder = FakeImageEncoder()
        assertEquals(initialProvider, requestedProvider)
        assertNotSame(initialProvider, requestedProvider)
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = initialProvider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = runtimeEncoderPrepareOperation { preparedRuntimeEncoder },
        )
        val session = activeOwner.commitInitialActiveSession()
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()

        val result = session.setParameters(
            ScreenCaptureParameters(encoderProvider = requestedProvider),
        )
        val active = (session.state.value as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Active

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(Math.addExact(checkNotNull(generationBeforeUpdate), 1L), activeOwner.currentOutputGenerationForTesting())
        assertEquals(requestedProvider.id, active.effectiveParameters.encoderInfo.providerId)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, preparedRuntimeEncoder.closeCount)
        session.close()
        runCurrent()
        assertEquals(1, preparedRuntimeEncoder.closeCount)
    }

    @Test
    fun providerOnlyPreparationFailurePreservesPreviousRuntimePlanAndResources() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = ImageEncoderPrepareOperation { _, _, _ ->
                ImageEncoderPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                    message = "candidate rejected",
                    cause = null,
                )
            },
        )
        val session = activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()

        val rejected = session.setParameters(
            ScreenCaptureParameters(encoderProvider = FakeImageEncoderProvider()),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.EncoderValidationFailed, rejected.problem.kind)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun providerOnlyStalePreparedCandidateIsClosedAndPreviousPlanRemainsActive() = runTest {
        val provider = FakeImageEncoderProvider()
        val staleCandidateEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = ImageEncoderPrepareOperation { token, requestedProvider, request ->
                token.invalidate()
                ImageEncoderPreparationResult.Success(
                    PreparedImageEncoderResources(
                        encoder = staleCandidateEncoder,
                        info = ImageEncoderInfo(
                            providerId = requestedProvider.id,
                            outputFormat = requestedProvider.outputFormat,
                            backendName = "stale",
                        ),
                        request = request,
                        cleanup = ImmediateProviderEncoderCleanup,
                    ),
                )
            },
        )
        val session = activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()

        val rejected = session.setParameters(
            ScreenCaptureParameters(encoderProvider = FakeImageEncoderProvider()),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(1, staleCandidateEncoder.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun providerOnlyTerminalBeforeCommitRejectsAndClosesCandidateWithoutPublicMutation() = runTest {
        val provider = FakeImageEncoderProvider()
        val candidateEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = runtimeEncoderPrepareOperation { candidateEncoder },
        )
        val session = activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()
        activeOwner.setBeforeRuntimeParameterCommitBridgeForTesting {
            fixture.runtime.callbackRegistration.emitStop()
        }

        val rejected = session.setParameters(
            ScreenCaptureParameters(encoderProvider = FakeImageEncoderProvider()),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(1, candidateEncoder.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        eventually("projection-stop terminal state") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        runCurrent()
    }

    @Test
    fun providerOnlyTerminalDuringPreparationInvalidatesWorkerAndClosesLateCandidate() = runTest {
        val initialProvider = FakeImageEncoderProvider()
        val requestedProvider = FakeImageEncoderProvider(id = "replacement-provider")
        val candidateEncoder = FakeImageEncoder(
            info = ImageEncoderInfo(
                providerId = requestedProvider.id,
                outputFormat = requestedProvider.outputFormat,
                backendName = "late-terminal-candidate",
            ),
        )
        val providerCreateEntered = CountDownLatch(1)
        val releaseProviderCreate = CountDownLatch(1)
        requestedProvider.encoderFactory = { candidateEncoder }
        requestedProvider.beforeCreate = {
            providerCreateEntered.countDown()
            var released = false
            while (!released) {
                try {
                    released = releaseProviderCreate.await(10L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Simulate provider code that ignores interruption and returns late.
                }
            }
        }
        val providerContext = ProviderPreparationContext()
        val encoderPreparer = ImageEncoderPreparer(providerContext)
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = initialProvider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = ImageEncoderPrepareOperation { token, provider, request ->
                encoderPreparer.prepare(token = token, provider = provider, request = request)
            },
        )
        val session = activeOwner.commitInitialActiveSession()
        val update = async(Dispatchers.Default) {
            session.setParameters(
                ScreenCaptureParameters(encoderProvider = requestedProvider),
            )
        }
        assertTrue("provider creation entered", providerCreateEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        session.stop()
        try {
            eventually("terminal update rejection without waiting for provider return") {
                update.isCompleted
            }
        } finally {
            releaseProviderCreate.countDown()
        }
        val rejected = update.await() as ScreenCaptureParameterUpdateResult.Rejected
        eventually("late terminal candidate cleanup") {
            candidateEncoder.closeCount == 1
        }

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertTrue(session.state.value is ScreenCaptureSessionState.Stopped)
        assertEquals(1, candidateEncoder.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, fixture.readbackResources.closeCount)
        providerContext.close()
        runCurrent()
    }

    @Test
    fun providerOnlyCancellationAfterSuccessfulPreparationClosesCandidateWithoutPublicMutation() = runTest {
        val provider = FakeImageEncoderProvider()
        val candidateEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = ImageEncoderPrepareOperation { _, requestedProvider, request ->
                currentCoroutineContext()[Job]?.cancel(CancellationException("cancel after successful provider preparation"))
                ImageEncoderPreparationResult.Success(
                    PreparedImageEncoderResources(
                        encoder = candidateEncoder,
                        info = ImageEncoderInfo(
                            providerId = requestedProvider.id,
                            outputFormat = requestedProvider.outputFormat,
                            backendName = "cancelled-candidate",
                        ),
                        request = request,
                        cleanup = ImmediateProviderEncoderCleanup,
                    ),
                )
            },
        )
        val session = activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()

        val update = async {
            session.setParameters(
                ScreenCaptureParameters(encoderProvider = FakeImageEncoderProvider()),
            )
        }
        val cancellation = runCatching { update.await() }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(1, candidateEncoder.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(0, fixture.readbackResources.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetReplacementPreparesAndAtomicallyInstallsCompleteResources() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.activeOwner
        val session = activeOwner.commitInitialActiveSession()
        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())

        val result = session.setParameters(
            ScreenCaptureParameters(
                mirror = Mirror.Horizontal,
                encoderProvider = provider,
            ),
        )
        val active = (session.state.value as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Active

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(Math.addExact(generationBeforeUpdate, 1L), activeOwner.currentOutputGenerationForTesting())
        assertEquals(Mirror.Horizontal, active.effectiveParameters.mirror)
        assertEquals(2, fixture.createdEncoderCountForTesting())
        assertEquals(2, fixture.readbackPreparationCountForTesting())
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, fixture.retiredReadbackCountForTesting())
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetPreparationFailurePreservesPreviousPlanAndResources() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
            runtimeOutputPlanPrepareOverride = {
                RenderingPipelinePreparationResult.Failure(
                    RenderingPipelinePreparationFailure(
                        kind = ScreenCaptureProblemKind.GlResourceFailure,
                        message = "candidate GL resources unavailable",
                    ),
                )
            },
        )
        val session = fixture.activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = fixture.activeOwner.currentOutputGenerationForTesting()

        val rejected = session.setParameters(
            ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, rejected.problem.kind)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, fixture.activeOwner.currentOutputGenerationForTesting())
        assertEquals(1, fixture.createdEncoderCountForTesting())
        assertEquals(1, fixture.readbackPreparationCountForTesting())
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(0, fixture.retiredReadbackCountForTesting())
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetPreparationDoesNotPublishOrMutateActivePlanBeforeCommit() = runTest {
        val provider = FakeImageEncoderProvider()
        val candidatePrepared = CountDownLatch(1)
        val releaseCandidate = CountDownLatch(1)
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
            afterRuntimeOutputPlanResourcesPrepared = {
                candidatePrepared.countDown()
                check(releaseCandidate.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release full-plan candidate."
                }
            },
        )
        val session = fixture.activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val statsBeforeUpdate = session.stats.value
        val generationBeforeUpdate = fixture.activeOwner.currentOutputGenerationForTesting()
        val update = async(Dispatchers.Default) {
            session.setParameters(
                ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
            )
        }
        assertTrue("full-plan candidate prepared", candidatePrepared.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(statsBeforeUpdate, session.stats.value)
        assertEquals(generationBeforeUpdate, fixture.activeOwner.currentOutputGenerationForTesting())
        assertEquals(0, fixture.closedEncoderCountForTesting())
        assertEquals(0, fixture.retiredReadbackCountForTesting())

        releaseCandidate.countDown()
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, update.await())
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetStaleLateSuccessClosesCandidateAndPreservesPreviousPlan() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
            afterRuntimeOutputPlanResourcesPrepared = { request -> request.planPreparationToken.invalidate() },
        )
        val session = fixture.activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = fixture.activeOwner.currentOutputGenerationForTesting()

        val rejected = session.setParameters(
            ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, fixture.activeOwner.currentOutputGenerationForTesting())
        assertEquals(2, fixture.createdEncoderCountForTesting())
        assertEquals(1, fixture.closedEncoderCountForTesting())
        assertEquals(1, fixture.retiredReadbackCountForTesting())
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetCancellationAfterSuccessfulPreparationClosesCandidateWithoutPublicMutation() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
            afterRuntimeOutputPlanResourcesPrepared = {
                currentCoroutineContext()[Job]?.cancel(CancellationException("cancel full candidate"))
            },
        )
        val session = fixture.activeOwner.commitInitialActiveSession()
        val stateBeforeUpdate = session.state.value
        val generationBeforeUpdate = fixture.activeOwner.currentOutputGenerationForTesting()

        val update = async {
            session.setParameters(
                ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
            )
        }
        val cancellation = runCatching { update.await() }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(stateBeforeUpdate, session.state.value)
        assertEquals(generationBeforeUpdate, fixture.activeOwner.currentOutputGenerationForTesting())
        assertEquals(2, fixture.createdEncoderCountForTesting())
        assertEquals(1, fixture.closedEncoderCountForTesting())
        assertEquals(1, fixture.retiredReadbackCountForTesting())
        session.close()
        runCurrent()
    }

    @Test
    fun fullSameTargetTerminalBeforeCommitRejectsAndRollsBackCandidate() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.activeOwner
        val session = activeOwner.commitInitialActiveSession()
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()
        activeOwner.setBeforeRuntimeParameterCommitBridgeForTesting {
            fixture.runtime.callbackRegistration.emitStop()
        }

        val rejected = session.setParameters(
            ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(2, fixture.createdEncoderCountForTesting())
        assertEquals(1, fixture.closedEncoderCountForTesting())
        assertEquals(1, fixture.retiredReadbackCountForTesting())
        eventually("projection-stop terminal state") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        runCurrent()
    }

    @Test
    fun fullSameTargetTerminalDuringPreparationInvalidatesTokenAndClosesLateCandidate() = runTest {
        val provider = FakeImageEncoderProvider()
        val candidatePrepared = CountDownLatch(1)
        val releaseCandidate = CountDownLatch(1)
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
            afterRuntimeOutputPlanResourcesPrepared = {
                candidatePrepared.countDown()
                check(releaseCandidate.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release terminal full-plan candidate."
                }
            },
        )
        val session = fixture.activeOwner.commitInitialActiveSession()
        val update = async(Dispatchers.Default) {
            session.setParameters(
                ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
            )
        }
        assertTrue("terminal full-plan candidate prepared", candidatePrepared.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        session.stop()
        assertTrue(session.state.value is ScreenCaptureSessionState.Stopped)
        releaseCandidate.countDown()
        val rejected = update.await() as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals(2, fixture.closedEncoderCountForTesting())
        assertEquals(2, fixture.retiredReadbackCountForTesting())
        runCurrent()
    }

    @Test
    fun invalidRuntimeParametersReturnPrecisePlannerProblem() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        val invalidCrop = session.setParameters(
            ScreenCaptureParameters(
                crop = CropInsetsPx(left = 10_000, right = 10_000),
                encoderProvider = provider,
            ),
        ) as ScreenCaptureParameterUpdateResult.Rejected
        val invalidCaps = session.setParameters(
            ScreenCaptureParameters(
                outputSize = OutputSize.ScaleFactor(2.0),
                encoderProvider = provider,
            ),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, invalidCrop.problem.kind)
        assertEquals(ScreenCaptureProblemKind.OutputLimitsExceeded, invalidCaps.problem.kind)
        session.close()
        runCurrent()
    }

    @Test
    fun runningSuspendedRuntimeParameterUpdateRemainsUnavailable() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()
        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        runCurrent()
        activeOwner.drainRuntimeProductionTick()
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)

        val rejected = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(15),
                encoderProvider = provider,
            ),
        ) as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
        session.close()
        runCurrent()
    }

    @Test
    fun frameRateOnlyUpdateBumpsGenerationResetsPacingAndDoesNotPrepareOrCloseResources() = runTest {
        val provider = FakeImageEncoderProvider()
        val nowNanos = 1_000L
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(1),
                encoderProvider = provider,
            ),
            elapsedRealtimeNanos = { nowNanos },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val firstTick = activeOwner.drainRuntimeProductionTick()
        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())
        val encoderPreparationCountBeforeUpdate = fixture.createdEncoderCountForTesting()
        val readbackPreparationCountBeforeUpdate = fixture.readbackPreparationCountForTesting()
        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(2),
                encoderProvider = provider,
            ),
        )
        val generationAfterUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val secondTick = activeOwner.drainRuntimeProductionTick()
        val active = (session.state.value as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Active

        assertEquals(RuntimeFrameProductionTickResult.Published, firstTick)
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertEquals(Math.addExact(generationBeforeUpdate, 1L), generationAfterUpdate)
        assertEquals(FrameRate.MaxFps(2), active.effectiveParameters.frameRate)
        assertEquals(RuntimeFrameProductionTickResult.Published, secondTick)
        assertEquals(2, fixture.encoder.encodeCount)
        assertEquals(0L, session.stats.value.droppedFrames.byFrameRatePolicy)
        assertEquals(encoderPreparationCountBeforeUpdate, fixture.createdEncoderCountForTesting())
        assertEquals(readbackPreparationCountBeforeUpdate, fixture.readbackPreparationCountForTesting())
        assertEquals(0, fixture.encoder.closeCount)
        session.close()
        runCurrent()
    }

    @Test
    fun frameRateOnlyUpdateClearsRetainedPeriodicRefreshFrame() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(intervalMillis = 300_000L),
                encoderProvider = provider,
            ),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.Published, activeOwner.drainRuntimeProductionTick())
        assertTrue(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertTrue(activeOwner.hasScheduledPeriodicRefreshForTesting())

        val result = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(30),
                encoderProvider = provider,
            ),
        )

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, result)
        assertFalse(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertFalse(activeOwner.hasScheduledPeriodicRefreshForTesting())
        session.close()
        runCurrent()
    }

    @Test
    fun frameRateOnlyUpdateKeepsProductionPausedUntilOldReadbackWorkFinishesStale() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(1),
                encoderProvider = provider,
            ),
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
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old readback entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(2),
                encoderProvider = provider,
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldWorkInFlight = activeOwner.drainRuntimeProductionTick()
        releaseReadPixels.countDown()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldWorkInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(0L, session.stats.value.droppedFrames.byEncoderBusy)
        session.close()
        runCurrent()
    }

    @Test
    fun providerOnlyUpdateKeepsOldMaterializedEncodeOnOldEncoderAndNewAdmissionPausedUntilStale() = runTest {
        val provider = FakeImageEncoderProvider()
        val replacementEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { replacementEncoder },
        )
        val oldEncodeEntered = CountDownLatch(1)
        val releaseOldEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { oldEncodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseOldEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release old encode."
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old encode entered", oldEncodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val generationBeforeUpdate = checkNotNull(activeOwner.currentOutputGenerationForTesting())
        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider(),
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldEncodeInFlight = activeOwner.drainRuntimeProductionTick()
        releaseOldEncode.countDown()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertEquals(Math.addExact(generationBeforeUpdate, 1L), activeOwner.currentOutputGenerationForTesting())
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldEncodeInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1, replacementEncoder.encodeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, replacementEncoder.closeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(0L, session.stats.value.droppedFrames.byEncoderBusy)
        session.close()
        runCurrent()
        assertEquals(1, replacementEncoder.closeCount)
    }

    @Test
    fun fullSameTargetUpdateDefersOldPipelineCloseAndAvoidsNewGenerationBusyDrops() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val oldEncodeEntered = CountDownLatch(1)
        val releaseOldEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { oldEncodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseOldEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release old full-plan encode."
            }
        }
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old full-plan encode entered", oldEncodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldEncodeInFlight = activeOwner.drainRuntimeProductionTick()
        releaseOldEncode.countDown()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldEncodeInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, fixture.retiredReadbackCountForTesting())
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(0L, session.stats.value.droppedFrames.byEncoderBusy)
        session.close()
        runCurrent()
    }

    @Test
    fun providerOnlyUpdateTurnsOldMaterializedReadbackExceptionIntoStaleDropWithoutFailingCurrentSession() = runTest {
        val provider = FakeImageEncoderProvider()
        val replacementEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { replacementEncoder },
        )
        val readPixelsEntered = CountDownLatch(1)
        val releaseReadPixels = CountDownLatch(1)
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(
            RuntimeEs2FrameRenderer(
                BlockingReadPixelsRuntimeGles20Api(
                    readPixelsEntered = readPixelsEntered,
                    releaseReadPixels = releaseReadPixels,
                    throwAfterRelease = true,
                ),
            ),
        )
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old readback entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider(),
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldWorkInFlight = activeOwner.drainRuntimeProductionTick()
        releaseReadPixels.countDown()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldWorkInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertTrue(session.state.value is ScreenCaptureSessionState.Running)
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, replacementEncoder.closeCount)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byTransientFailure)
        session.close()
        runCurrent()
        assertEquals(1, replacementEncoder.closeCount)
    }

    @Test
    fun providerOnlyUpdateTurnsOldMaterializedGlTimeoutIntoStaleDropWithoutFailingCurrentSession() = runTest {
        val provider = FakeImageEncoderProvider()
        val replacementEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { replacementEncoder },
        )
        fixture.runtime.targetOwner.suspendRuntimeAccessUntilCancelledWithoutLateResult = true
        val attemptMaterialized = CountDownLatch(1)
        val activeOwner = fixture.activeOwner
        activeOwner.overrideGlOperationTimeoutForTesting(timeoutMillis = 200L)
        activeOwner.setAfterProductionAttemptMaterializedForTesting {
            attemptMaterialized.countDown()
        }
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old production attempt materialized", attemptMaterialized.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider(),
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldWorkInFlight = activeOwner.drainRuntimeProductionTick()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        fixture.runtime.targetOwner.suspendRuntimeAccessUntilCancelledWithoutLateResult = false
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldWorkInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertTrue(session.state.value is ScreenCaptureSessionState.Running)
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, replacementEncoder.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.abandonGlLaneCount)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byTransientFailure)
        session.close()
        runCurrent()
        assertEquals(1, replacementEncoder.closeCount)
    }

    @Test
    fun providerOnlyUpdateTurnsOldMaterializedEncodeTimeoutIntoStaleDropWithoutFailingCurrentSession() = runTest {
        val provider = FakeImageEncoderProvider()
        val replacementEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { replacementEncoder },
        )
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
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider(),
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldEncodeInFlight = activeOwner.drainRuntimeProductionTick()
        val oldTickResult = oldTick.await()
        val pausedAfterOldTimeout = activeOwner.runtimeFrameLoopSnapshot()
        releaseEncode.countDown()
        eventually("old timed-out encoder returned and retired") {
            fixture.encoder.closeCount == 1 && !activeOwner.runtimeFrameLoopSnapshot().productionAdmissionPaused
        }
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldEncodeInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertTrue(pausedAfterOldTimeout.productionAdmissionPaused)
        assertTrue(session.state.value is ScreenCaptureSessionState.Running)
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1, replacementEncoder.encodeCount)
        assertEquals(0, replacementEncoder.closeCount)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byTransientFailure)
        session.close()
        runCurrent()
        assertEquals(1, replacementEncoder.closeCount)
    }

    @Test
    fun providerOnlyUpdateTurnsOldMaterializedEncodeExceptionIntoStaleDropWithoutFailingCurrentSession() = runTest {
        val provider = FakeImageEncoderProvider()
        val replacementEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { replacementEncoder },
        )
        val encodeEntered = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        fixture.encoder.onEncodeEntered = { encodeEntered.countDown() }
        fixture.encoder.awaitEncodeRelease = {
            check(releaseEncode.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release old encode."
            }
        }
        fixture.encoder.encodeFailure = IllegalStateException("old encoder failed")
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) {
            activeOwner.drainRuntimeProductionTick()
        }
        assertTrue("old encode entered", encodeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val updateResult = session.setParameters(
            ScreenCaptureParameters(
                encoderProvider = FakeImageEncoderProvider(),
            ),
        )
        val pausedAfterCommit = activeOwner.runtimeFrameLoopSnapshot()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val tickWhileOldEncodeInFlight = activeOwner.drainRuntimeProductionTick()
        releaseEncode.countDown()
        val oldTickResult = oldTick.await()
        val resumedAfterOldWork = activeOwner.runtimeFrameLoopSnapshot()
        val newTick = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult)
        assertTrue(pausedAfterCommit.productionAdmissionPaused)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, tickWhileOldEncodeInFlight)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldTickResult)
        assertFalse(resumedAfterOldWork.productionAdmissionPaused)
        assertTrue(session.state.value is ScreenCaptureSessionState.Running)
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(RuntimeFrameProductionTickResult.Published, newTick)
        assertEquals(1, fixture.encoder.encodeCount)
        assertEquals(1, replacementEncoder.encodeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, replacementEncoder.closeCount)
        assertEquals(0, fixture.runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, fixture.runtime.targetOwner.closeCount)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        assertEquals(0L, session.stats.value.droppedFrames.byTransientFailure)
        session.close()
        runCurrent()
        assertEquals(1, replacementEncoder.closeCount)
    }

    @Test
    fun setParametersAfterOwnerStopRejectsWithOwnerStopTerminalProblem() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        session.stop()
        val rejected = session.setParameters(ScreenCaptureParameters.defaults()) as ScreenCaptureParameterUpdateResult.Rejected
        runCurrent()

        val stopped = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, stopped.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals("Session was stopped by its owner.", rejected.problem.message)
    }

    @Test
    fun ownerStopRacingWhileRuntimeParameterBridgeHoldsOwnerLockCompletesWithoutDeadlock() = runTest {
        val provider = FakeImageEncoderProvider()
        val initialParameters = ScreenCaptureParameters(
            frameRate = FrameRate.MaxFps(30),
            encoderProvider = provider,
        )
        val fixture = prepareInitialRuntimeOwner(initialParameters = initialParameters)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()
        val bridgeEntered = CountDownLatch(1)
        val releaseBridge = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopReachedOwnerLockCheckpoint = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val updateResult = AtomicReference<ScreenCaptureParameterUpdateResult?>()
        activeOwner.setBeforeRuntimeParameterCommitBridgeForTesting {
            bridgeEntered.countDown()
            check(releaseBridge.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release runtime parameter bridge."
            }
        }
        activeOwner.setBeforeOwnerStopTerminalCommitForTesting {
            stopReachedOwnerLockCheckpoint.countDown()
        }

        val updateJob = launch(Dispatchers.Default) {
            updateResult.set(
                session.setParameters(
                    ScreenCaptureParameters(
                        frameRate = FrameRate.MaxFps(15),
                        encoderProvider = provider,
                    ),
                ),
            )
        }
        assertTrue("runtime parameter bridge entered", bridgeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        val stopThread = Thread {
            stopStarted.countDown()
            session.stop()
            stopReturned.countDown()
        }
        stopThread.start()
        assertTrue("owner stop started", stopStarted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        try {
            eventually("owner stop blocked on runtime owner lock") {
                stopThread.state == Thread.State.BLOCKED
            }
        } finally {
            releaseBridge.countDown()
        }

        updateJob.join()
        stopThread.join(TIMEOUT_MILLIS)
        assertFalse("owner stop thread remained alive", stopThread.isAlive)
        assertTrue(
            "owner stop reached terminal commit checkpoint after runtime bridge released",
            stopReachedOwnerLockCheckpoint.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )
        assertTrue("owner stop returned", stopReturned.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        runCurrent()

        val stopped = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, updateResult.get())
        assertEquals(ScreenCaptureStopReason.OwnerStop, stopped.reason)
    }

    @Test
    fun projectionStopBeforeRuntimeParameterBridgeCommitRejectsWithProjectionTerminalProblem() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.callbackRegistration.emitStop()
        val rejected = session.setParameters(ScreenCaptureParameters.defaults()) as ScreenCaptureParameterUpdateResult.Rejected

        eventually("projection-stop terminal state") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        val stopped = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, stopped.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, stopped.problem?.kind)
    }

    @Test
    fun projectionStopRacingWhileRuntimeParameterBridgeHoldsOwnerLockCompletesWithoutDeadlock() = runTest {
        val provider = FakeImageEncoderProvider()
        val candidateEncoder = FakeImageEncoder()
        val fixture = prepareInitialRuntimeOwner(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
        )
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            encoderPrepare = runtimeEncoderPrepareOperation { candidateEncoder },
        )
        val session = activeOwner.commitInitialActiveSession()
        val generationBeforeUpdate = activeOwner.currentOutputGenerationForTesting()
        val bridgeEntered = CountDownLatch(1)
        val releaseBridge = CountDownLatch(1)
        val projectionStopStarted = CountDownLatch(1)
        val projectionStopReturned = CountDownLatch(1)
        val updateResult = AtomicReference<ScreenCaptureParameterUpdateResult?>()
        activeOwner.setBeforeRuntimeParameterCommitBridgeForTesting {
            bridgeEntered.countDown()
            check(releaseBridge.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release runtime parameter bridge."
            }
        }

        val updateJob = launch(Dispatchers.Default) {
            updateResult.set(
                session.setParameters(
                    ScreenCaptureParameters(
                        encoderProvider = FakeImageEncoderProvider(),
                    ),
                ),
            )
        }
        assertTrue("runtime parameter bridge entered", bridgeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        val projectionStopThread = Thread {
            projectionStopStarted.countDown()
            fixture.runtime.callbackRegistration.emitStop()
            projectionStopReturned.countDown()
        }
        projectionStopThread.start()
        assertTrue("projection stop started", projectionStopStarted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        try {
            eventually("projection stop blocked on runtime owner lock") {
                projectionStopThread.state == Thread.State.BLOCKED
            }
        } finally {
            releaseBridge.countDown()
        }

        updateJob.join()
        projectionStopThread.join(TIMEOUT_MILLIS)
        assertFalse("projection stop thread remained alive", projectionStopThread.isAlive)
        assertTrue("projection stop returned", projectionStopReturned.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        eventually("projection-stop terminal state") {
            session.state.value is ScreenCaptureSessionState.Stopped
        }
        val stopped = session.state.value as ScreenCaptureSessionState.Stopped
        runCurrent()
        val rejected = updateResult.get() as ScreenCaptureParameterUpdateResult.Rejected

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejected.problem.kind)
        assertEquals(generationBeforeUpdate, activeOwner.currentOutputGenerationForTesting())
        assertEquals(1, candidateEncoder.closeCount)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, stopped.reason)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, stopped.problem?.kind)
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
    fun prePublicProjectionStopDoesNotDrainBeforeReturnedSessionIsArmed() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner(
            config = fixture.config,
            commitBoundary = InitialActivationCommitBoundary {
                fixture.runtime.callbackRegistration.emitStop()
            },
        )

        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val beforeArmState = session.state.value
        activeOwner.armReturnedSessionRuntimeSignals()
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val afterArmState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(initialState, beforeArmState)
        assertTrue(afterArmState is ScreenCaptureSessionState.Stopped)
        assertEquals(ScreenCaptureStopReason.CaptureEnded, (afterArmState as ScreenCaptureSessionState.Stopped).reason)
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
    fun prePublicGeometryChangeDoesNotSuspendBeforeReturnedSessionIsArmed() = runTest {
        val fixture = prepareInitialRuntimeOwner(apiLevel = 34)
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        fixture.runtime.callbackRegistration.emitResize(width = 640, height = 360)

        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val initialState = session.state.value
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val beforeArmState = session.state.value
        activeOwner.armReturnedSessionRuntimeSignals()
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val afterArmState = session.state.value
        session.close()
        runCurrent()

        assertTrue(initialState is ScreenCaptureSessionState.Running)
        assertTrue((initialState as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(initialState, beforeArmState)
        assertTrue(afterArmState is ScreenCaptureSessionState.Running)
        val suspended = (afterArmState as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Suspended
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, suspended.problem.kind)
        assertEquals(640, suspended.currentCaptureGeometry.widthPx)
        assertEquals(360, suspended.currentCaptureGeometry.heightPx)
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
        assertTrue(
            "Expected terminalState to be Stopped, was ${terminalState.describeForAssertion()}",
            terminalState is ScreenCaptureSessionState.Stopped,
        )
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
    fun postCommitMetricsProviderUpdateAutomaticallySuspendsWithoutOtherRuntimeSignals() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()

        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560))
        runCurrent()

        eventually("automatic runtime metrics suspension") {
            (session.state.value as? ScreenCaptureSessionState.Running)?.output is ScreenCaptureOutputState.Suspended
        }
        val running = session.state.value as ScreenCaptureSessionState.Running
        val suspended = running.output as ScreenCaptureOutputState.Suspended
        session.close()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, suspended.problem.kind)
        assertEquals(1440, suspended.currentCaptureGeometry.widthPx)
        assertEquals(2560, suspended.currentCaptureGeometry.heightPx)
        assertEquals(560, suspended.currentCaptureGeometry.densityDpi)
        assertEquals(0L, session.stats.value.framesPublished)
    }

    @Test
    fun postCommitUnavailableMetricsAreIgnoredAndDiagnosticIsEmitted() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession()
        val initialState = session.state.value as ScreenCaptureSessionState.Running
        val events = Collections.synchronizedList(mutableListOf<ScreenCaptureEventType>())
        val eventCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { event -> events += event.type }
        }

        fixture.runtime.metricsProvider.updateUnavailable(
            reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
            message = "source removed",
        )
        runCurrent()

        eventually("invalid runtime metrics diagnostic") {
            events.contains(ScreenCaptureEventType.InvalidMetricsIgnored)
        }
        val afterState = session.state.value as ScreenCaptureSessionState.Running
        eventCollector.cancel()
        session.close()
        runCurrent()

        assertEquals(initialState.output, afterState.output)
        assertEquals(initialState.capturedContentVisible, afterState.capturedContentVisible)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun prePublicUnavailableMetricsAreReportedAfterCommitWithoutSuspendingOutput() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        fixture.runtime.metricsProvider.updateUnavailable(
            reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
            message = "source removed before public commit",
        )
        runCurrent()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val initialState = session.state.value as ScreenCaptureSessionState.Running
        val events = Collections.synchronizedList(mutableListOf<ScreenCaptureEventType>())
        val eventCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { event -> events += event.type }
        }

        activeOwner.armReturnedSessionRuntimeSignals()
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val afterDrainState = session.state.value as ScreenCaptureSessionState.Running
        eventCollector.cancel()
        session.close()
        runCurrent()

        assertTrue(events.contains(ScreenCaptureEventType.InvalidMetricsIgnored))
        assertEquals(initialState.output, afterDrainState.output)
        assertTrue(afterDrainState.output is ScreenCaptureOutputState.Active)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun prePublicUnavailableMetricsDiagnosticDoesNotEmitBeforeReturnedSessionIsArmed() = runTest {
        val fixture = prepareInitialRuntimeOwner()
        fixture.runtime.metricsProvider.updateUnavailable(
            reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
            message = "source removed before public commit",
        )
        runCurrent()
        val activeOwner = fixture.transferToActiveRuntimeOwner()
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val initialState = session.state.value as ScreenCaptureSessionState.Running
        val events = Collections.synchronizedList(mutableListOf<ScreenCaptureEventType>())
        val eventCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { event -> events += event.type }
        }

        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val beforeArmEvents = events.toList()
        val beforeArmState = session.state.value as ScreenCaptureSessionState.Running
        activeOwner.armReturnedSessionRuntimeSignals()
        activeOwner.drainQueuedPostCommitRuntimeSignals()
        val afterArmState = session.state.value as ScreenCaptureSessionState.Running
        eventCollector.cancel()
        session.close()
        runCurrent()

        assertFalse(beforeArmEvents.contains(ScreenCaptureEventType.InvalidMetricsIgnored))
        assertTrue(events.contains(ScreenCaptureEventType.InvalidMetricsIgnored))
        assertEquals(initialState.output, beforeArmState.output)
        assertEquals(initialState.output, afterArmState.output)
        assertTrue(afterArmState.output is ScreenCaptureOutputState.Active)
        assertEquals(0L, session.stats.value.framesPublished)
        assertEquals(0L, session.stats.value.droppedFrames.total)
    }

    @Test
    fun metricsUpdateDuringScheduledRuntimeTurnIsNotLost() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction()
        val activeOwner = fixture.activeOwner
        val readPixelsEntered = CountDownLatch(1)
        val releaseReadPixels = CountDownLatch(1)
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
        fixture.runtime.metricsProvider.update(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 560))
        runCurrent()
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)

        releaseReadPixels.countDown()
        eventually("runtime metrics update after in-flight turn") {
            (session.state.value as? ScreenCaptureSessionState.Running)?.output is ScreenCaptureOutputState.Suspended
        }
        val suspended = (session.state.value as ScreenCaptureSessionState.Running).output as ScreenCaptureOutputState.Suspended
        session.close()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, suspended.problem.kind)
        assertEquals(560, suspended.currentCaptureGeometry.densityDpi)
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
    fun thirdCurrentProviderFailureSuspendsPersistentlyWithOneGenerationBumpAndEvent() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val events = mutableListOf<ScreenCaptureEventType>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { event -> events += event.type }
        }
        val initialGeneration = checkNotNull(activeOwner.currentOutputGenerationForTesting())

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val first = activeOwner.drainRuntimeProductionTick()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val second = activeOwner.drainRuntimeProductionTick()
        val stateAfterTwo = session.state.value
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val third = activeOwner.drainRuntimeProductionTick()
        runCurrent()

        val running = session.state.value as ScreenCaptureSessionState.Running
        val suspended = running.output as ScreenCaptureOutputState.Suspended
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, first)
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, second)
        assertTrue((stateAfterTwo as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, third)
        assertEquals(ScreenCaptureProblemKind.EncodeRepeatedFailure, suspended.problem.kind)
        assertEquals(Math.addExact(initialGeneration, 1L), activeOwner.currentOutputGenerationForTesting())
        assertEquals(3L, session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(1, events.count { event -> event == ScreenCaptureEventType.OutputPlanSuspended })
        assertTrue(activeOwner.runtimeFrameLoopSnapshot().productionAdmissionPaused)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        activeOwner.recordPeriodicRefreshWakeForTesting()
        fixture.runtime.callbackRegistration.emitVisibility(false)
        assertEquals(RuntimeFrameProductionTickResult.NoFrameSignal, activeOwner.drainRuntimeProductionTick())
        val paused = activeOwner.runtimeFrameLoopSnapshot()
        assertTrue(paused.sourceFrameSignalPending)
        assertTrue(paused.periodicRefreshWakePending)
        assertEquals(false, (session.state.value as ScreenCaptureSessionState.Running).capturedContentVisible)
        assertEquals(3, fixture.encoder.encodeCount)

        collector.cancel()
        session.close()
        runCurrent()
    }

    @Test
    fun providerThrowAndProtocolSuccessWithoutBytesAreCountedHardFailures() = runTest {
        val threwFixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        threwFixture.encoder.encodeFailure = IllegalStateException("provider threw")
        val threwOwner = threwFixture.activeOwner
        threwOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val threwSession = threwOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(3) {
            threwFixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeThrewDrop, threwOwner.drainRuntimeProductionTick())
        }
        val threwSuspended = (threwSession.state.value as ScreenCaptureSessionState.Running).output
        assertTrue(threwSuspended is ScreenCaptureOutputState.Suspended)

        val protocolFixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        protocolFixture.encoder.encodedBytes = byteArrayOf()
        val protocolOwner = protocolFixture.activeOwner
        protocolOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val protocolSession = protocolOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(3) {
            protocolFixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, protocolOwner.drainRuntimeProductionTick())
        }
        val protocolSuspended = (protocolSession.state.value as ScreenCaptureSessionState.Running).output
        assertTrue(protocolSuspended is ScreenCaptureOutputState.Suspended)

        threwSession.close()
        protocolSession.close()
        runCurrent()
    }

    @Test
    fun validEncodeSuccessResetsHardFailureStreakBeforeLaterFailures() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)

        fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        repeat(2) {
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
        }
        fixture.encoder.encodeResult = ImageEncodeResult.Success
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.Published, activeOwner.drainRuntimeProductionTick())
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed again")
        repeat(2) {
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
        }
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)

        session.close()
        runCurrent()
    }

    @Test
    fun capDropsDoNotIncrementOrResetCurrentHardFailureStreak() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            configMaxEncodedBytes = 1_024,
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)

        repeat(2) {
            fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            fixture.encoder.encodeResult = ImageEncodeResult.Success
            fixture.encoder.encodedBytes = ByteArray(1_025)
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodedSizeLimitDrop, activeOwner.drainRuntimeProductionTick())
        }
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("third provider failure")
        fixture.encoder.encodedBytes = byteArrayOf(1)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())

        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)
        assertEquals(3L, session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(2L, session.stats.value.droppedFrames.byEncodedSizeLimit)
        session.close()
        runCurrent()
    }

    @Test
    fun capRejectedThenProviderThrowRemainsEncodedSizeDropAndDoesNotPoisonHealth() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            configMaxEncodedBytes = 1_024,
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        fixture.encoder.encodedBytes = ByteArray(1_025)
        fixture.encoder.encodeFailureAfterWrite = IllegalStateException("provider surfaced sink rejection")
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)

        repeat(3) {
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodedSizeLimitDrop, activeOwner.drainRuntimeProductionTick())
        }

        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
        assertEquals(3L, session.stats.value.droppedFrames.byEncodedSizeLimit)
        assertEquals(0L, session.stats.value.droppedFrames.byTransientFailure)
        session.close()
        runCurrent()
    }

    @Test
    fun normalizedNoOpPreservesButFrameRateCommitResetsHardFailureStreak() = runTest {
        val provider = FakeImageEncoderProvider()
        val initialParameters = ScreenCaptureParameters(
            frameRate = FrameRate.PeriodicRefresh(300_000L),
            encoderProvider = provider,
        )
        val noOpFixture = prepareInitialRuntimeOwnerForProduction(initialParameters = initialParameters)
        noOpFixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val noOpOwner = noOpFixture.activeOwner
        noOpOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val noOpSession = noOpOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            noOpFixture.recordRuntimeFrameAvailableWithoutWake()
            noOpOwner.drainRuntimeProductionTick()
        }
        assertEquals(ScreenCaptureParameterUpdateResult.Applied, noOpSession.setParameters(initialParameters))
        noOpFixture.recordRuntimeFrameAvailableWithoutWake()
        noOpOwner.drainRuntimeProductionTick()
        assertTrue((noOpSession.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)

        val resetFixture = prepareInitialRuntimeOwnerForProduction(initialParameters = initialParameters)
        resetFixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val resetOwner = resetFixture.activeOwner
        resetOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val resetSession = resetOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            resetFixture.recordRuntimeFrameAvailableWithoutWake()
            resetOwner.drainRuntimeProductionTick()
        }
        assertEquals(
            ScreenCaptureParameterUpdateResult.Applied,
            resetSession.setParameters(ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(200_000L), encoderProvider = provider)),
        )
        resetFixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, resetOwner.drainRuntimeProductionTick())
        assertTrue((resetSession.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)

        noOpSession.close()
        resetSession.close()
        runCurrent()
    }

    @Test
    fun ownerStopWinningBeforeThirdHardFailureMakesAttemptStaleWithoutSuspension() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            fixture.recordRuntimeFrameAvailableWithoutWake()
            activeOwner.drainRuntimeProductionTick()
        }
        activeOwner.setBeforeEncodeNonSuccessDropForTesting { session.stop() }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val third = activeOwner.drainRuntimeProductionTick()

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, third)
        assertEquals(ScreenCaptureStopReason.OwnerStop, (session.state.value as ScreenCaptureSessionState.Stopped).reason)
        assertEquals(2L, session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        runCurrent()
    }

    @Test
    fun projectionStopWinningBeforeThirdHardFailureStopsWithoutSuspension() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("provider failed")
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            fixture.recordRuntimeFrameAvailableWithoutWake()
            activeOwner.drainRuntimeProductionTick()
        }
        activeOwner.setBeforeEncodeNonSuccessDropForTesting {
            fixture.runtime.callbackRegistration.emitStopRawOnly()
        }

        fixture.recordRuntimeFrameAvailableWithoutWake()
        val third = activeOwner.drainRuntimeProductionTick()

        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, third)
        val stopped = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.CaptureEnded, stopped.reason)
        assertEquals(2L, session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(1L, session.stats.value.droppedFrames.byStaleGeneration)
        session.close()
        runCurrent()
    }

    @Test
    fun busyCapAndCancellationOutcomesNeitherIncrementNorResetHardFailureStreak() = runTest {
        val fixture = prepareInitialRuntimeOwnerForProduction(
            configMaxEncodedBytes = 1_024,
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(300_000L)),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)

        fixture.encoder.encodeResult = ImageEncodeResult.Failed("first hard failure")
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
        activeOwner.forceEncoderBusyForTesting(true)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncoderBusyDrop, activeOwner.drainRuntimeProductionTick())
        activeOwner.forceEncoderBusyForTesting(false)
        activeOwner.forceReadbackBusyForTesting(true)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.ReadbackBusyDrop, activeOwner.drainRuntimeProductionTick())
        activeOwner.forceReadbackBusyForTesting(false)
        fixture.encoder.encodeResult = ImageEncodeResult.Success
        fixture.encoder.encodedBytes = ByteArray(1_025)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodedSizeLimitDrop, activeOwner.drainRuntimeProductionTick())
        fixture.encoder.encodeFailure = CancellationException("provider cancellation")
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeThrewDrop, activeOwner.drainRuntimeProductionTick())
        fixture.encoder.encodeFailure = null
        fixture.encoder.encodeResult = ImageEncodeResult.Failed("second hard failure")
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
        fixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())

        assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)
        assertEquals(4L, session.stats.value.droppedFrames.byTransientFailure)
        assertEquals(1L, session.stats.value.droppedFrames.byEncoderBusy)
        assertEquals(1L, session.stats.value.droppedFrames.byReadbackBusy)
        assertEquals(1L, session.stats.value.droppedFrames.byEncodedSizeLimit)
        session.close()
        runCurrent()
    }

    @Test
    fun providerOnlyAndFullPlanCommitsStartFreshEncodeHealth() = runTest {
        val provider = FakeImageEncoderProvider()
        val providerReplacement = FakeImageEncoder().apply {
            encodeResult = ImageEncodeResult.Failed("replacement failed")
        }
        val providerFixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            ),
            encoderPrepare = runtimeEncoderPrepareOperation { providerReplacement },
        )
        providerFixture.encoder.encodeResult = ImageEncodeResult.Failed("initial failed")
        val providerOwner = providerFixture.activeOwner
        providerOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val providerSession = providerOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            providerFixture.recordRuntimeFrameAvailableWithoutWake()
            providerOwner.drainRuntimeProductionTick()
        }
        assertEquals(
            ScreenCaptureParameterUpdateResult.Applied,
            providerSession.setParameters(
                ScreenCaptureParameters(
                    frameRate = FrameRate.PeriodicRefresh(300_000L),
                    encoderProvider = FakeImageEncoderProvider(),
                ),
            ),
        )
        providerFixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, providerOwner.drainRuntimeProductionTick())
        assertTrue((providerSession.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)

        val fullFixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            ),
        )
        fullFixture.encoder.encodeResult = ImageEncodeResult.Failed("initial failed")
        val fullOwner = fullFixture.activeOwner
        fullOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val fullSession = fullOwner.commitInitialActiveSession(armRuntimeSignals = false)
        repeat(2) {
            fullFixture.recordRuntimeFrameAvailableWithoutWake()
            fullOwner.drainRuntimeProductionTick()
        }
        assertEquals(
            ScreenCaptureParameterUpdateResult.Applied,
            fullSession.setParameters(
                ScreenCaptureParameters(
                    mirror = Mirror.Horizontal,
                    frameRate = FrameRate.PeriodicRefresh(300_000L),
                    encoderProvider = provider,
                ),
            ),
        )
        fullFixture.latestEncoderForTesting().encodeResult = ImageEncodeResult.Failed("full replacement failed")
        fullFixture.recordRuntimeFrameAvailableWithoutWake()
        assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, fullOwner.drainRuntimeProductionTick())
        assertTrue((fullSession.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)

        providerSession.close()
        fullSession.close()
        runCurrent()
    }

    @Test
    fun thresholdWinningBeforePreparedRuntimeUpdatesRejectsAndKeepsPersistentPause() = runTest {
        RuntimeHealthRaceUpdateKind.entries.forEach { updateKind ->
            val provider = FakeImageEncoderProvider()
            val replacementEncoder = FakeImageEncoder()
            val initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            )
            val fixture = prepareInitialRuntimeOwnerForProduction(
                initialParameters = initialParameters,
                encoderPrepare = if (updateKind == RuntimeHealthRaceUpdateKind.ProviderOnly) {
                    runtimeEncoderPrepareOperation { replacementEncoder }
                } else {
                    RuntimeProviderPreparationNotConfiguredForTesting
                },
            )
            fixture.encoder.encodeResult = ImageEncodeResult.Failed("initial provider failed")
            val activeOwner = fixture.activeOwner
            activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
            val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
            repeat(2) {
                fixture.recordRuntimeFrameAvailableWithoutWake()
                assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            }
            val generationBeforeRace = checkNotNull(activeOwner.currentOutputGenerationForTesting())
            val updateReadyForCommit = CountDownLatch(1)
            val releaseUpdateCommit = CountDownLatch(1)
            activeOwner.setBeforeRuntimeParameterCommitOwnerLockForTesting {
                updateReadyForCommit.countDown()
                check(releaseUpdateCommit.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release $updateKind update commit."
                }
            }
            val updateJob = async(Dispatchers.Default) {
                session.setParameters(runtimeHealthRaceUpdateParameters(updateKind, provider))
            }
            assertTrue("$updateKind update prepared", updateReadyForCommit.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            releaseUpdateCommit.countDown()
            val rejected = updateJob.await() as ScreenCaptureParameterUpdateResult.Rejected

            assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, rejected.problem.kind)
            assertEquals(Math.addExact(generationBeforeRace, 1L), activeOwner.currentOutputGenerationForTesting())
            assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)
            assertTrue(activeOwner.runtimeFrameLoopSnapshot().productionAdmissionPaused)
            when (updateKind) {
                RuntimeHealthRaceUpdateKind.FrameRateOnly -> assertEquals(0, fixture.closedEncoderCountForTesting())
                RuntimeHealthRaceUpdateKind.ProviderOnly -> assertEquals(1, replacementEncoder.closeCount)
                RuntimeHealthRaceUpdateKind.FullPlan -> assertEquals(1, fixture.latestEncoderForTesting().closeCount)
            }
            session.close()
            runCurrent()
        }
    }

    @Test
    fun runtimeUpdateWinningBeforeThirdFailureMakesOldAttemptStaleAndStartsFreshHealth() = runTest {
        RuntimeHealthRaceUpdateKind.entries.forEach { updateKind ->
            val provider = FakeImageEncoderProvider()
            val replacementEncoder = FakeImageEncoder()
            val initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            )
            val fixture = prepareInitialRuntimeOwnerForProduction(
                initialParameters = initialParameters,
                encoderPrepare = if (updateKind == RuntimeHealthRaceUpdateKind.ProviderOnly) {
                    runtimeEncoderPrepareOperation { replacementEncoder }
                } else {
                    RuntimeProviderPreparationNotConfiguredForTesting
                },
            )
            fixture.encoder.encodeResult = ImageEncodeResult.Failed("initial provider failed")
            val activeOwner = fixture.activeOwner
            activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
            val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
            repeat(2) {
                fixture.recordRuntimeFrameAvailableWithoutWake()
                assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            }
            val thirdFailureReady = CountDownLatch(1)
            val releaseThirdFailure = CountDownLatch(1)
            activeOwner.setBeforeEncodeNonSuccessDropForTesting {
                thirdFailureReady.countDown()
                check(releaseThirdFailure.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release old $updateKind failure."
                }
            }
            fixture.recordRuntimeFrameAvailableWithoutWake()
            val oldFailure = async(Dispatchers.Default) { activeOwner.drainRuntimeProductionTick() }
            assertTrue("$updateKind old failure ready", thirdFailureReady.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            val update = session.setParameters(runtimeHealthRaceUpdateParameters(updateKind, provider))
            releaseThirdFailure.countDown()
            val oldResult = oldFailure.await()
            activeOwner.setBeforeEncodeNonSuccessDropForTesting(null)
            val currentEncoder = when (updateKind) {
                RuntimeHealthRaceUpdateKind.FrameRateOnly -> fixture.encoder
                RuntimeHealthRaceUpdateKind.ProviderOnly -> replacementEncoder
                RuntimeHealthRaceUpdateKind.FullPlan -> fixture.latestEncoderForTesting()
            }
            currentEncoder.encodeResult = ImageEncodeResult.Failed("replacement provider failed")
            repeat(2) {
                fixture.recordRuntimeFrameAvailableWithoutWake()
                assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            }

            assertEquals(ScreenCaptureParameterUpdateResult.Applied, update)
            assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldResult)
            assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Active)
            assertFalse(activeOwner.runtimeFrameLoopSnapshot().productionAdmissionPaused)
            assertEquals(0, currentEncoder.closeCount)
            fixture.recordRuntimeFrameAvailableWithoutWake()
            assertEquals(RuntimeFrameProductionTickResult.EncodeFailedDrop, activeOwner.drainRuntimeProductionTick())
            assertTrue((session.state.value as ScreenCaptureSessionState.Running).output is ScreenCaptureOutputState.Suspended)
            session.close()
            runCurrent()
        }
    }

    @Test
    fun oldGenerationCannotRepopulatePacingAfterFrameRateCommitReset() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(frameRate = FrameRate.MaxFps(1), encoderProvider = provider),
            elapsedRealtimeNanos = { 1_000L },
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        activeOwner.setBeforeFrameRatePolicyAdmissionForTesting {
            admissionEntered.countDown()
            check(releaseAdmission.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release old pacing admission."
            }
        }
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) { activeOwner.drainRuntimeProductionTick() }
        assertTrue("old pacing admission entered", admissionEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val update = session.setParameters(
            ScreenCaptureParameters(frameRate = FrameRate.MaxFps(2), encoderProvider = provider),
        )
        activeOwner.setBeforeFrameRatePolicyAdmissionForTesting(null)
        releaseAdmission.countDown()
        val oldResult = oldTick.await()
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val newResult = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, update)
        assertEquals(RuntimeFrameProductionTickResult.NotMaterialized, oldResult)
        assertEquals(RuntimeFrameProductionTickResult.Published, newResult)
        assertEquals(0L, session.stats.value.droppedFrames.byFrameRatePolicy)
        session.close()
        runCurrent()
    }

    @Test
    fun oldGenerationSuccessfulReadbackCannotRepopulateNewGenerationSourceSuccess() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(encoderProvider = provider),
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
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) { activeOwner.drainRuntimeProductionTick() }
        assertTrue("old readback entered", readPixelsEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val update = session.setParameters(
            ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(intervalMillis = 1_000L),
                encoderProvider = provider,
            ),
        )
        releaseReadPixels.countDown()
        val oldResult = oldTick.await()
        activeOwner.recordPeriodicRefreshWakeForTesting()
        val refreshResult = activeOwner.drainRuntimeProductionTick()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, update)
        assertEquals(RuntimeFrameProductionTickResult.StaleDrop, oldResult)
        assertEquals(RuntimeFrameProductionTickResult.PeriodicRefreshNoSourceFrame, refreshResult)
        session.close()
        runCurrent()
    }

    @Test
    fun frameRateCommitAfterOldPublicationCannotRepopulatePeriodicRetentionOrScheduling() = runTest {
        val provider = FakeImageEncoderProvider()
        val fixture = prepareInitialRuntimeOwnerForProduction(
            initialParameters = ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            ),
        )
        val activeOwner = fixture.activeOwner
        activeOwner.replaceRuntimeFrameRendererForTesting(RuntimeEs2FrameRenderer(RuntimeProductionGles20Api()))
        val session = activeOwner.commitInitialActiveSession(armRuntimeSignals = false)
        val retentionEntered = CountDownLatch(1)
        val releaseRetention = CountDownLatch(1)
        activeOwner.setBeforePeriodicRefreshRetentionForTesting {
            retentionEntered.countDown()
            check(releaseRetention.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release old periodic retention."
            }
        }
        fixture.recordRuntimeFrameAvailableWithoutWake()
        val oldTick = async(Dispatchers.Default) { activeOwner.drainRuntimeProductionTick() }
        assertTrue("old periodic retention entered", retentionEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val update = session.setParameters(
            ScreenCaptureParameters(frameRate = FrameRate.MaxFps(30), encoderProvider = provider),
        )
        releaseRetention.countDown()
        val oldResult = oldTick.await()

        assertEquals(ScreenCaptureParameterUpdateResult.Applied, update)
        assertEquals(RuntimeFrameProductionTickResult.Published, oldResult)
        assertFalse(activeOwner.hasPeriodicRefreshFrameForTesting())
        assertFalse(activeOwner.hasScheduledPeriodicRefreshForTesting())
        session.close()
        runCurrent()
    }

    @Test
    fun runtimeUpdatesFenceOldPeriodicWakeAfterTimerValidationBeforeEnqueue() = runTest {
        RuntimeHealthRaceUpdateKind.entries.forEach { updateKind ->
            val provider = FakeImageEncoderProvider()
            val replacementEncoder = FakeImageEncoder()
            val fixture = prepareInitialRuntimeOwnerForProduction(
                initialParameters = ScreenCaptureParameters(
                    frameRate = FrameRate.PeriodicRefresh(300_000L),
                    encoderProvider = provider,
                ),
                encoderPrepare = if (updateKind == RuntimeHealthRaceUpdateKind.ProviderOnly) {
                    runtimeEncoderPrepareOperation { replacementEncoder }
                } else {
                    RuntimeProviderPreparationNotConfiguredForTesting
                },
            )
            val activeOwner = fixture.activeOwner
            activeOwner.overridePeriodicRefreshDelayForTesting(delayMillis = 50L)
            val wakeReadyForEnqueue = CountDownLatch(1)
            val releaseWakeEnqueue = CountDownLatch(1)
            val wakeEnqueueAttemptFinished = CountDownLatch(1)
            activeOwner.setBeforePeriodicRefreshWakeEnqueueForTesting {
                wakeReadyForEnqueue.countDown()
                check(releaseWakeEnqueue.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release the old $updateKind periodic wake."
                }
            }
            activeOwner.setAfterPeriodicRefreshWakeEnqueueAttemptForTesting {
                wakeEnqueueAttemptFinished.countDown()
            }
            val session = activeOwner.commitInitialActiveSession()
            assertTrue(
                "$updateKind periodic wake validated",
                wakeReadyForEnqueue.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )

            val update = session.setParameters(runtimeHealthRaceUpdateParameters(updateKind, provider))
            releaseWakeEnqueue.countDown()
            assertTrue(
                "$updateKind stale periodic wake retired",
                wakeEnqueueAttemptFinished.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )

            assertEquals(ScreenCaptureParameterUpdateResult.Applied, update)
            assertFalse(activeOwner.runtimeFrameLoopSnapshot().periodicRefreshWakePending)
            assertEquals(0L, activeOwner.periodicRefreshNoSourceWakeCountForTesting())
            session.close()
            runCurrent()
        }
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

    private suspend fun TestScope.prepareInitialRuntimeOwner(
        apiLevel: Int = 33,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): InitialActivationFixture {
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
        val preparedPlan = preActiveOwner.prepareInitialActivePlan(config, initialParameters = initialParameters)
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
        encoderPrepare: ImageEncoderPrepareOperation = RuntimeProviderPreparationNotConfiguredForTesting,
        beforeCommitBoundary: (TestRuntime) -> Unit = {},
        runCleanupSynchronously: Boolean = true,
        elapsedRealtimeNanos: () -> Long = { 1_000L },
        terminalCleanupFenceFactory: () -> AutoCloseable = { AutoCloseable {} },
        runtimeOutputPlanPrepareOverride: (suspend (OutputPlanPrepareRequest) -> RenderingPipelinePreparationResult?)? = null,
        afterRuntimeOutputPlanResourcesPrepared: (suspend (OutputPlanPrepareRequest) -> Unit)? = null,
    ): ProductionActivationFixture {
        val runtime = TestRuntime(apiLevel = 33, runCleanupSynchronously = runCleanupSynchronously)
        val config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider, maxEncodedBytes = configMaxEncodedBytes)
        val startupResources = runtime.start()
        val preActiveOwner = startupResources.transferToPreActiveRuntimeOwner()
        val preparedPlan = preActiveOwner.prepareInitialActivePlan(config, initialParameters = initialParameters)
        val preparer = RuntimeProductionRenderingPipelinePreparer(
            runtimeOutputPlanPrepareOverride = runtimeOutputPlanPrepareOverride,
            afterRuntimeOutputPlanResourcesPrepared = afterRuntimeOutputPlanResourcesPrepared,
        )
        val preparedResources = preActiveOwner.prepareInitialRenderingPipeline(preparedPlan = preparedPlan, preparer = preparer)
        val initialOwner = preActiveOwner.transferToInitialRuntimeResourceOwner(
            preparedPlan = preparedPlan,
            preparedResources = preparedResources,
        )
        val activeOwner = initialOwner.transferToActiveRuntimeOwner(
            config = config,
            encoderPrepare = encoderPrepare,
            outputPlanPreparer = preparer,
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
            createdEncoderCountForTesting = { preparer.encoders.size },
            closedEncoderCountForTesting = { preparer.encoders.sumOf(FakeImageEncoder::closeCount) },
            readbackPreparationCountForTesting = { preparer.readbackPreparationCount },
            retiredReadbackCountForTesting = { preparer.retiredReadbackCount.get() },
            latestEncoderForTesting = { preparer.encoders.last() },
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
            encoderPrepare = ImageEncoderPrepareOperation(ImageEncoderPreparer(providerContext)::prepare),
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
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L))
        }
        if (!condition()) {
            throw AssertionError("$description was not observed within ${timeoutMillis}ms")
        }
    }

    private fun ScreenCaptureSessionState.describeForAssertion(): String =
        when (this) {
            is ScreenCaptureSessionState.Failed -> "Failed(kind=${problem.kind}, message=${problem.message})"
            is ScreenCaptureSessionState.Running -> "Running(output=${output.describeForAssertion()}, capturedContentVisible=$capturedContentVisible)"
            is ScreenCaptureSessionState.Stopped -> "Stopped(reason=$reason, problemKind=${problem?.kind}, message=${problem?.message})"
        }

    private fun ScreenCaptureOutputState.describeForAssertion(): String =
        when (this) {
            is ScreenCaptureOutputState.Active -> "Active"
            is ScreenCaptureOutputState.Suspended -> "Suspended(kind=${problem.kind}, geometry=${currentCaptureGeometry.widthPx}x${currentCaptureGeometry.heightPx}@${currentCaptureGeometry.densityDpi})"
        }

    private fun assertRuntimeFrameLoopSnapshotEquals(
        expected: RuntimeFrameLoopSnapshot,
        actual: RuntimeFrameLoopSnapshot,
    ) {
        assertEquals(expected.committed, actual.committed)
        assertEquals(expected.productionAdmissionPaused, actual.productionAdmissionPaused)
        assertEquals(expected.sourceFrameSignalPending, actual.sourceFrameSignalPending)
        assertEquals(expected.periodicRefreshWakePending, actual.periodicRefreshWakePending)
        assertEquals(expected.latestSourceFrameGeneration, actual.latestSourceFrameGeneration)
        assertEquals(expected.admittedProductionAttempts, actual.admittedProductionAttempts)
    }

    private fun runtimeEncoderPrepareOperation(
        encoderFactory: () -> FakeImageEncoder,
    ): ImageEncoderPrepareOperation =
        ImageEncoderPrepareOperation { _, provider, request ->
            val encoder = encoderFactory()
            ImageEncoderPreparationResult.Success(
                PreparedImageEncoderResources(
                    encoder = encoder,
                    info = ImageEncoderInfo(
                        providerId = provider.id,
                        outputFormat = provider.outputFormat,
                        backendName = encoder.info.backendName,
                    ),
                    request = request,
                    cleanup = ImmediateProviderEncoderCleanup,
                ),
            )
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
            encoderPrepare: ImageEncoderPrepareOperation = RuntimeProviderPreparationNotConfiguredForTesting,
        ): ActiveRuntimeOwner =
            initialOwner.transferToActiveRuntimeOwner(
                config = config,
                encoderPrepare = encoderPrepare,
                commitBoundary = commitBoundary,
                elapsedRealtimeNanos = { 1_000L },
            )
    }

    private class ProductionActivationFixture(
        val runtime: TestRuntime,
        val activeOwner: ActiveRuntimeOwner,
        val encoder: FakeImageEncoder,
        val createdEncoderCountForTesting: () -> Int,
        val closedEncoderCountForTesting: () -> Int,
        val readbackPreparationCountForTesting: () -> Int,
        val retiredReadbackCountForTesting: () -> Int,
        val latestEncoderForTesting: () -> FakeImageEncoder,
    ) {
        lateinit var session: ScreenCaptureSession
    }

    private enum class RuntimeHealthRaceUpdateKind {
        FrameRateOnly,
        ProviderOnly,
        FullPlan,
    }

    private fun runtimeHealthRaceUpdateParameters(
        updateKind: RuntimeHealthRaceUpdateKind,
        provider: FakeImageEncoderProvider,
    ): ScreenCaptureParameters =
        when (updateKind) {
            RuntimeHealthRaceUpdateKind.FrameRateOnly -> ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(200_000L),
                encoderProvider = provider,
            )

            RuntimeHealthRaceUpdateKind.ProviderOnly -> ScreenCaptureParameters(
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = FakeImageEncoderProvider(),
            )

            RuntimeHealthRaceUpdateKind.FullPlan -> ScreenCaptureParameters(
                mirror = Mirror.Horizontal,
                frameRate = FrameRate.PeriodicRefresh(300_000L),
                encoderProvider = provider,
            )
        }

    private class ProviderBackedProductionActivationFixture(
        val runtime: TestRuntime,
        val activeOwner: ActiveRuntimeOwner,
        val providerContext: ProviderPreparationContext,
    )

    private class EqualFakeImageEncoderProvider(
        private val equalityKey: String,
    ) : ImageEncoderProvider {
        override val id: String = "fake-provider"
        override val outputFormat = EncodedImageFormats.Jpeg

        override fun createEncoder(request: ImageEncoderRequest): ImageEncoder =
            error("Runtime no-op identity tests must not call providers.")

        override fun equals(other: Any?): Boolean =
            other is EqualFakeImageEncoderProvider && equalityKey == other.equalityKey

        override fun hashCode(): Int =
            equalityKey.hashCode()
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

    private inner class RuntimeProductionRenderingPipelinePreparer(
        private val providerEncoderResources: (suspend (RenderingPipelinePrepareRequest) -> PreparedImageEncoderResources)? = null,
        private val runtimeOutputPlanPrepareOverride: (suspend (OutputPlanPrepareRequest) -> RenderingPipelinePreparationResult?)? = null,
        private val afterRuntimeOutputPlanResourcesPrepared: (suspend (OutputPlanPrepareRequest) -> Unit)? = null,
    ) : RenderingPipelinePreparer, OutputPlanPreparer {
        val encoders = mutableListOf<FakeImageEncoder>()
        var readbackPreparationCount = 0
        val retiredReadbackCount = AtomicInteger(0)

        override suspend fun prepareInitialRenderingPipeline(request: RenderingPipelinePrepareRequest): RenderingPipelinePreparationResult =
            prepare(request)

        override suspend fun prepareOutputPlan(request: OutputPlanPrepareRequest): RenderingPipelinePreparationResult {
            runtimeOutputPlanPrepareOverride?.invoke(request)?.let { return it }
            return prepare(request).also {
                afterRuntimeOutputPlanResourcesPrepared?.invoke(request)
            }
        }

        private suspend fun prepare(request: OutputPlanPrepareRequest): RenderingPipelinePreparationResult {
            val outputPlan = request.outputPlan
            readbackPreparationCount++
            val readback = PreparedEs2RenderingReadbackResources(
                retirementLane = { _, _ ->
                    retiredReadbackCount.incrementAndGet()
                    true
                },
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
            val encoderResources = (request as? RenderingPipelinePrepareRequest)?.let { startupRequest ->
                providerEncoderResources?.invoke(startupRequest)
            } ?: run {
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

    private fun runtimeProductionTransformPackage(request: OutputPlanPrepareRequest): FirstPlanRenderTransformPackage {
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
        private val throwAfterRelease: Boolean = false,
    ) : RuntimeGles20Api by RuntimeProductionGles20Api() {
        override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
            readPixelsEntered.countDown()
            check(releaseReadPixels.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release fake readPixels."
            }
            if (throwAfterRelease) {
                throw IllegalStateException("synthetic readPixels failure after release")
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

        val RuntimeProviderPreparationNotConfiguredForTesting = ImageEncoderPrepareOperation { _, _, _ ->
            ImageEncoderPreparationResult.Failure(
                kind = ScreenCaptureProblemKind.ParameterUpdateUnavailable,
                message = "Runtime provider preparation is not configured in this fixture.",
                cause = null,
            )
        }
    }

    private object DirectCoroutineDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }
}
