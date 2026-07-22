package io.screenstream.engine.internal.session.runtime

import android.content.Context
import android.media.projection.MediaProjection
import io.screenstream.engine.ScreenCaptureConfig
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.android.AndroidCaptureFactSink
import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.AndroidLaneTerminationReceipt
import io.screenstream.engine.internal.android.AndroidLaneStartupResult
import io.screenstream.engine.internal.android.AndroidProjectionStopEvidence
import io.screenstream.engine.internal.android.AndroidProjectionStopObligation
import io.screenstream.engine.internal.android.AndroidProjectionStopOwnerBindingToken
import io.screenstream.engine.internal.android.AndroidProjectionStopOwnerConstructionClaim
import io.screenstream.engine.internal.android.AndroidProjectionClosureReceipt
import io.screenstream.engine.internal.android.AndroidFinalProjectionStopAction
import io.screenstream.engine.internal.android.AndroidFinalProjectionStopOutcome
import io.screenstream.engine.internal.android.AndroidFiniteOperationIdentity
import io.screenstream.engine.internal.android.AndroidProjectionCallbackRegistrationEvidence
import io.screenstream.engine.internal.android.CaptureMetricsIngressPort
import io.screenstream.engine.internal.android.CaptureMetricsAttachmentAccess
import io.screenstream.engine.internal.android.CaptureMetricsEndpointTerminationReceipt
import io.screenstream.engine.internal.android.CaptureMetricsObservationSettlement
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.delivery.DeliveryAuthorityPort
import io.screenstream.engine.internal.delivery.DeliveryEndpointStartResult
import io.screenstream.engine.internal.delivery.DeliveryOwner
import io.screenstream.engine.internal.delivery.ObservationOwner
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.session.AndroidCleanupSettledFact
import io.screenstream.engine.internal.session.DeliveryCleanupSettledFact
import io.screenstream.engine.internal.session.GlCleanupSettledFact
import io.screenstream.engine.internal.session.JpegCleanupSettledFact
import io.screenstream.engine.internal.session.MetricsCleanupSettledFact
import io.screenstream.engine.internal.session.TargetCleanupSettledFact
import io.screenstream.engine.internal.session.SessionExternalFactsSettledFact
import io.screenstream.engine.internal.session.SessionControlResidueSettledFact
import io.screenstream.engine.internal.session.SessionRuntimeStartedFact
import io.screenstream.engine.internal.session.SessionRuntimeStartupFailedFact
import io.screenstream.engine.internal.session.SessionControlWakeScheduleFact
import io.screenstream.engine.internal.session.SessionControlWakeCancellationFact
import io.screenstream.engine.internal.session.SessionProjectionCallbackRegistrationFact
import io.screenstream.engine.internal.session.SessionGlConstructionFact
import io.screenstream.engine.internal.session.SessionTargetConstructionClaimFact
import io.screenstream.engine.internal.session.SessionTargetConstructionResultFact
import io.screenstream.engine.internal.session.SessionTargetListenerClaimFact
import io.screenstream.engine.internal.session.SessionTargetListenerAppliedFact
import io.screenstream.engine.internal.session.SessionVirtualDisplayClaimFact
import io.screenstream.engine.internal.session.SessionVirtualDisplayAppliedFact
import io.screenstream.engine.internal.session.SessionInitialResizeFact
import io.screenstream.engine.internal.session.SessionRuntimeAction
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlPipelineOwner.SessionConstructionCommand
import io.screenstream.engine.internal.session.cleanup.ExternalFactsSettledReceipt
import io.screenstream.engine.internal.session.cleanup.SessionCleanupDependencyToken
import io.screenstream.engine.internal.session.cleanup.SessionCleanupTransfer
import io.screenstream.engine.internal.session.cleanup.SessionControlResidueSettledProof
import io.screenstream.engine.internal.session.control.ControlCoordinator
import io.screenstream.engine.internal.session.control.ControlStartupResult
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.ControlWakeCancellationAction
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeScheduleAction
import io.screenstream.engine.internal.settlement.ControlWakeResidueSettledProof
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.isHandedOff
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.PreparedTargetAdmissionFact
import io.screenstream.engine.internal.target.TargetPreparationOutcome
import io.screenstream.engine.internal.target.TargetConstructionFoldToken
import io.screenstream.engine.internal.target.TargetOwner
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.target.TargetSourceAvailableFact
import io.screenstream.engine.internal.target.TargetSourceSignal
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.android.AndroidInitialResizeDeadlineIdentity
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.android.AndroidProjectionCallbackUnregistrationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerRemovalEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseEvidence
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetRetirementAdmissionClosedFact
import io.screenstream.engine.internal.target.TargetWorkDrainedFact
import io.screenstream.engine.internal.target.TargetGenerationFencedFact
import io.screenstream.engine.internal.gl.GlClaimedOperationFacts
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Platform-bearing start resource. The Session authority treats this as opaque. */
internal class SessionRuntimeStartRequest internal constructor(
    internal val parameters: ScreenCaptureParameters,
    projection: MediaProjection,
) {
    private val projectionOwner = AtomicReference<AndroidProjectionStopObligation?>(
        AndroidProjectionStopObligation(projection),
    )

    internal fun consumeProjection(): AndroidProjectionStopObligation =
        requireNotNull(projectionOwner.getAndSet(null)) { "MediaProjection was already transferred" }

    internal fun configureProjectionStop(identity: Long, clock: EngineClock, signal: SettlementSignal) {
        checkNotNull(projectionOwner.get()).configure(identity, clock, signal)
    }

    internal val hasProjectionOwnership: Boolean
        get() = projectionOwner.get() != null
}

internal class SessionRuntimeIdentityPlan internal constructor(
    internal val metricsAttachment: Long,
    internal val metricsReadinessDeadline: Long,
    internal val metricsReadinessWake: Long,
    internal val metricsClose: Long,
    internal val androidProjectionEpoch: Long,
    internal val androidCallback: Long,
    internal val androidStop: Long,
    internal val androidCallbackRegistrationOperation: Long,
    internal val androidCallbackRegistrationDeadline: Long,
    internal val androidCallbackRegistrationWake: Long,
    internal val glSessionConstructionOperation: Long,
    internal val glSessionConstructionDeadline: Long,
    internal val glSessionConstructionWake: Long,
    internal val glPartialCleanupOperation: Long,
    internal val glPartialCleanupDeadline: Long,
    internal val glPartialCleanupWake: Long,
    internal val reconciliationOccurrence: Long,
    internal val targetConstructionOperation: Long,
    internal val targetConstructionDeadline: Long,
    internal val targetConstructionWake: Long,
    internal val targetListenerInstallationOperation: Long,
    internal val targetListenerInstallationDeadline: Long,
    internal val targetListenerInstallationWake: Long,
    internal val targetSurfaceReleaseOperation: Long,
    internal val targetSurfaceReleaseDeadline: Long,
    internal val targetSurfaceReleaseWake: Long,
    internal val targetDestructionOperation: Long,
    internal val targetDestructionDeadline: Long,
    internal val targetDestructionWake: Long,
    internal val targetNamespaceDestructionOperation: Long,
    internal val targetNamespaceDestructionDeadline: Long,
    internal val targetNamespaceDestructionWake: Long,
    internal val virtualDisplayCreationOperation: Long,
    internal val virtualDisplayCreationDeadline: Long,
    internal val virtualDisplayCreationWake: Long,
    internal val initialResizeDeadline: Long,
    internal val initialResizeWake: Long,
    internal val androidCallbackUnregistration: Long,
    internal val targetListenerRemoval: Long,
    internal val virtualDisplayRelease: Long,
    internal val glProgramDestructionOperation: Long,
    internal val glProgramDestructionDeadline: Long,
    internal val glProgramDestructionWake: Long,
    internal val glSessionDestructionOperation: Long,
    internal val glSessionDestructionDeadline: Long,
    internal val glSessionDestructionWake: Long,
) {
    init {
        require(metricsAttachment > 0L && metricsReadinessDeadline > 0L && metricsReadinessWake > 0L)
        require(metricsClose > 0L && androidProjectionEpoch > 0L && androidCallback > 0L && androidStop > 0L)
        require(androidCallbackRegistrationOperation > 0L)
        require(androidCallbackRegistrationDeadline > 0L)
        require(androidCallbackRegistrationWake > 0L)
        require(glSessionConstructionOperation > 0L && glSessionConstructionDeadline > 0L && glSessionConstructionWake > 0L)
        require(glPartialCleanupOperation > 0L && glPartialCleanupDeadline > 0L && glPartialCleanupWake > 0L)
        require(reconciliationOccurrence > 0L)
        require(targetConstructionOperation > 0L && targetConstructionDeadline > 0L && targetConstructionWake > 0L)
        require(targetListenerInstallationOperation > 0L && targetListenerInstallationDeadline > 0L)
        require(targetListenerInstallationWake > 0L)
        require(targetSurfaceReleaseOperation > 0L && targetSurfaceReleaseDeadline > 0L && targetSurfaceReleaseWake > 0L)
        require(targetDestructionOperation > 0L && targetDestructionDeadline > 0L && targetDestructionWake > 0L)
        require(targetNamespaceDestructionOperation > 0L && targetNamespaceDestructionDeadline > 0L)
        require(targetNamespaceDestructionWake > 0L)
        require(virtualDisplayCreationOperation > 0L && virtualDisplayCreationDeadline > 0L && virtualDisplayCreationWake > 0L)
        require(initialResizeDeadline > 0L && initialResizeWake > 0L)
        require(androidCallbackUnregistration > 0L && targetListenerRemoval > 0L && virtualDisplayRelease > 0L)
        require(glProgramDestructionOperation > 0L && glProgramDestructionDeadline > 0L && glProgramDestructionWake > 0L)
        require(glSessionDestructionOperation > 0L && glSessionDestructionDeadline > 0L && glSessionDestructionWake > 0L)
    }
}

/** Owns concrete leaves and executes already-selected commands. It owns no lifecycle or product policy. */
internal interface SessionRuntimeCommandPort {
    val observationOwner: ObservationOwner
    val engineClock: EngineClock
    fun bind(
        factPort: SessionRuntimeFactPort,
        metricsIngress: CaptureMetricsIngressPort,
        androidFacts: AndroidCaptureFactSink,
        deliveryAuthority: DeliveryAuthorityPort,
        drain: () -> Unit,
    )
    fun signal()
    fun start(request: SessionRuntimeStartRequest, startupIdentity: Long, identities: SessionRuntimeIdentityPlan)
    fun beginCleanup(transfer: SessionCleanupTransfer)
    fun requestMetricsClose(observationIdentity: Long)
    fun collectMechanicalFacts()
    fun metricsAttachmentAccess(owner: MetricsRuntimeOwnership): CaptureMetricsAttachmentAccess?
    fun requestControlShutdown(proof: SessionControlResidueSettledProof)
    fun registerProjectionCallback(startupIdentity: Long)
    fun scheduleControlWake(fact: SessionControlWakeScheduleFact)
    fun cancelControlWake(fact: SessionControlWakeCancellationFact)
    fun constructGlSession(startupIdentity: Long)
    fun prepareTarget(startupIdentity: Long, requestedIdentity: TargetRequestedIdentity, plan: TargetPlan)
    fun applyTargetConstructionFold(action: SessionRuntimeAction.ApplyTargetConstructionFold)
    fun installTargetListener(action: SessionRuntimeAction.InstallTargetListener)
    fun applyTargetListener(action: SessionRuntimeAction.ApplyTargetListener)
    fun createVirtualDisplay(action: SessionRuntimeAction.CreateVirtualDisplay)
    fun applyVirtualDisplay(action: SessionRuntimeAction.ApplyVirtualDisplay)
    fun attachTargetOwner(owner: TargetRuntimeOwnership): Boolean
    fun attachStorageOwner(owner: StorageRuntimeOwnership): Boolean
}

