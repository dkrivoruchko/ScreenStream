package io.screenstream.engine.internal.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.TargetRetirement
import io.screenstream.engine.internal.target.TargetSurfaceReleaseReceipt
import java.util.concurrent.ScheduledExecutorService
import kotlin.concurrent.withLock

internal class GlOwnerBag internal constructor(
    internal var returnedOwner: OperationReturnedOwner? = null,
) : OperationOwnerBag

internal class GlClaimedOperationFacts private constructor(
    internal val operationIdentity: Long,
    internal val operationKind: GlOperationKind,
) {
    internal lateinit var result: GlOperationResult
        private set
    internal var receipt: GlOperationSuccessReceipt? = null
        private set
    internal var returnedOwner: OperationReturnedOwner? = null
        private set
    internal var throwable: Throwable? = null
        private set
    internal var preprobeErrorCode: Int = 0
        private set
    internal var preprobeErrorCodePresent: Boolean = false
        private set
    internal var postprobeErrorCode: Int = 0
        private set
    internal var postprobeErrorCodePresent: Boolean = false
        private set
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
        private set
    internal var colorActionFacts: GlColorActionFacts? = null
        private set
    internal var stateProbeFacts: GlProbeFacts? = null
        private set
    internal var drawReadProbeFacts: GlProbeFacts? = null
        private set
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    private fun freeze(
        evidence: GlOperationEvidence,
        normal: Boolean,
        timely: Boolean,
        colorActionFacts: GlColorActionFacts?,
        stateProbeFacts: GlProbeFacts?,
        drawReadProbeFacts: GlProbeFacts?,
    ): GlClaimedOperationFacts? {
        if (frozen) return null
        val exactResult = evidence.result ?: return null
        val successful = timely && normal && exactResult == GlOperationResult.Success && evidence.contextIntegrity == ContextIntegrity.Intact
        result = exactResult
        receipt = if (successful) evidence.receipt else null
        returnedOwner = evidence.returnedOwner
        throwable = evidence.throwable
        preprobeErrorCode = evidence.preprobeErrorCode
        preprobeErrorCodePresent = evidence.preprobeErrorCodePresent
        postprobeErrorCode = evidence.postprobeErrorCode
        postprobeErrorCodePresent = evidence.postprobeErrorCodePresent
        contextIntegrity = evidence.contextIntegrity
        this.colorActionFacts = colorActionFacts
        this.stateProbeFacts = stateProbeFacts
        this.drawReadProbeFacts = drawReadProbeFacts
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(evidence: GlOperationEvidence): GlClaimedOperationFacts =
            GlClaimedOperationFacts(evidence.operationIdentity, evidence.operationKind)

        internal fun freeze(
            facts: GlClaimedOperationFacts,
            evidence: GlOperationEvidence,
            normal: Boolean,
            timely: Boolean,
            colorActionFacts: GlColorActionFacts? = null,
            stateProbeFacts: GlProbeFacts? = null,
            drawReadProbeFacts: GlProbeFacts? = null,
        ): GlClaimedOperationFacts? =
            facts.freeze(evidence, normal, timely, colorActionFacts, stateProbeFacts, drawReadProbeFacts)
    }
}

