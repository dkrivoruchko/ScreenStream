package io.screenstream.capture.internal.runtime

/**
 * Submits tasks to a non-inline execution mechanism.
 *
 * Implementations must not invoke a task reentrantly on the calling thread. A `true` return means only that the
 * task was accepted and may enter; it does not prove entry or progress. A `false` return means the task will not
 * enter.
 */
internal fun interface NonInlineDispatcher {
    fun tryDispatch(task: Runnable): Boolean
}

/**
 * Schedules tasks for non-inline entry after a monotonic delay.
 *
 * Acceptance is placement evidence only: the delay is not a deadline and does not prove that the task will enter.
 * Rejection means the task will not enter.
 */
internal fun interface DelayedEntryScheduler {
    fun trySchedule(task: Runnable, delayNanos: Long): Boolean
}
