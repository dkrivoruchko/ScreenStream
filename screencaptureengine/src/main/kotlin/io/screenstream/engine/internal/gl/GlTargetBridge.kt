package io.screenstream.engine.internal.gl

import android.view.Surface
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.isHandedOff
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.TargetPortUseOutcome
import io.screenstream.engine.internal.target.TargetRetirement
import io.screenstream.engine.internal.target.TargetSurfaceReleaseEvidence

internal class GlSurfaceReleaseHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val operation: TargetRetirement.SurfaceReleaseOperation,
) : GlPipelineOwner.SurfaceReleaseCommand {
    override val occurrence: OperationOccurrence<TargetSurfaceReleaseEvidence> = operation.occurrence
    private val canonicalEvidence: TargetSurfaceReleaseEvidence = occurrence.returnCell.evidence
    private val claimedFacts: TargetSurfaceReleaseClaim = TargetSurfaceReleaseClaim.precreate(canonicalEvidence.receipt)
    private val endpointOperation = owner.endpointOperation(occurrence, Runnable { execute() })

    init {
        check(canonicalEvidence.operationIdentity == occurrence.identity)
        check(canonicalEvidence.receipt.operationIdentity == occurrence.identity)
    }

    private fun execute() {
        owner.checkFatalFence()
        try {
            check(operation.release() == TargetPortUseOutcome.BodyReturned)
            owner.checkFatalFence()
            check(operation.publishNormalReturn())
        } catch (error: Error) {
            throw error
        } catch (exception: Exception) {
            owner.checkFatalFence()
            check(operation.publishThrownReturn(exception))
        }
    }

    override fun submit(): Boolean =
        owner.submit(endpointOperation).isHandedOff

    override val deadlineWakeLink: ControlWakeLink = checkNotNull(occurrence.controlWakeLink)

    override fun claim(): TargetSurfaceReleaseClaim? {
        val exactReceipt = canonicalEvidence.receipt.takeIf {
            occurrence.returnCell.evidence === canonicalEvidence &&
                    canonicalEvidence.operationIdentity == occurrence.identity &&
                    canonicalEvidence.receipt.operationIdentity == occurrence.identity
        }
        val claim = when (occurrence.arbitrate()) {
            OperationArbitration.TimelyNormal ->
                exactReceipt?.let { TargetSurfaceReleaseClaim.freeze(claimedFacts, timely = true) }

            OperationArbitration.ExpiredNormal,
            OperationArbitration.CleanupNormal,
                -> exactReceipt?.let { TargetSurfaceReleaseClaim.freeze(claimedFacts, timely = false) }

            else -> null
        }
        owner.releaseSettledOperation(endpointOperation)
        return claim
    }
}

internal class GlTargetConstructionHandle internal constructor(
    private val owner: GlPipelineOwner,
    private val preparedTarget: PreparedTarget,
) : GlPipelineOwner.TargetConstructionCommand {
    private val evidence: GlOperationEvidence = preparedTarget.constructionGlEvidence
    override val occurrence: OperationOccurrence<GlOperationEvidence> = preparedTarget.constructionOccurrence
    private val endpointOperation = owner.endpointOperation(
        occurrence,
        Runnable { execute() },
    )

    private fun execute() {
        try {
            if (owner.constructTarget(preparedTarget, evidence)) {
                owner.checkFatalFence()
                evidence.result = GlOperationResult.Success
                evidence.contextIntegrity = ContextIntegrity.Intact
            }
            owner.checkFatalFence()
            owner.closeNormalResult(evidence)
            preparedTarget.publishConstructionNormalReturn()
        } catch (outOfResources: Surface.OutOfResourcesException) {
            owner.checkFatalFence()
            evidence.throwable = outOfResources
            evidence.result = GlOperationResult.ResourceExhausted
            evidence.contextIntegrity = owner.contextIntegrity
            preparedTarget.publishConstructionException(outOfResources)
        } catch (exception: Exception) {
            owner.checkFatalFence()
            evidence.throwable = exception
            if (evidence.result == null) {
                evidence.result = GlOperationResult.InternalFailure
                evidence.contextIntegrity = owner.contextIntegrity
            }
            preparedTarget.publishConstructionException(exception)
        }
    }

    override fun submit(): Boolean =
        owner.submit(endpointOperation).isHandedOff

    override val deadlineWakeLink: ControlWakeLink = preparedTarget.constructionDeadlineWakeLink

    override fun retireAfterTargetArbitration() {
        if (preparedTarget.isConstructionLaneMechanicallyComplete()) {
            owner.releaseSettledOperation(endpointOperation)
        }
    }
}
