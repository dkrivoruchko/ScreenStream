package io.screenstream.engine.internal.session

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.internal.android.CaptureMetricsAttachmentAccess
import io.screenstream.engine.internal.android.CaptureMetricsDisplayAssociation
import io.screenstream.engine.internal.android.CaptureMetricsHandleResult
import io.screenstream.engine.internal.android.CaptureMetricsSourceProvenance
import io.screenstream.engine.internal.settlement.ControlWakeSubmissionDisposition
import io.screenstream.engine.internal.settlement.ControlWakeThrowableDisposition
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition

/** Allocation-free helpers for the raw Metrics stage owned by [SessionAuthority]. */
internal object SessionMetricsReducer {
    internal fun beginLocked(stage: SessionMetricsRawStage, observationIdentity: Long) {
        stage.observationIdentity = observationIdentity
        stage.version = 1L
        stage.semanticOrdinal = 0L
        stage.sourceProvenance = null
        stage.hasEarliestPositive = false
        stage.earliestPositiveOrdinal = 0L
        stage.earliestPositiveNanos = Long.MIN_VALUE
        stage.earliestPositiveMetrics = null
        stage.hasFirstPreActiveLoss = false
        stage.firstPreActiveLossOrdinal = 0L
        stage.firstPreActiveLossNanos = Long.MIN_VALUE
        stage.hasLatestSample = false
        stage.latestSampleOrdinal = 0L
        stage.latestSampleNanos = Long.MIN_VALUE
        stage.latestMetrics = null
        stage.latestHasDisplayAssociation = false
        stage.latestDisplayId = 0
        stage.latestAssociationIdentity = 0L
        stage.latestValidityEpoch = 0L
        stage.hasFirstTerminal = false
        stage.firstTerminalOrdinal = 0L
        stage.firstTerminalNanos = Long.MIN_VALUE
        stage.firstTerminalKind = null
        stage.firstTerminalCause = null
        stage.firstTerminalPhase = null
        stage.phase = SessionMetricsSemanticPhase.AwaitingJointReadiness
        stage.failureProblem = null
        stage.failureCause = null
    }

    internal fun foldSampleLocked(
        stage: SessionMetricsRawStage,
        preActive: Boolean,
        ordinal: Long,
        sampledAtNanos: Long,
        metrics: CaptureMetrics?,
        association: CaptureMetricsDisplayAssociation?,
    ): Boolean {
        val hasAssociation = association != null
        val duplicate = stage.hasLatestSample && stage.latestMetrics == metrics &&
                stage.latestHasDisplayAssociation == hasAssociation &&
                (!hasAssociation || association != null && stage.latestDisplayId == association.displayId &&
                        stage.latestAssociationIdentity == association.associationIdentity &&
                        stage.latestValidityEpoch == association.validityEpoch)
        if (duplicate) return false

        stage.hasLatestSample = true
        stage.latestSampleOrdinal = ordinal
        stage.latestSampleNanos = sampledAtNanos
        stage.latestMetrics = metrics
        stage.latestHasDisplayAssociation = hasAssociation
        if (association != null) {
            stage.latestDisplayId = association.displayId
            stage.latestAssociationIdentity = association.associationIdentity
            stage.latestValidityEpoch = association.validityEpoch
        }

        if (metrics != null && !stage.hasEarliestPositive) {
            stage.hasEarliestPositive = true
            stage.earliestPositiveOrdinal = ordinal
            stage.earliestPositiveNanos = sampledAtNanos
            stage.earliestPositiveMetrics = metrics
        } else if (metrics == null && stage.hasEarliestPositive && preActive &&
            !stage.hasFirstPreActiveLoss
        ) {
            stage.hasFirstPreActiveLoss = true
            stage.firstPreActiveLossOrdinal = ordinal
            stage.firstPreActiveLossNanos = sampledAtNanos
            if (isJointReady(stage.phase)) {
                stageFailureLocked(stage, ScreenCaptureProblem.CaptureUnavailable, null)
            }
        }
        return true
    }

