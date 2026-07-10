@file:Suppress("unused", "CanBeParameter") // Dormant immutable inputs for the future reducer.

package dev.dmkr.screencaptureengine.internal.control

private const val TERMINAL_SLOT_COUNT = 18
private const val MAX_TURN_FACTS = 26

/** Exact normalized no-op proof. It carries no provider reference or ownership capability. */
internal class NormalizedNoOpReady(
    val fact: ControllerDirectFact.NormalizedNoOpReady,
    val output: NormalizedOutputValues,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = fact
    val desired: DesiredSnapshotIdentity
        get() = fact.desired
}

/** Full startup commit inputs; the direct fact itself remains a sequenced identity fence. */
internal class PrevalidatedInitialActive(
    val fact: ControllerDirectFact.InitialActiveReady,
    val target: ControllerTargetSnapshot,
    val effective: ControllerEffectiveSnapshot,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = fact
    init {
        require(effective.desiredIdentity == fact.desired)
        require(fact.target == target.identity && effective.targetIdentity == fact.target)
        require(target.assignment == TargetAssignmentEvidence.Acknowledged)
        require(fact.owner == effective.completeOwnerIdentity)
        require(fact.effective == effective.identity)
    }
}

/** Full store inputs for the only parameter path that retains its complete owner. */
internal class PrevalidatedFrameRateCommit(
    val direct: ControllerDirectFact.Parameter,
    val session: SessionIdentity,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.FrameRateCommitReady,
    val output: NormalizedOutputValues,
    val effective: ControllerEffectiveSnapshot,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct

    init {
        require(direct.session == session)
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(transaction.candidateClass == PreparedParameterCandidateClass.FrameRateOnly)
        require(transaction.stage == ParameterTransactionStage.Preparing)
        require(fact.transaction == transaction.identity)
        require(fact.desired == transaction.desired)
        require(fact.effective == effective.identity && effective.identity != transaction.previousEffective)
        require(effective.desiredIdentity == fact.desired)
        require(effective.output == output)
        require(fact.pacingReset.matches(output.frameRate))
        (fact.pacingReset as? FramePacingResetFact.PeriodicRefresh)?.source?.let { source ->
            require(source.previousEffective == transaction.previousEffective)
            require(source.target == effective.targetIdentity)
        }
    }
}

/** Returned candidate-preparation evidence with exact transaction and candidate fences. */
internal class PrevalidatedCandidatePrepared(
    val direct: ControllerDirectFact.Parameter,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.CandidatePrepared,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct
    init {
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(transaction.stage == ParameterTransactionStage.Preparing)
        require(transaction.candidateClass != PreparedParameterCandidateClass.FrameRateOnly)
        require(fact.transaction == transaction.identity)
        require(fact.candidate == transaction.candidate)
        require((fact.target == null) == (transaction.candidateClass == PreparedParameterCandidateClass.SameTargetReplacement))
    }
}

/** Failed candidate-preparation evidence; disposition remains future reducer policy. */
internal class PrevalidatedCandidatePreparationFailed(
    val direct: ControllerDirectFact.Parameter,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.CandidatePreparationFailed,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct

    init {
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(transaction.stage == ParameterTransactionStage.Preparing)
        require(transaction.candidateClass != PreparedParameterCandidateClass.FrameRateOnly)
        require(fact.transaction == transaction.identity)
        require(fact.candidate == transaction.candidate)
    }
}

/** Full acknowledged target value for one exact parameter target-return fact. */
internal class PrevalidatedParameterTargetAcknowledgement(
    val direct: ControllerDirectFact.Parameter,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.TargetAcknowledged,
    val target: ControllerTargetSnapshot,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct
    init {
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(transaction.stage == ParameterTransactionStage.RetargetStarted)
        require(fact.transaction == transaction.identity)
        require(fact.target == transaction.candidateTarget && target.identity == fact.target)
        require(target.assignment == TargetAssignmentEvidence.Acknowledged)
    }
}

