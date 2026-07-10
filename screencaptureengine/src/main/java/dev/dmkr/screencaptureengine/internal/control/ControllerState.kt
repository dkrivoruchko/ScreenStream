@file:Suppress("unused") // Dormant until controller reducer integration.

package dev.dmkr.screencaptureengine.internal.control

/** Lossless processed pause state. A resumed callback may leave one unprocessed freshness debt. */
internal class ControllerPauseState private constructor(
    val physicalPaused: Boolean,
    val debtSequence: IngressSequence?,
) {
    init {
        require(!physicalPaused || debtSequence != null) {
            "A physical pause must retain its accepted freshness epoch."
        }
    }

    val blocksActive: Boolean
        get() = physicalPaused || debtSequence != null

    internal companion object {
        internal val Clear: ControllerPauseState = ControllerPauseState(false, null)

        internal fun from(evidence: PauseEvidence): ControllerPauseState = ControllerPauseState(
            physicalPaused = evidence.physicalPaused,
            debtSequence = evidence.debtSequence,
        )
    }
}

internal sealed interface ControllerRunningOutput {
    val previousEffective: ControllerEffectiveSnapshot

    class Active internal constructor(
        val effective: ControllerEffectiveSnapshot,
    ) : ControllerRunningOutput {
        override val previousEffective: ControllerEffectiveSnapshot
            get() = effective
    }

    class Suspended internal constructor(
        override val previousEffective: ControllerEffectiveSnapshot,
        val currentGeometry: GeometrySnapshot,
        val principal: ReconfigurationReason,
    ) : ControllerRunningOutput
}

internal sealed interface ControllerTerminalOutcome {
    val evidence: TerminalEvidence

    class Stopped internal constructor(
        override val evidence: TerminalEvidence,
    ) : ControllerTerminalOutcome {
        init {
            require(evidence.winnerClass != TerminalWinnerClass.Failed)
        }
    }

    class Failed internal constructor(
        override val evidence: TerminalEvidence,
    ) : ControllerTerminalOutcome {
        init {
            require(evidence.winnerClass == TerminalWinnerClass.Failed)
        }
    }

    companion object {
        internal fun from(evidence: TerminalEvidence): ControllerTerminalOutcome =
            if (evidence.winnerClass == TerminalWinnerClass.Failed) Failed(evidence) else Stopped(evidence)
    }
}

internal sealed interface ControllerLifecycle {
    data object PreActive : ControllerLifecycle

    /** Final private startup state; caller resolution and projection freshness are decision data. */
    data object RetiredBeforePublic : ControllerLifecycle

    class Running internal constructor(
        val output: ControllerRunningOutput,
    ) : ControllerLifecycle

    class Terminal internal constructor(
        val outcome: ControllerTerminalOutcome,
    ) : ControllerLifecycle
}

/**
 * Permanent immutable logical controller state. Move-only ownership and its capabilities can
 * never enter this value graph.
 */
