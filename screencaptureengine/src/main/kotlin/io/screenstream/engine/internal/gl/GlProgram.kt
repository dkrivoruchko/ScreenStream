package io.screenstream.engine.internal.gl

import android.opengl.GLES20
import kotlin.concurrent.withLock

internal class GlProgram internal constructor(
    private val owner: GlPipelineOwner,
) {
    internal class State internal constructor() {
        internal var programName: Int = 0
            private set
        internal var oesMatrixLocation: Int = -1
            private set
        internal var imageMatrixLocation: Int = -1
            private set
        internal var colorActionLocation: Int = -1
            private set
        internal var grayscaleLocation: Int = -1
            private set
        internal var samplerLocation: Int = -1
            private set
        private var frozen: Boolean = false

        internal fun freeze(
            programName: Int,
            oesMatrixLocation: Int,
            imageMatrixLocation: Int,
            colorActionLocation: Int,
            grayscaleLocation: Int,
            samplerLocation: Int,
        ): Boolean {
            if (frozen || programName == 0 || oesMatrixLocation < 0 || imageMatrixLocation < 0 ||
                colorActionLocation < 0 || grayscaleLocation < 0 || samplerLocation < 0
            ) {
                return false
            }
            this.programName = programName
            this.oesMatrixLocation = oesMatrixLocation
            this.imageMatrixLocation = imageMatrixLocation
            this.colorActionLocation = colorActionLocation
            this.grayscaleLocation = grayscaleLocation
            this.samplerLocation = samplerLocation
            frozen = true
            return true
        }
    }

    private class Candidate {
        val state: State = State()
        var stateReady: Boolean = false
        var programName: Int = 0
        var oesMatrixLocation: Int = -1
        var imageMatrixLocation: Int = -1
        var colorActionLocation: Int = -1
        var grayscaleLocation: Int = -1
        var samplerLocation: Int = -1
        var vertexShaderDeleted: Boolean = false
        var fragmentShaderDeleted: Boolean = false
        var programDeleted: Boolean = false

        fun clearTransient() {
            stateReady = false
            programName = 0
            oesMatrixLocation = -1
            imageMatrixLocation = -1
            colorActionLocation = -1
            grayscaleLocation = -1
            samplerLocation = -1
            vertexShaderDeleted = false
            fragmentShaderDeleted = false
            programDeleted = false
        }
    }

    private val candidate: Candidate = Candidate()
    private val status: IntArray = IntArray(1)

    internal var current: State? = null
        private set
    internal var partialVertexShaderName: Int = 0
        private set
    internal var partialFragmentShaderName: Int = 0
        private set
    internal var partialProgramName: Int = 0
        private set

    internal val hasOwnedNames: Boolean
        get() = current != null || partialProgramName != 0 || partialVertexShaderName != 0 || partialFragmentShaderName != 0

    internal fun construct(evidence: GlOperationEvidence, precision: GlFragmentPrecision): Boolean {
        candidate.clearTransient()
        val fragmentShaderSource = when (precision) {
            GlFragmentPrecision.Highp -> FRAGMENT_SHADER_HIGHP
            GlFragmentPrecision.Mediump -> FRAGMENT_SHADER_MEDIUMP
        }
        val completion = owner.glesGroupCompletion(evidence) {
            val vertexShader = owner.outwardAdopt(adopt = { shader -> if (shader != 0) partialVertexShaderName = shader }) {
                GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            }
            if (vertexShader == 0) return@glesGroup false
            owner.outward { GLES20.glShaderSource(vertexShader, VERTEX_SHADER) }
            owner.outward { GLES20.glCompileShader(vertexShader) }
            owner.outward { GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, status, 0) }
            if (status[0] != GLES20.GL_TRUE) {
                owner.outward { GLES20.glDeleteShader(vertexShader) }
                candidate.vertexShaderDeleted = true
                return@glesGroup false
            }
            val fragmentShader = owner.outwardAdopt(adopt = { shader -> if (shader != 0) partialFragmentShaderName = shader }) {
                GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            }
            if (fragmentShader == 0) {
                owner.outward { GLES20.glDeleteShader(vertexShader) }
                candidate.vertexShaderDeleted = true
                return@glesGroup false
            }
            owner.outward { GLES20.glShaderSource(fragmentShader, fragmentShaderSource) }
            owner.outward { GLES20.glCompileShader(fragmentShader) }
            owner.outward { GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, status, 0) }
            if (status[0] != GLES20.GL_TRUE) {
                owner.outward { GLES20.glDeleteShader(vertexShader) }
                owner.outward { GLES20.glDeleteShader(fragmentShader) }
                candidate.vertexShaderDeleted = true
                candidate.fragmentShaderDeleted = true
                return@glesGroup false
            }
            val program = owner.outwardAdopt(adopt = { name -> if (name != 0) partialProgramName = name }) {
                GLES20.glCreateProgram()
            }
            if (program == 0) {
                owner.outward { GLES20.glDeleteShader(vertexShader) }
                owner.outward { GLES20.glDeleteShader(fragmentShader) }
                candidate.vertexShaderDeleted = true
                candidate.fragmentShaderDeleted = true
                return@glesGroup false
            }
            owner.outward { GLES20.glAttachShader(program, vertexShader) }
            owner.outward { GLES20.glAttachShader(program, fragmentShader) }
            owner.outward { GLES20.glBindAttribLocation(program, POSITION_ATTRIBUTE, "aPosition") }
            owner.outward { GLES20.glBindAttribLocation(program, TEX_COORD_ATTRIBUTE, "aTexCoord") }
            owner.outward { GLES20.glLinkProgram(program) }
            owner.outward { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0) }
            if (status[0] != GLES20.GL_TRUE) {
                owner.outward { GLES20.glDetachShader(program, vertexShader) }
                owner.outward { GLES20.glDetachShader(program, fragmentShader) }
                owner.outward { GLES20.glDeleteShader(vertexShader) }
                owner.outward { GLES20.glDeleteShader(fragmentShader) }
                owner.outward { GLES20.glDeleteProgram(program) }
                candidate.vertexShaderDeleted = true
                candidate.fragmentShaderDeleted = true
                candidate.programDeleted = true
                return@glesGroup false
            }
            val oesMatrixLocation = owner.outward { GLES20.glGetUniformLocation(program, "uOesMatrix") }
            val imageMatrixLocation = owner.outward { GLES20.glGetUniformLocation(program, "uImageMatrix") }
            val colorActionLocation = owner.outward { GLES20.glGetUniformLocation(program, "uColorAction") }
            val grayscaleLocation = owner.outward { GLES20.glGetUniformLocation(program, "uGrayscale") }
            val samplerLocation = owner.outward { GLES20.glGetUniformLocation(program, "uTexture") }
            owner.outward { GLES20.glDetachShader(program, vertexShader) }
            owner.outward { GLES20.glDetachShader(program, fragmentShader) }
            owner.outward { GLES20.glDeleteShader(vertexShader) }
            owner.outward { GLES20.glDeleteShader(fragmentShader) }
            candidate.vertexShaderDeleted = true
            candidate.fragmentShaderDeleted = true
            if (oesMatrixLocation < 0 || imageMatrixLocation < 0 || colorActionLocation < 0 || grayscaleLocation < 0 || samplerLocation < 0) {
                owner.outward { GLES20.glDeleteProgram(program) }
                candidate.programDeleted = true
                return@glesGroup false
            }
            candidate.programName = program
            candidate.oesMatrixLocation = oesMatrixLocation
            candidate.imageMatrixLocation = imageMatrixLocation
            candidate.colorActionLocation = colorActionLocation
            candidate.grayscaleLocation = grayscaleLocation
            candidate.samplerLocation = samplerLocation
            candidate.stateReady = true
            true
        }
        if (completion.cleanPostprobeObserved) {
            owner.glGate.withLock {
                consumeCleanDeletionsLocked()
            }
        }
        if (!completion.commandsSucceeded) {
            candidate.clearTransient()
            return false
        }
        owner.glGate.withLock {
            owner.checkFatalLocked()
            check(candidate.stateReady)
            check(
                candidate.state.freeze(
                    programName = candidate.programName,
                    oesMatrixLocation = candidate.oesMatrixLocation,
                    imageMatrixLocation = candidate.imageMatrixLocation,
                    colorActionLocation = candidate.colorActionLocation,
                    grayscaleLocation = candidate.grayscaleLocation,
                    samplerLocation = candidate.samplerLocation,
                )
            )
            current = candidate.state
            partialProgramName = 0
        }
        candidate.clearTransient()
        return true
    }

    internal fun destroy(evidence: GlDestructionEvidence): Boolean {
        val state = current
        if (state == null && partialProgramName == 0 && partialVertexShaderName == 0 && partialFragmentShaderName == 0) {
            return false
        }
        val deleted = owner.glesGroup(evidence) {
            if (state != null) {
                owner.outward { GLES20.glUseProgram(0) }
                owner.outward { GLES20.glDeleteProgram(state.programName) }
            }
            if (partialProgramName != 0) {
                owner.outward { GLES20.glDeleteProgram(partialProgramName) }
            }
            if (partialVertexShaderName != 0) {
                owner.outward { GLES20.glDeleteShader(partialVertexShaderName) }
            }
            if (partialFragmentShaderName != 0) {
                owner.outward { GLES20.glDeleteShader(partialFragmentShaderName) }
            }
            true
        }
        if (deleted) {
            owner.glGate.withLock {
                consumeNamespaceLocked()
            }
        }
        return deleted
    }

    internal fun consumeNamespaceLocked(authorizedPhysicalRetirement: Boolean = false) {
        check(owner.glGate.isHeldByCurrentThread)
        if (!authorizedPhysicalRetirement) owner.checkFatalLocked()
        current = null
        partialProgramName = 0
        partialVertexShaderName = 0
        partialFragmentShaderName = 0
    }

    private fun consumeCleanDeletionsLocked() {
        check(owner.glGate.isHeldByCurrentThread)
        owner.checkFatalLocked()
        if (candidate.vertexShaderDeleted) partialVertexShaderName = 0
        if (candidate.fragmentShaderDeleted) partialFragmentShaderName = 0
        if (candidate.programDeleted) partialProgramName = 0
    }

    private companion object {
        private const val POSITION_ATTRIBUTE: Int = 0
        private const val TEX_COORD_ATTRIBUTE: Int = 1

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

        private const val FRAGMENT_SHADER_EXTENSION: String =
            "#extension GL_OES_EGL_image_external : require\n"

        private const val FRAGMENT_SHADER_BODY: String = """
            uniform samplerExternalOES uTexture;
            uniform float uColorAction;
            uniform float uGrayscale;
            varying vec2 vTexCoord;
            float decodeSrgbScalar(float value) {
                if (value <= 0.04045) {
                    return value / 12.92;
                }
                return pow((value + 0.055) / 1.055, 2.4);
            }
            vec3 decodeSrgb(vec3 value) {
                return vec3(
                    decodeSrgbScalar(value.r),
                    decodeSrgbScalar(value.g),
                    decodeSrgbScalar(value.b)
                );
            }
            float encodeSrgbScalar(float value) {
                if (value <= 0.0031308) {
                    return value * 12.92;
                }
                return 1.055 * pow(value, 1.0 / 2.4) - 0.055;
            }
            vec3 encodeSrgb(vec3 value) {
                return vec3(
                    encodeSrgbScalar(value.r),
                    encodeSrgbScalar(value.g),
                    encodeSrgbScalar(value.b)
                );
            }
            void main() {
                vec4 sampled = texture2D(uTexture, vTexCoord);
                vec3 p3Linear = decodeSrgb(clamp(sampled.rgb, 0.0, 1.0));
                vec3 srgbLinear = mat3(
                    1.2247452668, -0.0420579309, -0.0196422806,
                    -0.2249043652, 1.0420810013, -0.0786549180,
                    0.0, 0.0, 1.0985371988
                ) * p3Linear;
                vec3 converted = encodeSrgb(clamp(srgbLinear, 0.0, 1.0));
                vec3 color = mix(sampled.rgb, converted, step(0.5, uColorAction));
                vec3 rgb8 = floor(clamp(color, 0.0, 1.0) * 255.0 + 0.5);
                float gray8 = floor(0.30078125 * rgb8.r + 0.5859375 * rgb8.g + 0.11328125 * rgb8.b + 0.5);
                float gray = gray8 / 255.0;
                color = mix(color, vec3(gray), step(0.5, uGrayscale));
                gl_FragColor = vec4(color, 1.0);
            }
        """

        private const val FRAGMENT_SHADER_HIGHP: String = "${FRAGMENT_SHADER_EXTENSION}precision highp float;$FRAGMENT_SHADER_BODY"
        private const val FRAGMENT_SHADER_MEDIUMP: String = "${FRAGMENT_SHADER_EXTENSION}precision mediump float;$FRAGMENT_SHADER_BODY"
    }
}
