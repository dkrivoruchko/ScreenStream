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
import io.screenstream.engine.internal.target.TargetAndroidPortBindingFact
import io.screenstream.engine.internal.target.TargetAndroidListenerInstallationBindingFact
import io.screenstream.engine.internal.target.TargetAndroidListenerRemovalBindingFact
import io.screenstream.engine.internal.target.TargetAndroidProducerBindingFact
import io.screenstream.engine.internal.target.TargetAndroidDetachBindingFact
import io.screenstream.engine.internal.target.TargetNoProducerReason
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetStagedDetachPortCommittedFact
import io.screenstream.engine.internal.target.TargetStagedDetachPortRetiredFact
import io.screenstream.engine.internal.target.TargetStagedDetachPortSettledFact
import io.screenstream.engine.internal.target.TargetStagedDetachPortUnusedFact
import io.screenstream.engine.internal.target.TargetStagedPortFact
import io.screenstream.engine.internal.target.TargetStagedPortPostExposedFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortCommittedFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortRetiredFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortSettledFact
import io.screenstream.engine.internal.target.TargetStagedProducerPortUnusedFact
import io.screenstream.engine.internal.target.TargetFrameQuiescedFact
import java.util.concurrent.atomic.AtomicReference

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

    internal fun recordResizeReturnedLocked(
        expected: AndroidVirtualDisplayLogicalTuple,
        actual: AndroidVirtualDisplayLogicalTuple,
    ): Boolean {
        if (returnedLogicalTuple != expected) return false
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

/** A returned attach throw may have changed the platform Surface; no producer fact is fabricated. */
internal class AndroidAttachmentUncertainVirtualDisplay(
    internal val prior: AndroidMechanicallyDetachedVirtualDisplay,
    internal val target: CurrentTarget,
    internal val producerPort: TargetPorts.AndroidSurfacePort,
    internal val settledEvidence: AndroidVirtualDisplayAttachEvidence,
) : AndroidVirtualDisplayOwnership {
    override val virtualDisplay: VirtualDisplay
        get() = prior.virtualDisplay

    override val mechanicalState: AndroidVirtualDisplayMechanicalState
        get() = prior.mechanicalState
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

    internal var selectedPlatformResult: AndroidTargetPlatformResult? = null
        private set

    internal var settledTargetResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.InitialProducerPortSettledOrAmbiguous? = null
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

    internal fun recordSelectedPlatformResultLocked(result: AndroidTargetPlatformResult): Boolean {
        if (selectedPlatformResult != null) return false
        selectedPlatformResult = result
        return true
    }

    internal fun recordSettledTargetResultLocked(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.InitialProducerPortSettledOrAmbiguous,
    ): Boolean {
        if (settledTargetResult != null || appliedTargetFact != null) return false
        settledTargetResult = result
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
    private var fixedBinding: AndroidTargetOperationBinding? = null
    private var fixedProducerResult: AndroidTargetPlatformResult.ProducerAttached? = null
    private var fixedReturnedWithoutProducerResult: AndroidTargetPlatformResult.ProducerUnavailable? = null
    private var fixedUnenteredResult: AndroidTargetPlatformResult.ProducerUnavailable? = null
    private var fixedInapplicableResult: AndroidTargetPlatformResult.ProducerUnavailable? = null
    private var fixedSettledResult: AndroidTargetPlatformResult.InitialProducerPortSettledOrAmbiguous? = null
    private var fixedOccurrenceNoEntryProof:
        AndroidOccurrenceNoPlatformEntryProof<AndroidVirtualDisplayCreationEvidence>? = null
    private val fixedPostTicket =
        AtomicReference<AndroidPostTicket<AndroidVirtualDisplayCreationEvidence>?>(null)

    internal val binding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedBinding)
    internal val producerResult: AndroidTargetPlatformResult.ProducerAttached
        get() = checkNotNull(fixedProducerResult)
    internal val returnedWithoutProducerResult: AndroidTargetPlatformResult.ProducerUnavailable
        get() = checkNotNull(fixedReturnedWithoutProducerResult)
    internal val unenteredResult: AndroidTargetPlatformResult.ProducerUnavailable
        get() = checkNotNull(fixedUnenteredResult)
    internal val inapplicableResult: AndroidTargetPlatformResult.ProducerUnavailable
        get() = checkNotNull(fixedInapplicableResult)
    internal val settledResult: AndroidTargetPlatformResult.InitialProducerPortSettledOrAmbiguous
        get() = checkNotNull(fixedSettledResult)
    internal val occurrenceNoEntryProof: AndroidOccurrenceNoPlatformEntryProof<AndroidVirtualDisplayCreationEvidence>
        get() = checkNotNull(fixedOccurrenceNoEntryProof)
    internal val postTicket: AndroidPostTicket<AndroidVirtualDisplayCreationEvidence>?
        get() = fixedPostTicket.get()

    internal fun bindPostTicket(ticket: AndroidPostTicket<AndroidVirtualDisplayCreationEvidence>): Boolean {
        if (ticket.occurrence !== occurrenceNoEntryProof.operation) return false
        return fixedPostTicket.compareAndSet(null, ticket)
    }

    internal fun bindOperation(operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>): Boolean {
        if (fixedBinding != null) return false
        val binding = AndroidTargetOperationBinding.create(port.bindingFact, operation)
        val occurrenceNoEntryProof = AndroidOccurrenceNoPlatformEntryProof(operation)
        fixedBinding = binding
        fixedProducerResult = AndroidTargetPlatformResult.ProducerAttached(binding)
        fixedReturnedWithoutProducerResult = AndroidTargetPlatformResult.ProducerUnavailable(
            binding,
            TargetNoProducerReason.ReturnedWithoutProducer,
        )
        fixedUnenteredResult = AndroidTargetPlatformResult.ProducerUnavailable(
            binding,
            TargetNoProducerReason.Unentered,
        )
        fixedInapplicableResult = AndroidTargetPlatformResult.ProducerUnavailable(
            binding,
            TargetNoProducerReason.Inapplicable,
        )
        fixedSettledResult = AndroidTargetPlatformResult.InitialProducerPortSettledOrAmbiguous(binding)
        fixedOccurrenceNoEntryProof = occurrenceNoEntryProof
        return true
    }

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

    internal var postOutcome: AndroidTargetPostOutcome? = null
        private set

    internal var consumedPostFact: TargetStagedPortFact? = null
        private set

    internal var settledTargetFact: TargetStagedProducerPortSettledFact? = null
        private set

    override val appliedProducerFact: TargetProducerEvidence?
        get() = appliedTargetFact as? TargetProducerEvidence

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerApplicationFact): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }

    internal fun recordSettledTargetFactLocked(fact: TargetStagedProducerPortSettledFact): Boolean {
        if (settledTargetFact != null || appliedTargetFact != null) return false
        settledTargetFact = fact
        return true
    }

    internal fun recordPostOutcomeLocked(outcome: AndroidTargetPostOutcome): Boolean {
        if (postOutcome != null) return false
        postOutcome = outcome
        return true
    }

    internal fun refinePostOutcomeToDefinitelyUnenteredLocked(
        outcome: AndroidTargetPostOutcome.DefinitelyUnentered,
    ): Boolean {
        if (postOutcome !is AndroidTargetPostOutcome.PostExposed) return false
        postOutcome = outcome
        return true
    }

    internal fun recordConsumedPostFactLocked(fact: TargetStagedPortFact): Boolean {
        val outcome = postOutcome ?: return false
        val existing = consumedPostFact
        val replacingPostExposed = outcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                existing is TargetStagedPortPostExposedFact
        val factMatchesOutcome = when (outcome) {
            is AndroidTargetPostOutcome.PostExposed -> fact is TargetStagedPortPostExposedFact
            is AndroidTargetPostOutcome.DefinitelyUnentered -> fact is TargetStagedProducerPortRetiredFact
            is AndroidTargetPostOutcome.RetiredUnused -> fact is TargetStagedProducerPortRetiredFact
        }
        if (!factMatchesOutcome || existing != null && !replacingPostExposed ||
            fact.operationIdentity != outcome.targetFact.operationIdentity ||
            fact.targetIdentity !== outcome.targetFact.targetIdentity ||
            fact.targetGeneration != outcome.targetFact.targetGeneration ||
            fact.retargetOccurrenceIdentity != outcome.targetFact.retargetOccurrenceIdentity ||
            fact.provenance !== outcome.targetFact.provenance
        ) {
            return false
        }
        consumedPostFact = fact
        return true
    }
}

