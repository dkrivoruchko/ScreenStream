package io.screenstream.engine

public class ImageRect private constructor(
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

    internal companion object {
        @JvmSynthetic
        internal fun create(left: Int, top: Int, right: Int, bottom: Int): ImageRect =
            ImageRect(left, top, right, bottom)
    }
}

public class CaptureGeometry private constructor(
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

    internal companion object {
        @JvmSynthetic
        internal fun create(widthPx: Int, heightPx: Int, densityDpi: Int): CaptureGeometry =
            CaptureGeometry(widthPx, heightPx, densityDpi)
    }
}

public class ImageSize private constructor(
    public val widthPx: Int,
    public val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
    }

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

    internal companion object {
        @JvmSynthetic
        internal fun create(widthPx: Int, heightPx: Int): ImageSize = ImageSize(widthPx, heightPx)
    }
}

public class ScreenCaptureEffectiveParameters private constructor(
    public val appliedParameters: ScreenCaptureParameters,
    public val captureGeometry: CaptureGeometry,
    public val appliedSourceRect: ImageRect,
    public val finalImageSize: ImageSize,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureEffectiveParameters) return false

        return appliedParameters == other.appliedParameters &&
                captureGeometry == other.captureGeometry &&
                appliedSourceRect == other.appliedSourceRect &&
                finalImageSize == other.finalImageSize
    }

    public override fun hashCode(): Int {
        var result: Int = appliedParameters.hashCode()
        result = 31 * result + captureGeometry.hashCode()
        result = 31 * result + appliedSourceRect.hashCode()
        result = 31 * result + finalImageSize.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureEffectiveParameters(" +
                "appliedParameters=$appliedParameters, " +
                "captureGeometry=$captureGeometry, " +
                "appliedSourceRect=$appliedSourceRect, " +
                "finalImageSize=$finalImageSize)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            appliedParameters: ScreenCaptureParameters,
            captureGeometry: CaptureGeometry,
            appliedSourceRect: ImageRect,
            finalImageSize: ImageSize,
        ): ScreenCaptureEffectiveParameters = ScreenCaptureEffectiveParameters(
            appliedParameters = appliedParameters,
            captureGeometry = captureGeometry,
            appliedSourceRect = appliedSourceRect,
            finalImageSize = finalImageSize,
        )
    }
}

public class EncodedImageFrame private constructor(
    private val readByteCount: () -> Int,
    private val readEffectiveParameters: () -> ScreenCaptureEffectiveParameters,
    private val readSequence: () -> Long,
    private val readTimestampElapsedRealtimeNanos: () -> Long,
    private val copyToOperation: (ByteArray, Int) -> Int,
    private val copyBytesOperation: () -> ByteArray,
) {
    public val byteCount: Int
        get() = readByteCount()

    public val effectiveParameters: ScreenCaptureEffectiveParameters
        get() = readEffectiveParameters()

    public val sequence: Long
        get() = readSequence()

    public val timestampElapsedRealtimeNanos: Long
        get() = readTimestampElapsedRealtimeNanos()

    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int =
        copyToOperation(destination, destinationOffset)

    public fun copyBytes(): ByteArray = copyBytesOperation()

    internal companion object {
        @JvmSynthetic
        internal fun create(
            readByteCount: () -> Int,
            readEffectiveParameters: () -> ScreenCaptureEffectiveParameters,
            readSequence: () -> Long,
            readTimestampElapsedRealtimeNanos: () -> Long,
            copyTo: (ByteArray, Int) -> Int,
            copyBytes: () -> ByteArray,
        ): EncodedImageFrame = EncodedImageFrame(
            readByteCount = readByteCount,
            readEffectiveParameters = readEffectiveParameters,
            readSequence = readSequence,
            readTimestampElapsedRealtimeNanos = readTimestampElapsedRealtimeNanos,
            copyToOperation = copyTo,
            copyBytesOperation = copyBytes,
        )
    }
}
