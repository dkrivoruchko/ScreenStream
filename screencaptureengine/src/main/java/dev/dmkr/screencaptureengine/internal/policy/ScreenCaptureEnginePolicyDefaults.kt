@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.policy

/** The single production source for every non-product policy default. */
internal object ScreenCaptureEnginePolicyDefaults {
    internal const val STARTUP_METRICS_TIMEOUT_MS: Long = 3_000L
    internal const val STARTUP_CAPTURE_GEOMETRY_TIMEOUT_MS: Long = 3_000L
    internal const val STARTUP_PLATFORM_RESUME_TIMEOUT_MS: Long = 3_000L
    internal const val PROVIDER_PREPARATION_START_TIMEOUT_MS: Long = 1_000L
    internal const val PROVIDER_PREPARATION_OPERATION_TIMEOUT_MS: Long = 5_000L
    internal const val ENCODER_DISPATCH_START_TIMEOUT_MS: Long = 1_000L
    internal const val ENCODE_OPERATION_TIMEOUT_MS: Long = 30_000L
    internal const val ENCODER_CLOSE_START_TIMEOUT_MS: Long = 1_000L
    internal const val ENCODER_CLOSE_OPERATION_TIMEOUT_MS: Long = 5_000L
    internal const val GL_OPERATION_START_TIMEOUT_MS: Long = 1_000L
    internal const val GL_OPERATION_TIMEOUT_MS: Long = 10_000L
    internal const val PLATFORM_READY_PERMIT_TIMEOUT_MS: Long = 1_000L
    internal const val PLATFORM_OPERATION_START_TIMEOUT_MS: Long = 1_000L
    internal const val PLATFORM_OPERATION_TIMEOUT_MS: Long = 10_000L

    // Values only: v41 does not define this cleanup schedule's admission cardinality or origin.
    internal val PLATFORM_CLEANUP_START_RETRY_DELAYS_MS: List<Long> = listOf(0L, 100L, 500L)
    internal const val LISTENER_RETIREMENT_START_TIMEOUT_MS: Long = 1_000L
    internal const val LISTENER_RETIREMENT_OPERATION_TIMEOUT_MS: Long = 5_000L
    internal const val MAX_GL_ERROR_DRAIN: Int = 16
    internal const val RETURNED_ENCODER_FAILURE_THRESHOLD: Int = 3
    internal const val MAX_RETURNED_ENCODER_RECOVERIES_PER_SESSION: Int = 1
    internal const val MAX_ENCODER_OOM_RECOVERIES_PER_SESSION: Int = 1
    internal const val MAX_CLEAN_PIPELINE_RESOURCE_RECOVERIES_PER_SESSION: Int = 1
    internal val TARGET_RECONFIGURATION_DELAYS_MS: List<Long> = listOf(0L, 100L, 500L)
    internal const val MAX_RESOURCE_TRIM_WAKES_PER_CYCLE: Int = 1
    internal const val PBO_SLOT_COUNT: Int = 1
    internal const val PBO_COMPLETION_TIMEOUT_MS: Long = 10_000L
    internal const val MAX_ENCODED_SEGMENT_BYTES: Int = 1_048_576
    internal const val TARGET_BUFFER_ADMISSION_ESTIMATE_COUNT: Int = 4
    internal const val EVENT_EXTRA_BUFFER_CAPACITY: Int = 64
    internal const val DIAGNOSTIC_REPEAT_WINDOW_MS: Long = 1_000L
    internal const val MAX_DIAGNOSTIC_MESSAGE_CHARS: Int = 1_024
    internal const val MAX_QUARANTINED_PROVIDER_WORKERS: Int = 1
    internal const val MAX_RETAINED_PROVIDER_DESCRIPTOR_SNAPSHOTS: Int = 7
    internal const val MAX_ACTIVE_PREPARATIONS_PER_SESSION: Int = 1
    internal const val MAX_OUTSTANDING_ENCODER_CLEANUP_RECORDS: Int = 3
    internal const val MAX_ACTIVE_FRAME_SUBSCRIPTIONS: Int = 16
    internal const val MAX_OUTSTANDING_CALLBACK_LEASES_PER_ENGINE: Int = 16
    internal const val MAX_PENDING_GEOMETRIES: Int = 1
    internal const val MAX_POTENTIALLY_BOUND_TARGETS: Int = 2
}
