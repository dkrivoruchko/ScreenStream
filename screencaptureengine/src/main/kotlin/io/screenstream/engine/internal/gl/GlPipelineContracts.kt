package io.screenstream.engine.internal.gl

import android.opengl.GLES20
import io.screenstream.engine.ColorMode
import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.TargetFrameQuiescedFact
import io.screenstream.engine.internal.target.TargetIdentity
import io.screenstream.engine.internal.target.TargetRetainedGlAdmittedFact
import io.screenstream.engine.internal.target.TargetRetainedGlEntryResult
import io.screenstream.engine.internal.target.TargetRetainedGlInertFact
import io.screenstream.engine.internal.target.TargetRetainedGlInertReason
import io.screenstream.engine.internal.target.TargetRetainedGlReservedFact
import io.screenstream.engine.internal.target.TargetRetainedGlSettlementResult
import io.screenstream.engine.internal.target.TargetRetainedGlSettledFact
import java.util.concurrent.atomic.AtomicBoolean

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

internal class GlRenderCurrentnessFact internal constructor(
    internal val renderTargetOwner: GlPipelineOwner.GlRenderTargetOwner,
    internal val renderGeneration: Long,
    internal val compatibilityFacts: GlRenderTargetCompatibilityFacts,
    internal val actualState: GlFrameActualState?,
    internal val contextIntegrity: ContextIntegrity,
    internal val pipelineComplete: Boolean,
    internal val destructionClaimed: Boolean,
    internal val lanePoisoned: Boolean,
    internal val observedFatal: Throwable?,
    internal val version: Long,
    internal val versionExhausted: Boolean,
    internal val reusable: Boolean,
) {
    init {
        require(renderGeneration > 0L)
        require(version > 0L)
        check(renderTargetOwner.renderGeneration == renderGeneration)
        check(renderTargetOwner.compatibilityFacts === compatibilityFacts)
        check(!reusable || !versionExhausted && pipelineComplete && !destructionClaimed &&
                contextIntegrity == ContextIntegrity.Intact && !lanePoisoned && observedFatal == null)
    }
}

/**
 * Immutable CPU-only frame state selected by reconciliation. The transform storage is privately owned so a
 * retained render target can replace the whole state reference under [GlPipelineOwner.glGate].
 */
internal class GlFrameDesiredState internal constructor(
    logicalInverseTransform: FloatArray,
    internal val colorMode: ColorMode,
) {
    private val ownedLogicalInverseTransform: FloatArray = logicalInverseTransform.copyOf()

    init {
        require(ownedLogicalInverseTransform.size == TRANSFORM_ELEMENT_COUNT)
        require(ownedLogicalInverseTransform.all(Float::isFinite))
    }

    internal fun copyLogicalInverseTransformTo(destination: FloatArray): Boolean {
        if (destination.size != TRANSFORM_ELEMENT_COUNT) return false
        ownedLogicalInverseTransform.copyInto(destination)
        return true
    }

    private companion object {
        private const val TRANSFORM_ELEMENT_COUNT: Int = 16
    }
}

/** Exact mechanically installed retained-frame CPU state. It grants no Session currentness or reopen authority. */
internal class GlFrameActualState private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val retainedReconfigurationIdentity: Long,
    internal val renderTargetOwner: GlPipelineOwner.GlRenderTargetOwner,
    internal val renderGeneration: Long,
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val reconciliationFacts: GlFrameDesiredState,
) {
    internal val colorActions: GlColorActionSet = GlColorActionSet(targetGeneration)

    init {
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(retainedReconfigurationIdentity > 0L)
        require(renderGeneration > 0L)
        require(targetGeneration > 0L)
        check(renderTargetOwner.renderGeneration == renderGeneration)
        check(targetIdentity.generation == targetGeneration)
        check(quiescedFact.targetIdentity === targetIdentity)
        check(quiescedFact.targetGeneration == targetGeneration)
        check(quiescedFact.sealedFact.targetIdentity === targetIdentity)
        check(quiescedFact.sealedFact.targetGeneration == targetGeneration)
        check(quiescedFact.originRetainedReconfigurationIdentity ==
                quiescedFact.sealedFact.originRetainedReconfigurationIdentity)
    }

    internal companion object {
        internal fun create(command: GlFrameReconciliationApplyCommand): GlFrameActualState =
            GlFrameActualState(
                desiredRevision = command.desiredRevision,
                geometryGeneration = command.geometryGeneration,
                lifecycleEpoch = command.lifecycleEpoch,
                retainedReconfigurationIdentity = command.retainedReconfigurationIdentity,
                renderTargetOwner = command.renderTargetOwner,
                renderGeneration = command.renderGeneration,
                targetIdentity = command.targetIdentity,
                targetGeneration = command.targetGeneration,
                quiescedFact = command.quiescedFact,
                reconciliationFacts = command.reconciliationFacts,
            )
    }
}

internal enum class GlFrameReconciliationRejectionReason {
    RenderTargetOwnerMismatch,
    RenderGenerationMismatch,
    TargetIdentityMismatch,
    TargetGenerationMismatch,
    QuiescenceMismatch,
    PipelineClosed,
    RepeatedCommand,
}

