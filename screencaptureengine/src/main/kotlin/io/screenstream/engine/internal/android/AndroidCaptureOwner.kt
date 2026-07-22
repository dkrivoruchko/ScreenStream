package io.screenstream.engine.internal.android

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import io.screenstream.engine.internal.AndroidLaneQuitAction
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetNoProducerEvidence
import io.screenstream.engine.internal.target.TargetListenerInstallationBindingCommittedFact
import io.screenstream.engine.internal.target.TargetListenerInstallationRequestClaim
import io.screenstream.engine.internal.target.TargetPortUseOutcome
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetProducerOperationKind
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private data class AndroidCapturedContentSize(
    val widthPx: Int,
    val heightPx: Int,
)

private class AndroidProjectionCallbackCurrentness(
    val owner: AndroidCaptureOwner,
    val projection: MediaProjection,
    val registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    val callback: MediaProjection.Callback,
    val provenance: AndroidCallbackProvenance,
)

private class AndroidProjectionCallbackOccurrence(
    val currentness: AndroidProjectionCallbackCurrentness,
    val sequence: Long,
)

private class AndroidProjectionCallbackClaim(
    internal val admissionCutoff: AndroidWorkAdmissionCutoff,
)

private class AndroidProjectionCallbackRoot(
    internal val claim: AndroidProjectionCallbackClaim,
    internal val operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    internal val provenance: AndroidCallbackProvenance,
    internal val ownerCutoffProof: AndroidOwnerPostCutoffProof<AndroidProjectionCallbackRegistrationEvidence>,
)

private sealed interface AndroidProjectionCallbackSlot {
    object OpenEmpty : AndroidProjectionCallbackSlot
    class Preparing(internal val claim: AndroidProjectionCallbackClaim) : AndroidProjectionCallbackSlot
    class OpenOwned(internal val root: AndroidProjectionCallbackRoot) : AndroidProjectionCallbackSlot
    object ClosedEmpty : AndroidProjectionCallbackSlot
    class ClosedPreparing(internal val claim: AndroidProjectionCallbackClaim) : AndroidProjectionCallbackSlot
    class ClosedOwned(internal val root: AndroidProjectionCallbackRoot) : AndroidProjectionCallbackSlot
}

private class AndroidVirtualDisplayCreationClaim(
    internal val target: CurrentTarget,
    internal val operationIdentity: Long,
    internal val admissionCutoff: AndroidWorkAdmissionCutoff,
) {
    private sealed interface ReturnedPreparation {
        class Port(internal val value: TargetPorts.AndroidSurfacePort) : ReturnedPreparation
        class Retired(
            internal val value: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact,
        ) : ReturnedPreparation
    }

    private val returnedPreparation = AtomicReference<ReturnedPreparation?>(null)

    internal fun root(port: TargetPorts.AndroidSurfacePort): Boolean =
        returnedPreparation.compareAndSet(null, ReturnedPreparation.Port(port))

    internal fun root(
        fact: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact,
    ): Boolean = returnedPreparation.compareAndSet(null, ReturnedPreparation.Retired(fact))

    internal fun owns(port: TargetPorts.AndroidSurfacePort): Boolean =
        (returnedPreparation.get() as? ReturnedPreparation.Port)?.value === port

    internal fun owns(
        fact: io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact,
    ): Boolean = (returnedPreparation.get() as? ReturnedPreparation.Retired)?.value === fact
}

private class AndroidVirtualDisplayCreationRoot(
    internal val claim: AndroidVirtualDisplayCreationClaim,
    internal val operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    internal val ownerCutoffProof: AndroidOwnerPostCutoffProof<AndroidVirtualDisplayCreationEvidence>,
)