/** Full fresh effective and target values for a later K4-prevalidated owner commit. */
internal class PrevalidatedOwnerCommit(
    val direct: ControllerDirectFact.Parameter,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.OwnerCommitReady,
    val target: ControllerTargetSnapshot,
    val effective: ControllerEffectiveSnapshot,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct
    init {
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(
            transaction.candidateClass == PreparedParameterCandidateClass.SameTargetReplacement &&
                    transaction.stage == ParameterTransactionStage.CandidateReady ||
                    transaction.candidateClass == PreparedParameterCandidateClass.TargetReplan &&
                    transaction.stage == ParameterTransactionStage.Converging,
        )
        require(fact.transaction == transaction.identity)
        require(fact.candidate == transaction.candidate)
        require(fact.owner == transaction.candidateOwner)
        require(fact.effective == effective.identity && effective.identity != transaction.previousEffective)
        require(effective.desiredIdentity == transaction.desired)
        require(effective.completeOwnerIdentity == fact.owner)
        require(effective.targetIdentity == target.identity)
        require(target.assignment == TargetAssignmentEvidence.Acknowledged)
        transaction.candidateTarget?.let { require(target.identity == it) }
    }
}

/** Full fresh reconfiguration-owner causal values; Store authority is minted later by K4. */
internal class PrevalidatedRecoveryOwnerCommit(
    val fact: ControllerDirectFact.CompleteOwnerReady,
    val effective: ControllerEffectiveSnapshot,
    val previousEffective: EffectiveSnapshotIdentity,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = fact
    init {
        require(fact.owner == effective.completeOwnerIdentity)
        require(fact.effective == effective.identity && effective.identity != previousEffective)
    }
}

/** Full acknowledged target value for startup or automatic recovery. */
internal class PrevalidatedRecoveryTargetAcknowledgement(
    val fact: ControllerDirectFact.TargetAcknowledged,
    val target: ControllerTargetSnapshot,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = fact

    init {
        require(fact.target == target.identity)
        require(target.assignment == TargetAssignmentEvidence.Acknowledged)
    }
}

/** Exact Active-parameter provider-preparation timeout quarantine. */
internal class PrevalidatedCandidatePreparationStartedTimeout(
    val direct: ControllerDirectFact.Parameter,
    val transaction: ParameterTransaction,
    val fact: ParameterTransactionFact.CandidatePreparationStartedTimedOut,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = direct

    init {
        require(direct.fact === fact)
        require(direct.origin.transaction == transaction.identity)
        require(direct.origin.candidate == transaction.candidate)
        require(transaction.stage == ParameterTransactionStage.Preparing)
        require(transaction.candidateClass != PreparedParameterCandidateClass.FrameRateOnly)
        require(fact.transaction == transaction.identity)
        require(fact.candidate == transaction.candidate)
    }
}

/** Exact normally returned automatic-recovery candidate-preparation failure. */
internal class PrevalidatedRecoveryCandidatePreparationFailed(
    val fact: ControllerDirectFact.RecoveryCandidatePreparationFailed,
) : ControllerPreparedFact {
    override val turnFact: ControllerTurnFact
        get() = fact
}

/** One accepted record in the complete finite controller-turn snapshot. */
internal sealed interface ControllerPreparedFact {
    val turnFact: ControllerTurnFact

    val sequence: IngressSequence
        get() = turnFact.sequence

    val priority: Int
        get() = turnFact.priority
}

/** One source fact and its fresh reason identity. Reconfiguration-start identities are turn-global. */
internal sealed interface PreparedSourceFact : ControllerPreparedFact {
    val reason: ReasonToken

    class Metrics(
        override val turnFact: ControllerIngress.Metrics,
        override val reason: ReasonToken,
    ) : PreparedSourceFact

    class CapturedResize(
        override val turnFact: ControllerIngress.CapturedResize,
        override val reason: ReasonToken,
    ) : PreparedSourceFact

    class SourceTrust(
        override val turnFact: ControllerIngress.SourceTrust,
        override val reason: ReasonToken,
    ) : PreparedSourceFact

    class Pause(
        override val turnFact: ControllerIngress.Pause,
        override val reason: ReasonToken,
    ) : PreparedSourceFact
}

/** Every terminal record retains the exact identity-tagged causal payload reported by its subsystem. */
internal class PreparedTerminalFact(
    override val turnFact: ControllerIngress.Terminal,
) : ControllerPreparedFact

internal class PreparedPrePublicFact(
    override val turnFact: ControllerDirectFact.PrePublicRetirement,
) : ControllerPreparedFact