internal class SessionRuntime internal constructor(
    context: Context,
    private val config: ScreenCaptureConfig,
    private val clock: EngineClock,
) : SessionRuntimeCommandPort {
    private val applicationContext: Context = requireNotNull(context.applicationContext)
    private val ports = AtomicReference<RuntimePorts?>(null)
    private val runtime = AtomicReference<RuntimeRoots?>(null)
    private val partial = AtomicReference<PartialRuntimeRoots?>(null)
    private val cleanup = AtomicReference<CleanupProgress?>(null)
    private val externalLedger = ExternalDependencyLedger()
    private val metricsExternalProducer = AtomicReference<MetricsExternalProducer?>(null)
    private val startupMechanical = AtomicReference<StartupMechanicalProgress?>(null)
    private val startupAdmission = StartupSubmissionAdmission()
    private val control = ControlCoordinator(
        drain = ::drainAuthority,
        failClosed = { raw, direct -> ports.get()?.publishControlFailure(runtime.get(), raw, direct) },
    )

    override val observationOwner: ObservationOwner = ObservationOwner()
    override val engineClock: EngineClock
        get() = clock

    override fun bind(
        factPort: SessionRuntimeFactPort,
        metricsIngress: CaptureMetricsIngressPort,
        androidFacts: AndroidCaptureFactSink,
        deliveryAuthority: DeliveryAuthorityPort,
        drain: () -> Unit,
    ) {
        check(ports.compareAndSet(null, RuntimePorts(factPort, metricsIngress, androidFacts, deliveryAuthority, drain)))
    }

    override fun signal() = control.signal()

    override fun requestMetricsClose(observationIdentity: Long) {
        val metrics = currentMetricsRoot() ?: return
        if (metrics.attachmentAccess.observationIdentity != observationIdentity ||
            metrics.owner.requestClose().not()
        ) return
        metrics.owner.submitPendingClose()
        control.signal()
    }

    override fun metricsAttachmentAccess(owner: MetricsRuntimeOwnership): CaptureMetricsAttachmentAccess? {
        val metrics = currentMetricsRoot() ?: return null
        return if (metrics === owner) metrics.attachmentAccess else null
    }

    /** The second live read closes the partial-to-runtime publication race. */
    private fun currentMetricsRoot(): MetricsRoot? =
        runtime.get()?.metrics ?: partial.get()?.metricsRoot ?: runtime.get()?.metrics

    override fun start(
        request: SessionRuntimeStartRequest,
        startupIdentity: Long,
        identities: SessionRuntimeIdentityPlan,
    ) {
        check(runtime.get() == null)
        val bound = checkNotNull(ports.get())
        val signal = SettlementSignal(control::signal)
        request.configureProjectionStop(identities.androidStop, clock, signal)
        val pendingAndroid = PendingAndroidRoot(request)
        val residue = PartialRuntimeRoots(startupIdentity, ControlRoot(control)).also {
            check(it.installPendingAndroid(pendingAndroid))
        }
        check(partial.compareAndSet(null, residue))
        when (val controlResult = control.start()) {
            ControlStartupResult.Ready -> Unit
            ControlStartupResult.AlreadyStarted -> return
            is ControlStartupResult.Failed -> {
                bound.factPort.publishRuntimeStartupFailed(
                    SessionRuntimeStartupFailedFact(
                        startupIdentity,
                        residue,
                        controlResult.cause,
                    ),
                )
                bound.drain()
                return
            }
        }

        try {
            val metrics = MetricsRoot(
                CaptureMetricsOwner(
                    applicationContext = applicationContext,
                    configuredSource = config.captureMetricsSource,
                    ingressPort = bound.metricsIngress,
                    clock = clock,
                    settlementSignal = signal,
                    attachmentIdentity = identities.metricsAttachment,
                    readinessDeadlineIdentity = identities.metricsReadinessDeadline,
                    readinessWakeIdentity = identities.metricsReadinessWake,
                    readinessTimeoutCause = IllegalStateException("Capture metrics readiness expired"),
                    closeOperationIdentity = identities.metricsClose,
                ),
            ).also { check(residue.publishMetricsRoot(it)) }
            val projectionStop = request.consumeProjection()
            val transferredProjection = TransferredProjectionRoot(projectionStop)
            check(residue.transferAndroidProjection(pendingAndroid, transferredProjection))
            val androidConstructionClaim = projectionStop.precreateOwnerConstructionClaim()
            val constructingAndroid = ConstructingProjectionRoot(
                projectionStop,
                androidConstructionClaim,
            )
            check(residue.publishAndroidConstruction(transferredProjection, constructingAndroid))
            check(projectionStop.claimOwnerConstruction(androidConstructionClaim))
            val androidOwner = AndroidCaptureOwner(
                    projectionStopObligation = projectionStop,
                    projectionConstructionClaim = androidConstructionClaim,
                    projection = projectionStop.projectionForClaimedOwnerConstruction(androidConstructionClaim),
                    projectionOwnerEpoch = identities.androidProjectionEpoch,
                    callbackIdentity = identities.androidCallback,
                    clock = clock,
                    settlementSignal = signal,
                    factSink = bound.androidFacts,
                )
            val androidBinding = projectionStop.precreateAndroidOwnerBindingToken(
                androidConstructionClaim,
                androidOwner,
                androidOwner.projectionBindingLane,
            )
            val android = AndroidRoot(
                androidOwner,
                identities.androidStop,
                projectionStop,
                androidBinding,
            )
            check(residue.publishAndroidCandidate(constructingAndroid, android))
            check(android.commitProjectionBinding())
            val gl = GlRoot(GlPipelineOwner(clock, signal, "ScreenCaptureEngine-GL")).also { residue.glRoot = it }
            val jpeg = JpegRoot(JpegRuntimeOwner(clock, signal)).also { residue.jpegRoot = it }
            val delivery = DeliveryRoot(DeliveryOwner(bound.deliveryAuthority, signal)).also { residue.deliveryRoot = it }

            check(metrics.owner.prestartEndpoint() == PrivateExecutorStartupDisposition.Ready)
            var metricsAttachmentResult = PrivateExecutorSubmissionResult.NotSubmitted
            val metricsAttachmentAdmitted = startupAdmission.runIfOpen {
                metricsAttachmentResult = metrics.owner.attach()
            }
            if (!metricsAttachmentAdmitted || !metricsAttachmentResult.isHandedOff) {
                throw MetricsAttachmentStartupFailure(metricsAttachmentResult)
            }

            check(android.claimLaneStart())
            val androidLaneStarted = android.owner.startLane()
            check(androidLaneStarted && android.owner.laneStartupResult is AndroidLaneStartupResult.Ready)
            check(gl.owner.prestartLane() == PrivateExecutorStartupDisposition.Ready)
            check(jpeg.owner.prestartJpegEndpoint() == PrivateExecutorStartupDisposition.Ready)
            check(delivery.owner.startEndpoint() == DeliveryEndpointStartResult.Ready)

            val roots = RuntimeRoots(startupIdentity, residue.controlRoot, metrics, android, gl, jpeg, delivery)
            check(runtime.compareAndSet(null, roots))
            val startupProgress = StartupMechanicalProgress(roots, identities, control::signal)
            check(startupMechanical.compareAndSet(null, startupProgress))
            partial.compareAndSet(residue, null)
            val externalMetrics = MetricsExternalProducer(metrics)
            check(metricsExternalProducer.compareAndSet(null, externalMetrics))
            externalLedger.register(externalMetrics)
            bound.factPort.publishRuntimeStarted(
                SessionRuntimeStartedFact(
                    startupIdentity,
                    roots,
                    RuntimeLaneReadiness(
                        ControlReady(roots.control), MetricsReady(metrics), AndroidReady(android),
                        GlReady(gl), JpegReady(jpeg), DeliveryReady(delivery),
                    ),
                ),
            )
            check(startupProgress.openAfterRuntimeStartedPublication())
            control.signal()
        } catch (raw: Throwable) {
            bound.factPort.publishRuntimeStartupFailed(
                SessionRuntimeStartupFailedFact(
                    startupIdentity,
                    residue,
                    raw,
                ),
            )
            control.signal()
            if (raw !is Exception && raw !is OutOfMemoryError) throw raw
        }
    }

    override fun beginCleanup(transfer: SessionCleanupTransfer) {
        startupAdmission.close()
        val roots: SessionRuntimeResidue? = runtime.get() ?: partial.get()
        val progress = CleanupProgress(transfer, roots)
        if (!cleanup.compareAndSet(null, progress)) return
        externalLedger.seal(transfer.dependencyToken)
        if (roots != null) {
            val metrics = roots.metrics as? MetricsRoot
            val android = roots.android as? AndroidRoot
            val projectionAndroid = roots.android as? AndroidProjectionCleanupRoot
            val gl = roots.gl as? GlRoot
            val jpeg = roots.jpeg as? JpegRoot
            val delivery = roots.delivery as? DeliveryRoot
            metrics?.owner?.let { owner ->
                owner.requestClose()
                owner.submitPendingClose()
            }
            android?.owner?.closeProjectionCallbackAuthority()
            android?.owner?.closeTargetListenerInstallationAdmission()
            jpeg?.owner?.requestJpegShutdown()
            delivery?.owner?.requestShutdown()
            projectionAndroid?.sealTerminalContext(transfer, transfer.dependencyToken)
        }
        collectMechanicalFacts()
        control.signal()
    }

    override fun collectMechanicalFacts() {
        try {
            val cleanupBeforeStartupCollection = cleanup.get()
            val rootsBeforeStartupCollection = cleanupBeforeStartupCollection?.roots as? RuntimeRoots
            val androidBeforeStartupCollection = rootsBeforeStartupCollection?.android
            androidBeforeStartupCollection?.owner?.laneTerminationReceipt?.let { receipt ->
                androidBeforeStartupCollection.owner.foldFinalLaneTerminationReceipt(receipt)
                cleanupBeforeStartupCollection?.androidLaneTerminationReceipt?.compareAndSet(null, receipt)
            }
            collectStartupFacts()
            val progress = cleanup.get() ?: return
            val bound = ports.get() ?: return
            val roots = progress.roots
            if (roots == null) {
                publishAbsentRoots(progress, bound.factPort)
                return
            }

            val metrics = roots.metrics as? MetricsRoot
            val android = roots.android as? AndroidRoot
            val projectionAndroid = roots.android as? AndroidProjectionCleanupRoot
            val gl = roots.gl as? GlRoot
            val jpeg = roots.jpeg as? JpegRoot
            val delivery = roots.delivery as? DeliveryRoot
            if (roots is RuntimeRoots && android != null && gl != null) {
                progressStartupCleanup(progress, roots, android, gl, bound.factPort)
            }
            metrics?.owner?.requestEndpointShutdown()
            val metricsObservation = metrics?.owner?.observationSettlement
            val metricsEndpoint = metrics?.owner?.endpointTerminationReceipt
            if (metrics != null && metricsObservation != null && metricsEndpoint != null) {
                if (progress.metricsPublished.compareAndSet(false, true)) {
                    bound.factPort.publishMetricsCleanupSettled(
                        MetricsCleanupSettledFact(
                            MetricsWholeRootSettled(metrics, metricsObservation, metricsEndpoint),
                        ),
                    )
                    metricsExternalProducer.get()?.let(externalLedger::close)
                }
            }
            if (android != null && android.owner.laneStartupResult !is AndroidLaneStartupResult.Ready) {
                android.owner.createProjectionStopOperation(android.stopIdentity)
                android.owner.requestLaneQuitSafely()
            }
            android?.owner?.laneTerminationReceipt?.let { raw ->
                android.owner.foldFinalLaneTerminationReceipt(raw)
                progress.androidLaneTerminationReceipt.compareAndSet(null, raw)
            }
            if (roots !is RuntimeRoots) {
                gl?.owner?.prepareOrderlyShutdown()?.let { gl.owner.requestOrderlyShutdown(it) }
            }
            gl?.owner?.laneTerminationReceipt?.let { raw ->
                if (progress.glPublished.compareAndSet(false, true)) {
                    bound.factPort.publishGlCleanupSettled(GlCleanupSettledFact(GlTerminated(gl, raw)))
                }
            }
            if (roots is RuntimeRoots && android != null &&
                (roots.target == null || progress.targetPublished.get()) && progress.glPublished.get()
            ) {
                progressAndroidStopAndLane(progress, android, bound.factPort)
            }
            jpeg?.owner?.requestJpegShutdown()
            jpeg?.owner?.jpegTerminationReceipt?.let { raw ->
                if (progress.jpegPublished.compareAndSet(false, true)) {
                    bound.factPort.publishJpegCleanupSettled(JpegCleanupSettledFact(JpegTerminated(jpeg, raw)))
                }
            }
            delivery?.owner?.terminationReceipt?.let { raw ->
                if (progress.deliveryPublished.compareAndSet(false, true)) {
                    bound.factPort.publishDeliveryCleanupSettled(DeliveryCleanupSettledFact(DeliveryTerminated(delivery, raw)))
                }
            }
            externalLedger.receipt()?.let { receipt ->
                if (progress.externalPublished.compareAndSet(false, true)) {
                    bound.factPort.publishExternalFactsSettled(SessionExternalFactsSettledFact(receipt))
                }
            }
            progressFinalProjectionStop(progress, projectionAndroid, bound.factPort)
        } finally {
            if (!publishOneWakeCancellationFact()) publishControlResidueSettledFact()
        }
    }

    private fun publishOneWakeCancellationFact(): Boolean {
        val startup = startupMechanical.get() ?: return false
        val cleanupProgress = cleanup.get() ?: return false
        val port = ports.get()?.factPort ?: return false
        controlWakeManifest(startup.roots, startup, cleanupProgress).forEach { link ->
            val action: ControlWakeCancellationAction = link.claimCancellationAction() ?: return@forEach
            port.publishControlWakeCancellation(
                SessionControlWakeCancellationFact(startup.roots.startupIdentity, startup.roots, link, action),
            )
            return true
        }
        return false
    }

    private fun publishControlResidueSettledFact() {
        val progress = cleanup.get() ?: return
        val port = ports.get()?.factPort ?: return
        if (progress.controlResidueProof.get() != null || !allNonControlRootsPublished(progress)) return
        val roots = progress.roots ?: return
        val links = controlWakeManifest(roots, startupMechanical.get(), progress)
        val wakeProofs = ArrayList<ControlWakeResidueSettledProof>(links.size)
        links.forEach { link -> wakeProofs += link.residueSettledProof() ?: return }
        val drainerProof = control.drainerSetSettledProof() ?: return
        val proof = SessionControlResidueSettledProof(
            transfer = progress.transfer,
            sessionGeneration = roots.startupIdentity,
            controlOwner = progress.transfer.control.owner,
            cutoff = progress.transfer.dependencyToken,
            wakeProofs = wakeProofs,
            drainerProof = drainerProof,
        )
        if (!progress.controlResidueProof.compareAndSet(null, proof)) return
        if (!control.sealFinalTurn(drainerProof)) {
            progress.controlResidueProof.compareAndSet(proof, null)
            return
        }
        port.publishControlResidueSettled(SessionControlResidueSettledFact(proof))
    }

    private fun allNonControlRootsPublished(progress: CleanupProgress): Boolean =
        (progress.transfer.metrics == null || progress.metricsPublished.get()) &&
                (progress.transfer.android == null || progress.androidPublished.get()) &&
                (progress.transfer.target == null || progress.targetPublished.get()) &&
                (progress.transfer.gl == null || progress.glPublished.get()) &&
                (progress.transfer.jpeg == null || progress.jpegPublished.get()) &&
                progress.transfer.storage == null &&
                (progress.transfer.delivery == null || progress.deliveryPublished.get()) &&
                progress.externalPublished.get()

    private fun controlWakeManifest(
        roots: SessionRuntimeResidue,
        startup: StartupMechanicalProgress?,
        cleanupProgress: CleanupProgress,
    ): List<ControlWakeLink> {
        val links = ArrayList<ControlWakeLink>(13)
        fun add(link: ControlWakeLink?) {
            if (link != null && links.none { it === link }) links += link
        }
        add((roots.metrics as? MetricsRoot)?.owner?.readinessWakeLink)
        add(startup?.projectionRegistrationOperation?.get()?.controlWakeLink)
        val glCommand = startup?.glConstructionCommand?.get()
        add(glCommand?.deadlineWakeLink)
        add(startup?.targetConstructionCommand?.get()?.deadlineWakeLink)
        add(startup?.targetListenerOperation?.get()?.controlWakeLink)
        val virtualDisplay = startup?.virtualDisplayOperation?.get()
        add(virtualDisplay?.controlWakeLink)
        add(virtualDisplay?.returnCell?.evidence?.initialResizeDeadlineOccurrence?.controlWakeLink)
        add(glCommand?.partialCleanupDeadlineWakeLink)
        add(cleanupProgress.surfaceRelease?.deadlineWakeLink)
        add(cleanupProgress.targetScopeDestruction?.deadlineWakeLink)
        add(cleanupProgress.targetScopeDestruction?.namespaceDeadlineWakeLink)
        add(cleanupProgress.programDestruction?.deadlineWakeLink)
        add(cleanupProgress.sessionDestruction?.deadlineWakeLink)
        return links
    }

    private fun progressStartupCleanup(
        cleanupProgress: CleanupProgress,
        roots: RuntimeRoots,
        android: AndroidRoot,
        gl: GlRoot,
        port: SessionRuntimeFactPort,
    ) {
        val startup = startupMechanical.get() ?: return

        val finalLaneReceipt = android.owner.laneTerminationReceipt
        if (finalLaneReceipt != null) {
            android.owner.foldFinalLaneTerminationReceipt(finalLaneReceipt)
            cleanupProgress.androidLaneTerminationReceipt.compareAndSet(null, finalLaneReceipt)
        }
        if (!progressProjectionCallbackCleanup(cleanupProgress, startup, android)) return

        val glFacts = startup.glConstructionFacts.get()
        val glCommand = startup.glConstructionCommand.get()
        if (glCommand != null && glFacts != null && glFacts.result != io.screenstream.engine.internal.gl.GlOperationResult.Success) {
            if (cleanupProgress.glPartialSubmitted.compareAndSet(false, true)) glCommand.submitPartialCleanup()
            publishCleanupWake(
                roots,
                glCommand.partialCleanupDeadlineWakeLink,
                cleanupProgress.glPartialWakeAction,
                port,
            )
            if (!cleanupProgress.glPartialClaimed.get() && glCommand.claimPartialCleanup() != null) {
                cleanupProgress.glPartialClaimed.set(true)
            }
        }

        val targetRoot = startup.targetRoot.get()
        val preparationOutcome = startup.targetPreparationOutcome.get()
        val prepared = startup.preparedTarget.get()
        var currentTarget = startup.currentTarget.get()
        if (targetRoot != null && prepared != null && currentTarget == null && !startup.targetFoldApplied.get()) {
            targetRoot.owner.closeConstructionAdmission()
            val admission = startup.targetAdmission.get()
            val requested = startup.targetRequestedIdentity.get()
            val plan = startup.targetPlan.get()
            if (admission != null && requested != null && plan != null) {
                val token = startup.targetFoldToken.get() ?: targetRoot.owner.claimPreparedTargetResult(
                    admission,
                    startup.identities.targetConstructionOperation,
                    requested,
                    plan,
                )?.also { startup.targetFoldToken.compareAndSet(null, it) }
                if (token != null && !startup.targetFoldApplied.get()) {
                    targetRoot.owner.selectPreparedTargetDisposition(
                        token,
                        startup.identities.targetConstructionOperation,
                        requested,
                        plan,
                        io.screenstream.engine.internal.target.TargetConstructionFoldDisposition.CleanupTerminal,
                    )
                    val result = targetRoot.owner.applyPreparedTargetFold(token)
                    startup.targetConstructionCommand.get()?.retireAfterTargetArbitration()
                    if (result != null) startup.targetFoldApplied.set(true)
                }
            }
        }

        if (targetRoot != null && preparationOutcome is TargetPreparationOutcome.StructurallyAbsent) {
            if (!cleanupProgress.structurallyAbsentPreparationRetired.get() &&
                targetRoot.owner.retireStructurallyAbsentPreparation(preparationOutcome)
            ) {
                cleanupProgress.structurallyAbsentPreparationRetired.set(true)
            }
            if (cleanupProgress.structurallyAbsentPreparationRetired.get() &&
                cleanupProgress.targetPublished.compareAndSet(false, true)
            ) {
                port.publishTargetCleanupSettled(TargetCleanupSettledFact(TargetRetired(targetRoot)))
            }
        }

        currentTarget = startup.currentTarget.get()
        if (currentTarget != null) {
            val listenerOperation = startup.targetListenerOperation.get()
            if (!startup.targetListenerApplied.get() && !cleanupProgress.listenerStructurallyRetired) {
                if (listenerOperation != null) {
                    val arbitration = listenerOperation.arbitrate()
                    if (arbitration != OperationArbitration.None) {
                        val platform = android.owner.claimTargetListenerInstallationResult(listenerOperation)
                        if (platform != null) {
                            val targetResult = currentTarget.consumeAndroidTargetPlatformResult(platform)
                                as? io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled
                            if (targetResult != null &&
                                android.owner.recordTargetListenerInstallationApplied(listenerOperation, targetResult)
                            ) {
                                startup.targetListenerApplied.set(true)
                            }
                        }
                    }
                }
            }

            if (!startup.targetListenerApplied.get() && !cleanupProgress.listenerStructurallyRetired) {
                if (!establishTargetRetirementFence(cleanupProgress, currentTarget)) return
                val cutoff = checkNotNull(cleanupProgress.retirementAdmissionClosed)
                val drained = checkNotNull(cleanupProgress.targetWorkDrained)
                val fenced = checkNotNull(cleanupProgress.targetGenerationFenced)
                if (listenerOperation == null) {
                    val unboundClaimProof = android.owner.targetListenerInstallationUnboundClaimRetiredProof()
                    if (unboundClaimProof != null) {
                        if (currentTarget.applyListenerInstallationClaimRetiredBeforeBinding(
                                unboundClaimProof.claim,
                                unboundClaimProof,
                                cutoff,
                                drained,
                                fenced,
                            ) != null
                        ) {
                            cleanupProgress.listenerClaimRetiredApplied.set(true)
                        }
                    } else if (currentTarget.applyListenerInstallationNeverRequested(cutoff, drained, fenced) != null) {
                        cleanupProgress.listenerNeverRequestedApplied.set(true)
                    }
                } else {
                    val bindingFact = android.owner.targetListenerInstallationBindingCommittedFact(listenerOperation)
                        ?: return
                    val noPlatformEntry = android.owner
                        .targetListenerInstallationNoPlatformEntryOutcome(listenerOperation)
                    if (noPlatformEntry != null) {
                        if (currentTarget.applyListenerInstallationNoPlatformEntry(
                                bindingFact,
                                noPlatformEntry,
                                cutoff,
                                drained,
                                fenced,
                            ) != null
                        ) {
                            cleanupProgress.listenerNoPlatformEntryApplied.set(true)
                        }
                    } else {
                        if (startup.virtualDisplayOperation.get() != null || startup.virtualDisplayRequested.get()) return
                        val proof = android.owner.finalLaneListenerInstallationNoEntryProof(listenerOperation) ?: return
                        if (currentTarget.applyListenerInstallationNeverInstalled(proof) != null) {
                            cleanupProgress.listenerNeverInstalledApplied.set(true)
                        }
                    }
                }
            }
            if (!startup.targetListenerApplied.get() && !cleanupProgress.listenerStructurallyRetired) return

            val creationOperation = startup.virtualDisplayOperation.get()
            if (startup.targetListenerApplied.get() && creationOperation != null &&
                !startup.virtualDisplayApplied.get()
            ) {
                val arbitration = creationOperation.arbitrate()
                if (arbitration != OperationArbitration.None) {
                    val platform = android.owner.claimVirtualDisplayCreationPlatformResult(creationOperation)
                    if (platform != null) {
                        val targetResult = currentTarget.consumeAndroidTargetPlatformResult(platform)
                        if (targetResult != null &&
                            android.owner.applyVirtualDisplayCreationTargetResult(creationOperation, targetResult)
                        ) {
                            startup.virtualDisplayApplied.set(true)
                        }
                    }
                }
                if (!startup.virtualDisplayApplied.get()) return
            }
            if (!establishTargetRetirementFence(cleanupProgress, currentTarget)) return

            val listenerRetired = if (cleanupProgress.listenerStructurallyRetired) {
                true
            } else {
                progressInstalledListenerRetirement(cleanupProgress, startup, android, currentTarget)
            }
            if (!listenerRetired) return

            if (!startup.virtualDisplayApplied.get() && !cleanupProgress.producerNeverRequestedApplied.get()) {
                if (creationOperation == null && !startup.virtualDisplayRequested.get()) {
                    val cutoff = cleanupProgress.retirementAdmissionClosed
                    val drained = cleanupProgress.targetWorkDrained
                    val fenced = cleanupProgress.targetGenerationFenced
                    if (cutoff != null && drained != null && fenced != null &&
                        currentTarget.applyProducerNeverRequested(
                            roots.startupIdentity,
                            cutoff,
                            drained,
                            fenced,
                        ) != null
                    ) {
                        cleanupProgress.producerNeverRequestedApplied.set(true)
                    }
                }
            }
            if (!startup.virtualDisplayApplied.get() && !cleanupProgress.producerNeverRequestedApplied.get()) return

            if (creationOperation != null && cleanupProgress.virtualDisplayRelease == null) {
                cleanupProgress.virtualDisplayRelease = android.owner.createVirtualDisplayReleaseOperation(
                    startup.identities.virtualDisplayRelease,
                )
            }
            cleanupProgress.virtualDisplayRelease?.let { operation ->
                if (cleanupProgress.virtualDisplayReleaseSubmitted.compareAndSet(false, true)) {
                    android.owner.submitVirtualDisplayRelease(operation)
                }
                if (!cleanupProgress.virtualDisplayReleaseApplied.get()) {
                    val arbitration = operation.arbitrate()
                    if (arbitration != OperationArbitration.None) {
                        val platform = android.owner.claimVirtualDisplayReleasePlatformResult(operation)
                        val applied = if (platform != null) {
                            val targetResult = currentTarget.consumeAndroidTargetPlatformResult(platform)
                            targetResult != null && android.owner.applyAttachedVirtualDisplayReleaseTargetResult(operation, targetResult)
                        } else {
                            android.owner.completeMechanicallyDetachedVirtualDisplayRelease(operation)
                        }
                        if (applied) cleanupProgress.virtualDisplayReleaseApplied.set(true)
                    }
                }
            }

            if (cleanupProgress.surfaceRelease == null) {
                cleanupProgress.surfaceRelease = currentTarget.surfaceReleaseReadyFact()?.let(gl.owner::prepareSurfaceRelease)
            }
            progressSurfaceAndTargetScopeCleanup(cleanupProgress, roots, gl, currentTarget, null, port)
        } else if (targetRoot != null && prepared != null && prepared.currentDisposition ==
            io.screenstream.engine.internal.target.PreparedTargetDisposition.CleanupClaimed
        ) {
            if (cleanupProgress.surfaceRelease == null) {
                cleanupProgress.surfaceRelease = gl.owner.prepareCleanupSurfaceRelease(prepared)
            }
            progressSurfaceAndTargetScopeCleanup(cleanupProgress, roots, gl, null, prepared, port)
        }

        val targetSettled = targetRoot == null || cleanupProgress.targetPublished.get()
        val partialSettled = glFacts == null || glFacts.result == io.screenstream.engine.internal.gl.GlOperationResult.Success ||
                cleanupProgress.glPartialClaimed.get()
        if (targetSettled && partialSettled) progressGlSessionCleanup(cleanupProgress, roots, gl, startup, port)
    }

    private fun progressProjectionCallbackCleanup(
        progress: CleanupProgress,
        startup: StartupMechanicalProgress,
        android: AndroidRoot,
    ): Boolean {
        val registration = startup.projectionRegistrationOperation.get()
        android.owner.projectionCallbackCleanupOutcome(registration)?.let { return true }
        if (registration == null) return false
        if (progress.callbackUnregistration == null) {
            progress.callbackUnregistration = android.owner.createProjectionCallbackUnregistrationOperation(
                startup.identities.androidCallbackUnregistration,
            )
        }
        progress.callbackUnregistration?.let { operation ->
            if (progress.callbackUnregistrationSubmitted.compareAndSet(false, true)) {
                android.owner.submitProjectionCallbackUnregistration(operation)
            }
            operation.arbitrate()
        }
        return android.owner.projectionCallbackCleanupOutcome(registration) != null
    }

    private fun establishTargetRetirementFence(
        progress: CleanupProgress,
        target: CurrentTarget,
    ): Boolean {
        progress.retirementAdmissionClosed = progress.retirementAdmissionClosed
            ?: target.closeRetirementAdmission()
        progress.targetWorkDrained = progress.targetWorkDrained
            ?: progress.retirementAdmissionClosed?.let(target::recordEnteredTargetWorkDrained)
        progress.targetGenerationFenced = progress.targetGenerationFenced
            ?: progress.targetWorkDrained?.let(target::fenceGeneration)
        return progress.retirementAdmissionClosed != null && progress.targetWorkDrained != null &&
                progress.targetGenerationFenced != null
    }

    private fun progressInstalledListenerRetirement(
        progress: CleanupProgress,
        startup: StartupMechanicalProgress,
        android: AndroidRoot,
        target: CurrentTarget,
    ): Boolean {
        if (progress.targetGenerationFenced != null && progress.listenerRemoval == null) {
            progress.listenerRemoval = android.owner.createTargetListenerRemovalOperation(
                target,
                startup.identities.targetListenerRemoval,
                null,
            )
        }
        val operation = progress.listenerRemoval ?: return false
        if (progress.listenerRemovalSubmitted.compareAndSet(false, true)) {
            android.owner.submitTargetListenerRemoval(operation)
        }
        operation.arbitrate()
        val ownerBag = operation.ownerBag as? io.screenstream.engine.internal.android.AndroidTargetListenerRemovalOwnerBag
            ?: return false
        return ownerBag.sentinelTicket.occurrence.returnCell.evidence.observedTargetResult != null
    }

    private fun progressAndroidStopAndLane(
        progress: CleanupProgress,
        android: AndroidRoot,
        port: SessionRuntimeFactPort,
    ) {
        if (progress.androidStop == null) {
            progress.androidStop = android.owner.createProjectionStopOperation(android.stopIdentity)
        }
        progress.androidStop?.let { operation ->
            if (progress.androidStopSubmitted.compareAndSet(false, true)) {
                android.owner.submitProjectionStop(operation)
            }
            operation.arbitrate()
        }
        android.owner.requestLaneQuitSafely()
        if (android.owner.laneTerminationReceipt == null) {
            return
        }
        val receipt = checkNotNull(android.owner.laneTerminationReceipt)
        android.owner.foldFinalLaneTerminationReceipt(receipt)
        progress.androidLaneTerminationReceipt.compareAndSet(null, receipt)
        val closure = android.owner.projectionClosureReceipt
        if (closure != null && progress.androidPublished.compareAndSet(false, true)) {
            val terminated = AndroidTerminated(android, receipt, closure, null)
            progress.androidTermination.compareAndSet(null, terminated)
            port.publishAndroidCleanupSettled(AndroidCleanupSettledFact(terminated))
        }
    }

    private fun progressFinalProjectionStop(
        progress: CleanupProgress,
        android: AndroidProjectionCleanupRoot?,
        port: SessionRuntimeFactPort,
    ) {
        if (android == null || progress.androidPublished.get()) return
        val existingClosure = android.closureReceipt()
        if (existingClosure != null) {
            if (android is AndroidRoot && android.owner.laneTerminationReceipt == null) return
            if (progress.androidPublished.compareAndSet(false, true)) {
                val laneReceipt = (android as? AndroidRoot)?.owner?.laneTerminationReceipt
                val terminated = AndroidTerminated(android, laneReceipt, existingClosure, null)
                progress.androidTermination.compareAndSet(null, terminated)
                port.publishAndroidCleanupSettled(
                    AndroidCleanupSettledFact(terminated),
                )
            }
            return
        }
        val action = progress.finalProjectionStopAction.get()
            ?: android.prepareFinalStopAction()?.also { progress.finalProjectionStopAction.compareAndSet(null, it) }
            ?: return
        if (progress.androidPublished.compareAndSet(false, true)) {
            val laneReceipt = (android as? AndroidRoot)?.owner?.laneTerminationReceipt
            val terminated = AndroidTerminated(android, laneReceipt, null, action)
            progress.androidTermination.compareAndSet(null, terminated)
            port.publishAndroidCleanupSettled(AndroidCleanupSettledFact(terminated))
        }
    }

    private fun progressSurfaceAndTargetScopeCleanup(
        progress: CleanupProgress,
        roots: RuntimeRoots,
        gl: GlRoot,
        currentTarget: CurrentTarget?,
        preparedTarget: PreparedTarget?,
        port: SessionRuntimeFactPort,
    ) {
        val startup = startupMechanical.get() ?: return
        progress.surfaceRelease?.let { command ->
            if (progress.surfaceReleaseSubmitted.compareAndSet(false, true)) command.submit()
            publishCleanupWake(roots, command.deadlineWakeLink, progress.surfaceReleaseWakeAction, port)
            if (!progress.surfaceReleaseClaimed.get() && command.claim() != null) {
                progress.surfaceReleaseClaimed.set(true)
            }
        }
        if (!progress.surfaceReleaseClaimed.get()) return
        if (progress.targetScopeDestruction == null) {
            val targetIdentity = GlFiniteOperationIdentity(
                startup.identities.targetDestructionOperation,
                startup.identities.targetDestructionDeadline,
                startup.identities.targetDestructionWake,
                startup.targetDestructionTimeout,
            )
            val namespaceIdentity = GlFiniteOperationIdentity(
                startup.identities.targetNamespaceDestructionOperation,
                startup.identities.targetNamespaceDestructionDeadline,
                startup.identities.targetNamespaceDestructionWake,
                startup.targetNamespaceDestructionTimeout,
            )
            progress.targetScopeDestruction = when {
                currentTarget != null -> gl.owner.prepareTargetScopeDestruction(
                    currentTarget,
                    targetIdentity,
                    namespaceIdentity,
                )
                preparedTarget != null -> gl.owner.prepareCleanupTargetScopeDestruction(
                    preparedTarget,
                    targetIdentity,
                    namespaceIdentity,
                )
                else -> null
            }
        }
        progress.targetScopeDestruction?.let { command ->
            if (progress.targetScopeSubmitted.compareAndSet(false, true)) command.submit()
            publishCleanupWake(roots, command.deadlineWakeLink, progress.targetScopeWakeAction, port)
            if (!progress.targetScopeClaimed.get() && command.claim() != null) {
                progress.targetScopeClaimed.set(true)
            }
            if (progress.targetScopeClaimed.get() && !progress.namespaceSubmitted.get()) {
                if (command.submitNamespaceRetirement()) progress.namespaceSubmitted.set(true)
            }
            if (progress.namespaceSubmitted.get()) {
                publishCleanupWake(roots, command.namespaceDeadlineWakeLink, progress.namespaceWakeAction, port)
                if (!progress.namespaceClaimed.get() && command.claimNamespaceRetirement() != null) {
                    progress.namespaceClaimed.set(true)
                }
            }
        }
        val targetRoot = roots.target as? TargetRoot
        if (preparedTarget?.isCleanupComplete() == true && targetRoot != null &&
            !progress.preparedTargetRetired.get() &&
            targetRoot.owner.retireMechanicallyCompletedPreparedTarget(preparedTarget) != null
        ) {
            progress.preparedTargetRetired.set(true)
        }
        val complete = currentTarget?.isFullyRetired == true || progress.preparedTargetRetired.get()
        if (complete && targetRoot != null && progress.targetPublished.compareAndSet(false, true)) {
            port.publishTargetCleanupSettled(TargetCleanupSettledFact(TargetRetired(targetRoot)))
        }
    }

    private fun progressGlSessionCleanup(
        progress: CleanupProgress,
        roots: RuntimeRoots,
        gl: GlRoot,
        startup: StartupMechanicalProgress,
        port: SessionRuntimeFactPort,
    ) {
        val glFacts = startup.glConstructionFacts.get()
        if (glFacts?.result == io.screenstream.engine.internal.gl.GlOperationResult.Success) {
            if (progress.programDestruction == null && !progress.programClaimed.get()) {
                progress.programDestruction = gl.owner.prepareProgramDestruction(
                    GlFiniteOperationIdentity(
                        startup.identities.glProgramDestructionOperation,
                        startup.identities.glProgramDestructionDeadline,
                        startup.identities.glProgramDestructionWake,
                        startup.glProgramDestructionTimeout,
                    ),
                )
            }
            progress.programDestruction?.let { command ->
                if (progress.programSubmitted.compareAndSet(false, true)) command.submit()
                publishCleanupWake(roots, command.deadlineWakeLink, progress.programWakeAction, port)
                if (!progress.programClaimed.get() && command.claim() != null) progress.programClaimed.set(true)
            }
            if (!progress.programClaimed.get()) return
            if (progress.sessionDestruction == null && !progress.sessionClaimed.get()) {
                progress.sessionDestruction = gl.owner.prepareHealthySessionDestruction(
                    GlFiniteOperationIdentity(
                        startup.identities.glSessionDestructionOperation,
                        startup.identities.glSessionDestructionDeadline,
                        startup.identities.glSessionDestructionWake,
                        startup.glSessionDestructionTimeout,
                    ),
                )
            }
            progress.sessionDestruction?.let { command ->
                if (progress.sessionSubmitted.compareAndSet(false, true)) command.submit()
                publishCleanupWake(roots, command.deadlineWakeLink, progress.sessionWakeAction, port)
                if (!progress.sessionClaimed.get() && command.claim() != null) progress.sessionClaimed.set(true)
            }
            if (!progress.sessionClaimed.get()) return
        }
        gl.owner.prepareOrderlyShutdown()?.let(gl.owner::requestOrderlyShutdown)
    }

    private fun publishCleanupWake(
        roots: RuntimeRoots,
        wakeLink: ControlWakeLink,
        slot: AtomicReference<ControlWakeScheduleAction?>,
        port: SessionRuntimeFactPort,
    ) {
        val action = wakeLink.claimSubmissionAction() ?: return
        if (!slot.compareAndSet(null, action)) return
        port.publishControlWakeSchedule(
            SessionControlWakeScheduleFact(roots.startupIdentity, roots, wakeLink, action),
        )
    }

    override fun requestControlShutdown(proof: SessionControlResidueSettledProof) {
        val progress = cleanup.get() ?: return
        if (!control.owner.ownsThread(Thread.currentThread())) return
        if (progress.controlResidueProof.get() !== proof || proof.transfer !== progress.transfer ||
            proof.controlOwner !== progress.transfer.control.owner ||
            proof.cutoff !== progress.transfer.dependencyToken ||
            proof.sessionGeneration != progress.roots?.startupIdentity ||
            proof.wakeProofs.any { !it.link.acceptsResidueSettledProof(it) } ||
            !control.acceptsDrainerSetSettledProof(proof.drainerProof)
        ) return
        if (!control.sealFinalTurn(proof.drainerProof)) return
        val android = progress.androidTermination.get()
        val action = android?.finalProjectionStopAction
        if (action != null && android.projectionClosureReceipt == null) {
            if (!progress.finalProjectionStopInvoked.compareAndSet(false, true)) return
            when (val outcome = action.invoke()) {
                is AndroidFinalProjectionStopOutcome.Returned -> {
                    val root = progress.roots?.android as? AndroidProjectionCleanupRoot ?: return
                    val closure = root.closureReceipt() ?: return
                    if (closure !== outcome.receipt) return
                }
                is AndroidFinalProjectionStopOutcome.Thrown,
                null,
                    -> return
            }
            control.requestFinalShutdown()
            return
        }
        if (progress.transfer.android != null && android?.projectionClosureReceipt == null) return
        control.requestFinalShutdown()
    }

    override fun registerProjectionCallback(startupIdentity: Long) {
        val progress = startupMechanical.get() ?: return
        if (progress.roots.startupIdentity != startupIdentity || cleanup.get() != null ||
            !progress.projectionRegistrationRequested.compareAndSet(false, true)
        ) return
        val identity = AndroidFiniteOperationIdentity(
            operationIdentity = progress.identities.androidCallbackRegistrationOperation,
            deadlineIdentity = progress.identities.androidCallbackRegistrationDeadline,
            deadlineWakeGeneration = progress.identities.androidCallbackRegistrationWake,
            timeoutCause = progress.projectionRegistrationTimeout,
        )
        val operation = progress.roots.android.owner.createProjectionCallbackRegistrationOperation(identity)
        if (operation == null) {
            ports.get()?.factPort?.publishRuntimeStartupFailed(
                SessionRuntimeStartupFailedFact(startupIdentity, progress.roots, progress.projectionRegistrationCreationFailure),
            )
            control.signal()
            return
        }
        progress.projectionRegistrationOperation.set(operation)
        if (!startupAdmission.runIfOpen { progress.roots.android.owner.submitProjectionCallbackRegistration(operation) }) {
            operation.arbitrateTerminal(mandatoryCleanup = false)
            control.signal()
            return
        }
    }

    override fun scheduleControlWake(fact: SessionControlWakeScheduleFact) {
        val progress = startupMechanical.get() ?: return
        if (progress.roots !== fact.ownership || progress.roots.startupIdentity != fact.startupIdentity ||
            !progress.ownsWake(fact.wakeLink, fact.action) &&
            cleanup.get()?.ownsWake(fact.wakeLink, fact.action, progress) != true
        ) return
        control.scheduleWake(fact.action, clock.nowNanos())
    }

    override fun cancelControlWake(fact: SessionControlWakeCancellationFact) {
        val progress = startupMechanical.get() ?: return
        if (progress.roots !== fact.ownership || progress.roots.startupIdentity != fact.startupIdentity ||
            !progress.ownsWakeLink(fact.wakeLink) && cleanup.get()?.ownsWakeLink(fact.wakeLink, progress) != true
        ) return
        control.cancelWake(fact.wakeLink, fact.action)
    }

    override fun constructGlSession(startupIdentity: Long) {
        val progress = startupMechanical.get() ?: return
        if (progress.roots.startupIdentity != startupIdentity || cleanup.get() != null ||
            !progress.glConstructionRequested.compareAndSet(false, true)
        ) return
        val command = progress.roots.gl.owner.prepareSessionConstruction(
            identity = GlFiniteOperationIdentity(
                progress.identities.glSessionConstructionOperation,
                progress.identities.glSessionConstructionDeadline,
                progress.identities.glSessionConstructionWake,
                progress.glConstructionTimeout,
            ),
            partialCleanupIdentity = GlFiniteOperationIdentity(
                progress.identities.glPartialCleanupOperation,
                progress.identities.glPartialCleanupDeadline,
                progress.identities.glPartialCleanupWake,
                progress.glPartialCleanupTimeout,
            ),
        )
        progress.glConstructionCommand.set(command)
        if (!startupAdmission.runIfOpen { command.submit() }) {
            command.submitPartialCleanup()
            control.signal()
            return
        }
    }

    override fun prepareTarget(
        startupIdentity: Long,
        requestedIdentity: TargetRequestedIdentity,
        plan: TargetPlan,
    ) {
        val progress = startupMechanical.get() ?: return
        if (progress.roots.startupIdentity != startupIdentity || cleanup.get() != null ||
            !progress.targetPreparationRequested.compareAndSet(false, true)
        ) return
        val targetRoot = TargetRoot(TargetOwner())
        if (!progress.roots.attachTarget(targetRoot)) {
            publishStartupMechanicalFailure(progress, progress.targetPreparationFailure)
            return
        }
        progress.targetRoot.set(targetRoot)
        val preparationOutcome = targetRoot.owner.prepareTarget(
            plan = plan,
            requestedIdentity = requestedIdentity,
            constructionIdentity = GlFiniteOperationIdentity(
                progress.identities.targetConstructionOperation,
                progress.identities.targetConstructionDeadline,
                progress.identities.targetConstructionWake,
                progress.targetConstructionTimeout,
            ),
            listenerInstallationOperationIdentity = progress.identities.targetListenerInstallationOperation,
            sourceSignal = progress.targetSourceSignal,
            clock = clock,
            settlementSignal = SettlementSignal(control::signal),
            surfaceReleaseOperationIdentity = progress.identities.targetSurfaceReleaseOperation,
            surfaceReleaseDeadlineIdentity = progress.identities.targetSurfaceReleaseDeadline,
            surfaceReleaseDeadlineWakeGeneration = progress.identities.targetSurfaceReleaseWake,
            surfaceReleaseTimeoutCause = progress.targetSurfaceReleaseTimeout,
            targetDestructionIdentity = GlFiniteOperationIdentity(
                progress.identities.targetDestructionOperation,
                progress.identities.targetDestructionDeadline,
                progress.identities.targetDestructionWake,
                progress.targetDestructionTimeout,
            ),
            namespaceDestructionIdentity = GlFiniteOperationIdentity(
                progress.identities.targetNamespaceDestructionOperation,
                progress.identities.targetNamespaceDestructionDeadline,
                progress.identities.targetNamespaceDestructionWake,
                progress.targetNamespaceDestructionTimeout,
            ),
        )
        if (preparationOutcome == null) {
            publishStartupMechanicalFailure(progress, progress.targetPreparationFailure)
            return
        }
        progress.targetRequestedIdentity.set(requestedIdentity)
        progress.targetPlan.set(plan)
        progress.targetPreparationOutcome.set(preparationOutcome)
        if (preparationOutcome is TargetPreparationOutcome.StructurallyAbsent) {
            val failure = preparationOutcome.failure
            if (failure != null) {
                publishStartupMechanicalFailure(progress, failure)
            } else {
                control.signal()
            }
            return
        }
        val prepared = (preparationOutcome as TargetPreparationOutcome.Prepared).preparedTarget
        val admission = targetRoot.owner.admitPreparedTarget(prepared, requestedIdentity, plan)
        if (admission == null) {
            publishStartupMechanicalFailure(progress, progress.targetAdmissionFailure)
            return
        }
        val command = progress.roots.gl.owner.prepareTargetConstruction(prepared)
        progress.preparedTarget.set(prepared)
        progress.targetAdmission.set(admission)
        progress.targetConstructionCommand.set(command)
        if (!startupAdmission.runIfOpen { command.submit() }) {
            targetRoot.owner.closeConstructionAdmission()
            control.signal()
            return
        }
    }

    override fun applyTargetConstructionFold(action: SessionRuntimeAction.ApplyTargetConstructionFold) {
        val progress = startupMechanical.get() ?: return
        val targetRoot = progress.targetRoot.get() ?: return
        if (progress.roots.startupIdentity != action.startupIdentity || cleanup.get() != null ||
            progress.roots.target !== action.owner || targetRoot !== action.owner ||
            progress.targetRequestedIdentity.get() !== action.requestedIdentity ||
            progress.targetPlan.get() !== action.plan || progress.targetFoldToken.get() !== action.token
        ) return
        startupAdmission.runIfOpen {
            if (!progress.targetFoldApplicationClaimed.compareAndSet(false, true)) return@runIfOpen
            val selected = targetRoot.owner.selectPreparedTargetDisposition(
                token = action.token,
                expectedConstructionOperationIdentity = progress.identities.targetConstructionOperation,
                currentRequestedIdentity = action.requestedIdentity,
                currentPlan = action.plan,
                requestedDisposition = action.disposition,
            )
            if (selected == null) {
                publishStartupMechanicalFailure(progress, progress.targetFoldFailure)
                return@runIfOpen
            }
            val result = targetRoot.owner.applyPreparedTargetFold(action.token)
            progress.targetConstructionCommand.get()?.retireAfterTargetArbitration()
            if (result == null) {
                publishStartupMechanicalFailure(progress, progress.targetFoldFailure)
                return@runIfOpen
            }
            if (result is io.screenstream.engine.internal.target.TargetConstructionInstalledFact) {
                progress.currentTarget.set(result.targetIdentity.target)
            }
            progress.targetFoldApplied.set(true)
            ports.get()?.factPort?.publishTargetConstructionResult(
                SessionTargetConstructionResultFact(
                    action.startupIdentity,
                    targetRoot,
                    result,
                    progress.preparedTarget.get()?.constructionGlEvidence?.result,
                    progress.preparedTarget.get()?.constructionGlEvidence?.contextIntegrity,
                ),
            )
            control.signal()
        }
    }

    override fun installTargetListener(action: SessionRuntimeAction.InstallTargetListener) {
        val progress = startupMechanical.get() ?: return
        val targetRoot = progress.targetRoot.get() ?: return
        if (cleanup.get() != null || progress.roots.startupIdentity != action.startupIdentity ||
            progress.roots.target !== action.targetOwner || targetRoot !== action.targetOwner ||
            action.installedTarget.requestedIdentity !== progress.targetRequestedIdentity.get() ||
            !progress.targetListenerRequested.compareAndSet(false, true)
        ) return
        val operation = progress.roots.android.owner.createTargetListenerInstallationOperation(
            action.installedTarget.targetIdentity.target,
            AndroidFiniteOperationIdentity(
                progress.identities.targetListenerInstallationOperation,
                progress.identities.targetListenerInstallationDeadline,
                progress.identities.targetListenerInstallationWake,
                progress.targetListenerTimeout,
            ),
        )
        if (operation == null) {
            if (cleanup.get() == null) {
                publishStartupMechanicalFailure(progress, progress.targetListenerPreparationFailure)
            } else {
                control.signal()
            }
            return
        }
        progress.targetListenerOperation.set(operation)
        if (!progress.roots.android.owner.submitTargetListenerInstallation(operation)) {
            control.signal()
            return
        }
    }

    override fun applyTargetListener(action: SessionRuntimeAction.ApplyTargetListener) {
        val progress = startupMechanical.get() ?: return
        val targetRoot = progress.targetRoot.get() ?: return
        val operation = progress.targetListenerOperation.get() ?: return
        if (cleanup.get() != null || progress.roots.startupIdentity != action.startupIdentity ||
            progress.roots.android !== action.androidOwner || progress.roots.target !== action.targetOwner ||
            targetRoot !== action.targetOwner || progress.targetListenerPlatformResult.get() !== action.platformResult
        ) return
        startupAdmission.runIfOpen {
            if (!progress.targetListenerApplicationClaimed.compareAndSet(false, true)) return@runIfOpen
            val targetResult = action.platformResult.targetFact.targetIdentity.target
                .consumeAndroidTargetPlatformResult(action.platformResult)
                as? io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled
            if (targetResult == null ||
                !progress.roots.android.owner.recordTargetListenerInstallationApplied(operation, targetResult)
            ) {
                publishStartupMechanicalFailure(progress, progress.targetListenerApplicationFailure)
                return@runIfOpen
            }
            progress.targetListenerApplied.set(true)
            ports.get()?.factPort?.publishTargetListenerApplied(
                SessionTargetListenerAppliedFact(
                    action.startupIdentity,
                    progress.roots.android,
                    targetRoot,
                    targetResult,
                ),
            )
            control.signal()
        }
    }

    override fun createVirtualDisplay(action: SessionRuntimeAction.CreateVirtualDisplay) {
        val progress = startupMechanical.get() ?: return
        val targetRoot = progress.targetRoot.get() ?: return
        if (cleanup.get() != null || progress.roots.startupIdentity != action.startupIdentity ||
            progress.roots.android !== action.androidOwner || progress.roots.target !== action.targetOwner ||
            targetRoot !== action.targetOwner || !progress.virtualDisplayPreparationClaimed.compareAndSet(false, true)
        ) return
        val operation = progress.roots.android.owner.createVirtualDisplayCreationOperation(
            target = action.installedTarget.targetIdentity.target,
            widthPx = action.captureGeometry.widthPx,
            heightPx = action.captureGeometry.heightPx,
            densityDpi = action.captureGeometry.densityDpi,
            identity = AndroidFiniteOperationIdentity(
                progress.identities.virtualDisplayCreationOperation,
                progress.identities.virtualDisplayCreationDeadline,
                progress.identities.virtualDisplayCreationWake,
                progress.virtualDisplayCreationTimeout,
            ),
            initialResizeDeadlineIdentity = if (action.apiBand == AndroidCaptureApiBand.Api34To37) {
                AndroidInitialResizeDeadlineIdentity(
                    progress.identities.initialResizeDeadline,
                    progress.identities.initialResizeWake,
                    progress.initialResizeTimeout,
                )
            } else {
                null
            },
        )
        if (operation == null) {
            publishStartupMechanicalFailure(progress, progress.virtualDisplayPreparationFailure)
            return
        }
        progress.virtualDisplayOperation.set(operation)
        progress.virtualDisplayRequested.set(true)
        if (!startupAdmission.runIfOpen { progress.roots.android.owner.submitVirtualDisplayCreation(operation) }) {
            operation.arbitrateTerminal(mandatoryCleanup = false)
            control.signal()
            return
        }
    }

    override fun applyVirtualDisplay(action: SessionRuntimeAction.ApplyVirtualDisplay) {
        val progress = startupMechanical.get() ?: return
        val targetRoot = progress.targetRoot.get() ?: return
        val operation = progress.virtualDisplayOperation.get() ?: return
        if (cleanup.get() != null || progress.roots.startupIdentity != action.startupIdentity ||
            progress.roots.android !== action.androidOwner || progress.roots.target !== action.targetOwner ||
            targetRoot !== action.targetOwner || progress.virtualDisplayPlatformResult.get() !== action.platformResult
        ) return
        startupAdmission.runIfOpen {
            if (!progress.virtualDisplayApplicationClaimed.compareAndSet(false, true)) return@runIfOpen
            val targetResult = action.platformResult.targetFact.targetIdentity.target
                .consumeAndroidTargetPlatformResult(action.platformResult)
            if (targetResult == null) {
                publishStartupMechanicalFailure(progress, progress.virtualDisplayApplicationFailure)
                return@runIfOpen
            }
            if (!progress.roots.android.owner.applyVirtualDisplayCreationTargetResult(operation, targetResult)) {
                publishStartupMechanicalFailure(progress, progress.virtualDisplayApplicationFailure)
                return@runIfOpen
            }
            progress.virtualDisplayApplied.set(true)
            ports.get()?.factPort?.publishVirtualDisplayApplied(
                SessionVirtualDisplayAppliedFact(
                    action.startupIdentity,
                    progress.roots.android,
                    targetRoot,
                    targetResult,
                    (operation.ownerBag as io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationOwnerBag)
                        .applicationCandidate.actualLogicalTuple,
                ),
            )
            control.signal()
        }
    }

    private fun publishStartupMechanicalFailure(progress: StartupMechanicalProgress, cause: Throwable) {
        ports.get()?.factPort?.publishRuntimeStartupFailed(
            SessionRuntimeStartupFailedFact(progress.roots.startupIdentity, progress.roots, cause),
        )
        control.signal()
    }

    override fun attachTargetOwner(owner: TargetRuntimeOwnership): Boolean =
        runtime.get()?.attachTarget(owner) == true

    override fun attachStorageOwner(owner: StorageRuntimeOwnership): Boolean =
        runtime.get()?.attachStorage(owner) == true

    private fun drainAuthority() {
        collectMechanicalFacts()
        ports.get()?.drain?.invoke()
    }

    private fun collectStartupFacts() {
        val progress = startupMechanical.get() ?: return
        if (!progress.runtimeStartedWasPublished) return
        val bound = ports.get() ?: return
        // Control is the sole successor driver: release the prior occurrence, then submit at most one dirty refresh
        // or the single close ticket. A dirty edge raised during a read remains sticky for the next drain.
        progress.roots.metrics.owner.drivePendingWork()
        val metricsWakeLink = progress.roots.metrics.owner.readinessWakeLink
        publishStartupWakeIfAdmitted(progress, metricsWakeLink, progress.metricsWakeAction, bound.factPort)
        progress.roots.metrics.owner.pollPhysical()
        val operation = progress.projectionRegistrationOperation.get() ?: return
        val wakeLink = operation.controlWakeLink
        publishStartupWakeIfAdmitted(progress, wakeLink, progress.projectionWakeAction, bound.factPort)
        val arbitration = operation.arbitrate()
        if (arbitration != OperationArbitration.None &&
            progress.projectionRegistrationPublished.compareAndSet(false, true)
        ) {
            bound.factPort.publishProjectionCallbackRegistration(
                SessionProjectionCallbackRegistrationFact(
                    progress.roots.startupIdentity,
                    progress.roots.android,
                    operation,
                    arbitration,
                ),
            )
        }
        val glCommand = progress.glConstructionCommand.get() ?: return
        publishStartupWakeIfAdmitted(progress, glCommand.deadlineWakeLink, progress.glWakeAction, bound.factPort)
        val glFacts = glCommand.claim()
        if (glFacts != null && progress.glConstructionPublished.compareAndSet(false, true)) {
            progress.glConstructionFacts.set(glFacts)
            bound.factPort.publishGlConstruction(
                SessionGlConstructionFact(
                    progress.roots.startupIdentity,
                    progress.roots.gl,
                    glFacts,
                    progress.roots.gl.owner.capabilityFacts,
                    progress.roots.android.owner.apiBand,
                ),
            )
        }
        val targetCommand = progress.targetConstructionCommand.get() ?: return
        publishStartupWakeIfAdmitted(progress, targetCommand.deadlineWakeLink, progress.targetWakeAction, bound.factPort)
        val targetRoot = progress.targetRoot.get() ?: return
        val admission = progress.targetAdmission.get() ?: return
        val requestedIdentity = progress.targetRequestedIdentity.get() ?: return
        val plan = progress.targetPlan.get() ?: return
        val token = targetRoot.owner.claimPreparedTargetResult(
            admission,
            progress.identities.targetConstructionOperation,
            requestedIdentity,
            plan,
        )
        if (token != null && progress.targetFoldToken.compareAndSet(null, token) &&
            progress.targetConstructionClaimPublished.compareAndSet(false, true)
        ) {
            bound.factPort.publishTargetConstructionClaim(
                SessionTargetConstructionClaimFact(
                    progress.roots.startupIdentity,
                    targetRoot,
                    requestedIdentity,
                    plan,
                    token,
                    progress.preparedTarget.get()?.constructionGlEvidence?.result,
                    progress.preparedTarget.get()?.constructionGlEvidence?.contextIntegrity,
                ),
            )
        }
        val listenerOperation = progress.targetListenerOperation.get() ?: return
        val listenerWakeLink = listenerOperation.controlWakeLink
        publishStartupWakeIfAdmitted(progress, listenerWakeLink, progress.targetListenerWakeAction, bound.factPort)
        val listenerArbitration = listenerOperation.arbitrate()
        if (listenerArbitration != OperationArbitration.None &&
            progress.targetListenerClaimPublished.compareAndSet(false, true)
        ) {
            val platformResult = progress.roots.android.owner
                .claimTargetListenerInstallationResult(listenerOperation)
            if (platformResult == null) {
                publishStartupMechanicalFailure(progress, progress.targetListenerClaimFailure)
                return
            }
            progress.targetListenerPlatformResult.set(platformResult)
            bound.factPort.publishTargetListenerClaim(
                SessionTargetListenerClaimFact(
                    progress.roots.startupIdentity,
                    progress.roots.android,
                    targetRoot,
                    listenerOperation,
                    listenerArbitration,
                    platformResult,
                ),
            )
        }
        val virtualDisplayOperation = progress.virtualDisplayOperation.get() ?: return
        val virtualDisplayWakeLink = virtualDisplayOperation.controlWakeLink
        publishStartupWakeIfAdmitted(progress, virtualDisplayWakeLink, progress.virtualDisplayWakeAction, bound.factPort)
        val virtualDisplayArbitration = virtualDisplayOperation.arbitrate()
        if (virtualDisplayArbitration != OperationArbitration.None &&
            progress.virtualDisplayClaimPublished.compareAndSet(false, true)
        ) {
            val platformResult = progress.roots.android.owner
                .claimVirtualDisplayCreationPlatformResult(virtualDisplayOperation)
            if (platformResult == null) {
                publishStartupMechanicalFailure(progress, progress.virtualDisplayClaimFailure)
                return
            }
            progress.virtualDisplayPlatformResult.set(platformResult)
            bound.factPort.publishVirtualDisplayClaim(
                SessionVirtualDisplayClaimFact(
                    progress.roots.startupIdentity,
                    progress.roots.android,
                    targetRoot,
                    virtualDisplayOperation,
                    virtualDisplayArbitration,
                    platformResult,
                ),
            )
        }
        if (!progress.virtualDisplayApplied.get()) return
        val initialDeadline = virtualDisplayOperation.returnCell.evidence.initialResizeDeadlineOccurrence ?: return
        publishStartupWakeIfAdmitted(
            progress,
            initialDeadline.controlWakeLink,
            progress.initialResizeWakeAction,
            bound.factPort,
        )
        if (!progress.initialResizePublished.get()) {
            var resize: io.screenstream.engine.internal.android.AndroidCaptureFact.CapturedContentResized? = null
            var startNanos = Long.MIN_VALUE
            var deadlineNanos = Long.MIN_VALUE
            var arbitrationNanos = Long.MIN_VALUE
            var timely = false
            var cause: Throwable? = null
            var ready = false
            virtualDisplayOperation.settlementGate.withLock {
                val evidence = virtualDisplayOperation.returnCell.evidence
                resize = evidence.firstInitialResizeFact
                startNanos = initialDeadline.startNanos
                deadlineNanos = initialDeadline.deadlineNanos
                val exactResize = resize
                if (exactResize != null) {
                    arbitrationNanos = clock.nowNanos()
                    timely = evidence.isTimelyInitialResizeLocked()
                    initialDeadline.retireLocked()
                    cause = if (timely) null else initialDeadline.timeoutCause
                    ready = true
                } else if (initialDeadline.disposition == DeadlineDisposition.Armed) {
                    arbitrationNanos = clock.nowNanos()
                    if (arbitrationNanos >= initialDeadline.deadlineNanos) {
                        initialDeadline.expireLocked()
                        cause = initialDeadline.timeoutCause
                        ready = true
                    }
                } else if (initialDeadline.disposition == DeadlineDisposition.Expired) {
                    arbitrationNanos = clock.nowNanos()
                    cause = initialDeadline.timeoutCause
                    ready = true
                }
            }
            if (ready && progress.initialResizePublished.compareAndSet(false, true)) {
                bound.factPort.publishInitialResize(
                    SessionInitialResizeFact(
                        progress.roots.startupIdentity,
                        progress.roots.android,
                        resize,
                        startNanos,
                        deadlineNanos,
                        arbitrationNanos,
                        timely,
                        cause,
                    ),
                )
            }
        }
    }

    private fun publishStartupWakeIfAdmitted(
        progress: StartupMechanicalProgress,
        wakeLink: ControlWakeLink?,
        slot: AtomicReference<ControlWakeScheduleAction?>,
        port: SessionRuntimeFactPort,
    ) {
        val exact = wakeLink ?: return
        startupAdmission.runIfOpen {
            val action = exact.claimSubmissionAction() ?: return@runIfOpen
            if (!slot.compareAndSet(null, action)) return@runIfOpen
            port.publishControlWakeSchedule(
                SessionControlWakeScheduleFact(progress.roots.startupIdentity, progress.roots, exact, action),
            )
        }
    }

    private fun publishAbsentRoots(progress: CleanupProgress, port: SessionRuntimeFactPort) {
        externalLedger.receipt()?.let { receipt ->
            if (progress.externalPublished.compareAndSet(false, true)) {
                port.publishExternalFactsSettled(SessionExternalFactsSettledFact(receipt))
            }
        }
    }
}

