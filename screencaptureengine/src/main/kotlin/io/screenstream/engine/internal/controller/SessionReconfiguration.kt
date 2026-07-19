package io.screenstream.engine.internal.controller

import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.jpeg.FrameworkJpegOwner
import io.screenstream.engine.internal.jpeg.JpegRuntimeTopologySnapshot
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetCurrentnessSnapshot
import io.screenstream.engine.internal.target.TargetProducerState

internal class SessionTopologyCaptureCommand internal constructor(
    internal val key: ReconciliationKey,
    internal val topologyIdentity: Long,
    internal val metricsOwner: CaptureMetricsOwner,
    internal val metricsSource: CaptureMetricsSource,
    internal val metricsObservationIdentity: Long,
    internal val metricsReadinessSequence: Long,
    internal val androidOwner: AndroidCaptureOwner,
    internal val projectionRegistrationIdentity: Long,
    internal val glOwner: GlPipelineOwner,
    internal val target: CurrentTarget,
    internal val renderTarget: GlPipelineOwner.GlRenderTargetOwner,
    internal val jpegOwner: JpegRuntimeOwner,
    internal val frameworkOwner: FrameworkJpegOwner?,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
)

internal class AcceptedTopologySnapshot internal constructor(
    internal val key: ReconciliationKey,
    internal val topologyIdentity: Long,
    internal val metricsOwner: CaptureMetricsOwner,
    internal val metricsSource: CaptureMetricsSource,
    internal val metricsObservationIdentity: Long,
    internal val metricsReadinessSequence: Long,
    internal val androidOwner: AndroidCaptureOwner,
    internal val projectionRegistrationIdentity: Long,
    internal val projectionCallbackInstalled: Boolean,
    internal val glOwner: GlPipelineOwner,
    internal val target: CurrentTarget,
    internal val targetCurrentness: TargetCurrentnessSnapshot,
    internal val targetActiveLeaseCount: Int,
    internal val renderTarget: GlPipelineOwner.GlRenderTargetOwner,
    internal val renderGeneration: Long,
    internal val jpegOwner: JpegRuntimeOwner,
    internal val jpegTopology: JpegRuntimeTopologySnapshot,
    internal val frameworkOwner: FrameworkJpegOwner?,
    internal val frameworkResourcesComplete: Boolean,
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
)

internal object SessionReconfiguration {
    internal fun captureCompleteTopology(command: SessionTopologyCaptureCommand): AcceptedTopologySnapshot? {
        val currentness = command.target.currentnessSnapshot()
        val activeLeaseCount = command.target.activeLeaseCount
        val jpegTopology = command.jpegOwner.stableTopologySnapshot() ?: return null
        val product = jpegTopology.product ?: return null
        val frameworkComplete = command.frameworkOwner?.hasCompleteResources() == true
        if (!command.androidOwner.isProjectionCallbackRegistered || command.projectionRegistrationIdentity <= 0L ||
            currentness.target !== command.target || currentness.plan !== command.target.plan ||
            !currentness.listenerInstalled || currentness.producerState != TargetProducerState.ProducerAttached ||
            currentness.generationFenced || activeLeaseCount != 0 ||
            command.renderTarget.compatibilityFacts.imageSize != command.effectiveParameters.finalImageSize ||
            command.renderTarget.compatibilityFacts.rgbaByteCount != product.carrier.byteCount ||
            (product !is io.screenstream.engine.internal.jpeg.JpegRuntimeProduct.NativeEnabled && !frameworkComplete)
        ) {
            return null
        }
        return AcceptedTopologySnapshot(
            key = command.key,
            topologyIdentity = command.topologyIdentity,
            metricsOwner = command.metricsOwner,
            metricsSource = command.metricsSource,
            metricsObservationIdentity = command.metricsObservationIdentity,
            metricsReadinessSequence = command.metricsReadinessSequence,
            androidOwner = command.androidOwner,
            projectionRegistrationIdentity = command.projectionRegistrationIdentity,
            projectionCallbackInstalled = true,
            glOwner = command.glOwner,
            target = command.target,
            targetCurrentness = currentness,
            targetActiveLeaseCount = activeLeaseCount,
            renderTarget = command.renderTarget,
            renderGeneration = command.renderTarget.renderGeneration,
            jpegOwner = command.jpegOwner,
            jpegTopology = jpegTopology,
            frameworkOwner = command.frameworkOwner,
            frameworkResourcesComplete = frameworkComplete,
            effectiveParameters = command.effectiveParameters,
        )
    }

    internal fun revalidate(snapshot: AcceptedTopologySnapshot): Boolean {
        val currentness = snapshot.target.currentnessSnapshot()
        return snapshot.projectionCallbackInstalled && snapshot.androidOwner.isProjectionCallbackRegistered &&
                currentness.target === snapshot.target &&
                currentness.generation == snapshot.targetCurrentness.generation &&
                currentness.plan === snapshot.targetCurrentness.plan &&
                currentness.listenerInstalled == snapshot.targetCurrentness.listenerInstalled &&
                currentness.producerState == snapshot.targetCurrentness.producerState &&
                currentness.generationFenced == snapshot.targetCurrentness.generationFenced &&
                currentness.version == snapshot.targetCurrentness.version &&
                snapshot.target.activeLeaseCount == snapshot.targetActiveLeaseCount &&
                snapshot.renderTarget.renderGeneration == snapshot.renderGeneration &&
                snapshot.jpegOwner.stableTopologySnapshot() === snapshot.jpegTopology &&
                (snapshot.frameworkOwner == null ||
                        snapshot.frameworkOwner.hasCompleteResources() == snapshot.frameworkResourcesComplete)
    }
}
