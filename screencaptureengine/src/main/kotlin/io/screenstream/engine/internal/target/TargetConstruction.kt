package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.TargetQuarantineChild
import io.screenstream.engine.internal.gl.ContextIntegrity
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlOperationEvidence
import io.screenstream.engine.internal.gl.GlOperationKind
import io.screenstream.engine.internal.gl.GlOperationResult
import io.screenstream.engine.internal.gl.glEnteredOperationSafetyNanos
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class TargetConstructionStage {
    Empty,
    OesOwned,
    SurfaceTextureOwned,
    BufferSizeApplied,
    SurfaceOwned,
}

private enum class TargetConstructionAdmission {
    Prospective,
    Admitted,
}

internal class TargetConstructionEvidence private constructor(
    operationIdentity: Long,
) : OperationEvidence {
    internal val glEvidence: GlOperationEvidence =
        GlOperationEvidence(operationIdentity, GlOperationKind.TargetConstruction)

    override val receipt = glEvidence.receipt
    override val returnedOwner: OperationReturnedOwner? = null

    var stage: TargetConstructionStage = TargetConstructionStage.Empty
    var oesTextureName: Int = 0
    var surfaceTexture: SurfaceTexture? = null
    var surface: Surface? = null

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            operationIdentity: Long,
        ): TargetConstructionEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionEvidence(operationIdentity)
        }
    }
}

private class TargetConstructionOwnerBag(
    var candidate: CurrentTarget?,
    val evidence: TargetConstructionEvidence,
) : OperationOwnerBag

internal class PreparedTargetQuarantineEvidence private constructor(
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val targetGeneration: Long,
    internal val owner: PreparedTarget,
    internal val constructionOccurrence: OperationOccurrence<TargetConstructionEvidence>,
    internal val cleanupSuffix: TargetRetirementSuffixEvidence?,
) {
    init {
        require(targetGeneration > 0L)
        check(owner.targetGeneration == targetGeneration)
        check(owner.requestedIdentity === requestedIdentity)
        check(owner.constructionOccurrence === constructionOccurrence)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetGeneration: Long,
            owner: PreparedTarget,
            constructionOccurrence: OperationOccurrence<TargetConstructionEvidence>,
            cleanupSuffix: TargetRetirementSuffixEvidence?,
        ): PreparedTargetQuarantineEvidence {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return PreparedTargetQuarantineEvidence(
                requestedIdentity,
                targetGeneration,
                owner,
                constructionOccurrence,
                cleanupSuffix,
            )
        }
    }
}

internal class PreparedTargetRetiredFact private constructor(
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val targetGeneration: Long,
    internal val retiredTarget: PreparedTarget,
) {
    init {
        require(targetGeneration > 0L)
        check(retiredTarget.requestedIdentity === requestedIdentity)
        check(retiredTarget.targetGeneration == targetGeneration)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetGeneration: Long,
            retiredTarget: PreparedTarget,
        ): PreparedTargetRetiredFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return PreparedTargetRetiredFact(requestedIdentity, targetGeneration, retiredTarget)
        }
    }
}

internal class PreparedTargetAdmissionFact private constructor(
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val targetGeneration: Long,
    internal val preparedTarget: PreparedTarget,
    internal val disposition: TargetConstructionAdmissionDisposition,
) {
    init {
        require(targetGeneration > 0L)
        check(preparedTarget.requestedIdentity === requestedIdentity)
        check(preparedTarget.targetGeneration == targetGeneration)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetGeneration: Long,
            preparedTarget: PreparedTarget,
            disposition: TargetConstructionAdmissionDisposition,
        ): PreparedTargetAdmissionFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return PreparedTargetAdmissionFact(
                requestedIdentity,
                targetGeneration,
                preparedTarget,
                disposition,
            )
        }
    }
}

