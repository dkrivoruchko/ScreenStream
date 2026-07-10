@file:Suppress("unused") // Dormant until controller-store integration.

package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.SourceRegion
import dev.dmkr.screencaptureengine.internal.encoding.EncodedFormatDescriptorSnapshot
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.BaselineOutputPlanFact
import dev.dmkr.screencaptureengine.internal.planning.RequestNonrepresentability

/** Engine-owned scalar copy of all provider-free requested output semantics. */
internal data class NormalizedOutputValues(
    val sourceRegion: NormalizedSourceRegion,
    val crop: NormalizedCrop,
    val outputSize: NormalizedOutputSize,
    val rotation: NormalizedRotation,
    val mirror: NormalizedMirror,
    val colorMode: NormalizedColorMode,
    val frameRate: NormalizedFrameRate,
) {
    internal fun hasSamePlanValues(other: NormalizedOutputValues): Boolean =
        sourceRegion == other.sourceRegion &&
                crop == other.crop &&
                outputSize == other.outputSize &&
                rotation == other.rotation &&
                mirror == other.mirror &&
                colorMode == other.colorMode

    internal fun hasSamePlanValues(parameters: ScreenCaptureParameters): Boolean =
        sourceRegion.matches(parameters.sourceRegion) &&
                crop.matches(parameters.crop) &&
                outputSize.matches(parameters.outputSize) &&
                rotation.matches(parameters.rotation) &&
                mirror.matches(parameters.mirror) &&
                colorMode.matches(parameters.colorMode)

    internal fun hasSameFrameRate(parameters: ScreenCaptureParameters): Boolean =
        frameRate.matches(parameters.frameRate)

    internal companion object {
        internal fun copyOf(parameters: ScreenCaptureParameters): NormalizedOutputValues = NormalizedOutputValues(
            sourceRegion = parameters.sourceRegion.normalized(),
            crop = NormalizedCrop.copyOf(parameters.crop),
            outputSize = parameters.outputSize.normalized(),
            rotation = parameters.rotation.normalized(),
            mirror = parameters.mirror.normalized(),
            colorMode = parameters.colorMode.normalized(),
            frameRate = parameters.frameRate.normalized(),
        )
    }
}

internal enum class NormalizedSourceRegion { Full, LeftHalf, RightHalf }

internal data class NormalizedCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
    }

    internal companion object {
        internal fun copyOf(value: CropInsetsPx): NormalizedCrop = NormalizedCrop(
            left = value.left,
            top = value.top,
            right = value.right,
            bottom = value.bottom,
        )
    }
}

internal sealed interface NormalizedOutputSize {
    data class ScaleFactor(val factor: Double) : NormalizedOutputSize {
        init {
            require(factor.isFinite() && factor > 0.0)
        }
    }

    data class TargetSize(
        val width: Int,
        val height: Int,
        val contentMode: NormalizedContentMode,
    ) : NormalizedOutputSize {
        init {
            require(width > 0 && height > 0)
        }
    }
}

internal enum class NormalizedContentMode { Stretch, AspectFit }
internal enum class NormalizedRotation { Degrees0, Degrees90, Degrees180, Degrees270 }
internal enum class NormalizedMirror { None, Horizontal, Vertical }
internal enum class NormalizedColorMode { Original, Grayscale }

internal sealed interface NormalizedFrameRate {
    data class MaxFps(val fps: Int) : NormalizedFrameRate {
        init {
            require(fps > 0)
        }
    }

    data class PeriodicRefresh(val intervalMillis: Long) : NormalizedFrameRate {
        init {
            require(intervalMillis > 0L)
        }
    }

    data object Auto : NormalizedFrameRate
}

/** Safe ordinary wrapper for caller behavior; equality never enters provider code. */
internal class ControllerProviderReference(
    val provider: ImageEncoderProvider,
) {
    internal fun refersTo(candidate: ImageEncoderProvider): Boolean = provider === candidate

    override fun equals(other: Any?): Boolean =
        other is ControllerProviderReference && provider === other.provider

    override fun hashCode(): Int = System.identityHashCode(provider)

    override fun toString(): String = "ControllerProviderReference(${provider.safeIdentityLabel()})"
}

/** Desired semantics plus the ordinary caller behavior identity retained by a ledger role. */
internal class ControllerDesiredSnapshot(
    val identity: DesiredSnapshotIdentity,
    val output: NormalizedOutputValues,
    val providerReference: ControllerProviderReference,
) {
    internal fun hasSameRequest(
        requestedOutput: NormalizedOutputValues,
        requestedProvider: ImageEncoderProvider,
    ): Boolean = output == requestedOutput && providerReference.refersTo(requestedProvider)

    internal fun hasSamePlanAndProvider(
        requestedOutput: NormalizedOutputValues,
        requestedProvider: ImageEncoderProvider,
    ): Boolean = output.hasSamePlanValues(requestedOutput) && providerReference.refersTo(requestedProvider)

    override fun equals(other: Any?): Boolean =
        other is ControllerDesiredSnapshot && output == other.output && providerReference == other.providerReference

    override fun hashCode(): Int = 31 * output.hashCode() + providerReference.hashCode()

    override fun toString(): String =
        "ControllerDesiredSnapshot(output=$output, providerReference=$providerReference)"
}

