package io.screenstream.engine.internal.controller

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.view.Display
import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.ScreenCaptureConfig
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureStopReason
import io.screenstream.engine.internal.CleanupMutation
import io.screenstream.engine.internal.CleanupOwner
import io.screenstream.engine.internal.ControlShutdownReadiness
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.StorageRetirementAction
import io.screenstream.engine.internal.ReturnedNativeFatalCleanupChild
import io.screenstream.engine.internal.TargetQuarantineChild
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.android.AndroidCaptureFact
import io.screenstream.engine.internal.android.AndroidCaptureFactSink
import io.screenstream.engine.internal.android.AndroidCaptureOwner
import io.screenstream.engine.internal.android.AndroidFiniteOperationIdentity
import io.screenstream.engine.internal.android.AndroidInitialResizeDeadlineIdentity
import io.screenstream.engine.internal.android.AndroidLaneStartupResult
import io.screenstream.engine.internal.android.AndroidProjectionCallbackRegistrationEvidence
import io.screenstream.engine.internal.android.AndroidProjectionCallbackUnregistrationEvidence
import io.screenstream.engine.internal.android.AndroidProjectionStopEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationOwnerBag
import io.screenstream.engine.internal.android.AndroidTargetListenerRemovalEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerRemovalOwnerBag
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationOwnerBag
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseMode
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseOwnerBag
import io.screenstream.engine.internal.android.CaptureMetricsClaimedValue
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.android.CaptureMetricsReadinessArbitration
import io.screenstream.engine.internal.android.CaptureMetricsTerminalArbitration
import io.screenstream.engine.internal.delivery.ObservationDiagnosticRequest
import io.screenstream.engine.internal.delivery.ObservationOwner
import io.screenstream.engine.internal.delivery.ObservationRunningStateSnapshot
import io.screenstream.engine.internal.delivery.ObservationStateSnapshot
import io.screenstream.engine.internal.delivery.ObservationStatsSnapshot
import io.screenstream.engine.internal.gl.ContextIntegrity
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.gl.GlClaimedOperationFacts
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlDestructionHandle
import io.screenstream.engine.internal.gl.GlOperationKind
import io.screenstream.engine.internal.gl.GlOperationResult
import io.screenstream.engine.internal.gl.GlPipelineOwner
import io.screenstream.engine.internal.jpeg.FrameworkJpegOwner
import io.screenstream.engine.internal.jpeg.FrameworkBitmapRecycleOccurrence
import io.screenstream.engine.internal.jpeg.FrameworkBitmapRecycleSettlement
import io.screenstream.engine.internal.jpeg.FrameworkResourceCreationOccurrence
import io.screenstream.engine.internal.jpeg.FrameworkResourceCreationResult
import io.screenstream.engine.internal.jpeg.JpegRuntimeProduct
import io.screenstream.engine.internal.jpeg.NativeEncodeFatalCleanupSettlement
import io.screenstream.engine.internal.jpeg.NativeEncodeOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeSettlement
import io.screenstream.engine.internal.jpeg.JpegFiniteOperationIdentity
import io.screenstream.engine.internal.jpeg.JpegPreparationOccurrence
import io.screenstream.engine.internal.settlement.ControlWakeCancellationAction
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeScheduleAction
import io.screenstream.engine.internal.settlement.ControlScheduledTaskRecord
import io.screenstream.engine.internal.settlement.ControlWakeSuppressionDisposition
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.TargetConstructionAdmissionDisposition
import io.screenstream.engine.internal.target.TargetConstructionFoldDisposition
import io.screenstream.engine.internal.target.TargetConstructionFoldToken
import io.screenstream.engine.internal.target.TargetOwner
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetProducerState
import io.screenstream.engine.internal.target.TargetSourceSignal
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SessionController internal constructor(
    context: Context,
    config: ScreenCaptureConfig,
    private val clock: EngineClock = EngineClock { SystemClock.elapsedRealtimeNanos() },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : AndroidCaptureFactSink, SettlementSignal, SessionControlSchedulerPort {

    internal enum class Lifecycle {
        NotStarted,
        Starting,
        Running,
        Terminal,
    }

    internal enum class RunningPhase {
        Active,
        Suspended,
    }

    internal enum class TerminalKind {
        CaptureEnded,
        OwnerStop,
        Failed,
    }

    internal class StartAdmission internal constructor(
        internal val accepted: Boolean,
        internal val completion: StartCompletion,
    )

    internal class StartCompletion internal constructor() {
        private val value = AtomicReference<StartOutcome?>(null)

        internal fun complete(outcome: StartOutcome): Boolean = value.compareAndSet(null, outcome)

        internal val outcome: StartOutcome?
            get() = value.get()
    }

    internal sealed interface StartOutcome {
        object RunningAssigned : StartOutcome

        class StoppedBeforeActive(
            internal val reason: ScreenCaptureStopReason,
        ) : StartOutcome

        class FailedBeforeActive(
            internal val problem: ScreenCaptureProblem,
            internal val cause: Throwable?,
        ) : StartOutcome
    }

    internal class FullGeometryAuthority(
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

    private class MetricsJointReadinessFacts(
        val owner: CaptureMetricsOwner,
        val source: CaptureMetricsSource,
        val observationIdentity: Long,
        val sequence: Long,
    )

    private class TerminalContender {
        var present: Boolean = false
        var kind: TerminalKind = TerminalKind.Failed
        var stopReason: ScreenCaptureStopReason? = null
        var problem: ScreenCaptureProblem? = null
        var cause: Throwable? = null

        fun clear() {
            present = false
            stopReason = null
            problem = null
            cause = null
        }
    }

    private class TerminalWinner {
        var fixed: Boolean = false
        var kind: TerminalKind = TerminalKind.Failed
        var stopReason: ScreenCaptureStopReason? = null
        var problem: ScreenCaptureProblem? = null
        var cause: Throwable? = null
    }

    private class StartupOwnerBag {
        var projection: MediaProjection? = null
        var projectionOwnerEpoch: Long = 0L
        var callbackIdentity: Long = 0L
        var metricsAttachmentIdentity: Long = 0L
        var metricsDeadlineIdentity: Long = 0L
        var metricsWakeIdentity: Long = 0L
        var metricsCloseIdentity: Long = 0L
        var constructionClaimed: Boolean = false
        var constructionComplete: Boolean = false
        var prestartClaimed: Boolean = false
        var metrics: CaptureMetricsOwner? = null
        var android: AndroidCaptureOwner? = null
        var gl: GlPipelineOwner? = null
        var jpeg: JpegRuntimeOwner? = null
        var storage: EncodedStorageOwner? = null

        fun bindLocked(
            projection: MediaProjection,
            projectionOwnerEpoch: Long,
            callbackIdentity: Long,
            metricsAttachmentIdentity: Long,
            metricsDeadlineIdentity: Long,
            metricsWakeIdentity: Long,
            metricsCloseIdentity: Long,
        ) {
            check(this.projection == null)
            this.projection = projection
            this.projectionOwnerEpoch = projectionOwnerEpoch
            this.callbackIdentity = callbackIdentity
            this.metricsAttachmentIdentity = metricsAttachmentIdentity
            this.metricsDeadlineIdentity = metricsDeadlineIdentity
            this.metricsWakeIdentity = metricsWakeIdentity
            this.metricsCloseIdentity = metricsCloseIdentity
        }

        val isComplete: Boolean
            get() = metrics != null && android != null && gl != null && jpeg != null && storage != null
    }

    private class StatsAuthority {
        var framesEncoded: Long = 0L
        var framesProduced: Long = 0L
        var byPipelineBusy: Long = 0L
        var byStaleWork: Long = 0L
        var byFailure: Long = 0L
        var byConsumerBusy: Long = 0L
        var byCallbackFailure: Long = 0L
        var encodeSamples: Long = 0L
        var encodeMeanNanos: Double = 0.0
        var readbackSamples: Long = 0L
        var readbackMeanNanos: Double = 0.0
        var encodedByteSamples: Long = 0L
        var encodedByteMean: Double = 0.0
        var lastEncodedByteCount: Int = 0
        var firstProducedNanos: Long = Long.MIN_VALUE
        var lastProducedNanos: Long = Long.MIN_VALUE
        var cutoff: Boolean = false

        fun snapshot(): ObservationStatsSnapshot {
            val fps = if (framesProduced < 2L || firstProducedNanos == Long.MIN_VALUE ||
                lastProducedNanos <= firstProducedNanos
            ) {
                0.0
            } else {
                val value = (framesProduced - 1L).toDouble() * NANOS_PER_SECOND.toDouble() /
                        (lastProducedNanos - firstProducedNanos).toDouble()
                if (value.isFinite()) value else Double.MAX_VALUE
            }
            val encodedAverage = if (encodedByteSamples == 0L) {
                0
            } else {
                minOf(Int.MAX_VALUE.toDouble(), kotlin.math.floor(encodedByteMean + 0.5)).toInt()
            }
            return ObservationStatsSnapshot(
                framesEncoded = framesEncoded,
                framesProduced = framesProduced,
                frameDropsByRateLimit = 0L,
                frameDropsByPipelineBusy = byPipelineBusy,
                frameDropsByStaleWork = byStaleWork,
                frameDropsByFailure = byFailure,
                deliveryDropsByConsumerBusy = byConsumerBusy,
                deliveryDropsByCallbackFailure = byCallbackFailure,
                averageProducedFps = fps,
                averageEncodeMs = finiteOrZero(encodeMeanNanos / NANOS_PER_MILLISECOND),
                averageReadbackMs = finiteOrZero(readbackMeanNanos / NANOS_PER_MILLISECOND),
                lastEncodedByteCount = lastEncodedByteCount,
                averageEncodedByteCount = encodedAverage,
            )
        }

        private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0
    }

    private class TurnSlots {
        var publishStarting = false
        var publishRunning: ObservationStateSnapshot.Running? = null
        var buildRunningOutput = false
        var runningParameters: ScreenCaptureParameters? = null
        var runningEffective: ScreenCaptureEffectiveParameters? = null
        var runningProblem: ScreenCaptureProblem? = null
        var runningGeometry: CaptureGeometry? = null
        var runningVisible: Boolean? = null
        var runningIsActive = false
        var publishStats: ObservationStatsSnapshot? = null
        var terminalStats: ObservationStatsSnapshot? = null
        var terminalDiagnosticSequence: Long = 0L
        var terminalDiagnosticKind: TerminalKind? = null
        var terminalDiagnosticProblem: ScreenCaptureProblem? = null
        var terminalDiagnosticCause: Throwable? = null
        var terminalState: ObservationStateSnapshot? = null
        var completeStart: StartOutcome? = null
        var buildTerminalOutputs = false
        var closeTargetAdmission = false

        fun clear() {
            publishStarting = false
            publishRunning = null
            buildRunningOutput = false
            runningParameters = null
            runningEffective = null
            runningProblem = null
            runningGeometry = null
            runningVisible = null
            runningIsActive = false
            publishStats = null
            terminalStats = null
            terminalDiagnosticSequence = 0L
            terminalDiagnosticKind = null
            terminalDiagnosticProblem = null
            terminalDiagnosticCause = null
            terminalState = null
            completeStart = null
            buildTerminalOutputs = false
            closeTargetAdmission = false
        }
    }

    private val applicationContext: Context =
        requireNotNull(context.applicationContext) { "context.applicationContext must be available" }
    private val metricsSource: CaptureMetricsSource? = config.captureMetricsSource
    private val jpegPolicy: JpegBackendPolicy = config.jpegBackendPolicy

    internal val sessionGate: ReentrantLock = ReentrantLock(false)
    internal val observationOwner: ObservationOwner = ObservationOwner()
    internal val cleanupOwner: CleanupOwner = CleanupOwner(sessionGate)
    internal val targetOwner: TargetOwner = TargetOwner(sessionGate)

    private val startCompletion = StartCompletion()
    private val acceptedAdmission = StartAdmission(true, startCompletion)
    private val rejectedAdmission = StartAdmission(false, startCompletion)
    private val terminalContenders: Array<TerminalContender> = Array(3) { TerminalContender() }
    private val terminalWinner = TerminalWinner()
    private val statsAuthority = StatsAuthority()
    private val turnSlots = TurnSlots()
    private val emergencyTurnSlots = TurnSlots()
    private val startupOwnerBag = StartupOwnerBag()

    private val controlPoison = AtomicReference<Throwable?>(null)
    private val unpublishedControlScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val controlScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val controlTermination = AtomicReference<SessionControlTerminationReceipt?>(null)
    private val preBarrierDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val drainerState = AtomicInteger(DRAIN_IDLE)
    private val drainerGeneration = AtomicLong(0L)
    private val drainerRecords: Array<SessionControlDrainerTaskRecord> = arrayOf(
        SessionControlDrainerTaskRecord(this, Runnable(::runDrainer)),
        SessionControlDrainerTaskRecord(this, Runnable(::runDrainer)),
    )

    private val latestResize = AtomicReference<AndroidCaptureFact.CapturedContentResized?>(null)
    private val latestVisibility = AtomicReference<AndroidCaptureFact.CapturedContentVisibilityChanged?>(null)
    private val captureEnded = AtomicReference<AndroidCaptureFact.CaptureEnded?>(null)

    private var lifecycle: Lifecycle = Lifecycle.NotStarted
    private var pendingStartupBag: StartupOwnerBag? = null
    private var runningPhase: RunningPhase? = null
    private var admissionsClosed: Boolean = false
    private var terminalCutoffApplied: Boolean = false
    private var targetAdmissionClosePending: Boolean = false
    private var ownersLaunched: Boolean = false
    private var androidLaneReadyOwner: AndroidCaptureOwner? = null
    private var metricsJointReadiness: MetricsJointReadinessFacts? = null
    private var startingAssigned: Boolean = false
    private var firstActiveAssigned: Boolean = false
    private var runningPublicationPending: Boolean = false
    private var runningPublicationProblem: ScreenCaptureProblem? = null
    private var requestedParameters: ScreenCaptureParameters? = null
    private var desiredRevision: Long = 0L
    private var geometryGeneration: Long = 0L
    private var lifecycleEpoch: Long = 0L
    private var reconciliationIdentity: Long = 0L
    private var nextIdentity: Long = 1L
    private var nextDiagnosticSequence: Long = 1L
    private var capturedContentVisible: Boolean? = null
    private var projectionEpoch: Long = 0L
    private var projectionCallbackIdentity: Long = 0L
    private var acceptedProjectionCallbackRegistrationIdentity: Long = 0L
    private var lastAndroidCallbackSequence: Long = 0L
    private var projectionGeometryAvailable: Boolean = false
    private var geometryBuildPending: Boolean = false
    private var projectionWidthPx: Int = 0
    private var projectionHeightPx: Int = 0
    private var latestMetricsFact: CaptureMetricsClaimedValue? = null
    private var combinedGeometryAuthority: FullGeometryAuthority? = null
    private var captureGeometry: CaptureGeometry? = null
    private var lastEffectiveParameters: ScreenCaptureEffectiveParameters? = null
    private var currentCalculation: Resolved? = null
    private var currentProvisional: ProvisionalFull? = null
    private var currentPlan: TargetPlan? = null

    private var projection: MediaProjection? = null
    private var metricsOwner: CaptureMetricsOwner? = null
    private var androidOwner: AndroidCaptureOwner? = null
    private var glOwner: GlPipelineOwner? = null
    private var jpegOwner: JpegRuntimeOwner? = null
    private var storageOwner: EncodedStorageOwner? = null
    private var preparedTarget: PreparedTarget? = null
    private var currentTarget: CurrentTarget? = null
    private var installedRenderTarget: GlPipelineOwner.GlRenderTargetOwner? = null
    private var installedFrameworkOwner: FrameworkJpegOwner? = null
    private var pendingFrameworkCreation: FrameworkResourceCreationOccurrence? = null
    private var pendingNativeEncode: NativeEncodeOccurrence? = null
    private var acceptedTopologySnapshot: AcceptedTopologySnapshot? = null
    private var pendingProjectionRegistration: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? = null
    private var pendingListenerInstallation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>? = null
    private var pendingVirtualDisplayCreation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? = null
    private var virtualDisplayReturnAccepted: Boolean = false
    private var preparedTargetCommand: GlPipelineOwner.TargetConstructionCommand? = null
    private var preparedTargetListenerIdentity: AndroidFiniteOperationIdentity? = null
    private var pendingRenderConstruction: GlPipelineOwner.RenderTargetConstructionCommand? = null
    private var pendingJpegPreparation: JpegPreparationOccurrence? = null
    private var nextRenderGeneration: Long = 1L
    private var cleanupProjectionUnregistration: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? = null
    private var cleanupVirtualDisplayRelease: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? = null
    private var cleanupProjectionStop: OperationOccurrence<AndroidProjectionStopEvidence>? = null
    private var cleanupListenerRemoval: OperationOccurrence<AndroidTargetListenerRemovalEvidence>? = null
    private var cleanupRenderOwner: GlPipelineOwner.GlRenderTargetOwner? = null
    private var cleanupRenderDestruction: GlPipelineOwner.DestructionCommand? = null
    private var cleanupSurfaceRelease: GlPipelineOwner.SurfaceReleaseCommand? = null
    private var cleanupTargetScope: GlPipelineOwner.TargetScopeDestructionCommand? = null
    private var cleanupTargetNamespaceSubmitted: Boolean = false
    private var cleanupFrameworkRecycle: FrameworkBitmapRecycleOccurrence? = null
    private var cleanupProgramDestruction: GlPipelineOwner.DestructionCommand? = null
    private var cleanupSessionDestruction: GlPipelineOwner.DestructionCommand? = null
    private var cleanupNativeCarrierFree: NativeCarrierFreeOccurrence? = null
    private var preparedTargetDestructionIdentity: GlFiniteOperationIdentity? = null
    private var preparedNamespaceDestructionIdentity: GlFiniteOperationIdentity? = null

    private var glCapabilities: GlCapabilityFacts? = null
    private var glSessionFacts: GlClaimedOperationFacts? = null
    private var glSessionCommand: GlPipelineOwner.SessionConstructionCommand? = null
    private var topologyPublicationIdentity: Long = 0L
    private var publicStatePublicationIdentity: Long = 0L
    private var publicStatsPublicationIdentity: Long = 0L
    private var cleanupWorkPending: Boolean = false
    private var pendingQuarantineDiagnostics: Long = 0L
    private var replacementJpegConstructionClaimed: Boolean = false

    internal val topologySnapshot: AcceptedTopologySnapshot?
        get() = sessionGate.withLock { acceptedTopologySnapshot }

    internal fun acceptStart(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters,
    ): StartAdmission {
        val accepted = sessionGate.withLock {
            if (lifecycle != Lifecycle.NotStarted) return@withLock false
            lifecycle = Lifecycle.Starting
            lifecycleEpoch = reserveIdentityLocked()
            desiredRevision = reserveIdentityLocked()
            projectionEpoch = reserveIdentityLocked()
            requestedParameters = initialParameters
            projection = mediaProjection
            startupOwnerBag.bindLocked(
                projection = mediaProjection,
                projectionOwnerEpoch = projectionEpoch,
                callbackIdentity = reserveIdentityLocked(),
                metricsAttachmentIdentity = reserveIdentityLocked(),
                metricsDeadlineIdentity = reserveIdentityLocked(),
                metricsWakeIdentity = reserveIdentityLocked(),
                metricsCloseIdentity = reserveIdentityLocked(),
            )
            projectionCallbackIdentity = startupOwnerBag.callbackIdentity
            pendingStartupBag = startupOwnerBag
            true
        }
        if (!accepted) return rejectedAdmission

        val scheduler = try {
            SessionControlScheduler(this)
        } catch (raw: Throwable) {
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            emergencyFailClosed(raw)
            return acceptedAdmission
        }
        if (!unpublishedControlScheduler.compareAndSet(null, scheduler)) {
            scheduler.shutdown()
            emergencyFailClosed(CONTROL_SCHEDULER_COLLISION)
            return acceptedAdmission
        }
        val startup = try {
            scheduler.prestart()
        } catch (raw: Throwable) {
            unpublishedControlScheduler.compareAndSet(scheduler, null)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            emergencyFailClosed(raw)
            return acceptedAdmission
        }
        if (startup != SessionControlStartupDisposition.Ready) {
            unpublishedControlScheduler.compareAndSet(scheduler, null)
            emergencyFailClosed(scheduler.startupFailure ?: CONTROL_SCHEDULER_UNAVAILABLE)
            return acceptedAdmission
        }
        if (!controlScheduler.compareAndSet(null, scheduler)) {
            unpublishedControlScheduler.compareAndSet(scheduler, null)
            scheduler.shutdown()
            if (controlScheduler.get() == null) emergencyFailClosed(CONTROL_SCHEDULER_COLLISION)
            else offerFailure(ScreenCaptureProblem.InternalFailure, CONTROL_SCHEDULER_COLLISION)
            return acceptedAdmission
        }
        unpublishedControlScheduler.compareAndSet(scheduler, null)
        preBarrierDirty.set(false)
        signal()
        return acceptedAdmission
    }

    internal fun updateDesired(parameters: ScreenCaptureParameters): Boolean {
        val changed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || admissionsClosed) return@withLock false
            if (requestedParameters == parameters) return@withLock true
            if (nextIdentity == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock false
            }
            requestedParameters = parameters
            desiredRevision = reserveIdentityLocked()
            reconciliationIdentity = 0L
            currentCalculation = null
            currentProvisional = null
            currentPlan = null
            reserveRunningPublicationLocked()
            true
        }
        if (changed) signal()
        return changed
    }

    private fun advanceReconfigurationBoundary() {
        sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed ||
                runningPhase != RunningPhase.Active || cleanupOwner.currentTargetRoot.target != null ||
                cleanupOwner.jpegRoot.owner != null || cleanupOwner.jpegRoot.frameworkOwner != null ||
                cleanupRenderOwner != null || pendingProjectionRegistration != null ||
                pendingListenerInstallation != null || pendingVirtualDisplayCreation != null ||
                pendingRenderConstruction != null || pendingJpegPreparation != null ||
                pendingFrameworkCreation != null
            ) {
                return
            }
            val calculation = currentCalculation ?: return
            val topology = acceptedTopologySnapshot ?: return
            val replaceTarget = calculation.targetAction == ReconciliationResourceAction.Replace
            val replaceRender = calculation.renderAction == ReconciliationResourceAction.Replace
            val replaceJpeg = calculation.jpegAction == ReconciliationResourceAction.Replace
            val replaceFramework = calculation.frameworkAction == ReconciliationResourceAction.Replace
            if (!replaceTarget && !replaceRender && !replaceJpeg && !replaceFramework) return
            if (!SessionReconfiguration.revalidate(topology) || topology.target.activeLeaseCount != 0) return

            val exactTarget = currentTarget ?: return
            val exactRender = installedRenderTarget ?: return
            if (!recordCleanupMutationLocked(cleanupOwner.attachCurrentTarget(exactTarget))) return
            cleanupRenderOwner = exactRender
            if (replaceFramework) {
                installedFrameworkOwner?.let { framework ->
                    if (!recordCleanupMutationLocked(cleanupOwner.attachFramework(framework))) return
                    installedFrameworkOwner = null
                }
            }
            if (replaceJpeg) {
                installedFrameworkOwner?.let { framework ->
                    if (!recordCleanupMutationLocked(cleanupOwner.attachFramework(framework))) return
                    installedFrameworkOwner = null
                }
                val exactJpeg = jpegOwner ?: return
                if (!recordCleanupMutationLocked(cleanupOwner.attachJpeg(exactJpeg))) return
                jpegOwner = null
                replacementJpegConstructionClaimed = false
            }
            currentTarget = null
            installedRenderTarget = null
            runningPhase = RunningPhase.Suspended
            advanceLifecycleEpochLocked()
            invalidateOutputLocked()
            invalidateReconciliationForTopologyMutationLocked()
            cleanupWorkPending = true
            reserveRunningPublicationLocked(ScreenCaptureProblem.Reconfiguring)
        }
    }

    private fun constructReplacementJpegOwner() {
        val claimed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed || jpegOwner != null ||
                cleanupOwner.jpegRoot.owner != null || replacementJpegConstructionClaimed
            ) {
                return@withLock false
            }
            replacementJpegConstructionClaimed = true
            true
        }
        if (!claimed) return
        val candidate = try {
            SessionStartupTopology.constructJpeg(clock, this)
        } catch (raw: Throwable) {
            sessionGate.withLock { replacementJpegConstructionClaimed = false }
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return
        }
        val startup = try {
            candidate.prestartJpegEndpoint()
        } catch (raw: Throwable) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupOwner.attachJpeg(candidate))
                cleanupWorkPending = true
                replacementJpegConstructionClaimed = false
            }
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return
        }
        if (startup != PrivateExecutorStartupDisposition.Ready) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupOwner.attachJpeg(candidate))
                cleanupWorkPending = true
                replacementJpegConstructionClaimed = false
            }
            offerFailure(ScreenCaptureProblem.InternalFailure, candidate.jpegEndpointStartupFailure)
            return
        }
        val installed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed || jpegOwner != null ||
                cleanupOwner.jpegRoot.owner != null
            ) {
                return@withLock false
            }
            jpegOwner = candidate
            replacementJpegConstructionClaimed = false
            topologyPublicationIdentity = reserveIdentityLocked()
            invalidateReconciliationForTopologyMutationLocked()
            true
        }
        if (!installed) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupOwner.attachJpeg(candidate))
                cleanupWorkPending = true
                replacementJpegConstructionClaimed = false
            }
        }
        signal()
    }

    internal fun requestOwnerStop() {
        val changed = sessionGate.withLock {
            if (terminalWinner.fixed || contenderLocked(TerminalKind.OwnerStop) != null) return@withLock false
            closeAdmissionLocked()
            val contender = terminalContenders[OWNER_STOP_CONTENDER]
            contender.present = true
            contender.kind = TerminalKind.OwnerStop
            contender.stopReason = ScreenCaptureStopReason.OwnerStop
            true
        }
        if (changed) signal()
    }

    internal fun cancelAcceptedStartWaiter(): Boolean {
        val accepted = sessionGate.withLock {
            lifecycle == Lifecycle.Starting && projection != null && !terminalWinner.fixed
        }
        if (accepted) requestOwnerStop()
        return accepted
    }

    internal fun offerFailure(problem: ScreenCaptureProblem, cause: Throwable?) {
        val changed = sessionGate.withLock { offerFailureLocked(problem, cause) }
        if (changed) signal()
    }

    private fun offerFailureLocked(problem: ScreenCaptureProblem, cause: Throwable?): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (terminalWinner.fixed) return false
        closeAdmissionLocked()
        val contender = terminalContenders[FAILURE_CONTENDER]
        if (contender.present) return false
        contender.present = true
        contender.kind = TerminalKind.Failed
        contender.problem = problem
        contender.cause = cause
        return true
    }

    override fun publish(fact: AndroidCaptureFact) {
        when (fact) {
            is AndroidCaptureFact.CapturedContentResized -> publishLatestAndroidFact(latestResize, fact)
            is AndroidCaptureFact.CapturedContentVisibilityChanged -> publishLatestAndroidFact(latestVisibility, fact)
            is AndroidCaptureFact.CaptureEnded -> captureEnded.compareAndSet(null, fact)
        }
        signal()
    }

    private fun <T : AndroidCaptureFact> publishLatestAndroidFact(cell: AtomicReference<T?>, fact: T) {
        while (true) {
            val prior = cell.get()
            if (prior != null && prior.callbackSequence >= fact.callbackSequence) return
            if (cell.compareAndSet(prior, fact)) return
        }
    }

    override fun signal() {
        if (controlScheduler.get() == null) {
            preBarrierDirty.set(true)
            return
        }
        while (true) {
            when (drainerState.get()) {
                DRAIN_IDLE -> if (drainerState.compareAndSet(DRAIN_IDLE, DRAIN_RUNNING)) {
                    submitDrainer()
                    return
                }

                DRAIN_RUNNING -> {
                    drainerState.compareAndSet(DRAIN_RUNNING, DRAIN_RUNNING_DIRTY)
                    return
                }

                DRAIN_RUNNING_DIRTY -> return
            }
        }
    }

    internal fun consumeMetricsReadiness() {
        val owner = metricsOwner ?: return
        when (owner.arbitrateReadiness()) {
            CaptureMetricsReadinessArbitration.None -> Unit
            CaptureMetricsReadinessArbitration.Timely -> {
                val fact = owner.claimLatest()
                if (fact == null || !fact.isAvailable || fact.metrics == null || fact.sequence <= 0L) {
                    offerFailure(ScreenCaptureProblem.InternalFailure, METRICS_JOINT_READINESS_EVIDENCE_INVALID)
                    return
                }
                val committed = sessionGate.withLock {
                    if (terminalWinner.fixed || admissionsClosed || metricsOwner !== owner ||
                        metricsJointReadiness != null
                    ) {
                        return@withLock false
                    }
                    metricsJointReadiness = MetricsJointReadinessFacts(
                        owner = owner,
                        source = fact.source,
                        observationIdentity = fact.observationIdentity,
                        sequence = fact.sequence,
                    )
                    latestMetricsFact = fact
                    geometryBuildPending = true
                    true
                }
                if (committed) signal()
            }

            CaptureMetricsReadinessArbitration.Expired,
            CaptureMetricsReadinessArbitration.CompletedBeforeReadiness,
            CaptureMetricsReadinessArbitration.AvailabilityLostBeforeActive,
                -> offerFailure(ScreenCaptureProblem.CaptureUnavailable, owner.providerCause)

            CaptureMetricsReadinessArbitration.FailedBeforeReadiness,
            CaptureMetricsReadinessArbitration.AttachmentFailed,
            CaptureMetricsReadinessArbitration.DeadlineGuardFailed,
                -> offerFailure(ScreenCaptureProblem.InternalFailure, owner.providerCause)
        }
    }

    internal fun consumeMetricsLatest() {
        val owner = sessionGate.withLock {
            val readiness = metricsJointReadiness ?: return@withLock null
            if (metricsOwner !== readiness.owner) return@withLock null
            readiness.owner
        } ?: return
        owner.claimLatest()?.let(::consumeMetricsValue)
    }

    private fun consumeAndroidLaneStartup() {
        val owner = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || androidLaneReadyOwner != null) return@withLock null
            androidOwner
        } ?: return
        when (val startup = owner.laneStartupResult) {
            AndroidLaneStartupResult.Pending -> Unit
            is AndroidLaneStartupResult.Ready -> {
                val committed = sessionGate.withLock {
                    if (terminalWinner.fixed || admissionsClosed || androidOwner !== owner || androidLaneReadyOwner != null) {
                        return@withLock false
                    }
                    androidLaneReadyOwner = owner
                    true
                }
                if (committed) signal()
            }

            is AndroidLaneStartupResult.Failed -> offerFailure(ScreenCaptureProblem.InternalFailure, startup.cause)
        }
    }

    internal fun consumeMetricsTerminal() {
        val owner = metricsOwner ?: return
        when (owner.claimTerminal()) {
            CaptureMetricsTerminalArbitration.None -> Unit
            CaptureMetricsTerminalArbitration.CompletedBeforeReadiness ->
                offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)

            CaptureMetricsTerminalArbitration.CompletedAfterReadiness -> emitDiagnostic(
                source = "MetricsProvider",
                label = "CapabilityCheck",
                message = "metrics observation completed; retained last valid value",
                cause = null,
            )

            CaptureMetricsTerminalArbitration.FailedBeforeReadiness,
            CaptureMetricsTerminalArbitration.FailedAfterReadiness,
                -> offerFailure(ScreenCaptureProblem.InternalFailure, owner.providerCause)
        }
    }

    private fun consumeMetricsValue(fact: CaptureMetricsClaimedValue) {
        val changed = sessionGate.withLock {
            if (terminalWinner.fixed || fact.sequence <= 0L) return@withLock false
            val old = latestMetricsFact
            if (old != null && old.source === fact.source && old.observationIdentity == fact.observationIdentity &&
                old.sequence == fact.sequence
            ) {
                return@withLock false
            }
            latestMetricsFact = fact
            geometryBuildPending = true
            true
        }
        if (changed) signal()
    }

    private fun consumeAndroidFacts() {
        var ended: AndroidCaptureFact.CaptureEnded? = captureEnded.getAndSet(null)
        var resized: AndroidCaptureFact.CapturedContentResized? = latestResize.getAndSet(null)
        var visibility: AndroidCaptureFact.CapturedContentVisibilityChanged? = latestVisibility.getAndSet(null)
        repeat(3) {
            val next = when {
                ended != null && (resized == null || ended!!.callbackSequence <= resized!!.callbackSequence) &&
                        (visibility == null || ended!!.callbackSequence <= visibility!!.callbackSequence) -> ended

                resized != null && (visibility == null || resized!!.callbackSequence <= visibility!!.callbackSequence) -> resized
                else -> visibility
            } ?: return@repeat
            when (next) {
                is AndroidCaptureFact.CaptureEnded -> ended = null
                is AndroidCaptureFact.CapturedContentResized -> resized = null
                is AndroidCaptureFact.CapturedContentVisibilityChanged -> visibility = null
            }
            sessionGate.withLock { consumeAndroidFactLocked(next) }
        }
    }

    private fun consumeAndroidFactLocked(fact: AndroidCaptureFact) {
        check(sessionGate.isHeldByCurrentThread)
        val owner = androidOwner ?: return
        val provenance = fact.provenance
        if (terminalWinner.fixed || owner.apiBand == AndroidCaptureApiBand.Unsupported || provenance.owner !== owner ||
            provenance.projectionOwnerEpoch != projectionEpoch ||
            provenance.callbackRegistrationIdentity != acceptedProjectionCallbackRegistrationIdentity ||
            provenance.callbackIdentity != projectionCallbackIdentity || fact.callbackSequence <= lastAndroidCallbackSequence
        ) {
            return
        }
        lastAndroidCallbackSequence = fact.callbackSequence
        when (fact) {
            is AndroidCaptureFact.CaptureEnded -> {
                closeAdmissionLocked()
                val contender = terminalContenders[CAPTURE_ENDED_CONTENDER]
                contender.present = true
                contender.kind = TerminalKind.CaptureEnded
                contender.stopReason = ScreenCaptureStopReason.CaptureEnded
            }

            is AndroidCaptureFact.CapturedContentResized -> {
                if (fact.widthPx <= 0 || fact.heightPx <= 0 ||
                    projectionWidthPx == fact.widthPx && projectionHeightPx == fact.heightPx
                ) {
                    return
                }
                projectionWidthPx = fact.widthPx
                projectionHeightPx = fact.heightPx
                if (!projectionGeometryAvailable) {
                    projectionGeometryAvailable = true
                    advanceLifecycleEpochLocked()
                }
                geometryBuildPending = true
            }

            is AndroidCaptureFact.CapturedContentVisibilityChanged -> {
                if (capturedContentVisible == fact.isVisible) return
                capturedContentVisible = fact.isVisible
                if (lifecycle == Lifecycle.Running) reserveRunningPublicationLocked()
            }
        }
    }

    private fun rebuildCombinedGeometry() {
        var owner: AndroidCaptureOwner? = null
        var metricsFact: CaptureMetricsClaimedValue? = null
        var projectionWidth = 0
        var projectionHeight = 0
        var projectionOwnerEpoch = 0L
        val claimed = sessionGate.withLock {
            if (!geometryBuildPending || terminalWinner.fixed) return@withLock false
            owner = androidOwner
            metricsFact = latestMetricsFact
            projectionWidth = projectionWidthPx
            projectionHeight = projectionHeightPx
            projectionOwnerEpoch = projectionEpoch
            geometryBuildPending = false
            owner != null && metricsFact != null
        }
        if (!claimed) return
        val exactOwner = checkNotNull(owner)
        val exactMetricsFact = checkNotNull(metricsFact)
        val metrics = exactMetricsFact.metrics
        val available = exactMetricsFact.isAvailable && metrics != null
        val width = when (exactOwner.apiBand) {
            AndroidCaptureApiBand.Api24To31,
            AndroidCaptureApiBand.Api32To33,
                -> metrics?.widthPx ?: 0

            AndroidCaptureApiBand.Api34To37 -> projectionWidth
            AndroidCaptureApiBand.Unsupported -> 0
        }
        val height = when (exactOwner.apiBand) {
            AndroidCaptureApiBand.Api24To31,
            AndroidCaptureApiBand.Api32To33,
                -> metrics?.heightPx ?: 0

            AndroidCaptureApiBand.Api34To37 -> projectionHeight
            AndroidCaptureApiBand.Unsupported -> 0
        }
        val density = metrics?.densityDpi ?: 0
        val effectiveAvailable = available && width > 0 && height > 0 && density > 0
        val candidate = FullGeometryAuthority(
            source = exactMetricsFact.source,
            observationIdentity = exactMetricsFact.observationIdentity,
            displayIdentity = exactMetricsFact.display,
            displayEpoch = exactMetricsFact.displayEpoch,
            projectionEpoch = projectionOwnerEpoch,
            available = effectiveAvailable,
            sourceWidthPx = metrics?.widthPx ?: 0,
            sourceHeightPx = metrics?.heightPx ?: 0,
            projectionWidthPx = projectionWidth,
            projectionHeightPx = projectionHeight,
            densityDpi = density,
        )
        val builtCaptureGeometry = if (effectiveAvailable) CaptureGeometry(width, height, density) else null

        val changed = sessionGate.withLock {
            if (terminalWinner.fixed || androidOwner !== exactOwner || latestMetricsFact !== exactMetricsFact ||
                projectionWidthPx != projectionWidth || projectionHeightPx != projectionHeight ||
                projectionEpoch != projectionOwnerEpoch
            ) {
                geometryBuildPending = true
                return@withLock false
            }
            if (sameGeometryAuthority(combinedGeometryAuthority, candidate)) return@withLock false
            if (geometryGeneration == Long.MAX_VALUE || nextIdentity == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock true
            }
            val priorAvailable = combinedGeometryAuthority?.available == true
            if (priorAvailable != candidate.available && combinedGeometryAuthority != null) advanceLifecycleEpochLocked()
            geometryGeneration = reserveIdentityLocked()
            combinedGeometryAuthority = candidate
            captureGeometry = builtCaptureGeometry
            reconciliationIdentity = 0L
            currentCalculation = null
            currentProvisional = null
            currentPlan = null
            if (!effectiveAvailable && firstActiveAssigned) {
                runningPhase = RunningPhase.Suspended
                invalidateOutputLocked()
                reserveRunningPublicationLocked(ScreenCaptureProblem.CaptureUnavailable)
            }
            true
        }
        if (changed) signal()
    }

    private fun sameGeometryAuthority(left: FullGeometryAuthority?, right: FullGeometryAuthority): Boolean =
        left != null && left.source === right.source && left.observationIdentity == right.observationIdentity &&
                left.displayIdentity === right.displayIdentity && left.displayEpoch == right.displayEpoch &&
                left.projectionEpoch == right.projectionEpoch && left.available == right.available &&
                left.sourceWidthPx == right.sourceWidthPx && left.sourceHeightPx == right.sourceHeightPx &&
                left.projectionWidthPx == right.projectionWidthPx && left.projectionHeightPx == right.projectionHeightPx &&
                left.densityDpi == right.densityDpi

    internal fun calculateLatestReconciliation(): ReconciliationCalculation? {
        var geometry: CaptureGeometry? = null
        var geometryAuthority: FullGeometryAuthority? = null
        var parameters: ScreenCaptureParameters? = null
        var capabilities: GlCapabilityFacts? = null
        var apiBand: AndroidCaptureApiBand? = null
        var keyDesired = 0L
        var keyGeometry = 0L
        var keyLifecycle = 0L
        var occurrenceIdentity = 0L
        var topologyIdentity = 0L
        var exactTarget: CurrentTarget? = null
        var exactRender: GlPipelineOwner.GlRenderTargetOwner? = null
        var exactFramework: FrameworkJpegOwner? = null
        var exactJpegOwner: JpegRuntimeOwner? = null
        val claimed = sessionGate.withLock {
            if (admissionsClosed || terminalWinner.fixed || metricsJointReadiness == null ||
                androidLaneReadyOwner !== androidOwner || reconciliationIdentity != 0L ||
                desiredRevision <= 0L || geometryGeneration <= 0L || lifecycleEpoch <= 0L
            ) {
                return@withLock false
            }
            parameters = requestedParameters ?: return@withLock false
            capabilities = glCapabilities ?: return@withLock false
            apiBand = androidOwner?.apiBand ?: return@withLock false
            geometry = captureGeometry
            geometryAuthority = combinedGeometryAuthority
            keyDesired = desiredRevision
            keyGeometry = geometryGeneration
            keyLifecycle = lifecycleEpoch
            occurrenceIdentity = reserveIdentityLocked()
            reconciliationIdentity = occurrenceIdentity
            topologyIdentity = topologyPublicationIdentity
            exactTarget = currentTarget
            exactRender = installedRenderTarget
            exactFramework = installedFrameworkOwner
            exactJpegOwner = jpegOwner
            true
        }
        if (!claimed) return null
        val jpegProduct = exactJpegOwner?.stableTopologySnapshot()?.product
        val key = ReconciliationKey(keyDesired, keyGeometry, keyLifecycle)
        val exactGeometry = geometry
        val input: ReconciliationInput = if (exactGeometry != null) {
            AuthoritativeInput(
                key = key,
                reconciliationOccurrenceIdentity = occurrenceIdentity,
                apiBand = checkNotNull(apiBand),
                captureGeometry = exactGeometry,
                parameters = checkNotNull(parameters),
                currentTopology = ReconciliationCurrentTopology(exactTarget, exactRender, jpegProduct, exactFramework),
                capabilities = checkNotNull(capabilities),
                topologyIdentity = topologyIdentity,
            )
        } else {
            val authority = geometryAuthority
            if (apiBand != AndroidCaptureApiBand.Api34To37 || authority == null || authority.sourceWidthPx <= 0 ||
                authority.sourceHeightPx <= 0 || authority.densityDpi <= 0
            ) {
                sessionGate.withLock { if (reconciliationIdentity == occurrenceIdentity) reconciliationIdentity = 0L }
                return null
            }
            ProvisionalBootstrapInput(
                key = key,
                reconciliationOccurrenceIdentity = occurrenceIdentity,
                apiBand = checkNotNull(apiBand),
                provisionalWidthPx = authority.sourceWidthPx,
                provisionalHeightPx = authority.sourceHeightPx,
                densityDpi = authority.densityDpi,
                capabilities = checkNotNull(capabilities),
                topologyIdentity = topologyIdentity,
            )
        }
        return ReconciliationOwner.calculate(input)
    }

    internal fun acceptReconciliation(calculation: ReconciliationCalculation): Boolean {
        val changed = sessionGate.withLock {
            val input = calculation.input
            if (terminalWinner.fixed || admissionsClosed || input.reconciliationOccurrenceIdentity != reconciliationIdentity ||
                input.key.desiredRevision != desiredRevision || input.key.geometryGeneration != geometryGeneration ||
                input.key.lifecycleEpoch != lifecycleEpoch || input.topologyIdentity != topologyPublicationIdentity
            ) {
                return@withLock false
            }
            when (calculation) {
                is Resolved -> {
                    currentCalculation = calculation
                    currentProvisional = null
                    currentPlan = calculation.targetPlan
                    true
                }

                is ProvisionalFull -> {
                    currentCalculation = null
                    currentProvisional = calculation
                    currentPlan = calculation.targetPlan
                    true
                }

                is InvalidRequest -> applyRecoverableOrStartupFailureLocked(ScreenCaptureProblem.InvalidRequest)
                is CapacityDenied -> applyRecoverableOrStartupFailureLocked(ScreenCaptureProblem.ResourceExhausted)
                is InternalFailure -> offerFailureLocked(ScreenCaptureProblem.InternalFailure, RECONCILIATION_EVIDENCE_INVALID)
            }
        }
        if (changed) signal()
        return changed
    }

    private fun applyRecoverableOrStartupFailureLocked(problem: ScreenCaptureProblem): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        return if (firstActiveAssigned && lastEffectiveParameters != null) {
            runningPhase = RunningPhase.Active
            reserveRunningPublicationLocked()
            true
        } else {
            offerFailureLocked(problem, null)
        }
    }

    internal fun installGlSessionFacts(facts: GlClaimedOperationFacts): Boolean {
        val owner = glOwner ?: return false
        val accepted = sessionGate.withLock {
            if (terminalWinner.fixed || facts.operationKind != GlOperationKind.SessionConstruction ||
                facts.result != GlOperationResult.Success || !facts.timely || facts.receipt == null ||
                facts.contextIntegrity != ContextIntegrity.Intact
            ) {
                return@withLock false
            }
            val capabilities = owner.capabilityFacts ?: return@withLock false
            if (glSessionFacts != null && glSessionFacts !== facts) return@withLock false
            glSessionFacts = facts
            glCapabilities = capabilities
            true
        }
        if (accepted) signal()
        return accepted
    }

    internal fun admitPreparedTarget(candidate: PreparedTarget): Boolean {
        val admitted = sessionGate.withLock {
            val reconciliationInput = currentCalculation?.input ?: currentProvisional?.input ?: return@withLock false
            val plan = currentPlan ?: return@withLock false
            if (preparedTarget != null || currentTarget != null || cleanupOwner.preparedTargetRoot.target != null ||
                cleanupOwner.currentTargetRoot.target != null || cleanupOwner.quarantineRoot.targetChild != null ||
                terminalWinner.fixed || admissionsClosed
            ) {
                return@withLock false
            }
            if (!targetOwner.admitPreparedTarget(
                    prospectiveTarget = candidate,
                    currentDesiredRevision = desiredRevision,
                    currentGeometryGeneration = geometryGeneration,
                    currentLifecycleEpoch = lifecycleEpoch,
                    currentReconciliationIdentity = reconciliationIdentity,
                    currentPlan = plan,
                )
            ) {
                return@withLock false
            }
            reconciliationInput.reconciliationOccurrenceIdentity
            preparedTarget = candidate
            true
        }
        if (admitted) signal()
        return admitted
    }

    internal fun claimPreparedTargetFold(): TargetConstructionFoldToken? = sessionGate.withLock {
        val candidate = preparedTarget ?: return@withLock null
        val plan = currentPlan ?: return@withLock null
        targetOwner.claimPreparedTargetResultLocked(
            target = candidate,
            expectedConstructionOperationIdentity = candidate.constructionOccurrence.identity,
            currentDesiredRevision = desiredRevision,
            currentGeometryGeneration = geometryGeneration,
            currentLifecycleEpoch = lifecycleEpoch,
            currentReconciliationIdentity = reconciliationIdentity,
            currentPlan = plan,
            admissionDisposition = if (terminalWinner.fixed || admissionsClosed) {
                TargetConstructionAdmissionDisposition.Terminal
            } else {
                TargetConstructionAdmissionDisposition.Active
            },
        )
    }

    internal fun selectAndApplyPreparedTargetFold(token: TargetConstructionFoldToken): TargetConstructionFoldDisposition? {
        val selected = sessionGate.withLock {
            val candidate = preparedTarget
            val plan = currentPlan
            val exactCurrent = candidate === token.preparedTarget && plan != null && !terminalWinner.fixed && !admissionsClosed &&
                    token.desiredRevision == desiredRevision && token.geometryGeneration == geometryGeneration &&
                    token.lifecycleEpoch == lifecycleEpoch && token.reconciliationIdentity == reconciliationIdentity &&
                    token.plan === plan
            val request = if (exactCurrent) {
                TargetConstructionFoldDisposition.Install
            } else if (terminalWinner.fixed || admissionsClosed) {
                TargetConstructionFoldDisposition.CleanupTerminal
            } else {
                TargetConstructionFoldDisposition.CleanupStale
            }
            if (plan == null) return@withLock null
            targetOwner.foldPreparedTargetResultLocked(
                token = token,
                expectedConstructionOperationIdentity = token.constructionOperationIdentity,
                currentDesiredRevision = desiredRevision,
                currentGeometryGeneration = geometryGeneration,
                currentLifecycleEpoch = lifecycleEpoch,
                currentReconciliationIdentity = reconciliationIdentity,
                currentPlan = plan,
                requestedDisposition = request,
            )
        } ?: return null

        if (!targetOwner.applyPreparedTargetFold(token)) return null
        sessionGate.withLock {
            if (selected == TargetConstructionFoldDisposition.Install &&
                token.installedTarget != null && preparedTarget === token.preparedTarget
            ) {
                currentTarget = token.installedTarget
                preparedTarget = null
                topologyPublicationIdentity = reserveIdentityLocked()
                invalidateReconciliationForTopologyMutationLocked()
            } else if (preparedTarget === token.preparedTarget) {
                preparedTarget = null
            }
        }
        if (selected != TargetConstructionFoldDisposition.Install) {
            sessionGate.withLock {
                if (cleanupOwner.attachPreparedTarget(token.preparedTarget) != CleanupMutation.None) cleanupWorkPending = true
            }
        }
        signal()
        return selected
    }

    internal fun acceptRenderTargetInstallation(
        command: GlPipelineOwner.RenderTargetConstructionCommand,
        claim: io.screenstream.engine.internal.gl.GlRenderTargetConstructionClaim,
    ): Boolean {
        val currentBeforeCommit = sessionGate.withLock {
            val target = currentTarget ?: return@withLock false
            val calculation = currentCalculation ?: return@withLock false
            !terminalWinner.fixed && !admissionsClosed && target.generation > 0L &&
                    calculation.input.key.desiredRevision == desiredRevision &&
                    calculation.input.key.geometryGeneration == geometryGeneration &&
                    calculation.input.key.lifecycleEpoch == lifecycleEpoch &&
                    installedRenderTarget == null && claim.facts.result == GlOperationResult.Success &&
                    claim.facts.receipt != null && claim.facts.timely &&
                    claim.facts.contextIntegrity == ContextIntegrity.Intact
        }
        if (!currentBeforeCommit) {
            command.claimCleanupDestruction(claim)?.destructionCommand?.submit()
            return false
        }
        val installed = command.commitInstallation(claim) ?: run {
            command.claimCleanupDestruction(claim)?.destructionCommand?.submit()
            return false
        }
        val accepted = sessionGate.withLock {
            val calculation = currentCalculation
            if (terminalWinner.fixed || admissionsClosed || installedRenderTarget != null || calculation == null ||
                calculation.input.key.desiredRevision != desiredRevision ||
                calculation.input.key.geometryGeneration != geometryGeneration ||
                calculation.input.key.lifecycleEpoch != lifecycleEpoch
            ) {
                return@withLock false
            }
            installedRenderTarget = installed
            topologyPublicationIdentity = reserveIdentityLocked()
            invalidateReconciliationForTopologyMutationLocked()
            true
        }
        if (!accepted) glOwner?.prepareRenderTargetDestruction(installed)?.submit()
        signal()
        return accepted
    }

    internal fun classifyAndSettleFrameworkCreation(
        occurrence: FrameworkResourceCreationOccurrence,
    ): FrameworkJpegOwner? {
        val current = sessionGate.withLock {
            !terminalWinner.fixed && !admissionsClosed && pendingFrameworkCreation === occurrence &&
                    occurrence.desiredRevision == desiredRevision && occurrence.geometryGeneration == geometryGeneration &&
                    occurrence.lifecycleEpoch == lifecycleEpoch
        }
        val evidence = occurrence.operation.settlementGate.withLock {
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
            FrameworkCreationClassification(
                timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely,
                normal = occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal,
                result = occurrence.operation.returnCell.evidence.result,
                cause = occurrence.operation.returnCell.evidence.failureCause,
                completeOwner = occurrence.operation.returnCell.evidence.returnedOwner,
            )
        }
        val safeComplete = evidence.timely && evidence.normal &&
                evidence.result == FrameworkResourceCreationResult.Complete && evidence.completeOwner != null
        val installed = FrameworkJpegOwner.settleResourceCreation(occurrence, installAllowed = current && safeComplete)
        if (installed != null) {
            val accepted = sessionGate.withLock {
                if (terminalWinner.fixed || admissionsClosed || pendingFrameworkCreation !== occurrence ||
                    occurrence.desiredRevision != desiredRevision || occurrence.geometryGeneration != geometryGeneration ||
                    occurrence.lifecycleEpoch != lifecycleEpoch || installedFrameworkOwner != null
                ) {
                    return@withLock false
                }
                installedFrameworkOwner = installed
                pendingFrameworkCreation = null
                topologyPublicationIdentity = reserveIdentityLocked()
                invalidateReconciliationForTopologyMutationLocked()
                true
            }
            if (accepted) {
                signal()
                return installed
            }
        }

        sessionGate.withLock {
            if (pendingFrameworkCreation === occurrence) pendingFrameworkCreation = null
        }
        if (!current) {
            recycleReturnedFrameworkOwner(occurrence)
            return null
        }
        when (evidence.result) {
            FrameworkResourceCreationResult.ResourceExhausted ->
                offerFailure(ScreenCaptureProblem.ResourceExhausted, evidence.cause)

            FrameworkResourceCreationResult.InternalFailure,
            FrameworkResourceCreationResult.NotSettled,
            FrameworkResourceCreationResult.Complete,
                -> offerFailure(ScreenCaptureProblem.InternalFailure, evidence.cause)
        }
        recycleReturnedFrameworkOwner(occurrence)
        return null
    }

    private class FrameworkCreationClassification(
        val timely: Boolean,
        val normal: Boolean,
        val result: FrameworkResourceCreationResult,
        val cause: Throwable?,
        val completeOwner: OperationReturnedOwner?,
    )

    private fun recycleReturnedFrameworkOwner(occurrence: FrameworkResourceCreationOccurrence) {
        val operationIdentity = sessionGate.withLock { reserveIdentityLocked() }
        val recycle = FrameworkJpegOwner.beginTerminalReturnedOwnerRecycle(occurrence, operationIdentity)
        if (recycle == null) {
            FrameworkJpegOwner.settleResourceCreationWithoutReturnedBitmap(occurrence)
            return
        }
        val owner = recycle.ownerBag.owner ?: return
        sessionGate.withLock {
            if (lifecycle == Lifecycle.Terminal || jpegOwner !== owner.jpegRuntimeOwner) {
                recordCleanupMutationLocked(cleanupOwner.attachJpeg(owner.jpegRuntimeOwner))
            }
            recordCleanupMutationLocked(cleanupOwner.attachFramework(owner))
            if (cleanupFrameworkRecycle == null) cleanupFrameworkRecycle = recycle
            cleanupWorkPending = true
        }
    }

    internal fun settleSafeNativeFailure(
        occurrence: NativeEncodeOccurrence,
        returnedFatal: Throwable? = null,
    ): Boolean {
        val owner = jpegOwner ?: return false
        val storage = storageOwner ?: return false
        if (returnedFatal != null) {
            val child = ReturnedNativeFatalCleanupChild(owner, occurrence, storage)
            sessionGate.withLock {
                val attached = cleanupOwner.attachJpeg(owner) != CleanupMutation.None || cleanupOwner.jpegRoot.owner === owner
                val storageAttached = cleanupOwner.attachStorage(storage) != CleanupMutation.None ||
                        cleanupOwner.storageRoot.owner === storage
                if (attached && storageAttached) cleanupOwner.attachReturnedNativeFatal(child)
            }
            return when (owner.settleReturnedFatalNativeEncodeCleanup(occurrence, storage)) {
                NativeEncodeFatalCleanupSettlement.NotReady -> false
                NativeEncodeFatalCleanupSettlement.Reduced -> {
                    offerFailure(ScreenCaptureProblem.InternalFailure, returnedFatal)
                    true
                }

                NativeEncodeFatalCleanupSettlement.UnsafeResidue -> {
                    offerFailure(ScreenCaptureProblem.InternalFailure, returnedFatal)
                    true
                }
            }
        }

        val exactCurrentHealth = sessionGate.withLock {
            val topology = owner.stableTopologySnapshot()
            !terminalWinner.fixed && topology?.product === occurrence.capturedProduct &&
                    topology.nativeHealth == io.screenstream.engine.internal.jpeg.NativeJpegHealth.Enabled
        }
        val disabled = if (exactCurrentHealth) {
            owner.commitSafeNativeDisable(occurrence)
        } else {
            owner.retireSafeNativeDisable(occurrence)
        }
        if (!disabled) return false
        if (exactCurrentHealth) {
            sessionGate.withLock {
                advanceLifecycleEpochLocked()
                runningPhase = if (firstActiveAssigned) RunningPhase.Suspended else null
                invalidateOutputLocked()
                saturatingIncrementFailureLocked()
                if (firstActiveAssigned) reserveRunningPublicationLocked(ScreenCaptureProblem.Reconfiguring)
            }
            emitDiagnostic(
                source = "NativeJpeg",
                label = "RuntimeModeChanged",
                message = "native allocation failed safely; Framework required for later frames",
                cause = occurrence.operation.returnCell.evidence.failureCause,
            )
            signal()
        }
        return true
    }

    internal fun commitActive(effectiveParameters: ScreenCaptureEffectiveParameters): Boolean {
        var calculation: Resolved? = null
        var metrics: CaptureMetricsOwner? = null
        var readiness: MetricsJointReadinessFacts? = null
        var android: AndroidCaptureOwner? = null
        var gl: GlPipelineOwner? = null
        var target: CurrentTarget? = null
        var renderTarget: GlPipelineOwner.GlRenderTargetOwner? = null
        var runtime: JpegRuntimeOwner? = null
        var framework: FrameworkJpegOwner? = null
        var parameters: ScreenCaptureParameters? = null
        var visible: Boolean? = null
        var topologyIdentity = 0L
        var registrationIdentity = 0L
        val selected = sessionGate.withLock {
            calculation = currentCalculation
            metrics = metricsOwner
            readiness = metricsJointReadiness
            android = androidOwner
            gl = glOwner
            target = currentTarget
            renderTarget = installedRenderTarget
            runtime = jpegOwner
            framework = installedFrameworkOwner
            parameters = requestedParameters
            visible = capturedContentVisible
            topologyIdentity = topologyPublicationIdentity
            registrationIdentity = acceptedProjectionCallbackRegistrationIdentity
            !terminalWinner.fixed && !admissionsClosed && calculation != null && metrics != null && readiness != null &&
                    android != null && gl != null && target != null && renderTarget != null && runtime != null &&
                    parameters != null && registrationIdentity > 0L &&
                    checkNotNull(calculation).effectiveParameters == effectiveParameters &&
                    cleanupOwner.currentTargetRoot.target == null && cleanupOwner.jpegRoot.owner == null &&
                    cleanupOwner.jpegRoot.frameworkOwner == null
        }
        if (!selected) return false
        val exactReadiness = checkNotNull(readiness)
        val topology = SessionReconfiguration.captureCompleteTopology(SessionTopologyCaptureCommand(
            key = checkNotNull(calculation).input.key,
            topologyIdentity = topologyIdentity,
            metricsOwner = checkNotNull(metrics),
            metricsSource = exactReadiness.source,
            metricsObservationIdentity = exactReadiness.observationIdentity,
            metricsReadinessSequence = exactReadiness.sequence,
            androidOwner = checkNotNull(android),
            projectionRegistrationIdentity = registrationIdentity,
            glOwner = checkNotNull(gl),
            target = checkNotNull(target),
            renderTarget = checkNotNull(renderTarget),
            jpegOwner = checkNotNull(runtime),
            frameworkOwner = framework,
            effectiveParameters = effectiveParameters,
        )) ?: return false
        if (!SessionReconfiguration.revalidate(topology)) return false
        val publicSnapshot = ObservationStateSnapshot.Running(
            requestedParameters = checkNotNull(parameters),
            runningState = ObservationRunningStateSnapshot.Active(effectiveParameters),
            capturedContentVisible = visible,
        )
        val committed = sessionGate.withLock {
            val currentReadiness = metricsJointReadiness
            if (terminalWinner.fixed || admissionsClosed || currentCalculation !== calculation ||
                topologyPublicationIdentity != topology.topologyIdentity || metricsOwner !== topology.metricsOwner ||
                currentReadiness == null || currentReadiness.owner !== topology.metricsOwner ||
                currentReadiness.source !== topology.metricsSource ||
                currentReadiness.observationIdentity != topology.metricsObservationIdentity ||
                currentReadiness.sequence != topology.metricsReadinessSequence ||
                androidOwner !== topology.androidOwner ||
                acceptedProjectionCallbackRegistrationIdentity != topology.projectionRegistrationIdentity ||
                glOwner !== topology.glOwner || currentTarget !== topology.target ||
                !topology.target.isCurrentnessVersion(topology.targetCurrentness.version) ||
                installedRenderTarget !== topology.renderTarget || jpegOwner !== topology.jpegOwner ||
                checkNotNull(runtime).stableTopologySnapshot() !== topology.jpegTopology ||
                installedFrameworkOwner !== topology.frameworkOwner || requestedParameters !== parameters
            ) {
                return@withLock false
            }
            acceptedTopologySnapshot = topology
            lastEffectiveParameters = effectiveParameters
            lifecycle = Lifecycle.Running
            runningPhase = RunningPhase.Active
            if (!firstActiveAssigned) metricsOwner?.commitFirstActiveLocked()
            publicStatePublicationIdentity = reserveIdentityLocked()
            true
        }
        if (!committed) return false

        SessionPublication.dispatch(
            observationOwner,
            SessionPublicationBatch(running = publicSnapshot),
        )
        val first = sessionGate.withLock {
            if (terminalWinner.fixed || lifecycle != Lifecycle.Running || runningPhase != RunningPhase.Active) {
                return@withLock false
            }
            val wasFirst = !firstActiveAssigned
            firstActiveAssigned = true
            wasFirst
        }
        if (first) startCompletion.complete(StartOutcome.RunningAssigned)
        return true
    }

    internal fun recordMechanicallySuccessfulEncode(
        encodedByteCount: Int,
        encodeDurationNanos: Long,
    ) {
        sessionGate.withLock {
            if (statsAuthority.cutoff || encodedByteCount <= 0 || encodeDurationNanos < 0L) return
            statsAuthority.framesEncoded = saturatedIncrement(statsAuthority.framesEncoded)
            statsAuthority.encodeSamples = saturatedIncrement(statsAuthority.encodeSamples)
            statsAuthority.encodeMeanNanos = orderedMean(
                statsAuthority.encodeMeanNanos,
                encodeDurationNanos.toDouble(),
                statsAuthority.encodeSamples,
            )
            statsAuthority.encodedByteSamples = saturatedIncrement(statsAuthority.encodedByteSamples)
            statsAuthority.encodedByteMean = orderedMean(
                statsAuthority.encodedByteMean,
                encodedByteCount.toDouble(),
                statsAuthority.encodedByteSamples,
            )
            statsAuthority.lastEncodedByteCount = encodedByteCount
        }
    }

    internal fun recordMechanicallySuccessfulReadback(readbackDurationNanos: Long) {
        sessionGate.withLock {
            if (statsAuthority.cutoff || readbackDurationNanos < 0L) return
            statsAuthority.readbackSamples = saturatedIncrement(statsAuthority.readbackSamples)
            statsAuthority.readbackMeanNanos = orderedMean(
                statsAuthority.readbackMeanNanos,
                readbackDurationNanos.toDouble(),
                statsAuthority.readbackSamples,
            )
        }
    }

    internal fun recordProduced(timestampNanos: Long) {
        sessionGate.withLock {
            if (statsAuthority.cutoff || timestampNanos < 0L) return
            statsAuthority.framesProduced = saturatedIncrement(statsAuthority.framesProduced)
            if (statsAuthority.firstProducedNanos == Long.MIN_VALUE) statsAuthority.firstProducedNanos = timestampNanos
            statsAuthority.lastProducedNanos = timestampNanos
        }
    }

    internal fun executeWakeSubmission(
        link: ControlWakeLink,
        action: ControlWakeScheduleAction,
    ) {
        val scheduler = controlScheduler.get() ?: run {
            link.publishSchedulingFailure(action, RejectedExecutionException("Control scheduler unavailable"))
            offerFailure(ScreenCaptureProblem.InternalFailure, CONTROL_SCHEDULER_UNAVAILABLE)
            return
        }
        val delay = maxOf(0L, action.dueNanos - clock.nowNanos())
        try {
            val future = scheduler.schedule(action.runner, delay, TimeUnit.NANOSECONDS)
            val outer = future as Runnable
            if (!link.publishSchedulingAccepted(action, future, outer)) {
                executeDetachedFutureCancellation(future, outer)
            }
        } catch (raw: Throwable) {
            link.publishSchedulingFailure(action, raw)
            poisonControl(raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
    }

    internal fun executeWakeCancellation(
        link: ControlWakeLink,
        action: ControlWakeCancellationAction,
    ) {
        val future = checkNotNull(action.future)
        val outer = checkNotNull(action.outerWrapper)
        var cancelReturned: Boolean? = null
        var cancelFailure: Throwable? = null
        var suppression = ControlWakeSuppressionDisposition.NotAttempted
        try {
            cancelReturned = future.cancel(false)
            if (cancelReturned) {
                suppression = if (action.trySuppress()) {
                    ControlWakeSuppressionDisposition.Succeeded
                } else {
                    ControlWakeSuppressionDisposition.Failed
                }
            }
        } catch (raw: Throwable) {
            cancelFailure = raw
        }

        var removalReturned: Boolean? = null
        var removalFailure: Throwable? = null
        try {
            removalReturned = controlScheduler.get()?.remove(outer) ?: false
        } catch (raw: Throwable) {
            removalFailure = raw
        }
        link.publishCancellation(
            action = action,
            cancelReturned = cancelReturned,
            cancelFailure = cancelFailure,
            suppressionDisposition = suppression,
            removalReturned = removalReturned,
            removalFailure = removalFailure,
        )
        val fatal = when {
            cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure) -> cancelFailure
            removalFailure != null && FatalThrowablePolicy.isDirectFatal(removalFailure) -> removalFailure
            else -> null
        }
        val ordinary = cancelFailure ?: removalFailure
        if (ordinary != null) poisonControl(ordinary)
        if (fatal != null) FatalThrowablePolicy.rethrow(fatal)
    }

    private fun executeDetachedFutureCancellation(future: Future<*>, outer: Runnable) {
        try {
            future.cancel(false)
        } finally {
            controlScheduler.get()?.remove(outer)
        }
    }

    private fun submitDrainer() {
        val scheduler = controlScheduler.get()
        if (scheduler == null) {
            preBarrierDirty.set(true)
            return
        }
        val generation = drainerGeneration.updateAndGet { current ->
            if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
        }
        if (generation == Long.MAX_VALUE) {
            emergencyFailClosed(IDENTITY_EXHAUSTED)
            return
        }
        val record = drainerRecords[(generation and 1L).toInt()]
        if (!record.prepare(generation)) {
            emergencyFailClosed(CONTROL_DRAINER_RECORD_REUSE)
            return
        }
        try {
            scheduler.execute(record.runner)
            record.publishSubmissionAccepted(generation)
        } catch (raw: Throwable) {
            record.publishSubmissionFailure(generation, raw)
            poisonControl(raw)
            emergencyFailClosed(raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
    }

    private fun runDrainer() {
        controlPoison.get()?.let { raw ->
            emergencyFailClosed(raw)
            drainerState.set(DRAIN_IDLE)
            return
        }
        while (true) {
            executeCleanupActions()
            if (sessionGate.withLock { terminalCutoffApplied }) {
                latestResize.getAndSet(null)
                latestVisibility.getAndSet(null)
                captureEnded.getAndSet(null)
            } else {
                constructOwnersForAcceptedStart()
                prestartAndAdoptStartupOwners()
                launchOwnersForAcceptedStart()
                sessionGate.withLock { metricsOwner }?.readinessWakeLink?.let(::serviceWakeLink)
                consumeGlSessionConstruction()
                consumeAndroidLaneStartup()
                advanceProjectionRegistration()
                consumeAndroidFacts()
                consumeMetricsReadiness()
                consumeMetricsLatest()
                consumeMetricsTerminal()
                rebuildCombinedGeometry()
                calculateLatestReconciliation()?.let(::acceptReconciliation)
                advanceReconfigurationBoundary()
                constructReplacementJpegOwner()
                advanceTargetConstruction()
                advanceListenerInstallation()
                advanceVirtualDisplayCreation()
                calculateLatestReconciliation()?.let(::acceptReconciliation)
                advanceRenderTargetConstruction()
                advanceJpegPreparation()
                advanceFrameworkPreparation()
                acceptCompleteTopology()
            }

            turnSlots.clear()
            sessionGate.withLock { collectTurnLocked(turnSlots) }
            executeTurnUnlocked(turnSlots)

            if (hasImmediateControllerWork()) {
                drainerState.set(DRAIN_RUNNING_DIRTY)
            }
            if (drainerState.compareAndSet(DRAIN_RUNNING_DIRTY, DRAIN_RUNNING)) continue
            if (drainerState.compareAndSet(DRAIN_RUNNING, DRAIN_IDLE)) return
        }
    }

    private fun collectTurnLocked(slots: TurnSlots) {
        check(sessionGate.isHeldByCurrentThread)
        if (lifecycle == Lifecycle.Starting && !startingAssigned) {
            startingAssigned = true
            publicStatePublicationIdentity = reserveIdentityLocked()
            slots.publishStarting = true
        }
        if (targetAdmissionClosePending) {
            targetAdmissionClosePending = false
            slots.closeTargetAdmission = true
        }
        if (runningPublicationPending && lifecycle == Lifecycle.Running) {
            val parameters = requestedParameters
            val effective = lastEffectiveParameters
            if (parameters != null && effective != null) {
                slots.buildRunningOutput = true
                slots.runningParameters = parameters
                slots.runningEffective = effective
                slots.runningProblem = runningPublicationProblem
                slots.runningGeometry = captureGeometry
                slots.runningVisible = capturedContentVisible
                slots.runningIsActive = runningPublicationProblem == null && runningPhase == RunningPhase.Active
                publicStatePublicationIdentity = reserveIdentityLocked()
            }
            runningPublicationPending = false
            runningPublicationProblem = null
        }
        chooseTerminalWinnerLocked()
        if (terminalWinner.fixed && !terminalCutoffApplied) {
            applyTerminalCutoffLocked(slots)
            return
        }
    }

    private fun executeTurnUnlocked(slots: TurnSlots) {
        if (slots.buildTerminalOutputs) buildTerminalOutputsUnlocked(slots)
        if (slots.buildRunningOutput) {
            val effective = checkNotNull(slots.runningEffective)
            val running = if (slots.runningIsActive) {
                ObservationRunningStateSnapshot.Active(effective)
            } else {
                ObservationRunningStateSnapshot.Suspended(
                    problem = slots.runningProblem ?: ScreenCaptureProblem.Reconfiguring,
                    lastEffectiveParameters = effective,
                    lastKnownCaptureGeometry = slots.runningGeometry,
                )
            }
            slots.publishRunning = ObservationStateSnapshot.Running(
                checkNotNull(slots.runningParameters),
                running,
                slots.runningVisible,
            )
        }
        if (slots.publishStarting) slots.publishStats = statsAuthority.snapshot()
        val diagnostic = if (slots.terminalDiagnosticSequence > 0L) {
            val message = when (slots.terminalDiagnosticKind) {
                TerminalKind.CaptureEnded -> "capture ended; terminal cleanup started"
                TerminalKind.OwnerStop -> "owner stop won; terminal cleanup started"
                TerminalKind.Failed ->
                    "${slots.terminalDiagnosticProblem ?: ScreenCaptureProblem.InternalFailure} won; terminal cleanup started"

                null -> "terminal cleanup started"
            }
            ObservationDiagnosticRequest(
                sequence = slots.terminalDiagnosticSequence,
                timestampEpochMillis = wallClockMillis(),
                source = "Session",
                label = "SessionTerminal",
                message = message,
                cause = slots.terminalDiagnosticCause,
            )
        } else {
            null
        }
        val batch = SessionPublicationBatch(
            starting = slots.publishStarting,
            startingStats = slots.publishStats,
            running = slots.publishRunning,
            terminalStats = slots.terminalStats,
            terminalDiagnostic = diagnostic,
            terminalState = slots.terminalState,
        )
        if (slots.closeTargetAdmission) targetOwner.closeConstructionAdmission()
        SessionPublication.dispatch(observationOwner, batch)
        slots.completeStart?.let(startCompletion::complete)
    }

    private fun buildTerminalOutputsUnlocked(slots: TurnSlots) {
        val parameters = checkNotNull(requestedParameters)
        slots.terminalStats = statsAuthority.snapshot()
        slots.terminalState = when (terminalWinner.kind) {
            TerminalKind.CaptureEnded,
            TerminalKind.OwnerStop,
                -> ObservationStateSnapshot.Stopped(
                reason = checkNotNull(terminalWinner.stopReason),
                requestedParameters = parameters,
                lastEffectiveParameters = lastEffectiveParameters,
            )

            TerminalKind.Failed -> ObservationStateSnapshot.Failed(
                problem = terminalWinner.problem ?: ScreenCaptureProblem.InternalFailure,
                requestedParameters = parameters,
                lastEffectiveParameters = lastEffectiveParameters,
            )
        }
        if (!firstActiveAssigned) {
            slots.completeStart = when (terminalWinner.kind) {
                TerminalKind.CaptureEnded,
                TerminalKind.OwnerStop,
                    -> StartOutcome.StoppedBeforeActive(checkNotNull(terminalWinner.stopReason))

                TerminalKind.Failed -> StartOutcome.FailedBeforeActive(
                    terminalWinner.problem ?: ScreenCaptureProblem.InternalFailure,
                    terminalWinner.cause,
                )
            }
        }
    }

    private fun chooseTerminalWinnerLocked() {
        check(sessionGate.isHeldByCurrentThread)
        if (terminalWinner.fixed) return
        val contender = when {
            terminalContenders[CAPTURE_ENDED_CONTENDER].present -> terminalContenders[CAPTURE_ENDED_CONTENDER]
            terminalContenders[OWNER_STOP_CONTENDER].present -> terminalContenders[OWNER_STOP_CONTENDER]
            terminalContenders[FAILURE_CONTENDER].present -> terminalContenders[FAILURE_CONTENDER]
            else -> return
        }
        terminalWinner.fixed = true
        terminalWinner.kind = contender.kind
        terminalWinner.stopReason = contender.stopReason
        terminalWinner.problem = contender.problem
        terminalWinner.cause = contender.cause
        terminalContenders.forEach(TerminalContender::clear)
    }

    private fun applyTerminalCutoffLocked(slots: TurnSlots) {
        check(sessionGate.isHeldByCurrentThread)
        closeAdmissionLocked()
        terminalCutoffApplied = true
        statsAuthority.cutoff = true
        lifecycle = Lifecycle.Terminal
        runningPhase = null
        advanceLifecycleEpochLocked()
        invalidateOutputLocked()

        val startupBag = pendingStartupBag
        val exactMetrics = metricsOwner ?: startupBag?.metrics
        val exactAndroid = androidOwner ?: startupBag?.android
        val exactPrepared = preparedTarget
        val exactCurrent = currentTarget
        val exactRender = installedRenderTarget
        val exactGl = glOwner ?: startupBag?.gl
        val exactJpeg = jpegOwner ?: startupBag?.jpeg
        val exactStorage = storageOwner ?: startupBag?.storage
        if (exactMetrics != null && cleanupOwner.attachMetrics(exactMetrics) != CleanupMutation.None) cleanupWorkPending = true
        if (exactAndroid != null && cleanupOwner.attachAndroid(exactAndroid) != CleanupMutation.None) cleanupWorkPending = true
        if (exactPrepared != null && cleanupOwner.attachPreparedTarget(exactPrepared) != CleanupMutation.None) cleanupWorkPending = true
        if (exactCurrent != null && cleanupOwner.attachCurrentTarget(exactCurrent) != CleanupMutation.None) cleanupWorkPending = true
        if (exactGl != null && cleanupOwner.attachGl(exactGl) != CleanupMutation.None) cleanupWorkPending = true
        if (exactJpeg != null && cleanupOwner.attachJpeg(exactJpeg) != CleanupMutation.None) cleanupWorkPending = true
        installedFrameworkOwner?.let { framework ->
            if (cleanupOwner.attachFramework(framework) != CleanupMutation.None) cleanupWorkPending = true
        }
        if (exactStorage != null && cleanupOwner.attachStorage(exactStorage) != CleanupMutation.None) cleanupWorkPending = true

        metricsOwner = null
        metricsJointReadiness = null
        androidOwner = null
        androidLaneReadyOwner = null
        projection = null
        preparedTarget = null
        currentTarget = null
        glOwner = null
        jpegOwner = null
        storageOwner = null
        pendingStartupBag = null
        installedRenderTarget = null
        cleanupRenderOwner = exactRender
        installedFrameworkOwner = null
        currentCalculation = null
        currentProvisional = null
        currentPlan = exactPrepared?.plan
        latestMetricsFact = null
        combinedGeometryAuthority = null
        captureGeometry = null

        publicStatsPublicationIdentity = reserveIdentityLocked()
        slots.buildTerminalOutputs = true
        slots.terminalDiagnosticSequence = reserveDiagnosticSequenceLocked()
        slots.terminalDiagnosticKind = terminalWinner.kind
        slots.terminalDiagnosticProblem = terminalWinner.problem
        slots.terminalDiagnosticCause = terminalWinner.cause
        publicStatePublicationIdentity = reserveIdentityLocked()
    }

    private fun executeCleanupActions() {
        var metricsAction: io.screenstream.engine.internal.MetricsEndpointShutdownAction? = null
        var androidAction: io.screenstream.engine.internal.AndroidLaneQuitAction? = null
        var existingGlAction: io.screenstream.engine.internal.GlLaneShutdownAction? = null
        var glForPreparation: GlPipelineOwner? = null
        var jpegAction: io.screenstream.engine.internal.JpegEndpointShutdownAction? = null
        var existingStorageAction: StorageRetirementAction? = null
        var storageForPreparation: EncodedStorageOwner? = null
        var returnedNativeFatal: io.screenstream.engine.internal.ReturnedNativeFatalCleanupChild? = null
        var awaitingStage5 = false
        foldTerminalLateOccurrences()
        val metricsForPreparation = sessionGate.withLock { cleanupOwner.metricsRoot.owner }
        metricsForPreparation?.prepareEndpointShutdown()
        advancePreparedTargetCleanup()
        val targetForProgress = sessionGate.withLock { cleanupOwner.currentTargetRoot.target }
        if (targetForProgress != null) advanceTerminalTargetCleanup(targetForProgress)
        val androidForProgress = sessionGate.withLock { cleanupOwner.androidRoot.owner }
        if (androidForProgress != null) advanceTerminalAndroidCleanup(androidForProgress)
        advanceTerminalFrameworkCleanup()
        advanceTerminalJpegProductCleanup()
        val glForProgress = sessionGate.withLock { cleanupOwner.glRoot.owner }
        if (glForProgress != null) advanceTerminalGlCleanup(glForProgress)

        sessionGate.withLock {
            if (lifecycle != Lifecycle.Terminal && !cleanupWorkPending) return
            cleanupWorkPending = false
            metricsAction = cleanupOwner.claimMetricsShutdownAction()
            androidAction = cleanupOwner.claimAndroidQuitAction()
            existingGlAction = cleanupOwner.claimExistingGlShutdownAction()
            if (existingGlAction == null) glForPreparation = cleanupOwner.selectGlShutdownOwner()
            jpegAction = cleanupOwner.claimJpegShutdownAction()
            existingStorageAction = cleanupOwner.claimExistingStorageRetirementAction()
            if (existingStorageAction == null) storageForPreparation = cleanupOwner.selectStorageRetirementOwner()
            returnedNativeFatal = cleanupOwner.selectReturnedNativeFatal()
            awaitingStage5 = cleanupOwner.controlShutdownReadiness == ControlShutdownReadiness.AwaitingStage5Delivery
        }

        val attempt = SessionTerminalCleanup.attempt(
            SessionTerminalCleanupCommand(
                metrics = metricsAction,
                android = androidAction,
                gl = existingGlAction,
                jpeg = jpegAction,
                storage = existingStorageAction,
            ),
        )
        if (attempt.hasFailure) {
            sessionGate.withLock {
                if (attempt.metricsFailure != null) {
                    metricsForPreparation?.let { recordCleanupMutationLocked(cleanupOwner.quarantineMetrics(it)) }
                }
                if (attempt.androidFailure != null) {
                    androidForProgress?.let { recordCleanupMutationLocked(cleanupOwner.quarantineAndroid(it)) }
                }
                if (attempt.glFailure != null) {
                    glForProgress?.let { recordCleanupMutationLocked(cleanupOwner.quarantineGl(it)) }
                }
                if (attempt.jpegFailure != null) {
                    cleanupOwner.jpegRoot.owner?.let { recordCleanupMutationLocked(cleanupOwner.quarantineJpeg(it)) }
                }
                if (attempt.storageFailure != null) {
                    cleanupOwner.storageRoot.owner?.let { recordCleanupMutationLocked(cleanupOwner.quarantineStorage(it)) }
                }
            }
        }

        sessionGate.withLock {
            cleanupOwner.metricsRoot.owner?.endpointTerminationReceipt?.let { receipt ->
                if (cleanupOwner.reduceMetrics(receipt) != CleanupMutation.None) cleanupWorkPending = true
            }
            cleanupOwner.androidRoot.owner?.laneTerminationReceipt?.let { receipt ->
                if (cleanupOwner.reduceAndroid(receipt) != CleanupMutation.None) cleanupWorkPending = true
            }
            cleanupOwner.glRoot.owner?.laneTerminationReceipt?.let { receipt ->
                if (cleanupOwner.reduceGl(receipt) != CleanupMutation.None) cleanupWorkPending = true
            }
            cleanupOwner.jpegRoot.owner?.jpegTerminationReceipt?.let { receipt ->
                if (cleanupOwner.reduceJpeg(receipt) != CleanupMutation.None) cleanupWorkPending = true
            }
            cleanupOwner.storageRoot.owner?.let { owner ->
                if (cleanupOwner.reduceStorage(owner) != CleanupMutation.None) cleanupWorkPending = true
            }
            cleanupOwner.quarantineRoot.metrics?.endpointTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedMetrics(receipt))) cleanupWorkPending = true
            }
            cleanupOwner.quarantineRoot.android?.laneTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedAndroid(receipt))) cleanupWorkPending = true
            }
            cleanupOwner.quarantineRoot.gl?.laneTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedGl(receipt))) cleanupWorkPending = true
            }
            cleanupOwner.quarantineRoot.jpeg?.jpegTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedJpeg(receipt))) cleanupWorkPending = true
            }
            cleanupOwner.quarantineRoot.framework?.let { owner ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedFrameworkProvenComplete(owner))) {
                    cleanupWorkPending = true
                }
            }
            cleanupOwner.quarantineRoot.storage?.let { owner ->
                if (recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedStorage(owner))) cleanupWorkPending = true
            }
            when (val child = cleanupOwner.quarantineRoot.targetChild) {
                is TargetQuarantineChild.Prepared -> if (child.target.isCleanupComplete() &&
                    recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedPreparedTargetProvenComplete(child.target))
                ) {
                    cleanupWorkPending = true
                }

                is TargetQuarantineChild.Current -> if (child.target.isFullyRetired &&
                    recordCleanupMutationLocked(cleanupOwner.reduceQuarantinedCurrentTargetProvenComplete(child.target))
                ) {
                    cleanupWorkPending = true
                }

                null -> Unit
            }
        }

        val glOwner = glForPreparation
        if (glOwner != null) {
            val command = glOwner.prepareOrderlyShutdown()
            if (command != null) {
                val candidate = io.screenstream.engine.internal.GlLaneShutdownAction(glOwner, command)
                val action = sessionGate.withLock {
                    cleanupOwner.installAndClaimGlShutdownAction(glOwner, command, candidate)
                }
                action?.runUnlocked()
            }
        }
        val storageOwner = storageForPreparation
        if (storageOwner != null) {
            val prepared = StorageRetirementAction.prepare(storageOwner)
            if (prepared != null) {
                val action = sessionGate.withLock {
                    cleanupOwner.installAndClaimStorageRetirementAction(storageOwner, prepared)
                }
                action?.runUnlocked()
            }
        }
        val nativeFatal = returnedNativeFatal
        if (nativeFatal != null) {
            val reduction = nativeFatal.owner.settleReturnedFatalNativeEncodeCleanup(
                nativeFatal.occurrence,
                nativeFatal.storage,
            )
            sessionGate.withLock {
                if (recordCleanupMutationLocked(cleanupOwner.applyReturnedNativeFatalReduction(nativeFatal, reduction))) {
                    cleanupWorkPending = true
                }
            }
        }
        if (awaitingStage5) {
            // Stage 5 must provide the real Delivery dependency before final Control shutdown is reachable.
            publishPendingQuarantineDiagnostics()
            return
        }
        publishPendingQuarantineDiagnostics()
    }

    private fun foldTerminalLateOccurrences() {
        if (!sessionGate.withLock { terminalCutoffApplied }) return
        sessionGate.withLock { pendingProjectionRegistration }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            if (occurrence.arbitrate() != OperationArbitration.None) {
                sessionGate.withLock {
                    if (pendingProjectionRegistration === occurrence) pendingProjectionRegistration = null
                    cleanupWorkPending = true
                }
            }
        }
        sessionGate.withLock { pendingListenerInstallation }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            val arbitration = occurrence.arbitrate()
            if (arbitration != OperationArbitration.None) {
                if (arbitration.isNormalReturn()) {
                    val bag = occurrence.ownerBag as AndroidTargetListenerInstallationOwnerBag
                    bag.target.applyListenerInstallationReceipt(bag.port, occurrence)
                }
                sessionGate.withLock {
                    if (pendingListenerInstallation === occurrence) pendingListenerInstallation = null
                    cleanupWorkPending = true
                }
            }
        }
        sessionGate.withLock { pendingVirtualDisplayCreation }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            occurrence.returnCell.evidence.initialResizeDeadlineOccurrence?.controlWakeLink?.let(::serviceWakeLink)
            val arbitration = occurrence.arbitrate()
            if (arbitration != OperationArbitration.None) {
                if (arbitration.isNormalReturn()) {
                    val bag = occurrence.ownerBag as AndroidVirtualDisplayCreationOwnerBag
                    val candidate = bag.target.producerApplicationCandidateAfterSettlement(bag.port, occurrence)
                    val fact = candidate?.let(bag.target::applyProducerApplication)
                    val owner = sessionGate.withLock { cleanupOwner.androidRoot.owner }
                    if (fact != null) owner?.applyVirtualDisplayCreationTargetFact(occurrence, fact)
                }
                sessionGate.withLock {
                    if (pendingVirtualDisplayCreation === occurrence) pendingVirtualDisplayCreation = null
                    virtualDisplayReturnAccepted = false
                    cleanupWorkPending = true
                }
            }
        }
        sessionGate.withLock { pendingRenderConstruction }?.let { command ->
            serviceWakeLink(command.deadlineWakeLink)
            val claim = command.claim()
            if (claim != null) {
                val destruction = command.claimCleanupDestruction(claim)?.destructionCommand
                sessionGate.withLock {
                    if (pendingRenderConstruction === command) pendingRenderConstruction = null
                    if (destruction != null && cleanupRenderDestruction == null) cleanupRenderDestruction = destruction
                    cleanupWorkPending = true
                }
                if (destruction != null) {
                    destruction.submit()
                    serviceWakeLink(destruction.deadlineWakeLink)
                }
            }
        }
        sessionGate.withLock { pendingJpegPreparation }?.let { occurrence ->
            occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
            val returned = occurrence.operation.settlementGate.withLock {
                occurrence.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (returned) {
                sessionGate.withLock { cleanupOwner.jpegRoot.owner }?.installPrepared(occurrence)
                sessionGate.withLock {
                    if (pendingJpegPreparation === occurrence) pendingJpegPreparation = null
                    cleanupWorkPending = true
                }
            }
        }
        sessionGate.withLock { pendingFrameworkCreation }?.let { occurrence ->
            occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
            val returned = occurrence.operation.settlementGate.withLock {
                occurrence.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (returned) {
                recycleReturnedFrameworkOwner(occurrence)
                sessionGate.withLock {
                    if (pendingFrameworkCreation === occurrence) pendingFrameworkCreation = null
                    cleanupWorkPending = true
                }
            }
        }
    }

    private fun OperationArbitration.isNormalReturn(): Boolean =
        this == OperationArbitration.TimelyNormal || this == OperationArbitration.ExpiredNormal ||
                this == OperationArbitration.CleanupNormal

    private fun recordCleanupMutationLocked(mutation: CleanupMutation): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (mutation == CleanupMutation.QuarantineAttached || mutation == CleanupMutation.QuarantineReduced) {
            pendingQuarantineDiagnostics = saturatedIncrement(pendingQuarantineDiagnostics)
            cleanupWorkPending = true
        }
        return mutation != CleanupMutation.None
    }

    private fun publishPendingQuarantineDiagnostics() {
        val count = sessionGate.withLock {
            val value = pendingQuarantineDiagnostics
            pendingQuarantineDiagnostics = 0L
            value
        }
        var remaining = count
        while (remaining > 0L) {
            emitDiagnostic(
                source = "Cleanup",
                label = "QuarantineChanged",
                message = "permanent cleanup quarantine root changed",
                cause = null,
            )
            remaining--
        }
    }

    private fun reserveTerminalOperationIdentity(): Long {
        return sessionGate.withLock { reserveIdentityLocked() }
    }

    private fun reserveTerminalGlIdentity(timeoutCause: Throwable): GlFiniteOperationIdentity {
        val operation = reserveTerminalOperationIdentity()
        val deadline = reserveTerminalOperationIdentity()
        val wake = reserveTerminalOperationIdentity()
        return GlFiniteOperationIdentity(operation, deadline, wake, timeoutCause)
    }

    private fun advanceTerminalGlCleanup(owner: GlPipelineOwner) {
        val targetCleanupPending = sessionGate.withLock {
            cleanupOwner.preparedTargetRoot.target != null || cleanupOwner.currentTargetRoot.target != null ||
                    cleanupOwner.quarantineRoot.targetChild != null
        }
        if (targetCleanupPending) return
        val partial = sessionGate.withLock { glSessionCommand }
        if (partial != null) {
            serviceWakeLink(partial.partialCleanupDeadlineWakeLink)
            val facts = partial.claimPartialCleanup() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupOwner.quarantineGl(owner)) }
                return
            }
            sessionGate.withLock {
                if (glSessionCommand === partial) {
                    glSessionCommand = null
                    cleanupWorkPending = true
                }
            }
        }

        val program = sessionGate.withLock { cleanupProgramDestruction }
        if (program != null) {
            serviceWakeLink(program.deadlineWakeLink)
            val facts = program.claim() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupOwner.quarantineGl(owner)) }
                return
            }
            val exact = program as? GlDestructionHandle ?: return
            owner.clearProgramDestruction(exact)
            sessionGate.withLock {
                if (cleanupProgramDestruction === program) {
                    cleanupProgramDestruction = null
                    cleanupWorkPending = true
                }
            }
        } else {
            val command = owner.prepareProgramDestruction(reserveTerminalGlIdentity(PROGRAM_DESTRUCTION_TIMEOUT))
            if (command != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.glRoot.owner !== owner || cleanupProgramDestruction != null) return@withLock false
                    cleanupProgramDestruction = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
                return
            }
        }

        val session = sessionGate.withLock { cleanupSessionDestruction }
        if (session != null) {
            serviceWakeLink(session.deadlineWakeLink)
            val facts = session.claim() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupOwner.quarantineGl(owner)) }
                return
            }
            sessionGate.withLock {
                if (cleanupSessionDestruction === session) {
                    cleanupSessionDestruction = null
                    cleanupWorkPending = true
                }
            }
        } else {
            val command = owner.prepareHealthySessionDestruction(reserveTerminalGlIdentity(SESSION_DESTRUCTION_TIMEOUT))
            if (command != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.glRoot.owner !== owner || cleanupSessionDestruction != null) return@withLock false
                    cleanupSessionDestruction = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
            }
        }
    }

    private fun advanceTerminalFrameworkCleanup() {
        val owner = sessionGate.withLock { cleanupOwner.jpegRoot.frameworkOwner } ?: return
        val existing = sessionGate.withLock { cleanupFrameworkRecycle }
        if (existing != null) {
            when (owner.settleRecycle(existing)) {
                FrameworkBitmapRecycleSettlement.NotSettled -> return
                FrameworkBitmapRecycleSettlement.CleanupCompleted -> sessionGate.withLock {
                    if (cleanupFrameworkRecycle === existing) cleanupFrameworkRecycle = null
                    if (cleanupOwner.reduceFramework(owner) != CleanupMutation.None) cleanupWorkPending = true
                }

                FrameworkBitmapRecycleSettlement.ReplacementAuthorized,
                FrameworkBitmapRecycleSettlement.UnsafeResidue,
                    -> sessionGate.withLock {
                    recordCleanupMutationLocked(cleanupOwner.quarantineFramework(owner))
                    cleanupOwner.jpegRoot.owner?.let {
                        recordCleanupMutationLocked(cleanupOwner.quarantineJpeg(it))
                    }
                }
            }
            return
        }
        val occurrence = owner.beginTerminalRecycle(
            desiredRevision,
            geometryGeneration,
            lifecycleEpoch,
            reserveTerminalOperationIdentity(),
        ) ?: return
        sessionGate.withLock {
            if (cleanupOwner.jpegRoot.frameworkOwner === owner && cleanupFrameworkRecycle == null) {
                cleanupFrameworkRecycle = occurrence
            }
        }
    }

    private fun advanceTerminalJpegProductCleanup() {
        val owner = sessionGate.withLock { cleanupOwner.jpegRoot.owner } ?: return
        if (sessionGate.withLock { cleanupOwner.jpegRoot.frameworkOwner != null }) return
        val existing = sessionGate.withLock { cleanupNativeCarrierFree }
        if (existing != null) {
            when (owner.settleNativeCarrierFree(existing)) {
                NativeCarrierFreeSettlement.NotSettled -> return
                NativeCarrierFreeSettlement.CleanupCompleted -> sessionGate.withLock {
                    if (cleanupNativeCarrierFree === existing) {
                        cleanupNativeCarrierFree = null
                        cleanupWorkPending = true
                    }
                }

                NativeCarrierFreeSettlement.ReplacementAuthorized,
                NativeCarrierFreeSettlement.UnsafeResidue,
                    -> sessionGate.withLock { recordCleanupMutationLocked(cleanupOwner.quarantineJpeg(owner)) }
            }
            return
        }
        val product = owner.stableTopologySnapshot()?.product ?: return
        when (product) {
            is JpegRuntimeProduct.NativeEnabled,
            is JpegRuntimeProduct.FrameworkOnNativeCarrier,
                -> {
                val occurrence = owner.beginTerminalNativeCarrierFree(
                    product,
                    desiredRevision,
                    geometryGeneration,
                    lifecycleEpoch,
                    reserveTerminalOperationIdentity(),
                ) ?: return
                sessionGate.withLock {
                    if (cleanupOwner.jpegRoot.owner === owner && cleanupNativeCarrierFree == null) {
                        cleanupNativeCarrierFree = occurrence
                    }
                }
            }

            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> {
                if (owner.detachManagedForReplacement(product) && owner.discardReplacementAuthorizationForTerminal(product)) {
                    sessionGate.withLock { cleanupWorkPending = true }
                }
            }
        }
    }

    private fun advancePreparedTargetCleanup() {
        val target = sessionGate.withLock { cleanupOwner.preparedTargetRoot.target } ?: return
        val command = sessionGate.withLock { preparedTargetCommand } ?: return
        serviceWakeLink(command.deadlineWakeLink)
        val token = claimPreparedTargetFold() ?: return
        command.retireAfterTargetArbitration()
        selectAndApplyPreparedTargetFold(token) ?: return
        val cleanupTarget = token.cleanupTarget ?: return
        sessionGate.withLock {
            cleanupOwner.reducePreparedTargetProvenComplete(target)
            if (cleanupOwner.attachCurrentTarget(cleanupTarget) != CleanupMutation.None) cleanupWorkPending = true
            if (preparedTargetCommand === command) preparedTargetCommand = null
        }
    }

    private fun advanceTerminalTargetCleanup(target: CurrentTarget) {
        target.recordRetirementAdmissionClosed()
        target.recordEnteredTargetWorkDrained()
        target.fenceGeneration()

        val pendingRender = sessionGate.withLock { pendingRenderConstruction }
        if (pendingRender != null) {
            serviceWakeLink(pendingRender.deadlineWakeLink)
            val claim = pendingRender.claim() ?: return
            val cleanup = pendingRender.claimCleanupDestruction(claim)?.destructionCommand
            sessionGate.withLock {
                if (pendingRenderConstruction === pendingRender) pendingRenderConstruction = null
                if (cleanup != null && cleanupRenderDestruction == null) cleanupRenderDestruction = cleanup
                cleanupWorkPending = true
            }
            if (cleanup != null) {
                cleanup.submit()
                serviceWakeLink(cleanup.deadlineWakeLink)
            }
            return
        }

        val listener = sessionGate.withLock { cleanupListenerRemoval }
        val android = sessionGate.withLock { cleanupOwner.androidRoot.owner ?: androidOwner }
        if (listener != null) {
            val returned = listener.settlementGate.withLock {
                listener.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            val bag = listener.ownerBag as AndroidTargetListenerRemovalOwnerBag
            val applied = bag.target.applyListenerRemovalSettlement(bag.port, listener)
            sessionGate.withLock {
                if (cleanupListenerRemoval === listener) cleanupListenerRemoval = null
                cleanupWorkPending = true
            }
            if (!applied) return
        } else {
            val snapshot = target.currentnessSnapshot()
            if (snapshot.listenerInstalled) {
                val exactAndroid = android ?: return
                val operationIdentity = reserveTerminalOperationIdentity()
                val operation = exactAndroid.createTargetListenerRemovalOperation(target, operationIdentity, null) ?: return
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.currentTargetRoot.target !== target || cleanupListenerRemoval != null) {
                        return@withLock false
                    }
                    cleanupListenerRemoval = operation
                    true
                }
                if (rooted) exactAndroid.submitTargetListenerRemoval(operation)
                return
            }
        }

        val virtualDisplayRelease = sessionGate.withLock { cleanupVirtualDisplayRelease }
        if (virtualDisplayRelease != null) {
            val returned = virtualDisplayRelease.settlementGate.withLock {
                virtualDisplayRelease.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            if (virtualDisplayRelease.returnCell.disposition == OperationReturnDisposition.Normal) {
                val bag = virtualDisplayRelease.ownerBag as AndroidVirtualDisplayReleaseOwnerBag
                when (val mode = bag.mode) {
                    is AndroidVirtualDisplayReleaseMode.Attached -> {
                        val candidate = mode.ownership.target.producerDetachApplicationCandidateAfterSettlement(
                            mode.targetPort,
                            virtualDisplayRelease,
                        )
                        val receipt = candidate?.let(mode.ownership.target::applyProducerDetachApplication)
                        if (receipt != null) android?.applyAttachedVirtualDisplayReleaseTargetFact(
                            virtualDisplayRelease,
                            receipt,
                        )
                    }

                    is AndroidVirtualDisplayReleaseMode.MechanicallyDetached ->
                        android?.completeMechanicallyDetachedVirtualDisplayRelease(virtualDisplayRelease)
                }
            }
            sessionGate.withLock {
                if (cleanupVirtualDisplayRelease === virtualDisplayRelease) cleanupVirtualDisplayRelease = null
                cleanupWorkPending = true
            }
        } else {
            val exactAndroid = android ?: return
            val operation = exactAndroid.createVirtualDisplayReleaseOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.currentTargetRoot.target !== target || cleanupVirtualDisplayRelease != null) {
                        return@withLock false
                    }
                    cleanupVirtualDisplayRelease = operation
                    true
                }
                if (rooted) exactAndroid.submitVirtualDisplayRelease(operation)
                return
            }
        }

        val gl = sessionGate.withLock { cleanupOwner.glRoot.owner ?: glOwner } ?: return
        val renderCommand = sessionGate.withLock { cleanupRenderDestruction }
        if (renderCommand != null) {
            serviceWakeLink(renderCommand.deadlineWakeLink)
            val claim = renderCommand.claim() ?: return
            if (claim.result != GlOperationResult.Success) return
            sessionGate.withLock {
                if (cleanupRenderDestruction === renderCommand) {
                    cleanupRenderDestruction = null
                    cleanupRenderOwner = null
                    cleanupWorkPending = true
                }
            }
        } else {
            val render = sessionGate.withLock { cleanupRenderOwner }
            if (render != null) {
                val command = gl.prepareRenderTargetDestruction(render) ?: return
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.currentTargetRoot.target !== target || cleanupRenderOwner !== render ||
                        cleanupRenderDestruction != null
                    ) {
                        return@withLock false
                    }
                    cleanupRenderDestruction = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
                return
            }
        }

        val surfaceCommand = sessionGate.withLock { cleanupSurfaceRelease }
        if (surfaceCommand != null) {
            serviceWakeLink(surfaceCommand.deadlineWakeLink)
            val claim = surfaceCommand.claim() ?: return
            if (claim.receipt.operationIdentity != target.surfaceReleaseOccurrence.identity) return
            target.settleConstructionResourceObligations()
            sessionGate.withLock {
                if (cleanupSurfaceRelease === surfaceCommand) {
                    cleanupSurfaceRelease = null
                    cleanupWorkPending = true
                }
            }
        } else if (!target.hasAppliedSurfaceReleaseReceipt) {
            if (!target.isSurfaceReleaseReady) return
            val command = gl.prepareSurfaceRelease(target) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupOwner.currentTargetRoot.target !== target || cleanupSurfaceRelease != null) return@withLock false
                cleanupSurfaceRelease = command
                true
            }
            if (rooted) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }

        val targetCommand = sessionGate.withLock { cleanupTargetScope }
        if (targetCommand != null) {
            serviceWakeLink(targetCommand.deadlineWakeLink)
            serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
            if (!cleanupTargetNamespaceSubmitted) {
                val claim = targetCommand.claim() ?: return
                if (claim.result == GlOperationResult.Success && target.isFullyRetired) {
                    sessionGate.withLock {
                        if (cleanupTargetScope === targetCommand) cleanupTargetScope = null
                    }
                } else if (targetCommand.submitNamespaceRetirement()) {
                    cleanupTargetNamespaceSubmitted = true
                    serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
                    return
                } else {
                    return
                }
            } else {
                targetCommand.claimNamespaceRetirement() ?: return
                sessionGate.withLock {
                    if (cleanupTargetScope === targetCommand) {
                        cleanupTargetScope = null
                        cleanupTargetNamespaceSubmitted = false
                    }
                }
            }
        } else if (!target.isFullyRetired) {
            val targetIdentity = sessionGate.withLock { preparedTargetDestructionIdentity } ?: return
            val namespaceIdentity = sessionGate.withLock { preparedNamespaceDestructionIdentity } ?: return
            val command = gl.prepareTargetScopeDestruction(target, targetIdentity, namespaceIdentity) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupOwner.currentTargetRoot.target !== target || cleanupTargetScope != null) return@withLock false
                cleanupTargetScope = command
                cleanupTargetNamespaceSubmitted = false
                true
            }
            if (rooted) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }

        if (target.isFullyRetired) {
            sessionGate.withLock {
                if (cleanupOwner.reduceCurrentTargetProvenComplete(target) != CleanupMutation.None) cleanupWorkPending = true
            }
        }
    }

    private fun advanceTerminalAndroidCleanup(owner: AndroidCaptureOwner) {
        owner.closeProjectionCallbackAuthority()

        val unregister = sessionGate.withLock { cleanupProjectionUnregistration }
        if (unregister != null) {
            val returned = unregister.settlementGate.withLock {
                unregister.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            sessionGate.withLock {
                if (cleanupProjectionUnregistration === unregister) cleanupProjectionUnregistration = null
                cleanupWorkPending = true
            }
        } else {
            val operation = owner.createProjectionCallbackUnregistrationOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.androidRoot.owner !== owner || cleanupProjectionUnregistration != null) {
                        return@withLock false
                    }
                    cleanupProjectionUnregistration = operation
                    true
                }
                if (rooted) owner.submitProjectionCallbackUnregistration(operation)
                return
            }
        }

        val release = sessionGate.withLock { cleanupVirtualDisplayRelease }
        if (release != null) {
            val returned = release.settlementGate.withLock {
                release.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            if (release.returnCell.disposition == OperationReturnDisposition.Normal) {
                val bag = release.ownerBag as AndroidVirtualDisplayReleaseOwnerBag
                when (val mode = bag.mode) {
                    is AndroidVirtualDisplayReleaseMode.Attached -> {
                        val candidate = mode.ownership.target.producerDetachApplicationCandidateAfterSettlement(
                            mode.targetPort,
                            release,
                        )
                        val receipt = candidate?.let(mode.ownership.target::applyProducerDetachApplication)
                        if (receipt != null) owner.applyAttachedVirtualDisplayReleaseTargetFact(release, receipt)
                    }

                    is AndroidVirtualDisplayReleaseMode.MechanicallyDetached ->
                        owner.completeMechanicallyDetachedVirtualDisplayRelease(release)
                }
            }
            sessionGate.withLock {
                if (cleanupVirtualDisplayRelease === release) cleanupVirtualDisplayRelease = null
                cleanupWorkPending = true
            }
        } else {
            val operation = owner.createVirtualDisplayReleaseOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupOwner.androidRoot.owner !== owner || cleanupVirtualDisplayRelease != null) {
                        return@withLock false
                    }
                    cleanupVirtualDisplayRelease = operation
                    true
                }
                if (rooted) owner.submitVirtualDisplayRelease(operation)
                return
            }
        }

        val stop = sessionGate.withLock { cleanupProjectionStop }
        if (stop != null) {
            val returned = stop.settlementGate.withLock {
                stop.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            sessionGate.withLock {
                if (cleanupProjectionStop === stop) cleanupProjectionStop = null
                cleanupWorkPending = true
            }
        } else if (!owner.isLaneQuitReady) {
            val operation = owner.createProjectionStopOperation(reserveTerminalOperationIdentity()) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupOwner.androidRoot.owner !== owner || cleanupProjectionStop != null) return@withLock false
                cleanupProjectionStop = operation
                true
            }
            if (rooted) owner.submitProjectionStop(operation)
        }
    }

    private fun reserveRunningPublicationLocked(problem: ScreenCaptureProblem? = null) {
        check(sessionGate.isHeldByCurrentThread)
        if (requestedParameters == null || lastEffectiveParameters == null) return
        runningPublicationPending = true
        runningPublicationProblem = problem
    }

    private fun reserveDiagnosticSequenceLocked(): Long {
        check(sessionGate.isHeldByCurrentThread)
        if (nextDiagnosticSequence == Long.MAX_VALUE) {
            offerFailureLocked(ScreenCaptureProblem.InternalFailure, DIAGNOSTIC_SEQUENCE_EXHAUSTED)
            return 0L
        }
        return nextDiagnosticSequence++
    }

    private fun emitDiagnostic(source: String, label: String, message: String, cause: Throwable?) {
        val sequence = sessionGate.withLock { reserveDiagnosticSequenceLocked() }
        if (sequence == 0L) return
        val request = ObservationDiagnosticRequest(sequence, wallClockMillis(), source, label, message, cause)
        observationOwner.tryEmitDiagnostic(request)
    }

    private fun hasImmediateControllerWork(): Boolean =
        latestResize.get() != null || latestVisibility.get() != null || captureEnded.get() != null ||
                sessionGate.withLock {
                    cleanupWorkPending || !terminalCutoffApplied && terminalContenders.any { it.present }
                }

    private fun closeAdmissionLocked() {
        check(sessionGate.isHeldByCurrentThread)
        admissionsClosed = true
        targetAdmissionClosePending = true
    }

    private fun invalidateOutputLocked() {
        check(sessionGate.isHeldByCurrentThread)
        acceptedTopologySnapshot = null
        topologyPublicationIdentity = if (nextIdentity == Long.MAX_VALUE) Long.MAX_VALUE else reserveIdentityLocked()
    }

    private fun invalidateReconciliationForTopologyMutationLocked() {
        check(sessionGate.isHeldByCurrentThread)
        reconciliationIdentity = 0L
        currentCalculation = null
        currentProvisional = null
        currentPlan = currentTarget?.plan
    }

    private fun advanceLifecycleEpochLocked() {
        check(sessionGate.isHeldByCurrentThread)
        lifecycleEpoch = reserveIdentityLocked()
    }

    private fun reserveIdentityLocked(): Long {
        check(sessionGate.isHeldByCurrentThread)
        if (nextIdentity <= 0L || nextIdentity == Long.MAX_VALUE) {
            if (!terminalWinner.fixed) offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
            return Long.MAX_VALUE
        }
        return nextIdentity++
    }

    private fun contenderLocked(kind: TerminalKind): TerminalContender? {
        check(sessionGate.isHeldByCurrentThread)
        return terminalContenders.firstOrNull { it.present && it.kind == kind }
    }

    private fun saturatingIncrementFailureLocked() {
        check(sessionGate.isHeldByCurrentThread)
        statsAuthority.byFailure = saturatedIncrement(statsAuthority.byFailure)
    }

    private fun poisonControl(raw: Throwable) {
        controlPoison.compareAndSet(null, raw)
    }

    private fun emergencyFailClosed(raw: Throwable) {
        emergencyTurnSlots.clear()
        val shouldApply = sessionGate.withLock {
            closeAdmissionLocked()
            offerFailureLocked(ScreenCaptureProblem.InternalFailure, raw)
            chooseTerminalWinnerLocked()
            if (!terminalCutoffApplied) {
                applyTerminalCutoffLocked(emergencyTurnSlots)
                true
            } else {
                false
            }
        }
        if (shouldApply) executeTurnUnlocked(emergencyTurnSlots)
    }

    override fun onControlTaskPoison(raw: Throwable) {
        poisonControl(raw)
    }

    override fun onControlTaskPostStackPoison(raw: Throwable) {
        poisonControl(raw)
        drainerState.set(DRAIN_IDLE)
        signal()
    }

    override fun onControlTaskDirectFatal(
        @Suppress("UNUSED_PARAMETER") record: ControlScheduledTaskRecord,
        raw: Throwable,
    ) {
        poisonControl(raw)
    }

    override fun onControlSchedulerTerminated(receipt: SessionControlTerminationReceipt) {
        if (controlTermination.compareAndSet(null, receipt)) {
            val links = sessionGate.withLock { fixedControlWakeInventoryLocked() }
            links.forEach(ControlWakeLink::publishSchedulerTermination)
        }
        preBarrierDirty.set(true)
    }

    private fun fixedControlWakeInventoryLocked(): List<ControlWakeLink> {
        check(sessionGate.isHeldByCurrentThread)
        val links = LinkedHashSet<ControlWakeLink>()
        fun add(link: ControlWakeLink?) {
            if (link != null) links.add(link)
        }
        add(metricsOwner?.readinessWakeLink)
        add(pendingStartupBag?.metrics?.readinessWakeLink)
        add(cleanupOwner.metricsRoot.owner?.readinessWakeLink)
        add(cleanupOwner.quarantineRoot.metrics?.readinessWakeLink)
        add(glSessionCommand?.deadlineWakeLink)
        add(glSessionCommand?.partialCleanupDeadlineWakeLink)
        add(pendingProjectionRegistration?.controlWakeLink)
        add(preparedTargetCommand?.deadlineWakeLink)
        add(pendingListenerInstallation?.controlWakeLink)
        add(pendingVirtualDisplayCreation?.controlWakeLink)
        add(pendingVirtualDisplayCreation?.returnCell?.evidence?.initialResizeDeadlineOccurrence?.controlWakeLink)
        add(pendingRenderConstruction?.deadlineWakeLink)
        add(pendingJpegPreparation?.operation?.controlWakeLink)
        add(pendingFrameworkCreation?.operation?.controlWakeLink)
        add(pendingNativeEncode?.operation?.controlWakeLink)
        add(cleanupProjectionUnregistration?.controlWakeLink)
        add(cleanupVirtualDisplayRelease?.controlWakeLink)
        add(cleanupProjectionStop?.controlWakeLink)
        add(cleanupListenerRemoval?.controlWakeLink)
        add(cleanupRenderDestruction?.deadlineWakeLink)
        add(cleanupSurfaceRelease?.deadlineWakeLink)
        add(cleanupTargetScope?.deadlineWakeLink)
        add(cleanupTargetScope?.namespaceDeadlineWakeLink)
        add(cleanupFrameworkRecycle?.operation?.controlWakeLink)
        add(cleanupProgramDestruction?.deadlineWakeLink)
        add(cleanupSessionDestruction?.deadlineWakeLink)
        add(cleanupNativeCarrierFree?.operation?.controlWakeLink)
        return links.toList()
    }

    internal fun constructOwnersForAcceptedStart(): Boolean {
        val bag = sessionGate.withLock {
            val current = pendingStartupBag ?: return@withLock null
            if (current.constructionClaimed || current.constructionComplete || lifecycle != Lifecycle.Starting ||
                terminalWinner.fixed || admissionsClosed
            ) {
                return@withLock null
            }
            current.constructionClaimed = true
            current
        } ?: return false

        try {
            val metrics = SessionStartupTopology.constructMetrics(
                SessionMetricsConstructionCommand(
                    applicationContext = applicationContext,
                    source = metricsSource,
                    sessionGate = sessionGate,
                    clock = clock,
                    signal = this,
                    attachmentIdentity = bag.metricsAttachmentIdentity,
                    deadlineIdentity = bag.metricsDeadlineIdentity,
                    wakeIdentity = bag.metricsWakeIdentity,
                    timeoutCause = METRICS_READINESS_TIMEOUT,
                    closeIdentity = bag.metricsCloseIdentity,
                ),
            )
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.metrics == null) bag.metrics = metrics else cleanupOwner.attachMetrics(metrics)
            }

            val android = SessionStartupTopology.constructAndroid(
                SessionAndroidConstructionCommand(
                    projection = checkNotNull(bag.projection),
                    projectionOwnerEpoch = bag.projectionOwnerEpoch,
                    callbackIdentity = bag.callbackIdentity,
                    clock = clock,
                    signal = this,
                    factSink = this,
                ),
            )
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.android == null) bag.android = android else cleanupOwner.attachAndroid(android)
            }

            val gl = SessionStartupTopology.constructGl(clock, this)
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.gl == null) bag.gl = gl else cleanupOwner.attachGl(gl)
            }

            val jpeg = SessionStartupTopology.constructJpeg(clock, this)
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.jpeg == null) bag.jpeg = jpeg else cleanupOwner.attachJpeg(jpeg)
            }

            val storage = SessionStartupTopology.constructStorage()
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.storage == null) bag.storage = storage else cleanupOwner.attachStorage(storage)
                if (pendingStartupBag === bag && bag.isComplete) bag.constructionComplete = true
            }
        } catch (raw: Throwable) {
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return false
        }
        signal()
        return true
    }

    private fun prestartAndAdoptStartupOwners() {
        var metrics: CaptureMetricsOwner? = null
        var android: AndroidCaptureOwner? = null
        var gl: GlPipelineOwner? = null
        var jpeg: JpegRuntimeOwner? = null
        var storage: EncodedStorageOwner? = null
        val bag = sessionGate.withLock {
            val current = pendingStartupBag ?: return@withLock null
            if (!current.constructionComplete || current.prestartClaimed || !current.isComplete ||
                lifecycle != Lifecycle.Starting || terminalWinner.fixed || admissionsClosed
            ) {
                return@withLock null
            }
            current.prestartClaimed = true
            metrics = current.metrics
            android = current.android
            gl = current.gl
            jpeg = current.jpeg
            storage = current.storage
            current
        } ?: return

        try {
            val facts = SessionStartupTopology.prestart(
                SessionStartupPrestartCommand(checkNotNull(metrics), checkNotNull(gl), checkNotNull(jpeg)),
            )
            if (!facts.ready) {
                offerFailure(ScreenCaptureProblem.InternalFailure, facts.failure)
                return
            }
        } catch (raw: Throwable) {
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return
        }

        val adopted = sessionGate.withLock {
            if (pendingStartupBag !== bag || lifecycle != Lifecycle.Starting || terminalWinner.fixed || admissionsClosed ||
                metricsOwner != null || androidOwner != null || glOwner != null || jpegOwner != null || storageOwner != null
            ) {
                return@withLock false
            }
            metricsOwner = metrics
            androidOwner = android
            glOwner = gl
            jpegOwner = jpeg
            storageOwner = storage
            pendingStartupBag = null
            true
        }
        if (adopted) signal()
    }

    private fun launchOwnersForAcceptedStart() {
        var metrics: CaptureMetricsOwner? = null
        var android: AndroidCaptureOwner? = null
        var gl: GlPipelineOwner? = null
        var constructionOperation = 0L
        var constructionDeadline = 0L
        var constructionWake = 0L
        var cleanupOperation = 0L
        var cleanupDeadline = 0L
        var cleanupWake = 0L
        val reserved = sessionGate.withLock {
            if (lifecycle != Lifecycle.Starting || terminalWinner.fixed || admissionsClosed || ownersLaunched) {
                return@withLock false
            }
            metrics = metricsOwner ?: return@withLock false
            android = androidOwner ?: return@withLock false
            gl = glOwner ?: return@withLock false
            constructionOperation = reserveIdentityLocked()
            constructionDeadline = reserveIdentityLocked()
            constructionWake = reserveIdentityLocked()
            cleanupOperation = reserveIdentityLocked()
            cleanupDeadline = reserveIdentityLocked()
            cleanupWake = reserveIdentityLocked()
            ownersLaunched = true
            true
        }
        if (!reserved) return
        val input = SessionStartupLaunchCommand(
            metrics = checkNotNull(metrics),
            android = checkNotNull(android),
            gl = checkNotNull(gl),
            construction = GlFiniteOperationIdentity(
                constructionOperation,
                constructionDeadline,
                constructionWake,
                GL_SESSION_CONSTRUCTION_TIMEOUT,
            ),
            partialCleanup = GlFiniteOperationIdentity(
                cleanupOperation,
                cleanupDeadline,
                cleanupWake,
                GL_PARTIAL_SESSION_CLEANUP_TIMEOUT,
            ),
        )

        try {
            val facts = SessionStartupTopology.launch(input)
            if (!facts.androidStartAccepted && facts.androidStartup is AndroidLaneStartupResult.Failed) {
                offerFailure(ScreenCaptureProblem.InternalFailure, facts.androidStartup.cause)
                return
            }
            val command = checkNotNull(facts.glCommand)
            val accepted = sessionGate.withLock {
                if (terminalWinner.fixed || admissionsClosed || glOwner !== input.gl || glSessionCommand != null) {
                    return@withLock false
                }
                glSessionCommand = command
                true
            }
            if (!accepted) return
            command.submit()
        } catch (raw: Throwable) {
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
        }
    }

    private fun consumeGlSessionConstruction() {
        val command = sessionGate.withLock { glSessionCommand } ?: return
        serviceWakeLink(command.deadlineWakeLink)
        serviceWakeLink(command.partialCleanupDeadlineWakeLink)
        val facts = command.claim() ?: return
        val accepted = installGlSessionFacts(facts)
        if (!accepted) {
            command.submitPartialCleanup()
            serviceWakeLink(command.partialCleanupDeadlineWakeLink)
            val problem = if (facts.result == GlOperationResult.ResourceExhausted) {
                ScreenCaptureProblem.ResourceExhausted
            } else {
                ScreenCaptureProblem.InternalFailure
            }
            offerFailure(problem, facts.throwable)
        } else {
            sessionGate.withLock {
                if (glSessionCommand === command) glSessionCommand = null
            }
        }
    }

    private fun advanceProjectionRegistration() {
        val existing = sessionGate.withLock { pendingProjectionRegistration }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            when (val arbitration = existing.arbitrate()) {
                OperationArbitration.None -> return
                OperationArbitration.TimelyNormal -> {
                    val accepted = sessionGate.withLock {
                        if (pendingProjectionRegistration !== existing || terminalWinner.fixed || admissionsClosed ||
                            androidOwner == null
                        ) {
                            return@withLock false
                        }
                        acceptedProjectionCallbackRegistrationIdentity = existing.identity
                        pendingProjectionRegistration = null
                        true
                    }
                    if (accepted) signal()
                }

                else -> {
                    sessionGate.withLock {
                        if (pendingProjectionRegistration === existing) pendingProjectionRegistration = null
                    }
                    offerFailure(
                        if (arbitration == OperationArbitration.TimelyThrown) {
                            ScreenCaptureProblem.InternalFailure
                        } else {
                            ScreenCaptureProblem.CaptureUnavailable
                        },
                        existing.returnCell.throwable,
                    )
                }
            }
            return
        }

        val owner = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || acceptedProjectionCallbackRegistrationIdentity != 0L ||
                pendingProjectionRegistration != null || metricsJointReadiness == null ||
                androidLaneReadyOwner !== androidOwner
            ) {
                return@withLock null
            }
            androidOwner
        } ?: return
        val identity = reserveAndroidIdentity(PROJECTION_CALLBACK_REGISTRATION_TIMEOUT) ?: return
        val operation = owner.createProjectionCallbackRegistrationOperation(identity) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, PROJECTION_CALLBACK_REGISTRATION_COLLISION)
            return
        }
        val installed = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || androidOwner !== owner || pendingProjectionRegistration != null) {
                return@withLock false
            }
            pendingProjectionRegistration = operation
            true
        }
        if (!installed) return
        owner.submitProjectionCallbackRegistration(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceTargetConstruction() {
        val existingCommand = sessionGate.withLock { preparedTargetCommand }
        if (existingCommand != null) {
            serviceWakeLink(existingCommand.deadlineWakeLink)
            val token = claimPreparedTargetFold() ?: return
            existingCommand.retireAfterTargetArbitration()
            selectAndApplyPreparedTargetFold(token)
            sessionGate.withLock {
                if (preparedTargetCommand === existingCommand) preparedTargetCommand = null
            }
            return
        }

        var plan: TargetPlan? = null
        var desired = 0L
        var geometry = 0L
        var epoch = 0L
        var reconciliation = 0L
        var owner: GlPipelineOwner? = null
        val selected = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || acceptedProjectionCallbackRegistrationIdentity == 0L ||
                currentTarget != null || preparedTarget != null || preparedTargetCommand != null || glSessionFacts == null
            ) {
                return@withLock false
            }
            plan = currentPlan ?: return@withLock false
            desired = desiredRevision
            geometry = geometryGeneration
            epoch = lifecycleEpoch
            reconciliation = reconciliationIdentity
            owner = glOwner ?: return@withLock false
            true
        }
        if (!selected) return

        val construction = reserveGlIdentity(TARGET_CONSTRUCTION_TIMEOUT) ?: return
        val listener = reserveAndroidIdentity(TARGET_LISTENER_INSTALLATION_TIMEOUT) ?: return
        val surfaceRelease = reserveAndroidIdentity(TARGET_SURFACE_RELEASE_TIMEOUT) ?: return
        val targetDestruction = reserveGlIdentity(TARGET_DESTRUCTION_TIMEOUT) ?: return
        val namespaceDestruction = reserveGlIdentity(TARGET_NAMESPACE_DESTRUCTION_TIMEOUT) ?: return
        val candidate = targetOwner.prepareTarget(
            plan = checkNotNull(plan),
            desiredRevision = desired,
            geometryGeneration = geometry,
            lifecycleEpoch = epoch,
            reconciliationIdentity = reconciliation,
            constructionIdentity = construction,
            listenerInstallationOperationIdentity = listener.operationIdentity,
            sourceSignal = TargetSourceSignal { _ -> signal() },
            clock = clock,
            settlementSignal = this,
            surfaceReleaseOperationIdentity = surfaceRelease.operationIdentity,
            surfaceReleaseDeadlineIdentity = surfaceRelease.deadlineIdentity,
            surfaceReleaseDeadlineWakeGeneration = surfaceRelease.deadlineWakeGeneration,
            surfaceReleaseTimeoutCause = surfaceRelease.timeoutCause,
            targetDestructionIdentity = targetDestruction,
            namespaceDestructionIdentity = namespaceDestruction,
        ) ?: return
        val command = checkNotNull(owner).prepareTargetConstruction(candidate)
        if (!admitPreparedTarget(candidate)) return
        val rooted = sessionGate.withLock {
            if (preparedTarget !== candidate || terminalWinner.fixed || admissionsClosed || preparedTargetCommand != null) {
                return@withLock false
            }
            preparedTargetListenerIdentity = listener
            preparedTargetDestructionIdentity = targetDestruction
            preparedNamespaceDestructionIdentity = namespaceDestruction
            preparedTargetCommand = command
            true
        }
        if (!rooted) return
        command.submit()
        serviceWakeLink(command.deadlineWakeLink)
    }

    private fun advanceListenerInstallation() {
        val existing = sessionGate.withLock { pendingListenerInstallation }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            when (val arbitration = existing.arbitrate()) {
                OperationArbitration.None -> return
                OperationArbitration.TimelyNormal -> {
                    val bag = existing.ownerBag as AndroidTargetListenerInstallationOwnerBag
                    val applied = bag.target.applyListenerInstallationReceipt(bag.port, existing)
                    sessionGate.withLock {
                        if (pendingListenerInstallation === existing) pendingListenerInstallation = null
                    }
                    if (!applied) {
                        offerFailure(ScreenCaptureProblem.InternalFailure, TARGET_LISTENER_RECEIPT_REJECTED)
                    } else {
                        signal()
                    }
                }

                else -> {
                    sessionGate.withLock {
                        if (pendingListenerInstallation === existing) pendingListenerInstallation = null
                    }
                    offerFailure(ScreenCaptureProblem.CaptureUnavailable, existing.returnCell.throwable)
                }
            }
            return
        }

        var target: CurrentTarget? = null
        var owner: AndroidCaptureOwner? = null
        var identity: AndroidFiniteOperationIdentity? = null
        val selected = sessionGate.withLock {
            target = currentTarget
            owner = androidOwner
            identity = preparedTargetListenerIdentity
            !terminalWinner.fixed && !admissionsClosed && target != null && owner != null && identity != null
        }
        if (!selected || checkNotNull(target).currentnessSnapshot().listenerInstalled) return
        val operation = checkNotNull(owner).createTargetListenerInstallationOperation(
            checkNotNull(target),
            checkNotNull(identity),
        ) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, TARGET_LISTENER_OPERATION_REJECTED)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || currentTarget !== target || androidOwner !== owner ||
                preparedTargetListenerIdentity !== identity || pendingListenerInstallation != null
            ) {
                return@withLock false
            }
            pendingListenerInstallation = operation
            true
        }
        if (!rooted) return
        checkNotNull(owner).submitTargetListenerInstallation(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceVirtualDisplayCreation() {
        val existing = sessionGate.withLock { pendingVirtualDisplayCreation }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            existing.returnCell.evidence.initialResizeDeadlineOccurrence?.controlWakeLink?.let(::serviceWakeLink)
            if (!virtualDisplayReturnAccepted) {
                when (val arbitration = existing.arbitrate()) {
                    OperationArbitration.None -> return
                    OperationArbitration.TimelyNormal -> {
                        val bag = existing.ownerBag as AndroidVirtualDisplayCreationOwnerBag
                        val candidate = bag.target.producerApplicationCandidateAfterSettlement(bag.port, existing)
                        val fact = candidate?.let(bag.target::applyProducerApplication)
                        val applied = fact != null && checkNotNull(androidOwner).applyVirtualDisplayCreationTargetFact(existing, fact)
                        if (!applied || fact !is io.screenstream.engine.internal.target.TargetProducerEvidence) {
                            offerFailure(ScreenCaptureProblem.CaptureUnavailable, VIRTUAL_DISPLAY_PRODUCER_REJECTED)
                            return
                        }
                        virtualDisplayReturnAccepted = true
                    }

                    else -> {
                        sessionGate.withLock {
                            if (pendingVirtualDisplayCreation === existing) pendingVirtualDisplayCreation = null
                        }
                        offerFailure(ScreenCaptureProblem.CaptureUnavailable, existing.returnCell.throwable)
                        return
                    }
                }
            }

            val api34 = sessionGate.withLock { androidOwner?.apiBand == AndroidCaptureApiBand.Api34To37 }
            if (api34) {
                val evidence = existing.returnCell.evidence
                val timelyResize = existing.settlementGate.withLock { evidence.isTimelyInitialResizeLocked() }
                if (!timelyResize) {
                    val expired = existing.settlementGate.withLock {
                        evidence.initialResizeDeadlineOccurrence?.disposition == DeadlineDisposition.Expired
                    }
                    if (expired) offerFailure(ScreenCaptureProblem.CaptureUnavailable, INITIAL_RESIZE_TIMEOUT)
                    return
                }
            }
            sessionGate.withLock {
                if (pendingVirtualDisplayCreation === existing) {
                    pendingVirtualDisplayCreation = null
                    virtualDisplayReturnAccepted = false
                    topologyPublicationIdentity = reserveIdentityLocked()
                }
            }
            signal()
            return
        }

        var target: CurrentTarget? = null
        var owner: AndroidCaptureOwner? = null
        var logicalWidth = 0
        var logicalHeight = 0
        var density = 0
        val selected = sessionGate.withLock {
            target = currentTarget
            owner = androidOwner
            val logical = captureGeometry
            val provisional = combinedGeometryAuthority
            logicalWidth = logical?.widthPx ?: provisional?.sourceWidthPx ?: 0
            logicalHeight = logical?.heightPx ?: provisional?.sourceHeightPx ?: 0
            density = logical?.densityDpi ?: provisional?.densityDpi ?: 0
            !terminalWinner.fixed && !admissionsClosed && target != null && owner != null &&
                    logicalWidth > 0 && logicalHeight > 0 && density > 0
        }
        if (!selected) return
        val currentness = checkNotNull(target).currentnessSnapshot()
        if (!currentness.listenerInstalled || currentness.producerState != TargetProducerState.AwaitingEvidence) return
        val identity = reserveAndroidIdentity(VIRTUAL_DISPLAY_CREATION_TIMEOUT) ?: return
        val initialResize = if (checkNotNull(owner).apiBand == AndroidCaptureApiBand.Api34To37) {
            var deadline = 0L
            var wake = 0L
            val reserved = sessionGate.withLock {
                if (terminalWinner.fixed || admissionsClosed) return@withLock false
                deadline = reserveIdentityLocked()
                wake = reserveIdentityLocked()
                true
            }
            if (!reserved) return
            AndroidInitialResizeDeadlineIdentity(deadline, wake, INITIAL_RESIZE_TIMEOUT)
        } else {
            null
        }
        val operation = checkNotNull(owner).createVirtualDisplayCreationOperation(
            target = checkNotNull(target),
            widthPx = logicalWidth,
            heightPx = logicalHeight,
            densityDpi = density,
            identity = identity,
            initialResizeDeadlineIdentity = initialResize,
        ) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, VIRTUAL_DISPLAY_OPERATION_REJECTED)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || currentTarget !== target || androidOwner !== owner ||
                pendingVirtualDisplayCreation != null
            ) {
                return@withLock false
            }
            pendingVirtualDisplayCreation = operation
            virtualDisplayReturnAccepted = false
            true
        }
        if (!rooted) return
        checkNotNull(owner).submitVirtualDisplayCreation(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceRenderTargetConstruction() {
        val existing = sessionGate.withLock { pendingRenderConstruction }
        if (existing != null) {
            serviceWakeLink(existing.deadlineWakeLink)
            val claim = existing.claim() ?: return
            acceptRenderTargetInstallation(existing, claim)
            sessionGate.withLock {
                if (pendingRenderConstruction === existing) pendingRenderConstruction = null
            }
            return
        }

        var target: CurrentTarget? = null
        var calculation: Resolved? = null
        var owner: GlPipelineOwner? = null
        var renderGeneration = 0L
        val selected = sessionGate.withLock {
            target = currentTarget
            calculation = currentCalculation
            owner = glOwner
            if (terminalWinner.fixed || admissionsClosed || target == null || calculation == null || owner == null ||
                installedRenderTarget != null || pendingRenderConstruction != null
            ) {
                return@withLock false
            }
            renderGeneration = nextRenderGeneration
            if (renderGeneration == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock false
            }
            nextRenderGeneration++
            true
        }
        if (!selected) return
        val targetSnapshot = checkNotNull(target).currentnessSnapshot()
        if (!targetSnapshot.listenerInstalled || targetSnapshot.producerState != TargetProducerState.ProducerAttached ||
            targetSnapshot.generationFenced
        ) {
            return
        }
        val construction = reserveGlIdentity(RENDER_TARGET_CONSTRUCTION_TIMEOUT) ?: return
        val destruction = reserveGlIdentity(RENDER_TARGET_DESTRUCTION_TIMEOUT) ?: return
        val command = checkNotNull(owner).prepareRenderTargetConstruction(
            identity = construction,
            destructionIdentity = destruction,
            renderGeneration = renderGeneration,
            compatibilityFacts = checkNotNull(calculation).renderCompatibilityFacts,
            targetGeneration = targetSnapshot.generation,
            reconciliationFacts = checkNotNull(calculation).frameReconciliationFacts,
        ) ?: run {
            offerFailure(ScreenCaptureProblem.ResourceExhausted, null)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || currentTarget !== target || currentCalculation !== calculation ||
                glOwner !== owner || installedRenderTarget != null || pendingRenderConstruction != null ||
                !checkNotNull(target).isCurrentnessVersion(targetSnapshot.version)
            ) {
                return@withLock false
            }
            pendingRenderConstruction = command
            true
        }
        if (!rooted) {
            val retainedForCleanup = sessionGate.withLock {
                if (glOwner !== owner || pendingRenderConstruction != null) return@withLock false
                pendingRenderConstruction = command
                true
            }
            if (retainedForCleanup) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }
        command.submit()
        serviceWakeLink(command.deadlineWakeLink)
    }

    private fun advanceJpegPreparation() {
        val existing = sessionGate.withLock { pendingJpegPreparation }
        if (existing != null) {
            existing.operation.controlWakeLink?.let(::serviceWakeLink)
            val mechanicallyReturned = existing.operation.settlementGate.withLock {
                existing.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!mechanicallyReturned) return
            val owner = sessionGate.withLock { jpegOwner }
            val installed = owner?.installPrepared(existing) == true
            sessionGate.withLock {
                if (pendingJpegPreparation === existing) pendingJpegPreparation = null
                if (installed) {
                    topologyPublicationIdentity = reserveIdentityLocked()
                    invalidateReconciliationForTopologyMutationLocked()
                }
            }
            if (!installed) {
                val failure = existing.operation.returnCell.evidence.failure
                offerFailure(
                    if (failure == io.screenstream.engine.internal.jpeg.JpegRuntimeFailure.ResourceExhausted) {
                        ScreenCaptureProblem.ResourceExhausted
                    } else {
                        ScreenCaptureProblem.InternalFailure
                    },
                    existing.operation.returnCell.evidence.failureCause,
                )
            } else {
                signal()
            }
            return
        }

        var owner: JpegRuntimeOwner? = null
        var calculation: Resolved? = null
        var desired = 0L
        var geometry = 0L
        var epoch = 0L
        val selected = sessionGate.withLock {
            owner = jpegOwner
            calculation = currentCalculation
            desired = desiredRevision
            geometry = geometryGeneration
            epoch = lifecycleEpoch
            !terminalWinner.fixed && !admissionsClosed && owner != null && calculation != null &&
                    installedRenderTarget != null && pendingJpegPreparation == null
        }
        if (!selected || checkNotNull(owner).stableTopologySnapshot()?.product != null) return
        val identity = reserveJpegIdentity(JPEG_PREPARATION_TIMEOUT) ?: return
        val occurrence = checkNotNull(owner).prepare(
            policy = jpegPolicy,
            byteCount = checkNotNull(calculation).rgbaByteCount,
            desiredRevision = desired,
            geometryGeneration = geometry,
            lifecycleEpoch = epoch,
            identity = identity,
        ) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, JPEG_PREPARATION_REJECTED)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || jpegOwner !== owner || currentCalculation !== calculation ||
                pendingJpegPreparation != null
            ) {
                return@withLock false
            }
            pendingJpegPreparation = occurrence
            true
        }
        if (!rooted) return
        occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceFrameworkPreparation() {
        val existing = sessionGate.withLock { pendingFrameworkCreation }
        if (existing != null) {
            existing.operation.controlWakeLink?.let(::serviceWakeLink)
            val mechanicallyReturned = existing.operation.settlementGate.withLock {
                existing.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (mechanicallyReturned) classifyAndSettleFrameworkCreation(existing)
            return
        }
        var runtime: JpegRuntimeOwner? = null
        var calculation: Resolved? = null
        var desired = 0L
        var geometry = 0L
        var epoch = 0L
        val selected = sessionGate.withLock {
            runtime = jpegOwner
            calculation = currentCalculation
            desired = desiredRevision
            geometry = geometryGeneration
            epoch = lifecycleEpoch
            !terminalWinner.fixed && !admissionsClosed && runtime != null && calculation != null &&
                    installedRenderTarget != null && installedFrameworkOwner == null && pendingFrameworkCreation == null &&
                    cleanupOwner.jpegRoot.frameworkOwner == null
        }
        if (!selected) return
        val product = checkNotNull(runtime).stableTopologySnapshot()?.product ?: return
        if (product is JpegRuntimeProduct.NativeEnabled) return
        val identity = reserveJpegIdentity(FRAMEWORK_PREPARATION_TIMEOUT) ?: return
        val occurrence = when (product) {
            is JpegRuntimeProduct.FrameworkOnNativeCarrier -> FrameworkJpegOwner.beginResourceCreation(
                checkNotNull(runtime), clock, product, checkNotNull(calculation).finalImageSize,
                desired, geometry, epoch, identity,
            )

            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> FrameworkJpegOwner.beginResourceCreation(
                checkNotNull(runtime), clock, product, checkNotNull(calculation).finalImageSize,
                desired, geometry, epoch, identity,
            )

            is JpegRuntimeProduct.NativeEnabled -> null
        } ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, FRAMEWORK_PREPARATION_REJECTED)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || jpegOwner !== runtime || currentCalculation !== calculation ||
                pendingFrameworkCreation != null
            ) {
                return@withLock false
            }
            pendingFrameworkCreation = occurrence
            true
        }
        if (!rooted) return
        occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun acceptCompleteTopology() {
        var calculation: Resolved? = null
        val eligible = sessionGate.withLock {
            calculation = currentCalculation
            !terminalWinner.fixed && !admissionsClosed && calculation != null && currentTarget != null &&
                    installedRenderTarget != null && acceptedTopologySnapshot == null &&
                    calculation?.targetAction == ReconciliationResourceAction.Retain &&
                    calculation?.renderAction == ReconciliationResourceAction.Retain &&
                    calculation?.jpegAction == ReconciliationResourceAction.Retain &&
                    calculation?.frameworkAction != ReconciliationResourceAction.Create &&
                    calculation?.frameworkAction != ReconciliationResourceAction.Replace
        }
        if (eligible) commitActive(checkNotNull(calculation).effectiveParameters)
    }

    private fun serviceWakeLink(link: ControlWakeLink) {
        link.claimSubmissionAction()?.let { action -> executeWakeSubmission(link, action) }
        link.claimCancellationAction()?.let { action -> executeWakeCancellation(link, action) }
    }

    private fun reserveAndroidIdentity(timeoutCause: Throwable): AndroidFiniteOperationIdentity? {
        var operationIdentity = 0L
        var deadlineIdentity = 0L
        var wakeGeneration = 0L
        val reserved = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed) return@withLock false
            operationIdentity = reserveIdentityLocked()
            deadlineIdentity = reserveIdentityLocked()
            wakeGeneration = reserveIdentityLocked()
            true
        }
        return if (reserved) {
            AndroidFiniteOperationIdentity(operationIdentity, deadlineIdentity, wakeGeneration, timeoutCause)
        } else {
            null
        }
    }

    private fun reserveGlIdentity(timeoutCause: Throwable): GlFiniteOperationIdentity? {
        var operationIdentity = 0L
        var deadlineIdentity = 0L
        var wakeGeneration = 0L
        val reserved = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed) return@withLock false
            operationIdentity = reserveIdentityLocked()
            deadlineIdentity = reserveIdentityLocked()
            wakeGeneration = reserveIdentityLocked()
            true
        }
        return if (reserved) {
            GlFiniteOperationIdentity(operationIdentity, deadlineIdentity, wakeGeneration, timeoutCause)
        } else {
            null
        }
    }

    private fun reserveJpegIdentity(timeoutCause: Throwable): JpegFiniteOperationIdentity? {
        var operationIdentity = 0L
        var deadlineIdentity = 0L
        var wakeGeneration = 0L
        val reserved = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed) return@withLock false
            operationIdentity = reserveIdentityLocked()
            deadlineIdentity = reserveIdentityLocked()
            wakeGeneration = reserveIdentityLocked()
            true
        }
        return if (reserved) {
            JpegFiniteOperationIdentity(operationIdentity, deadlineIdentity, wakeGeneration, timeoutCause)
        } else {
            null
        }
    }

    internal fun installedOwnerIdentitiesMatch(
        target: CurrentTarget?,
        renderTarget: GlPipelineOwner.GlRenderTargetOwner?,
        jpegProduct: JpegRuntimeProduct?,
        frameworkOwner: FrameworkJpegOwner?,
    ): Boolean = sessionGate.withLock {
        currentTarget === target && installedRenderTarget === renderTarget &&
                jpegOwner?.stableTopologySnapshot()?.product === jpegProduct &&
                installedFrameworkOwner === frameworkOwner
    }

    internal fun claimWakeSubmission(
        occurrence: OperationOccurrence<*>,
    ): ControlWakeScheduleAction? = occurrence.controlWakeLink?.claimSubmissionAction()

    internal fun claimWakeCancellation(
        occurrence: OperationOccurrence<*>,
    ): ControlWakeCancellationAction? = occurrence.controlWakeLink?.claimCancellationAction()

    private fun orderedMean(previous: Double, sample: Double, count: Long): Double {
        if (count <= 0L || !sample.isFinite()) return previous
        val next = previous + (sample - previous) / count.toDouble()
        return if (next.isFinite()) next else previous
    }

    private fun saturatedIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private companion object {
        private const val DRAIN_IDLE: Int = 0
        private const val DRAIN_RUNNING: Int = 1
        private const val DRAIN_RUNNING_DIRTY: Int = 2
        private const val CAPTURE_ENDED_CONTENDER: Int = 0
        private const val OWNER_STOP_CONTENDER: Int = 1
        private const val FAILURE_CONTENDER: Int = 2
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND: Double = 1_000_000.0

        private val IDENTITY_EXHAUSTED = IllegalStateException("Session identity exhausted before reuse")
        private val DIAGNOSTIC_SEQUENCE_EXHAUSTED =
            IllegalStateException("Session diagnostic sequence exhausted before reuse")
        private val CONTROL_SCHEDULER_COLLISION = IllegalStateException("Control scheduler already exists")
        private val CONTROL_SCHEDULER_UNAVAILABLE = IllegalStateException("Control scheduler unavailable")
        private val CONTROL_DRAINER_RECORD_REUSE =
            IllegalStateException("Control drainer record reused before settlement")
        private val RECONCILIATION_EVIDENCE_INVALID = IllegalStateException("Reconciliation evidence is invalid")
        private val METRICS_JOINT_READINESS_EVIDENCE_INVALID =
            IllegalStateException("Metrics joint-readiness evidence is invalid")
        private val METRICS_READINESS_TIMEOUT = IllegalStateException("Metrics readiness expired")
        private val GL_SESSION_CONSTRUCTION_TIMEOUT =
            IllegalStateException("GL Session construction exceeded its entered-operation interval")
        private val GL_PARTIAL_SESSION_CLEANUP_TIMEOUT =
            IllegalStateException("GL partial-session cleanup exceeded its entered-operation interval")
        private val PROJECTION_CALLBACK_REGISTRATION_TIMEOUT =
            IllegalStateException("projection callback registration exceeded its entered-operation interval")
        private val PROJECTION_CALLBACK_REGISTRATION_COLLISION =
            IllegalStateException("projection callback registration occurrence collided")
        private val TARGET_CONSTRUCTION_TIMEOUT =
            IllegalStateException("target construction exceeded its entered-operation interval")
        private val TARGET_LISTENER_INSTALLATION_TIMEOUT =
            IllegalStateException("target listener installation exceeded its entered-operation interval")
        private val TARGET_LISTENER_RECEIPT_REJECTED =
            IllegalStateException("target listener installation receipt was rejected")
        private val TARGET_LISTENER_OPERATION_REJECTED =
            IllegalStateException("target listener installation operation was rejected")
        private val TARGET_SURFACE_RELEASE_TIMEOUT =
            IllegalStateException("target surface release exceeded its entered-operation interval")
        private val TARGET_DESTRUCTION_TIMEOUT =
            IllegalStateException("target destruction exceeded its entered-operation interval")
        private val TARGET_NAMESPACE_DESTRUCTION_TIMEOUT =
            IllegalStateException("target namespace destruction exceeded its entered-operation interval")
        private val VIRTUAL_DISPLAY_CREATION_TIMEOUT =
            IllegalStateException("VirtualDisplay creation exceeded its entered-operation interval")
        private val VIRTUAL_DISPLAY_OPERATION_REJECTED =
            IllegalStateException("VirtualDisplay creation operation was rejected")
        private val VIRTUAL_DISPLAY_PRODUCER_REJECTED =
            IllegalStateException("VirtualDisplay producer evidence was rejected")
        private val INITIAL_RESIZE_TIMEOUT =
            IllegalStateException("initial captured-content resize expired")
        private val RENDER_TARGET_CONSTRUCTION_TIMEOUT =
            IllegalStateException("render-target construction exceeded its entered-operation interval")
        private val RENDER_TARGET_DESTRUCTION_TIMEOUT =
            IllegalStateException("render-target destruction exceeded its entered-operation interval")
        private val PROGRAM_DESTRUCTION_TIMEOUT =
            IllegalStateException("GL program destruction exceeded its entered-operation interval")
        private val SESSION_DESTRUCTION_TIMEOUT =
            IllegalStateException("GL session destruction exceeded its entered-operation interval")
        private val JPEG_PREPARATION_TIMEOUT =
            IllegalStateException("JPEG preparation exceeded its entered-operation interval")
        private val JPEG_PREPARATION_REJECTED = IllegalStateException("JPEG preparation was rejected")
        private val FRAMEWORK_PREPARATION_TIMEOUT =
            IllegalStateException("Framework JPEG preparation exceeded its entered-operation interval")
        private val FRAMEWORK_PREPARATION_REJECTED =
            IllegalStateException("Framework JPEG preparation was rejected")
    }
}
