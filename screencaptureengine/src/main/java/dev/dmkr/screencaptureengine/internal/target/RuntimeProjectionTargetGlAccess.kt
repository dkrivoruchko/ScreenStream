package dev.dmkr.screencaptureengine.internal.target

import android.opengl.GLES11Ext
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeFrameSignalSink
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle

/**
 * Runtime-only owner-mediated access to the current projection target.
 *
 * Installing a frame sink only wires `SurfaceTexture` callbacks into an enqueue-only scheduler
 * signal; it must not consume a frame. [withCurrentRuntimeProjectionTarget] is the scoped GL-thread
 * boundary where the target owner, generation, identity, current context, `updateTexImage`, OES
 * matrix, and timestamp are trusted for one runtime production attempt.
 */
internal interface RuntimeProjectionTargetGlAccess {
    suspend fun installRuntimeFrameSignalSink(
        target: ProjectionTargetHandle,
        generation: Long,
        sink: RuntimeFrameSignalSink,
    )

    suspend fun clearRuntimeFrameSignalSink(
        target: ProjectionTargetHandle,
        generation: Long,
    )

    suspend fun <T> withCurrentRuntimeProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit = {},
        block: RuntimeProjectionTargetGlScope.() -> T,
    ): T
}

internal interface RuntimeProjectionTargetGlScope {
    val gl: GlLaneScope
    val generation: Long
    val width: Int
    val height: Int
    val densityDpi: Int
    val projectionTargetIdentity: RuntimeProjectionTargetIdentity
    val externalOesTexture: RuntimeExternalOesTexture

    fun updateTexImage()

    fun getTransformMatrix(destination: FloatArray)

    fun timestampNanos(): Long
}

internal class RuntimeProjectionTargetOwnerIdentity internal constructor()

internal class RuntimeProjectionTargetInstanceIdentity internal constructor()

internal data class RuntimeExternalOesTexture(
    val textureId: Int,
    val textureTarget: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
) {
    init {
        require(textureId > 0) { "textureId must be nonzero." }
        require(textureTarget == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            "Runtime projection texture must be external OES."
        }
    }
}

internal data class RuntimeProjectionTargetIdentity(
    internal val ownerIdentity: RuntimeProjectionTargetOwnerIdentity,
    internal val targetIdentity: RuntimeProjectionTargetInstanceIdentity,
    internal val generation: Long,
    internal val externalOesTexture: RuntimeExternalOesTexture,
) {
    init {
        require(generation > 0L) { "generation must be positive." }
    }
}

internal class RuntimeProjectionTextureFrame internal constructor(
    internal val projectionTargetIdentity: RuntimeProjectionTargetIdentity,
    internal val transformMatrix: FloatArray,
    internal val timestampNanos: Long,
) {
    internal val projectionTargetGeneration: Long = projectionTargetIdentity.generation
    internal val externalOesTexture: RuntimeExternalOesTexture = projectionTargetIdentity.externalOesTexture

    init {
        require(transformMatrix.size == MATRIX_4_VALUE_COUNT) { "Runtime OES matrix must contain 16 values." }
        require(transformMatrix.all(Float::isFinite)) { "Runtime OES matrix values must be finite." }
    }
}

internal fun RuntimeProjectionTargetGlScope.consumeLatestFrame(): RuntimeProjectionTextureFrame {
    val matrix = FloatArray(MATRIX_4_VALUE_COUNT)
    return consumeLatestFrame(matrix)
}

internal fun RuntimeProjectionTargetGlScope.consumeLatestFrame(matrixScratch: FloatArray): RuntimeProjectionTextureFrame {
    require(matrixScratch.size == MATRIX_4_VALUE_COUNT) { "Runtime OES matrix scratch must contain 16 values." }
    updateTexImage()
    getTransformMatrix(matrixScratch)
    return RuntimeProjectionTextureFrame(
        projectionTargetIdentity = projectionTargetIdentity,
        transformMatrix = matrixScratch,
        timestampNanos = timestampNanos(),
    )
}

private const val MATRIX_4_VALUE_COUNT: Int = 16
