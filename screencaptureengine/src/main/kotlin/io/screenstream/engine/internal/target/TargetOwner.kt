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

    internal var currentTarget: CurrentTarget? = null
        private set

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
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
        require(targetDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity > 0L)

        val predecessorGeneration = sessionGate.withLock {
            if (!constructionAdmissionOpen || currentTarget != null || preparedTarget != null || lastTargetGeneration == Long.MAX_VALUE) {
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
        currentPlan: TargetPlan,
    ): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (!constructionAdmissionOpen || currentTarget != null || preparedTarget != null) {
            return false
        }

        val predecessorGeneration = lastTargetGeneration
        return prospectiveTarget.constructionSettlementGate.withLock {
            if (!prospectiveTarget.isExactProspective(
                    expectedPredecessorGeneration = predecessorGeneration,
                    currentDesiredRevision = currentDesiredRevision,
                    currentGeometryGeneration = currentGeometryGeneration,
                    currentLifecycleEpoch = currentLifecycleEpoch,
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

    internal fun arbitratePreparedTarget(
        target: PreparedTarget,
        expectedConstructionOperationIdentity: Long,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentPlan: TargetPlan,
    ): PreparedTargetDisposition = sessionGate.withLock {
        if (preparedTarget !== target) return@withLock target.currentDisposition

        target.constructionSettlementGate.withLock {
            val installed = target.claimInstalledLocked(
                expectedConstructionOperationIdentity = expectedConstructionOperationIdentity,
                currentDesiredRevision = currentDesiredRevision,
                currentGeometryGeneration = currentGeometryGeneration,
                currentLifecycleEpoch = currentLifecycleEpoch,
                currentPlan = currentPlan,
            )
            if (installed != null) {
                check(currentTarget == null)
                currentTarget = installed
                preparedTarget = null
                return@withLock PreparedTargetDisposition.Installed
            }

            val disposition = target.currentDisposition
            if (disposition == PreparedTargetDisposition.CleanupClaimed) {
                preparedTarget = target
            }
            disposition
        }
    }

    internal fun closeConstructionAdmission(): Boolean = sessionGate.withLock {
        if (!constructionAdmissionOpen) return@withLock false
        constructionAdmissionOpen = false
        true
    }

    internal fun claimPreparedTargetForTerminalCleanup(target: PreparedTarget): PreparedTargetDisposition =
        sessionGate.withLock {
            constructionAdmissionOpen = false
            if (preparedTarget !== target) {
                return@withLock target.currentDisposition
            }
            target.constructionSettlementGate.withLock {
                target.claimTerminalCleanupLocked()
            }
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

    internal fun clearRetiredCurrentTarget(targetGeneration: Long): CurrentTarget? {
        val target = sessionGate.withLock {
            currentTarget?.takeIf { it.generation == targetGeneration }
        } ?: return null
        if (!target.isFullyRetired) return null
        return sessionGate.withLock {
            if (currentTarget !== target) return@withLock null
            currentTarget = null
            target
        }
    }

    internal fun hasPendingSource(targetGeneration: Long): Boolean {
        val target = sessionGate.withLock {
            currentTarget?.takeIf { it.generation == targetGeneration }
        } ?: return false
        return target.hasPendingSource
    }

    internal fun consumePendingSource(targetGeneration: Long): Boolean {
        val target = sessionGate.withLock {
            currentTarget?.takeIf { it.generation == targetGeneration }
        } ?: return false
        return target.consumePendingSource()
    }
}
