package io.screenstream.engine.internal.android

import android.media.projection.MediaProjection
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal object AndroidProjectionCallbackRegistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackRegistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackRegistrationReceipt =
        AndroidProjectionCallbackRegistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackRegistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag {
    private var fixedOccurrenceNoEntryProof:
        AndroidOccurrenceNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>? = null
    private val fixedPostTicket =
        AtomicReference<AndroidPostTicket<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    private val fixedSchedulerOutcome = AtomicReference<AndroidPostResult?>(null)

    internal val occurrenceNoEntryProof:
        AndroidOccurrenceNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>
        get() = checkNotNull(fixedOccurrenceNoEntryProof)

    internal val postTicket: AndroidPostTicket<AndroidProjectionCallbackRegistrationEvidence>?
        get() = fixedPostTicket.get()

    internal val schedulerOutcome: AndroidPostResult?
        get() = fixedSchedulerOutcome.get()

    internal fun bindOperation(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean {
        if (fixedOccurrenceNoEntryProof != null) return false
        fixedOccurrenceNoEntryProof = AndroidOccurrenceNoPlatformEntryProof(operation)
        return true
    }

    internal fun bindPostTicket(
        ticket: AndroidPostTicket<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean {
        if (ticket.occurrence !== occurrenceNoEntryProof.operation) return false
        return fixedPostTicket.compareAndSet(null, ticket)
    }

    internal fun bindSchedulerOutcome(outcome: AndroidPostResult): Boolean =
        fixedSchedulerOutcome.compareAndSet(null, outcome)
}

internal enum class AndroidProjectionCallbackNoPlatformEntryRoute {
    PreparedButNeverSubmitted,
    SchedulerNotSubmitted,
    SchedulerRejected,
    AcceptedDefinitelyUnentered,
}

/** Android-owned closure of the callback-registration cleanup obligation. */
internal sealed class AndroidProjectionCallbackCleanupOutcome private constructor() {
    internal class StructurallyInapplicable internal constructor(
        internal val owner: AndroidCaptureOwner,
    ) : AndroidProjectionCallbackCleanupOutcome()

    internal class RegistrationDidNotEnterPlatform internal constructor(
        internal val owner: AndroidCaptureOwner,
        internal val operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
        internal val ownerBag: AndroidProjectionCallbackRegistrationOwnerBag,
        internal val proof: AndroidNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>,
        internal val route: AndroidProjectionCallbackNoPlatformEntryRoute,
    ) : AndroidProjectionCallbackCleanupOutcome()

    internal class UnregistrationReturned internal constructor(
        internal val owner: AndroidCaptureOwner,
        internal val registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
        internal val registrationOwnerBag: AndroidProjectionCallbackRegistrationOwnerBag,
        internal val unregistrationOperation: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>,
        internal val unregistrationOwnerBag: AndroidProjectionCallbackUnregistrationOwnerBag,
        internal val returnDisposition: OperationReturnDisposition,
    ) : AndroidProjectionCallbackCleanupOutcome()
}

internal object AndroidProjectionCallbackUnregistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackUnregistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackUnregistrationReceipt =
        AndroidProjectionCallbackUnregistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackUnregistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag

/** Precreated exact receipt that becomes authoritative only on this obligation's physical normal return. */
internal class AndroidProjectionClosureReceipt private constructor(
    internal val obligation: AndroidProjectionStopObligation,
) : OperationReceipt {
    internal companion object {
        internal fun create(obligation: AndroidProjectionStopObligation): AndroidProjectionClosureReceipt =
            AndroidProjectionClosureReceipt(obligation)
    }
}

internal class AndroidProjectionStopEvidence internal constructor(
    obligation: AndroidProjectionStopObligation,
) : OperationEvidence {
    override val receipt: AndroidProjectionClosureReceipt = AndroidProjectionClosureReceipt.create(obligation)
    override val returnedOwner: OperationReturnedOwner? = null
}

internal enum class AndroidProjectionStopRoute {
    NormalHandler,
    FinalControl,
}

internal enum class AndroidProjectionStopCallDisposition {
    Unentered,
    InCall,
    ReturnedNormal,
    ReturnedThrown,
}

internal class AndroidProjectionStopOwnerBag internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val projection: MediaProjection,
) : OperationOwnerBag {
    internal var operation: OperationOccurrence<AndroidProjectionStopEvidence>? = null
        private set
    internal var normalTicket: AndroidPostTicket<AndroidProjectionStopEvidence>? = null
        private set
    internal var route: AndroidProjectionStopRoute? = null
        private set
    internal var callDisposition: AndroidProjectionStopCallDisposition = AndroidProjectionStopCallDisposition.Unentered
        private set
    internal var returnedThrowable: Throwable? = null
        private set

    internal fun bindOperation(value: OperationOccurrence<AndroidProjectionStopEvidence>): Boolean {
        if (operation != null) return false
        operation = value
        return true
    }

    internal fun bindNormalTicket(value: AndroidPostTicket<AndroidProjectionStopEvidence>): Boolean {
        if (normalTicket != null || value.occurrence !== operation) return false
        normalTicket = value
        return true
    }

    internal fun beginCallLocked(value: AndroidProjectionStopRoute): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        if (route != null || callDisposition != AndroidProjectionStopCallDisposition.Unentered ||
            exact.entryDisposition != OperationEntryDisposition.Entered ||
            exact.returnCell.disposition != OperationReturnDisposition.Empty
        ) return false
        route = value
        callDisposition = AndroidProjectionStopCallDisposition.InCall
        return true
    }

    internal fun recordNormalReturnLocked(): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        if (callDisposition != AndroidProjectionStopCallDisposition.InCall) return false
        callDisposition = AndroidProjectionStopCallDisposition.ReturnedNormal
        return true
    }

    internal fun recordThrownReturnLocked(raw: Throwable): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        if (callDisposition != AndroidProjectionStopCallDisposition.InCall) return false
        returnedThrowable = raw
        callDisposition = AndroidProjectionStopCallDisposition.ReturnedThrown
        return true
    }
}

