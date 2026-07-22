package io.screenstream.engine.internal.gl

import android.annotation.TargetApi
import android.hardware.DataSpace
import android.os.Build
import io.screenstream.engine.ColorMode
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.isHandedOff
import io.screenstream.engine.internal.target.TargetRetainedGlAdmittedFact
import kotlin.concurrent.withLock

internal class GlRenderTargetOwnerImpl internal constructor(
    override val renderGeneration: Long,
    override val compatibilityFacts: GlRenderTargetCompatibilityFacts,
) : GlPipelineOwner.GlRenderTargetOwner {
    init {
        require(renderGeneration > 0L)
    }
}

internal class GlRenderTargetConstructionClaim private constructor(
    private val expectedFacts: GlClaimedOperationFacts,
    private val expectedCandidate: GlPipelineOwner.GlRenderTargetOwner,
) {
    internal val facts: GlClaimedOperationFacts
        get() = expectedFacts

    internal val candidate: GlPipelineOwner.GlRenderTargetOwner
        get() = expectedCandidate

    private var frozen: Boolean = false

    private fun freeze(
        facts: GlClaimedOperationFacts,
        candidate: GlPipelineOwner.GlRenderTargetOwner,
    ): GlRenderTargetConstructionClaim? {
        if (frozen || facts !== expectedFacts || candidate !== expectedCandidate) return null
        frozen = true
        return this
    }

    internal fun matches(
        facts: GlClaimedOperationFacts,
        candidate: GlPipelineOwner.GlRenderTargetOwner,
    ): Boolean = frozen && facts === expectedFacts && candidate === expectedCandidate

    internal companion object {
        internal fun precreate(
            facts: GlClaimedOperationFacts,
            candidate: GlPipelineOwner.GlRenderTargetOwner,
        ): GlRenderTargetConstructionClaim = GlRenderTargetConstructionClaim(facts, candidate)

        internal fun freeze(
            claim: GlRenderTargetConstructionClaim,
            facts: GlClaimedOperationFacts,
            candidate: GlPipelineOwner.GlRenderTargetOwner,
        ): GlRenderTargetConstructionClaim? = claim.freeze(facts, candidate)
    }
}

internal class GlRenderTargetCleanupClaim private constructor(
    internal val constructionClaim: GlRenderTargetConstructionClaim,
) {
    internal var destructionCommand: GlPipelineOwner.DestructionCommand? = null
        private set

    private var frozen: Boolean = false

    private fun freeze(
        constructionClaim: GlRenderTargetConstructionClaim,
        destructionCommand: GlPipelineOwner.DestructionCommand?,
    ): GlRenderTargetCleanupClaim? {
        if (frozen || constructionClaim !== this.constructionClaim) return null
        this.destructionCommand = destructionCommand
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(
            constructionClaim: GlRenderTargetConstructionClaim,
        ): GlRenderTargetCleanupClaim = GlRenderTargetCleanupClaim(constructionClaim)

        internal fun freeze(
            claim: GlRenderTargetCleanupClaim,
            constructionClaim: GlRenderTargetConstructionClaim,
            destructionCommand: GlPipelineOwner.DestructionCommand?,
        ): GlRenderTargetCleanupClaim? = claim.freeze(constructionClaim, destructionCommand)
    }
}

private enum class GlRenderTargetConstructionDisposition {
    Unclaimed,
    Installed,
    CleanupClaimed,
}

