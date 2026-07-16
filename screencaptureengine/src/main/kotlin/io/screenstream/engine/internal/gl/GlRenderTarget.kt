package io.screenstream.engine.internal.gl

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import java.util.concurrent.ScheduledExecutorService
import kotlin.concurrent.withLock

internal class GlRenderTargetOwnerImpl internal constructor(
    override val renderGeneration: Long,
    override val compatibilityFacts: GlRenderTargetCompatibilityFacts,
) : GlPipelineOwner.GlRenderTargetOwner {
    init {
        require(renderGeneration > 0L)
    }
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
    private var renderReservation: GlLaneTicket? = null
    private var destructionHandle: GlDestructionHandle? = null
    private var destructionClaimed: Boolean = false

    internal fun bindRenderReservation(owner: GlPipelineOwner, ticket: GlLaneTicket) {
        check(owner.glGate.isHeldByCurrentThread)
        owner.checkFatalLocked()
        check(renderReservation == null)
        renderReservation = ticket
    }

    internal fun releaseRenderReservation(owner: GlPipelineOwner): Boolean = owner.glGate.withLock {
        owner.checkFatalLocked()
        val ticket = renderReservation ?: return@withLock false
        if (!owner.laneRuntime.releaseRenderReservationLocked(ticket)) return@withLock false
        renderReservation = null
        true
    }

    internal fun inverseUnenteredReservation(owner: GlPipelineOwner, ticket: GlLaneTicket): Boolean = owner.glGate.withLock {
        if (renderReservation !== ticket || textureName != 0 || framebufferName != 0 ||
            owner.installedRenderTarget === this || owner.contextRenderTarget !== this ||
            !owner.laneRuntime.canInverseUnenteredRenderLocked(ticket)
        ) {
            return@withLock false
        }
        renderReservation = null
        owner.contextRenderTarget = null
        true
    }

    internal fun consumeNamespaceLocked(owner: GlPipelineOwner): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        owner.checkFatalLocked()
        if (!canConsumeNamespaceLocked(owner)) return false
        val ticket = renderReservation
        if (ticket != null) {
            check(owner.laneRuntime.consumeRenderReservationAfterNamespaceLocked(ticket))
        }
        renderReservation = null
        framebufferName = 0
        textureName = 0
        return true
    }

    internal fun canConsumeNamespaceLocked(owner: GlPipelineOwner): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        val ticket = renderReservation ?: return true
        return owner.laneRuntime.canConsumeRenderReservationAfterNamespaceLocked(ticket)
    }

    internal fun bindDestructionHandle(handle: GlDestructionHandle) {
        check(destructionHandle == null)
        destructionHandle = handle
    }

    internal fun claimDestruction(owner: GlPipelineOwner, installed: Boolean): GlDestructionHandle? {
        check(owner.glGate.isHeldByCurrentThread)
        if (destructionClaimed) return null
        val handle = destructionHandle ?: return null
        if (!owner.laneRuntime.issueLocked(handle.laneTicket)) return null
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
    identity: GlFiniteOperationIdentity,
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
    private val runnable: Runnable = owner.fatalBoundary { execute() }
    private val laneTicket: GlLaneTicket = GlLaneTicket()
    private var cleanupEligible: Boolean = false

    private fun execute() {
        if (!owner.enter(laneTicket, occurrence)) return
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
        owner.finishLaneReturn(laneTicket, cleanupDependent = true)
    }

    override fun submit(): Boolean = owner.laneRuntime.submissionBoundary {
        val submitted = try {
            owner.submit(laneTicket, occurrence, runnable)
        } catch (error: Error) {
            try {
                state.inverseUnenteredReservation(owner, laneTicket)
            } catch (_: Throwable) {
                // The original fatal remains authoritative.
            }
            throw error
        }
        if (!submitted) state.inverseUnenteredReservation(owner, laneTicket)
        submitted
    }

    internal fun admitLane(): Boolean = owner.glGate.withLock {
        if (owner.contextRenderTarget != null || !owner.laneRuntime.issueLocked(laneTicket, reserveRender = true)) {
            return@withLock false
        }
        state.bindRenderReservation(owner, laneTicket)
        owner.contextRenderTarget = state
        true
    }

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = occurrence.submitRequestedDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = occurrence.performRequestedDeadlineCancellation()

    override fun claim(): GlClaimedOperationFacts? {
        val facts = owner.claimOperation(occurrence, claimedFacts)
        owner.retireMechanicallyCompleteLane(laneTicket, occurrence)
        if (facts == null) {
            val mechanicallyReturned = occurrence.settlementGate.withLock {
                occurrence.returnCell.disposition != OperationReturnDisposition.Empty
            }
            cleanupEligible = mechanicallyReturned && owner.glGate.withLock { owner.installedRenderTarget } !== state &&
                    (state.textureName != 0 || state.framebufferName != 0)
            if (!cleanupEligible) state.inverseUnenteredReservation(owner, laneTicket)
            return null
        }
        if (facts.timely && facts.result == GlOperationResult.Success &&
            facts.receipt === evidence.receipt && facts.returnedOwner === candidate &&
            facts.contextIntegrity == ContextIntegrity.Intact
        ) {
            owner.checkFatalFence()
            val installed = owner.glGate.withLock {
                owner.checkFatalFence()
                if (owner.installedRenderTarget != null || state.textureName == 0 || state.framebufferName == 0) {
                    false
                } else {
                    owner.installedRenderTarget = state
                    true
                }
            }
            if (!installed) {
                cleanupEligible = true
                return null
            }
            cleanupEligible = false
            check(state.releaseRenderReservation(owner))
        } else {
            cleanupEligible = true
        }
        return facts
    }

    override fun prepareCleanupDestruction(): GlPipelineOwner.DestructionCommand? = owner.glGate.withLock {
        if (!cleanupEligible || owner.installedRenderTarget === state) {
            return@withLock null
        }
        state.claimDestruction(owner, installed = false)
    }
}
