@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

/** Normalized no-op updates bypass this transaction graph without allocating candidate work. */
internal enum class PreparedParameterCandidateClass { FrameRateOnly, SameTargetReplacement, TargetReplan }

/**
 * Closed evidence from candidate preparation. `ConcreteResourceExhaustion` requires a checked
 * device/backend limit, memory-admission denial, actual allocation failure, or an allocation/OOM
 * return with exclusive ownership restored. The future policy mapper, rather than the transaction
 * graph, translates these values to the public rejection taxonomy.
 */
internal enum class ParameterPreparationRejectionEvidence {
    RequestedPlanInvalid,
    RenderingSetupFailed,
    EncoderSetupFailed,
    ConcreteResourceExhaustion,
}

internal enum class ParameterTransactionStage {
    Preparing,
    CandidateReady,
    ReadyPermit,
    Suspended,
    Draining,
    RetargetArmed,
    RetargetStarted,
    TargetAcknowledged,
    Converging,
    Restoring,
    Completed,
}

internal sealed interface ParameterTransactionFact {
    val transaction: TransactionIdentity

    /** Ordered facts supplied directly by current controller work; caller cancellation is ingress-only. */
    sealed interface Direct : ParameterTransactionFact

    data class CandidatePrepared(
        override val transaction: TransactionIdentity,
        val candidate: CandidateIdentity,
        val owner: CompleteOwnerIdentity,
        val target: TargetIdentity? = null,
    ) : Direct

    data class FrameRateCommitReady(
        override val transaction: TransactionIdentity,
        val desired: DesiredSnapshotIdentity,
        val effective: EffectiveSnapshotIdentity,
        val pacingReset: FramePacingResetFact,
    ) : Direct

    data class ReadyPermitAcquired(override val transaction: TransactionIdentity) : Direct

    data class DrainCompleted(
        override val transaction: TransactionIdentity,
        val effective: EffectiveSnapshotIdentity,
    ) : Direct

    data class RetargetStarted(
        override val transaction: TransactionIdentity,
        val target: TargetIdentity,
    ) : Direct

    data class TargetAcknowledged(
        override val transaction: TransactionIdentity,
        val target: TargetIdentity,
    ) : Direct

    data class OwnerCommitReady(
        override val transaction: TransactionIdentity,
        val candidate: CandidateIdentity,
        val owner: CompleteOwnerIdentity,
        val effective: EffectiveSnapshotIdentity,
    ) : Direct

    data class CandidatePreparationFailed(
        override val transaction: TransactionIdentity,
        val candidate: CandidateIdentity,
        val evidence: ParameterPreparationRejectionEvidence,
        val ownership: ReturnedCandidatePreparationOwnership,
    ) : Direct

    data class CandidatePreparationStartedTimedOut(
        override val transaction: TransactionIdentity,
        val candidate: CandidateIdentity,
    ) : Direct

    data class ReadyPermitRejected(override val transaction: TransactionIdentity) : Direct

    data class RetargetStartTimedOut(override val transaction: TransactionIdentity) : Direct

    data class Superseded(override val transaction: TransactionIdentity) : Direct

    data class Cancelled(override val transaction: TransactionIdentity) : ParameterTransactionFact
}

internal sealed interface ParameterTransition {
    data class Advanced(val transaction: ParameterTransaction) : ParameterTransition

    data class WaiterDetached(val transaction: ParameterTransaction) : ParameterTransition

    data class Superseded(val transaction: ParameterTransaction) : ParameterTransition

    data object Stale : ParameterTransition

    data object Illegal : ParameterTransition
}

