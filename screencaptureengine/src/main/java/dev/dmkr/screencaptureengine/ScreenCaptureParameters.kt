package dev.dmkr.screencaptureengine

/**
 * User-requested capture/render/encode parameters.
 *
 * These values select the initial plan passed to [ScreenCaptureEngine.startSession] and candidate
 * plans requested through [ScreenCaptureSession.setParameters]. [ScreenCaptureEffectiveParameters]
 * reports the normalized plan that is active for the current geometry and runtime capabilities.
 *
 * Runtime updates support normalized no-ops, frame-rate-only changes, provider-only changes with an
 * unchanged encoder input shape, and complete output-plan replacements that reuse the current
 * projection target and virtual-display target assignment. Requests that require retargeting, target
 * replacement, live geometry replan, or recovery from [ScreenCaptureOutputState.Suspended] are
 * rejected with [ScreenCaptureProblemKind.ParameterUpdateUnavailable].
 */
public class ScreenCaptureParameters public constructor(
    /** Logical source region selected before crop. */
    public val sourceRegion: SourceRegion = SourceRegion.Full,

    /** Logical pixel crop applied inside [sourceRegion]. */
    public val crop: CropInsetsPx = CropInsetsPx.Zero,

    /** Final encoded image sizing policy. */
    public val outputSize: OutputSize = OutputSize.ScaleFactor(1.0),

    /** Rotation applied after source selection and crop. */
    public val rotation: Rotation = Rotation.Degrees0,

    /** Mirror transform applied during final rendering. */
    public val mirror: Mirror = Mirror.None,

    /** Final color transform before encoding. */
    public val colorMode: ColorMode = ColorMode.Original,

    /** Production pacing policy. */
    public val frameRate: FrameRate = FrameRate.Auto,

    /** Synchronous encoded-image provider. */
    public val encoderProvider: ImageEncoderProvider = JpegImageEncoderProvider(),
) {
    init {
        require(encoderProvider.id.isNotBlank()) { "encoderProvider.id must not be blank" }
        require(encoderProvider.outputFormat.name.isNotBlank()) { "encoderProvider.outputFormat.name must not be blank" }
        require(encoderProvider.outputFormat.mimeType.isNotBlank()) { "encoderProvider.outputFormat.mimeType must not be blank" }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureParameters && sourceRegion == other.sourceRegion && crop == other.crop && outputSize == other.outputSize &&
                rotation == other.rotation && mirror == other.mirror && colorMode == other.colorMode && frameRate == other.frameRate &&
                encoderProvider == other.encoderProvider

    public override fun hashCode(): Int {
        var result = sourceRegion.hashCode()
        result = 31 * result + crop.hashCode()
        result = 31 * result + outputSize.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + mirror.hashCode()
        result = 31 * result + colorMode.hashCode()
        result = 31 * result + frameRate.hashCode()
        result = 31 * result + encoderProvider.hashCode()
        return result
    }

    public companion object {
        /** Default parameter set. */
        public fun defaults(): ScreenCaptureParameters = ScreenCaptureParameters()
    }
}
