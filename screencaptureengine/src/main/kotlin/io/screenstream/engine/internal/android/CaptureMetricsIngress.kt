package io.screenstream.engine.internal.android

import io.screenstream.engine.CaptureMetrics

// Aggregate-owned leaf-facing seam. Metrics publishes only complete cumulative value snapshots.
internal fun interface CaptureMetricsIngressPort {
    fun publishMetricsSummary(fact: CaptureMetricsIngressSummaryFact): CaptureMetricsIngressResult
}

/** Platform-neutral provenance fixed by the observation before a callback crosses into Session authority. */
internal enum class CaptureMetricsSourceProvenance {
    Custom,
    BuiltInDefaultDisplay,
    BuiltInFixedDisplay,
}

/**
 * Immutable identity evidence for one exact Android Display association. The Android Display itself remains in
 * the Metrics leaf. A zero validity epoch means that the association was observed unavailable before a valid
 * epoch could be installed; positive epochs identify continuous-validity lifetimes.
 */
internal class CaptureMetricsDisplayAssociation internal constructor(
    internal val displayId: Int,
    internal val associationIdentity: Long,
    internal val validityEpoch: Long,
) {
    init {
        require(associationIdentity > 0L)
        require(validityEpoch >= 0L)
    }
}

/** One precreated sample shell, fixed exactly once before it becomes part of a published summary. */
internal class CaptureMetricsSampleIngressFact internal constructor(
    internal val observationIdentity: Long,
    internal val sourceProvenance: CaptureMetricsSourceProvenance,
    internal val metrics: CaptureMetrics?,
    internal val displayAssociation: CaptureMetricsDisplayAssociation?,
) {
    private var fixedSequence = 0L
    private var fixedSampledAtNanos = Long.MIN_VALUE

    init {
        require(observationIdentity > 0L)
        require(
            sourceProvenance != CaptureMetricsSourceProvenance.Custom || displayAssociation == null,
        )
        require(
            metrics == null ||
                sourceProvenance == CaptureMetricsSourceProvenance.Custom ||
                checkNotNull(displayAssociation).validityEpoch > 0L,
        )
    }

    internal val sequence: Long
        get() = checkNotNull(fixedSequence.takeIf { it > 0L })

    internal val sampledAtNanos: Long
        get() {
            check(fixedSequence > 0L)
            return fixedSampledAtNanos
        }

    internal val isAvailable: Boolean
        get() = metrics != null

    internal fun fix(sequence: Long, sampledAtNanos: Long) {
        check(fixedSequence == 0L)
        require(sequence > 0L)
        fixedSampledAtNanos = sampledAtNanos
        fixedSequence = sequence
    }
}

/** One precreated terminal shell, fixed exactly once before it becomes part of a published summary. */
internal class CaptureMetricsTerminalIngressFact internal constructor(
    internal val observationIdentity: Long,
    internal val kind: CaptureMetricsTerminalKind,
    internal val cause: Throwable?,
) {
    private var fixedSequence = 0L
    private var fixedObservedAtNanos = Long.MIN_VALUE
    private var fixedPhase: CaptureMetricsTerminalPhase? = null

    init {
        require(observationIdentity > 0L)
    }

    internal val sequence: Long
        get() = checkNotNull(fixedSequence.takeIf { it > 0L })

    internal val observedAtNanos: Long
        get() {
            check(fixedSequence > 0L)
            return fixedObservedAtNanos
        }

    internal val phase: CaptureMetricsTerminalPhase
        get() = checkNotNull(fixedPhase)

    internal fun fix(
        sequence: Long,
        observedAtNanos: Long,
        phase: CaptureMetricsTerminalPhase,
    ) {
        check(fixedSequence == 0L)
        require(sequence > 0L)
        fixedObservedAtNanos = observedAtNanos
        fixedPhase = phase
        fixedSequence = sequence
    }
}

/**
 * One precreated, one-shot cumulative snapshot. Its five references are the complete bounded Metrics ingress
 * truth after [lastSequence]: earliest positive, sticky first post-positive loss, latest nullable sample, and
 * first terminal. Session can merge snapshots by the embedded semantic sequences regardless of arrival order.
 */
internal class CaptureMetricsIngressSummaryFact internal constructor(
    internal val observationIdentity: Long,
) {
    private var fixedLastSequence = 0L
    private var fixedEarliestPositive: CaptureMetricsSampleIngressFact? = null
    private var fixedFirstPostPositiveUnavailable: CaptureMetricsSampleIngressFact? = null
    private var fixedLatestSample: CaptureMetricsSampleIngressFact? = null
    private var fixedFirstTerminal: CaptureMetricsTerminalIngressFact? = null

    init {
        require(observationIdentity > 0L)
    }

    internal val lastSequence: Long
        get() = checkNotNull(fixedLastSequence.takeIf { it > 0L })

    internal val earliestPositive: CaptureMetricsSampleIngressFact?
        get() = fixedEarliestPositive

    internal val firstPostPositiveUnavailable: CaptureMetricsSampleIngressFact?
        get() = fixedFirstPostPositiveUnavailable

    internal val latestSample: CaptureMetricsSampleIngressFact?
        get() = fixedLatestSample

    internal val firstTerminal: CaptureMetricsTerminalIngressFact?
        get() = fixedFirstTerminal

    internal fun fix(
        lastSequence: Long,
        earliestPositive: CaptureMetricsSampleIngressFact?,
        firstPostPositiveUnavailable: CaptureMetricsSampleIngressFact?,
        latestSample: CaptureMetricsSampleIngressFact?,
        firstTerminal: CaptureMetricsTerminalIngressFact?,
    ) {
        check(fixedLastSequence == 0L)
        require(lastSequence > 0L)
        check(earliestPositive == null || earliestPositive.isAvailable)
        check(firstPostPositiveUnavailable == null || !firstPostPositiveUnavailable.isAvailable)
        check(firstPostPositiveUnavailable == null || earliestPositive != null)
        check(firstTerminal == null || firstTerminal.sequence == lastSequence)
        fixedEarliestPositive = earliestPositive
        fixedFirstPostPositiveUnavailable = firstPostPositiveUnavailable
        fixedLatestSample = latestSample
        fixedFirstTerminal = firstTerminal
        fixedLastSequence = lastSequence
    }
}

internal enum class CaptureMetricsIngressResult {
    Published,
    Duplicate,
    Closed,
    SequenceExhausted,
    RejectedAdmission,
    RejectedCurrentness,
}
