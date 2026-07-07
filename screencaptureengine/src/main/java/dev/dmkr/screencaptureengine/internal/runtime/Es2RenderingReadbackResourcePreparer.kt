package dev.dmkr.screencaptureengine.internal.runtime

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import java.nio.ByteBuffer

/**
 * Lower-level ES2 rendering/readback resource/config preparer.
 *
 * The caller must invoke this from the owning current GL lane while the request projection target is
 * active for the same current context. This validates the selected original or grayscale external-OES
 * program path, output framebuffer, tight RGBA readback shape, and direct readback storage.
 *
 * Direct readback storage is allocated only after the current GL state, projection target, selected
 * shader program, output framebuffer, and pack alignment have been accepted. This pass does not
 * install a frame loop, consume a frame, update a `SurfaceTexture`, read pixels, or invoke an encoder.
 */
internal class Es2RenderingReadbackResourcePreparer internal constructor(
    private val gles: Gles20Api = AndroidGles20Api,
    private val readbackBufferAllocator: DirectByteBufferAllocator = JvmDirectByteBufferAllocator,
) {
    internal fun prepare(
        request: Es2RenderingReadbackPrepareRequest,
    ): Es2RenderingReadbackPreparationResult {
        val gl = request.gl
        val savedGlState = try {
            gl.checkCurrentContext("prepare ES2 rendering readback resources")
            saveEs2ReadinessGlState(gl = gl, gles = gles)
        } catch (cause: Throwable) {
            return Es2RenderingReadbackPreparationResult.Failure(
                kind = ScreenCaptureProblemKind.GlInvariantViolation,
                message = "ES2 rendering readback preparation requires valid current GL state.",
                cause = cause,
            )
        }

        val builder = Es2RenderingReadbackGlObjectBuilder(gles = gles)
        val candidate = Es2RenderingReadbackCandidateBuilder(
            request = request,
            builder = builder,
            gl = gl,
        ).buildOrFailure()
        val restoreFailure = savedGlState.restore(gl = gl, gles = gles)

        return when {
            candidate.failure != null -> {
                mergeRestoreFailure(result = candidate.failure, restoreFailure = restoreFailure)
            }

            restoreFailure != null -> {
                val failure = Es2RenderingReadbackPreparationFailure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "ES2 readiness GL state restore failed.",
                    cause = restoreFailure,
                )
                builder.rollback(gl = gl, primaryFailure = failure)
                failure.toResultFailure()
            }

            else -> {
                val prepared = candidate.prepared ?: error("ES2 candidate is missing after successful preparation.")
                try {
                    val resources = PreparedEs2RenderingReadbackResources(
                        retirementLane = request.retirementLane,
                        glObjects = prepared.glObjects,
                        width = request.readbackSpec.width,
                        height = request.readbackSpec.height,
                        rowStrideBytes = request.readbackSpec.rowStrideBytes,
                        readbackBuffer = prepared.readbackBuffer,
                        gles = gles,
                    )
                    builder.transferOwnership()
                    Es2RenderingReadbackPreparationResult.Success(resources)
                } catch (cause: Throwable) {
                    val failure = Es2RenderingReadbackPreparationFailure(
                        kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                        message = "ES2 prepared readback resource transfer failed.",
                        cause = cause,
                    )
                    builder.rollback(gl = gl, primaryFailure = failure)
                    failure.toResultFailure()
                }
            }
        }
    }

    private inner class Es2RenderingReadbackCandidateBuilder(
        private val request: Es2RenderingReadbackPrepareRequest,
        private val builder: Es2RenderingReadbackGlObjectBuilder,
        private val gl: GlLaneScope,
    ) {
        fun buildOrFailure(): Es2RenderingReadbackCandidate {
            validateProjectionTargetOes()?.let { return failure(it) }
            validateProjectionTargetShape(
                actual = request.projectionTarget,
                expected = request.projectionTargetSpec,
            )?.let { return failure(it) }

            val bufferByteCount = try {
                validateReadbackShape(spec = request.readbackSpec)
            } catch (cause: Es2RenderingReadbackPreparationFailure) {
                return failure(cause.toResultFailure())
            }

            if (!validateNoObjectGlCapabilities()) return failure()

            val glObjects = buildGlObjects() ?: return failure()
            if (!configurePackAlignment()) return failure()
            val readbackBuffer = allocateReadbackBuffer(bufferByteCount = bufferByteCount) ?: return failure()

            return Es2RenderingReadbackCandidate(
                prepared = PreparedEs2RenderingReadbackCandidate(
                    glObjects = glObjects,
                    readbackBuffer = readbackBuffer,
                ),
                failure = null,
            )
        }

        private var failure: Es2RenderingReadbackPreparationResult.Failure? = null

        private fun validateProjectionTargetOes(): Es2RenderingReadbackPreparationResult.Failure? =
            try {
                request.projectionTarget.validateExternalOesTexture()
                null
            } catch (cause: Throwable) {
                Es2RenderingReadbackPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.GlInvariantViolation,
                    message = "ES2 rendering readback preparation requires a valid projection target.",
                    cause = cause,
                ).also { failure = it }
            }

        private fun validateProjectionTargetShape(
            actual: ProjectionTargetGlScope,
            expected: Es2ProjectionTargetSpec,
        ): Es2RenderingReadbackPreparationResult.Failure? =
            try {
                if ((actual.width != expected.width) || (actual.height != expected.height)) {
                    throw Es2RenderingReadbackPreparationFailure(
                        kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                        message = "Projection target dimensions ${actual.width}x${actual.height} do not match expected " +
                                "${expected.width}x${expected.height}.",
                        cause = null,
                    )
                }
                if (actual.densityDpi != expected.densityDpi) {
                    throw Es2RenderingReadbackPreparationFailure(
                        kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                        message = "Projection target density ${actual.densityDpi} does not match expected ${expected.densityDpi}.",
                        cause = null,
                    )
                }
                if (actual.generation != expected.generation) {
                    throw Es2RenderingReadbackPreparationFailure(
                        kind = ScreenCaptureProblemKind.GlInvariantViolation,
                        message = "Projection target generation ${actual.generation} does not match expected ${expected.generation}.",
                        cause = null,
                    )
                }
                null
            } catch (cause: Es2RenderingReadbackPreparationFailure) {
                cause.toResultFailure().also { failure = it }
            }

        private fun validateNoObjectGlCapabilities(): Boolean =
            try {
                builder.validateNoObjectCapabilities(
                    gl = gl,
                    outputWidth = request.readbackSpec.width,
                    outputHeight = request.readbackSpec.height,
                )
                true
            } catch (cause: Es2RenderingReadbackPreparationFailure) {
                failure = cause.toResultFailure()
                false
            } catch (cause: Throwable) {
                val failure = Es2RenderingReadbackPreparationFailure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "ES2 GL capability validation failed.",
                    cause = cause,
                )
                this.failure = failure.toResultFailure()
                false
            }

        private fun allocateReadbackBuffer(bufferByteCount: Int): ByteBuffer? =
            try {
                readbackBufferAllocator.allocateDirect(bufferByteCount)
            } catch (cause: Throwable) {
                val failure = Es2RenderingReadbackPreparationFailure(
                    kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                    message = "ES2 readback storage preparation failed.",
                    cause = cause,
                )
                builder.rollback(gl = gl, primaryFailure = failure)
                this.failure = failure.toResultFailure()
                null
            }

        private fun buildGlObjects(): PreparedEs2RenderingReadbackGlObjects? =
            try {
                builder.build(
                    gl = gl,
                    outputWidth = request.readbackSpec.width,
                    outputHeight = request.readbackSpec.height,
                    shaderVariant = request.selectedColorMode.toEs2RenderingShaderVariant(),
                )
            } catch (cause: Es2RenderingReadbackPreparationFailure) {
                builder.rollback(gl = gl, primaryFailure = cause)
                failure = cause.toResultFailure()
                null
            } catch (cause: Throwable) {
                val failure = Es2RenderingReadbackPreparationFailure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "ES2 GL resource preparation failed.",
                    cause = cause,
                )
                builder.rollback(gl = gl, primaryFailure = failure)
                this.failure = failure.toResultFailure()
                null
            }

        private fun configurePackAlignment(): Boolean =
            try {
                gles.pixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
                gl.checkGl("configure ES2 readback pack alignment")
                true
            } catch (cause: Throwable) {
                val failure = Es2RenderingReadbackPreparationFailure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "ES2 readback pack alignment setup failed.",
                    cause = cause,
                )
                builder.rollback(gl = gl, primaryFailure = failure)
                this.failure = failure.toResultFailure()
                false
            }

        private fun failure(
            failure: Es2RenderingReadbackPreparationResult.Failure = checkNotNull(this.failure),
        ): Es2RenderingReadbackCandidate =
            Es2RenderingReadbackCandidate(prepared = null, failure = failure)
    }

    private fun validateReadbackShape(spec: Es2ReadbackSpec): Int {
        if (spec.readbackMode != ReadbackMode.Es2) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback resources require an ES2 output plan.",
                cause = null,
            )
        }
        if (spec.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback requires RGBA8888 sRGB opaque encoder input.",
                cause = null,
            )
        }
        val tightRowStrideBytes = try {
            Math.multiplyExact(spec.width, RGBA_8888_BYTES_PER_PIXEL)
        } catch (cause: ArithmeticException) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback row stride overflowed.",
                cause = cause,
            )
        }
        if (spec.rowStrideBytes != tightRowStrideBytes) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback requires tight RGBA8888 row stride.",
                cause = null,
            )
        }
        val checkedByteCount = try {
            Math.multiplyExact(spec.rowStrideBytes, spec.height)
        } catch (cause: ArithmeticException) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback byte count overflowed.",
                cause = cause,
            )
        }
        if (checkedByteCount.toLong() != spec.byteCount) {
            throw Es2RenderingReadbackPreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "ES2 readback byte count does not match the output spec.",
                cause = null,
            )
        }
        return checkedByteCount
    }
}

