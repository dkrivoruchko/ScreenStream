package io.screenstream.capture.internal.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * A queue-less single-occupancy submission slot over a [NonInlineDispatcher].
 *
 * The exact attempt is installed before dispatch can expose it. Its body cannot enter until the dispatch call has
 * resolved as accepted. An accepted attempt owns the slot until its body returns normally; only then is the slot
 * released and its `afterTaskReleased` callback invoked. A throwing or nonreturning body therefore authorizes
 * neither release nor a successor. Rejection clears only the exact attempt that was not accepted.
 */
internal class SerialTaskSlot(
    private val dispatcher: NonInlineDispatcher,
) {
    internal sealed interface Submission {
        data object Accepted : Submission
        data object Occupied : Submission
        class Rejected(internal val cause: Exception?) : Submission
    }

    private class ContractViolation : IllegalStateException("NonInlineDispatcher invoked a task reentrantly on the calling thread")

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class DispatchAttempt(
        private val task: () -> Unit,
        private val afterTaskReleased: (() -> Unit)?,
        private val currentAttempt: AtomicReference<DispatchAttempt?>,
    ) : Runnable {
        private enum class Outcome { Pending, Enter, Rejected, ContractViolation, }

        private val dispatchingThread: Thread = Thread.currentThread()
        private var outcome: Outcome = Outcome.Pending

        fun publishReturned(accepted: Boolean): Boolean = publish(if (accepted) Outcome.Enter else Outcome.Rejected)

        fun publishRejected(): Boolean = publish(Outcome.Rejected)

        private fun publish(resolved: Outcome): Boolean = synchronized(this) {
            check((resolved != Outcome.Pending) && (resolved != Outcome.ContractViolation))
            if (outcome == Outcome.ContractViolation) return@synchronized false
            check(outcome == Outcome.Pending)
            outcome = resolved
            (this as Object).notifyAll()
            true
        }

        override fun run() {
            var interrupted = false
            val resolved = synchronized(this) {
                if ((outcome == Outcome.Pending) && (Thread.currentThread() === dispatchingThread)) {
                    outcome = Outcome.ContractViolation
                    (this as Object).notifyAll()
                    return@synchronized outcome
                }
                while (outcome == Outcome.Pending) {
                    try {
                        (this as Object).wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                outcome
            }
            if (interrupted) Thread.currentThread().interrupt()
            when (resolved) {
                Outcome.Enter -> enterAndRelease()
                Outcome.Rejected, Outcome.ContractViolation -> return
                Outcome.Pending -> error("Dispatch outcome remained pending after wait")
            }
        }

        private fun enterAndRelease() {
            task()
            if (currentAttempt.compareAndSet(this, null)) afterTaskReleased?.invoke()
        }
    }

    private val currentAttempt = AtomicReference<DispatchAttempt?>(null)

    internal fun trySubmit(task: () -> Unit, afterTaskReleased: (() -> Unit)? = null): Submission {
        val attempt = DispatchAttempt(task = task, afterTaskReleased = afterTaskReleased, currentAttempt = currentAttempt)
        if (!currentAttempt.compareAndSet(null, attempt)) return Submission.Occupied

        val accepted = try {
            dispatcher.tryDispatch(attempt)
        } catch (failure: Exception) {
            val published = attempt.publishRejected()
            currentAttempt.compareAndSet(attempt, null)
            if (!published) throw ContractViolation()
            return Submission.Rejected(failure)
        }
        if (!attempt.publishReturned(accepted)) {
            currentAttempt.compareAndSet(attempt, null)
            throw ContractViolation()
        }
        if (accepted) return Submission.Accepted
        currentAttempt.compareAndSet(attempt, null)
        return Submission.Rejected(cause = null)
    }
}
