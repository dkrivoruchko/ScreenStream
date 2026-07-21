package io.screenstream.engine.internal.target

import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TargetOwner {
    private val targetGate: ReentrantLock = ReentrantLock(false)
    private val constructionProof: () -> Unit = {}

    private var preparedTarget: PreparedTarget? = null
    private var admissionCandidate: PreparedTarget? = null
    private var lastTargetGeneration: Long = 0L
    private var constructionAdmissionOpen: Boolean = true

    internal fun acceptsConstructionProof(proof: () -> Unit): Boolean =
        constructionProof === proof

    internal fun prepareTarget(
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
    ): PreparedTarget? {
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
        require(targetDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity > 0L)

        var predecessorGeneration = 0L
        var targetGeneration = 0L
        val generationReserved = targetGate.withLock {
            if (!constructionAdmissionOpen || preparedTarget != null || admissionCandidate != null ||
                lastTargetGeneration == Long.MAX_VALUE
            ) {
                return@withLock false
            }
            predecessorGeneration = lastTargetGeneration
            targetGeneration = predecessorGeneration + 1L
            lastTargetGeneration = targetGeneration
            true
        }
        if (!generationReserved) return null

        return PreparedTarget.precreate(
            targetOwner = this,
            constructionProof = constructionProof,
            targetGate = targetGate,
            targetGeneration = targetGeneration,
            predecessorGeneration = predecessorGeneration,
            plan = plan,
            requestedIdentity = requestedIdentity,
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
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
    ): PreparedTargetAdmissionFact? {
        val predecessorGeneration = prospectiveTarget.targetGeneration - 1L
        val reservedGeneration = targetGate.withLock {
            if (!constructionAdmissionOpen || preparedTarget != null || admissionCandidate != null ||
                lastTargetGeneration != prospectiveTarget.targetGeneration
            ) {
                return@withLock false
            }
            admissionCandidate = prospectiveTarget
            true
        }
        if (!reservedGeneration) return null
        val admitted = prospectiveTarget.constructionSettlementGate.withLock {
            if (!prospectiveTarget.isExactProspective(
                    expectedPredecessorGeneration = predecessorGeneration,
                    currentRequestedIdentity = currentRequestedIdentity,
                    currentPlan = currentPlan,
                )
            ) {
                return@withLock false
            }

            prospectiveTarget.admitLocked()
            true
        }
        if (!admitted) {
            targetGate.withLock {
                if (admissionCandidate === prospectiveTarget) admissionCandidate = null
            }
            return null
        }
        var admissionDisposition = TargetConstructionAdmissionDisposition.Terminal
        val committed = targetGate.withLock {
            if (admissionCandidate !== prospectiveTarget || preparedTarget != null ||
                lastTargetGeneration != prospectiveTarget.targetGeneration
            ) {
                return@withLock false
            }
            admissionDisposition = if (constructionAdmissionOpen) {
                TargetConstructionAdmissionDisposition.Active
            } else {
                TargetConstructionAdmissionDisposition.Terminal
            }
            preparedTarget = prospectiveTarget
            admissionCandidate = null
            true
        }
        if (!committed) {
            prospectiveTarget.constructionSettlementGate.withLock {
                check(prospectiveTarget.withdrawAdmissionLocked())
            }
            targetGate.withLock {
                if (admissionCandidate === prospectiveTarget) admissionCandidate = null
            }
            return null
        }
        return PreparedTargetAdmissionFact.create(
            targetOwner = this,
            constructionProof = constructionProof,
            requestedIdentity = prospectiveTarget.requestedIdentity,
            targetGeneration = prospectiveTarget.targetGeneration,
            preparedTarget = prospectiveTarget,
            disposition = admissionDisposition,
        )
    }

    internal fun claimPreparedTargetResult(
        admissionFact: PreparedTargetAdmissionFact,
        expectedConstructionOperationIdentity: Long,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
    ): TargetConstructionFoldToken? {
        val target = admissionFact.preparedTarget
        val boundedAdmission = targetGate.withLock {
            if (preparedTarget !== target ||
                admissionFact.requestedIdentity !== target.requestedIdentity ||
                admissionFact.targetGeneration != target.targetGeneration
            ) {
                return null
            }
            if (constructionAdmissionOpen &&
                admissionFact.disposition == TargetConstructionAdmissionDisposition.Active
            ) {
                TargetConstructionAdmissionDisposition.Active
            } else {
                TargetConstructionAdmissionDisposition.Terminal
            }
        }
        return target.constructionSettlementGate.withLock {
            target.claimConstructionResultLocked(
                expectedConstructionOperationIdentity = expectedConstructionOperationIdentity,
                currentRequestedIdentity = currentRequestedIdentity,
                currentPlan = currentPlan,
                admissionDisposition = boundedAdmission,
            )
        }
    }

    internal fun selectPreparedTargetDisposition(
        token: TargetConstructionFoldToken,
        expectedConstructionOperationIdentity: Long,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
        requestedDisposition: TargetConstructionFoldDisposition,
    ): TargetConstructionFoldDisposition? {
        val target = token.preparedTarget
        var exactPreparedTarget = false
        var boundedDisposition = requestedDisposition
        targetGate.withLock {
            exactPreparedTarget = preparedTarget === target &&
                    lastTargetGeneration == token.targetGeneration
            boundedDisposition = when {
                requestedDisposition == TargetConstructionFoldDisposition.CleanupTerminal -> requestedDisposition
                !constructionAdmissionOpen -> TargetConstructionFoldDisposition.CleanupTerminal
                !exactPreparedTarget -> TargetConstructionFoldDisposition.CleanupCollision
                else -> requestedDisposition
            }
        }
        val selected = target.constructionSettlementGate.withLock {
            target.selectFoldLocked(
                token = token,
                expectedConstructionOperationIdentity = expectedConstructionOperationIdentity,
                currentRequestedIdentity = currentRequestedIdentity,
                currentPlan = currentPlan,
                requestedDisposition = boundedDisposition,
            )
        } ?: return null
        targetGate.withLock {
            if (selected == TargetConstructionFoldDisposition.CleanupTerminal) {
                constructionAdmissionOpen = false
            }
        }
        return selected
    }

    internal fun applyPreparedTargetFold(token: TargetConstructionFoldToken): TargetConstructionResultFact? {
        check(!token.preparedTarget.constructionSettlementGate.isHeldByCurrentThread)
        var selected = token.disposition ?: return null
        val admissionOpen = targetGate.withLock { constructionAdmissionOpen }
        if (!admissionOpen && selected != TargetConstructionFoldDisposition.CleanupTerminal) {
            val overridden = token.preparedTarget.constructionSettlementGate.withLock {
                token.preparedTarget.overrideSelectedFoldWithTerminalLocked(token)
            }
            if (!overridden) return null
            selected = TargetConstructionFoldDisposition.CleanupTerminal
        }
        var applied = targetGate.withLock {
            if (!constructionAdmissionOpen &&
                selected != TargetConstructionFoldDisposition.CleanupTerminal
            ) {
                return@withLock false
            }
            if (selected == TargetConstructionFoldDisposition.Install &&
                (!constructionAdmissionOpen || preparedTarget !== token.preparedTarget ||
                        lastTargetGeneration != token.targetGeneration)
            ) {
                return@withLock false
            }
            if (!token.preparedTarget.applyFold(token)) return@withLock false
            if (selected == TargetConstructionFoldDisposition.Install) preparedTarget = null
            if (selected == TargetConstructionFoldDisposition.CleanupTerminal) constructionAdmissionOpen = false
            true
        }
        if (!applied && selected != TargetConstructionFoldDisposition.CleanupTerminal) {
            val closedBeforeApply = targetGate.withLock { !constructionAdmissionOpen }
            if (closedBeforeApply) {
                val overridden = token.preparedTarget.constructionSettlementGate.withLock {
                    token.preparedTarget.overrideSelectedFoldWithTerminalLocked(token)
                }
                if (!overridden) return null
                selected = TargetConstructionFoldDisposition.CleanupTerminal
                applied = targetGate.withLock {
                    if (!token.preparedTarget.applyFold(token)) return@withLock false
                    constructionAdmissionOpen = false
                    true
                }
            }
        }
        if (!applied) return null
        return token.claimResultFact()
    }

    internal fun closeConstructionAdmission(): TargetConstructionAdmissionClosedFact? {
        var closedGeneration = 0L
        val closed = targetGate.withLock {
            if (!constructionAdmissionOpen) return@withLock false
            constructionAdmissionOpen = false
            closedGeneration = lastTargetGeneration
            true
        }
        return if (closed) {
            TargetConstructionAdmissionClosedFact.create(this, constructionProof, closedGeneration)
        } else {
            null
        }
    }

    internal fun retireMechanicallyCompletedPreparedTarget(target: PreparedTarget): PreparedTargetRetiredFact? {
        if (target.currentDisposition != PreparedTargetDisposition.CleanupClaimed || !target.isCleanupComplete()) {
            return null
        }
        val retired = targetGate.withLock {
            if (preparedTarget !== target) return@withLock false
            preparedTarget = null
            true
        }
        if (!retired) return null
        return PreparedTargetRetiredFact.create(
            targetOwner = this,
            constructionProof = constructionProof,
            requestedIdentity = target.requestedIdentity,
            targetGeneration = target.targetGeneration,
            retiredTarget = target,
        )
    }

    internal fun hasPendingSource(target: CurrentTarget): Boolean = target.hasPendingSource

    internal fun consumePendingSource(target: CurrentTarget): Boolean = target.consumePendingSource()

    internal fun detachedGlFramePort(
        target: CurrentTarget,
        operationIdentity: Long,
    ): TargetPorts.GlFramePort? = target.detachedGlFramePort(operationIdentity)

    internal fun commitGlFramePort(
        target: CurrentTarget,
        port: TargetPorts.GlFramePort,
    ): Boolean = target.commitGlFramePort(port)

    internal fun commitGlFrameReservation(
        target: CurrentTarget,
        port: TargetPorts.GlFramePort,
    ): TargetFrameReservationResult = target.commitGlFrameReservation(port)

    internal fun enterGlFramePort(
        target: CurrentTarget,
        port: TargetPorts.GlFramePort,
    ): TargetFrameEntryResult = target.enterGlFramePort(port)

    internal fun retireGlFramePortAfterSettlement(
        target: CurrentTarget,
        port: TargetPorts.GlFramePort,
        operation: OperationOccurrence<*>,
    ): Boolean = target.retireGlFramePortAfterSettlement(port, operation)

    internal fun retireGlFramePortFactAfterSettlement(
        target: CurrentTarget,
        port: TargetPorts.GlFramePort,
        operation: OperationOccurrence<*>,
    ): TargetFramePortRetiredFact? = target.retireGlFramePortFactAfterSettlement(port, operation)

    internal fun sealFrameAdmission(
        target: CurrentTarget,
        retainedReconfigurationIdentity: Long,
    ): TargetFrameAdmissionSealedFact? = target.sealFrameAdmission(retainedReconfigurationIdentity)

    internal fun claimFrameQuiesced(
        target: CurrentTarget,
        sealedFact: TargetFrameAdmissionSealedFact,
    ): TargetFrameQuiescedFact? = target.claimFrameQuiesced(sealedFact)

    internal fun reserveRetainedGlMutation(
        target: CurrentTarget,
        quiescedFact: TargetFrameQuiescedFact,
        retainedReconfigurationIdentity: Long,
    ): TargetRetainedGlReservationResult =
        target.reserveRetainedGlMutation(quiescedFact, retainedReconfigurationIdentity)

    internal fun enterRetainedGlMutation(
        target: CurrentTarget,
        reservedFact: TargetRetainedGlReservedFact,
    ): TargetRetainedGlEntryResult = target.enterRetainedGlMutation(reservedFact)

    internal fun settleRetainedGlMutation(
        target: CurrentTarget,
        admittedFact: TargetRetainedGlAdmittedFact,
    ): TargetRetainedGlSettlementResult = target.settleRetainedGlMutation(admittedFact)

    internal fun reopenFrameAdmission(
        target: CurrentTarget,
        quiescedFact: TargetFrameQuiescedFact,
        retainedReconfigurationIdentity: Long,
    ): TargetFrameAdmissionReopenResult =
        target.reopenFrameAdmission(quiescedFact, retainedReconfigurationIdentity)
}
