package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.internal.capture.CapturePlan

internal enum class EncoderEntryState {
    Queued,
    Entered,
    CutoffInert,
    Closed,
}

internal class ProductionRecord internal constructor(
    internal val configRevision: Long,
    internal val productionId: Long,
) {
    init {
        require(configRevision > 0L)
        require(productionId > 0L)
    }
}

/** Closed JPEG-role task inventory. Object identity is local ownership evidence, never a Session generation. */
internal sealed interface EncoderTask

internal sealed interface EncoderSetupTask : EncoderTask {
    val plan: CapturePlan
}

internal class EncoderBackendPreparationTask internal constructor(
    override val plan: CapturePlan,
    internal val backendPolicy: JpegBackendPolicy,
    internal val existingHealthCell: NativeHealthCell?,
) : EncoderSetupTask

internal class EncoderNativeAllocationTask internal constructor(
    override val plan: CapturePlan,
    internal val layout: RgbaLayout,
    internal val preparation: EncoderBackendPreparation.NativeCarrier,
    internal val candidate: NativeMallocCarrier,
) : EncoderSetupTask {
    init {
        check(candidate.layout === layout)
    }
}

internal class EncoderManagedAllocationTask internal constructor(
    override val plan: CapturePlan,
    internal val layout: RgbaLayout,
    internal val preparation: EncoderBackendPreparation.ManagedCarrier,
) : EncoderSetupTask

internal class EncoderFrameworkPreparationTask internal constructor(
    override val plan: CapturePlan,
    internal val runtime: EncoderRuntime,
    internal val fallback: Boolean,
) : EncoderSetupTask

/** First, independently timed retirement stage: recycle the installed Framework owner when present. */
internal class EncoderRuntimeRetirementTask internal constructor(
    internal val runtime: EncoderRuntime,
) : EncoderTask

/** Second, independently timed retirement stage: free/drop the exact carrier after Bitmap retirement. */
internal class EncoderCarrierRetirementTask internal constructor(
    internal val runtime: EncoderRuntime,
) : EncoderTask

/** Retires a returned Framework owner which currentness arbitration forbade from being installed. */
internal class EncoderUninstalledFrameworkRetirementTask internal constructor(
    internal val preparation: EncoderFrameworkPreparation,
) : EncoderTask {
    internal val runtime: EncoderRuntime = preparation.runtime
}

/** Exact uninstalled owner whose one-shot recycle returned retained; this residue is never retried. */
internal class EncoderUninstalledFrameworkResidue internal constructor(
    internal val preparation: EncoderFrameworkPreparation,
    internal val retirement: FrameworkBitmapRetirement.Retained,
) {
    init {
        check(
            preparation is EncoderFrameworkPreparation.Prepared ||
                    (preparation is EncoderFrameworkPreparation.Failed && preparation.ownerResidue != null),
        )
    }
}

internal sealed interface EncoderProductionTask : EncoderTask {
    val runtime: EncoderRuntime
    val record: ProductionRecord

    class Framework internal constructor(
        internal val production: FrameworkProduction,
    ) : EncoderProductionTask {
        override val runtime: EncoderRuntime = production.runtime
        override val record: ProductionRecord = production.record
    }

    class Native internal constructor(
        internal val production: NativeJpegProduction,
    ) : EncoderProductionTask {
        override val runtime: EncoderRuntime = production.runtime
        override val record: ProductionRecord = production.record
    }
}

/** Closed JPEG-role result inventory; each leaf is valid only for its matching concrete task. */
internal sealed interface EncoderTaskResult {
    class BackendPrepared internal constructor(
        internal val result: EncoderBackendPreparation,
    ) : EncoderTaskResult

    class RuntimeAllocated internal constructor(
        internal val result: EncoderRuntimeCreation,
    ) : EncoderTaskResult

    class FrameworkPrepared internal constructor(
        internal val result: EncoderFrameworkPreparation,
    ) : EncoderTaskResult

    class FrameworkRetired internal constructor(
        internal val result: EncoderFrameworkRetirement,
    ) : EncoderTaskResult