internal class PreparedCancellationFact(
    override val turnFact: ControllerIngress.Cancellation,
) : ControllerPreparedFact

internal class PreparedVisibilityFact(
    override val turnFact: ControllerIngress.Visibility,
) : ControllerPreparedFact

internal class PreparedParameterAdmissionFact(
    override val turnFact: ControllerDirectFact.ParameterAdmitted,
) : ControllerPreparedFact

/** Direct parameter progress that requires no Store-bound value proof. */
internal class PreparedParameterProgressFact(
    override val turnFact: ControllerDirectFact.Parameter,
) : ControllerPreparedFact {
    init {
        require(
            turnFact.fact is ParameterTransactionFact.ReadyPermitAcquired ||
                    turnFact.fact is ParameterTransactionFact.DrainCompleted ||
                    turnFact.fact is ParameterTransactionFact.RetargetStarted ||
                    turnFact.fact is ParameterTransactionFact.ReadyPermitRejected ||
                    turnFact.fact is ParameterTransactionFact.RetargetStartTimedOut ||
                    turnFact.fact is ParameterTransactionFact.Superseded,
        ) { "This parameter fact requires a different exact prepared wrapper." }
    }
}

internal class PreparedArbiterReasonAddedFact(
    override val turnFact: ControllerDirectFact.ArbiterReasonAdded,
) : ControllerPreparedFact

internal class PreparedArbiterReasonClearedFact(
    override val turnFact: ControllerDirectFact.ArbiterReasonCleared,
) : ControllerPreparedFact

/** The only fresh reconfiguration/candidate identity pair available to one complete turn. */
internal class ControllerReconfigurationStartIdentity(
    val reconfiguration: ReconfigurationIdentity,
    val candidate: CandidateIdentity,
)

/** One owned immutable complete turn, including any pre-public and terminal records. */
internal class ControllerPreparedTurn(
    facts: Collection<ControllerPreparedFact>,
    val reconfigurationStart: ControllerReconfigurationStartIdentity? = null,
) {
    private val facts: List<ControllerPreparedFact> = facts.toList()

    val size: Int
        get() = facts.size

    operator fun get(index: Int): ControllerPreparedFact = facts[index]

    init {
        require(TerminalEvidence.entries.size == TERMINAL_SLOT_COUNT) {
            "The prepared-turn terminal inventory must be updated atomically."
        }
        require(this.facts.isNotEmpty()) { "A complete turn must contain an accepted fact." }
        require(this.facts.size <= MAX_TURN_FACTS) { "A complete turn exceeds its structural bound." }
        require(this.facts.zipWithNext().all { (first, second) -> first precedes second }) {
            "Prepared facts must be strictly ordered by priority and ingress sequence."
        }
        require(this.facts.mapTo(mutableSetOf()) { it.sequence }.size == this.facts.size) {
            "Every prepared fact must own a distinct ingress sequence."
        }
        require(exactSlotsAreUnique(this.facts)) { "A complete turn contains a duplicate ingress/direct slot." }
        val sessions = this.facts.mapNotNull { prepared ->
            when (val fact = prepared.turnFact) {
                is ControllerIngress.Terminal -> fact.cause.fence.session
                is ControllerDirectFact -> fact.session
                else -> null
            }
        }
        require(sessions.distinct().size <= 1) { "One complete turn cannot mix session identities." }
        val sourceReasons = this.facts.filterIsInstance<PreparedSourceFact>().map { it.reason }
        require(sourceReasons.toSet().size == sourceReasons.size) {
            "Every accepted source fact must own a fresh reason identity."
        }
    }
}

private infix fun ControllerPreparedFact.precedes(other: ControllerPreparedFact): Boolean =
    priority < other.priority || priority == other.priority && sequence.value < other.sequence.value

