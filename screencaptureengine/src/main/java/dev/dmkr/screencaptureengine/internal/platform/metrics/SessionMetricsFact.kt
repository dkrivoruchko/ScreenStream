@file:Suppress("unused") // Dormant until the v41 controller is integrated.

package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.internal.control.ControllerCancellationMarkerRevision
import dev.dmkr.screencaptureengine.internal.control.ControllerMetricsAttachmentIdentity
import dev.dmkr.screencaptureengine.internal.control.ControllerOperationIdentity
import dev.dmkr.screencaptureengine.internal.control.SessionIdentity

/** Identity fence attached to every mechanical metrics-collector fact. */
internal data class SessionMetricsFactTag(
    val session: SessionIdentity,
    val attachment: ControllerMetricsAttachmentIdentity,
    val operation: ControllerOperationIdentity,
)

/**
 * Provider-free facts observed by the session metrics collector.
 *
 * These facts deliberately retain invalid scalar values and provider causes. The controller alone
 * decides validity, cancellation attribution, public failure, revisions, retry, and severity.
 */
internal sealed interface SessionMetricsFact {
    val tag: SessionMetricsFactTag

    data class Available(
        override val tag: SessionMetricsFactTag,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
    ) : SessionMetricsFact

    data class Unavailable(
        override val tag: SessionMetricsFactTag,
        val reason: CaptureMetricsUnavailableReason,
        val message: String?,
    ) : SessionMetricsFact

    data class GetterThrew(
        override val tag: SessionMetricsFactTag,
        val cause: Throwable,
    ) : SessionMetricsFact

    data class CollectionCompleted(
        override val tag: SessionMetricsFactTag,
    ) : SessionMetricsFact

    data class CollectionThrew(
        override val tag: SessionMetricsFactTag,
        val cause: Throwable,
    ) : SessionMetricsFact

    data class CancellationMarked(
        override val tag: SessionMetricsFactTag,
        val marker: ControllerCancellationMarkerRevision,
    ) : SessionMetricsFact
}

/** Exact session-retirement proof required before caller provider references may be released. */
internal data class SessionMetricsBarrierProof(
    val session: SessionIdentity,
    val attachment: ControllerMetricsAttachmentIdentity,
)

/** Short synchronous handoff into the controller's identity-fenced metrics ingress. */
internal fun interface SessionMetricsFactSink {
    fun accept(fact: SessionMetricsFact)
}
