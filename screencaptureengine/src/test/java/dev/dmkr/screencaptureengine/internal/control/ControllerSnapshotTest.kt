package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.EncodedImageFormat
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import dev.dmkr.screencaptureengine.internal.encoding.EncodedFormatDescriptorSnapshot
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanFact
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanner
import dev.dmkr.screencaptureengine.internal.planning.PositiveRatio
import dev.dmkr.screencaptureengine.internal.planning.RequestNonrepresentability
import dev.dmkr.screencaptureengine.internal.planning.SamplingDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerSnapshotTest {
    @Test
    fun publicValuesAreCopiedIntoProviderFreeNormalizedScalars() {
        val provider = BenignProvider()
        val variants = listOf(
            ScreenCaptureParameters(encoderProvider = provider),
            ScreenCaptureParameters(sourceRegion = SourceRegion.LeftHalf, encoderProvider = provider),
            ScreenCaptureParameters(sourceRegion = SourceRegion.RightHalf, encoderProvider = provider),
            ScreenCaptureParameters(crop = CropInsetsPx(1, 2, 3, 4), encoderProvider = provider),
            ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5), encoderProvider = provider),
            ScreenCaptureParameters(outputSize = OutputSize.TargetSize(7, 9, ContentMode.Stretch), encoderProvider = provider),
            ScreenCaptureParameters(outputSize = OutputSize.TargetSize(7, 9, ContentMode.AspectFit), encoderProvider = provider),
            ScreenCaptureParameters(rotation = Rotation.Degrees90, encoderProvider = provider),
            ScreenCaptureParameters(rotation = Rotation.Degrees180, encoderProvider = provider),
            ScreenCaptureParameters(rotation = Rotation.Degrees270, encoderProvider = provider),
            ScreenCaptureParameters(mirror = Mirror.Horizontal, encoderProvider = provider),
            ScreenCaptureParameters(mirror = Mirror.Vertical, encoderProvider = provider),
            ScreenCaptureParameters(colorMode = ColorMode.Grayscale, encoderProvider = provider),
            ScreenCaptureParameters(frameRate = FrameRate.MaxFps(17), encoderProvider = provider),
            ScreenCaptureParameters(frameRate = FrameRate.PeriodicRefresh(2_000), encoderProvider = provider),
        ).map(NormalizedOutputValues::copyOf)

        assertEquals(variants.size, variants.toSet().size)
        assertEquals(NormalizedSourceRegion.LeftHalf, variants[1].sourceRegion)
        assertEquals(NormalizedCrop(1, 2, 3, 4), variants[3].crop)
        assertEquals(NormalizedOutputSize.ScaleFactor(0.5), variants[4].outputSize)
        assertEquals(NormalizedOutputSize.TargetSize(7, 9, NormalizedContentMode.Stretch), variants[5].outputSize)
        assertEquals(NormalizedFrameRate.MaxFps(17), variants[13].frameRate)
        assertEquals(NormalizedFrameRate.PeriodicRefresh(2_000), variants[14].frameRate)
    }

    @Test
    fun normalizedScalarsHaveOnlyV41RepresentabilityBounds() {
        val crop = NormalizedCrop(Int.MAX_VALUE, 0, 0, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, crop.left)
        assertEquals(Int.MAX_VALUE, crop.bottom)
        assertEquals(Double.MIN_VALUE, NormalizedOutputSize.ScaleFactor(Double.MIN_VALUE).factor, 0.0)
        assertEquals(Double.MAX_VALUE, NormalizedOutputSize.ScaleFactor(Double.MAX_VALUE).factor, 0.0)
        assertEquals(Int.MAX_VALUE, NormalizedOutputSize.TargetSize(Int.MAX_VALUE, 1, NormalizedContentMode.Stretch).width)
        assertEquals(Int.MAX_VALUE, NormalizedFrameRate.MaxFps(Int.MAX_VALUE).fps)
        assertEquals(1L, NormalizedFrameRate.PeriodicRefresh(1L).intervalMillis)
        assertEquals(Long.MAX_VALUE, NormalizedFrameRate.PeriodicRefresh(Long.MAX_VALUE).intervalMillis)

        assertThrows(IllegalArgumentException::class.java) { NormalizedCrop(-1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedOutputSize.ScaleFactor(0.0) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedOutputSize.ScaleFactor(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedOutputSize.TargetSize(0, 1, NormalizedContentMode.Stretch)
        }
        assertThrows(IllegalArgumentException::class.java) { NormalizedFrameRate.MaxFps(0) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedFrameRate.PeriodicRefresh(0L) }
    }

    @Test
    fun desiredAndCandidateNeverInvokeHostileProviderBehaviorMethods() {
        val provider = HostileProvider()
        val first = desired(1, baseOutput, provider)
        val sameSemantics = desired(2, baseOutput, provider)
        val plan = planned().plan
        val firstCandidate = ControllerCandidateSnapshot(CandidateIdentity(1), first, geometry, plan)
        val secondCandidate = ControllerCandidateSnapshot(CandidateIdentity(2), sameSemantics, geometry, plan)

        assertEquals(first, sameSemantics)
        assertEquals(first.hashCode(), sameSemantics.hashCode())
        assertTrue(first.toString().contains("HostileProvider@"))
        assertEquals(firstCandidate, secondCandidate)
        assertEquals(firstCandidate.hashCode(), secondCandidate.hashCode())
        assertTrue(firstCandidate.toString().contains("finalSize="))
    }

    @Test
    fun providerReferenceIdentityOverridesCallerEquality() {
        val firstProvider = EqualityLiarProvider()
        val secondProvider = EqualityLiarProvider()
        val first = desired(1, baseOutput, firstProvider)
        val second = desired(2, baseOutput, secondProvider)

        assertNotEquals(first, second)
        assertFalse(first.hasSameRequest(baseOutput, secondProvider))
        assertEquals(
            ControllerParameterClassification.RequiresPlanning,
            ControllerParameterClassifier.classifyRequest(first, request(secondProvider)),
        )
    }

    @Test
    fun noOpIsSingletonAndFrameOnlyRetainsEveryPlanAndProviderValue() {
        val provider = ArmableHostileProvider()
        val requestedNoOp = request(provider)
        val requestedFrameOnly = request(provider, frameRate = FrameRate.MaxFps(23))
        val autoFrameChanges = listOf(
            FrameRate.MaxFps(1),
            FrameRate.MaxFps(120),
            FrameRate.PeriodicRefresh(1_000),
            FrameRate.PeriodicRefresh(300_000),
        ).map { request(provider, frameRate = it) }
        val maxFpsNoOp = request(provider, frameRate = FrameRate.MaxFps(1))
        val maxFpsChanges = listOf(
            FrameRate.Auto,
            FrameRate.MaxFps(2),
            FrameRate.PeriodicRefresh(1_000),
        ).map { request(provider, frameRate = it) }
        val periodicNoOp = request(provider, frameRate = FrameRate.PeriodicRefresh(1_000))
        val periodicChanges = listOf(
            FrameRate.Auto,
            FrameRate.MaxFps(1),
            FrameRate.PeriodicRefresh(2_000),
        ).map { request(provider, frameRate = it) }
        provider.arm()
        val current = desired(1, baseOutput, provider)

        val firstNoOp = ControllerParameterClassifier.classifyRequest(current, requestedNoOp)
        val secondNoOp = ControllerParameterClassifier.classifyRequest(current, requestedNoOp)
        assertSame(ControllerParameterClassification.NormalizedNoOp, firstNoOp)
        assertSame(firstNoOp, secondNoOp)

        val frameOnly = baseOutput.copy(frameRate = NormalizedFrameRate.MaxFps(23))
        assertSame(
            ControllerParameterClassification.FrameRateOnly,
            ControllerParameterClassifier.classifyRequest(current, requestedFrameOnly),
        )
        assertTrue(current.hasSamePlanAndProvider(frameOnly, provider))

        autoFrameChanges.forEach { changedRequest ->
            assertSame(
                ControllerParameterClassification.FrameRateOnly,
                ControllerParameterClassifier.classifyRequest(
                    current,
                    changedRequest,
                ),
            )
        }
        val maxFpsCurrent = desired(
            2,
            baseOutput.copy(frameRate = NormalizedFrameRate.MaxFps(1)),
            provider,
        )
        assertSame(
            ControllerParameterClassification.NormalizedNoOp,
            ControllerParameterClassifier.classifyRequest(
                maxFpsCurrent,
                maxFpsNoOp,
            ),
        )
        maxFpsChanges.forEach { changedRequest ->
            assertSame(
                ControllerParameterClassification.FrameRateOnly,
                ControllerParameterClassifier.classifyRequest(
                    maxFpsCurrent,
                    changedRequest,
                ),
            )
        }
        val periodicCurrent = desired(
            3,
            baseOutput.copy(frameRate = NormalizedFrameRate.PeriodicRefresh(1_000)),
            provider,
        )
        assertSame(
            ControllerParameterClassification.NormalizedNoOp,
            ControllerParameterClassifier.classifyRequest(
                periodicCurrent,
                periodicNoOp,
            ),
        )
        periodicChanges.forEach { changedRequest ->
            assertSame(
                ControllerParameterClassification.FrameRateOnly,
                ControllerParameterClassifier.classifyRequest(
                    periodicCurrent,
                    changedRequest,
                ),
            )
        }
    }

    @Test
    fun everyNonFrameOutputDifferenceAndProviderDifferenceNeedsPlanning() {
        val provider = BenignProvider()
        val current = desired(1, baseOutput, provider)
        val variants = listOf(
            request(provider, sourceRegion = SourceRegion.LeftHalf),
            request(provider, sourceRegion = SourceRegion.RightHalf),
            request(provider, crop = CropInsetsPx(1, 0, 0, 0)),
            request(provider, crop = CropInsetsPx(0, 1, 0, 0)),
            request(provider, crop = CropInsetsPx(0, 0, 1, 0)),
            request(provider, crop = CropInsetsPx(0, 0, 0, 1)),
            request(provider, outputSize = OutputSize.ScaleFactor(0.75)),
            request(provider, outputSize = OutputSize.ScaleFactor(0.10)),
            request(provider, outputSize = OutputSize.ScaleFactor(2.00)),
            request(provider, outputSize = OutputSize.TargetSize(7, 9, ContentMode.Stretch)),
            request(provider, outputSize = OutputSize.TargetSize(7, 9, ContentMode.AspectFit)),
            request(provider, outputSize = OutputSize.TargetSize(1, 1, ContentMode.Stretch)),
            request(provider, outputSize = OutputSize.TargetSize(32_768, 32_768, ContentMode.AspectFit)),
            request(provider, rotation = Rotation.Degrees90),
            request(provider, rotation = Rotation.Degrees180),
            request(provider, rotation = Rotation.Degrees270),
            request(provider, mirror = Mirror.Horizontal),
            request(provider, mirror = Mirror.Vertical),
            request(provider, colorMode = ColorMode.Grayscale),
        )

        variants.forEach { requested ->
            assertSame(
                ControllerParameterClassification.RequiresPlanning,
                ControllerParameterClassifier.classifyRequest(current, requested),
            )
        }
        assertSame(
            ControllerParameterClassification.RequiresPlanning,
            ControllerParameterClassifier.classifyRequest(current, request(BenignProvider())),
        )
    }

    @Test
    fun plannedReplacementIsTypedAsSameTargetTargetReplanOrInvalid() {
        val target = ControllerTargetSnapshot(
            identity = TargetIdentity(1),
            geometry = geometry,
            targetSize = Size(100, 80),
            samplingCapacity = SamplingDemand(PositiveRatio(1, 1), PositiveRatio(1, 1)),
            assignment = TargetAssignmentEvidence.Acknowledged,
            health = TargetHealthEvidence.Validated,
            wholeGeometryMappingValidated = true,
        )
        val planned = planned()
        val same = ControllerParameterClassifier.classifyReplacement(
            planFact = planned,
            currentTarget = target,
            currentGeometry = geometry,
            requiresFreshIdentity = false,
        )
        assertTrue(same is ControllerParameterClassification.SameTargetReplacement)

        val replan = ControllerParameterClassifier.classifyReplacement(
            planFact = planned,
            currentTarget = target,
            currentGeometry = geometry,
            requiresFreshIdentity = true,
        ) as ControllerParameterClassification.TargetReplan
        assertEquals(TargetReplacementReason.FreshIdentityRequired, replan.reason)

        RequestNonrepresentability.entries.forEach { reason ->
            val invalid = ControllerParameterClassifier.classifyReplacement(
                planFact = BaselineOutputPlanFact.RequestNotRepresentable(reason),
                currentTarget = target,
                currentGeometry = geometry,
                requiresFreshIdentity = false,
            ) as ControllerParameterClassification.Invalid
            assertEquals(reason, invalid.reason)
        }
    }

    @Test
    fun effectiveSnapshotOwnsCopiedFormatAndSeparatesPublicSemanticsFromFenceAndAllocationFacts() {
        val plan = planned().plan
        val first = effective(1, plan, EncodedFormatDescriptorSnapshot.copy("JPEG", "image/jpeg"))
        val samePublicValue = effective(
            id = 2,
            plan = plan.copy(
                orientedContentSize = Size(
                    plan.orientedContentSize.width + 1,
                    plan.orientedContentSize.height + 1,
                ),
                projectionTargetSize = Size(
                    plan.projectionTargetSize.width + 1,
                    plan.projectionTargetSize.height + 1,
                ),
                samplingDemand = SamplingDemand(PositiveRatio(1, 3), PositiveRatio(1, 4)),
                rowStrideBytes = plan.rowStrideBytes + 4,
                requiredRgbaBytes = plan.requiredRgbaBytes + 4,
            ),
            format = EncodedFormatDescriptorSnapshot.copy("JPEG", "image/jpeg"),
        )

        assertTrue(first.hasSamePublicValue(samePublicValue))
        assertEquals(first, samePublicValue)
        assertEquals(first.hashCode(), samePublicValue.hashCode())

        val publicDifferences = listOf(
            effective(3, plan, first.encodedFormat, output = baseOutput.copy(frameRate = NormalizedFrameRate.MaxFps(30))),
            effective(4, plan, first.encodedFormat, geometry = geometry.copy(densityDpi = 321)),
            effective(
                5,
                plan.copy(
                    appliedSourceRect = plan.appliedSourceRect.let { rect ->
                        ImageRect(rect.left, rect.top, rect.right - 1, rect.bottom)
                    },
                ),
                first.encodedFormat,
            ),
            effective(6, plan.copy(finalImageSize = Size(plan.finalImageSize.width - 1, plan.finalImageSize.height)), first.encodedFormat),
            effective(7, plan, EncodedFormatDescriptorSnapshot.copy("PNG", "image/png")),
        )
        publicDifferences.forEach { different ->
            assertFalse(first.hasSamePublicValue(different))
            assertNotEquals(first, different)
        }
    }

    private fun effective(
        id: Long,
        plan: BaselineOutputPlan,
        format: EncodedFormatDescriptorSnapshot,
        output: NormalizedOutputValues = baseOutput,
        geometry: GeometrySnapshot = Companion.geometry,
    ): ControllerEffectiveSnapshot = ControllerEffectiveSnapshot(
        identity = EffectiveSnapshotIdentity(id),
        desiredIdentity = DesiredSnapshotIdentity(id),
        targetIdentity = TargetIdentity(id),
        completeOwnerIdentity = CompleteOwnerIdentity(id),
        output = output,
        geometry = geometry,
        plan = plan,
        encodedFormat = format,
    )

    private fun desired(id: Long, output: NormalizedOutputValues, provider: ImageEncoderProvider) =
        ControllerDesiredSnapshot(DesiredSnapshotIdentity(id), output, ControllerProviderReference(provider))

    private fun request(
        provider: ImageEncoderProvider,
        sourceRegion: SourceRegion = SourceRegion.Full,
        crop: CropInsetsPx = CropInsetsPx.Zero,
        outputSize: OutputSize = OutputSize.ScaleFactor(0.5),
        rotation: Rotation = Rotation.Degrees0,
        mirror: Mirror = Mirror.None,
        colorMode: ColorMode = ColorMode.Original,
        frameRate: FrameRate = FrameRate.Auto,
    ): ScreenCaptureParameters = ScreenCaptureParameters(
        sourceRegion = sourceRegion,
        crop = crop,
        outputSize = outputSize,
        rotation = rotation,
        mirror = mirror,
        colorMode = colorMode,
        frameRate = frameRate,
        encoderProvider = provider,
    )

    private fun planned(): BaselineOutputPlanFact.Planned = BaselineOutputPlanner.planScaleFactor(
        logicalCaptureSize = Size(100, 80),
        sourceRegion = SourceRegion.Full,
        crop = CropInsetsPx.Zero,
        rotation = Rotation.Degrees0,
        factor = 0.5,
    ) as BaselineOutputPlanFact.Planned

    private companion object {
        val geometry = GeometrySnapshot(width = 100, height = 80, densityDpi = 320)
        val baseOutput = NormalizedOutputValues(
            sourceRegion = NormalizedSourceRegion.Full,
            crop = NormalizedCrop(0, 0, 0, 0),
            outputSize = NormalizedOutputSize.ScaleFactor(0.5),
            rotation = NormalizedRotation.Degrees0,
            mirror = NormalizedMirror.None,
            colorMode = NormalizedColorMode.Original,
            frameRate = NormalizedFrameRate.Auto,
        )
    }
}

private open class BenignProvider : ImageEncoderProvider {
    override val id: String = "test"
    override val outputFormat: EncodedImageFormat = EncodedImageFormat("TEST", "image/test")
    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder = error("not called")
}

private class HostileProvider : ImageEncoderProvider {
    override val id: String get() = error("provider id getter called")
    override val outputFormat: EncodedImageFormat get() = error("provider format getter called")
    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder = error("create called")
    override fun equals(other: Any?): Boolean = error("provider equals called")
    override fun hashCode(): Int = error("provider hashCode called")
    override fun toString(): String = error("provider toString called")
}

private class ArmableHostileProvider : ImageEncoderProvider {
    private var armed = false
    override val id: String get() = if (armed) error("provider id getter called") else "test"
    override val outputFormat: EncodedImageFormat
        get() = if (armed) error("provider format getter called") else EncodedImageFormat("TEST", "image/test")

    fun arm() {
        armed = true
    }

    override fun createEncoder(request: ImageEncoderRequest): ImageEncoder = error("create called")
    override fun equals(other: Any?): Boolean = error("provider equals called")
    override fun hashCode(): Int = error("provider hashCode called")
    override fun toString(): String = error("provider toString called")
}

private class EqualityLiarProvider : BenignProvider() {
    override fun equals(other: Any?): Boolean = true
    override fun hashCode(): Int = 0
}