internal sealed interface GlFrameReconciliationResult {
    internal class Applied internal constructor(
        internal val actualState: GlFrameActualState,
    ) : GlFrameReconciliationResult

    internal class Rejected internal constructor(
        internal val reason: GlFrameReconciliationRejectionReason,
    ) : GlFrameReconciliationResult
}

private class GlFrameReconciliationInertMapping(
    val targetFact: TargetRetainedGlInertFact,
    val result: GlFrameReconciliationResult.Rejected,
)

internal class GlFrameReconciliationInvocationCell internal constructor() {
    @Volatile
    internal var targetEntry: TargetRetainedGlEntryResult? = null
        private set
    @Volatile
    internal var applyResult: GlFrameReconciliationResult? = null
        private set
    @Volatile
    internal var applyThrowable: Throwable? = null
        private set
    @Volatile
    internal var settlementReturn: TargetRetainedGlSettlementResult? = null
        private set
    @Volatile
    internal var settlementThrowable: Throwable? = null
        private set
    @Volatile
    internal var claimedDurableSettledFact: TargetRetainedGlSettledFact? = null
        private set
    @Volatile
    internal var propagation: Throwable? = null
        private set
    @Volatile
    internal var settledFactClaimed: Boolean = false
        private set
    @Volatile
    internal var propagationRecorded: Boolean = false
        private set

    internal fun recordTargetEntry(entry: TargetRetainedGlEntryResult) {
        targetEntry = entry
    }

    internal fun recordApplyResult(result: GlFrameReconciliationResult) {
        applyResult = result
    }

    internal fun recordApplyThrowable(raw: Throwable) {
        applyThrowable = raw
    }

    internal fun recordSettlementReturn(result: TargetRetainedGlSettlementResult) {
        settlementReturn = result
    }

    internal fun recordSettlementThrowable(raw: Throwable) {
        settlementThrowable = raw
    }

    internal fun recordClaimedDurableSettledFact(fact: TargetRetainedGlSettledFact?) {
        claimedDurableSettledFact = fact
        settledFactClaimed = true
    }

    internal fun recordPropagation(raw: Throwable?) {
        propagation = raw
        propagationRecorded = true
    }
}

/**
 * Fully materialized retained-frame mutation. All candidate outputs are created by construction before the
 * command can cross [GlPipelineOwner.glGate]. The copied topology fields are echo-only identity fences.
 */