private class RuntimePorts(
    val factPort: SessionRuntimeFactPort,
    val metricsIngress: CaptureMetricsIngressPort,
    val androidFacts: AndroidCaptureFactSink,
    val deliveryAuthority: DeliveryAuthorityPort,
    val drain: () -> Unit,
) {
    fun publishControlFailure(ownership: SessionRuntimeOwnership?, raw: Throwable, direct: Boolean) {
        if (direct) factPort.publishControlDirectFatal(io.screenstream.engine.internal.session.SessionControlDirectFatalFact(ownership, raw))
        else factPort.publishControlException(io.screenstream.engine.internal.session.SessionControlExceptionFact(ownership, raw))
    }
}

private class PartialRuntimeRoots(
    override val startupIdentity: Long,
    val controlRoot: ControlRoot,
) : SessionRuntimeResidue {
    private val metricsOwnership = AtomicReference<MetricsRoot?>(null)
    private val androidOwnership = AtomicReference<AndroidRuntimeOwnership?>(null)
    var glRoot: GlRoot? = null
    var jpegRoot: JpegRoot? = null
    var deliveryRoot: DeliveryRoot? = null
    override val control: ControlRuntimeOwnership get() = controlRoot
    val metricsRoot: MetricsRoot?
        get() = metricsOwnership.get()
    override val metrics: MetricsRuntimeOwnership?
        get() = metricsOwnership.get()
    override val android: AndroidRuntimeOwnership? get() = androidOwnership.get()
    override val target: TargetRuntimeOwnership? get() = null
    override val gl: GlRuntimeOwnership? get() = glRoot
    override val jpeg: JpegRuntimeOwnership? get() = jpegRoot
    override val storage: StorageRuntimeOwnership? get() = null
    override val delivery: DeliveryRuntimeOwnership? get() = deliveryRoot

    fun publishMetricsRoot(metrics: MetricsRoot): Boolean =
        metricsOwnership.compareAndSet(null, metrics)

    fun installPendingAndroid(pending: PendingAndroidRoot): Boolean =
        androidOwnership.compareAndSet(null, pending)

    fun transferAndroidProjection(
        pending: PendingAndroidRoot,
        transferred: TransferredProjectionRoot,
    ): Boolean = androidOwnership.compareAndSet(pending, transferred)

    fun publishAndroidConstruction(
        transferred: TransferredProjectionRoot,
        constructing: ConstructingProjectionRoot,
    ): Boolean = androidOwnership.compareAndSet(transferred, constructing)

    fun publishAndroidCandidate(
        constructing: ConstructingProjectionRoot,
        candidate: AndroidRoot,
    ): Boolean = androidOwnership.compareAndSet(constructing, candidate)
}