internal class GlClaimedDestructionFacts private constructor(
    internal val operationIdentity: Long,
    internal val destructionKind: GlDestructionKind,
) {
    internal lateinit var result: GlOperationResult
        private set
    internal var receipt: GlDestructionSuccessReceipt? = null
        private set
    internal var throwable: Throwable? = null
        private set
    internal var preprobeErrorCode: Int = 0
        private set
    internal var preprobeErrorCodePresent: Boolean = false
        private set
    internal var postprobeErrorCode: Int = 0
        private set
    internal var postprobeErrorCodePresent: Boolean = false
        private set
    internal var contextIntegrity: ContextIntegrity = ContextIntegrity.Unknown
        private set
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    private fun freeze(evidence: GlDestructionEvidence, normal: Boolean, timely: Boolean): GlClaimedDestructionFacts? {
        if (frozen) return null
        val exactResult = evidence.result ?: return null
        result = exactResult
        receipt = if (normal && exactResult == GlOperationResult.Success &&
            (evidence.contextIntegrity == ContextIntegrity.Intact || evidence.destructionKind == GlDestructionKind.ContextNamespace)
        ) {
            evidence.receipt
        } else {
            null
        }
        throwable = evidence.throwable
        preprobeErrorCode = evidence.preprobeErrorCode
        preprobeErrorCodePresent = evidence.preprobeErrorCodePresent
        postprobeErrorCode = evidence.postprobeErrorCode
        postprobeErrorCodePresent = evidence.postprobeErrorCodePresent
        contextIntegrity = evidence.contextIntegrity
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(evidence: GlDestructionEvidence): GlClaimedDestructionFacts =
            GlClaimedDestructionFacts(evidence.operationIdentity, evidence.destructionKind)

        internal fun freeze(facts: GlClaimedDestructionFacts, evidence: GlDestructionEvidence, normal: Boolean, timely: Boolean): GlClaimedDestructionFacts? =
            facts.freeze(evidence, normal, timely)
    }
}

internal class TargetSurfaceReleaseClaim private constructor(
    internal val receipt: TargetSurfaceReleaseReceipt,
) {
    internal var timely: Boolean = false
        private set
    private var frozen: Boolean = false

    private fun freeze(timely: Boolean): TargetSurfaceReleaseClaim? {
        if (frozen) return null
        this.timely = timely
        frozen = true
        return this
    }

    internal companion object {
        internal fun precreate(receipt: TargetSurfaceReleaseReceipt): TargetSurfaceReleaseClaim = TargetSurfaceReleaseClaim(receipt)

        internal fun freeze(claim: TargetSurfaceReleaseClaim, timely: Boolean): TargetSurfaceReleaseClaim? = claim.freeze(timely)
    }
}

internal enum class GlDestructionAction {
    RenderTarget,
    Program,
    Session,
}