/** Fully planned, still-unbound replacement candidate. */
internal class ControllerCandidateSnapshot(
    val identity: CandidateIdentity,
    val desired: ControllerDesiredSnapshot,
    val geometry: GeometrySnapshot,
    val plan: BaselineOutputPlan,
) {
    override fun equals(other: Any?): Boolean =
        other is ControllerCandidateSnapshot &&
                desired == other.desired &&
                geometry == other.geometry &&
                plan == other.plan

    override fun hashCode(): Int = 31 * (31 * desired.hashCode() + geometry.hashCode()) + plan.hashCode()

    override fun toString(): String =
        "ControllerCandidateSnapshot(desired=$desired, geometry=$geometry, " +
                "finalSize=${plan.finalImageSize.width}x${plan.finalImageSize.height})"
}

/** Provider-free record of the last committed Active output. */
internal class ControllerEffectiveSnapshot(
    val identity: EffectiveSnapshotIdentity,
    val desiredIdentity: DesiredSnapshotIdentity,
    val targetIdentity: TargetIdentity,
    val completeOwnerIdentity: CompleteOwnerIdentity,
    val output: NormalizedOutputValues,
    val geometry: GeometrySnapshot,
    val plan: BaselineOutputPlan,
    val encodedFormat: EncodedFormatDescriptorSnapshot,
) {
    internal fun hasSamePublicValue(other: ControllerEffectiveSnapshot): Boolean =
        output == other.output &&
                geometry == other.geometry &&
                plan.appliedSourceRect == other.plan.appliedSourceRect &&
                plan.finalImageSize == other.plan.finalImageSize &&
                encodedFormat == other.encodedFormat

    override fun equals(other: Any?): Boolean =
        other is ControllerEffectiveSnapshot && hasSamePublicValue(other)

    override fun hashCode(): Int {
        var result = output.hashCode()
        result = 31 * result + geometry.hashCode()
        result = 31 * result + plan.appliedSourceRect.hashCode()
        result = 31 * result + plan.finalImageSize.hashCode()
        return 31 * result + encodedFormat.hashCode()
    }

    override fun toString(): String =
        "ControllerEffectiveSnapshot(output=$output, geometry=$geometry, " +
                "finalSize=${plan.finalImageSize.width}x${plan.finalImageSize.height}, encodedFormat=$encodedFormat)"
}

internal sealed interface ControllerParameterClassification {
    data object NormalizedNoOp : ControllerParameterClassification
    data object FrameRateOnly : ControllerParameterClassification
    data object RequiresPlanning : ControllerParameterClassification

    data class SameTargetReplacement(
        val plan: BaselineOutputPlan,
    ) : ControllerParameterClassification

    data class TargetReplan(
        val plan: BaselineOutputPlan,
        val reason: TargetReplacementReason,
    ) : ControllerParameterClassification

    data class Invalid(
        val reason: RequestNonrepresentability,
    ) : ControllerParameterClassification
}

/** Pure two-stage classifier; exact no-op and frame-only paths never inspect provider descriptors or plan. */
internal object ControllerParameterClassifier {
    internal fun classifyRequest(
        currentDesired: ControllerDesiredSnapshot,
        requestedParameters: ScreenCaptureParameters,
    ): ControllerParameterClassification {
        val sameProvider = currentDesired.providerReference.refersTo(requestedParameters.encoderProvider)
        val samePlan = currentDesired.output.hasSamePlanValues(requestedParameters)
        return when {
            sameProvider && samePlan && currentDesired.output.hasSameFrameRate(requestedParameters) ->
                ControllerParameterClassification.NormalizedNoOp

            sameProvider && samePlan ->
                ControllerParameterClassification.FrameRateOnly

            else -> ControllerParameterClassification.RequiresPlanning
        }
    }

    internal fun classifyReplacement(
        planFact: BaselineOutputPlanFact,
        currentTarget: ControllerTargetSnapshot,
        currentGeometry: GeometrySnapshot,
        requiresFreshIdentity: Boolean,
    ): ControllerParameterClassification = when (planFact) {
        is BaselineOutputPlanFact.RequestNotRepresentable ->
            ControllerParameterClassification.Invalid(planFact.reason)

        is BaselineOutputPlanFact.Planned -> when (
            val retention = currentTarget.retentionFor(
                geometry = currentGeometry,
                demand = planFact.plan.samplingDemand,
                requiresFreshIdentity = requiresFreshIdentity,
            )
        ) {
            TargetRetention.Retain -> ControllerParameterClassification.SameTargetReplacement(planFact.plan)
            is TargetRetention.Replace -> ControllerParameterClassification.TargetReplan(planFact.plan, retention.reason)
        }
    }
}