internal class GlColorActionSet internal constructor(
    targetGeneration: Long,
) {
    private val srgbColor: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Srgb, ColorMode.Color)
    private val srgbGrayscale: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Srgb, ColorMode.Grayscale)
    private val displayP3Color: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.DisplayP3, ColorMode.Color)
    private val displayP3Grayscale: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.DisplayP3, ColorMode.Grayscale)
    private val scrgbColor: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Scrgb, ColorMode.Color)
    private val scrgbGrayscale: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Scrgb, ColorMode.Grayscale)
    private val hdrColor: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.HdrTransfer, ColorMode.Color)
    private val hdrGrayscale: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.HdrTransfer, ColorMode.Grayscale)
    private val nominalColor: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.NominalSdr, ColorMode.Color)
    private val nominalGrayscale: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.NominalSdr, ColorMode.Grayscale)

    internal fun actionFor(dataSpace: Int, colorMode: ColorMode): GlColorActionFacts {
        val classification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33DataSpace.classify(dataSpace)
        } else {
            GlColorClassification.NominalSdr
        }
        return when (classification) {
            GlColorClassification.Srgb -> when (colorMode) {
                ColorMode.Color -> srgbColor
                ColorMode.Grayscale -> srgbGrayscale
            }

            GlColorClassification.DisplayP3 -> when (colorMode) {
                ColorMode.Color -> displayP3Color
                ColorMode.Grayscale -> displayP3Grayscale
            }

            GlColorClassification.Scrgb -> when (colorMode) {
                ColorMode.Color -> scrgbColor
                ColorMode.Grayscale -> scrgbGrayscale
            }

            GlColorClassification.HdrTransfer -> when (colorMode) {
                ColorMode.Color -> hdrColor
                ColorMode.Grayscale -> hdrGrayscale
            }

            GlColorClassification.NominalSdr -> when (colorMode) {
                ColorMode.Color -> nominalColor
                ColorMode.Grayscale -> nominalGrayscale
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33DataSpace {
        fun classify(dataSpace: Int): GlColorClassification = when {
            dataSpace == DataSpace.DATASPACE_SRGB -> GlColorClassification.Srgb
            dataSpace == DataSpace.DATASPACE_DISPLAY_P3 -> GlColorClassification.DisplayP3
            dataSpace == DataSpace.DATASPACE_SCRGB || dataSpace == DataSpace.DATASPACE_SCRGB_LINEAR ->
                GlColorClassification.Scrgb

            DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_ST2084 ||
                    DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_HLG ->
                GlColorClassification.HdrTransfer

            else -> GlColorClassification.NominalSdr
        }
    }
}

internal class GlRenderTargetState private constructor(
    internal val owner: GlPipelineOwner.GlRenderTargetOwner,
    internal val targetGeneration: Long,
    reconciliationFacts: GlFrameDesiredState,
) {
    internal var textureName: Int = 0
    internal var framebufferName: Int = 0
    internal val bootstrapDesiredState: GlFrameDesiredState = reconciliationFacts
    internal val bootstrapColorActions: GlColorActionSet = GlColorActionSet(targetGeneration)
    internal var lastColorAction: GlColorActionFacts? = null
    private var exactActualState: GlFrameActualState? = null
    private var destructionHandle: GlDestructionHandle? = null
    private var destructionClaimed: Boolean = false

    internal fun inverseUnenteredOwnership(owner: GlPipelineOwner): Boolean = owner.glGate.withLock {
        if (textureName != 0 || framebufferName != 0 ||
            owner.installedRenderTarget === this || owner.contextRenderTarget !== this
        ) {
            return@withLock false
        }
        owner.contextRenderTarget = null
        true
    }

    internal fun consumeNamespaceLocked(
        owner: GlPipelineOwner,
        authorizedPhysicalRetirement: Boolean = false,
    ): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        if (!authorizedPhysicalRetirement) owner.checkFatalLocked()
        if (!canConsumeNamespaceLocked(owner)) return false
        framebufferName = 0
        textureName = 0
        return true
    }

    internal fun canConsumeNamespaceLocked(owner: GlPipelineOwner): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        return true
    }

    internal fun bindDestructionHandle(handle: GlDestructionHandle) {
        check(destructionHandle == null)
        destructionHandle = handle
    }

    internal fun claimDestruction(owner: GlPipelineOwner, installed: Boolean): GlDestructionHandle? {
        check(owner.glGate.isHeldByCurrentThread)
        if (destructionClaimed) return null
        val handle = destructionHandle ?: return null
        handle.installedRenderTargetDestruction = installed
        destructionClaimed = true
        owner.commitRenderCurrentnessMutationLocked()
        return handle
    }

    internal fun destructionClaimedLocked(owner: GlPipelineOwner): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        return destructionClaimed
    }

    internal fun actualStateLocked(owner: GlPipelineOwner): GlFrameActualState? {
        check(owner.glGate.isHeldByCurrentThread)
        return exactActualState
    }

    internal fun applyFrameReconciliationLocked(
        owner: GlPipelineOwner,
        command: GlFrameReconciliationApplyCommand,
        admittedFact: TargetRetainedGlAdmittedFact,
    ): GlFrameReconciliationResult {
        check(owner.glGate.isHeldByCurrentThread)
        val reservation = command.targetReservation.reservation
        val structuralRejection = when {
            this.owner.renderGeneration != command.renderGeneration ||
                    command.renderTargetOwner.renderGeneration != command.renderGeneration ->
                GlFrameReconciliationRejectionReason.RenderGenerationMismatch

            this.owner !== command.renderTargetOwner ->
                GlFrameReconciliationRejectionReason.RenderTargetOwnerMismatch

            admittedFact.reservation !== reservation ||
                    reservation.reservedFact !== command.targetReservation ||
                    reservation.admittedFact !== admittedFact ->
                GlFrameReconciliationRejectionReason.QuiescenceMismatch

            command.quiescedFact.targetIdentity !== command.targetIdentity ||
                    command.quiescedFact.sealedFact.targetIdentity !== command.targetIdentity ||
                    reservation.targetIdentity !== command.targetIdentity ||
                    reservation.quiescedFact !== command.quiescedFact ->
                GlFrameReconciliationRejectionReason.TargetIdentityMismatch

            command.targetIdentity.generation != command.targetGeneration ||
                    command.quiescedFact.targetGeneration != command.targetGeneration ||
                    command.quiescedFact.sealedFact.targetGeneration != command.targetGeneration ||
                    reservation.targetGeneration != command.targetGeneration ->
                GlFrameReconciliationRejectionReason.TargetGenerationMismatch

            command.quiescedFact.originRetainedReconfigurationIdentity !=
                    command.quiescedFact.sealedFact.originRetainedReconfigurationIdentity ||
                    command.quiescedFact.sealedEpoch != command.quiescedFact.sealedFact.sealedEpoch ||
                    reservation.retainedReconfigurationIdentity != command.retainedReconfigurationIdentity ->
                GlFrameReconciliationRejectionReason.QuiescenceMismatch

            else -> null
        }
        val pipelineClosed = owner.frameReconciliationPipelineClosedLocked(this)
        val endpointPoisoned = owner.frameReconciliationEndpointPoisonedLocked()
        val rejection = structuralRejection ?: if (pipelineClosed || endpointPoisoned) {
            GlFrameReconciliationRejectionReason.PipelineClosed
        } else {
            null
        }
        if (rejection != null) return command.rejectedResult(rejection)

        val applied = command.appliedResult()
        exactActualState = applied.actualState
        lastColorAction = null
        owner.commitRenderCurrentnessMutationLocked()
        return applied
    }

    internal companion object {
        internal fun create(
            owner: GlPipelineOwner.GlRenderTargetOwner,
            targetGeneration: Long,
            reconciliationFacts: GlFrameDesiredState,
        ): GlRenderTargetState =
            GlRenderTargetState(owner, targetGeneration, reconciliationFacts)
    }
}

