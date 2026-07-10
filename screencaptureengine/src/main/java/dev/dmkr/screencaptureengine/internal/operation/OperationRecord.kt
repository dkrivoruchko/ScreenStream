@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.operation

internal enum class OperationPhase {
    Queued,
    StartTimedOut,
    Started,
    Returned,
    Failed,
}

internal enum class OperationReturnKind {
    Returned,
    Failed,
}

internal data class OperationReturnFact<out T : Any>(
    val kind: OperationReturnKind,
    val value: T,
    val completedAtNanos: Long,
    val cancellationMarkedBeforeReturn: Boolean,
)

internal data class OperationRecordSnapshot<T : Any>(
    val identity: WorkIdentity,
    val phase: OperationPhase,
    val startedAtNanos: Long?,
    val returnFact: OperationReturnFact<T>?,
    val cancellationMarker: OperationCancellationMarker?,
    val cancellationMarkedBeforeReturn: Boolean?,
    val completion: OperationCompletionSnapshot<OperationReturnFact<T>>,
)

internal sealed interface OperationCompletion<out T> {
    data class Returned<T>(
        val fact: T,
    ) : OperationCompletion<T>

    data object TimedOut : OperationCompletion<Nothing>
}

internal sealed interface OperationCompletionDecision<out T> {
    data class Completed<T>(
        val fact: T,
    ) : OperationCompletionDecision<T>

    data object TimedOut : OperationCompletionDecision<Nothing>

    data class LateReturn<T>(
        val fact: T,
    ) : OperationCompletionDecision<T>

    data class AlreadyCompleted<T>(
        val completion: OperationCompletion<T>,
        val retainedReturnFact: T?,
    ) : OperationCompletionDecision<T>
}

internal sealed interface OperationCleanupClaim<out T> {
    data class Claimed<T>(
        val fact: T,
    ) : OperationCleanupClaim<T>

    data object AwaitingReturn : OperationCleanupClaim<Nothing>

    data class AlreadyClaimed<T>(
        val fact: T,
    ) : OperationCleanupClaim<T>
}

internal data class OperationCompletionSnapshot<T>(
    val completion: OperationCompletion<T>?,
    val retainedReturnFact: T?,
    val cleanupClaimed: Boolean,
)

internal sealed interface OperationStartFact {
    data class Started(
        val startedAtNanos: Long,
    ) : OperationStartFact

    data class CancelledBeforeStart(
        val marker: OperationCancellationMarker,
    ) : OperationStartFact

    data class NotQueued(
        val phase: OperationPhase,
    ) : OperationStartFact
}

internal sealed interface OperationStartTimeoutFact<out T : Any> {
    data object TimedOutBeforeStart : OperationStartTimeoutFact<Nothing>

    data class AlreadyStarted(
        val startedAtNanos: Long,
    ) : OperationStartTimeoutFact<Nothing>

    data class AlreadyCompleted<T : Any>(
        val fact: OperationReturnFact<T>,
    ) : OperationStartTimeoutFact<T>

    data object AlreadyTimedOut : OperationStartTimeoutFact<Nothing>

    data class CancelledBeforeStart(
        val marker: OperationCancellationMarker,
    ) : OperationStartTimeoutFact<Nothing>
}

internal sealed interface OperationCancellationFact {
    data object MarkedBeforeReturn : OperationCancellationFact

    data object MarkedAfterReturn : OperationCancellationFact

    data class AlreadyMarked(
        val marker: OperationCancellationMarker,
        val beforeReturn: Boolean,
    ) : OperationCancellationFact
}

internal sealed interface OperationReturnRecordingFact<out T : Any> {
    data class Recorded<T : Any>(
        val fact: OperationReturnFact<T>,
        val completionDecision: OperationCompletionDecision<OperationReturnFact<T>>,
    ) : OperationReturnRecordingFact<T>

    data class AlreadyRecorded<T : Any>(
        val fact: OperationReturnFact<T>,
    ) : OperationReturnRecordingFact<T>

