package dev.dmkr.screencaptureengine.internal.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ControllerTurnPrecisionTest {
    @Test
    fun everyTypedStartupFailureRetainsCauseAndExactProjectionFreshness() {
        val cause = IllegalStateException("not inspected")
        PrePublicStartFailureEvidence.entries.forEach { evidence ->
            PrePublicProjectionFreshness.entries
                .filterNot { it == PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness }
                .forEach { freshness ->
                    val failure = PrePublicStartOutcome.Failure(evidence, cause)
                    val retirement = PrePublicStartRetirement(freshness, failure)

                    assertEquals(evidence, failure.evidence)
                    assertSame(cause, failure.cause)
                    assertEquals(
                        freshness != PrePublicProjectionFreshness.ReusableBeforeAttachment &&
                                freshness != PrePublicProjectionFreshness.ReusableAfterDetachAcknowledged,
                        retirement.requiresFreshProjection,
                    )
                }
        }
    }

    @Test
    fun callerCancellationUsesOnlyTheClosedFreshnessMatrix() {
        val accepted = setOf(
            PrePublicProjectionFreshness.ReusableBeforeAttachment,
            PrePublicProjectionFreshness.DiscardConsumed,
            PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness,
        )
        PrePublicProjectionFreshness.entries.forEach { freshness ->
            val result = runCatching {
                PrePublicStartRetirement(freshness, PrePublicStartOutcome.CallerCancellation)
            }
            assertEquals(freshness in accepted, result.isSuccess)
        }
    }

    @Test
    fun normalOccurrenceReservationsAreStrictAndCannotRepresentTheFrozenBoundary() {
        val reservations = ControllerTurnOccurrenceReservations.NormalRange(
            stateCommit = ControllerOccurrenceIdentity(1),
            resultCommit = ControllerOccurrenceIdentity(2),
            event = ControllerOccurrenceIdentity(3),
            diagnostic = ControllerOccurrenceIdentity(4),
        )
        assertEquals(1L, reservations.stateCommit?.value)
        assertEquals(2L, reservations.resultCommit?.value)
        assertEquals(3L, reservations.event?.value)
        assertEquals(4L, reservations.diagnostic?.value)
        assertThrows(IllegalArgumentException::class.java) {
            ControllerOccurrenceIdentity(Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerTurnOccurrenceReservations.NormalRange(
                stateCommit = ControllerOccurrenceIdentity(2),
                resultCommit = ControllerOccurrenceIdentity(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerTurnOccurrenceReservations.NormalRange(event = ControllerOccurrenceIdentity(1))
        }
        ControllerTurnOccurrenceReservations.NormalRange(
            stateCommit = ControllerOccurrenceIdentity(10),
            resultCommit = ControllerOccurrenceIdentity(11),
        )
        val diagnosticOnly = ControllerTurnOccurrenceReservations.NormalRange(
            diagnostic = ControllerOccurrenceIdentity(12),
        )
        assertEquals(12L, diagnosticOnly.diagnostic?.value)
        assertThrows(IllegalArgumentException::class.java) {
            ControllerTurnOccurrenceReservations.NormalRange(
                stateCommit = ControllerOccurrenceIdentity(2),
                resultCommit = ControllerOccurrenceIdentity(3),
                event = ControllerOccurrenceIdentity(1),
            )
        }
        assertSame(
            ControllerTurnOccurrenceReservations.FrozenSequenceBoundary,
            ControllerTurnOccurrenceReservations.FrozenSequenceBoundary,
        )
    }

    @Test
    fun sourceReasonIdentitiesStayFactBoundAndReconfigurationStartIsGlobalImmutableInput() {
        val metrics = ControllerIngress.Metrics(IngressSequence(1), MetricsEvidence(2, 3, 4))
        val resize = ControllerIngress.CapturedResize(IngressSequence(2), CapturedResizeEvidence(5, 6))
        val trust = ControllerIngress.SourceTrust(IngressSequence(3), SourceTrustEvidence.Invalid)
        val pause = ControllerIngress.Pause(IngressSequence(4), PauseEvidence(true, IngressSequence(4)))
        val start = ControllerReconfigurationStartIdentity(ReconfigurationIdentity(1), CandidateIdentity(1))
        val valid = ControllerPreparedTurn(
            listOf(
                PreparedSourceFact.Metrics(metrics, ReasonToken(1)),
                PreparedSourceFact.CapturedResize(resize, ReasonToken(2)),
                PreparedSourceFact.SourceTrust(trust, ReasonToken(3)),
                PreparedSourceFact.Pause(pause, ReasonToken(4)),
            ),
            reconfigurationStart = start,
        )
        assertSame(metrics, (valid[0] as PreparedSourceFact.Metrics).turnFact)
        assertEquals(ReconfigurationIdentity(1), start.reconfiguration)
        assertEquals(CandidateIdentity(1), start.candidate)
        assertSame(start, valid.reconfigurationStart)
    }

    @Test
    fun trustBudgetsCannotCrossTheIndependentMetricsAndResizeChannels() {
        val metricsTrust = ControllerIngress.SourceTrust(IngressSequence(1), SourceTrustEvidence.NoLongerAvailable)
        val resizeTrust = ControllerIngress.SourceTrust(IngressSequence(2), SourceTrustEvidence.InvalidResize)
        ControllerPreparedTurn(
            listOf(
                PreparedSourceFact.SourceTrust(metricsTrust, ReasonToken(1)),
                PreparedSourceFact.SourceTrust(resizeTrust, ReasonToken(2)),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ControllerPreparedTurn(
                listOf(
                    PreparedSourceFact.SourceTrust(metricsTrust, ReasonToken(1)),
                    PreparedSourceFact.SourceTrust(
                        ControllerIngress.SourceTrust(IngressSequence(2), SourceTrustEvidence.Invalid),
                        ReasonToken(2),
                    ),
                ),
            )
        }
    }
}
