@file:Suppress("unused") // Dormant until controller reducer integration.

package dev.dmkr.screencaptureengine.internal.control

/**
 * One sequenced fact supplied directly by the controller's current operation. These facts never
 * enter generic ingress slots; every variant retains the session and exact work-origin fences.
 */
internal sealed interface ControllerDirectFact : ControllerTurnFact {
    val session: SessionIdentity

    /** Exact pre-public failure/cancellation record; it shares the complete turn with terminal ingress. */
    class PrePublicRetirement(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val retirement: PrePublicStartRetirement,
    ) : ControllerDirectFact {
        override val priority: Int = COMMIT_PRIORITY
    }

    /** Ordered no-op acknowledgement for one current serialized parameter call. */
    data class NormalizedNoOpReady(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val call: TransactionIdentity,
        val desired: DesiredSnapshotIdentity,
    ) : ControllerDirectFact {
        override val priority: Int = COMMIT_PRIORITY
    }

    /** Ordered admission of one normalized non-noop public parameter call. */
    data class ParameterAdmitted(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Parameter,
        val desired: DesiredSnapshotIdentity,
        val previousEffective: EffectiveSnapshotIdentity,
        val candidateClass: PreparedParameterCandidateClass,
    ) : ControllerDirectFact {
        override val priority: Int = COMMIT_PRIORITY
    }

    data class InitialActiveReady(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Startup,
        val desired: DesiredSnapshotIdentity,
        val target: TargetIdentity,
        val owner: CompleteOwnerIdentity,
        val effective: EffectiveSnapshotIdentity,
    ) : ControllerDirectFact {
        override val priority: Int = COMMIT_PRIORITY
    }

    data class Parameter(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Parameter,
        val fact: ParameterTransactionFact.Direct,
    ) : ControllerDirectFact {
        init {
            require(fact.transaction == origin.transaction) { "Parameter fact and origin must identify one transaction." }
            val factCandidate: CandidateIdentity? = when (fact) {
                is ParameterTransactionFact.CandidatePrepared -> fact.candidate
                is ParameterTransactionFact.CandidatePreparationFailed -> fact.candidate
                is ParameterTransactionFact.CandidatePreparationStartedTimedOut -> fact.candidate
                is ParameterTransactionFact.FrameRateCommitReady,
                is ParameterTransactionFact.ReadyPermitAcquired,
                is ParameterTransactionFact.DrainCompleted,
                is ParameterTransactionFact.RetargetStarted,
                is ParameterTransactionFact.TargetAcknowledged,
                is ParameterTransactionFact.ReadyPermitRejected,
                is ParameterTransactionFact.RetargetStartTimedOut,
                is ParameterTransactionFact.Superseded,
                    -> null

                is ParameterTransactionFact.OwnerCommitReady -> fact.candidate
            }
            require(factCandidate == null || factCandidate == origin.candidate) {
                "Candidate-bearing parameter facts must match the direct origin."
            }
        }

        override val priority: Int = COMMIT_PRIORITY
    }

    data class ArbiterReasonAdded(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Reconfiguration,
        val token: ReasonToken,
        val reason: ReconfigurationReasonSpec,
        val fence: ArbiterFenceEvidence,
    ) : ControllerDirectFact {
        override val priority: Int = RECOVERY_PRIORITY
    }

    data class ArbiterReasonCleared(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Reconfiguration,
        val token: ReasonToken,
        val key: ReconfigurationReasonKey,
    ) : ControllerDirectFact {
        override val priority: Int = RECOVERY_PRIORITY
    }

    data class TargetAcknowledged(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Recovery,
        val target: TargetIdentity,
    ) : ControllerDirectFact {
        override val priority: Int = RECOVERY_PRIORITY
    }

    data class CompleteOwnerReady(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Reconfiguration,
        val owner: CompleteOwnerIdentity,
        val effective: EffectiveSnapshotIdentity,
    ) : ControllerDirectFact {
        override val priority: Int = RECOVERY_PRIORITY
    }

    data class RecoveryCandidatePreparationFailed(
        override val session: SessionIdentity,
        override val sequence: IngressSequence,
        val origin: ControllerFactOrigin.Reconfiguration,
        val evidence: RecoveryCandidatePreparationFailureEvidence,
        val ownership: ReturnedCandidatePreparationOwnership,
    ) : ControllerDirectFact {
        override val priority: Int = RECOVERY_PRIORITY
    }

    private companion object {
        const val COMMIT_PRIORITY = 5
        const val RECOVERY_PRIORITY = 6
    }
}

/** Exact pacing epoch replacement selected for a frame-rate-only commit. */
internal sealed interface FramePacingResetFact {
    val commitSampleNanos: Long

    class Auto(
        override val commitSampleNanos: Long,
    ) : FramePacingResetFact {
        init {
            require(commitSampleNanos >= 0L)
        }
    }

    class MaxFps(
        val fps: Int,
        override val commitSampleNanos: Long,
    ) : FramePacingResetFact {
        init {
            require(fps > 0)
            require(commitSampleNanos >= 0L)
        }
    }

    class PeriodicRefresh(
        val intervalMillis: Long,
        val source: PeriodicRefreshSourceFence,
        override val commitSampleNanos: Long,
    ) : FramePacingResetFact {
        init {
            require(intervalMillis > 0L)
            require(commitSampleNanos >= 0L)
        }
    }
}

/** Exact prior output/target fence for the periodic policy's current-source bit. */
internal sealed interface PeriodicRefreshSourceFence {
    val previousEffective: EffectiveSnapshotIdentity
    val previousOutputRevision: Long
    val target: TargetIdentity

    class NotAcquired(
        override val previousEffective: EffectiveSnapshotIdentity,
        override val previousOutputRevision: Long,
        override val target: TargetIdentity,
    ) : PeriodicRefreshSourceFence {
        init {
            require(previousOutputRevision > 0L)
        }
    }

    class Acquired(
        override val previousEffective: EffectiveSnapshotIdentity,
        override val previousOutputRevision: Long,
        override val target: TargetIdentity,
    ) : PeriodicRefreshSourceFence {
        init {
            require(previousOutputRevision > 0L)
        }
    }
}

internal enum class RecoveryCandidatePreparationFailureEvidence {
    RequestedPlanInvalid,
    RenderingSetupFailed,
    EncoderSetupFailed,
    ConcreteResourceExhaustion,
}

/** Mechanical returned-failure ownership; timeout quarantine has a separate direct fact. */
internal enum class ReturnedCandidatePreparationOwnership { NoEncoderToCleanup, EncoderNeedsCleanup }

/** Closed evidence for whether adding one arbiter reason also invalidates output freshness. */
internal enum class ArbiterFenceEvidence { None, OutputFreshness }

/** Drains one finite ingress snapshot, appends at most one direct fact, and preserves all records. */
internal object ControllerTurnFactMerge {
    fun drain(
        ingress: ControllerIngressStore,
        direct: ControllerDirectFact? = null,
    ): List<ControllerTurnFact> = buildList {
        addAll(ingress.snapshot())
        direct?.let(::add)
    }.sortedWith(compareBy({ fact: ControllerTurnFact -> fact.priority }, { it.sequence.value }))
}
