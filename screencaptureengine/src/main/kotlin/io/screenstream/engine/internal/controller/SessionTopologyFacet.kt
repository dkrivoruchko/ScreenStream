package io.screenstream.engine.internal.controller

import android.media.projection.MediaProjection
import android.view.Display
import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.AndroidFiniteOperationIdentity
import io.screenstream.engine.internal.android.AndroidProjectionCallbackRegistrationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.android.CaptureMetricsClaimedValue
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.gl.GlClaimedOperationFacts
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.jpeg.FrameworkJpegOwner
import io.screenstream.engine.internal.jpeg.FrameworkResourceCreationOccurrence
import io.screenstream.engine.internal.jpeg.JpegPreparationOccurrence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.PreparedTargetAdmissionFact
import io.screenstream.engine.internal.target.TargetIdentity
import io.screenstream.engine.internal.target.TargetPlan

internal class TopologyStamp internal constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
) {
    init {
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
    }
}

/**
 * Exact passive snapshot for the aggregate root's sole retained-reconfiguration occurrence.
 * [topologyStamp] remains the only Session currentness identity; [identity] fences only this operation.
 */
internal class SessionRetainedReconfigurationOccurrence internal constructor(
    internal val identity: Long,
    internal val calculation: Resolved,
    internal val target: CurrentTarget,
) {
    internal val topologyStamp: TopologyStamp = calculation.input.stamp
    internal val reconciliationOccurrenceIdentity: Long =
        calculation.input.reconciliationOccurrenceIdentity
    internal val targetIdentity: TargetIdentity = target.identity
    internal val targetGeneration: Long = target.generation

    init {
        require(identity > 0L)
        require(reconciliationOccurrenceIdentity > 0L)
        require(targetGeneration > 0L)
        check(calculation.targetAction == ReconciliationResourceAction.Retain)
        check(targetIdentity.generation == targetGeneration)
    }
}

internal class SessionFullGeometryAuthority internal constructor(
    internal val source: CaptureMetricsSource,
    internal val observationIdentity: Long,
    internal val displayIdentity: Display?,
    internal val displayEpoch: Long,
    internal val projectionEpoch: Long,
    internal val available: Boolean,
    internal val sourceWidthPx: Int,
    internal val sourceHeightPx: Int,
    internal val projectionWidthPx: Int,
    internal val projectionHeightPx: Int,
    internal val densityDpi: Int,
)

internal class SessionMetricsJointReadinessFacts internal constructor(
    internal val owner: CaptureMetricsOwner,
    internal val source: CaptureMetricsSource,
    internal val observationIdentity: Long,
    internal val sequence: Long,
)

internal class SessionTopologyFacet internal constructor() {
    internal var androidLaneReadyOwner: AndroidCaptureOwner? = null
    internal var metricsJointReadiness: SessionMetricsJointReadinessFacts? = null

    internal var requestedParameters: ScreenCaptureParameters? = null
    internal var desiredRevision: Long = 0L
    internal var geometryGeneration: Long = 0L
    internal var lifecycleEpoch: Long = 0L
    internal var reconciliationIdentity: Long = 0L
    internal var capturedContentVisible: Boolean? = null
    internal var projectionEpoch: Long = 0L
    internal var projectionCallbackIdentity: Long = 0L
    internal var acceptedProjectionCallbackRegistrationIdentity: Long = 0L
    internal var lastAndroidCallbackSequence: Long = 0L
    internal var projectionGeometryAvailable: Boolean = false
    internal var geometryBuildPending: Boolean = false
    internal var projectionWidthPx: Int = 0
    internal var projectionHeightPx: Int = 0
    internal var latestMetricsFact: CaptureMetricsClaimedValue? = null
    internal var combinedGeometryAuthority: SessionFullGeometryAuthority? = null
    internal var captureGeometry: CaptureGeometry? = null
    internal var lastEffectiveParameters: ScreenCaptureEffectiveParameters? = null
    internal var currentCalculation: Resolved? = null
    internal var currentProvisional: ProvisionalFull? = null
    internal var currentPlan: TargetPlan? = null

    internal var projection: MediaProjection? = null
    internal var metricsOwner: CaptureMetricsOwner? = null
    internal var androidOwner: AndroidCaptureOwner? = null
    internal var glOwner: GlPipelineOwner? = null
    internal var jpegOwner: JpegRuntimeOwner? = null
    internal var storageOwner: EncodedStorageOwner? = null
    internal var preparedTarget: PreparedTarget? = null
    internal var preparedTargetAdmissionFact: PreparedTargetAdmissionFact? = null
    internal var currentTarget: CurrentTarget? = null
    internal var installedRenderTarget: GlPipelineOwner.GlRenderTargetOwner? = null
    internal var installedFrameworkOwner: FrameworkJpegOwner? = null
    internal var pendingFrameworkCreation: FrameworkResourceCreationOccurrence? = null
    internal var acceptedTopologySnapshot: AcceptedTopologySnapshot? = null
    internal var pendingProjectionRegistration: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? = null
    internal var pendingListenerInstallation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>? = null
    internal var pendingVirtualDisplayCreation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? = null
    internal var virtualDisplayReturnAccepted: Boolean = false
    internal var preparedTargetCommand: GlPipelineOwner.TargetConstructionCommand? = null
    internal var preparedTargetListenerIdentity: AndroidFiniteOperationIdentity? = null
    internal var pendingRenderConstruction: GlPipelineOwner.RenderTargetConstructionCommand? = null
    internal var pendingJpegPreparation: JpegPreparationOccurrence? = null
    internal var nextRenderGeneration: Long = 1L

    internal var glCapabilities: GlCapabilityFacts? = null
    internal var glSessionFacts: GlClaimedOperationFacts? = null
    internal var glSessionCommand: GlPipelineOwner.SessionConstructionCommand? = null
    internal var replacementJpegConstructionClaimed: Boolean = false
}
