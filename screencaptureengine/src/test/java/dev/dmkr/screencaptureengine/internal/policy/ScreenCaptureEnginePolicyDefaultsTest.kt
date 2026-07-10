package dev.dmkr.screencaptureengine.internal.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureEnginePolicyDefaultsTest {
    @Test
    fun allThirtyNineDefaultsMatchTheNormativeTable() {
        val actual = with(ScreenCaptureEnginePolicyDefaults) {
            listOf(
                STARTUP_METRICS_TIMEOUT_MS,
                STARTUP_CAPTURE_GEOMETRY_TIMEOUT_MS,
                STARTUP_PLATFORM_RESUME_TIMEOUT_MS,
                PROVIDER_PREPARATION_START_TIMEOUT_MS,
                PROVIDER_PREPARATION_OPERATION_TIMEOUT_MS,
                ENCODER_DISPATCH_START_TIMEOUT_MS,
                ENCODE_OPERATION_TIMEOUT_MS,
                ENCODER_CLOSE_START_TIMEOUT_MS,
                ENCODER_CLOSE_OPERATION_TIMEOUT_MS,
                GL_OPERATION_START_TIMEOUT_MS,
                GL_OPERATION_TIMEOUT_MS,
                PLATFORM_READY_PERMIT_TIMEOUT_MS,
                PLATFORM_OPERATION_START_TIMEOUT_MS,
                PLATFORM_OPERATION_TIMEOUT_MS,
                PLATFORM_CLEANUP_START_RETRY_DELAYS_MS,
                LISTENER_RETIREMENT_START_TIMEOUT_MS,
                LISTENER_RETIREMENT_OPERATION_TIMEOUT_MS,
                MAX_GL_ERROR_DRAIN,
                RETURNED_ENCODER_FAILURE_THRESHOLD,
                MAX_RETURNED_ENCODER_RECOVERIES_PER_SESSION,
                MAX_ENCODER_OOM_RECOVERIES_PER_SESSION,
                MAX_CLEAN_PIPELINE_RESOURCE_RECOVERIES_PER_SESSION,
                TARGET_RECONFIGURATION_DELAYS_MS,
                MAX_RESOURCE_TRIM_WAKES_PER_CYCLE,
                PBO_SLOT_COUNT,
                PBO_COMPLETION_TIMEOUT_MS,
                MAX_ENCODED_SEGMENT_BYTES,
                TARGET_BUFFER_ADMISSION_ESTIMATE_COUNT,
                EVENT_EXTRA_BUFFER_CAPACITY,
                DIAGNOSTIC_REPEAT_WINDOW_MS,
                MAX_DIAGNOSTIC_MESSAGE_CHARS,
                MAX_QUARANTINED_PROVIDER_WORKERS,
                MAX_RETAINED_PROVIDER_DESCRIPTOR_SNAPSHOTS,
                MAX_ACTIVE_PREPARATIONS_PER_SESSION,
                MAX_OUTSTANDING_ENCODER_CLEANUP_RECORDS,
                MAX_ACTIVE_FRAME_SUBSCRIPTIONS,
                MAX_OUTSTANDING_CALLBACK_LEASES_PER_ENGINE,
                MAX_PENDING_GEOMETRIES,
                MAX_POTENTIALLY_BOUND_TARGETS,
            )
        }
        val expected = listOf(
            3_000L,
            3_000L,
            3_000L,
            1_000L,
            5_000L,
            1_000L,
            30_000L,
            1_000L,
            5_000L,
            1_000L,
            10_000L,
            1_000L,
            1_000L,
            10_000L,
            listOf(0L, 100L, 500L),
            1_000L,
            5_000L,
            16,
            3,
            1,
            1,
            1,
            listOf(0L, 100L, 500L),
            1,
            1,
            10_000L,
            1_048_576,
            4,
            64,
            1_000L,
            1_024,
            1,
            7,
            1,
            3,
            16,
            16,
            1,
            2,
        )

        assertEquals(39, actual.size)
        assertEquals(expected, actual)
    }
}
