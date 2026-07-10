package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGeometryAccumulatorTest {
    @Test
    fun api24To33AcceptsFirstMetricsTupleAndSuppressesDuplicate() {
        val first = accumulator(ControllerGeometryMode.MetricsAuthoritative)
            .acceptMetrics(MetricsEvidence(1080, 1920, 420)).accepted()
        assertEquals(GeometrySnapshot(1080, 1920, 420), first.geometry)
        assertTrue(first.first)

        val duplicate = first.accumulator.acceptMetrics(MetricsEvidence(1080, 1920, 420)).duplicate()
        assertFalse(duplicate.sourceTrustRestored)
        assertEquals(first.geometry, duplicate.geometry)

        val changed = duplicate.accumulator.acceptMetrics(MetricsEvidence(1920, 1080, 480)).accepted()
        assertEquals(GeometrySnapshot(1920, 1080, 480), changed.geometry)
        assertFalse(changed.first)
    }

    @Test
    fun api24To33IgnoresCapturedResizeChannel() {
        val initial = accumulator(ControllerGeometryMode.MetricsAuthoritative)
        val beforeMetrics = initial.acceptCapturedResize(CapturedResizeEvidence(0, 0)).awaiting()
        assertEquals(GeometryAwaiting.Metrics, beforeMetrics.missing)
        assertNull(beforeMetrics.accumulator.latestCapturedResize)
        assertNull(beforeMetrics.accumulator.capturedResizeUntrustedEvidence)

        val accepted = beforeMetrics.accumulator.acceptMetrics(MetricsEvidence(720, 1280, 320)).accepted()
        val ignoredInvalidResize = accepted.accumulator
            .acceptSourceTrust(SourceTrustEvidence.InvalidResize).duplicate()
        assertEquals(accepted.geometry, ignoredInvalidResize.geometry)
        assertNull(ignoredInvalidResize.accumulator.capturedResizeUntrustedEvidence)
    }

    @Test
    fun api34ResizeAndDensityArrivalOrdersAreEquivalentAndMetricsSizeIsBootstrapOnly() {
        val resizeFirst = accumulator(ControllerGeometryMode.CapturedResizeAuthoritative)
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).awaiting()
        assertEquals(GeometryAwaiting.Density, resizeFirst.missing)
        val resizeFirstAccepted = resizeFirst.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).accepted()

        val densityFirst = accumulator(ControllerGeometryMode.CapturedResizeAuthoritative)
            .acceptMetrics(MetricsEvidence(111, 222, 440)).awaiting()
        assertEquals(GeometryAwaiting.CapturedResize, densityFirst.missing)
        val densityFirstAccepted = densityFirst.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).accepted()

        assertEquals(GeometrySnapshot(900, 1600, 440), resizeFirstAccepted.geometry)
        assertEquals(resizeFirstAccepted.geometry, densityFirstAccepted.geometry)
    }

    @Test
    fun api34DensityOnlyAndResizeOnlyChangesProduceExactTuples() {
        val initial = acceptedApi34()
        val bootstrapSizeOnly = initial.accumulator
            .acceptMetrics(MetricsEvidence(Int.MAX_VALUE, Int.MAX_VALUE, 440)).duplicate()
        assertEquals(initial.geometry, bootstrapSizeOnly.geometry)

        val density = bootstrapSizeOnly.accumulator.acceptMetrics(MetricsEvidence(1, 1, 480)).accepted()
        assertEquals(GeometrySnapshot(900, 1600, 480), density.geometry)
        assertFalse(density.first)

        val resize = density.accumulator.acceptCapturedResize(CapturedResizeEvidence(1200, 700)).accepted()
        assertEquals(GeometrySnapshot(1200, 700, 480), resize.geometry)
        assertFalse(resize.first)
    }

    @Test
    fun sourceUntrustedRetainsLastValidAndEquivalentEvidenceIsMarkedUnchanged() {
        val accepted = acceptedApi34()
        val unavailable = accepted.accumulator
            .acceptSourceTrust(SourceTrustEvidence.NoLongerAvailable).untrusted()
        assertEquals(accepted.geometry, unavailable.retainedGeometry)
        assertTrue(unavailable.sourceTrustChanged)
        assertTrue(unavailable.sourceBecameUntrusted)

        val repeated = unavailable.accumulator
            .acceptSourceTrust(SourceTrustEvidence.NoLongerAvailable).untrusted()
        assertEquals(accepted.geometry, repeated.retainedGeometry)
        assertFalse(repeated.sourceTrustChanged)
        assertFalse(repeated.sourceBecameUntrusted)

        val changedReason = repeated.accumulator
            .acceptSourceTrust(SourceTrustEvidence.Invalid).untrusted()
        assertTrue(changedReason.sourceTrustChanged)
        assertFalse(changedReason.sourceBecameUntrusted)
    }

    @Test
    fun metricsUnavailabilityRequiresNewValidMetricsAndCannotBeClearedByResize() {
        val accepted = acceptedApi34()
        val unavailable = accepted.accumulator
            .acceptSourceTrust(SourceTrustEvidence.NotReady).untrusted()

        val resize = unavailable.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).untrusted()
        assertEquals(SourceTrustEvidence.NotReady, resize.evidence)
        assertFalse(resize.sourceTrustChanged)

        val recovered = resize.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).duplicate()
        assertEquals(accepted.geometry, recovered.geometry)
        assertTrue(recovered.sourceTrustRestored)
    }

    @Test
    fun invalidResizeRequiresNewValidResizeAndCannotBeClearedByMetrics() {
        val accepted = acceptedApi34()
        val invalidResize = accepted.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(0, 1600)).notRepresentable()
        assertEquals(GeometryNonrepresentability.CapturedResizeWidth, invalidResize.reason)
        assertTrue(invalidResize.sourceBecameUntrusted)

        val metrics = invalidResize.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 480)).untrusted()
        assertEquals(SourceTrustEvidence.InvalidResize, metrics.evidence)
        assertEquals(accepted.geometry, metrics.retainedGeometry)

        val recovered = metrics.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).accepted()
        assertEquals(GeometrySnapshot(900, 1600, 480), recovered.geometry)
    }

    @Test
    fun independentInvalidChannelsRecoverOnlyAfterBothHaveNewValidValues() {
        val accepted = acceptedApi34()
        val invalidMetrics = accepted.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 0)).notRepresentable()
        val bothInvalid = invalidMetrics.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 0)).notRepresentable()
        assertFalse(bothInvalid.sourceBecameUntrusted)

        val metricsRecovered = bothInvalid.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).untrusted()
        assertEquals(SourceTrustEvidence.InvalidResize, metricsRecovered.evidence)

        val fullyRecovered = metricsRecovered.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).duplicate()
        assertEquals(accepted.geometry, fullyRecovered.geometry)
        assertTrue(fullyRecovered.sourceTrustRestored)
    }

    @Test
    fun validRecoveryWithChangedTupleAcceptsOneNewGeometry() {
        val accepted = acceptedApi34()
        val untrusted = accepted.accumulator
            .acceptSourceTrust(SourceTrustEvidence.Invalid).untrusted()
        val changedWhileUntrusted = untrusted.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(1000, 1700)).untrusted()
        val recovered = changedWhileUntrusted.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).accepted()

        assertEquals(GeometrySnapshot(1000, 1700, 440), recovered.geometry)
        assertFalse(recovered.first)
    }

    @Test
    fun nonrepresentableSignalsRetainLastComponentsAndRequireValidReplacement() {
        val accepted = acceptedApi34()
        val invalidMetrics = accepted.accumulator
            .acceptMetrics(MetricsEvidence(0, 1, 1)).notRepresentable()
        assertEquals(GeometryNonrepresentability.MetricsWidth, invalidMetrics.reason)
        assertEquals(MetricsEvidence(4000, 3000, 440), invalidMetrics.accumulator.latestMetrics)
        assertEquals(accepted.geometry, invalidMetrics.retainedGeometry)

        val invalidResize = invalidMetrics.accumulator
            .acceptCapturedResize(CapturedResizeEvidence(1, 0)).notRepresentable()
        assertEquals(GeometryNonrepresentability.CapturedResizeHeight, invalidResize.reason)
        assertEquals(CapturedResizeEvidence(900, 1600), invalidResize.accumulator.latestCapturedResize)

        val stillInvalid = invalidResize.accumulator
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).untrusted()
        assertEquals(SourceTrustEvidence.InvalidResize, stillInvalid.evidence)
    }

    @Test
    fun everyRawScalarHasTypedNonrepresentability() {
        val initial = acceptedApi34().accumulator
        val cases = listOf(
            initial.acceptMetrics(MetricsEvidence(0, 1, 1)).notRepresentable().reason to
                    GeometryNonrepresentability.MetricsWidth,
            initial.acceptMetrics(MetricsEvidence(1, 0, 1)).notRepresentable().reason to
                    GeometryNonrepresentability.MetricsHeight,
            initial.acceptMetrics(MetricsEvidence(1, 1, 0)).notRepresentable().reason to
                    GeometryNonrepresentability.Density,
            initial.acceptCapturedResize(CapturedResizeEvidence(0, 1)).notRepresentable().reason to
                    GeometryNonrepresentability.CapturedResizeWidth,
            initial.acceptCapturedResize(CapturedResizeEvidence(1, 0)).notRepresentable().reason to
                    GeometryNonrepresentability.CapturedResizeHeight,
        )
        cases.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }

    @Test
    fun untrustedBeforeAnyGeometryRetainsNothing() {
        val fact = accumulator(ControllerGeometryMode.MetricsAuthoritative)
            .acceptSourceTrust(SourceTrustEvidence.Invalid).untrusted()
        assertNull(fact.retainedGeometry)
        assertTrue(fact.sourceBecameUntrusted)
    }

    @Test
    fun positiveIntMaximumGeometryHasNoLocalFixedCap() {
        val accepted = accumulator(ControllerGeometryMode.MetricsAuthoritative)
            .acceptMetrics(MetricsEvidence(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)).accepted()

        assertEquals(GeometrySnapshot(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), accepted.geometry)
    }

    private fun acceptedApi34(): ControllerGeometryFact.Accepted =
        accumulator(ControllerGeometryMode.CapturedResizeAuthoritative)
            .acceptMetrics(MetricsEvidence(4000, 3000, 440)).accumulator
            .acceptCapturedResize(CapturedResizeEvidence(900, 1600)).accepted()

    private fun accumulator(mode: ControllerGeometryMode): ControllerGeometryAccumulator =
        ControllerGeometryAccumulator.create(mode)

    private fun ControllerGeometryFact.accepted(): ControllerGeometryFact.Accepted =
        this as ControllerGeometryFact.Accepted

    private fun ControllerGeometryFact.awaiting(): ControllerGeometryFact.Awaiting =
        this as ControllerGeometryFact.Awaiting

    private fun ControllerGeometryFact.duplicate(): ControllerGeometryFact.Duplicate =
        this as ControllerGeometryFact.Duplicate

    private fun ControllerGeometryFact.untrusted(): ControllerGeometryFact.Untrusted =
        this as ControllerGeometryFact.Untrusted

    private fun ControllerGeometryFact.notRepresentable(): ControllerGeometryFact.NotRepresentable =
        this as ControllerGeometryFact.NotRepresentable
}
