package io.screenstream.engine.internal.metrics

import android.view.Display
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsSource

internal enum class MetricsSourceKind {
    SessionDefaultDisplay,
    FixedDisplay,
    Custom,
}

/** Identity-bearing description of the exact source selected for this Session. */
internal class MetricsSourceIdentity internal constructor(
    internal val source: CaptureMetricsSource,
    internal val kind: MetricsSourceKind,
)

/** Resource-local key. It is deliberately not a Session currentness generation. */
internal class MetricsSourceKey internal constructor(
    internal val sourceIdentity: MetricsSourceIdentity,
    internal val display: Display?,
    internal val displayEpoch: BuiltInCaptureMetricsEpoch?,
) {
    internal fun matches(other: MetricsSourceKey): Boolean =
        sourceIdentity === other.sourceIdentity && display === other.display && displayEpoch === other.displayEpoch
}

internal sealed interface MetricsAvailabilityFact {
    val sequence: Long
    val observedAtNanos: Long
    val sourceKey: MetricsSourceKey

    class Available internal constructor(
        override val sequence: Long,
        override val observedAtNanos: Long,
        override val sourceKey: MetricsSourceKey,
        internal val metrics: CaptureMetrics,
    ) : MetricsAvailabilityFact

    class Unavailable internal constructor(
        override val sequence: Long,
        override val observedAtNanos: Long,
        override val sourceKey: MetricsSourceKey,
    ) : MetricsAvailabilityFact
}

internal class MetricsFirstPositiveBoundary internal constructor(
    internal val startNanos: Long,
    internal val deadlineNanos: Long,
    internal val expiryCause: Throwable,
)

internal class MetricsEarliestPositiveFact internal constructor(
    internal val sequence: Long,
    internal val observedAtNanos: Long,
    internal val metrics: CaptureMetrics,
    internal val sourceKey: MetricsSourceKey,
    internal val timely: Boolean,
)

internal class MetricsLossBeforeFirstActiveFact internal constructor(
    internal val sequence: Long,
    internal val observedAtNanos: Long,
    internal val sourceKey: MetricsSourceKey,
)

internal enum class MetricsTerminalPhase {
    BeforeJointReadiness,
    AfterJointReadiness,
}

internal sealed interface MetricsTerminalFact {
    val sequence: Long
    val observedAtNanos: Long
    val phase: MetricsTerminalPhase

    class Completed internal constructor(
        override val sequence: Long,
        override val observedAtNanos: Long,
        override val phase: MetricsTerminalPhase,
    ) : MetricsTerminalFact

    class Failed internal constructor(
        override val sequence: Long,
        override val observedAtNanos: Long,
        override val phase: MetricsTerminalPhase,
        internal val cause: Throwable,
    ) : MetricsTerminalFact
}

internal sealed interface MetricsAttachmentOutcome {
    data object NotQueued : MetricsAttachmentOutcome
    data object Queued : MetricsAttachmentOutcome
    data object Entered : MetricsAttachmentOutcome

    class ReturnedWithHandle internal constructor(
        internal val returnedAtNanos: Long,
    ) : MetricsAttachmentOutcome

    class Thrown internal constructor(
        internal val returnedAtNanos: Long,
        internal val cause: Exception,
        internal val timely: Boolean,
    ) : MetricsAttachmentOutcome

    class InvalidHandle internal constructor(
        internal val returnedAtNanos: Long,
        internal val timely: Boolean,
    ) : MetricsAttachmentOutcome

    class BoundaryFailed internal constructor(internal val cause: Exception) : MetricsAttachmentOutcome
    class DispatchFailed internal constructor(internal val cause: Exception?) : MetricsAttachmentOutcome
    data object CutoffInert : MetricsAttachmentOutcome
    class DirectFatal internal constructor(internal val cause: Throwable) : MetricsAttachmentOutcome
}

internal class MetricsJointReadinessFact internal constructor(
    internal val positive: MetricsEarliestPositiveFact,
    internal val handleReturnedAtNanos: Long,
)

