package io.screenstream.engine.internal.session

import io.screenstream.engine.internal.android.AndroidCaptureFact
import io.screenstream.engine.internal.delivery.DeliveryFailureNotice
import java.util.concurrent.atomic.AtomicReference

/** Bounded typed fact cells. This is transport only; it owns no lifecycle or classification. */
internal class SessionFactInbox internal constructor() {
    private val runtimeStarted = AtomicReference<SessionRuntimeStartedFact?>(null)
    private val runtimeStartupFailed = AtomicReference<SessionRuntimeStartupFailedFact?>(null)
    private val controlWakeSchedule = AtomicReference<SessionControlWakeScheduleFact?>(null)
    private val controlWakeCancellation = AtomicReference<SessionControlWakeCancellationFact?>(null)
    private val projectionCallbackRegistration =
        AtomicReference<SessionProjectionCallbackRegistrationFact?>(null)
    private val glConstruction = AtomicReference<SessionGlConstructionFact?>(null)
    private val targetConstructionClaim = AtomicReference<SessionTargetConstructionClaimFact?>(null)
    private val targetConstructionResult = AtomicReference<SessionTargetConstructionResultFact?>(null)
    private val targetListenerClaim = AtomicReference<SessionTargetListenerClaimFact?>(null)
    private val targetListenerApplied = AtomicReference<SessionTargetListenerAppliedFact?>(null)
    private val virtualDisplayClaim = AtomicReference<SessionVirtualDisplayClaimFact?>(null)
    private val virtualDisplayApplied = AtomicReference<SessionVirtualDisplayAppliedFact?>(null)
    private val initialResize = AtomicReference<SessionInitialResizeFact?>(null)
    private val topologyReady = AtomicReference<SessionStartupTopologyReadyFact?>(null)
    private val controlException = AtomicReference<SessionControlExceptionFact?>(null)
    private val controlFatal = AtomicReference<SessionControlDirectFatalFact?>(null)
    private val metricsCleanup = AtomicReference<MetricsCleanupSettledFact?>(null)
    private val androidCleanup = AtomicReference<AndroidCleanupSettledFact?>(null)
    private val targetCleanup = AtomicReference<TargetCleanupSettledFact?>(null)
    private val glCleanup = AtomicReference<GlCleanupSettledFact?>(null)
    private val jpegCleanup = AtomicReference<JpegCleanupSettledFact?>(null)
    private val storageCleanup = AtomicReference<StorageCleanupSettledFact?>(null)
    private val deliveryCleanup = AtomicReference<DeliveryCleanupSettledFact?>(null)
    private val externalFacts = AtomicReference<SessionExternalFactsSettledFact?>(null)
    private val controlResidue = AtomicReference<SessionControlResidueSettledFact?>(null)
    private val capturedContentSize = AtomicReference<AndroidCaptureFact.CapturedContentResized?>(null)
    private val capturedContentVisibility = AtomicReference<AndroidCaptureFact.CapturedContentVisibilityChanged?>(null)
    private val captureEnded = AtomicReference<AndroidCaptureFact.CaptureEnded?>(null)
    private val deliveryFailure = AtomicReference<DeliveryFailureNotice?>(null)

    internal fun offer(fact: SessionRuntimeStartedFact): Boolean = runtimeStarted.compareAndSet(null, fact)
    internal fun offer(fact: SessionRuntimeStartupFailedFact): Boolean = runtimeStartupFailed.compareAndSet(null, fact)
    internal fun offer(fact: SessionControlWakeScheduleFact): Boolean = controlWakeSchedule.compareAndSet(null, fact)
    internal fun offer(fact: SessionControlWakeCancellationFact): Boolean = controlWakeCancellation.compareAndSet(null, fact)
    internal fun offer(fact: SessionProjectionCallbackRegistrationFact): Boolean =
        projectionCallbackRegistration.compareAndSet(null, fact)
    internal fun offer(fact: SessionGlConstructionFact): Boolean = glConstruction.compareAndSet(null, fact)
    internal fun offer(fact: SessionTargetConstructionClaimFact): Boolean =
        targetConstructionClaim.compareAndSet(null, fact)
    internal fun offer(fact: SessionTargetConstructionResultFact): Boolean =
        targetConstructionResult.compareAndSet(null, fact)
    internal fun offer(fact: SessionTargetListenerClaimFact): Boolean = targetListenerClaim.compareAndSet(null, fact)
    internal fun offer(fact: SessionTargetListenerAppliedFact): Boolean = targetListenerApplied.compareAndSet(null, fact)
    internal fun offer(fact: SessionVirtualDisplayClaimFact): Boolean = virtualDisplayClaim.compareAndSet(null, fact)
    internal fun offer(fact: SessionVirtualDisplayAppliedFact): Boolean = virtualDisplayApplied.compareAndSet(null, fact)
    internal fun offer(fact: SessionInitialResizeFact): Boolean = initialResize.compareAndSet(null, fact)
    internal fun offer(fact: SessionStartupTopologyReadyFact): Boolean = topologyReady.compareAndSet(null, fact)
    internal fun offer(fact: SessionControlExceptionFact): Boolean = controlException.compareAndSet(null, fact)
    internal fun offer(fact: SessionControlDirectFatalFact): Boolean = controlFatal.compareAndSet(null, fact)
    internal fun offer(fact: MetricsCleanupSettledFact): Boolean = metricsCleanup.compareAndSet(null, fact)
    internal fun offer(fact: AndroidCleanupSettledFact): Boolean = androidCleanup.compareAndSet(null, fact)
    internal fun offer(fact: TargetCleanupSettledFact): Boolean = targetCleanup.compareAndSet(null, fact)
    internal fun offer(fact: GlCleanupSettledFact): Boolean = glCleanup.compareAndSet(null, fact)
    internal fun offer(fact: JpegCleanupSettledFact): Boolean = jpegCleanup.compareAndSet(null, fact)
    internal fun offer(fact: StorageCleanupSettledFact): Boolean = storageCleanup.compareAndSet(null, fact)
    internal fun offer(fact: DeliveryCleanupSettledFact): Boolean = deliveryCleanup.compareAndSet(null, fact)
    internal fun offer(fact: SessionExternalFactsSettledFact): Boolean = externalFacts.compareAndSet(null, fact)
    internal fun offer(fact: SessionControlResidueSettledFact): Boolean = controlResidue.compareAndSet(null, fact)
    internal fun offer(fact: DeliveryFailureNotice): Boolean = deliveryFailure.compareAndSet(null, fact)