private class PreparedEs2RenderingReadbackCandidate(
    val glObjects: PreparedEs2RenderingReadbackGlObjects,
    val readbackBuffer: ByteBuffer,
)

private class Es2RenderingReadbackCandidate(
    val prepared: PreparedEs2RenderingReadbackCandidate?,
    val failure: Es2RenderingReadbackPreparationResult.Failure?,
) {
    init {
        check((prepared == null) xor (failure == null))
    }
}

private class SavedEs2ReadinessGlState private constructor(
    private val activeTexture: Int,
    private val texture2dBinding: Int,
    private val externalOesBinding: Int,
    private val framebufferBinding: Int,
    private val packAlignment: Int,
) {
    fun restore(gl: GlLaneScope, gles: Gles20Api): Throwable? =
        CleanupFailureCollector().also { restoreFailures ->
            restoreFailures.collect { gles.activeTexture(ES2_READINESS_TEXTURE_UNIT) }
            restoreFailures.collect { gles.bindTexture(GLES20.GL_TEXTURE_2D, texture2dBinding) }
            restoreFailures.collect { gles.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalOesBinding) }
            restoreFailures.collect { gles.bindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferBinding) }
            restoreFailures.collect { gles.pixelStorei(GLES20.GL_PACK_ALIGNMENT, packAlignment) }
            restoreFailures.collect { gles.activeTexture(activeTexture) }
            restoreFailures.collect { gl.checkGl("restore ES2 readiness GL state") }
        }.failureOrNull()

    companion object {
        fun save(gl: GlLaneScope, gles: Gles20Api): SavedEs2ReadinessGlState {
            val activeTexture = IntArray(1)
            val texture2dBinding = IntArray(1)
            val externalOesBinding = IntArray(1)
            val framebufferBinding = IntArray(1)
            val packAlignment = IntArray(1)
            gles.getIntegerv(GLES20.GL_ACTIVE_TEXTURE, activeTexture, 0)
            try {
                gles.activeTexture(ES2_READINESS_TEXTURE_UNIT)
                gles.getIntegerv(GLES20.GL_TEXTURE_BINDING_2D, texture2dBinding, 0)
                gles.getIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, externalOesBinding, 0)
                gles.getIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
                gles.getIntegerv(GLES20.GL_PACK_ALIGNMENT, packAlignment, 0)
                gl.checkGl("save ES2 readiness GL state")
            } catch (cause: Throwable) {
                runCatching {
                    gles.activeTexture(activeTexture[0])
                }.onFailure(cause::addSuppressed)
                throw cause
            }
            return SavedEs2ReadinessGlState(
                activeTexture = activeTexture[0],
                texture2dBinding = texture2dBinding[0],
                externalOesBinding = externalOesBinding[0],
                framebufferBinding = framebufferBinding[0],
                packAlignment = packAlignment[0],
            )
        }
    }
}