internal object AndroidVirtualDisplayDetachReceipt : OperationReceipt

internal class AndroidVirtualDisplayDetachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayDetachReceipt = AndroidVirtualDisplayDetachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetFact: TargetProducerDetachReceipt? = null
        private set

    internal var postOutcome: AndroidTargetPostOutcome? = null
        private set

    internal var consumedPostFact: TargetStagedPortFact? = null
        private set

    internal var settledTargetFact: TargetStagedDetachPortSettledFact? = null
        private set

    internal fun recordAppliedTargetFactLocked(fact: TargetProducerDetachReceipt): Boolean {
        if (appliedTargetFact != null) return false
        appliedTargetFact = fact
        return true
    }

    internal fun recordSettledTargetFactLocked(fact: TargetStagedDetachPortSettledFact): Boolean {
        if (settledTargetFact != null || appliedTargetFact != null) return false
        settledTargetFact = fact
        return true
    }

    internal fun recordPostOutcomeLocked(outcome: AndroidTargetPostOutcome): Boolean {
        if (postOutcome != null) return false
        postOutcome = outcome
        return true
    }

    internal fun refinePostOutcomeToDefinitelyUnenteredLocked(
        outcome: AndroidTargetPostOutcome.DefinitelyUnentered,
    ): Boolean {
        if (postOutcome !is AndroidTargetPostOutcome.PostExposed) return false
        postOutcome = outcome
        return true
    }

    internal fun recordConsumedPostFactLocked(fact: TargetStagedPortFact): Boolean {
        val outcome = postOutcome ?: return false
        val existing = consumedPostFact
        val replacingPostExposed = outcome is AndroidTargetPostOutcome.DefinitelyUnentered &&
                existing is TargetStagedPortPostExposedFact
        val factMatchesOutcome = when (outcome) {
            is AndroidTargetPostOutcome.PostExposed -> fact is TargetStagedPortPostExposedFact
            is AndroidTargetPostOutcome.DefinitelyUnentered -> fact is TargetStagedDetachPortRetiredFact
            is AndroidTargetPostOutcome.RetiredUnused -> fact is TargetStagedDetachPortUnusedFact
        }
        if (!factMatchesOutcome || existing != null && !replacingPostExposed ||
            fact.operationIdentity != outcome.targetFact.operationIdentity ||
            fact.targetIdentity !== outcome.targetFact.targetIdentity ||
            fact.targetGeneration != outcome.targetFact.targetGeneration ||
            fact.retargetOccurrenceIdentity != outcome.targetFact.retargetOccurrenceIdentity ||
            fact.provenance !== outcome.targetFact.provenance
        ) {
            return false
        }
        consumedPostFact = fact
        return true
    }
}

