package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeParameterUpdateClassifierTest {
    @Test
    fun classify_normalizedNoOpResolvesAutoToEffectiveMaxFps30() {
        val activeProvider = TestImageEncoderProvider(idSuffix = "active")
        val activeParameters = ScreenCaptureParameters(
            frameRate = FrameRate.MaxFps(30),
            encoderProvider = activeProvider,
        )
        val snapshot = activeSnapshot(activeParameters)

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(
                frameRate = FrameRate.Auto,
                encoderProvider = activeProvider,
            ),
        )

        assertTrue(classification is RuntimeParameterUpdateClassification.NormalizedNoOp)
        assertEquals(FrameRate.MaxFps(30), classification.candidatePlan?.frameRate)
    }

    @Test
    fun classify_frameRateOnlyWhenOnlyEffectiveFrameRateChanges() {
        val activeProvider = TestImageEncoderProvider(idSuffix = "active")
        val snapshot = activeSnapshot(
            ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(30),
                encoderProvider = activeProvider,
            ),
        )

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(
                frameRate = FrameRate.MaxFps(15),
                encoderProvider = activeProvider,
            ),
        )

        assertTrue(classification is RuntimeParameterUpdateClassification.FrameRateOnly)
        assertEquals(FrameRate.MaxFps(15), classification.candidatePlan?.frameRate)
    }

    @Test
    fun classify_providerOnlyWhenProviderConfigChangesAndPlanShapeIsSame() {
        val activeParameters = ScreenCaptureParameters(encoderProvider = TestImageEncoderProvider(idSuffix = "active"))
        val snapshot = activeSnapshot(activeParameters)

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(encoderProvider = TestImageEncoderProvider(idSuffix = "candidate")),
        )

        assertTrue(classification is RuntimeParameterUpdateClassification.ProviderOnlySameTarget)
        assertEquals(snapshot.currentOutputPlan.encoderRequest, classification.candidatePlan?.encoderRequest)
    }

    @Test
    fun classify_providerOnlyWhenEqualButDistinctProviderInstanceKeepsPlanShapeSame() {
        val activeProvider = TestImageEncoderProvider(idSuffix = "same")
        val requestedProvider = TestImageEncoderProvider(idSuffix = "same")
        assertEquals(activeProvider, requestedProvider)
        assertNotSame(activeProvider, requestedProvider)
        val snapshot = activeSnapshot(ScreenCaptureParameters(encoderProvider = activeProvider))

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(encoderProvider = requestedProvider),
        )

        assertTrue(classification is RuntimeParameterUpdateClassification.ProviderOnlySameTarget)
        assertEquals(snapshot.currentOutputPlan.encoderRequest, classification.candidatePlan?.encoderRequest)
    }

    @Test
    fun classify_fullSameTargetReplacementWhenRenderPlanChangesWithoutRetargeting() {
        val snapshot = activeSnapshot(ScreenCaptureParameters())

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(mirror = Mirror.Horizontal),
        )

        assertTrue(classification is RuntimeParameterUpdateClassification.FullSameTargetReplacement)
        assertEquals(snapshot.currentOutputPlan.captureTarget, classification.candidatePlan?.captureTarget)
    }

    @Test
    fun classify_unavailableWhenValidRequestNeedsTargetChange() {
        val snapshot = activeSnapshot(ScreenCaptureParameters())

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5)),
        )

        val unavailable = classification as RuntimeParameterUpdateClassification.Unavailable
        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, unavailable.problemKind)
        assertEquals(RuntimeParameterUpdateUnavailableReason.TargetChangeRequired, unavailable.reason)
    }

    @Test
    fun classify_unavailableWhenOutputIsSuspended() {
        val snapshot = activeSnapshot(
            parameters = ScreenCaptureParameters(),
            outputState = RuntimeParameterOutputState.Suspended,
        )

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(mirror = Mirror.Horizontal),
        )

        val unavailable = classification as RuntimeParameterUpdateClassification.Unavailable
        assertEquals(ScreenCaptureProblemKind.ParameterUpdateUnavailable, unavailable.problemKind)
        assertEquals(RuntimeParameterUpdateUnavailableReason.SuspendedOutputRecovery, unavailable.reason)
    }

    @Test
    fun classify_rejectedKeepsPreciseInvalidCropPlannerProblem() {
        val snapshot = activeSnapshot(ScreenCaptureParameters())

        val classification = classifier().classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(crop = CropInsetsPx(left = 50, right = 50)),
        )

        val rejected = classification as RuntimeParameterUpdateClassification.Rejected
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, rejected.problem.kind)
    }

    @Test
    fun classify_rejectedKeepsPreciseCapsPlannerProblem() {
        val snapshot = activeSnapshot(ScreenCaptureParameters())

        val classification = classifier(maxOutputPixels = 9_999).classify(
            activeSnapshot = snapshot,
            requestedParameters = ScreenCaptureParameters(),
        )

        val rejected = classification as RuntimeParameterUpdateClassification.Rejected
        assertEquals(ScreenCaptureProblemKind.OutputLimitsExceeded, rejected.problem.kind)
    }

    @Test
    fun sameTargetPredicateRejectsGenerationAndSemanticsChanges() {
        val current = targetIdentity(width = 100, height = 100, densityDpi = 320)

        assertEquals(false, current.copyForTest(captureGeometryGeneration = 2L).isSameTargetAs(current))
        assertEquals(false, current.copyForTest(targetGeneration = 2L).isSameTargetAs(current))
        assertEquals(false, current.copyForTest(semantics = RuntimeProjectionTargetSemantics.ExternalSurface).isSameTargetAs(current))
    }

    private fun classifier(maxOutputPixels: Int = 268_435_456): RuntimeParameterUpdateClassifier =
        RuntimeParameterUpdateClassifier(
            planner = planner(maxOutputPixels = maxOutputPixels),
        )

    private fun activeSnapshot(
        parameters: ScreenCaptureParameters,
        outputState: RuntimeParameterOutputState = RuntimeParameterOutputState.Active,
    ): RuntimeParameterActiveSnapshot {
        val geometry = CaptureGeometry(
            widthPx = 100,
            heightPx = 100,
            densityDpi = 320,
            source = CaptureGeometrySource.MetricsProvider,
        )
        val outputPlan = planner().plan(geometry = geometry, parameters = parameters).successPlan()
        val encoderInfo = ImageEncoderInfo(
            providerId = parameters.encoderProvider.id,
            outputFormat = parameters.encoderProvider.outputFormat,
            backendName = "test",
        )
        val effectiveParameters = outputPlan.toEffectiveParameters(encoderInfo)
        assertSame(encoderInfo, effectiveParameters.encoderInfo)
        return RuntimeParameterActiveSnapshot(
            outputState = outputState,
            currentRequestedParameters = parameters,
            currentOutputPlan = outputPlan,
            currentEffectiveParameters = effectiveParameters,
            currentCaptureGeometry = geometry,
            currentCaptureGeometryGeneration = 1L,
            currentProjectionTarget = targetIdentity(
                width = outputPlan.captureTarget.width,
                height = outputPlan.captureTarget.height,
                densityDpi = geometry.densityDpi,
            ),
        )
    }

    private fun planner(maxOutputPixels: Int = 268_435_456): ScreenCaptureOutputPlanner =
        ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = maxOutputPixels,
                maxEncodedBytes = 64 * 1024,
            ),
        )

    private fun OutputPlanResult.successPlan(): ScreenCaptureOutputPlan =
        (this as OutputPlanResult.Success).plan

    private fun targetIdentity(
        width: Int,
        height: Int,
        densityDpi: Int,
        captureGeometryGeneration: Long = 1L,
        targetGeneration: Long = 1L,
        semantics: RuntimeProjectionTargetSemantics = RuntimeProjectionTargetSemantics.SurfaceTextureOes,
    ): RuntimeProjectionTargetIdentity =
        RuntimeProjectionTargetIdentity(
            width = width,
            height = height,
            densityDpi = densityDpi,
            captureGeometryGeneration = captureGeometryGeneration,
            targetGeneration = targetGeneration,
            semantics = semantics,
        )

    private fun RuntimeProjectionTargetIdentity.copyForTest(
        captureGeometryGeneration: Long = this.captureGeometryGeneration,
        targetGeneration: Long = this.targetGeneration,
        semantics: RuntimeProjectionTargetSemantics = this.semantics,
    ): RuntimeProjectionTargetIdentity =
        RuntimeProjectionTargetIdentity(
            width = width,
            height = height,
            densityDpi = densityDpi,
            captureGeometryGeneration = captureGeometryGeneration,
            targetGeneration = targetGeneration,
            semantics = semantics,
        )

    private class TestImageEncoderProvider(private val idSuffix: String) : ImageEncoderProvider {
        override val id: String = "test-jpeg"
        override val outputFormat: EncodedImageFormat = EncodedImageFormats.Jpeg

        override fun createEncoder(request: ImageEncoderRequest): ImageEncoder =
            error("Classifier tests must not call providers.")

        override fun equals(other: Any?): Boolean = other is TestImageEncoderProvider && idSuffix == other.idSuffix

        override fun hashCode(): Int = idSuffix.hashCode()
    }

}