internal class Es2RenderingReadbackPrepareRequest internal constructor(
    internal val gl: GlLaneScope,
    internal val projectionTarget: ProjectionTargetGlScope,
    internal val projectionTargetSpec: Es2ProjectionTargetSpec,
    internal val readbackSpec: Es2ReadbackSpec,
    internal val selectedColorMode: ColorMode,
    internal val retirementLane: GlResourceRetirementLane,
)

internal class Es2ProjectionTargetSpec internal constructor(
    internal val width: Int,
    internal val height: Int,
    internal val densityDpi: Int,
    internal val generation: Long,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
        require(generation > 0L) { "generation must be positive, was $generation" }
    }
}

internal class Es2ReadbackSpec internal constructor(
    internal val width: Int,
    internal val height: Int,
    internal val rowStrideBytes: Int,
    internal val byteCount: Long,
    internal val inputFormat: ImageEncoderInputFormat,
    internal val readbackMode: ReadbackMode,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(rowStrideBytes > 0) { "rowStrideBytes must be positive, was $rowStrideBytes" }
        require(byteCount > 0L) { "byteCount must be positive, was $byteCount" }
    }
}

internal fun interface DirectByteBufferAllocator {
    fun allocateDirect(byteCount: Int): ByteBuffer
}

internal object JvmDirectByteBufferAllocator : DirectByteBufferAllocator {
    override fun allocateDirect(byteCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(byteCount)
}

internal sealed class Es2RenderingReadbackPreparationResult private constructor() {
    internal class Success internal constructor(
        internal val resources: PreparedEs2RenderingReadbackResources,
    ) : Es2RenderingReadbackPreparationResult()