internal object AndroidVirtualDisplayResizeReceipt : OperationReceipt

internal class AndroidVirtualDisplayResizeEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayResizeReceipt = AndroidVirtualDisplayResizeReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTuple: AndroidVirtualDisplayLogicalTuple? = null
        private set

    internal var selectedResult: AndroidVirtualDisplayResizeResult? = null
        private set

    internal var consumedResult: AndroidVirtualDisplayResizeResult.Applied? = null
        private set

    internal fun recordAppliedTupleLocked(tuple: AndroidVirtualDisplayLogicalTuple): Boolean {
        if (appliedTuple != null) return false
        appliedTuple = tuple
        return true
    }

    internal fun recordSelectedResultLocked(result: AndroidVirtualDisplayResizeResult): Boolean {
        if (selectedResult != null) return false
        selectedResult = result
        return true
    }

    internal fun recordConsumedResultLocked(result: AndroidVirtualDisplayResizeResult.Applied): Boolean {
        if (selectedResult !== result || consumedResult != null || result.actualTuple != appliedTuple) return false
        consumedResult = result
        return true
    }
}

internal class AndroidVirtualDisplayResizeOwnerBag(
    internal val ownership: AndroidVirtualDisplayOwnership,
    internal val command: AndroidVirtualDisplayResizeCommand,
) : OperationOwnerBag {
    private var fixedAppliedResult: AndroidVirtualDisplayResizeResult.Applied? = null
    private var fixedFailedResult: AndroidVirtualDisplayResizeResult.Failed? = null
    private var fixedTerminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Resize? = null

    internal val appliedResult: AndroidVirtualDisplayResizeResult.Applied
        get() = checkNotNull(fixedAppliedResult)
    internal val failedResult: AndroidVirtualDisplayResizeResult.Failed
        get() = checkNotNull(fixedFailedResult)
    internal val terminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Resize
        get() = checkNotNull(fixedTerminalTransfer)

    internal fun bindOperation(operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>): Boolean {
        if (fixedAppliedResult != null) return false
        fixedAppliedResult = AndroidVirtualDisplayResizeResult.Applied(
            operation.identity,
            command.reconfigurationIdentity,
            command.requestedTuple,
        )
        fixedFailedResult = AndroidVirtualDisplayResizeResult.Failed(
            operation.identity,
            command.reconfigurationIdentity,
        )
        fixedTerminalTransfer = AndroidVirtualDisplayMutationTerminalTransfer.Resize(operation)
        return true
    }
}