private fun exactSlotsAreUnique(facts: List<ControllerPreparedFact>): Boolean {
    val terminals = BooleanArray(TERMINAL_SLOT_COUNT)
    var metrics = false
    var resize = false
    var metricsTrust = false
    var resizeTrust = false
    var pause = false
    var cancellation = false
    var visibility = false
    var direct = false
    for (prepared in facts) {
        when (val fact = prepared.turnFact) {
            is ControllerIngress.Terminal -> {
                if (fact.evidence.ordinal >= terminals.size || terminals[fact.evidence.ordinal]) return false
                terminals[fact.evidence.ordinal] = true
            }

            is ControllerIngress.Metrics -> if (metrics) return false else metrics = true
            is ControllerIngress.CapturedResize -> if (resize) return false else resize = true
            is ControllerIngress.SourceTrust -> if (fact.evidence == SourceTrustEvidence.InvalidResize) {
                if (resizeTrust) return false else resizeTrust = true
            } else {
                if (metricsTrust) return false else metricsTrust = true
            }

            is ControllerIngress.Pause -> if (pause) return false else pause = true
            is ControllerIngress.Cancellation -> if (cancellation) return false else cancellation = true
            is ControllerIngress.Visibility -> if (visibility) return false else visibility = true
            is ControllerDirectFact -> if (direct) return false else direct = true
        }
        if (!prepared.matchesExactWrapper()) return false
    }
    return true
}

private fun ControllerPreparedFact.matchesExactWrapper(): Boolean = when (val fact = turnFact) {
    is ControllerIngress.Terminal -> this is PreparedTerminalFact
    is ControllerIngress.Metrics -> this is PreparedSourceFact.Metrics
    is ControllerIngress.CapturedResize -> this is PreparedSourceFact.CapturedResize
    is ControllerIngress.SourceTrust -> this is PreparedSourceFact.SourceTrust
    is ControllerIngress.Pause -> this is PreparedSourceFact.Pause
    is ControllerIngress.Cancellation -> this is PreparedCancellationFact
    is ControllerIngress.Visibility -> this is PreparedVisibilityFact
    is ControllerDirectFact.PrePublicRetirement -> this is PreparedPrePublicFact
    is ControllerDirectFact.NormalizedNoOpReady -> this is NormalizedNoOpReady
    is ControllerDirectFact.ParameterAdmitted -> this is PreparedParameterAdmissionFact
    is ControllerDirectFact.InitialActiveReady -> this is PrevalidatedInitialActive
    is ControllerDirectFact.Parameter -> when (fact.fact) {
        is ParameterTransactionFact.CandidatePrepared -> this is PrevalidatedCandidatePrepared
        is ParameterTransactionFact.FrameRateCommitReady -> this is PrevalidatedFrameRateCommit
        is ParameterTransactionFact.ReadyPermitAcquired,
        is ParameterTransactionFact.DrainCompleted,
        is ParameterTransactionFact.RetargetStarted,
        is ParameterTransactionFact.ReadyPermitRejected,
        is ParameterTransactionFact.RetargetStartTimedOut,
        is ParameterTransactionFact.Superseded,
            -> this is PreparedParameterProgressFact

        is ParameterTransactionFact.TargetAcknowledged -> this is PrevalidatedParameterTargetAcknowledgement
        is ParameterTransactionFact.OwnerCommitReady -> this is PrevalidatedOwnerCommit
        is ParameterTransactionFact.CandidatePreparationFailed -> this is PrevalidatedCandidatePreparationFailed
        is ParameterTransactionFact.CandidatePreparationStartedTimedOut ->
            this is PrevalidatedCandidatePreparationStartedTimeout
    }

    is ControllerDirectFact.ArbiterReasonAdded -> this is PreparedArbiterReasonAddedFact
    is ControllerDirectFact.ArbiterReasonCleared -> this is PreparedArbiterReasonClearedFact
    is ControllerDirectFact.TargetAcknowledged -> this is PrevalidatedRecoveryTargetAcknowledgement
    is ControllerDirectFact.CompleteOwnerReady -> this is PrevalidatedRecoveryOwnerCommit
    is ControllerDirectFact.RecoveryCandidatePreparationFailed ->
        this is PrevalidatedRecoveryCandidatePreparationFailed
}

private fun FramePacingResetFact.matches(frameRate: NormalizedFrameRate): Boolean = when (this) {
    is FramePacingResetFact.Auto -> frameRate === NormalizedFrameRate.Auto
    is FramePacingResetFact.MaxFps -> frameRate is NormalizedFrameRate.MaxFps && fps == frameRate.fps
    is FramePacingResetFact.PeriodicRefresh ->
        frameRate is NormalizedFrameRate.PeriodicRefresh && intervalMillis == frameRate.intervalMillis
}