internal class AndroidProjectionStopTerminalCutoffProof private constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val cutoffIdentity: Any,
) {
    internal companion object {
        internal fun create(
            obligation: AndroidProjectionStopObligation,
            cutoffIdentity: Any,
        ): AndroidProjectionStopTerminalCutoffProof =
            AndroidProjectionStopTerminalCutoffProof(obligation, cutoffIdentity)
    }
}

internal class AndroidProjectionStopWorkManifestProof private constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val cutoff: AndroidProjectionStopTerminalCutoffProof,
    internal val manifestIdentity: Any,
) {
    internal companion object {
        internal fun create(
            obligation: AndroidProjectionStopObligation,
            cutoff: AndroidProjectionStopTerminalCutoffProof,
            manifestIdentity: Any,
        ): AndroidProjectionStopWorkManifestProof =
            AndroidProjectionStopWorkManifestProof(obligation, cutoff, manifestIdentity)
    }
}

internal class AndroidProjectionStopPrerequisitesProof internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val androidOwner: AndroidCaptureOwner?,
    internal val callbackEvidenceIdentity: Any,
    internal val virtualDisplayEvidenceIdentity: Any,
)

internal class AndroidLaneNeverStartedProof private constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val lane: AndroidLaneRuntime?,
    internal val runtimeProof: AndroidLaneRuntimeNeverStartedProof?,
) {
    internal companion object {
        internal fun create(
            obligation: AndroidProjectionStopObligation,
            lane: AndroidLaneRuntime?,
            runtimeProof: AndroidLaneRuntimeNeverStartedProof?,
        ): AndroidLaneNeverStartedProof = AndroidLaneNeverStartedProof(obligation, lane, runtimeProof)
    }
}

