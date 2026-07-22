package io.screenstream.engine.internal.session.runtime

/**
 * Exact runtime roots transferred as one startup product. Implementations wrap the physical leaf owners; the
 * Session authority compares these identities and never reaches through them to platform handles.
 */
internal interface SessionRuntimeResidue {
    val startupIdentity: Long
    val control: ControlRuntimeOwnership?
    val metrics: MetricsRuntimeOwnership?
    val android: AndroidRuntimeOwnership?
    val target: TargetRuntimeOwnership?
    val gl: GlRuntimeOwnership?
    val jpeg: JpegRuntimeOwnership?
    val storage: StorageRuntimeOwnership?
    val delivery: DeliveryRuntimeOwnership?
}

internal interface SessionRuntimeOwnership : SessionRuntimeResidue {
    override val control: ControlRuntimeOwnership
    override val metrics: MetricsRuntimeOwnership
    override val android: AndroidRuntimeOwnership
    override val target: TargetRuntimeOwnership?
    override val gl: GlRuntimeOwnership
    override val jpeg: JpegRuntimeOwnership
    override val storage: StorageRuntimeOwnership?
    override val delivery: DeliveryRuntimeOwnership
}

internal interface ControlRuntimeOwnership {
    val terminationReceipt: ControlTerminationReceipt
}
internal interface MetricsRuntimeOwnership {
    val jointReadinessReceipt: MetricsJointReadinessReceipt
}
internal interface AndroidRuntimeOwnership {
    val apiBand: io.screenstream.engine.internal.android.AndroidCaptureApiBand
        get() = io.screenstream.engine.internal.android.AndroidCaptureApiBand.Unsupported
    fun matchesCallbackProvenance(
        provenance: io.screenstream.engine.internal.android.AndroidCallbackProvenance,
    ): Boolean = false
}
internal interface TargetRuntimeOwnership
internal interface GlRuntimeOwnership
internal interface JpegRuntimeOwnership
internal interface StorageRuntimeOwnership
internal interface DeliveryRuntimeOwnership

internal interface ControlLaneReadyReceipt { val owner: ControlRuntimeOwnership }
internal interface MetricsLaneReadyReceipt { val owner: MetricsRuntimeOwnership }
internal interface AndroidLaneReadyReceipt { val owner: AndroidRuntimeOwnership }
internal interface GlLaneReadyReceipt { val owner: GlRuntimeOwnership }
internal interface JpegLaneReadyReceipt { val owner: JpegRuntimeOwnership }
internal interface DeliveryLaneReadyReceipt { val owner: DeliveryRuntimeOwnership }

internal interface MetricsJointReadinessReceipt { val owner: MetricsRuntimeOwnership }
internal interface AndroidCaptureReadyReceipt { val owner: AndroidRuntimeOwnership }
internal interface TargetTopologyReadyReceipt { val owner: TargetRuntimeOwnership }
internal interface GlTopologyReadyReceipt { val owner: GlRuntimeOwnership }
internal interface JpegTopologyReadyReceipt { val owner: JpegRuntimeOwnership }

/** Exact complete live-topology evidence consumed by the startup-to-Active transition. */
internal class ActiveTopologyEvidence internal constructor(
    internal val metrics: MetricsJointReadinessReceipt,
    internal val android: AndroidCaptureReadyReceipt,
    internal val target: TargetTopologyReadyReceipt,
    internal val gl: GlTopologyReadyReceipt,
    internal val jpeg: JpegTopologyReadyReceipt,
)

/** Frozen exact evidence that all six Session-owned lanes were constructed and prestarted exactly once. */
internal class RuntimeLaneReadiness internal constructor(
    internal val control: ControlLaneReadyReceipt,
    internal val metrics: MetricsLaneReadyReceipt,
    internal val android: AndroidLaneReadyReceipt,
    internal val gl: GlLaneReadyReceipt,
    internal val jpeg: JpegLaneReadyReceipt,
    internal val delivery: DeliveryLaneReadyReceipt,
)

