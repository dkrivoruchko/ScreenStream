package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.glEnteredOperationSafetyNanos
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionRejectionResult
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private enum class TargetConstructionStage {
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

private enum class TargetConstructionWorkerResponsibility {
    TargetOwner,
    GlWorker,
    Cleanup,
}

private object TargetConstructionReceipt : OperationReceipt

private class TargetConstructionReturnedOwner : OperationReturnedOwner

private class TargetConstructionEvidence : OperationEvidence {
    override val receipt: TargetConstructionReceipt = TargetConstructionReceipt
    override val returnedOwner: TargetConstructionReturnedOwner =
        TargetConstructionReturnedOwner()

    var stage: TargetConstructionStage = TargetConstructionStage.Empty
    var oesTextureName: Int = 0
    var surfaceTexture: SurfaceTexture? = null
    var surface: Surface? = null
}

private class TargetConstructionOwnerBag(
    var candidate: CurrentTarget?,
    val evidence: TargetConstructionEvidence,
) : OperationOwnerBag

internal class PreparedTarget private constructor(
    internal val targetGeneration: Long,
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
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
    private val constructionOccurrence: OperationOccurrence<TargetConstructionEvidence>,
    private val ownerBag: TargetConstructionOwnerBag,
    private val settlementSignal: SettlementSignal,
) {
    private var admission: TargetConstructionAdmission =
        TargetConstructionAdmission.Prospective
    private var disposition: PreparedTargetDisposition =
        PreparedTargetDisposition.Unclaimed
    private var workerResponsibility: TargetConstructionWorkerResponsibility =
        TargetConstructionWorkerResponsibility.TargetOwner
    private var terminalCleanup: Boolean = false

    init {
        require(targetGeneration > 0L)
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(predecessorGeneration in 0L..<Long.MAX_VALUE)
        require(targetGeneration == predecessorGeneration + 1L)
        require(constructionOccurrence.identity == constructionIdentity.operationIdentity)
    }

    internal val constructionOperationIdentity: Long
        get() = constructionIdentity.operationIdentity

    internal val constructionSettlementGate: ReentrantLock
        get() = constructionOccurrence.settlementGate

    internal val currentDisposition: PreparedTargetDisposition
        get() = constructionSettlementGate.withLock { disposition }

    internal val surfaceReleaseOccurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        get() {
            check(currentDisposition == PreparedTargetDisposition.CleanupClaimed)
            return checkNotNull(ownerBag.candidate).surfaceReleaseOccurrence
        }

    internal fun beginConstructionSubmission(): Boolean = constructionSettlementGate.withLock {
        if (admission != TargetConstructionAdmission.Admitted ||
            disposition != PreparedTargetDisposition.Unclaimed ||
            workerResponsibility !=
            TargetConstructionWorkerResponsibility.TargetOwner
        ) {
            return@withLock false
        }
        if (!constructionOccurrence.beginSubmission()) return@withLock false
        workerResponsibility = TargetConstructionWorkerResponsibility.GlWorker
        true
    }

    internal fun publishConstructionSubmissionAccepted(): Boolean {
        val published = constructionSettlementGate.withLock {
            val accepted = constructionOccurrence.publishSubmissionAccepted()
            if (accepted && disposition == PreparedTargetDisposition.CleanupClaimed) {
                workerResponsibility = TargetConstructionWorkerResponsibility.Cleanup
            }
            accepted
        }
        if (published) settlementSignal.signal()
        return published
    }

    internal fun publishConstructionSubmissionRejected(thrown: Throwable): OperationSubmissionRejectionResult {
        val result = constructionSettlementGate.withLock {
            val rejection = constructionOccurrence.publishSubmissionRejected(thrown)
            if (rejection == OperationSubmissionRejectionResult.Active) {
                workerResponsibility = TargetConstructionWorkerResponsibility.TargetOwner
            } else if (disposition == PreparedTargetDisposition.CleanupClaimed) {
                workerResponsibility = TargetConstructionWorkerResponsibility.Cleanup
            }
            rejection
        }
        if (result != OperationSubmissionRejectionResult.NotCurrent) {
            settlementSignal.signal()
        }
        return result
    }

    internal fun tryEnterConstruction(): OperationEntryResult {
        val result = constructionSettlementGate.withLock {
            if (admission != TargetConstructionAdmission.Admitted ||
                disposition != PreparedTargetDisposition.Unclaimed ||
                workerResponsibility != TargetConstructionWorkerResponsibility.GlWorker
            ) {
                return@withLock OperationEntryResult.NotCurrent
            }
            constructionOccurrence.tryEnter().also { entry ->
                if (entry == OperationEntryResult.InvalidDeadline) {
                    workerResponsibility = TargetConstructionWorkerResponsibility.TargetOwner
                }
            }
        }
        if (result == OperationEntryResult.Entered) {
            constructionOccurrence.requestDeadlineWake()
        }
        if (result != OperationEntryResult.NotCurrent) settlementSignal.signal()
        return result
    }

    internal fun submitRequestedConstructionDeadlineWake(scheduler: ScheduledExecutorService): Boolean =
        constructionOccurrence.submitRequestedDeadlineWake(scheduler)

    internal fun performRequestedConstructionDeadlineCancellation(): Boolean =
        constructionOccurrence.performRequestedDeadlineCancellation()

    internal fun adoptOesTextureName(oesTextureName: Int): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.Empty || oesTextureName == 0) {
            return@withLock false
        }
        ownerBag.evidence.oesTextureName = oesTextureName
        ownerBag.evidence.stage = TargetConstructionStage.OesOwned
        if (disposition == PreparedTargetDisposition.CleanupClaimed) {
            checkNotNull(ownerBag.candidate).adoptConstructionOesTextureName(oesTextureName)
            ownerBag.evidence.oesTextureName = 0
        }
        true
    }

    internal fun adoptSurfaceTexture(surfaceTexture: SurfaceTexture): Boolean = constructionSettlementGate.withLock {
        if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.OesOwned || ownerBag.evidence.surfaceTexture != null) {
            return@withLock false
        }
        ownerBag.evidence.surfaceTexture = surfaceTexture
        ownerBag.evidence.stage = TargetConstructionStage.SurfaceTextureOwned
        if (disposition == PreparedTargetDisposition.CleanupClaimed) {
            checkNotNull(ownerBag.candidate).adoptConstructionSurfaceTexture(surfaceTexture)
            ownerBag.evidence.surfaceTexture = null
        }
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
        if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.BufferSizeApplied || ownerBag.evidence.surface != null) {
            return@withLock false
        }
        ownerBag.evidence.surface = surface
        ownerBag.evidence.stage = TargetConstructionStage.SurfaceOwned
        if (disposition == PreparedTargetDisposition.CleanupClaimed) {
            checkNotNull(ownerBag.candidate).adoptConstructionSurface(surface)
            ownerBag.evidence.surface = null
        }
        true
    }

    internal fun publishConstructionNormalReturn(): Boolean {
        val published = constructionSettlementGate.withLock {
            if (!canWorkerPublishLocked() || ownerBag.evidence.stage != TargetConstructionStage.SurfaceOwned) {
                return@withLock false
            }
            constructionOccurrence.publishNormalReturn().also { completed ->
                if (completed) {
                    workerResponsibility = if (disposition == PreparedTargetDisposition.CleanupClaimed) {
                        constructionOccurrence.arbitrateTerminal(mandatoryCleanup = false)
                        TargetConstructionWorkerResponsibility.Cleanup
                    } else {
                        TargetConstructionWorkerResponsibility.TargetOwner
                    }
                }
            }
        }
        if (published) settlementSignal.signal()
        return published
    }

    internal fun publishConstructionException(thrown: Exception): Boolean =
        publishConstructionThrownReturn(thrown)

    internal fun isConstructionMechanicallySettledForCleanup(): Boolean {
        var terminalConversionRequired = false
        val settled = constructionSettlementGate.withLock {
            if (disposition != PreparedTargetDisposition.CleanupClaimed) {
                return@withLock false
            }
            terminalConversionRequired = terminalCleanup
            isConstructionMechanicallySettledLocked()
        }
        if (settled) {
            val candidate = checkNotNull(ownerBag.candidate)
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

    internal fun beginSurfaceReleaseSubmission(): Boolean =
        isConstructionMechanicallySettledForCleanup() &&
                checkNotNull(ownerBag.candidate).beginUninstalledSurfaceReleaseSubmission(constructionSettled = true)

    internal fun detachedSurfaceReleasePort(): TargetRetirement.SurfaceReleasePort? {
        if (!isConstructionMechanicallySettledForCleanup()) return null
        return checkNotNull(ownerBag.candidate).detachedSurfaceReleasePort()
    }

    internal fun commitSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort): Boolean =
        isConstructionMechanicallySettledForCleanup() && checkNotNull(ownerBag.candidate).commitSurfaceReleasePort(port)

    internal fun enterSurfaceRelease(port: TargetRetirement.SurfaceReleasePort): OperationEntryResult =
        if (isConstructionMechanicallySettledForCleanup()) {
            checkNotNull(ownerBag.candidate).enterSurfaceRelease(port)
        } else {
            OperationEntryResult.NotCurrent
        }

    internal fun releaseEnteredSurface(port: TargetRetirement.SurfaceReleasePort): Boolean =
        isConstructionMechanicallySettledForCleanup() && checkNotNull(ownerBag.candidate).releaseEnteredSurface(port)

    internal fun publishSurfaceReleaseNormalReturn(): Boolean =
        checkNotNull(ownerBag.candidate).publishSurfaceReleaseNormalReturn()

    internal fun publishSurfaceReleaseThrownReturn(thrown: Throwable): Boolean =
        checkNotNull(ownerBag.candidate).publishSurfaceReleaseThrownReturn(thrown)

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

    internal fun isExactProspective(
        expectedPredecessorGeneration: Long,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentPlan: TargetPlan,
    ): Boolean = constructionSettlementGate.withLock {
        val deadline = constructionOccurrence.deadlineOccurrence ?: return@withLock false
        admission == TargetConstructionAdmission.Prospective &&
                disposition == PreparedTargetDisposition.Unclaimed &&
                predecessorGeneration == expectedPredecessorGeneration &&
                expectedPredecessorGeneration != Long.MAX_VALUE &&
                targetGeneration == expectedPredecessorGeneration + 1L &&
                desiredRevision == currentDesiredRevision &&
                geometryGeneration == currentGeometryGeneration &&
                lifecycleEpoch == currentLifecycleEpoch &&
                plan === currentPlan &&
                constructionOccurrence.identity ==
                constructionIdentity.operationIdentity &&
                deadline.identity == constructionIdentity.deadlineIdentity &&
                deadline.wakeLink.generation ==
                constructionIdentity.initialWakeGeneration &&
                deadline.wakeLink.timeoutCause === constructionIdentity.timeoutCause &&
                constructionOccurrence.ownerBag === ownerBag &&
                constructionOccurrence.returnCell.evidence === ownerBag.evidence &&
                ownerBag.candidate?.matchesPrecreatedIdentity(
                    expectedPlan = plan,
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
        check(disposition == PreparedTargetDisposition.Unclaimed)
        admission = TargetConstructionAdmission.Admitted
    }

    internal fun claimInstalledLocked(
        expectedConstructionOperationIdentity: Long,
        currentDesiredRevision: Long,
        currentGeometryGeneration: Long,
        currentLifecycleEpoch: Long,
        currentPlan: TargetPlan,
    ): CurrentTarget? {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (disposition != PreparedTargetDisposition.Unclaimed) return null
        val exactCurrent =
            admission == TargetConstructionAdmission.Admitted &&
                    constructionOccurrence.identity ==
                    expectedConstructionOperationIdentity &&
                    constructionIdentity.operationIdentity ==
                    expectedConstructionOperationIdentity &&
                    desiredRevision == currentDesiredRevision &&
                    geometryGeneration == currentGeometryGeneration &&
                    lifecycleEpoch == currentLifecycleEpoch &&
                    plan === currentPlan
        if (!exactCurrent) {
            transferConstructionToCleanupLocked()
            return null
        }
        return when (constructionOccurrence.arbitrate()) {
            OperationArbitration.None -> null
            OperationArbitration.TimelyNormal -> {
                if (ownerBag.evidence.stage !=
                    TargetConstructionStage.SurfaceOwned ||
                    ownerBag.evidence.oesTextureName == 0 ||
                    ownerBag.evidence.surfaceTexture == null ||
                    ownerBag.evidence.surface == null
                ) {
                    transferConstructionToCleanupLocked()
                    null
                } else {
                    val candidate = checkNotNull(ownerBag.candidate)
                    transferEvidenceToCandidateLocked(candidate)
                    candidate.finishConstructionOwnership(installed = true)
                    ownerBag.candidate = null
                    disposition = PreparedTargetDisposition.Installed
                    workerResponsibility = TargetConstructionWorkerResponsibility.TargetOwner
                    candidate
                }
            }

            else -> {
                transferConstructionToCleanupLocked()
                null
            }
        }
    }

    internal fun claimCleanupLocked(): PreparedTargetDisposition {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (disposition != PreparedTargetDisposition.Unclaimed) return disposition
        transferConstructionToCleanupLocked()
        return disposition
    }

    internal fun claimTerminalCleanupLocked(): PreparedTargetDisposition {
        check(constructionSettlementGate.isHeldByCurrentThread)
        terminalCleanup = true
        return claimCleanupLocked()
    }

    private fun publishConstructionThrownReturn(thrown: Throwable): Boolean {
        val published = constructionSettlementGate.withLock {
            if (!canWorkerPublishLocked()) return@withLock false
            constructionOccurrence.publishThrownReturn(thrown).also { completed ->
                if (completed) {
                    workerResponsibility = if (disposition == PreparedTargetDisposition.CleanupClaimed) {
                        constructionOccurrence.arbitrateTerminal(mandatoryCleanup = false)
                        TargetConstructionWorkerResponsibility.Cleanup
                    } else {
                        TargetConstructionWorkerResponsibility.TargetOwner
                    }
                }
            }
        }
        if (published) settlementSignal.signal()
        return published
    }

    private fun canWorkerPublishLocked(): Boolean {
        check(constructionSettlementGate.isHeldByCurrentThread)
        return admission == TargetConstructionAdmission.Admitted &&
                workerResponsibility !=
                TargetConstructionWorkerResponsibility.TargetOwner &&
                constructionOccurrence.entryDisposition ==
                OperationEntryDisposition.Entered &&
                constructionOccurrence.returnCell.disposition ==
                OperationReturnDisposition.Empty
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

    private fun transferConstructionToCleanupLocked() {
        check(constructionSettlementGate.isHeldByCurrentThread)
        if (disposition == PreparedTargetDisposition.CleanupClaimed) return
        check(disposition == PreparedTargetDisposition.Unclaimed)
        constructionOccurrence.arbitrateTerminal(mandatoryCleanup = false)
        val candidate = checkNotNull(ownerBag.candidate)
        transferEvidenceToCandidateLocked(candidate)
        candidate.finishConstructionOwnership(installed = false)
        disposition = PreparedTargetDisposition.CleanupClaimed
        workerResponsibility = TargetConstructionWorkerResponsibility.Cleanup
    }

    private fun transferEvidenceToCandidateLocked(candidate: CurrentTarget) {
        check(constructionSettlementGate.isHeldByCurrentThread)
        val oesTextureName = ownerBag.evidence.oesTextureName
        if (oesTextureName != 0) {
            candidate.adoptConstructionOesTextureName(oesTextureName)
            ownerBag.evidence.oesTextureName = 0
        }
        ownerBag.evidence.surfaceTexture?.let { surfaceTexture ->
            candidate.adoptConstructionSurfaceTexture(surfaceTexture)
            ownerBag.evidence.surfaceTexture = null
        }
        ownerBag.evidence.surface?.let { surface ->
            candidate.adoptConstructionSurface(surface)
            ownerBag.evidence.surface = null
        }
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetGate: ReentrantLock,
            targetGeneration: Long,
            predecessorGeneration: Long,
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
        ): PreparedTarget {
            val evidence = TargetConstructionEvidence()
            val candidate = CurrentTarget.precreateCandidate(
                targetOwner = targetOwner,
                constructionProof = constructionProof,
                targetGate = targetGate,
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
                targetGeneration,
                desiredRevision,
                geometryGeneration,
                lifecycleEpoch,
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
