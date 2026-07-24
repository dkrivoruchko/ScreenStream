package io.screenstream.engine.internal.capture

import android.annotation.TargetApi
import android.hardware.DataSpace
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import io.screenstream.engine.ColorMode
import io.screenstream.engine.Mirror
import io.screenstream.engine.Rotation
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.SourceRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal enum class SourceColorClassification {
    Srgb,
    DisplayP3,
    Scrgb,
    HdrTransfer,
    NominalSdr,
}

internal class CaptureColorFact internal constructor(
    internal val classification: SourceColorClassification,
    internal val colorMode: ColorMode,
)

internal fun interface CaptureColorFactSink {
    /** Bounded fact ingress only; implementations may not run Control policy inline. */
    fun onColorAction(fact: CaptureColorFact)
}

internal class GlRenderer internal constructor(
    private val eglOwner: EglOwner,
    private var targetOwner: TargetOwner,
    private val precision: FragmentPrecision,
    private val colorFactSink: CaptureColorFactSink,
    private val clock: CaptureClock,
) {
    private val textureNames = IntArray(1)
    private val framebufferNames = IntArray(1)
    private val shaderStatus = IntArray(1)
    private val programStatus = IntArray(1)
    private val oesTransform = FloatArray(16)
    private val logicalInverse = FloatArray(16)
    private val positionBuffer: FloatBuffer = directFloatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val textureCoordinateBuffer: FloatBuffer = directFloatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
    )

    private var outputTextureName = 0
    private var framebufferName = 0
    private var vertexShaderName = 0
    private var fragmentShaderName = 0
    private var programName = 0
    private var oesMatrixLocation = -1
    private var imageMatrixLocation = -1
    private var colorActionLocation = -1
    private var grayscaleLocation = -1
    private var samplerLocation = -1
    private var outputWidthPx = 0
    private var outputHeightPx = 0
    private var colorMode: ColorMode = ColorMode.Color
    private var lastClassification: SourceColorClassification? = null
    private var closeAttempted = false

    internal fun construct(plan: CapturePlan) {
        check(programName == 0 && outputTextureName == 0 && framebufferName == 0)
        eglOwner.validateTargetAndOutput(plan)
        computeLogicalInverse(plan, logicalInverse)
        colorMode = plan.parameters.colorMode
        val deadline = CaptureOperationDeadline.start(clock, glEnteredOperationSafetyNanos)
        eglOwner.runGlesGroup { gl ->
            vertexShaderName = compileShader(gl, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            fragmentShaderName = compileShader(
                gl,
                GLES20.GL_FRAGMENT_SHADER,
                if (precision == FragmentPrecision.High) FRAGMENT_SHADER_HIGHP else FRAGMENT_SHADER_MEDIUMP,
            )
            val candidateProgram = gl.createProgram()
            if (candidateProgram == 0) return@runGlesGroup false
            programName = candidateProgram
            gl.attachShader(candidateProgram, vertexShaderName)
            gl.attachShader(candidateProgram, fragmentShaderName)
            gl.bindAttribLocation(candidateProgram, POSITION_ATTRIBUTE, "aPosition")
            gl.bindAttribLocation(candidateProgram, TEX_COORD_ATTRIBUTE, "aTexCoord")
            gl.linkProgram(candidateProgram)
            gl.getProgramStatus(candidateProgram, programStatus)
            if (programStatus[0] != GLES20.GL_TRUE) return@runGlesGroup false
            oesMatrixLocation = gl.getUniformLocation(candidateProgram, "uOesMatrix")
            imageMatrixLocation = gl.getUniformLocation(candidateProgram, "uImageMatrix")
            colorActionLocation = gl.getUniformLocation(candidateProgram, "uColorAction")
            grayscaleLocation = gl.getUniformLocation(candidateProgram, "uGrayscale")
            samplerLocation = gl.getUniformLocation(candidateProgram, "uTexture")
            if (oesMatrixLocation < 0 || imageMatrixLocation < 0 || colorActionLocation < 0 ||
                grayscaleLocation < 0 || samplerLocation < 0
            ) {
                return@runGlesGroup false
            }
            gl.detachShader(candidateProgram, vertexShaderName)
            gl.detachShader(candidateProgram, fragmentShaderName)
            allocateOutput(gl, plan.outputWidthPx, plan.outputHeightPx)
        }
        outputWidthPx = plan.outputWidthPx
        outputHeightPx = plan.outputHeightPx
        if (!deadline.returnedTimely(clock)) {
            eglOwner.markUnknown()
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("GL renderer construction returned after its safety interval"),
            )
        }
    }

    internal fun applyAfterPreflight(plan: CapturePlan) {
        computeLogicalInverse(plan, logicalInverse)
        if (colorMode != plan.parameters.colorMode) lastClassification = null
        colorMode = plan.parameters.colorMode
        if (outputWidthPx == plan.outputWidthPx && outputHeightPx == plan.outputHeightPx) return
        val deadline = CaptureOperationDeadline.start(clock, glEnteredOperationSafetyNanos)
        eglOwner.runGlesGroup { gl ->
            if (framebufferName != 0) {
                framebufferNames[0] = framebufferName
                gl.deleteFramebuffers(framebufferNames)
                framebufferName = 0
            }
            if (outputTextureName != 0) {
                textureNames[0] = outputTextureName
                gl.deleteTextures(textureNames)
                outputTextureName = 0
            }
            allocateOutput(gl, plan.outputWidthPx, plan.outputHeightPx)
        }
        outputWidthPx = plan.outputWidthPx
        outputHeightPx = plan.outputHeightPx
        if (!deadline.returnedTimely(clock)) {
            eglOwner.markUnknown()
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("GL output reconfiguration returned after its safety interval"),
            )
        }
    }

    internal fun replaceTarget(replacement: TargetOwner) {
        targetOwner = replacement
        lastClassification = null
    }

    /** Returns the interval bracketing only the normal glReadPixels call. */
    internal fun readFrame(carrier: ByteBuffer): Long {
        check(carrier.isDirect && !carrier.isReadOnly)
        check(carrier.position() == 0 && carrier.limit() == outputWidthPx * outputHeightPx * RGBA_BYTES_PER_PIXEL)
        val frameDeadlineNanos = Math.addExact(clock.elapsedRealtimeNanos(), glEnteredOperationSafetyNanos)
        eglOwner.requireCurrent()
        val dataSpace = targetOwner.updateAndCopyTransform(oesTransform)
        val classification = classifyDataSpace(dataSpace)

        val state = eglOwner.beginGlesGroup()
        try {
            state.bindFramebuffer(framebufferName)
            state.useProgram(programName)
            state.viewport(outputWidthPx, outputHeightPx)
            state.activeTexture(GLES20.GL_TEXTURE0)
            state.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, targetOwner.oesTextureName())
            state.uniform1i(samplerLocation, 0)
            state.uniformMatrix4fv(oesMatrixLocation, oesTransform)
            state.uniformMatrix4fv(imageMatrixLocation, logicalInverse)
            state.uniform1f(
                colorActionLocation,
                if (classification == SourceColorClassification.DisplayP3) 1f else 0f,
            )
            state.uniform1f(grayscaleLocation, if (colorMode == ColorMode.Grayscale) 1f else 0f)
            positionBuffer.position(0)
            textureCoordinateBuffer.position(0)
            state.vertexAttribPointer(POSITION_ATTRIBUTE, positionBuffer)
            state.vertexAttribPointer(TEX_COORD_ATTRIBUTE, textureCoordinateBuffer)
            state.enableVertexAttribArray(POSITION_ATTRIBUTE)
            state.enableVertexAttribArray(TEX_COORD_ATTRIBUTE)
            state.colorMask()
            state.packAlignmentOne()
            state.disable(GLES20.GL_BLEND)
            state.disable(GLES20.GL_DEPTH_TEST)
            state.disable(GLES20.GL_STENCIL_TEST)
            state.disable(GLES20.GL_SCISSOR_TEST)
            state.disable(GLES20.GL_CULL_FACE)
            state.disable(GLES20.GL_DITHER)
        } catch (failure: Exception) {
            eglOwner.failExternalGlCall(failure)
        }
        eglOwner.endGlesGroup(true)

        val drawRead = eglOwner.beginGlesGroup()
        val readbackStartedNanos: Long
        val readbackFinishedNanos: Long
        try {
            drawRead.drawTriangleStrip()
            readbackStartedNanos = clock.elapsedRealtimeNanos()
            drawRead.readPixels(outputWidthPx, outputHeightPx, carrier)
            readbackFinishedNanos = clock.elapsedRealtimeNanos()
        } catch (failure: Exception) {
            eglOwner.failExternalGlCall(failure)
        }
        eglOwner.endGlesGroup(true)
        if (carrier.position() != 0 || carrier.limit() != outputWidthPx * outputHeightPx * RGBA_BYTES_PER_PIXEL ||
            carrier.capacity() < carrier.limit()
        ) {
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("Direct carrier range changed during readback"),
            )
        }
        if (clock.elapsedRealtimeNanos() >= frameDeadlineNanos) {
            eglOwner.markUnknown()
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("GL frame operation returned after its safety interval"),
            )
        }
        val duration = try {
            Math.subtractExact(readbackFinishedNanos, readbackStartedNanos)
        } catch (failure: ArithmeticException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        if (duration < 0L) {
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("Readback clock moved backwards"),
            )
        }
        if (lastClassification != classification) {
            lastClassification = classification
            colorFactSink.onColorAction(CaptureColorFact(classification, colorMode))
        }
        return duration
    }

    internal fun close(): Throwable? {
        if (closeAttempted || !eglOwner.isHealthy) return null
        closeAttempted = true
        return try {
            eglOwner.runGlesGroup { gl ->
                if (framebufferName != 0) {
                    framebufferNames[0] = framebufferName
                    gl.deleteFramebuffers(framebufferNames)
                    framebufferName = 0
                }
                if (outputTextureName != 0) {
                    textureNames[0] = outputTextureName
                    gl.deleteTextures(textureNames)
                    outputTextureName = 0
                }
                if (programName != 0) {
                    gl.deleteProgram(programName)
                    programName = 0
                }
                if (vertexShaderName != 0) {
                    gl.deleteShader(vertexShaderName)
                    vertexShaderName = 0
                }
                if (fragmentShaderName != 0) {
                    gl.deleteShader(fragmentShaderName)
                    fragmentShaderName = 0
                }
                true
            }
            null
        } catch (failure: CaptureBoundaryFailure) {
            failure.physicalCause ?: failure
        }
    }

    private fun compileShader(gl: GlesPlatform, type: Int, source: String): Int {
        val shader = gl.createShader(type)
        if (shader == 0) return 0
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        gl.getShaderStatus(shader, shaderStatus)
        if (shaderStatus[0] != GLES20.GL_TRUE) {
            gl.deleteShader(shader)
            return 0
        }
        return shader
    }

    private fun allocateOutput(gl: GlesPlatform, widthPx: Int, heightPx: Int): Boolean {
        gl.genTextures(textureNames)
        val texture = textureNames[0]
        if (texture == 0) return false
        outputTextureName = texture
        gl.bindTexture(GLES20.GL_TEXTURE_2D, texture)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        gl.texParameter(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        gl.texImage2d(widthPx, heightPx)
        gl.genFramebuffers(framebufferNames)
        val framebuffer = framebufferNames[0]
        if (framebuffer == 0) return false
        framebufferName = framebuffer
        gl.bindFramebuffer(framebuffer)
        gl.framebufferTexture2d(texture)
        return gl.checkFramebufferStatus() == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val POSITION_ATTRIBUTE = 0
        private const val TEX_COORD_ATTRIBUTE = 1

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private fun computeLogicalInverse(plan: CapturePlan, destination: FloatArray) {
            val width = plan.sourceWidthPx
            val height = plan.sourceHeightPx
            val parameters = plan.parameters
            val regionLeft: Int
            val regionRight: Int
            when (parameters.sourceRegion) {
                SourceRegion.Full -> {
                    regionLeft = 0
                    regionRight = width
                }

                SourceRegion.LeftHalf -> {
                    require(width >= 2)
                    regionLeft = 0
                    regionRight = width / 2
                }

                SourceRegion.RightHalf -> {
                    require(width >= 2)
                    regionLeft = width / 2
                    regionRight = width
                }
            }
            val crop = parameters.crop
            val appliedLeft = regionLeft + crop.left
            val appliedTop = crop.top
            val appliedRight = regionRight - crop.right
            val appliedBottom = height - crop.bottom
            require(appliedRight > appliedLeft && appliedBottom > appliedTop)
            val croppedWidth = appliedRight - appliedLeft
            val croppedHeight = appliedBottom - appliedTop

            val p00 = DoubleArray(2)
            val p10 = DoubleArray(2)
            val p01 = DoubleArray(2)
            mapPoint(0.0, 0.0, width, height, appliedLeft, appliedTop, croppedWidth, croppedHeight, parameters.rotation, parameters.mirror, p00)
            mapPoint(1.0, 0.0, width, height, appliedLeft, appliedTop, croppedWidth, croppedHeight, parameters.rotation, parameters.mirror, p10)
            mapPoint(0.0, 1.0, width, height, appliedLeft, appliedTop, croppedWidth, croppedHeight, parameters.rotation, parameters.mirror, p01)
            destination.fill(0f)
            destination[0] = finiteFloat(p10[0] - p00[0])
            destination[1] = finiteFloat(p10[1] - p00[1])
            destination[4] = finiteFloat(p01[0] - p00[0])
            destination[5] = finiteFloat(p01[1] - p00[1])
            destination[10] = 1f
            destination[12] = finiteFloat(p00[0])
            destination[13] = finiteFloat(p00[1])
            destination[15] = 1f
        }

        @Suppress("LongParameterList")
        private fun mapPoint(
            a: Double,
            b: Double,
            captureWidth: Int,
            captureHeight: Int,
            appliedLeft: Int,
            appliedTop: Int,
            croppedWidth: Int,
            croppedHeight: Int,
            rotation: Rotation,
            mirror: Mirror,
            result: DoubleArray,
        ) {
            val rotated = rotation == Rotation.Degrees90 || rotation == Rotation.Degrees270
            val orientedWidth = if (rotated) croppedHeight.toDouble() else croppedWidth.toDouble()
            val orientedHeight = if (rotated) croppedWidth.toDouble() else croppedHeight.toDouble()
            var u = a * orientedWidth
            var v = b * orientedHeight
            when (mirror) {
                Mirror.None -> Unit
                Mirror.Horizontal -> u = orientedWidth - u
                Mirror.Vertical -> v = orientedHeight - v
            }
            val x: Double
            val y: Double
            when (rotation) {
                Rotation.Degrees0 -> {
                    x = u; y = v
                }

                Rotation.Degrees90 -> {
                    x = v; y = croppedHeight - u
                }

                Rotation.Degrees180 -> {
                    x = croppedWidth - u; y = croppedHeight - v
                }

                Rotation.Degrees270 -> {
                    x = croppedWidth - v; y = u
                }
            }
            result[0] = (appliedLeft + x) / captureWidth.toDouble()
            result[1] = (appliedTop + y) / captureHeight.toDouble()
        }

        private fun finiteFloat(value: Double): Float {
            require(value.isFinite() && value in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble())
            return value.toFloat()
        }

        private fun classifyDataSpace(dataSpace: Int): SourceColorClassification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Api33DataSpace.classify(dataSpace)
            } else {
                SourceColorClassification.NominalSdr
            }

        @TargetApi(Build.VERSION_CODES.TIRAMISU)
        private object Api33DataSpace {
            fun classify(dataSpace: Int): SourceColorClassification = when {
                dataSpace == DataSpace.DATASPACE_SRGB -> SourceColorClassification.Srgb
                dataSpace == DataSpace.DATASPACE_DISPLAY_P3 -> SourceColorClassification.DisplayP3
                dataSpace == DataSpace.DATASPACE_SCRGB || dataSpace == DataSpace.DATASPACE_SCRGB_LINEAR ->
                    SourceColorClassification.Scrgb

                DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_ST2084 ||
                        DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_HLG ->
                    SourceColorClassification.HdrTransfer

                else -> SourceColorClassification.NominalSdr
            }
        }

        private const val VERTEX_SHADER: String = """
            uniform mat4 uOesMatrix;
            uniform mat4 uImageMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec4 framebufferCoordinate = vec4(aTexCoord.x, 1.0 - aTexCoord.y, 0.0, 1.0);
                vTexCoord = (uOesMatrix * uImageMatrix * framebufferCoordinate).xy;
            }
        """

        private const val FRAGMENT_EXTENSION: String = "#extension GL_OES_EGL_image_external : require\n"
        private const val FRAGMENT_BODY: String = """
            uniform samplerExternalOES uTexture;
            uniform float uColorAction;
            uniform float uGrayscale;
            varying vec2 vTexCoord;
            float decodeSrgb(float component) {
                return component <= 0.04045
                    ? component / 12.92
                    : pow((component + 0.055) / 1.055, 2.4);
            }
            float encodeSrgb(float component) {
                return component <= 0.0031308
                    ? 12.92 * component
                    : 1.055 * pow(component, 1.0 / 2.4) - 0.055;
            }
            void main() {
                vec4 sampled = texture2D(uTexture, vTexCoord);
                vec3 color = clamp(sampled.rgb, 0.0, 1.0);
                if (uColorAction > 0.5) {
                    float red = decodeSrgb(color.r);
                    float green = decodeSrgb(color.g);
                    float blue = decodeSrgb(color.b);
                    float linearRed = 1.2247452668 * red - 0.2249043652 * green;
                    float linearGreen = -0.0420579309 * red + 1.0420810013 * green;
                    float linearBlue = -0.0196422806 * red - 0.0786549180 * green + 1.0985371988 * blue;
                    color = vec3(
                        encodeSrgb(clamp(linearRed, 0.0, 1.0)),
                        encodeSrgb(clamp(linearGreen, 0.0, 1.0)),
                        encodeSrgb(clamp(linearBlue, 0.0, 1.0))
                    );
                }
                vec3 rgb8 = min(vec3(255.0), max(vec3(0.0), floor(255.0 * color + vec3(0.5))));
                if (uGrayscale > 0.5) {
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
