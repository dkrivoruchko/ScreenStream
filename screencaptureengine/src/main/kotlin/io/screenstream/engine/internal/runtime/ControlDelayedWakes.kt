package io.screenstream.engine.internal.runtime

import io.screenstream.engine.internal.capture.CaptureCommand
import io.screenstream.engine.internal.jpeg.JpegEnteredOperation

/**
 * The complete, closed inventory of resource-free Control delayed wakes.
 *
 * A slot owns only scheduling identity. Its deadline remains a prompt to re-evaluate durable
 * Control state, never deadline authority. All methods are Control-confined.
 */
internal class ControlDelayedWakes internal constructor() {
    internal val readiness = ControlDelayedWakeSlot<ReadinessWake>()
    internal val pacing = ControlDelayedWakeSlot<PacingWake>()
    internal val repeat = ControlDelayedWakeSlot<RepeatWake>()
    internal val stats = ControlDelayedWakeSlot<StatsWake>()
    internal val captureTimeout = ControlDelayedWakeSlot<CaptureTimeoutWake>()
    internal val jpegTimeout = ControlDelayedWakeSlot<JpegTimeoutWake>()

    internal fun suppressAll() {
        readiness.suppress()
        pacing.suppress()
        repeat.suppress()
        stats.suppress()
        captureTimeout.suppress()
        jpegTimeout.suppress()
    }
}

internal sealed interface ControlDelayedWake {
    val targetNanos: Long
}

internal class ReadinessWake internal constructor(
    override val targetNanos: Long,
) : ControlDelayedWake

internal class PacingWake internal constructor(
    override val targetNanos: Long,
    internal val configRevision: Long,
) : ControlDelayedWake

internal class RepeatWake internal constructor(
    override val targetNanos: Long,
    internal val configRevision: Long,
) : ControlDelayedWake

internal class StatsWake internal constructor(
    override val targetNanos: Long,
) : ControlDelayedWake

internal class CaptureTimeoutWake internal constructor(
    override val targetNanos: Long,
    internal val command: CaptureCommand,
) : ControlDelayedWake

internal class JpegTimeoutWake internal constructor(
    override val targetNanos: Long,
    internal val operation: JpegEnteredOperation,
) : ControlDelayedWake {
    init {
        require(targetNanos == operation.deadlineNanos)
    }
}

internal class ControlDelayedWakeSlot<W : ControlDelayedWake> internal constructor() {
    private var current: W? = null

    /** Retains the already-current wake unless [candidate] is strictly earlier. */
    internal fun arm(candidate: W): Boolean {
        val installed = current
        if (installed != null && installed.targetNanos <= candidate.targetNanos) return false
        current = candidate
        return true
    }

    /** Consumes only the exact current scheduling identity. */
    internal fun enter(expected: W): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }

    /** Makes the exact current scheduling identity stale. */
    internal fun suppress(expected: W): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }

    /** Makes any current scheduling identity stale. */
    internal fun suppress(): W? = current.also { current = null }
}