internal sealed interface AndroidVirtualDisplayResizeResult {
    val operationIdentity: Long
    val reconfigurationIdentity: Long

    class Applied(
        override val operationIdentity: Long,
        override val reconfigurationIdentity: Long,
        internal val actualTuple: AndroidVirtualDisplayLogicalTuple,
    ) : AndroidVirtualDisplayResizeResult

    class Failed(
        override val operationIdentity: Long,
        override val reconfigurationIdentity: Long,
    ) : AndroidVirtualDisplayResizeResult
}

internal sealed class AndroidVirtualDisplayMutationTerminalTransfer(
    internal val operation: OperationOccurrence<*>,
) {
    class Resize(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
    ) : AndroidVirtualDisplayMutationTerminalTransfer(operation)

    class Detach(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ) : AndroidVirtualDisplayMutationTerminalTransfer(operation)

    class Attach(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ) : AndroidVirtualDisplayMutationTerminalTransfer(operation)
}

internal class AndroidVirtualDisplayResizeCommand(
    internal val target: CurrentTarget,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val reconfigurationIdentity: Long,
    internal val requestedTuple: AndroidVirtualDisplayLogicalTuple,
) {
    init {
        require(reconfigurationIdentity > 0L)
        require(quiescedFact.targetIdentity.matches(target))
        require(quiescedFact.originRetainedReconfigurationIdentity == reconfigurationIdentity)
    }
}

internal sealed interface AndroidTargetPostOutcome {
    val binding: AndroidTargetOperationBinding
    val targetFact: TargetStagedPortFact
        get() = binding.targetFact as TargetStagedPortFact

