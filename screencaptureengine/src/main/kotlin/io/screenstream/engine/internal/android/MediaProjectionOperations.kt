package io.screenstream.engine.internal.android

import android.media.projection.MediaProjection
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDirectCleanupAdmissionProof
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
    AuthoritativePreSubmissionCutoff,
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
) : OperationOwnerBag {
    private val fixedPostTicket =
        AtomicReference<AndroidPostTicket<AndroidProjectionCallbackUnregistrationEvidence>?>(null)

    internal val postTicket: AndroidPostTicket<AndroidProjectionCallbackUnregistrationEvidence>
        get() = checkNotNull(fixedPostTicket.get())

    internal fun bindPostTicket(
        ticket: AndroidPostTicket<AndroidProjectionCallbackUnregistrationEvidence>,
    ): Boolean = fixedPostTicket.compareAndSet(null, ticket)
}

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

internal sealed interface AndroidProjectionStopNormalRouteAdmissionState {
    data object Open : AndroidProjectionStopNormalRouteAdmissionState
    data object TicketPreparationClaimed : AndroidProjectionStopNormalRouteAdmissionState
    class Bound(
        internal val proof: AndroidProjectionStopBoundNormalRouteProof,
    ) : AndroidProjectionStopNormalRouteAdmissionState
    class SealedBoundProof(
        internal val proof: AndroidProjectionStopBoundNormalRouteProof,
        internal val admission: AndroidProjectionStopDirectAdmission,
    ) : AndroidProjectionStopNormalRouteAdmissionState
    class SealedNoTicketProof(
        internal val proof: AndroidProjectionStopNoTicketNormalRouteProof,
        internal val admission: AndroidProjectionStopDirectAdmission,
    ) : AndroidProjectionStopNormalRouteAdmissionState
}

internal class AndroidProjectionStopOwnerBag internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val projection: MediaProjection,
) : OperationOwnerBag {
    internal var operation: OperationOccurrence<AndroidProjectionStopEvidence>? = null
        private set
    private val noTicketProof = AndroidProjectionStopNoTicketNormalRouteProof(this)
    @Volatile
    private var normalRouteAdmission: AndroidProjectionStopNormalRouteAdmissionState =
        AndroidProjectionStopNormalRouteAdmissionState.Open
    internal val normalTicket: AndroidPostTicket<AndroidProjectionStopEvidence>?
        get() = when (val state = normalRouteAdmission) {
            is AndroidProjectionStopNormalRouteAdmissionState.Bound -> state.proof.ticket
            is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof -> state.proof.ticket
            else -> null
        }
    internal val boundNormalRouteProof: AndroidProjectionStopBoundNormalRouteProof?
        get() = when (val state = normalRouteAdmission) {
            is AndroidProjectionStopNormalRouteAdmissionState.Bound -> state.proof
            is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof -> state.proof
            else -> null
        }
    internal val noTicketNormalRouteProof: AndroidProjectionStopNoTicketNormalRouteProof
        get() = noTicketProof
    internal val sealedNormalRouteAdmission:
        AndroidProjectionStopNormalRouteAdmissionState?
        get() = normalRouteAdmission.takeIf {
            it is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof ||
                it is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof
        }
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

    internal fun bindNormalTicketLocked(
        value: AndroidPostTicket<AndroidProjectionStopEvidence>,
        proof: AndroidProjectionStopBoundNormalRouteProof,
        boundState: AndroidProjectionStopNormalRouteAdmissionState.Bound,
    ): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        if (normalRouteAdmission !== AndroidProjectionStopNormalRouteAdmissionState.Open ||
            value.occurrence !== exact || proof.ownerBag !== this || proof.ticket !== value ||
            boundState.proof !== proof
        ) return false
        normalRouteAdmission = AndroidProjectionStopNormalRouteAdmissionState.TicketPreparationClaimed
        normalRouteAdmission = boundState
        return true
    }

    internal fun sealNoTicketAdmissionLocked(
        sealedState: AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof,
    ): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        val current = normalRouteAdmission
        if (current is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof) {
            return current === sealedState
        }
        if (current !== AndroidProjectionStopNormalRouteAdmissionState.Open ||
            sealedState.proof !== noTicketProof
        ) return false
        normalRouteAdmission = sealedState
        return true
    }

    internal fun canSealNoTicketAdmissionLocked(
        sealedState: AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof,
    ): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        return sealedState.proof === noTicketProof && sealedState.proof.ownerBag === this &&
                normalRouteAdmission === AndroidProjectionStopNormalRouteAdmissionState.Open
    }

    internal fun sealBoundAdmissionLocked(
        sealedState: AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof,
    ): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        val current = normalRouteAdmission
        if (current is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof) {
            return current === sealedState
        }
        if (current !is AndroidProjectionStopNormalRouteAdmissionState.Bound ||
            current.proof !== sealedState.proof
        ) return false
        normalRouteAdmission = sealedState
        return true
    }

    internal fun canSealBoundAdmissionLocked(
        sealedState: AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof,
    ): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        val current = normalRouteAdmission
        return current is AndroidProjectionStopNormalRouteAdmissionState.Bound &&
                current.proof === sealedState.proof && sealedState.proof.ownerBag === this
    }

    internal fun acceptsDirectAdmissionLocked(admission: AndroidProjectionStopDirectAdmission): Boolean {
        val exact = checkNotNull(operation)
        check(exact.settlementGate.isHeldByCurrentThread)
        return when (val state = normalRouteAdmission) {
            is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof -> state.admission === admission
            is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof -> state.admission === admission
            else -> false
        }
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

internal class AndroidProjectionStopNoTicketNormalRouteProof internal constructor(
    internal val ownerBag: AndroidProjectionStopOwnerBag,
)

internal class AndroidProjectionStopBoundNormalRouteProof internal constructor(
    internal val ownerBag: AndroidProjectionStopOwnerBag,
    internal val ticket: AndroidPostTicket<AndroidProjectionStopEvidence>,
)

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

internal class AndroidProjectionStopOwnerBindingToken internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val constructionClaim: AndroidProjectionStopOwnerConstructionClaim,
    internal val owner: AndroidCaptureOwner,
    internal val lane: AndroidLaneRuntime,
) : AndroidProjectionStopOwnerConstructionState