private class RuntimeRoots(
    override val startupIdentity: Long,
    override val control: ControlRoot,
    override val metrics: MetricsRoot,
    override val android: AndroidRoot,
    override val gl: GlRoot,
    override val jpeg: JpegRoot,
    override val delivery: DeliveryRoot,
) : SessionRuntimeOwnership {
    private val targetOwner = AtomicReference<TargetRuntimeOwnership?>(null)
    private val storageOwner = AtomicReference<StorageRuntimeOwnership?>(null)
    override val target: TargetRuntimeOwnership? get() = targetOwner.get()
    override val storage: StorageRuntimeOwnership? get() = storageOwner.get()
    fun attachTarget(owner: TargetRuntimeOwnership): Boolean = targetOwner.compareAndSet(null, owner)
    fun attachStorage(owner: StorageRuntimeOwnership): Boolean = storageOwner.compareAndSet(null, owner)
}

private class ControlRoot(coordinator: ControlCoordinator) : ControlRuntimeOwnership {
    override val terminationReceipt: ControlTerminationReceipt = coordinator.bindCleanupOwner(this)
}
private class MetricsRoot(val owner: CaptureMetricsOwner) : MetricsRuntimeOwnership {
    val attachmentAccess: CaptureMetricsAttachmentAccess
        get() = owner.attachmentAccess
    override val jointReadinessReceipt: MetricsJointReadinessReceipt = MetricsJointReady(this)
}
private interface AndroidProjectionCleanupRoot : AndroidRuntimeOwnership {
    fun sealTerminalContext(cutoffIdentity: Any, workManifestIdentity: Any)
    fun prepareFinalStopAction(): AndroidFinalProjectionStopAction?
    fun closureReceipt(): AndroidProjectionClosureReceipt?
}

