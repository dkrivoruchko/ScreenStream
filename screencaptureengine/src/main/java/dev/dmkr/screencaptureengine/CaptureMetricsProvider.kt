package dev.dmkr.screencaptureengine

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Supplies capture metrics as state.
 *
 * Width and height in available states describe the logical capture area used for output planning; density is used for virtual-display sizing. Unavailable
 * startup metrics fail startup when possible; unavailable running metrics are ignored by the engine while the last valid metrics remain in use. Provider
 * objects are owned by the caller and may outlive a session; a session only owns its own observation of a provider.
 */
public interface CaptureMetricsProvider {
    /** Latest provider state. Only [CaptureMetricsState.Available] carries valid capture metrics. */
    public val metrics: StateFlow<CaptureMetricsState>
}

/** Latest metrics-provider state. */
public sealed interface CaptureMetricsState {
    /** Metrics are available and positive. */
    public class Available public constructor(public val metrics: CaptureMetrics) : CaptureMetricsState {
        public override fun equals(other: Any?): Boolean = other is Available && metrics == other.metrics

        public override fun hashCode(): Int = metrics.hashCode()
    }

    /**
     * Metrics are unavailable.
     *
     * This state does not carry fallback geometry. Providers must emit [Available] with positive [CaptureMetrics] when valid metrics are known.
     */
    public class Unavailable public constructor(
        public val reason: CaptureMetricsUnavailableReason,
        public val message: String? = null,
    ) : CaptureMetricsState {
        public override fun equals(other: Any?): Boolean = other is Unavailable && reason == other.reason && message == other.message

        public override fun hashCode(): Int = 31 * reason.hashCode() + (message?.hashCode() ?: 0)
    }
}

/** Technical reason why a metrics provider cannot currently expose valid metrics. */
public enum class CaptureMetricsUnavailableReason {
    /** The intended source is valid but not ready to provide a snapshot. */
    SourceNotReady,

    /** The intended source exists but its current snapshot is invalid for capture planning. */
    SourceInvalid,

    /** The intended source was valid earlier but is no longer available. */
    SourceNoLongerAvailable,
}

/**
 * Internal session attachment hook for built-in metrics providers.
 *
 * Factory providers do not register long-lived platform listeners at construction time. Runtime sessions attach an observation and dispose only that
 * attachment on stop, failure, or startup failure; the provider object remains caller-owned and reusable.
 */
@Suppress("unused")
internal interface EngineAttachableCaptureMetricsProvider : CaptureMetricsProvider {
    fun attachSessionAttachment(onMetricsChanged: () -> Unit): DisposableHandle
}
