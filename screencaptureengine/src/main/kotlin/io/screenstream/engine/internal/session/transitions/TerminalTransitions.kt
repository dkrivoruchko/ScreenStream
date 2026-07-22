package io.screenstream.engine.internal.session.transitions

import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureStopReason
import io.screenstream.engine.internal.session.SessionLifecycle
import io.screenstream.engine.internal.session.SessionTerminalCandidate
import io.screenstream.engine.internal.session.SessionTerminalContenders
import io.screenstream.engine.internal.session.SessionTerminalKind
import io.screenstream.engine.internal.session.SessionTerminalWinner
import io.screenstream.engine.internal.session.cleanup.AndroidCleanupRoot
import io.screenstream.engine.internal.session.cleanup.ControlCleanupRoot
import io.screenstream.engine.internal.session.cleanup.DeliveryCleanupRoot
import io.screenstream.engine.internal.session.cleanup.GlCleanupRoot
import io.screenstream.engine.internal.session.cleanup.JpegCleanupRoot
import io.screenstream.engine.internal.session.cleanup.MetricsCleanupRoot
import io.screenstream.engine.internal.session.cleanup.SessionCleanupTransfer
import io.screenstream.engine.internal.session.cleanup.StorageCleanupRoot
import io.screenstream.engine.internal.session.cleanup.TargetCleanupRoot
import io.screenstream.engine.internal.session.runtime.SessionRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.SessionRuntimeResidue

internal object TerminalTransitions {
    internal fun captureEnded(): SessionTerminalCandidate = SessionTerminalCandidate(
        kind = SessionTerminalKind.CaptureEnded,
        stopReason = ScreenCaptureStopReason.CaptureEnded,
        problem = null,
        cause = null,
    )

    internal fun ownerStop(): SessionTerminalCandidate = SessionTerminalCandidate(
        kind = SessionTerminalKind.OwnerStop,
        stopReason = ScreenCaptureStopReason.OwnerStop,
        problem = null,
        cause = null,
    )

    internal fun failure(problem: ScreenCaptureProblem, cause: Throwable?): SessionTerminalCandidate =
        SessionTerminalCandidate(
            kind = SessionTerminalKind.Failed,
            stopReason = null,
            problem = problem,
            cause = cause,
        )

    internal fun record(
        contenders: SessionTerminalContenders,
        candidate: SessionTerminalCandidate,
    ): SessionTerminalContenders = when (candidate.kind) {
        SessionTerminalKind.CaptureEnded -> if (contenders.captureEnded == null) {
            SessionTerminalContenders(candidate, contenders.ownerStop, contenders.failure)
        } else {
            contenders
        }

        SessionTerminalKind.OwnerStop -> if (contenders.ownerStop == null) {
            SessionTerminalContenders(contenders.captureEnded, candidate, contenders.failure)
        } else {
            contenders
        }

        SessionTerminalKind.Failed -> if (contenders.failure == null) {
            SessionTerminalContenders(contenders.captureEnded, contenders.ownerStop, candidate)
        } else {
            contenders
        }
    }

    internal fun chooseWinner(
        contenders: SessionTerminalContenders,
        lifecycle: SessionLifecycle,
        desiredParameters: ScreenCaptureParameters?,
    ): SessionTerminalWinner? {
        val selected = contenders.captureEnded ?: contenders.ownerStop ?: contenders.failure ?: return null
        val requested = desiredParameters ?: ScreenCaptureParameters()
        val lastEffective = when (lifecycle) {
            is SessionLifecycle.Active -> lifecycle.effectiveParameters
            is SessionLifecycle.Reconfiguring -> lifecycle.lastEffectiveParameters
            is SessionLifecycle.Suspended -> lifecycle.lastEffectiveParameters
            else -> null
        }
        return SessionTerminalWinner(
            kind = selected.kind,
            stopReason = selected.stopReason,
            problem = selected.problem,
            cause = selected.cause,
            requestedParameters = requested,
            lastEffectiveParameters = lastEffective,
        )
    }

    internal fun transferRuntime(ownership: SessionRuntimeOwnership): SessionCleanupTransfer =
        transferResidue(ownership)

    internal fun transferResidue(residue: SessionRuntimeResidue): SessionCleanupTransfer =
        SessionCleanupTransfer(
            sessionGeneration = residue.startupIdentity,
            metrics = residue.metrics?.let(::MetricsCleanupRoot),
            android = residue.android?.let(::AndroidCleanupRoot),
            target = residue.target?.let(::TargetCleanupRoot),
            gl = residue.gl?.let(::GlCleanupRoot),
            jpeg = residue.jpeg?.let(::JpegCleanupRoot),
            storage = residue.storage?.let(::StorageCleanupRoot),
            delivery = residue.delivery?.let(::DeliveryCleanupRoot),
            control = ControlCleanupRoot(requireNotNull(residue.control)),
        )
}