    internal class Failure internal constructor(
        internal val kind: ScreenCaptureProblemKind,
        internal val message: String,
        internal val cause: Throwable?,
    ) : Es2RenderingReadbackPreparationResult()
}

private const val ES2_READINESS_TEXTURE_UNIT: Int = GLES20.GL_TEXTURE0
private const val RGBA_8888_BYTES_PER_PIXEL: Int = 4

private class Es2RenderingReadbackGlObjectBuilder(
    private val gles: Gles20Api,
) {
    private var outputTextureId: Int = 0
    private var outputFramebufferId: Int = 0
    private var outputRenderbufferId: Int = 0
    private var programId: Int = 0
    private var vertexShaderId: Int = 0
    private var fragmentShaderId: Int = 0
    private var transferred = false

    fun validateNoObjectCapabilities(gl: GlLaneScope, outputWidth: Int, outputHeight: Int) {
        validateOutputLimits(gl = gl, outputWidth = outputWidth, outputHeight = outputHeight)
        validateShaderCompiler(gl = gl)
    }

    fun build(
        gl: GlLaneScope,
        outputWidth: Int,
        outputHeight: Int,
        shaderVariant: Es2RenderingShaderVariant,
    ): PreparedEs2RenderingReadbackGlObjects {
        vertexShaderId = compileShader(
            gl = gl,
            type = GLES20.GL_VERTEX_SHADER,
            source = ES2_OES_VERTEX_SHADER,
            label = "vertex",
        )
        fragmentShaderId = compileShader(
            gl = gl,
            type = GLES20.GL_FRAGMENT_SHADER,
            source = shaderVariant.fragmentShaderSource,
            label = "fragment",
        )
        programId = linkProgram(gl = gl, vertexShaderId = vertexShaderId, fragmentShaderId = fragmentShaderId)
        validateProgram(gl = gl, programId = programId)
        val programBinding = resolveProgramBinding(gl = gl, programId = programId, shaderVariant = shaderVariant)
        createOutputTexture(gl = gl, outputWidth = outputWidth, outputHeight = outputHeight)
        createOutputFramebuffer(gl = gl)
        return PreparedEs2RenderingReadbackGlObjects(
            outputTextureId = outputTextureId,
            outputFramebufferId = outputFramebufferId,
            outputRenderbufferId = outputRenderbufferId,
            programId = programId,
            vertexShaderId = vertexShaderId,
            fragmentShaderId = fragmentShaderId,
            programBinding = programBinding,
        )
    }

    fun transferOwnership() {
        transferred = true
        outputTextureId = 0
        outputFramebufferId = 0
        outputRenderbufferId = 0
        programId = 0
        vertexShaderId = 0
        fragmentShaderId = 0
    }

    fun rollback(gl: GlLaneScope, primaryFailure: Throwable) {
        if (transferred) return
        cleanupOwnedResources(gl = gl)?.let { cleanupFailure ->
            suppressCleanupFailure(primaryFailure = primaryFailure, cleanupFailure = cleanupFailure)
        }
        outputTextureId = 0
        outputFramebufferId = 0
        outputRenderbufferId = 0
        programId = 0
        vertexShaderId = 0
        fragmentShaderId = 0
    }

    private fun validateOutputLimits(gl: GlLaneScope, outputWidth: Int, outputHeight: Int) {
        val maxTextureSize = IntArray(1)
        val maxViewportDims = IntArray(2)
        gles.getIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        gles.getIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDims, 0)
        gl.checkGl("query ES2 output GL limits")
        if ((maxTextureSize[0] <= 0) || (maxViewportDims[0] <= 0) || (maxViewportDims[1] <= 0)) {
            throw glResourceFailure("ES2 output GL limits are invalid.")
        }
        if ((outputWidth > maxTextureSize[0]) || (outputHeight > maxTextureSize[0])) {
            throw glResourceFailure("ES2 output texture exceeds GL_MAX_TEXTURE_SIZE.")
        }
        if ((outputWidth > maxViewportDims[0]) || (outputHeight > maxViewportDims[1])) {
            throw glResourceFailure("ES2 output dimensions exceed GL_MAX_VIEWPORT_DIMS.")
        }
    }