    internal fun offer(fact: AndroidCaptureFact): Boolean = when (fact) {
        is AndroidCaptureFact.CapturedContentResized -> {
            capturedContentSize.set(fact)
            true
        }
        is AndroidCaptureFact.CapturedContentVisibilityChanged -> {
            capturedContentVisibility.set(fact)
            true
        }
        is AndroidCaptureFact.CaptureEnded -> captureEnded.compareAndSet(null, fact)
    }

    internal fun drain(): SessionFactBatch = SessionFactBatch(
        runtimeStarted = runtimeStarted.getAndSet(null),
        runtimeStartupFailed = runtimeStartupFailed.getAndSet(null),
        controlWakeSchedule = controlWakeSchedule.getAndSet(null),
        controlWakeCancellation = controlWakeCancellation.getAndSet(null),
        projectionCallbackRegistration = projectionCallbackRegistration.getAndSet(null),
        glConstruction = glConstruction.getAndSet(null),
        targetConstructionClaim = targetConstructionClaim.getAndSet(null),
        targetConstructionResult = targetConstructionResult.getAndSet(null),
        targetListenerClaim = targetListenerClaim.getAndSet(null),
        targetListenerApplied = targetListenerApplied.getAndSet(null),
        virtualDisplayClaim = virtualDisplayClaim.getAndSet(null),
        virtualDisplayApplied = virtualDisplayApplied.getAndSet(null),
        initialResize = initialResize.getAndSet(null),
        topologyReady = topologyReady.getAndSet(null),
        controlException = controlException.getAndSet(null),
        controlFatal = controlFatal.getAndSet(null),
        metricsCleanup = metricsCleanup.getAndSet(null),
        androidCleanup = androidCleanup.getAndSet(null),
        targetCleanup = targetCleanup.getAndSet(null),
        glCleanup = glCleanup.getAndSet(null),
        jpegCleanup = jpegCleanup.getAndSet(null),
        storageCleanup = storageCleanup.getAndSet(null),
        deliveryCleanup = deliveryCleanup.getAndSet(null),
        externalFacts = externalFacts.getAndSet(null),
        controlResidue = controlResidue.getAndSet(null),
        capturedContentSize = capturedContentSize.getAndSet(null),
        capturedContentVisibility = capturedContentVisibility.getAndSet(null),
        captureEnded = captureEnded.getAndSet(null),
        deliveryFailure = deliveryFailure.getAndSet(null),
    )
}

internal class SessionFactBatch internal constructor(
    internal val runtimeStarted: SessionRuntimeStartedFact?,
    internal val runtimeStartupFailed: SessionRuntimeStartupFailedFact?,
    internal val controlWakeSchedule: SessionControlWakeScheduleFact?,
    internal val controlWakeCancellation: SessionControlWakeCancellationFact?,
    internal val projectionCallbackRegistration: SessionProjectionCallbackRegistrationFact?,
    internal val glConstruction: SessionGlConstructionFact?,
    internal val targetConstructionClaim: SessionTargetConstructionClaimFact?,
    internal val targetConstructionResult: SessionTargetConstructionResultFact?,
    internal val targetListenerClaim: SessionTargetListenerClaimFact?,
    internal val targetListenerApplied: SessionTargetListenerAppliedFact?,
    internal val virtualDisplayClaim: SessionVirtualDisplayClaimFact?,
    internal val virtualDisplayApplied: SessionVirtualDisplayAppliedFact?,
    internal val initialResize: SessionInitialResizeFact?,
    internal val topologyReady: SessionStartupTopologyReadyFact?,
    internal val controlException: SessionControlExceptionFact?,
    internal val controlFatal: SessionControlDirectFatalFact?,
    internal val metricsCleanup: MetricsCleanupSettledFact?,
    internal val androidCleanup: AndroidCleanupSettledFact?,
    internal val targetCleanup: TargetCleanupSettledFact?,
    internal val glCleanup: GlCleanupSettledFact?,
    internal val jpegCleanup: JpegCleanupSettledFact?,
    internal val storageCleanup: StorageCleanupSettledFact?,
    internal val deliveryCleanup: DeliveryCleanupSettledFact?,
    internal val externalFacts: SessionExternalFactsSettledFact?,
    internal val controlResidue: SessionControlResidueSettledFact?,
    internal val capturedContentSize: AndroidCaptureFact.CapturedContentResized?,
    internal val capturedContentVisibility: AndroidCaptureFact.CapturedContentVisibilityChanged?,
    internal val captureEnded: AndroidCaptureFact.CaptureEnded?,
    internal val deliveryFailure: DeliveryFailureNotice?,
)