internal class GlDestructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    identity: GlFiniteOperationIdentity,
    private val action: GlDestructionAction,
    private val renderTarget: GlPipelineOwner.GlRenderTargetOwner?,
    private val renderState: GlRenderTargetState?,
    internal var installedRenderTargetDestruction: Boolean,
) : GlPipelineOwner.DestructionCommand {
    private val kind: GlDestructionKind = when (action) {
        GlDestructionAction.RenderTarget -> GlDestructionKind.RenderTarget
        GlDestructionAction.Program -> GlDestructionKind.Program
        GlDestructionAction.Session -> GlDestructionKind.Session
    }
    private val evidence: GlDestructionEvidence = GlDestructionEvidence(identity.operationIdentity, kind)
    private val occurrence: OperationOccurrence<GlDestructionEvidence> =
        owner.destructionOccurrence(identity = identity, evidence = evidence, ownerBag = GlOwnerBag(renderTarget))
    private val claimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(evidence)
    private val runnable: Runnable = owner.fatalBoundary { execute() }
    internal val laneTicket: GlLaneTicket = GlLaneTicket()

    private fun execute() {
        if (!owner.enter(laneTicket, occurrence)) return
        try {
            val success = when (action) {
                GlDestructionAction.RenderTarget -> owner.destroyRenderTarget(checkNotNull(renderState), installedRenderTargetDestruction, evidence)
                GlDestructionAction.Program -> owner.destroyProgram(evidence)
                GlDestructionAction.Session -> owner.destroyHealthySession(laneTicket, evidence)
            }
            owner.checkFatalFence()
            if (success) evidence.result = GlOperationResult.Success
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
        owner.finishLaneReturn(laneTicket)
    }

    override fun submit(): Boolean = owner.submit(laneTicket, occurrence, runnable)

    internal fun admitLane(): Boolean = owner.laneRuntime.issue(laneTicket)

    internal fun admitHealthyLaneLocked(): Boolean {
        check(owner.glGate.isHeldByCurrentThread)
        return owner.laneRuntime.issueTeardownLocked(laneTicket, allowDormantRender = false)
    }

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = occurrence.submitRequestedDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = occurrence.performRequestedDeadlineCancellation()

    override fun claim(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(occurrence, claimedFacts)
        owner.retireMechanicallyCompleteLane(laneTicket, occurrence)
        if (facts == null) return null
        if (action == GlDestructionAction.RenderTarget && installedRenderTargetDestruction &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt
        ) {
            owner.checkFatalFence()
            val exactState = checkNotNull(renderState)
            val cleared = owner.glGate.withLock {
                owner.checkFatalFence()
                if (owner.installedRenderTarget !== exactState) {
                    false
                } else {
                    owner.installedRenderTarget = null
                    true
                }
            }
            if (!cleared) return null
        }
        if (action == GlDestructionAction.RenderTarget && !installedRenderTargetDestruction &&
            facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt
        ) {
            val exactState = checkNotNull(renderState)
            if (!exactState.releaseRenderReservation(owner)) return null
        }
        if (action == GlDestructionAction.Session && facts.result == GlOperationResult.Success && facts.receipt === evidence.receipt) {
            check(owner.completeTeardownReservation(laneTicket))
        }
        return facts
    }
}

internal class GlTargetScopeDestructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val graph: TargetRetirement.TargetScopeDestructionGraph,
) : GlPipelineOwner.TargetScopeDestructionCommand {
    private val evidence: GlDestructionEvidence = graph.targetEvidence
    private val occurrence: OperationOccurrence<GlDestructionEvidence> = graph.targetOccurrence
    private val namespaceEvidence: GlDestructionEvidence = graph.namespaceEvidence
    private val namespaceOccurrence: OperationOccurrence<GlDestructionEvidence> = graph.namespaceOccurrence
    private val claimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(evidence)
    private val namespaceClaimedFacts: GlClaimedDestructionFacts = GlClaimedDestructionFacts.precreate(namespaceEvidence)
    private val runnable: Runnable = owner.fatalBoundary { executeTargetScope() }
    private val namespaceRunnable: Runnable = owner.fatalBoundary { executeNamespace() }
    private val targetLaneTicket: GlLaneTicket = GlLaneTicket()
    private val namespaceLaneTicket: GlLaneTicket = GlLaneTicket()
    private var namespaceAdmitted: Boolean = false
    private var namespaceConsumed: Boolean = false
    private var namespaceSuffixSucceeded: Boolean = false

    private fun executeTargetScope() {
        if (!owner.enter(targetLaneTicket, occurrence)) return
        var oesPhaseEntered = false
        var remainingOesName = 0
        try {
            owner.checkFatalFence()
            val released = graph.withHandles { surfaceTexture, oesTextureName ->
                if (surfaceTexture != null) {
                    owner.outward { surfaceTexture.release() }
                    owner.checkFatalFence()
                    graph.recordSurfaceTextureRelease()
                }
                if (oesTextureName != 0) {
                    remainingOesName = oesTextureName
                    if (owner.contextIntegrity == ContextIntegrity.Intact) {
                        oesPhaseEntered = true
                        val deleted = owner.glesGroup(evidence) {
                            owner.outward { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0) }
                            owner.generatedNames[0] = oesTextureName
                            owner.outward { GLES20.glDeleteTextures(1, owner.generatedNames, 0) }
                            true
                        }
                        if (deleted) {
                            owner.checkFatalFence()
                            graph.recordTargetReceipt()
                        } else if (owner.contextIntegrity != ContextIntegrity.Intact) {
                            owner.checkFatalFence()
                            graph.recordNamespaceTransfer()
                            owner.checkFatalFence()
                            check(owner.bindContextNamespace(graph))
                        }
                    } else {
                        owner.checkFatalFence()
                        graph.recordNamespaceTransfer()
                        owner.checkFatalFence()
                        check(owner.bindContextNamespace(graph))
                    }
                }
            }
            if (!released) {
                owner.checkFatalFence()
                evidence.result = GlOperationResult.InternalFailure
                evidence.contextIntegrity = owner.contextIntegrity
            } else if (graph.isTargetReceiptSelected()) {
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
            } else if (graph.isNamespaceTransferSelected()) {
                evidence.result =
                    if (owner.contextIntegrity == ContextIntegrity.PoisonedByOutOfMemory) {
                        GlOperationResult.ResourceExhausted
                    } else {
                        GlOperationResult.InternalFailure
                    }
                evidence.contextIntegrity = owner.contextIntegrity
            } else {
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
                graph.recordTargetReceipt()
            }
            owner.checkFatalFence()
            graph.freeze()
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            occurrence.publishNormalReturn()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            if (oesPhaseEntered && remainingOesName != 0 && owner.contextIntegrity != ContextIntegrity.Intact &&
                !graph.isTargetReceiptSelected() && !graph.isNamespaceTransferSelected()
            ) {
                graph.recordNamespaceTransfer()
                owner.checkFatalFence()
                check(owner.bindContextNamespace(graph))
            }
            evidence.throwable = exception
            evidence.result = GlOperationResult.InternalFailure
            evidence.contextIntegrity = owner.contextIntegrity
            owner.checkFatalFence()
            graph.freeze()
            owner.checkFatalFence()
            occurrence.publishThrownReturn(exception)
        }
        owner.finishLaneReturn(targetLaneTicket)
    }

    private fun executeNamespace() {
        if (!owner.enter(namespaceLaneTicket, namespaceOccurrence)) return
        try {
            if (owner.unbindAndDestroyContext(namespaceLaneTicket, namespaceEvidence)) {
                owner.checkFatalFence()
                namespaceEvidence.result = GlOperationResult.Success
                namespaceEvidence.contextIntegrity = owner.contextIntegrity
            }
            owner.checkFatalFence()
            owner.closeNormalResult(namespaceEvidence)
            namespaceOccurrence.publishNormalReturn()
            owner.checkFatalFence()
            owner.signalSettlement()
        } catch (exception: Exception) {
            owner.checkFatalFence()
            owner.markContextUnknown()
            namespaceEvidence.throwable = exception
            namespaceEvidence.result = GlOperationResult.InternalFailure
            namespaceEvidence.contextIntegrity = ContextIntegrity.Unknown
            namespaceOccurrence.publishThrownReturn(exception)
            owner.checkFatalFence()
            owner.signalSettlement()
        }
        val suffixSucceeded = if (owner.contextUnbound) {
            owner.destroyPbufferAndReleaseThread()
        } else {
            owner.recordUnreachableSuffixResidue()
            false
        }
        owner.finishLaneReturn(namespaceLaneTicket, signal = false)
        if (suffixSucceeded) {
            recordNamespaceSuffixSuccess()
        } else {
            owner.signalSettlement()
        }
    }

    override fun submit(): Boolean = owner.submit(targetLaneTicket, occurrence, runnable)

    internal fun admitLane(): Boolean = owner.laneRuntime.issue(targetLaneTicket)

    override fun claim(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(occurrence, claimedFacts)
        owner.retireMechanicallyCompleteLane(targetLaneTicket, occurrence)
        if (facts == null) return null
        owner.checkFatalFence()
        val namespaceSelected = graph.isNamespaceTransferSelected()
        val namespaceIssued =
            if (namespaceSelected) {
                owner.issueTeardown(namespaceLaneTicket, allowDormantRender = true)
            } else {
                owner.laneRuntime.issue(namespaceLaneTicket)
            }
        if (!namespaceIssued) return null
        if (!graph.applyTargetProjection()) {
            if (!namespaceSelected) {
                owner.laneRuntime.cancelWithoutExecutorCall(namespaceLaneTicket)
            }
            return null
        }
        namespaceAdmitted = graph.isNamespaceTransferSelected()
        if (!namespaceAdmitted) owner.laneRuntime.cancelWithoutExecutorCall(namespaceLaneTicket)
        return facts
    }

    override fun submitNamespaceRetirement(): Boolean =
        namespaceAdmitted && owner.submit(namespaceLaneTicket, namespaceOccurrence, namespaceRunnable)

    override fun claimNamespaceRetirement(): GlClaimedDestructionFacts? {
        val facts = owner.claimDestruction(namespaceOccurrence, namespaceClaimedFacts)
        owner.retireMechanicallyCompleteLane(namespaceLaneTicket, namespaceOccurrence)
        if (facts == null) return null
        owner.checkFatalFence()
        if (!namespaceAdmitted || !graph.applyNamespaceProjection()) return null
        if (!recordNamespaceConsumed()) return null
        return facts
    }

    private fun recordNamespaceConsumed(): Boolean {
        val consumed = owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!namespaceConsumed)
            if (namespaceSuffixSucceeded && !owner.ownsTeardownReservationLocked(namespaceLaneTicket)) {
                return@withLock false
            }
            if (!owner.consumeContextNamespaceLocked(graph)) return@withLock false
            namespaceConsumed = true
            if (namespaceSuffixSucceeded) {
                check(owner.completeTeardownReservationLocked(namespaceLaneTicket))
            }
            true
        }
        owner.signalSettlement()
        return consumed
    }

    private fun recordNamespaceSuffixSuccess() {
        owner.glGate.withLock {
            owner.checkFatalLocked()
            check(!namespaceSuffixSucceeded)
            if (namespaceConsumed && !owner.ownsTeardownReservationLocked(namespaceLaneTicket)) {
                return@withLock
            }
            namespaceSuffixSucceeded = true
            if (namespaceConsumed) {
                check(owner.completeTeardownReservationLocked(namespaceLaneTicket))
            }
        }
        owner.signalSettlement()
    }

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = occurrence.submitRequestedDeadlineWake(scheduler)

    override fun submitRequestedNamespaceDeadlineWake(scheduler: ScheduledExecutorService): Boolean = namespaceOccurrence.submitRequestedDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = occurrence.performRequestedDeadlineCancellation()

    override fun performRequestedNamespaceDeadlineCancellation(): Boolean = namespaceOccurrence.performRequestedDeadlineCancellation()
}