    private fun validateShaderCompiler(gl: GlLaneScope) {
        val shaderCompilerAvailable = BooleanArray(1)
        gles.getBooleanv(GLES20.GL_SHADER_COMPILER, shaderCompilerAvailable, 0)
        gl.checkGl("query ES2 shader compiler availability")
        if (!shaderCompilerAvailable[0]) {
            throw glResourceFailure("ES2 shader compiler is unavailable.")
        }
    }

    private fun compileShader(gl: GlLaneScope, type: Int, source: String, label: String): Int {
        val shaderId = gles.createShader(type)
        if (shaderId == 0) {
            gl.checkGl("create ES2 $label shader")
            throw glResourceFailure("glCreateShader returned 0 for ES2 $label shader.")
        }
        registerShader(type = type, shaderId = shaderId)
        gl.checkGl("create ES2 $label shader")
        gles.shaderSource(shaderId, source)
        gles.compileShader(shaderId)
        gl.checkGl("compile ES2 $label shader")
        val status = IntArray(1)
        gles.getShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, status, 0)
        gl.checkGl("query ES2 $label shader compile status")
        if (status[0] != GLES20.GL_TRUE) {
            throw glResourceFailure("ES2 $label shader compilation failed: ${gles.getShaderInfoLog(shaderId)}")
        }
        return shaderId
    }

    private fun linkProgram(gl: GlLaneScope, vertexShaderId: Int, fragmentShaderId: Int): Int {
        val programId = gles.createProgram()
        if (programId == 0) {
            gl.checkGl("create ES2 program")
            throw glResourceFailure("glCreateProgram returned 0 for ES2 program.")
        }
        this.programId = programId
        gl.checkGl("create ES2 program")
        gles.attachShader(programId, vertexShaderId)
        gles.attachShader(programId, fragmentShaderId)
        gles.linkProgram(programId)
        gl.checkGl("link ES2 program")
        val status = IntArray(1)
        gles.getProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
        gl.checkGl("query ES2 program link status")
        if (status[0] != GLES20.GL_TRUE) {
            throw glResourceFailure("ES2 program link failed: ${gles.getProgramInfoLog(programId)}")
        }
        return programId
    }

    private fun validateProgram(gl: GlLaneScope, programId: Int) {
        gles.validateProgram(programId)
        gl.checkGl("validate ES2 program")
        val status = IntArray(1)
        gles.getProgramiv(programId, GLES20.GL_VALIDATE_STATUS, status, 0)
        gl.checkGl("query ES2 program validation status")
        if (status[0] != GLES20.GL_TRUE) {
            throw glResourceFailure("ES2 program validation failed: ${gles.getProgramInfoLog(programId)}")
        }
    }

    private fun resolveProgramBinding(
        gl: GlLaneScope,
        programId: Int,
        shaderVariant: Es2RenderingShaderVariant,
    ): Es2RenderingProgramBindingMetadata {
        val positionLocation = gles.getAttribLocation(programId, A_POSITION)
        val textureCoordinateLocation = gles.getAttribLocation(programId, A_TEX_COORD)
        val externalOesTextureSamplerLocation = gles.getUniformLocation(programId, U_TEXTURE)
        val textureMatrixLocation = gles.getUniformLocation(programId, U_TEX_MATRIX)
        gl.checkGl("query ES2 program locations")
        val missing = buildList {
            if (positionLocation < 0) add(A_POSITION)
            if (textureCoordinateLocation < 0) add(A_TEX_COORD)
            if (externalOesTextureSamplerLocation < 0) add(U_TEXTURE)
            if (textureMatrixLocation < 0) add(U_TEX_MATRIX)
        }
        if (missing.isNotEmpty()) {
            throw glResourceFailure("ES2 program is missing required locations: ${missing.joinToString()}.")
        }
        return Es2RenderingProgramBindingMetadata(
            programId = programId,
            shaderVariant = shaderVariant,
            attributeLocations = Es2RenderingProgramAttributeLocations(
                position = positionLocation,
                textureCoordinate = textureCoordinateLocation,
            ),
            uniformLocations = Es2RenderingProgramUniformLocations(
                externalOesTextureSampler = externalOesTextureSamplerLocation,
                textureMatrix = textureMatrixLocation,
            ),
            dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                uniformName = U_TEX_MATRIX,
                location = textureMatrixLocation,
                matrixElementCount = ES2_MAT4_ELEMENT_COUNT,
                compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
            ),
        )
    }

    private fun createOutputTexture(gl: GlLaneScope, outputWidth: Int, outputHeight: Int) {
        val textures = IntArray(1)
        gles.genTextures(1, textures, 0)
        outputTextureId = textures[0]
        if (outputTextureId == 0) {
            gl.checkGl("generate ES2 output texture")
            throw glResourceFailure("glGenTextures returned 0 for ES2 output texture.")
        }
        gl.checkGl("generate ES2 output texture")
        gles.bindTexture(GLES20.GL_TEXTURE_2D, outputTextureId)
        gles.texParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        gles.texParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        gles.texParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        gles.texParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        gles.texImage2D(
            target = GLES20.GL_TEXTURE_2D,
            level = 0,
            internalformat = GLES20.GL_RGBA,
            width = outputWidth,
            height = outputHeight,
            border = 0,
            format = GLES20.GL_RGBA,
            type = GLES20.GL_UNSIGNED_BYTE,
            pixels = null,
        )
        gl.checkGl("allocate ES2 output texture")
    }

    private fun createOutputFramebuffer(gl: GlLaneScope) {
        val framebuffers = IntArray(1)
        gles.genFramebuffers(1, framebuffers, 0)
        outputFramebufferId = framebuffers[0]
        if (outputFramebufferId == 0) {
            gl.checkGl("generate ES2 output framebuffer")
            throw glResourceFailure("glGenFramebuffers returned 0 for ES2 output framebuffer.")
        }
        gl.checkGl("generate ES2 output framebuffer")
        gles.bindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebufferId)
        gles.framebufferTexture2D(
            target = GLES20.GL_FRAMEBUFFER,
            attachment = GLES20.GL_COLOR_ATTACHMENT0,
            textarget = GLES20.GL_TEXTURE_2D,
            texture = outputTextureId,
            level = 0,
        )
        gl.checkGl("attach ES2 output texture to framebuffer")
        val status = gles.checkFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        gl.checkGl("check ES2 output framebuffer status")
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw glResourceFailure("ES2 output framebuffer is incomplete: 0x${Integer.toHexString(status)}.")
        }
    }

    private fun glResourceFailure(message: String): Es2RenderingReadbackPreparationFailure =
        Es2RenderingReadbackPreparationFailure(
            kind = ScreenCaptureProblemKind.GlResourceFailure,
            message = message,
            cause = null,
        )

    private fun registerShader(type: Int, shaderId: Int) {
        when (type) {
            GLES20.GL_VERTEX_SHADER -> vertexShaderId = shaderId
            GLES20.GL_FRAGMENT_SHADER -> fragmentShaderId = shaderId
            else -> error("Unsupported ES2 shader type $type.")
        }
    }

    private fun cleanupOwnedResources(gl: GlLaneScope): Throwable? {
        try {
            gl.checkCurrentContext("rollback ES2 rendering readback resources")
        } catch (cause: Throwable) {
            return cause
        }
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collectDelete(programId, gles::deleteProgram)
        cleanupFailures.collectDelete(vertexShaderId, gles::deleteShader)
        cleanupFailures.collectDelete(fragmentShaderId, gles::deleteShader)
        cleanupFailures.collectDelete(outputFramebufferId, gles::deleteFramebuffer)
        cleanupFailures.collectDelete(outputRenderbufferId, gles::deleteRenderbuffer)
        cleanupFailures.collectDelete(outputTextureId, gles::deleteTexture)
        cleanupFailures.collect { gl.checkGl("rollback ES2 rendering readback resources") }
        return cleanupFailures.failureOrNull()
    }
}

