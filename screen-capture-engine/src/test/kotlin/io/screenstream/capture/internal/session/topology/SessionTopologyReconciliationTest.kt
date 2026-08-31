package io.screenstream.capture.internal.session.topology

import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.FrameRate
import io.screenstream.capture.ScreenCaptureParameters
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
            requireCompletionCloseSettlement = false,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(metricsUpdate)
        val staleCandidate = topology.resolvePlan(
            platformSdkInt = 30,
            requireCompletionCloseSettlement = false,
        ) as SessionTopology.PlanDecision.Install

        topology.recordCapturedContentVisibility(isVisible = true)

        assertFalse(staleCandidate.isCurrent(topology))
        val currentCandidate = topology.resolvePlan(
            platformSdkInt = 30,
            requireCompletionCloseSettlement = false,
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
            requireCompletionCloseSettlement = false,
        ) as SessionTopology.MetricsDecision.Update
        topology.commitMetrics(initialUpdate)
        val stalePlan = topology.resolvePlan(
            platformSdkInt = 30,
            requireCompletionCloseSettlement = false,
        ) as SessionTopology.PlanDecision.Install

        val changedSnapshot = readyMetrics(widthPx = 101, heightPx = 200, densityDpi = 300)
        val changedUpdate = topology.prepareMetrics(
            snapshot = changedSnapshot,
            platformSdkInt = 30,
            requireCompletionCloseSettlement = false,
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
            requireCompletionCloseSettlement = false,
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
                requireCompletionCloseSettlement = false,
            ),
        )

        val desiredIngress = topology.prepareDesiredIngress() ?: error("missing desired ingress")
        topology.commitDesired(desiredIngress)
        val laterUpdate = topology.prepareMetrics(
            snapshot = laterSnapshot,
            platformSdkInt = 30,
            requireCompletionCloseSettlement = false,
        ) as SessionTopology.MetricsDecision.Update
        val metricsRevision = topology.commitMetrics(laterUpdate) ?: error("missing Metrics topology revision")

        assertTrue(metricsRevision > desiredIngress.revision)
        assertSame(
            SessionTopology.MetricsDecision.Duplicate,
            topology.prepareMetrics(
                snapshot = laterSnapshot,
                platformSdkInt = 30,
                requireCompletionCloseSettlement = false,
            ),
        )
    }

    private fun readyMetrics(widthPx: Int, heightPx: Int, densityDpi: Int): MetricsSnapshot = MetricsSnapshot(
        metrics = CaptureMetrics(widthPx, heightPx, densityDpi),
        lifecycle = MetricsAttachmentLifecycle.Live,
        handleAdopted = true,
        completionCloseSettled = false,
        failure = null,
    )

}
