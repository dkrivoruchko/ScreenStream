package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.CleanupOwner
import io.screenstream.engine.internal.android.AndroidProjectionCallbackUnregistrationEvidence
import io.screenstream.engine.internal.android.AndroidProjectionStopEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerRemovalEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseEvidence
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.jpeg.FrameworkBitmapRecycleOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOccurrence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetConstructionFailureFact
import io.screenstream.engine.internal.target.TargetGenerationFencedFact
import io.screenstream.engine.internal.target.TargetRetirementAdmissionClosedFact
import io.screenstream.engine.internal.target.TargetSurfaceReleaseReadyFact
import io.screenstream.engine.internal.target.TargetWorkDrainedFact

internal class SessionAcceptedControlRuntimeRoot internal constructor(
    internal val runtimeOwner: SessionControlRuntimeOwner,
    internal val terminationRoot: SessionControlTerminationRoot,
    internal val terminationReceipt: SessionControlTerminationReceipt,
    internal val finalShutdownAction: SessionControlFinalShutdownAction,
) {
    init {
        require(terminationRoot.runtimeOwner === runtimeOwner)
        require(terminationReceipt.runtimeIdentity === runtimeOwner)
        require(terminationReceipt.exactRoot === terminationRoot)
    }
}

internal class SessionStartupControlRuntimeRoot internal constructor(
    internal val runtimeOwner: SessionControlRuntimeOwner,
    internal val startupRecord: SessionControlStartupRecord,
    internal val startupCleanupAction: SessionControlStartupCleanupAction,
) {
    internal val terminationRoot: SessionControlTerminationRoot = runtimeOwner.terminationRoot
    internal val terminationReceipt: SessionControlTerminationReceipt = terminationRoot.exactReceipt
}

internal class SessionCleanupFacet internal constructor(
    internal val owner: CleanupOwner,
) {
    internal var acceptedControlRuntimeRoot: SessionAcceptedControlRuntimeRoot? = null
    internal var unresolvedStartupControlRuntimeRoot: SessionStartupControlRuntimeRoot? = null

    internal var projectionUnregistration: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? = null
    internal var virtualDisplayRelease: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? = null
    internal var projectionStop: OperationOccurrence<AndroidProjectionStopEvidence>? = null
    internal var listenerRemoval: OperationOccurrence<AndroidTargetListenerRemovalEvidence>? = null
    internal var renderOwner: GlPipelineOwner.GlRenderTargetOwner? = null
    internal var renderDestruction: GlPipelineOwner.DestructionCommand? = null
    internal var surfaceRelease: GlPipelineOwner.SurfaceReleaseCommand? = null
    internal var targetScope: GlPipelineOwner.TargetScopeDestructionCommand? = null
    internal var targetNamespaceSubmitted: Boolean = false
    internal var preparedTargetFailureFact: TargetConstructionFailureFact? = null
    internal var targetRetirementRoot: CurrentTarget? = null
    internal var targetRetirementAdmissionClosedFact: TargetRetirementAdmissionClosedFact? = null
    internal var targetWorkDrainedFact: TargetWorkDrainedFact? = null
    internal var targetGenerationFencedFact: TargetGenerationFencedFact? = null
    internal var targetSurfaceReleaseReadyFact: TargetSurfaceReleaseReadyFact? = null
    internal var frameworkRecycle: FrameworkBitmapRecycleOccurrence? = null
    internal var programDestruction: GlPipelineOwner.DestructionCommand? = null
    internal var sessionDestruction: GlPipelineOwner.DestructionCommand? = null
    internal var nativeCarrierFree: NativeCarrierFreeOccurrence? = null
    internal var preparedTargetDestructionIdentity: GlFiniteOperationIdentity? = null
    internal var preparedNamespaceDestructionIdentity: GlFiniteOperationIdentity? = null
    internal var workPending: Boolean = false
    internal var pendingQuarantineDiagnostics: Long = 0L
}