internal class GlFrameReconciliationApplyCommand internal constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val retainedReconfigurationIdentity: Long,
    internal val renderTargetOwner: GlPipelineOwner.GlRenderTargetOwner,
    internal val renderGeneration: Long,
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val targetReservation: TargetRetainedGlReservedFact,
    internal val reconciliationFacts: GlFrameDesiredState,
) {
    private val invoked: AtomicBoolean = AtomicBoolean(false)
    internal val invocationCell: GlFrameReconciliationInvocationCell = GlFrameReconciliationInvocationCell()
    private val settlementRejectedInvariant: IllegalStateException =
        IllegalStateException("Target retained GL settlement returned without a durable settled fact")
    private val missingApplyOutcomeInvariant: IllegalStateException =
        IllegalStateException("GL retained reconciliation produced no apply outcome")
    private val inertMappingInvariant: IllegalStateException =
        IllegalStateException("Target retained GL entry returned a noncanonical inert fact")
    private val candidateActualState: GlFrameActualState = GlFrameActualState.create(this)
    private val applied: GlFrameReconciliationResult.Applied =
        GlFrameReconciliationResult.Applied(candidateActualState)
    private val renderTargetOwnerRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.RenderTargetOwnerMismatch)
    private val renderGenerationRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.RenderGenerationMismatch)
    private val targetIdentityRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.TargetIdentityMismatch)
    private val targetGenerationRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.TargetGenerationMismatch)
    private val quiescenceRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.QuiescenceMismatch)
    private val pipelineClosedRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.PipelineClosed)
    private val repeatedCommandRejected: GlFrameReconciliationResult.Rejected =
        GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.RepeatedCommand)
    private val staleQuiescenceInertMapping: GlFrameReconciliationInertMapping = inertMapping(
        targetReservation.reservation.inertFact(TargetRetainedGlInertReason.StaleQuiescence),
    )
    private val retirementClosedInertMapping: GlFrameReconciliationInertMapping = inertMapping(
        targetReservation.reservation.inertFact(TargetRetainedGlInertReason.RetirementClosed),
    )
    private val reservationAlreadyPresentInertMapping: GlFrameReconciliationInertMapping = inertMapping(
        targetReservation.reservation.inertFact(TargetRetainedGlInertReason.ReservationAlreadyPresent),
    )
    private val repeatedEntryInertMapping: GlFrameReconciliationInertMapping = inertMapping(
        targetReservation.reservation.inertFact(TargetRetainedGlInertReason.RepeatedEntry),
    )

    init {
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(retainedReconfigurationIdentity > 0L)
        require(renderGeneration > 0L)
        require(targetGeneration > 0L)
        check(renderTargetOwner.renderGeneration == renderGeneration)
        check(targetIdentity.generation == targetGeneration)
        check(quiescedFact.targetIdentity === targetIdentity)
        check(quiescedFact.targetGeneration == targetGeneration)
        check(quiescedFact.sealedFact.targetIdentity === targetIdentity)
        check(quiescedFact.sealedFact.targetGeneration == targetGeneration)
        check(quiescedFact.originRetainedReconfigurationIdentity ==
                quiescedFact.sealedFact.originRetainedReconfigurationIdentity)
        check(targetReservation.reservation.targetIdentity === targetIdentity)
        check(targetReservation.reservation.targetGeneration == targetGeneration)
        check(targetReservation.reservation.quiescedFact === quiescedFact)
        check(targetReservation.reservation.retainedReconfigurationIdentity == retainedReconfigurationIdentity)
    }

    internal fun invoke(owner: GlPipelineOwner): GlFrameReconciliationResult {
        if (!invoked.compareAndSet(false, true)) return repeatedCommandRejected
        val entry = try {
            targetReservation.reservation.enter()
        } catch (raw: Throwable) {
            return propagate(raw)
        }
        invocationCell.recordTargetEntry(entry)
        val admitted = when (entry) {
            is TargetRetainedGlInertFact -> {
                invocationCell.recordPropagation(null)
                return inertResult(entry)
            }

            is TargetRetainedGlAdmittedFact -> entry
        }
        try {
            invocationCell.recordApplyResult(owner.applyAdmittedFrameReconciliation(this, admitted))
        } catch (raw: Throwable) {
            invocationCell.recordApplyThrowable(raw)
        }
        try {
            val settlementReturn = targetReservation.reservation.settle(admitted)
            invocationCell.recordSettlementReturn(settlementReturn)
            invocationCell.recordClaimedDurableSettledFact(settlementReturn.claimSettledFact())
        } catch (raw: Throwable) {
            invocationCell.recordSettlementThrowable(raw)
        }
        val settlementCandidate = invocationCell.settlementThrowable ?: when {
            invocationCell.settledFactClaimed && invocationCell.claimedDurableSettledFact == null ->
                settlementRejectedInvariant
            else -> null
        }
        val selectedRaw = selectPropagation(invocationCell.applyThrowable, settlementCandidate)
        if (selectedRaw != null) return propagate(selectedRaw)
        val result = invocationCell.applyResult ?: return propagate(missingApplyOutcomeInvariant)
        invocationCell.recordPropagation(null)
        return result
    }

    private fun selectPropagation(applyRaw: Throwable?, settlementRaw: Throwable?): Throwable? = when {
        applyRaw != null && FatalThrowablePolicy.isDirectFatal(applyRaw) -> applyRaw
        settlementRaw != null && FatalThrowablePolicy.isDirectFatal(settlementRaw) -> settlementRaw
        applyRaw != null -> applyRaw
        else -> settlementRaw
    }

    private fun propagate(raw: Throwable): Nothing {
        invocationCell.recordPropagation(raw)
        FatalThrowablePolicy.rethrow(raw)
    }

    internal fun appliedResult(): GlFrameReconciliationResult.Applied = applied

    internal fun rejectedResult(
        reason: GlFrameReconciliationRejectionReason,
    ): GlFrameReconciliationResult.Rejected = when (reason) {
        GlFrameReconciliationRejectionReason.RenderTargetOwnerMismatch -> renderTargetOwnerRejected
        GlFrameReconciliationRejectionReason.RenderGenerationMismatch -> renderGenerationRejected
        GlFrameReconciliationRejectionReason.TargetIdentityMismatch -> targetIdentityRejected
        GlFrameReconciliationRejectionReason.TargetGenerationMismatch -> targetGenerationRejected
        GlFrameReconciliationRejectionReason.QuiescenceMismatch -> quiescenceRejected
        GlFrameReconciliationRejectionReason.PipelineClosed -> pipelineClosedRejected
        GlFrameReconciliationRejectionReason.RepeatedCommand -> repeatedCommandRejected
    }

    private fun inertMapping(inertFact: TargetRetainedGlInertFact): GlFrameReconciliationInertMapping =
        GlFrameReconciliationInertMapping(
            inertFact,
            GlFrameReconciliationResult.Rejected(GlFrameReconciliationRejectionReason.QuiescenceMismatch),
        )

    private fun inertResult(inertFact: TargetRetainedGlInertFact): GlFrameReconciliationResult.Rejected {
        val mapping = when (inertFact.reason) {
            TargetRetainedGlInertReason.StaleQuiescence -> staleQuiescenceInertMapping
            TargetRetainedGlInertReason.RetirementClosed -> retirementClosedInertMapping
            TargetRetainedGlInertReason.ReservationAlreadyPresent -> reservationAlreadyPresentInertMapping
            TargetRetainedGlInertReason.RepeatedEntry -> repeatedEntryInertMapping
        }
        if (mapping.targetFact !== inertFact) propagate(inertMappingInvariant)
        return mapping.result
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
    TargetAdmissionRejected,
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