    internal fun foldTerminalLocked(
        stage: SessionMetricsRawStage,
        ordinal: Long,
        observedAtNanos: Long,
        kind: SessionMetricsTerminalKind,
        cause: Throwable?,
    ) {
        stage.hasFirstTerminal = true
        stage.firstTerminalOrdinal = ordinal
        stage.firstTerminalNanos = observedAtNanos
        stage.firstTerminalKind = kind
        stage.firstTerminalCause = cause
        stage.firstTerminalPhase = if (isJointReady(stage.phase)) {
            SessionMetricsTerminalPhase.AfterJointReadiness
        } else {
            SessionMetricsTerminalPhase.BeforeJointReadiness
        }
        if (stage.firstTerminalPhase == SessionMetricsTerminalPhase.AfterJointReadiness) {
            if (kind == SessionMetricsTerminalKind.Completed) {
                stage.phase = SessionMetricsSemanticPhase.CompletionPending
            } else {
                stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, cause)
            }
        }
    }

    /** Must run with Session gate -> attachment.settlementGate held in that order. */
    internal fun foldAttachmentLocked(
        stage: SessionMetricsRawStage,
        attachment: CaptureMetricsAttachmentAccess,
        observedAtNanos: Long,
        endpointFailure: Throwable?,
        refreshFailure: Throwable?,
        closeFailure: Throwable?,
    ): Boolean {
        val phaseBefore = stage.phase
        val problemBefore = stage.failureProblem
        val causeBefore = stage.failureCause
        val occurrence = attachment.occurrence
        val returnCell = occurrence.returnCell
        val evidence = returnCell.evidence
        val deadlineArmed = attachment.deadline.disposition == DeadlineDisposition.Armed
        val returnComplete = returnCell.disposition != OperationReturnDisposition.Empty
        val returnTimely = deadlineArmed && returnComplete &&
                returnCell.settlementNanos < attachment.deadline.deadlineNanos
        val expiryObserved = attachment.deadline.disposition == DeadlineDisposition.Expired ||
                deadlineArmed && (returnComplete && !returnTimely ||
                observedAtNanos >= attachment.deadline.deadlineNanos)

        // The recorded settlement T is classified before an observation-now expiry. While readiness is
        // unresolved, the typed outward result remains authoritative over callbacks staged before return.
        if (!isJointReady(stage.phase) && returnTimely &&
            returnCell.disposition == OperationReturnDisposition.Thrown
        ) {
            attachment.deadline.retireLocked()
            stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, returnCell.throwable)
            return changed(stage, phaseBefore, problemBefore, causeBefore)
        }
        var attachmentAdopted = false
        if (returnTimely && returnCell.disposition == OperationReturnDisposition.Normal) {
            when (val handle = evidence.handleResult) {
                is CaptureMetricsHandleResult.Adopted -> {
                    if (handle.owner !== evidence.subscriptionOwner || !handle.owner.isBound) {
                        attachment.deadline.retireLocked()
                        stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, null)
                        return changed(stage, phaseBefore, problemBefore, causeBefore)
                    }
                    attachmentAdopted = true
                }

                CaptureMetricsHandleResult.StructurallyAbsent,
                CaptureMetricsHandleResult.Pending -> {
                    attachment.deadline.retireLocked()
                    stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, null)
                    return changed(stage, phaseBefore, problemBefore, causeBefore)
                }
            }
        }

        val attachmentFailure = attachment.readinessGuardFailure
            ?: occurrence.submissionAmbiguousFatal
            ?: occurrence.submissionFailure
            ?: endpointFailure
            ?: refreshFailure
            ?: closeFailure
            ?: if (attachment.wakeLink.submissionDisposition == ControlWakeSubmissionDisposition.Rejected &&
                attachment.wakeLink.schedulingThrowableDisposition ==
                ControlWakeThrowableDisposition.NonfatalException
            ) attachment.wakeLink.schedulingFailure else null
        if (attachmentFailure != null ||
            occurrence.entryDisposition != OperationEntryDisposition.Entered &&
            (occurrence.submissionDisposition == OperationSubmissionDisposition.Rejected ||
                    occurrence.submissionDisposition == OperationSubmissionDisposition.Cancelled)
        ) {
            attachment.deadline.retireLocked()
            stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, attachmentFailure)
            return changed(stage, phaseBefore, problemBefore, causeBefore)
        }

        if (stage.hasFirstTerminal && stage.firstTerminalKind == SessionMetricsTerminalKind.Failed &&
            !isJointReady(stage.phase)
        ) {
            if (returnComplete || expiryObserved) {
                attachment.deadline.retireLocked()
                stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, stage.firstTerminalCause)
            }
            return changed(stage, phaseBefore, problemBefore, causeBefore)
        }

        if (stage.phase == SessionMetricsSemanticPhase.AwaitingJointReadiness &&
            !stage.hasFirstTerminal && !stage.hasFirstPreActiveLoss &&
            stage.hasEarliestPositive && attachmentAdopted && attachment.deadline.startNanos >= 0L &&
            stage.earliestPositiveNanos >= attachment.deadline.startNanos &&
            stage.earliestPositiveNanos < attachment.deadline.deadlineNanos
        ) {
            stage.phase = SessionMetricsSemanticPhase.JointReadiness
            attachment.deadline.retireLocked()
            return true
        }

        if (stage.phase == SessionMetricsSemanticPhase.AwaitingJointReadiness &&
            stage.hasFirstPreActiveLoss && (returnComplete || expiryObserved)
        ) {
            attachment.deadline.retireLocked()
            stageFailureLocked(stage, ScreenCaptureProblem.CaptureUnavailable, null)
            return changed(stage, phaseBefore, problemBefore, causeBefore)
        }
        if (stage.phase == SessionMetricsSemanticPhase.AwaitingJointReadiness &&
            stage.hasFirstTerminal && stage.firstTerminalKind == SessionMetricsTerminalKind.Completed &&
            (returnComplete || expiryObserved)
        ) {
            attachment.deadline.retireLocked()
            stageFailureLocked(stage, ScreenCaptureProblem.CaptureUnavailable, null)
            return changed(stage, phaseBefore, problemBefore, causeBefore)
        }
        if (stage.phase == SessionMetricsSemanticPhase.AwaitingJointReadiness && expiryObserved) {
            attachment.deadline.expireLocked()
            stageFailureLocked(stage, ScreenCaptureProblem.CaptureUnavailable, null)
        }
        return changed(stage, phaseBefore, problemBefore, causeBefore)
    }

    internal fun decisionLocked(stage: SessionMetricsRawStage): SessionMetricsControlDecision =
        when (stage.phase) {
            SessionMetricsSemanticPhase.JointReadiness -> SessionMetricsControlDecision.Readiness
            SessionMetricsSemanticPhase.CompletionPending -> SessionMetricsControlDecision.Completion
            SessionMetricsSemanticPhase.FailurePending -> SessionMetricsControlDecision.Failure
            else -> SessionMetricsControlDecision.None
        }

    /** Builds only an uncommitted candidate and must be called after all gates are released. */
    internal fun readinessCandidate(
        state: SessionState,
        metrics: CaptureMetrics?,
    ): SessionState? {
        val startup = state.startup
        val owner = state.runtimeOwnership?.metrics
        if (startup?.stage != SessionStartupStage.AwaitingMetrics || owner == null || metrics == null) {
            return null
        }
        val geometry = CaptureGeometry.create(metrics.widthPx, metrics.heightPx, metrics.densityDpi)
        return state.copy(
            startup = SessionStartupState(
                identities = startup.identities,
                stage = SessionStartupStage.AwaitingProjectionCallbackRegistration,
                laneReadiness = startup.laneReadiness,
                metricsReadiness = owner.jointReadinessReceipt,
                captureGeometry = geometry,
            ),
        )
    }

    internal fun applyControlCommitLocked(
        stage: SessionMetricsRawStage,
        decision: SessionMetricsControlDecision,
        terminalCutoff: Boolean,
    ) {
        stage.phase = if (terminalCutoff) {
            SessionMetricsSemanticPhase.Cutoff
        } else {
            when (decision) {
                SessionMetricsControlDecision.Readiness -> SessionMetricsSemanticPhase.Open
                SessionMetricsControlDecision.Completion -> SessionMetricsSemanticPhase.ClosedRetainingLast
                SessionMetricsControlDecision.Failure -> stage.phase
                SessionMetricsControlDecision.None -> stage.phase
            }
        }
    }

    internal fun stageClockFailureLocked(
        stage: SessionMetricsRawStage,
        cause: Throwable,
    ) {
        stageFailureLocked(stage, ScreenCaptureProblem.InternalFailure, cause)
    }

    private fun stageFailureLocked(
        stage: SessionMetricsRawStage,
        problem: ScreenCaptureProblem,
        cause: Throwable?,
    ) {
        stage.phase = SessionMetricsSemanticPhase.FailurePending
        stage.failureProblem = problem
        stage.failureCause = cause
    }

    private fun isJointReady(phase: SessionMetricsSemanticPhase): Boolean = when (phase) {
        SessionMetricsSemanticPhase.JointReadiness,
        SessionMetricsSemanticPhase.Open,
        SessionMetricsSemanticPhase.CompletionPending,
        SessionMetricsSemanticPhase.ClosedRetainingLast,
            -> true
        else -> false
    }

    private fun changed(
        stage: SessionMetricsRawStage,
        phaseBefore: SessionMetricsSemanticPhase,
        problemBefore: ScreenCaptureProblem?,
        causeBefore: Throwable?,
    ): Boolean = stage.phase != phaseBefore || stage.failureProblem != problemBefore ||
            stage.failureCause !== causeBefore
}

