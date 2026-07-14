package io.screenstream.engine.internal

import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class TargetMode {
    Full,
    Downscaled,
}

internal class TargetPlan(
    internal val mode: TargetMode,
    internal val targetWidthPx: Int,
    internal val targetHeightPx: Int,
) {
    init {
        require(targetWidthPx > 0)
        require(targetHeightPx > 0)
    }
}

internal fun interface TargetSourceSignal {
    fun signal(targetGeneration: Long)
}

internal class TargetResources(
    internal val surfaceTexture: SurfaceTexture,
    internal val surface: Surface,
    internal val oesTextureName: Int,
) {
    init {
        require(oesTextureName != 0)
    }
}

internal object TargetSurfaceReleaseReceipt : OperationReceipt

internal class TargetSurfaceReleaseEvidence : OperationEvidence {
    override val receipt: TargetSurfaceReleaseReceipt = TargetSurfaceReleaseReceipt

    override val returnedOwner: OperationReturnedOwner? = null
}

internal class TargetSurfaceReleaseOwnerBag(
    internal val resources: TargetResources,
) : OperationOwnerBag

internal class TargetLease internal constructor(
    private val target: CurrentTarget,
) {
    private var released: Boolean = false

    internal val targetGeneration: Long
        get() = target.generation

    internal val surfaceTexture: SurfaceTexture
        get() = target.resources.surfaceTexture

    internal val oesTextureName: Int
        get() = target.resources.oesTextureName

    internal fun release(): Boolean {
        val releaseClaimed: Boolean = synchronized(this) {
            if (released) return@synchronized false
            released = true
            true
        }
        if (!releaseClaimed) return false

        target.releaseLeaseFromHandle()
        return true
    }
}

