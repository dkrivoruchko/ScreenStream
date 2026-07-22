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
    private val constructionAdmissionClosedFact: TargetConstructionAdmissionClosedFact =
        TargetConstructionAdmissionClosedFact.create(this, constructionProof)

    private var preparedTarget: PreparedTarget? = null
    private var admissionCandidate: PreparedTarget? = null
    private var installedTarget: CurrentTarget? = null
    private var latestPredecessorRetiredFact: TargetPredecessorRetiredFact? = null
    private var lastTargetGeneration: Long = 0L
    private var constructionAdmissionOpen: Boolean = true
    private var lastPreparationOutcome: TargetPreparationOutcome? = null
    private var preparationInFlight: Boolean = false

    internal fun acceptsConstructionProof(proof: () -> Unit): Boolean =
        constructionProof === proof

    internal fun prepareTarget(
        predecessorRetiredFact: TargetPredecessorRetiredFact?,
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
    ): TargetPreparationOutcome? {
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
        require(targetDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity != targetDestructionIdentity.operationIdentity)

        var predecessorGeneration = 0L
        var targetGeneration = 0L
        val generationReserved = targetGate.withLock {
            val exactPredecessor = if (lastTargetGeneration == 0L) {
                predecessorRetiredFact == null && latestPredecessorRetiredFact == null
            } else {
                predecessorRetiredFact === latestPredecessorRetiredFact &&
                        predecessorRetiredFact?.targetGeneration == lastTargetGeneration
            }
            if (!constructionAdmissionOpen || preparationInFlight || lastPreparationOutcome != null ||
                preparedTarget != null || admissionCandidate != null ||
                installedTarget != null || !exactPredecessor ||
                lastTargetGeneration == Long.MAX_VALUE
            ) {
                return@withLock false
            }
            predecessorGeneration = lastTargetGeneration
            targetGeneration = predecessorGeneration + 1L
            if (predecessorGeneration != 0L) latestPredecessorRetiredFact = null
            lastTargetGeneration = targetGeneration
            preparationInFlight = true
            true
        }
        if (!generationReserved) return null

        val constructionProvenance: TargetConstructionProvenance
        val preparedOutcome: TargetPreparationOutcome.Prepared
        val structurallyAbsentOutcome: TargetPreparationOutcome.StructurallyAbsent
        try {
            constructionProvenance = TargetConstructionProvenance.create(
                targetOwner = this,
                constructionProof = constructionProof,
                requestedIdentity = requestedIdentity,
                plan = plan,
                predecessorGeneration = predecessorGeneration,
                targetGeneration = targetGeneration,
                constructionOperationIdentity = constructionIdentity.operationIdentity,
            )
            preparedOutcome = TargetPreparationOutcome.Prepared.precreate(
                targetOwner = this,
                constructionProof = constructionProof,
                constructionProvenance = constructionProvenance,
            )
            structurallyAbsentOutcome = TargetPreparationOutcome.StructurallyAbsent.precreate(
                targetOwner = this,
                constructionProof = constructionProof,
                constructionProvenance = constructionProvenance,
            )
        } catch (failure: Throwable) {
            targetGate.withLock {
                check(preparationInFlight)
                preparationInFlight = false
            }
            throw failure
        }

        val outcome = try {
            val target = PreparedTarget.precreate(
                targetOwner = this,
                constructionProof = constructionProof,
                targetGate = targetGate,
                targetGeneration = targetGeneration,
                predecessorGeneration = predecessorGeneration,
                plan = plan,
                requestedIdentity = requestedIdentity,
                constructionProvenance = constructionProvenance,
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
            check(target.belongsTo(this, constructionProof))
            preparedOutcome.bindPreparedTarget(target)
            preparedOutcome
        } catch (failure: Throwable) {
            structurallyAbsentOutcome.recordPrecreationFailure(failure)
            structurallyAbsentOutcome
        }
        return targetGate.withLock {
            check(preparationInFlight)
            lastPreparationOutcome = outcome
            preparationInFlight = false
            outcome
        }
    }

    /** Lock-published diagnostic/consumption view; the returned immutable outcome grants no authority. */
    internal fun preparationOutcome(
        requestedIdentity: TargetRequestedIdentity,
        targetGeneration: Long,
    ): TargetPreparationOutcome? = targetGate.withLock {
        lastPreparationOutcome?.takeIf {
            it.requestedIdentity === requestedIdentity && it.targetGeneration == targetGeneration
        }
    }

    internal fun retireStructurallyAbsentPreparation(
        outcome: TargetPreparationOutcome.StructurallyAbsent,
    ): Boolean = targetGate.withLock {
        if (lastPreparationOutcome !== outcome) return@withLock false
        lastPreparationOutcome = null
        true
    }

    internal fun admitPreparedTarget(
        prospectiveTarget: PreparedTarget,
        currentRequestedIdentity: TargetRequestedIdentity,
        currentPlan: TargetPlan,
    ): PreparedTargetAdmissionFact? {
        if (!prospectiveTarget.belongsTo(this, constructionProof)) return null
        val predecessorGeneration = prospectiveTarget.targetGeneration - 1L
        val reservedGeneration = targetGate.withLock {
            val preparation = lastPreparationOutcome as? TargetPreparationOutcome.Prepared
            if (preparedTarget != null || admissionCandidate != null ||
                lastTargetGeneration != prospectiveTarget.targetGeneration ||
                preparation == null || preparation.preparedTarget !== prospectiveTarget ||
                preparation.requestedIdentity !== prospectiveTarget.requestedIdentity ||
                preparation.plan !== prospectiveTarget.plan
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
            lastPreparationOutcome = null
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
        return prospectiveTarget.admissionFact(admissionDisposition)
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
                closeConstructionAdmissionLocked()
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
                        installedTarget != null || lastTargetGeneration != token.targetGeneration)
            ) {
                return@withLock false
            }
            if (!token.preparedTarget.applyFold(token)) return@withLock false
            if (selected == TargetConstructionFoldDisposition.Install) {
                preparedTarget = null
                installedTarget = token.candidate
            }
            if (selected == TargetConstructionFoldDisposition.CleanupTerminal) closeConstructionAdmissionLocked()
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
                    closeConstructionAdmissionLocked()
                    true
                }
            }
        }
        if (!applied) return null
        return token.claimResultFact()
    }

    internal fun closeConstructionAdmission(): TargetConstructionAdmissionClosedFact = targetGate.withLock {
        closeConstructionAdmissionLocked()
    }

    internal fun closeConstructionAdmissionForPreparedTerminalCleanup(
        target: PreparedTarget,
    ): TargetConstructionAdmissionClosedFact? = targetGate.withLock {
        if (preparedTarget !== target || target.targetGeneration != lastTargetGeneration) {
            return@withLock null
        }
        closeConstructionAdmissionLocked()
    }

    private fun closeConstructionAdmissionLocked(): TargetConstructionAdmissionClosedFact {
        check(targetGate.isHeldByCurrentThread)
        constructionAdmissionOpen = false
        latestPredecessorRetiredFact = null
        constructionAdmissionClosedFact.recordClosureLocked(lastTargetGeneration)
        return constructionAdmissionClosedFact
    }

    internal fun retireMechanicallyCompletedNonterminalPreparedTarget(
        target: PreparedTarget,
    ): PreparedTargetMechanicallyRetiredFact? {
        if (!target.isNonterminalCleanupComplete()) return null
        return targetGate.withLock {
            if (preparedTarget !== target || !constructionAdmissionOpen || installedTarget != null) {
                return@withLock null
            }
            val fact = target.nonterminalRetirementFactLocked() ?: return@withLock null
            preparedTarget = null
            latestPredecessorRetiredFact = fact
            fact
        }
    }

    internal fun retireMechanicallyCompletedTerminalPreparedTarget(
        target: PreparedTarget,
    ): PreparedTargetTerminalRetiredFact? {
        if (target.closeConstructionCleanupForTerminal() == null) return null
        if (target.currentDisposition != PreparedTargetDisposition.CleanupClaimed || !target.isCleanupComplete()) {
            return null
        }
        return targetGate.withLock {
            if (preparedTarget !== target || constructionAdmissionOpen) return@withLock null
            val fact = target.terminalRetirementFactLocked() ?: return@withLock null
            preparedTarget = null
            fact
        }
    }

    internal fun retireMechanicallyCompletedCurrentTarget(
        target: CurrentTarget,
        retirementEvidence: TargetRetirementCompleteEvidence,
    ): CurrentTargetMechanicallyRetiredFact? = targetGate.withLock {
        if (installedTarget !== target || target.identity.generation != lastTargetGeneration) {
            return@withLock null
        }
        val fact = target.claimMechanicallyRetiredFact(retirementEvidence) ?: return@withLock null
        installedTarget = null
        latestPredecessorRetiredFact = fact.takeIf { constructionAdmissionOpen }
        fact
    }

    internal fun claimPendingSource(
        targetIdentity: TargetIdentity,
        expectedFrameAdmissionEpoch: Long,
    ): TargetPendingSourceClaim? =
        targetIdentity.target.claimPendingSource(targetIdentity, expectedFrameAdmissionEpoch)

    internal fun commitPendingSource(
        claim: TargetPendingSourceClaim,
    ): TargetPendingSourceCommitResult = claim.targetIdentity.target.commitPendingSource(claim)

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
