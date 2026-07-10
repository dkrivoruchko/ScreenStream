package dev.dmkr.screencaptureengine.internal.rendering.pipeline

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoder
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImmediateProviderEncoderCleanup
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanResult
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.startup.FakeProjectionSurfaceHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PreparedRenderingPipelineResourcesTest {
    @Test
    fun fullCloseClosesRenderReadbackAndActiveEncoderOnce() {
        val fixture = activeResourcesFixture()

        fixture.activeResources.close()
        fixture.activeResources.close()

        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
    }

    @Test
    fun encoderOnlyCloseDoesNotCloseRenderReadbackResources() {
        val fixture = activeResourcesFixture()
        val renderReadbackResources = fixture.activeResources.renderReadbackResourcesForRuntime

        fixture.activeResources.closeEncoderResourcesOnly()
        fixture.activeResources.closeEncoderResourcesOnly()

        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertSame(renderReadbackResources, fixture.activeResources.renderReadbackResourcesForRuntime)
        assertSame(fixture.renderTransformPackage, fixture.activeResources.renderTransformPackage)

        fixture.activeResources.close()

        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
    }

    @Test
    fun encoderOnlyReplacementRetiresOldEncoderWithoutClosingRenderReadbackResources() {
        val fixture = activeResourcesFixture(encoderProviderId = "old-provider")
        val candidateEncoder = FakeImageEncoder(info = encoderInfo(providerId = "new-provider"))
        val candidate = ActiveRuntimeEncoderResourcesCandidate(
            preparedEncoderResources(
                encoder = candidateEncoder,
                requestPlan = fixture.outputPlan,
            ),
        )
        val renderReadbackResources = fixture.activeResources.renderReadbackResourcesForRuntime

        val retired = fixture.activeResources.replaceEncoderResourcesOnly(candidate)
        candidate.close()

        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(0, candidateEncoder.closeCount)
        assertSame(renderReadbackResources, fixture.activeResources.renderReadbackResourcesForRuntime)
        assertSame(fixture.renderTransformPackage, fixture.activeResources.renderTransformPackage)
        assertSame(candidateEncoder.info, fixture.activeResources.encoderInfo)
        assertSame(candidateEncoder, fixture.activeResources.encoderResourcesForRuntime.encoder)

        retired.close()
        retired.close()

        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(0, candidateEncoder.closeCount)

        fixture.activeResources.close()

        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, candidateEncoder.closeCount)
    }

    @Test
    fun closingUninstalledEncoderCandidateClosesOnlyCandidateEncoder() {
        val fixture = activeResourcesFixture()
        val candidateEncoder = FakeImageEncoder(info = encoderInfo(providerId = "candidate-provider"))
        val candidate = ActiveRuntimeEncoderResourcesCandidate(
            preparedEncoderResources(
                encoder = candidateEncoder,
                requestPlan = fixture.outputPlan,
            ),
        )

        candidate.close()
        candidate.close()

        assertEquals(0, fixture.readbackResources.closeCount)
        assertEquals(0, fixture.encoder.closeCount)
        assertEquals(1, candidateEncoder.closeCount)

        fixture.activeResources.close()

        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, candidateEncoder.closeCount)
    }

    @Test
    fun completeRuntimeCandidateRollbackAndMoveHaveSingleOwner() {
        val outputPlan = outputPlan()
        val projectionTarget = ProjectionTargetSnapshot(
            generation = 1L,
            width = outputPlan.captureTarget.width,
            height = outputPlan.captureTarget.height,
            densityDpi = outputPlan.captureGeometry.densityDpi,
            surface = FakeProjectionSurfaceHandle,
        )
        val rollbackReadback = TestPreparedRenderingPipelineResource()
        val rollbackEncoder = FakeImageEncoder()
        val rollback = PreparedRenderingPipelineComponents(
            readbackResources = rollbackReadback,
            renderTransformPackage = testRenderTransformPackage(outputPlan, projectionTarget),
            encoderResources = preparedEncoderResources(rollbackEncoder, outputPlan),
        ).moveToActiveRuntimeCandidate(outputPlan, projectionTarget)

        rollback.close()
        rollback.close()

        assertEquals(1, rollbackReadback.closeCount)
        assertEquals(1, rollbackEncoder.closeCount)

        val movedReadback = TestPreparedRenderingPipelineResource()
        val movedEncoder = FakeImageEncoder()
        val movedCandidate = PreparedRenderingPipelineComponents(
            readbackResources = movedReadback,
            renderTransformPackage = testRenderTransformPackage(outputPlan, projectionTarget),
            encoderResources = preparedEncoderResources(movedEncoder, outputPlan),
        ).moveToActiveRuntimeCandidate(outputPlan, projectionTarget)
        val active = movedCandidate.moveToActiveRuntimeOwner()

        movedCandidate.close()
        assertEquals(0, movedReadback.closeCount)
        assertEquals(0, movedEncoder.closeCount)

        active.close()
        assertEquals(1, movedReadback.closeCount)
        assertEquals(1, movedEncoder.closeCount)
    }

    @Test
    fun encoderCandidateRejectsAccessAndMoveAfterCloseWithoutDoubleCleanup() {
        val outputPlan = outputPlan()
        val encoder = FakeImageEncoder()
        val candidate = ActiveRuntimeEncoderResourcesCandidate(
            preparedEncoderResources(encoder = encoder, requestPlan = outputPlan),
        )

        candidate.close()

        assertThrows(IllegalStateException::class.java) { candidate.encoderInfo }
        assertThrows(IllegalStateException::class.java) { candidate.encoderResourcesForValidation }
        assertThrows(IllegalStateException::class.java) { candidate.moveToActiveRuntimeOwner() }
        candidate.close()
        assertEquals(1, encoder.closeCount)
    }

    @Test
    fun completeAndEncoderCandidatesRejectDoubleMoveAndKeepMovedOwnerSingle() {
        val outputPlan = outputPlan()
        val projectionTarget = ProjectionTargetSnapshot(
            generation = 1L,
            width = outputPlan.captureTarget.width,
            height = outputPlan.captureTarget.height,
            densityDpi = outputPlan.captureGeometry.densityDpi,
            surface = FakeProjectionSurfaceHandle,
        )
        val readback = TestPreparedRenderingPipelineResource()
        val completeEncoder = FakeImageEncoder()
        val completeCandidate = PreparedRenderingPipelineComponents(
            readbackResources = readback,
            renderTransformPackage = testRenderTransformPackage(outputPlan, projectionTarget),
            encoderResources = preparedEncoderResources(completeEncoder, outputPlan),
        ).moveToActiveRuntimeCandidate(outputPlan, projectionTarget)
        val activeCompleteResources = completeCandidate.moveToActiveRuntimeOwner()

        assertThrows(IllegalStateException::class.java) { completeCandidate.encoderInfo }
        assertThrows(IllegalStateException::class.java) { completeCandidate.moveToActiveRuntimeOwner() }
        completeCandidate.close()
        assertEquals(0, readback.closeCount)
        assertEquals(0, completeEncoder.closeCount)

        val encoderOnly = FakeImageEncoder()
        val encoderCandidate = ActiveRuntimeEncoderResourcesCandidate(
            preparedEncoderResources(encoder = encoderOnly, requestPlan = outputPlan),
        )
        val activeEncoderOwner = encoderCandidate.moveToActiveRuntimeOwner()

        assertThrows(IllegalStateException::class.java) { encoderCandidate.encoderInfo }
        assertThrows(IllegalStateException::class.java) { encoderCandidate.moveToActiveRuntimeOwner() }
        encoderCandidate.close()
        assertEquals(0, encoderOnly.closeCount)

        activeCompleteResources.close()
        activeEncoderOwner.close()
        assertEquals(1, readback.closeCount)
        assertEquals(1, completeEncoder.closeCount)
        assertEquals(1, encoderOnly.closeCount)
    }

    @Test
    fun replacingEncoderOnClosedActiveResourcesClosesCandidateExactlyOnce() {
        val fixture = activeResourcesFixture()
        val candidateEncoder = FakeImageEncoder(info = encoderInfo(providerId = "candidate-provider"))
        val candidate = ActiveRuntimeEncoderResourcesCandidate(
            preparedEncoderResources(
                encoder = candidateEncoder,
                requestPlan = fixture.outputPlan,
            ),
        )
        fixture.activeResources.close()

        assertThrows(IllegalStateException::class.java) {
            fixture.activeResources.replaceEncoderResourcesOnly(candidate)
        }

        candidate.close()
        fixture.activeResources.close()
        assertEquals(1, fixture.readbackResources.closeCount)
        assertEquals(1, fixture.encoder.closeCount)
        assertEquals(1, candidateEncoder.closeCount)
    }

    @Test
    fun activeEncoderAccessorsRejectAfterEncoderOnlyAndFullClose() {
        val encoderOnlyFixture = activeResourcesFixture()
        encoderOnlyFixture.activeResources.closeEncoderResourcesOnly()

        assertThrows(IllegalStateException::class.java) { encoderOnlyFixture.activeResources.encoderInfo }
        assertThrows(IllegalStateException::class.java) {
            encoderOnlyFixture.activeResources.encoderResourcesForRuntime
        }
        assertSame(
            encoderOnlyFixture.renderTransformPackage,
            encoderOnlyFixture.activeResources.renderTransformPackage,
        )
        encoderOnlyFixture.activeResources.close()
        assertEquals(1, encoderOnlyFixture.readbackResources.closeCount)
        assertEquals(1, encoderOnlyFixture.encoder.closeCount)

        val fullCloseFixture = activeResourcesFixture()
        fullCloseFixture.activeResources.close()

        assertThrows(IllegalStateException::class.java) { fullCloseFixture.activeResources.encoderInfo }
        assertThrows(IllegalStateException::class.java) {
            fullCloseFixture.activeResources.encoderResourcesForRuntime
        }
        fullCloseFixture.activeResources.close()
        assertEquals(1, fullCloseFixture.readbackResources.closeCount)
        assertEquals(1, fullCloseFixture.encoder.closeCount)
    }

    private fun activeResourcesFixture(
        encoderProviderId: String = "active-provider",
    ): ActiveResourcesFixture {
        val outputPlan = outputPlan()
        val projectionTarget = ProjectionTargetSnapshot(
            generation = 1L,
            width = outputPlan.captureTarget.width,
            height = outputPlan.captureTarget.height,
            densityDpi = outputPlan.captureGeometry.densityDpi,
            surface = FakeProjectionSurfaceHandle,
        )
        val renderTransformPackage = testRenderTransformPackage(
            outputPlan = outputPlan,
            projectionTarget = projectionTarget,
        )
        val readbackResources = TestPreparedRenderingPipelineResource()
        val encoder = FakeImageEncoder(info = encoderInfo(providerId = encoderProviderId))
        val activeResources = ActiveRuntimePreparedRenderingPipelineResources(
            readbackResources = readbackResources,
            renderTransformPackage = renderTransformPackage,
            encoderResources = preparedEncoderResources(
                encoder = encoder,
                requestPlan = outputPlan,
            ),
        )
        return ActiveResourcesFixture(
            outputPlan = outputPlan,
            renderTransformPackage = renderTransformPackage,
            readbackResources = readbackResources,
            encoder = encoder,
            activeResources = activeResources,
        )
    }

    private fun outputPlan(): ScreenCaptureOutputPlan {
        val result = ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = 268_435_456,
                maxEncodedBytes = 1_024,
                readbackMode = ReadbackMode.Es2,
            ),
        ).plan(
            geometry = CaptureGeometry(
                widthPx = 64,
                heightPx = 48,
                densityDpi = 320,
                source = CaptureGeometrySource.MetricsProvider,
            ),
            parameters = ScreenCaptureParameters(),
        )
        return (result as OutputPlanResult.Success).plan
    }

    private fun preparedEncoderResources(
        encoder: FakeImageEncoder,
        requestPlan: ScreenCaptureOutputPlan,
    ): PreparedImageEncoderResources =
        PreparedImageEncoderResources(
            encoder = encoder,
            info = encoder.info,
            request = requestPlan.encoderRequest,
            cleanup = ImmediateProviderEncoderCleanup,
        )

    private fun encoderInfo(providerId: String): ImageEncoderInfo =
        ImageEncoderInfo(
            providerId = providerId,
            outputFormat = EncodedImageFormats.Jpeg,
            backendName = "test-backend",
        )

    private class ActiveResourcesFixture(
        val outputPlan: ScreenCaptureOutputPlan,
        val renderTransformPackage: Any,
        val readbackResources: TestPreparedRenderingPipelineResource,
        val encoder: FakeImageEncoder,
        val activeResources: ActiveRuntimePreparedRenderingPipelineResources,
    )
}