internal class ControllerState private constructor(
    val controllerIdentity: ControllerIdentity,
    val sessionIdentity: SessionIdentity,
    val revisions: CommittedRevisions,
    val geometry: ControllerGeometryAccumulator,
    val capturedContentVisible: Boolean?,
    val pause: ControllerPauseState,
    val arbiter: ReconfigurationArbiter,
    val parameterTransaction: ParameterTransaction?,
    val lifecycle: ControllerLifecycle,
) {
    init {
        validateShape()
    }

    val acceptsIngress: Boolean
        get() = lifecycle is ControllerLifecycle.PreActive || lifecycle is ControllerLifecycle.Running

    val admitsProduction: Boolean
        get() = (lifecycle as? ControllerLifecycle.Running)?.output is ControllerRunningOutput.Active

    val admitsDelivery: Boolean
        get() = admitsProduction

    val hasPublicState: Boolean
        get() = lifecycle is ControllerLifecycle.Running || lifecycle is ControllerLifecycle.Terminal

    val publicPrincipal: ReconfigurationReasonSpec?
        get() = ((lifecycle as? ControllerLifecycle.Running)?.output as? ControllerRunningOutput.Suspended)
            ?.principal
            ?.spec

    /**
     * Applies one reducer-owned immutable state replacement. The lifecycle graph is checked here;
     * semantic priority, revisions and effects remain reducer policy.
     */
    internal fun evolve(
        revisions: CommittedRevisions = this.revisions,
        geometry: ControllerGeometryAccumulator = this.geometry,
        capturedContentVisible: Boolean? = this.capturedContentVisible,
        pause: ControllerPauseState = this.pause,
        arbiter: ReconfigurationArbiter = this.arbiter,
        parameterTransaction: ParameterTransaction? = this.parameterTransaction,
        lifecycle: ControllerLifecycle = this.lifecycle,
    ): ControllerState {
        require(lifecycle.isReachableFrom(this.lifecycle)) { "The controller lifecycle transition is unreachable." }
        lifecycle.requireEffectiveContinuityFrom(this.lifecycle)
        require(geometry.mode == this.geometry.mode) { "The controller geometry mode is fixed at creation." }
        revisions.requireNotBefore(this.revisions)
        if (lifecycle is ControllerLifecycle.Terminal) {
            require(revisions.geometryRevision == this.revisions.geometryRevision)
            require(revisions.targetRevision == this.revisions.targetRevision)
        }
        if (this.lifecycle === ControllerLifecycle.RetiredBeforePublic || this.lifecycle is ControllerLifecycle.Terminal) {
            require(
                revisions === this.revisions &&
                        geometry === this.geometry &&
                        capturedContentVisible == this.capturedContentVisible &&
                        pause === this.pause &&
                        arbiter === this.arbiter &&
                        parameterTransaction === this.parameterTransaction &&
                        lifecycle === this.lifecycle,
            ) { "A committed retirement or terminal state is immutable." }
            return this
        }
        return ControllerState(
            controllerIdentity = controllerIdentity,
            sessionIdentity = sessionIdentity,
            revisions = revisions,
            geometry = geometry,
            capturedContentVisible = capturedContentVisible,
            pause = pause,
            arbiter = arbiter,
            parameterTransaction = parameterTransaction,
            lifecycle = lifecycle,
        )
    }

    private fun validateShape() {
        require(geometry.mode == ControllerGeometryMode.CapturedResizeAuthoritative || capturedContentVisible == null) {
            "Authoritative content visibility exists only in the captured-resize API band."
        }
        require(
            arbiter.associatedParameterTransaction == null ||
                    arbiter.associatedParameterTransaction == parameterTransaction?.identity,
        ) { "The arbiter cannot associate a foreign or absent parameter transaction." }
        require(parameterTransaction?.stage != ParameterTransactionStage.Completed) {
            "A completed parameter transaction cannot remain live in controller state."
        }
        require(parameterTransaction.isCompatibleWith(lifecycle)) {
            "The live parameter transaction stage contradicts the controller lifecycle."
        }
        require(
            parameterTransaction == null ||
                    parameterTransaction.previousEffective ==
                    (lifecycle as? ControllerLifecycle.Running)?.output?.previousEffective?.identity,
        ) { "The live parameter transaction must fence the exact last committed Active snapshot." }
        val acceptedGeometry = geometry.lastAcceptedGeometry
        when (val phase = lifecycle) {
            ControllerLifecycle.PreActive -> {
                require(revisions.outputRevision == 0L) {
                    "Pre-Active state cannot own a committed public output."
                }
                require(parameterTransaction == null)
            }

            ControllerLifecycle.RetiredBeforePublic -> {
                require(revisions.outputRevision == 0L)
                requireTerminalShape()
            }

            is ControllerLifecycle.Running -> {
                require(revisions.outputRevision > 0L && revisions.geometryRevision > 0L && revisions.targetRevision > 0L)
                when (val output = phase.output) {
                    is ControllerRunningOutput.Active -> {
                        require(acceptedGeometry == output.effective.geometry)
                        require(geometry.metricsUntrustedEvidence == null)
                        require(geometry.capturedResizeUntrustedEvidence == null)
                        require(!pause.blocksActive)
                        require(arbiter.reasons.isEmpty())
                        require(arbiter.pending == null)
                    }

                    is ControllerRunningOutput.Suspended -> {
                        require(acceptedGeometry == output.currentGeometry)
                        require(arbiter.principal == output.principal)
                    }
                }
            }

            is ControllerLifecycle.Terminal -> {
                require(
                    revisions.geometryRevision > 0L &&
                            revisions.targetRevision > 0L &&
                            revisions.outputRevision > 0L,
                )
                requireTerminalShape()
            }
        }
    }

    private fun requireTerminalShape() {
        require(parameterTransaction == null)
        require(arbiter.activeReconfiguration == null)
        require(arbiter.associatedParameterTransaction == null)
        require(arbiter.pending == null)
        require(arbiter.reasons.isEmpty())
    }

    internal companion object {
        internal fun create(
            controllerIdentity: ControllerIdentity,
            sessionIdentity: SessionIdentity,
            geometryMode: ControllerGeometryMode,
        ): ControllerState = ControllerState(
            controllerIdentity = controllerIdentity,
            sessionIdentity = sessionIdentity,
            revisions = CommittedRevisions(),
            geometry = ControllerGeometryAccumulator.create(geometryMode),
            capturedContentVisible = null,
            pause = ControllerPauseState.Clear,
            arbiter = ReconfigurationArbiter(),
            parameterTransaction = null,
            lifecycle = ControllerLifecycle.PreActive,
        )
    }
}

