package io.screenstream.engine.internal.session

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.session.runtime.ActiveTopologyEvidence
import io.screenstream.engine.internal.session.runtime.AndroidTerminationReceipt
import io.screenstream.engine.internal.session.runtime.DeliveryTerminationReceipt
import io.screenstream.engine.internal.session.runtime.GlTerminationReceipt
import io.screenstream.engine.internal.session.runtime.JpegTerminationReceipt
import io.screenstream.engine.internal.session.runtime.MetricsTerminationReceipt
import io.screenstream.engine.internal.session.runtime.RuntimeLaneReadiness
import io.screenstream.engine.internal.session.runtime.SessionRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.SessionRuntimeResidue
import io.screenstream.engine.internal.session.runtime.StorageRetirementReceipt
import io.screenstream.engine.internal.session.runtime.TargetRetirementReceipt
import io.screenstream.engine.internal.session.cleanup.ExternalFactsSettledReceipt
import io.screenstream.engine.internal.session.cleanup.SessionControlResidueSettledProof
import io.screenstream.engine.internal.android.CaptureMetricsReadinessMechanicalFact
import io.screenstream.engine.internal.android.AndroidProjectionCallbackRegistrationEvidence
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeScheduleAction
import io.screenstream.engine.internal.settlement.ControlWakeCancellationAction
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.session.runtime.AndroidRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.MetricsJointReadinessReceipt
import io.screenstream.engine.internal.session.runtime.MetricsRuntimeOwnership
import io.screenstream.engine.internal.session.runtime.GlRuntimeOwnership
import io.screenstream.engine.internal.gl.GlClaimedOperationFacts
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.session.runtime.TargetRuntimeOwnership
import io.screenstream.engine.internal.target.TargetConstructionFoldToken
import io.screenstream.engine.internal.target.TargetConstructionResultFact
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.android.AndroidCaptureFact

internal class SessionRuntimeStartedFact internal constructor(
    internal val startupIdentity: Long,
    internal val ownership: SessionRuntimeOwnership,
    internal val laneReadiness: RuntimeLaneReadiness,
) {
    init {
        require(startupIdentity > 0L)
        require(ownership.startupIdentity == startupIdentity)
        check(laneReadiness.control.owner === ownership.control)
        check(laneReadiness.metrics.owner === ownership.metrics)
        check(laneReadiness.android.owner === ownership.android)
        check(laneReadiness.gl.owner === ownership.gl)
        check(laneReadiness.jpeg.owner === ownership.jpeg)
        check(laneReadiness.delivery.owner === ownership.delivery)
    }
}

internal class SessionRuntimeStartupFailedFact internal constructor(
    internal val startupIdentity: Long,
    internal val residue: SessionRuntimeResidue?,
    internal val raw: Throwable,
) {
    init {
        require(startupIdentity > 0L)
        require(residue == null || residue.startupIdentity == startupIdentity)
    }
}

/**
 * Frozen leaf result offered after the exact live topology has been built and mechanically revalidated.
 */
internal class SessionStartupTopologyReadyFact internal constructor(
    internal val startupIdentity: Long,
    internal val topologyIdentity: Long,
    internal val ownership: SessionRuntimeOwnership,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val captureGeometry: CaptureGeometry,
    internal val capturedContentVisible: Boolean?,
    internal val currentness: SessionCurrentness,
    internal val topologyEvidence: ActiveTopologyEvidence,
) {
    init {
        require(startupIdentity > 0L)
        require(topologyIdentity > 0L)
        require(ownership.startupIdentity == startupIdentity)
        require(currentness.desiredRevision > 0L)
        require(currentness.geometryGeneration > 0L)
        require(currentness.lifecycleEpoch > 0L)
        check(topologyEvidence.metrics.owner === ownership.metrics)
        check(topologyEvidence.android.owner === ownership.android)
        check(topologyEvidence.target.owner === ownership.target)
        check(topologyEvidence.gl.owner === ownership.gl)
        check(topologyEvidence.jpeg.owner === ownership.jpeg)
    }
}

internal class SessionControlExceptionFact internal constructor(
    internal val ownership: SessionRuntimeOwnership?,
    internal val cause: Throwable,
)

internal class SessionMetricsReadinessFact internal constructor(
    internal val startupIdentity: Long,
    internal val owner: MetricsRuntimeOwnership,
    internal val mechanical: CaptureMetricsReadinessMechanicalFact,
    internal val timelyReceipt: MetricsJointReadinessReceipt?,
) {
    init {
        require(startupIdentity > 0L)
        check(timelyReceipt == null || timelyReceipt.owner === owner)
    }
}

internal class SessionControlWakeScheduleFact internal constructor(
    internal val startupIdentity: Long,
    internal val ownership: SessionRuntimeOwnership,
    internal val wakeLink: ControlWakeLink,
    internal val action: ControlWakeScheduleAction,
) {
    init {
        require(startupIdentity > 0L)
        check(ownership.startupIdentity == startupIdentity)
    }
}

internal class SessionControlWakeCancellationFact internal constructor(
    internal val startupIdentity: Long,
    internal val ownership: SessionRuntimeOwnership,
    internal val wakeLink: ControlWakeLink,
    internal val action: ControlWakeCancellationAction,
) {
    init {
        require(startupIdentity > 0L)
        check(ownership.startupIdentity == startupIdentity)
    }
}

