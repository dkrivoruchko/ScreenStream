@file:Suppress("unused") // Dormant until controller reducer integration.

package dev.dmkr.screencaptureengine.internal.control

internal enum class ControllerGeometryMode {
    MetricsAuthoritative,
    CapturedResizeAuthoritative,
}

internal enum class GeometryAwaiting {
    Metrics,
    Density,
    CapturedResize,
}

internal enum class GeometryNonrepresentability {
    MetricsWidth,
    MetricsHeight,
    Density,
    CapturedResizeWidth,
    CapturedResizeHeight,
}

internal sealed interface ControllerGeometryFact {
    val accumulator: ControllerGeometryAccumulator

    data class Accepted(
        override val accumulator: ControllerGeometryAccumulator,
        val geometry: GeometrySnapshot,
        val first: Boolean,
    ) : ControllerGeometryFact

    data class Awaiting(
        override val accumulator: ControllerGeometryAccumulator,
        val missing: GeometryAwaiting,
    ) : ControllerGeometryFact

    data class Duplicate(
        override val accumulator: ControllerGeometryAccumulator,
        val geometry: GeometrySnapshot,
        val sourceTrustRestored: Boolean,
    ) : ControllerGeometryFact

    data class Untrusted(
        override val accumulator: ControllerGeometryAccumulator,
        val evidence: SourceTrustEvidence,
        val retainedGeometry: GeometrySnapshot?,
        val sourceTrustChanged: Boolean,
        val sourceBecameUntrusted: Boolean,
    ) : ControllerGeometryFact

    data class NotRepresentable(
        override val accumulator: ControllerGeometryAccumulator,
        val reason: GeometryNonrepresentability,
        val retainedGeometry: GeometrySnapshot?,
        val sourceTrustChanged: Boolean,
        val sourceBecameUntrusted: Boolean,
    ) : ControllerGeometryFact
}