/** One bounded mutable raw stage. Its sole owner and gate are SessionAuthority/sessionGate. */
internal class SessionMetricsRawStage internal constructor() {
    internal var observationIdentity: Long = 0L
    internal var version: Long = 0L
    internal var semanticOrdinal: Long = 0L
    internal var sourceProvenance: CaptureMetricsSourceProvenance? = null
    internal var hasEarliestPositive: Boolean = false
    internal var earliestPositiveOrdinal: Long = 0L
    internal var earliestPositiveNanos: Long = Long.MIN_VALUE
    internal var earliestPositiveMetrics: CaptureMetrics? = null
    internal var hasFirstPreActiveLoss: Boolean = false
    internal var firstPreActiveLossOrdinal: Long = 0L
    internal var firstPreActiveLossNanos: Long = Long.MIN_VALUE
    internal var hasLatestSample: Boolean = false
    internal var latestSampleOrdinal: Long = 0L
    internal var latestSampleNanos: Long = Long.MIN_VALUE
    internal var latestMetrics: CaptureMetrics? = null
    internal var latestHasDisplayAssociation: Boolean = false
    internal var latestDisplayId: Int = 0
    internal var latestAssociationIdentity: Long = 0L
    internal var latestValidityEpoch: Long = 0L
    internal var hasFirstTerminal: Boolean = false
    internal var firstTerminalOrdinal: Long = 0L
    internal var firstTerminalNanos: Long = Long.MIN_VALUE
    internal var firstTerminalKind: SessionMetricsTerminalKind? = null
    internal var firstTerminalCause: Throwable? = null
    internal var firstTerminalPhase: SessionMetricsTerminalPhase? = null
    internal var phase: SessionMetricsSemanticPhase = SessionMetricsSemanticPhase.Dormant
    internal var failureProblem: ScreenCaptureProblem? = null
    internal var failureCause: Throwable? = null
}

internal enum class SessionMetricsSemanticPhase {
    Dormant,
    AwaitingJointReadiness,
    JointReadiness,
    Open,
    CompletionPending,
    FailurePending,
    ClosedRetainingLast,
    Cutoff,
}

internal enum class SessionMetricsTerminalKind { Completed, Failed }
internal enum class SessionMetricsTerminalPhase { BeforeJointReadiness, AfterJointReadiness }
internal enum class SessionMetricsControlDecision { None, Readiness, Completion, Failure }
