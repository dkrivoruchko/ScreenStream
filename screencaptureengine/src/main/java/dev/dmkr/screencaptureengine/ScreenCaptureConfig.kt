package dev.dmkr.screencaptureengine

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Session-wide limits and integration hooks.
 *
 * These values bound retained encoded snapshots, published output size, encoded frame size, and default callback dispatch. They are public construction
 * limits, not allocation guarantees. Per-frame capture/render/encode choices live in [ScreenCaptureParameters].
 */
public class ScreenCaptureConfig public constructor(
    /** Source of display/captured-content bootstrap metrics and density. */
    public val metricsProvider: CaptureMetricsProvider,

    /** Number of immutable public delivery snapshot slots in `1..16`, excluding the internal latest frame. */
    public val publishedSnapshotSlotCount: Int = DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT,

    /** Consecutive direct per-subscription delivery-problem threshold in `1..1024` for slow classification. */
    public val slowConsumerThreshold: Int = DEFAULT_SLOW_CONSUMER_THRESHOLD,

    /** Maximum final published image pixels in `1..268435456` before device/runtime limits are considered. */
    public val maxOutputPixels: Int = DEFAULT_MAX_OUTPUT_PIXELS,

    /** Hard cap in `1024..268435456` bytes for the internal latest frame and each public delivery snapshot. */
    public val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,

    /** Session-level dispatcher for public frame callback bodies. Null uses the engine-owned bounded callback dispatcher. */
    public val frameCallbackDispatcher: CoroutineDispatcher? = null,
) {
    init {
        require(publishedSnapshotSlotCount in PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE) {
            "publishedSnapshotSlotCount must be in $PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE, was $publishedSnapshotSlotCount"
        }
        require(slowConsumerThreshold in SLOW_CONSUMER_THRESHOLD_RANGE) {
            "slowConsumerThreshold must be in $SLOW_CONSUMER_THRESHOLD_RANGE, was $slowConsumerThreshold"
        }
        require(maxOutputPixels in MAX_OUTPUT_PIXELS_RANGE) {
            "maxOutputPixels must be in $MAX_OUTPUT_PIXELS_RANGE, was $maxOutputPixels"
        }
        require(maxEncodedBytes in MAX_ENCODED_BYTES_RANGE) {
            "maxEncodedBytes must be in $MAX_ENCODED_BYTES_RANGE, was $maxEncodedBytes"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureConfig && metricsProvider == other.metricsProvider &&
                publishedSnapshotSlotCount == other.publishedSnapshotSlotCount && slowConsumerThreshold == other.slowConsumerThreshold &&
                maxOutputPixels == other.maxOutputPixels && maxEncodedBytes == other.maxEncodedBytes &&
                frameCallbackDispatcher == other.frameCallbackDispatcher

    public override fun hashCode(): Int =
        31 * (31 * (31 * (31 * (31 * metricsProvider.hashCode() + publishedSnapshotSlotCount) + slowConsumerThreshold) + maxOutputPixels) + maxEncodedBytes) +
                (frameCallbackDispatcher?.hashCode() ?: 0)
}

private const val DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT: Int = 4
private const val DEFAULT_SLOW_CONSUMER_THRESHOLD: Int = 2
private const val DEFAULT_MAX_OUTPUT_PIXELS: Int = 2_073_600
private const val DEFAULT_MAX_ENCODED_BYTES: Int = 8 * 1024 * 1024
private val PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE: IntRange = 1..16
private val SLOW_CONSUMER_THRESHOLD_RANGE: IntRange = 1..1024
private val MAX_OUTPUT_PIXELS_RANGE: IntRange = 1..268_435_456
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456