    class CarrierRetired internal constructor(
        internal val result: EncoderRuntimeRetirement,
    ) : EncoderTaskResult

    class UninstalledFrameworkRetired internal constructor(
        internal val result: FrameworkBitmapRetirement,
    ) : EncoderTaskResult

    class FrameworkProduced internal constructor(
        internal val result: FrameworkJpegResult,
    ) : EncoderTaskResult

    class NativeProduced internal constructor(
        internal val result: NativeJpegResult,
    ) : EncoderTaskResult
}

/** Durable entered-operation boundary used by the one JPEG timeout family. */
internal class JpegEnteredOperation internal constructor(
    internal val task: EncoderTask,
    internal val startedAtNanos: Long,
    internal val deadlineNanos: Long,
) {
    init {
        require(startedAtNanos >= 0L)
        require(deadlineNanos > startedAtNanos)
    }
}

/** Exact normal-return fact. A null operation/result denotes cutoff before outward entry. */
internal class EncoderTaskReturn internal constructor(
    internal val task: EncoderTask,
    internal val result: EncoderTaskResult?,
    internal val operation: JpegEnteredOperation?,
    internal val returnedAtNanos: Long?,
) {
    init {
        require((operation == null) == (returnedAtNanos == null))
        require(operation == null || operation.task === task)
        require(returnedAtNanos == null || returnedAtNanos >= operation!!.startedAtNanos)
    }

    internal val returnedTimely: Boolean
        get() = operation == null || checkNotNull(returnedAtNanos) < operation.deadlineNanos
}

/** Permanent JPEG ownership root inspected only while the session gate is held. */
internal class EncoderCapsule internal constructor() {
    internal var entryState: EncoderEntryState = EncoderEntryState.Closed
        private set
    internal var runtime: EncoderRuntime? = null
        private set
    internal var task: EncoderTask? = null
        private set
    internal var enteredOperation: JpegEnteredOperation? = null
        private set
    internal var pendingTaskAfterPermitRelease: EncoderTask? = null
        private set
    internal var uninstalledFrameworkResidue: EncoderUninstalledFrameworkResidue? = null
        private set
    internal var nativeHealthCell: NativeHealthCell? = null
        private set

    internal val productionTask: EncoderProductionTask?
        get() = task as? EncoderProductionTask
    internal val setupTask: EncoderSetupTask?
        get() = task as? EncoderSetupTask
    internal val runtimeRetirementTask: EncoderRuntimeRetirementTask?
        get() = task as? EncoderRuntimeRetirementTask

    internal val hasUnresolvedOwnership: Boolean
        get() = runtime != null || entryState != EncoderEntryState.Closed || task != null ||
                enteredOperation != null || pendingTaskAfterPermitRelease != null ||
                uninstalledFrameworkResidue != null

    internal val processLifetimeResidueCause: Throwable?
        get() = uninstalledFrameworkResidue?.retirement?.cause

    internal val hasOnlyProcessLifetimeResidue: Boolean
        get() = uninstalledFrameworkResidue != null && runtime == null &&
                entryState == EncoderEntryState.Closed && task == null && enteredOperation == null &&
                pendingTaskAfterPermitRelease == null

    internal fun installInitialRuntime(candidate: EncoderRuntime) {
        check(runtime == null)
        checkIdleTaskSlot()
        val installedHealth = nativeHealthCell
        if (installedHealth == null) nativeHealthCell = candidate.nativeHealthCell
        else check(installedHealth === candidate.nativeHealthCell)
        runtime = candidate
    }