internal class SessionProjectionCallbackRegistrationFact internal constructor(
    internal val startupIdentity: Long,
    internal val owner: AndroidRuntimeOwnership,
    internal val occurrence: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    internal val arbitration: OperationArbitration,
) {
    init {
        require(startupIdentity > 0L)
        check(arbitration != OperationArbitration.None)
    }
}

internal class SessionControlDirectFatalFact internal constructor(
    internal val ownership: SessionRuntimeOwnership?,
    internal val cause: Throwable,
)

internal class SessionGlConstructionFact internal constructor(
    internal val startupIdentity: Long,
    internal val owner: GlRuntimeOwnership,
    internal val facts: GlClaimedOperationFacts,
    internal val capabilities: GlCapabilityFacts?,
    internal val apiBand: AndroidCaptureApiBand,
) {
    init {
        require(startupIdentity > 0L)
    }
}

internal class SessionTargetConstructionClaimFact internal constructor(
    internal val startupIdentity: Long,
    internal val owner: TargetRuntimeOwnership,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val plan: TargetPlan,
    internal val token: TargetConstructionFoldToken,
    internal val glResult: io.screenstream.engine.internal.gl.GlOperationResult?,
    internal val contextIntegrity: io.screenstream.engine.internal.gl.ContextIntegrity?,
) {
    init {
        require(startupIdentity > 0L)
        check(token.requestedIdentity === requestedIdentity)
        check(token.plan === plan)
    }
}

internal class SessionTargetConstructionResultFact internal constructor(
    internal val startupIdentity: Long,
    internal val owner: TargetRuntimeOwnership,
    internal val result: TargetConstructionResultFact,
    internal val glResult: io.screenstream.engine.internal.gl.GlOperationResult?,
    internal val contextIntegrity: io.screenstream.engine.internal.gl.ContextIntegrity?,
) {
    init {
        require(startupIdentity > 0L)
    }
}

internal class SessionTargetListenerClaimFact internal constructor(
    internal val startupIdentity: Long,
    internal val androidOwner: AndroidRuntimeOwnership,
    internal val targetOwner: TargetRuntimeOwnership,
    internal val occurrence: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal val arbitration: OperationArbitration,
    internal val platformResult: AndroidTargetPlatformResult.ListenerInstalled,
) {
    init {
        require(startupIdentity > 0L)
        check(arbitration != OperationArbitration.None)
        check(platformResult.binding.operationIdentity == occurrence.identity)
    }
}

internal class SessionTargetListenerAppliedFact internal constructor(
    internal val startupIdentity: Long,
    internal val androidOwner: AndroidRuntimeOwnership,
    internal val targetOwner: TargetRuntimeOwnership,
    internal val result: TargetAndroidPlatformApplicationResult.ListenerInstalled,
) {
    init {
        require(startupIdentity > 0L)
    }
}

internal class SessionVirtualDisplayClaimFact internal constructor(
    internal val startupIdentity: Long,
    internal val androidOwner: AndroidRuntimeOwnership,
    internal val targetOwner: TargetRuntimeOwnership,
    internal val occurrence: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    internal val arbitration: OperationArbitration,
    internal val platformResult: AndroidTargetPlatformResult,
) {
    init {
        require(startupIdentity > 0L)
        check(arbitration != OperationArbitration.None)
        check(platformResult.binding.operationIdentity == occurrence.identity)
    }
}

internal class SessionVirtualDisplayAppliedFact internal constructor(
    internal val startupIdentity: Long,
    internal val androidOwner: AndroidRuntimeOwnership,
    internal val targetOwner: TargetRuntimeOwnership,
    internal val result: TargetAndroidPlatformApplicationResult,
    internal val actualLogicalTuple: io.screenstream.engine.internal.android.AndroidVirtualDisplayLogicalTuple,
) {
    init {
        require(startupIdentity > 0L)
    }
}

internal class SessionInitialResizeFact internal constructor(
    internal val startupIdentity: Long,
    internal val androidOwner: AndroidRuntimeOwnership,
    internal val resize: AndroidCaptureFact.CapturedContentResized?,
    internal val deadlineStartNanos: Long,
    internal val deadlineNanos: Long,
    internal val arbitrationNanos: Long,
    internal val timely: Boolean,
    internal val cause: Throwable?,
) {
    init {
        require(startupIdentity > 0L)
        check(timely == (resize != null && resize.sampleNanos < deadlineNanos))
        check(timely || cause != null)
    }
}

internal class MetricsCleanupSettledFact internal constructor(
    internal val receipt: MetricsTerminationReceipt,
)

internal class AndroidCleanupSettledFact internal constructor(
    internal val receipt: AndroidTerminationReceipt,
)

internal class TargetCleanupSettledFact internal constructor(
    internal val receipt: TargetRetirementReceipt,
)

internal class GlCleanupSettledFact internal constructor(
    internal val receipt: GlTerminationReceipt,
)

internal class JpegCleanupSettledFact internal constructor(
    internal val receipt: JpegTerminationReceipt,
)

internal class StorageCleanupSettledFact internal constructor(
    internal val receipt: StorageRetirementReceipt,
)

internal class DeliveryCleanupSettledFact internal constructor(
    internal val receipt: DeliveryTerminationReceipt,
)

internal class SessionExternalFactsSettledFact internal constructor(
    internal val receipt: ExternalFactsSettledReceipt,
)

internal class SessionControlResidueSettledFact internal constructor(
    internal val proof: SessionControlResidueSettledProof,
)
