package io.screenstream.engine.internal.gl

import android.view.Surface
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.TargetRetirement
import io.screenstream.engine.internal.target.TargetSurfaceReleaseEvidence
import java.util.concurrent.ScheduledExecutorService

internal interface GlSurfaceReleaseAccess {
    val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
    fun beginSubmission(): Boolean
    fun detachedPort(): TargetRetirement.SurfaceReleasePort?
    fun commitPort(port: TargetRetirement.SurfaceReleasePort): Boolean
    fun enter(port: TargetRetirement.SurfaceReleasePort): OperationEntryResult
    fun release(port: TargetRetirement.SurfaceReleasePort): Boolean
    fun publishNormalReturn(): Boolean
    fun publishThrownReturn(thrown: Throwable): Boolean
}

internal class InstalledSurfaceReleaseAccess(
    private val target: CurrentTarget,
) : GlSurfaceReleaseAccess {
    override val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        get() = target.surfaceReleaseOccurrence

    override fun beginSubmission(): Boolean = target.beginSurfaceReleaseSubmission()

    override fun detachedPort(): TargetRetirement.SurfaceReleasePort? = target.detachedSurfaceReleasePort()

    override fun commitPort(port: TargetRetirement.SurfaceReleasePort): Boolean = target.commitSurfaceReleasePort(port)

    override fun enter(port: TargetRetirement.SurfaceReleasePort): OperationEntryResult = target.enterSurfaceRelease(port)

    override fun release(port: TargetRetirement.SurfaceReleasePort): Boolean = target.releaseEnteredSurface(port)

    override fun publishNormalReturn(): Boolean = target.publishSurfaceReleaseNormalReturn()

    override fun publishThrownReturn(thrown: Throwable): Boolean = target.publishSurfaceReleaseThrownReturn(thrown)
}

internal class CleanupSurfaceReleaseAccess(
    private val target: PreparedTarget,
) : GlSurfaceReleaseAccess {
    override val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence>
        get() = target.surfaceReleaseOccurrence

    override fun beginSubmission(): Boolean = target.beginSurfaceReleaseSubmission()

    override fun detachedPort(): TargetRetirement.SurfaceReleasePort? = target.detachedSurfaceReleasePort()

    override fun commitPort(port: TargetRetirement.SurfaceReleasePort): Boolean = target.commitSurfaceReleasePort(port)

    override fun enter(port: TargetRetirement.SurfaceReleasePort): OperationEntryResult = target.enterSurfaceRelease(port)

    override fun release(port: TargetRetirement.SurfaceReleasePort): Boolean = target.releaseEnteredSurface(port)

    override fun publishNormalReturn(): Boolean = target.publishSurfaceReleaseNormalReturn()

    override fun publishThrownReturn(thrown: Throwable): Boolean = target.publishSurfaceReleaseThrownReturn(thrown)
}

internal class GlSurfaceReleaseHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val access: GlSurfaceReleaseAccess,
    private val port: TargetRetirement.SurfaceReleasePort,
) : GlPipelineOwner.SurfaceReleaseCommand {
    private val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence> = access.occurrence
    private val canonicalEvidence: TargetSurfaceReleaseEvidence = occurrence.returnCell.evidence
    private val claimedFacts: TargetSurfaceReleaseClaim = TargetSurfaceReleaseClaim.precreate(canonicalEvidence.receipt)
    private val runnable: Runnable = owner.fatalBoundary { execute() }
    internal val laneTicket: GlLaneTicket = GlLaneTicket()

    init {
        check(port.operationIdentity == occurrence.identity)
        check(canonicalEvidence.operationIdentity == occurrence.identity)
        check(canonicalEvidence.receipt.operationIdentity == occurrence.identity)
    }

    private fun execute() {
        owner.checkFatalFence()
        if (!owner.laneRuntime.enter(laneTicket)) return
        owner.checkFatalFence()
        val entry = access.enter(port)
        owner.checkFatalFence()
        if (entry != OperationEntryResult.Entered) {
            owner.laneRuntime.returnWithoutOccurrenceEntry(laneTicket)
            return
        }
        owner.checkFatalFence()
        try {
            check(access.release(port))
            owner.checkFatalFence()
            check(access.publishNormalReturn())
        } catch (error: Error) {
            throw error
        } catch (exception: Exception) {
            owner.checkFatalFence()
            check(access.publishThrownReturn(exception))
        }
        owner.checkFatalFence()
        owner.finishLaneReturn(laneTicket)
    }

    override fun submit(): Boolean = owner.laneRuntime.submissionBoundary {
        if (!owner.laneRuntime.beginSubmission(laneTicket)) return@submissionBoundary false
        owner.checkFatalFence()
        if (!access.beginSubmission()) {
            owner.laneRuntime.cancelWithoutExecutorCall(laneTicket)
            return@submissionBoundary false
        }
        owner.checkFatalFence()
        val rejected = owner.laneRuntime.execute(runnable)
        if (rejected != null) {
            occurrence.publishSubmissionRejected(rejected)
            owner.laneRuntime.rejected(laneTicket)
            owner.checkFatalFence()
            owner.signalSettlement()
            owner.checkFatalFence()
            return@submissionBoundary false
        }
        occurrence.publishSubmissionAccepted()
        owner.laneRuntime.accepted(laneTicket)
        owner.checkFatalFence()
        owner.signalSettlement()
        owner.checkFatalFence()
        true
    }

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = occurrence.submitRequestedDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = occurrence.performRequestedDeadlineCancellation()

    override fun claim(): TargetSurfaceReleaseClaim? {
        val exactReceipt = canonicalEvidence.receipt.takeIf {
            occurrence.returnCell.evidence === canonicalEvidence &&
                    canonicalEvidence.operationIdentity == port.operationIdentity &&
                    canonicalEvidence.receipt.operationIdentity == port.operationIdentity
        }
        val claim = when (occurrence.arbitrate()) {
            OperationArbitration.TimelyNormal ->
                exactReceipt?.let { TargetSurfaceReleaseClaim.freeze(claimedFacts, timely = true) }

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> exactReceipt?.let { TargetSurfaceReleaseClaim.freeze(claimedFacts, timely = false) }

            else -> null
        }
        owner.retireMechanicallyCompleteLane(ticket = laneTicket, occurrence = occurrence)
        return claim
    }
}

