package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureEffectiveParameters
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind

/**
 * Pure classifier for v37 runtime parameter-update decisions.
 *
 * The classifier prepares no runtime resources and calls no provider code. It only replans the
 * requested parameters against the supplied active snapshot, checks the same-target boundary, and
 * returns the transaction shape the lifecycle layer may attempt.
 */
internal class RuntimeParameterUpdateClassifier internal constructor(
    private val planner: ScreenCaptureOutputPlanner,
) {
    internal fun classify(
        activeSnapshot: RuntimeParameterActiveSnapshot,
        requestedParameters: ScreenCaptureParameters,
    ): RuntimeParameterUpdateClassification {
        if (activeSnapshot.outputState != RuntimeParameterOutputState.Active) {
            return RuntimeParameterUpdateClassification.Unavailable(
                RuntimeParameterUpdateUnavailableReason.SuspendedOutputRecovery,
            )
        }

        val candidatePlan = when (
            val result = planner.plan(
                geometry = activeSnapshot.currentCaptureGeometry,
                parameters = requestedParameters,
            )
        ) {
            is OutputPlanResult.Failure -> return RuntimeParameterUpdateClassification.Rejected(
                RuntimeParameterUpdatePlanningProblem(kind = result.kind, message = result.message),
            )

            is OutputPlanResult.Success -> result.plan
        }

        val candidateTarget = RuntimeProjectionTargetIdentity(
            width = candidatePlan.captureTarget.width,
            height = candidatePlan.captureTarget.height,
            densityDpi = candidatePlan.captureGeometry.densityDpi,
            captureGeometryGeneration = activeSnapshot.currentCaptureGeometryGeneration,
            targetGeneration = activeSnapshot.currentProjectionTarget.targetGeneration,
            semantics = activeSnapshot.currentProjectionTarget.semantics,
        )
        if (!candidateTarget.isSameTargetAs(activeSnapshot.currentProjectionTarget)) {
            return RuntimeParameterUpdateClassification.Unavailable(
                RuntimeParameterUpdateUnavailableReason.TargetChangeRequired,
            )
        }

        val sameProviderConfig = hasSameProviderIdentityAndConfiguration(
            activeSnapshot = activeSnapshot,
            requestedProvider = requestedParameters.encoderProvider,
        )
        return when {
            sameProviderConfig && candidatePlan.hasSameOutputPlanAs(activeSnapshot.currentOutputPlan) ->
                RuntimeParameterUpdateClassification.NormalizedNoOp(candidatePlan)

            sameProviderConfig && candidatePlan.hasSameOutputPlanExceptFrameRateAs(activeSnapshot.currentOutputPlan) ->
                RuntimeParameterUpdateClassification.FrameRateOnly(candidatePlan)

            !sameProviderConfig && candidatePlan.hasSameOutputPlanAs(activeSnapshot.currentOutputPlan) ->
                RuntimeParameterUpdateClassification.ProviderOnlySameTarget(candidatePlan)

            else -> RuntimeParameterUpdateClassification.FullSameTargetReplacement(candidatePlan)
        }
    }

    private fun hasSameProviderIdentityAndConfiguration(
        activeSnapshot: RuntimeParameterActiveSnapshot,
        requestedProvider: ImageEncoderProvider,
    ): Boolean {
        val activeProvider = activeSnapshot.currentRequestedParameters.encoderProvider
        val activeEncoderInfo = activeSnapshot.currentEffectiveParameters.encoderInfo
        return requestedProvider === activeProvider &&
                requestedProvider.id == activeProvider.id &&
                requestedProvider.outputFormat == activeProvider.outputFormat &&
                requestedProvider.id == activeEncoderInfo.providerId &&
                requestedProvider.outputFormat == activeEncoderInfo.outputFormat
    }
}

internal class RuntimeParameterActiveSnapshot internal constructor(
    internal val outputState: RuntimeParameterOutputState,
    internal val currentRequestedParameters: ScreenCaptureParameters,
    internal val currentOutputPlan: ScreenCaptureOutputPlan,
    internal val currentEffectiveParameters: ScreenCaptureEffectiveParameters,
    internal val currentCaptureGeometry: CaptureGeometry,
    internal val currentCaptureGeometryGeneration: Long,
    internal val currentProjectionTarget: RuntimeProjectionTargetIdentity,
) {
    init {
        require(currentCaptureGeometryGeneration >= 0L) {
            "currentCaptureGeometryGeneration must be non-negative, was $currentCaptureGeometryGeneration"
        }
    }
}

