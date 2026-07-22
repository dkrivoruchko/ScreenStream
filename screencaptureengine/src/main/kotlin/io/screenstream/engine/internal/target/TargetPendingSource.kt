package io.screenstream.engine.internal.target

import java.util.concurrent.atomic.AtomicReference

/**
 * Target-local pending-source mechanics. This helper owns no gate, policy, or currentness authority: its state
 * is advanced only by [CurrentTarget] while that owner performs the corresponding Target transition.
 */
internal class TargetPendingSourceLatch private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val targetIdentity: TargetIdentity,
    private val sourceAvailableFact: TargetSourceAvailableFact,
    initialFrameAdmissionEpoch: Long,
) {
    internal enum class AdmissionDisposition {
        Open,
        Sealed,
        RetirementClosed,
        GenerationFenced,
    }

    internal class PreparedEpoch private constructor(
        internal val targetIdentity: TargetIdentity,
        internal val frameAdmissionEpoch: Long,
    ) {
        private val openClearState: State =
            State(this, AdmissionDisposition.Open, pending = false, claimCycle = null)
        private val openPendingUnclaimedState: State =
            State(this, AdmissionDisposition.Open, pending = true, claimCycle = null)
        private val sealedClearState: State =
            State(this, AdmissionDisposition.Sealed, pending = false, claimCycle = null)
        private val sealedPendingState: State =
            State(this, AdmissionDisposition.Sealed, pending = true, claimCycle = null)
        private val retirementClosedClearState: State =
            State(this, AdmissionDisposition.RetirementClosed, pending = false, claimCycle = null)
        private val retirementClosedPendingState: State =
            State(this, AdmissionDisposition.RetirementClosed, pending = true, claimCycle = null)

        internal fun state(
            disposition: AdmissionDisposition,
            pending: Boolean,
        ): State = when (disposition) {
            AdmissionDisposition.Open -> if (pending) openPendingUnclaimedState else openClearState
            AdmissionDisposition.Sealed -> if (pending) sealedPendingState else sealedClearState
            AdmissionDisposition.RetirementClosed ->
                if (pending) retirementClosedPendingState else retirementClosedClearState
            AdmissionDisposition.GenerationFenced -> error("A prepared epoch has no generation-fenced state")
        }

        internal companion object {
            internal fun create(
                targetOwner: TargetOwner,
                constructionProof: () -> Unit,
                targetIdentity: TargetIdentity,
                sourceAvailableFact: TargetSourceAvailableFact,
                frameAdmissionEpoch: Long,
            ): PreparedEpoch {
                check(targetOwner.acceptsConstructionProof(constructionProof))
                require(frameAdmissionEpoch > 0L)
                check(sourceAvailableFact.targetIdentity === targetIdentity)
                return PreparedEpoch(targetIdentity, frameAdmissionEpoch)
            }
        }
    }

    internal class ClaimCycle(
        internal val claim: TargetPendingSourceClaim,
        internal val consumedFact: TargetPendingSourceConsumedFact,
    )

    internal class State(
        internal val epoch: PreparedEpoch?,
        internal val disposition: AdmissionDisposition,
        internal val pending: Boolean,
        internal val claimCycle: ClaimCycle?,
    )

    private val initialEpoch: PreparedEpoch = PreparedEpoch.create(
        targetOwner,
        constructionProof,
        targetIdentity,
        sourceAvailableFact,
        initialFrameAdmissionEpoch,
    )
    private val generationFencedState: State =
        State(
            epoch = null,
            disposition = AdmissionDisposition.GenerationFenced,
            pending = false,
            claimCycle = null,
        )
    private val state: AtomicReference<State> =
        AtomicReference(initialEpoch.state(AdmissionDisposition.Open, pending = false))

    init {
        check(targetOwner.acceptsConstructionProof(constructionProof))
        require(initialFrameAdmissionEpoch > 0L)
        check(sourceAvailableFact.targetIdentity === targetIdentity)
    }

    internal fun prepareEpoch(frameAdmissionEpoch: Long): PreparedEpoch =
        PreparedEpoch.create(
            targetOwner,
            constructionProof,
            targetIdentity,
            sourceAvailableFact,
            frameAdmissionEpoch,
        )

    /**
     * Creates and installs the exact one-shot claim/result pair before the controller's final gated commit.
     * Repeated scans of the same pending indication return the already-installed claim without consuming it.
     */
    internal fun claim(
        expectedTargetIdentity: TargetIdentity,
        expectedFrameAdmissionEpoch: Long,
    ): TargetPendingSourceClaim? {
        require(expectedFrameAdmissionEpoch > 0L)
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch ?: return null
            if (snapshot.disposition != AdmissionDisposition.Open || !snapshot.pending ||
                expectedTargetIdentity !== targetIdentity || epoch.targetIdentity !== expectedTargetIdentity ||
                epoch.frameAdmissionEpoch != expectedFrameAdmissionEpoch
            ) {
                return null
            }
            snapshot.claimCycle?.let { return it.claim }

            val claim = TargetPendingSourceClaim.create(
                targetOwner,
                constructionProof,
                targetIdentity,
                epoch.frameAdmissionEpoch,
            )
            val cycle = ClaimCycle(
                claim,
                TargetPendingSourceConsumedFact.create(
                    targetOwner,
                    constructionProof,
                    claim,
                    sourceAvailableFact,
                ),
            )
            val claimedState = State(
                epoch,
                AdmissionDisposition.Open,
                pending = true,
                claimCycle = cycle,
            )
            if (state.compareAndSet(snapshot, claimedState)) return claim
        }
    }

    /**
     * Allocation-free final exchange. It takes no lock and makes no outward call, so the controller may invoke
     * it inside the Session production-reservation commit.
     */
    internal fun commit(claim: TargetPendingSourceClaim): TargetPendingSourceCommitResult {
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch
            val claimCycle = snapshot.claimCycle
            if (snapshot.disposition != AdmissionDisposition.Open || !snapshot.pending || epoch == null ||
                epoch.targetIdentity !== targetIdentity || claimCycle == null || claimCycle.claim !== claim ||
                claim.targetIdentity !== targetIdentity ||
                claim.frameAdmissionEpoch != epoch.frameAdmissionEpoch
            ) {
                return claim.inertResult(targetOwner, constructionProof)
            }
            if (state.compareAndSet(
                    snapshot,
                    epoch.state(AdmissionDisposition.Open, pending = false),
                )
            ) {
                return claimCycle.consumedFact
            }
        }
    }

    /** Marks a callback durable before its controller signal is emitted. */
    internal fun offer(callbackTargetIdentity: TargetIdentity): TargetSourceAvailableFact? {
        if (callbackTargetIdentity !== targetIdentity || !callbackTargetIdentity.matches(targetIdentity.target)) {
            return null
        }
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch ?: return null
            if (snapshot.disposition == AdmissionDisposition.RetirementClosed ||
                snapshot.disposition == AdmissionDisposition.GenerationFenced
            ) {
                return null
            }
            if (snapshot.pending) return sourceAvailableFact
            if (state.compareAndSet(snapshot, epoch.state(snapshot.disposition, pending = true))) {
                return sourceAvailableFact
            }
        }
    }

    /** Invalidates open claims but preserves a pending indication across a reversible pause. */
    internal fun seal(expectedFrameAdmissionEpoch: Long) {
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch ?: return
            if (snapshot.disposition != AdmissionDisposition.Open ||
                epoch.frameAdmissionEpoch != expectedFrameAdmissionEpoch
            ) {
                return
            }
            if (state.compareAndSet(snapshot, epoch.state(AdmissionDisposition.Sealed, snapshot.pending))) return
        }
    }

    /** Installs a fresh claim identity while carrying any deferred pending indication into the reopened epoch. */
    internal fun reopen(
        expectedSealedFrameAdmissionEpoch: Long,
        preparedEpoch: PreparedEpoch,
    ) {
        check(preparedEpoch.targetIdentity === targetIdentity)
        check(expectedSealedFrameAdmissionEpoch < Long.MAX_VALUE)
        check(preparedEpoch.frameAdmissionEpoch == expectedSealedFrameAdmissionEpoch + 1L)
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch
            check(snapshot.disposition == AdmissionDisposition.Sealed)
            check(epoch != null && epoch.frameAdmissionEpoch == expectedSealedFrameAdmissionEpoch)
            if (state.compareAndSet(
                    snapshot,
                    preparedEpoch.state(AdmissionDisposition.Open, snapshot.pending),
                )
            ) {
                return
            }
        }
    }

    /** Permanently closes source admission while retaining the bit until the generation fence. */
    internal fun closeForRetirement() {
        while (true) {
            val snapshot = state.get()
            val epoch = snapshot.epoch ?: return
            if (snapshot.disposition == AdmissionDisposition.RetirementClosed) return
            if (snapshot.disposition == AdmissionDisposition.GenerationFenced) return
            if (state.compareAndSet(
                    snapshot,
                    epoch.state(AdmissionDisposition.RetirementClosed, snapshot.pending),
                )
            ) {
                return
            }
        }
    }

    /** Applies the irreversible generation fence and only then discards unmaterialized pending work. */
    internal fun fenceGeneration() {
        val snapshot = state.get()
        check(snapshot.disposition == AdmissionDisposition.RetirementClosed)
        state.set(generationFencedState)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            sourceAvailableFact: TargetSourceAvailableFact,
            initialFrameAdmissionEpoch: Long,
        ): TargetPendingSourceLatch = TargetPendingSourceLatch(
            targetOwner,
            constructionProof,
            targetIdentity,
            sourceAvailableFact,
            initialFrameAdmissionEpoch,
        )
    }
}