internal class GlTargetConstructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val preparedTarget: PreparedTarget,
) : GlPipelineOwner.TargetConstructionCommand {
    private val failure: IllegalStateException = IllegalStateException("Target GL construction failed")
    private val evidence: GlOperationEvidence = GlOperationEvidence(
        preparedTarget.constructionOperationIdentity,
        GlOperationKind.TargetConstruction,
    )
    private val runnable: Runnable = owner.fatalBoundary { execute() }
    private val laneTicket: GlLaneTicket = GlLaneTicket()

    private fun execute() {
        if (!owner.laneRuntime.enter(laneTicket)) return
        owner.checkFatalFence()
        if (preparedTarget.tryEnterConstruction() != OperationEntryResult.Entered) {
            owner.laneRuntime.returnWithoutOccurrenceEntry(laneTicket)
            return
        }
        try {
            if (owner.constructTarget(preparedTarget, evidence)) {
                owner.checkFatalFence()
                preparedTarget.publishConstructionNormalReturn()
            } else {
                owner.checkFatalFence()
                preparedTarget.publishConstructionException(failure)
            }
        } catch (outOfResources: Surface.OutOfResourcesException) {
            owner.checkFatalFence()
            preparedTarget.publishConstructionException(outOfResources)
        } catch (exception: Exception) {
            owner.checkFatalFence()
            preparedTarget.publishConstructionException(exception)
        }
        owner.finishLaneReturn(laneTicket)
    }

    override fun submit(): Boolean = owner.laneRuntime.submissionBoundary {
        if (!owner.laneRuntime.beginSubmission(laneTicket)) return@submissionBoundary false
        if (!preparedTarget.beginConstructionSubmission()) {
            owner.laneRuntime.cancelWithoutExecutorCall(laneTicket)
            return@submissionBoundary false
        }
        owner.checkFatalFence()
        val rejected = owner.laneRuntime.execute(runnable)
        if (rejected != null) {
            preparedTarget.publishConstructionSubmissionRejected(rejected)
            owner.laneRuntime.rejected(laneTicket)
            owner.checkFatalFence()
            owner.signalSettlement()
            owner.checkFatalFence()
            return@submissionBoundary false
        }
        preparedTarget.publishConstructionSubmissionAccepted()
        owner.laneRuntime.accepted(laneTicket)
        owner.checkFatalFence()
        owner.signalSettlement()
        owner.checkFatalFence()
        true
    }

    override fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean = preparedTarget.submitRequestedConstructionDeadlineWake(scheduler)

    override fun performRequestedDeadlineCancellation(): Boolean = preparedTarget.performRequestedConstructionDeadlineCancellation()

    internal fun admitLane(): Boolean = owner.laneRuntime.issue(laneTicket)

    override fun retireAfterTargetArbitration() {
        if (preparedTarget.isConstructionLaneMechanicallyComplete()) {
            owner.retireReturnedLane(laneTicket)
        }
    }
}
