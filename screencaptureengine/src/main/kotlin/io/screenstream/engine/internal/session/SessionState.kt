package io.screenstream.engine.internal.session

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureStopReason
import io.screenstream.engine.internal.delivery.DeliveryHandoffRecord
import io.screenstream.engine.internal.delivery.DeliveryRegistration
import io.screenstream.engine.internal.session.cleanup.SessionCleanupTransfer
import io.screenstream.engine.internal.session.runtime.ActiveTopologyEvidence
import io.screenstream.engine.internal.session.runtime.SessionRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.MetricsJointReadinessReceipt
import io.screenstream.engine.internal.session.runtime.RuntimeLaneReadiness
import io.screenstream.engine.internal.session.runtime.SessionRuntimeIdentityPlan
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.target.TargetConstructionInstalledFact
import io.screenstream.engine.internal.session.reconciliation.Resolved

/**
 * Data owned by the one Session commit authority. This type performs no platform or scheduling work.
 */
internal class SessionState internal constructor(
    internal val lifecycle: SessionLifecycle = SessionLifecycle.NotStarted,
    internal val desired: SessionDesiredParameters? = null,
    internal val currentness: SessionCurrentness = SessionCurrentness.Empty,
    internal val runtimeOwnership: SessionRuntimeOwnership? = null,
    internal val topology: SessionTopology? = null,
    internal val admissions: SessionAdmissions = SessionAdmissions.Closed,
    internal val registration: DeliveryRegistration? = null,
    internal val handoff: DeliveryHandoffRecord? = null,
    internal val terminalContenders: SessionTerminalContenders = SessionTerminalContenders(),
    internal val terminalWinner: SessionTerminalWinner? = null,
    internal val cleanupTransfer: SessionCleanupTransfer? = null,
    internal val cleanupReceipts: io.screenstream.engine.internal.session.cleanup.SessionCleanupReceipts? = null,
    internal val startup: SessionStartupState? = null,
    internal val nextIdentity: Long = 1L,
    internal val terminalCutoffApplied: Boolean = false,
    internal val controlShutdownRequested: Boolean = false,
)

internal fun SessionState.copy(
    lifecycle: SessionLifecycle = this.lifecycle,
    desired: SessionDesiredParameters? = this.desired,
    currentness: SessionCurrentness = this.currentness,
    runtimeOwnership: SessionRuntimeOwnership? = this.runtimeOwnership,
    topology: SessionTopology? = this.topology,
    admissions: SessionAdmissions = this.admissions,
    registration: DeliveryRegistration? = this.registration,
    handoff: DeliveryHandoffRecord? = this.handoff,
    terminalContenders: SessionTerminalContenders = this.terminalContenders,
    terminalWinner: SessionTerminalWinner? = this.terminalWinner,
    cleanupTransfer: SessionCleanupTransfer? = this.cleanupTransfer,
    cleanupReceipts: io.screenstream.engine.internal.session.cleanup.SessionCleanupReceipts? = this.cleanupReceipts,
    startup: SessionStartupState? = this.startup,
    nextIdentity: Long = this.nextIdentity,
    terminalCutoffApplied: Boolean = this.terminalCutoffApplied,
    controlShutdownRequested: Boolean = this.controlShutdownRequested,
): SessionState = SessionState(
    lifecycle = lifecycle,
    desired = desired,
    currentness = currentness,
    runtimeOwnership = runtimeOwnership,
    topology = topology,
    admissions = admissions,
    registration = registration,
    handoff = handoff,
    terminalContenders = terminalContenders,
    terminalWinner = terminalWinner,
    cleanupTransfer = cleanupTransfer,
    cleanupReceipts = cleanupReceipts,
    startup = startup,
    nextIdentity = nextIdentity,
    terminalCutoffApplied = terminalCutoffApplied,
    controlShutdownRequested = controlShutdownRequested,
)

internal class SessionStartupState internal constructor(
    internal val identities: SessionRuntimeIdentityPlan,
    internal val stage: SessionStartupStage,
    internal val laneReadiness: RuntimeLaneReadiness? = null,
    internal val metricsReadiness: MetricsJointReadinessReceipt? = null,
    internal val captureGeometry: CaptureGeometry? = null,
    internal val capturedContentVisible: Boolean? = null,
    internal val apiBand: AndroidCaptureApiBand? = null,
    internal val glCapabilities: GlCapabilityFacts? = null,
    internal val targetPlan: TargetPlan? = null,
    internal val targetRequestedIdentity: TargetRequestedIdentity? = null,
    internal val installedTarget: TargetConstructionInstalledFact? = null,
    internal val resolvedTopology: Resolved? = null,
    internal val lastAndroidCallbackSequence: Long = 0L,
    internal val committedTopologyReady: SessionStartupTopologyReadyFact? = null,
)