internal sealed interface AndroidProjectionStopOwnerConstructionState {
    data object Unbound : AndroidProjectionStopOwnerConstructionState
}

internal class AndroidProjectionStopOwnerConstructionClaim internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
) {
    internal val constructingState = Constructing(this)
    internal val laneStartClaim = LaneStartAdmission.LaneStartClaim(this)
    internal val laneStartAdmission = AtomicReference<LaneStartAdmission>(LaneStartAdmission.Open)

    internal class Constructing internal constructor(
        internal val claim: AndroidProjectionStopOwnerConstructionClaim,
    ) : AndroidProjectionStopOwnerConstructionState

    internal sealed interface LaneStartAdmission {
        data object Open : LaneStartAdmission
        class LaneStartClaim internal constructor(
            internal val constructionClaim: AndroidProjectionStopOwnerConstructionClaim,
        ) : LaneStartAdmission
        class TerminalClaim internal constructor(
            internal val constructionClaim: AndroidProjectionStopOwnerConstructionClaim,
            internal val action: AndroidFinalProjectionStopAction,
        ) : LaneStartAdmission
    }
}

internal class AndroidProjectionStopTerminalNoOwnerClaim internal constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val constructionClaim: AndroidProjectionStopOwnerConstructionClaim?,
    internal val action: AndroidFinalProjectionStopAction,
) : AndroidProjectionStopOwnerConstructionState

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

internal sealed interface AndroidProjectionStopDirectAdmission {
    val settlementProof: OperationDirectCleanupAdmissionProof?

    class LaneNeverStartedNoTicket internal constructor(
        internal val neverStarted: AndroidLaneNeverStartedProof,
        internal val noTicketProof: AndroidProjectionStopNoTicketNormalRouteProof,
        override val settlementProof: OperationDirectCleanupAdmissionProof,
    ) : AndroidProjectionStopDirectAdmission

    class LaneNeverStartedBoundNeverSubmitted internal constructor(
        internal val neverStarted: AndroidLaneNeverStartedProof,
        internal val boundProof: AndroidProjectionStopBoundNormalRouteProof,
        override val settlementProof: OperationDirectCleanupAdmissionProof,
    ) : AndroidProjectionStopDirectAdmission

    class LaneNeverStartedAuthoritativelyRejected internal constructor(
        internal val neverStarted: AndroidLaneNeverStartedProof,
        internal val boundProof: AndroidProjectionStopBoundNormalRouteProof,
    ) : AndroidProjectionStopDirectAdmission {
        override val settlementProof: OperationDirectCleanupAdmissionProof? = null
    }

    class FinalWorkerReturnedNoTicket internal constructor(
        internal val terminationReceipt: AndroidLaneTerminationReceipt,
        internal val noTicketProof: AndroidProjectionStopNoTicketNormalRouteProof,
        override val settlementProof: OperationDirectCleanupAdmissionProof,
    ) : AndroidProjectionStopDirectAdmission

    class FinalWorkerReturnedPostCutoff internal constructor(
        internal val terminationReceipt: AndroidLaneTerminationReceipt,
        internal val boundProof: AndroidProjectionStopBoundNormalRouteProof,
        internal val cutoffProof: AndroidLanePostCutoffProof<AndroidProjectionStopEvidence>,
        override val settlementProof: OperationDirectCleanupAdmissionProof,
    ) : AndroidProjectionStopDirectAdmission

    class FinalWorkerReturnedBoundNoEntry internal constructor(
        internal val boundProof: AndroidProjectionStopBoundNormalRouteProof,
        internal val noEntryProof: AndroidFinalLaneNoEntryProof<AndroidProjectionStopEvidence>,
    ) : AndroidProjectionStopDirectAdmission {
        override val settlementProof: OperationDirectCleanupAdmissionProof? = null
    }
}

