package dev.dmkr.screencaptureengine.internal.rendering.es2

import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot

/**
 * Builds the immutable render metadata for the initial output plan.
 *
 * The package validates that the frozen output plan, projection target snapshot, ES2 readback spec,
 * and selected ES2 program metadata agree before runtime takes ownership. It records only static
 * transform data plus the dynamic OES matrix slot; it does not fetch a concrete OES matrix, render,
 * read pixels, encode, publish frames, or install the frame loop.
 */
internal object FirstPlanRenderTransformPackageBuilder {
    fun build(request: FirstPlanRenderTransformPackageBuildRequest): FirstPlanRenderTransformPackageBuildResult {
        validateProjectionTarget(request)?.let { return it }
        validateReadbackShape(outputPlan = request.outputPlan, readbackSpec = request.readbackSpec)?.let { return it }
        validateProgramBinding(outputPlan = request.outputPlan, programBinding = request.programBinding)?.let { return it }

        val plan = request.outputPlan
        val logicalContentSize = Size(width = plan.captureGeometry.widthPx, height = plan.captureGeometry.heightPx)
        val captureTargetSize = Size(width = plan.captureTarget.width, height = plan.captureTarget.height)
        val logicalToCaptureTargetMapping = buildLogicalToCaptureTargetMapping(
            logicalContentSize = logicalContentSize,
            captureTargetSize = captureTargetSize,
            appliedSourceRect = plan.appliedSourceRect,
        ) ?: return failure(
            kind = ScreenCaptureProblemKind.GlResourceFailure,
            message = "First-plan logical-to-capture-target mapping is not representable.",
        )
        val sourceTransformMatrix = buildStaticSourceTransformMatrix(
            logicalContentSize = logicalContentSize,
            appliedSourceRect = plan.appliedSourceRect,
            rotation = plan.rotation,
            mirror = plan.mirror,
        ) ?: return failure(
            kind = ScreenCaptureProblemKind.GlResourceFailure,
            message = "First-plan static render transform matrix is not representable.",
        )

        return FirstPlanRenderTransformPackageBuildResult.Success(
            renderTransformPackage = FirstPlanRenderTransformPackage(
                projectionTargetGeneration = request.projectionTarget.generation,
                logicalContentSize = logicalContentSize,
                captureTargetSize = captureTargetSize,
                appliedSourceRect = plan.appliedSourceRect,
                logicalToCaptureTargetMapping = logicalToCaptureTargetMapping,
                outputViewport = FirstPlanRenderViewport(
                    x = 0,
                    y = 0,
                    width = plan.finalImageSize.width,
                    height = plan.finalImageSize.height,
                ),
                sourceTransformMatrix = sourceTransformMatrix,
                colorMode = plan.colorMode,
                programBinding = request.programBinding,
                dynamicOesMatrixSlot = FirstPlanDynamicOesMatrixSlot(
                    uniformName = request.programBinding.textureMatrixUniformName,
                    uniformLocation = request.programBinding.textureMatrixUniformLocation,
                    valueShape = FirstPlanMatrixValueShape.Mat4ColumnMajorFloatArray,
                ),
                oesCompositionRule = FirstPlanOesCompositionRule.DynamicOesMatrixAfterStaticPlanTransform,
                encoderInputNormalizationStrategy = FirstPlanEncoderInputNormalizationStrategy.RenderSpaceVerticalInversion,
                readbackShape = FirstPlanReadbackShape(
                    width = request.readbackSpec.width,
                    height = request.readbackSpec.height,
                    rowStrideBytes = request.readbackSpec.rowStrideBytes,
                    byteCount = request.readbackSpec.byteCount,
                    inputFormat = request.readbackSpec.inputFormat,
                    readbackMode = request.readbackSpec.readbackMode,
                    rowOrder = FirstPlanReadbackRowOrder.TopToBottom,
                ),
            ),
        )
    }

    private fun validateProjectionTarget(request: FirstPlanRenderTransformPackageBuildRequest): FirstPlanRenderTransformPackageBuildResult.Failure? {
        val target = request.projectionTarget
        val plan = request.outputPlan
        return when {
            target.generation <= 0L -> failure(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Projection target generation must be positive.",
            )

            target.width != plan.captureTarget.width || target.height != plan.captureTarget.height -> failure(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Projection target dimensions do not match the first output plan.",
            )

            target.densityDpi != plan.captureGeometry.densityDpi -> failure(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "Projection target density does not match the first output plan.",
            )

            else -> null
        }
    }

