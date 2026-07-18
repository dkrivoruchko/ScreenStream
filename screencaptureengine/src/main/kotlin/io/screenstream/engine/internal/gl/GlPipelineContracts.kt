package io.screenstream.engine.internal.gl

import android.opengl.GLES20
import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner

internal enum class GlFragmentPrecision {
    Highp,
    Mediump,
}

internal class GlFragmentPrecisionFacts internal constructor() {
    internal var rangeLow: Int = 0
        private set
    internal var rangeHigh: Int = 0
        private set
    internal var precisionBits: Int = 0
        private set
    internal lateinit var selectedPrecision: GlFragmentPrecision
        private set
    private var frozen: Boolean = false

    internal fun freeze(rangeLow: Int, rangeHigh: Int, precisionBits: Int, selectedPrecision: GlFragmentPrecision): Boolean {
        if (frozen) return false
        val allZero: Boolean = rangeLow == 0 && rangeHigh == 0 && precisionBits == 0
        val allPositive: Boolean = rangeLow > 0 && rangeHigh > 0 && precisionBits > 0

        if (!allZero && !allPositive ||
            allZero && selectedPrecision != GlFragmentPrecision.Mediump ||
            allPositive && selectedPrecision != GlFragmentPrecision.Highp
        ) {
            return false
        }
        this.rangeLow = rangeLow
        this.rangeHigh = rangeHigh
        this.precisionBits = precisionBits
        this.selectedPrecision = selectedPrecision
        frozen = true
        return true
    }
}

internal class GlCapabilityFacts internal constructor() {
    internal var maxTextureSize: Int = 0
        private set
    internal var maxViewportWidth: Int = 0
        private set
    internal var maxViewportHeight: Int = 0
        private set
    internal val fragmentPrecision: GlFragmentPrecisionFacts = GlFragmentPrecisionFacts()
    private var frozen: Boolean = false

    internal fun freeze(
        maxTextureSize: Int,
        maxViewportWidth: Int,
        maxViewportHeight: Int,
        rangeLow: Int,
        rangeHigh: Int,
        precisionBits: Int,
        selectedPrecision: GlFragmentPrecision,
    ): Boolean {
        if (frozen || maxTextureSize <= 0 || maxViewportWidth <= 0 || maxViewportHeight <= 0 ||
            !fragmentPrecision.freeze(rangeLow, rangeHigh, precisionBits, selectedPrecision)
        ) {
            return false
        }
        this.maxTextureSize = maxTextureSize
        this.maxViewportWidth = maxViewportWidth
        this.maxViewportHeight = maxViewportHeight
        frozen = true
        return true
    }
}

internal class GlRenderTargetCompatibilityFacts internal constructor(
    internal val imageSize: ImageSize,
    internal val rgbaByteCount: Int,
) {
    init {
        val pixelCount: Long = imageSize.widthPx.toLong() * imageSize.heightPx.toLong()

        require(pixelCount in 1L..Int.MAX_VALUE.toLong() / 4L)
        require(4L * pixelCount == rgbaByteCount.toLong())
    }
}

internal class GlFiniteOperationIdentity internal constructor(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val initialWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(operationIdentity > 0L)
        require(deadlineIdentity > 0L)
        require(initialWakeGeneration > 0L)
    }
}

internal enum class GlOperationKind {
    SessionConstruction,
    TargetConstruction,
    RenderTargetConstruction,
    Frame,
}

internal enum class GlDestructionKind {
    RenderTarget,
    Program,
    TargetScope,
    Session,
    ContextNamespace,
}

internal enum class ContextIntegrity {
    Intact,
    PoisonedByOutOfMemory,
    Unknown,
}

internal enum class GlOperationResult {
    Success,
    ResourceExhausted,
    InternalFailure,
}

internal class GlOperationSuccessReceipt internal constructor(
    internal val operationIdentity: Long,
    internal val operationKind: GlOperationKind,
) : OperationReceipt {
    internal val result: GlOperationResult = GlOperationResult.Success

    init {
        require(operationIdentity > 0L)
    }
}

internal class GlOperationEvidence internal constructor(
    internal val operationIdentity: Long,
    internal val operationKind: GlOperationKind,
) : OperationEvidence {
    override val receipt: GlOperationSuccessReceipt = GlOperationSuccessReceipt(operationIdentity, operationKind)

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var result: GlOperationResult? = null
    internal var throwable: Throwable? = null
    internal var preprobeErrorCode: Int = GLES20.GL_NO_ERROR
    internal var preprobeErrorCodePresent: Boolean = false
    internal var postprobeErrorCode: Int = GLES20.GL_NO_ERROR
    internal var postprobeErrorCodePresent: Boolean = false
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown

    init {
        require(operationIdentity > 0L)
    }
}

internal class GlDestructionSuccessReceipt internal constructor(
    internal val operationIdentity: Long,
    internal val destructionKind: GlDestructionKind,
) : OperationReceipt {
    init {
        require(operationIdentity > 0L)
    }
}

internal class GlDestructionEvidence internal constructor(
    internal val operationIdentity: Long,
    internal val destructionKind: GlDestructionKind,
) : OperationEvidence {
    override val receipt: GlDestructionSuccessReceipt = GlDestructionSuccessReceipt(operationIdentity, destructionKind)

    override val returnedOwner: OperationReturnedOwner? = null

    internal var result: GlOperationResult? = null
    internal var throwable: Throwable? = null
    internal var preprobeErrorCode: Int = GLES20.GL_NO_ERROR
    internal var preprobeErrorCodePresent: Boolean = false
    internal var postprobeErrorCode: Int = GLES20.GL_NO_ERROR
    internal var postprobeErrorCodePresent: Boolean = false
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown

    init {
        require(operationIdentity > 0L)
    }
}