    data object NotStarted : OperationReturnRecordingFact<Nothing>
}

internal sealed interface OperationTimeoutFenceFact<out T : Any> {
    data class NotStarted(
        val phase: OperationPhase,
    ) : OperationTimeoutFenceFact<Nothing>

    data class Decided<T : Any>(
        val decision: OperationCompletionDecision<OperationReturnFact<T>>,
    ) : OperationTimeoutFenceFact<T>
}

/**
 * Mechanical lifecycle record for one ownership-sensitive operation.
 *
 * The record stores the worker return fact before making its completion decision visible. Start
 * timeout, operation timeout, cancellation, and return all linearize under one record lock; no
 * executor/future state is used as ownership evidence. Public severity, retry, poison, and cleanup
 * policy remain controller-owned.
 */
internal class OperationRecord<T : Any>(
    internal val identity: WorkIdentity,
) {
    private val lock = Any()

    private var phase = OperationPhase.Queued
    private var startedAtNanos: Long? = null
    private var returnFact: OperationReturnFact<T>? = null
    private var cancellationMarker: OperationCancellationMarker? = null
    private var cancellationMarkedBeforeReturn: Boolean? = null
    private var completion: OperationCompletion<OperationReturnFact<T>>? = null
    private var cleanupClaimed = false

    internal fun snapshot(): OperationRecordSnapshot<T> =
        synchronized(lock) {
            snapshotLocked()
        }

    internal fun tryStart(startedAtNanos: Long): OperationStartFact {
        require(startedAtNanos >= 0L) { "Operation start timestamp must be non-negative." }
        return synchronized(lock) {
            when {
                phase != OperationPhase.Queued -> OperationStartFact.NotQueued(phase)
                cancellationMarker != null -> OperationStartFact.CancelledBeforeStart(checkNotNull(cancellationMarker))
                else -> {
                    phase = OperationPhase.Started
                    this.startedAtNanos = startedAtNanos
                    OperationStartFact.Started(startedAtNanos)
                }
            }
        }
    }

    /** Atomically decides the queued start deadline without creating an engine-cancellation marker. */
    internal fun fenceStartTimeout(): OperationStartTimeoutFact<T> =
        synchronized(lock) {
            when (phase) {
                OperationPhase.Queued -> {
                    val marker = cancellationMarker
                    if (marker != null) {
                        OperationStartTimeoutFact.CancelledBeforeStart(marker)
                    } else {
                        phase = OperationPhase.StartTimedOut
                        OperationStartTimeoutFact.TimedOutBeforeStart
                    }
                }

                OperationPhase.StartTimedOut -> OperationStartTimeoutFact.AlreadyTimedOut
                OperationPhase.Started -> OperationStartTimeoutFact.AlreadyStarted(
                    startedAtNanos = checkNotNull(startedAtNanos),
                )

                OperationPhase.Returned,
                OperationPhase.Failed,
                -> OperationStartTimeoutFact.AlreadyCompleted(fact = checkNotNull(returnFact))
            }
        }

    internal fun markCancellation(marker: OperationCancellationMarker): OperationCancellationFact =
        synchronized(lock) {
            cancellationMarker?.let { existing ->
                return@synchronized OperationCancellationFact.AlreadyMarked(
                    marker = existing,
                    beforeReturn = checkNotNull(cancellationMarkedBeforeReturn),
                )
            }

            cancellationMarker = marker
            val beforeReturn = returnFact == null
            cancellationMarkedBeforeReturn = beforeReturn
            if (beforeReturn) {
                OperationCancellationFact.MarkedBeforeReturn
            } else {
                OperationCancellationFact.MarkedAfterReturn
            }
        }

    internal fun fenceOperationTimeout(): OperationTimeoutFenceFact<T> =
        synchronized(lock) {
            when (phase) {
                OperationPhase.Queued,
                OperationPhase.StartTimedOut,
                -> OperationTimeoutFenceFact.NotStarted(phase)

                OperationPhase.Started,
                OperationPhase.Returned,
                OperationPhase.Failed,
                -> OperationTimeoutFenceFact.Decided(timeOutOperationLocked())
            }
        }

    internal fun recordReturned(
        value: T,
        completedAtNanos: Long,
    ): OperationReturnRecordingFact<T> =
        recordReturn(kind = OperationReturnKind.Returned, value = value, completedAtNanos = completedAtNanos)

    internal fun recordFailed(
        value: T,
        completedAtNanos: Long,
    ): OperationReturnRecordingFact<T> =
        recordReturn(kind = OperationReturnKind.Failed, value = value, completedAtNanos = completedAtNanos)

    internal fun claimCleanup(): OperationCleanupClaim<OperationReturnFact<T>> =
        synchronized(lock) {
            val fact = returnFact ?: return@synchronized OperationCleanupClaim.AwaitingReturn
            if (cleanupClaimed) {
                OperationCleanupClaim.AlreadyClaimed(fact)
            } else {
                cleanupClaimed = true
                OperationCleanupClaim.Claimed(fact)
            }
        }

    private fun recordReturn(
        kind: OperationReturnKind,
        value: T,
        completedAtNanos: Long,
    ): OperationReturnRecordingFact<T> {
        require(completedAtNanos >= 0L) { "Operation completion timestamp must be non-negative." }
        return synchronized(lock) {
            val existing = returnFact
            when {
                phase == OperationPhase.Queued || phase == OperationPhase.StartTimedOut -> {
                    OperationReturnRecordingFact.NotStarted
                }
                existing != null -> OperationReturnRecordingFact.AlreadyRecorded(existing)
                else -> {
                    val startTimestamp = checkNotNull(startedAtNanos)
                    require(completedAtNanos >= startTimestamp) {
                        "Operation completion timestamp must not precede its start timestamp."
                    }
                    val fact = OperationReturnFact(
                        kind = kind,
                        value = value,
                        completedAtNanos = completedAtNanos,
                        cancellationMarkedBeforeReturn = cancellationMarkedBeforeReturn == true,
                    )
                    returnFact = fact
                    phase = when (kind) {
                        OperationReturnKind.Returned -> OperationPhase.Returned
                        OperationReturnKind.Failed -> OperationPhase.Failed
                    }
                    OperationReturnRecordingFact.Recorded(
                        fact = fact,
                        completionDecision = completeOperationLocked(fact),
                    )
                }
            }
        }
    }

    private fun snapshotLocked(): OperationRecordSnapshot<T> =
        OperationRecordSnapshot(
            identity = identity,
            phase = phase,
            startedAtNanos = startedAtNanos,
            returnFact = returnFact,
            cancellationMarker = cancellationMarker,
            cancellationMarkedBeforeReturn = cancellationMarkedBeforeReturn,
            completion = OperationCompletionSnapshot(
                completion = completion,
                retainedReturnFact = returnFact,
                cleanupClaimed = cleanupClaimed,
            ),
        )

    private fun completeOperationLocked(
        fact: OperationReturnFact<T>,
    ): OperationCompletionDecision<OperationReturnFact<T>> = when (completion) {
        null -> {
            completion = OperationCompletion.Returned(fact)
            OperationCompletionDecision.Completed(fact)
        }

        OperationCompletion.TimedOut -> OperationCompletionDecision.LateReturn(fact)
        is OperationCompletion.Returned<*> -> error("A returned completion must have only one return fact.")
    }

    private fun timeOutOperationLocked(): OperationCompletionDecision<OperationReturnFact<T>> =
        when (val existing = completion) {
            null -> {
                completion = OperationCompletion.TimedOut
                OperationCompletionDecision.TimedOut
            }

            else -> OperationCompletionDecision.AlreadyCompleted(
                completion = existing,
                retainedReturnFact = returnFact,
            )
        }
}
