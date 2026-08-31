package io.screenstream.capture.internal.capture

import android.annotation.SuppressLint
import android.hardware.DataSpace
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.screenstream.capture.ColorMode
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class GLRenderer(
    private val eglOwner: EglOwner,
    private var targetOwner: TargetOwner,
    private val precision: EglOwner.FragmentPrecision,
    private val clock: ElapsedRealtimeClock,
    private val platformSdkInt: Int,
) {
    internal class RetirementOutcome(
        internal val cleanupFailure: Throwable?,
        internal val residue: Throwable?,
        internal val glNameResidue: EglOwner.GLNameResidue? = null,
    )

    private enum class Retirement { Live, Attempted, Retired, }

    private val textureNames = IntArray(1)
    private val framebufferNames = IntArray(1)
    private val framebufferComponentBits = IntArray(1)
    private val shaderStatus = IntArray(1)
    private val programStatus = IntArray(1)
    private val surfaceTextureTransformMatrix = FloatArray(TargetPlatform.TRANSFORM_MATRIX_FLOAT_COUNT)
    private val logicalInverseMatrix = FloatArray(TargetPlatform.TRANSFORM_MATRIX_FLOAT_COUNT)
    private val positionBuffer: FloatBuffer = directFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val textureCoordinateBuffer: FloatBuffer = directFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private var outputTextureName = 0
    private var framebufferName = 0
    private var pendingOutputTextureName = 0
    private var pendingFramebufferName = 0
    private var vertexShaderName = 0
    private var fragmentShaderName = 0
    private var programName = 0
    private var oesMatrixLocation = -1
    private var imageMatrixLocation = -1
    private var grayscaleLocation = -1
    private var sourceTextureLocation = -1
    private var outputLayout: Rgba8888Layout? = null
    private var colorMode: ColorMode = ColorMode.Color
    private var sourceRestorableAfterReadFailure = true
    private var retirement = Retirement.Live
    private var retirementFailure: Throwable? = null

    internal val sourceRestorableAfterLastReadFailure: Boolean
        get() = sourceRestorableAfterReadFailure

    internal fun open(plan: CapturePlan) {
        check((programName == 0) && (outputTextureName == 0) && (framebufferName == 0))
        eglOwner.validateTargetAndOutput(plan)
        computeLogicalInverseMatrix(plan, logicalInverseMatrix)
        colorMode = plan.colorMode
        eglOwner.runGlesGroup { gl ->
            if (compileShader(gl, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) == 0) {
                return@runGlesGroup false
            }
            val source = if (precision == EglOwner.FragmentPrecision.High) FRAGMENT_SHADER_HIGHP else FRAGMENT_SHADER_MEDIUMP
            if (compileShader(gl, GLES20.GL_FRAGMENT_SHADER, source) == 0) {
                return@runGlesGroup false
            }
            val candidateProgram = gl.createProgram()
            if (candidateProgram == 0) return@runGlesGroup false
            programName = candidateProgram
            gl.attachShader(candidateProgram, vertexShaderName)
            gl.attachShader(candidateProgram, fragmentShaderName)
            gl.bindAttribLocation(candidateProgram, POSITION_ATTRIBUTE_INDEX, POSITION_ATTRIBUTE_NAME)
            gl.bindAttribLocation(candidateProgram, TEX_COORD_ATTRIBUTE_INDEX, TEX_COORD_ATTRIBUTE_NAME)
            gl.linkProgram(candidateProgram)
            programStatus[0] = GLES20.GL_FALSE
            gl.getProgramStatus(candidateProgram, programStatus)
            if (programStatus[0] != GLES20.GL_TRUE) return@runGlesGroup false
            oesMatrixLocation = gl.getUniformLocation(candidateProgram, OES_MATRIX_UNIFORM_NAME)
            imageMatrixLocation = gl.getUniformLocation(candidateProgram, IMAGE_MATRIX_UNIFORM_NAME)
            grayscaleLocation = gl.getUniformLocation(candidateProgram, GRAYSCALE_UNIFORM_NAME)
            sourceTextureLocation = gl.getUniformLocation(candidateProgram, SOURCE_TEXTURE_UNIFORM_NAME)
            if ((oesMatrixLocation < 0) || (imageMatrixLocation < 0) ||
                (grayscaleLocation < 0) || (sourceTextureLocation < 0)
            ) {
                return@runGlesGroup false
            }
            gl.detachShader(candidateProgram, vertexShaderName)
            gl.detachShader(candidateProgram, fragmentShaderName)
            allocateOutput(gl, plan.rgbaLayout.widthPx, plan.rgbaLayout.heightPx, pending = false)
        }
        outputLayout = plan.rgbaLayout
    }

    internal fun applyAfterPreflight(plan: CapturePlan) {
        val currentLayout = checkNotNull(outputLayout)
        if ((currentLayout.widthPx == plan.rgbaLayout.widthPx) && (currentLayout.heightPx == plan.rgbaLayout.heightPx)) {
            computeLogicalInverseMatrix(plan, logicalInverseMatrix)
            colorMode = plan.colorMode
            outputLayout = plan.rgbaLayout
            return
        }
        computeLogicalInverseMatrix(plan, logicalInverseMatrix)
        colorMode = plan.colorMode
        check(pendingFramebufferName == 0)
        check(pendingOutputTextureName == 0)
        eglOwner.runGlesGroup { gl ->
            if (framebufferName != 0) {
                framebufferNames[0] = framebufferName
                gl.deleteFramebuffers(framebufferNames)
            }
            if (outputTextureName != 0) {
                textureNames[0] = outputTextureName
                gl.deleteTextures(textureNames)
            }
            allocateOutput(gl, plan.rgbaLayout.widthPx, plan.rgbaLayout.heightPx, pending = true)
        }
        framebufferName = pendingFramebufferName
        outputTextureName = pendingOutputTextureName
        pendingFramebufferName = 0
        pendingOutputTextureName = 0
        outputLayout = plan.rgbaLayout
    }

    internal fun replaceTarget(replacement: TargetOwner) {
        targetOwner = replacement
    }

    internal fun readFrame(carrier: ByteBuffer): Long {
        val layout = checkNotNull(outputLayout)
        check(carrier.isExactWritableRgbaCarrier(layout.byteCount))
        sourceRestorableAfterReadFailure = true
        return materializeFrame(carrier)
    }

    internal fun retireGLNamesAfterContextDestroyed(proof: EglOwner.GLNamespaceDestroyedProof): Boolean {
        if (!proof.matches(eglOwner)) return false
        framebufferName = 0
        outputTextureName = 0
        pendingFramebufferName = 0
        pendingOutputTextureName = 0
        programName = 0
        vertexShaderName = 0
        fragmentShaderName = 0
        retirement = Retirement.Retired
        return true
    }

    private fun materializeFrame(carrier: ByteBuffer): Long {
        val layout = checkNotNull(outputLayout)
        val preparedTexture = targetOwner.requireSurfaceTexture()
        var operationFailure: CaptureBoundaryFailure? = null
        var readbackStartedNanos = 0L
        var readbackFinishedNanos = 0L
        eglOwner.runGlesGroup { gl ->
            sourceRestorableAfterReadFailure = false
            val dataSpace = targetOwner.updateFrameAndReadDataSpace(preparedTexture, surfaceTextureTransformMatrix)
            if (isDisplayP3DataSpace(dataSpace, platformSdkInt)) {
                operationFailure = CaptureBoundaryFailure(
                    problem = ScreenCaptureProblem.UnsupportedColorSpace,
                    physicalCause = CapturePhysicalException("Display P3 source color space is unsupported"),
                )
                return@runGlesGroup true
            }
            gl.bindFramebuffer(framebufferName)
            gl.useProgram(programName)
            gl.viewport(layout.widthPx, layout.heightPx)
            gl.activeTexture(GLES20.GL_TEXTURE0)
            gl.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, targetOwner.requireOesTextureName())
            gl.uniform1i(sourceTextureLocation, 0)
            gl.uniformMatrix4fv(oesMatrixLocation, surfaceTextureTransformMatrix)
            gl.uniformMatrix4fv(imageMatrixLocation, logicalInverseMatrix)
            gl.uniform1f(grayscaleLocation, if (colorMode == ColorMode.Grayscale) 1f else 0f)
            positionBuffer.position(0)
            textureCoordinateBuffer.position(0)
            gl.vertexAttribPointer(POSITION_ATTRIBUTE_INDEX, positionBuffer)
            gl.vertexAttribPointer(TEX_COORD_ATTRIBUTE_INDEX, textureCoordinateBuffer)
            gl.enableVertexAttribArray(POSITION_ATTRIBUTE_INDEX)
            gl.enableVertexAttribArray(TEX_COORD_ATTRIBUTE_INDEX)
            gl.colorMask()
            gl.packAlignmentOne()
            gl.disable(GLES20.GL_BLEND)
            gl.disable(GLES20.GL_DEPTH_TEST)
            gl.disable(GLES20.GL_STENCIL_TEST)
            gl.disable(GLES20.GL_SCISSOR_TEST)
            gl.disable(GLES20.GL_CULL_FACE)
            gl.disable(GLES20.GL_DITHER)
            gl.drawTriangleStrip()
            readbackStartedNanos = clock.nowNanos()
            gl.readPixels(layout.widthPx, layout.heightPx, carrier)
            readbackFinishedNanos = clock.nowNanos()
            if (!carrier.isExactWritableRgbaCarrier(layout.byteCount)) {
                throw CaptureBoundaryFailure(
                    problem = ScreenCaptureProblem.InternalFailure,
                    physicalCause = CapturePhysicalException("Direct carrier range changed during readback"),
                )
            }
            true
        }
        operationFailure?.let { throw it }
        val duration = try {
            Math.subtractExact(readbackFinishedNanos, readbackStartedNanos)
        } catch (failure: ArithmeticException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        if (duration < 0L) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, CapturePhysicalException("Readback clock moved backwards"))
        }
        return duration
    }

    internal fun close(): RetirementOutcome {
        if (retirement == Retirement.Retired) return RetirementOutcome(null, null)
        val ownsNames = (framebufferName != 0) || (outputTextureName != 0) ||
                (pendingFramebufferName != 0) || (pendingOutputTextureName != 0) ||
                (programName != 0) || (vertexShaderName != 0) || (fragmentShaderName != 0)
        if (!ownsNames) {
            retirement = Retirement.Retired
            return RetirementOutcome(cleanupFailure = null, residue = null)
        }
        if (!eglOwner.isHealthy) {
            val residue = retirementFailure ?: CapturePhysicalException("Renderer GL names remain in an unusable namespace")
            return RetirementOutcome(
                cleanupFailure = retirementFailure,
                residue = residue,
                glNameResidue = EglOwner.GLNameResidue(eglOwner, residue),
            )
        }
        if (retirement == Retirement.Attempted) {
            val residue = retirementFailure ?: CapturePhysicalException("Renderer GL-name deletion remains unproved")
            return RetirementOutcome(
                cleanupFailure = retirementFailure,
                residue = residue,
                glNameResidue = EglOwner.GLNameResidue(eglOwner, residue),
            )
        }
        retirementFailure = try {
            retirement = Retirement.Attempted
            eglOwner.runGlesGroup { gl ->
                if (framebufferName != 0) {
                    framebufferNames[0] = framebufferName
                    gl.deleteFramebuffers(framebufferNames)
                }
                if (outputTextureName != 0) {
                    textureNames[0] = outputTextureName
                    gl.deleteTextures(textureNames)
                }
                if ((pendingFramebufferName != 0) && (pendingFramebufferName != framebufferName)) {
                    framebufferNames[0] = pendingFramebufferName
                    gl.deleteFramebuffers(framebufferNames)
                }
                if ((pendingOutputTextureName != 0) && (pendingOutputTextureName != outputTextureName)) {
                    textureNames[0] = pendingOutputTextureName
                    gl.deleteTextures(textureNames)
                }
                if (programName != 0) {
                    gl.deleteProgram(programName)
                }
                if (vertexShaderName != 0) {
                    gl.deleteShader(vertexShaderName)
                }
                if (fragmentShaderName != 0) {
                    gl.deleteShader(fragmentShaderName)
                }
                true
            }
            framebufferName = 0
            outputTextureName = 0
            pendingFramebufferName = 0
            pendingOutputTextureName = 0
            programName = 0
            vertexShaderName = 0
            fragmentShaderName = 0
            retirement = Retirement.Retired
            null
        } catch (failure: Exception) {
            failure
        }
        val residue = retirementFailure ?: if (retirement == Retirement.Retired) {
            null
        } else {
            CapturePhysicalException("Renderer GL-name deletion remains unproved")
        }
        return RetirementOutcome(
            cleanupFailure = retirementFailure,
            residue = residue,
            glNameResidue = residue?.let { EglOwner.GLNameResidue(eglOwner, it) },
        )
    }

    private fun compileShader(gl: GlesPlatform, type: Int, source: String): Int {
        val shader = gl.createShader(type)
        if (shader == 0) return 0
        if (type == GLES20.GL_VERTEX_SHADER) {
            vertexShaderName = shader
        } else {
            fragmentShaderName = shader
        }
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        shaderStatus[0] = GLES20.GL_FALSE
        gl.getShaderStatus(shader, shaderStatus)
        if (shaderStatus[0] != GLES20.GL_TRUE) {
            gl.deleteShader(shader)
            return 0
        }
        return shader
    }

    private fun allocateOutput(gl: GlesPlatform, widthPx: Int, heightPx: Int, pending: Boolean): Boolean {
        textureNames[0] = 0
        gl.genTextures(textureNames)
        val texture = textureNames[0]
        if (texture == 0) return false
        if (pending) {
            pendingOutputTextureName = texture
        } else {
            outputTextureName = texture
        }
        gl.bindTexture(GLES20.GL_TEXTURE_2D, texture)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        gl.texImage2D(widthPx, heightPx)
        framebufferNames[0] = 0
        gl.genFramebuffers(framebufferNames)
        val framebuffer = framebufferNames[0]
        if (framebuffer == 0) return false
        if (pending) {
            pendingFramebufferName = framebuffer
        } else {
            framebufferName = framebuffer
        }
        gl.bindFramebuffer(framebuffer)
        gl.framebufferTexture2D(texture)
        if (gl.checkFramebufferStatus() != GLES20.GL_FRAMEBUFFER_COMPLETE) return false
        gl.getInteger(GLES20.GL_RED_BITS, framebufferComponentBits)
        val redBits = framebufferComponentBits[0]
        gl.getInteger(GLES20.GL_GREEN_BITS, framebufferComponentBits)
        val greenBits = framebufferComponentBits[0]
        gl.getInteger(GLES20.GL_BLUE_BITS, framebufferComponentBits)
        val blueBits = framebufferComponentBits[0]
        return minOf(redBits, greenBits, blueBits) >= 8
    }

    private companion object {
        private const val POSITION_ATTRIBUTE_INDEX = 0
        private const val TEX_COORD_ATTRIBUTE_INDEX = 1
        private const val POSITION_ATTRIBUTE_NAME = "aPosition"
        private const val TEX_COORD_ATTRIBUTE_NAME = "aTexCoord"
        private const val OES_MATRIX_UNIFORM_NAME = "uOesMatrix"
        private const val IMAGE_MATRIX_UNIFORM_NAME = "uImageMatrix"
        private const val GRAYSCALE_UNIFORM_NAME = "uGrayscale"
        private const val SOURCE_TEXTURE_UNIFORM_NAME = "uSourceTexture"

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private fun computeLogicalInverseMatrix(plan: CapturePlan, destination: FloatArray) {
            val sourceWidthPx = plan.sourceWidthPx
            val sourceHeightPx = plan.sourceHeightPx
            val appliedSourceRect = plan.appliedSourceRect
            val appliedLeftPx = appliedSourceRect.leftPx
            val appliedTopPx = appliedSourceRect.topPx
            val appliedRightPx = appliedSourceRect.rightPx
            val appliedBottomPx = appliedSourceRect.bottomPx
            val croppedWidthPx = appliedRightPx - appliedLeftPx
            val croppedHeightPx = appliedBottomPx - appliedTopPx

            val mappedOrigin = DoubleArray(2)
            val mappedUnitX = DoubleArray(2)
            val mappedUnitY = DoubleArray(2)
            mapOutputPointToSource(
                outputXFraction = 0.0,
                outputYFraction = 0.0,
                captureWidthPx = sourceWidthPx,
                captureHeightPx = sourceHeightPx,
                appliedLeftPx = appliedLeftPx,
                appliedTopPx = appliedTopPx,
                croppedWidthPx = croppedWidthPx,
                croppedHeightPx = croppedHeightPx,
                rotation = plan.rotation,
                mirror = plan.mirror,
                destination = mappedOrigin,
            )
            mapOutputPointToSource(
                outputXFraction = 1.0,
                outputYFraction = 0.0,
                captureWidthPx = sourceWidthPx,
                captureHeightPx = sourceHeightPx,
                appliedLeftPx = appliedLeftPx,
                appliedTopPx = appliedTopPx,
                croppedWidthPx = croppedWidthPx,
                croppedHeightPx = croppedHeightPx,
                rotation = plan.rotation,
                mirror = plan.mirror,
                destination = mappedUnitX,
            )
            mapOutputPointToSource(
                outputXFraction = 0.0,
                outputYFraction = 1.0,
                captureWidthPx = sourceWidthPx,
                captureHeightPx = sourceHeightPx,
                appliedLeftPx = appliedLeftPx,
                appliedTopPx = appliedTopPx,
                croppedWidthPx = croppedWidthPx,
                croppedHeightPx = croppedHeightPx,
                rotation = plan.rotation,
                mirror = plan.mirror,
                destination = mappedUnitY,
            )
            destination.fill(0f)
            destination[0] = toFiniteFloat(mappedUnitX[0] - mappedOrigin[0])
            destination[1] = toFiniteFloat(mappedUnitX[1] - mappedOrigin[1])
            destination[4] = toFiniteFloat(mappedUnitY[0] - mappedOrigin[0])
            destination[5] = toFiniteFloat(mappedUnitY[1] - mappedOrigin[1])
            destination[10] = 1f
            destination[12] = toFiniteFloat(mappedOrigin[0])
            destination[13] = toFiniteFloat(mappedOrigin[1])
            destination[15] = 1f
        }

        @Suppress("LongParameterList")
        private fun mapOutputPointToSource(
            outputXFraction: Double,
            outputYFraction: Double,
            captureWidthPx: Int,
            captureHeightPx: Int,
            appliedLeftPx: Int,
            appliedTopPx: Int,
            croppedWidthPx: Int,
            croppedHeightPx: Int,
            rotation: Rotation,
            mirror: Mirror,
            destination: DoubleArray,
        ) {
            val rotated = (rotation == Rotation.Degrees90) || (rotation == Rotation.Degrees270)
            val orientedWidthPx = if (rotated) croppedHeightPx.toDouble() else croppedWidthPx.toDouble()
            val orientedHeightPx = if (rotated) croppedWidthPx.toDouble() else croppedHeightPx.toDouble()
            var orientedX = outputXFraction * orientedWidthPx
            var orientedY = outputYFraction * orientedHeightPx
            when (mirror) {
                Mirror.None -> Unit
                Mirror.Horizontal -> orientedX = orientedWidthPx - orientedX
                Mirror.Vertical -> orientedY = orientedHeightPx - orientedY
            }
            val sourceX: Double
            val sourceY: Double
            when (rotation) {
                Rotation.Degrees0 -> {
                    sourceX = orientedX
                    sourceY = orientedY
                }

                Rotation.Degrees90 -> {
                    sourceX = orientedY
                    sourceY = croppedHeightPx - orientedX
                }

                Rotation.Degrees180 -> {
                    sourceX = croppedWidthPx - orientedX
                    sourceY = croppedHeightPx - orientedY
                }

                Rotation.Degrees270 -> {
                    sourceX = croppedWidthPx - orientedY
                    sourceY = orientedX
                }
            }
            destination[0] = (appliedLeftPx + sourceX) / captureWidthPx.toDouble()
            destination[1] = (appliedTopPx + sourceY) / captureHeightPx.toDouble()
        }

        private fun toFiniteFloat(value: Double): Float {
            require(value.isFinite() && (value in (-Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble())))
            return value.toFloat()
        }

        @SuppressLint("NewApi")
        private fun isDisplayP3DataSpace(dataSpace: Int, platformSdkInt: Int): Boolean =
            TargetPlatform.supportsDataSpace(platformSdkInt) && Api33DataSpace.isDisplayP3(dataSpace)

        @RequiresApi(VERSION_CODES.TIRAMISU)
        private object Api33DataSpace {
            fun isDisplayP3(dataSpace: Int): Boolean = dataSpace == DataSpace.DATASPACE_DISPLAY_P3
        }

        private const val VERTEX_SHADER: String = """
            uniform mat4 $OES_MATRIX_UNIFORM_NAME;
            uniform mat4 $IMAGE_MATRIX_UNIFORM_NAME;
            attribute vec4 $POSITION_ATTRIBUTE_NAME;
            attribute vec4 $TEX_COORD_ATTRIBUTE_NAME;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = $POSITION_ATTRIBUTE_NAME;
                vec4 framebufferCoordinate = vec4($TEX_COORD_ATTRIBUTE_NAME.x, $TEX_COORD_ATTRIBUTE_NAME.y, 0.0, 1.0);
                vec4 imageCoordinate = $IMAGE_MATRIX_UNIFORM_NAME * framebufferCoordinate;
                imageCoordinate.y = 1.0 - imageCoordinate.y;
                vTexCoord = ($OES_MATRIX_UNIFORM_NAME * imageCoordinate).xy;
            }
        """

        private const val FRAGMENT_EXTENSION: String = "#extension GL_OES_EGL_image_external : require\n"
        private const val FRAGMENT_BODY: String = """
            uniform samplerExternalOES $SOURCE_TEXTURE_UNIFORM_NAME;
            uniform float $GRAYSCALE_UNIFORM_NAME;
            varying vec2 vTexCoord;
            void main() {
                vec4 sampled = texture2D($SOURCE_TEXTURE_UNIFORM_NAME, vTexCoord);
                vec3 color = clamp(sampled.rgb, 0.0, 1.0);
                vec3 rgb8 = min(vec3(255.0), max(vec3(0.0), floor(255.0 * color + vec3(0.5))));
                if ($GRAYSCALE_UNIFORM_NAME > 0.5) {
                    float gray8 = floor(
                        0.30078125 * rgb8.r + 0.5859375 * rgb8.g + 0.11328125 * rgb8.b + 0.5
                    );
                    rgb8 = vec3(gray8);
                }
                gl_FragColor = vec4(rgb8 / 255.0, 1.0);
            }
        """
        private const val FRAGMENT_SHADER_HIGHP: String = "${FRAGMENT_EXTENSION}precision highp float;$FRAGMENT_BODY"
        private const val FRAGMENT_SHADER_MEDIUMP: String = "${FRAGMENT_EXTENSION}precision mediump float;$FRAGMENT_BODY"
    }
}