internal class CurrentTarget internal constructor(
    internal val plan: TargetPlan,
    internal val generation: Long,
    internal val resources: TargetResources,
    listenerInstallationOperationIdentity: Long,
    sourceSignal: TargetSourceSignal,
    clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    surfaceReleaseOperationIdentity: Long,
    surfaceReleaseDeadlineIdentity: Long,
    surfaceReleaseDeadlineWakeGeneration: Long,
    surfaceReleaseTimeoutCause: Throwable,
) {
    private val targetGate: ReentrantLock = ReentrantLock(false)
    private val latestPendingSource: AtomicBoolean = AtomicBoolean(false)
    private val expectedListenerInstallationOperationIdentity: Long = listenerInstallationOperationIdentity
    private var expectedListenerRemovalOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var expectedSetSurfaceDetachOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var expectedVirtualDisplayReleaseOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var armedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var observedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var appliedProducerDetachReceiptOperationIdentity: Long = NO_OPERATION_IDENTITY
    private var listenerInstalled: Boolean = false
    private var sourceSignalsAccepted: Boolean = false
    private var retirementAdmissionClosed: Boolean = false
    private var enteredTargetWorkDrained: Boolean = false
    private var generationFenced: Boolean = false
    private var listenerRemoved: Boolean = false
    private var surfaceTextureReleased: Boolean = false
    private var oesTextureDestroyed: Boolean = false
    private var leaseCount: Int = 0

    internal val frameAvailableListener: SurfaceTexture.OnFrameAvailableListener =
        SurfaceTexture.OnFrameAvailableListener {
            publishSourceAvailable(sourceSignal)
        }

    private val listenerSentinelRunnable: Runnable = Runnable {
        publishListenerSentinel()
    }

    internal val surfaceReleaseOccurrence: OperationOccurrence<TargetSurfaceReleaseEvidence> =
        OperationOccurrence(
            identity = surfaceReleaseOperationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(TargetSurfaceReleaseEvidence()),
            ownerBag = TargetSurfaceReleaseOwnerBag(resources),
            deadlineIdentity = surfaceReleaseDeadlineIdentity,
            deadlineDurationNanos = androidEnteredOperationSafetyNanos,
            initialWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
            timeoutCause = surfaceReleaseTimeoutCause,
            wakeSignal = settlementSignal,
        )

    init {
        require(generation > 0L)
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
    }

    internal val surface: Surface
        get() = resources.surface

    internal val surfaceTexture: SurfaceTexture
        get() = resources.surfaceTexture

    internal val oesTextureName: Int
        get() = resources.oesTextureName

    internal val hasPendingSource: Boolean
        get() = targetGate.withLock {
            !generationFenced && latestPendingSource.get()
        }

    internal val activeLeaseCount: Int
        get() = targetGate.withLock { leaseCount }

    internal val isProducerAttachmentPermitted: Boolean
        get() = targetGate.withLock {
            listenerInstalled && !generationFenced
        }

    internal val isSurfaceReleaseReady: Boolean
        get() = targetGate.withLock {
            retirementAdmissionClosed &&
                    enteredTargetWorkDrained &&
                    generationFenced &&
                    listenerRemoved &&
                    expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY &&
                    observedListenerSentinelOperationIdentity == expectedListenerRemovalOperationIdentity &&
                    appliedProducerDetachReceiptOperationIdentity != NO_OPERATION_IDENTITY &&
                    leaseCount == 0
        }

    internal val hasAppliedSurfaceReleaseReceipt: Boolean
        get() = surfaceReleaseOccurrence.settlementGate.withLock {
            surfaceReleaseOccurrence.returnCell.disposition == OperationReturnDisposition.Normal &&
                    surfaceReleaseOccurrence.returnCell.use != OperationReturnUse.Unclaimed
        }

    internal val isFullyRetired: Boolean
        get() = targetGate.withLock {
            surfaceTextureReleased && oesTextureDestroyed
        }

    internal fun recordListenerInstallationReceipt(operationIdentity: Long): Boolean = targetGate.withLock {
        if (operationIdentity != expectedListenerInstallationOperationIdentity || listenerInstalled || generationFenced) {
            return@withLock false
        }

        listenerInstalled = true
        sourceSignalsAccepted = true
        true
    }

    internal fun acquireLease(): TargetLease? = targetGate.withLock {
        if (retirementAdmissionClosed || generationFenced || leaseCount == Int.MAX_VALUE) {
            return@withLock null
        }

        val lease = TargetLease(this)
        leaseCount += 1
        lease
    }

    internal fun consumePendingSource(): Boolean = targetGate.withLock {
        if (retirementAdmissionClosed || generationFenced) return@withLock false
        latestPendingSource.getAndSet(false)
    }

    internal fun recordRetirementAdmissionClosed(): Boolean = targetGate.withLock {
        if (retirementAdmissionClosed) return@withLock false
        retirementAdmissionClosed = true
        true
    }

    internal fun recordEnteredTargetWorkDrained(): Boolean = targetGate.withLock {
        if (!retirementAdmissionClosed || enteredTargetWorkDrained) return@withLock false
        enteredTargetWorkDrained = true
        true
    }

    internal fun fenceGeneration(): Boolean = targetGate.withLock {
        if (!retirementAdmissionClosed || !enteredTargetWorkDrained || generationFenced) {
            return@withLock false
        }

        sourceSignalsAccepted = false
        generationFenced = true
        latestPendingSource.set(false)
        true
    }

    internal fun bindListenerRemovalOperationIdentity(operationIdentity: Long): Boolean = targetGate.withLock {
        require(operationIdentity > 0L)

        if (expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY) {
            return@withLock expectedListenerRemovalOperationIdentity == operationIdentity
        }
        if (operationIdentity == expectedSetSurfaceDetachOperationIdentity ||
            operationIdentity == expectedVirtualDisplayReleaseOperationIdentity
        ) {
            return@withLock false
        }

        expectedListenerRemovalOperationIdentity = operationIdentity
        true
    }

    internal fun bindSetSurfaceDetachOperationIdentity(operationIdentity: Long): Boolean = targetGate.withLock {
        require(operationIdentity > 0L)

        if (expectedSetSurfaceDetachOperationIdentity != NO_OPERATION_IDENTITY) {
            return@withLock expectedSetSurfaceDetachOperationIdentity == operationIdentity
        }
        if (operationIdentity == expectedListenerRemovalOperationIdentity ||
            operationIdentity == expectedVirtualDisplayReleaseOperationIdentity
        ) {
            return@withLock false
        }

        expectedSetSurfaceDetachOperationIdentity = operationIdentity
        true
    }

    internal fun bindVirtualDisplayReleaseOperationIdentity(operationIdentity: Long): Boolean = targetGate.withLock {
        require(operationIdentity > 0L)

        if (expectedVirtualDisplayReleaseOperationIdentity != NO_OPERATION_IDENTITY) {
            return@withLock expectedVirtualDisplayReleaseOperationIdentity == operationIdentity
        }
        if (operationIdentity == expectedListenerRemovalOperationIdentity ||
            operationIdentity == expectedSetSurfaceDetachOperationIdentity
        ) {
            return@withLock false
        }

        expectedVirtualDisplayReleaseOperationIdentity = operationIdentity
        true
    }

    internal fun armListenerSentinelAfterPublishedNormalRemoval(operationIdentity: Long): Runnable? =
        targetGate.withLock {
            if (!generationFenced ||
                expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY ||
                operationIdentity != expectedListenerRemovalOperationIdentity ||
                armedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY ||
                observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY
            ) {
                return@withLock null
            }

            armedListenerSentinelOperationIdentity = operationIdentity
            listenerSentinelRunnable
        }

    internal fun recordListenerRemovalReceipt(operationIdentity: Long): Boolean = targetGate.withLock {
        if (!generationFenced ||
            expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY ||
            operationIdentity != expectedListenerRemovalOperationIdentity ||
            listenerRemoved
        ) {
            return@withLock false
        }

        listenerRemoved = true
        true
    }

    internal fun recordSetSurfaceDetachReceipt(operationIdentity: Long): Boolean = targetGate.withLock {
        if (!generationFenced ||
            expectedSetSurfaceDetachOperationIdentity == NO_OPERATION_IDENTITY ||
            operationIdentity != expectedSetSurfaceDetachOperationIdentity ||
            appliedProducerDetachReceiptOperationIdentity != NO_OPERATION_IDENTITY
        ) {
            return@withLock false
        }

        appliedProducerDetachReceiptOperationIdentity = operationIdentity
        true
    }

    internal fun recordVirtualDisplayReleaseReceipt(operationIdentity: Long): Boolean = targetGate.withLock {
        if (!generationFenced ||
            expectedVirtualDisplayReleaseOperationIdentity == NO_OPERATION_IDENTITY ||
            operationIdentity != expectedVirtualDisplayReleaseOperationIdentity ||
            appliedProducerDetachReceiptOperationIdentity != NO_OPERATION_IDENTITY
        ) {
            return@withLock false
        }

        appliedProducerDetachReceiptOperationIdentity = operationIdentity
        true
    }

    internal fun beginSurfaceReleaseSubmission(): Boolean {
        if (!isSurfaceReleaseReady) return false
        return surfaceReleaseOccurrence.beginSubmission()
    }

    internal fun executeSurfaceRelease(): OperationEntryResult {
        val entryResult = surfaceReleaseOccurrence.tryEnter()
        if (entryResult == OperationEntryResult.NotCurrent) return entryResult

        settlementSignal.signal()
        if (entryResult != OperationEntryResult.Entered) return entryResult

        try {
            resources.surface.release()
            surfaceReleaseOccurrence.publishNormalReturn()
        } catch (thrown: Throwable) {
            surfaceReleaseOccurrence.publishThrownReturn(thrown)
        }

        settlementSignal.signal()
        return entryResult
    }

    internal fun recordSurfaceTextureReleaseReceipt(): Boolean {
        if (!hasAppliedSurfaceReleaseReceipt) return false

        return targetGate.withLock {
            if (surfaceTextureReleased) return@withLock false
            surfaceTextureReleased = true
            true
        }
    }

    internal fun recordOesTextureDestructionReceipt(): Boolean = targetGate.withLock {
        if (!surfaceTextureReleased || oesTextureDestroyed) return@withLock false
        oesTextureDestroyed = true
        true
    }

    private fun publishSourceAvailable(sourceSignal: TargetSourceSignal) {
        val published: Boolean = targetGate.withLock {
            if (!sourceSignalsAccepted || generationFenced) return@withLock false
            latestPendingSource.set(true)
            true
        }
        if (published) sourceSignal.signal(generation)
    }

    private fun publishListenerSentinel() {
        val published: Boolean = targetGate.withLock {
            val armedOperationIdentity = armedListenerSentinelOperationIdentity
            if (!generationFenced ||
                armedOperationIdentity == NO_OPERATION_IDENTITY ||
                armedOperationIdentity != expectedListenerRemovalOperationIdentity ||
                observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY
            ) {
                return@withLock false
            }

            armedListenerSentinelOperationIdentity = NO_OPERATION_IDENTITY
            observedListenerSentinelOperationIdentity = armedOperationIdentity
            true
        }
        if (published) settlementSignal.signal()
    }

    internal fun releaseLeaseFromHandle() {
        targetGate.withLock {
            check(leaseCount > 0)
            leaseCount -= 1
        }
        settlementSignal.signal()
    }

    private companion object {
        private const val NO_OPERATION_IDENTITY: Long = 0L
    }
}

