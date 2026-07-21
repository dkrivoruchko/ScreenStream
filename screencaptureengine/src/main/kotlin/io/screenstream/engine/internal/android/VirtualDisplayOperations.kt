package io.screenstream.engine.internal.android

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import io.screenstream.engine.internal.settlement.DeadlineArmResult
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence

internal const val initialCapturedResizeReadinessNanos: Long = 5_000_000_000L

internal data class AndroidVirtualDisplayLogicalTuple(
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val densityDpi: Int,
) {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
    }
}

internal object AndroidVirtualDisplayCreationReceipt : OperationReceipt

internal enum class AndroidVirtualDisplayReturnedOwnerDisposition {
    Empty,
    Rooted,
    Installed,
    Collision,
}

internal sealed interface AndroidVirtualDisplayOwnership {
    val virtualDisplay: VirtualDisplay
    val mechanicalState: AndroidVirtualDisplayMechanicalState

    val actualLogicalTuple: AndroidVirtualDisplayLogicalTuple
        get() = mechanicalState.actualLogicalTuple
}

internal class AndroidVirtualDisplayReturnedOwnerCell : OperationReturnedOwner {
    internal var virtualDisplay: VirtualDisplay? = null
        private set

    internal fun recordLocked(returnedVirtualDisplay: VirtualDisplay): Boolean {
        if (virtualDisplay != null) return false
        virtualDisplay = returnedVirtualDisplay
        return true
    }
}

internal class AndroidVirtualDisplayMechanicalState(
    private val returnedOwnerCell: AndroidVirtualDisplayReturnedOwnerCell,
) {
    @Volatile
    private var returnedLogicalTuple: AndroidVirtualDisplayLogicalTuple? = null

    internal val actualLogicalTuple: AndroidVirtualDisplayLogicalTuple
        get() = checkNotNull(returnedLogicalTuple)

    internal fun recordCreationReturnedLocked(actual: AndroidVirtualDisplayLogicalTuple): Boolean {
        if (returnedLogicalTuple != null || returnedOwnerCell.virtualDisplay == null) return false
        returnedLogicalTuple = actual
        return true
    }

    internal val virtualDisplay: VirtualDisplay
        get() = checkNotNull(returnedOwnerCell.virtualDisplay)
}

internal sealed interface AndroidVirtualDisplayProducerApplicationEvidence {
    val appliedProducerFact: TargetProducerEvidence?
}

internal class AndroidAttachedVirtualDisplay private constructor(
    override val mechanicalState: AndroidVirtualDisplayMechanicalState,
    internal val target: CurrentTarget,
    internal val producerPort: TargetPorts.AndroidSurfacePort,
    private val producerEvidence: AndroidVirtualDisplayProducerApplicationEvidence,
) : AndroidVirtualDisplayOwnership {
    private var exactProducerOperation: OperationOccurrence<*>? = null

    internal constructor(
        returnedOwnerCell: AndroidVirtualDisplayReturnedOwnerCell,
        target: CurrentTarget,
        producerPort: TargetPorts.AndroidSurfacePort,
        producerEvidence: AndroidVirtualDisplayProducerApplicationEvidence,
    ) : this(
        AndroidVirtualDisplayMechanicalState(returnedOwnerCell),
        target,
        producerPort,
        producerEvidence,
    )

    internal constructor(
        previousOwnership: AndroidMechanicallyDetachedVirtualDisplay,
        target: CurrentTarget,
        producerPort: TargetPorts.AndroidSurfacePort,
        producerEvidence: AndroidVirtualDisplayProducerApplicationEvidence,
    ) : this(previousOwnership.mechanicalState, target, producerPort, producerEvidence)

    override val virtualDisplay: VirtualDisplay
        get() = mechanicalState.virtualDisplay

    internal val producerOperation: OperationOccurrence<*>
        get() = checkNotNull(exactProducerOperation)

    internal val appliedTargetFact: TargetProducerEvidence
        get() = checkNotNull(producerEvidence.appliedProducerFact)

    internal val targetGeneration: Long
        get() = appliedTargetFact.targetGeneration

    internal fun bindProducerOperation(operation: OperationOccurrence<*>): Boolean {
        if (exactProducerOperation != null || operation.identity != producerPort.operationIdentity) return false
        exactProducerOperation = operation
        return true
    }
}

