package io.screenstream.engine.internal.delivery

import io.screenstream.engine.internal.observation.DeliveryCleanupReturn
import io.screenstream.engine.internal.observation.TerminalCleanupSink
import io.screenstream.engine.internal.runtime.AsyncSerialView
import io.screenstream.engine.internal.storage.EncodedPayloadLeaseRelease

/**
 * The sole Delivery execution role. It consumes an injected non-direct serial permit and owns no executor,
 * thread, queue, retry, watchdog, or waiting mechanism.
 */
internal class DeliveryRole internal constructor(
    private val sessionGate: Any,
    private val serialView: AsyncSerialView,
    private val capsule: DeliveryCapsule,
    private val boundary: DeliverySessionBoundary,
    private val terminalCleanupSink: TerminalCleanupSink,
) {
    /** The caller has already queued [handoff] in [capsule] while holding the same Session gate. */
    internal fun submit(handoff: DeliveryHandoff): DeliverySubmission {
        val permitReleasedWake = DeliveryPermitReleasedWake()
        val accepted = try {
            serialView.submit(
                task = { permitReleasedWake.install(runHandoff(handoff)) },
                afterPermitReleased = permitReleasedWake::request,
            )
        } catch (exception: Exception) {
            return DeliverySubmission.SubmissionFailedRetained(exception)
        } catch (fatal: Throwable) {
            throw fatal
        }
        return if (accepted) {
            DeliverySubmission.Accepted
        } else {
            closeRejectedSubmission(handoff, null)
        }
    }

    /** Exact callback-thread probe for front-door self-unsubscribe arbitration under [sessionGate]. */
    internal fun isSelfUnsubscribeLocked(registrationId: Long): Boolean =
        capsule.isEnteredCallbackThread(registrationId, Thread.currentThread())

    private fun runHandoff(handoff: DeliveryHandoff): DeliveryControlWake? {
        val entered = try {
            synchronized(sessionGate) {
                when {
                    capsule.entryState == DeliveryEntryState.CutoffInert && capsule.handoff === handoff -> false
                    boundary.isEntryAdmittedLocked(capsule, handoff) -> {
                        check(capsule.markEntered(handoff, Thread.currentThread()))
                        true
                    }

                    else -> {
                        check(capsule.markCutoffInert(handoff))
                        false
                    }
                }
            }
        } catch (exception: Exception) {
            return closeRejectedWorkerReturn(handoff, exception)
        } catch (fatal: Throwable) {
            try {
                closeWorkerFatalBeforeOpen(handoff, fatal)
            } catch (_: Throwable) {
                // Worker-side settlement cannot replace the exact fatal that escaped arbitration.
            }
            throw fatal
        }

        if (!entered) {
            val release = checkNotNull(handoff.borrowedFrame.releaseWithoutOpening())
            val result = DeliveryClosedResult.CutoffBeforeEntry(handoff, release)
            return settleRealReturn(handoff, result)
        }

        try {
            handoff.borrowedFrame.openOn(Thread.currentThread())
        } catch (exception: Exception) {
            val release = checkNotNull(handoff.borrowedFrame.releaseWithoutOpening())
            return settleRealReturn(
                handoff,
                DeliveryClosedResult.InternalFailure(handoff, release, exception),
            )
        } catch (fatal: Throwable) {
            val release = checkNotNull(handoff.borrowedFrame.releaseWithoutOpening())
            settleRealReturn(handoff, DeliveryClosedResult.DirectFatal(handoff, release, fatal))
            throw fatal
        }
        var callbackFailure: Throwable? = null
        val release: EncodedPayloadLeaseRelease
        try {
            handoff.callback(handoff.borrowedFrame.frame)
        } catch (raw: Throwable) {
            callbackFailure = raw
        } finally {
            release = checkNotNull(handoff.borrowedFrame.closeAndRelease())
        }

        val result = when (val raw = callbackFailure) {
            null -> DeliveryClosedResult.CallbackReturned(handoff, release)
            is Exception -> DeliveryClosedResult.CallbackException(handoff, release, raw)
            else -> DeliveryClosedResult.DirectFatal(handoff, release, raw)
        }
        val wake = settleRealReturn(handoff, result)
        if (callbackFailure != null && callbackFailure !is Exception) throw callbackFailure
        return wake
    }

    private fun closeRejectedSubmission(handoff: DeliveryHandoff, exception: Exception?): DeliverySubmission.Rejected {
        val closure = closeRejected(handoff, exception)
        closure.outcome.controlWake?.request()
        return DeliverySubmission.Rejected(closure.result, closure.outcome.disposition)
    }

    private fun closeRejectedWorkerReturn(handoff: DeliveryHandoff, exception: Exception): DeliveryControlWake? =
        closeRejected(handoff, exception).outcome.controlWake

    private fun closeRejected(handoff: DeliveryHandoff, exception: Exception?): DeliveryRejectedClosure {
        val release = checkNotNull(handoff.borrowedFrame.releaseWithoutOpening())
        val result = DeliveryClosedResult.InternalFailure(handoff, release, exception)
        val outcome = synchronized(sessionGate) {
            check(capsule.markCutoffInert(handoff))
            settleLocked(handoff, result)
        }
        observeTerminalReturn(outcome.disposition, exception)
        return DeliveryRejectedClosure(result, outcome)
    }

    private fun closeWorkerFatalBeforeOpen(handoff: DeliveryHandoff, fatal: Throwable) {
        val release = checkNotNull(handoff.borrowedFrame.releaseWithoutOpening())
        val result = DeliveryClosedResult.DirectFatal(handoff, release, fatal)
        val outcome = synchronized(sessionGate) {
            if (capsule.entryState == DeliveryEntryState.Queued) {
                check(capsule.markCutoffInert(handoff))
            } else {
                check(
                    capsule.handoff === handoff &&
                            (capsule.entryState == DeliveryEntryState.Entered ||
                                    capsule.entryState == DeliveryEntryState.CutoffInert),
                )
            }
            settleLocked(handoff, result)
        }
        observeTerminalReturn(outcome.disposition, fatal)
        boundary.selectDirectFatal(result)
    }

    private fun settleRealReturn(
        handoff: DeliveryHandoff,
        result: DeliveryClosedResult,
    ): DeliveryControlWake? {
        val outcome = synchronized(sessionGate) { settleLocked(handoff, result) }
        val cause = when (result) {
            is DeliveryClosedResult.CallbackException -> result.exception
            is DeliveryClosedResult.InternalFailure -> result.exception
            is DeliveryClosedResult.DirectFatal -> result.fatal
            is DeliveryClosedResult.CallbackReturned,
            is DeliveryClosedResult.CutoffBeforeEntry,
                -> null
        }
        observeTerminalReturn(outcome.disposition, cause)
        if (result is DeliveryClosedResult.DirectFatal) boundary.selectDirectFatal(result)
        return outcome.controlWake
    }

    private fun settleLocked(
        handoff: DeliveryHandoff,
        result: DeliveryClosedResult,
    ): DeliveryInstallOutcome {
        capsule.recordRealReturn(handoff, result)
        val outcome = boundary.installClosedResultLocked(capsule, handoff, result)
        capsule.closeInstalled(handoff, result)
        return outcome
    }

    private fun observeTerminalReturn(disposition: DeliveryInstallDisposition, cause: Throwable?) {
        if (disposition == DeliveryInstallDisposition.TerminalCleanup) {
            terminalCleanupSink.deliveryReturned(DeliveryCleanupReturn(capsule, null, false, cause))
        }
    }

    private class DeliveryRejectedClosure(
        val result: DeliveryClosedResult.InternalFailure,
        val outcome: DeliveryInstallOutcome,
    )

    /** One accepted Delivery task can install at most one post-permit Control wake. */
    private class DeliveryPermitReleasedWake {
        private var installed = false
        private var wake: DeliveryControlWake? = null

        fun install(candidate: DeliveryControlWake?) {
            check(!installed)
            wake = candidate
            installed = true
        }

        fun request() {
            check(installed)
            installed = false
            val claimed = wake
            wake = null
            claimed?.request()
        }
    }
}