internal class TargetConstructionFoldToken private constructor(
    internal val preparedTarget: PreparedTarget,
    internal val candidate: CurrentTarget,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val constructionIdentity: GlFiniteOperationIdentity,
    internal val predecessorGeneration: Long,
    internal val targetGeneration: Long,
    internal val plan: TargetPlan,
) {
    internal val constructionOperationIdentity: Long
        get() = constructionIdentity.operationIdentity

    internal val disposition: TargetConstructionFoldDisposition?
        get() = preparedTarget.selectedFoldDisposition

    internal val admissionDisposition: TargetConstructionAdmissionDisposition?
        get() = preparedTarget.claimedAdmissionDisposition

    internal fun claimResultFact(): TargetConstructionResultFact? =
        preparedTarget.claimConstructionResultFact(this)

    internal companion object {
        internal fun precreate(
            preparedTarget: PreparedTarget,
            candidate: CurrentTarget,
            requestedIdentity: TargetRequestedIdentity,
            constructionIdentity: GlFiniteOperationIdentity,
            predecessorGeneration: Long,
            targetGeneration: Long,
            plan: TargetPlan,
        ): TargetConstructionFoldToken = TargetConstructionFoldToken(
            preparedTarget = preparedTarget,
            candidate = candidate,
            requestedIdentity = requestedIdentity,
            constructionIdentity = constructionIdentity,
            predecessorGeneration = predecessorGeneration,
            targetGeneration = targetGeneration,
            plan = plan,
        )
    }
}