    private fun validateReadbackShape(
        outputPlan: ScreenCaptureOutputPlan,
        readbackSpec: Es2ReadbackSpec,
    ): FirstPlanRenderTransformPackageBuildResult.Failure? {
        val tightRowStrideBytes = checkedRgba8888RowStrideBytes(outputPlan.finalImageSize.width)
            ?: return failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan readback row stride overflowed.",
            )
        val checkedByteCount = checkedMultiplyLong(readbackSpec.rowStrideBytes, readbackSpec.height)
            ?: return failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan readback byte count overflowed.",
            )
        return when {
            outputPlan.readbackMode != ReadbackMode.Es2 || readbackSpec.readbackMode != ReadbackMode.Es2 -> failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan render transform package requires ES2 readback.",
            )

            outputPlan.encoderRequest.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque ||
                    readbackSpec.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque -> failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan render transform package requires RGBA8888 sRGB opaque encoder input.",
            )

            readbackSpec.width != outputPlan.finalImageSize.width || readbackSpec.height != outputPlan.finalImageSize.height -> failure(
                kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                message = "First-plan readback dimensions do not match the output plan.",
            )

            outputPlan.rowStrideBytes != tightRowStrideBytes || outputPlan.encoderRequest.rowStrideBytes != tightRowStrideBytes ||
                    readbackSpec.rowStrideBytes != tightRowStrideBytes -> failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan ES2 readback requires tight RGBA8888 row stride.",
            )

            outputPlan.rgbaByteCount != checkedByteCount || readbackSpec.byteCount != checkedByteCount -> failure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "First-plan readback byte count does not match the output plan.",
            )

            else -> null
        }
    }

    private fun validateProgramBinding(
        outputPlan: ScreenCaptureOutputPlan,
        programBinding: FirstPlanEs2ProgramBinding,
    ): FirstPlanRenderTransformPackageBuildResult.Failure? =
        when {
            programBinding.shaderVariant.supportedColorMode != outputPlan.colorMode -> failure(
                kind = ScreenCaptureProblemKind.GlResourceFailure,
                message = "First-plan color mode is not supported by the selected ES2 shader variant.",
            )

            programBinding.programId <= 0 -> failure(
                kind = ScreenCaptureProblemKind.GlResourceFailure,
                message = "First-plan ES2 program id is invalid.",
            )

            programBinding.positionAttributeLocation < 0 ||
                    programBinding.textureCoordinateAttributeLocation < 0 ||
                    programBinding.textureSamplerUniformLocation < 0 ||
                    programBinding.textureMatrixUniformLocation < 0 -> failure(
                kind = ScreenCaptureProblemKind.GlResourceFailure,
                message = "First-plan ES2 program is missing required locations.",
            )

            else -> null
        }

    private fun buildLogicalToCaptureTargetMapping(
        logicalContentSize: Size,
        captureTargetSize: Size,
        appliedSourceRect: ImageRect,
    ): FirstPlanLogicalToCaptureTargetMapping? {
        if (appliedSourceRect.width <= 0 || appliedSourceRect.height <= 0) return null
        val scaleX = captureTargetSize.width.toDouble() / logicalContentSize.width.toDouble()
        val scaleY = captureTargetSize.height.toDouble() / logicalContentSize.height.toDouble()
        if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0.0 || scaleY <= 0.0) return null

        val sourceRectPx = FirstPlanFloatRect(
            left = (appliedSourceRect.left.toDouble() * scaleX).toFloat(),
            top = (appliedSourceRect.top.toDouble() * scaleY).toFloat(),
            right = (appliedSourceRect.right.toDouble() * scaleX).toFloat(),
            bottom = (appliedSourceRect.bottom.toDouble() * scaleY).toFloat(),
        )
        val sourceRectNormalized = FirstPlanFloatRect(
            left = appliedSourceRect.left.toFloat() / logicalContentSize.width.toFloat(),
            top = appliedSourceRect.top.toFloat() / logicalContentSize.height.toFloat(),
            right = appliedSourceRect.right.toFloat() / logicalContentSize.width.toFloat(),
            bottom = appliedSourceRect.bottom.toFloat() / logicalContentSize.height.toFloat(),
        )
        return if (sourceRectPx.isFiniteNonEmpty() && sourceRectNormalized.isFiniteNonEmpty()) {
            FirstPlanLogicalToCaptureTargetMapping(
                scaleX = scaleX.toFloat(),
                scaleY = scaleY.toFloat(),
                sourceRectInCaptureTargetPixels = sourceRectPx,
                sourceRectInCaptureTargetNormalized = sourceRectNormalized,
            )
        } else {
            null
        }
    }

    private fun buildStaticSourceTransformMatrix(
        logicalContentSize: Size,
        appliedSourceRect: ImageRect,
        rotation: Rotation,
        mirror: Mirror,
    ): FirstPlanRenderMatrix4? {
        val sourceLeft = appliedSourceRect.left.toDouble() / logicalContentSize.width.toDouble()
        val sourceTop = appliedSourceRect.top.toDouble() / logicalContentSize.height.toDouble()
        val sourceWidth = appliedSourceRect.width.toDouble() / logicalContentSize.width.toDouble()
        val sourceHeight = appliedSourceRect.height.toDouble() / logicalContentSize.height.toDouble()
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return null

        val matrix = Matrix3.identity()
            .then(framebufferToLogicalOutputMatrix())
            .then(mirror.toInverseOutputMatrix())
            .then(rotation.toInverseSourceMatrix())
            .then(Matrix3.scale(sourceWidth, sourceHeight))
            .then(Matrix3.translate(sourceLeft, sourceTop))
        return matrix.toRenderMatrix4OrNull()
    }

    private fun failure(
        kind: ScreenCaptureProblemKind,
        message: String,
        cause: Throwable? = null,
    ): FirstPlanRenderTransformPackageBuildResult.Failure =
        FirstPlanRenderTransformPackageBuildResult.Failure(kind = kind, message = message, cause = cause)
}