private sealed interface AndroidVirtualDisplayCreationSlot {
    object OpenEmpty : AndroidVirtualDisplayCreationSlot
    class Preparing(internal val claim: AndroidVirtualDisplayCreationClaim) : AndroidVirtualDisplayCreationSlot
    class CommitInFlight(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
    class Owned(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
    class RetiredUnused(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
    object ClosedEmpty : AndroidVirtualDisplayCreationSlot
    class ClosedPreparing(internal val claim: AndroidVirtualDisplayCreationClaim) : AndroidVirtualDisplayCreationSlot
    class ClosedCommitInFlight(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
    class ClosedOwned(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
    class ClosedRetiredUnused(internal val root: AndroidVirtualDisplayCreationRoot) : AndroidVirtualDisplayCreationSlot
}

private class AndroidVirtualDisplayReleaseClaim(
    internal val operationIdentity: Long,
    internal val ownership: AndroidVirtualDisplayOwnership,
) {
    private val returnedTargetCandidate = AtomicReference<TargetPorts.DetachedInitialReleasePort?>(null)

    internal fun root(candidate: TargetPorts.DetachedInitialReleasePort): Boolean =
        returnedTargetCandidate.compareAndSet(null, candidate)

    internal fun owns(candidate: TargetPorts.DetachedInitialReleasePort): Boolean =
        returnedTargetCandidate.get() === candidate

    internal val hasReturnedCandidate: Boolean
        get() = returnedTargetCandidate.get() != null
}

private class AndroidVirtualDisplayReleaseRoot(
    internal val claim: AndroidVirtualDisplayReleaseClaim,
    internal val operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
)

private sealed interface AndroidVirtualDisplayReleaseSlot {
    object Empty : AndroidVirtualDisplayReleaseSlot
    class Preparing(internal val claim: AndroidVirtualDisplayReleaseClaim) : AndroidVirtualDisplayReleaseSlot
    class Owned(internal val root: AndroidVirtualDisplayReleaseRoot) : AndroidVirtualDisplayReleaseSlot
    class RetiredUnused(internal val root: AndroidVirtualDisplayReleaseRoot) : AndroidVirtualDisplayReleaseSlot
}

internal class AndroidCaptureOwner(
    private val projectionStopObligation: AndroidProjectionStopObligation,
    internal val projectionConstructionClaim: AndroidProjectionStopOwnerConstructionClaim,
    private val projection: MediaProjection,
    private val projectionOwnerEpoch: Long,
    private val callbackIdentity: Long,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    private val factSink: AndroidCaptureFactSink,
) {
    internal val cleanupQuitAction: AndroidLaneQuitAction = AndroidLaneQuitAction(this)
    internal val apiBand: AndroidCaptureApiBand = when (Build.VERSION.SDK_INT) {
        in Build.VERSION_CODES.N..Build.VERSION_CODES.S -> AndroidCaptureApiBand.Api24To31
        in Build.VERSION_CODES.S_V2..Build.VERSION_CODES.TIRAMISU -> AndroidCaptureApiBand.Api32To33
        in Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Build.VERSION_CODES.CINNAMON_BUN -> AndroidCaptureApiBand.Api34To37
        else -> AndroidCaptureApiBand.Unsupported
    }

    private val laneRuntime = AndroidLaneRuntime(settlementSignal)
    internal val projectionBindingLane: AndroidLaneRuntime
        get() = laneRuntime
    private val callbackSlot = AtomicReference<AndroidProjectionCallbackSlot>(AndroidProjectionCallbackSlot.OpenEmpty)
    private val callbackSequence = AtomicLong(0L)
    private val lastCapturedContentSize = AtomicReference<AndroidCapturedContentSize?>(null)
    private val callbackRegistrationNoPlatformEntryProof =
        AtomicReference<AndroidNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    private val callbackCleanupOutcome =
        AtomicReference<AndroidProjectionCallbackCleanupOutcome?>(null)
    private val callbackUnregistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>?>(null)
    private val virtualDisplayCreationSlot =
        AtomicReference<AndroidVirtualDisplayCreationSlot>(AndroidVirtualDisplayCreationSlot.OpenEmpty)
    private val virtualDisplayCreationNoPlatformEntryProof =
        AtomicReference<AndroidNoPlatformEntryProof<AndroidVirtualDisplayCreationEvidence>?>(null)
    private val targetListenerInstallationClaim =
        AtomicReference<TargetListenerInstallationRequestClaim?>(null)
    private val targetListenerInstallationBoundRoot =
        AtomicReference<AndroidTargetListenerInstallationBoundRoot?>(null)
    private val targetListenerInstallationUnboundClaimRetiredProof =
        AtomicReference<AndroidTargetListenerInstallationUnboundClaimRetiredProof?>(null)
    private val targetListenerInstallationFinalLaneNoEntryProof =
        AtomicReference<AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>?>(null)
    private val targetListenerRemovalRoot = AtomicReference<AndroidTargetListenerRemovalRoot?>(null)
    private val targetListenerInstallationAdmissionGate = ReentrantLock()
    private val targetListenerInstallationAdmissionCutoff = AndroidTargetListenerInstallationAdmissionCutoff()
    private var targetListenerInstallationAdmissionOpen: Boolean = true
    private var targetListenerInstallationInFlight: Int = 0
    private val virtualDisplayOwner = AtomicReference<AndroidVirtualDisplayOwnership?>(null)
    private val virtualDisplayReleaseSlot =
        AtomicReference<AndroidVirtualDisplayReleaseSlot>(AndroidVirtualDisplayReleaseSlot.Empty)
    internal val virtualDisplayMutations = AndroidVirtualDisplayMutator(
        ownership = virtualDisplayOwner,
        lane = laneRuntime,
        clock = clock,
        settlementSignal = settlementSignal,
    )
    private val projectionStopOperation = AtomicReference<OperationOccurrence<AndroidProjectionStopEvidence>?>(null)

    init {
        require(
            projectionStopObligation.acceptsOwnerConstructionProjection(
                projectionConstructionClaim,
                projection,
            ),
        )
    }

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val currentness = authoritativeApi34To37CallbackCurrentness() ?: return
            val size = AndroidCapturedContentSize(width, height)
            while (true) {
                val previous = lastCapturedContentSize.get()
                if (previous == size) return
                if (lastCapturedContentSize.compareAndSet(previous, size)) break
            }
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            val fact = AndroidCaptureFact.CapturedContentResized(
                provenance = callbackOccurrence.currentness.provenance,
                callbackSequence = callbackOccurrence.sequence,
                sampleNanos = clock.nowNanos(),
                widthPx = width,
                heightPx = height,
            )
            val operation = currentVirtualDisplayCreationOperation()
            val recorded = operation?.settlementGate?.withLock {
                currentVirtualDisplayCreationOperation() === operation &&
                    operation.returnCell.evidence.recordInitialResizeLocked(fact)
            } == true
            factSink.publish(fact)
            if (recorded) signalBestEffort()
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            val currentness = authoritativeApi34To37CallbackCurrentness() ?: return
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            factSink.publish(
                AndroidCaptureFact.CapturedContentVisibilityChanged(
                    provenance = callbackOccurrence.currentness.provenance,
                    callbackSequence = callbackOccurrence.sequence,
                    sampleNanos = clock.nowNanos(),
                    isVisible = isVisible,
                ),
            )
        }

        override fun onStop() {
            val currentness = authoritativeCallbackCurrentness() ?: return
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            factSink.publish(
                AndroidCaptureFact.CaptureEnded(
                    provenance = callbackOccurrence.currentness.provenance,
                    callbackSequence = callbackOccurrence.sequence,
                    sampleNanos = clock.nowNanos(),
                ),
            )
        }
    }

    init {
        require(projectionOwnerEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal val isProjectionCallbackRegistered: Boolean
        get() {
            val registration = currentProjectionCallbackRoot()?.operation ?: return false
            val registered = registration.settlementGate.withLock {
                registration.returnCell.disposition == OperationReturnDisposition.Normal
            }
            if (!registered) return false
            val unregistration = callbackUnregistrationOperation.get() ?: return true
            return unregistration.settlementGate.withLock {
                unregistration.returnCell.disposition != OperationReturnDisposition.Normal
            }
        }

    private fun currentProjectionCallbackRoot(): AndroidProjectionCallbackRoot? =
        when (val slot = callbackSlot.get()) {
            is AndroidProjectionCallbackSlot.OpenOwned -> slot.root
            is AndroidProjectionCallbackSlot.ClosedOwned -> slot.root
            else -> null
        }

    private fun currentProjectionCallbackOperation(): OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? =
        currentProjectionCallbackRoot()?.operation

    private val isProjectionCallbackAuthorityOpen: Boolean
        get() = when (callbackSlot.get()) {
            AndroidProjectionCallbackSlot.OpenEmpty,
            is AndroidProjectionCallbackSlot.Preparing,
            is AndroidProjectionCallbackSlot.OpenOwned,
                -> true
            else -> false
        }

    private fun resolveProjectionCallbackRoot(
        preparing: AndroidProjectionCallbackSlot.Preparing,
        claim: AndroidProjectionCallbackClaim,
        root: AndroidProjectionCallbackRoot,
    ) {
        while (true) {
            val exact = callbackSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim -> AndroidProjectionCallbackSlot.OpenOwned(root)
                exact is AndroidProjectionCallbackSlot.ClosedPreparing && exact.claim === claim ->
                    AndroidProjectionCallbackSlot.ClosedOwned(root)
                else -> error("Projection callback preparation lost its exact claim")
            }
            if (callbackSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun resolveProjectionCallbackPreparationFailure(
        preparing: AndroidProjectionCallbackSlot.Preparing,
        claim: AndroidProjectionCallbackClaim,
    ) {
        while (true) {
            val exact = callbackSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim -> AndroidProjectionCallbackSlot.OpenEmpty
                exact is AndroidProjectionCallbackSlot.ClosedPreparing && exact.claim === claim ->
                    AndroidProjectionCallbackSlot.ClosedEmpty
                else -> error("Projection callback preparation lost its exact claim")
            }
            if (callbackSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun closeProjectionCallbackSlot(): Boolean {
        while (true) {
            val exact = callbackSlot.get()
            val replacement = when (exact) {
                AndroidProjectionCallbackSlot.OpenEmpty -> AndroidProjectionCallbackSlot.ClosedEmpty
                is AndroidProjectionCallbackSlot.Preparing -> {
                    exact.claim.admissionCutoff.close()
                    AndroidProjectionCallbackSlot.ClosedPreparing(exact.claim)
                }
                is AndroidProjectionCallbackSlot.OpenOwned -> {
                    exact.root.claim.admissionCutoff.close()
                    AndroidProjectionCallbackSlot.ClosedOwned(exact.root)
                }
                AndroidProjectionCallbackSlot.ClosedEmpty,
                is AndroidProjectionCallbackSlot.ClosedPreparing,
                is AndroidProjectionCallbackSlot.ClosedOwned,
                    -> return false
            }
            if (callbackSlot.compareAndSet(exact, replacement)) return true
        }
    }

    private fun currentVirtualDisplayCreationRoot(): AndroidVirtualDisplayCreationRoot? =
        when (val slot = virtualDisplayCreationSlot.get()) {
            is AndroidVirtualDisplayCreationSlot.CommitInFlight -> slot.root
            is AndroidVirtualDisplayCreationSlot.Owned -> slot.root
            is AndroidVirtualDisplayCreationSlot.RetiredUnused -> slot.root
            is AndroidVirtualDisplayCreationSlot.ClosedOwned -> slot.root
            is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight -> slot.root
            is AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused -> slot.root
            else -> null
        }

    private fun currentVirtualDisplayCreationOperation(): OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? =
        currentVirtualDisplayCreationRoot()?.operation

    private fun resolveVirtualDisplayCreationRoot(
        preparing: AndroidVirtualDisplayCreationSlot.Preparing,
        claim: AndroidVirtualDisplayCreationClaim,
        root: AndroidVirtualDisplayCreationRoot,
    ) {
        while (true) {
            val exact = virtualDisplayCreationSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim ->
                    AndroidVirtualDisplayCreationSlot.CommitInFlight(root)
                exact is AndroidVirtualDisplayCreationSlot.ClosedPreparing && exact.claim === claim ->
                    AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight(root)
                else -> error("VirtualDisplay creation preparation lost its exact claim")
            }
            if (virtualDisplayCreationSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun resolveVirtualDisplayCreationPreparationFailure(
        preparing: AndroidVirtualDisplayCreationSlot.Preparing,
        claim: AndroidVirtualDisplayCreationClaim,
    ) {
        while (true) {
            val exact = virtualDisplayCreationSlot.get()
            val replacement = when {
                exact === preparing && preparing.claim === claim -> AndroidVirtualDisplayCreationSlot.OpenEmpty
                exact is AndroidVirtualDisplayCreationSlot.ClosedPreparing && exact.claim === claim ->
                    AndroidVirtualDisplayCreationSlot.ClosedEmpty
                else -> error("VirtualDisplay creation preparation lost its exact claim")
            }
            if (virtualDisplayCreationSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun retireVirtualDisplayCreationRoot(
        root: AndroidVirtualDisplayCreationRoot,
    ): Boolean {
        val openRetired = AndroidVirtualDisplayCreationSlot.RetiredUnused(root)
        val closedRetired = AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused(root)
        while (true) {
            val exact = virtualDisplayCreationSlot.get()
            val replacement = when (exact) {
                is AndroidVirtualDisplayCreationSlot.CommitInFlight ->
                    openRetired.takeIf { exact.root === root } ?: return false
                is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight ->
                    closedRetired.takeIf { exact.root === root } ?: return false
                is AndroidVirtualDisplayCreationSlot.RetiredUnused -> return exact.root === root
                is AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused -> return exact.root === root
                else -> return false
            }
            if (virtualDisplayCreationSlot.compareAndSet(exact, replacement)) return true
        }
    }

    private fun commitVirtualDisplayCreationRoot(
        root: AndroidVirtualDisplayCreationRoot,
    ): Boolean {
        val openOwned = AndroidVirtualDisplayCreationSlot.Owned(root)
        val closedOwned = AndroidVirtualDisplayCreationSlot.ClosedOwned(root)
        while (true) {
            val exact = virtualDisplayCreationSlot.get()
            val replacement = when (exact) {
                is AndroidVirtualDisplayCreationSlot.CommitInFlight ->
                    openOwned.takeIf { exact.root === root } ?: return false
                is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight ->
                    closedOwned.takeIf { exact.root === root } ?: return false
                is AndroidVirtualDisplayCreationSlot.Owned -> return exact.root === root
                is AndroidVirtualDisplayCreationSlot.ClosedOwned -> return exact.root === root
                else -> return false
            }
            if (virtualDisplayCreationSlot.compareAndSet(exact, replacement)) return true
        }
    }

    private fun closeVirtualDisplayCreationAdmission() {
        while (true) {
            val exact = virtualDisplayCreationSlot.get()
            val replacement = when (exact) {
                AndroidVirtualDisplayCreationSlot.OpenEmpty -> AndroidVirtualDisplayCreationSlot.ClosedEmpty
                is AndroidVirtualDisplayCreationSlot.Preparing -> {
                    exact.claim.admissionCutoff.close()
                    AndroidVirtualDisplayCreationSlot.ClosedPreparing(exact.claim)
                }
                is AndroidVirtualDisplayCreationSlot.CommitInFlight -> {
                    exact.root.claim.admissionCutoff.close()
                    AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight(exact.root)
                }
                is AndroidVirtualDisplayCreationSlot.Owned -> {
                    exact.root.claim.admissionCutoff.close()
                    AndroidVirtualDisplayCreationSlot.ClosedOwned(exact.root)
                }
                is AndroidVirtualDisplayCreationSlot.RetiredUnused -> {
                    exact.root.claim.admissionCutoff.close()
                    AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused(exact.root)
                }
                AndroidVirtualDisplayCreationSlot.ClosedEmpty,
                is AndroidVirtualDisplayCreationSlot.ClosedPreparing,
                is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight,
                is AndroidVirtualDisplayCreationSlot.ClosedOwned,
                is AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused,
                    -> return
            }
            if (virtualDisplayCreationSlot.compareAndSet(exact, replacement)) return
        }
    }

    private fun currentVirtualDisplayReleaseRoot(): AndroidVirtualDisplayReleaseRoot? =
        when (val slot = virtualDisplayReleaseSlot.get()) {
            is AndroidVirtualDisplayReleaseSlot.Owned -> slot.root
            is AndroidVirtualDisplayReleaseSlot.RetiredUnused -> slot.root
            else -> null
        }

    private fun currentVirtualDisplayReleaseOperation(): OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? =
        currentVirtualDisplayReleaseRoot()?.operation

    private fun resolveVirtualDisplayReleaseRoot(
        preparing: AndroidVirtualDisplayReleaseSlot.Preparing,
        claim: AndroidVirtualDisplayReleaseClaim,
        root: AndroidVirtualDisplayReleaseRoot,
    ): AndroidVirtualDisplayReleaseSlot.Owned {
        val owned = AndroidVirtualDisplayReleaseSlot.Owned(root)
        check(preparing.claim === claim && virtualDisplayReleaseSlot.compareAndSet(preparing, owned))
        return owned
    }

    private fun resolveVirtualDisplayReleasePreparationFailure(
        preparing: AndroidVirtualDisplayReleaseSlot.Preparing,
        claim: AndroidVirtualDisplayReleaseClaim,
    ) {
        check(preparing.claim === claim &&
            virtualDisplayReleaseSlot.compareAndSet(preparing, AndroidVirtualDisplayReleaseSlot.Empty))
    }

    private fun retireVirtualDisplayReleaseRoot(
        owned: AndroidVirtualDisplayReleaseSlot.Owned,
        root: AndroidVirtualDisplayReleaseRoot,
    ): Boolean = owned.root === root &&
        virtualDisplayReleaseSlot.compareAndSet(owned, AndroidVirtualDisplayReleaseSlot.RetiredUnused(root))

    internal val laneStartupResult: AndroidLaneStartupResult
        get() = laneRuntime.startupResult

    internal val observedLaneFatal: Throwable?
        get() = laneRuntime.observedFatal

    internal val observedOrdinaryLaneFailure: Exception?
        get() = laneRuntime.observedOrdinaryLaneFailure

    internal val hasLaneReturned: Boolean
        get() = laneTerminationReceipt != null

    internal val laneTerminationReceipt: AndroidLaneTerminationReceipt?
        get() = laneRuntime.terminationReceipt

    internal val laneReturnCause: Throwable?
        get() = laneRuntime.threadReturnCause

    internal val laneQuitOutcome: AndroidLaneQuitOutcome?
        get() = laneRuntime.observedQuitOutcome

    internal val retainedTargetListenerRemovalRoot: AndroidTargetListenerRemovalRoot?
        get() = targetListenerRemovalRoot.get()

    internal fun startLane(): Boolean = laneRuntime.start()

    internal fun createProjectionCallbackRegistrationOperation(
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? {
        val claim = AndroidProjectionCallbackClaim(AndroidWorkAdmissionCutoff())
        val preparing = AndroidProjectionCallbackSlot.Preparing(claim)
        if (!callbackSlot.compareAndSet(AndroidProjectionCallbackSlot.OpenEmpty, preparing)) return null
        try {
            val ownerBag = AndroidProjectionCallbackRegistrationOwnerBag(projection, projectionCallback)
            val operation = finiteOccurrence(
                identity = identity,
                evidence = AndroidProjectionCallbackRegistrationEvidence(),
                ownerBag = ownerBag,
            )
            check(ownerBag.bindOperation(operation))
            val provenance = AndroidCallbackProvenance(
                owner = this,
                projectionOwnerEpoch = projectionOwnerEpoch,
                callbackRegistrationIdentity = operation.identity,
                callbackIdentity = callbackIdentity,
            )
            val ticket = laneRuntime.ticket(
                occurrence = operation,
                postRejectionMessage = "Android projection callback registration rejected",
                enteredWork = AndroidEnteredWork { handler ->
                    try {
                        projection.registerCallback(projectionCallback, handler)
                        operation.publishNormalReturn()
                    } catch (failure: Exception) {
                        operation.publishThrownReturn(failure)
                    }
                    signalBestEffort()
                },
            )
            check(ownerBag.bindPostTicket(ticket))
            val ownerCutoffProof = AndroidOwnerPostCutoffProof(claim.admissionCutoff, ticket, operation)
            val root = AndroidProjectionCallbackRoot(claim, operation, provenance, ownerCutoffProof)
            resolveProjectionCallbackRoot(preparing, claim, root)
            return operation
        } catch (raw: Throwable) {
            resolveProjectionCallbackPreparationFailure(preparing, claim)
            throw raw
        }
    }

    internal fun submitProjectionCallbackRegistration(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidProjectionCallbackRegistrationOwnerBag ?: return false
        val root = (callbackSlot.get() as? AndroidProjectionCallbackSlot.OpenOwned)?.root ?: return false
        if (root.operation !== operation || !root.claim.admissionCutoff.reserve()) return false
        return try {
            val outcome = laneRuntime.post(checkNotNull(ownerBag.postTicket))
            check(ownerBag.bindSchedulerOutcome(outcome))
            outcome == AndroidPostResult.Accepted
        } finally {
            root.claim.admissionCutoff.release()
        }
    }

    internal fun createTargetListenerInstallationOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidTargetListenerInstallationEvidence>? {
        if (!reserveTargetListenerInstallationAdmission()) return null
        try {
            if (targetListenerInstallationClaim.get() != null ||
                targetListenerInstallationBoundRoot.get() != null
            ) {
                return null
            }
            val claim = target.claimListenerInstallationRequest(identity.operationIdentity) ?: return null
            targetListenerInstallationClaim.set(claim)
            val ownerBag = AndroidTargetListenerInstallationOwnerBag(target, claim)
            val operation = finiteOccurrence(
                identity = identity,
                evidence = AndroidTargetListenerInstallationEvidence(),
                ownerBag = ownerBag,
            )
            check(ownerBag.bindOperation(operation))
            val ticket = laneRuntime.ticket(
                occurrence = operation,
                postRejectionMessage = "Android target-listener installation rejected",
                enteredWork = AndroidEnteredWork { handler ->
                    try {
                        check(
                            ownerBag.port.withListener { surfaceTexture, listener ->
                                surfaceTexture.setOnFrameAvailableListener(listener, handler)
                            } == TargetPortUseOutcome.BodyReturned,
                        )
                        operation.publishNormalReturn()
                    } catch (failure: Exception) {
                        operation.publishThrownReturn(failure)
                    }
                    signalBestEffort()
                },
            )
            check(ownerBag.bindPostTicket(ticket))
            val boundRoot = AndroidTargetListenerInstallationBoundRoot.create(
                target,
                claim,
                ownerBag,
                operation,
                ticket,
                targetListenerInstallationAdmissionCutoff,
            )
            check(targetListenerInstallationBoundRoot.compareAndSet(null, boundRoot))
            val committedFact = try {
                target.bindAndroidTargetOperation(claim, ownerBag.binding)
            } catch (raw: Throwable) {
                try {
                    if (boundRoot.exactCommittedCapability() == null) {
                        targetListenerInstallationBoundRoot.compareAndSet(boundRoot, null)
                    }
                } finally {
                    throw raw
                }
            }
            if (committedFact == null) {
                val recovered = boundRoot.exactCommittedCapability()
                if (recovered != null) return operation
                check(targetListenerInstallationBoundRoot.compareAndSet(boundRoot, null))
                return null
            }
            check(boundRoot.retainCommittedCapability(committedFact))
            return operation
        } finally {
            releaseTargetListenerInstallationAdmission()
        }
    }

    internal fun submitTargetListenerInstallation(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): Boolean {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return false
        val committedFact = boundRoot.exactCommittedCapability() ?: return false
        if (!boundRoot.claimSubmission()) return false
        if (!reserveTargetListenerInstallationAdmission()) {
            val authoritativeCutoff = targetListenerInstallationAdmissionGate.withLock {
                targetListenerInstallationAdmissionCutoff.takeIf { !targetListenerInstallationAdmissionOpen }
            }
            if (authoritativeCutoff != null) {
                check(operation.settlementGate.withLock {
                    boundRoot.boundNeverSubmittedProof.activateLocked(committedFact, authoritativeCutoff)
                })
            }
            signalBestEffort()
            return false
        }
        return try {
            when (val schedulerOutcome = laneRuntime.post(boundRoot.ticket)) {
                AndroidPostResult.Accepted -> true
                AndroidPostResult.NotSubmitted,
                AndroidPostResult.Rejected,
                    -> {
                        settleTargetListenerInstallationNoPlatformEntry(
                            boundRoot,
                            schedulerOutcome,
                        )
                        signalBestEffort()
                        false
                    }
            }
        } finally {
            releaseTargetListenerInstallationAdmission()
        }
    }

    internal fun claimTargetListenerInstallationResult(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidTargetPlatformResult.ListenerInstalled? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        val committedFact = boundRoot.exactCommittedCapability() ?: return null
        return operation.settlementGate.withLock {
            boundRoot.result.takeIf {
            committedFact.claim === boundRoot.claim && committedFact.binding === boundRoot.binding &&
                operation.returnCell.disposition == OperationReturnDisposition.Normal
            }
        }
    }

    internal fun recordTargetListenerInstallationApplied(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidTargetListenerInstallationOwnerBag ?: return@withLock false
        val fact = result.fact
        if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            fact.targetIdentity !== ownerBag.port.targetIdentity || fact.operationIdentity != operation.identity ||
            fact.provenance !== ownerBag.port.provenance
        ) {
            return@withLock false
        }
        operation.returnCell.evidence.recordAppliedTargetResultLocked(result)
    }

    internal fun closeTargetListenerInstallationAdmission(): Boolean {
        val closed = targetListenerInstallationAdmissionGate.withLock {
            if (!targetListenerInstallationAdmissionOpen) return@withLock false
            targetListenerInstallationAdmissionOpen = false
            true
        }
        if (closed) signalBestEffort()
        return closed
    }

    internal fun acceptsTargetListenerInstallationUnboundClaimRetiredProofCreation(
        claim: TargetListenerInstallationRequestClaim,
    ): Boolean = targetListenerInstallationAdmissionGate.withLock {
        !targetListenerInstallationAdmissionOpen && targetListenerInstallationInFlight == 0 &&
            targetListenerInstallationClaim.get() === claim &&
            targetListenerInstallationBoundRoot.get() == null &&
            targetListenerInstallationFinalLaneNoEntryProof.get() == null
    }

    internal fun targetListenerInstallationUnboundClaimRetiredProof():
        AndroidTargetListenerInstallationUnboundClaimRetiredProof? =
        targetListenerInstallationAdmissionGate.withLock {
            if (targetListenerInstallationAdmissionOpen || targetListenerInstallationInFlight != 0) {
                return@withLock null
            }
            targetListenerInstallationUnboundClaimRetiredProof.get()?.let { return@withLock it }
            val claim = targetListenerInstallationClaim.get() ?: return@withLock null
            if (targetListenerInstallationBoundRoot.get() != null ||
                targetListenerInstallationFinalLaneNoEntryProof.get() != null
            ) {
                return@withLock null
            }
            val proof = AndroidTargetListenerInstallationUnboundClaimRetiredProof.create(this, claim)
            targetListenerInstallationUnboundClaimRetiredProof.compareAndSet(null, proof)
            targetListenerInstallationUnboundClaimRetiredProof.get()
        }

    internal fun targetListenerInstallationBindingCommittedFact(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): TargetListenerInstallationBindingCommittedFact? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        return boundRoot.exactCommittedCapability()
    }

    internal fun targetListenerInstallationNoPlatformEntryOutcome(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        if (boundRoot.exactCommittedCapability() == null) return null
        return recoverTargetListenerInstallationNoPlatformEntry(boundRoot)
    }

    internal fun targetListenerInstallationBoundNeverSubmittedProof(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidTargetListenerInstallationBoundNeverSubmittedProof? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        val committedFact = boundRoot.exactCommittedCapability() ?: return null
        if (boundRoot.boundNeverSubmittedProof.committedFact == null) {
            val authoritativeCutoff = targetListenerInstallationAdmissionGate.withLock {
                targetListenerInstallationAdmissionCutoff.takeIf {
                    !targetListenerInstallationAdmissionOpen && targetListenerInstallationInFlight == 0 &&
                        targetListenerInstallationBoundRoot.get() === boundRoot
                }
            } ?: return null
            val activated = operation.settlementGate.withLock {
                if (boundRoot.boundNeverSubmittedProof.committedFact == null) {
                    boundRoot.boundNeverSubmittedProof.activateLocked(committedFact, authoritativeCutoff)
                } else {
                    true
                }
            }
            if (!activated) return null
        }
        return boundRoot.boundNeverSubmittedProof.takeIf {
            it.committedFact === committedFact
        }
    }

    internal fun targetListenerInstallationLaneCutoffProof(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidLanePostCutoffProof<AndroidTargetListenerInstallationEvidence>? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        return boundRoot.ticket.authoritativePostCutoffProof.takeIf {
            it.operation === operation && it.isActivatedExact
        }
    }

    internal fun createVirtualDisplayCreationOperation(
        target: CurrentTarget,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        identity: AndroidFiniteOperationIdentity,
        initialResizeDeadlineIdentity: AndroidInitialResizeDeadlineIdentity?,
    ): AndroidVirtualDisplayCreationPreparationResult? {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
        require(
            if (apiBand == AndroidCaptureApiBand.Api34To37) {
                initialResizeDeadlineIdentity != null
            } else {
                initialResizeDeadlineIdentity == null
            },
        )
        if (!isProjectionCallbackRegistered || !isProjectionCallbackAuthorityOpen) {
            return null
        }
        val claim = AndroidVirtualDisplayCreationClaim(
            target,
            identity.operationIdentity,
            AndroidWorkAdmissionCutoff(),
        )
        val preparing = AndroidVirtualDisplayCreationSlot.Preparing(claim)
        if (!virtualDisplayCreationSlot.compareAndSet(AndroidVirtualDisplayCreationSlot.OpenEmpty, preparing)) return null
        val preparedPort = try {
            target.prepareProducerPort(
                identity.operationIdentity,
                TargetProducerOperationKind.VirtualDisplayCreation,
            )
        } catch (raw: Throwable) {
            resolveVirtualDisplayCreationPreparationFailure(preparing, claim)
            throw raw
        }
        if (preparedPort is io.screenstream.engine.internal.target.TargetProducerPreparationRetiredUnusedFact) {
            check(claim.root(preparedPort))
            check(claim.owns(preparedPort))
            val result = AndroidVirtualDisplayCreationPreparationResult.TargetPreparationFailed(preparedPort)
            resolveVirtualDisplayCreationPreparationFailure(preparing, claim)
            return result
        }
        val port = preparedPort as? TargetPorts.AndroidSurfacePort
            ?: error("Target producer preparation returned an unknown result")
        check(claim.root(port))
        check(claim.owns(port))
        val returnedOwnerCell = AndroidVirtualDisplayReturnedOwnerCell()
        val evidence = AndroidVirtualDisplayCreationEvidence(returnedOwnerCell)
        val initialLogicalTuple = AndroidVirtualDisplayLogicalTuple(widthPx, heightPx, densityDpi)
        val applicationCandidate = AndroidAttachedVirtualDisplay(
            returnedOwnerCell,
            target,
            port,
            evidence,
        )
        val ownerBag = AndroidVirtualDisplayCreationOwnerBag(
            projection = projection,
            target = target,
            port = port,
            initialLogicalTuple = initialLogicalTuple,
            applicationCandidate = applicationCandidate,
        )
        val operation = finiteOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = ownerBag,
        )
        check(applicationCandidate.bindProducerOperation(operation))
        check(ownerBag.bindOperation(operation))
        if (initialResizeDeadlineIdentity != null) {
            val initialResizeDeadlineOccurrence = DeadlineOccurrence(
                identity = initialResizeDeadlineIdentity.deadlineIdentity,
                boundOccurrenceIdentity = operation.identity,
                durationNanos = initialCapturedResizeReadinessNanos,
                initialWakeGeneration = initialResizeDeadlineIdentity.deadlineWakeGeneration,
                timeoutCause = initialResizeDeadlineIdentity.timeoutCause,
                settlementGate = operation.settlementGate,
                clock = clock,
                signal = settlementSignal,
            )
            val deadlineBound = operation.settlementGate.withLock {
                evidence.bindInitialResizeDeadlineLocked(initialResizeDeadlineOccurrence)
            }
            check(deadlineBound)
        }
        val ticket = laneRuntime.ticket(
            occurrence = operation,
            postRejectionMessage = "Android VirtualDisplay creation rejected",
            enteredWork = AndroidEnteredWork {
                executeVirtualDisplayCreation(operation, ownerBag)
            },
        )
        check(ownerBag.bindPostTicket(ticket))
        val ownerCutoffProof = AndroidOwnerPostCutoffProof(claim.admissionCutoff, ticket, operation)
        val root = AndroidVirtualDisplayCreationRoot(claim, operation, ownerCutoffProof)
        resolveVirtualDisplayCreationRoot(preparing, claim, root)
        return when (val commit = checkNotNull(target.bindAndroidTargetOperation(port, ownerBag.binding))) {
            is io.screenstream.engine.internal.target.TargetProducerPortCommittedFact -> {
                check(ownerBag.recordTargetCommit(commit))
                check(commitVirtualDisplayCreationRoot(root))
                AndroidVirtualDisplayCreationPreparationResult.Ready(operation, commit)
            }

            is io.screenstream.engine.internal.target.TargetProducerPortRetiredUnusedFact -> {
                check(ownerBag.recordTargetCommit(commit))
                check(operation.settlementGate.withLock { operation.settleInertBeforeEntryLocked() })
                check(retireVirtualDisplayCreationRoot(root))
                AndroidVirtualDisplayCreationPreparationResult.RetiredUnused(operation, commit)
            }

            else -> error("Unexpected initial producer-port commit result")
        }
    }

    internal fun submitVirtualDisplayCreation(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        val root = (virtualDisplayCreationSlot.get() as? AndroidVirtualDisplayCreationSlot.Owned)?.root
            ?: return false
        if (root.operation !== operation ||
            ownerBag.targetCommit !is io.screenstream.engine.internal.target.TargetProducerPortCommittedFact
        ) return false
        if (!root.claim.admissionCutoff.reserve()) return false
        return try {
            laneRuntime.post(ownerBag.postTicket) == AndroidPostResult.Accepted
        } finally {
            root.claim.admissionCutoff.release()
        }
    }

    internal fun claimVirtualDisplayCreationPlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): AndroidTargetPlatformResult? {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return null
        if (ownerBag.targetCommit !is io.screenstream.engine.internal.target.TargetProducerPortCommittedFact) {
            return null
        }
        publishNoPlatformEntryIfProven(
            operation = operation,
            retainedOperation = { currentVirtualDisplayCreationOperation() },
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            occurrenceProof = ownerBag.occurrenceNoEntryProof,
            ticket = ownerBag.postTicket,
            ownerCutoffProof = currentVirtualDisplayCreationRoot()?.ownerCutoffProof,
        )
        return operation.settlementGate.withLock {
            val evidence = operation.returnCell.evidence
            evidence.selectedPlatformResult?.let { return@withLock it }
            val exactNoEntryProof = virtualDisplayCreationNoPlatformEntryProof.get()
            val result = when {
            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    currentVirtualDisplayCreationOperation() === operation &&
                    exactNoEntryProof?.operation === operation ->
                ownerBag.unenteredResult

            operation.returnCell.disposition == OperationReturnDisposition.Normal && evidence.returnedVirtualDisplay != null ->
                ownerBag.producerResult

            operation.returnCell.disposition == OperationReturnDisposition.Normal ->
                ownerBag.returnedWithoutProducerResult

            operation.returnCell.disposition == OperationReturnDisposition.Thrown &&
                    evidence.returnedVirtualDisplay == null ->
                ownerBag.settledResult

            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    virtualDisplayCreationNoPlatformEntryProof.get()?.operation === operation ->
                ownerBag.unenteredResult

            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    operation.domain == OperationDomain.Cleanup &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.Cancelled &&
                    operation.submissionFailure == null && operation.submissionAmbiguousFatal == null ->
                ownerBag.inapplicableResult

                else -> null
            } ?: return@withLock null
            check(evidence.recordSelectedPlatformResultLocked(result))
            result
        }
    }

    internal fun applyVirtualDisplayCreationTargetResult(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        val evidence = operation.returnCell.evidence
        val candidate = ownerBag.applicationCandidate
        return operation.settlementGate.withLock {
            if (currentVirtualDisplayCreationOperation() !== operation ||
                evidence.selectedPlatformResult == null ||
                evidence.appliedTargetFact != null || evidence.settledTargetResult != null
            ) {
                return@withLock false
            }
            when (result) {
                is io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.InitialProducerPortSettledOrAmbiguous -> {
                    val fact = result.fact
                    if (operation.returnCell.disposition != OperationReturnDisposition.Thrown ||
                        evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
                        evidence.returnedVirtualDisplay != null || evidence.selectedPlatformResult !== ownerBag.settledResult ||
                        fact.targetIdentity !== ownerBag.port.targetIdentity || fact.operationIdentity != operation.identity ||
                        fact.provenance !== ownerBag.port.provenance
                    ) {
                        return@withLock false
                    }
                    evidence.recordSettledTargetResultLocked(result)
                }

                is io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.Producer -> {
                    when (val fact = result.fact) {
                        is TargetNoProducerEvidence -> {
                            if (!matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)) {
                                return@withLock false
                            }
                            if (evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
                                evidence.returnedVirtualDisplay != null ||
                                (evidence.selectedPlatformResult as? AndroidTargetPlatformResult.ProducerUnavailable)?.reason != fact.reason
                            ) {
                                return@withLock false
                            }
                            if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
                            val existingOwnership = virtualDisplayOwner.get()
                            if (existingOwnership == null) {
                                true
                            } else {
                                if (!evidence.recordCollisionLocked(existingOwnership)) return@withLock false
                                false
                            }
                        }

                        is TargetProducerEvidence -> {
                            if (!matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)) {
                                return@withLock false
                            }
                            val returnedDisplay = evidence.returnedVirtualDisplay ?: return@withLock false
                            if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
                                evidence.selectedPlatformResult !== ownerBag.producerResult ||
                                evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Rooted
                            ) {
                                return@withLock false
                            }
                            if (candidate.virtualDisplay !== returnedDisplay ||
                                !evidence.recordAppliedTargetFactLocked(fact)
                            ) {
                                return@withLock false
                            }
                            if (virtualDisplayOwner.compareAndSet(null, candidate)) {
                                evidence.recordInstalledLocked() && candidate.compactAfterApplication(operation, fact)
                            } else {
                                if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                                false
                            }
                        }
                    }
                }

                else -> false
            }
        }
    }

    internal fun createTargetListenerRemovalOperation(
        target: CurrentTarget,
        operationIdentity: Long,
        finiteIdentity: AndroidFiniteOperationIdentity?,
    ): AndroidTargetListenerRemovalPreparationResult? {
        require(operationIdentity > 0L)
        require(finiteIdentity == null || finiteIdentity.operationIdentity == operationIdentity)
        val evidence = AndroidTargetListenerRemovalEvidence()
        val ownerBag = AndroidTargetListenerRemovalOwnerBag(target, operationIdentity)
        val operation = if (finiteIdentity == null) {
            cleanupOccurrence(operationIdentity, evidence, ownerBag)
        } else {
            finiteOccurrence(finiteIdentity, evidence, ownerBag)
        }
        val sentinelTicket = laneRuntime.listenerSentinelTicket(
            operationIdentity,
            "Android target-listener sentinel rejected",
            AndroidEnteredWork {
                try {
                    val targetResult = checkNotNull(
                        ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.sentinelObservedResult),
                    ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved
                    check(ownerBag.recordSentinelObservedApplicationResult(targetResult))
                    operation.publishNormalReturn()
                } catch (failure: Exception) {
                    operation.publishThrownReturn(failure)
                    throw failure
                }
                signalBestEffort()
            },
            AndroidListenerSentinelFinalWork { disposition, exactFailure ->
                if (operation.identity != operationIdentity) return@AndroidListenerSentinelFinalWork
                when (disposition) {
                    AndroidListenerSentinelMechanicalDisposition.Accepted -> Unit
                    AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered -> {
                        check(operation.settlementGate.withLock {
                            val evidence = operation.returnCell.evidence
                            when (val current = evidence.sentinelPostOutcome) {
                                null -> evidence.recordSentinelPostOutcomeLocked(
                                    ownerBag.sentinelDefinitelyUnentered,
                                    exactFailure,
                                )
                                ownerBag.sentinelDefinitelyUnentered ->
                                    evidence.sentinelPostFailureResidue === exactFailure
                                ownerBag.sentinelPostExposed ->
                                    evidence.refineSentinelPostOutcomeToDefinitelyUnenteredLocked(
                                        ownerBag.sentinelPostExposed,
                                        ownerBag.sentinelDefinitelyUnentered,
                                        exactFailure,
                                    )
                                else -> false
                            }
                        })
                        if (exactFailure is Exception) operation.publishThrownReturn(exactFailure)
                        else operation.publishNormalReturn()
                    }
                    AndroidListenerSentinelMechanicalDisposition.Pending,
                    AndroidListenerSentinelMechanicalDisposition.RejectedFinal,
                    AndroidListenerSentinelMechanicalDisposition.AwaitingEntry,
                        -> Unit
                }
                signalBestEffort()
            },
        )
        check(ownerBag.bindSentinelTicket(sentinelTicket))
        val removalTicket = laneRuntime.ticket(
            operation,
            "Android target-listener removal rejected",
            AndroidEnteredWork { handler ->
                executeTargetListenerRemoval(operation, ownerBag, handler)
            },
        )
        check(ownerBag.bindRemovalTicket(removalTicket))
        val root = AndroidTargetListenerRemovalRoot(ownerBag, operation, sentinelTicket)
        if (!targetListenerRemovalRoot.compareAndSet(null, root)) return null
        val port = target.registerListenerRemovalPort(operationIdentity)
        if (port == null) {
            check(root.recordTargetRejected())
            check(operation.settlementGate.withLock { operation.settleInertBeforeEntryLocked() })
            return AndroidTargetListenerRemovalPreparationResult.TargetRejected(root)
        }
        check(ownerBag.bindTargetPort(port))
        check(ownerBag.bindOperations(operation))
        check(target.bindAndroidListenerRemovalOperations(port, ownerBag.removalBinding, ownerBag.sentinelBinding))
        check(root.recordTargetBound())
        return AndroidTargetListenerRemovalPreparationResult.Ready(operation, root)
    }

    internal fun submitTargetListenerRemoval(operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>): Boolean {
        val ownerBag = operation.ownerBag as? AndroidTargetListenerRemovalOwnerBag ?: return false
        val root = targetListenerRemovalRoot.get()
            ?.takeIf { it.removalOperation === operation } ?: return false
        if (root.currentDisposition != AndroidTargetListenerRemovalRootDisposition.TargetBound) return false
        return laneRuntime.post(ownerBag.removalTicket) == AndroidPostResult.Accepted
    }

    internal fun closeProjectionCallbackAuthority(): Boolean {
        if (!closeProjectionCallbackSlot()) return false
        closeVirtualDisplayCreationAdmission()
        return true
    }

    internal fun foldFinalLaneTerminationReceipt(receipt: AndroidLaneTerminationReceipt) {
        foldFinalLaneNoEntry(
            receipt = receipt,
            operation = currentProjectionCallbackOperation(),
            retainedOperation = { currentProjectionCallbackOperation() },
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            ticket = { ownerBag ->
                (ownerBag as? AndroidProjectionCallbackRegistrationOwnerBag)?.postTicket
            },
        )
        foldFinalLaneNoEntry(
            receipt = receipt,
            operation = currentVirtualDisplayCreationOperation(),
            retainedOperation = { currentVirtualDisplayCreationOperation() },
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            ticket = { ownerBag ->
                (ownerBag as? AndroidVirtualDisplayCreationOwnerBag)?.postTicket
            },
        )
        foldFinalListenerInstallationNoEntry(
            receipt = receipt,
            operation = targetListenerInstallationBoundRoot.get()?.operation,
        )
        targetListenerRemovalRoot.get()?.sentinelTicket?.foldFinalLaneNoEntry(receipt)
    }

    internal fun finalLaneListenerInstallationNoEntryProof(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>? {
        if (targetListenerInstallationBoundNeverSubmittedProof(operation) != null) return null
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        if (boundRoot.exactCommittedCapability() == null) return null
        val proof = targetListenerInstallationFinalLaneNoEntryProof.get() ?: return null
        return operation.settlementGate.withLock {
            proof.takeIf {
                targetListenerInstallationBoundRoot.get() === boundRoot &&
                    boundRoot.activatedNoPlatformEntryOutcomeLocked() == null &&
                    boundRoot.ticket === it.ticket &&
                    it.operation === operation &&
                    it.operationIdentity == operation.identity &&
                    it.ticket.occurrence === operation &&
                    it.ticket.operationIdentity == operation.identity &&
                    it.lane === laneRuntime &&
                    laneRuntime.terminationReceipt === it.terminationReceipt &&
                    laneRuntime.acceptsTerminationReceipt(it.terminationReceipt) &&
                    it.ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    operation.submissionAmbiguousFatal == null &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    (operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
                        it.ticket.postFailureResidue == null && operation.submissionFailure == null &&
                        operation.disposition == OperationDisposition.Cancelled ||
                        operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                        it.ticket.postFailureResidue != null &&
                        operation.submissionFailure === it.ticket.postFailureResidue &&
                        operation.disposition == OperationDisposition.SchedulerRejected) &&
                    operation.returnCell.disposition == OperationReturnDisposition.Empty
            }
        }
    }

    internal fun createProjectionCallbackUnregistrationOperation(
        operationIdentity: Long,
    ): OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? {
        require(operationIdentity > 0L)
        if (isProjectionCallbackAuthorityOpen) return null
        callbackUnregistrationOperation.get()?.let { existing ->
            if (existing.settlementGate.withLock {
                    existing.returnCell.disposition != OperationReturnDisposition.Empty
                }
            ) return null
        }
        val registrationOperation = currentProjectionCallbackOperation() ?: return null
        publishNoPlatformEntryIfProven(
            operation = registrationOperation,
            retainedOperation = { currentProjectionCallbackOperation() },
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            occurrenceProof = (registrationOperation.ownerBag as AndroidProjectionCallbackRegistrationOwnerBag)
                .occurrenceNoEntryProof,
            ticket = (registrationOperation.ownerBag as AndroidProjectionCallbackRegistrationOwnerBag).postTicket,
            ownerCutoffProof = currentProjectionCallbackRoot()?.ownerCutoffProof,
        )
        if (callbackRegistrationNoPlatformEntryProof.get() != null || registrationOperation.settlementGate.withLock {
                registrationOperation.returnCell.disposition == OperationReturnDisposition.Empty
            }
        ) return null

        val ownerBag = AndroidProjectionCallbackUnregistrationOwnerBag(projection, projectionCallback)
        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidProjectionCallbackUnregistrationEvidence(),
            ownerBag = ownerBag,
        )
        val ticket = laneRuntime.ticket(
            operation,
            "Android projection callback unregistration rejected",
            AndroidEnteredWork {
                try {
                    projection.unregisterCallback(projectionCallback)
                    operation.publishNormalReturn()
                } catch (failure: Exception) {
                    operation.publishThrownReturn(failure)
                }
                signalBestEffort()
            },
        )
        check(ownerBag.bindPostTicket(ticket))
        return if (callbackUnregistrationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun projectionCallbackCleanupOutcome(
        registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?,
    ): AndroidProjectionCallbackCleanupOutcome? {
        callbackCleanupOutcome.get()?.let { outcome ->
            return outcome.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }
        if (isProjectionCallbackAuthorityOpen) return null
        if (registrationOperation == null) {
            if (callbackSlot.get() !== AndroidProjectionCallbackSlot.ClosedEmpty) return null
            val outcome = AndroidProjectionCallbackCleanupOutcome.StructurallyInapplicable(this)
            callbackCleanupOutcome.compareAndSet(null, outcome)
            return callbackCleanupOutcome.get()
                ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }
        if (currentProjectionCallbackOperation() !== registrationOperation) return null
        val ownerBag = registrationOperation.ownerBag as? AndroidProjectionCallbackRegistrationOwnerBag
            ?: return null
        if (ownerBag.projection !== projection || ownerBag.callback !== projectionCallback ||
            ownerBag.occurrenceNoEntryProof.operation !== registrationOperation ||
            registrationOperation.returnCell.evidence.receipt !== AndroidProjectionCallbackRegistrationReceipt
        ) {
            return null
        }

        publishNoPlatformEntryIfProven(
            operation = registrationOperation,
            retainedOperation = { currentProjectionCallbackOperation() },
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            occurrenceProof = ownerBag.occurrenceNoEntryProof,
            ticket = ownerBag.postTicket,
            ownerCutoffProof = currentProjectionCallbackRoot()?.ownerCutoffProof,
        )?.let { proof ->
            val route = registrationOperation.settlementGate.withLock {
                exactProjectionCallbackNoEntryRouteLocked(registrationOperation, ownerBag, proof)
            } ?: return null
            val outcome = AndroidProjectionCallbackCleanupOutcome.RegistrationDidNotEnterPlatform(
                owner = this,
                operation = registrationOperation,
                ownerBag = ownerBag,
                proof = proof,
                route = route,
            )
            callbackCleanupOutcome.compareAndSet(null, outcome)
            return callbackCleanupOutcome.get()
                ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }

        val unregistration = callbackUnregistrationOperation.get() ?: return null
        val unregistrationOwnerBag = unregistration.ownerBag
            as? AndroidProjectionCallbackUnregistrationOwnerBag ?: return null
        val registrationSettled = registrationOperation.settlementGate.withLock {
            if (currentProjectionCallbackOperation() !== registrationOperation ||
                registrationOperation.entryDisposition != OperationEntryDisposition.Entered ||
                registrationOperation.returnCell.disposition == OperationReturnDisposition.Empty ||
                registrationOperation.returnCell.evidence.receipt !== AndroidProjectionCallbackRegistrationReceipt
            ) {
                return@withLock false
            }
            true
        }
        if (!registrationSettled) return null
        val returnDisposition = unregistration.settlementGate.withLock {
            if (callbackUnregistrationOperation.get() !== unregistration ||
                unregistrationOwnerBag.projection !== projection ||
                unregistrationOwnerBag.callback !== projectionCallback ||
                unregistration.entryDisposition != OperationEntryDisposition.Entered ||
                unregistration.returnCell.disposition == OperationReturnDisposition.Empty ||
                unregistration.returnCell.evidence.receipt !== AndroidProjectionCallbackUnregistrationReceipt
            ) {
                null
            } else {
                unregistration.returnCell.disposition
            }
        } ?: return null
        val outcome = AndroidProjectionCallbackCleanupOutcome.UnregistrationReturned(
            owner = this,
            registrationOperation = registrationOperation,
            registrationOwnerBag = ownerBag,
            unregistrationOperation = unregistration,
            unregistrationOwnerBag = unregistrationOwnerBag,
            returnDisposition = returnDisposition,
        )
        callbackCleanupOutcome.compareAndSet(null, outcome)
        return callbackCleanupOutcome.get()
            ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
    }

    internal fun submitProjectionCallbackUnregistration(
        operation: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidProjectionCallbackUnregistrationOwnerBag ?: return false
        if (callbackUnregistrationOperation.get() !== operation) return false
        return laneRuntime.post(ownerBag.postTicket) == AndroidPostResult.Accepted
    }

    internal fun createVirtualDisplayReleaseOperation(
        operationIdentity: Long,
    ): AndroidVirtualDisplayReleasePreparationResult? {
        require(operationIdentity > 0L)
        if (!isProjectionCallbackCleanupComplete()) return null
        virtualDisplayMutations.sealTerminalCutoff()
        if (virtualDisplayMutations.hasUnsettledOperation) return null
        val creationRoot = when (val slot = virtualDisplayCreationSlot.get()) {
            is AndroidVirtualDisplayCreationSlot.Owned -> slot.root
            is AndroidVirtualDisplayCreationSlot.ClosedOwned -> slot.root
            is AndroidVirtualDisplayCreationSlot.Preparing,
            is AndroidVirtualDisplayCreationSlot.ClosedPreparing,
            is AndroidVirtualDisplayCreationSlot.CommitInFlight,
            is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight,
            is AndroidVirtualDisplayCreationSlot.RetiredUnused,
            is AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused,
            AndroidVirtualDisplayCreationSlot.OpenEmpty,
            AndroidVirtualDisplayCreationSlot.ClosedEmpty,
                -> return null
        }
        val creationOperation = creationRoot.operation
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = { currentVirtualDisplayCreationOperation() },
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            occurrenceProof = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag)
                .occurrenceNoEntryProof,
            ticket = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag).postTicket,
            ownerCutoffProof = creationRoot.ownerCutoffProof,
        )
        if (virtualDisplayCreationNoPlatformEntryProof.get() != null || creationOperation.settlementGate.withLock {
                creationOperation.returnCell.disposition == OperationReturnDisposition.Empty
            }
        ) return null
        val ownership = virtualDisplayOwner.get() ?: return null
        val claim = AndroidVirtualDisplayReleaseClaim(operationIdentity, ownership)
        val preparing = AndroidVirtualDisplayReleaseSlot.Preparing(claim)
        if (!virtualDisplayReleaseSlot.compareAndSet(AndroidVirtualDisplayReleaseSlot.Empty, preparing)) return null
        try {
            val mode = when (ownership) {
                is AndroidAttachedVirtualDisplay -> {
                    val candidate = ownership.target.prepareInitialVirtualDisplayReleasePort(operationIdentity)
                    check(claim.root(candidate) && claim.owns(candidate))
                    AndroidVirtualDisplayReleaseMode.Attached(ownership, candidate)
                }

                is AndroidAttachmentUncertainVirtualDisplay -> {
                    val candidate = ownership.target.prepareInitialVirtualDisplayReleasePort(operationIdentity)
                    check(claim.root(candidate) && claim.owns(candidate))
                    AndroidVirtualDisplayReleaseMode.AttachmentUncertain(ownership, candidate)
                }

                is AndroidMechanicallyDetachedVirtualDisplay ->
                    AndroidVirtualDisplayReleaseMode.MechanicallyDetached(ownership)
            }
            val ownerBag = AndroidVirtualDisplayReleaseOwnerBag(mode)
            val operation = cleanupOccurrence(
                identity = operationIdentity,
                evidence = AndroidVirtualDisplayReleaseEvidence(),
                ownerBag = ownerBag,
            )
            when (mode) {
                is AndroidVirtualDisplayReleaseMode.Attached -> {
                    check(ownerBag.bindOperation(operation))
                }

                is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> {
                    check(ownerBag.bindOperation(operation))
                }

                is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> check(ownerBag.bindOperation(operation))
            }
            val ticket = laneRuntime.ticket(
                occurrence = operation,
                postRejectionMessage = "Android VirtualDisplay release rejected",
                enteredWork = AndroidEnteredWork {
                    try {
                        check(virtualDisplayOwner.get() === ownerBag.mode.ownership)
                        ownerBag.virtualDisplay.release()
                        operation.publishNormalReturn()
                    } catch (failure: Exception) {
                        operation.publishThrownReturn(failure)
                    }
                    signalBestEffort()
                },
            )
            check(ownerBag.bindPostTicket(ticket))
            val root = AndroidVirtualDisplayReleaseRoot(claim, operation)
            val owned = resolveVirtualDisplayReleaseRoot(preparing, claim, root)
            return when (mode) {
                is AndroidVirtualDisplayReleaseMode.Attached -> when (val commit = checkNotNull(
                    mode.ownership.target.commitInitialVirtualDisplayReleasePort(
                        mode.targetCandidate,
                        ownerBag.binding,
                    ),
                )) {
                    is io.screenstream.engine.internal.target.TargetInitialReleasePortCommittedFact -> {
                        check(ownerBag.recordTargetCommit(commit))
                        AndroidVirtualDisplayReleasePreparationResult.Ready(operation)
                    }

                    is io.screenstream.engine.internal.target.TargetInitialReleasePortRetiredUnusedFact -> {
                        check(ownerBag.recordTargetCommit(commit))
                        check(operation.settlementGate.withLock { operation.settleInertBeforeEntryLocked() })
                        check(retireVirtualDisplayReleaseRoot(owned, root))
                        AndroidVirtualDisplayReleasePreparationResult.RetiredUnused(operation, commit)
                    }

                    else -> error("Unexpected initial release-port commit result")
                }

                is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> when (val commit = checkNotNull(
                    mode.ownership.target.commitInitialVirtualDisplayReleasePort(
                        mode.targetCandidate,
                        ownerBag.binding,
                    ),
                )) {
                    is io.screenstream.engine.internal.target.TargetInitialReleasePortCommittedFact -> {
                        check(ownerBag.recordTargetCommit(commit))
                        AndroidVirtualDisplayReleasePreparationResult.Ready(operation)
                    }

                    is io.screenstream.engine.internal.target.TargetInitialReleasePortRetiredUnusedFact -> {
                        check(ownerBag.recordTargetCommit(commit))
                        check(operation.settlementGate.withLock { operation.settleInertBeforeEntryLocked() })
                        check(retireVirtualDisplayReleaseRoot(owned, root))
                        AndroidVirtualDisplayReleasePreparationResult.RetiredUnused(operation, commit)
                    }

                    else -> error("Unexpected initial release-port commit result")
                }

                is AndroidVirtualDisplayReleaseMode.MechanicallyDetached ->
                    AndroidVirtualDisplayReleasePreparationResult.Ready(operation)
            }
        } catch (raw: Throwable) {
            if (virtualDisplayReleaseSlot.get() === preparing && !claim.hasReturnedCandidate) {
                resolveVirtualDisplayReleasePreparationFailure(preparing, claim)
            }
            throw raw
        }
    }

    internal fun submitVirtualDisplayRelease(operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return false
        val root = (virtualDisplayReleaseSlot.get() as? AndroidVirtualDisplayReleaseSlot.Owned)?.root ?: return false
        if (root.operation !== operation) return false
        val hasTargetAuthority = when (ownerBag.mode) {
            is AndroidVirtualDisplayReleaseMode.Attached,
            is AndroidVirtualDisplayReleaseMode.AttachmentUncertain ->
                ownerBag.targetCommit is io.screenstream.engine.internal.target.TargetInitialReleasePortCommittedFact

            is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> true
        }
        return hasTargetAuthority && laneRuntime.post(ownerBag.postTicket) == AndroidPostResult.Accepted
    }

    internal fun claimVirtualDisplayReleasePlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
    ): AndroidTargetPlatformResult.ProducerDetached? = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock null
        if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            ownerBag.mode is AndroidVirtualDisplayReleaseMode.MechanicallyDetached
        ) {
            return@withLock null
        }
        operation.returnCell.evidence.selectedPlatformResult?.let { return@withLock it }
        ownerBag.result.also { check(operation.returnCell.evidence.recordSelectedPlatformResultLocked(it)) }
    }

    internal fun applyAttachedVirtualDisplayReleaseTargetResult(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val targetResult = result as? io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.Detach
            ?: return@withLock false
        val fact = targetResult.receipt
        val targetPort = when (val mode = ownerBag.mode) {
            is AndroidVirtualDisplayReleaseMode.Attached -> mode.targetCandidate.port
            is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> mode.targetCandidate.port
            is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> return@withLock false
        }
        val evidence = operation.returnCell.evidence
        if (currentVirtualDisplayReleaseOperation() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.selectedPlatformResult !== ownerBag.result ||
            evidence.appliedTargetFact != null ||
            fact.operationIdentity != operation.identity || fact.provenance !== targetPort.provenance
        ) {
            return@withLock false
        }
        if (virtualDisplayOwner.get() !== ownerBag.mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(ownerBag.mode.ownership, null)) {
            check(evidence.recordAppliedTargetFactLocked(fact))
            evidence.recordClearedLocked(ownerBag.mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun completeMechanicallyDetachedVirtualDisplayRelease(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val mode = ownerBag.mode as? AndroidVirtualDisplayReleaseMode.MechanicallyDetached ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (currentVirtualDisplayReleaseOperation() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.clearedOwnership != null ||
            evidence.collisionObserved
        ) {
            return@withLock false
        }
        if (virtualDisplayOwner.get() !== mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(mode.ownership, null)) {
            evidence.recordClearedLocked(mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun createProjectionStopOperation(operationIdentity: Long): OperationOccurrence<AndroidProjectionStopEvidence>? {
        require(operationIdentity > 0L)
        if (!projectionStopObligation.hasSealedTerminalContext ||
            !isProjectionCallbackCleanupComplete() || !isVirtualDisplayCleanupComplete() ||
            projectionStopObligation.closureReceipt() != null
        ) {
            return null
        }
        val operation = projectionStopObligation.createOperation()
        check(operation.identity == operationIdentity)
        return if (projectionStopOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitProjectionStop(
        operation: OperationOccurrence<AndroidProjectionStopEvidence>,
    ): Boolean {
        if (projectionStopOperation.get() !== operation) return false
        val ticket = laneRuntime.ticket(
            occurrence = operation,
            postRejectionMessage = "Android projection stop rejected",
            enteredWork = AndroidEnteredWork { projectionStopObligation.invokeNormal(operation, checkNotNull(
                (operation.ownerBag as AndroidProjectionStopOwnerBag).normalTicket,
            )) },
        )
        val boundProof = AndroidProjectionStopBoundNormalRouteProof(
            operation.ownerBag as AndroidProjectionStopOwnerBag,
            ticket,
        )
        val boundState = AndroidProjectionStopNormalRouteAdmissionState.Bound(boundProof)
        if (!projectionStopObligation.bindNormalTicket(operation, ticket, boundProof, boundState)) return false
        return laneRuntime.post(ticket) == AndroidPostResult.Accepted
    }

    internal fun requestLaneQuitSafely(): Boolean {
        val operation = projectionStopOperation.get() ?: return false
        val stopReturned = operation.settlementGate.withLock {
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        }
        if (!stopReturned && projectionStopObligation.laneNeverStartedProof() == null &&
            laneTerminationReceipt == null &&
            !projectionStopObligation.allowsLaneRetirementAfterAuthoritativeRejection(operation)
        ) return false
        return laneRuntime.requestQuitSafely()
    }

    internal val isLaneQuitReady: Boolean
        get() = projectionStopOperation.get() != null

    internal val projectionClosureReceipt: AndroidProjectionClosureReceipt?
        get() = projectionStopObligation.closureReceipt()

    internal fun sealProjectionStopTerminalContext(
        cutoffIdentity: Any,
        workManifestIdentity: Any,
    ) {
        val cutoff = projectionStopObligation.sealTerminalCutoff(cutoffIdentity)
        projectionStopObligation.sealWorkManifest(cutoff, workManifestIdentity)
    }

    internal fun prepareFinalProjectionStopAction(): AndroidFinalProjectionStopAction? {
        if (!isProjectionCallbackCleanupComplete() || !isVirtualDisplayCleanupComplete()) return null
        val exactOperation = projectionStopOperation.get() ?: projectionStopObligation.createOperation().also {
            projectionStopOperation.compareAndSet(null, it)
        }
        if (projectionStopOperation.get() !== exactOperation) return null
        val prerequisites = AndroidProjectionStopPrerequisitesProof(
            projectionStopObligation,
            this,
            currentCallbackCleanupEvidenceIdentity(),
            currentVirtualDisplayCleanupEvidenceIdentity(),
        )
        val neverStarted = projectionStopObligation.laneNeverStartedProof()
        val finalReceipt = if (neverStarted == null) laneTerminationReceipt ?: return null else null
        return projectionStopObligation.prepareFinalAction(prerequisites, neverStarted, finalReceipt)
    }

    internal fun acceptsProjectionStopPrerequisites(
        proof: AndroidProjectionStopPrerequisitesProof,
    ): Boolean = proof.obligation === projectionStopObligation && proof.androidOwner === this &&
            isProjectionCallbackCleanupComplete() && isVirtualDisplayCleanupComplete() &&
            proof.callbackEvidenceIdentity === currentCallbackCleanupEvidenceIdentity() &&
            proof.virtualDisplayEvidenceIdentity === currentVirtualDisplayCleanupEvidenceIdentity()

    private fun currentCallbackCleanupEvidenceIdentity(): Any = callbackCleanupOutcome.get()
        ?: callbackUnregistrationOperation.get()
        ?: callbackRegistrationNoPlatformEntryProof.get()
        ?: currentProjectionCallbackOperation()
        ?: this

    private fun currentVirtualDisplayCleanupEvidenceIdentity(): Any = currentVirtualDisplayReleaseOperation()
        ?: (virtualDisplayReleaseSlot.get() as? AndroidVirtualDisplayReleaseSlot.Preparing)?.claim
        ?: virtualDisplayCreationNoPlatformEntryProof.get()
        ?: currentVirtualDisplayCreationOperation()
        ?: when (val slot = virtualDisplayCreationSlot.get()) {
            is AndroidVirtualDisplayCreationSlot.Preparing -> slot.claim
            is AndroidVirtualDisplayCreationSlot.ClosedPreparing -> slot.claim
            else -> null
        }
        ?: this

    private fun authoritativeApi34To37CallbackCurrentness(): AndroidProjectionCallbackCurrentness? =
        when (Build.VERSION.SDK_INT) {
            in Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Build.VERSION_CODES.CINNAMON_BUN ->
                authoritativeCallbackCurrentness()

            else -> null
        }

    private fun authoritativeCallbackCurrentness(): AndroidProjectionCallbackCurrentness? {
        if (!isProjectionCallbackAuthorityOpen || !isProjectionCallbackRegistered) return null
        val root = currentProjectionCallbackRoot() ?: return null
        val registrationOperation = root.operation
        val provenance = root.provenance
        return AndroidProjectionCallbackCurrentness(
            owner = this,
            projection = projection,
            registrationOperation = registrationOperation,
            callback = projectionCallback,
            provenance = provenance,
        )
    }

    private fun isExactCallbackCurrent(currentness: AndroidProjectionCallbackCurrentness): Boolean =
        currentness.owner === this &&
                currentness.projection === projection &&
                currentness.callback === projectionCallback &&
                isProjectionCallbackAuthorityOpen &&
                isProjectionCallbackRegistered &&
                currentProjectionCallbackOperation() === currentness.registrationOperation &&
                currentProjectionCallbackRoot()?.provenance === currentness.provenance

    private fun nextExactCallbackOccurrence(
        currentness: AndroidProjectionCallbackCurrentness,
    ): AndroidProjectionCallbackOccurrence? {
        val sequence = nextCallbackSequence() ?: return null
        if (!isExactCallbackCurrent(currentness)) return null
        return AndroidProjectionCallbackOccurrence(currentness, sequence)
    }

    private fun nextCallbackSequence(): Long? {
        while (true) {
            val current = callbackSequence.get()
            if (current == Long.MAX_VALUE) {
                closeProjectionCallbackAuthority()
                signalBestEffort()
                return null
            }
            val next = current + 1L
            if (callbackSequence.compareAndSet(current, next)) return next
        }
    }

    private fun isProjectionCallbackCleanupComplete(): Boolean {
        return projectionCallbackCleanupOutcome(currentProjectionCallbackOperation()) != null
    }

    private fun exactProjectionCallbackNoEntryRouteLocked(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
        ownerBag: AndroidProjectionCallbackRegistrationOwnerBag,
        proof: AndroidNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>,
    ): AndroidProjectionCallbackNoPlatformEntryRoute? {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (currentProjectionCallbackOperation() !== operation || proof.operation !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return null
        }
        val ticket = ownerBag.postTicket ?: return null
        if (!exactProjectionCallbackTicket(ticket, operation)) return null
        if (operation.submissionDisposition == OperationSubmissionDisposition.None) {
            return AndroidProjectionCallbackNoPlatformEntryRoute.AuthoritativePreSubmissionCutoff.takeIf {
                exactProjectionCallbackTicket(ticket, operation) &&
                    (proof === currentProjectionCallbackRoot()?.ownerCutoffProof ||
                        proof === ticket.authoritativePostCutoffProof &&
                        ticket.authoritativePostCutoffProof.isActivatedExact) &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    ticket.postFailureResidue == null &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.Cancelled
            }
        }
        if (operation.submissionDisposition == OperationSubmissionDisposition.Rejected) {
            return AndroidProjectionCallbackNoPlatformEntryRoute.SchedulerRejected.takeIf {
                ticket.postFailureResidue != null &&
                    operation.submissionFailure === ticket.postFailureResidue &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.SchedulerRejected &&
                    (ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                        ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                        proof === ownerBag.occurrenceNoEntryProof ||
                        proof is AndroidFinalLaneNoEntryProof && proof.ticket === ticket &&
                        proof.lane === laneRuntime && proof.operation === operation &&
                        laneRuntime.terminationReceipt === proof.terminationReceipt)
            }
        }
        if (operation.submissionDisposition != OperationSubmissionDisposition.Accepted ||
            operation.entryDisposition != OperationEntryDisposition.Cancelled ||
            operation.disposition != OperationDisposition.Cancelled &&
            operation.disposition != OperationDisposition.DeadlineGuardFailed
        ) return null
        val exactAcceptedProof = when (proof) {
            is AndroidReturnedWithoutPlatformEntryProof ->
                proof.ticket === ticket && proof.operation === operation &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.Returned

            is AndroidFinalLaneNoEntryProof ->
                ticket === proof.ticket && proof.operationIdentity == operation.identity &&
                    proof.lane === laneRuntime && proof.workerIdentity === ticket.workerIdentity &&
                    laneRuntime.terminationReceipt === proof.terminationReceipt &&
                    laneRuntime.acceptsTerminationReceipt(proof.terminationReceipt) &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    ticket.postFailureResidue == null && operation.submissionFailure == null

            else -> proof === ownerBag.occurrenceNoEntryProof &&
                ticket.physicalState == AndroidPostPhysicalDisposition.Returned &&
                ticket.postFailureResidue == null && operation.submissionFailure == null &&
                operation.submissionAmbiguousFatal == null
        }
        return AndroidProjectionCallbackNoPlatformEntryRoute.AcceptedDefinitelyUnentered.takeIf {
            exactAcceptedProof
        }
    }

    private fun exactProjectionCallbackTicket(
        ticket: AndroidPostTicket<AndroidProjectionCallbackRegistrationEvidence>?,
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean = ticket != null && ticket.lane === laneRuntime && ticket.occurrence === operation &&
            ticket.operationIdentity == operation.identity && ticket.workerIdentity.lane === laneRuntime &&
            ticket.terminationReceipt.lane === laneRuntime &&
            ticket.terminationReceipt.matchesWorker(ticket.workerIdentity)

    private fun acceptsProjectionCallbackCleanupOutcome(
        outcome: AndroidProjectionCallbackCleanupOutcome,
        registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?,
    ): Boolean = when (outcome) {
        is AndroidProjectionCallbackCleanupOutcome.StructurallyInapplicable ->
            outcome.owner === this && registrationOperation == null && currentProjectionCallbackOperation() == null

        is AndroidProjectionCallbackCleanupOutcome.RegistrationDidNotEnterPlatform ->
            outcome.owner === this && outcome.operation === registrationOperation &&
                currentProjectionCallbackOperation() === outcome.operation &&
                outcome.ownerBag === outcome.operation.ownerBag && outcome.proof.operation === outcome.operation

        is AndroidProjectionCallbackCleanupOutcome.UnregistrationReturned ->
            outcome.owner === this && outcome.registrationOperation === registrationOperation &&
                currentProjectionCallbackOperation() === outcome.registrationOperation &&
                callbackUnregistrationOperation.get() === outcome.unregistrationOperation &&
                outcome.registrationOwnerBag === outcome.registrationOperation.ownerBag &&
                outcome.unregistrationOwnerBag === outcome.unregistrationOperation.ownerBag
    }

    private fun isVirtualDisplayCleanupComplete(): Boolean {
        if (virtualDisplayMutations.hasUnsettledOperation) return false
        if (virtualDisplayReleaseSlot.get() is AndroidVirtualDisplayReleaseSlot.Preparing) return false
        if (virtualDisplayCreationSlot.get() is AndroidVirtualDisplayCreationSlot.Preparing ||
            virtualDisplayCreationSlot.get() is AndroidVirtualDisplayCreationSlot.ClosedPreparing ||
            virtualDisplayCreationSlot.get() is AndroidVirtualDisplayCreationSlot.CommitInFlight ||
            virtualDisplayCreationSlot.get() is AndroidVirtualDisplayCreationSlot.ClosedCommitInFlight
        ) return false
        val creationOperation = currentVirtualDisplayCreationOperation() ?: return true
        if (isRetiredUnusedVirtualDisplayCreationComplete(creationOperation)) return true
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = { currentVirtualDisplayCreationOperation() },
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            occurrenceProof = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag)
                .occurrenceNoEntryProof,
            ticket = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag).postTicket,
            ownerCutoffProof = currentVirtualDisplayCreationRoot()?.ownerCutoffProof,
        )
        if (virtualDisplayCreationNoPlatformEntryProof.get() == null && creationOperation.settlementGate.withLock {
                creationOperation.returnCell.disposition == OperationReturnDisposition.Empty
            }
        ) return false
        if (virtualDisplayCreationNoPlatformEntryProof.get() != null) return true

        val releaseOperation = currentVirtualDisplayReleaseOperation()
            ?: return creationOperation.settlementGate.withLock {
                val evidence = creationOperation.returnCell.evidence
                creationOperation.returnCell.disposition != OperationReturnDisposition.Empty &&
                        evidence.returnedOwnerDisposition == AndroidVirtualDisplayReturnedOwnerDisposition.Empty &&
                        evidence.returnedVirtualDisplay == null
            }

        return releaseOperation.settlementGate.withLock {
            if (currentVirtualDisplayReleaseOperation() !== releaseOperation) return@withLock false
            val evidence = releaseOperation.returnCell.evidence
            when (releaseOperation.returnCell.disposition) {
                OperationReturnDisposition.Empty -> false
                OperationReturnDisposition.Thrown -> true
                OperationReturnDisposition.Normal -> {
                    val mode = (releaseOperation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag)?.mode
                        ?: return@withLock false
                    val ownershipSettled = evidence.clearedOwnership === mode.ownership || evidence.collisionObserved
                    when (mode) {
                        is AndroidVirtualDisplayReleaseMode.Attached ->
                            evidence.appliedTargetFact != null && ownershipSettled

                        is AndroidVirtualDisplayReleaseMode.AttachmentUncertain ->
                            evidence.appliedTargetFact != null && ownershipSettled

                        is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> ownershipSettled
                    }
                }
            }
        }
    }

    private fun isRetiredUnusedVirtualDisplayCreationComplete(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): Boolean {
        val slot = virtualDisplayCreationSlot.get()
        val root = when (slot) {
            is AndroidVirtualDisplayCreationSlot.RetiredUnused -> slot.root
            is AndroidVirtualDisplayCreationSlot.ClosedRetiredUnused -> slot.root
            else -> return false
        }
        if (root.operation !== operation || virtualDisplayOwner.get() != null) return false
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        return operation.settlementGate.withLock {
            currentVirtualDisplayCreationRoot() === root &&
                ownerBag.targetCommit is io.screenstream.engine.internal.target.TargetProducerPortRetiredUnusedFact &&
                operation.submissionDisposition == OperationSubmissionDisposition.None &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.evidence.returnedVirtualDisplay == null &&
                operation.returnCell.evidence.returnedOwnerDisposition ==
                AndroidVirtualDisplayReturnedOwnerDisposition.Empty
        }
    }

    private fun <R : OperationEvidence> publishNoPlatformEntryIfProven(
        operation: OperationOccurrence<R>,
        retainedOperation: () -> OperationOccurrence<R>?,
        noPlatformEntryProof: AtomicReference<AndroidNoPlatformEntryProof<R>?>,
        occurrenceProof: AndroidOccurrenceNoPlatformEntryProof<R>,
        ticket: AndroidPostTicket<R>?,
        ownerCutoffProof: AndroidOwnerPostCutoffProof<R>?,
    ): AndroidNoPlatformEntryProof<R>? {
        noPlatformEntryProof.get()?.let { return it }
        if (retainedOperation() !== operation || occurrenceProof.operation !== operation) return null

        return operation.settlementGate.withLock {
            noPlatformEntryProof.get()?.let { return@withLock it }
            if (retainedOperation() !== operation ||
                occurrenceProof.operation !== operation ||
                operation.returnCell.disposition != OperationReturnDisposition.Empty
            ) {
                return@withLock null
            }

            val authoritativeCutoffProof: AndroidNoPlatformEntryProof<R>? =
                ticket?.authoritativePostCutoffProof
                    ?.takeIf { proof -> proof.operation === operation && proof.activateLocked() }
                    ?: ownerCutoffProof?.takeIf { proof -> proof.operation === operation && proof.activateLocked() }
            val returnedWithoutEntryProof = ticket?.returnedWithoutPlatformEntryProof
                ?.takeIf { proof -> proof.operation === operation && proof.activateLocked() }
            val noPlatformEntryProven = authoritativeCutoffProof != null || returnedWithoutEntryProof != null ||
                when (operation.entryDisposition) {
                OperationEntryDisposition.Cancelled -> when (operation.disposition) {
                    OperationDisposition.Cancelled ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
                            ticket?.physicalState == AndroidPostPhysicalDisposition.Returned &&
                            ticket.postFailureResidue == null &&
                            ticket.failureExposure == AndroidPostFailureExposure.None &&
                            operation.submissionFailure == null && operation.submissionAmbiguousFatal == null

                    OperationDisposition.SchedulerRejected ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                            ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                            ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                            ticket.postFailureResidue != null &&
                            operation.submissionFailure === ticket.postFailureResidue

                    OperationDisposition.DeadlineGuardFailed ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
                            ticket?.physicalState == AndroidPostPhysicalDisposition.Returned &&
                            ticket.postFailureResidue == null &&
                            ticket.failureExposure == AndroidPostFailureExposure.None &&
                            operation.submissionFailure == null && operation.submissionAmbiguousFatal == null

                    else -> false
                }

                OperationEntryDisposition.Unentered -> when (operation.disposition) {
                    OperationDisposition.SchedulerRejected ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                                ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                                ticket?.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                                operation.settleInertBeforeEntryLocked()

                    else -> false
                }

                OperationEntryDisposition.Entered -> false
            }
            if (!noPlatformEntryProven) return@withLock null

            noPlatformEntryProof.compareAndSet(
                null,
                authoritativeCutoffProof ?: returnedWithoutEntryProof ?: occurrenceProof,
            )
            noPlatformEntryProof.get()
        }
    }

    private fun <R : OperationEvidence> foldFinalLaneNoEntry(
        receipt: AndroidLaneTerminationReceipt,
        operation: OperationOccurrence<R>?,
        retainedOperation: () -> OperationOccurrence<R>?,
        noPlatformEntryProof: AtomicReference<AndroidNoPlatformEntryProof<R>?>,
        ticket: (OperationOwnerBag) -> AndroidPostTicket<R>?,
    ) {
        if (operation == null || retainedOperation() !== operation || noPlatformEntryProof.get() != null) return
        val retainedTicket = ticket(operation.ownerBag) ?: return
        operation.settlementGate.withLock {
            if (retainedOperation() !== operation || noPlatformEntryProof.get() != null) return@withLock
            val proof = laneRuntime.proveFinalLaneNoEntryLocked(receipt, retainedTicket, operation) ?: return@withLock
            noPlatformEntryProof.compareAndSet(null, proof)
        }
    }

    private fun foldFinalListenerInstallationNoEntry(
        receipt: AndroidLaneTerminationReceipt,
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>?,
    ) {
        if (operation == null || targetListenerInstallationFinalLaneNoEntryProof.get() != null) return
        if (targetListenerInstallationBoundNeverSubmittedProof(operation) != null) return
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return
        if (boundRoot.exactCommittedCapability() == null) return
        val retainedTicket = boundRoot.ticket
        operation.settlementGate.withLock {
            if (targetListenerInstallationBoundRoot.get() !== boundRoot ||
                boundRoot.activatedNoPlatformEntryOutcomeLocked() != null ||
                targetListenerInstallationFinalLaneNoEntryProof.get() != null
            ) {
                return@withLock
            }
            val proof = laneRuntime.proveFinalLaneNoEntryLocked(receipt, retainedTicket, operation) ?: return@withLock
            targetListenerInstallationFinalLaneNoEntryProof.compareAndSet(null, proof)
        }
    }

    private fun <R : OperationEvidence> finiteOccurrence(
        identity: AndroidFiniteOperationIdentity,
        evidence: R,
        ownerBag: OperationOwnerBag
    ): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity.operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
            deadlineIdentity = identity.deadlineIdentity,
            deadlineDurationNanos = androidEnteredOperationSafetyNanos,
            initialWakeGeneration = identity.deadlineWakeGeneration,
            timeoutCause = identity.timeoutCause,
            wakeSignal = settlementSignal,
        )

    private fun <R : OperationEvidence> cleanupOccurrence(identity: Long, evidence: R, ownerBag: OperationOwnerBag): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
        ).also { occurrence ->
            check(occurrence.arbitrateTerminal(mandatoryCleanup = true) == OperationTerminalArbitration.Transferred)
        }

    private fun executeVirtualDisplayCreation(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        ownerBag: AndroidVirtualDisplayCreationOwnerBag,
    ) {
        try {
            var returnedDisplay: VirtualDisplay? = null
            var directCreateOutOfMemoryError: OutOfMemoryError? = null
            val rawCallEntered = ownerBag.port.withSurface { surface ->
                val createdDisplay = try {
                    projection.createVirtualDisplay(
                        "ScreenCaptureEngine",
                        ownerBag.widthPx,
                        ownerBag.heightPx,
                        ownerBag.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null,
                    )
                } catch (error: OutOfMemoryError) {
                    val recorded = operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordDirectCreateOutOfMemoryLocked(error)
                    }
                    if (!recorded) throw ownerBag.directCreateOutOfMemoryRecordFailure
                    directCreateOutOfMemoryError = error
                    return@withSurface
                }
                if (createdDisplay != null) {
                    val returnedOwnerRooted = operation.settlementGate.withLock {
                        operation.returnCell.evidence.rootReturnedVirtualDisplayLocked(createdDisplay) &&
                                ownerBag.applicationCandidate.mechanicalState.recordCreationReturnedLocked(
                                    ownerBag.initialLogicalTuple,
                                )
                    }
                    check(returnedOwnerRooted)
                }
                returnedDisplay = createdDisplay
            }
            check(rawCallEntered == TargetPortUseOutcome.BodyReturned)
            val directCreateFailure = directCreateOutOfMemoryError
            if (directCreateFailure != null) {
                publishVirtualDisplayCreationThrown(operation, directCreateFailure)
            } else {
                publishVirtualDisplayCreationReturn(operation, returnedDisplay)
            }
        } catch (failure: Exception) {
            publishVirtualDisplayCreationThrown(operation, failure)
        } finally {
            signalBestEffort()
        }
    }

    private fun executeTargetListenerRemoval(
        operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
        ownerBag: AndroidTargetListenerRemovalOwnerBag,
        handler: Handler,
    ) {
        try {
            check(
                ownerBag.port.withSurfaceTexture { surfaceTexture ->
                    surfaceTexture.setOnFrameAvailableListener(null, handler)
                } == TargetPortUseOutcome.BodyReturned,
            )
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordListenerRemovalReturnLocked()
            })
            val removalTargetResult = checkNotNull(
                ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.removalReturnedResult),
            ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalReturned
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordRemovalReturnedTargetResultLocked(removalTargetResult)
            })
            val settledTargetResult = checkNotNull(
                ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.removalSettledResult),
            ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalSettled
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordSettledTargetResultLocked(settledTargetResult)
            })
            try {
                laneRuntime.post(ownerBag.sentinelTicket)
            } catch (raw: Throwable) {
                check(operation.settlementGate.withLock {
                    reconcileSentinelPostOutcomeLocked(operation, ownerBag, ownerBag.sentinelPostExposed)
                })
                throw raw
            }
            val sentinelDisposition = ownerBag.sentinelTicket.mechanicalDisposition
            val sentinelOutcome = when (sentinelDisposition) {
                AndroidListenerSentinelMechanicalDisposition.Accepted,
                AndroidListenerSentinelMechanicalDisposition.AwaitingEntry,
                    -> ownerBag.sentinelPostExposed
                AndroidListenerSentinelMechanicalDisposition.Pending,
                AndroidListenerSentinelMechanicalDisposition.RejectedFinal,
                AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered,
                    -> ownerBag.sentinelDefinitelyUnentered
                }
            check(operation.settlementGate.withLock {
                reconcileSentinelPostOutcomeLocked(operation, ownerBag, sentinelOutcome)
            })
            when (sentinelDisposition) {
                AndroidListenerSentinelMechanicalDisposition.Accepted,
                AndroidListenerSentinelMechanicalDisposition.Pending,
                    -> operation.publishNormalReturn()
                AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered -> {
                    val exactFailure = ownerBag.sentinelTicket.postFailureResidue
                    if (exactFailure is Exception) operation.publishThrownReturn(exactFailure)
                    else operation.publishNormalReturn()
                }
                AndroidListenerSentinelMechanicalDisposition.RejectedFinal -> {
                    val exactFailure = ownerBag.sentinelTicket.postFailureResidue
                    if (exactFailure is Exception) operation.publishThrownReturn(exactFailure)
                }
                AndroidListenerSentinelMechanicalDisposition.AwaitingEntry -> Unit
            }
        } catch (failure: Exception) {
            operation.publishThrownReturn(failure)
        }
        signalBestEffort()
    }

    private fun reconcileSentinelPostOutcomeLocked(
        operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
        ownerBag: AndroidTargetListenerRemovalOwnerBag,
        proposed: AndroidListenerSentinelPostOutcome,
    ): Boolean {
        val evidence = operation.returnCell.evidence
        val exactFailure = ownerBag.sentinelTicket.postFailureResidue
        return when (val current = evidence.sentinelPostOutcome) {
            null -> evidence.recordSentinelPostOutcomeLocked(proposed, exactFailure)
            proposed -> evidence.sentinelPostFailureResidue === exactFailure
            ownerBag.sentinelPostExposed ->
                proposed === ownerBag.sentinelDefinitelyUnentered &&
                    evidence.refineSentinelPostOutcomeToDefinitelyUnenteredLocked(
                        ownerBag.sentinelPostExposed,
                        ownerBag.sentinelDefinitelyUnentered,
                        exactFailure,
                    )
            ownerBag.sentinelDefinitelyUnentered ->
                proposed === ownerBag.sentinelPostExposed &&
                    evidence.sentinelPostFailureResidue === exactFailure
            else -> false
        }
    }

    private fun publishVirtualDisplayCreationReturn(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        returnedDisplay: VirtualDisplay?,
    ): Boolean {
        val sampleNanos = clock.nowNanos()
        return operation.settlementGate.withLock {
            if (operation.entryDisposition != OperationEntryDisposition.Entered) return@withLock false
            operation.returnCell.evidence.armOrRetireInitialResizeLocked(
                sampleNanos = sampleNanos,
                returnedDisplayPresent = returnedDisplay != null,
            )
            operation.returnCell.publishNormalLocked(sampleNanos)
        }
    }

    private fun publishVirtualDisplayCreationThrown(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        thrown: Throwable,
    ): Boolean = operation.settlementGate.withLock {
        if (operation.entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        operation.returnCell.evidence.retireInitialResizeLocked()
        when (thrown) {
            is Exception -> operation.publishThrownReturn(thrown)
            is OutOfMemoryError -> {
                check(operation.returnCell.evidence.directCreateOutOfMemoryError === thrown)
                operation.returnCell.publishThrownLocked(clock.nowNanos(), thrown)
            }

            else -> operation.publishDirectFatalReturnLocked(thrown)
        }
    }

    private fun matchesProducerFact(
        fact: TargetProducerApplicationFact,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidSurfacePort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.operationKind == port.operationKind &&
            fact.provenance === port.provenance

    private fun matchesDetachFact(
        fact: TargetProducerDetachReceipt,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidDetachPort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.detachKind == port.detachKind &&
            fact.provenance === port.provenance

    private fun reserveTargetListenerInstallationAdmission(): Boolean =
        targetListenerInstallationAdmissionGate.withLock {
            if (!targetListenerInstallationAdmissionOpen || targetListenerInstallationInFlight == Int.MAX_VALUE) {
                return@withLock false
            }
            targetListenerInstallationInFlight += 1
            true
        }

    private fun releaseTargetListenerInstallationAdmission() {
        targetListenerInstallationAdmissionGate.withLock {
            check(targetListenerInstallationInFlight > 0)
            targetListenerInstallationInFlight -= 1
        }
        signalBestEffort()
    }

    private fun settleTargetListenerInstallationNoPlatformEntry(
        boundRoot: AndroidTargetListenerInstallationBoundRoot,
        schedulerOutcome: AndroidPostResult,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        if (targetListenerInstallationBoundRoot.get() !== boundRoot ||
            boundRoot.exactCommittedCapability() == null || !boundRoot.hasClaimedSubmission
        ) return null
        val operation = boundRoot.operation
        return operation.settlementGate.withLock {
            boundRoot.activatedNoPlatformEntryOutcomeLocked()?.let { return@withLock it }
            when (schedulerOutcome) {
                AndroidPostResult.NotSubmitted -> {
                    boundRoot.ticket.authoritativePostCutoffProof.activateLocked()
                    null
                }

                AndroidPostResult.Rejected -> {
                    val failure = boundRoot.ticket.postFailureResidue as? Exception ?: return@withLock null
                    if (boundRoot.ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
                        boundRoot.ticket.failureExposure != AndroidPostFailureExposure.AuthoritativeRejection ||
                        operation.entryDisposition != OperationEntryDisposition.Unentered ||
                        !operation.settleInertBeforeEntryLocked()
                    ) {
                        return@withLock null
                    }
                    boundRoot.rejectedOutcome.takeIf { it.activateLocked(failure) }
                }

                AndroidPostResult.Accepted -> null
            }
        }
    }

    private fun recoverTargetListenerInstallationNoPlatformEntry(
        boundRoot: AndroidTargetListenerInstallationBoundRoot,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        val operation = boundRoot.operation
        return operation.settlementGate.withLock {
            boundRoot.activatedNoPlatformEntryOutcomeLocked()?.let { return@withLock it }
            val returnedProof = boundRoot.ticket.returnedWithoutPlatformEntryProof
                .takeIf { it.activateLocked() }
            when (operation.submissionDisposition) {
                OperationSubmissionDisposition.Rejected -> {
                    val failure = boundRoot.ticket.postFailureResidue as? Exception ?: return@withLock null
                    if (boundRoot.ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
                        boundRoot.ticket.failureExposure != AndroidPostFailureExposure.AuthoritativeRejection ||
                        operation.entryDisposition != OperationEntryDisposition.Unentered ||
                        !operation.settleInertBeforeEntryLocked()
                    ) {
                        return@withLock null
                    }
                    boundRoot.rejectedOutcome.takeIf { it.activateLocked(failure) }
                }

                OperationSubmissionDisposition.None -> null

                OperationSubmissionDisposition.Accepted -> returnedProof?.let { exactProof ->
                    boundRoot.acceptedInertOutcome.takeIf { it.activateLocked(exactProof) }
                }

                OperationSubmissionDisposition.Submitting,
                OperationSubmissionDisposition.Cancelled,
                    -> null
            }
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
        }
    }
}