internal enum class SessionStartupStage {
    AwaitingRuntime,
    AwaitingMetrics,
    AwaitingProjectionCallbackRegistration,
    AwaitingGlSession,
    AwaitingTargetConstruction,
    AwaitingTargetInstallation,
    AwaitingTargetListener,
    AwaitingTargetListenerApplication,
    AwaitingVirtualDisplay,
    AwaitingVirtualDisplayApplication,
    AwaitingInitialResize,
    AwaitingTargetReconfiguration,
    AwaitingRenderTarget,
    Ready,
}

internal sealed interface SessionLifecycle {
    internal object NotStarted : SessionLifecycle

    internal class Starting internal constructor(
        internal val startupIdentity: Long,
        internal val expectedCurrentness: SessionCurrentness,
    ) : SessionLifecycle

    internal class Active internal constructor(
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
        internal val capturedContentVisible: Boolean?,
    ) : SessionLifecycle

    internal class Reconfiguring internal constructor(
        internal val requestedParameters: ScreenCaptureParameters,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val lastKnownCaptureGeometry: CaptureGeometry?,
        internal val capturedContentVisible: Boolean?,
    ) : SessionLifecycle

    internal class Suspended internal constructor(
        internal val requestedParameters: ScreenCaptureParameters,
        internal val problem: ScreenCaptureProblem,
        internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        internal val lastKnownCaptureGeometry: CaptureGeometry?,
        internal val capturedContentVisible: Boolean?,
    ) : SessionLifecycle

    internal class Terminal internal constructor(
        internal val winner: SessionTerminalWinner,
    ) : SessionLifecycle
}

internal class SessionDesiredParameters internal constructor(
    internal val revision: Long,
    internal val parameters: ScreenCaptureParameters,
) {
    init {
        require(revision > 0L)
    }
}

internal data class SessionCurrentness internal constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
) {
    init {
        require(desiredRevision >= 0L)
        require(geometryGeneration >= 0L)
        require(lifecycleEpoch >= 0L)
        check(
            (desiredRevision == 0L && geometryGeneration == 0L && lifecycleEpoch == 0L) ||
                    (desiredRevision > 0L && geometryGeneration > 0L && lifecycleEpoch > 0L),
        )
    }

    internal companion object {
        internal val Empty: SessionCurrentness = SessionCurrentness(0L, 0L, 0L)
    }
}

internal class SessionTopology internal constructor(
    internal val topologyIdentity: Long,
    internal val startupIdentity: Long,
    internal val ownership: SessionRuntimeOwnership,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val captureGeometry: CaptureGeometry,
    internal val evidence: ActiveTopologyEvidence,
) {
    init {
        require(topologyIdentity > 0L)
        require(startupIdentity > 0L)
        require(ownership.startupIdentity == startupIdentity)
        check(evidence.metrics.owner === ownership.metrics)
        check(evidence.android.owner === ownership.android)
        check(ownership.target != null && evidence.target.owner === ownership.target)
        check(evidence.gl.owner === ownership.gl)
        check(evidence.jpeg.owner === ownership.jpeg)
    }
}

internal enum class SessionAdmissions {
    Open,
    Paused,
    Closed,
}

internal enum class SessionTerminalKind {
    CaptureEnded,
    OwnerStop,
    Failed,
}

internal class SessionTerminalCandidate internal constructor(
    internal val kind: SessionTerminalKind,
    internal val stopReason: ScreenCaptureStopReason?,
    internal val problem: ScreenCaptureProblem?,
    internal val cause: Throwable?,
) {
    init {
        when (kind) {
            SessionTerminalKind.CaptureEnded -> check(
                stopReason == ScreenCaptureStopReason.CaptureEnded && problem == null && cause == null,
            )

            SessionTerminalKind.OwnerStop -> check(
                stopReason == ScreenCaptureStopReason.OwnerStop && problem == null && cause == null,
            )

            SessionTerminalKind.Failed -> check(stopReason == null && problem != null)
        }
    }
}

internal class SessionTerminalWinner internal constructor(
    internal val kind: SessionTerminalKind,
    internal val stopReason: ScreenCaptureStopReason?,
    internal val problem: ScreenCaptureProblem?,
    internal val cause: Throwable?,
    internal val requestedParameters: ScreenCaptureParameters,
    internal val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
)

internal class SessionTerminalContenders internal constructor(
    internal val captureEnded: SessionTerminalCandidate? = null,
    internal val ownerStop: SessionTerminalCandidate? = null,
    internal val failure: SessionTerminalCandidate? = null,
)