internal class GlClaimArbitrator internal constructor(
    private val owner: GlPipelineOwner,
) {
    internal fun claimOperation(
        occurrence: OperationOccurrence<GlOperationEvidence>,
        facts: GlClaimedOperationFacts,
        colorActionFacts: GlColorActionFacts? = null,
        stateProbeFacts: GlProbeFacts? = null,
        drawReadProbeFacts: GlProbeFacts? = null,
    ): GlClaimedOperationFacts? {
        owner.checkFatalFence()
        val arbitration = occurrence.arbitrate()
        owner.checkFatalFence()
        val claimed = when (arbitration) {
            OperationArbitration.TimelyNormal -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = true,
                timely = true,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            OperationArbitration.TimelyThrown -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = false,
                timely = true,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = true,
                timely = false,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            OperationArbitration.ExpiredThrown,
            OperationArbitration.CleanupThrown,
                -> GlClaimedOperationFacts.freeze(
                facts,
                occurrence.returnCell.evidence,
                normal = false,
                timely = false,
                colorActionFacts,
                stateProbeFacts,
                drawReadProbeFacts,
            )

            else -> null
        }
        owner.checkFatalFence()
        return claimed
    }

    internal fun claimDestruction(occurrence: OperationOccurrence<GlDestructionEvidence>, facts: GlClaimedDestructionFacts): GlClaimedDestructionFacts? {
        owner.checkFatalFence()
        val arbitration = occurrence.arbitrate()
        owner.checkFatalFence()
        val claimed = when (arbitration) {
            OperationArbitration.TimelyNormal ->
                GlClaimedDestructionFacts.freeze(facts, occurrence.returnCell.evidence, normal = true, timely = true)

            OperationArbitration.TimelyThrown ->
                GlClaimedDestructionFacts.freeze(facts, occurrence.returnCell.evidence, normal = false, timely = true)

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> GlClaimedDestructionFacts.freeze(facts, occurrence.returnCell.evidence, normal = true, timely = false)

            OperationArbitration.ExpiredThrown,
            OperationArbitration.CleanupThrown,
                -> GlClaimedDestructionFacts.freeze(facts, occurrence.returnCell.evidence, normal = false, timely = false)

            else -> null
        }
        owner.checkFatalFence()
        return claimed
    }
}
