package io.screenstream.engine.internal.target

import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TargetOwner(
    private val sessionGate: ReentrantLock,
) {
    private val targetGate: ReentrantLock = ReentrantLock(false)
    private val constructionProof: () -> Unit = {}

    private var preparedTarget: PreparedTarget? = null
    private var lastTargetGeneration: Long = 0L
    private var constructionAdmissionOpen: Boolean = true

    internal fun acceptsConstructionProof(proof: () -> Unit): Boolean =
        constructionProof === proof

    internal fun prepareTarget(
        plan: TargetPlan,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        reconciliationIdentity: Long,
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
    ): PreparedTarget? {
        check(!sessionGate.isHeldByCurrentThread)
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(reconciliationIdentity > 0L)
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
        require(targetDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity > 0L)

        val predecessorGeneration = sessionGate.withLock {
            if (!constructionAdmissionOpen || preparedTarget != null || lastTargetGeneration == Long.MAX_VALUE) {
                return null
            }
            lastTargetGeneration
        }
        val targetGeneration = predecessorGeneration + 1L

        return PreparedTarget.precreate(
            targetOwner = this,
            constructionProof = constructionProof,
            targetGate = targetGate,
            targetGeneration = targetGeneration,
            predecessorGeneration = predecessorGeneration,
            plan = plan,
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            reconciliationIdentity = reconciliationIdentity,
            constructionIdentity = constructionIdentity,
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
    }

    internal fun admitPreparedTarget(
        prospectiveTarget: PreparedTarget,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentReconciliationIdentity: Long,
        currentPlan: TargetPlan,
    ): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (!constructionAdmissionOpen || preparedTarget != null) {
            return false
        }

        val predecessorGeneration = lastTargetGeneration
        return prospectiveTarget.constructionSettlementGate.withLock {
            if (!prospectiveTarget.isExactProspective(
                    expectedPredecessorGeneration = predecessorGeneration,
                    currentDesiredRevision = currentDesiredRevision,
                    currentGeometryGeneration = currentGeometryGeneration,
                    currentLifecycleEpoch = currentLifecycleEpoch,
                    currentReconciliationIdentity = currentReconciliationIdentity,
                    currentPlan = currentPlan,
                )
            ) {
                return@withLock false
            }

            prospectiveTarget.admitLocked()
            lastTargetGeneration = prospectiveTarget.targetGeneration
            preparedTarget = prospectiveTarget
            true
        }
    }

    internal fun claimPreparedTargetResultLocked(
        target: PreparedTarget,
        expectedConstructionOperationIdentity: Long,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentReconciliationIdentity: Long,
        currentPlan: TargetPlan,
        admissionDisposition: TargetConstructionAdmissionDisposition,
    ): TargetConstructionFoldToken? {
        check(sessionGate.isHeldByCurrentThread)
        if (preparedTarget !== target) return null
        val effectiveAdmission = if (constructionAdmissionOpen) {
            admissionDisposition
        } else {
            TargetConstructionAdmissionDisposition.Terminal
        }
        return target.constructionSettlementGate.withLock {
            target.claimConstructionResultLocked(
                expectedConstructionOperationIdentity = expectedConstructionOperationIdentity,
                currentDesiredRevision = currentDesiredRevision,
                currentGeometryGeneration = currentGeometryGeneration,
                currentLifecycleEpoch = currentLifecycleEpoch,
                currentReconciliationIdentity = currentReconciliationIdentity,
                currentPlan = currentPlan,
                admissionDisposition = effectiveAdmission,
            )
        }
    }

    internal fun foldPreparedTargetResultLocked(
        token: TargetConstructionFoldToken,
        expectedConstructionOperationIdentity: Long,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentReconciliationIdentity: Long,
        currentPlan: TargetPlan,
        requestedDisposition: TargetConstructionFoldDisposition,
    ): TargetConstructionFoldDisposition? {
        check(sessionGate.isHeldByCurrentThread)
        val target = token.preparedTarget
        val exactPreparedTarget = preparedTarget === target &&
                lastTargetGeneration == token.targetGeneration
        val effectiveDisposition = when {
            requestedDisposition == TargetConstructionFoldDisposition.CleanupTerminal -> requestedDisposition
            !constructionAdmissionOpen -> TargetConstructionFoldDisposition.CleanupTerminal
            !exactPreparedTarget -> TargetConstructionFoldDisposition.CleanupCollision
            else -> requestedDisposition
        }
        val selected = target.constructionSettlementGate.withLock {
            target.selectFoldLocked(
                token = token,
                expectedConstructionOperationIdentity = expectedConstructionOperationIdentity,
                currentDesiredRevision = currentDesiredRevision,
                currentGeometryGeneration = currentGeometryGeneration,
                currentLifecycleEpoch = currentLifecycleEpoch,
                currentReconciliationIdentity = currentReconciliationIdentity,
                currentPlan = currentPlan,
                requestedDisposition = effectiveDisposition,
            )
        } ?: return null
        if (selected == TargetConstructionFoldDisposition.Install && exactPreparedTarget) {
            preparedTarget = null
        }
        if (selected == TargetConstructionFoldDisposition.CleanupTerminal) {
            constructionAdmissionOpen = false
        }
        return selected
    }

    internal fun applyPreparedTargetFold(token: TargetConstructionFoldToken): Boolean {
        check(!sessionGate.isHeldByCurrentThread)
        check(!token.preparedTarget.constructionSettlementGate.isHeldByCurrentThread)
        return token.preparedTarget.applyFold(token)
    }

    internal fun closeConstructionAdmission(): Boolean = sessionGate.withLock {
        if (!constructionAdmissionOpen) return@withLock false
        constructionAdmissionOpen = false
        true
    }

    internal fun clearMechanicallyCompletedPreparedTarget(target: PreparedTarget): Boolean {
        if (target.currentDisposition != PreparedTargetDisposition.CleanupClaimed || !target.isCleanupComplete()) {
            return false
        }
        return sessionGate.withLock {
            if (preparedTarget !== target) return@withLock false
            preparedTarget = null
            true
        }
    }

    internal fun hasPendingSource(target: CurrentTarget): Boolean = target.hasPendingSource

    internal fun consumePendingSource(target: CurrentTarget): Boolean = target.consumePendingSource()
}
