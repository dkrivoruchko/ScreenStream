package io.screenstream.engine.internal.gl

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.isHandedOff
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

internal class GlRenderTargetState private constructor(
    internal val owner: GlPipelineOwner.GlRenderTargetOwner,
    internal val targetGeneration: Long,
    reconciliationFacts: GlFrameReconciliationFacts,
) {
    internal var textureName: Int = 0
    internal var framebufferName: Int = 0
    internal val logicalInverseTransform: FloatArray = FloatArray(16).also {
        check(reconciliationFacts.copyLogicalInverseTransformTo(it))
    }
    private val srgbAction: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Srgb, reconciliationFacts.colorMode)
    private val displayP3Action: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.DisplayP3, reconciliationFacts.colorMode)
    private val scrgbAction: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.Scrgb, reconciliationFacts.colorMode)
    private val hdrAction: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.HdrTransfer, reconciliationFacts.colorMode)
    private val nominalAction: GlColorActionFacts =
        GlColorActionFacts(targetGeneration, GlColorClassification.NominalSdr, reconciliationFacts.colorMode)
    internal var lastColorAction: GlColorActionFacts? = null
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

    internal fun consumeNamespaceLocked(owner: GlPipelineOwner): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        owner.checkFatalLocked()
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
        return handle
    }

    internal fun actionFor(dataSpace: Int): GlColorActionFacts = when {
        dataSpace == DataSpace.DATASPACE_SRGB -> srgbAction
        dataSpace == DataSpace.DATASPACE_DISPLAY_P3 -> displayP3Action
        dataSpace == DataSpace.DATASPACE_SCRGB || dataSpace == DataSpace.DATASPACE_SCRGB_LINEAR -> scrgbAction
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_ST2084 ||
                        DataSpace.getTransfer(dataSpace) == DataSpace.TRANSFER_HLG) -> hdrAction

        else -> nominalAction
    }

    internal companion object {
        internal fun create(
            owner: GlPipelineOwner.GlRenderTargetOwner,
            targetGeneration: Long,
            reconciliationFacts: GlFrameReconciliationFacts,
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
    private val occurrence: OperationOccurrence<GlOperationEvidence> = owner.operationOccurrence(identity, evidence, ownerBag)
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
