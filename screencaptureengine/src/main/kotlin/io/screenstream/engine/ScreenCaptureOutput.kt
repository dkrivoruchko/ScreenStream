package io.screenstream.engine

public class ImageRect internal constructor(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageRect) return false

        return left == other.left && top == other.top && right == other.right && bottom == other.bottom
    }

    public override fun hashCode(): Int {
        var result: Int = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }

    public override fun toString(): String = "ImageRect(left=$left, top=$top, right=$right, bottom=$bottom)"
}

public class CaptureGeometry internal constructor(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureGeometry) return false

        return widthPx == other.widthPx && heightPx == other.heightPx && densityDpi == other.densityDpi
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = 31 * result + heightPx.hashCode()
        result = 31 * result + densityDpi.hashCode()
        return result
    }

    public override fun toString(): String =
        "CaptureGeometry(widthPx=$widthPx, heightPx=$heightPx, densityDpi=$densityDpi)"
}

public class ImageSize internal constructor(
    public val widthPx: Int,
    public val heightPx: Int,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageSize) return false

        return widthPx == other.widthPx && heightPx == other.heightPx
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = 31 * result + heightPx.hashCode()
        return result
    }

    public override fun toString(): String = "ImageSize(widthPx=$widthPx, heightPx=$heightPx)"
}

public class ScreenCaptureEffectiveParameters internal constructor(
    public val captureGeometry: CaptureGeometry,
    public val sourceRegion: SourceRegion,
    public val crop: CropInsetsPx,
    public val appliedSourceRect: ImageRect,
    public val outputSize: OutputSize,
    public val finalImageSize: ImageSize,
    public val rotation: Rotation,
    public val mirror: Mirror,
    public val colorMode: ColorMode,
    public val frameRate: FrameRate,
    public val frameRepeatIntervalMillis: Long?,
    public val jpegQuality: Int,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureEffectiveParameters) return false

        return captureGeometry == other.captureGeometry &&
                sourceRegion == other.sourceRegion &&
                crop == other.crop &&
                appliedSourceRect == other.appliedSourceRect &&
                outputSize == other.outputSize &&
                finalImageSize == other.finalImageSize &&
                rotation == other.rotation &&
                mirror == other.mirror &&
                colorMode == other.colorMode &&
                frameRate == other.frameRate &&
                frameRepeatIntervalMillis == other.frameRepeatIntervalMillis &&
                jpegQuality == other.jpegQuality
    }

    public override fun hashCode(): Int {
        var result: Int = captureGeometry.hashCode()
        result = 31 * result + sourceRegion.hashCode()
        result = 31 * result + crop.hashCode()
        result = 31 * result + appliedSourceRect.hashCode()
        result = 31 * result + outputSize.hashCode()
        result = 31 * result + finalImageSize.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + mirror.hashCode()
        result = 31 * result + colorMode.hashCode()
        result = 31 * result + frameRate.hashCode()
        result = 31 * result + (frameRepeatIntervalMillis?.hashCode() ?: 0)
        result = 31 * result + jpegQuality.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureEffectiveParameters(" +
                "captureGeometry=$captureGeometry, " +
                "sourceRegion=$sourceRegion, " +
                "crop=$crop, " +
                "appliedSourceRect=$appliedSourceRect, " +
                "outputSize=$outputSize, " +
                "finalImageSize=$finalImageSize, " +
                "rotation=$rotation, " +
                "mirror=$mirror, " +
                "colorMode=$colorMode, " +
                "frameRate=$frameRate, " +
                "frameRepeatIntervalMillis=$frameRepeatIntervalMillis, " +
                "jpegQuality=$jpegQuality)"
}

public interface EncodedImageFrame {
    public val byteCount: Int
    public val imageSize: ImageSize
    public val sequence: Long
    public val timestampElapsedRealtimeNanos: Long

    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int

    public fun copyBytes(): ByteArray
}