internal class FirstPlanRenderTransformPackageBuildRequest internal constructor(
    internal val outputPlan: ScreenCaptureOutputPlan,
    internal val projectionTarget: ProjectionTargetSnapshot,
    internal val readbackSpec: Es2ReadbackSpec,
    internal val programBinding: FirstPlanEs2ProgramBinding,
)

internal sealed class FirstPlanRenderTransformPackageBuildResult private constructor() {
    internal class Success internal constructor(
        internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    ) : FirstPlanRenderTransformPackageBuildResult()

    internal class Failure internal constructor(
        internal val kind: ScreenCaptureProblemKind,
        internal val message: String,
        internal val cause: Throwable?,
    ) : FirstPlanRenderTransformPackageBuildResult()
}

/**
 * Static first-plan render/readback contract retained with prepared runtime resources.
 *
 * Runtime rendering supplies the per-frame OES matrix and composes it with [sourceTransformMatrix]
 * according to [oesCompositionRule]. The stored [programBinding] is already matched to [colorMode].
 */
internal class FirstPlanRenderTransformPackage internal constructor(
    internal val projectionTargetGeneration: Long,
    internal val logicalContentSize: Size,
    internal val captureTargetSize: Size,
    internal val appliedSourceRect: ImageRect,
    internal val logicalToCaptureTargetMapping: FirstPlanLogicalToCaptureTargetMapping,
    internal val outputViewport: FirstPlanRenderViewport,
    internal val sourceTransformMatrix: FirstPlanRenderMatrix4,
    internal val colorMode: ColorMode,
    internal val programBinding: FirstPlanEs2ProgramBinding,
    internal val dynamicOesMatrixSlot: FirstPlanDynamicOesMatrixSlot,
    internal val oesCompositionRule: FirstPlanOesCompositionRule,
    internal val encoderInputNormalizationStrategy: FirstPlanEncoderInputNormalizationStrategy,
    internal val readbackShape: FirstPlanReadbackShape,
)

internal class FirstPlanLogicalToCaptureTargetMapping internal constructor(
    internal val scaleX: Float,
    internal val scaleY: Float,
    internal val sourceRectInCaptureTargetPixels: FirstPlanFloatRect,
    internal val sourceRectInCaptureTargetNormalized: FirstPlanFloatRect,
) {
    init {
        require(scaleX.isFinite() && scaleX > 0.0f) { "scaleX must be finite and positive." }
        require(scaleY.isFinite() && scaleY > 0.0f) { "scaleY must be finite and positive." }
    }
}