    class RetiredUnused(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPostOutcome {
        init {
            require(
                targetFact is TargetStagedProducerPortUnusedFact ||
                        targetFact is TargetStagedDetachPortUnusedFact,
            )
        }
    }

    class DefinitelyUnentered(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPostOutcome {
        init {
            require(
                targetFact is TargetStagedProducerPortCommittedFact ||
                        targetFact is TargetStagedDetachPortCommittedFact,
            )
        }
    }

    class PostExposed(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPostOutcome {
        init {
            require(
                targetFact is TargetStagedProducerPortCommittedFact ||
                        targetFact is TargetStagedDetachPortCommittedFact,
            )
        }
    }
}

/** Android-owned opaque root retained by Target without exposing occurrence or Handler-ticket internals. */
internal class AndroidTargetOperationBinding private constructor(
    internal val targetFact: TargetAndroidPortBindingFact,
    private val occurrence: OperationOccurrence<*>,
) {
    internal val targetIdentity = targetFact.targetIdentity
    internal val operationIdentity = targetFact.operationIdentity
    internal val reconfigurationIdentity = (targetFact as? TargetStagedPortFact)?.retargetOccurrenceIdentity
    internal val provenance = targetFact.provenance

    internal companion object {
        internal fun create(
            targetFact: TargetAndroidPortBindingFact,
            occurrence: OperationOccurrence<*>,
        ): AndroidTargetOperationBinding {
            require(occurrence.identity == targetFact.operationIdentity)
            return AndroidTargetOperationBinding(targetFact, occurrence)
        }
    }
}

internal sealed interface AndroidTargetPlatformResult {
    val binding: AndroidTargetOperationBinding
    val targetFact: TargetAndroidPortBindingFact
        get() = binding.targetFact

    class ProducerAttached(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(
                targetFact is TargetStagedProducerPortCommittedFact ||
                        targetFact is TargetAndroidProducerBindingFact,
            )
        }
    }

    class ProducerUnavailable(
        override val binding: AndroidTargetOperationBinding,
        internal val reason: TargetNoProducerReason,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidProducerBindingFact)
        }
    }

    class ProducerPortSettled(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetStagedProducerPortCommittedFact)
        }
    }

    class InitialProducerPortSettledOrAmbiguous(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidProducerBindingFact)
        }
    }

    class ProducerDetached(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(
                targetFact is TargetStagedDetachPortCommittedFact ||
                        targetFact is TargetAndroidDetachBindingFact,
            )
        }
    }

    class DetachPortSettled(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetStagedDetachPortCommittedFact)
        }
    }

    class ListenerInstalled(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidListenerInstallationBindingFact)
        }
    }

    class ListenerRemovalReturned(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidListenerRemovalBindingFact)
        }
    }

    class ListenerRemovalSettled(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidListenerRemovalBindingFact)
        }
    }

    class ListenerSentinelObserved(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidTargetPlatformResult {
        init {
            require(targetFact is TargetAndroidListenerRemovalBindingFact)
        }
    }
}

internal class AndroidVirtualDisplayDetachOwnerBag(
    internal val ownership: AndroidAttachedVirtualDisplay,
    internal val target: CurrentTarget,
    internal val stagedPort: TargetPorts.StagedAndroidDetachPort,
    private val evidence: AndroidVirtualDisplayDetachEvidence,
) : OperationOwnerBag {
    private var fixedPostTicket: AndroidPostTicket<AndroidVirtualDisplayDetachEvidence>? = null
    private var fixedBinding: AndroidTargetOperationBinding? = null
    private var fixedRetiredUnusedOutcome: AndroidTargetPostOutcome.RetiredUnused? = null
    private var fixedDefinitelyUnenteredOutcome: AndroidTargetPostOutcome.DefinitelyUnentered? = null
    private var fixedPostExposedOutcome: AndroidTargetPostOutcome.PostExposed? = null
    private var fixedPlatformResult: AndroidTargetPlatformResult.ProducerDetached? = null
    private var fixedSettledResult: AndroidTargetPlatformResult.DetachPortSettled? = null
    private var fixedDetachedCandidate: AndroidMechanicallyDetachedVirtualDisplay? = null
    private var fixedTerminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Detach? = null

    internal val committedFact: TargetStagedDetachPortCommittedFact
        get() = stagedPort.commitCorrelation

    internal val postTicket: AndroidPostTicket<AndroidVirtualDisplayDetachEvidence>?
        get() = fixedPostTicket

    internal fun bindPostTicket(ticket: AndroidPostTicket<AndroidVirtualDisplayDetachEvidence>): Boolean {
        if (fixedPostTicket != null) return false
        fixedPostTicket = ticket
        return true
    }

    internal val binding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedBinding)

    internal val retiredUnusedOutcome: AndroidTargetPostOutcome.RetiredUnused
        get() = checkNotNull(fixedRetiredUnusedOutcome)
    internal val definitelyUnenteredOutcome: AndroidTargetPostOutcome.DefinitelyUnentered
        get() = checkNotNull(fixedDefinitelyUnenteredOutcome)
    internal val postExposedOutcome: AndroidTargetPostOutcome.PostExposed
        get() = checkNotNull(fixedPostExposedOutcome)
    internal val platformResult: AndroidTargetPlatformResult.ProducerDetached
        get() = checkNotNull(fixedPlatformResult)
    internal val settledResult: AndroidTargetPlatformResult.DetachPortSettled
        get() = checkNotNull(fixedSettledResult)
    internal val detachedCandidate: AndroidMechanicallyDetachedVirtualDisplay
        get() = checkNotNull(fixedDetachedCandidate)
    internal val terminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Detach
        get() = checkNotNull(fixedTerminalTransfer)

    internal fun bindOperation(
        binding: AndroidTargetOperationBinding,
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): Boolean {
        if (fixedBinding != null || binding.targetFact !== committedFact) return false
        val detachedCandidate = AndroidMechanicallyDetachedVirtualDisplay(ownership, stagedPort.port, evidence)
        check(detachedCandidate.bindDetachOperation(operation))
        fixedBinding = binding
        fixedRetiredUnusedOutcome = AndroidTargetPostOutcome.RetiredUnused(
            AndroidTargetOperationBinding.create(stagedPort.unusedCorrelation, operation),
        )
        fixedDefinitelyUnenteredOutcome = AndroidTargetPostOutcome.DefinitelyUnentered(binding)
        fixedPostExposedOutcome = AndroidTargetPostOutcome.PostExposed(binding)
        fixedPlatformResult = AndroidTargetPlatformResult.ProducerDetached(binding)
        fixedSettledResult = AndroidTargetPlatformResult.DetachPortSettled(binding)
        fixedDetachedCandidate = detachedCandidate
        fixedTerminalTransfer = AndroidVirtualDisplayMutationTerminalTransfer.Detach(operation)
        return true
    }
}