private class AndroidRoot(
    val owner: AndroidCaptureOwner,
    val stopIdentity: Long,
    private val projectionStop: AndroidProjectionStopObligation,
    private val bindingToken: AndroidProjectionStopOwnerBindingToken,
) : AndroidProjectionCleanupRoot {
    fun commitProjectionBinding(): Boolean =
        bindingToken.obligation === projectionStop && bindingToken.owner === owner &&
            bindingToken.lane === owner.projectionBindingLane &&
            projectionStop.commitAndroidOwnerBinding(bindingToken)

    fun claimLaneStart(): Boolean =
        commitProjectionBinding() && projectionStop.claimAndroidLaneStart(bindingToken)

    override val apiBand: AndroidCaptureApiBand
        get() = owner.apiBand
    override fun matchesCallbackProvenance(
        provenance: io.screenstream.engine.internal.android.AndroidCallbackProvenance,
    ): Boolean = provenance.owner === owner
    override fun sealTerminalContext(cutoffIdentity: Any, workManifestIdentity: Any) {
        if (commitProjectionBinding()) {
            owner.sealProjectionStopTerminalContext(cutoffIdentity, workManifestIdentity)
        } else {
            val cutoff = projectionStop.sealTerminalCutoff(cutoffIdentity)
            projectionStop.sealWorkManifest(cutoff, workManifestIdentity)
        }
    }

    override fun prepareFinalStopAction(): AndroidFinalProjectionStopAction? {
        return if (commitProjectionBinding()) {
            owner.prepareFinalProjectionStopAction()
        } else {
            projectionStop.prepareFinalActionWithoutAndroidOwner(bindingToken.constructionClaim)
        }
    }

    override fun closureReceipt(): AndroidProjectionClosureReceipt? = projectionStop.closureReceipt()
}

