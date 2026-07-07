package dev.dmkr.screencaptureengine.internal.runtime

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

class Es2RenderingReadbackResourcePreparerTest {
    @Test
    fun es2ReadinessProductionSourcesDoNotCrossForbiddenRuntimeBoundary() {
        val readinessFiles = resolveEs2ReadinessProductionFiles()
        val forbiddenLiteralPatterns = listOf(
            "updateTexImage(",
            "glReadPixels",
            "GLES20.glReadPixels",
            "ImageEncoder.encode",
            "publishEncodedFrame(",
            "publishFrame",
            "ScreenCaptureSessionCore",
            "RuntimeFrameLoopInstalled",
            "InitialActivePlanCommitted",
            "startSession(",
            "Running(",
            "ScreenCaptureSession",
            "SessionStarted",
            "PreActiveRuntimeOwner",
            "JpegImageEncoderProvider",
            "Bitmap",
            "compress(",
            "GLES30",
            "GLES31",
            "GLES32",
        )
        val forbiddenRegexPatterns = listOf(
            Regex("\\.encode\\s*\\("),
            Regex("\\.publish\\s*\\("),
            Regex("(?i)(pbo|pixelbufferobject)"),
        )
        val bareDirectGlCall = Regex(
            "(?<![.\\w])gl(?:ActiveTexture|AttachShader|Bind|Check|Clear|Compile|Create|Delete|Detach|Draw|" +
                    "Framebuffer|Gen|Get|Link|PixelStore|ReadPixels|Shader|Tex|Use|Validate|Viewport)\\w*\\s*\\(",
        )

        readinessFiles.forEach { sourceFile ->
            val content = Files.readString(sourceFile.path)
            val executableContent = content.withoutKotlinCommentsAndStrings()
            forbiddenLiteralPatterns.forEach { pattern ->
                assertFalse(
                    "${sourceFile.displayPath} contains forbidden readiness boundary pattern $pattern",
                    executableContent.contains(pattern),
                )
            }
            forbiddenRegexPatterns.forEach { pattern ->
                assertFalse(
                    "${sourceFile.displayPath} contains forbidden readiness boundary pattern ${pattern.pattern}",
                    pattern.containsMatchIn(executableContent),
                )
            }
            if (!sourceFile.displayPath.endsWith("Gles20Api.kt")) {
                assertFalse(
                    "${sourceFile.displayPath} calls GLES20 directly instead of Gles20Api",
                    directGles20CallRegex(executableContent).containsMatchIn(executableContent),
                )
                assertFalse(
                    "${sourceFile.displayPath} calls bare GLES directly instead of Gles20Api",
                    bareDirectGlCall.containsMatchIn(executableContent),
                )
            }
        }
    }