private class Es2RenderingReadbackPreparationFailure(
    val kind: ScreenCaptureProblemKind,
    override val message: String,
    override val cause: Throwable?,
) : Exception(message, cause)

private fun Es2RenderingReadbackPreparationFailure.toResultFailure(): Es2RenderingReadbackPreparationResult.Failure =
    Es2RenderingReadbackPreparationResult.Failure(kind = kind, message = message, cause = cause ?: this)

private fun saveEs2ReadinessGlState(gl: GlLaneScope, gles: Gles20Api): SavedEs2ReadinessGlState =
    SavedEs2ReadinessGlState.save(gl = gl, gles = gles)

private fun mergeRestoreFailure(
    result: Es2RenderingReadbackPreparationResult.Failure,
    restoreFailure: Throwable?,
): Es2RenderingReadbackPreparationResult.Failure {
    if (restoreFailure == null) return result
    result.cause?.let(restoreFailure::addSuppressed)
    return Es2RenderingReadbackPreparationResult.Failure(
        kind = ScreenCaptureProblemKind.GlResourceFailure,
        message = "ES2 readiness GL state restore failed.",
        cause = restoreFailure,
    )
}

private fun CleanupFailureCollector.collectDelete(handle: Int, delete: (Int) -> Unit) {
    if (handle == 0) return
    collect { delete(handle) }
}

