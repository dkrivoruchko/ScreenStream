package dev.dmkr.screencaptureengine.internal.lifecycle

/**
 * Tracks consecutive runtime encode hard failures for one caller-defined health key at a time.
 *
 * The caller owns synchronization and current-generation validation. A different failure key starts
 * a new streak, while a successful result resets only the matching streak.
 */
internal class RuntimeEncodeHardFailureTracker<Key : Any> internal constructor(
    private val threshold: Int,
) {
    private var streakKey: Key? = null
    private var consecutiveHardFailures: Int = 0

    init {
        require(threshold > 0) { "Runtime encode hard-failure threshold must be positive, was $threshold." }
    }

    internal fun recordHardFailure(key: Key): RuntimeEncodeHardFailureResult {
        if (streakKey != key) {
            streakKey = key
            consecutiveHardFailures = 0
        }
        check(consecutiveHardFailures < threshold) {
            "Runtime encode hard-failure threshold was already reached for this key; reset the tracker before recording another failure."
        }

        consecutiveHardFailures += 1
        return if (consecutiveHardFailures == threshold) {
            RuntimeEncodeHardFailureResult.ThresholdReached
        } else {
            RuntimeEncodeHardFailureResult.BelowThreshold
        }
    }

    internal fun recordSuccess(key: Key) {
        if (streakKey == key) {
            reset()
        }
    }

    internal fun reset() {
        streakKey = null
        consecutiveHardFailures = 0
    }
}

internal sealed interface RuntimeEncodeHardFailureResult {
    data object BelowThreshold : RuntimeEncodeHardFailureResult

    data object ThresholdReached : RuntimeEncodeHardFailureResult
}
