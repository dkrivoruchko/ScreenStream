package io.screenstream.engine.internal.android

import android.content.Context
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.SettlementSignal

/** Thin observation-local facade over the single physical Metrics root. */
internal class CaptureMetricsOwner internal constructor(
    applicationContext: Context,
    configuredSource: CaptureMetricsSource?,
    ingressPort: CaptureMetricsIngressPort,
    clock: EngineClock,
    settlementSignal: SettlementSignal,
    attachmentIdentity: Long,
    readinessDeadlineIdentity: Long,
    readinessWakeIdentity: Long,
    readinessTimeoutCause: Throwable,
    closeOperationIdentity: Long,
) {
    private val source = configuredSource ?: BuiltInCaptureMetricsDefinition(applicationContext)
    private val namespace = CaptureMetricsObservationIdentity(attachmentIdentity)
    private val provenance = when (val builtIn = source) {
        is BuiltInCaptureMetricsDefinition -> if (builtIn.fixedDisplay == null) {
            CaptureMetricsSourceProvenance.BuiltInDefaultDisplay
        } else {
            CaptureMetricsSourceProvenance.BuiltInFixedDisplay
        }
        else -> CaptureMetricsSourceProvenance.Custom
    }
    private val mechanics = CaptureMetricsMechanicsOwner(
        source = source,
        sourceProvenance = provenance,
        ingress = ingressPort,
        refreshIdentities = CaptureMetricsOperationIdentityAllocator(
            namespace,
            setOf(attachmentIdentity, closeOperationIdentity),
        ),
        clock = clock,
        settlementSignal = settlementSignal,
        attachmentIdentity = attachmentIdentity,
        readinessDeadlineIdentity = readinessDeadlineIdentity,
        readinessWakeIdentity = readinessWakeIdentity,
        readinessTimeoutCause = readinessTimeoutCause,
        closeOperationIdentity = closeOperationIdentity,
    )

    /** Eager exact attachment root; Session terminal arbitration nests only its settlement gate. */
    internal val attachmentAccess: CaptureMetricsAttachmentAccess = mechanics.attachmentAccess
    internal val endpointTerminationReceipt: CaptureMetricsEndpointTerminationReceipt?
        get() = mechanics.endpointTerminationReceipt
    internal val observationSettlement: CaptureMetricsObservationSettlement?
        get() = mechanics.observationSettlement
    internal val readinessWakeLink: ControlWakeLink = attachmentAccess.wakeLink
    internal fun prestartEndpoint(): PrivateExecutorStartupDisposition = mechanics.prestartEndpoint()
    internal fun attach(): PrivateExecutorSubmissionResult = mechanics.attach()
    internal fun pollPhysical(): CaptureMetricsIngressResult = mechanics.pollPhysical()
    internal fun requestClose(): Boolean = mechanics.requestClose()
    internal fun drivePendingWork(): PrivateExecutorSubmissionResult = mechanics.drivePendingWork()
    internal fun submitPendingClose(): PrivateExecutorSubmissionResult = mechanics.submitPendingClose()
    internal fun requestEndpointShutdown(): CaptureMetricsEndpointShutdownActionOutcome? =
        mechanics.requestEndpointShutdown()
}