    internal fun queue(exactTask: EncoderTask) {
        checkIdleTaskSlot()
        when (exactTask) {
            is EncoderBackendPreparationTask -> {
                check(runtime == null)
                check(exactTask.existingHealthCell === nativeHealthCell)
            }

            is EncoderNativeAllocationTask -> {
                check(runtime == null)
                check(exactTask.preparation.nativeHealthCell === nativeHealthCell)
                check(exactTask.candidate.layout === exactTask.layout)
            }

            is EncoderManagedAllocationTask -> {
                check(runtime == null)
                check(exactTask.preparation.nativeHealthCell === nativeHealthCell)
            }

            is EncoderFrameworkPreparationTask -> checkRuntimeTask(exactTask.runtime)
            is EncoderRuntimeRetirementTask -> checkRuntimeTask(exactTask.runtime)
            is EncoderCarrierRetirementTask -> checkRuntimeTask(exactTask.runtime)
            is EncoderUninstalledFrameworkRetirementTask -> {
                checkRuntimeTask(exactTask.runtime)
                check(uninstalledFrameworkResidue == null)
            }

            is EncoderProductionTask -> checkRuntimeTask(exactTask.runtime)
        }
        task = exactTask
        entryState = EncoderEntryState.Queued
    }

    internal fun namesQueued(expected: EncoderTask): Boolean =
        entryState == EncoderEntryState.Queued && task === expected && enteredOperation == null

    internal fun markEntered(expected: EncoderTask, operation: JpegEnteredOperation?): Boolean {
        if (!namesQueued(expected) || operation?.task?.let { it !== expected } == true) return false
        entryState = EncoderEntryState.Entered
        enteredOperation = operation
        return true
    }

    internal fun markCutoffInert(expected: EncoderTask): Boolean {
        if (!namesQueued(expected)) return false
        entryState = EncoderEntryState.CutoffInert
        return true
    }

    /** Adopt returned physical ownership before Control consumes or validates the semantic result. */
    internal fun closeAfterRealReturn(expected: EncoderTask, result: EncoderTaskResult) {
        check(entryState == EncoderEntryState.Entered && task === expected)
        checkResultMatchesTask(expected, result)
        if (result is EncoderTaskResult.BackendPrepared) {
            adoptReturnedHealthCell(expected as EncoderBackendPreparationTask, result.result)
        }
        when (result) {
            is EncoderTaskResult.RuntimeAllocated -> {
                check(runtime == null)
                val creation = result.result
                validateRuntimeAllocationIdentity(expected, creation)
                runtime = when (creation) {
                    is EncoderRuntimeCreation.Created -> creation.runtime
                    is EncoderRuntimeCreation.Failed -> creation.retainedRuntime
                }
            }

            is EncoderTaskResult.CarrierRetired -> if (result.result == EncoderRuntimeRetirement.Closed) {
                val retiring = (expected as EncoderCarrierRetirementTask).runtime
                check(runtime === retiring && retiring.isRetired())
                runtime = null
            }

            is EncoderTaskResult.UninstalledFrameworkRetired -> {
                val retirement = result.result
                if (retirement is FrameworkBitmapRetirement.Retained) {
                    check(uninstalledFrameworkResidue == null)
                    val retiring = expected as EncoderUninstalledFrameworkRetirementTask
                    uninstalledFrameworkResidue = EncoderUninstalledFrameworkResidue(
                        retiring.preparation,
                        retirement,
                    )
                }
            }

            else -> Unit
        }
        task = null
        enteredOperation = null
        entryState = EncoderEntryState.Closed
    }

    internal fun closeSkippedBeforeEntry(expected: EncoderTask) {
        check(entryState == EncoderEntryState.CutoffInert && task === expected)
        task = null
        enteredOperation = null
        entryState = EncoderEntryState.Closed
    }

    internal fun holdAfterPermitRelease(successor: EncoderTask) {
        check(entryState == EncoderEntryState.Closed && task == null && enteredOperation == null)
        check(pendingTaskAfterPermitRelease == null)
        when (successor) {
            is EncoderBackendPreparationTask -> {
                check(runtime == null)
                check(successor.existingHealthCell === nativeHealthCell)
            }

            is EncoderNativeAllocationTask -> {
                check(runtime == null)
                check(successor.preparation.nativeHealthCell === nativeHealthCell)
            }

            is EncoderManagedAllocationTask -> {
                check(runtime == null)
                check(successor.preparation.nativeHealthCell === nativeHealthCell)
            }

            is EncoderFrameworkPreparationTask -> checkRuntimeTask(successor.runtime)
            is EncoderRuntimeRetirementTask -> checkRuntimeTask(successor.runtime)
            is EncoderCarrierRetirementTask -> checkRuntimeTask(successor.runtime)
            is EncoderUninstalledFrameworkRetirementTask -> {
                checkRuntimeTask(successor.runtime)
                check(uninstalledFrameworkResidue == null)
            }

            is EncoderProductionTask -> checkRuntimeTask(successor.runtime)
        }
        pendingTaskAfterPermitRelease = successor
    }

