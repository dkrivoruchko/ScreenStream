package io.screenstream.engine

public class ScreenCaptureParameters(
    public val sourceRegion: SourceRegion = SourceRegion.Full,
    public val crop: CropInsetsPx = CropInsetsPx.Zero,
    public val outputSize: OutputSize = OutputSize.ScaleFactor(0.5),
    public val rotation: Rotation = Rotation.Degrees0,
    public val mirror: Mirror = Mirror.None,
    public val colorMode: ColorMode = ColorMode.Color,
    public val frameRate: FrameRate = FrameRate.Auto,
    public val frameRepeatIntervalMillis: Long? = null,
    public val jpegQuality: Int = 80,
) {
    init {
        requireLocallyValid()
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureParameters) return false

        return sourceRegion == other.sourceRegion &&
                crop == other.crop &&
                outputSize == other.outputSize &&
                rotation == other.rotation &&
                mirror == other.mirror &&
                colorMode == other.colorMode &&
                frameRate == other.frameRate &&
                frameRepeatIntervalMillis == other.frameRepeatIntervalMillis &&
                jpegQuality == other.jpegQuality
    }

    public override fun hashCode(): Int {
        var result: Int = sourceRegion.hashCode()
        result = 31 * result + crop.hashCode()
        result = 31 * result + outputSize.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + mirror.hashCode()
        result = 31 * result + colorMode.hashCode()
        result = 31 * result + frameRate.hashCode()
        result = 31 * result + (frameRepeatIntervalMillis?.hashCode() ?: 0)
        result = 31 * result + jpegQuality.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureParameters(" +
                "sourceRegion=$sourceRegion, " +
                "crop=$crop, " +
                "outputSize=$outputSize, " +
                "rotation=$rotation, " +
                "mirror=$mirror, " +
                "colorMode=$colorMode, " +
                "frameRate=$frameRate, " +
                "frameRepeatIntervalMillis=$frameRepeatIntervalMillis, " +
                "jpegQuality=$jpegQuality)"
}

public enum class SourceRegion {
    Full,
    LeftHalf,
    RightHalf,
}

public class CropInsetsPx(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
) {
    init {
        require(left >= 0) { "left must be non-negative" }
        require(top >= 0) { "top must be non-negative" }
        require(right >= 0) { "right must be non-negative" }
        require(bottom >= 0) { "bottom must be non-negative" }
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CropInsetsPx) return false

        return left == other.left && top == other.top && right == other.right && bottom == other.bottom
    }

    public override fun hashCode(): Int {
        var result: Int = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }

    public override fun toString(): String = "CropInsetsPx(left=$left, top=$top, right=$right, bottom=$bottom)"

    public companion object {
        public val Zero: CropInsetsPx = CropInsetsPx(left = 0, top = 0, right = 0, bottom = 0)
    }
}

public sealed interface OutputSize {

    public class ScaleFactor(public val factor: Double) : OutputSize {
        init {
            require(factor.isFinite() && factor > 0.0) { "factor must be finite and positive" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScaleFactor) return false

            return factor == other.factor
        }

        public override fun hashCode(): Int = factor.hashCode()

        public override fun toString(): String = "ScaleFactor(factor=$factor)"
    }

    public class TargetSize(
        public val width: Int,
        public val height: Int,
        public val contentMode: ContentMode = ContentMode.AspectFit,
    ) : OutputSize {
        init {
            require(width > 0) { "width must be positive" }
            require(height > 0) { "height must be positive" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TargetSize) return false

            return width == other.width && height == other.height && contentMode == other.contentMode
        }

        public override fun hashCode(): Int {
            var result: Int = width.hashCode()
            result = 31 * result + height.hashCode()
            result = 31 * result + contentMode.hashCode()
            return result
        }

        public override fun toString(): String = "TargetSize(width=$width, height=$height, contentMode=$contentMode)"
    }
}

public enum class ContentMode {
    Stretch,
    AspectFit,
}

public enum class Rotation {
    Degrees0,
    Degrees90,
    Degrees180,
    Degrees270,
}

public enum class Mirror {
    None,
    Horizontal,
    Vertical,
}

public enum class ColorMode {
    Color,
    Grayscale,
}

public sealed interface FrameRate {

    public object Auto : FrameRate {
        public override fun equals(other: Any?): Boolean = other is Auto

        public override fun hashCode(): Int = javaClass.hashCode()

        public override fun toString(): String = "Auto"
    }

    public class MaxFps(public val fps: Int) : FrameRate {
        init {
            require(fps in 1..120) { "fps must be in 1..120" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MaxFps) return false

            return fps == other.fps
        }

        public override fun hashCode(): Int = fps.hashCode()

        public override fun toString(): String = "MaxFps(fps=$fps)"
    }

    public class SampleEvery(public val intervalMillis: Long) : FrameRate {
        init {
            require(intervalMillis in 1_001L..3_600_000L) { "intervalMillis must be in 1001..3600000" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SampleEvery) return false

            return intervalMillis == other.intervalMillis
        }

        public override fun hashCode(): Int = intervalMillis.hashCode()

        public override fun toString(): String = "SampleEvery(intervalMillis=$intervalMillis)"
    }
}

@JvmSynthetic
internal fun ScreenCaptureParameters.requireLocallyValid() {
    require(crop.left >= 0) { "left must be non-negative" }
    require(crop.top >= 0) { "top must be non-negative" }
    require(crop.right >= 0) { "right must be non-negative" }
    require(crop.bottom >= 0) { "bottom must be non-negative" }

    when (outputSize) {
        is OutputSize.ScaleFactor ->
            require(outputSize.factor.isFinite() && outputSize.factor > 0.0) { "factor must be finite and positive" }

        is OutputSize.TargetSize -> {
            require(outputSize.width > 0) { "width must be positive" }
            require(outputSize.height > 0) { "height must be positive" }
        }
    }

    when (frameRate) {
        FrameRate.Auto -> Unit
        is FrameRate.MaxFps ->
            require(frameRate.fps in 1..120) { "fps must be in 1..120" }

        is FrameRate.SampleEvery ->
            require(frameRate.intervalMillis in 1_001L..3_600_000L) { "intervalMillis must be in 1001..3600000" }
    }

    require(frameRepeatIntervalMillis == null || frameRepeatIntervalMillis in 1_000L..3_600_000L) {
        "frameRepeatIntervalMillis must be null or in 1000..3600000"
    }
    require(jpegQuality in 0..100) { "jpegQuality must be in 0..100" }
}
