package io.screenstream.capture.internal.session.topology

import android.os.Build.VERSION_CODES
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.capture.CapturePlan
import io.screenstream.capture.internal.capture.CaptureProjectionIdentity
import io.screenstream.capture.internal.capture.CaptureTargetMode
import io.screenstream.capture.internal.metrics.MetricsSnapshot

/**
 * Exclusive semantic owner of desired parameters, configuration revisions, readiness, resolved plans, effective
 * output, and captured-content visibility for one session.
 *
 * Plan and publication candidates capture exact owner/generation evidence and are not commits. The coordinator must
 * revalidate them immediately before joining them with other session domains. Leaf completion, matching values, or
 * a previously resolved revision alone never establishes currentness.
 */
internal class SessionTopology {
    internal class ParameterUpdate(
        internal val parameters: ScreenCaptureParameters,
    )

    internal class DesiredTransition(
        internal val previousParameters: ScreenCaptureParameters,
        internal val parameters: ScreenCaptureParameters,
        internal val revision: Long,
        internal val isCachedImageCompatible: Boolean,
        internal val historicalEffectiveParameters: ScreenCaptureEffectiveParameters?,
        internal val problem: ScreenCaptureProblem?,
    )

    internal sealed interface MetricsDecision {
        data object Duplicate : MetricsDecision
        data object BlockedByPendingIngress : MetricsDecision

        class Failed(internal val cause: Throwable) : MetricsDecision

        class Update(
            internal val snapshot: MetricsSnapshot,
            internal val requiresTopologyRevision: Boolean,
            internal val closesActiveAdmission: Boolean,
            internal val wasActive: Boolean,
            internal val historicalEffectiveParameters: ScreenCaptureEffectiveParameters?,
        ) : MetricsDecision
    }

    internal sealed interface PendingResizeDecision {
        data object None : PendingResizeDecision
        data object BlockedByPendingIngress : PendingResizeDecision
        data object RevisionCandidate : PendingResizeDecision
    }

    internal sealed interface PlanDecision {
        data object Suspended : PlanDecision
        data object WaitingForIngress : PlanDecision
        data object WaitingForMetrics : PlanDecision
        data object Current : PlanDecision

        class Install(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val plan: SessionPlanResolution.Resolved,
            internal val pausesActiveTopology: Boolean,
        ) : PlanDecision {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)

        }