/** Immutable API-band-specific accumulation of raw geometry components and source trust. */
internal class ControllerGeometryAccumulator private constructor(
    internal val mode: ControllerGeometryMode,
    internal val latestMetrics: MetricsEvidence?,
    internal val latestCapturedResize: CapturedResizeEvidence?,
    internal val metricsUntrustedEvidence: SourceTrustEvidence?,
    internal val capturedResizeUntrustedEvidence: SourceTrustEvidence?,
    internal val lastAcceptedGeometry: GeometrySnapshot?,
) {
    internal fun acceptMetrics(evidence: MetricsEvidence): ControllerGeometryFact {
        val invalid = evidence.nonrepresentability()
        if (invalid != null) return notRepresentable(invalid, GeometryTrustChannel.Metrics)

        val restored = metricsUntrustedEvidence != null
        return copy(latestMetrics = evidence, metricsUntrustedEvidence = null)
            .resolve(sourceTrustRestored = restored)
    }

    internal fun acceptCapturedResize(evidence: CapturedResizeEvidence): ControllerGeometryFact {
        if (mode == ControllerGeometryMode.MetricsAuthoritative) {
            return resolve(sourceTrustRestored = false)
        }
        val invalid = evidence.nonrepresentability()
        if (invalid != null) return notRepresentable(invalid, GeometryTrustChannel.CapturedResize)

        val restored = capturedResizeUntrustedEvidence != null
        return copy(latestCapturedResize = evidence, capturedResizeUntrustedEvidence = null)
            .resolve(sourceTrustRestored = restored)
    }

    internal fun acceptSourceTrust(evidence: SourceTrustEvidence): ControllerGeometryFact = when (evidence) {
        SourceTrustEvidence.NotReady,
        SourceTrustEvidence.Invalid,
        SourceTrustEvidence.NoLongerAvailable,
            -> untrusted(evidence, GeometryTrustChannel.Metrics)

        SourceTrustEvidence.InvalidResize -> if (mode == ControllerGeometryMode.CapturedResizeAuthoritative) {
            untrusted(evidence, GeometryTrustChannel.CapturedResize)
        } else {
            resolve(sourceTrustRestored = false)
        }
    }

    private fun resolve(sourceTrustRestored: Boolean): ControllerGeometryFact {
        metricsUntrustedEvidence?.let { return unchangedUntrusted(it) }
        capturedResizeUntrustedEvidence?.let { return unchangedUntrusted(it) }

        val metrics = latestMetrics ?: return ControllerGeometryFact.Awaiting(
            accumulator = this,
            missing = when (mode) {
                ControllerGeometryMode.MetricsAuthoritative -> GeometryAwaiting.Metrics
                ControllerGeometryMode.CapturedResizeAuthoritative -> GeometryAwaiting.Density
            },
        )
        val width: Int
        val height: Int
        when (mode) {
            ControllerGeometryMode.MetricsAuthoritative -> {
                width = metrics.width
                height = metrics.height
            }

            ControllerGeometryMode.CapturedResizeAuthoritative -> {
                val resize = latestCapturedResize
                    ?: return ControllerGeometryFact.Awaiting(this, GeometryAwaiting.CapturedResize)
                width = resize.width
                height = resize.height
            }
        }
        val geometry = GeometrySnapshot(width, height, metrics.densityDpi)
        if (geometry == lastAcceptedGeometry) {
            return ControllerGeometryFact.Duplicate(this, geometry, sourceTrustRestored)
        }

        val accepted = copy(lastAcceptedGeometry = geometry)
        return ControllerGeometryFact.Accepted(
            accumulator = accepted,
            geometry = geometry,
            first = lastAcceptedGeometry == null,
        )
    }

    private fun untrusted(
        evidence: SourceTrustEvidence,
        channel: GeometryTrustChannel,
    ): ControllerGeometryFact {
        val previous = trustFor(channel)
        val becameUntrusted = metricsUntrustedEvidence == null && capturedResizeUntrustedEvidence == null
        val accumulator = copyTrust(channel, evidence)
        return ControllerGeometryFact.Untrusted(
            accumulator = accumulator,
            evidence = evidence,
            retainedGeometry = lastAcceptedGeometry,
            sourceTrustChanged = previous != evidence,
            sourceBecameUntrusted = becameUntrusted,
        )
    }

    private fun notRepresentable(
        reason: GeometryNonrepresentability,
        channel: GeometryTrustChannel,
    ): ControllerGeometryFact {
        val evidence = when (reason) {
            GeometryNonrepresentability.CapturedResizeWidth,
            GeometryNonrepresentability.CapturedResizeHeight,
                -> SourceTrustEvidence.InvalidResize

            GeometryNonrepresentability.MetricsWidth,
            GeometryNonrepresentability.MetricsHeight,
            GeometryNonrepresentability.Density,
                -> SourceTrustEvidence.Invalid
        }
        val previous = trustFor(channel)
        val becameUntrusted = metricsUntrustedEvidence == null && capturedResizeUntrustedEvidence == null
        val accumulator = copyTrust(channel, evidence)
        return ControllerGeometryFact.NotRepresentable(
            accumulator = accumulator,
            reason = reason,
            retainedGeometry = lastAcceptedGeometry,
            sourceTrustChanged = previous != evidence,
            sourceBecameUntrusted = becameUntrusted,
        )
    }

    private fun unchangedUntrusted(evidence: SourceTrustEvidence): ControllerGeometryFact.Untrusted =
        ControllerGeometryFact.Untrusted(
            accumulator = this,
            evidence = evidence,
            retainedGeometry = lastAcceptedGeometry,
            sourceTrustChanged = false,
            sourceBecameUntrusted = false,
        )

    private fun trustFor(channel: GeometryTrustChannel): SourceTrustEvidence? = when (channel) {
        GeometryTrustChannel.Metrics -> metricsUntrustedEvidence
        GeometryTrustChannel.CapturedResize -> capturedResizeUntrustedEvidence
    }

    private fun copyTrust(
        channel: GeometryTrustChannel,
        evidence: SourceTrustEvidence,
    ): ControllerGeometryAccumulator = when (channel) {
        GeometryTrustChannel.Metrics -> copy(metricsUntrustedEvidence = evidence)
        GeometryTrustChannel.CapturedResize -> copy(capturedResizeUntrustedEvidence = evidence)
    }

    private fun copy(
        latestMetrics: MetricsEvidence? = this.latestMetrics,
        latestCapturedResize: CapturedResizeEvidence? = this.latestCapturedResize,
        metricsUntrustedEvidence: SourceTrustEvidence? = this.metricsUntrustedEvidence,
        capturedResizeUntrustedEvidence: SourceTrustEvidence? = this.capturedResizeUntrustedEvidence,
        lastAcceptedGeometry: GeometrySnapshot? = this.lastAcceptedGeometry,
    ): ControllerGeometryAccumulator = ControllerGeometryAccumulator(
        mode = mode,
        latestMetrics = latestMetrics,
        latestCapturedResize = latestCapturedResize,
        metricsUntrustedEvidence = metricsUntrustedEvidence,
        capturedResizeUntrustedEvidence = capturedResizeUntrustedEvidence,
        lastAcceptedGeometry = lastAcceptedGeometry,
    )

    internal companion object {
        internal fun create(mode: ControllerGeometryMode): ControllerGeometryAccumulator =
            ControllerGeometryAccumulator(mode, null, null, null, null, null)
    }
}

private enum class GeometryTrustChannel { Metrics, CapturedResize }

private fun MetricsEvidence.nonrepresentability(): GeometryNonrepresentability? = when {
    width <= 0 -> GeometryNonrepresentability.MetricsWidth
    height <= 0 -> GeometryNonrepresentability.MetricsHeight
    densityDpi <= 0 -> GeometryNonrepresentability.Density
    else -> null
}

private fun CapturedResizeEvidence.nonrepresentability(): GeometryNonrepresentability? = when {
    width <= 0 -> GeometryNonrepresentability.CapturedResizeWidth
    height <= 0 -> GeometryNonrepresentability.CapturedResizeHeight
    else -> null
}
