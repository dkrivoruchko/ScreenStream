@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.control

import dev.dmkr.screencaptureengine.internal.policy.ScreenCaptureEnginePolicyDefaults

internal class TargetRetryAdmission internal constructor(
    internal val slotNumber: Int,
    internal val eligibleAt: MonotonicDeadline,
    private val scheduleIdentity: Any,
) {
    internal fun belongsTo(scheduleIdentity: Any): Boolean = this.scheduleIdentity === scheduleIdentity
}

internal sealed interface TargetRetryAdvance {
    data class NextSlot(
        val eligibleAt: MonotonicDeadline,
    ) : TargetRetryAdvance

    data object Exhausted : TargetRetryAdvance

    data object Stale : TargetRetryAdvance
}

/** Controller-confined mechanical three-slot epoch schedule; control supplies qualifying-return facts. */
internal class TargetRetrySchedule(
    epochOriginNanos: Long,
) {
    private val identity = Any()
    private val eligibility = ScreenCaptureEnginePolicyDefaults.TARGET_RECONFIGURATION_DELAYS_MS.map { delayMillis ->
        deadlineAfter(
            anchorNanos = epochOriginNanos,
            deltaNanos = Math.multiplyExact(delayMillis, NANOS_PER_MILLISECOND),
        )
    }

    private var nextSlotIndex = 0
    private var activeAdmission: TargetRetryAdmission? = null
    private var closed = false

    init {
        require(epochOriginNanos >= 0L) { "The retry epoch origin must be non-negative." }
        check(eligibility.size == TOTAL_TARGET_ADMISSIONS) {
            "The target retry policy must define exactly three total admissions."
        }
    }

    internal val remainingAdmissionCount: Int
        get() = if (closed) 0 else eligibility.size - nextSlotIndex

    internal val nextEligibility: MonotonicDeadline?
        get() = if (closed || activeAdmission != null) null else eligibility.getOrNull(nextSlotIndex)

    internal fun tryAdmit(rawNowNanos: Long): TargetRetryAdmission? {
        require(rawNowNanos >= 0L) { "The monotonic clock sample must be non-negative." }
        if (closed || activeAdmission != null) return null
        val eligibleAt = eligibility.getOrNull(nextSlotIndex) ?: return null
        if (eligibleAt !is MonotonicDeadline.Finite || rawNowNanos < eligibleAt.elapsedRealtimeNanos) return null

        val admission = TargetRetryAdmission(
            slotNumber = nextSlotIndex + 1,
            eligibleAt = eligibleAt,
            scheduleIdentity = identity,
        )
        nextSlotIndex += 1
        activeAdmission = admission
        return admission
    }

    internal fun recordQualifyingTransientReturn(admission: TargetRetryAdmission): TargetRetryAdvance {
        if (!ownsActive(admission)) return TargetRetryAdvance.Stale
        activeAdmission = null
        val next = eligibility.getOrNull(nextSlotIndex)
        if (next == null) {
            closed = true
            return TargetRetryAdvance.Exhausted
        }
        return TargetRetryAdvance.NextSlot(eligibleAt = next)
    }

    internal fun recordNonQualifyingCompletion(admission: TargetRetryAdmission): Boolean {
        if (!ownsActive(admission)) return false
        activeAdmission = null
        closed = true
        return true
    }

    private fun ownsActive(admission: TargetRetryAdmission): Boolean =
        admission.belongsTo(identity) && activeAdmission === admission
}

private const val TOTAL_TARGET_ADMISSIONS: Int = 3
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