internal interface MetricsTerminationReceipt {
    val owner: MetricsRuntimeOwnership
    val observationSettlement: io.screenstream.engine.internal.android.CaptureMetricsObservationSettlement
    val endpointTerminationReceipt: io.screenstream.engine.internal.android.CaptureMetricsEndpointTerminationReceipt
}
internal interface AndroidTerminationReceipt {
    val owner: AndroidRuntimeOwnership
    val projectionClosureReceipt: io.screenstream.engine.internal.android.AndroidProjectionClosureReceipt?
    val finalProjectionStopAction: io.screenstream.engine.internal.android.AndroidFinalProjectionStopAction?
}
internal interface TargetRetirementReceipt { val owner: TargetRuntimeOwnership }
internal interface GlTerminationReceipt { val owner: GlRuntimeOwnership }
internal interface JpegTerminationReceipt { val owner: JpegRuntimeOwnership }
internal interface StorageRetirementReceipt { val owner: StorageRuntimeOwnership }
internal interface DeliveryTerminationReceipt { val owner: DeliveryRuntimeOwnership }
internal interface ControlTerminationReceipt {
    val owner: ControlRuntimeOwnership
    val released: Boolean
}

/** Facts returned by mechanics; no method here can select lifecycle, currentness, fallback, or cleanup policy. */
internal interface SessionRuntimeFactPort {
    fun publishRuntimeStarted(fact: io.screenstream.engine.internal.session.SessionRuntimeStartedFact)
    fun publishRuntimeStartupFailed(fact: io.screenstream.engine.internal.session.SessionRuntimeStartupFailedFact)
    fun publishControlWakeSchedule(fact: io.screenstream.engine.internal.session.SessionControlWakeScheduleFact)
    fun publishControlWakeCancellation(fact: io.screenstream.engine.internal.session.SessionControlWakeCancellationFact)
    fun publishProjectionCallbackRegistration(
        fact: io.screenstream.engine.internal.session.SessionProjectionCallbackRegistrationFact,
    )
    fun publishGlConstruction(fact: io.screenstream.engine.internal.session.SessionGlConstructionFact)
    fun publishTargetConstructionClaim(
        fact: io.screenstream.engine.internal.session.SessionTargetConstructionClaimFact,
    )
    fun publishTargetConstructionResult(
        fact: io.screenstream.engine.internal.session.SessionTargetConstructionResultFact,
    )
    fun publishTargetListenerClaim(fact: io.screenstream.engine.internal.session.SessionTargetListenerClaimFact)
    fun publishTargetListenerApplied(fact: io.screenstream.engine.internal.session.SessionTargetListenerAppliedFact)
    fun publishVirtualDisplayClaim(fact: io.screenstream.engine.internal.session.SessionVirtualDisplayClaimFact)
    fun publishVirtualDisplayApplied(fact: io.screenstream.engine.internal.session.SessionVirtualDisplayAppliedFact)
    fun publishInitialResize(fact: io.screenstream.engine.internal.session.SessionInitialResizeFact)
    fun publishStartupTopologyReady(fact: io.screenstream.engine.internal.session.SessionStartupTopologyReadyFact)
    fun publishControlException(fact: io.screenstream.engine.internal.session.SessionControlExceptionFact)
    fun publishControlDirectFatal(fact: io.screenstream.engine.internal.session.SessionControlDirectFatalFact)
    fun publishMetricsCleanupSettled(fact: io.screenstream.engine.internal.session.MetricsCleanupSettledFact)
    fun publishAndroidCleanupSettled(fact: io.screenstream.engine.internal.session.AndroidCleanupSettledFact)
    fun publishTargetCleanupSettled(fact: io.screenstream.engine.internal.session.TargetCleanupSettledFact)
    fun publishGlCleanupSettled(fact: io.screenstream.engine.internal.session.GlCleanupSettledFact)
    fun publishJpegCleanupSettled(fact: io.screenstream.engine.internal.session.JpegCleanupSettledFact)
    fun publishStorageCleanupSettled(fact: io.screenstream.engine.internal.session.StorageCleanupSettledFact)
    fun publishDeliveryCleanupSettled(fact: io.screenstream.engine.internal.session.DeliveryCleanupSettledFact)
    fun publishExternalFactsSettled(fact: io.screenstream.engine.internal.session.SessionExternalFactsSettledFact)
    fun publishControlResidueSettled(fact: io.screenstream.engine.internal.session.SessionControlResidueSettledFact)
}