private class ConstructingProjectionRoot(
    private val projectionStop: AndroidProjectionStopObligation,
    private val constructionClaim: AndroidProjectionStopOwnerConstructionClaim,
) : AndroidProjectionCleanupRoot {
    override fun sealTerminalContext(cutoffIdentity: Any, workManifestIdentity: Any) {
        check(projectionStop.claimOwnerConstruction(constructionClaim))
        val cutoff = projectionStop.sealTerminalCutoff(cutoffIdentity)
        projectionStop.sealWorkManifest(cutoff, workManifestIdentity)
    }

    override fun prepareFinalStopAction(): AndroidFinalProjectionStopAction? {
        if (!projectionStop.claimOwnerConstruction(constructionClaim)) {
            return projectionStop.prepareFinalActionWithoutAndroidOwner(constructionClaim)
        }
        return projectionStop.prepareFinalActionWithoutAndroidOwner(constructionClaim)
    }

    override fun closureReceipt(): AndroidProjectionClosureReceipt? = projectionStop.closureReceipt()
}

private class PendingAndroidRoot(private val request: SessionRuntimeStartRequest) : AndroidProjectionCleanupRoot {
    private val projectionStop by lazy(LazyThreadSafetyMode.SYNCHRONIZED, request::consumeProjection)
    override fun sealTerminalContext(cutoffIdentity: Any, workManifestIdentity: Any) {
        val cutoff = projectionStop.sealTerminalCutoff(cutoffIdentity)
        projectionStop.sealWorkManifest(cutoff, workManifestIdentity)
    }