internal sealed interface AndroidFinalProjectionStopOutcome {
    class Returned internal constructor(internal val receipt: AndroidProjectionClosureReceipt) :
        AndroidFinalProjectionStopOutcome

    class Thrown internal constructor() : AndroidFinalProjectionStopOutcome {
        private val exactThrowable = AtomicReference<Throwable?>(null)
        internal val raw: Throwable
            get() = checkNotNull(exactThrowable.get())
        internal fun record(value: Throwable): Boolean = exactThrowable.compareAndSet(null, value)
    }
}

/** Opaque, one-shot final-Control action. It exposes neither projection nor retry authority. */
internal class AndroidFinalProjectionStopAction private constructor(
    internal val obligation: AndroidProjectionStopObligation,
    internal val operation: OperationOccurrence<AndroidProjectionStopEvidence>,
    internal val ownerBag: AndroidProjectionStopOwnerBag,
    internal val cutoff: AndroidProjectionStopTerminalCutoffProof,
    internal val manifest: AndroidProjectionStopWorkManifestProof,
    internal val prerequisites: AndroidProjectionStopPrerequisitesProof,
    internal val directAdmission: AndroidProjectionStopDirectAdmission,
) {
    private val invoked = AtomicBoolean(false)
    internal val returnedOutcome = AndroidFinalProjectionStopOutcome.Returned(operation.returnCell.evidence.receipt)
    internal val thrownOutcome = AndroidFinalProjectionStopOutcome.Thrown()

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
            directAdmission: AndroidProjectionStopDirectAdmission,
        ): AndroidFinalProjectionStopAction = AndroidFinalProjectionStopAction(
            obligation, operation, ownerBag, cutoff, manifest, prerequisites, directAdmission,
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
    private val androidConstruction = AtomicReference<AndroidProjectionStopOwnerConstructionState>(
        AndroidProjectionStopOwnerConstructionState.Unbound,
    )
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

    internal fun precreateOwnerConstructionClaim(): AndroidProjectionStopOwnerConstructionClaim =
        AndroidProjectionStopOwnerConstructionClaim(this)

    internal fun claimOwnerConstruction(claim: AndroidProjectionStopOwnerConstructionClaim): Boolean {
        if (claim.obligation !== this || claim.constructingState.claim !== claim) return false
        val current = androidConstruction.get()
        if (current === claim.constructingState) return true
        if (current !== AndroidProjectionStopOwnerConstructionState.Unbound) return false
        return androidConstruction.compareAndSet(current, claim.constructingState)
    }

    internal fun projectionForClaimedOwnerConstruction(
        claim: AndroidProjectionStopOwnerConstructionClaim,
    ): MediaProjection {
        check(claim.obligation === this && androidConstruction.get() === claim.constructingState)
        return projection
    }

    internal fun acceptsOwnerConstructionProjection(
        claim: AndroidProjectionStopOwnerConstructionClaim,
        candidate: MediaProjection,
    ): Boolean = candidate === projection && claim.obligation === this &&
            androidConstruction.get() === claim.constructingState

    internal fun precreateAndroidOwnerBindingToken(
        claim: AndroidProjectionStopOwnerConstructionClaim,
        owner: AndroidCaptureOwner,
        lane: AndroidLaneRuntime,
    ): AndroidProjectionStopOwnerBindingToken =
        AndroidProjectionStopOwnerBindingToken(this, claim, owner, lane)

    internal fun commitAndroidOwnerBinding(token: AndroidProjectionStopOwnerBindingToken): Boolean {
        if (token.obligation !== this || token.constructionClaim.obligation !== this ||
            token.owner.projectionConstructionClaim !== token.constructionClaim ||
            token.owner.projectionBindingLane !== token.lane
        ) return false
        val current = androidConstruction.get()
        if (current === token) return true
        if (current !== token.constructionClaim.constructingState) return false
        return androidConstruction.compareAndSet(token.constructionClaim.constructingState, token)
    }

    internal fun claimAndroidLaneStart(token: AndroidProjectionStopOwnerBindingToken): Boolean {
        if (androidConstruction.get() !== token || token.obligation !== this) return false
        val claim = token.constructionClaim
        val current = claim.laneStartAdmission.get()
        if (current === claim.laneStartClaim) return true
        if (current !== AndroidProjectionStopOwnerConstructionClaim.LaneStartAdmission.Open) return false
        return claim.laneStartAdmission.compareAndSet(current, claim.laneStartClaim)
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
        proof: AndroidProjectionStopBoundNormalRouteProof,
        boundState: AndroidProjectionStopNormalRouteAdmissionState.Bound,
    ): Boolean {
        if (operation.get() !== exactOperation || ticket.occurrence !== exactOperation) return false
        val ownerBag = exactOperation.ownerBag as? AndroidProjectionStopOwnerBag ?: return false
        return exactOperation.settlementGate.withLock {
            operation.get() === exactOperation && ownerBag.obligation === this &&
                ownerBag.bindNormalTicketLocked(ticket, proof, boundState)
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

    internal fun allowsLaneRetirementAfterAuthoritativeRejection(
        exactOperation: OperationOccurrence<AndroidProjectionStopEvidence>,
    ): Boolean = exactOperation.settlementGate.withLock {
        operation.get() === exactOperation &&
                acceptsAuthoritativeRejectedNormalPostLocked(
                    exactOperation,
                    exactOperation.ownerBag as? AndroidProjectionStopOwnerBag ?: return@withLock false,
                )
    }

    internal fun laneNeverStartedProof(): AndroidLaneNeverStartedProof? {
        val token = boundAndroidToken()
        if (token != null &&
            token.constructionClaim.laneStartAdmission.get() !==
            AndroidProjectionStopOwnerConstructionClaim.LaneStartAdmission.Open
        ) return null
        val lane = token?.lane
        val runtimeProof = lane?.proveNeverStarted()
        if (lane != null && runtimeProof == null) return null
        val exact = operation.get()
        if (exact != null) {
            val eligible = exact.settlementGate.withLock {
                val bag = exact.ownerBag as AndroidProjectionStopOwnerBag
                val ticket = bag.normalTicket
                val boundNeverSubmitted = ticket != null && ticket.occurrence === exact &&
                        ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                        ticket.postFailureResidue == null &&
                        exact.submissionDisposition == OperationSubmissionDisposition.None &&
                        exact.submissionFailure == null && exact.submissionAmbiguousFatal == null
                (ticket == null || boundNeverSubmitted ||
                        acceptsAuthoritativeRejectedNormalPostLocked(exact, bag)) &&
                        bag.callDisposition == AndroidProjectionStopCallDisposition.Unentered &&
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
        finalTerminationReceipt: AndroidLaneTerminationReceipt?,
        terminalConstructionClaim: AndroidProjectionStopOwnerConstructionClaim? = null,
    ): AndroidFinalProjectionStopAction? {
        finalAction.get()?.let { return it }
        val terminal = cutoff.get() ?: return null
        val work = manifest.get() ?: return null
        if (terminal.obligation !== this || work.obligation !== this || work.cutoff !== terminal ||
            prerequisites.obligation !== this ||
            !acceptsPrerequisiteOwner(prerequisites, terminalConstructionClaim) ||
            (prerequisites.androidOwner != null &&
                    !prerequisites.androidOwner.acceptsProjectionStopPrerequisites(prerequisites)) ||
            (neverStarted == null) == (finalTerminationReceipt == null)
        ) return null
        val exact = createOperation()
        val ownerBag = exact.ownerBag as AndroidProjectionStopOwnerBag
        val sealedAdmissionCandidate = precreateDirectAdmissionCandidate(
            exact,
            ownerBag,
            neverStarted,
            finalTerminationReceipt,
        ) ?: return null
        val directAdmission = when (sealedAdmissionCandidate) {
            is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof ->
                sealedAdmissionCandidate.admission
            is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof ->
                sealedAdmissionCandidate.admission
            else -> return null
        }
        val actionCandidate = AndroidFinalProjectionStopAction.create(
            this, exact, ownerBag, terminal, work, prerequisites, directAdmission,
        )
        val terminalNoOwnerClaim = if (prerequisites.androidOwner == null) {
            AndroidProjectionStopTerminalNoOwnerClaim(
                this,
                terminalConstructionClaim,
                actionCandidate,
            )
        } else {
            null
        }
        val terminalLaneStartClaim = if (prerequisites.androidOwner != null && neverStarted != null) {
            val token = boundAndroidToken() ?: return null
            AndroidProjectionStopOwnerConstructionClaim.LaneStartAdmission.TerminalClaim(
                token.constructionClaim,
                actionCandidate,
            )
        } else {
            null
        }
        if (terminalNoOwnerClaim != null) {
            val expectedConstruction = terminalConstructionClaim?.constructingState
                ?: AndroidProjectionStopOwnerConstructionState.Unbound
            if (!androidConstruction.compareAndSet(expectedConstruction, terminalNoOwnerClaim)) {
                return finalAction.get()
            }
        }
        if (terminalLaneStartClaim != null) {
            val laneAdmission = terminalLaneStartClaim.constructionClaim.laneStartAdmission
            if (!laneAdmission.compareAndSet(
                    AndroidProjectionStopOwnerConstructionClaim.LaneStartAdmission.Open,
                    terminalLaneStartClaim,
                )
            ) {
                return finalAction.get()
            }
        }
        val sealed = exact.settlementGate.withLock {
            if (ownerBag.obligation !== this || ownerBag.callDisposition != AndroidProjectionStopCallDisposition.Unentered ||
                exact.entryDisposition != OperationEntryDisposition.Unentered ||
                exact.returnCell.disposition != OperationReturnDisposition.Empty
            ) return@withLock false
            sealDirectAdmissionLocked(exact, ownerBag, sealedAdmissionCandidate)
        }
        if (!sealed) {
            check(terminalNoOwnerClaim == null && terminalLaneStartClaim == null)
            return null
        }
        finalAction.compareAndSet(null, actionCandidate)
        return finalAction.get()
    }

    internal fun prepareFinalActionWithoutAndroidOwner(
        constructionClaim: AndroidProjectionStopOwnerConstructionClaim? = null,
    ): AndroidFinalProjectionStopAction? {
        finalAction.get()?.let { return it }
        val construction = androidConstruction.get()
        if (constructionClaim == null) {
            if (construction !== AndroidProjectionStopOwnerConstructionState.Unbound) return null
        } else if (construction !== constructionClaim.constructingState) {
            return null
        }
        val neverStarted = laneNeverStartedProof() ?: return null
        val proof = AndroidProjectionStopPrerequisitesProof(this, null, this, this)
        return prepareFinalAction(proof, neverStarted, null, constructionClaim)
    }

    internal fun invokeFinal(action: AndroidFinalProjectionStopAction): AndroidFinalProjectionStopOutcome? {
        if (finalAction.get() !== action || action.obligation !== this || action.cutoff !== cutoff.get() ||
            action.manifest !== manifest.get() || action.prerequisites.obligation !== this ||
            !acceptsActionOwner(action) ||
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
            ownerBag.acceptsDirectAdmissionLocked(action.directAdmission) &&
                    enterDirectAdmissionLocked(exact, ownerBag, action.directAdmission) &&
                    ownerBag.beginCallLocked(AndroidProjectionStopRoute.FinalControl)
        }
        if (!entered) return null
        var callFailure: Throwable? = null
        try {
            projection.stop()
        } catch (raw: Throwable) {
            callFailure = raw
        }
        val raw = callFailure
        if (raw == null) {
            exact.settlementGate.withLock {
                check(ownerBag.recordNormalReturnLocked())
                check(exact.returnCell.publishNormalLocked(Long.MIN_VALUE))
            }
            signalBestEffort()
            exact.arbitrate()
            return action.returnedOutcome
        }
        check(action.thrownOutcome.record(raw))
        if (raw is Exception) {
            exact.settlementGate.withLock {
                check(ownerBag.recordThrownReturnLocked(raw))
                check(exact.returnCell.publishThrownLocked(Long.MIN_VALUE, raw))
            }
            signalBestEffort()
            exact.arbitrate()
            return action.thrownOutcome
        }
        exact.settlementGate.withLock {
            check(ownerBag.recordThrownReturnLocked(raw))
            check(exact.publishDirectFatalReturnLocked(raw))
        }
        signalBestEffort()
        throw raw
    }

    private fun acceptsPrerequisiteOwner(
        prerequisites: AndroidProjectionStopPrerequisitesProof,
        terminalConstructionClaim: AndroidProjectionStopOwnerConstructionClaim?,
    ): Boolean {
        val owner = prerequisites.androidOwner
        if (owner != null) return terminalConstructionClaim == null && boundAndroidToken()?.owner === owner
        val construction = androidConstruction.get()
        return if (terminalConstructionClaim == null) {
            construction === AndroidProjectionStopOwnerConstructionState.Unbound
        } else {
            terminalConstructionClaim.obligation === this &&
                construction === terminalConstructionClaim.constructingState
        }
    }

    private fun acceptsActionOwner(action: AndroidFinalProjectionStopAction): Boolean {
        val owner = action.prerequisites.androidOwner
        if (owner != null) {
            val token = boundAndroidToken() ?: return false
            if (token.owner !== owner) return false
            if (action.directAdmission is AndroidProjectionStopDirectAdmission.LaneNeverStartedNoTicket ||
                action.directAdmission is AndroidProjectionStopDirectAdmission.LaneNeverStartedBoundNeverSubmitted ||
                action.directAdmission is AndroidProjectionStopDirectAdmission.LaneNeverStartedAuthoritativelyRejected
            ) {
                val terminal = token.constructionClaim.laneStartAdmission.get() as?
                    AndroidProjectionStopOwnerConstructionClaim.LaneStartAdmission.TerminalClaim ?: return false
                return terminal.constructionClaim === token.constructionClaim && terminal.action === action
            }
            return true
        }
        val terminal = androidConstruction.get() as? AndroidProjectionStopTerminalNoOwnerClaim ?: return false
        return terminal.obligation === this && terminal.action === action
    }

    private fun precreateDirectAdmissionCandidate(
        exact: OperationOccurrence<AndroidProjectionStopEvidence>,
        ownerBag: AndroidProjectionStopOwnerBag,
        neverStarted: AndroidLaneNeverStartedProof?,
        finalTerminationReceipt: AndroidLaneTerminationReceipt?,
    ): AndroidProjectionStopNormalRouteAdmissionState? {
        ownerBag.sealedNormalRouteAdmission?.let { return it }
        val settlementProof = exact.directCleanupAdmissionCandidate
        val boundProof = ownerBag.boundNormalRouteProof
        if (neverStarted != null) {
            val lane = boundAndroidToken()?.lane
            if (finalTerminationReceipt != null || neverStarted.obligation !== this || neverStarted.lane !== lane ||
                (lane != null && neverStarted.runtimeProof?.let { proof ->
                    proof.lane === lane && proof.workerIdentity.lane === lane && lane.proveNeverStarted() === proof
                } != true) || lane == null && neverStarted.runtimeProof != null
            ) return null
            if (boundProof == null) {
                val admission = AndroidProjectionStopDirectAdmission.LaneNeverStartedNoTicket(
                    neverStarted,
                    ownerBag.noTicketNormalRouteProof,
                    settlementProof,
                )
                return AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof(
                    ownerBag.noTicketNormalRouteProof,
                    admission,
                )
            }
            val submission = exact.settlementGate.withLock { exact.submissionDisposition }
            val admission = when (submission) {
                OperationSubmissionDisposition.None ->
                    AndroidProjectionStopDirectAdmission.LaneNeverStartedBoundNeverSubmitted(
                        neverStarted, boundProof, settlementProof,
                    )
                OperationSubmissionDisposition.Rejected ->
                    AndroidProjectionStopDirectAdmission.LaneNeverStartedAuthoritativelyRejected(
                        neverStarted, boundProof,
                    )
                else -> return null
            }
            return AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof(boundProof, admission)
        }

        val receipt = finalTerminationReceipt ?: return null
        val lane = boundAndroidToken()?.lane ?: return null
        if (!lane.acceptsTerminationReceipt(receipt) || lane.terminationReceipt !== receipt) return null
        if (boundProof == null) {
            val admission = AndroidProjectionStopDirectAdmission.FinalWorkerReturnedNoTicket(
                receipt,
                ownerBag.noTicketNormalRouteProof,
                settlementProof,
            )
            return AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof(
                ownerBag.noTicketNormalRouteProof,
                admission,
            )
        }
        val ticket = boundProof.ticket
        if (ticket.occurrence !== exact || ticket.lane !== lane || ticket.workerIdentity.lane !== lane ||
            ticket.terminationReceipt !== receipt || ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack
        ) return null
        var submission: OperationSubmissionDisposition? = null
        var noEntry: AndroidFinalLaneNoEntryProof<AndroidProjectionStopEvidence>? = null
        exact.settlementGate.withLock {
            submission = exact.submissionDisposition
            if (submission == OperationSubmissionDisposition.Accepted ||
                submission == OperationSubmissionDisposition.Rejected
            ) noEntry = lane.observeFinalLaneNoEntryLocked(receipt, ticket, exact)
        }
        val admission = when (submission) {
            OperationSubmissionDisposition.None -> {
                if (!ticket.authoritativePostCutoffProof.isCutoffObservedExact) null
                else AndroidProjectionStopDirectAdmission.FinalWorkerReturnedPostCutoff(
                    receipt, boundProof, ticket.authoritativePostCutoffProof, settlementProof,
                )
            }
            OperationSubmissionDisposition.Accepted,
            OperationSubmissionDisposition.Rejected,
                -> noEntry?.let {
                    AndroidProjectionStopDirectAdmission.FinalWorkerReturnedBoundNoEntry(boundProof, it)
                }
            else -> null
        } ?: return null
        return AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof(boundProof, admission)
    }

    private fun sealDirectAdmissionLocked(
        exact: OperationOccurrence<AndroidProjectionStopEvidence>,
        ownerBag: AndroidProjectionStopOwnerBag,
        sealedAdmission: AndroidProjectionStopNormalRouteAdmissionState,
    ): Boolean {
        check(exact.settlementGate.isHeldByCurrentThread)
        if (exact.domain != io.screenstream.engine.internal.settlement.OperationDomain.Cleanup ||
            exact.disposition != OperationDisposition.Cleanup ||
            exact.entryDisposition != OperationEntryDisposition.Unentered ||
            exact.returnCell.disposition != OperationReturnDisposition.Empty ||
            exact.submissionAmbiguousFatal != null || ownerBag.route != null ||
            ownerBag.callDisposition != AndroidProjectionStopCallDisposition.Unentered
        ) return false
        val admission = when (sealedAdmission) {
            is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof -> sealedAdmission.admission
            is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof -> sealedAdmission.admission
            else -> return false
        }
        if (ownerBag.acceptsDirectAdmissionLocked(admission)) return true
        return when (admission) {
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedNoTicket -> {
                val lane = boundAndroidToken()?.lane
                if (admission.neverStarted.obligation !== this || admission.neverStarted.lane !== lane ||
                    (lane != null && admission.neverStarted.runtimeProof?.let { lane.proveNeverStarted() === it } != true)
                ) return false
                sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof &&
                    sealedAdmission.proof === admission.noTicketProof &&
                    exact.submissionDisposition == OperationSubmissionDisposition.None &&
                    ownerBag.canSealNoTicketAdmissionLocked(sealedAdmission) &&
                    exact.sealDirectCleanupAdmissionFromNoSubmissionLocked(admission.settlementProof) &&
                    ownerBag.sealNoTicketAdmissionLocked(sealedAdmission)
            }
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedBoundNeverSubmitted -> {
                val lane = boundAndroidToken()?.lane
                admission.neverStarted.obligation === this && admission.neverStarted.lane === lane &&
                    sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof &&
                    sealedAdmission.proof === admission.boundProof &&
                    exact.submissionDisposition == OperationSubmissionDisposition.None &&
                    admission.boundProof.ticket.occurrence === exact &&
                    admission.boundProof.ticket.lane === lane &&
                    admission.boundProof.ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    admission.boundProof.ticket.postFailureResidue == null &&
                    exact.submissionFailure == null && exact.submissionAmbiguousFatal == null &&
                    ownerBag.canSealBoundAdmissionLocked(sealedAdmission) &&
                    exact.sealDirectCleanupAdmissionFromNoSubmissionLocked(admission.settlementProof) &&
                    ownerBag.sealBoundAdmissionLocked(sealedAdmission)
            }
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedAuthoritativelyRejected -> {
                val lane = boundAndroidToken()?.lane
                admission.neverStarted.obligation === this && admission.neverStarted.lane === lane &&
                    sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof &&
                    sealedAdmission.proof === admission.boundProof &&
                    ownerBag.canSealBoundAdmissionLocked(sealedAdmission) &&
                    acceptsAuthoritativeRejectedNormalPostLocked(exact, ownerBag) &&
                    ownerBag.sealBoundAdmissionLocked(sealedAdmission)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedNoTicket -> {
                val lane = boundAndroidToken()?.lane ?: return false
                lane.terminationReceipt === admission.terminationReceipt &&
                    lane.acceptsTerminationReceipt(admission.terminationReceipt) &&
                    exact.submissionDisposition == OperationSubmissionDisposition.None &&
                    sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedNoTicketProof &&
                    sealedAdmission.proof === admission.noTicketProof &&
                    ownerBag.canSealNoTicketAdmissionLocked(sealedAdmission) &&
                    exact.sealDirectCleanupAdmissionFromNoSubmissionLocked(admission.settlementProof) &&
                    ownerBag.sealNoTicketAdmissionLocked(sealedAdmission)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedPostCutoff -> {
                val lane = boundAndroidToken()?.lane ?: return false
                val ticket = admission.boundProof.ticket
                lane.terminationReceipt === admission.terminationReceipt &&
                    lane.acceptsTerminationReceipt(admission.terminationReceipt) &&
                    admission.cutoffProof === ticket.authoritativePostCutoffProof &&
                    admission.cutoffProof.isCutoffObservedExact && ticket.occurrence === exact && ticket.lane === lane &&
                    ticket.terminationReceipt === admission.terminationReceipt &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    exact.submissionDisposition == OperationSubmissionDisposition.None &&
                    sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof &&
                    sealedAdmission.proof === admission.boundProof &&
                    ownerBag.canSealBoundAdmissionLocked(sealedAdmission) &&
                    exact.sealDirectCleanupAdmissionFromNoSubmissionLocked(admission.settlementProof) &&
                    ownerBag.sealBoundAdmissionLocked(sealedAdmission)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedBoundNoEntry -> {
                val lane = boundAndroidToken()?.lane ?: return false
                val ticket = admission.boundProof.ticket
                val exactNoEntry = lane.observeFinalLaneNoEntryLocked(
                    admission.noEntryProof.terminationReceipt,
                    ticket,
                    exact,
                ) === admission.noEntryProof
                exactNoEntry && sealedAdmission is AndroidProjectionStopNormalRouteAdmissionState.SealedBoundProof &&
                    sealedAdmission.proof === admission.boundProof &&
                    ownerBag.canSealBoundAdmissionLocked(sealedAdmission) &&
                    (exact.submissionDisposition != OperationSubmissionDisposition.Rejected ||
                        acceptsAuthoritativeRejectedNormalPostLocked(exact, ownerBag)) &&
                    ownerBag.sealBoundAdmissionLocked(sealedAdmission)
            }
        }
    }

    private fun enterDirectAdmissionLocked(
        exact: OperationOccurrence<AndroidProjectionStopEvidence>,
        ownerBag: AndroidProjectionStopOwnerBag,
        admission: AndroidProjectionStopDirectAdmission,
    ): Boolean {
        check(exact.settlementGate.isHeldByCurrentThread)
        return when (admission) {
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedNoTicket -> {
                val lane = boundAndroidToken()?.lane
                admission.neverStarted.obligation === this && admission.neverStarted.lane === lane &&
                    admission.noTicketProof.ownerBag === ownerBag &&
                    exact.tryEnterDirectCleanupFromNoSubmissionLocked(admission.settlementProof)
            }
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedBoundNeverSubmitted -> {
                val lane = boundAndroidToken()?.lane
                admission.neverStarted.obligation === this && admission.neverStarted.lane === lane &&
                    admission.boundProof.ownerBag === ownerBag &&
                    exact.tryEnterDirectCleanupFromNoSubmissionLocked(admission.settlementProof)
            }
            is AndroidProjectionStopDirectAdmission.LaneNeverStartedAuthoritativelyRejected -> {
                val lane = boundAndroidToken()?.lane
                val rejection = exact.submissionFailure ?: return false
                admission.neverStarted.obligation === this && admission.neverStarted.lane === lane &&
                    admission.boundProof.ownerBag === ownerBag &&
                    acceptsAuthoritativeRejectedNormalPostLocked(exact, ownerBag) &&
                    exact.tryEnterAlternateDirectAfterRejectedCleanupSubmissionLocked(rejection)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedNoTicket -> {
                val lane = boundAndroidToken()?.lane ?: return false
                admission.noTicketProof.ownerBag === ownerBag && lane.terminationReceipt === admission.terminationReceipt &&
                    lane.acceptsTerminationReceipt(admission.terminationReceipt) &&
                    exact.tryEnterDirectCleanupFromNoSubmissionLocked(admission.settlementProof)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedPostCutoff -> {
                val lane = boundAndroidToken()?.lane ?: return false
                val ticket = admission.boundProof.ticket
                admission.boundProof.ownerBag === ownerBag && admission.cutoffProof === ticket.authoritativePostCutoffProof &&
                    admission.cutoffProof.isCutoffObservedExact && ticket.occurrence === exact && ticket.lane === lane &&
                    ticket.terminationReceipt === admission.terminationReceipt &&
                    lane.terminationReceipt === admission.terminationReceipt &&
                    lane.acceptsTerminationReceipt(admission.terminationReceipt) &&
                    ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    exact.tryEnterDirectCleanupFromNoSubmissionLocked(admission.settlementProof)
            }
            is AndroidProjectionStopDirectAdmission.FinalWorkerReturnedBoundNoEntry -> {
                val lane = boundAndroidToken()?.lane ?: return false
                val ticket = admission.boundProof.ticket
                if (admission.boundProof.ownerBag !== ownerBag || admission.noEntryProof.ticket !== ticket ||
                    lane.observeFinalLaneNoEntryLocked(admission.noEntryProof.terminationReceipt, ticket, exact) !==
                    admission.noEntryProof
                ) return false
                when (exact.submissionDisposition) {
                    OperationSubmissionDisposition.Accepted ->
                        exact.tryEnterLocked() == OperationEntryResult.Entered
                    OperationSubmissionDisposition.Rejected -> {
                        val rejection = exact.submissionFailure ?: return false
                        acceptsAuthoritativeRejectedNormalPostLocked(exact, ownerBag) &&
                            exact.tryEnterAlternateDirectAfterRejectedCleanupSubmissionLocked(rejection)
                    }
                    else -> false
                }
            }
        }
    }

    private fun acceptsAuthoritativeRejectedNormalPostLocked(
        exact: OperationOccurrence<AndroidProjectionStopEvidence>,
        ownerBag: AndroidProjectionStopOwnerBag,
    ): Boolean {
        check(exact.settlementGate.isHeldByCurrentThread)
        val lane = boundAndroidToken()?.lane ?: return false
        val ticket = ownerBag.normalTicket ?: return false
        val failure = ticket.postFailureResidue as? Exception ?: return false
        return operation.get() === exact && exact.ownerBag === ownerBag && ownerBag.obligation === this &&
                ticket.lane === lane && ticket.occurrence === exact &&
                ticket.operationIdentity == exact.identity && ticket.workerIdentity.lane === lane &&
                ticket.terminationReceipt.lane === lane && ticket.terminationReceipt.matchesWorker(ticket.workerIdentity) &&
                ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                ticket.failureExposure == AndroidPostFailureExposure.AuthoritativeRejection &&
                exact.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                exact.submissionFailure === failure && exact.submissionAmbiguousFatal == null &&
                exact.disposition == OperationDisposition.Cleanup &&
                exact.entryDisposition == OperationEntryDisposition.Unentered &&
                exact.returnCell.disposition == OperationReturnDisposition.Empty &&
                ownerBag.callDisposition == AndroidProjectionStopCallDisposition.Unentered &&
                ownerBag.route == null
    }

    private fun boundAndroidToken(): AndroidProjectionStopOwnerBindingToken? =
        androidConstruction.get() as? AndroidProjectionStopOwnerBindingToken

    private fun signalBestEffort() {
        try {
            configuredSignal.get()?.signal()
        } catch (_: Throwable) {
        }
    }
}