    internal fun promoteAfterPermitRelease(): EncoderTask? {
        val successor = pendingTaskAfterPermitRelease ?: return null
        check(isIdleTaskSlotIgnoringPending())
        pendingTaskAfterPermitRelease = null
        task = successor
        entryState = EncoderEntryState.Queued
        return successor
    }

    internal fun detachRetiredRuntime(expected: EncoderRuntime): Boolean {
        if (runtime !== expected || !expected.isRetired() || !isIdleTaskSlot()) return false
        runtime = null
        return true
    }

    private fun checkResultMatchesTask(expected: EncoderTask, result: EncoderTaskResult) {
        check(
            when (expected) {
                is EncoderBackendPreparationTask -> result is EncoderTaskResult.BackendPrepared
                is EncoderNativeAllocationTask,
                is EncoderManagedAllocationTask,
                    -> result is EncoderTaskResult.RuntimeAllocated

                is EncoderFrameworkPreparationTask ->
                    result is EncoderTaskResult.FrameworkPrepared && result.result.runtime === expected.runtime

                is EncoderRuntimeRetirementTask -> result is EncoderTaskResult.FrameworkRetired
                is EncoderCarrierRetirementTask -> result is EncoderTaskResult.CarrierRetired
                is EncoderUninstalledFrameworkRetirementTask ->
                    result is EncoderTaskResult.UninstalledFrameworkRetired

                is EncoderProductionTask.Framework ->
                    result is EncoderTaskResult.FrameworkProduced &&
                            result.result.configRevision == expected.record.configRevision &&
                            result.result.productionId == expected.record.productionId

                is EncoderProductionTask.Native ->
                    result is EncoderTaskResult.NativeProduced && result.result.record === expected.record
            },
        )
    }

    private fun adoptReturnedHealthCell(
        task: EncoderBackendPreparationTask,
        preparation: EncoderBackendPreparation,
    ) {
        val returnedHealth = preparation.nativeHealthCell
        val existingHealth = nativeHealthCell
        check(task.existingHealthCell === existingHealth)
        if (existingHealth != null) {
            check(returnedHealth === existingHealth)
        } else if (returnedHealth != null) {
            nativeHealthCell = returnedHealth
        }
    }

    private fun validateRuntimeAllocationIdentity(task: EncoderTask, creation: EncoderRuntimeCreation) {
        val adoptedRuntime = when (creation) {
            is EncoderRuntimeCreation.Created -> creation.runtime
            is EncoderRuntimeCreation.Failed -> creation.retainedRuntime
        }
        adoptedRuntime?.let { check(it.nativeHealthCell === nativeHealthCell) }
        if (task is EncoderNativeAllocationTask) {
            if (adoptedRuntime == null) check(task.candidate.isRetired())
            else check(adoptedRuntime.carrier === task.candidate)
        }
    }

    private fun checkRuntimeTask(expectedRuntime: EncoderRuntime) {
        check(runtime === expectedRuntime)
        check(expectedRuntime.nativeHealthCell === nativeHealthCell)
    }

    private fun isIdleTaskSlot(): Boolean = isIdleTaskSlotIgnoringPending() && pendingTaskAfterPermitRelease == null

    private fun isIdleTaskSlotIgnoringPending(): Boolean = entryState == EncoderEntryState.Closed && task == null &&
            enteredOperation == null

    private fun checkIdleTaskSlot() {
        check(isIdleTaskSlot())
    }
}

internal sealed interface EncoderRetirement {
    data object Closed : EncoderRetirement

    class ReturnExpected internal constructor(
        internal val capsule: EncoderCapsule,
    ) : EncoderRetirement

    class ProcessLifetimeResidue internal constructor(
        internal val capsule: EncoderCapsule,
    ) : EncoderRetirement
}
