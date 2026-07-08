package dev.dmkr.screencaptureengine

/**
 * Effective parameters after validation, geometry resolution, and backend selection.
 *
 * These are the authoritative public coordinates and dimensions for active output. In particular, [appliedSourceRect] is always reported in logical
 * captured-content coordinates before capture-target sampling, even when [captureTarget] is downscaled.
 */
public class ScreenCaptureEffectiveParameters public constructor(
    /** Geometry used to derive this plan. */
    public val captureGeometry: CaptureGeometry,

    /** Projection target selected for this plan. */
    public val captureTarget: CaptureTarget,

    /** Source region requested by the owner. */
    public val sourceRegion: SourceRegion,

    /** Crop requested by the owner. */
    public val crop: CropInsetsPx,

    /** Final selected source rect in logical captured-content coordinates. */
    public val appliedSourceRect: ImageRect,

    /** Source size after crop and rotation, before final output sizing. */
    public val orientedContentSize: Size,

    /** Output sizing policy requested by the owner. */
    public val outputSize: OutputSize,

    /** Final encoded image dimensions. */
    public val finalImageSize: Size,

    /** Rotation applied by this plan. */
    public val rotation: Rotation,

    /** Mirror transform applied by this plan. */
    public val mirror: Mirror,

    /** Color transform applied by this plan. */
    public val colorMode: ColorMode,

    /** Readback backend selected by this plan. */
    public val readbackMode: ReadbackMode,

    /** Encoder provider/backend selected by this plan. */
    public val encoderInfo: ImageEncoderInfo,

    /** Frame-rate policy applied by this plan. */
    public val frameRate: FrameRate,
) {
    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureEffectiveParameters && captureGeometry == other.captureGeometry && captureTarget == other.captureTarget &&
                sourceRegion == other.sourceRegion && crop == other.crop && appliedSourceRect == other.appliedSourceRect &&
                orientedContentSize == other.orientedContentSize && outputSize == other.outputSize && finalImageSize == other.finalImageSize &&
                rotation == other.rotation && mirror == other.mirror && colorMode == other.colorMode && readbackMode == other.readbackMode &&
                encoderInfo == other.encoderInfo && frameRate == other.frameRate

    public override fun hashCode(): Int {
        var result = captureGeometry.hashCode()
        result = 31 * result + captureTarget.hashCode()
        result = 31 * result + sourceRegion.hashCode()
        result = 31 * result + crop.hashCode()
        result = 31 * result + appliedSourceRect.hashCode()
        result = 31 * result + orientedContentSize.hashCode()
        result = 31 * result + outputSize.hashCode()
        result = 31 * result + finalImageSize.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + mirror.hashCode()
        result = 31 * result + colorMode.hashCode()
        result = 31 * result + readbackMode.hashCode()
        result = 31 * result + encoderInfo.hashCode()
        result = 31 * result + frameRate.hashCode()
        return result
    }
}
