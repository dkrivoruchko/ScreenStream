package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.runtime.AsyncSerialView
import io.screenstream.engine.internal.runtime.ElapsedRealtimeClock

internal sealed interface JpegEntryDecision {
    class Entered internal constructor(
        internal val operation: JpegEnteredOperation?,
    ) : JpegEntryDecision

    data object CutoffInert : JpegEntryDecision
}

/** Session-gate boundary for the closed concrete JPEG task inventory. */
internal interface JpegTaskBoundary {
    fun arbitrate(capsule: EncoderCapsule, task: EncoderTask): JpegEntryDecision

    /** The first gated mutation closes the exact task in [EncoderCapsule]. */
    fun onReturned(capsule: EncoderCapsule, returned: EncoderTaskReturn)

    fun onSkippedBeforeEntry(capsule: EncoderCapsule, returned: EncoderTaskReturn)

    /** Records the identical fatal only; a fatal has no permit-release continuation. */
    fun onFatal(capsule: EncoderCapsule, task: EncoderTask, fatal: Throwable)
}

/** Called only after the JPEG serial permit has physically been released. */
internal fun interface JpegPermitReleasedSink {
    fun onPermitReleased(capsule: EncoderCapsule, task: EncoderTask)
}

internal sealed interface JpegSubmission {
    data object Accepted : JpegSubmission
    data object PermitUnavailableRetained : JpegSubmission

    class SubmissionFailedRetained internal constructor(
        internal val cause: Exception,
    ) : JpegSubmission
}

/** One injected non-direct serial JPEG role; all lifecycle and currentness remain in Control. */
internal class JpegRole internal constructor(
    private val serialPermit: AsyncSerialView,
    private val clock: ElapsedRealtimeClock,
    private val boundary: JpegTaskBoundary,
    private val permitReleasedSink: JpegPermitReleasedSink,
) {
    internal fun submitQueued(capsule: EncoderCapsule, task: EncoderTask): JpegSubmission {
        check(capsule.namesQueued(task))
        val accepted = try {
            serialPermit.submit(
                task = { runOne(capsule, task) },
                afterPermitReleased = { permitReleasedSink.onPermitReleased(capsule, task) },
            )
        } catch (failure: Exception) {
            return JpegSubmission.SubmissionFailedRetained(failure)
        }
        return if (accepted) JpegSubmission.Accepted else JpegSubmission.PermitUnavailableRetained
    }

    private fun runOne(capsule: EncoderCapsule, task: EncoderTask) {
        val decision = try {
            boundary.arbitrate(capsule, task)
        } catch (fatal: Throwable) {
            notifyFatalWithoutReplacing(capsule, task, fatal)
            throw fatal
        }

        when (decision) {
            is JpegEntryDecision.Entered -> {
                check(capsule.entryState == EncoderEntryState.Entered)
                val result = try {
                    executeEntered(task)
                } catch (fatal: Throwable) {
                    notifyFatalWithoutReplacing(capsule, task, fatal)
                    throw fatal
                }
                val returnedAtNanos = decision.operation?.let { clock.nanos() }
                try {
                    boundary.onReturned(
                        capsule,
                        EncoderTaskReturn(task, result, decision.operation, returnedAtNanos),
                    )
                } catch (fatal: Throwable) {
                    notifyFatalWithoutReplacing(capsule, task, fatal)
                    throw fatal
                }
            }

            JpegEntryDecision.CutoffInert -> {
                check(capsule.entryState == EncoderEntryState.CutoffInert)
                val result = try {
                    skipBeforeEntry(task)
                } catch (fatal: Throwable) {
                    notifyFatalWithoutReplacing(capsule, task, fatal)
                    throw fatal
                }
                try {
                    boundary.onSkippedBeforeEntry(
                        capsule,
                        EncoderTaskReturn(task, result, operation = null, returnedAtNanos = null),
                    )
                } catch (fatal: Throwable) {
                    notifyFatalWithoutReplacing(capsule, task, fatal)
                    throw fatal
                }
            }
        }
    }

    private fun executeEntered(task: EncoderTask): EncoderTaskResult = when (task) {
        is EncoderBackendPreparationTask -> EncoderTaskResult.BackendPrepared(
            EncoderRuntime.prepareBackend(task.backendPolicy, task.existingHealthCell),
        )

        is EncoderNativeAllocationTask -> EncoderTaskResult.RuntimeAllocated(
            EncoderRuntime.allocateNativeRuntime(task.layout, task.preparation, task.candidate),
        )

        is EncoderManagedAllocationTask -> EncoderTaskResult.RuntimeAllocated(
            EncoderRuntime.allocateManagedRuntime(task.layout, task.preparation),
        )

        is EncoderFrameworkPreparationTask -> EncoderTaskResult.FrameworkPrepared(
            task.runtime.prepareFrameworkOwner(),
        )

        is EncoderRuntimeRetirementTask -> EncoderTaskResult.FrameworkRetired(
            task.runtime.retireFrameworkOwner(),
        )

        is EncoderCarrierRetirementTask -> EncoderTaskResult.CarrierRetired(
            task.runtime.retireCarrier(),
        )

        is EncoderUninstalledFrameworkRetirementTask -> EncoderTaskResult.UninstalledFrameworkRetired(
            retireUninstalledFrameworkOwner(task.preparation),
        )

        is EncoderProductionTask.Framework -> EncoderTaskResult.FrameworkProduced(
            executeFrameworkJpeg(task.production, clock),
        )

        is EncoderProductionTask.Native -> EncoderTaskResult.NativeProduced(
            executeNativeJpeg(task.production, clock),
        )
    }

    private fun skipBeforeEntry(task: EncoderTask): EncoderTaskResult? = when (task) {
        is EncoderProductionTask.Framework -> EncoderTaskResult.FrameworkProduced(
            checkNotNull(task.runtime.skipBeforeEntry(task.production)),
        )

        is EncoderProductionTask.Native -> EncoderTaskResult.NativeProduced(
            checkNotNull(task.runtime.skipNativeBeforeEntry(task.production)),
        )

        is EncoderBackendPreparationTask,
        is EncoderNativeAllocationTask,
        is EncoderManagedAllocationTask,
        is EncoderFrameworkPreparationTask,
        is EncoderRuntimeRetirementTask,
        is EncoderCarrierRetirementTask,
        is EncoderUninstalledFrameworkRetirementTask,
            -> null
    }

    private fun notifyFatalWithoutReplacing(
        capsule: EncoderCapsule,
        task: EncoderTask,
        fatal: Throwable,
    ) {
        try {
            boundary.onFatal(capsule, task, fatal)
        } catch (_: Throwable) {
            // The task-side fatal remains the exact propagated object.
        }
    }
}
