package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.SourceRegion
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanResult
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class FirstPlanRenderTransformPackageTest {
    @Test
    fun cropOffsetIsTranslatedAfterCropScaleInFullSourceNormalizedSpace() {
        val packageUnderTest = buildPackage(
            parameters = ScreenCaptureParameters(
                crop = CropInsetsPx(left = 10, top = 20, right = 40, bottom = 10),
                outputSize = OutputSize.TargetSize(width = 50, height = 50, contentMode = ContentMode.Stretch),
            ),
        )

        assertPointEquals(
            expected = Point2(x = 0.10f, y = 0.90f),
            actual = packageUnderTest.sourceTransformMatrix.map(x = 0.0f, y = 0.0f),
        )
        assertPointEquals(
            expected = Point2(x = 0.60f, y = 0.20f),
            actual = packageUnderTest.sourceTransformMatrix.map(x = 1.0f, y = 1.0f),
        )
    }

    @Test
    fun renderSpaceVerticalInversionIsAppliedBeforeRotationAndMirror() {
        val packageUnderTest = buildPackage(
            geometry = CaptureGeometry(widthPx = 100, heightPx = 80, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(
                crop = CropInsetsPx(left = 10, top = 5, right = 30, bottom = 15),
                rotation = Rotation.Degrees90,
                mirror = Mirror.Horizontal,
                outputSize = OutputSize.ScaleFactor(1.0),
            ),
        )

        assertPointEquals(
            expected = Point2(x = 0.70f, y = 0.25f),
            actual = packageUnderTest.sourceTransformMatrix.map(x = 0.25f, y = 0.0f),
        )
        assertPointEquals(
            expected = Point2(x = 0.10f, y = 0.25f),
            actual = packageUnderTest.sourceTransformMatrix.map(x = 0.25f, y = 1.0f),
        )
    }

    @Test
    fun matrixValuesAreDefensivelyCopiedOnConstructionAndRead() {
        val sourceValues = identityMatrix4Values()
        val matrix = FirstPlanRenderMatrix4(sourceValues)

        sourceValues[0] = 42.0f
        val firstRead = matrix.values
        firstRead[5] = 24.0f

        assertEquals(1.0f, matrix.values[0], 0.0f)
        assertEquals(1.0f, matrix.values[5], 0.0f)

        val packageUnderTest = buildPackage()
        val packageMatrixValues = packageUnderTest.sourceTransformMatrix.values
        packageMatrixValues[0] = Float.NaN

        assertTrue(packageUnderTest.sourceTransformMatrix.values[0].isFinite())
    }

    @Test
    fun logicalToCaptureTargetMappingIsRetainedForDownscaledCroppedPlan() {
        val geometry = CaptureGeometry(widthPx = 101, heightPx = 77, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider)
        val parameters = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
            rotation = Rotation.Degrees90,
            outputSize = OutputSize.TargetSize(width = 36, height = 20, contentMode = ContentMode.AspectFit),
        )
        val plan = plan(geometry = geometry, parameters = parameters)
        val packageUnderTest = (buildResult(plan = plan) as FirstPlanRenderTransformPackageBuildResult.Success).renderTransformPackage

        assertTrue(plan.captureTarget.isEarlyDownscaled)
        assertTrue(plan.appliedSourceRect.left > 0)
        assertTrue(plan.appliedSourceRect.top > 0)
        assertTrue(plan.appliedSourceRect.right < geometry.widthPx)
        assertTrue(plan.appliedSourceRect.bottom < geometry.heightPx)
        assertEquals(plan.captureGeometry.widthPx, packageUnderTest.logicalContentSize.width)
        assertEquals(plan.captureGeometry.heightPx, packageUnderTest.logicalContentSize.height)
        assertEquals(plan.captureTarget.width, packageUnderTest.captureTargetSize.width)
        assertEquals(plan.captureTarget.height, packageUnderTest.captureTargetSize.height)
        assertEquals(plan.appliedSourceRect, packageUnderTest.appliedSourceRect)

        val mapping = packageUnderTest.logicalToCaptureTargetMapping
        val expectedScaleX = plan.captureTarget.width.toFloat() / plan.captureGeometry.widthPx.toFloat()
        val expectedScaleY = plan.captureTarget.height.toFloat() / plan.captureGeometry.heightPx.toFloat()
        assertEquals(expectedScaleX, mapping.scaleX, FLOAT_TOLERANCE)
        assertEquals(expectedScaleY, mapping.scaleY, FLOAT_TOLERANCE)
        assertFloatRectEquals(
            expectedLeft = plan.appliedSourceRect.left.toFloat() * expectedScaleX,
            expectedTop = plan.appliedSourceRect.top.toFloat() * expectedScaleY,
            expectedRight = plan.appliedSourceRect.right.toFloat() * expectedScaleX,
            expectedBottom = plan.appliedSourceRect.bottom.toFloat() * expectedScaleY,
            actual = mapping.sourceRectInCaptureTargetPixels,
        )
        assertFloatRectEquals(
            expectedLeft = plan.appliedSourceRect.left.toFloat() / plan.captureGeometry.widthPx.toFloat(),
            expectedTop = plan.appliedSourceRect.top.toFloat() / plan.captureGeometry.heightPx.toFloat(),
            expectedRight = plan.appliedSourceRect.right.toFloat() / plan.captureGeometry.widthPx.toFloat(),
            expectedBottom = plan.appliedSourceRect.bottom.toFloat() / plan.captureGeometry.heightPx.toFloat(),
            actual = mapping.sourceRectInCaptureTargetNormalized,
        )
    }

    @Test
    fun grayscaleColorModeAcceptsGrayscaleShaderVariant() {
        val packageUnderTest = buildPackage(
            parameters = ScreenCaptureParameters(colorMode = ColorMode.Grayscale),
            programBinding = grayscaleProgramBinding(),
        )

        assertEquals(ColorMode.Grayscale, packageUnderTest.colorMode)
        assertEquals(FirstPlanEs2ShaderVariant.Grayscale, packageUnderTest.programBinding.shaderVariant)
        assertEquals(ColorMode.Grayscale, packageUnderTest.programBinding.shaderVariant.supportedColorMode)
    }

    @Test
    fun grayscaleColorModeRejectsOriginalShaderVariant() {
        val result = buildResult(
            parameters = ScreenCaptureParameters(colorMode = ColorMode.Grayscale),
            programBinding = originalProgramBinding(),
        )

        val failure = result as FirstPlanRenderTransformPackageBuildResult.Failure
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("color mode"))
    }

    @Test
    fun originalColorModeRejectsGrayscaleShaderVariant() {
        val result = buildResult(programBinding = grayscaleProgramBinding())

        val failure = result as FirstPlanRenderTransformPackageBuildResult.Failure
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("color mode"))
    }

    @Test
    fun es2ProgramBindingMetadataMapsToFirstPlanBindingExplicitly() {
        val originalBinding = es2ProgramBinding(Es2RenderingShaderVariant.OriginalExternalOes).toFirstPlanEs2ProgramBinding()
        val grayscaleBinding = es2ProgramBinding(Es2RenderingShaderVariant.GrayscaleExternalOes).toFirstPlanEs2ProgramBinding()

        assertEquals(FirstPlanEs2ShaderVariant.OriginalColor, originalBinding.shaderVariant)
        assertEquals(ColorMode.Original, originalBinding.shaderVariant.supportedColorMode)
        assertEquals(FirstPlanEs2ShaderVariant.Grayscale, grayscaleBinding.shaderVariant)
        assertEquals(ColorMode.Grayscale, grayscaleBinding.shaderVariant.supportedColorMode)
        assertEquals(10, grayscaleBinding.programId)
        assertEquals(1, grayscaleBinding.positionAttributeLocation)
        assertEquals(2, grayscaleBinding.textureCoordinateAttributeLocation)
        assertEquals(3, grayscaleBinding.textureSamplerUniformLocation)
        assertEquals(4, grayscaleBinding.textureMatrixUniformLocation)
    }

    @Test
    fun readbackShapeIsTightTopToBottomRgbaEs2() {
        val packageUnderTest = buildPackage(
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(width = 37, height = 19, contentMode = ContentMode.Stretch),
            ),
        )

        assertEquals(37, packageUnderTest.readbackShape.width)
        assertEquals(19, packageUnderTest.readbackShape.height)
        assertEquals(148, packageUnderTest.readbackShape.rowStrideBytes)
        assertEquals(2_812L, packageUnderTest.readbackShape.byteCount)
        assertEquals(ImageEncoderInputFormat.Rgba8888SrgbOpaque, packageUnderTest.readbackShape.inputFormat)
        assertEquals(ReadbackMode.Es2, packageUnderTest.readbackShape.readbackMode)
        assertEquals(FirstPlanReadbackRowOrder.TopToBottom, packageUnderTest.readbackShape.rowOrder)
    }

    @Test
    fun paddedReadbackStrideIsRejected() {
        val plan = plan()
        val paddedRowStrideBytes = plan.rowStrideBytes + 4

        val result = buildResult(
            plan = plan,
            readbackSpec = Es2ReadbackSpec(
                width = plan.finalImageSize.width,
                height = plan.finalImageSize.height,
                rowStrideBytes = paddedRowStrideBytes,
                byteCount = paddedRowStrideBytes.toLong() * plan.finalImageSize.height.toLong(),
                inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
                readbackMode = ReadbackMode.Es2,
            ),
        )

        val failure = result as FirstPlanRenderTransformPackageBuildResult.Failure
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
    }

    @Test
    fun paddedOutputPlanStrideIsRejected() {
        val plan = plan()
        val paddedRowStrideBytes = plan.rowStrideBytes + 4
        val paddedPlan = plan.withEncoderRequest(
            encoderRequest = ImageEncoderRequest(
                width = plan.encoderRequest.width,
                height = plan.encoderRequest.height,
                rowStrideBytes = paddedRowStrideBytes,
                maxEncodedBytes = plan.encoderRequest.maxEncodedBytes,
                inputFormat = plan.encoderRequest.inputFormat,
            ),
            rowStrideBytes = paddedRowStrideBytes,
            rgbaByteCount = paddedRowStrideBytes.toLong() * plan.finalImageSize.height.toLong(),
        )

        val result = buildResult(plan = paddedPlan)

        val failure = result as FirstPlanRenderTransformPackageBuildResult.Failure
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
    }

    @Test
    fun oesMatrixIsDeferredMetadataOnly() {
        val packageUnderTest = buildPackage()

        assertEquals("uTexMatrix", packageUnderTest.dynamicOesMatrixSlot.uniformName)
        assertEquals(4, packageUnderTest.dynamicOesMatrixSlot.uniformLocation)
        assertEquals(FirstPlanMatrixValueShape.Mat4ColumnMajorFloatArray, packageUnderTest.dynamicOesMatrixSlot.valueShape)
        assertEquals(
            FirstPlanOesCompositionRule.DynamicOesMatrixAfterStaticPlanTransform,
            packageUnderTest.oesCompositionRule,
        )
        val concreteOesMatrixStorageFieldPaths = concreteOesMatrixStorageFieldPaths()
        assertTrue(
            "OES metadata must not retain concrete matrix storage: $concreteOesMatrixStorageFieldPaths",
            concreteOesMatrixStorageFieldPaths.isEmpty(),
        )
    }

    private fun buildPackage(
        geometry: CaptureGeometry = defaultGeometry(),
        parameters: ScreenCaptureParameters = ScreenCaptureParameters(),
        programBinding: FirstPlanEs2ProgramBinding = originalProgramBinding(),
    ): FirstPlanRenderTransformPackage =
        (buildResult(
            geometry = geometry,
            parameters = parameters,
            programBinding = programBinding,
        ) as FirstPlanRenderTransformPackageBuildResult.Success).renderTransformPackage

    private fun buildResult(
        geometry: CaptureGeometry = defaultGeometry(),
        parameters: ScreenCaptureParameters = ScreenCaptureParameters(),
        plan: ScreenCaptureOutputPlan = plan(geometry = geometry, parameters = parameters),
        readbackSpec: Es2ReadbackSpec = readbackSpec(plan),
        programBinding: FirstPlanEs2ProgramBinding = originalProgramBinding(),
    ): FirstPlanRenderTransformPackageBuildResult =
        FirstPlanRenderTransformPackageBuilder.build(
            FirstPlanRenderTransformPackageBuildRequest(
                outputPlan = plan,
                projectionTarget = ProjectionTargetSnapshot(
                    generation = 1L,
                    width = plan.captureTarget.width,
                    height = plan.captureTarget.height,
                    densityDpi = plan.captureGeometry.densityDpi,
                    surface = TestProjectionSurfaceHandle,
                ),
                readbackSpec = readbackSpec,
                programBinding = programBinding,
            ),
        )

    private fun plan(
        geometry: CaptureGeometry = defaultGeometry(),
        parameters: ScreenCaptureParameters = ScreenCaptureParameters(),
    ): ScreenCaptureOutputPlan =
        (planner().plan(geometry = geometry, parameters = parameters) as OutputPlanResult.Success).plan

    private fun planner(): ScreenCaptureOutputPlanner =
        ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = 268_435_456,
                maxEncodedBytes = 1_024,
            ),
        )

    private fun readbackSpec(plan: ScreenCaptureOutputPlan): Es2ReadbackSpec =
        Es2ReadbackSpec(
            width = plan.finalImageSize.width,
            height = plan.finalImageSize.height,
            rowStrideBytes = plan.rowStrideBytes,
            byteCount = plan.rgbaByteCount,
            inputFormat = plan.encoderRequest.inputFormat,
            readbackMode = plan.readbackMode,
        )

    private fun originalProgramBinding(): FirstPlanEs2ProgramBinding =
        FirstPlanEs2ProgramBinding(
            shaderVariant = FirstPlanEs2ShaderVariant.OriginalColor,
            programId = 10,
            positionAttributeLocation = 1,
            textureCoordinateAttributeLocation = 2,
            textureSamplerUniformLocation = 3,
            textureMatrixUniformLocation = 4,
        )

    private fun grayscaleProgramBinding(): FirstPlanEs2ProgramBinding =
        FirstPlanEs2ProgramBinding(
            shaderVariant = FirstPlanEs2ShaderVariant.Grayscale,
            programId = 10,
            positionAttributeLocation = 1,
            textureCoordinateAttributeLocation = 2,
            textureSamplerUniformLocation = 3,
            textureMatrixUniformLocation = 4,
        )

    private fun es2ProgramBinding(shaderVariant: Es2RenderingShaderVariant): Es2RenderingProgramBindingMetadata =
        Es2RenderingProgramBindingMetadata(
            programId = 10,
            shaderVariant = shaderVariant,
            attributeLocations = Es2RenderingProgramAttributeLocations(
                position = 1,
                textureCoordinate = 2,
            ),
            uniformLocations = Es2RenderingProgramUniformLocations(
                externalOesTextureSampler = 3,
                textureMatrix = 4,
            ),
            dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                uniformName = "uTexMatrix",
                location = 4,
                matrixElementCount = 16,
                compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
            ),
        )

    private fun ScreenCaptureOutputPlan.withEncoderRequest(
        encoderRequest: ImageEncoderRequest,
        rowStrideBytes: Int,
        rgbaByteCount: Long,
    ): ScreenCaptureOutputPlan =
        ScreenCaptureOutputPlan(
            captureGeometry = captureGeometry,
            captureTarget = captureTarget,
            sourceRegion = sourceRegion,
            crop = crop,
            appliedSourceRect = appliedSourceRect,
            orientedContentSize = orientedContentSize,
            outputSize = outputSize,
            finalImageSize = finalImageSize,
            rotation = rotation,
            mirror = mirror,
            colorMode = colorMode,
            readbackMode = readbackMode,
            frameRate = frameRate,
            encoderRequest = encoderRequest,
            rowStrideBytes = rowStrideBytes,
            rgbaByteCount = rgbaByteCount,
        )

    private fun FirstPlanRenderMatrix4.map(x: Float, y: Float): Point2 {
        val matrix = values
        return Point2(
            x = matrix[0] * x + matrix[4] * y + matrix[12],
            y = matrix[1] * x + matrix[5] * y + matrix[13],
        )
    }

    private fun assertPointEquals(expected: Point2, actual: Point2) {
        assertEquals(expected.x, actual.x, FLOAT_TOLERANCE)
        assertEquals(expected.y, actual.y, FLOAT_TOLERANCE)
    }

    private fun assertFloatRectEquals(
        expectedLeft: Float,
        expectedTop: Float,
        expectedRight: Float,
        expectedBottom: Float,
        actual: FirstPlanFloatRect,
    ) {
        assertEquals(expectedLeft, actual.left, FLOAT_TOLERANCE)
        assertEquals(expectedTop, actual.top, FLOAT_TOLERANCE)
        assertEquals(expectedRight, actual.right, FLOAT_TOLERANCE)
        assertEquals(expectedBottom, actual.bottom, FLOAT_TOLERANCE)
    }

    private fun concreteOesMatrixStorageFieldPaths(): List<String> {
        val visitedTypes = mutableSetOf<Class<*>>()
        return FirstPlanRenderTransformPackage::class.java.declaredFields
            .filter { field -> field.isOesMetadataRoot() }
            .flatMap { field -> field.concreteOesMatrixStorageFieldPaths(path = field.name, visitedTypes = visitedTypes) }
    }

    private fun Field.concreteOesMatrixStorageFieldPaths(path: String, visitedTypes: MutableSet<Class<*>>): List<String> {
        if (type == FloatArray::class.java || type == FirstPlanRenderMatrix4::class.java) return listOf(path)
        if (!type.isInspectableFirstPlanMetadataType() || !visitedTypes.add(type)) return emptyList()
        return type.declaredFields.flatMap { field ->
            field.concreteOesMatrixStorageFieldPaths(path = "$path.${field.name}", visitedTypes = visitedTypes)
        }
    }

    private fun Field.isOesMetadataRoot(): Boolean =
        name.contains("oes", ignoreCase = true) || type.simpleName.contains("Oes", ignoreCase = true)

    private fun Class<*>.isInspectableFirstPlanMetadataType(): Boolean =
        !isPrimitive && !isEnum && !isArray && name.startsWith(firstPlanRuntimeClassPrefix())

    private fun firstPlanRuntimeClassPrefix(): String =
        FirstPlanRenderTransformPackage::class.java.name.removeSuffix("RenderTransformPackage")

    private fun identityMatrix4Values(): FloatArray =
        FloatArray(16) { index -> if (index % 5 == 0) 1.0f else 0.0f }

    private fun defaultGeometry(): CaptureGeometry =
        CaptureGeometry(widthPx = 100, heightPx = 100, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider)

    private data class Point2(val x: Float, val y: Float)

    private object TestProjectionSurfaceHandle : ProjectionSurfaceHandle

    private companion object {
        const val FLOAT_TOLERANCE: Float = 0.000001f
    }
}