    @Test
    fun kotlinSanitizerIgnoresForbiddenBoundaryTokensInCommentsAndNonExecutableLiterals() {
        val source = listOf(
            "package synthetic",
            "/** KDoc must ignore GLES20.glReadPixels(...) and ImageEncoder.encode(...) */",
            "internal fun ignoredForbiddenTokens() {",
            "    // GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3) and updateTexImage(",
            "    /* Block comment must ignore publishEncodedFrame( and GLES30. */",
            "    val escaped = \"GLES20.glReadPixels(\\\"still literal\\\") and ScreenCaptureSessionCore\"",
            "    val raw = \"\"\"GLES20.glDrawArrays(...) and glReadPixels are literal text\"\"\"",
            "    val quote = '\"'",
            "    val slash = '/'",
            "    val dollar = '$'",
            "    val executable = 1",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(executableContent.contains("val executable = 1"))
        listOf(
            "GLES20.glReadPixels",
            "GLES20.glDrawArrays",
            "ImageEncoder.encode",
            "publishEncodedFrame(",
            "updateTexImage(",
            "ScreenCaptureSessionCore",
            "GLES30",
            "glReadPixels",
            "\"still literal\"",
            "'\"'",
            "'/'",
            "'$'",
        ).forEach { forbidden ->
            assertFalse(
                "Sanitized executable content retained non-executable token $forbidden",
                executableContent.contains(forbidden),
            )
        }
    }

    @Test
    fun kotlinSanitizerPreservesExecutableStringTemplateExpressions() {
        val source = listOf(
            "package synthetic",
            "internal fun catchesForbiddenTemplates(buffer: java.nio.Buffer) {",
            "    val regular = \"literal \${GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val raw = \"\"\"literal \${GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)} text\"\"\"",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(
            "Regular string template expression was stripped from executable content",
            executableContent.contains("GLES20.glDrawArrays"),
        )
        assertTrue(
            "Raw string template expression was stripped from executable content",
            executableContent.contains("GLES20.glReadPixels"),
        )
        assertFalse(executableContent.contains("literal"))
    }

    @Test
    fun kotlinSanitizerHonorsMultiDollarStringTemplateThresholds() {
        val source = listOf(
            "package synthetic",
            "internal fun catchesOnlyExecutableMultiDollarTemplates() {",
            "    val normalRegular = \"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val normalRaw = \"\"\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"\"\"",
            "    val multiRegularLiteral = \$\$\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val multiRawLiteral = \$\$\"\"\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"\"\"",
            "    val multiRegularExecutable = \$\$\"literal \$\${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val multiRawExecutable = \$\$\"\"\"literal \$\${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"\"\"",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertEquals(
            "Sanitizer should preserve only executable string-template GLES20 calls",
            4,
            directGles20CallRegex(executableContent).findAll(executableContent).count(),
        )
        assertFalse(executableContent.contains("literal"))
    }

    @Test
    fun directGles20GuardCatchesAliasImportedCalls() {
        val source = listOf(
            "package synthetic",
            "import android.opengl.GLES20 as GL",
            "internal fun directAliasCall() {",
            "    GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(
            "Direct GLES20 alias call was not caught",
            directGles20CallRegex(executableContent).containsMatchIn(executableContent),
        )
    }

    @Test
    fun directGles20GuardCatchesFullyQualifiedCallsAfterSanitizer() {
        val source = listOf(
            "package synthetic",
            "internal fun directFullyQualifiedCall() {",
            "    // android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)",
            "    val literal = \"android.opengl.GLES20.glDrawArrays(...)\"",
            "    android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertEquals(
            "Fully-qualified direct GLES20 call should be caught only from executable content",
            1,
            directGles20CallRegex(executableContent).findAll(executableContent).count(),
        )
    }

    @Test
    fun gles20ApiDoesNotExposeRuntimeRenderingOrReadbackMethods() {
        val forbiddenMethodFragments = listOf("readPixels", "draw", "pbo", "fence", "map")

        forbiddenMethodFragments.forEach { fragment ->
            assertFalse(
                "Gles20Api exposes forbidden readiness method fragment $fragment",
                Gles20Api::class.java.methods.any { method -> method.name.contains(fragment, ignoreCase = true) },
            )
        }
    }

    @Test
    fun prepareBuildsFinalImageResourcesAndReadbackBuffer() {
        val gles = RecordingGles20Api()
        val gl = FakeGlLaneScope()
        val projectionTarget = FakeProjectionTargetGlScope()
        val retirementLane = ManualRetirementLane()
        val result = preparer(gles = gles).prepare(
            request = request(gl = gl, projectionTarget = projectionTarget, retirementLane = retirementLane),
        )

        val resources = result.successResources()
        assertEquals(30, resources.width)
        assertEquals(20, resources.height)
        assertEquals(120, resources.rowStrideBytes)
        assertEquals(2_400, resources.readbackBufferCapacityBytes)
        assertTrue(resources.readbackBuffer.isDirect)
        assertOriginalExternalOesProgramBinding(resources.programBinding)
        assertEquals(1, projectionTarget.oesValidationCount)
        assertEquals(listOf(Pair(GLES20.GL_PACK_ALIGNMENT, 1), Pair(GLES20.GL_PACK_ALIGNMENT, 4)), gles.pixelStoreiCalls)
        assertEquals(listOf(TexImage2DCall(width = 30, height = 20)), gles.texImage2DCalls)
        assertEquals(listOf(FramebufferTexture2DCall(texture = 30)), gles.framebufferTexture2DCalls)
        assertTrue(
            gles.shaderSources.values.any { source ->
                source.contains("GL_OES_EGL_image_external") && source.contains("samplerExternalOES")
            },
        )
        assertTrue(
            gles.shaderSources.values.any { source ->
                source.contains("gl_FragColor = vec4(color.rgb, 1.0);")
            },
        )
        assertFalse(
            gles.shaderSources.values.any { source ->
                source.contains("dot(color.rgb")
            },
        )
        assertEquals(
            listOf("aPosition", "aTexCoord"),
            gles.attribLocationNames,
        )
        assertEquals(
            listOf("uTexture", "uTexMatrix"),
            gles.uniformLocationNames,
        )
        assertEquals(listOf(20), gles.validateProgramCalls)
        assertFalse(Gles20Api::class.java.methods.any { method -> method.name.contains("readPixels", ignoreCase = true) })
        assertEquals(emptyList<String>(), gles.deleteCalls)

        resources.close()
        resources.close()
        assertEquals(1, retirementLane.queuedCount)
        retirementLane.runNext()

        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun prepareBuildsGrayscaleExternalOesProgramWhenSelected() {
        val gles = RecordingGles20Api()
        val result = preparer(gles = gles).prepare(
            request = request(selectedColorMode = ColorMode.Grayscale),
        )

        val resources = result.successResources()
        assertGrayscaleExternalOesProgramBinding(resources.programBinding)
        assertEquals(listOf(20), gles.validateProgramCalls)
        assertTrue(
            gles.shaderSources.values.any { source ->
                source.contains("GL_OES_EGL_image_external") &&
                        source.contains("samplerExternalOES") &&
                        source.contains("dot(color.rgb, vec3(0.299, 0.587, 0.114))") &&
                        source.contains("gl_FragColor = vec4(gray, gray, gray, 1.0);")
            },
        )
    }

    @Test
    fun prepareRestoresPreExistingGlStateAfterReadinessValidation() {
        val gles = RecordingGles20Api().apply {
            activeTexture = GLES20.GL_TEXTURE0 + 3
            boundTexture2d = 701
            boundExternalOesTexture = 702
            boundFramebuffer = 703
            packAlignment = 4
        }
        val result = preparer(gles = gles).prepare(
            request = request(projectionTarget = FakeProjectionTargetGlScope(gles = gles)),
        )

        result.successResources()
        assertEquals(GLES20.GL_TEXTURE0 + 3, gles.activeTexture)
        assertEquals(701, gles.boundTexture2d)
        assertEquals(702, gles.boundExternalOesTexture)
        assertEquals(703, gles.boundFramebuffer)
        assertEquals(4, gles.packAlignment)
    }

    @Test
    fun saveStateFailureAfterActiveTextureSwitchReturnsGlInvariantViolationAndRestoresOriginalActiveTexture() {
        val gles = RecordingGles20Api().apply {
            activeTexture = GLES20.GL_TEXTURE0 + 5
            getIntegervFailures += GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES
        }
        val result = preparer(gles = gles).prepare(request())

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.GlInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("current GL state"))
        assertEquals(GLES20.GL_TEXTURE0 + 5, gles.activeTexture)
        assertEquals(0, gles.createShaderCount)
        assertEquals(emptyList<String>(), gles.deleteCalls)
    }

    @Test
    fun currentContextFailureReturnsGlInvariantViolationBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val gl = FakeGlLaneScope().apply {
            failingCurrentContextOperations += "prepare ES2 rendering readback resources"
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request(gl = gl))

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("current GL state"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun restoreFailureAttemptsRemainingTouchedStateAndRollsBackBeforePreparedTransfer() {
        val textureRestoreFailure = IllegalStateException("restore 2d failed")
        val oesRestoreFailure = IllegalStateException("restore oes failed")
        val gles = RecordingGles20Api().apply {
            activeTexture = GLES20.GL_TEXTURE0 + 4
            boundTexture2d = 701
            boundExternalOesTexture = 702
            boundFramebuffer = 703
            packAlignment = 8
            bindTextureFailures["${GLES20.GL_TEXTURE_2D}:701"] = textureRestoreFailure
            bindTextureFailures["${GLES11Ext.GL_TEXTURE_EXTERNAL_OES}:702"] = oesRestoreFailure
        }
        val retirementLane = ManualRetirementLane()
        val result = preparer(gles = gles).prepare(request(retirementLane = retirementLane))

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertSame(textureRestoreFailure, failure.cause)
        assertSame(oesRestoreFailure, textureRestoreFailure.suppressed.single())
        assertEquals(0, retirementLane.queuedCount)
        assertEquals(GLES20.GL_TEXTURE0 + 4, gles.activeTexture)
        assertEquals(703, gles.boundFramebuffer)
        assertEquals(8, gles.packAlignment)
        assertTrue(gles.bindTextureCalls.contains("${GLES11Ext.GL_TEXTURE_EXTERNAL_OES}:702"))
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun projectionTargetOesValidationFailureReturnsGlInvariantViolationBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val projectionTarget = FakeProjectionTargetGlScope(
            oesValidationFailure = IllegalStateException("OES validation failed"),
        )
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(projectionTarget = projectionTarget),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("valid projection target"))
        assertEquals(1, projectionTarget.oesValidationCount)
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun projectionTargetShapeMismatchReturnsInternalInvariantViolationBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val projectionTarget = FakeProjectionTargetGlScope(width = 42, height = 33, densityDpi = 320)
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(projectionTarget = projectionTarget),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("dimensions"))
        assertEquals(1, projectionTarget.oesValidationCount)
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun projectionTargetDensityMismatchReturnsInternalInvariantViolationBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val projectionTarget = FakeProjectionTargetGlScope(width = 43, height = 33, densityDpi = 319)
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(projectionTarget = projectionTarget),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("density"))
        assertEquals(1, projectionTarget.oesValidationCount)
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun projectionTargetGenerationMismatchReturnsGlInvariantViolationBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val projectionTarget = FakeProjectionTargetGlScope()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(
                projectionTarget = projectionTarget,
                plan = plan,
                projectionTargetSpec = projectionTargetSpec(plan = plan, generation = 2L),
            ),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlInvariantViolation, failure.kind)
        assertTrue(failure.message.contains("generation"))
        assertEquals(1, projectionTarget.oesValidationCount)
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun nonEs2ReadbackModeReturnsReadbackUnavailableBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(
                plan = plan,
                readbackSpec = readbackSpec(plan = plan, readbackMode = ReadbackMode.Es3Pbo),
            ),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertTrue(failure.message.contains("ES2 output plan"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun byteCountMismatchReturnsReadbackUnavailableBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(plan = plan, readbackSpec = readbackSpec(plan = plan, byteCount = plan.rgbaByteCount + 4L)),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertTrue(failure.message.contains("byte count"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun byteCountOverflowReturnsReadbackUnavailableBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(
                plan = plan,
                readbackSpec = readbackSpec(
                    plan = plan,
                    width = 536_870_911,
                    height = 2,
                    rowStrideBytes = 2_147_483_644,
                    byteCount = 4_294_967_288L,
                ),
            ),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertTrue(failure.message.contains("byte count overflowed"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun nonTightReadbackStrideReturnsReadbackUnavailableBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(
                plan = plan,
                readbackSpec = readbackSpec(
                    plan = plan,
                    rowStrideBytes = 124,
                    byteCount = 124L * plan.finalImageSize.height,
                ),
            ),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertTrue(failure.message.contains("tight RGBA8888 row stride"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun tooSmallReadbackStrideReturnsReadbackUnavailableBeforeGlObjectAllocation() {
        val gles = RecordingGles20Api()
        val plan = nonTrivialOutputPlan()
        val prepared = prepareCountingAllocations(
            gles = gles,
            request = request(
                plan = plan,
                readbackSpec = readbackSpec(
                    plan = plan,
                    rowStrideBytes = 116,
                    byteCount = 116L * plan.finalImageSize.height,
                ),
            ),
        )

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertTrue(failure.message.contains("tight RGBA8888 row stride"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun shaderCompileFailureReturnsGlResourceFailureAndDeletesPartialObjects() {
        val gles = RecordingGles20Api().apply {
            failCompileShaderType = GLES20.GL_FRAGMENT_SHADER
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("fragment shader compilation failed"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun unavailableShaderCompilerReturnsGlResourceFailureBeforeShaderCreation() {
        val gles = RecordingGles20Api().apply {
            shaderCompilerAvailable = false
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("shader compiler is unavailable"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun textureSizeLimitFailureReturnsGlResourceFailureBeforeShaderCreation() {
        val gles = RecordingGles20Api().apply {
            maxTextureSize = 16
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("GL_MAX_TEXTURE_SIZE"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun viewportLimitFailureReturnsGlResourceFailureBeforeShaderCreation() {
        val gles = RecordingGles20Api().apply {
            maxViewportWidth = 29
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("GL_MAX_VIEWPORT_DIMS"))
        assertNoGlObjectOrReadbackAllocation(gles = gles, allocationCount = prepared.allocationCount)
    }

    @Test
    fun shaderCreationZeroReturnsGlResourceFailureAndDoesNotDeleteNeverCreatedShader() {
        val gles = RecordingGles20Api().apply {
            zeroShaderType = GLES20.GL_VERTEX_SHADER
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("glCreateShader returned 0"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(emptyList<String>(), gles.deleteCalls)
    }

    @Test
    fun programCreationZeroReturnsGlResourceFailureAndDeletesOwnedShadersOnly() {
        val gles = RecordingGles20Api().apply {
            createProgramResult = 0
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("glCreateProgram returned 0"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun textureCreationZeroReturnsGlResourceFailureAndDoesNotDeleteNeverCreatedTexture() {
        val gles = RecordingGles20Api().apply {
            generateZeroTexture = true
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("glGenTextures returned 0"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun programLinkFailureReturnsGlResourceFailureAndDeletesShadersAndProgram() {
        val gles = RecordingGles20Api().apply {
            linkStatus = GLES20.GL_FALSE
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("program link failed"))
        assertTrue(failure.message.contains("program log for 20"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun programValidationFailureReturnsGlResourceFailureAndDeletesShadersAndProgram() {
        val gles = RecordingGles20Api().apply {
            validateStatus = GLES20.GL_FALSE
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("program validation failed"))
        assertTrue(failure.message.contains("program log for 20"))
        assertEquals(listOf(20), gles.validateProgramCalls)
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun missingProgramLocationReturnsGlResourceFailureAndDeletesShadersAndProgram() {
        val gles = RecordingGles20Api().apply {
            missingAttribLocations += "aTexCoord"
            missingUniformLocations += "uTexMatrix"
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("missing required locations"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun incompleteFramebufferReturnsGlResourceFailureAndRollsBackGlObjects() {
        val gles = RecordingGles20Api().apply {
            framebufferStatus = GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("framebuffer is incomplete"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun rollbackCurrentContextFailureSuppressesCleanupFailureAndSkipsDeletes() {
        val gles = RecordingGles20Api().apply {
            framebufferStatus = GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        }
        val gl = FakeGlLaneScope().apply {
            failingCurrentContextOperations += "rollback ES2 rendering readback resources"
        }
        val result = preparer(gles = gles).prepare(request(gl = gl))

        val failure = result.failure()
        val primaryFailure = checkNotNull(failure.cause)
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(primaryFailure.message.orEmpty().contains("framebuffer is incomplete"))
        assertEquals("rollback ES2 rendering readback resources failed", primaryFailure.suppressed.single().message)
        assertEquals(emptyList<String>(), gles.deleteCalls)
    }

    @Test
    fun rollbackDeleteFailureIsSuppressedAndLaterHandlesAreStillDeleted() {
        val deleteProgramFailure = IllegalStateException("delete program failed")
        val deleteTextureFailure = IllegalStateException("delete texture failed")
        val gles = RecordingGles20Api().apply {
            framebufferStatus = GLES20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
            deleteFailures["deleteProgram:20"] = deleteProgramFailure
            deleteFailures["deleteTexture:30"] = deleteTextureFailure
        }
        val result = preparer(gles = gles).prepare(request())

        val failure = result.failure()
        val primaryFailure = checkNotNull(failure.cause)
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(primaryFailure.message.orEmpty().contains("framebuffer is incomplete"))
        assertSame(deleteProgramFailure, primaryFailure.suppressed.single())
        assertSame(deleteTextureFailure, deleteProgramFailure.suppressed.single())
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun framebufferCreationZeroReturnsGlResourceFailureAndDoesNotDeleteNeverCreatedFramebuffer() {
        val gles = RecordingGles20Api().apply {
            generateZeroFramebuffer = true
        }
        val prepared = prepareCountingAllocations(gles = gles, request = request())

        val failure = prepared.result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("glGenFramebuffers returned 0"))
        assertEquals(0, prepared.allocationCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun readbackAllocationFailureReturnsReadbackUnavailableAfterGlReadinessAndRollsBackObjects() {
        val gles = RecordingGles20Api()
        val allocationFailure = OutOfMemoryError("direct buffer unavailable")
        var allocationCount = 0
        val result = preparer(gles = gles) {
            allocationCount += 1
            throw allocationFailure
        }.prepare(request())

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, failure.kind)
        assertSame(allocationFailure, failure.cause)
        assertEquals(1, allocationCount)
        assertEquals(listOf(Pair(GLES20.GL_PACK_ALIGNMENT, 1), Pair(GLES20.GL_PACK_ALIGNMENT, 4)), gles.pixelStoreiCalls)
        assertEquals(listOf(TexImage2DCall(width = 30, height = 20)), gles.texImage2DCalls)
        assertEquals(listOf(FramebufferTexture2DCall(texture = 30)), gles.framebufferTexture2DCalls)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun restoreFailureDuringReadbackAllocationFailureDominatesAsGlResourceFailure() {
        val gles = RecordingGles20Api()
        val gl = FakeGlLaneScope().apply {
            failingCheckGlOperations += "restore ES2 readiness GL state"
        }
        val allocationFailure = OutOfMemoryError("direct buffer unavailable")
        val result = preparer(gles = gles) {
            throw allocationFailure
        }.prepare(request(gl = gl))

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("restore"))
        assertEquals("restore ES2 readiness GL state failed", failure.cause?.message)
        assertSame(allocationFailure, failure.cause?.suppressed?.single())
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun packAlignmentSetupFailureReturnsGlResourceFailureAndRollsBackGlObjects() {
        val gles = RecordingGles20Api().apply {
            activeTexture = GLES20.GL_TEXTURE0 + 2
            boundTexture2d = 801
            boundExternalOesTexture = 803
            boundFramebuffer = 802
            packAlignment = 8
        }
        val gl = FakeGlLaneScope().apply {
            failingCheckGlOperations += "configure ES2 readback pack alignment"
        }
        val result = preparer(gles = gles).prepare(request(gl = gl))

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("pack alignment"))
        assertEquals(listOf(Pair(GLES20.GL_PACK_ALIGNMENT, 1), Pair(GLES20.GL_PACK_ALIGNMENT, 8)), gles.pixelStoreiCalls)
        assertEquals(GLES20.GL_TEXTURE0 + 2, gles.activeTexture)
        assertEquals(801, gles.boundTexture2d)
        assertEquals(803, gles.boundExternalOesTexture)
        assertEquals(802, gles.boundFramebuffer)
        assertEquals(8, gles.packAlignment)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    @Test
    fun restoreFailureAfterReadinessSuccessRollsBackBeforePreparedTransfer() {
        val gles = RecordingGles20Api()
        val gl = FakeGlLaneScope().apply {
            failingCheckGlOperations += "restore ES2 readiness GL state"
        }
        val retirementLane = ManualRetirementLane()
        val result = preparer(gles = gles).prepare(
            request = request(gl = gl, retirementLane = retirementLane),
        )

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, failure.kind)
        assertTrue(failure.message.contains("restore"))
        assertEquals(0, retirementLane.queuedCount)
        assertEquals(
            listOf(
                "deleteProgram:20",
                "deleteShader:10",
                "deleteShader:11",
                "deleteFramebuffer:40",
                "deleteTexture:30",
            ),
            gles.deleteCalls,
        )
    }

    private fun preparer(
        gles: RecordingGles20Api,
        allocator: DirectByteBufferAllocator = DirectByteBufferAllocator { byteCount ->
            ByteBuffer.allocateDirect(byteCount)
        },
    ): Es2RenderingReadbackResourcePreparer =
        Es2RenderingReadbackResourcePreparer(gles = gles, readbackBufferAllocator = allocator)

    private fun prepareCountingAllocations(
        gles: RecordingGles20Api,
        request: Es2RenderingReadbackPrepareRequest,
    ): PreparationWithAllocationCount {
        var allocationCount = 0
        val result = preparer(gles = gles) { byteCount ->
            allocationCount += 1
            ByteBuffer.allocateDirect(byteCount)
        }.prepare(request)
        return PreparationWithAllocationCount(result = result, allocationCount = allocationCount)
    }

    private fun assertNoGlObjectOrReadbackAllocation(gles: RecordingGles20Api, allocationCount: Int) {
        assertNoGlObjectAllocation(gles = gles)
        assertEquals(0, allocationCount)
    }

    private fun assertNoGlObjectAllocation(gles: RecordingGles20Api) {
        assertEquals(0, gles.createShaderCount)
        assertEquals(emptyList<TexImage2DCall>(), gles.texImage2DCalls)
        assertEquals(emptyList<FramebufferTexture2DCall>(), gles.framebufferTexture2DCalls)
        assertEquals(emptyList<String>(), gles.deleteCalls)
    }

    private fun assertOriginalExternalOesProgramBinding(binding: Es2RenderingProgramBindingMetadata) {
        assertEquals(20, binding.programId)
        assertEquals(Es2RenderingShaderVariant.OriginalExternalOes, binding.shaderVariant)
        assertEquals(ColorMode.Original, binding.shaderVariant.supportedColorMode)
        assertCommonProgramBinding(binding)
    }

    private fun assertGrayscaleExternalOesProgramBinding(binding: Es2RenderingProgramBindingMetadata) {
        assertEquals(20, binding.programId)
        assertEquals(Es2RenderingShaderVariant.GrayscaleExternalOes, binding.shaderVariant)
        assertEquals(ColorMode.Grayscale, binding.shaderVariant.supportedColorMode)
        assertCommonProgramBinding(binding)
    }

    private fun assertCommonProgramBinding(binding: Es2RenderingProgramBindingMetadata) {
        assertEquals(3, binding.attributeLocations.position)
        assertEquals(4, binding.attributeLocations.textureCoordinate)
        assertEquals(5, binding.uniformLocations.externalOesTextureSampler)
        assertEquals(6, binding.uniformLocations.textureMatrix)
        assertEquals("uTexMatrix", binding.dynamicOesMatrixUniformSlot.uniformName)
        assertEquals(6, binding.dynamicOesMatrixUniformSlot.location)
        assertEquals(16, binding.dynamicOesMatrixUniformSlot.matrixElementCount)
        assertEquals(
            Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
            binding.dynamicOesMatrixUniformSlot.compositionRule,
        )
    }

    private fun resolveEs2ReadinessProductionFiles(): List<Es2ReadinessSourceFile> {
        val candidateRelativePaths = listOf(
            listOf(
                "screencaptureengine/src/main/java/dev/dmkr/screencaptureengine/internal/runtime/Gles20Api.kt",
                "screencaptureengine/src/main/java/dev/dmkr/screencaptureengine/internal/runtime/Es2RenderingReadbackResourcePreparer.kt",
                "screencaptureengine/src/main/java/dev/dmkr/screencaptureengine/internal/runtime/PreparedEs2RenderingReadbackResources.kt",
            ),
            listOf(
                "src/main/java/dev/dmkr/screencaptureengine/internal/runtime/Gles20Api.kt",
                "src/main/java/dev/dmkr/screencaptureengine/internal/runtime/Es2RenderingReadbackResourcePreparer.kt",
                "src/main/java/dev/dmkr/screencaptureengine/internal/runtime/PreparedEs2RenderingReadbackResources.kt",
            ),
        )
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val roots = generateSequence(start) { path -> path.parent }.toList()
        roots.forEach { root ->
            candidateRelativePaths.forEach { relativePaths ->
                val files = relativePaths.map { relativePath ->
                    Es2ReadinessSourceFile(path = root.resolve(relativePath), displayPath = relativePath)
                }
                if (files.all { Files.isRegularFile(it.path) }) return files
            }
        }
        error("Could not resolve ES2 readiness production source files from $start.")
    }

    private fun directGles20CallRegex(executableContent: String): Regex {
        val gles20References = buildList {
            add("android.opengl.GLES20")
            add("GLES20")
            Regex("""\bimport\s+android\.opengl\.GLES20\s+as\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(executableContent)
                .map { match -> match.groupValues[1] }
                .forEach(::add)
        }.distinct()
        val referencePattern = gles20References.joinToString(separator = "|") { reference ->
            Regex.escape(reference)
        }
        return Regex("(?<![.\\w])(?:$referencePattern)\\.gl[A-Z]")
    }

    private fun String.withoutKotlinCommentsAndStrings(): String {
        val stripped = StringBuilder(length)

        fun appendWhitespace(start: Int, endExclusive: Int) {
            repeat(endExclusive - start) {
                stripped.append(' ')
            }
        }

        fun appendLineComment(start: Int): Int {
            val end = indexOf('\n', startIndex = start).let { if (it < 0) length else it }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendBlockComment(start: Int): Int {
            var end = start + 2
            var depth = 1
            while ((end < length) && (depth > 0)) {
                when {
                    startsWith("/*", startIndex = end) -> {
                        depth += 1
                        end += 2
                    }

                    startsWith("*/", startIndex = end) -> {
                        depth -= 1
                        end += 2
                    }

                    else -> end += 1
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendCharLiteral(start: Int): Int {
            var end = start + 1
            var escaped = false
            while (end < length) {
                val char = this[end]
                end += 1
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '\'') {
                    break
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        lateinit var appendCode: (start: Int, endExclusive: Int, stopAtTemplateEnd: Boolean) -> Int

        fun dollarRunEnd(start: Int, endExclusive: Int): Int {
            var end = start
            while ((end < endExclusive) && (this[end] == '$')) {
                end += 1
            }
            return end
        }

        fun templateExpressionMarkerEnd(start: Int, endExclusive: Int, interpolationThreshold: Int): Int? {
            if (this[start] != '$') return null
            val dollarEnd = dollarRunEnd(start = start, endExclusive = endExclusive)
            return if (
                (dollarEnd < endExclusive) &&
                (this[dollarEnd] == '{') &&
                (dollarEnd - start >= interpolationThreshold)
            ) {
                dollarEnd + 1
            } else {
                null
            }
        }

        fun appendTemplateExpression(start: Int, markerEndExclusive: Int, endExclusive: Int): Int {
            appendWhitespace(start = start, endExclusive = markerEndExclusive)
            return appendCode(markerEndExclusive, endExclusive, true)
        }

        fun appendRegularString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 1
            appendWhitespace(start = start, endExclusive = current)
            var escaped = false
            while (current < endExclusive) {
                val markerEnd = if (!escaped) {
                    templateExpressionMarkerEnd(
                        start = current,
                        endExclusive = endExclusive,
                        interpolationThreshold = interpolationThreshold,
                    )
                } else {
                    null
                }
                when {
                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        val char = this[current]
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                        if (escaped) {
                            escaped = false
                        } else if (char == '\\') {
                            escaped = true
                        } else if (char == '"') {
                            return current
                        }
                    }
                }
            }
            return current
        }

        fun appendRawString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 3
            appendWhitespace(start = start, endExclusive = current)
            while (current < endExclusive) {
                val markerEnd = templateExpressionMarkerEnd(
                    start = current,
                    endExclusive = endExclusive,
                    interpolationThreshold = interpolationThreshold,
                )
                when {
                    startsWith("\"\"\"", startIndex = current) -> {
                        appendWhitespace(start = current, endExclusive = current + 3)
                        return current + 3
                    }

                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                    }
                }
            }
            return current
        }

        appendCode = appendCode@{ start, endExclusive, stopAtTemplateEnd ->
            var current = start
            var nestedBraceDepth = 0
            while (current < endExclusive) {
                when {
                    stopAtTemplateEnd && (this[current] == '}') && (nestedBraceDepth == 0) -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        return@appendCode current + 1
                    }

                    startsWith("//", startIndex = current) -> {
                        current = appendLineComment(start = current)
                    }

                    startsWith("/*", startIndex = current) -> {
                        current = appendBlockComment(start = current)
                    }

                    startsWith("\"\"\"", startIndex = current) -> {
                        current = appendRawString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '"' -> {
                        current = appendRegularString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '\'' -> {
                        current = appendCharLiteral(start = current)
                    }

                    this[current] == '$' -> {
                        val dollarEnd = dollarRunEnd(start = current, endExclusive = endExclusive)
                        current = when {
                            startsWith("\"\"\"", startIndex = dollarEnd) -> appendRawString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            (dollarEnd < endExclusive) && (this[dollarEnd] == '"') -> appendRegularString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            else -> {
                                stripped.append(this[current])
                                current + 1
                            }
                        }
                    }

                    stopAtTemplateEnd && (this[current] == '{') -> {
                        stripped.append(this[current])
                        nestedBraceDepth += 1
                        current += 1
                    }

                    stopAtTemplateEnd && (this[current] == '}') -> {
                        stripped.append(this[current])
                        nestedBraceDepth -= 1
                        current += 1
                    }

                    else -> {
                        stripped.append(this[current])
                        current += 1
                    }
                }
            }
            current
        }

        appendCode(0, length, false)
        return stripped.toString()
    }

    private data class PreparationWithAllocationCount(
        val result: Es2RenderingReadbackPreparationResult,
        val allocationCount: Int,
    )

    private data class Es2ReadinessSourceFile(
        val path: Path,
        val displayPath: String,
    )

    private fun request(
        gl: GlLaneScope = FakeGlLaneScope(),
        projectionTarget: ProjectionTargetGlScope = FakeProjectionTargetGlScope(),
        plan: ScreenCaptureOutputPlan = nonTrivialOutputPlan(),
        projectionTargetSpec: Es2ProjectionTargetSpec = projectionTargetSpec(plan),
        readbackSpec: Es2ReadbackSpec = readbackSpec(plan),
        selectedColorMode: ColorMode = plan.colorMode,
        retirementLane: GlResourceRetirementLane = ManualRetirementLane(),
    ): Es2RenderingReadbackPrepareRequest =
        Es2RenderingReadbackPrepareRequest(
            gl = gl,
            projectionTarget = projectionTarget,
            projectionTargetSpec = projectionTargetSpec,
            readbackSpec = readbackSpec,
            selectedColorMode = selectedColorMode,
            retirementLane = retirementLane,
        )

    private fun projectionTargetSpec(
        plan: ScreenCaptureOutputPlan,
        width: Int = plan.captureTarget.width,
        height: Int = plan.captureTarget.height,
        densityDpi: Int = plan.captureGeometry.densityDpi,
        generation: Long = 1L,
    ): Es2ProjectionTargetSpec =
        Es2ProjectionTargetSpec(
            width = width,
            height = height,
            densityDpi = densityDpi,
            generation = generation,
        )

    private fun readbackSpec(
        plan: ScreenCaptureOutputPlan,
        width: Int = plan.finalImageSize.width,
        height: Int = plan.finalImageSize.height,
        rowStrideBytes: Int = plan.rowStrideBytes,
        byteCount: Long = plan.rgbaByteCount,
        inputFormat: ImageEncoderInputFormat = plan.encoderRequest.inputFormat,
        readbackMode: ReadbackMode = plan.readbackMode,
    ): Es2ReadbackSpec =
        Es2ReadbackSpec(
            width = width,
            height = height,
            rowStrideBytes = rowStrideBytes,
            byteCount = byteCount,
            inputFormat = inputFormat,
            readbackMode = readbackMode,
        )

    private fun nonTrivialOutputPlan(): ScreenCaptureOutputPlan {
        val result = ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = 268_435_456,
                maxEncodedBytes = 1_024,
                readbackMode = ReadbackMode.Es2,
            ),
        ).plan(
            geometry = CaptureGeometry(
                widthPx = 101,
                heightPx = 77,
                densityDpi = 320,
                source = CaptureGeometrySource.MetricsProvider,
            ),
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
                rotation = Rotation.Degrees90,
                outputSize = OutputSize.TargetSize(width = 36, height = 20, contentMode = ContentMode.AspectFit),
            ),
        )
        val plan = (result as OutputPlanResult.Success).plan
        assertEquals(101, plan.captureGeometry.widthPx)
        assertEquals(77, plan.captureGeometry.heightPx)
        assertEquals(43, plan.captureTarget.width)
        assertEquals(33, plan.captureTarget.height)
        assertEquals(30, plan.finalImageSize.width)
        assertEquals(20, plan.finalImageSize.height)
        assertEquals(120, plan.encoderRequest.rowStrideBytes)
        assertEquals(2_400L, plan.rgbaByteCount)
        return plan
    }

    private fun Es2RenderingReadbackPreparationResult.successResources(): PreparedEs2RenderingReadbackResources =
        (this as Es2RenderingReadbackPreparationResult.Success).resources

    private fun Es2RenderingReadbackPreparationResult.failure(): Es2RenderingReadbackPreparationResult.Failure =
        this as Es2RenderingReadbackPreparationResult.Failure

    private class FakeGlLaneScope : GlLaneScope {
        var currentContextChecks = 0
        val glChecks = mutableListOf<String>()
        val failingCurrentContextOperations = mutableSetOf<String>()
        val failingCheckGlOperations = mutableSetOf<String>()

        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

        override fun checkCurrentContext(operation: String) {
            currentContextChecks += 1
            if (operation in failingCurrentContextOperations) {
                throw IllegalStateException("$operation failed")
            }
        }

        override fun checkGl(operation: String) {
            glChecks += operation
            if (operation in failingCheckGlOperations) {
                throw IllegalStateException("$operation failed")
            }
        }
    }

    private class FakeProjectionTargetGlScope(
        override val width: Int = 43,
        override val height: Int = 33,
        override val densityDpi: Int = 320,
        private val gles: RecordingGles20Api? = null,
        private val oesTextureId: Int = 900,
        private val oesValidationFailure: Throwable? = null,
    ) : ProjectionTargetGlScope {
        var oesValidationCount = 0

        override val generation: Long = 1L

        override fun validateExternalOesTexture() {
            oesValidationCount += 1
            oesValidationFailure?.let { throw it }
            gles?.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            gles?.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }
    }

    private class ManualRetirementLane : GlResourceRetirementLane {
        private val queued = ArrayDeque<GlLaneScope.() -> Unit>()
        private val scope = FakeGlLaneScope()

        val queuedCount: Int
            get() = queued.size

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            queued.addLast(block)
            return true
        }

        fun runNext() {
            queued.removeFirst().invoke(scope)
        }
    }

    private class RecordingGles20Api : Gles20Api {
        var failCompileShaderType: Int? = null
        var zeroShaderType: Int? = null
        var createProgramResult: Int = 20
        var linkStatus: Int = GLES20.GL_TRUE
        var validateStatus: Int = GLES20.GL_TRUE
        var generateZeroTexture: Boolean = false
        var generateZeroFramebuffer: Boolean = false
        var shaderCompilerAvailable: Boolean = true
        var framebufferStatus: Int = GLES20.GL_FRAMEBUFFER_COMPLETE
        var maxTextureSize: Int = 4_096
        var maxViewportWidth: Int = 4_096
        var maxViewportHeight: Int = 4_096
        val getIntegervFailures = mutableSetOf<Int>()
        val bindTextureFailures = mutableMapOf<String, Throwable>()
        val bindTextureCalls = mutableListOf<String>()
        val missingAttribLocations = mutableSetOf<String>()
        val missingUniformLocations = mutableSetOf<String>()
        val shaderSources = mutableMapOf<Int, String>()
        val attribLocationNames = mutableListOf<String>()
        val uniformLocationNames = mutableListOf<String>()
        val texImage2DCalls = mutableListOf<TexImage2DCall>()
        val framebufferTexture2DCalls = mutableListOf<FramebufferTexture2DCall>()
        val pixelStoreiCalls = mutableListOf<Pair<Int, Int>>()
        val validateProgramCalls = mutableListOf<Int>()
        val deleteCalls = mutableListOf<String>()
        val deleteFailures = mutableMapOf<String, Throwable>()
        private val shaderTypes = mutableMapOf<Int, Int>()
        private var nextShaderId = 10
        var createShaderCount = 0
        var activeTexture: Int = GLES20.GL_TEXTURE0
        var boundTexture2d: Int = 0
        var boundExternalOesTexture: Int = 0
        var boundFramebuffer: Int = 0
        var packAlignment: Int = 4

        override fun getIntegerv(pname: Int, params: IntArray, offset: Int) {
            if (pname in getIntegervFailures) {
                throw IllegalStateException("glGetIntegerv $pname failed")
            }
            when (pname) {
                GLES20.GL_MAX_TEXTURE_SIZE -> params[offset] = maxTextureSize
                GLES20.GL_MAX_VIEWPORT_DIMS -> {
                    params[offset] = maxViewportWidth
                    params[offset + 1] = maxViewportHeight
                }

                GLES20.GL_ACTIVE_TEXTURE -> params[offset] = activeTexture
                GLES20.GL_TEXTURE_BINDING_2D -> params[offset] = boundTexture2d
                GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES -> params[offset] = boundExternalOesTexture
                GLES20.GL_FRAMEBUFFER_BINDING -> params[offset] = boundFramebuffer
                GLES20.GL_PACK_ALIGNMENT -> params[offset] = packAlignment
                else -> error("Unexpected glGetIntegerv pname $pname")
            }
        }

        override fun getBooleanv(pname: Int, params: BooleanArray, offset: Int) {
            check(pname == GLES20.GL_SHADER_COMPILER)
            params[offset] = shaderCompilerAvailable
        }

        override fun activeTexture(texture: Int) {
            activeTexture = texture
        }

        override fun createShader(type: Int): Int {
            createShaderCount += 1
            if (type == zeroShaderType) return 0
            return nextShaderId.also { shaderId ->
                nextShaderId += 1
                shaderTypes[shaderId] = type
            }
        }

        override fun shaderSource(shader: Int, source: String) {
            shaderSources[shader] = source
        }

        override fun compileShader(shader: Int) = Unit

        override fun getShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {
            check(pname == GLES20.GL_COMPILE_STATUS)
            params[offset] = if (shaderTypes[shader] == failCompileShaderType) GLES20.GL_FALSE else GLES20.GL_TRUE
        }

        override fun getShaderInfoLog(shader: Int): String =
            "compile log for $shader"

        override fun createProgram(): Int = createProgramResult

        override fun attachShader(program: Int, shader: Int) = Unit

        override fun linkProgram(program: Int) = Unit

        override fun validateProgram(program: Int) {
            validateProgramCalls += program
        }

        override fun getProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
            params[offset] = when (pname) {
                GLES20.GL_LINK_STATUS -> linkStatus
                GLES20.GL_VALIDATE_STATUS -> validateStatus
                else -> error("Unexpected program iv pname $pname")
            }
        }

        override fun getProgramInfoLog(program: Int): String =
            "program log for $program"

        override fun getAttribLocation(program: Int, name: String): Int {
            attribLocationNames += name
            return if (name in missingAttribLocations) {
                -1
            } else {
                when (name) {
                    "aPosition" -> 3
                    "aTexCoord" -> 4
                    else -> error("Unexpected attribute location name $name")
                }
            }
        }

        override fun getUniformLocation(program: Int, name: String): Int {
            uniformLocationNames += name
            return if (name in missingUniformLocations) {
                -1
            } else {
                when (name) {
                    "uTexture" -> 5
                    "uTexMatrix" -> 6
                    else -> error("Unexpected uniform location name $name")
                }
            }
        }

        override fun genTextures(n: Int, textures: IntArray, offset: Int) {
            check(n == 1)
            textures[offset] = if (generateZeroTexture) 0 else 30
        }

        override fun bindTexture(target: Int, texture: Int) {
            val call = "$target:$texture"
            bindTextureCalls += call
            bindTextureFailures[call]?.let { throw it }
            when (target) {
                GLES20.GL_TEXTURE_2D -> boundTexture2d = texture
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES -> boundExternalOesTexture = texture
                else -> error("Unexpected texture target $target")
            }
        }

        override fun texParameteri(target: Int, pname: Int, param: Int) = Unit

        override fun texImage2D(
            target: Int,
            level: Int,
            internalformat: Int,
            width: Int,
            height: Int,
            border: Int,
            format: Int,
            type: Int,
            pixels: Buffer?,
        ) {
            check(target == GLES20.GL_TEXTURE_2D)
            check(internalformat == GLES20.GL_RGBA)
            check(format == GLES20.GL_RGBA)
            check(type == GLES20.GL_UNSIGNED_BYTE)
            check(pixels == null)
            texImage2DCalls += TexImage2DCall(width = width, height = height)
        }

        override fun genFramebuffers(n: Int, framebuffers: IntArray, offset: Int) {
            check(n == 1)
            framebuffers[offset] = if (generateZeroFramebuffer) 0 else 40
        }

        override fun bindFramebuffer(target: Int, framebuffer: Int) {
            check(target == GLES20.GL_FRAMEBUFFER)
            boundFramebuffer = framebuffer
        }

        override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) {
            check(target == GLES20.GL_FRAMEBUFFER)
            check(attachment == GLES20.GL_COLOR_ATTACHMENT0)
            check(textarget == GLES20.GL_TEXTURE_2D)
            framebufferTexture2DCalls += FramebufferTexture2DCall(texture = texture)
        }

        override fun checkFramebufferStatus(target: Int): Int {
            check(target == GLES20.GL_FRAMEBUFFER)
            return framebufferStatus
        }

        override fun pixelStorei(pname: Int, param: Int) {
            pixelStoreiCalls += Pair(pname, param)
            if (pname == GLES20.GL_PACK_ALIGNMENT) {
                packAlignment = param
            }
        }

        override fun deleteTexture(textureId: Int) {
            recordDelete("deleteTexture:$textureId", textureId)
        }

        override fun deleteFramebuffer(framebufferId: Int) {
            recordDelete("deleteFramebuffer:$framebufferId", framebufferId)
        }

        override fun deleteRenderbuffer(renderbufferId: Int) {
            recordDelete("deleteRenderbuffer:$renderbufferId", renderbufferId)
        }

        override fun deleteProgram(programId: Int) {
            recordDelete("deleteProgram:$programId", programId)
        }

        override fun deleteShader(shaderId: Int) {
            recordDelete("deleteShader:$shaderId", shaderId)
        }

        private fun recordDelete(call: String, handle: Int) {
            if (handle == 0) return
            deleteCalls += call
            deleteFailures[call]?.let { throw it }
        }
    }

    private data class TexImage2DCall(val width: Int, val height: Int)

    private data class FramebufferTexture2DCall(val texture: Int)
}