internal class FirstPlanFloatRect internal constructor(
    internal val left: Float,
    internal val top: Float,
    internal val right: Float,
    internal val bottom: Float,
) {
    internal val width: Float
        get() = right - left

    internal val height: Float
        get() = bottom - top

    internal fun isFiniteNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width > 0.0f && height > 0.0f
}

internal class FirstPlanRenderViewport internal constructor(
    internal val x: Int,
    internal val y: Int,
    internal val width: Int,
    internal val height: Int,
) {
    init {
        require(x >= 0) { "x must be non-negative, was $x" }
        require(y >= 0) { "y must be non-negative, was $y" }
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }
}

internal class FirstPlanRenderMatrix4 internal constructor(values: FloatArray) {
    private val storedValues: FloatArray = values.copyOf()

    internal val values: FloatArray
        get() = storedValues.copyOf()

    init {
        require(values.size == MATRIX_4_VALUE_COUNT) { "First-plan render matrix must contain 16 values." }
        require(values.all(Float::isFinite)) { "First-plan render matrix values must be finite." }
    }

    override fun equals(other: Any?): Boolean =
        other is FirstPlanRenderMatrix4 && storedValues.contentEquals(other.storedValues)

    override fun hashCode(): Int = storedValues.contentHashCode()
}

internal class FirstPlanEs2ProgramBinding internal constructor(
    internal val shaderVariant: FirstPlanEs2ShaderVariant,
    internal val programId: Int,
    internal val positionAttributeName: String = A_POSITION,
    internal val positionAttributeLocation: Int,
    internal val textureCoordinateAttributeName: String = A_TEX_COORD,
    internal val textureCoordinateAttributeLocation: Int,
    internal val textureSamplerUniformName: String = U_TEXTURE,
    internal val textureSamplerUniformLocation: Int,
    internal val textureMatrixUniformName: String = U_TEX_MATRIX,
    internal val textureMatrixUniformLocation: Int,
)

/**
 * Converts validated ES2 readiness metadata into the first-plan binding model explicitly.
 */
internal fun Es2RenderingProgramBindingMetadata.toFirstPlanEs2ProgramBinding(): FirstPlanEs2ProgramBinding =
    FirstPlanEs2ProgramBinding(
        shaderVariant = shaderVariant.toFirstPlanEs2ShaderVariant(),
        programId = programId,
        positionAttributeLocation = attributeLocations.position,
        textureCoordinateAttributeLocation = attributeLocations.textureCoordinate,
        textureSamplerUniformLocation = uniformLocations.externalOesTextureSampler,
        textureMatrixUniformLocation = uniformLocations.textureMatrix,
    )

private fun Es2RenderingShaderVariant.toFirstPlanEs2ShaderVariant(): FirstPlanEs2ShaderVariant =
    when (this) {
        Es2RenderingShaderVariant.OriginalExternalOes -> FirstPlanEs2ShaderVariant.OriginalColor
        Es2RenderingShaderVariant.GrayscaleExternalOes -> FirstPlanEs2ShaderVariant.Grayscale
    }

internal enum class FirstPlanEs2ShaderVariant(internal val supportedColorMode: ColorMode) {
    OriginalColor(ColorMode.Original),
    Grayscale(ColorMode.Grayscale),
}

internal class FirstPlanDynamicOesMatrixSlot internal constructor(
    internal val uniformName: String,
    internal val uniformLocation: Int,
    internal val valueShape: FirstPlanMatrixValueShape,
)

internal enum class FirstPlanMatrixValueShape {
    Mat4ColumnMajorFloatArray,
}

internal enum class FirstPlanOesCompositionRule {
    DynamicOesMatrixAfterStaticPlanTransform,
}

internal enum class FirstPlanEncoderInputNormalizationStrategy {
    RenderSpaceVerticalInversion,
}

internal class FirstPlanReadbackShape internal constructor(
    internal val width: Int,
    internal val height: Int,
    internal val rowStrideBytes: Int,
    internal val byteCount: Long,
    internal val inputFormat: ImageEncoderInputFormat,
    internal val readbackMode: ReadbackMode,
    internal val rowOrder: FirstPlanReadbackRowOrder,
)