internal class PreparedTarget private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    internal val targetGeneration: Long,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val plan: TargetPlan,
    private val targetGate: ReentrantLock,
    private val predecessorGeneration: Long,
    private val constructionIdentity: GlFiniteOperationIdentity,
    private val listenerInstallationOperationIdentity: Long,
    private val surfaceReleaseOperationIdentity: Long,
    private val surfaceReleaseDeadlineIdentity: Long,
    private val surfaceReleaseDeadlineWakeGeneration: Long,
    private val surfaceReleaseTimeoutCause: Throwable,
    private val targetDestructionIdentity: GlFiniteOperationIdentity,
    private val namespaceDestructionIdentity: GlFiniteOperationIdentity,
    internal val constructionOccurrence: OperationOccurrence<TargetConstructionEvidence>,
    private val ownerBag: TargetConstructionOwnerBag,
    private val settlementSignal: SettlementSignal,
) {
    internal val quarantineChild: TargetQuarantineChild.Prepared = TargetQuarantineChild.Prepared(this)
    private var admission: TargetConstructionAdmission =
        TargetConstructionAdmission.Prospective
    private var disposition: PreparedTargetDisposition =
        PreparedTargetDisposition.Unclaimed
    private var constructionResultClaimed: Boolean = false
    private var installEligible: Boolean = false
    private var foldSelected: Boolean = false
    private var foldApplied: Boolean = false
    private var resultFactClaimed: Boolean = false
    private var terminalCleanup: Boolean = false
    internal var claimedAdmissionDisposition: TargetConstructionAdmissionDisposition? = null
        private set
    internal var selectedFoldDisposition: TargetConstructionFoldDisposition? = null
        private set
    private var pendingOesTextureName: Int = 0
    private var pendingSurfaceTexture: SurfaceTexture? = null
    private var pendingSurface: Surface? = null
    private val foldToken: TargetConstructionFoldToken = TargetConstructionFoldToken.precreate(
        preparedTarget = this,
        candidate = checkNotNull(ownerBag.candidate),
        requestedIdentity = requestedIdentity,
        constructionIdentity = constructionIdentity,
        predecessorGeneration = predecessorGeneration,
        targetGeneration = targetGeneration,
        plan = plan,
    )

    init {
        check(targetOwner.acceptsConstructionProof(constructionProof))
        require(targetGeneration > 0L)
        require(predecessorGeneration in 0L..<Long.MAX_VALUE)
        require(targetGeneration == predecessorGeneration + 1L)
        require(constructionOccurrence.identity == constructionIdentity.operationIdentity)
    }

    internal val constructionGlEvidence: GlOperationEvidence
        get() = ownerBag.evidence.glEvidence

    internal val constructionSettlementGate: ReentrantLock
        get() = constructionOccurrence.settlementGate

    internal val currentDisposition: PreparedTargetDisposition
        get() = targetGate.withLock { disposition }

    internal val surfaceReleaseOccurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        get() {
            check(currentDisposition == PreparedTargetDisposition.CleanupClaimed)
            return checkNotNull(ownerBag.candidate).surfaceReleaseOccurrence
        }

    internal val constructionDeadlineWakeLink: ControlWakeLink =
        checkNotNull(constructionOccurrence.controlWakeLink)

    internal fun adoptOesTextureName(oesTextureName: Int): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.Empty || oesTextureName == 0) {
            return@withLock false
        }
        ownerBag.evidence.oesTextureName = oesTextureName
        ownerBag.evidence.stage = TargetConstructionStage.OesOwned
        true
    }

    internal fun adoptSurfaceTexture(surfaceTexture: SurfaceTexture): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() ||
            ownerBag.evidence.stage != TargetConstructionStage.OesOwned ||
            ownerBag.evidence.surfaceTexture != null
        ) {
            return@withLock false
        }
        ownerBag.evidence.surfaceTexture = surfaceTexture
        ownerBag.evidence.stage = TargetConstructionStage.SurfaceTextureOwned
        true
    }

    internal fun recordDefaultBufferSizeApplied(): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.SurfaceTextureOwned) {
            return@withLock false
        }
        ownerBag.evidence.stage = TargetConstructionStage.BufferSizeApplied
        true
    }

    internal fun adoptSurface(surface: Surface): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() ||
            ownerBag.evidence.stage != TargetConstructionStage.BufferSizeApplied ||
            ownerBag.evidence.surface != null
        ) {
            return@withLock false
        }
        ownerBag.evidence.surface = surface
        ownerBag.evidence.stage = TargetConstructionStage.SurfaceOwned
        true
    }

    internal fun publishConstructionNormalReturn(): Boolean {
        val classified = constructionSettlementGate.withLock {
            canWorkerPublishLocked() && ownerBag.evidence.glEvidence.result != null
        }
        if (!classified) return false
        val published = constructionOccurrence.publishNormalReturn()
        if (published) settlementSignal.signal()
        return published
    }

    internal fun publishConstructionException(thrown: Exception): Boolean =
        publishConstructionThrownReturn(thrown)

    internal fun isConstructionMechanicallySettledForCleanup(): Boolean {
        var terminalConversionRequired = false
        val settled = constructionSettlementGate.withLock {
            if (!foldApplied || disposition != PreparedTargetDisposition.CleanupClaimed) {
                return@withLock false
            }
            terminalConversionRequired = terminalCleanup
            isConstructionMechanicallySettledLocked().also { mechanicallySettled ->
                if (mechanicallySettled) moveEvidenceToPendingLocked()
            }
        }
        if (settled) {
            val candidate = checkNotNull(ownerBag.candidate)
            applyPendingResources(candidate)
            candidate.settleConstructionResourceObligations()
            if (terminalConversionRequired) {
                candidate.convertConstructionCleanupOccurrencesForTerminal()
            }
        }
        return settled
    }

    internal fun isConstructionLaneMechanicallyComplete(): Boolean = constructionSettlementGate.withLock {
        isConstructionMechanicallySettledLocked()
    }

    internal fun prepareCleanupSurfaceReleaseOperation(): TargetRetirement.SurfaceReleaseOperation? {
        if (!isConstructionMechanicallySettledForCleanup()) return null
        return checkNotNull(ownerBag.candidate)
            .prepareUninstalledSurfaceReleaseOperation(constructionSettled = true)
    }

    internal fun prepareTargetScopeDestructionGraph(
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetRetirement.TargetScopeDestructionGraph? {
        if (!isConstructionMechanicallySettledForCleanup()) return null
        return checkNotNull(ownerBag.candidate)
            .prepareTargetScopeDestructionGraph(targetIdentity, namespaceIdentity)
    }

    internal fun isCleanupComplete(): Boolean =
        isConstructionMechanicallySettledForCleanup() && checkNotNull(ownerBag.candidate).isFullyRetired

    internal fun quarantineEvidence(): PreparedTargetQuarantineEvidence {
        val candidate = checkNotNull(ownerBag.candidate)
        val suffix = if (isConstructionMechanicallySettledForCleanup()) {
            candidate.retirementSuffixEvidence()
        } else {
            null
        }
        return PreparedTargetQuarantineEvidence.create(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            requestedIdentity = requestedIdentity,
            targetGeneration = targetGeneration,
            owner = this,
            constructionOccurrence = constructionOccurrence,
            cleanupSuffix = suffix,
        )
    }

    internal fun isExactProspective(
        expectedPredecessorGeneration: Long,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
    ): Boolean = constructionSettlementGate.withLock {
        val deadline = constructionOccurrence.deadlineOccurrence ?: return@withLock false
        admission == TargetConstructionAdmission.Prospective &&
                !constructionResultClaimed && !foldSelected && !foldApplied &&
                disposition == PreparedTargetDisposition.Unclaimed &&
                predecessorGeneration == expectedPredecessorGeneration &&
                expectedPredecessorGeneration != Long.MAX_VALUE &&
                targetGeneration == expectedPredecessorGeneration + 1L &&
                requestedIdentity === currentRequestedIdentity &&
                plan === currentPlan &&
                constructionOccurrence.identity ==
                constructionIdentity.operationIdentity &&
                deadline.identity == constructionIdentity.deadlineIdentity &&
                deadline.controlWakeLink.generation ==
                constructionIdentity.initialWakeGeneration &&
                deadline.timeoutCause === constructionIdentity.timeoutCause &&
                constructionOccurrence.ownerBag === ownerBag &&
                constructionOccurrence.returnCell.evidence === ownerBag.evidence &&
                ownerBag.candidate?.matchesPrecreatedIdentity(
                    expectedPlan = plan,
                    expectedRequestedIdentity = requestedIdentity,
                    expectedGeneration = targetGeneration,
                    listenerInstallationOperationIdentity = listenerInstallationOperationIdentity,
                    surfaceReleaseOperationIdentity = surfaceReleaseOperationIdentity,
                    surfaceReleaseDeadlineIdentity = surfaceReleaseDeadlineIdentity,
                    surfaceReleaseDeadlineWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
                    surfaceReleaseTimeoutCause = surfaceReleaseTimeoutCause,
                    targetDestructionIdentity = targetDestructionIdentity,
                    namespaceDestructionIdentity = namespaceDestructionIdentity,
                ) == true &&
                ownerBag.candidate?.usesTargetGate(targetGate) == true &&
                constructionOccurrence.domain == OperationDomain.Active &&
                constructionOccurrence.submissionDisposition ==
                OperationSubmissionDisposition.None &&
                constructionOccurrence.entryDisposition ==
                OperationEntryDisposition.Unentered &&
                constructionOccurrence.returnCell.disposition ==
                OperationReturnDisposition.Empty
    }

    internal fun admitLocked() {
        check(constructionSettlementGate.isHeldByCurrentThread)
        check(admission == TargetConstructionAdmission.Prospective)
        check(!constructionResultClaimed && !foldSelected && !foldApplied)
        admission = TargetConstructionAdmission.Admitted
    }

    internal fun withdrawAdmissionLocked(): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (admission != TargetConstructionAdmission.Admitted || constructionResultClaimed || foldSelected ||
            foldApplied || constructionOccurrence.submissionDisposition != OperationSubmissionDisposition.None ||
            constructionOccurrence.entryDisposition != OperationEntryDisposition.Unentered ||
            constructionOccurrence.returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return false
        }
        admission = TargetConstructionAdmission.Prospective
        return true
    }

    internal fun claimConstructionResultLocked(
        expectedConstructionOperationIdentity: Long,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
        admissionDisposition: TargetConstructionAdmissionDisposition,
    ): TargetConstructionFoldToken? {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (constructionResultClaimed || foldSelected || foldApplied ||
            disposition != PreparedTargetDisposition.Unclaimed
        ) {
            return null
        }
        val exactCurrent =
            admission == TargetConstructionAdmission.Admitted &&
                    constructionOccurrence.identity ==
                    expectedConstructionOperationIdentity &&
                    constructionIdentity.operationIdentity ==
                    expectedConstructionOperationIdentity &&
                    requestedIdentity === currentRequestedIdentity &&
                    plan === currentPlan && hasExactConstructionBindingLocked()
        if (admissionDisposition == TargetConstructionAdmissionDisposition.Terminal) {
            constructionOccurrence.arbitrateTerminal(mandatoryCleanup = false)
            constructionResultClaimed = true
            claimedAdmissionDisposition = admissionDisposition
            terminalCleanup = true
            installEligible = false
            selectedFoldDisposition = TargetConstructionFoldDisposition.CleanupTerminal
            moveEvidenceToPendingLocked()
            return foldToken
        }
        val arbitration = constructionOccurrence.arbitrate()
        if (arbitration == OperationArbitration.None) return null

        constructionResultClaimed = true
        claimedAdmissionDisposition = admissionDisposition
        terminalCleanup = false
        installEligible = when (arbitration) {
            OperationArbitration.TimelyNormal -> {
                val glEvidence = ownerBag.evidence.glEvidence
                exactCurrent &&
                        admissionDisposition == TargetConstructionAdmissionDisposition.Active &&
                        constructionOccurrence.returnCell.evidence === ownerBag.evidence &&
                        ownerBag.evidence.receipt === glEvidence.receipt &&
                        glEvidence.operationIdentity == constructionOccurrence.identity &&
                        glEvidence.operationKind == GlOperationKind.TargetConstruction &&
                        glEvidence.receipt.operationIdentity == constructionOccurrence.identity &&
                        glEvidence.receipt.operationKind == GlOperationKind.TargetConstruction &&
                        glEvidence.result == GlOperationResult.Success &&
                        glEvidence.contextIntegrity == ContextIntegrity.Intact &&
                        ownerBag.evidence.stage == TargetConstructionStage.SurfaceOwned &&
                        ownerBag.evidence.oesTextureName != 0 &&
                        ownerBag.evidence.surfaceTexture != null &&
                        ownerBag.evidence.surface != null
            }

            else -> false
        }
        if (!installEligible) {
            selectedFoldDisposition = when {
                !exactCurrent -> TargetConstructionFoldDisposition.CleanupStale
                else -> TargetConstructionFoldDisposition.CleanupFailure
            }
        }
        moveEvidenceToPendingLocked()
        return foldToken
    }

    internal fun selectFoldLocked(
        token: TargetConstructionFoldToken,
        expectedConstructionOperationIdentity: Long,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
        requestedDisposition: TargetConstructionFoldDisposition,
    ): TargetConstructionFoldDisposition? {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (token !== foldToken || !constructionResultClaimed || foldSelected || foldApplied) return null
        val sameFullKey = token.preparedTarget === this && token.candidate === ownerBag.candidate &&
                token.requestedIdentity === requestedIdentity &&
                token.constructionIdentity === constructionIdentity &&
                token.constructionOperationIdentity == constructionIdentity.operationIdentity &&
                token.predecessorGeneration == predecessorGeneration && token.targetGeneration == targetGeneration &&
                token.plan === plan && expectedConstructionOperationIdentity == constructionIdentity.operationIdentity &&
                currentRequestedIdentity === requestedIdentity &&
                currentPlan === plan && hasExactConstructionBindingLocked()
        val selected = when {
            requestedDisposition == TargetConstructionFoldDisposition.CleanupTerminal ->
                TargetConstructionFoldDisposition.CleanupTerminal

            requestedDisposition == TargetConstructionFoldDisposition.CleanupCollision ->
                TargetConstructionFoldDisposition.CleanupCollision

            requestedDisposition != TargetConstructionFoldDisposition.Install -> requestedDisposition

            requestedDisposition == TargetConstructionFoldDisposition.Install && installEligible && sameFullKey &&
                    selectedFoldDisposition == null ->
                TargetConstructionFoldDisposition.Install

            selectedFoldDisposition != null -> checkNotNull(selectedFoldDisposition)
            else -> TargetConstructionFoldDisposition.CleanupStale
        }
        if (selected == TargetConstructionFoldDisposition.Install && !sameFullKey) {
            selectedFoldDisposition = TargetConstructionFoldDisposition.CleanupStale
        } else {
            selectedFoldDisposition = selected
        }
        terminalCleanup = terminalCleanup ||
                selectedFoldDisposition == TargetConstructionFoldDisposition.CleanupTerminal
        foldSelected = true
        return selectedFoldDisposition
    }

    internal fun overrideSelectedFoldWithTerminalLocked(token: TargetConstructionFoldToken): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (token !== foldToken || !foldSelected || foldApplied ||
            selectedFoldDisposition == TargetConstructionFoldDisposition.CleanupTerminal) {
            return false
        }
        selectedFoldDisposition = TargetConstructionFoldDisposition.CleanupTerminal
        terminalCleanup = true
        return true
    }

    private fun publishConstructionThrownReturn(thrown: Throwable): Boolean {
        val canPublish = constructionSettlementGate.withLock { canWorkerPublishLocked() }
        if (!canPublish) return false
        if (thrown !is Exception) FatalThrowablePolicy.rethrow(thrown)
        val published = constructionOccurrence.publishThrownReturn(thrown)
        if (published) settlementSignal.signal()
        return published
    }

    private fun canWorkerPublishLocked(): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        return admission == TargetConstructionAdmission.Admitted &&
                constructionOccurrence.entryDisposition ==
                OperationEntryDisposition.Entered &&
                constructionOccurrence.returnCell.disposition ==
                OperationReturnDisposition.Empty
    }

    private fun hasExactConstructionBindingLocked(): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        val deadline = constructionOccurrence.deadlineOccurrence ?: return false
        val candidate = ownerBag.candidate ?: return false
        return foldToken.preparedTarget === this && foldToken.candidate === candidate &&
                foldToken.constructionIdentity === constructionIdentity &&
                constructionOccurrence.identity == constructionIdentity.operationIdentity &&
                deadline.identity == constructionIdentity.deadlineIdentity &&
                deadline.boundOccurrenceIdentity == constructionIdentity.operationIdentity &&
                deadline.controlWakeLink === constructionDeadlineWakeLink &&
                deadline.timeoutCause === constructionIdentity.timeoutCause &&
                constructionOccurrence.ownerBag === ownerBag &&
                constructionOccurrence.returnCell.evidence === ownerBag.evidence &&
                candidate.matchesPrecreatedIdentity(
                    expectedPlan = plan,
                    expectedRequestedIdentity = requestedIdentity,
                    expectedGeneration = targetGeneration,
                    listenerInstallationOperationIdentity = listenerInstallationOperationIdentity,
                    surfaceReleaseOperationIdentity = surfaceReleaseOperationIdentity,
                    surfaceReleaseDeadlineIdentity = surfaceReleaseDeadlineIdentity,
                    surfaceReleaseDeadlineWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
                    surfaceReleaseTimeoutCause = surfaceReleaseTimeoutCause,
                    targetDestructionIdentity = targetDestructionIdentity,
                    namespaceDestructionIdentity = namespaceDestructionIdentity,
                ) && candidate.usesTargetGate(targetGate)
    }

    private fun isConstructionMechanicallySettledLocked(): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (constructionOccurrence.returnCell.disposition != OperationReturnDisposition.Empty) {
            return true
        }
        if (constructionOccurrence.entryDisposition == OperationEntryDisposition.Cancelled) {
            return true
        }
        if (constructionOccurrence.entryDisposition != OperationEntryDisposition.Unentered) {
            return false
        }
        val schedulerRejected = constructionOccurrence.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                constructionOccurrence.disposition == OperationDisposition.SchedulerRejected

        return schedulerRejected || constructionOccurrence.disposition == OperationDisposition.DeadlineGuardFailed
    }

    private fun moveEvidenceToPendingLocked() {
        check(constructionSettlementGate.isHeldByCurrentThread)
        val oesTextureName = ownerBag.evidence.oesTextureName
        if (oesTextureName != 0) {
            check(pendingOesTextureName == 0)
            pendingOesTextureName = oesTextureName
            ownerBag.evidence.oesTextureName = 0
        }
        ownerBag.evidence.surfaceTexture?.let { surfaceTexture ->
            check(pendingSurfaceTexture == null)
            pendingSurfaceTexture = surfaceTexture
            ownerBag.evidence.surfaceTexture = null
        }
        ownerBag.evidence.surface?.let { surface ->
            check(pendingSurface == null)
            pendingSurface = surface
            ownerBag.evidence.surface = null
        }
    }

    internal fun applyFold(token: TargetConstructionFoldToken): Boolean {
        return targetGate.withLock {
            if (token !== foldToken || !foldSelected || foldApplied ||
                disposition != PreparedTargetDisposition.Unclaimed
            ) {
                return@withLock false
            }
            val selected = checkNotNull(selectedFoldDisposition)
            val candidate = checkNotNull(ownerBag.candidate)
            applyPendingResourcesLocked(candidate)
            val installed = selected == TargetConstructionFoldDisposition.Install
            candidate.finishConstructionOwnership(installed)
            disposition = if (installed) {
                PreparedTargetDisposition.Installed
            } else {
                PreparedTargetDisposition.CleanupClaimed
            }
            foldApplied = true
            true
        }
    }

    internal fun claimConstructionResultFact(token: TargetConstructionFoldToken): TargetConstructionResultFact? {
        val selected = targetGate.withLock {
            if (token !== foldToken || !foldApplied || resultFactClaimed) return@withLock null
            resultFactClaimed = true
            checkNotNull(selectedFoldDisposition)
        } ?: return null
        return if (selected == TargetConstructionFoldDisposition.Install) {
            checkNotNull(ownerBag.candidate).installedConstructionFact(
                targetOwner,
                constructionProof,
                constructionIdentity.operationIdentity,
                ownerBag.evidence.glEvidence.receipt,
            )
        } else {
            constructionFailureFact(token, selected)
        }
    }

    internal fun constructionFailureFact(
        token: TargetConstructionFoldToken,
        selected: TargetConstructionFoldDisposition,
    ): TargetConstructionFailureFact? {
        var returnDisposition = OperationReturnDisposition.Empty
        var failure: Throwable? = null
        var stage = TargetConstructionStage.Empty
        constructionSettlementGate.withLock {
            if (token !== foldToken || selectedFoldDisposition != selected) return null
            returnDisposition = constructionOccurrence.returnCell.disposition
            failure = constructionOccurrence.returnCell.throwable
            stage = ownerBag.evidence.stage
        }
        return checkNotNull(ownerBag.candidate).constructionFailureFact(
            requester = targetOwner,
            proof = constructionProof,
            constructionOperationIdentity = constructionIdentity.operationIdentity,
            disposition = selected,
            returnDisposition = returnDisposition,
            failure = failure,
            stage = stage,
        )
    }

    private fun applyPendingResources(candidate: CurrentTarget) = targetGate.withLock {
        applyPendingResourcesLocked(candidate)
    }

    private fun applyPendingResourcesLocked(candidate: CurrentTarget) {
        check(targetGate.isHeldByCurrentThread)
        if (pendingOesTextureName != 0) {
            candidate.adoptConstructionOesTextureName(pendingOesTextureName)
            pendingOesTextureName = 0
        }
        pendingSurfaceTexture?.let(candidate::adoptConstructionSurfaceTexture)
        pendingSurfaceTexture = null
        pendingSurface?.let(candidate::adoptConstructionSurface)
        pendingSurface = null
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetGate: ReentrantLock,
            targetGeneration: Long,
            predecessorGeneration: Long,
            plan: TargetPlan,
            requestedIdentity: TargetRequestedIdentity,
            constructionIdentity: GlFiniteOperationIdentity,
            listenerInstallationOperationIdentity: Long,
            sourceSignal: TargetSourceSignal,
            clock: EngineClock,
            settlementSignal: SettlementSignal,
            surfaceReleaseOperationIdentity: Long,
            surfaceReleaseDeadlineIdentity: Long,
            surfaceReleaseDeadlineWakeGeneration: Long,
            surfaceReleaseTimeoutCause: Throwable,
            targetDestructionIdentity: GlFiniteOperationIdentity,
            namespaceDestructionIdentity: GlFiniteOperationIdentity,
        ): PreparedTarget {
            val evidence = TargetConstructionEvidence.create(
                targetOwner,
                constructionProof,
                constructionIdentity.operationIdentity,
            )
            val candidate = CurrentTarget.precreateCandidate(
                targetOwner = targetOwner,
                constructionProof = constructionProof,
                targetGate = targetGate,
                requestedIdentity = requestedIdentity,
                plan = plan,
                generation = targetGeneration,
                listenerInstallationOperationIdentity = listenerInstallationOperationIdentity,
                sourceSignal = sourceSignal,
                clock = clock,
                settlementSignal = settlementSignal,
                surfaceReleaseOperationIdentity = surfaceReleaseOperationIdentity,
                surfaceReleaseDeadlineIdentity = surfaceReleaseDeadlineIdentity,
                surfaceReleaseDeadlineWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
                surfaceReleaseTimeoutCause = surfaceReleaseTimeoutCause,
                targetDestructionIdentity = targetDestructionIdentity,
                namespaceDestructionIdentity = namespaceDestructionIdentity,
            )
            val ownerBag = TargetConstructionOwnerBag(candidate, evidence)
            val occurrence = OperationOccurrence(
                identity = constructionIdentity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = ownerBag,
                deadlineIdentity = constructionIdentity.deadlineIdentity,
                deadlineDurationNanos = glEnteredOperationSafetyNanos,
                initialWakeGeneration = constructionIdentity.initialWakeGeneration,
                timeoutCause = constructionIdentity.timeoutCause,
                wakeSignal = settlementSignal,
            )
            return PreparedTarget(
                targetOwner,
                constructionProof,
                targetGeneration,
                requestedIdentity,
                plan,
                targetGate,
                predecessorGeneration,
                constructionIdentity,
                listenerInstallationOperationIdentity,
                surfaceReleaseOperationIdentity,
                surfaceReleaseDeadlineIdentity,
                surfaceReleaseDeadlineWakeGeneration,
                surfaceReleaseTimeoutCause,
                targetDestructionIdentity,
                namespaceDestructionIdentity,
                occurrence,
                ownerBag,
                settlementSignal,
            )
        }
    }
}