    override fun prepareFinalStopAction(): AndroidFinalProjectionStopAction? =
        projectionStop.prepareFinalActionWithoutAndroidOwner()

    override fun closureReceipt(): AndroidProjectionClosureReceipt? = projectionStop.closureReceipt()
}

private class TransferredProjectionRoot(
    private val projectionStop: AndroidProjectionStopObligation,
) : AndroidProjectionCleanupRoot {
    override fun sealTerminalContext(cutoffIdentity: Any, workManifestIdentity: Any) {
        val cutoff = projectionStop.sealTerminalCutoff(cutoffIdentity)
        projectionStop.sealWorkManifest(cutoff, workManifestIdentity)
    }

    override fun prepareFinalStopAction(): AndroidFinalProjectionStopAction? =
        projectionStop.prepareFinalActionWithoutAndroidOwner()

    override fun closureReceipt(): AndroidProjectionClosureReceipt? = projectionStop.closureReceipt()
}
private class GlRoot(val owner: GlPipelineOwner) : GlRuntimeOwnership
private class TargetRoot(val owner: TargetOwner) : TargetRuntimeOwnership
private class JpegRoot(val owner: JpegRuntimeOwner) : JpegRuntimeOwnership
private class DeliveryRoot(val owner: DeliveryOwner) : DeliveryRuntimeOwnership

private class ControlReady(override val owner: ControlRuntimeOwnership) : ControlLaneReadyReceipt
private class MetricsReady(override val owner: MetricsRuntimeOwnership) : MetricsLaneReadyReceipt
private class MetricsJointReady(override val owner: MetricsRuntimeOwnership) : MetricsJointReadinessReceipt
private class AndroidReady(override val owner: AndroidRuntimeOwnership) : AndroidLaneReadyReceipt
private class GlReady(override val owner: GlRuntimeOwnership) : GlLaneReadyReceipt
private class JpegReady(override val owner: JpegRuntimeOwnership) : JpegLaneReadyReceipt
private class DeliveryReady(override val owner: DeliveryRuntimeOwnership) : DeliveryLaneReadyReceipt

private class MetricsWholeRootSettled(
    override val owner: MetricsRuntimeOwnership,
    override val observationSettlement: CaptureMetricsObservationSettlement,
    override val endpointTerminationReceipt: CaptureMetricsEndpointTerminationReceipt,
) : MetricsTerminationReceipt

private class MetricsAttachmentStartupFailure(
    internal val submissionResult: PrivateExecutorSubmissionResult,
) : IllegalStateException("Capture metrics attachment was not handed off: $submissionResult")
private class AndroidTerminated(
    override val owner: AndroidRuntimeOwnership,
    val raw: io.screenstream.engine.internal.android.AndroidLaneTerminationReceipt?,
    closure: AndroidProjectionClosureReceipt?,
    override val finalProjectionStopAction: AndroidFinalProjectionStopAction?,
) : AndroidTerminationReceipt {
    override val projectionClosureReceipt: AndroidProjectionClosureReceipt? = closure

    init {
        check((closure == null) != (finalProjectionStopAction == null))
    }
}
private class GlTerminated(override val owner: GlRuntimeOwnership, val raw: PrivateExecutorTerminationReceipt) : GlTerminationReceipt
private class TargetRetired(override val owner: TargetRuntimeOwnership) : TargetRetirementReceipt
private class JpegTerminated(override val owner: JpegRuntimeOwnership, val raw: PrivateExecutorTerminationReceipt) : JpegTerminationReceipt
private class DeliveryTerminated(override val owner: DeliveryRuntimeOwnership, val raw: io.screenstream.engine.internal.delivery.DeliveryTerminationReceipt) : DeliveryTerminationReceipt
private class ExternalSettled(override val dependencyToken: SessionCleanupDependencyToken) : ExternalFactsSettledReceipt

private class ExternalDependencyLedger {
    private val gate = ReentrantLock(false)
    private val open = java.util.IdentityHashMap<ExternalProducerIdentity, Boolean>()
    private var sealedToken: SessionCleanupDependencyToken? = null
    private var settled: ExternalSettled? = null

    fun register(identity: ExternalProducerIdentity) = gate.withLock {
        check(sealedToken == null)
        check(open.put(identity, true) == null)
    }

    fun close(identity: ExternalProducerIdentity) = gate.withLock {
        if (open.remove(identity) != null) settleIfCompleteLocked()
    }

    fun seal(token: SessionCleanupDependencyToken) = gate.withLock {
        if (sealedToken == null) sealedToken = token else check(sealedToken === token)
        settleIfCompleteLocked()
    }

    fun receipt(): ExternalSettled? = gate.withLock { settled }

    private fun settleIfCompleteLocked() {
        val token = sealedToken ?: return
        if (open.isEmpty() && settled == null) settled = ExternalSettled(token)
    }
}

private sealed interface ExternalProducerIdentity
private class MetricsExternalProducer(val root: MetricsRoot) : ExternalProducerIdentity