internal class AndroidVirtualDisplayAttachOwnerBag(
    internal val priorOwnership: AndroidMechanicallyDetachedVirtualDisplay,
    internal val target: CurrentTarget,
    internal val stagedPort: TargetPorts.StagedAndroidSurfacePort,
    internal val applicationCandidate: AndroidAttachedVirtualDisplay,
    private val evidence: AndroidVirtualDisplayAttachEvidence,
) : OperationOwnerBag {
    private var fixedPostTicket: AndroidPostTicket<AndroidVirtualDisplayAttachEvidence>? = null
    private var fixedBinding: AndroidTargetOperationBinding? = null
    private var fixedRetiredUnusedOutcome: AndroidTargetPostOutcome.RetiredUnused? = null
    private var fixedDefinitelyUnenteredOutcome: AndroidTargetPostOutcome.DefinitelyUnentered? = null
    private var fixedPostExposedOutcome: AndroidTargetPostOutcome.PostExposed? = null
    private var fixedPlatformResult: AndroidTargetPlatformResult.ProducerAttached? = null
    private var fixedSettledResult: AndroidTargetPlatformResult.ProducerPortSettled? = null
    private var fixedUncertainCandidate: AndroidAttachmentUncertainVirtualDisplay? = null
    private var fixedTerminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Attach? = null

    internal val committedFact: TargetStagedProducerPortCommittedFact
        get() = stagedPort.commitCorrelation

    internal val postTicket: AndroidPostTicket<AndroidVirtualDisplayAttachEvidence>?
        get() = fixedPostTicket

    internal fun bindPostTicket(ticket: AndroidPostTicket<AndroidVirtualDisplayAttachEvidence>): Boolean {
        if (fixedPostTicket != null) return false
        fixedPostTicket = ticket
        return true
    }

    internal val binding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedBinding)

    internal val retiredUnusedOutcome: AndroidTargetPostOutcome.RetiredUnused
        get() = checkNotNull(fixedRetiredUnusedOutcome)
    internal val definitelyUnenteredOutcome: AndroidTargetPostOutcome.DefinitelyUnentered
        get() = checkNotNull(fixedDefinitelyUnenteredOutcome)
    internal val postExposedOutcome: AndroidTargetPostOutcome.PostExposed
        get() = checkNotNull(fixedPostExposedOutcome)
    internal val platformResult: AndroidTargetPlatformResult.ProducerAttached
        get() = checkNotNull(fixedPlatformResult)
    internal val settledResult: AndroidTargetPlatformResult.ProducerPortSettled
        get() = checkNotNull(fixedSettledResult)
    internal val uncertainCandidate: AndroidAttachmentUncertainVirtualDisplay
        get() = checkNotNull(fixedUncertainCandidate)
    internal val terminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Attach
        get() = checkNotNull(fixedTerminalTransfer)

    internal fun bindOperation(
        binding: AndroidTargetOperationBinding,
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): Boolean {
        if (fixedBinding != null || binding.targetFact !== committedFact) return false
        fixedBinding = binding
        fixedRetiredUnusedOutcome = AndroidTargetPostOutcome.RetiredUnused(
            AndroidTargetOperationBinding.create(stagedPort.unusedCorrelation, operation),
        )
        fixedDefinitelyUnenteredOutcome = AndroidTargetPostOutcome.DefinitelyUnentered(binding)
        fixedPostExposedOutcome = AndroidTargetPostOutcome.PostExposed(binding)
        fixedPlatformResult = AndroidTargetPlatformResult.ProducerAttached(binding)
        fixedSettledResult = AndroidTargetPlatformResult.ProducerPortSettled(binding)
        fixedUncertainCandidate = AndroidAttachmentUncertainVirtualDisplay(
            priorOwnership,
            target,
            stagedPort.port,
            evidence,
        )
        fixedTerminalTransfer = AndroidVirtualDisplayMutationTerminalTransfer.Attach(operation)
        return true
    }
}

