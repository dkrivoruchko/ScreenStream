package io.screenstream.capture.internal.session.topology

import android.os.Build
import io.mockk.mockk
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.FrameRate
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.capture.CaptureProjectionIdentity
import io.screenstream.capture.internal.metrics.MetricsAttachmentLifecycle
import io.screenstream.capture.internal.metrics.MetricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

internal class SessionTopologyReconciliationTest {
    // Verification: API-04
    // Verification: SES-03
    @Test
    fun pendingUpdatesConflateToNewestDesireAndEqualValueIsNoOp() {
        val topology = SessionTopology()
        val initial = ScreenCaptureParameters()
        topology.initialize(initial)
        val intermediate = initial.copy(frameRate = FrameRate.MaxFps(30))
        val newest = intermediate.copy(jpegQuality = 70)

        topology.commitParameterUpdate(topology.prepareParameterUpdate(intermediate) ?: error("missing intermediate update"))
        topology.commitParameterUpdate(topology.prepareParameterUpdate(newest) ?: error("missing newest update"))
        val transition = topology.prepareDesiredIngress() ?: error("missing desired transition")

        assertEquals(initial, transition.previousParameters)
        assertEquals(newest, transition.parameters)
        assertFalse(transition.isCachedImageCompatible)
        topology.commitDesired(transition)
        assertNull(topology.prepareDesiredIngress())
        assertNull(topology.prepareParameterUpdate(newest.copy()))
    }

    // Verification: SES-03
    @Test
    fun pacingOnlyUpdatePreservesCachedImageCompatibility() {
        val topology = SessionTopology()
        val initial = ScreenCaptureParameters()
        topology.initialize(initial)
        val pacingOnly = initial.copy(frameRate = FrameRate.SamplingInterval(1_001.milliseconds))

        topology.commitParameterUpdate(topology.prepareParameterUpdate(pacingOnly) ?: error("missing pacing update"))
        val transition = topology.prepareDesiredIngress() ?: error("missing desired transition")

        assertTrue(transition.isCachedImageCompatible)
    }