internal sealed interface AndroidFinalProjectionStopOutcome {
    class Returned internal constructor(internal val receipt: AndroidProjectionClosureReceipt) :
        AndroidFinalProjectionStopOutcome

    class Thrown internal constructor(internal val raw: Throwable) : AndroidFinalProjectionStopOutcome
}

/** Opaque, one-shot final-Control action. It exposes neither projection nor retry authority. */
internal class AndroidFinalProjectionStopAction private constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val operation: OperationOccurrence<AndroidProjectionStopEvidence>,
    internal val ownerBag: AndroidProjectionStopOwnerBag,
    internal val cutoff: AndroidProjectionStopTerminalCutoffProof,
    internal val manifest: AndroidProjectionStopWorkManifestProof,
    internal val prerequisites: AndroidProjectionStopPrerequisitesProof,
    internal val neverStarted: AndroidLaneNeverStartedProof?,
    internal val finalLaneNoEntry: AndroidFinalLaneNoEntryProof<AndroidProjectionStopEvidence>?,
) {
    private val invoked = AtomicBoolean(false)

    internal fun invoke(): AndroidFinalProjectionStopOutcome? {
        if (!invoked.compareAndSet(false, true)) return null
        return obligation.invokeFinal(this)
    }

    internal companion object {
        internal fun create(
            obligation: AndroidProjectionStopObligation,
            operation: OperationOccurrence<AndroidProjectionStopEvidence>,
            ownerBag: AndroidProjectionStopOwnerBag,
            cutoff: AndroidProjectionStopTerminalCutoffProof,
            manifest: AndroidProjectionStopWorkManifestProof,
            prerequisites: AndroidProjectionStopPrerequisitesProof,
            neverStarted: AndroidLaneNeverStartedProof?,
            finalLaneNoEntry: AndroidFinalLaneNoEntryProof<AndroidProjectionStopEvidence>?,
        ): AndroidFinalProjectionStopAction = AndroidFinalProjectionStopAction(
            obligation, operation, ownerBag, cutoff, manifest, prerequisites, neverStarted, finalLaneNoEntry,
        )
    }
}