internal enum class RuntimeParameterOutputState {
    Active,
    Suspended,
}

internal class RuntimeProjectionTargetIdentity internal constructor(
    internal val width: Int,
    internal val height: Int,
    internal val densityDpi: Int,
    internal val captureGeometryGeneration: Long,
    internal val targetGeneration: Long,
    internal val semantics: RuntimeProjectionTargetSemantics,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
        require(captureGeometryGeneration >= 0L) {
            "captureGeometryGeneration must be non-negative, was $captureGeometryGeneration"
        }
        require(targetGeneration >= 0L) { "targetGeneration must be non-negative, was $targetGeneration" }
    }

    internal fun isSameTargetAs(current: RuntimeProjectionTargetIdentity): Boolean =
        width == current.width &&
                height == current.height &&
                densityDpi == current.densityDpi &&
                captureGeometryGeneration == current.captureGeometryGeneration &&
                targetGeneration == current.targetGeneration &&
                semantics == current.semantics
}

internal enum class RuntimeProjectionTargetSemantics {
    SurfaceTextureOes,
    ExternalSurface,
}

internal sealed class RuntimeParameterUpdateClassification private constructor() {
    internal abstract val candidatePlan: ScreenCaptureOutputPlan?

    internal class NormalizedNoOp internal constructor(
        override val candidatePlan: ScreenCaptureOutputPlan,
    ) : RuntimeParameterUpdateClassification()

    internal class FrameRateOnly internal constructor(
        override val candidatePlan: ScreenCaptureOutputPlan,
    ) : RuntimeParameterUpdateClassification()

    internal class ProviderOnlySameTarget internal constructor(
        override val candidatePlan: ScreenCaptureOutputPlan,
    ) : RuntimeParameterUpdateClassification()

    internal class FullSameTargetReplacement internal constructor(
        override val candidatePlan: ScreenCaptureOutputPlan,
    ) : RuntimeParameterUpdateClassification()

    internal class Rejected internal constructor(
        internal val problem: RuntimeParameterUpdatePlanningProblem,
    ) : RuntimeParameterUpdateClassification() {
        override val candidatePlan: ScreenCaptureOutputPlan? = null
    }

    internal class Unavailable internal constructor(
        internal val reason: RuntimeParameterUpdateUnavailableReason,
    ) : RuntimeParameterUpdateClassification() {
        override val candidatePlan: ScreenCaptureOutputPlan? = null

        internal val problemKind: ScreenCaptureProblemKind = ScreenCaptureProblemKind.ParameterUpdateUnavailable
    }
}

internal class RuntimeParameterUpdatePlanningProblem internal constructor(
    internal val kind: ScreenCaptureProblemKind,
    internal val message: String,
)

internal enum class RuntimeParameterUpdateUnavailableReason {
    TargetChangeRequired,
    SuspendedOutputRecovery,
}

private fun ScreenCaptureOutputPlan.hasSameOutputPlanAs(other: ScreenCaptureOutputPlan): Boolean =
    hasSameOutputPlanExceptFrameRateAs(other) && frameRate == other.frameRate

private fun ScreenCaptureOutputPlan.hasSameOutputPlanExceptFrameRateAs(other: ScreenCaptureOutputPlan): Boolean =
    captureGeometry == other.captureGeometry &&
            captureTarget == other.captureTarget &&
            sourceRegion == other.sourceRegion &&
            crop == other.crop &&
            appliedSourceRect == other.appliedSourceRect &&
            orientedContentSize == other.orientedContentSize &&
            outputSize == other.outputSize &&
            finalImageSize == other.finalImageSize &&
            rotation == other.rotation &&
            mirror == other.mirror &&
            colorMode == other.colorMode &&
            readbackMode == other.readbackMode &&
            encoderRequest == other.encoderRequest &&
            rowStrideBytes == other.rowStrideBytes &&
            rgbaByteCount == other.rgbaByteCount