    // Verification: MET-03
    // Verification: SES-03
    @Test
    fun completedPositiveMetricsResolveBeforeCloseSettlementAndRetainExactSnapshotCurrentness() {
        val topology = SessionTopology()
        topology.initialize(ScreenCaptureParameters())
        val metrics = CaptureMetrics(widthPx = 100, heightPx = 200, densityDpi = 300)
        val beforeCloseSettlement = MetricsSnapshot(
            metrics = metrics,
            lifecycle = MetricsAttachmentLifecycle.Completed,
            handleAdopted = true,
            completionCloseSettled = false,
            failure = null,
        )
        val metricsUpdate = topology.prepareMetrics(
            snapshot = beforeCloseSettlement,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(metricsUpdate)

        val staleCandidate = topology.resolvePlan(platformSdkInt = 30) as SessionTopology.PlanDecision.Install
        assertEquals(metrics.widthPx, staleCandidate.plan.effectiveParameters.captureGeometry.widthPx)
        assertEquals(metrics.heightPx, staleCandidate.plan.effectiveParameters.captureGeometry.heightPx)
        assertEquals(metrics.densityDpi, staleCandidate.plan.effectiveParameters.captureGeometry.densityDpi)

        val afterCloseSettlement = MetricsSnapshot(
            metrics = metrics,
            lifecycle = MetricsAttachmentLifecycle.Completed,
            handleAdopted = true,
            completionCloseSettled = true,
            failure = null,
        )
        val settlementUpdate = topology.prepareMetrics(
            snapshot = afterCloseSettlement,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(settlementUpdate)

        assertFalse(staleCandidate.isCurrent(topology))
        assertTrue(topology.resolvePlan(platformSdkInt = 30) is SessionTopology.PlanDecision.Install)
    }

    // Verification: SES-03
    @Test
    fun visibilityChangeInvalidatesPlanCandidateButEqualValueIsNoOp() {
        val topology = SessionTopology()
        topology.initialize(ScreenCaptureParameters())
        val snapshot = MetricsSnapshot(
            metrics = CaptureMetrics(widthPx = 100, heightPx = 200, densityDpi = 300),
            lifecycle = MetricsAttachmentLifecycle.Live,
            handleAdopted = true,
            completionCloseSettled = false,
            failure = null,
        )
        val metricsUpdate = topology.prepareMetrics(
            snapshot = snapshot,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(metricsUpdate)
        val staleCandidate = topology.resolvePlan(
            platformSdkInt = 30,
        ) as SessionTopology.PlanDecision.Install

        topology.recordCapturedContentVisibility(isVisible = true)

        assertFalse(staleCandidate.isCurrent(topology))
        val currentCandidate = topology.resolvePlan(
            platformSdkInt = 30,
        ) as SessionTopology.PlanDecision.Install
        topology.recordCapturedContentVisibility(isVisible = true)
        assertTrue(currentCandidate.isCurrent(topology))
    }

    // Verification: SES-03
    @Test
    fun changedMetricsAuthorityInvalidatesThePreviouslyResolvedPlanCandidate() {
        val topology = SessionTopology()
        topology.initialize(ScreenCaptureParameters())
        val initialSnapshot = readyMetrics(widthPx = 100, heightPx = 200, densityDpi = 300)
        val initialUpdate = topology.prepareMetrics(
            snapshot = initialSnapshot,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(initialUpdate)
        val stalePlan = topology.resolvePlan(
            platformSdkInt = 30,
        ) as SessionTopology.PlanDecision.Install

        val changedSnapshot = readyMetrics(widthPx = 101, heightPx = 200, densityDpi = 300)
        val changedUpdate = topology.prepareMetrics(
            snapshot = changedSnapshot,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(changedUpdate)

        assertFalse(stalePlan.isCurrent(topology))
    }

    // Verification: SES-03
    @Test
    fun metricsTopologyRevisionWaitsForPendingDesiredParameterIngress() {
        val topology = SessionTopology()
        val initialParameters = ScreenCaptureParameters()
        topology.initialize(initialParameters)
        val initialSnapshot = readyMetrics(widthPx = 100, heightPx = 200, densityDpi = 300)
        val initialUpdate = topology.prepareMetrics(
            snapshot = initialSnapshot,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(initialUpdate)

        val requestedParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(30))
        topology.commitParameterUpdate(
            topology.prepareParameterUpdate(requestedParameters) ?: error("missing parameter ingress"),
        )
        val laterSnapshot = readyMetrics(widthPx = 120, heightPx = 200, densityDpi = 300)

        assertSame(
            SessionTopology.MetricsDecision.BlockedByPendingIngress,
            topology.prepareMetrics(
                snapshot = laterSnapshot,
                platformSdkInt = 30,
            ),
        )

        val desiredIngress = topology.prepareDesiredIngress() ?: error("missing desired ingress")
        topology.commitDesired(desiredIngress)
        val laterUpdate = topology.prepareMetrics(
            snapshot = laterSnapshot,
            platformSdkInt = 30,
        ) as SessionTopology.MetricsDecision.Update
        val metricsRevision = topology.commitMetrics(laterUpdate) ?: error("missing Metrics topology revision")

        assertTrue(metricsRevision > desiredIngress.revision)
        assertSame(
            SessionTopology.MetricsDecision.Duplicate,
            topology.prepareMetrics(
                snapshot = laterSnapshot,
                platformSdkInt = 30,
            ),
        )
    }

    // Verification: SES-03
    @Test
    fun deniedApplyWaitsUntilRelevantRequestGeometryOrAvailabilityRevision() {
        val requestCase = suspendedAfterDeniedApply(platformSdkInt = Build.VERSION_CODES.S_V2)
        assertSame(SessionTopology.ConvergenceStep.Waiting, requestCase.topology.nextConvergence(false))
        assertNull(requestCase.topology.prepareParameterUpdate(requestCase.deniedParameters.copy()))
        assertSame(SessionTopology.ConvergenceStep.Waiting, requestCase.topology.nextConvergence(false))

        val changedRequest = requestCase.deniedParameters.copy(outputSize = OutputSize.ScaleFactor(0.75))
        requestCase.topology.commitParameterUpdate(
            requestCase.topology.prepareParameterUpdate(changedRequest) ?: error("missing changed request"),
        )
        requestCase.topology.commitDesired(
            requestCase.topology.prepareDesiredIngress() ?: error("missing changed desired ingress"),
        )
        commitCurrentPlan(requestCase.topology, requestCase.platformSdkInt)
        val requestReopen = requestCase.topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Apply
        assertTrue(requestReopen.revision > requestCase.deniedRevision)

        val geometryCase = suspendedAfterDeniedApply(
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            authoritativeResize = true,
        )
        geometryCase.topology.recordCapturedResize(
            projectionIdentity = geometryCase.projectionIdentity,
            widthPx = METRICS_WIDTH_PX,
            heightPx = METRICS_HEIGHT_PX,
        )
        assertSame(
            SessionTopology.PendingResizeDecision.None,
            geometryCase.topology.preparePendingResizeRevision(),
        )
        assertSame(SessionTopology.ConvergenceStep.Waiting, geometryCase.topology.nextConvergence(false))
        geometryCase.topology.recordCapturedResize(
            projectionIdentity = geometryCase.projectionIdentity,
            widthPx = METRICS_WIDTH_PX + 20,
            heightPx = METRICS_HEIGHT_PX,
        )
        assertSame(
            SessionTopology.PendingResizeDecision.RevisionCandidate,
            geometryCase.topology.preparePendingResizeRevision(),
        )
        val geometryRevision = geometryCase.topology.commitPendingResizeRevision()
        commitCurrentPlan(geometryCase.topology, geometryCase.platformSdkInt)
        val geometryReopen = geometryCase.topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Apply
        assertEquals(geometryRevision, geometryReopen.revision)
        assertTrue(geometryReopen.revision > geometryCase.deniedRevision)

        val availabilityCase = suspendedAfterDeniedApply(platformSdkInt = Build.VERSION_CODES.S_V2)
        val sameAuthority = readyMetrics(METRICS_WIDTH_PX, METRICS_HEIGHT_PX, METRICS_DENSITY_DPI)
        val sameAuthorityUpdate = availabilityCase.topology.prepareMetrics(
            snapshot = sameAuthority,
            platformSdkInt = availabilityCase.platformSdkInt,
        ) as SessionTopology.MetricsDecision.Update
        assertNull(availabilityCase.topology.commitMetrics(sameAuthorityUpdate))
        assertSame(SessionTopology.ConvergenceStep.Waiting, availabilityCase.topology.nextConvergence(false))
        assertSame(
            SessionTopology.MetricsDecision.Duplicate,
            availabilityCase.topology.prepareMetrics(sameAuthority, availabilityCase.platformSdkInt),
        )

        val unavailable = unavailableMetricsSnapshot()
        val unavailableUpdate = availabilityCase.topology.prepareMetrics(
            snapshot = unavailable,
            platformSdkInt = availabilityCase.platformSdkInt,
        ) as SessionTopology.MetricsDecision.Update
        availabilityCase.topology.commitMetrics(unavailableUpdate)
        assertSame(SessionTopology.ConvergenceStep.Waiting, availabilityCase.topology.nextConvergence(false))
        assertSame(
            SessionTopology.MetricsDecision.Duplicate,
            availabilityCase.topology.prepareMetrics(unavailable, availabilityCase.platformSdkInt),
        )

        val availableAgain = readyMetrics(METRICS_WIDTH_PX, METRICS_HEIGHT_PX, METRICS_DENSITY_DPI)
        val availableUpdate = availabilityCase.topology.prepareMetrics(
            snapshot = availableAgain,
            platformSdkInt = availabilityCase.platformSdkInt,
        ) as SessionTopology.MetricsDecision.Update
        val availableRevision = availabilityCase.topology.commitMetrics(availableUpdate)
            ?: error("missing available Metrics revision")
        commitCurrentPlan(availabilityCase.topology, availabilityCase.platformSdkInt)
        val availabilityReopen = availabilityCase.topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Apply
        assertEquals(availableRevision, availabilityReopen.revision)
        assertTrue(availabilityReopen.revision > availabilityCase.deniedRevision)
    }

    private class SuspendedTopology(
        val topology: SessionTopology,
        val platformSdkInt: Int,
        val projectionIdentity: CaptureProjectionIdentity,
        val deniedParameters: ScreenCaptureParameters,
        val deniedRevision: Long,
    )

    private fun suspendedAfterDeniedApply(
        platformSdkInt: Int,
        authoritativeResize: Boolean = false,
    ): SuspendedTopology {
        val topology = SessionTopology()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val deniedParameters = initialParameters.copy(outputSize = OutputSize.ScaleFactor(0.5))
        val metrics = readyMetrics(METRICS_WIDTH_PX, METRICS_HEIGHT_PX, METRICS_DENSITY_DPI)
        val projectionIdentity = mockk<CaptureProjectionIdentity>()
        topology.initialize(initialParameters)
        topology.commitMetrics(
            topology.prepareMetrics(metrics, platformSdkInt) as SessionTopology.MetricsDecision.Update,
        )
        val initialPlan = commitCurrentPlan(topology, platformSdkInt)
        val open = topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Open
        topology.commitCaptureOpenDispatch(open)
        if (authoritativeResize) {
            topology.recordCapturedResize(
                projectionIdentity,
                METRICS_WIDTH_PX,
                METRICS_HEIGHT_PX,
            )
        }
        val openResult = topology.prepareCaptureOpenResultCandidate(initialPlan.plan.capturePlan)
            ?: error("missing Capture Open result candidate")
        val acceptedPendingResize = topology.commitCaptureOpenResult(
            candidate = openResult,
            appliedPlan = initialPlan.plan.capturePlan,
            projectionIdentity = projectionIdentity,
        )
        if (authoritativeResize) {
            assertTrue(acceptedPendingResize)
            topology.commitPendingResizeRevision()
            commitCurrentPlan(topology, platformSdkInt)
            val definitiveApply = topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Apply
            topology.commitCaptureApplyDispatch(definitiveApply)
            val definitiveResult = topology.prepareCaptureApplyResultCandidate(
                definitiveApply.revision,
                definitiveApply.plan,
            ) ?: error("missing definitive Capture Apply result candidate")
            topology.commitCaptureApplyResult(definitiveResult, appliedPlan = definitiveApply.plan)
        } else {
            assertFalse(acceptedPendingResize)
        }
        val encoding = topology.nextConvergence(false) as SessionTopology.ConvergenceStep.ReconcileEncoding
        topology.commitEncodingDispatch(encoding)
        val encodingResult = topology.prepareEncodingReconcileResultCandidate(encoding.revision, encoding.plan)
            ?: error("missing Encoding reconcile result candidate")
        topology.commitEncodingResult(encodingResult, ready = true)
        val active = topology.assessActivePublication(platformSdkInt, metrics) as SessionTopology.ActiveAssessment.Candidate
        topology.commitActivePublication(active, isFirstPublicAssignment = true)
        topology.settleActivePublication(canContinueOrdinaryWork = true)

        topology.commitParameterUpdate(
            topology.prepareParameterUpdate(deniedParameters) ?: error("missing denied request"),
        )
        topology.commitDesired(topology.prepareDesiredIngress() ?: error("missing denied desired ingress"))
        commitCurrentPlan(topology, platformSdkInt)
        val deniedApply = topology.nextConvergence(false) as SessionTopology.ConvergenceStep.Apply
        topology.commitCaptureApplyDispatch(deniedApply)
        val deniedResult = topology.prepareCaptureApplyResultCandidate(deniedApply.revision, deniedApply.plan)
            ?: error("missing denied Capture Apply result candidate")
        topology.commitCaptureApplyResult(deniedResult, appliedPlan = null)
        val suspension = topology.prepareSuspension(ScreenCaptureProblem.ResourceExhausted)
            ?: error("missing denied Apply suspension")
        topology.commitPausedPublication(suspension)
        return SuspendedTopology(
            topology = topology,
            platformSdkInt = platformSdkInt,
            projectionIdentity = projectionIdentity,
            deniedParameters = deniedParameters,
            deniedRevision = deniedApply.revision,
        )
    }

    private fun commitCurrentPlan(
        topology: SessionTopology,
        platformSdkInt: Int,
    ): SessionTopology.PlanDecision.Install =
        (topology.resolvePlan(platformSdkInt) as SessionTopology.PlanDecision.Install).also(topology::commitPlan)

    private fun readyMetrics(widthPx: Int, heightPx: Int, densityDpi: Int): MetricsSnapshot = MetricsSnapshot(
        metrics = CaptureMetrics(widthPx, heightPx, densityDpi),
        lifecycle = MetricsAttachmentLifecycle.Live,
        handleAdopted = true,
        completionCloseSettled = false,
        failure = null,
    )

    private fun unavailableMetricsSnapshot(): MetricsSnapshot = MetricsSnapshot(
        metrics = null,
        lifecycle = MetricsAttachmentLifecycle.Live,
        handleAdopted = true,
        completionCloseSettled = false,
        failure = null,
    )

    private companion object {
        const val METRICS_WIDTH_PX = 100
        const val METRICS_HEIGHT_PX = 200
        const val METRICS_DENSITY_DPI = 300
    }

}