internal class AndroidMechanicallyDetachedVirtualDisplay(
    internal val attached: AndroidAttachedVirtualDisplay,
    internal val detachPort: TargetPorts.AndroidDetachPort,
    private val detachEvidence: AndroidVirtualDisplayDetachEvidence,
) : AndroidVirtualDisplayOwnership {
    private var exactDetachOperation: OperationOccurrence<*>? = null

    override val virtualDisplay: VirtualDisplay
        get() = attached.virtualDisplay

    override val mechanicalState: AndroidVirtualDisplayMechanicalState
        get() = attached.mechanicalState

    internal val detachOperation: OperationOccurrence<*>
        get() = checkNotNull(exactDetachOperation)

    internal val appliedTargetFact: TargetProducerDetachReceipt
        get() = checkNotNull(detachEvidence.appliedTargetFact)

    internal fun bindDetachOperation(operation: OperationOccurrence<*>): Boolean {
        if (exactDetachOperation != null || operation.identity != detachPort.operationIdentity) return false
        exactDetachOperation = operation
        return true
    }
}

internal class AndroidVirtualDisplayCreationEvidence(
    private val returnedOwnerCell: AndroidVirtualDisplayReturnedOwnerCell,
) : OperationEvidence, AndroidVirtualDisplayProducerApplicationEvidence {
    internal val returnedVirtualDisplay: VirtualDisplay?
        get() = returnedOwnerCell.virtualDisplay

    internal val virtualDisplay: VirtualDisplay?
        get() = returnedVirtualDisplay

    override val receipt: AndroidVirtualDisplayCreationReceipt = AndroidVirtualDisplayCreationReceipt
    override val returnedOwner: OperationReturnedOwner?
        get() = when (returnedOwnerDisposition) {
            AndroidVirtualDisplayReturnedOwnerDisposition.Rooted,
            AndroidVirtualDisplayReturnedOwnerDisposition.Collision,
                -> returnedOwnerCell

            AndroidVirtualDisplayReturnedOwnerDisposition.Empty,
            AndroidVirtualDisplayReturnedOwnerDisposition.Installed,
                -> null
        }

    internal var returnedOwnerDisposition: AndroidVirtualDisplayReturnedOwnerDisposition =
        AndroidVirtualDisplayReturnedOwnerDisposition.Empty
        private set

    internal var collisionExistingOwnership: AndroidVirtualDisplayOwnership? = null
        private set

    internal var collisionObserved: Boolean = false
        private set

    internal var directCreateOutOfMemoryError: OutOfMemoryError? = null
        private set

    internal var initialResizeDeadlineOccurrence: DeadlineOccurrence? = null
        private set

    internal var firstInitialResizeFact: AndroidCaptureFact.CapturedContentResized? = null
        private set

    internal var initialResizeArmResult: DeadlineArmResult? = null
        private set

    internal var appliedTargetFact: TargetProducerApplicationFact? = null
        private set

    override val appliedProducerFact: TargetProducerEvidence?
        get() = appliedTargetFact as? TargetProducerEvidence

    internal fun bindInitialResizeDeadlineLocked(
        deadlineOccurrence: DeadlineOccurrence,
    ): Boolean {
        if (initialResizeDeadlineOccurrence != null) return false
        initialResizeDeadlineOccurrence = deadlineOccurrence
        return true
    }

    internal fun recordInitialResizeLocked(fact: AndroidCaptureFact.CapturedContentResized): Boolean {
        if (initialResizeDeadlineOccurrence == null || firstInitialResizeFact != null || fact.widthPx <= 0 || fact.heightPx <= 0) {
            return false
        }
        firstInitialResizeFact = fact
        return true
    }

    internal fun rootReturnedVirtualDisplayLocked(virtualDisplay: VirtualDisplay): Boolean {
        if (returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
            !returnedOwnerCell.recordLocked(virtualDisplay)
        ) {
            return false
        }
        returnedOwnerDisposition = AndroidVirtualDisplayReturnedOwnerDisposition.Rooted
        return true
    }

    internal fun recordDirectCreateOutOfMemoryLocked(error: OutOfMemoryError): Boolean {
        if (directCreateOutOfMemoryError != null) return false
        directCreateOutOfMemoryError = error
        return true
    }

    internal fun armOrRetireInitialResizeLocked(sampleNanos: Long, returnedDisplayPresent: Boolean): DeadlineArmResult? {
        val deadline = initialResizeDeadlineOccurrence ?: return null
        return if (returnedDisplayPresent) {
            deadline.armLocked(sampleNanos).also { result -> initialResizeArmResult = result }
        } else {
            deadline.retireLocked()
            null
        }
    }

    internal fun retireInitialResizeLocked() {
        initialResizeDeadlineOccurrence?.retireLocked()
    }

    internal fun recordInstalledLocked(): Boolean {
        if (returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Rooted) return false
        returnedOwnerDisposition = AndroidVirtualDisplayReturnedOwnerDisposition.Installed
        return true
    }

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerApplicationFact): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }

    internal fun recordCollisionLocked(existingOwnership: AndroidVirtualDisplayOwnership?): Boolean {
        if (collisionObserved) return false
        collisionObserved = true
        collisionExistingOwnership = existingOwnership
        if (returnedOwnerDisposition == AndroidVirtualDisplayReturnedOwnerDisposition.Rooted) {
            returnedOwnerDisposition = AndroidVirtualDisplayReturnedOwnerDisposition.Collision
        }
        return true
    }

    internal fun isTimelyInitialResizeLocked(): Boolean {
        val fact = firstInitialResizeFact ?: return false
        val deadlineNanos = initialResizeDeadlineOccurrence?.deadlineNanos ?: return false
        return fact.sampleNanos < deadlineNanos
    }
}