/** Android-owned single-call arbiter shared by the Handler route and the final-Control route. */
internal class AndroidProjectionStopObligation internal constructor(
    private val projection: MediaProjection,
) {
    private val configuredIdentity = AtomicReference<Long?>(null)
    private val configuredClock = AtomicReference<EngineClock?>(null)
    private val configuredSignal = AtomicReference<SettlementSignal?>(null)
    private val androidOwner = AtomicReference<AndroidCaptureOwner?>(null)
    private val androidLane = AtomicReference<AndroidLaneRuntime?>(null)
    private val operation = AtomicReference<OperationOccurrence<AndroidProjectionStopEvidence>?>(null)
    private val cutoff = AtomicReference<AndroidProjectionStopTerminalCutoffProof?>(null)
    private val manifest = AtomicReference<AndroidProjectionStopWorkManifestProof?>(null)
    private val finalAction = AtomicReference<AndroidFinalProjectionStopAction?>(null)

    internal fun configure(identity: Long, clock: EngineClock, signal: SettlementSignal) {
        require(identity > 0L)
        if (configuredIdentity.compareAndSet(null, identity)) {
            check(configuredClock.compareAndSet(null, clock))
            check(configuredSignal.compareAndSet(null, signal))
        } else {
            check(configuredIdentity.get() == identity && configuredClock.get() === clock && configuredSignal.get() === signal)
        }
    }

    internal fun bindAndroidOwner(owner: AndroidCaptureOwner, lane: AndroidLaneRuntime): MediaProjection {
        check(androidOwner.compareAndSet(null, owner))
        check(androidLane.compareAndSet(null, lane))
        return projection
    }

    internal fun sealTerminalCutoff(cutoffIdentity: Any): AndroidProjectionStopTerminalCutoffProof {
        cutoff.get()?.let {
            check(it.cutoffIdentity === cutoffIdentity)
            return it
        }
        val proof = AndroidProjectionStopTerminalCutoffProof.create(this, cutoffIdentity)
        cutoff.compareAndSet(null, proof)
        return checkNotNull(cutoff.get()).also { check(it.cutoffIdentity === cutoffIdentity) }
    }

    internal fun sealWorkManifest(
        terminal: AndroidProjectionStopTerminalCutoffProof,
        manifestIdentity: Any,
    ): AndroidProjectionStopWorkManifestProof {
        check(terminal === cutoff.get() && terminal.obligation === this)
        manifest.get()?.let {
            check(it.cutoff === terminal && it.manifestIdentity === manifestIdentity)
            return it
        }
        val proof = AndroidProjectionStopWorkManifestProof.create(this, terminal, manifestIdentity)
        manifest.compareAndSet(null, proof)
        return checkNotNull(manifest.get()).also {
            check(it.cutoff === terminal && it.manifestIdentity === manifestIdentity)
        }
    }

    internal val hasSealedTerminalContext: Boolean
        get() {
            val exactCutoff = cutoff.get() ?: return false
            val exactManifest = manifest.get() ?: return false
            return exactCutoff.obligation === this && exactManifest.obligation === this &&
                    exactManifest.cutoff === exactCutoff
        }

    internal fun createOperation(): OperationOccurrence<AndroidProjectionStopEvidence> {
        operation.get()?.let { return it }
        val evidence = AndroidProjectionStopEvidence(this)
        val ownerBag = AndroidProjectionStopOwnerBag(this, projection)
        val candidate = OperationOccurrence(
            identity = checkNotNull(configuredIdentity.get()),
            clock = checkNotNull(configuredClock.get()),
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
        ).also {
            check(it.arbitrateTerminal(mandatoryCleanup = true) == OperationTerminalArbitration.Transferred)
            check(ownerBag.bindOperation(it))
        }
        operation.compareAndSet(null, candidate)
        return checkNotNull(operation.get())
    }

    internal fun bindNormalTicket(
        exactOperation: OperationOccurrence<AndroidProjectionStopEvidence>,
        ticket: AndroidPostTicket<AndroidProjectionStopEvidence>,
    ): Boolean {
        if (operation.get() !== exactOperation || ticket.occurrence !== exactOperation) return false
        val ownerBag = exactOperation.ownerBag as? AndroidProjectionStopOwnerBag ?: return false
        return exactOperation.settlementGate.withLock {
            operation.get() === exactOperation && ownerBag.obligation === this && ownerBag.bindNormalTicket(ticket)
        }
    }

    internal fun invokeNormal(
        exactOperation: OperationOccurrence<AndroidProjectionStopEvidence>,
        ticket: AndroidPostTicket<AndroidProjectionStopEvidence>,
    ) {
        val ownerBag = exactOperation.ownerBag as AndroidProjectionStopOwnerBag
        val entered = exactOperation.settlementGate.withLock {
            operation.get() === exactOperation && ownerBag.normalTicket === ticket &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.OnStack &&
                    ownerBag.beginCallLocked(AndroidProjectionStopRoute.NormalHandler)
        }
        check(entered)
        try {
            projection.stop()
        } catch (failure: Exception) {
            exactOperation.settlementGate.withLock {
                check(ownerBag.recordThrownReturnLocked(failure))
                check(exactOperation.returnCell.publishThrownLocked(Long.MIN_VALUE, failure))
            }
            signalBestEffort()
            return
        } catch (raw: Throwable) {
            exactOperation.settlementGate.withLock { check(ownerBag.recordThrownReturnLocked(raw)) }
            signalBestEffort()
            throw raw
        }
        exactOperation.settlementGate.withLock {
            check(ownerBag.recordNormalReturnLocked())
            check(exactOperation.returnCell.publishNormalLocked(Long.MIN_VALUE))
        }
        signalBestEffort()
    }

    internal fun closureReceipt(): AndroidProjectionClosureReceipt? {
        val exact = operation.get() ?: return null
        return exact.settlementGate.withLock {
            val ownerBag = exact.ownerBag as? AndroidProjectionStopOwnerBag ?: return@withLock null
            exact.returnCell.evidence.receipt.takeIf {
                ownerBag.obligation === this &&
                        ownerBag.callDisposition == AndroidProjectionStopCallDisposition.ReturnedNormal &&
                        exact.entryDisposition == OperationEntryDisposition.Entered &&
                        exact.returnCell.disposition == OperationReturnDisposition.Normal
            }
        }
    }

    internal fun laneNeverStartedProof(): AndroidLaneNeverStartedProof? {
        val lane = androidLane.get()
        val runtimeProof = lane?.proveNeverStarted()
        if (lane != null && runtimeProof == null) return null
        val exact = operation.get()
        if (exact != null) {
            val eligible = exact.settlementGate.withLock {
                val bag = exact.ownerBag as AndroidProjectionStopOwnerBag
                bag.normalTicket == null && bag.callDisposition == AndroidProjectionStopCallDisposition.Unentered &&
                        exact.entryDisposition == OperationEntryDisposition.Unentered &&
                        exact.returnCell.disposition == OperationReturnDisposition.Empty
            }
            if (!eligible) return null
        }
        return AndroidLaneNeverStartedProof.create(this, lane, runtimeProof)
    }

    internal fun prepareFinalAction(
        prerequisites: AndroidProjectionStopPrerequisitesProof,
        neverStarted: AndroidLaneNeverStartedProof?,
        finalLaneNoEntry: AndroidFinalLaneNoEntryProof<AndroidProjectionStopEvidence>?,
    ): AndroidFinalProjectionStopAction? {
        finalAction.get()?.let { return it }
        val terminal = cutoff.get() ?: return null
        val work = manifest.get() ?: return null
        if (terminal.obligation !== this || work.obligation !== this || work.cutoff !== terminal ||
            prerequisites.obligation !== this ||
            prerequisites.androidOwner !== androidOwner.get() ||
            (prerequisites.androidOwner != null &&
                    !prerequisites.androidOwner.acceptsProjectionStopPrerequisites(prerequisites)) ||
            (neverStarted == null) == (finalLaneNoEntry == null)
        ) return null
        val exact = createOperation()
        val ownerBag = exact.ownerBag as AndroidProjectionStopOwnerBag
        val eligible = exact.settlementGate.withLock {
            if (ownerBag.obligation !== this || ownerBag.callDisposition != AndroidProjectionStopCallDisposition.Unentered ||
                exact.entryDisposition != OperationEntryDisposition.Unentered ||
                exact.returnCell.disposition != OperationReturnDisposition.Empty
            ) return@withLock false
            when {
                neverStarted != null -> neverStarted.obligation === this && neverStarted.lane === androidLane.get() &&
                        (neverStarted.lane == null ||
                                neverStarted.runtimeProof?.let { proof ->
                                    proof.lane === neverStarted.lane &&
                                            proof.workerIdentity.lane === neverStarted.lane
                                } == true) &&
                        ownerBag.normalTicket == null
                finalLaneNoEntry != null -> {
                    val lane = androidLane.get() ?: return@withLock false
                    val ticket = ownerBag.normalTicket ?: return@withLock false
                    finalLaneNoEntry.operation === exact && finalLaneNoEntry.ticket === ticket &&
                            lane.observeFinalLaneNoEntryLocked(finalLaneNoEntry.terminationReceipt, ticket, exact) === finalLaneNoEntry
                }
                else -> false
            }
        }
        if (!eligible) return null
        val candidate = AndroidFinalProjectionStopAction.create(
            this, exact, ownerBag, terminal, work, prerequisites, neverStarted, finalLaneNoEntry,
        )
        finalAction.compareAndSet(null, candidate)
        return finalAction.get()
    }

    internal fun prepareFinalActionWithoutAndroidOwner(): AndroidFinalProjectionStopAction? {
        if (androidOwner.get() != null || androidLane.get() != null) return null
        val neverStarted = laneNeverStartedProof() ?: return null
        val proof = AndroidProjectionStopPrerequisitesProof(this, null, this, this)
        return prepareFinalAction(proof, neverStarted, null)
    }

    internal fun invokeFinal(action: AndroidFinalProjectionStopAction): AndroidFinalProjectionStopOutcome? {
        if (finalAction.get() !== action || action.obligation !== this || action.cutoff !== cutoff.get() ||
            action.manifest !== manifest.get() || action.prerequisites.obligation !== this ||
            action.prerequisites.androidOwner !== androidOwner.get() ||
            (action.prerequisites.androidOwner != null &&
                    !action.prerequisites.androidOwner.acceptsProjectionStopPrerequisites(action.prerequisites))
        ) return null
        val exact = action.operation
        val ownerBag = action.ownerBag
        val entered = exact.settlementGate.withLock {
            if (operation.get() !== exact || exact.ownerBag !== ownerBag || ownerBag.obligation !== this ||
                ownerBag.callDisposition != AndroidProjectionStopCallDisposition.Unentered ||
                exact.entryDisposition != OperationEntryDisposition.Unentered ||
                exact.returnCell.disposition != OperationReturnDisposition.Empty
            ) return@withLock false
            if (action.neverStarted != null) {
                if (action.neverStarted.obligation !== this || action.neverStarted.lane !== androidLane.get() ||
                    (action.neverStarted.lane != null &&
                            action.neverStarted.runtimeProof?.let { proof ->
                                proof.lane !== action.neverStarted.lane ||
                                        proof.workerIdentity.lane !== action.neverStarted.lane
                            } != false) ||
                    ownerBag.normalTicket != null
                ) return@withLock false
                if (exact.submissionDisposition == OperationSubmissionDisposition.None) {
                    if (!exact.beginSubmissionLocked() || !exact.publishSubmissionAcceptedLocked()) return@withLock false
                }
            } else {
                val proof = action.finalLaneNoEntry ?: return@withLock false
                val lane = androidLane.get() ?: return@withLock false
                val ticket = ownerBag.normalTicket ?: return@withLock false
                if (lane.observeFinalLaneNoEntryLocked(proof.terminationReceipt, ticket, exact) !== proof) return@withLock false
            }
            exact.tryEnterLocked() == OperationEntryResult.Entered &&
                    ownerBag.beginCallLocked(AndroidProjectionStopRoute.FinalControl)
        }
        if (!entered) return null
        return try {
            projection.stop()
            exact.settlementGate.withLock {
                check(ownerBag.recordNormalReturnLocked())
                check(exact.returnCell.publishNormalLocked(Long.MIN_VALUE))
            }
            signalBestEffort()
            exact.arbitrate()
            AndroidFinalProjectionStopOutcome.Returned(exact.returnCell.evidence.receipt)
        } catch (failure: Exception) {
            exact.settlementGate.withLock {
                check(ownerBag.recordThrownReturnLocked(failure))
                check(exact.returnCell.publishThrownLocked(Long.MIN_VALUE, failure))
            }
            signalBestEffort()
            exact.arbitrate()
            AndroidFinalProjectionStopOutcome.Thrown(failure)
        } catch (raw: Throwable) {
            exact.settlementGate.withLock {
                check(ownerBag.recordThrownReturnLocked(raw))
                check(exact.publishDirectFatalReturnLocked(raw))
            }
            signalBestEffort()
            throw raw
        }
    }

    private fun signalBestEffort() {
        try {
            configuredSignal.get()?.signal()
        } catch (_: Throwable) {
        }
    }
}