internal class MetricsExpiryFact internal constructor(
    internal val observedAtNanos: Long,
    internal val boundary: MetricsFirstPositiveBoundary,
)

internal sealed interface MetricsCloseOutcome {
    data object NotRequested : MetricsCloseOutcome
    data object AwaitingAttachmentReturn : MetricsCloseOutcome
    data object Queued : MetricsCloseOutcome
    data object Entered : MetricsCloseOutcome
    data object Closed : MetricsCloseOutcome
    data object CutoffInert : MetricsCloseOutcome
    class DispatchFailed internal constructor(internal val cause: Exception?) : MetricsCloseOutcome
    class Failed internal constructor(internal val cause: Exception) : MetricsCloseOutcome
    class DirectFatal internal constructor(internal val cause: Throwable) : MetricsCloseOutcome
}

internal class MetricsIngressFailureFact internal constructor(internal val cause: Throwable)

/** Immutable bounded view consumed by Control while it already holds the Session gate. */
internal class SessionMetricsSummary internal constructor(
    internal val sourceIdentity: MetricsSourceIdentity,
    internal val observationSequence: Long,
    internal val boundary: MetricsFirstPositiveBoundary?,
    internal val earliestPositive: MetricsEarliestPositiveFact?,
    internal val lossBeforeFirstActive: MetricsLossBeforeFirstActiveFact?,
    internal val latest: MetricsAvailabilityFact?,
    internal val firstTerminal: MetricsTerminalFact?,
    internal val attachmentOutcome: MetricsAttachmentOutcome,
    internal val jointReadiness: MetricsJointReadinessFact?,
    internal val expiry: MetricsExpiryFact?,
    internal val closeOutcome: MetricsCloseOutcome,
    internal val ingressFailure: MetricsIngressFailureFact?,
    internal val cutoff: Boolean,
)

internal class MetricsAttachmentDispatch internal constructor(internal val capsule: MetricsCapsule)
internal class MetricsCloseDispatch internal constructor(internal val capsule: MetricsCapsule)

internal sealed interface MetricsSubmissionOutcome {
    data object Accepted : MetricsSubmissionOutcome
    data object Rejected : MetricsSubmissionOutcome
    class Failed internal constructor(internal val cause: Exception) : MetricsSubmissionOutcome
}

internal sealed interface MetricsExpiryOutcome {
    data object NotExpired : MetricsExpiryOutcome
    data object AlreadyResolved : MetricsExpiryOutcome
    class Expired internal constructor(
        internal val fact: MetricsExpiryFact,
        internal val closeDispatch: MetricsCloseDispatch?,
    ) : MetricsExpiryOutcome
}

internal class MetricsCutoff internal constructor(
    internal val retirement: MetricsRetirement,
    internal val closeDispatch: MetricsCloseDispatch?,
)

internal sealed interface MetricsLateCutoffFact {
    val capsule: MetricsCapsule
    val residueRemains: Boolean
    val cause: Throwable?

    class AttachmentSettled internal constructor(
        override val capsule: MetricsCapsule,
        override val residueRemains: Boolean,
        override val cause: Throwable?,
    ) : MetricsLateCutoffFact

    class CloseSettled internal constructor(
        override val capsule: MetricsCapsule,
        override val residueRemains: Boolean,
        override val cause: Throwable?,
    ) : MetricsLateCutoffFact
}

internal fun interface MetricsLateCutoffSink {
    fun onLateMetricsFact(fact: MetricsLateCutoffFact)
}

internal const val FIRST_METRICS_POSITIVE_SAFETY_NANOS: Long = 5_000_000_000L

internal fun MetricsAvailabilityFact.sameAuthorityValue(other: MetricsAvailabilityFact): Boolean = when {
    this is MetricsAvailabilityFact.Available && other is MetricsAvailabilityFact.Available ->
        metrics == other.metrics && sourceKey.matches(other.sourceKey)

    this is MetricsAvailabilityFact.Unavailable && other is MetricsAvailabilityFact.Unavailable ->
        sourceKey.matches(other.sourceKey)

    else -> false
}