internal class TargetOwner {
    internal var currentTarget: CurrentTarget? = null
        private set

    private var lastTargetGeneration: Long = 0L

    internal fun createCurrentTarget(
        plan: TargetPlan,
        resources: TargetResources,
        listenerInstallationOperationIdentity: Long,
        sourceSignal: TargetSourceSignal,
        clock: EngineClock,
        settlementSignal: SettlementSignal,
        surfaceReleaseOperationIdentity: Long,
        surfaceReleaseDeadlineIdentity: Long,
        surfaceReleaseDeadlineWakeGeneration: Long,
        surfaceReleaseTimeoutCause: Throwable,
    ): CurrentTarget? {
        check(currentTarget == null)
        if (lastTargetGeneration == Long.MAX_VALUE) return null

        val targetGeneration = lastTargetGeneration + 1L
        val target = CurrentTarget(
            plan = plan,
            generation = targetGeneration,
            resources = resources,
            listenerInstallationOperationIdentity = listenerInstallationOperationIdentity,
            sourceSignal = sourceSignal,
            clock = clock,
            settlementSignal = settlementSignal,
            surfaceReleaseOperationIdentity = surfaceReleaseOperationIdentity,
            surfaceReleaseDeadlineIdentity = surfaceReleaseDeadlineIdentity,
            surfaceReleaseDeadlineWakeGeneration = surfaceReleaseDeadlineWakeGeneration,
            surfaceReleaseTimeoutCause = surfaceReleaseTimeoutCause,
        )

        lastTargetGeneration = targetGeneration
        currentTarget = target
        return target
    }

    internal fun clearRetiredCurrentTarget(targetGeneration: Long): CurrentTarget? {
        val target = currentTarget ?: return null
        if (target.generation != targetGeneration || !target.isFullyRetired) return null

        currentTarget = null
        return target
    }

    internal fun hasPendingSource(targetGeneration: Long): Boolean {
        val target = currentTarget ?: return false
        return target.generation == targetGeneration && target.hasPendingSource
    }

    internal fun consumePendingSource(targetGeneration: Long): Boolean {
        val target = currentTarget ?: return false
        return target.generation == targetGeneration && target.consumePendingSource()
    }
}