internal class GlRenderTargetConstructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val identity: GlFiniteOperationIdentity,
    private val candidate: GlPipelineOwner.GlRenderTargetOwner,
    private val state: GlRenderTargetState,
) : GlPipelineOwner.RenderTargetConstructionCommand {
    private val evidence: GlOperationEvidence = GlOperationEvidence(
        identity.operationIdentity,
        GlOperationKind.RenderTargetConstruction
    ).apply {
        returnedOwner = candidate
    }
    private val ownerBag: GlOwnerBag = GlOwnerBag(candidate)
    override val occurrence: OperationOccurrence<GlOperationEvidence> = owner.operationOccurrence(identity, evidence, ownerBag)
    private val claimedFacts: GlClaimedOperationFacts = GlClaimedOperationFacts.precreate(evidence)
    private val constructionClaim: GlRenderTargetConstructionClaim =
        GlRenderTargetConstructionClaim.precreate(claimedFacts, candidate)
    private val cleanupClaim: GlRenderTargetCleanupClaim =
        GlRenderTargetCleanupClaim.precreate(constructionClaim)
    private val endpointOperation = owner.endpointOperation(occurrence, Runnable { execute() })
    private var disposition: GlRenderTargetConstructionDisposition =
        GlRenderTargetConstructionDisposition.Unclaimed

    private fun execute() {
        try {
            if (owner.constructRenderTarget(state, evidence)) {
                owner.checkFatalFence()
                evidence.result = GlOperationResult.Success
            }
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            occurrence.publishNormalReturn()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            owner.markContextUnknown()
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = ContextIntegrity.Unknown
            occurrence.publishThrownReturn(exception)
        }
    }

    override fun submit(): Boolean {
        val result = owner.submit(endpointOperation)
        if (result == PrivateExecutorSubmissionResult.NotSubmitted) {
            state.inverseUnenteredOwnership(owner)
        }
        return result.isHandedOff
    }

    internal fun claimContextOwnership(): Boolean = owner.glGate.withLock {
        if (owner.contextRenderTarget != null) {
            return@withLock false
        }
        owner.contextRenderTarget = state
        true
    }

    override val deadlineWakeLink: ControlWakeLink = checkNotNull(occurrence.controlWakeLink)

    override fun claim(): GlRenderTargetConstructionClaim? {
        val facts = owner.claimOperation(occurrence, claimedFacts)
        owner.releaseSettledOperation(endpointOperation)
        if (facts == null) {
            val safelyUnentered = occurrence.settlementGate.withLock {
                occurrence.entryDisposition != OperationEntryDisposition.Entered &&
                        (occurrence.disposition == OperationDisposition.SchedulerRejected ||
                                occurrence.disposition == OperationDisposition.DeadlineGuardFailed ||
                                occurrence.disposition == OperationDisposition.Cancelled)
            }
            if (safelyUnentered) state.inverseUnenteredOwnership(owner)
            return null
        }
        return GlRenderTargetConstructionClaim.freeze(constructionClaim, facts, candidate)
    }

    override fun commitInstallation(
        claim: GlRenderTargetConstructionClaim,
    ): GlPipelineOwner.GlRenderTargetOwner? {
        owner.checkFatalFence()
        return owner.glGate.withLock {
            owner.checkFatalFence()
            if (disposition != GlRenderTargetConstructionDisposition.Unclaimed ||
                !isExactClaim(claim, requireSuccessfulReceipt = true) ||
                owner.contextRenderTarget !== state || owner.installedRenderTarget != null ||
                state.textureName == 0 || state.framebufferName == 0
            ) {
                return@withLock null
            }
            owner.installedRenderTarget = state
            owner.commitRenderCurrentnessMutationLocked()
            disposition = GlRenderTargetConstructionDisposition.Installed
            candidate
        }
    }

    override fun claimCleanupDestruction(
        claim: GlRenderTargetConstructionClaim,
    ): GlRenderTargetCleanupClaim? = owner.glGate.withLock {
        owner.checkFatalLocked()
        if (disposition != GlRenderTargetConstructionDisposition.Unclaimed ||
            !isExactClaim(claim, requireSuccessfulReceipt = false) ||
            owner.installedRenderTarget === state || owner.contextRenderTarget !== state
        ) {
            return@withLock null
        }
        val destructionCommand = if (state.textureName == 0 && state.framebufferName == 0) {
            owner.contextRenderTarget = null
            null
        } else {
            state.claimDestruction(owner, installed = false) ?: return@withLock null
        }
        disposition = GlRenderTargetConstructionDisposition.CleanupClaimed
        GlRenderTargetCleanupClaim.freeze(cleanupClaim, claim, destructionCommand)
    }

    private fun isExactClaim(
        claim: GlRenderTargetConstructionClaim,
        requireSuccessfulReceipt: Boolean,
    ): Boolean {
        val deadline = occurrence.deadlineOccurrence ?: return false
        val facts = claim.facts
        val receiptMatches = facts.receipt == null || facts.receipt === evidence.receipt
        val successfulReceipt = facts.timely && facts.result == GlOperationResult.Success &&
                facts.receipt === evidence.receipt && facts.contextIntegrity == ContextIntegrity.Intact
        return claim === constructionClaim && claim.matches(claimedFacts, candidate) &&
                facts === claimedFacts && facts.operationIdentity == occurrence.identity &&
                facts.operationKind == GlOperationKind.RenderTargetConstruction &&
                occurrence.identity == identity.operationIdentity &&
                deadline.identity == identity.deadlineIdentity &&
                deadline.boundOccurrenceIdentity == identity.operationIdentity &&
                deadline.controlWakeLink === deadlineWakeLink &&
                deadline.timeoutCause === identity.timeoutCause &&
                occurrence.returnCell.evidence === evidence && evidence.operationIdentity == occurrence.identity &&
                evidence.operationKind == GlOperationKind.RenderTargetConstruction &&
                evidence.receipt.operationIdentity == occurrence.identity &&
                evidence.receipt.operationKind == GlOperationKind.RenderTargetConstruction &&
                state.owner === candidate && state.targetGeneration > 0L &&
                evidence.returnedOwner === candidate && ownerBag.returnedOwner === candidate &&
                facts.returnedOwner === candidate && receiptMatches &&
                (!requireSuccessfulReceipt || successfulReceipt)
    }
}