internal data class ParameterTransaction(
    val identity: TransactionIdentity,
    val desired: DesiredSnapshotIdentity,
    val previousEffective: EffectiveSnapshotIdentity,
    val candidate: CandidateIdentity,
    val candidateClass: PreparedParameterCandidateClass,
    val stage: ParameterTransactionStage = ParameterTransactionStage.Preparing,
    val candidateOwner: CompleteOwnerIdentity? = null,
    val candidateTarget: TargetIdentity? = null,
    val waiterAttached: Boolean = true,
    val superseded: Boolean = false,
) {
    init {
        require(isStructurallyValid()) { "The parameter transaction stage is structurally invalid." }
        require(!superseded || stage == ParameterTransactionStage.RetargetStarted || stage == ParameterTransactionStage.Completed) {
            "Only a started mutation or completed transaction may be superseded."
        }
        require(
            waiterAttached ||
                    stage == ParameterTransactionStage.RetargetStarted ||
                    stage == ParameterTransactionStage.TargetAcknowledged ||
                    stage == ParameterTransactionStage.Converging ||
                    stage == ParameterTransactionStage.Restoring ||
                    stage == ParameterTransactionStage.Completed,
        ) { "A waiter can detach only at a restoration, started-mutation, or completed boundary." }
        require(
            waiterAttached ||
                    !superseded ||
                    candidateClass == PreparedParameterCandidateClass.TargetReplan &&
                    candidateOwner != null &&
                    candidateTarget != null,
        ) { "A detached superseded transaction must own a started target mutation." }
    }

    /**
     * Applies one ordered fact directly under the future controller gate. External facts receive
     * their ingress sequence before this call; they are never conflated in [ControllerIngressStore].
     */
    fun accept(fact: ParameterTransactionFact): ParameterTransition {
        if (fact.transaction != identity || stage == ParameterTransactionStage.Completed) return ParameterTransition.Stale
        if (hasStaleFence(fact)) return ParameterTransition.Stale
        if (superseded) {
            return when (fact) {
                is ParameterTransactionFact.TargetAcknowledged ->
                    if (stage == ParameterTransactionStage.RetargetStarted && fact.target == candidateTarget) {
                        ParameterTransition.Superseded(copy(stage = ParameterTransactionStage.Completed))
                    } else {
                        ParameterTransition.Stale
                    }

                is ParameterTransactionFact.Cancelled ->
                    if (stage == ParameterTransactionStage.RetargetStarted && waiterAttached) {
                        ParameterTransition.WaiterDetached(copy(waiterAttached = false))
                    } else {
                        ParameterTransition.Stale
                    }

                else -> ParameterTransition.Stale
            }
        }
        val next = when (fact) {
            is ParameterTransactionFact.CandidatePrepared -> candidatePrepared(fact)
            is ParameterTransactionFact.FrameRateCommitReady -> frameRateCommitReady(fact)
            is ParameterTransactionFact.ReadyPermitAcquired -> targetReplanAdvance(
                ParameterTransactionStage.ReadyPermit,
            )

            is ParameterTransactionFact.DrainCompleted -> advance(
                ParameterTransactionStage.Draining,
                ParameterTransactionStage.RetargetArmed,
            )

            is ParameterTransactionFact.RetargetStarted -> targetAdvance(
                fact.target,
                ParameterTransactionStage.RetargetArmed,
                ParameterTransactionStage.RetargetStarted,
            )

            is ParameterTransactionFact.TargetAcknowledged -> targetAdvance(
                fact.target,
                ParameterTransactionStage.RetargetStarted,
                ParameterTransactionStage.TargetAcknowledged,
            )

            is ParameterTransactionFact.OwnerCommitReady -> ownerCommitReady(fact)
            is ParameterTransactionFact.CandidatePreparationFailed -> if (
                candidateClass != PreparedParameterCandidateClass.FrameRateOnly
            ) {
                advance(ParameterTransactionStage.Preparing, ParameterTransactionStage.Completed)
            } else {
                null
            }

            is ParameterTransactionFact.CandidatePreparationStartedTimedOut -> if (
                candidateClass != PreparedParameterCandidateClass.FrameRateOnly
            ) {
                advance(ParameterTransactionStage.Preparing, ParameterTransactionStage.Completed)
            } else {
                null
            }

            is ParameterTransactionFact.ReadyPermitRejected -> targetReplanAdvance(
                ParameterTransactionStage.Completed,
            )

            is ParameterTransactionFact.RetargetStartTimedOut -> advance(
                ParameterTransactionStage.RetargetArmed,
                ParameterTransactionStage.Restoring,
            )

            is ParameterTransactionFact.Superseded -> return supersede()
            is ParameterTransactionFact.Cancelled -> return cancel()
        }
        return next?.let(ParameterTransition::Advanced) ?: ParameterTransition.Illegal
    }

    private fun candidatePrepared(fact: ParameterTransactionFact.CandidatePrepared): ParameterTransaction? =
        if (stage == ParameterTransactionStage.Preparing) {
            val targetValid = when (candidateClass) {
                PreparedParameterCandidateClass.FrameRateOnly -> false
                PreparedParameterCandidateClass.SameTargetReplacement -> fact.target == null
                PreparedParameterCandidateClass.TargetReplan -> fact.target != null
            }
            if (targetValid) {
                copy(
                    stage = ParameterTransactionStage.CandidateReady,
                    candidateOwner = fact.owner,
                    candidateTarget = fact.target,
                )
            } else {
                null
            }
        } else null

    private fun frameRateCommitReady(fact: ParameterTransactionFact.FrameRateCommitReady): ParameterTransaction? =
        if (
            candidateClass == PreparedParameterCandidateClass.FrameRateOnly &&
            fact.desired == desired &&
            fact.effective != previousEffective
        ) {
            advance(ParameterTransactionStage.Preparing, ParameterTransactionStage.Completed)
        } else {
            null
        }

    private fun ownerCommitReady(fact: ParameterTransactionFact.OwnerCommitReady): ParameterTransaction? {
        if (
            fact.candidate != candidate ||
            fact.owner != candidateOwner ||
            fact.effective == previousEffective
        ) return null
        val legal =
            candidateClass == PreparedParameterCandidateClass.SameTargetReplacement &&
                    stage == ParameterTransactionStage.CandidateReady ||
                    candidateClass == PreparedParameterCandidateClass.TargetReplan &&
                    stage == ParameterTransactionStage.Converging
        return if (legal) copy(stage = ParameterTransactionStage.Completed) else null
    }

    /** Reducer-owned logical suspension commit; never accepted as an external work fact. */
    internal fun commitSuspension(): ParameterTransition = ownedAdvance(
        from = ParameterTransactionStage.ReadyPermit,
        to = ParameterTransactionStage.Suspended,
    )

    /** Reducer-owned drain command; completion alone returns as an external work fact. */
    internal fun beginDrain(): ParameterTransition = ownedAdvance(
        from = ParameterTransactionStage.Suspended,
        to = ParameterTransactionStage.Draining,
    )

    /** Reducer-owned convergence command after the acknowledged target is current. */
    internal fun beginConvergence(): ParameterTransition = ownedAdvance(
        from = ParameterTransactionStage.TargetAcknowledged,
        to = ParameterTransactionStage.Converging,
    )

    /** Reducer-owned restoration commit after an unstarted retarget is rejected or cancelled. */
    internal fun commitRestoration(): ParameterTransition = ownedAdvance(
        from = ParameterTransactionStage.Restoring,
        to = ParameterTransactionStage.Completed,
    )

    /** Reducer-owned terminal invalidation closes any still-live transaction. */
    internal fun invalidateForTerminal(): ParameterTransition =
        if (stage == ParameterTransactionStage.Completed) {
            ParameterTransition.Stale
        } else {
            ParameterTransition.Advanced(copy(stage = ParameterTransactionStage.Completed))
        }

    private fun ownedAdvance(
        from: ParameterTransactionStage,
        to: ParameterTransactionStage,
    ): ParameterTransition {
        if (stage == ParameterTransactionStage.Completed) return ParameterTransition.Stale
        val next = if (candidateClass == PreparedParameterCandidateClass.TargetReplan) {
            advance(from, to)
        } else {
            null
        }
        return next?.let(ParameterTransition::Advanced) ?: ParameterTransition.Illegal
    }

    private fun cancel(): ParameterTransition = when {
        !waiterAttached -> ParameterTransition.Stale
        stage in beforeSuspensionStages -> ParameterTransition.Advanced(
            copy(stage = ParameterTransactionStage.Completed, waiterAttached = false),
        )

        stage in preRetargetLatchStages -> ParameterTransition.Advanced(
            copy(stage = ParameterTransactionStage.Restoring, waiterAttached = false),
        )

        else -> ParameterTransition.WaiterDetached(copy(waiterAttached = false))
    }

    private fun supersede(): ParameterTransition = when (stage) {
        in beforeSuspensionStages -> ParameterTransition.Advanced(
            copy(stage = ParameterTransactionStage.Completed, superseded = true),
        )

        in preRetargetLatchStages -> ParameterTransition.Superseded(
            copy(stage = ParameterTransactionStage.Completed, superseded = true),
        )

        ParameterTransactionStage.RetargetStarted -> ParameterTransition.Superseded(copy(superseded = true))
        else -> ParameterTransition.Superseded(copy(stage = ParameterTransactionStage.Completed, superseded = true))
    }

    private fun advance(from: ParameterTransactionStage, to: ParameterTransactionStage): ParameterTransaction? =
        if (stage == from) copy(stage = to) else null

    private fun targetReplanAdvance(
        to: ParameterTransactionStage,
    ): ParameterTransaction? = if (candidateClass == PreparedParameterCandidateClass.TargetReplan) {
        advance(ParameterTransactionStage.CandidateReady, to)
    } else {
        null
    }

    private fun targetAdvance(
        target: TargetIdentity,
        from: ParameterTransactionStage,
        to: ParameterTransactionStage,
    ): ParameterTransaction? = if (stage == from && candidateTarget == target) copy(stage = to) else null

    private fun hasStaleFence(fact: ParameterTransactionFact): Boolean = when (fact) {
        is ParameterTransactionFact.CandidatePrepared ->
            stage == ParameterTransactionStage.Preparing &&
                    candidateClass != PreparedParameterCandidateClass.FrameRateOnly &&
                    fact.candidate != candidate

        is ParameterTransactionFact.CandidatePreparationFailed ->
            stage == ParameterTransactionStage.Preparing &&
                    candidateClass != PreparedParameterCandidateClass.FrameRateOnly &&
                    fact.candidate != candidate

        is ParameterTransactionFact.CandidatePreparationStartedTimedOut ->
            stage == ParameterTransactionStage.Preparing &&
                    candidateClass != PreparedParameterCandidateClass.FrameRateOnly &&
                    fact.candidate != candidate

        is ParameterTransactionFact.FrameRateCommitReady ->
            stage == ParameterTransactionStage.Preparing &&
                    candidateClass == PreparedParameterCandidateClass.FrameRateOnly &&
                    (
                            fact.desired != desired ||
                                    fact.effective == previousEffective
                            )

        is ParameterTransactionFact.DrainCompleted ->
            stage == ParameterTransactionStage.Draining && fact.effective != previousEffective

        is ParameterTransactionFact.RetargetStarted ->
            stage == ParameterTransactionStage.RetargetArmed && fact.target != candidateTarget

        is ParameterTransactionFact.TargetAcknowledged ->
            stage == ParameterTransactionStage.RetargetStarted && fact.target != candidateTarget

        is ParameterTransactionFact.OwnerCommitReady -> {
            val legalStage = candidateClass == PreparedParameterCandidateClass.SameTargetReplacement &&
                    stage == ParameterTransactionStage.CandidateReady ||
                    candidateClass == PreparedParameterCandidateClass.TargetReplan &&
                    stage == ParameterTransactionStage.Converging
            legalStage && (
                    fact.candidate != candidate ||
                            fact.owner != candidateOwner ||
                            fact.effective == previousEffective
                    )
        }

        else -> false
    }

    private fun isStructurallyValid(): Boolean = when (stage) {
        ParameterTransactionStage.Preparing -> candidateOwner == null && candidateTarget == null
        ParameterTransactionStage.CandidateReady -> candidateOwner != null && candidateTargetIsValid()
        ParameterTransactionStage.ReadyPermit,
        ParameterTransactionStage.Suspended,
        ParameterTransactionStage.Draining,
        ParameterTransactionStage.RetargetArmed,
        ParameterTransactionStage.RetargetStarted,
        ParameterTransactionStage.TargetAcknowledged,
        ParameterTransactionStage.Converging,
        ParameterTransactionStage.Restoring,
            -> candidateClass == PreparedParameterCandidateClass.TargetReplan &&
                candidateOwner != null && candidateTarget != null

        ParameterTransactionStage.Completed -> candidateTargetIsValid()
    }

    private fun candidateTargetIsValid(): Boolean = when (candidateClass) {
        PreparedParameterCandidateClass.FrameRateOnly -> candidateOwner == null && candidateTarget == null
        PreparedParameterCandidateClass.SameTargetReplacement -> candidateTarget == null
        PreparedParameterCandidateClass.TargetReplan ->
            candidateOwner == null && candidateTarget == null ||
                    candidateOwner != null && candidateTarget != null
    }

    internal companion object {
        private val beforeSuspensionStages = setOf(
            ParameterTransactionStage.Preparing,
            ParameterTransactionStage.CandidateReady,
            ParameterTransactionStage.ReadyPermit,
        )
        private val preRetargetLatchStages = setOf(
            ParameterTransactionStage.Suspended,
            ParameterTransactionStage.Draining,
            ParameterTransactionStage.RetargetArmed,
        )

        internal fun admit(fact: ControllerDirectFact.ParameterAdmitted): ParameterTransaction = ParameterTransaction(
            identity = fact.origin.transaction,
            desired = fact.desired,
            previousEffective = fact.previousEffective,
            candidate = fact.origin.candidate,
            candidateClass = fact.candidateClass,
        )
    }
}
