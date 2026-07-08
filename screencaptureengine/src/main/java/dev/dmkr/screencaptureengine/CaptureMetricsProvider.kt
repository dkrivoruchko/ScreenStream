package dev.dmkr.screencaptureengine

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Supplies capture metrics as state.
 *
 * Width and height describe the logical capture area used for output planning; density is used for virtual-display sizing. Invalid startup metrics fail startup
 * when possible; invalid running metrics are ignored by the engine. Provider objects are owned by the caller and may outlive a session; a session only owns its
 * own observation of a provider.
 */
public interface CaptureMetricsProvider {
    /** Latest known valid metrics. Invalid runtime emissions are ignored by the engine. */
    public val metrics: StateFlow<CaptureMetrics>
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