internal class AndroidVirtualDisplayCreationOwnerBag(
    internal val projection: MediaProjection,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidSurfacePort,
    internal val initialLogicalTuple: AndroidVirtualDisplayLogicalTuple,
    internal val applicationCandidate: AndroidAttachedVirtualDisplay,
) : OperationOwnerBag {
    internal val widthPx: Int
        get() = initialLogicalTuple.widthPx

    internal val heightPx: Int
        get() = initialLogicalTuple.heightPx

    internal val densityDpi: Int
        get() = initialLogicalTuple.densityDpi
}

internal object AndroidVirtualDisplayAttachReceipt : OperationReceipt

internal class AndroidVirtualDisplayAttachEvidence : OperationEvidence, AndroidVirtualDisplayProducerApplicationEvidence {
    override val receipt: AndroidVirtualDisplayAttachReceipt = AndroidVirtualDisplayAttachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetFact: TargetProducerApplicationFact? = null
        private set

    override val appliedProducerFact: TargetProducerEvidence?
        get() = appliedTargetFact as? TargetProducerEvidence

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerApplicationFact): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }
}

internal object AndroidVirtualDisplayDetachReceipt : OperationReceipt

internal class AndroidVirtualDisplayDetachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayDetachReceipt = AndroidVirtualDisplayDetachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetFact: TargetProducerDetachReceipt? = null
        private set

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerDetachReceipt): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }
}

internal object AndroidVirtualDisplayReleaseReceipt : OperationReceipt

internal class AndroidVirtualDisplayReleaseEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayReleaseReceipt = AndroidVirtualDisplayReleaseReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetFact: TargetProducerDetachReceipt? = null
        private set

    internal var clearedOwnership: AndroidVirtualDisplayOwnership? = null
        private set

    internal var collisionExistingOwnership: AndroidVirtualDisplayOwnership? = null
        private set

    internal var collisionObserved: Boolean = false
        private set

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerDetachReceipt): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }

    internal fun recordClearedLocked(ownership: AndroidVirtualDisplayOwnership): Boolean {
        if (clearedOwnership != null) return false
        clearedOwnership = ownership
        return true
    }

    internal fun recordCollisionLocked(existingOwnership: AndroidVirtualDisplayOwnership?): Boolean {
        if (collisionObserved) return false
        collisionObserved = true
        collisionExistingOwnership = existingOwnership
        return true
    }
}

internal sealed interface AndroidVirtualDisplayReleaseMode {
    val ownership: AndroidVirtualDisplayOwnership

    class Attached(
        override val ownership: AndroidAttachedVirtualDisplay,
        internal val targetPort: TargetPorts.AndroidDetachPort,
    ) : AndroidVirtualDisplayReleaseMode

    class MechanicallyDetached(
        override val ownership: AndroidMechanicallyDetachedVirtualDisplay,
    ) : AndroidVirtualDisplayReleaseMode
}

internal class AndroidVirtualDisplayReleaseOwnerBag(
    internal val mode: AndroidVirtualDisplayReleaseMode,
) : OperationOwnerBag {
    internal val virtualDisplay: VirtualDisplay
        get() = mode.ownership.virtualDisplay
}