private fun SourceRegion.normalized(): NormalizedSourceRegion = when (this) {
    SourceRegion.Full -> NormalizedSourceRegion.Full
    SourceRegion.LeftHalf -> NormalizedSourceRegion.LeftHalf
    SourceRegion.RightHalf -> NormalizedSourceRegion.RightHalf
}

private fun OutputSize.normalized(): NormalizedOutputSize = when (this) {
    is OutputSize.ScaleFactor -> NormalizedOutputSize.ScaleFactor(factor)
    is OutputSize.TargetSize -> NormalizedOutputSize.TargetSize(width, height, contentMode.normalized())
}

private fun ContentMode.normalized(): NormalizedContentMode = when (this) {
    ContentMode.Stretch -> NormalizedContentMode.Stretch
    ContentMode.AspectFit -> NormalizedContentMode.AspectFit
}

private fun Rotation.normalized(): NormalizedRotation = when (this) {
    Rotation.Degrees0 -> NormalizedRotation.Degrees0
    Rotation.Degrees90 -> NormalizedRotation.Degrees90
    Rotation.Degrees180 -> NormalizedRotation.Degrees180
    Rotation.Degrees270 -> NormalizedRotation.Degrees270
}

private fun Mirror.normalized(): NormalizedMirror = when (this) {
    Mirror.None -> NormalizedMirror.None
    Mirror.Horizontal -> NormalizedMirror.Horizontal
    Mirror.Vertical -> NormalizedMirror.Vertical
}

private fun ColorMode.normalized(): NormalizedColorMode = when (this) {
    ColorMode.Original -> NormalizedColorMode.Original
    ColorMode.Grayscale -> NormalizedColorMode.Grayscale
}

private fun FrameRate.normalized(): NormalizedFrameRate = when (this) {
    is FrameRate.MaxFps -> NormalizedFrameRate.MaxFps(fps)
    is FrameRate.PeriodicRefresh -> NormalizedFrameRate.PeriodicRefresh(intervalMillis)
    FrameRate.Auto -> NormalizedFrameRate.Auto
}

private fun NormalizedSourceRegion.matches(value: SourceRegion): Boolean = when (this) {
    NormalizedSourceRegion.Full -> value === SourceRegion.Full
    NormalizedSourceRegion.LeftHalf -> value === SourceRegion.LeftHalf
    NormalizedSourceRegion.RightHalf -> value === SourceRegion.RightHalf
}

private fun NormalizedCrop.matches(value: CropInsetsPx): Boolean =
    left == value.left && top == value.top && right == value.right && bottom == value.bottom

private fun NormalizedOutputSize.matches(value: OutputSize): Boolean = when (this) {
    is NormalizedOutputSize.ScaleFactor -> value is OutputSize.ScaleFactor && factor == value.factor
    is NormalizedOutputSize.TargetSize ->
        value is OutputSize.TargetSize &&
                width == value.width &&
                height == value.height &&
                contentMode.matches(value.contentMode)
}

private fun NormalizedContentMode.matches(value: ContentMode): Boolean = when (this) {
    NormalizedContentMode.Stretch -> value === ContentMode.Stretch
    NormalizedContentMode.AspectFit -> value === ContentMode.AspectFit
}

private fun NormalizedRotation.matches(value: Rotation): Boolean = when (this) {
    NormalizedRotation.Degrees0 -> value === Rotation.Degrees0
    NormalizedRotation.Degrees90 -> value === Rotation.Degrees90
    NormalizedRotation.Degrees180 -> value === Rotation.Degrees180
    NormalizedRotation.Degrees270 -> value === Rotation.Degrees270
}

private fun NormalizedMirror.matches(value: Mirror): Boolean = when (this) {
    NormalizedMirror.None -> value === Mirror.None
    NormalizedMirror.Horizontal -> value === Mirror.Horizontal
    NormalizedMirror.Vertical -> value === Mirror.Vertical
}

private fun NormalizedColorMode.matches(value: ColorMode): Boolean = when (this) {
    NormalizedColorMode.Original -> value === ColorMode.Original
    NormalizedColorMode.Grayscale -> value === ColorMode.Grayscale
}

private fun NormalizedFrameRate.matches(value: FrameRate): Boolean = when (this) {
    is NormalizedFrameRate.MaxFps -> value is FrameRate.MaxFps && fps == value.fps
    is NormalizedFrameRate.PeriodicRefresh ->
        value is FrameRate.PeriodicRefresh && intervalMillis == value.intervalMillis

    NormalizedFrameRate.Auto -> value === FrameRate.Auto
}

private fun Any.safeIdentityLabel(): String =
    "${javaClass.name}@${System.identityHashCode(this).toUInt().toString(16)}"