        class Rejected(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val problem: ScreenCaptureProblem,
            internal val cause: Throwable?,
        ) : PlanDecision {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)

        }
    }

    internal sealed interface ConvergenceStep {
        data object Waiting : ConvergenceStep
        data object Ready : ConvergenceStep

        class Open(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val plan: CapturePlan,
        ) : ConvergenceStep {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)

        }

        class Apply(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val plan: CapturePlan,
        ) : ConvergenceStep {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)

        }

        class ReconcileEncoding(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val plan: Rgba8888Layout,
        ) : ConvergenceStep {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation)

        }
    }

    internal class CaptureOpenResultCandidate(
        private val owner: SessionTopology,
        private val dispatch: ConvergenceStep.Open,
    ) {
        internal fun isPending(expectedOwner: SessionTopology): Boolean = (owner === expectedOwner) &&
                (expectedOwner.captureOpenPending === dispatch)

        internal fun referencesPlan(expectedPlan: CapturePlan): Boolean = dispatch.plan === expectedPlan

    }

    internal class CaptureApplyResultCandidate(
        private val owner: SessionTopology,
        private val dispatch: ConvergenceStep.Apply,
    ) {
        internal fun isPending(expectedOwner: SessionTopology): Boolean = (owner === expectedOwner) &&
                (expectedOwner.captureApplyPending === dispatch)

        internal fun isCurrent(expectedOwner: SessionTopology): Boolean = isPending(expectedOwner) &&
                (expectedOwner.pendingRevision == dispatch.revision) &&
                (expectedOwner.desiredRevision == dispatch.revision) &&
                (expectedOwner.currentPlan()?.capturePlan === dispatch.plan)

        internal fun referencesPlan(expectedPlan: CapturePlan): Boolean = dispatch.plan === expectedPlan

    }

    internal class EncodingReconcileResultCandidate(
        private val owner: SessionTopology,
        private val dispatch: ConvergenceStep.ReconcileEncoding,
    ) {
        internal fun isPending(expectedOwner: SessionTopology): Boolean = (owner === expectedOwner) &&
                (expectedOwner.encodingPending === dispatch)

        internal fun isCurrent(expectedOwner: SessionTopology): Boolean = isPending(expectedOwner) &&
                (expectedOwner.pendingRevision == dispatch.revision) &&
                (expectedOwner.desiredRevision == dispatch.revision) &&
                (expectedOwner.currentPlan()?.encoderPlan === dispatch.plan)

    }

    internal class ProductionReadiness(
        private val owner: SessionTopology,
        private val generation: Long,
        internal val revision: Long,
        internal val parameters: ScreenCaptureParameters,
        internal val plan: SessionPlanResolution.Resolved,
    ) {
        internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
            (owner === expectedOwner) && (generation == expectedOwner.generation) &&
                    expectedOwner.acceptsSettledRevision(revision) && expectedOwner.productionReady(plan)

        internal fun isCachedImageCurrent(expectedOwner: SessionTopology, cachedParameters: ScreenCaptureEffectiveParameters): Boolean =
            isCurrent(expectedOwner) && expectedOwner.cacheIsCurrent(this, cachedParameters)

    }

    internal sealed interface ActiveAssessment {
        data object NotReady : ActiveAssessment
        data object AlreadyPublished : ActiveAssessment

        class Candidate(
            private val owner: SessionTopology,
            private val generation: Long,
            internal val revision: Long,
            internal val plan: SessionPlanResolution.Resolved,
            private val metricsSnapshot: MetricsSnapshot,
            private val requireCompletionCloseSettlement: Boolean,
            internal val isCapturedContentVisible: Boolean?,
            private val platformSdkInt: Int,
        ) : ActiveAssessment {
            internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (generation == expectedOwner.generation) &&
                        expectedOwner.activeReady(plan, revision, platformSdkInt) &&
                        (expectedOwner.isCapturedContentVisible == isCapturedContentVisible) &&
                        (expectedOwner.lastMetricsSnapshot === metricsSnapshot) &&
                        metricsSnapshot.isReady(requireCompletionCloseSettlement)

            internal fun acceptsSnapshot(expectedOwner: SessionTopology, snapshot: MetricsSnapshot): Boolean =
                isCurrent(expectedOwner) && (metricsSnapshot === snapshot)

            internal fun wasReservedBy(expectedOwner: SessionTopology): Boolean =
                (owner === expectedOwner) && (expectedOwner.desiredRevision == revision) &&
                        (expectedOwner.currentPlan() === plan) && (expectedOwner.lastMetricsSnapshot === metricsSnapshot)

            internal fun acceptsSettlementSnapshot(expectedOwner: SessionTopology, snapshot: MetricsSnapshot): Boolean =
                wasReservedBy(expectedOwner) && (expectedOwner.isCapturedContentVisible == isCapturedContentVisible) &&
                        (metricsSnapshot === snapshot) && snapshot.isReady(requireCompletionCloseSettlement) &&
                        expectedOwner.activeReady(plan, revision, platformSdkInt)

        }
    }

    internal sealed interface ActiveTargetDiagnostic {
        data object None : ActiveTargetDiagnostic
        class Selected(internal val targetMode: CaptureTargetMode) : ActiveTargetDiagnostic
        class Changed(internal val previous: CaptureTargetMode, internal val current: CaptureTargetMode) : ActiveTargetDiagnostic
    }

    internal class ActivePublicationCommit(
        private val owner: SessionTopology,
        private val generation: Long,
        private val candidate: ActiveAssessment.Candidate,
        internal val diagnostic: ActiveTargetDiagnostic,
    ) {
        internal fun isCurrent(expectedOwner: SessionTopology, snapshot: MetricsSnapshot): Boolean =
            (owner === expectedOwner) && (generation == expectedOwner.generation) &&
                    candidate.acceptsSettlementSnapshot(expectedOwner, snapshot)
    }

    internal class ActiveVisibilityPublication(
        private val owner: SessionTopology,
        private val generation: Long,
        private val publicPlan: SessionPlanResolution.Resolved,
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
        internal val isCapturedContentVisible: Boolean?,
    ) {
        internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
            (owner === expectedOwner) && (generation == expectedOwner.generation) && expectedOwner.active &&
                    (expectedOwner.publicEffectivePlan === publicPlan) &&
                    (expectedOwner.isCapturedContentVisible == isCapturedContentVisible) &&
                    (expectedOwner.lastPublishedCapturedContentVisibility != isCapturedContentVisible)
    }

    internal class PausedPublication(
        private val owner: SessionTopology,
        private val generation: Long,
        internal val revision: Long,
        internal val requestedParameters: ScreenCaptureParameters,
        internal val historicalEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val isCapturedContentVisible: Boolean?,
        internal val problem: ScreenCaptureProblem?,
    ) {
        internal fun isCurrent(expectedOwner: SessionTopology): Boolean =
            (owner === expectedOwner) && (generation == expectedOwner.generation) &&
                    (expectedOwner.desiredRevision == revision) &&
                    (expectedOwner.isCapturedContentVisible == isCapturedContentVisible)

    }

    internal class TerminalEvidence(
        private val owner: SessionTopology,
        internal val requestedParameters: ScreenCaptureParameters,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) {
        internal fun isCurrent(expectedOwner: SessionTopology): Boolean = owner === expectedOwner
    }

    private class ResizeGeometry(val widthPx: Int, val heightPx: Int) {
        init {
            require((widthPx > 0) && (heightPx > 0))
        }
    }

    private class Suspension(val revision: Long, val problem: ScreenCaptureProblem)

    private class PlanGeometryInputs(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val sourceDimensionsAreAuthoritative: Boolean,
    )

    private class CapturedContentResizeState {
        sealed interface Arrival {
            data object Ignored : Arrival
            data object PendingProjection : Arrival
            data object Unchanged : Arrival
            class Accept(val geometry: ResizeGeometry, val isInitial: Boolean) : Arrival
        }

        private var identity: CaptureProjectionIdentity? = null
        private var projectionBound = false
        private var pendingGeometry: ResizeGeometry? = null
        private var acceptedGeometry: ResizeGeometry? = null
        var hasPendingRevision = false

        val hasAcceptedGeometry: Boolean get() = acceptedGeometry != null
        val acceptedWidthPx: Int get() = acceptedGeometry?.widthPx ?: 0
        val acceptedHeightPx: Int get() = acceptedGeometry?.heightPx ?: 0

        fun bindProjection(projectionIdentity: CaptureProjectionIdentity): Boolean {
            check(!projectionBound)
            val currentIdentity = identity
            check((currentIdentity == null) || (currentIdentity === projectionIdentity))
            val geometry = pendingGeometry
            check((geometry == null) || (acceptedGeometry == null))
            if (currentIdentity == null) identity = projectionIdentity
            projectionBound = true
            if (geometry == null) return false
            pendingGeometry = null
            acceptedGeometry = geometry
            hasPendingRevision = true
            return true
        }

        fun classify(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int): Arrival {
            val geometry = ResizeGeometry(widthPx, heightPx)
            val accepted = acceptedGeometry
            if ((accepted != null) && (accepted.widthPx == widthPx) && (accepted.heightPx == heightPx)) return Arrival.Unchanged
            if (accepted != null) return Arrival.Accept(geometry, isInitial = false)
            val current = identity
            if (current == null) identity = projectionIdentity else if (current !== projectionIdentity) return Arrival.Ignored
            if (!projectionBound) {
                pendingGeometry = geometry
                return Arrival.PendingProjection
            }
            return Arrival.Accept(geometry, isInitial = true)
        }

        fun accept(geometry: ResizeGeometry, isInitial: Boolean) {
            check((acceptedGeometry == null) == isInitial)
            acceptedGeometry = geometry
            hasPendingRevision = true
        }
    }

    private var generation = 0L
    private var nextRevision = 0L
    private var pendingRevision = 0L
    private var pendingParameters = ScreenCaptureParameters()
    private var desiredRevision = 0L
    private var desiredParameters = ScreenCaptureParameters()
    private var suspension: Suspension? = null

    private var metricsAttachmentClaimed = false
    private var metricsCompletionDiagnosticClaimed = false
    private var lastMetricsSnapshot: MetricsSnapshot? = null

    private var resolvedPlan: SessionPlanResolution.Resolved? = null
    private var resolvedPlanRevision = 0L
    private var captureOpenStarted = false
    private var captureOpenPending: ConvergenceStep.Open? = null
    private var captureOpened = false
    private var captureAppliedPlan: CapturePlan? = null
    private var captureApplyPending: ConvergenceStep.Apply? = null
    private var encodingPending: ConvergenceStep.ReconcileEncoding? = null
    private var encoderReadyPlan: Rgba8888Layout? = null

    private var active = false
    private var publicEffectivePlan: SessionPlanResolution.Resolved? = null
    private var isCapturedContentVisible: Boolean? = null
    private var lastPublishedCapturedContentVisibility: Boolean? = null
    private var lastPublishedPausedRevision = 0L
    private var lastPublishedPauseProblem: ScreenCaptureProblem? = null
    private var lastEffectiveParameters: ScreenCaptureEffectiveParameters? = null
    private var lastPublishedTargetMode: CaptureTargetMode? = null
    private val capturedContentResize = CapturedContentResizeState()

    internal fun claimMetricsAttachment(): Boolean {
        if (metricsAttachmentClaimed) return false
        metricsAttachmentClaimed = true
        advanceGeneration()
        return true
    }

    internal fun claimMetricsCompletionDiagnostic(): Boolean {
        if (metricsCompletionDiagnosticClaimed) return false
        metricsCompletionDiagnosticClaimed = true
        advanceGeneration()
        return true
    }

    internal fun initialize(parameters: ScreenCaptureParameters) {
        check((nextRevision == 0L) && (pendingRevision == 0L) && (desiredRevision == 0L))
        nextRevision = 1L
        pendingRevision = 1L
        pendingParameters = parameters
        desiredRevision = 1L
        desiredParameters = parameters
        advanceGeneration()
    }

    internal fun prepareParameterUpdate(parameters: ScreenCaptureParameters): ParameterUpdate? {
        if (parameters == pendingParameters) return null
        return ParameterUpdate(parameters)
    }

    internal fun commitParameterUpdate(candidate: ParameterUpdate) {
        val revision = allocateRevision()
        pendingRevision = revision
        pendingParameters = candidate.parameters
        advanceGeneration()
    }

    internal fun prepareDesiredIngress(): DesiredTransition? {
        if (pendingRevision == desiredRevision) return null
        val publishedPlan = publicEffectivePlan
        val historical = publishedPlan?.effectiveParameters ?: lastEffectiveParameters
        val unavailable = lastMetricsSnapshot?.let { it.metrics == null } == true
        val problem = if (unavailable && suspension?.problem == ScreenCaptureProblem.CaptureUnavailable) {
            ScreenCaptureProblem.CaptureUnavailable
        } else {
            null
        }
        return DesiredTransition(
            previousParameters = desiredParameters,
            parameters = pendingParameters,
            revision = pendingRevision,
            isCachedImageCompatible = sameCachedImageParameters(desiredParameters, pendingParameters),
            historicalEffectiveParameters = historical,
            problem = problem,
        )
    }

    internal fun commitDesired(candidate: DesiredTransition): PausedPublication? {
        check((candidate.revision == pendingRevision) && (candidate.parameters === pendingParameters))
        suspension = null
        active = false
        desiredRevision = candidate.revision
        desiredParameters = candidate.parameters
        advanceGeneration()
        val historical = candidate.historicalEffectiveParameters ?: return null
        return PausedPublication(
            owner = this,
            generation = generation,
            revision = desiredRevision,
            requestedParameters = desiredParameters,
            historicalEffectiveParameters = historical,
            isCapturedContentVisible = isCapturedContentVisible,
            problem = candidate.problem,
        )
    }

    internal fun prepareMetrics(snapshot: MetricsSnapshot, platformSdkInt: Int, requireCompletionCloseSettlement: Boolean): MetricsDecision {
        if (snapshot === lastMetricsSnapshot) return MetricsDecision.Duplicate
        snapshot.failure?.let { return MetricsDecision.Failed(it) }
        val previousSnapshot = lastMetricsSnapshot
        val publishedPlan = publicEffectivePlan
        val requiresTopologyRevision = when {
            (previousSnapshot != null) && sameMetricsAuthority(previousSnapshot.metrics, snapshot.metrics, platformSdkInt) -> false
            snapshot.metrics != null -> true
            else -> lastEffectiveParameters != null
        }
        if (requiresTopologyRevision && (pendingRevision != desiredRevision)) {
            return MetricsDecision.BlockedByPendingIngress
        }
        return MetricsDecision.Update(
            snapshot = snapshot,
            requiresTopologyRevision = requiresTopologyRevision,
            closesActiveAdmission = (publishedPlan != null) &&
                    (previousSnapshot?.isReady(requireCompletionCloseSettlement) == true) &&
                    (!snapshot.isReady(requireCompletionCloseSettlement)),
            wasActive = publishedPlan != null,
            historicalEffectiveParameters = lastEffectiveParameters,
        )
    }

    internal fun commitMetrics(candidate: MetricsDecision.Update): Long? {
        check((lastMetricsSnapshot !== candidate.snapshot) && (!candidate.requiresTopologyRevision || (pendingRevision == desiredRevision)))
        val topologyRevision = if (candidate.requiresTopologyRevision) allocateRevision() else null
        topologyRevision?.let(::commitAllocatedTopologyRevision)
        if (candidate.closesActiveAdmission) active = false
        lastMetricsSnapshot = candidate.snapshot
        advanceGeneration()
        return topologyRevision
    }

    internal fun preparePendingResizeRevision(): PendingResizeDecision = when {
        !capturedContentResize.hasPendingRevision -> PendingResizeDecision.None
        pendingRevision != desiredRevision -> PendingResizeDecision.BlockedByPendingIngress
        else -> PendingResizeDecision.RevisionCandidate
    }

    internal fun commitPendingResizeRevision(): Long {
        check(capturedContentResize.hasPendingRevision)
        val topologyRevision = allocateRevision()
        capturedContentResize.hasPendingRevision = false
        commitAllocatedTopologyRevision(topologyRevision)
        advanceGeneration()
        return topologyRevision
    }

    internal fun resolvePlan(platformSdkInt: Int, requireCompletionCloseSettlement: Boolean): PlanDecision {
        if (pendingRevision != desiredRevision) return PlanDecision.WaitingForIngress
        if (suspension?.revision == desiredRevision) return PlanDecision.Suspended
        if (currentPlan() != null) return PlanDecision.Current
        val geometryInputs = resolvePlanGeometryInputs(platformSdkInt, requireCompletionCloseSettlement) ?: return PlanDecision.WaitingForMetrics
        return when (val resolution = SessionPlanResolution.resolve(
            parameters = desiredParameters,
            widthPx = geometryInputs.widthPx,
            heightPx = geometryInputs.heightPx,
            densityDpi = geometryInputs.densityDpi,
            platformSdkInt = platformSdkInt,
            sourceDimensionsAreAuthoritative = geometryInputs.sourceDimensionsAreAuthoritative,
        )) {
            is SessionPlanResolution.Rejected -> PlanDecision.Rejected(this, generation, desiredRevision, resolution.problem, resolution.cause)
            is SessionPlanResolution.Resolved -> {
                val previous = resolvedPlan
                PlanDecision.Install(
                    owner = this,
                    generation = generation,
                    revision = desiredRevision,
                    plan = resolution,
                    pausesActiveTopology = (active) && (previous != null) &&
                            (!previous.capturePlan.hasSameCaptureConfigurationAs(resolution.capturePlan)),
                )
            }
        }
    }

    internal fun commitPlan(candidate: PlanDecision.Install) {
        check((candidate.isCurrent(this)) && (candidate.revision == desiredRevision))
        resolvedPlan = candidate.plan
        resolvedPlanRevision = candidate.revision
        if (candidate.pausesActiveTopology) active = false
        advanceGeneration()
    }

    internal fun commitRejectedPlan(candidate: PlanDecision.Rejected) {
        check((candidate.isCurrent(this)) && (candidate.revision == desiredRevision))
        resolvedPlanRevision = 0L
        advanceGeneration()
    }

    internal fun nextConvergence(productionMaterialized: Boolean): ConvergenceStep {
        if (pendingRevision != desiredRevision) return ConvergenceStep.Waiting
        val plan = currentPlan() ?: return ConvergenceStep.Waiting
        if (productionMaterialized) return ConvergenceStep.Waiting
        if (captureOpenPending != null) return ConvergenceStep.Waiting
        if (!captureOpenStarted) {
            return ConvergenceStep.Open(this, generation, desiredRevision, plan.capturePlan)
        }
        if (!captureOpened) return ConvergenceStep.Waiting
        if (captureApplyPending != null) return ConvergenceStep.Waiting
        if (captureAppliedPlan?.hasSameCaptureConfigurationAs(plan.capturePlan) != true) {
            return ConvergenceStep.Apply(this, generation, desiredRevision, plan.capturePlan)
        }
        if (encodingPending != null) return ConvergenceStep.Waiting
        if (encoderReadyPlan !== plan.encoderPlan) {
            return ConvergenceStep.ReconcileEncoding(this, generation, desiredRevision, plan.encoderPlan)
        }
        return ConvergenceStep.Ready
    }

    internal fun commitCaptureOpenDispatch(candidate: ConvergenceStep.Open) {
        check((candidate.isCurrent(this)) && (!captureOpenStarted) && (captureOpenPending == null))
        captureOpenStarted = true
        captureOpenPending = candidate
        advanceGeneration()
    }

    internal fun settleCaptureOpenDispatchFailure(candidate: ConvergenceStep.Open) {
        check(captureOpenPending === candidate)
        captureOpenPending = null
        advanceGeneration()
    }

    internal fun prepareCaptureOpenResultCandidate(plan: CapturePlan): CaptureOpenResultCandidate? {
        val pending = captureOpenPending ?: return null
        if (pending.plan !== plan) return null
        return CaptureOpenResultCandidate(this, pending)
    }

    internal fun commitCaptureOpenResult(
        candidate: CaptureOpenResultCandidate,
        appliedPlan: CapturePlan,
        projectionIdentity: CaptureProjectionIdentity,
    ): Boolean {
        check(candidate.isPending(this) && candidate.referencesPlan(appliedPlan))
        val isPendingResizeAccepted = capturedContentResize.bindProjection(projectionIdentity)
        captureOpenPending = null
        captureOpened = true
        captureAppliedPlan = appliedPlan
        advanceGeneration()
        return isPendingResizeAccepted
    }

    internal fun commitCaptureOpenFailure(candidate: CaptureOpenResultCandidate) {
        check(candidate.isPending(this))
        captureOpenPending = null
        captureOpened = false
        captureAppliedPlan = null
        advanceGeneration()
    }

    internal fun commitCaptureApplyDispatch(candidate: ConvergenceStep.Apply) {
        check((candidate.isCurrent(this)) && (captureOpened) && (captureApplyPending == null))
        captureApplyPending = candidate
        advanceGeneration()
    }

    internal fun rollbackCaptureApplyDispatch(candidate: ConvergenceStep.Apply) {
        check(captureApplyPending === candidate)
        captureApplyPending = null
        advanceGeneration()
    }

    internal fun isCurrentCaptureApplyDispatch(candidate: ConvergenceStep.Apply): Boolean =
        (captureApplyPending === candidate) && (pendingRevision == desiredRevision) &&
                (desiredRevision == candidate.revision) && (currentPlan()?.capturePlan === candidate.plan)

    internal fun prepareCaptureApplyResultCandidate(revision: Long, plan: CapturePlan): CaptureApplyResultCandidate? {
        val pending = captureApplyPending ?: return null
        if ((pending.revision != revision) || (pending.plan !== plan)) return null
        return CaptureApplyResultCandidate(this, pending)
    }

    internal fun commitCaptureApplyResult(candidate: CaptureApplyResultCandidate, appliedPlan: CapturePlan?) {
        check((candidate.isPending(this)) && ((appliedPlan == null) || (candidate.referencesPlan(appliedPlan))))
        captureApplyPending = null
        appliedPlan?.let { captureAppliedPlan = it }
        advanceGeneration()
    }

    internal fun commitEncodingDispatch(candidate: ConvergenceStep.ReconcileEncoding) {
        check((candidate.isCurrent(this)) && (encodingPending == null))
        encodingPending = candidate
        encoderReadyPlan = null
        advanceGeneration()
    }

    internal fun rollbackEncodingDispatch(candidate: ConvergenceStep.ReconcileEncoding) {
        check(encodingPending === candidate)
        encodingPending = null
        advanceGeneration()
    }

    internal fun isCurrentEncodingDispatch(candidate: ConvergenceStep.ReconcileEncoding): Boolean =
        (encodingPending === candidate) && (pendingRevision == desiredRevision) &&
                (desiredRevision == candidate.revision) && (currentPlan()?.encoderPlan === candidate.plan)

    internal fun prepareEncodingReconcileResultCandidate(revision: Long, plan: Rgba8888Layout): EncodingReconcileResultCandidate? {
        val pending = encodingPending ?: return null
        if ((pending.revision != revision) || (pending.plan !== plan)) return null
        return EncodingReconcileResultCandidate(this, pending)
    }

    internal fun commitEncodingResult(candidate: EncodingReconcileResultCandidate, ready: Boolean) {
        check(candidate.isPending(this))
        val pending = checkNotNull(encodingPending)
        encodingPending = null
        encoderReadyPlan = pending.plan.takeIf { ready }
        advanceGeneration()
    }

    internal fun invalidateEncodingReadiness(): Boolean {
        val readyPlan = encoderReadyPlan ?: return false
        val invalidatesActivePlan = publicEffectivePlan?.encoderPlan === readyPlan
        encoderReadyPlan = null
        if (invalidatesActivePlan) active = false
        advanceGeneration()
        return invalidatesActivePlan
    }

    internal fun prepareProductionReadiness(): ProductionReadiness? {
        if (pendingRevision != desiredRevision) return null
        val plan = currentPlan() ?: return null
        if (!productionReady(plan)) return null
        return ProductionReadiness(this, generation, desiredRevision, desiredParameters, plan)
    }

    internal fun assessActivePublication(
        platformSdkInt: Int,
        snapshot: MetricsSnapshot,
        requireCompletionCloseSettlement: Boolean,
    ): ActiveAssessment {
        if (active) return ActiveAssessment.AlreadyPublished
        val plan = currentPlan() ?: return ActiveAssessment.NotReady
        if ((lastMetricsSnapshot !== snapshot) || (!snapshot.isReady(requireCompletionCloseSettlement))) {
            return ActiveAssessment.NotReady
        }
        if (!activeReady(plan, desiredRevision, platformSdkInt)) return ActiveAssessment.NotReady
        return ActiveAssessment.Candidate(
            owner = this,
            generation = generation,
            revision = desiredRevision,
            plan = plan,
            metricsSnapshot = snapshot,
            requireCompletionCloseSettlement = requireCompletionCloseSettlement,
            isCapturedContentVisible = isCapturedContentVisible,
            platformSdkInt = platformSdkInt,
        )
    }

    internal fun commitActivePublication(
        candidate: ActiveAssessment.Candidate,
        isFirstPublicAssignment: Boolean,
    ): ActivePublicationCommit {
        check(candidate.isCurrent(this))
        lastEffectiveParameters = candidate.plan.effectiveParameters
        lastPublishedCapturedContentVisibility = candidate.isCapturedContentVisible
        lastPublishedPausedRevision = 0L
        lastPublishedPauseProblem = null
        suspension = null
        publicEffectivePlan = candidate.plan
        val targetMode = candidate.plan.capturePlan.targetMode
        val previous = lastPublishedTargetMode
        lastPublishedTargetMode = targetMode
        active = false
        advanceGeneration()
        val diagnostic = when {
            isFirstPublicAssignment -> ActiveTargetDiagnostic.Selected(targetMode)
            (previous != null) && (previous != targetMode) -> ActiveTargetDiagnostic.Changed(previous, targetMode)
            else -> ActiveTargetDiagnostic.None
        }
        return ActivePublicationCommit(this, generation, candidate, diagnostic)
    }

    internal fun settleActivePublication(canContinueOrdinaryWork: Boolean) {
        if (!canContinueOrdinaryWork) return
        active = true
        advanceGeneration()
    }

    internal fun prepareActiveVisibility(): ActiveVisibilityPublication? {
        val plan = publicEffectivePlan ?: return null
        if (!active || lastPublishedCapturedContentVisibility == isCapturedContentVisible) return null
        return ActiveVisibilityPublication(
            owner = this,
            generation = generation,
            publicPlan = plan,
            effectiveParameters = plan.effectiveParameters,
            isCapturedContentVisible = isCapturedContentVisible,
        )
    }

    internal fun commitActiveVisibility(candidate: ActiveVisibilityPublication) {
        check(candidate.isCurrent(this))
        lastPublishedCapturedContentVisibility = candidate.isCapturedContentVisible
    }

    internal fun prepareSuspension(problem: ScreenCaptureProblem): PausedPublication? {
        require(
            (problem == ScreenCaptureProblem.InvalidRequest) ||
                    (problem == ScreenCaptureProblem.CaptureUnavailable) ||
                    (problem == ScreenCaptureProblem.ResourceExhausted),
        )
        val historical = lastEffectiveParameters ?: return null
        if ((lastPublishedPausedRevision == desiredRevision) && (lastPublishedPauseProblem == problem) &&
            (lastPublishedCapturedContentVisibility == isCapturedContentVisible)
        ) {
            return null
        }
        return PausedPublication(
            owner = this,
            generation = generation,
            revision = desiredRevision,
            requestedParameters = desiredParameters,
            historicalEffectiveParameters = historical,
            isCapturedContentVisible = isCapturedContentVisible,
            problem = problem,
        )
    }

    internal fun prepareReconfiguration(): PausedPublication? {
        val historical = publicEffectivePlan?.effectiveParameters ?: return null
        if (suspension?.revision == desiredRevision) return null
        if ((lastPublishedPausedRevision == desiredRevision) && (lastPublishedPauseProblem == null) &&
            (lastPublishedCapturedContentVisibility == isCapturedContentVisible)
        ) {
            return null
        }
        return PausedPublication(
            owner = this,
            generation = generation,
            revision = desiredRevision,
            requestedParameters = desiredParameters,
            historicalEffectiveParameters = historical,
            isCapturedContentVisible = isCapturedContentVisible,
            problem = null,
        )
    }

    internal fun preparePausedVisibility(): PausedPublication? {
        val historical = lastEffectiveParameters ?: return null
        if ((active) || (lastPublishedCapturedContentVisibility == isCapturedContentVisible)) return null
        val problem = suspension?.takeIf { it.revision == desiredRevision }?.problem
        return PausedPublication(
            owner = this,
            generation = generation,
            revision = desiredRevision,
            requestedParameters = desiredParameters,
            historicalEffectiveParameters = historical,
            isCapturedContentVisible = isCapturedContentVisible,
            problem = problem,
        )
    }

    internal fun commitPausedPublication(candidate: PausedPublication) {
        check(candidate.isCurrent(this))
        val committedSuspension = candidate.problem?.let { Suspension(candidate.revision, it) }
        active = false
        publicEffectivePlan = null
        lastPublishedCapturedContentVisibility = candidate.isCapturedContentVisible
        lastPublishedPausedRevision = candidate.revision
        lastPublishedPauseProblem = candidate.problem
        suspension = committedSuspension
        advanceGeneration()
    }

    internal fun prepareTerminalEvidence(): TerminalEvidence = TerminalEvidence(
        owner = this,
        requestedParameters = pendingParameters,
        lastEffectiveParameters = lastEffectiveParameters,
    )

    internal fun invalidateActiveTopology() {
        if (!active && (publicEffectivePlan == null)) return
        active = false
        publicEffectivePlan = null
        advanceGeneration()
    }

    internal fun acceptsSettledRevision(expectedRevision: Long): Boolean =
        (pendingRevision == expectedRevision) && (desiredRevision == expectedRevision)

    internal val isActive: Boolean
        get() = active

    internal fun isActiveFor(expectedRevision: Long): Boolean = (active) && (acceptsSettledRevision(expectedRevision))

    internal fun recordCapturedContentVisibility(isVisible: Boolean) {
        if (isCapturedContentVisible == isVisible) return
        isCapturedContentVisible = isVisible
        advanceGeneration()
    }

    internal fun recordCapturedResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) {
        when (val arrival = capturedContentResize.classify(projectionIdentity, widthPx, heightPx)) {
            CapturedContentResizeState.Arrival.Ignored, CapturedContentResizeState.Arrival.Unchanged -> Unit
            CapturedContentResizeState.Arrival.PendingProjection -> advanceGeneration()
            is CapturedContentResizeState.Arrival.Accept -> {
                capturedContentResize.accept(arrival.geometry, arrival.isInitial)
                advanceGeneration()
            }
        }
    }

    private fun currentPlan(): SessionPlanResolution.Resolved? =
        resolvedPlan?.takeIf { resolvedPlanRevision == desiredRevision }

    private fun productionReady(plan: SessionPlanResolution.Resolved): Boolean =
        (currentPlan() === plan) && (encoderReadyPlan === plan.encoderPlan) && (encodingPending == null)

    private fun supportsAuthoritativeCapturedContentResize(platformSdkInt: Int): Boolean =
        platformSdkInt >= VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun activeReady(plan: SessionPlanResolution.Resolved, revision: Long, platformSdkInt: Int): Boolean =
        (currentPlan() === plan) && (acceptsSettledRevision(revision)) && (captureOpened) &&
                (captureApplyPending == null) &&
                (captureAppliedPlan?.hasSameCaptureConfigurationAs(plan.capturePlan) == true) &&
                (encoderReadyPlan === plan.encoderPlan) && (encodingPending == null) &&
                (!supportsAuthoritativeCapturedContentResize(platformSdkInt) || capturedContentResize.hasAcceptedGeometry)

    private fun resolvePlanGeometryInputs(
        platformSdkInt: Int,
        requireCompletionCloseSettlement: Boolean,
    ): PlanGeometryInputs? {
        val snapshot = lastMetricsSnapshot?.takeIf { it.isReady(requireCompletionCloseSettlement) } ?: return null
        val metrics = snapshot.metrics ?: return null
        val supportsResizeAuthority = supportsAuthoritativeCapturedContentResize(platformSdkInt)
        val resizeOwnsDimensions = (supportsResizeAuthority) && (capturedContentResize.hasAcceptedGeometry)
        return PlanGeometryInputs(
            widthPx = if (resizeOwnsDimensions) capturedContentResize.acceptedWidthPx else metrics.widthPx,
            heightPx = if (resizeOwnsDimensions) capturedContentResize.acceptedHeightPx else metrics.heightPx,
            densityDpi = metrics.densityDpi,
            sourceDimensionsAreAuthoritative = (!supportsResizeAuthority) || (resizeOwnsDimensions),
        )
    }

    private fun sameMetricsAuthority(previous: CaptureMetrics?, current: CaptureMetrics?, platformSdkInt: Int): Boolean {
        if ((previous == null) && (current == null)) return true
        if ((previous == null) || (current == null)) return false
        return if ((supportsAuthoritativeCapturedContentResize(platformSdkInt)) && (capturedContentResize.hasAcceptedGeometry)) {
            previous.densityDpi == current.densityDpi
        } else {
            previous == current
        }
    }

    private fun allocateRevision(): Long {
        val revision = Math.addExact(nextRevision, 1L)
        nextRevision = revision
        return revision
    }

    private fun commitAllocatedTopologyRevision(revision: Long) {
        check((revision == nextRevision) && (revision > desiredRevision) && (pendingRevision == desiredRevision))
        pendingRevision = revision
        desiredRevision = revision
        suspension = null
        active = false
    }

    private fun advanceGeneration() {
        generation += 1L
    }

    private fun cacheIsCurrent(candidate: ProductionReadiness, cachedParameters: ScreenCaptureEffectiveParameters): Boolean =
        (captureAppliedPlan?.hasSameCaptureConfigurationAs(candidate.plan.capturePlan) == true) &&
                (captureApplyPending == null) && (encoderReadyPlan === candidate.plan.encoderPlan) &&
                (encodingPending == null) &&
                (sameCachedEffectiveParameters(cachedParameters, candidate.plan.effectiveParameters))

    private fun sameCachedImageParameters(left: ScreenCaptureParameters, right: ScreenCaptureParameters): Boolean =
        (left.sourceRegion == right.sourceRegion) && (left.crop == right.crop) &&
                (left.outputSize == right.outputSize) && (left.rotation == right.rotation) &&
                (left.mirror == right.mirror) && (left.colorMode == right.colorMode) &&
                (left.jpegQuality == right.jpegQuality)

    private fun sameCachedEffectiveParameters(left: ScreenCaptureEffectiveParameters, right: ScreenCaptureEffectiveParameters): Boolean =
        (sameCachedImageParameters(left.appliedParameters, right.appliedParameters)) &&
                (left.captureGeometry == right.captureGeometry) && (left.appliedSourceRect == right.appliedSourceRect) &&
                (left.finalImageSize == right.finalImageSize)

}