private class CleanupProgress(
    val transfer: SessionCleanupTransfer,
    val roots: SessionRuntimeResidue?,
) {
    var callbackUnregistration: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? = null
    var listenerRemoval: OperationOccurrence<AndroidTargetListenerRemovalEvidence>? = null
    var virtualDisplayRelease: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? = null
    var androidStop: OperationOccurrence<AndroidProjectionStopEvidence>? = null
    var retirementAdmissionClosed: TargetRetirementAdmissionClosedFact? = null
    var targetWorkDrained: TargetWorkDrainedFact? = null
    var targetGenerationFenced: TargetGenerationFencedFact? = null
    var surfaceRelease: GlPipelineOwner.SurfaceReleaseCommand? = null
    var targetScopeDestruction: GlPipelineOwner.TargetScopeDestructionCommand? = null
    var programDestruction: GlPipelineOwner.DestructionCommand? = null
    var sessionDestruction: GlPipelineOwner.DestructionCommand? = null
    val androidLaneTerminationReceipt = AtomicReference<AndroidLaneTerminationReceipt?>(null)
    val callbackUnregistrationSubmitted = AtomicBoolean(false)
    val listenerNeverInstalledApplied = AtomicBoolean(false)
    val listenerNeverRequestedApplied = AtomicBoolean(false)
    val listenerClaimRetiredApplied = AtomicBoolean(false)
    val listenerNoPlatformEntryApplied = AtomicBoolean(false)
    val listenerStructurallyRetired: Boolean
        get() = listenerNeverInstalledApplied.get() || listenerNeverRequestedApplied.get() ||
                listenerClaimRetiredApplied.get() || listenerNoPlatformEntryApplied.get()
    val producerNeverRequestedApplied = AtomicBoolean(false)
    val listenerRemovalSubmitted = AtomicBoolean(false)
    val virtualDisplayReleaseSubmitted = AtomicBoolean(false)
    val virtualDisplayReleaseApplied = AtomicBoolean(false)
    val androidStopSubmitted = AtomicBoolean(false)
    val surfaceReleaseSubmitted = AtomicBoolean(false)
    val surfaceReleaseClaimed = AtomicBoolean(false)
    val targetScopeSubmitted = AtomicBoolean(false)
    val targetScopeClaimed = AtomicBoolean(false)
    val namespaceSubmitted = AtomicBoolean(false)
    val namespaceClaimed = AtomicBoolean(false)
    val glPartialSubmitted = AtomicBoolean(false)
    val glPartialClaimed = AtomicBoolean(false)
    val programSubmitted = AtomicBoolean(false)
    val programClaimed = AtomicBoolean(false)
    val sessionSubmitted = AtomicBoolean(false)
    val sessionClaimed = AtomicBoolean(false)
    val targetPublished = AtomicBoolean(false)
    val preparedTargetRetired = AtomicBoolean(false)
    val structurallyAbsentPreparationRetired = AtomicBoolean(false)
    val glPartialWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val surfaceReleaseWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val targetScopeWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val namespaceWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val programWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val sessionWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)

    fun ownsWake(
        wakeLink: ControlWakeLink,
        action: ControlWakeScheduleAction,
        startup: StartupMechanicalProgress,
    ): Boolean {
        val glCommand = startup.glConstructionCommand.get()
        if (glCommand?.partialCleanupDeadlineWakeLink === wakeLink && glPartialWakeAction.get() === action) return true
        if (surfaceRelease?.deadlineWakeLink === wakeLink && surfaceReleaseWakeAction.get() === action) return true
        if (targetScopeDestruction?.deadlineWakeLink === wakeLink && targetScopeWakeAction.get() === action) return true
        if (targetScopeDestruction?.namespaceDeadlineWakeLink === wakeLink && namespaceWakeAction.get() === action) return true
        if (programDestruction?.deadlineWakeLink === wakeLink && programWakeAction.get() === action) return true
        return sessionDestruction?.deadlineWakeLink === wakeLink && sessionWakeAction.get() === action
    }

    fun ownsWakeLink(wakeLink: ControlWakeLink, startup: StartupMechanicalProgress): Boolean {
        if (startup.glConstructionCommand.get()?.partialCleanupDeadlineWakeLink === wakeLink) return true
        if (surfaceRelease?.deadlineWakeLink === wakeLink) return true
        if (targetScopeDestruction?.deadlineWakeLink === wakeLink ||
            targetScopeDestruction?.namespaceDeadlineWakeLink === wakeLink
        ) return true
        return programDestruction?.deadlineWakeLink === wakeLink || sessionDestruction?.deadlineWakeLink === wakeLink
    }
    val metricsPublished = AtomicBoolean(false)
    val androidPublished = AtomicBoolean(false)
    val androidTermination = AtomicReference<AndroidTerminated?>(null)
    val finalProjectionStopAction = AtomicReference<AndroidFinalProjectionStopAction?>(null)
    val finalProjectionStopInvoked = AtomicBoolean(false)
    val glPublished = AtomicBoolean(false)
    val jpegPublished = AtomicBoolean(false)
    val deliveryPublished = AtomicBoolean(false)
    val externalPublished = AtomicBoolean(false)
    val controlResidueProof = AtomicReference<SessionControlResidueSettledProof?>(null)
}

private class StartupMechanicalProgress(
    val roots: RuntimeRoots,
    val identities: SessionRuntimeIdentityPlan,
    signal: () -> Unit,
) {
    /** Keeps attachment-originated Control signals from driving successor work before RuntimeStarted is offered. */
    private val runtimeStartedPublished = AtomicBoolean(false)
    val runtimeStartedWasPublished: Boolean
        get() = runtimeStartedPublished.get()

    fun openAfterRuntimeStartedPublication(): Boolean =
        runtimeStartedPublished.compareAndSet(false, true)

    val metricsWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val projectionRegistrationRequested = AtomicBoolean(false)
    val projectionRegistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    val projectionWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val projectionRegistrationPublished = AtomicBoolean(false)
    val glConstructionRequested = AtomicBoolean(false)
    val glConstructionCommand = AtomicReference<SessionConstructionCommand?>(null)
    val glWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val glConstructionPublished = AtomicBoolean(false)
    val glConstructionFacts = AtomicReference<GlClaimedOperationFacts?>(null)
    val targetPreparationRequested = AtomicBoolean(false)
    val targetRoot = AtomicReference<TargetRoot?>(null)
    val currentTarget = AtomicReference<CurrentTarget?>(null)
    val targetRequestedIdentity = AtomicReference<TargetRequestedIdentity?>(null)
    val targetPlan = AtomicReference<TargetPlan?>(null)
    val targetPreparationOutcome = AtomicReference<TargetPreparationOutcome?>(null)
    val preparedTarget = AtomicReference<PreparedTarget?>(null)
    val targetAdmission = AtomicReference<PreparedTargetAdmissionFact?>(null)
    val targetConstructionCommand =
        AtomicReference<GlPipelineOwner.TargetConstructionCommand?>(null)
    val targetWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val targetFoldToken = AtomicReference<TargetConstructionFoldToken?>(null)
    val targetConstructionClaimPublished = AtomicBoolean(false)
    val targetFoldApplicationClaimed = AtomicBoolean(false)
    val targetFoldApplied = AtomicBoolean(false)
    val targetListenerRequested = AtomicBoolean(false)
    val targetListenerOperation =
        AtomicReference<OperationOccurrence<AndroidTargetListenerInstallationEvidence>?>(null)
    val targetListenerWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val targetListenerPlatformResult = AtomicReference<AndroidTargetPlatformResult.ListenerInstalled?>(null)
    val targetListenerClaimPublished = AtomicBoolean(false)
    val targetListenerApplicationClaimed = AtomicBoolean(false)
    val targetListenerApplied = AtomicBoolean(false)
    val virtualDisplayRequested = AtomicBoolean(false)
    val virtualDisplayPreparationClaimed = AtomicBoolean(false)
    val virtualDisplayOperation = AtomicReference<OperationOccurrence<AndroidVirtualDisplayCreationEvidence>?>(null)
    val virtualDisplayWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val virtualDisplayPlatformResult = AtomicReference<AndroidTargetPlatformResult?>(null)
    val virtualDisplayClaimPublished = AtomicBoolean(false)
    val virtualDisplayApplicationClaimed = AtomicBoolean(false)
    val virtualDisplayApplied = AtomicBoolean(false)
    val initialResizeWakeAction = AtomicReference<ControlWakeScheduleAction?>(null)
    val initialResizePublished = AtomicBoolean(false)
    val targetSourceAvailable = AtomicReference<TargetSourceAvailableFact?>(null)
    val targetSourceSignal = TargetSourceSignal { fact ->
        targetSourceAvailable.set(fact)
        signal()
    }
    val projectionRegistrationTimeout = IllegalStateException("Projection callback registration expired")
    val projectionRegistrationCreationFailure =
        IllegalStateException("Projection callback registration could not be prepared")
    val glConstructionTimeout = IllegalStateException("GL Session construction expired")
    val glPartialCleanupTimeout = IllegalStateException("GL partial Session cleanup expired")
    val targetPreparationFailure = IllegalStateException("Target preparation could not be created")
    val targetAdmissionFailure = IllegalStateException("Target preparation could not be admitted")
    val targetFoldFailure = IllegalStateException("Target construction fold could not be applied")
    val targetConstructionTimeout = IllegalStateException("Target construction expired")
    val targetSurfaceReleaseTimeout = IllegalStateException("Target surface release expired")
    val targetDestructionTimeout = IllegalStateException("Target destruction expired")
    val targetNamespaceDestructionTimeout = IllegalStateException("Target namespace destruction expired")
    val targetListenerTimeout = IllegalStateException("Target listener installation expired")
    val targetListenerPreparationFailure = IllegalStateException("Target listener installation could not be prepared")
    val targetListenerClaimFailure = IllegalStateException("Target listener result could not be claimed")
    val targetListenerApplicationFailure = IllegalStateException("Target listener result could not be applied")
    val virtualDisplayCreationTimeout = IllegalStateException("VirtualDisplay creation expired")
    val initialResizeTimeout = IllegalStateException("Initial captured-content resize expired")
    val virtualDisplayPreparationFailure = IllegalStateException("VirtualDisplay creation could not be prepared")
    val virtualDisplayClaimFailure = IllegalStateException("VirtualDisplay creation result could not be claimed")
    val virtualDisplayApplicationFailure = IllegalStateException("VirtualDisplay result could not be applied")
    val glProgramDestructionTimeout = IllegalStateException("GL program destruction expired")
    val glSessionDestructionTimeout = IllegalStateException("GL Session destruction expired")

    fun ownsWake(wakeLink: ControlWakeLink, action: ControlWakeScheduleAction): Boolean {
        if (roots.metrics.owner.readinessWakeLink === wakeLink && metricsWakeAction.get() === action) return true
        val operation = projectionRegistrationOperation.get() ?: return false
        if (operation.controlWakeLink === wakeLink && projectionWakeAction.get() === action) return true
        val glCommand = glConstructionCommand.get() ?: return false
        if (glCommand.deadlineWakeLink === wakeLink && glWakeAction.get() === action) return true
        val targetCommand = targetConstructionCommand.get() ?: return false
        if (targetCommand.deadlineWakeLink === wakeLink && targetWakeAction.get() === action) return true
        val listenerOperation = targetListenerOperation.get() ?: return false
        if (listenerOperation.controlWakeLink === wakeLink && targetListenerWakeAction.get() === action) return true
        val virtualDisplayOperation = virtualDisplayOperation.get() ?: return false
        if (virtualDisplayOperation.controlWakeLink === wakeLink && virtualDisplayWakeAction.get() === action) return true
        val initialDeadline = virtualDisplayOperation.returnCell.evidence.initialResizeDeadlineOccurrence ?: return false
        return initialDeadline.controlWakeLink === wakeLink && initialResizeWakeAction.get() === action
    }

    fun ownsWakeLink(wakeLink: ControlWakeLink): Boolean {
        if (roots.metrics.owner.readinessWakeLink === wakeLink) return true
        if (projectionRegistrationOperation.get()?.controlWakeLink === wakeLink) return true
        val glCommand = glConstructionCommand.get()
        if (glCommand?.deadlineWakeLink === wakeLink || glCommand?.partialCleanupDeadlineWakeLink === wakeLink) return true
        if (targetConstructionCommand.get()?.deadlineWakeLink === wakeLink) return true
        if (targetListenerOperation.get()?.controlWakeLink === wakeLink) return true
        val vd = virtualDisplayOperation.get()
        if (vd?.controlWakeLink === wakeLink || vd?.returnCell?.evidence?.initialResizeDeadlineOccurrence?.controlWakeLink === wakeLink) return true
        return false
    }
}

/** Root-before-submit cutoff arbitration. A claim made before close may finish; no later active submit is admitted. */
private class StartupSubmissionAdmission {
    private val gate = ReentrantLock(false)
    private var open = true

    fun runIfOpen(action: () -> Unit): Boolean {
        val admittedBeforeCutoff = gate.withLock { open }
        if (!admittedBeforeCutoff) return false
        action()
        return true
    }

    fun close() = gate.withLock {
        open = false
    }
}
