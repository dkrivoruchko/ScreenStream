package io.screenstream.engine.internal.session

import io.screenstream.engine.internal.delivery.ObservationDiagnosticRequest
import io.screenstream.engine.internal.delivery.ObservationStateSnapshot
import io.screenstream.engine.internal.delivery.ObservationStatsSnapshot
import io.screenstream.engine.internal.session.cleanup.SessionCleanupTransfer
import io.screenstream.engine.internal.target.TargetConstructionFoldDisposition
import io.screenstream.engine.internal.target.TargetConstructionFoldToken
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.session.runtime.TargetRuntimeOwnership
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.session.runtime.AndroidRuntimeOwnership

/**
 * One immutable result of a gated commit. Runtime executes these already-selected effects in field order.
 */
internal class CommittedTurn internal constructor(
    internal val publication: SessionPublicationAction? = null,
    internal val cleanupTransfer: SessionCleanupTransfer? = null,
    internal val completeStart: SessionStartCompletion? = null,
    internal val runtimeAction: SessionRuntimeAction? = null,
    internal val requestAnotherDrain: Boolean = false,
) {
    init {
        when (completeStart) {
            SessionStartCompletion.Active -> check(publication?.active != null)
            is SessionStartCompletion.Stopped -> check(publication?.stopped != null)
            is SessionStartCompletion.Failed -> check(publication?.failed != null)
            null -> Unit
        }
        if (cleanupTransfer != null) {
            check(
                publication?.stopped != null || publication?.failed != null ||
                        runtimeAction is SessionRuntimeAction.BeginCleanup,
            )
        }
    }

    internal companion object {
        internal val None: CommittedTurn = CommittedTurn()
    }
}

internal sealed interface SessionRuntimeAction {
    internal class Start internal constructor(
        internal val request: io.screenstream.engine.internal.session.runtime.SessionRuntimeStartRequest,
        internal val startupIdentity: Long,
        internal val identities: io.screenstream.engine.internal.session.runtime.SessionRuntimeIdentityPlan,
    ) : SessionRuntimeAction

    internal class BeginCleanup internal constructor(
        internal val transfer: SessionCleanupTransfer,
    ) : SessionRuntimeAction

    internal class RegisterProjectionCallback internal constructor(
        internal val startupIdentity: Long,
    ) : SessionRuntimeAction

    internal class ScheduleControlWake internal constructor(
        internal val fact: SessionControlWakeScheduleFact,
    ) : SessionRuntimeAction

    internal class CancelControlWake internal constructor(
        internal val fact: SessionControlWakeCancellationFact,
    ) : SessionRuntimeAction

    internal class ConstructGlSession internal constructor(
        internal val startupIdentity: Long,
    ) : SessionRuntimeAction

    internal class PrepareTarget internal constructor(
        internal val startupIdentity: Long,
        internal val requestedIdentity: TargetRequestedIdentity,
        internal val plan: TargetPlan,
    ) : SessionRuntimeAction

    internal class ApplyTargetConstructionFold internal constructor(
        internal val startupIdentity: Long,
        internal val owner: TargetRuntimeOwnership,
        internal val requestedIdentity: TargetRequestedIdentity,
        internal val plan: TargetPlan,
        internal val token: TargetConstructionFoldToken,
        internal val disposition: TargetConstructionFoldDisposition,
    ) : SessionRuntimeAction

    internal class InstallTargetListener internal constructor(
        internal val startupIdentity: Long,
        internal val targetOwner: TargetRuntimeOwnership,
        internal val installedTarget: io.screenstream.engine.internal.target.TargetConstructionInstalledFact,
    ) : SessionRuntimeAction

    internal class ApplyTargetListener internal constructor(
        internal val startupIdentity: Long,
        internal val androidOwner: AndroidRuntimeOwnership,
        internal val targetOwner: TargetRuntimeOwnership,
        internal val platformResult: AndroidTargetPlatformResult.ListenerInstalled,
    ) : SessionRuntimeAction

    internal class CreateVirtualDisplay internal constructor(
        internal val startupIdentity: Long,
        internal val androidOwner: AndroidRuntimeOwnership,
        internal val targetOwner: TargetRuntimeOwnership,
        internal val installedTarget: io.screenstream.engine.internal.target.TargetConstructionInstalledFact,
        internal val captureGeometry: io.screenstream.engine.CaptureGeometry,
        internal val apiBand: io.screenstream.engine.internal.android.AndroidCaptureApiBand,
    ) : SessionRuntimeAction

    internal class ApplyVirtualDisplay internal constructor(
        internal val startupIdentity: Long,
        internal val androidOwner: AndroidRuntimeOwnership,
        internal val targetOwner: TargetRuntimeOwnership,
        internal val platformResult: io.screenstream.engine.internal.android.AndroidTargetPlatformResult,
    ) : SessionRuntimeAction

    internal class ShutdownControl internal constructor(
        internal val proof: io.screenstream.engine.internal.session.cleanup.SessionControlResidueSettledProof,
    ) : SessionRuntimeAction
}

internal class SessionPublicationAction internal constructor(
    internal val starting: Boolean = false,
    internal val active: ObservationStateSnapshot.Active? = null,
    internal val reconfiguring: ObservationStateSnapshot.Reconfiguring? = null,
    internal val suspended: ObservationStateSnapshot.Suspended? = null,
    internal val finalStats: ObservationStatsSnapshot? = null,
    internal val terminalDiagnostic: ObservationDiagnosticRequest? = null,
    internal val stopped: ObservationStateSnapshot.Stopped? = null,
    internal val failed: ObservationStateSnapshot.Failed? = null,
) {
    init {
        val stateCount = (if (starting) 1 else 0) +
                (if (active != null) 1 else 0) +
                (if (reconfiguring != null) 1 else 0) +
                (if (suspended != null) 1 else 0) +
                (if (stopped != null) 1 else 0) +
                (if (failed != null) 1 else 0)
        require(stateCount <= 1)
        val terminal = stopped != null || failed != null
        check(!terminal || finalStats != null)
        check(!terminal || terminalDiagnostic != null)
        check(terminal || terminalDiagnostic == null)
    }
}

internal sealed interface SessionStartCompletion {
    internal object Active : SessionStartCompletion

    internal class Stopped internal constructor(
        internal val winner: SessionTerminalWinner,
    ) : SessionStartCompletion

    internal class Failed internal constructor(
        internal val winner: SessionTerminalWinner,
    ) : SessionStartCompletion
}