private fun suppressCleanupFailure(primaryFailure: Throwable, cleanupFailure: Throwable) {
    val suppressionTarget = primaryFailure.cause ?: primaryFailure
    suppressionTarget.addSuppressed(cleanupFailure)
}

private fun ColorMode.toEs2RenderingShaderVariant(): Es2RenderingShaderVariant =
    when (this) {
        ColorMode.Original -> Es2RenderingShaderVariant.OriginalExternalOes
        ColorMode.Grayscale -> Es2RenderingShaderVariant.GrayscaleExternalOes
    }

private const val A_POSITION: String = "aPosition"
private const val A_TEX_COORD: String = "aTexCoord"
private const val U_TEXTURE: String = "uTexture"
private const val U_TEX_MATRIX: String = "uTexMatrix"
private const val GRAYSCALE_RED_WEIGHT: String = "0.299"
private const val GRAYSCALE_GREEN_WEIGHT: String = "0.587"
private const val GRAYSCALE_BLUE_WEIGHT: String = "0.114"
private const val ES2_MAT4_ELEMENT_COUNT: Int = 16

private val ES2_OES_VERTEX_SHADER: String = """
    attribute vec4 $A_POSITION;
    attribute vec2 $A_TEX_COORD;
    uniform mat4 $U_TEX_MATRIX;
    varying vec2 vTexCoord;

    void main() {
        gl_Position = $A_POSITION;
        vTexCoord = ($U_TEX_MATRIX * vec4($A_TEX_COORD, 0.0, 1.0)).xy;
    }
""".trimIndent()

private val ES2_OES_FRAGMENT_SHADER: String = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES $U_TEXTURE;
    varying vec2 vTexCoord;

    void main() {
        vec4 color = texture2D($U_TEXTURE, vTexCoord);
        gl_FragColor = vec4(color.rgb, 1.0);
    }
""".trimIndent()

private val ES2_GRAYSCALE_OES_FRAGMENT_SHADER: String = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES $U_TEXTURE;
    varying vec2 vTexCoord;

    void main() {
        vec4 color = texture2D($U_TEXTURE, vTexCoord);
        float gray = dot(color.rgb, vec3($GRAYSCALE_RED_WEIGHT, $GRAYSCALE_GREEN_WEIGHT, $GRAYSCALE_BLUE_WEIGHT));
        gl_FragColor = vec4(gray, gray, gray, 1.0);
    }
""".trimIndent()

private val Es2RenderingShaderVariant.fragmentShaderSource: String
    get() = when (this) {
        Es2RenderingShaderVariant.OriginalExternalOes -> ES2_OES_FRAGMENT_SHADER
        Es2RenderingShaderVariant.GrayscaleExternalOes -> ES2_GRAYSCALE_OES_FRAGMENT_SHADER
    }
