package io.screenstream.engine.internal.gl

import android.opengl.GLES20

/**
 * Owns the one Session capability query and its preallocated query storage.
 *
 * This is a physical GL collaborator: it can report immutable capability facts, but it has no authority over
 * Session admission, Target selection, render-owner currentness, fallback, or lifecycle.
 */
internal class GlCapabilityOwner internal constructor(
    private val authority: GlPipelineOwner,
) {
    private val maxTextureSize: IntArray = IntArray(1)
    private val maxViewportDimensions: IntArray = IntArray(2)
    private val highFloatRange: IntArray = IntArray(2)
    private val highFloatPrecision: IntArray = IntArray(1)
    private val candidate: GlCapabilityFacts = GlCapabilityFacts()

    @Volatile
    private var installed: GlCapabilityFacts? = null
    private var stagedPrecision: GlFragmentPrecision? = null

    internal val facts: GlCapabilityFacts?
        get() = installed

    internal fun query(evidence: GlOperationEvidence): Boolean {
        if (installed != null) return false
        stagedPrecision = null

        val clean = authority.glesGroup(evidence) {
            authority.outward {
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            }
            authority.outward {
                GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDimensions, 0)
            }
            authority.outward {
                GLES20.glGetShaderPrecisionFormat(
                    GLES20.GL_FRAGMENT_SHADER,
                    GLES20.GL_HIGH_FLOAT,
                    highFloatRange,
                    0,
                    highFloatPrecision,
                    0,
                )
            }

            val selected = when {
                highFloatRange[0] > 0 && highFloatRange[1] > 0 && highFloatPrecision[0] > 0 ->
                    GlFragmentPrecision.Highp

                highFloatRange[0] == 0 && highFloatRange[1] == 0 && highFloatPrecision[0] == 0 ->
                    GlFragmentPrecision.Mediump

                else -> return@glesGroup false
            }
            if (maxTextureSize[0] <= 0 ||
                maxViewportDimensions[0] <= 0 ||
                maxViewportDimensions[1] <= 0
            ) {
                return@glesGroup false
            }
            stagedPrecision = selected
            true
        }

        if (!clean) {
            stagedPrecision = null
            return false
        }

        authority.checkFatalFence()
        val precision = checkNotNull(stagedPrecision)
        check(
            candidate.freeze(
                maxTextureSize = maxTextureSize[0],
                maxViewportWidth = maxViewportDimensions[0],
                maxViewportHeight = maxViewportDimensions[1],
                rangeLow = highFloatRange[0],
                rangeHigh = highFloatRange[1],
                precisionBits = highFloatPrecision[0],
                selectedPrecision = precision,
            )
        )
        installed = candidate
        stagedPrecision = null
        return true
    }
}