private fun ControllerLifecycle.isReachableFrom(previous: ControllerLifecycle): Boolean = when (previous) {
    ControllerLifecycle.PreActive ->
        this is ControllerLifecycle.PreActive ||
                this is ControllerLifecycle.RetiredBeforePublic ||
                this is ControllerLifecycle.Running && output is ControllerRunningOutput.Active

    is ControllerLifecycle.Running -> this is ControllerLifecycle.Running || this is ControllerLifecycle.Terminal
    ControllerLifecycle.RetiredBeforePublic -> this === previous
    is ControllerLifecycle.Terminal -> this === previous
}

private fun CommittedRevisions.requireNotBefore(previous: CommittedRevisions) {
    require(geometryRevision >= previous.geometryRevision)
    require(targetRevision >= previous.targetRevision)
    require(outputRevision >= previous.outputRevision)
}

private fun ParameterTransaction?.isCompatibleWith(lifecycle: ControllerLifecycle): Boolean {
    this ?: return true
    val running = lifecycle as? ControllerLifecycle.Running ?: return false
    return when (running.output) {
        is ControllerRunningOutput.Active -> when (candidateClass) {
            PreparedParameterCandidateClass.FrameRateOnly -> stage == ParameterTransactionStage.Preparing
            PreparedParameterCandidateClass.SameTargetReplacement -> stage in preparationParameterStages
            PreparedParameterCandidateClass.TargetReplan ->
                stage in preparationParameterStages || stage == ParameterTransactionStage.ReadyPermit
        }

        is ControllerRunningOutput.Suspended -> when (candidateClass) {
            PreparedParameterCandidateClass.FrameRateOnly -> false
            PreparedParameterCandidateClass.SameTargetReplacement -> stage in preparationParameterStages
            PreparedParameterCandidateClass.TargetReplan ->
                stage in preparationParameterStages || stage in postSuspensionParameterStages
        }
    }
}

private val preparationParameterStages: Set<ParameterTransactionStage> = setOf(
    ParameterTransactionStage.Preparing,
    ParameterTransactionStage.CandidateReady,
)

private val postSuspensionParameterStages: Set<ParameterTransactionStage> = setOf(
    ParameterTransactionStage.Suspended,
    ParameterTransactionStage.Draining,
    ParameterTransactionStage.RetargetArmed,
    ParameterTransactionStage.RetargetStarted,
    ParameterTransactionStage.TargetAcknowledged,
    ParameterTransactionStage.Converging,
    ParameterTransactionStage.Restoring,
)

private fun ControllerLifecycle.requireEffectiveContinuityFrom(previous: ControllerLifecycle) {
    val nextOutput = (this as? ControllerLifecycle.Running)?.output ?: return
    val previousOutput = (previous as? ControllerLifecycle.Running)?.output ?: return
    val retained = when (previousOutput) {
        is ControllerRunningOutput.Active -> previousOutput.effective
        is ControllerRunningOutput.Suspended -> previousOutput.previousEffective
    }
    if (nextOutput is ControllerRunningOutput.Suspended) {
        require(nextOutput.previousEffective === retained) {
            "Suspension must retain the exact last committed Active snapshot."
        }
    }
}