internal enum class FirstPlanReadbackRowOrder {
    TopToBottom,
}

private class Matrix3 private constructor(private val values: DoubleArray) {
    fun multiply(right: Matrix3): Matrix3 {
        val out = DoubleArray(MATRIX_3_VALUE_COUNT)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                out[row * 3 + column] =
                    values[row * 3] * right.values[column] +
                            values[row * 3 + 1] * right.values[3 + column] +
                            values[row * 3 + 2] * right.values[6 + column]
            }
        }
        return Matrix3(out)
    }

    fun then(next: Matrix3): Matrix3 =
        next.multiply(this)

    fun toRenderMatrix4OrNull(): FirstPlanRenderMatrix4? {
        if (!values.all(Double::isFinite)) return null
        val matrix4 = floatArrayOf(
            values[0].toFloat(),
            values[3].toFloat(),
            0.0f,
            values[6].toFloat(),
            values[1].toFloat(),
            values[4].toFloat(),
            0.0f,
            values[7].toFloat(),
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            values[2].toFloat(),
            values[5].toFloat(),
            0.0f,
            values[8].toFloat(),
        )
        return if (matrix4.all(Float::isFinite)) FirstPlanRenderMatrix4(matrix4) else null
    }

    companion object {
        fun raw(values: DoubleArray): Matrix3 = Matrix3(values)

        fun identity(): Matrix3 = Matrix3(
            doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
        )

        fun translate(x: Double, y: Double): Matrix3 = Matrix3(
            doubleArrayOf(
                1.0, 0.0, x,
                0.0, 1.0, y,
                0.0, 0.0, 1.0,
            ),
        )

        fun scale(x: Double, y: Double): Matrix3 = Matrix3(
            doubleArrayOf(
                x, 0.0, 0.0,
                0.0, y, 0.0,
                0.0, 0.0, 1.0,
            ),
        )
    }
}

private fun framebufferToLogicalOutputMatrix(): Matrix3 =
    Matrix3.translate(0.0, 1.0).multiply(Matrix3.scale(1.0, -1.0))

private fun Mirror.toInverseOutputMatrix(): Matrix3 =
    when (this) {
        Mirror.None -> Matrix3.identity()
        Mirror.Horizontal -> Matrix3.translate(1.0, 0.0).multiply(Matrix3.scale(-1.0, 1.0))
        Mirror.Vertical -> Matrix3.translate(0.0, 1.0).multiply(Matrix3.scale(1.0, -1.0))
    }

private fun Rotation.toInverseSourceMatrix(): Matrix3 =
    when (this) {
        Rotation.Degrees0 -> Matrix3.identity()
        Rotation.Degrees90 -> Matrix3.raw(
            doubleArrayOf(
                0.0, 1.0, 0.0,
                -1.0, 0.0, 1.0,
                0.0, 0.0, 1.0,
            ),
        )

        Rotation.Degrees180 -> Matrix3.translate(1.0, 1.0).multiply(Matrix3.scale(-1.0, -1.0))
        Rotation.Degrees270 -> Matrix3.raw(
            doubleArrayOf(
                0.0, -1.0, 1.0,
                1.0, 0.0, 0.0,
                0.0, 0.0, 1.0,
            ),
        )
    }

private fun checkedRgba8888RowStrideBytes(width: Int): Int? =
    try {
        Math.multiplyExact(width, RGBA_8888_BYTES_PER_PIXEL)
    } catch (_: ArithmeticException) {
        null
    }

private fun checkedMultiplyLong(left: Int, right: Int): Long? =
    try {
        Math.multiplyExact(left, right).toLong()
    } catch (_: ArithmeticException) {
        null
    }

private const val RGBA_8888_BYTES_PER_PIXEL: Int = 4
private const val MATRIX_3_VALUE_COUNT: Int = 9
private const val MATRIX_4_VALUE_COUNT: Int = 16
private const val A_POSITION: String = "aPosition"
private const val A_TEX_COORD: String = "aTexCoord"
private const val U_TEXTURE: String = "uTexture"
private const val U_TEX_MATRIX: String = "uTexMatrix"