internal sealed interface AndroidVirtualDisplayDetachPreparationResult {
    class Ready(
        internal val operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ) : AndroidVirtualDisplayDetachPreparationResult

    class RetiredUnused(
        internal val operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
        internal val outcome: AndroidTargetPostOutcome.RetiredUnused,
        internal val terminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Detach,
    ) : AndroidVirtualDisplayDetachPreparationResult
}

internal sealed interface AndroidVirtualDisplayAttachPreparationResult {
    class Ready(
        internal val operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ) : AndroidVirtualDisplayAttachPreparationResult

    class RetiredUnused(
        internal val operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        internal val outcome: AndroidTargetPostOutcome.RetiredUnused,
        internal val terminalTransfer: AndroidVirtualDisplayMutationTerminalTransfer.Attach,
    ) : AndroidVirtualDisplayAttachPreparationResult
}

internal object AndroidVirtualDisplayReleaseReceipt : OperationReceipt

internal class AndroidVirtualDisplayReleaseEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayReleaseReceipt = AndroidVirtualDisplayReleaseReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetFact: TargetProducerDetachReceipt? = null
        private set

    internal var selectedPlatformResult: AndroidTargetPlatformResult.ProducerDetached? = null
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

    internal fun recordSelectedPlatformResultLocked(
        result: AndroidTargetPlatformResult.ProducerDetached,
    ): Boolean {
        if (selectedPlatformResult != null) return false
        selectedPlatformResult = result
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

    class AttachmentUncertain(
        override val ownership: AndroidAttachmentUncertainVirtualDisplay,
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

    private var fixedBinding: AndroidTargetOperationBinding? = null
    private var fixedResult: AndroidTargetPlatformResult.ProducerDetached? = null

    internal val binding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedBinding)
    internal val result: AndroidTargetPlatformResult.ProducerDetached
        get() = checkNotNull(fixedResult)

    internal fun bindOperation(operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>): Boolean {
        val port = when (val exactMode = mode) {
            is AndroidVirtualDisplayReleaseMode.Attached -> exactMode.targetPort
            is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> exactMode.targetPort
            is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> return false
        }
        if (fixedBinding != null) return false
        val binding = AndroidTargetOperationBinding.create(port.bindingFact, operation)
        fixedBinding = binding
        fixedResult = AndroidTargetPlatformResult.ProducerDetached(binding)
        return true
    }
}
