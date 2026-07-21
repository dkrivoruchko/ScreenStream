package io.screenstream.engine.internal.controller

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.view.Display
import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.CaptureMetrics
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
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationOwnerBag
import io.screenstream.engine.internal.android.AndroidTargetListenerRemovalOwnerBag
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationOwnerBag
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseMode
import io.screenstream.engine.internal.android.AndroidVirtualDisplayReleaseOwnerBag
import io.screenstream.engine.internal.android.CaptureMetricsClaimedValue
import io.screenstream.engine.internal.android.CaptureMetricsIngressPort
import io.screenstream.engine.internal.android.CaptureMetricsIngressResult
import io.screenstream.engine.internal.android.CaptureMetricsOwner
import io.screenstream.engine.internal.android.CaptureMetricsReadinessArbitration
import io.screenstream.engine.internal.android.CaptureMetricsTerminalArbitration
import io.screenstream.engine.internal.android.CaptureMetricsTerminalKind
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
import io.screenstream.engine.internal.jpeg.FrameworkBitmapRecycleSettlement
import io.screenstream.engine.internal.jpeg.FrameworkResourceCreationOccurrence
import io.screenstream.engine.internal.jpeg.FrameworkResourceCreationResult
import io.screenstream.engine.internal.jpeg.JpegRuntimeProduct
import io.screenstream.engine.internal.jpeg.NativeEncodeFatalCleanupSettlement
import io.screenstream.engine.internal.jpeg.NativeEncodeOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeSettlement
import io.screenstream.engine.internal.jpeg.JpegFiniteOperationIdentity
import io.screenstream.engine.internal.settlement.ControlWakeCancellationAction
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeOuterRemovalDisposition
import io.screenstream.engine.internal.settlement.ControlWakeRemovalFailureDisposition
import io.screenstream.engine.internal.settlement.ControlWakeScheduleAction
import io.screenstream.engine.internal.settlement.ControlScheduledTaskRecord
import io.screenstream.engine.internal.settlement.ControlPoisonAuthority
import io.screenstream.engine.internal.settlement.ControlPoisonClaimOutcome
import io.screenstream.engine.internal.settlement.ControlPoisonPublicationDisposition
import io.screenstream.engine.internal.settlement.ControlWakeActionPublicationOutcome
import io.screenstream.engine.internal.settlement.ControlWakeScheduleFailurePublicationOutcome
import io.screenstream.engine.internal.settlement.ControlWakeScheduleReturnPublicationOutcome
import io.screenstream.engine.internal.settlement.ControlWakeSuccessorResult
import io.screenstream.engine.internal.settlement.ControlWakeSuppressionDisposition
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.PreparedTarget
import io.screenstream.engine.internal.target.PreparedTargetAdmissionFact
import io.screenstream.engine.internal.target.TargetConstructionAdmissionDisposition
import io.screenstream.engine.internal.target.TargetConstructionFailureFact
import io.screenstream.engine.internal.target.TargetConstructionFoldDisposition
import io.screenstream.engine.internal.target.TargetConstructionInstalledFact
import io.screenstream.engine.internal.target.TargetConstructionResultFact
import io.screenstream.engine.internal.target.TargetOwner
import io.screenstream.engine.internal.target.TargetPlan
import io.screenstream.engine.internal.target.TargetProducerState
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.target.TargetRetirementCompleteEvidence
import io.screenstream.engine.internal.target.TargetSourceSignal
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
) : AndroidCaptureFactSink, CaptureMetricsIngressPort, SettlementSignal, SessionControlSchedulerPort {

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

    private enum class ControlAggregateFactSite {
        StartupInvariant,
        SchedulerUnavailable,
        ScheduleInvariant,
        CancellationInvariant,
        DetachedInvariant,
        DrainerIdentityExhausted,
        SuccessorIdentityExhausted,
        DrainerRecordReuse,
        ScheduleClaimExhausted,
        CancelClaimExhausted,
        RemoveClaimExhausted,
        DetachedCancelClaimExhausted,
        DetachedRemoveClaimExhausted,
        SuccessorClaimExhausted,
        StartupSecondary,
        StartupCleanupSecondary,
        ScheduleSecondary,
        CancellationSecondary,
        DetachedSecondary,
        DrainerSecondary,
        SuccessorSecondary,
    }

    private enum class ControlAggregateFactPublicationOutcome {
        Published,
        AlreadyPublished,
        IdentityMismatch,
        Exhausted,
    }

    private class ControlAggregateFactReceipt(
        val site: ControlAggregateFactSite,
        val identity: Long,
    )

    private class ControlAggregateFactCell(
        site: ControlAggregateFactSite,
        identity: Long,
    ) {
        val exactReceipt: ControlAggregateFactReceipt = ControlAggregateFactReceipt(site, identity)
        private val state = AtomicInteger(FACT_EMPTY)
        private val exactRaw = AtomicReference<Throwable?>(null)

        fun publish(
            receipt: ControlAggregateFactReceipt,
            raw: Throwable,
        ): ControlAggregateFactPublicationOutcome {
            if (receipt !== exactReceipt || receipt.site != exactReceipt.site ||
                receipt.identity != exactReceipt.identity
            ) {
                return ControlAggregateFactPublicationOutcome.IdentityMismatch
            }
            if (receipt.identity <= 0L || receipt.identity == Long.MAX_VALUE) {
                return ControlAggregateFactPublicationOutcome.Exhausted
            }
            if (state.compareAndSet(FACT_EMPTY, FACT_PUBLISHING)) {
                exactRaw.set(raw)
                state.set(FACT_PUBLISHED)
                return ControlAggregateFactPublicationOutcome.Published
            }
            while (state.get() == FACT_PUBLISHING) Unit
            return if (exactRaw.get() === raw) {
                ControlAggregateFactPublicationOutcome.AlreadyPublished
            } else {
                ControlAggregateFactPublicationOutcome.Exhausted
            }
        }

        private companion object {
            private const val FACT_EMPTY = 0
            private const val FACT_PUBLISHING = 1
            private const val FACT_PUBLISHED = 2
        }
    }

    private enum class DeferredControlTerminalPublicationOutcome {
        Published,
        AlreadyPublished,
        Exhausted,
    }

    private class DeferredControlTerminalPublication {
        private val state = AtomicInteger(DEFERRED_EMPTY)
        private val stableRaw = AtomicReference<Throwable?>(null)

        fun publish(raw: Throwable): DeferredControlTerminalPublicationOutcome {
            if (state.compareAndSet(DEFERRED_EMPTY, DEFERRED_PUBLISHING)) {
                stableRaw.set(raw)
                state.set(DEFERRED_PENDING)
                return DeferredControlTerminalPublicationOutcome.Published
            }
            val observed = state.get()
            return if (observed == DEFERRED_PUBLISHING || stableRaw.get() === raw) {
                DeferredControlTerminalPublicationOutcome.AlreadyPublished
            } else {
                DeferredControlTerminalPublicationOutcome.Exhausted
            }
        }

        fun claim(): Throwable? =
            if (state.compareAndSet(DEFERRED_PENDING, DEFERRED_APPLYING)) stableRaw.get() else null

        fun publishApplied(raw: Throwable): Boolean =
            stableRaw.get() === raw && state.compareAndSet(DEFERRED_APPLYING, DEFERRED_APPLIED)

        fun publishRetry(raw: Throwable): Boolean =
            stableRaw.get() === raw && state.compareAndSet(DEFERRED_APPLYING, DEFERRED_PENDING)

        private companion object {
            private const val DEFERRED_EMPTY = 0
            private const val DEFERRED_PUBLISHING = 1
            private const val DEFERRED_PENDING = 2
            private const val DEFERRED_APPLYING = 3
            private const val DEFERRED_APPLIED = 4
        }
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
    internal val targetOwner: TargetOwner = TargetOwner()

    private val topologyFacet = SessionTopologyFacet()
    private val productionFacet = SessionProductionFacet()
    private val deliveryFacet = SessionDeliveryFacet()
    private val statsFacet = SessionStatsFacet()
    private val cleanupFacet = SessionCleanupFacet(CleanupOwner(sessionGate))

    private val startCompletion = StartCompletion()
    private val acceptedAdmission = StartAdmission(true, startCompletion)
    private val rejectedAdmission = StartAdmission(false, startCompletion)
    private val terminalContenders: Array<TerminalContender> = Array(3) { TerminalContender() }
    private val terminalWinner = TerminalWinner()
    private val turnSlots = TurnSlots()
    private val emergencyTurnSlots = TurnSlots()
    private val startupOwnerBag = StartupOwnerBag()

    private val controlPoisonAuthority = ControlPoisonAuthority()
    private val controlStartupInvariantFact =
        ControlAggregateFactCell(ControlAggregateFactSite.StartupInvariant, 1L)
    private val controlSchedulerUnavailableFact =
        ControlAggregateFactCell(ControlAggregateFactSite.SchedulerUnavailable, 3L)
    private val controlScheduleInvariantFact =
        ControlAggregateFactCell(ControlAggregateFactSite.ScheduleInvariant, 4L)
    private val controlCancellationInvariantFact =
        ControlAggregateFactCell(ControlAggregateFactSite.CancellationInvariant, 5L)
    private val controlDetachedInvariantFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DetachedInvariant, 6L)
    private val controlDrainerIdentityExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DrainerIdentityExhausted, 7L)
    private val controlSuccessorIdentityExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.SuccessorIdentityExhausted, 8L)
    private val controlDrainerRecordReuseFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DrainerRecordReuse, 9L)
    private val controlScheduleClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.ScheduleClaimExhausted, 10L)
    private val controlCancelClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.CancelClaimExhausted, 11L)
    private val controlRemoveClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.RemoveClaimExhausted, 12L)
    private val controlDetachedCancelClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DetachedCancelClaimExhausted, 13L)
    private val controlDetachedRemoveClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DetachedRemoveClaimExhausted, 14L)
    private val controlSuccessorClaimExhaustedFact =
        ControlAggregateFactCell(ControlAggregateFactSite.SuccessorClaimExhausted, 15L)
    private val controlStartupSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.StartupSecondary, 16L)
    private val controlStartupCleanupSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.StartupCleanupSecondary, 17L)
    private val controlScheduleSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.ScheduleSecondary, 20L)
    private val controlCancellationSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.CancellationSecondary, 21L)
    private val controlDetachedSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DetachedSecondary, 22L)
    private val controlDrainerSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.DrainerSecondary, 23L)
    private val controlSuccessorSecondaryFact =
        ControlAggregateFactCell(ControlAggregateFactSite.SuccessorSecondary, 24L)
    private val deferredControlTerminalPublication = DeferredControlTerminalPublication()
    private val unpublishedControlRuntimeOwner = AtomicReference<SessionControlRuntimeOwner?>(null)
    private val unpublishedControlRuntimeRoot = AtomicReference<SessionStartupControlRuntimeRoot?>(null)
    private val unpublishedControlScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val controlScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val acceptedControlRuntimePublished = java.util.concurrent.atomic.AtomicBoolean(false)
    private val preBarrierDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val drainerState = AtomicInteger(DRAIN_IDLE)
    private val drainerGeneration = AtomicLong(0L)
    private val drainerRecords: Array<SessionControlDrainerTaskRecord> = arrayOf(
        SessionControlDrainerTaskRecord(controlPoisonAuthority, Runnable(::runDrainer)),
        SessionControlDrainerTaskRecord(controlPoisonAuthority, Runnable(::runDrainer)),
    )

    private val latestResize = AtomicReference<AndroidCaptureFact.CapturedContentResized?>(null)
    private val latestVisibility = AtomicReference<AndroidCaptureFact.CapturedContentVisibilityChanged?>(null)
    private val captureEnded = AtomicReference<AndroidCaptureFact.CaptureEnded?>(null)

    private var lifecycle: Lifecycle = Lifecycle.NotStarted
    private var pendingStartupBag: StartupOwnerBag? = null
    private var runningPhase: RunningPhase? = null
    @Volatile
    private var admissionsClosed: Boolean = false
    private var terminalCutoffApplied: Boolean = false
    private var ownersLaunched: Boolean = false
    private var startingAssigned: Boolean = false
    private var firstActiveAssigned: Boolean = false
    private var nextIdentity: Long = 1L

    internal val topologySnapshot: AcceptedTopologySnapshot?
        get() = sessionGate.withLock { topologyFacet.acceptedTopologySnapshot }

    internal fun acceptStart(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters,
    ): StartAdmission {
        convergeDeferredControlTerminalPublication()
        val accepted = sessionGate.withLock {
            if (lifecycle != Lifecycle.NotStarted) return@withLock false
            lifecycle = Lifecycle.Starting
            topologyFacet.lifecycleEpoch = reserveIdentityLocked()
            topologyFacet.desiredRevision = reserveIdentityLocked()
            topologyFacet.projectionEpoch = reserveIdentityLocked()
            topologyFacet.requestedParameters = initialParameters
            topologyFacet.projection = mediaProjection
            startupOwnerBag.bindLocked(
                projection = mediaProjection,
                projectionOwnerEpoch = topologyFacet.projectionEpoch,
                callbackIdentity = reserveIdentityLocked(),
                metricsAttachmentIdentity = reserveIdentityLocked(),
                metricsDeadlineIdentity = reserveIdentityLocked(),
                metricsWakeIdentity = reserveIdentityLocked(),
                metricsCloseIdentity = reserveIdentityLocked(),
            )
            topologyFacet.projectionCallbackIdentity = startupOwnerBag.callbackIdentity
            pendingStartupBag = startupOwnerBag
            true
        }
        if (!accepted) return rejectedAdmission

        val startupRecord = SessionControlStartupRecord()
        val startupCleanupAction = SessionControlStartupCleanupAction(startupRecord)
        val runtimeOwner = SessionControlRuntimeOwner()
        val startupRuntimeRoot = SessionStartupControlRuntimeRoot(
            runtimeOwner,
            startupRecord,
            startupCleanupAction,
        )
        check(unpublishedControlRuntimeOwner.compareAndSet(null, runtimeOwner))
        check(unpublishedControlRuntimeRoot.compareAndSet(null, startupRuntimeRoot))
        retainUnresolvedStartupControlRuntime(startupRuntimeRoot)
        val scheduler = try {
            SessionControlScheduler(
                runtimeOwner,
                controlPoisonAuthority,
                startupRecord,
                startupCleanupAction,
                this,
            )
        } catch (raw: Throwable) {
            when (startupRecord.publishConstructionFailure(raw)) {
                SessionControlStartupFactPublicationOutcome.Published -> Unit
                SessionControlStartupFactPublicationOutcome.NotEligible -> publishAggregateFact(
                    controlStartupInvariantFact,
                    CONTROL_STARTUP_CONSTRUCTION_FACT_NOT_ELIGIBLE,
                )
            }
            val constructionDirectFatal = startupRecord.constructionDirectFatalFailure ?:
                if (FatalThrowablePolicy.isDirectFatal(raw)) raw else null
            settleStartupFailureAndCleanup(
                startupRuntimeRoot = startupRuntimeRoot,
                scheduler = null,
                primaryRaw = raw,
                primaryDirectFatalRaw = constructionDirectFatal,
            )
            return acceptedAdmission
        }
        check(unpublishedControlScheduler.compareAndSet(null, scheduler))
        val acceptedRuntimeRoot = SessionAcceptedControlRuntimeRoot(
            runtimeOwner = runtimeOwner,
            terminationRoot = scheduler.terminationRoot,
            terminationReceipt = scheduler.terminationRoot.exactReceipt,
            finalShutdownAction = scheduler.finalShutdownAction,
        )
        val prestartOutcome = try {
            scheduler.prestart()
        } catch (raw: Throwable) {
            publishAggregateFact(controlStartupInvariantFact, raw)
            settleStartupFailureAndCleanup(
                startupRuntimeRoot,
                scheduler,
                raw,
                if (FatalThrowablePolicy.isDirectFatal(raw)) raw else null,
            )
            return acceptedAdmission
        }
        return when (prestartOutcome) {
            SessionControlPrestartOutcome.Ready -> {
                check(controlScheduler.compareAndSet(null, scheduler))
                check(
                    scheduler.transferReadyRuntimeOwner() ==
                            SessionControlRuntimeTransferOutcome.Transferred,
                )
                storeAcceptedControlRuntime(startupRuntimeRoot, acceptedRuntimeRoot)
                clearUnpublishedControlRuntime(scheduler, startupRuntimeRoot, runtimeOwner)
                acceptedControlRuntimePublished.set(true)
                preBarrierDirty.set(false)
                signal()
                acceptedAdmission
            }

            SessionControlPrestartOutcome.CleanupRequired -> {
                val primaryRaw = checkNotNull(startupRecord.startupFailure)
                settleStartupFailureAndCleanup(
                    startupRuntimeRoot,
                    scheduler,
                    primaryRaw,
                    startupRecord.startupDirectFatalFailure,
                )
                acceptedAdmission
            }

            SessionControlPrestartOutcome.NotEligible -> {
                publishAggregateFact(controlStartupInvariantFact, CONTROL_STARTUP_NOT_ELIGIBLE)
                settleStartupFailureAndCleanup(
                    startupRuntimeRoot,
                    scheduler,
                    CONTROL_STARTUP_NOT_ELIGIBLE,
                    null,
                )
                acceptedAdmission
            }
        }
    }

    private fun retainUnresolvedStartupControlRuntime(root: SessionStartupControlRuntimeRoot) {
        sessionGate.withLock {
            val existing = cleanupFacet.unresolvedStartupControlRuntimeRoot
            if (existing === root) return@withLock
            check(existing == null)
            cleanupFacet.unresolvedStartupControlRuntimeRoot = root
        }
    }

    private fun storeAcceptedControlRuntime(
        startupRoot: SessionStartupControlRuntimeRoot,
        acceptedRoot: SessionAcceptedControlRuntimeRoot,
    ) {
        sessionGate.withLock {
            check(cleanupFacet.acceptedControlRuntimeRoot == null)
            check(cleanupFacet.unresolvedStartupControlRuntimeRoot === startupRoot)
            cleanupFacet.unresolvedStartupControlRuntimeRoot = null
            cleanupFacet.acceptedControlRuntimeRoot = acceptedRoot
        }
    }

    private fun releaseResolvedStartupControlRuntime(root: SessionStartupControlRuntimeRoot) {
        sessionGate.withLock {
            check(cleanupFacet.unresolvedStartupControlRuntimeRoot === root)
            cleanupFacet.unresolvedStartupControlRuntimeRoot = null
        }
    }

    private fun clearUnpublishedControlRuntime(
        scheduler: SessionControlScheduler?,
        root: SessionStartupControlRuntimeRoot,
        runtimeOwner: SessionControlRuntimeOwner,
    ) {
        if (scheduler == null) {
            check(unpublishedControlScheduler.get() == null)
        } else {
            check(unpublishedControlScheduler.compareAndSet(scheduler, null))
        }
        check(unpublishedControlRuntimeRoot.compareAndSet(root, null))
        check(unpublishedControlRuntimeOwner.compareAndSet(runtimeOwner, null))
    }

    private fun publishAggregateFact(cell: ControlAggregateFactCell, raw: Throwable) {
        when (cell.publish(cell.exactReceipt, raw)) {
            ControlAggregateFactPublicationOutcome.Published,
            ControlAggregateFactPublicationOutcome.AlreadyPublished,
                -> Unit

            ControlAggregateFactPublicationOutcome.IdentityMismatch ->
                throw CONTROL_AGGREGATE_FACT_IDENTITY_MISMATCH

            ControlAggregateFactPublicationOutcome.Exhausted -> throw CONTROL_AGGREGATE_FACT_EXHAUSTED
        }
    }

    private fun settleRecordedControlFailure(
        primaryRaw: Throwable,
        directFatalRaw: Throwable?,
        secondaryFact: ControlAggregateFactCell,
    ) {
        if (directFatalRaw != null) {
            settleDirectFatalControlFailure(primaryRaw, directFatalRaw)
        }
        var secondaryRaw: Throwable? = null
        try {
            val emergencyRaw = when (controlPoisonAuthority.publish(primaryRaw)) {
                ControlPoisonPublicationDisposition.Published,
                ControlPoisonPublicationDisposition.AlreadyPublished,
                    -> controlPoisonAuthority.observe() ?: primaryRaw

                ControlPoisonPublicationDisposition.ClaimExhausted ->
                    controlPoisonAuthority.observe() ?: primaryRaw
            }
            emergencyFailClosed(emergencyRaw)
        } catch (raw: Throwable) {
            publishAggregateFact(secondaryFact, raw)
            secondaryRaw = raw
        }
        if (secondaryRaw != null && FatalThrowablePolicy.isDirectFatal(secondaryRaw)) {
            settleDirectFatalControlFailure(primaryRaw, secondaryRaw)
        }
        if (secondaryRaw != null) throw secondaryRaw
    }

    private fun settleDirectFatalControlFailure(
        primaryRaw: Throwable,
        exactDirectFatalRaw: Throwable,
    ): Nothing {
        try {
            val stablePrimaryRaw = when (controlPoisonAuthority.publish(primaryRaw)) {
                ControlPoisonPublicationDisposition.Published,
                ControlPoisonPublicationDisposition.AlreadyPublished,
                    -> controlPoisonAuthority.observe() ?: primaryRaw

                ControlPoisonPublicationDisposition.ClaimExhausted ->
                    controlPoisonAuthority.observe() ?: primaryRaw
            }
            admissionsClosed = true
            when (deferredControlTerminalPublication.publish(stablePrimaryRaw)) {
                DeferredControlTerminalPublicationOutcome.Published,
                DeferredControlTerminalPublicationOutcome.AlreadyPublished,
                    -> Unit

                DeferredControlTerminalPublicationOutcome.Exhausted -> Unit
            }
        } finally {
            FatalThrowablePolicy.rethrow(exactDirectFatalRaw)
        }
    }

    private fun convergeDeferredControlTerminalPublication() {
        val stablePrimaryRaw = deferredControlTerminalPublication.claim() ?: return
        try {
            emergencyFailClosed(stablePrimaryRaw)
            check(deferredControlTerminalPublication.publishApplied(stablePrimaryRaw))
        } catch (raw: Throwable) {
            check(deferredControlTerminalPublication.publishRetry(stablePrimaryRaw))
            throw raw
        }
    }

    private fun settleControlClaimExhausted(
        claimFact: ControlAggregateFactCell,
        secondaryFact: ControlAggregateFactCell,
    ) {
        val raw = checkNotNull(controlPoisonAuthority.observe())
        publishAggregateFact(claimFact, raw)
        settleRecordedControlFailure(raw, null, secondaryFact)
    }

    private fun settleStartupFailureAndCleanup(
        startupRuntimeRoot: SessionStartupControlRuntimeRoot,
        scheduler: SessionControlScheduler?,
        primaryRaw: Throwable,
        primaryDirectFatalRaw: Throwable?,
    ) {
        if (primaryDirectFatalRaw != null) {
            try {
                clearUnpublishedControlRuntime(scheduler, startupRuntimeRoot, startupRuntimeRoot.runtimeOwner)
            } finally {
                settleDirectFatalControlFailure(primaryRaw, primaryDirectFatalRaw)
            }
        }
        var cleanupRaw: Throwable? = null
        var cleanupOutcome: SessionControlStartupCleanupExecutionOutcome? = null
        try {
            cleanupOutcome = startupRuntimeRoot.startupCleanupAction.execute()
        } catch (raw: Throwable) {
            publishAggregateFact(controlStartupCleanupSecondaryFact, raw)
            cleanupRaw = raw
        }
        val releasedReceipt = startupRuntimeRoot.startupCleanupAction.releasedReceipt
        val exactReleased = cleanupOutcome == SessionControlStartupCleanupExecutionOutcome.Released &&
                releasedReceipt === startupRuntimeRoot.terminationReceipt
        if (cleanupOutcome == SessionControlStartupCleanupExecutionOutcome.Released && !exactReleased) {
            publishAggregateFact(controlStartupCleanupSecondaryFact, CONTROL_STARTUP_RELEASE_RECEIPT_MISMATCH)
        }
        val shutdownDirectFatal = startupRuntimeRoot.startupRecord.shutdownDirectFatalFailure
        val cleanupDirectFatal =
            if (cleanupRaw != null && FatalThrowablePolicy.isDirectFatal(cleanupRaw)) cleanupRaw else null
        val exactDirectFatal = shutdownDirectFatal ?: cleanupDirectFatal
        if (exactDirectFatal != null) {
            try {
                clearUnpublishedControlRuntime(scheduler, startupRuntimeRoot, startupRuntimeRoot.runtimeOwner)
            } finally {
                settleDirectFatalControlFailure(primaryRaw, exactDirectFatal)
            }
        }
        settleStartupControlRuntimeOwnership(startupRuntimeRoot, scheduler, exactReleased)
        settleRecordedControlFailure(primaryRaw, null, controlStartupSecondaryFact)
        if (cleanupRaw != null) throw cleanupRaw
    }

    private fun settleStartupControlRuntimeOwnership(
        startupRuntimeRoot: SessionStartupControlRuntimeRoot,
        scheduler: SessionControlScheduler?,
        exactReleased: Boolean,
    ) {
        if (exactReleased) {
            releaseResolvedStartupControlRuntime(startupRuntimeRoot)
        } else {
            retainUnresolvedStartupControlRuntime(startupRuntimeRoot)
        }
        clearUnpublishedControlRuntime(scheduler, startupRuntimeRoot, startupRuntimeRoot.runtimeOwner)
    }

    override fun publishMetricsSample(
        expectedOwner: CaptureMetricsOwner,
        expectedObservationIdentity: Long,
        metrics: CaptureMetrics?,
        display: Display?,
        displayEpoch: Long,
    ): CaptureMetricsIngressResult = sessionGate.withLock {
        if (terminalWinner.fixed || admissionsClosed || lifecycle == Lifecycle.Terminal) {
            return@withLock CaptureMetricsIngressResult.RejectedAdmission
        }
        if (topologyFacet.metricsOwner !== expectedOwner ||
            expectedObservationIdentity != startupOwnerBag.metricsAttachmentIdentity
        ) {
            return@withLock CaptureMetricsIngressResult.RejectedCurrentness
        }
        expectedOwner.commitMetricsSampleIngressLocked(
            expectedObservationIdentity = expectedObservationIdentity,
            metrics = metrics,
            sampleNanos = clock.nowNanos(),
            display = display,
            displayEpoch = displayEpoch,
        )
    }

    override fun publishMetricsTerminal(
        expectedOwner: CaptureMetricsOwner,
        expectedObservationIdentity: Long,
        kind: CaptureMetricsTerminalKind,
        cause: Throwable?,
    ): CaptureMetricsIngressResult = sessionGate.withLock {
        if (terminalWinner.fixed || admissionsClosed || lifecycle == Lifecycle.Terminal) {
            return@withLock CaptureMetricsIngressResult.RejectedAdmission
        }
        if (topologyFacet.metricsOwner !== expectedOwner ||
            expectedObservationIdentity != startupOwnerBag.metricsAttachmentIdentity
        ) {
            return@withLock CaptureMetricsIngressResult.RejectedCurrentness
        }
        expectedOwner.commitMetricsTerminalIngressLocked(
            expectedObservationIdentity = expectedObservationIdentity,
            kind = kind,
            cause = cause,
        )
    }

    internal fun updateDesired(parameters: ScreenCaptureParameters): Boolean {
        convergeDeferredControlTerminalPublication()
        val changed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || admissionsClosed) return@withLock false
            if (topologyFacet.requestedParameters == parameters) return@withLock true
            if (nextIdentity == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock false
            }
            topologyFacet.requestedParameters = parameters
            topologyFacet.desiredRevision = reserveIdentityLocked()
            topologyFacet.reconciliationIdentity = 0L
            topologyFacet.currentCalculation = null
            topologyFacet.currentProvisional = null
            topologyFacet.currentPlan = null
            reserveRunningPublicationLocked()
            true
        }
        if (changed) signal()
        return changed
    }

    private fun advanceReconfigurationBoundary() {
        var calculation: Resolved? = null
        var topology: AcceptedTopologySnapshot? = null
        val selected = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed ||
                runningPhase != RunningPhase.Active || cleanupFacet.owner.currentTargetRoot.target != null ||
                cleanupFacet.owner.jpegRoot.owner != null || cleanupFacet.owner.jpegRoot.frameworkOwner != null ||
                cleanupFacet.renderOwner != null || topologyFacet.pendingProjectionRegistration != null ||
                topologyFacet.pendingListenerInstallation != null || topologyFacet.pendingVirtualDisplayCreation != null ||
                topologyFacet.pendingRenderConstruction != null || topologyFacet.pendingJpegPreparation != null ||
                topologyFacet.pendingFrameworkCreation != null
            ) {
                return@withLock false
            }
            calculation = topologyFacet.currentCalculation ?: return@withLock false
            topology = topologyFacet.acceptedTopologySnapshot ?: return@withLock false
            val exactCalculation = checkNotNull(calculation)
            exactCalculation.targetAction == ReconciliationResourceAction.Replace ||
                    exactCalculation.renderAction == ReconciliationResourceAction.Replace ||
                    exactCalculation.jpegAction == ReconciliationResourceAction.Replace ||
                    exactCalculation.frameworkAction == ReconciliationResourceAction.Replace
        }
        if (!selected) return
        val exactCalculation = checkNotNull(calculation)
        val exactTopology = checkNotNull(topology)
        val targetRevalidation = SessionReconfiguration.revalidate(exactTopology) ?: return
        if (exactTopology.target.activeLeaseCount != 0) return

        sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed ||
                runningPhase != RunningPhase.Active || topologyFacet.currentCalculation !== exactCalculation ||
                topologyFacet.acceptedTopologySnapshot !== exactTopology ||
                exactTopology.stamp.desiredRevision != topologyFacet.desiredRevision ||
                exactTopology.stamp.geometryGeneration != topologyFacet.geometryGeneration ||
                exactTopology.stamp.lifecycleEpoch != topologyFacet.lifecycleEpoch ||
                topologyFacet.metricsOwner !== exactTopology.metricsOwner ||
                topologyFacet.androidOwner !== exactTopology.androidOwner || topologyFacet.glOwner !== exactTopology.glOwner ||
                topologyFacet.currentTarget !== exactTopology.target ||
                targetRevalidation.targetIdentity !== exactTopology.targetCurrentness.targetIdentity ||
                targetRevalidation.version != exactTopology.targetCurrentness.version ||
                topologyFacet.installedRenderTarget !== exactTopology.renderTarget ||
                topologyFacet.jpegOwner !== exactTopology.jpegOwner ||
                topologyFacet.installedFrameworkOwner !== exactTopology.frameworkOwner ||
                topologyFacet.acceptedProjectionCallbackRegistrationIdentity != exactTopology.projectionRegistrationIdentity ||
                cleanupFacet.owner.currentTargetRoot.target != null || cleanupFacet.owner.jpegRoot.owner != null ||
                cleanupFacet.owner.jpegRoot.frameworkOwner != null || cleanupFacet.renderOwner != null ||
                topologyFacet.pendingProjectionRegistration != null || topologyFacet.pendingListenerInstallation != null ||
                topologyFacet.pendingVirtualDisplayCreation != null || topologyFacet.pendingRenderConstruction != null ||
                topologyFacet.pendingJpegPreparation != null || topologyFacet.pendingFrameworkCreation != null
            ) {
                return
            }
            val replaceTarget = exactCalculation.targetAction == ReconciliationResourceAction.Replace
            val replaceRender = exactCalculation.renderAction == ReconciliationResourceAction.Replace
            val replaceJpeg = exactCalculation.jpegAction == ReconciliationResourceAction.Replace
            val replaceFramework = exactCalculation.frameworkAction == ReconciliationResourceAction.Replace
            if (!replaceTarget && !replaceRender && !replaceJpeg && !replaceFramework) return
            if (!recordCleanupMutationLocked(cleanupFacet.owner.attachCurrentTarget(exactTopology.target))) return
            val exactRender = exactTopology.renderTarget
            cleanupFacet.renderOwner = exactRender
            if (replaceFramework) {
                topologyFacet.installedFrameworkOwner?.let { framework ->
                    if (!recordCleanupMutationLocked(cleanupFacet.owner.attachFramework(framework))) return
                    topologyFacet.installedFrameworkOwner = null
                }
            }
            if (replaceJpeg) {
                topologyFacet.installedFrameworkOwner?.let { framework ->
                    if (!recordCleanupMutationLocked(cleanupFacet.owner.attachFramework(framework))) return
                    topologyFacet.installedFrameworkOwner = null
                }
                val exactJpeg = topologyFacet.jpegOwner ?: return
                if (!recordCleanupMutationLocked(cleanupFacet.owner.attachJpeg(exactJpeg))) return
                topologyFacet.jpegOwner = null
                topologyFacet.replacementJpegConstructionClaimed = false
            }
            topologyFacet.currentTarget = null
            topologyFacet.installedRenderTarget = null
            runningPhase = RunningPhase.Suspended
            advanceLifecycleEpochLocked()
            invalidateOutputLocked()
            invalidateReconciliationForTopologyMutationLocked()
            cleanupFacet.workPending = true
            reserveRunningPublicationLocked(ScreenCaptureProblem.Reconfiguring)
        }
    }

    private fun constructReplacementJpegOwner() {
        val claimed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed || topologyFacet.jpegOwner != null ||
                cleanupFacet.owner.jpegRoot.owner != null || topologyFacet.replacementJpegConstructionClaimed
            ) {
                return@withLock false
            }
            topologyFacet.replacementJpegConstructionClaimed = true
            true
        }
        if (!claimed) return
        val candidate = try {
            SessionStartupTopology.constructJpeg(clock, this)
        } catch (raw: Throwable) {
            sessionGate.withLock { topologyFacet.replacementJpegConstructionClaimed = false }
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return
        }
        val startup = try {
            candidate.prestartJpegEndpoint()
        } catch (raw: Throwable) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupFacet.owner.attachJpeg(candidate))
                cleanupFacet.workPending = true
                topologyFacet.replacementJpegConstructionClaimed = false
            }
            offerFailure(ScreenCaptureProblem.InternalFailure, raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return
        }
        if (startup != PrivateExecutorStartupDisposition.Ready) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupFacet.owner.attachJpeg(candidate))
                cleanupFacet.workPending = true
                topologyFacet.replacementJpegConstructionClaimed = false
            }
            offerFailure(ScreenCaptureProblem.InternalFailure, candidate.jpegEndpointStartupFailure)
            return
        }
        val installed = sessionGate.withLock {
            if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed || topologyFacet.jpegOwner != null ||
                cleanupFacet.owner.jpegRoot.owner != null
            ) {
                return@withLock false
            }
            topologyFacet.jpegOwner = candidate
            topologyFacet.replacementJpegConstructionClaimed = false
            invalidateReconciliationForTopologyMutationLocked()
            true
        }
        if (!installed) {
            sessionGate.withLock {
                recordCleanupMutationLocked(cleanupFacet.owner.attachJpeg(candidate))
                cleanupFacet.workPending = true
                topologyFacet.replacementJpegConstructionClaimed = false
            }
        }
        signal()
    }

    internal fun requestOwnerStop() {
        convergeDeferredControlTerminalPublication()
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
        convergeDeferredControlTerminalPublication()
        val accepted = sessionGate.withLock {
            lifecycle == Lifecycle.Starting && topologyFacet.projection != null && !terminalWinner.fixed
        }
        if (accepted) requestOwnerStop()
        return accepted
    }

    internal fun offerFailure(problem: ScreenCaptureProblem, cause: Throwable?) {
        convergeDeferredControlTerminalPublication()
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

    private fun isAcceptedControlRuntimeReleased(): Boolean {
        if (!acceptedControlRuntimePublished.get()) return false
        val scheduler = controlScheduler.get() ?: return false
        val terminationRoot = scheduler.terminationRoot
        val releasedReceipt = terminationRoot.observeReleasedReceipt()
        if (releasedReceipt != null) check(releasedReceipt === terminationRoot.exactReceipt)
        return releasedReceipt === terminationRoot.exactReceipt
    }

    override fun signal() {
        convergeDeferredControlTerminalPublication()
        if (!acceptedControlRuntimePublished.get()) {
            preBarrierDirty.set(true)
            return
        }
        if (isAcceptedControlRuntimeReleased()) return
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
        var owner: CaptureMetricsOwner? = null
        val arbitration = sessionGate.withLock {
            owner = topologyFacet.metricsOwner ?: return@withLock CaptureMetricsReadinessArbitration.None
            checkNotNull(owner).arbitrateReadinessLocked()
        }
        val exactOwner = owner ?: return
        when (arbitration) {
            CaptureMetricsReadinessArbitration.None -> Unit
            CaptureMetricsReadinessArbitration.Timely -> {
                var current = false
                val claimed = sessionGate.withLock {
                    if (terminalWinner.fixed || admissionsClosed || topologyFacet.metricsOwner !== exactOwner ||
                        topologyFacet.metricsJointReadiness != null
                    ) {
                        return@withLock false
                    }
                    current = true
                    exactOwner.claimLatestLocked()
                }
                if (!current) return
                val fact = if (claimed) exactOwner.materializeLatestClaimUnlocked() else null
                if (fact == null || !fact.isAvailable || fact.metrics == null || fact.sequence <= 0L) {
                    offerFailure(ScreenCaptureProblem.InternalFailure, METRICS_JOINT_READINESS_EVIDENCE_INVALID)
                    return
                }
                val committed = sessionGate.withLock {
                    if (terminalWinner.fixed || admissionsClosed || topologyFacet.metricsOwner !== exactOwner ||
                        topologyFacet.metricsJointReadiness != null
                    ) {
                        return@withLock false
                    }
                    topologyFacet.metricsJointReadiness = SessionMetricsJointReadinessFacts(
                        owner = exactOwner,
                        source = fact.source,
                        observationIdentity = fact.observationIdentity,
                        sequence = fact.sequence,
                    )
                    topologyFacet.latestMetricsFact = fact
                    topologyFacet.geometryBuildPending = true
                    true
                }
                if (committed) signal()
            }

            CaptureMetricsReadinessArbitration.Expired,
            CaptureMetricsReadinessArbitration.CompletedBeforeReadiness,
            CaptureMetricsReadinessArbitration.AvailabilityLostBeforeActive,
                -> {
                    val cause = sessionGate.withLock { exactOwner.providerCauseLocked() }
                    offerFailure(ScreenCaptureProblem.CaptureUnavailable, cause)
                }

            CaptureMetricsReadinessArbitration.FailedBeforeReadiness,
            CaptureMetricsReadinessArbitration.AttachmentFailed,
            CaptureMetricsReadinessArbitration.DeadlineGuardFailed,
                -> {
                    val cause = sessionGate.withLock { exactOwner.providerCauseLocked() }
                    offerFailure(ScreenCaptureProblem.InternalFailure, cause)
                }
        }
    }

    internal fun consumeMetricsLatest() {
        var owner: CaptureMetricsOwner? = null
        val claimed = sessionGate.withLock {
            val readiness = topologyFacet.metricsJointReadiness ?: return@withLock null
            if (topologyFacet.metricsOwner !== readiness.owner) return@withLock null
            owner = readiness.owner
            checkNotNull(owner).claimLatestLocked()
        } ?: return
        if (!claimed) return
        consumeMetricsValue(checkNotNull(owner).materializeLatestClaimUnlocked())
    }

    private fun consumeAndroidLaneStartup() {
        val owner = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.androidLaneReadyOwner != null) return@withLock null
            topologyFacet.androidOwner
        } ?: return
        when (val startup = owner.laneStartupResult) {
            AndroidLaneStartupResult.Pending -> Unit
            is AndroidLaneStartupResult.Ready -> {
                val committed = sessionGate.withLock {
                    if (terminalWinner.fixed || admissionsClosed || topologyFacet.androidOwner !== owner || topologyFacet.androidLaneReadyOwner != null) {
                        return@withLock false
                    }
                    topologyFacet.androidLaneReadyOwner = owner
                    true
                }
                if (committed) signal()
            }

            is AndroidLaneStartupResult.Failed -> offerFailure(ScreenCaptureProblem.InternalFailure, startup.cause)
        }
    }

    internal fun consumeMetricsTerminal() {
        var owner: CaptureMetricsOwner? = null
        val arbitration = sessionGate.withLock {
            owner = topologyFacet.metricsOwner ?: return@withLock CaptureMetricsTerminalArbitration.None
            checkNotNull(owner).claimTerminalLocked()
        }
        val exactOwner = owner ?: return
        when (arbitration) {
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
                -> {
                    val cause = sessionGate.withLock { exactOwner.providerCauseLocked() }
                    offerFailure(ScreenCaptureProblem.InternalFailure, cause)
                }
        }
    }

    private fun consumeMetricsValue(fact: CaptureMetricsClaimedValue) {
        val changed = sessionGate.withLock {
            if (terminalWinner.fixed || fact.sequence <= 0L) return@withLock false
            val old = topologyFacet.latestMetricsFact
            if (old != null && old.source === fact.source && old.observationIdentity == fact.observationIdentity &&
                old.sequence == fact.sequence
            ) {
                return@withLock false
            }
            topologyFacet.latestMetricsFact = fact
            topologyFacet.geometryBuildPending = true
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
        val owner = topologyFacet.androidOwner ?: return
        val provenance = fact.provenance
        if (terminalWinner.fixed || owner.apiBand == AndroidCaptureApiBand.Unsupported || provenance.owner !== owner ||
            provenance.projectionOwnerEpoch != topologyFacet.projectionEpoch ||
            provenance.callbackRegistrationIdentity != topologyFacet.acceptedProjectionCallbackRegistrationIdentity ||
            provenance.callbackIdentity != topologyFacet.projectionCallbackIdentity || fact.callbackSequence <= topologyFacet.lastAndroidCallbackSequence
        ) {
            return
        }
        topologyFacet.lastAndroidCallbackSequence = fact.callbackSequence
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
                    topologyFacet.projectionWidthPx == fact.widthPx && topologyFacet.projectionHeightPx == fact.heightPx
                ) {
                    return
                }
                topologyFacet.projectionWidthPx = fact.widthPx
                topologyFacet.projectionHeightPx = fact.heightPx
                if (!topologyFacet.projectionGeometryAvailable) {
                    topologyFacet.projectionGeometryAvailable = true
                    advanceLifecycleEpochLocked()
                }
                topologyFacet.geometryBuildPending = true
            }

            is AndroidCaptureFact.CapturedContentVisibilityChanged -> {
                if (topologyFacet.capturedContentVisible == fact.isVisible) return
                topologyFacet.capturedContentVisible = fact.isVisible
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
            if (!topologyFacet.geometryBuildPending || terminalWinner.fixed) return@withLock false
            owner = topologyFacet.androidOwner
            metricsFact = topologyFacet.latestMetricsFact
            projectionWidth = topologyFacet.projectionWidthPx
            projectionHeight = topologyFacet.projectionHeightPx
            projectionOwnerEpoch = topologyFacet.projectionEpoch
            topologyFacet.geometryBuildPending = false
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
        val candidate = SessionFullGeometryAuthority(
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
            if (terminalWinner.fixed || topologyFacet.androidOwner !== exactOwner || topologyFacet.latestMetricsFact !== exactMetricsFact ||
                topologyFacet.projectionWidthPx != projectionWidth || topologyFacet.projectionHeightPx != projectionHeight ||
                topologyFacet.projectionEpoch != projectionOwnerEpoch
            ) {
                topologyFacet.geometryBuildPending = true
                return@withLock false
            }
            if (sameGeometryAuthority(topologyFacet.combinedGeometryAuthority, candidate)) return@withLock false
            if (topologyFacet.geometryGeneration == Long.MAX_VALUE || nextIdentity == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock true
            }
            val priorAvailable = topologyFacet.combinedGeometryAuthority?.available == true
            if (priorAvailable != candidate.available && topologyFacet.combinedGeometryAuthority != null) advanceLifecycleEpochLocked()
            topologyFacet.geometryGeneration = reserveIdentityLocked()
            topologyFacet.combinedGeometryAuthority = candidate
            topologyFacet.captureGeometry = builtCaptureGeometry
            topologyFacet.reconciliationIdentity = 0L
            topologyFacet.currentCalculation = null
            topologyFacet.currentProvisional = null
            topologyFacet.currentPlan = null
            if (!effectiveAvailable && firstActiveAssigned) {
                runningPhase = RunningPhase.Suspended
                invalidateOutputLocked()
                reserveRunningPublicationLocked(ScreenCaptureProblem.CaptureUnavailable)
            }
            true
        }
        if (changed) signal()
    }

    private fun sameGeometryAuthority(left: SessionFullGeometryAuthority?, right: SessionFullGeometryAuthority): Boolean =
        left != null && left.source === right.source && left.observationIdentity == right.observationIdentity &&
                left.displayIdentity === right.displayIdentity && left.displayEpoch == right.displayEpoch &&
                left.projectionEpoch == right.projectionEpoch && left.available == right.available &&
                left.sourceWidthPx == right.sourceWidthPx && left.sourceHeightPx == right.sourceHeightPx &&
                left.projectionWidthPx == right.projectionWidthPx && left.projectionHeightPx == right.projectionHeightPx &&
                left.densityDpi == right.densityDpi

    internal fun calculateLatestReconciliation(): ReconciliationCalculation? {
        var geometry: CaptureGeometry? = null
        var geometryAuthority: SessionFullGeometryAuthority? = null
        var parameters: ScreenCaptureParameters? = null
        var capabilities: GlCapabilityFacts? = null
        var apiBand: AndroidCaptureApiBand? = null
        var stampDesiredRevision = 0L
        var stampGeometryGeneration = 0L
        var stampLifecycleEpoch = 0L
        var occurrenceIdentity = 0L
        var exactTarget: CurrentTarget? = null
        var exactGlOwner: GlPipelineOwner? = null
        var exactRender: GlPipelineOwner.GlRenderTargetOwner? = null
        var exactFramework: FrameworkJpegOwner? = null
        var exactJpegOwner: JpegRuntimeOwner? = null
        var exactAcceptedTopology: AcceptedTopologySnapshot? = null
        val claimed = sessionGate.withLock {
            if (admissionsClosed || terminalWinner.fixed || topologyFacet.metricsJointReadiness == null ||
                topologyFacet.androidLaneReadyOwner !== topologyFacet.androidOwner || topologyFacet.reconciliationIdentity != 0L ||
                topologyFacet.desiredRevision <= 0L || topologyFacet.geometryGeneration <= 0L || topologyFacet.lifecycleEpoch <= 0L
            ) {
                return@withLock false
            }
            parameters = topologyFacet.requestedParameters ?: return@withLock false
            capabilities = topologyFacet.glCapabilities ?: return@withLock false
            apiBand = topologyFacet.androidOwner?.apiBand ?: return@withLock false
            geometry = topologyFacet.captureGeometry
            geometryAuthority = topologyFacet.combinedGeometryAuthority
            stampDesiredRevision = topologyFacet.desiredRevision
            stampGeometryGeneration = topologyFacet.geometryGeneration
            stampLifecycleEpoch = topologyFacet.lifecycleEpoch
            occurrenceIdentity = reserveIdentityLocked()
            topologyFacet.reconciliationIdentity = occurrenceIdentity
            exactTarget = topologyFacet.currentTarget
            exactGlOwner = topologyFacet.glOwner
            exactRender = topologyFacet.installedRenderTarget
            exactFramework = topologyFacet.installedFrameworkOwner
            exactJpegOwner = topologyFacet.jpegOwner
            exactAcceptedTopology = topologyFacet.acceptedTopologySnapshot
            true
        }
        if (!claimed) return null
        val targetCurrentness = exactTarget?.currentnessFact()
        val installedTargetTopology = exactAcceptedTopology?.takeIf { it.target === exactTarget }
        val targetTopologyFacts = exactTarget?.let { target ->
            val currentness = checkNotNull(targetCurrentness)
            ReconciliationTargetTopologyFacts(
                plan = currentness.plan,
                installedCaptureWidthPx = installedTargetTopology?.effectiveParameters?.captureGeometry?.widthPx,
                installedCaptureHeightPx = installedTargetTopology?.effectiveParameters?.captureGeometry?.heightPx,
                reusable = currentness.targetIdentity.matches(target) && currentness.plan === target.plan &&
                        currentness.listenerInstalled && currentness.producerState == TargetProducerState.ProducerAttached &&
                        !currentness.generationFenced && !currentness.frameAdmissionRetirementClosed,
            )
        }
        val renderCurrentness = exactRender?.let { render ->
            exactGlOwner?.renderCurrentnessFact(render)
        }
        val renderTopologyFacts = renderCurrentness?.let { currentness ->
            ReconciliationRenderTopologyFacts(
                compatibility = currentness.compatibilityFacts,
                reusable = currentness.reusable,
            )
        }
        val jpegTopology = exactJpegOwner?.stableTopologySnapshot()
        val jpegTopologyFacts = jpegTopology?.product?.let { product ->
            val backend = when (product) {
                is JpegRuntimeProduct.NativeEnabled -> ReconciliationJpegBackend.NativeEnabled
                is JpegRuntimeProduct.FrameworkOnNativeCarrier -> ReconciliationJpegBackend.FrameworkOnNativeCarrier
                is JpegRuntimeProduct.FrameworkOnManagedCarrier -> ReconciliationJpegBackend.FrameworkOnManagedCarrier
            }
            ReconciliationJpegTopologyFacts(
                backend = backend,
                carrierByteCount = product.carrier.byteCount,
                reusable = true,
            )
        }
        val frameworkResourcesComplete = exactFramework?.hasCompleteResources()
        val frameworkTopologyFacts = exactFramework?.let { framework ->
            ReconciliationFrameworkTopologyFacts(
                imageSize = framework.imageSize,
                pixelByteCount = framework.pixelByteCount,
                resourcesComplete = frameworkResourcesComplete == true,
            )
        }
        val exactTopologyStillCurrent = sessionGate.withLock {
            topologyFacet.reconciliationIdentity == occurrenceIdentity &&
                    topologyFacet.desiredRevision == stampDesiredRevision &&
                    topologyFacet.geometryGeneration == stampGeometryGeneration &&
                    topologyFacet.lifecycleEpoch == stampLifecycleEpoch &&
                    topologyFacet.currentTarget === exactTarget &&
                    topologyFacet.glOwner === exactGlOwner &&
                    topologyFacet.installedRenderTarget === exactRender &&
                    topologyFacet.installedFrameworkOwner === exactFramework &&
                    topologyFacet.jpegOwner === exactJpegOwner &&
                    topologyFacet.acceptedTopologySnapshot === exactAcceptedTopology
        }
        val exactTargetCurrentnessStillCurrent = targetCurrentness?.let { currentness ->
            exactTarget?.isCurrentnessVersion(currentness.version) == true
        } ?: (exactTarget == null)
        val targetCurrentnessExhausted = targetCurrentness?.versionExhausted == true ||
                exactTarget?.currentnessVersionExhausted == true
        val exactRenderCurrentnessStillCurrent = renderCurrentness?.let { currentness ->
            exactGlOwner?.isRenderCurrentnessVersion(currentness) == true
        } ?: (exactRender == null)
        val exactGlFatal = exactGlOwner?.observedFatal
        if (exactGlFatal != null) FatalThrowablePolicy.rethrow(exactGlFatal)
        val glRenderCurrentnessExhausted = exactGlOwner?.isRenderCurrentnessVersionExhausted == true
        val renderCurrentnessInvalid = glRenderCurrentnessExhausted ||
                (exactRender != null &&
                        (renderCurrentness == null || renderCurrentness.renderTargetOwner !== exactRender ||
                                !renderCurrentness.reusable || renderCurrentness.versionExhausted ||
                                exactGlOwner?.isRenderCurrentnessVersionExhausted != false ||
                                renderCurrentness.lanePoisoned || renderCurrentness.observedFatal != null))
        if (!exactTopologyStillCurrent ||
            (!targetCurrentnessExhausted && !exactTargetCurrentnessStillCurrent) ||
            (!renderCurrentnessInvalid && !exactRenderCurrentnessStillCurrent) ||
            exactJpegOwner?.stableTopologySnapshot() !== jpegTopology ||
            exactFramework?.hasCompleteResources() != frameworkResourcesComplete
        ) {
            sessionGate.withLock {
                if (topologyFacet.reconciliationIdentity == occurrenceIdentity) {
                    topologyFacet.reconciliationIdentity = 0L
                }
            }
            return null
        }
        val stamp = TopologyStamp(stampDesiredRevision, stampGeometryGeneration, stampLifecycleEpoch)
        val exactGeometry = geometry
        val input: ReconciliationInput = if (exactGeometry != null) {
            AuthoritativeInput(
                stamp = stamp,
                reconciliationOccurrenceIdentity = occurrenceIdentity,
                apiBand = checkNotNull(apiBand),
                captureGeometry = exactGeometry,
                parameters = checkNotNull(parameters),
                currentTopology = ReconciliationCurrentTopology(
                    target = targetTopologyFacts,
                    render = renderTopologyFacts,
                    jpeg = jpegTopologyFacts,
                    framework = frameworkTopologyFacts,
                ),
                capabilities = checkNotNull(capabilities),
            )
        } else {
            val authority = geometryAuthority
            if (apiBand != AndroidCaptureApiBand.Api34To37 || authority == null || authority.sourceWidthPx <= 0 ||
                authority.sourceHeightPx <= 0 || authority.densityDpi <= 0
            ) {
                sessionGate.withLock { if (topologyFacet.reconciliationIdentity == occurrenceIdentity) topologyFacet.reconciliationIdentity = 0L }
                return null
            }
            ProvisionalBootstrapInput(
                stamp = stamp,
                reconciliationOccurrenceIdentity = occurrenceIdentity,
                apiBand = checkNotNull(apiBand),
                provisionalWidthPx = authority.sourceWidthPx,
                provisionalHeightPx = authority.sourceHeightPx,
                densityDpi = authority.densityDpi,
                capabilities = checkNotNull(capabilities),
            )
        }
        return if (targetCurrentnessExhausted || renderCurrentnessInvalid) {
            InternalFailure(input)
        } else {
            ReconciliationOwner.calculate(input)
        }
    }

    internal fun acceptReconciliation(calculation: ReconciliationCalculation): Boolean {
        val changed = sessionGate.withLock {
            val input = calculation.input
            if (terminalWinner.fixed || admissionsClosed || input.reconciliationOccurrenceIdentity != topologyFacet.reconciliationIdentity ||
                input.stamp.desiredRevision != topologyFacet.desiredRevision ||
                input.stamp.geometryGeneration != topologyFacet.geometryGeneration ||
                input.stamp.lifecycleEpoch != topologyFacet.lifecycleEpoch
            ) {
                return@withLock false
            }
            when (calculation) {
                is Resolved -> {
                    topologyFacet.currentCalculation = calculation
                    topologyFacet.currentProvisional = null
                    topologyFacet.currentPlan = calculation.targetPlan
                    true
                }

                is ProvisionalFull -> {
                    topologyFacet.currentCalculation = null
                    topologyFacet.currentProvisional = calculation
                    topologyFacet.currentPlan = calculation.targetPlan
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
        return if (firstActiveAssigned && topologyFacet.lastEffectiveParameters != null) {
            runningPhase = RunningPhase.Active
            reserveRunningPublicationLocked()
            true
        } else {
            offerFailureLocked(problem, null)
        }
    }

    internal fun installGlSessionFacts(facts: GlClaimedOperationFacts): Boolean {
        val owner = topologyFacet.glOwner ?: return false
        val accepted = sessionGate.withLock {
            if (terminalWinner.fixed || facts.operationKind != GlOperationKind.SessionConstruction ||
                facts.result != GlOperationResult.Success || !facts.timely || facts.receipt == null ||
                facts.contextIntegrity != ContextIntegrity.Intact
            ) {
                return@withLock false
            }
            val capabilities = owner.capabilityFacts ?: return@withLock false
            if (topologyFacet.glSessionFacts != null && topologyFacet.glSessionFacts !== facts) return@withLock false
            topologyFacet.glSessionFacts = facts
            topologyFacet.glCapabilities = capabilities
            true
        }
        if (accepted) signal()
        return accepted
    }

    private fun admitPreparedTarget(
        candidate: PreparedTarget,
        requestedIdentity: TargetRequestedIdentity,
        plan: TargetPlan,
        glOwner: GlPipelineOwner,
    ): PreparedTargetAdmissionFact? {
        val admissionFact = targetOwner.admitPreparedTarget(
            prospectiveTarget = candidate,
            currentRequestedIdentity = requestedIdentity,
            currentPlan = plan,
        ) ?: return null
        val rooted = sessionGate.withLock {
            if (admissionFact.preparedTarget !== candidate || admissionFact.requestedIdentity !== requestedIdentity ||
                admissionFact.targetGeneration != candidate.targetGeneration || candidate.plan !== plan ||
                topologyFacet.preparedTarget != null || topologyFacet.preparedTargetAdmissionFact != null ||
                cleanupFacet.owner.preparedTargetRoot.target != null
            ) {
                return@withLock false
            }
            topologyFacet.preparedTargetAdmissionFact = admissionFact
            val currentRequest = admissionFact.disposition == TargetConstructionAdmissionDisposition.Active &&
                    !terminalWinner.fixed && !admissionsClosed && topologyFacet.glOwner === glOwner &&
                    topologyFacet.currentTarget == null && topologyFacet.currentPlan === plan &&
                    cleanupFacet.owner.currentTargetRoot.target == null &&
                    cleanupFacet.owner.quarantineRoot.targetChild == null &&
                    isTargetRequestCurrentLocked(requestedIdentity)
            if (currentRequest) {
                topologyFacet.preparedTarget = candidate
            } else if (cleanupFacet.owner.attachPreparedTarget(candidate) != CleanupMutation.None) {
                cleanupFacet.workPending = true
            } else {
                topologyFacet.preparedTargetAdmissionFact = null
                return@withLock false
            }
            true
        }
        if (rooted) signal()
        return admissionFact.takeIf { rooted }
    }

    private fun foldPreparedTargetResult(): TargetConstructionResultFact? {
        var admissionFact: PreparedTargetAdmissionFact? = null
        var candidate: PreparedTarget? = null
        val captured = sessionGate.withLock {
            admissionFact = topologyFacet.preparedTargetAdmissionFact ?: return@withLock false
            candidate = checkNotNull(admissionFact).preparedTarget
            val exactRoot = topologyFacet.preparedTarget === candidate ||
                    cleanupFacet.owner.preparedTargetRoot.target === candidate
            val cleanupFallbackCapacity = cleanupFacet.owner.currentTargetRoot.target == null ||
                    cleanupFacet.owner.quarantineRoot.targetChild == null
            exactRoot && cleanupFallbackCapacity
        }
        if (!captured) return null
        val exactAdmission = checkNotNull(admissionFact)
        val exactCandidate = checkNotNull(candidate)
        val requestedIdentity = exactAdmission.requestedIdentity
        val plan = exactCandidate.plan
        val token = targetOwner.claimPreparedTargetResult(
            admissionFact = exactAdmission,
            expectedConstructionOperationIdentity = exactCandidate.constructionOccurrence.identity,
            currentRequestedIdentity = requestedIdentity,
            currentPlan = plan,
        ) ?: return null

        val requestedDisposition = sessionGate.withLock {
            val exactRoot = topologyFacet.preparedTargetAdmissionFact === exactAdmission &&
                    exactAdmission.preparedTarget === exactCandidate &&
                    (topologyFacet.preparedTarget === exactCandidate ||
                            cleanupFacet.owner.preparedTargetRoot.target === exactCandidate)
            when {
                terminalWinner.fixed || admissionsClosed -> TargetConstructionFoldDisposition.CleanupTerminal
                exactRoot && topologyFacet.preparedTarget === exactCandidate &&
                        topologyFacet.currentTarget == null && topologyFacet.currentPlan === plan &&
                        isTargetRequestCurrentLocked(requestedIdentity) -> TargetConstructionFoldDisposition.Install
                else -> TargetConstructionFoldDisposition.CleanupStale
            }
        }
        targetOwner.selectPreparedTargetDisposition(
            token = token,
            expectedConstructionOperationIdentity = exactCandidate.constructionOccurrence.identity,
            currentRequestedIdentity = requestedIdentity,
            currentPlan = plan,
            requestedDisposition = requestedDisposition,
        ) ?: return null
        val result = targetOwner.applyPreparedTargetFold(token) ?: return null

        sessionGate.withLock {
            val exactTopologyRoot = topologyFacet.preparedTarget === exactCandidate &&
                    topologyFacet.preparedTargetAdmissionFact === exactAdmission
            when (result) {
                is TargetConstructionInstalledFact -> {
                    val installedTarget = result.targetIdentity.target
                    val exactInstalled = result.requestedIdentity === requestedIdentity && result.plan === plan &&
                            result.constructionOperationIdentity == exactCandidate.constructionOccurrence.identity &&
                            result.targetIdentity.matches(installedTarget)
                    val acceptInstalled = exactInstalled && exactTopologyRoot && !terminalWinner.fixed && !admissionsClosed &&
                            topologyFacet.currentTarget == null && topologyFacet.currentPlan === plan &&
                            isTargetRequestCurrentLocked(requestedIdentity)
                    if (acceptInstalled) {
                        topologyFacet.currentTarget = installedTarget
                        invalidateReconciliationForTopologyMutationLocked()
                    }
                    val retainedResult = acceptInstalled || (exactInstalled &&
                            (retainCurrentTargetForCleanupLocked(installedTarget) ||
                                    retainStaleCurrentTargetInTopologyLocked(
                                        target = installedTarget,
                                        exactPreparedTarget = exactCandidate,
                                        exactAdmissionFact = exactAdmission,
                                    )))
                    if (exactTopologyRoot && retainedResult) {
                        topologyFacet.preparedTarget = null
                        topologyFacet.preparedTargetAdmissionFact = null
                    }
                }

                is TargetConstructionFailureFact -> {
                    val exactFailure = result.requestedIdentity === requestedIdentity &&
                            result.constructionOperationIdentity == exactCandidate.constructionOccurrence.identity &&
                            result.cleanupTarget === exactCandidate &&
                            result.cleanupTarget === result.targetIdentity.target &&
                            result.cleanupTarget.requestedIdentity === requestedIdentity
                    val retainedForCleanup = exactFailure &&
                            (cleanupFacet.owner.preparedTargetRoot.target === exactCandidate ||
                                    cleanupFacet.owner.attachPreparedTarget(exactCandidate) != CleanupMutation.None)
                    if (retainedForCleanup) {
                        cleanupFacet.workPending = true
                        cleanupFacet.preparedTargetFailureFact = result
                    }
                    if (exactTopologyRoot && retainedForCleanup) topologyFacet.preparedTarget = null
                }
            }
        }
        signal()
        return result
    }

    internal fun acceptRenderTargetInstallation(
        command: GlPipelineOwner.RenderTargetConstructionCommand,
        claim: io.screenstream.engine.internal.gl.GlRenderTargetConstructionClaim,
    ): Boolean {
        var target: CurrentTarget? = null
        var glOwner: GlPipelineOwner? = null
        val captured = sessionGate.withLock {
            target = topologyFacet.currentTarget
            glOwner = topologyFacet.glOwner
            target != null && glOwner != null
        }
        if (!captured) {
            command.claimCleanupDestruction(claim)?.destructionCommand?.submit()
            return false
        }
        val exactTarget = checkNotNull(target)
        val exactGlOwner = checkNotNull(glOwner)
        val targetCurrentness = exactTarget.currentnessFact()
        val currentBeforeCommit = sessionGate.withLock {
            val calculation = topologyFacet.currentCalculation ?: return@withLock false
            !terminalWinner.fixed && !admissionsClosed && topologyFacet.currentTarget === exactTarget &&
                    topologyFacet.glOwner === exactGlOwner &&
                    targetCurrentness.targetIdentity.matches(exactTarget) && !targetCurrentness.generationFenced &&
                    calculation.input.stamp.desiredRevision == topologyFacet.desiredRevision &&
                    calculation.input.stamp.geometryGeneration == topologyFacet.geometryGeneration &&
                    calculation.input.stamp.lifecycleEpoch == topologyFacet.lifecycleEpoch &&
                    topologyFacet.installedRenderTarget == null && claim.facts.result == GlOperationResult.Success &&
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
        val targetRecheck = exactTarget.currentnessFact()
        val accepted = sessionGate.withLock {
            val calculation = topologyFacet.currentCalculation
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.currentTarget !== exactTarget ||
                topologyFacet.glOwner !== exactGlOwner ||
                targetRecheck.targetIdentity !== targetCurrentness.targetIdentity ||
                targetRecheck.version != targetCurrentness.version ||
                topologyFacet.installedRenderTarget != null || calculation == null ||
                calculation.input.stamp.desiredRevision != topologyFacet.desiredRevision ||
                calculation.input.stamp.geometryGeneration != topologyFacet.geometryGeneration ||
                calculation.input.stamp.lifecycleEpoch != topologyFacet.lifecycleEpoch
            ) {
                return@withLock false
            }
            topologyFacet.installedRenderTarget = installed
            invalidateReconciliationForTopologyMutationLocked()
            true
        }
        if (!accepted) exactGlOwner.prepareRenderTargetDestruction(installed)?.submit()
        signal()
        return accepted
    }

    internal fun classifyAndSettleFrameworkCreation(
        occurrence: FrameworkResourceCreationOccurrence,
    ): FrameworkJpegOwner? {
        val current = sessionGate.withLock {
            !terminalWinner.fixed && !admissionsClosed && topologyFacet.pendingFrameworkCreation === occurrence &&
                    occurrence.desiredRevision == topologyFacet.desiredRevision && occurrence.geometryGeneration == topologyFacet.geometryGeneration &&
                    occurrence.lifecycleEpoch == topologyFacet.lifecycleEpoch
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
                if (terminalWinner.fixed || admissionsClosed || topologyFacet.pendingFrameworkCreation !== occurrence ||
                    occurrence.desiredRevision != topologyFacet.desiredRevision || occurrence.geometryGeneration != topologyFacet.geometryGeneration ||
                    occurrence.lifecycleEpoch != topologyFacet.lifecycleEpoch || topologyFacet.installedFrameworkOwner != null
                ) {
                    return@withLock false
                }
                topologyFacet.installedFrameworkOwner = installed
                topologyFacet.pendingFrameworkCreation = null
                invalidateReconciliationForTopologyMutationLocked()
                true
            }
            if (accepted) {
                signal()
                return installed
            }
        }

        sessionGate.withLock {
            if (topologyFacet.pendingFrameworkCreation === occurrence) topologyFacet.pendingFrameworkCreation = null
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
            if (lifecycle == Lifecycle.Terminal || topologyFacet.jpegOwner !== owner.jpegRuntimeOwner) {
                recordCleanupMutationLocked(cleanupFacet.owner.attachJpeg(owner.jpegRuntimeOwner))
            }
            recordCleanupMutationLocked(cleanupFacet.owner.attachFramework(owner))
            if (cleanupFacet.frameworkRecycle == null) cleanupFacet.frameworkRecycle = recycle
            cleanupFacet.workPending = true
        }
    }

    internal fun settleSafeNativeFailure(
        occurrence: NativeEncodeOccurrence,
        returnedFatal: Throwable? = null,
    ): Boolean {
        val owner = topologyFacet.jpegOwner ?: return false
        val storage = topologyFacet.storageOwner ?: return false
        if (returnedFatal != null) {
            val child = ReturnedNativeFatalCleanupChild(owner, occurrence, storage)
            sessionGate.withLock {
                val attached = cleanupFacet.owner.attachJpeg(owner) != CleanupMutation.None || cleanupFacet.owner.jpegRoot.owner === owner
                val storageAttached = cleanupFacet.owner.attachStorage(storage) != CleanupMutation.None ||
                        cleanupFacet.owner.storageRoot.owner === storage
                if (attached && storageAttached) cleanupFacet.owner.attachReturnedNativeFatal(child)
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
        var readiness: SessionMetricsJointReadinessFacts? = null
        var android: AndroidCaptureOwner? = null
        var gl: GlPipelineOwner? = null
        var target: CurrentTarget? = null
        var renderTarget: GlPipelineOwner.GlRenderTargetOwner? = null
        var runtime: JpegRuntimeOwner? = null
        var framework: FrameworkJpegOwner? = null
        var parameters: ScreenCaptureParameters? = null
        var visible: Boolean? = null
        var registrationIdentity = 0L
        val selected = sessionGate.withLock {
            calculation = topologyFacet.currentCalculation
            metrics = topologyFacet.metricsOwner
            readiness = topologyFacet.metricsJointReadiness
            android = topologyFacet.androidOwner
            gl = topologyFacet.glOwner
            target = topologyFacet.currentTarget
            renderTarget = topologyFacet.installedRenderTarget
            runtime = topologyFacet.jpegOwner
            framework = topologyFacet.installedFrameworkOwner
            parameters = topologyFacet.requestedParameters
            visible = topologyFacet.capturedContentVisible
            registrationIdentity = topologyFacet.acceptedProjectionCallbackRegistrationIdentity
            !terminalWinner.fixed && !admissionsClosed && calculation != null && metrics != null && readiness != null &&
                    android != null && gl != null && target != null && renderTarget != null && runtime != null &&
                    parameters != null && registrationIdentity > 0L &&
                    checkNotNull(calculation).effectiveParameters == effectiveParameters &&
                    cleanupFacet.owner.currentTargetRoot.target == null && cleanupFacet.owner.jpegRoot.owner == null &&
                    cleanupFacet.owner.jpegRoot.frameworkOwner == null
        }
        if (!selected) return false
        val exactReadiness = checkNotNull(readiness)
        val topology = SessionReconfiguration.captureCompleteTopology(SessionTopologyCaptureCommand(
            stamp = checkNotNull(calculation).input.stamp,
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
        val targetRecheck = SessionReconfiguration.revalidate(topology) ?: return false
        val publicSnapshot = ObservationStateSnapshot.Running(
            requestedParameters = checkNotNull(parameters),
            runningState = ObservationRunningStateSnapshot.Active(effectiveParameters),
            capturedContentVisible = visible,
        )
        val committed = sessionGate.withLock {
            val currentReadiness = topologyFacet.metricsJointReadiness
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.currentCalculation !== calculation ||
                topology.stamp.desiredRevision != topologyFacet.desiredRevision ||
                topology.stamp.geometryGeneration != topologyFacet.geometryGeneration ||
                topology.stamp.lifecycleEpoch != topologyFacet.lifecycleEpoch ||
                topologyFacet.metricsOwner !== topology.metricsOwner ||
                currentReadiness == null || currentReadiness.owner !== topology.metricsOwner ||
                currentReadiness.source !== topology.metricsSource ||
                currentReadiness.observationIdentity != topology.metricsObservationIdentity ||
                currentReadiness.sequence != topology.metricsReadinessSequence ||
                topologyFacet.androidOwner !== topology.androidOwner ||
                topologyFacet.acceptedProjectionCallbackRegistrationIdentity != topology.projectionRegistrationIdentity ||
                topologyFacet.glOwner !== topology.glOwner || topologyFacet.currentTarget !== topology.target ||
                targetRecheck.targetIdentity !== topology.targetCurrentness.targetIdentity ||
                targetRecheck.version != topology.targetCurrentness.version ||
                topologyFacet.installedRenderTarget !== topology.renderTarget || topologyFacet.jpegOwner !== topology.jpegOwner ||
                checkNotNull(runtime).stableTopologySnapshot() !== topology.jpegTopology ||
                topologyFacet.installedFrameworkOwner !== topology.frameworkOwner || topologyFacet.requestedParameters !== parameters
            ) {
                return@withLock false
            }
            topologyFacet.acceptedTopologySnapshot = topology
            topologyFacet.lastEffectiveParameters = effectiveParameters
            lifecycle = Lifecycle.Running
            runningPhase = RunningPhase.Active
            if (!firstActiveAssigned) topologyFacet.metricsOwner?.commitFirstActiveLocked()
            statsFacet.publicStatePublicationIdentity = reserveIdentityLocked()
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
            if (statsFacet.cutoff || encodedByteCount <= 0 || encodeDurationNanos < 0L) return
            statsFacet.framesEncoded = saturatedIncrement(statsFacet.framesEncoded)
            statsFacet.encodeSamples = saturatedIncrement(statsFacet.encodeSamples)
            statsFacet.encodeMeanNanos = orderedMean(
                statsFacet.encodeMeanNanos,
                encodeDurationNanos.toDouble(),
                statsFacet.encodeSamples,
            )
            statsFacet.encodedByteSamples = saturatedIncrement(statsFacet.encodedByteSamples)
            statsFacet.encodedByteMean = orderedMean(
                statsFacet.encodedByteMean,
                encodedByteCount.toDouble(),
                statsFacet.encodedByteSamples,
            )
            statsFacet.lastEncodedByteCount = encodedByteCount
        }
    }

    internal fun recordMechanicallySuccessfulReadback(readbackDurationNanos: Long) {
        sessionGate.withLock {
            if (statsFacet.cutoff || readbackDurationNanos < 0L) return
            statsFacet.readbackSamples = saturatedIncrement(statsFacet.readbackSamples)
            statsFacet.readbackMeanNanos = orderedMean(
                statsFacet.readbackMeanNanos,
                readbackDurationNanos.toDouble(),
                statsFacet.readbackSamples,
            )
        }
    }

    internal fun recordProduced(timestampNanos: Long) {
        sessionGate.withLock {
            if (statsFacet.cutoff || timestampNanos < 0L) return
            statsFacet.framesProduced = saturatedIncrement(statsFacet.framesProduced)
            if (statsFacet.firstProducedNanos == Long.MIN_VALUE) statsFacet.firstProducedNanos = timestampNanos
            statsFacet.lastProducedNanos = timestampNanos
        }
    }

    internal fun executeWakeSubmission(
        action: ControlWakeScheduleAction,
    ) {
        if (isAcceptedControlRuntimeReleased()) return
        when (action.claimInvocation(controlPoisonAuthority)) {
            ControlPoisonClaimOutcome.PoisonFenced -> return
            ControlPoisonClaimOutcome.ClaimExhausted -> {
                settleControlClaimExhausted(controlScheduleClaimExhaustedFact, controlScheduleSecondaryFact)
                return
            }

            ControlPoisonClaimOutcome.Admitted -> Unit
        }
        val nowNanos = try {
            clock.nowNanos()
        } catch (raw: Throwable) {
            val directFatalRaw = if (FatalThrowablePolicy.isDirectFatal(raw)) raw else null
            val publicationOutcome = if (directFatalRaw != null) {
                action.publishDirectFatalPreInvocationFailure(raw)
            } else {
                action.publishSignalledPreInvocationFailure(raw)
            }
            when (publicationOutcome) {
                ControlWakeActionPublicationOutcome.Published -> Unit
                ControlWakeActionPublicationOutcome.NotEligible ->
                    publishAggregateFact(controlScheduleInvariantFact, CONTROL_SCHEDULE_PREINVOCATION_FACT_NOT_ELIGIBLE)
            }
            settleRecordedControlFailure(raw, directFatalRaw, controlScheduleSecondaryFact)
            return
        }
        val scheduler = controlScheduler.get() ?: run {
            when (action.publishSignalledPreInvocationFailure(CONTROL_SCHEDULER_UNAVAILABLE)) {
                ControlWakeActionPublicationOutcome.Published -> Unit
                ControlWakeActionPublicationOutcome.NotEligible ->
                    publishAggregateFact(controlScheduleInvariantFact, CONTROL_SCHEDULE_PREINVOCATION_FACT_NOT_ELIGIBLE)
            }
            settleRecordedControlFailure(
                CONTROL_SCHEDULER_UNAVAILABLE,
                null,
                controlScheduleSecondaryFact,
            )
            return
        }
        val delay = maxOf(0L, action.dueNanos - nowNanos)
        when (action.markInvocationStarted(scheduler.runtimeOwner)) {
            ControlWakeActionPublicationOutcome.Published -> Unit
            ControlWakeActionPublicationOutcome.NotEligible -> {
                publishAggregateFact(controlScheduleInvariantFact, CONTROL_SCHEDULE_MARK_NOT_ELIGIBLE)
                settleRecordedControlFailure(
                    CONTROL_SCHEDULE_MARK_NOT_ELIGIBLE,
                    null,
                    controlScheduleSecondaryFact,
                )
                return
            }
        }
        val future = try {
            scheduler.schedule(action.runner, delay, TimeUnit.NANOSECONDS)
        } catch (raw: Throwable) {
            when (action.publishInvocationFailure(raw)) {
                ControlWakeScheduleFailurePublicationOutcome.Rejected,
                ControlWakeScheduleFailurePublicationOutcome.Ambiguous,
                    -> Unit

                ControlWakeScheduleFailurePublicationOutcome.NotEligible ->
                    publishAggregateFact(controlScheduleInvariantFact, CONTROL_SCHEDULE_FAILURE_FACT_NOT_ELIGIBLE)
            }
            val directFatalRaw = if (FatalThrowablePolicy.isDirectFatal(raw)) raw else null
            settleRecordedControlFailure(raw, directFatalRaw, controlScheduleSecondaryFact)
            return
        }
        val outer = future as Runnable
        when (action.publishReturned(future, outer)) {
            ControlWakeScheduleReturnPublicationOutcome.Accepted -> Unit
            ControlWakeScheduleReturnPublicationOutcome.Detached -> executeDetachedScheduleReturn(action)
            ControlWakeScheduleReturnPublicationOutcome.NotEligible -> {
                publishAggregateFact(controlScheduleInvariantFact, CONTROL_SCHEDULE_RETURN_NOT_ELIGIBLE)
                settleRecordedControlFailure(
                    CONTROL_SCHEDULE_RETURN_NOT_ELIGIBLE,
                    null,
                    controlScheduleSecondaryFact,
                )
            }
        }
    }

    internal fun executeWakeCancellation(
        link: ControlWakeLink,
        action: ControlWakeCancellationAction,
    ) {
        if (isAcceptedControlRuntimeReleased()) return
        val scheduler = controlScheduler.get() ?: run {
            publishAggregateFact(controlSchedulerUnavailableFact, CONTROL_SCHEDULER_UNAVAILABLE)
            controlPoisonAuthority.publish(CONTROL_SCHEDULER_UNAVAILABLE)
            when (action.claimCancelInvocation(controlPoisonAuthority)) {
                ControlPoisonClaimOutcome.PoisonFenced -> Unit
                ControlPoisonClaimOutcome.ClaimExhausted -> publishAggregateFact(
                    controlCancelClaimExhaustedFact,
                    checkNotNull(controlPoisonAuthority.observe()),
                )

                ControlPoisonClaimOutcome.Admitted -> {
                    publishAggregateFact(controlCancellationInvariantFact, CONTROL_CANCEL_ADMITTED_WITHOUT_SCHEDULER)
                }
            }
            settleRecordedControlFailure(
                CONTROL_SCHEDULER_UNAVAILABLE,
                null,
                controlCancellationSecondaryFact,
            )
            return
        }
        val future = checkNotNull(action.future)
        val outer = checkNotNull(action.outerWrapper)
        var cancelReturned: Boolean? = null
        var cancelFailure: Throwable? = null
        var suppression = ControlWakeSuppressionDisposition.NotAttempted
        when (action.claimCancelInvocation(controlPoisonAuthority)) {
            ControlPoisonClaimOutcome.PoisonFenced -> return
            ControlPoisonClaimOutcome.ClaimExhausted -> {
                settleControlClaimExhausted(controlCancelClaimExhaustedFact, controlCancellationSecondaryFact)
                return
            }

            ControlPoisonClaimOutcome.Admitted -> Unit
        }
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

        val directFatalCancellation =
            cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)
        var removalReturned: Boolean? = null
        var removalFailure: Throwable? = null
        var removeClaimExhausted = false
        val removalDisposition = when {
            directFatalCancellation -> ControlWakeOuterRemovalDisposition.NotAttempted
            else -> when (action.claimRemoveInvocation(controlPoisonAuthority)) {
                ControlPoisonClaimOutcome.PoisonFenced -> ControlWakeOuterRemovalDisposition.PoisonFenced
                ControlPoisonClaimOutcome.ClaimExhausted -> {
                    removeClaimExhausted = true
                    ControlWakeOuterRemovalDisposition.PoisonFenced
                }

                ControlPoisonClaimOutcome.Admitted -> try {
                    removalReturned = scheduler.remove(outer)
                    ControlWakeOuterRemovalDisposition.Returned
                } catch (raw: Throwable) {
                    removalFailure = raw
                    ControlWakeOuterRemovalDisposition.Thrown
                }
            }
        }
        val firstFailure = cancelFailure ?: removalFailure
        val cancellationPublished = link.publishCancellation(
                action = action,
                cancelReturned = cancelReturned,
                cancelFailure = cancelFailure,
                suppressionDisposition = suppression,
                removalDisposition = removalDisposition,
                removalReturned = removalReturned,
                removalFailure = removalFailure,
            )
        if (!cancellationPublished) {
            publishAggregateFact(
                controlCancellationInvariantFact,
                firstFailure ?: CONTROL_CANCELLATION_FACT_NOT_ELIGIBLE,
            )
        }
        val exactDirectFatal = when {
            cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure) -> cancelFailure
            cancellationPublished &&
                    (link.outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.DirectFatal ||
                            link.outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.OtherThrowable) ->
                checkNotNull(removalFailure)

            removalFailure != null && FatalThrowablePolicy.isDirectFatal(removalFailure) -> removalFailure
            else -> null
        }
        if (exactDirectFatal != null) {
            settleRecordedControlFailure(
                checkNotNull(firstFailure),
                exactDirectFatal,
                controlCancellationSecondaryFact,
            )
        } else if (cancelFailure != null) {
            settleRecordedControlFailure(cancelFailure, null, controlCancellationSecondaryFact)
        } else if (removalFailure != null &&
            (!cancellationPublished ||
                    link.outerRemovalFailureDisposition != ControlWakeRemovalFailureDisposition.OrdinaryException)
        ) {
            settleRecordedControlFailure(removalFailure, null, controlCancellationSecondaryFact)
        } else if (removeClaimExhausted) {
            settleControlClaimExhausted(controlRemoveClaimExhaustedFact, controlCancellationSecondaryFact)
        }
    }

    private fun executeDetachedScheduleReturn(action: ControlWakeScheduleAction) {
        if (isAcceptedControlRuntimeReleased()) return
        val future = checkNotNull(action.returnedFuture)
        val outer = checkNotNull(action.returnedOuterWrapper)
        val scheduler = controlScheduler.get() ?: run {
            publishAggregateFact(controlSchedulerUnavailableFact, CONTROL_SCHEDULER_UNAVAILABLE)
            controlPoisonAuthority.publish(CONTROL_SCHEDULER_UNAVAILABLE)
            when (action.claimDetachedCancelInvocation(controlPoisonAuthority)) {
                ControlPoisonClaimOutcome.PoisonFenced -> Unit
                ControlPoisonClaimOutcome.ClaimExhausted ->
                    publishAggregateFact(
                        controlDetachedCancelClaimExhaustedFact,
                        checkNotNull(controlPoisonAuthority.observe()),
                    )

                ControlPoisonClaimOutcome.Admitted ->
                    publishAggregateFact(controlDetachedInvariantFact, CONTROL_DETACHED_ADMITTED_WITHOUT_SCHEDULER)
            }
            settleRecordedControlFailure(
                CONTROL_SCHEDULER_UNAVAILABLE,
                null,
                controlDetachedSecondaryFact,
            )
            return
        }
        var cancelFailure: Throwable? = null
        when (action.claimDetachedCancelInvocation(controlPoisonAuthority)) {
            ControlPoisonClaimOutcome.PoisonFenced -> return
            ControlPoisonClaimOutcome.ClaimExhausted -> {
                settleControlClaimExhausted(
                    controlDetachedCancelClaimExhaustedFact,
                    controlDetachedSecondaryFact,
                )
                return
            }

            ControlPoisonClaimOutcome.Admitted -> Unit
        }
        var cancelReturned: Boolean? = null
        var suppression = ControlWakeSuppressionDisposition.NotAttempted
        try {
            cancelReturned = future.cancel(false)
            if (cancelReturned) {
                suppression = if (action.trySuppressDetached()) {
                    ControlWakeSuppressionDisposition.Succeeded
                } else {
                    ControlWakeSuppressionDisposition.Failed
                }
            }
        } catch (raw: Throwable) {
            cancelFailure = raw
        }
        val directFatalCancellation =
            cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)
        var removalReturned: Boolean? = null
        var removalFailure: Throwable? = null
        var removeClaimExhausted = false
        val removalDisposition: ControlWakeOuterRemovalDisposition
        if (!directFatalCancellation) {
            removalDisposition = when (action.claimDetachedRemoveInvocation(controlPoisonAuthority)) {
                ControlPoisonClaimOutcome.PoisonFenced -> ControlWakeOuterRemovalDisposition.PoisonFenced
                ControlPoisonClaimOutcome.ClaimExhausted -> {
                    removeClaimExhausted = true
                    ControlWakeOuterRemovalDisposition.PoisonFenced
                }

                ControlPoisonClaimOutcome.Admitted -> try {
                    removalReturned = scheduler.remove(outer)
                    ControlWakeOuterRemovalDisposition.Returned
                } catch (raw: Throwable) {
                    removalFailure = raw
                    ControlWakeOuterRemovalDisposition.Thrown
                }
            }
        } else {
            removalDisposition = ControlWakeOuterRemovalDisposition.NotAttempted
        }
        val firstFailure = cancelFailure ?: removalFailure
        val detachedSettlementPublished = when (action.publishDetachedSettlement(
            cancelReturned = cancelReturned,
            cancelFailure = cancelFailure,
            suppressionDisposition = suppression,
            removalDisposition = removalDisposition,
            removalReturned = removalReturned,
            removalFailure = removalFailure,
        )) {
            ControlWakeActionPublicationOutcome.Published -> true
            ControlWakeActionPublicationOutcome.NotEligible -> {
                publishAggregateFact(
                    controlDetachedInvariantFact,
                    firstFailure ?: CONTROL_DETACHED_FACT_NOT_ELIGIBLE,
                )
                false
            }
        }
        val exactDirectFatal = when {
            cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure) -> cancelFailure
            detachedSettlementPublished &&
                    (action.detachedRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.DirectFatal ||
                            action.detachedRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.OtherThrowable) ->
                checkNotNull(removalFailure)

            removalFailure != null && FatalThrowablePolicy.isDirectFatal(removalFailure) -> removalFailure
            else -> null
        }
        if (exactDirectFatal != null) {
            settleRecordedControlFailure(
                checkNotNull(firstFailure),
                exactDirectFatal,
                controlDetachedSecondaryFact,
            )
        } else if (cancelFailure != null) {
            settleRecordedControlFailure(cancelFailure, null, controlDetachedSecondaryFact)
        } else if (removalFailure != null &&
            (!detachedSettlementPublished ||
                    action.detachedRemovalFailureDisposition != ControlWakeRemovalFailureDisposition.OrdinaryException)
        ) {
            settleRecordedControlFailure(removalFailure, null, controlDetachedSecondaryFact)
        } else if (removeClaimExhausted) {
            settleControlClaimExhausted(
                controlDetachedRemoveClaimExhaustedFact,
                controlDetachedSecondaryFact,
            )
        }
    }

    private fun submitDrainer() {
        if (isAcceptedControlRuntimeReleased()) {
            drainerState.set(DRAIN_IDLE)
            return
        }
        val scheduler = controlScheduler.get() ?: run {
            if (controlPoisonAuthority.observe() == null) {
                preBarrierDirty.set(true)
            } else {
                drainerState.set(DRAIN_IDLE)
            }
            return
        }
        val generation = drainerGeneration.updateAndGet { current ->
            if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
        }
        if (generation == Long.MAX_VALUE) {
            try {
                publishAggregateFact(controlDrainerIdentityExhaustedFact, IDENTITY_EXHAUSTED)
                settleRecordedControlFailure(IDENTITY_EXHAUSTED, null, controlDrainerSecondaryFact)
            } finally {
                drainerState.set(DRAIN_IDLE)
            }
            return
        }
        val record = drainerRecords[(generation and 1L).toInt()]
        if (!record.prepare(generation)) {
            try {
                publishAggregateFact(controlDrainerRecordReuseFact, CONTROL_DRAINER_RECORD_REUSE)
                settleRecordedControlFailure(CONTROL_DRAINER_RECORD_REUSE, null, controlDrainerSecondaryFact)
            } finally {
                drainerState.set(DRAIN_IDLE)
            }
            return
        }
        when (record.claimSubmissionInvocation(generation)) {
            SessionControlDrainerInvocationClaimOutcome.PoisonFenced -> {
                drainerState.set(DRAIN_IDLE)
                return
            }

            SessionControlDrainerInvocationClaimOutcome.ClaimExhausted -> {
                try {
                    val raw = checkNotNull(controlPoisonAuthority.observe())
                    settleRecordedControlFailure(raw, null, controlDrainerSecondaryFact)
                } finally {
                    drainerState.set(DRAIN_IDLE)
                }
                return
            }

            SessionControlDrainerInvocationClaimOutcome.NotEligible -> {
                try {
                    publishAggregateFact(controlDrainerRecordReuseFact, CONTROL_DRAINER_RECORD_REUSE)
                    settleRecordedControlFailure(CONTROL_DRAINER_RECORD_REUSE, null, controlDrainerSecondaryFact)
                } finally {
                    drainerState.set(DRAIN_IDLE)
                }
                return
            }

            SessionControlDrainerInvocationClaimOutcome.Admitted -> Unit
        }
        try {
            scheduler.execute(record.runner)
        } catch (raw: Throwable) {
            try {
                when (record.publishSubmissionFailure(generation, raw)) {
                    SessionControlDrainerSubmissionFailurePublicationOutcome.Rejected,
                    SessionControlDrainerSubmissionFailurePublicationOutcome.Ambiguous,
                        -> Unit

                    SessionControlDrainerSubmissionFailurePublicationOutcome.NotEligible ->
                        publishAggregateFact(controlDrainerRecordReuseFact, raw)
                }
                val directFatalRaw = if (FatalThrowablePolicy.isDirectFatal(raw)) raw else null
                settleRecordedControlFailure(raw, directFatalRaw, controlDrainerSecondaryFact)
            } finally {
                drainerState.set(DRAIN_IDLE)
            }
            return
        }
        when (record.publishSubmissionAccepted(generation)) {
            SessionControlDrainerSubmissionAcceptancePublicationOutcome.Published -> Unit
            SessionControlDrainerSubmissionAcceptancePublicationOutcome.NotEligible -> {
                publishAggregateFact(controlDrainerRecordReuseFact, CONTROL_DRAINER_ACCEPTANCE_NOT_ELIGIBLE)
                settleRecordedControlFailure(
                    CONTROL_DRAINER_ACCEPTANCE_NOT_ELIGIBLE,
                    null,
                    controlDrainerSecondaryFact,
                )
            }
        }
    }

    private fun runDrainer() {
        if (isAcceptedControlRuntimeReleased()) {
            drainerState.set(DRAIN_IDLE)
            return
        }
        val poisonedRaw = controlPoisonAuthority.observe()
        if (poisonedRaw != null) {
            emergencyFailClosed(poisonedRaw)
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
                sessionGate.withLock { topologyFacet.metricsOwner }?.readinessWakeLink?.let(::serviceWakeLink)
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
            statsFacet.publicStatePublicationIdentity = reserveIdentityLocked()
            slots.publishStarting = true
        }
        if (deliveryFacet.targetAdmissionClosePending) {
            deliveryFacet.targetAdmissionClosePending = false
            slots.closeTargetAdmission = true
        }
        if (statsFacet.runningPublicationPending && lifecycle == Lifecycle.Running) {
            val parameters = topologyFacet.requestedParameters
            val effective = topologyFacet.lastEffectiveParameters
            if (parameters != null && effective != null) {
                slots.buildRunningOutput = true
                slots.runningParameters = parameters
                slots.runningEffective = effective
                slots.runningProblem = statsFacet.runningPublicationProblem
                slots.runningGeometry = topologyFacet.captureGeometry
                slots.runningVisible = topologyFacet.capturedContentVisible
                slots.runningIsActive = statsFacet.runningPublicationProblem == null && runningPhase == RunningPhase.Active
                statsFacet.publicStatePublicationIdentity = reserveIdentityLocked()
            }
            statsFacet.runningPublicationPending = false
            statsFacet.runningPublicationProblem = null
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
        if (slots.publishStarting) slots.publishStats = statsFacet.snapshot()
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
        val parameters = checkNotNull(topologyFacet.requestedParameters)
        slots.terminalStats = statsFacet.snapshot()
        slots.terminalState = when (terminalWinner.kind) {
            TerminalKind.CaptureEnded,
            TerminalKind.OwnerStop,
                -> ObservationStateSnapshot.Stopped(
                reason = checkNotNull(terminalWinner.stopReason),
                requestedParameters = parameters,
                lastEffectiveParameters = topologyFacet.lastEffectiveParameters,
            )

            TerminalKind.Failed -> ObservationStateSnapshot.Failed(
                problem = terminalWinner.problem ?: ScreenCaptureProblem.InternalFailure,
                requestedParameters = parameters,
                lastEffectiveParameters = topologyFacet.lastEffectiveParameters,
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
        statsFacet.cutoff = true
        lifecycle = Lifecycle.Terminal
        runningPhase = null
        advanceLifecycleEpochLocked()
        invalidateOutputLocked()

        val startupBag = pendingStartupBag
        val exactMetrics = topologyFacet.metricsOwner ?: startupBag?.metrics
        val exactAndroid = topologyFacet.androidOwner ?: startupBag?.android
        val exactPrepared = topologyFacet.preparedTarget
        val exactCurrent = topologyFacet.currentTarget
        val exactRender = topologyFacet.installedRenderTarget
        val exactGl = topologyFacet.glOwner ?: startupBag?.gl
        val exactJpeg = topologyFacet.jpegOwner ?: startupBag?.jpeg
        val exactStorage = topologyFacet.storageOwner ?: startupBag?.storage
        if (exactMetrics != null && cleanupFacet.owner.attachMetrics(exactMetrics) != CleanupMutation.None) cleanupFacet.workPending = true
        if (exactAndroid != null && cleanupFacet.owner.attachAndroid(exactAndroid) != CleanupMutation.None) cleanupFacet.workPending = true
        if (exactPrepared != null && cleanupFacet.owner.attachPreparedTarget(exactPrepared) != CleanupMutation.None) cleanupFacet.workPending = true
        if (exactCurrent != null && cleanupFacet.owner.attachCurrentTarget(exactCurrent) != CleanupMutation.None) cleanupFacet.workPending = true
        if (exactGl != null && cleanupFacet.owner.attachGl(exactGl) != CleanupMutation.None) cleanupFacet.workPending = true
        if (exactJpeg != null && cleanupFacet.owner.attachJpeg(exactJpeg) != CleanupMutation.None) cleanupFacet.workPending = true
        topologyFacet.installedFrameworkOwner?.let { framework ->
            if (cleanupFacet.owner.attachFramework(framework) != CleanupMutation.None) cleanupFacet.workPending = true
        }
        if (exactStorage != null && cleanupFacet.owner.attachStorage(exactStorage) != CleanupMutation.None) cleanupFacet.workPending = true

        topologyFacet.metricsOwner = null
        topologyFacet.metricsJointReadiness = null
        topologyFacet.androidOwner = null
        topologyFacet.androidLaneReadyOwner = null
        topologyFacet.projection = null
        topologyFacet.preparedTarget = null
        topologyFacet.currentTarget = null
        topologyFacet.glOwner = null
        topologyFacet.jpegOwner = null
        topologyFacet.storageOwner = null
        pendingStartupBag = null
        topologyFacet.installedRenderTarget = null
        cleanupFacet.renderOwner = exactRender
        topologyFacet.installedFrameworkOwner = null
        topologyFacet.currentCalculation = null
        topologyFacet.currentProvisional = null
        topologyFacet.currentPlan = exactPrepared?.plan
        topologyFacet.latestMetricsFact = null
        topologyFacet.combinedGeometryAuthority = null
        topologyFacet.captureGeometry = null

        statsFacet.publicStatsPublicationIdentity = reserveIdentityLocked()
        slots.buildTerminalOutputs = true
        slots.terminalDiagnosticSequence = reserveDiagnosticSequenceLocked()
        slots.terminalDiagnosticKind = terminalWinner.kind
        slots.terminalDiagnosticProblem = terminalWinner.problem
        slots.terminalDiagnosticCause = terminalWinner.cause
        statsFacet.publicStatePublicationIdentity = reserveIdentityLocked()
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
        var metricsCloseRequested = false
        val metricsForPreparation = sessionGate.withLock {
            val owner = cleanupFacet.owner.metricsRoot.owner ?: return@withLock null
            metricsCloseRequested = owner.requestCloseLocked()
            owner
        }
        if (metricsForPreparation != null) {
            metricsForPreparation.applyCloseRequestEffectsUnlocked(metricsCloseRequested)
            metricsForPreparation.prepareEndpointShutdown()
        }
        advancePreparedTargetCleanup()
        val targetForProgress = sessionGate.withLock { cleanupFacet.owner.currentTargetRoot.target }
        if (targetForProgress != null) advanceTerminalTargetCleanup(targetForProgress)
        val androidForProgress = sessionGate.withLock { cleanupFacet.owner.androidRoot.owner }
        if (androidForProgress != null) advanceTerminalAndroidCleanup(androidForProgress)
        advanceTerminalFrameworkCleanup()
        advanceTerminalJpegProductCleanup()
        val glForProgress = sessionGate.withLock { cleanupFacet.owner.glRoot.owner }
        if (glForProgress != null) advanceTerminalGlCleanup(glForProgress)

        sessionGate.withLock {
            if (lifecycle != Lifecycle.Terminal && !cleanupFacet.workPending) return
            cleanupFacet.workPending = false
            metricsAction = cleanupFacet.owner.claimMetricsShutdownAction()
            androidAction = cleanupFacet.owner.claimAndroidQuitAction()
            existingGlAction = cleanupFacet.owner.claimExistingGlShutdownAction()
            if (existingGlAction == null) glForPreparation = cleanupFacet.owner.selectGlShutdownOwner()
            jpegAction = cleanupFacet.owner.claimJpegShutdownAction()
            existingStorageAction = cleanupFacet.owner.claimExistingStorageRetirementAction()
            if (existingStorageAction == null) storageForPreparation = cleanupFacet.owner.selectStorageRetirementOwner()
            returnedNativeFatal = cleanupFacet.owner.selectReturnedNativeFatal()
            awaitingStage5 = cleanupFacet.owner.controlShutdownReadiness == ControlShutdownReadiness.AwaitingStage5Delivery
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
                    metricsForPreparation?.let { recordCleanupMutationLocked(cleanupFacet.owner.quarantineMetrics(it)) }
                }
                if (attempt.androidFailure != null) {
                    androidForProgress?.let { recordCleanupMutationLocked(cleanupFacet.owner.quarantineAndroid(it)) }
                }
                if (attempt.glFailure != null) {
                    glForProgress?.let { recordCleanupMutationLocked(cleanupFacet.owner.quarantineGl(it)) }
                }
                if (attempt.jpegFailure != null) {
                    cleanupFacet.owner.jpegRoot.owner?.let { recordCleanupMutationLocked(cleanupFacet.owner.quarantineJpeg(it)) }
                }
                if (attempt.storageFailure != null) {
                    cleanupFacet.owner.storageRoot.owner?.let { recordCleanupMutationLocked(cleanupFacet.owner.quarantineStorage(it)) }
                }
            }
        }

        val quarantinedTargetChild = sessionGate.withLock { cleanupFacet.owner.quarantineRoot.targetChild }
        val quarantinedPreparedRetired = when (quarantinedTargetChild) {
            is TargetQuarantineChild.Prepared -> if (quarantinedTargetChild.target.isCleanupComplete()) {
                targetOwner.retireMechanicallyCompletedPreparedTarget(quarantinedTargetChild.target)
            } else {
                null
            }

            else -> null
        }
        val quarantinedCurrentComplete = when (quarantinedTargetChild) {
            is TargetQuarantineChild.Current -> quarantinedTargetChild.target.retirementSuffixEvidence()
                .takeIf { it is TargetRetirementCompleteEvidence && it.targetIdentity.matches(quarantinedTargetChild.target) }

            else -> null
        }

        sessionGate.withLock {
            cleanupFacet.owner.metricsRoot.owner?.endpointTerminationReceipt?.let { receipt ->
                if (cleanupFacet.owner.reduceMetrics(receipt) != CleanupMutation.None) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.androidRoot.owner?.laneTerminationReceipt?.let { receipt ->
                if (cleanupFacet.owner.reduceAndroid(receipt) != CleanupMutation.None) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.glRoot.owner?.laneTerminationReceipt?.let { receipt ->
                if (cleanupFacet.owner.reduceGl(receipt) != CleanupMutation.None) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.jpegRoot.owner?.jpegTerminationReceipt?.let { receipt ->
                if (cleanupFacet.owner.reduceJpeg(receipt) != CleanupMutation.None) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.storageRoot.owner?.let { owner ->
                if (cleanupFacet.owner.reduceStorage(owner) != CleanupMutation.None) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.quarantineRoot.metrics?.endpointTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedMetrics(receipt))) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.quarantineRoot.android?.laneTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedAndroid(receipt))) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.quarantineRoot.gl?.laneTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedGl(receipt))) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.quarantineRoot.jpeg?.jpegTerminationReceipt?.let { receipt ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedJpeg(receipt))) cleanupFacet.workPending = true
            }
            cleanupFacet.owner.quarantineRoot.framework?.let { owner ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedFrameworkProvenComplete(owner))) {
                    cleanupFacet.workPending = true
                }
            }
            cleanupFacet.owner.quarantineRoot.storage?.let { owner ->
                if (recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedStorage(owner))) cleanupFacet.workPending = true
            }
            when (val child = cleanupFacet.owner.quarantineRoot.targetChild) {
                is TargetQuarantineChild.Prepared -> if (child === quarantinedTargetChild &&
                    quarantinedPreparedRetired?.retiredTarget === child.target &&
                    recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedPreparedTargetProvenComplete(child.target))
                ) {
                    if (topologyFacet.preparedTargetAdmissionFact?.preparedTarget === child.target) {
                        topologyFacet.preparedTargetAdmissionFact = null
                    }
                    if (cleanupFacet.preparedTargetFailureFact?.let { fact ->
                            fact.requestedIdentity === child.target.requestedIdentity &&
                                    fact.targetIdentity.generation == child.target.targetGeneration
                        } == true
                    ) {
                        cleanupFacet.preparedTargetFailureFact = null
                    }
                    cleanupFacet.workPending = true
                }

                is TargetQuarantineChild.Current -> if (child === quarantinedTargetChild && quarantinedCurrentComplete != null &&
                    recordCleanupMutationLocked(cleanupFacet.owner.reduceQuarantinedCurrentTargetProvenComplete(child.target))
                ) {
                    cleanupFacet.workPending = true
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
                    cleanupFacet.owner.installAndClaimGlShutdownAction(glOwner, command, candidate)
                }
                action?.runUnlocked()
            }
        }
        val storageOwner = storageForPreparation
        if (storageOwner != null) {
            val prepared = StorageRetirementAction.prepare(storageOwner)
            if (prepared != null) {
                val action = sessionGate.withLock {
                    cleanupFacet.owner.installAndClaimStorageRetirementAction(storageOwner, prepared)
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
                if (recordCleanupMutationLocked(cleanupFacet.owner.applyReturnedNativeFatalReduction(nativeFatal, reduction))) {
                    cleanupFacet.workPending = true
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
        sessionGate.withLock { topologyFacet.pendingProjectionRegistration }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            if (occurrence.arbitrate() != OperationArbitration.None) {
                sessionGate.withLock {
                    if (topologyFacet.pendingProjectionRegistration === occurrence) topologyFacet.pendingProjectionRegistration = null
                    cleanupFacet.workPending = true
                }
            }
        }
        sessionGate.withLock { topologyFacet.pendingListenerInstallation }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            val arbitration = occurrence.arbitrate()
            if (arbitration != OperationArbitration.None) {
                if (arbitration.isNormalReturn()) {
                    val bag = occurrence.ownerBag as AndroidTargetListenerInstallationOwnerBag
                    bag.target.applyListenerInstallationReceipt(bag.port, occurrence)
                }
                sessionGate.withLock {
                    if (topologyFacet.pendingListenerInstallation === occurrence) topologyFacet.pendingListenerInstallation = null
                    cleanupFacet.workPending = true
                }
            }
        }
        sessionGate.withLock { topologyFacet.pendingVirtualDisplayCreation }?.let { occurrence ->
            occurrence.controlWakeLink?.let(::serviceWakeLink)
            occurrence.returnCell.evidence.initialResizeDeadlineOccurrence?.controlWakeLink?.let(::serviceWakeLink)
            val arbitration = occurrence.arbitrate()
            if (arbitration != OperationArbitration.None) {
                if (arbitration.isNormalReturn()) {
                    val bag = occurrence.ownerBag as AndroidVirtualDisplayCreationOwnerBag
                    val candidate = bag.target.producerApplicationCandidateAfterSettlement(bag.port, occurrence)
                    val fact = candidate?.let(bag.target::applyProducerApplication)
                    val owner = sessionGate.withLock { cleanupFacet.owner.androidRoot.owner }
                    if (fact != null) owner?.applyVirtualDisplayCreationTargetFact(occurrence, fact)
                }
                sessionGate.withLock {
                    if (topologyFacet.pendingVirtualDisplayCreation === occurrence) topologyFacet.pendingVirtualDisplayCreation = null
                    topologyFacet.virtualDisplayReturnAccepted = false
                    cleanupFacet.workPending = true
                }
            }
        }
        sessionGate.withLock { topologyFacet.pendingRenderConstruction }?.let { command ->
            serviceWakeLink(command.deadlineWakeLink)
            val claim = command.claim()
            if (claim != null) {
                val destruction = command.claimCleanupDestruction(claim)?.destructionCommand
                sessionGate.withLock {
                    if (topologyFacet.pendingRenderConstruction === command) topologyFacet.pendingRenderConstruction = null
                    if (destruction != null && cleanupFacet.renderDestruction == null) cleanupFacet.renderDestruction = destruction
                    cleanupFacet.workPending = true
                }
                if (destruction != null) {
                    destruction.submit()
                    serviceWakeLink(destruction.deadlineWakeLink)
                }
            }
        }
        sessionGate.withLock { topologyFacet.pendingJpegPreparation }?.let { occurrence ->
            occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
            val returned = occurrence.operation.settlementGate.withLock {
                occurrence.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (returned) {
                sessionGate.withLock { cleanupFacet.owner.jpegRoot.owner }?.installPrepared(occurrence)
                sessionGate.withLock {
                    if (topologyFacet.pendingJpegPreparation === occurrence) topologyFacet.pendingJpegPreparation = null
                    cleanupFacet.workPending = true
                }
            }
        }
        sessionGate.withLock { topologyFacet.pendingFrameworkCreation }?.let { occurrence ->
            occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
            val returned = occurrence.operation.settlementGate.withLock {
                occurrence.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (returned) {
                recycleReturnedFrameworkOwner(occurrence)
                sessionGate.withLock {
                    if (topologyFacet.pendingFrameworkCreation === occurrence) topologyFacet.pendingFrameworkCreation = null
                    cleanupFacet.workPending = true
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
            cleanupFacet.pendingQuarantineDiagnostics = saturatedIncrement(cleanupFacet.pendingQuarantineDiagnostics)
            cleanupFacet.workPending = true
        }
        return mutation != CleanupMutation.None
    }

    private fun publishPendingQuarantineDiagnostics() {
        val count = sessionGate.withLock {
            val value = cleanupFacet.pendingQuarantineDiagnostics
            cleanupFacet.pendingQuarantineDiagnostics = 0L
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
            cleanupFacet.owner.preparedTargetRoot.target != null || cleanupFacet.owner.currentTargetRoot.target != null ||
                    cleanupFacet.owner.quarantineRoot.targetChild != null
        }
        if (targetCleanupPending) return
        val partial = sessionGate.withLock { topologyFacet.glSessionCommand }
        if (partial != null) {
            serviceWakeLink(partial.partialCleanupDeadlineWakeLink)
            val facts = partial.claimPartialCleanup() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupFacet.owner.quarantineGl(owner)) }
                return
            }
            sessionGate.withLock {
                if (topologyFacet.glSessionCommand === partial) {
                    topologyFacet.glSessionCommand = null
                    cleanupFacet.workPending = true
                }
            }
        }

        val program = sessionGate.withLock { cleanupFacet.programDestruction }
        if (program != null) {
            serviceWakeLink(program.deadlineWakeLink)
            val facts = program.claim() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupFacet.owner.quarantineGl(owner)) }
                return
            }
            val exact = program as? GlDestructionHandle ?: return
            owner.clearProgramDestruction(exact)
            sessionGate.withLock {
                if (cleanupFacet.programDestruction === program) {
                    cleanupFacet.programDestruction = null
                    cleanupFacet.workPending = true
                }
            }
        } else {
            val command = owner.prepareProgramDestruction(reserveTerminalGlIdentity(PROGRAM_DESTRUCTION_TIMEOUT))
            if (command != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.glRoot.owner !== owner || cleanupFacet.programDestruction != null) return@withLock false
                    cleanupFacet.programDestruction = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
                return
            }
        }

        val session = sessionGate.withLock { cleanupFacet.sessionDestruction }
        if (session != null) {
            serviceWakeLink(session.deadlineWakeLink)
            val facts = session.claim() ?: return
            if (facts.result != GlOperationResult.Success) {
                sessionGate.withLock { recordCleanupMutationLocked(cleanupFacet.owner.quarantineGl(owner)) }
                return
            }
            sessionGate.withLock {
                if (cleanupFacet.sessionDestruction === session) {
                    cleanupFacet.sessionDestruction = null
                    cleanupFacet.workPending = true
                }
            }
        } else {
            val command = owner.prepareHealthySessionDestruction(reserveTerminalGlIdentity(SESSION_DESTRUCTION_TIMEOUT))
            if (command != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.glRoot.owner !== owner || cleanupFacet.sessionDestruction != null) return@withLock false
                    cleanupFacet.sessionDestruction = command
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
        val owner = sessionGate.withLock { cleanupFacet.owner.jpegRoot.frameworkOwner } ?: return
        val existing = sessionGate.withLock { cleanupFacet.frameworkRecycle }
        if (existing != null) {
            when (owner.settleRecycle(existing)) {
                FrameworkBitmapRecycleSettlement.NotSettled -> return
                FrameworkBitmapRecycleSettlement.CleanupCompleted -> sessionGate.withLock {
                    if (cleanupFacet.frameworkRecycle === existing) cleanupFacet.frameworkRecycle = null
                    if (cleanupFacet.owner.reduceFramework(owner) != CleanupMutation.None) cleanupFacet.workPending = true
                }

                FrameworkBitmapRecycleSettlement.ReplacementAuthorized,
                FrameworkBitmapRecycleSettlement.UnsafeResidue,
                    -> sessionGate.withLock {
                    recordCleanupMutationLocked(cleanupFacet.owner.quarantineFramework(owner))
                    cleanupFacet.owner.jpegRoot.owner?.let {
                        recordCleanupMutationLocked(cleanupFacet.owner.quarantineJpeg(it))
                    }
                }
            }
            return
        }
        val occurrence = owner.beginTerminalRecycle(
            topologyFacet.desiredRevision,
            topologyFacet.geometryGeneration,
            topologyFacet.lifecycleEpoch,
            reserveTerminalOperationIdentity(),
        ) ?: return
        sessionGate.withLock {
            if (cleanupFacet.owner.jpegRoot.frameworkOwner === owner && cleanupFacet.frameworkRecycle == null) {
                cleanupFacet.frameworkRecycle = occurrence
            }
        }
    }

    private fun advanceTerminalJpegProductCleanup() {
        val owner = sessionGate.withLock { cleanupFacet.owner.jpegRoot.owner } ?: return
        if (sessionGate.withLock { cleanupFacet.owner.jpegRoot.frameworkOwner != null }) return
        val existing = sessionGate.withLock { cleanupFacet.nativeCarrierFree }
        if (existing != null) {
            when (owner.settleNativeCarrierFree(existing)) {
                NativeCarrierFreeSettlement.NotSettled -> return
                NativeCarrierFreeSettlement.CleanupCompleted -> sessionGate.withLock {
                    if (cleanupFacet.nativeCarrierFree === existing) {
                        cleanupFacet.nativeCarrierFree = null
                        cleanupFacet.workPending = true
                    }
                }

                NativeCarrierFreeSettlement.ReplacementAuthorized,
                NativeCarrierFreeSettlement.UnsafeResidue,
                    -> sessionGate.withLock { recordCleanupMutationLocked(cleanupFacet.owner.quarantineJpeg(owner)) }
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
                    topologyFacet.desiredRevision,
                    topologyFacet.geometryGeneration,
                    topologyFacet.lifecycleEpoch,
                    reserveTerminalOperationIdentity(),
                ) ?: return
                sessionGate.withLock {
                    if (cleanupFacet.owner.jpegRoot.owner === owner && cleanupFacet.nativeCarrierFree == null) {
                        cleanupFacet.nativeCarrierFree = occurrence
                    }
                }
            }

            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> {
                if (owner.detachManagedForReplacement(product) && owner.discardReplacementAuthorizationForTerminal(product)) {
                    sessionGate.withLock { cleanupFacet.workPending = true }
                }
            }
        }
    }

    private fun advancePreparedTargetCleanup() {
        val target = sessionGate.withLock { cleanupFacet.owner.preparedTargetRoot.target } ?: return
        val targetRequestedIdentity = target.requestedIdentity
        val constructionCommand = sessionGate.withLock { topologyFacet.preparedTargetCommand }
        if (constructionCommand != null) {
            serviceWakeLink(constructionCommand.deadlineWakeLink)
            val result = foldPreparedTargetResult() ?: return
            val retained = sessionGate.withLock {
                cleanupFacet.owner.preparedTargetRoot.target === target &&
                        topologyFacet.preparedTargetCommand === constructionCommand &&
                        result.requestedIdentity === targetRequestedIdentity &&
                        isTargetConstructionResultRetainedLocked(result)
            }
            if (!retained) return
            constructionCommand.retireAfterTargetArbitration()
            sessionGate.withLock {
                if (cleanupFacet.owner.preparedTargetRoot.target !== target ||
                    topologyFacet.preparedTargetCommand !== constructionCommand ||
                    result.requestedIdentity !== targetRequestedIdentity ||
                    !isTargetConstructionResultRetainedLocked(result)
                ) {
                    return@withLock
                }
                when (result) {
                    is TargetConstructionInstalledFact -> {
                        val installedTarget = result.targetIdentity.target
                        if (cleanupFacet.owner.currentTargetRoot.target === installedTarget &&
                            result.targetIdentity.matches(installedTarget) &&
                            recordCleanupMutationLocked(cleanupFacet.owner.reducePreparedTargetProvenComplete(target))
                        ) {
                            if (topologyFacet.preparedTargetAdmissionFact?.preparedTarget === target) {
                                topologyFacet.preparedTargetAdmissionFact = null
                            }
                            topologyFacet.preparedTargetCommand = null
                            cleanupFacet.workPending = true
                        }
                    }

                    is TargetConstructionFailureFact -> if (cleanupFacet.preparedTargetFailureFact === result) {
                        topologyFacet.preparedTargetCommand = null
                        cleanupFacet.workPending = true
                    }
                }
            }
            return
        }

        val failureFact = sessionGate.withLock {
            cleanupFacet.preparedTargetFailureFact.takeIf { fact ->
                cleanupFacet.owner.preparedTargetRoot.target === target &&
                        topologyFacet.preparedTargetAdmissionFact?.preparedTarget === target &&
                        fact.requestedIdentity === target.requestedIdentity
            }
        } ?: return
        if (!target.isConstructionMechanicallySettledForCleanup()) return
        val gl = sessionGate.withLock { cleanupFacet.owner.glRoot.owner ?: topologyFacet.glOwner } ?: return

        val surfaceCommand = sessionGate.withLock { cleanupFacet.surfaceRelease }
        if (surfaceCommand != null) {
            serviceWakeLink(surfaceCommand.deadlineWakeLink)
            val claim = surfaceCommand.claim() ?: return
            val appliedReceipt = failureFact.cleanupTarget.appliedSurfaceReleaseReceipt()
            if (appliedReceipt !== claim.receipt) {
                val quarantine = target.quarantineEvidence()
                sessionGate.withLock {
                    if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                        quarantine.requestedIdentity === target.requestedIdentity && quarantine.owner === target
                    ) {
                        recordCleanupMutationLocked(cleanupFacet.owner.quarantinePreparedTarget(target))
                    }
                }
                return
            }
            sessionGate.withLock {
                if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                    cleanupFacet.surfaceRelease === surfaceCommand
                ) {
                    cleanupFacet.surfaceRelease = null
                    cleanupFacet.workPending = true
                }
            }
            return
        }
        if (failureFact.cleanupTarget.appliedSurfaceReleaseReceipt() == null) {
            val command = gl.prepareCleanupSurfaceRelease(target)
            if (command != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.preparedTargetRoot.target !== target ||
                        cleanupFacet.preparedTargetFailureFact !== failureFact || cleanupFacet.surfaceRelease != null
                    ) {
                        return@withLock false
                    }
                    cleanupFacet.surfaceRelease = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
                return
            }
        }

        val targetCommand = sessionGate.withLock { cleanupFacet.targetScope }
        if (targetCommand != null) {
            serviceWakeLink(targetCommand.deadlineWakeLink)
            serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
            if (!sessionGate.withLock { cleanupFacet.targetNamespaceSubmitted }) {
                val claim = targetCommand.claim() ?: return
                if (claim.result == GlOperationResult.Success && target.isCleanupComplete()) {
                    sessionGate.withLock {
                        if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                            cleanupFacet.targetScope === targetCommand
                        ) {
                            cleanupFacet.targetScope = null
                            cleanupFacet.workPending = true
                        }
                    }
                } else if (targetCommand.submitNamespaceRetirement()) {
                    sessionGate.withLock {
                        if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                            cleanupFacet.targetScope === targetCommand
                        ) {
                            cleanupFacet.targetNamespaceSubmitted = true
                        }
                    }
                    serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
                    return
                } else {
                    return
                }
            } else {
                targetCommand.claimNamespaceRetirement() ?: return
                sessionGate.withLock {
                    if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                        cleanupFacet.targetScope === targetCommand
                    ) {
                        cleanupFacet.targetScope = null
                        cleanupFacet.targetNamespaceSubmitted = false
                        cleanupFacet.workPending = true
                    }
                }
            }
        } else if (!target.isCleanupComplete()) {
            val targetIdentity = sessionGate.withLock { cleanupFacet.preparedTargetDestructionIdentity } ?: return
            val namespaceIdentity = sessionGate.withLock { cleanupFacet.preparedNamespaceDestructionIdentity } ?: return
            val command = gl.prepareCleanupTargetScopeDestruction(target, targetIdentity, namespaceIdentity) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.preparedTargetRoot.target !== target ||
                    cleanupFacet.preparedTargetFailureFact !== failureFact || cleanupFacet.targetScope != null
                ) {
                    return@withLock false
                }
                cleanupFacet.targetScope = command
                cleanupFacet.targetNamespaceSubmitted = false
                true
            }
            if (rooted) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }

        val quarantineEvidence = target.quarantineEvidence()
        if (quarantineEvidence.cleanupSuffix !is TargetRetirementCompleteEvidence ||
            quarantineEvidence.requestedIdentity !== target.requestedIdentity || quarantineEvidence.owner !== target
        ) {
            return
        }
        val retiredFact = targetOwner.retireMechanicallyCompletedPreparedTarget(target) ?: return
        sessionGate.withLock {
            if (cleanupFacet.owner.preparedTargetRoot.target === target &&
                topologyFacet.preparedTargetAdmissionFact?.preparedTarget === target &&
                cleanupFacet.preparedTargetFailureFact === failureFact && retiredFact.retiredTarget === target &&
                retiredFact.requestedIdentity === target.requestedIdentity
            ) {
                recordCleanupMutationLocked(cleanupFacet.owner.reducePreparedTargetProvenComplete(target))
                topologyFacet.preparedTargetAdmissionFact = null
                cleanupFacet.preparedTargetFailureFact = null
                cleanupFacet.workPending = true
            }
        }
    }

    private fun advanceTerminalTargetCleanup(target: CurrentTarget) {
        var admissionClosedFact = sessionGate.withLock {
            cleanupFacet.targetRetirementAdmissionClosedFact.takeIf {
                cleanupFacet.targetRetirementRoot === target && it.targetIdentity.matches(target)
            }
        }
        if (admissionClosedFact == null) {
            val candidate = target.closeRetirementAdmission() ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target !== target ||
                    cleanupFacet.targetRetirementRoot != null ||
                    cleanupFacet.targetRetirementAdmissionClosedFact != null || !candidate.targetIdentity.matches(target)
                ) {
                    return@withLock false
                }
                cleanupFacet.targetRetirementRoot = target
                cleanupFacet.targetRetirementAdmissionClosedFact = candidate
                true
            }
            if (!rooted) return
            admissionClosedFact = candidate
        }

        var workDrainedFact = sessionGate.withLock {
            cleanupFacet.targetWorkDrainedFact.takeIf {
                cleanupFacet.targetRetirementRoot === target &&
                        it.admissionClosedFact === admissionClosedFact && it.targetIdentity.matches(target)
            }
        }
        if (workDrainedFact == null) {
            val candidate = target.recordEnteredTargetWorkDrained(checkNotNull(admissionClosedFact)) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target !== target ||
                    cleanupFacet.targetRetirementRoot !== target ||
                    cleanupFacet.targetRetirementAdmissionClosedFact !== admissionClosedFact ||
                    cleanupFacet.targetWorkDrainedFact != null || candidate.admissionClosedFact !== admissionClosedFact
                ) {
                    return@withLock false
                }
                cleanupFacet.targetWorkDrainedFact = candidate
                true
            }
            if (!rooted) return
            workDrainedFact = candidate
        }

        var generationFencedFact = sessionGate.withLock {
            cleanupFacet.targetGenerationFencedFact.takeIf {
                cleanupFacet.targetRetirementRoot === target &&
                        it.workDrainedFact === workDrainedFact && it.targetIdentity.matches(target)
            }
        }
        if (generationFencedFact == null) {
            val candidate = target.fenceGeneration(checkNotNull(workDrainedFact)) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target !== target ||
                    cleanupFacet.targetRetirementRoot !== target || cleanupFacet.targetWorkDrainedFact !== workDrainedFact ||
                    cleanupFacet.targetGenerationFencedFact != null || candidate.workDrainedFact !== workDrainedFact
                ) {
                    return@withLock false
                }
                cleanupFacet.targetGenerationFencedFact = candidate
                true
            }
            if (!rooted) return
            generationFencedFact = candidate
        }

        val pendingRender = sessionGate.withLock { topologyFacet.pendingRenderConstruction }
        if (pendingRender != null) {
            serviceWakeLink(pendingRender.deadlineWakeLink)
            val claim = pendingRender.claim() ?: return
            val cleanup = pendingRender.claimCleanupDestruction(claim)?.destructionCommand
            sessionGate.withLock {
                if (topologyFacet.pendingRenderConstruction === pendingRender) topologyFacet.pendingRenderConstruction = null
                if (cleanup != null && cleanupFacet.renderDestruction == null) cleanupFacet.renderDestruction = cleanup
                cleanupFacet.workPending = true
            }
            if (cleanup != null) {
                cleanup.submit()
                serviceWakeLink(cleanup.deadlineWakeLink)
            }
            return
        }

        val listener = sessionGate.withLock { cleanupFacet.listenerRemoval }
        val android = sessionGate.withLock { cleanupFacet.owner.androidRoot.owner ?: topologyFacet.androidOwner }
        if (listener != null) {
            val returned = listener.settlementGate.withLock {
                listener.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            val bag = listener.ownerBag as AndroidTargetListenerRemovalOwnerBag
            val applied = bag.target.applyListenerRemovalSettlement(bag.port, listener)
            sessionGate.withLock {
                if (cleanupFacet.listenerRemoval === listener) cleanupFacet.listenerRemoval = null
                cleanupFacet.workPending = true
            }
            if (!applied) return
        } else {
            val currentness = target.currentnessFact()
            if (currentness.listenerInstalled) {
                val exactAndroid = android ?: return
                val operationIdentity = reserveTerminalOperationIdentity()
                val operation = exactAndroid.createTargetListenerRemovalOperation(target, operationIdentity, null) ?: return
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.listenerRemoval != null) {
                        return@withLock false
                    }
                    cleanupFacet.listenerRemoval = operation
                    true
                }
                if (rooted) exactAndroid.submitTargetListenerRemoval(operation)
                return
            }
        }

        val virtualDisplayRelease = sessionGate.withLock { cleanupFacet.virtualDisplayRelease }
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
                if (cleanupFacet.virtualDisplayRelease === virtualDisplayRelease) cleanupFacet.virtualDisplayRelease = null
                cleanupFacet.workPending = true
            }
        } else {
            val exactAndroid = android ?: return
            val operation = exactAndroid.createVirtualDisplayReleaseOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.virtualDisplayRelease != null) {
                        return@withLock false
                    }
                    cleanupFacet.virtualDisplayRelease = operation
                    true
                }
                if (rooted) exactAndroid.submitVirtualDisplayRelease(operation)
                return
            }
        }

        val gl = sessionGate.withLock { cleanupFacet.owner.glRoot.owner ?: topologyFacet.glOwner } ?: return
        val renderCommand = sessionGate.withLock { cleanupFacet.renderDestruction }
        if (renderCommand != null) {
            serviceWakeLink(renderCommand.deadlineWakeLink)
            val claim = renderCommand.claim() ?: return
            if (claim.result != GlOperationResult.Success) return
            sessionGate.withLock {
                if (cleanupFacet.renderDestruction === renderCommand) {
                    cleanupFacet.renderDestruction = null
                    cleanupFacet.renderOwner = null
                    cleanupFacet.workPending = true
                }
            }
        } else {
            val render = sessionGate.withLock { cleanupFacet.renderOwner }
            if (render != null) {
                val command = gl.prepareRenderTargetDestruction(render) ?: return
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.renderOwner !== render ||
                        cleanupFacet.renderDestruction != null
                    ) {
                        return@withLock false
                    }
                    cleanupFacet.renderDestruction = command
                    true
                }
                if (rooted) {
                    command.submit()
                    serviceWakeLink(command.deadlineWakeLink)
                }
                return
            }
        }

        val surfaceCommand = sessionGate.withLock { cleanupFacet.surfaceRelease }
        if (surfaceCommand != null) {
            serviceWakeLink(surfaceCommand.deadlineWakeLink)
            val claim = surfaceCommand.claim() ?: return
            val appliedReceipt = target.appliedSurfaceReleaseReceipt()
            if (appliedReceipt !== claim.receipt || claim.receipt.operationIdentity != target.surfaceReleaseOccurrence.identity) {
                val quarantine = target.quarantineEvidence() ?: return
                sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target === target &&
                        cleanupFacet.targetRetirementRoot === target && quarantine.owner === target &&
                        quarantine.targetIdentity.matches(target) && quarantine.suffix.targetIdentity === quarantine.targetIdentity
                    ) {
                        recordCleanupMutationLocked(cleanupFacet.owner.quarantineCurrentTarget(target))
                        clearTargetRetirementFactsLocked(target)
                    }
                }
                return
            }
            sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target === target &&
                    cleanupFacet.targetRetirementRoot === target && cleanupFacet.surfaceRelease === surfaceCommand
                ) {
                    cleanupFacet.surfaceRelease = null
                    cleanupFacet.workPending = true
                }
            }
        } else if (target.appliedSurfaceReleaseReceipt() == null) {
            var readinessFact = sessionGate.withLock {
                cleanupFacet.targetSurfaceReleaseReadyFact.takeIf {
                    cleanupFacet.targetRetirementRoot === target &&
                            it.generationFencedFact === generationFencedFact && it.targetIdentity.matches(target)
                }
            }
            if (readinessFact == null) {
                val candidate = target.surfaceReleaseReadyFact() ?: return
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.targetRetirementRoot !== target ||
                        cleanupFacet.targetGenerationFencedFact !== generationFencedFact ||
                        cleanupFacet.targetSurfaceReleaseReadyFact != null ||
                        candidate.generationFencedFact !== generationFencedFact
                    ) {
                        return@withLock false
                    }
                    cleanupFacet.targetSurfaceReleaseReadyFact = candidate
                    true
                }
                if (!rooted) return
                readinessFact = candidate
            }
            val command = gl.prepareSurfaceRelease(checkNotNull(readinessFact)) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.targetRetirementRoot !== target ||
                    cleanupFacet.targetSurfaceReleaseReadyFact !== readinessFact ||
                    checkNotNull(readinessFact).generationFencedFact !== generationFencedFact ||
                    cleanupFacet.surfaceRelease != null
                ) {
                    return@withLock false
                }
                cleanupFacet.surfaceRelease = command
                true
            }
            if (rooted) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }

        val targetCommand = sessionGate.withLock { cleanupFacet.targetScope }
        if (targetCommand != null) {
            serviceWakeLink(targetCommand.deadlineWakeLink)
            serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
            if (!sessionGate.withLock { cleanupFacet.targetNamespaceSubmitted }) {
                val claim = targetCommand.claim() ?: return
                if (claim.result == GlOperationResult.Success && target.isFullyRetired) {
                    sessionGate.withLock {
                        if (cleanupFacet.owner.currentTargetRoot.target === target &&
                            cleanupFacet.targetRetirementRoot === target && cleanupFacet.targetScope === targetCommand
                        ) {
                            cleanupFacet.targetScope = null
                            cleanupFacet.workPending = true
                        }
                    }
                } else if (targetCommand.submitNamespaceRetirement()) {
                    sessionGate.withLock {
                        if (cleanupFacet.owner.currentTargetRoot.target === target &&
                            cleanupFacet.targetRetirementRoot === target && cleanupFacet.targetScope === targetCommand
                        ) {
                            cleanupFacet.targetNamespaceSubmitted = true
                        }
                    }
                    serviceWakeLink(targetCommand.namespaceDeadlineWakeLink)
                    return
                } else {
                    return
                }
            } else {
                targetCommand.claimNamespaceRetirement() ?: return
                sessionGate.withLock {
                    if (cleanupFacet.owner.currentTargetRoot.target === target &&
                        cleanupFacet.targetRetirementRoot === target && cleanupFacet.targetScope === targetCommand
                    ) {
                        cleanupFacet.targetScope = null
                        cleanupFacet.targetNamespaceSubmitted = false
                        cleanupFacet.workPending = true
                    }
                }
            }
        } else if (!target.isFullyRetired) {
            val targetIdentity = sessionGate.withLock { cleanupFacet.preparedTargetDestructionIdentity } ?: return
            val namespaceIdentity = sessionGate.withLock { cleanupFacet.preparedNamespaceDestructionIdentity } ?: return
            val command = gl.prepareTargetScopeDestruction(target, targetIdentity, namespaceIdentity) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target !== target || cleanupFacet.targetRetirementRoot !== target ||
                    cleanupFacet.targetGenerationFencedFact !== generationFencedFact ||
                    cleanupFacet.targetSurfaceReleaseReadyFact == null || cleanupFacet.targetScope != null
                ) {
                    return@withLock false
                }
                cleanupFacet.targetScope = command
                cleanupFacet.targetNamespaceSubmitted = false
                true
            }
            if (rooted) {
                command.submit()
                serviceWakeLink(command.deadlineWakeLink)
            }
            return
        }

        if (target.isFullyRetired) {
            val completeEvidence = target.retirementSuffixEvidence()
            sessionGate.withLock {
                if (cleanupFacet.owner.currentTargetRoot.target === target && cleanupFacet.targetRetirementRoot === target &&
                    completeEvidence is TargetRetirementCompleteEvidence && completeEvidence.targetIdentity.matches(target)
                ) {
                    if (cleanupFacet.owner.reduceCurrentTargetProvenComplete(target) != CleanupMutation.None) {
                        clearTargetRetirementFactsLocked(target)
                        cleanupFacet.workPending = true
                    }
                }
            }
        }
    }

    private fun advanceTerminalAndroidCleanup(owner: AndroidCaptureOwner) {
        owner.closeProjectionCallbackAuthority()

        val unregister = sessionGate.withLock { cleanupFacet.projectionUnregistration }
        if (unregister != null) {
            val returned = unregister.settlementGate.withLock {
                unregister.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            sessionGate.withLock {
                if (cleanupFacet.projectionUnregistration === unregister) cleanupFacet.projectionUnregistration = null
                cleanupFacet.workPending = true
            }
        } else {
            val operation = owner.createProjectionCallbackUnregistrationOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.androidRoot.owner !== owner || cleanupFacet.projectionUnregistration != null) {
                        return@withLock false
                    }
                    cleanupFacet.projectionUnregistration = operation
                    true
                }
                if (rooted) owner.submitProjectionCallbackUnregistration(operation)
                return
            }
        }

        val release = sessionGate.withLock { cleanupFacet.virtualDisplayRelease }
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
                if (cleanupFacet.virtualDisplayRelease === release) cleanupFacet.virtualDisplayRelease = null
                cleanupFacet.workPending = true
            }
        } else {
            val operation = owner.createVirtualDisplayReleaseOperation(reserveTerminalOperationIdentity())
            if (operation != null) {
                val rooted = sessionGate.withLock {
                    if (cleanupFacet.owner.androidRoot.owner !== owner || cleanupFacet.virtualDisplayRelease != null) {
                        return@withLock false
                    }
                    cleanupFacet.virtualDisplayRelease = operation
                    true
                }
                if (rooted) owner.submitVirtualDisplayRelease(operation)
                return
            }
        }

        val stop = sessionGate.withLock { cleanupFacet.projectionStop }
        if (stop != null) {
            val returned = stop.settlementGate.withLock {
                stop.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!returned) return
            sessionGate.withLock {
                if (cleanupFacet.projectionStop === stop) cleanupFacet.projectionStop = null
                cleanupFacet.workPending = true
            }
        } else if (!owner.isLaneQuitReady) {
            val operation = owner.createProjectionStopOperation(reserveTerminalOperationIdentity()) ?: return
            val rooted = sessionGate.withLock {
                if (cleanupFacet.owner.androidRoot.owner !== owner || cleanupFacet.projectionStop != null) return@withLock false
                cleanupFacet.projectionStop = operation
                true
            }
            if (rooted) owner.submitProjectionStop(operation)
        }
    }

    private fun reserveRunningPublicationLocked(problem: ScreenCaptureProblem? = null) {
        check(sessionGate.isHeldByCurrentThread)
        if (topologyFacet.requestedParameters == null || topologyFacet.lastEffectiveParameters == null) return
        statsFacet.runningPublicationPending = true
        statsFacet.runningPublicationProblem = problem
    }

    private fun reserveDiagnosticSequenceLocked(): Long {
        check(sessionGate.isHeldByCurrentThread)
        if (statsFacet.nextDiagnosticSequence == Long.MAX_VALUE) {
            offerFailureLocked(ScreenCaptureProblem.InternalFailure, DIAGNOSTIC_SEQUENCE_EXHAUSTED)
            return 0L
        }
        return statsFacet.nextDiagnosticSequence++
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
                    cleanupFacet.workPending || !terminalCutoffApplied && terminalContenders.any { it.present }
                }

    private fun closeAdmissionLocked() {
        check(sessionGate.isHeldByCurrentThread)
        admissionsClosed = true
        deliveryFacet.targetAdmissionClosePending = true
    }

    private fun invalidateOutputLocked() {
        check(sessionGate.isHeldByCurrentThread)
        topologyFacet.acceptedTopologySnapshot = null
    }

    private fun invalidateReconciliationForTopologyMutationLocked() {
        check(sessionGate.isHeldByCurrentThread)
        topologyFacet.reconciliationIdentity = 0L
        topologyFacet.currentCalculation = null
        topologyFacet.currentProvisional = null
        topologyFacet.currentPlan = topologyFacet.currentTarget?.plan
    }

    private fun advanceLifecycleEpochLocked() {
        check(sessionGate.isHeldByCurrentThread)
        topologyFacet.lifecycleEpoch = reserveIdentityLocked()
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
        statsFacet.byFailure = saturatedIncrement(statsFacet.byFailure)
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

    override fun onControlTaskOrdinaryEscaped(
        @Suppress("UNUSED_PARAMETER") record: ControlScheduledTaskRecord,
        raw: Throwable,
    ) {
        try {
            controlPoisonAuthority.publish(raw)
            emergencyFailClosed(controlPoisonAuthority.observe() ?: raw)
        } finally {
            drainerState.set(DRAIN_IDLE)
        }
    }

    override fun onControlTaskDirectFatal(
        @Suppress("UNUSED_PARAMETER") record: ControlScheduledTaskRecord,
        raw: Throwable,
    ) {
        try {
            settleDirectFatalControlFailure(raw, raw)
        } finally {
            drainerState.set(DRAIN_IDLE)
        }
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
                    ingress = this,
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
                if (pendingStartupBag === bag && bag.metrics == null) bag.metrics = metrics else cleanupFacet.owner.attachMetrics(metrics)
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
                if (pendingStartupBag === bag && bag.android == null) bag.android = android else cleanupFacet.owner.attachAndroid(android)
            }

            val gl = SessionStartupTopology.constructGl(clock, this)
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.gl == null) bag.gl = gl else cleanupFacet.owner.attachGl(gl)
            }

            val jpeg = SessionStartupTopology.constructJpeg(clock, this)
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.jpeg == null) bag.jpeg = jpeg else cleanupFacet.owner.attachJpeg(jpeg)
            }

            val storage = SessionStartupTopology.constructStorage()
            sessionGate.withLock {
                if (pendingStartupBag === bag && bag.storage == null) bag.storage = storage else cleanupFacet.owner.attachStorage(storage)
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
                topologyFacet.metricsOwner != null || topologyFacet.androidOwner != null || topologyFacet.glOwner != null || topologyFacet.jpegOwner != null || topologyFacet.storageOwner != null
            ) {
                return@withLock false
            }
            topologyFacet.metricsOwner = metrics
            topologyFacet.androidOwner = android
            topologyFacet.glOwner = gl
            topologyFacet.jpegOwner = jpeg
            topologyFacet.storageOwner = storage
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
            metrics = topologyFacet.metricsOwner ?: return@withLock false
            android = topologyFacet.androidOwner ?: return@withLock false
            gl = topologyFacet.glOwner ?: return@withLock false
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
                if (terminalWinner.fixed || admissionsClosed || topologyFacet.glOwner !== input.gl || topologyFacet.glSessionCommand != null) {
                    return@withLock false
                }
                topologyFacet.glSessionCommand = command
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
        val command = sessionGate.withLock { topologyFacet.glSessionCommand } ?: return
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
                if (topologyFacet.glSessionCommand === command) topologyFacet.glSessionCommand = null
            }
        }
    }

    private fun advanceProjectionRegistration() {
        val existing = sessionGate.withLock { topologyFacet.pendingProjectionRegistration }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            when (val arbitration = existing.arbitrate()) {
                OperationArbitration.None -> return
                OperationArbitration.TimelyNormal -> {
                    val accepted = sessionGate.withLock {
                        if (topologyFacet.pendingProjectionRegistration !== existing || terminalWinner.fixed || admissionsClosed ||
                            topologyFacet.androidOwner == null
                        ) {
                            return@withLock false
                        }
                        topologyFacet.acceptedProjectionCallbackRegistrationIdentity = existing.identity
                        topologyFacet.pendingProjectionRegistration = null
                        true
                    }
                    if (accepted) signal()
                }

                else -> {
                    sessionGate.withLock {
                        if (topologyFacet.pendingProjectionRegistration === existing) topologyFacet.pendingProjectionRegistration = null
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
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.acceptedProjectionCallbackRegistrationIdentity != 0L ||
                topologyFacet.pendingProjectionRegistration != null || topologyFacet.metricsJointReadiness == null ||
                topologyFacet.androidLaneReadyOwner !== topologyFacet.androidOwner
            ) {
                return@withLock null
            }
            topologyFacet.androidOwner
        } ?: return
        val identity = reserveAndroidIdentity(PROJECTION_CALLBACK_REGISTRATION_TIMEOUT) ?: return
        val operation = owner.createProjectionCallbackRegistrationOperation(identity) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, PROJECTION_CALLBACK_REGISTRATION_COLLISION)
            return
        }
        val installed = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.androidOwner !== owner || topologyFacet.pendingProjectionRegistration != null) {
                return@withLock false
            }
            topologyFacet.pendingProjectionRegistration = operation
            true
        }
        if (!installed) return
        owner.submitProjectionCallbackRegistration(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceTargetConstruction() {
        val existingCommand = sessionGate.withLock { topologyFacet.preparedTargetCommand }
        if (existingCommand != null) {
            serviceWakeLink(existingCommand.deadlineWakeLink)
            val result = foldPreparedTargetResult() ?: return
            val retained = sessionGate.withLock {
                topologyFacet.preparedTargetCommand === existingCommand &&
                        isTargetConstructionResultRetainedLocked(result)
            }
            if (!retained) return
            existingCommand.retireAfterTargetArbitration()
            sessionGate.withLock {
                if (topologyFacet.preparedTargetCommand === existingCommand &&
                    isTargetConstructionResultRetainedLocked(result)
                ) {
                    topologyFacet.preparedTargetCommand = null
                }
            }
            return
        }

        var plan: TargetPlan? = null
        var requestedIdentity: TargetRequestedIdentity? = null
        var owner: GlPipelineOwner? = null
        val selected = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.acceptedProjectionCallbackRegistrationIdentity == 0L ||
                topologyFacet.currentTarget != null || topologyFacet.preparedTarget != null ||
                topologyFacet.preparedTargetAdmissionFact != null || topologyFacet.preparedTargetCommand != null ||
                cleanupFacet.owner.preparedTargetRoot.target != null ||
                cleanupFacet.owner.currentTargetRoot.target != null ||
                cleanupFacet.owner.quarantineRoot.targetChild != null ||
                topologyFacet.glSessionFacts == null || topologyFacet.reconciliationIdentity <= 0L
            ) {
                return@withLock false
            }
            val reconciliationInput = topologyFacet.currentCalculation?.input ?:
                topologyFacet.currentProvisional?.input ?: return@withLock false
            plan = topologyFacet.currentPlan ?: return@withLock false
            val stamp = reconciliationInput.stamp
            if (stamp.desiredRevision != topologyFacet.desiredRevision ||
                stamp.geometryGeneration != topologyFacet.geometryGeneration ||
                stamp.lifecycleEpoch != topologyFacet.lifecycleEpoch ||
                reconciliationInput.reconciliationOccurrenceIdentity != topologyFacet.reconciliationIdentity
            ) {
                return@withLock false
            }
            requestedIdentity = TargetRequestedIdentity(
                desiredRevision = stamp.desiredRevision,
                geometryGeneration = stamp.geometryGeneration,
                lifecycleEpoch = stamp.lifecycleEpoch,
                reconciliationIdentity = topologyFacet.reconciliationIdentity,
            )
            owner = topologyFacet.glOwner ?: return@withLock false
            true
        }
        if (!selected) return

        val construction = reserveGlIdentity(TARGET_CONSTRUCTION_TIMEOUT) ?: return
        val listener = reserveAndroidIdentity(TARGET_LISTENER_INSTALLATION_TIMEOUT) ?: return
        val surfaceRelease = reserveAndroidIdentity(TARGET_SURFACE_RELEASE_TIMEOUT) ?: return
        val targetDestruction = reserveGlIdentity(TARGET_DESTRUCTION_TIMEOUT) ?: return
        val namespaceDestruction = reserveGlIdentity(TARGET_NAMESPACE_DESTRUCTION_TIMEOUT) ?: return
        val exactRequestedIdentity = checkNotNull(requestedIdentity)
        val exactPlan = checkNotNull(plan)
        val candidate = targetOwner.prepareTarget(
            plan = exactPlan,
            requestedIdentity = exactRequestedIdentity,
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
        val exactGlOwner = checkNotNull(owner)
        val admissionFact = admitPreparedTarget(candidate, exactRequestedIdentity, exactPlan, exactGlOwner) ?: return
        val rooted = sessionGate.withLock {
            val exactOwner = topologyFacet.glOwner === exactGlOwner || cleanupFacet.owner.glRoot.owner === exactGlOwner
            val exactTargetRoot = topologyFacet.preparedTarget === candidate ||
                    cleanupFacet.owner.preparedTargetRoot.target === candidate
            if (!exactTargetRoot ||
                topologyFacet.preparedTargetAdmissionFact !== admissionFact ||
                admissionFact.requestedIdentity !== exactRequestedIdentity || candidate.plan !== exactPlan ||
                !exactOwner || topologyFacet.preparedTargetCommand != null
            ) {
                return@withLock false
            }
            topologyFacet.preparedTargetListenerIdentity = listener
            cleanupFacet.preparedTargetDestructionIdentity = targetDestruction
            cleanupFacet.preparedNamespaceDestructionIdentity = namespaceDestruction
            topologyFacet.preparedTargetCommand = command
            true
        }
        if (!rooted) return
        command.submit()
        serviceWakeLink(command.deadlineWakeLink)
    }

    private fun advanceListenerInstallation() {
        val existing = sessionGate.withLock { topologyFacet.pendingListenerInstallation }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            when (val arbitration = existing.arbitrate()) {
                OperationArbitration.None -> return
                OperationArbitration.TimelyNormal -> {
                    val bag = existing.ownerBag as AndroidTargetListenerInstallationOwnerBag
                    val applied = bag.target.applyListenerInstallationReceipt(bag.port, existing)
                    sessionGate.withLock {
                        if (topologyFacet.pendingListenerInstallation === existing) topologyFacet.pendingListenerInstallation = null
                    }
                    if (!applied) {
                        offerFailure(ScreenCaptureProblem.InternalFailure, TARGET_LISTENER_RECEIPT_REJECTED)
                    } else {
                        signal()
                    }
                }

                else -> {
                    sessionGate.withLock {
                        if (topologyFacet.pendingListenerInstallation === existing) topologyFacet.pendingListenerInstallation = null
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
            target = topologyFacet.currentTarget
            owner = topologyFacet.androidOwner
            identity = topologyFacet.preparedTargetListenerIdentity
            !terminalWinner.fixed && !admissionsClosed && target != null && owner != null && identity != null
        }
        if (!selected || checkNotNull(target).currentnessFact().listenerInstalled) return
        val operation = checkNotNull(owner).createTargetListenerInstallationOperation(
            checkNotNull(target),
            checkNotNull(identity),
        ) ?: run {
            offerFailure(ScreenCaptureProblem.InternalFailure, TARGET_LISTENER_OPERATION_REJECTED)
            return
        }
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.currentTarget !== target || topologyFacet.androidOwner !== owner ||
                topologyFacet.preparedTargetListenerIdentity !== identity || topologyFacet.pendingListenerInstallation != null
            ) {
                return@withLock false
            }
            topologyFacet.pendingListenerInstallation = operation
            true
        }
        if (!rooted) return
        checkNotNull(owner).submitTargetListenerInstallation(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceVirtualDisplayCreation() {
        val existing = sessionGate.withLock { topologyFacet.pendingVirtualDisplayCreation }
        if (existing != null) {
            existing.controlWakeLink?.let(::serviceWakeLink)
            existing.returnCell.evidence.initialResizeDeadlineOccurrence?.controlWakeLink?.let(::serviceWakeLink)
            val exactOwnerBag = existing.ownerBag as AndroidVirtualDisplayCreationOwnerBag
            var returnAccepted = false
            var androidOwner: AndroidCaptureOwner? = null
            val captured = sessionGate.withLock {
                if (terminalWinner.fixed || admissionsClosed ||
                    topologyFacet.pendingVirtualDisplayCreation !== existing ||
                    topologyFacet.currentTarget !== exactOwnerBag.target
                ) {
                    return@withLock false
                }
                returnAccepted = topologyFacet.virtualDisplayReturnAccepted
                androidOwner = topologyFacet.androidOwner
                androidOwner != null
            }
            if (!captured) return
            val exactAndroidOwner = checkNotNull(androidOwner)
            if (!returnAccepted) {
                when (val arbitration = existing.arbitrate()) {
                    OperationArbitration.None -> return
                    OperationArbitration.TimelyNormal -> {
                        val candidate = exactOwnerBag.target.producerApplicationCandidateAfterSettlement(
                            exactOwnerBag.port,
                            existing,
                        )
                        val fact = candidate?.let(exactOwnerBag.target::applyProducerApplication)
                        val applied = fact != null && exactAndroidOwner.applyVirtualDisplayCreationTargetFact(existing, fact)
                        if (!applied || fact !is io.screenstream.engine.internal.target.TargetProducerEvidence) {
                            offerFailure(ScreenCaptureProblem.CaptureUnavailable, VIRTUAL_DISPLAY_PRODUCER_REJECTED)
                            return
                        }
                        val committed = sessionGate.withLock {
                            if (terminalWinner.fixed || admissionsClosed ||
                                topologyFacet.pendingVirtualDisplayCreation !== existing ||
                                topologyFacet.androidOwner !== exactAndroidOwner ||
                                topologyFacet.currentTarget !== exactOwnerBag.target ||
                                topologyFacet.virtualDisplayReturnAccepted != returnAccepted
                            ) {
                                return@withLock false
                            }
                            topologyFacet.virtualDisplayReturnAccepted = true
                            true
                        }
                        if (!committed) return
                    }

                    else -> {
                        sessionGate.withLock {
                            if (topologyFacet.pendingVirtualDisplayCreation === existing &&
                                topologyFacet.androidOwner === exactAndroidOwner &&
                                topologyFacet.currentTarget === exactOwnerBag.target &&
                                topologyFacet.virtualDisplayReturnAccepted == returnAccepted
                            ) {
                                topologyFacet.pendingVirtualDisplayCreation = null
                            }
                        }
                        offerFailure(ScreenCaptureProblem.CaptureUnavailable, existing.returnCell.throwable)
                        return
                    }
                }
            }

            val api34 = exactAndroidOwner.apiBand == AndroidCaptureApiBand.Api34To37
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
                if (topologyFacet.pendingVirtualDisplayCreation === existing &&
                    topologyFacet.androidOwner === exactAndroidOwner &&
                    topologyFacet.currentTarget === exactOwnerBag.target &&
                    topologyFacet.virtualDisplayReturnAccepted
                ) {
                    topologyFacet.pendingVirtualDisplayCreation = null
                    topologyFacet.virtualDisplayReturnAccepted = false
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
            target = topologyFacet.currentTarget
            owner = topologyFacet.androidOwner
            val logical = topologyFacet.captureGeometry
            val provisional = topologyFacet.combinedGeometryAuthority
            logicalWidth = logical?.widthPx ?: provisional?.sourceWidthPx ?: 0
            logicalHeight = logical?.heightPx ?: provisional?.sourceHeightPx ?: 0
            density = logical?.densityDpi ?: provisional?.densityDpi ?: 0
            !terminalWinner.fixed && !admissionsClosed && target != null && owner != null &&
                    logicalWidth > 0 && logicalHeight > 0 && density > 0
        }
        if (!selected) return
        val currentness = checkNotNull(target).currentnessFact()
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
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.currentTarget !== target || topologyFacet.androidOwner !== owner ||
                topologyFacet.pendingVirtualDisplayCreation != null
            ) {
                return@withLock false
            }
            topologyFacet.pendingVirtualDisplayCreation = operation
            topologyFacet.virtualDisplayReturnAccepted = false
            true
        }
        if (!rooted) return
        checkNotNull(owner).submitVirtualDisplayCreation(operation)
        operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceRenderTargetConstruction() {
        val existing = sessionGate.withLock { topologyFacet.pendingRenderConstruction }
        if (existing != null) {
            serviceWakeLink(existing.deadlineWakeLink)
            val claim = existing.claim() ?: return
            acceptRenderTargetInstallation(existing, claim)
            sessionGate.withLock {
                if (topologyFacet.pendingRenderConstruction === existing) topologyFacet.pendingRenderConstruction = null
            }
            return
        }

        var target: CurrentTarget? = null
        var calculation: Resolved? = null
        var owner: GlPipelineOwner? = null
        var renderGeneration = 0L
        val selected = sessionGate.withLock {
            target = topologyFacet.currentTarget
            calculation = topologyFacet.currentCalculation
            owner = topologyFacet.glOwner
            if (terminalWinner.fixed || admissionsClosed || target == null || calculation == null || owner == null ||
                topologyFacet.installedRenderTarget != null || topologyFacet.pendingRenderConstruction != null
            ) {
                return@withLock false
            }
            renderGeneration = topologyFacet.nextRenderGeneration
            if (renderGeneration == Long.MAX_VALUE) {
                offerFailureLocked(ScreenCaptureProblem.InternalFailure, IDENTITY_EXHAUSTED)
                return@withLock false
            }
            topologyFacet.nextRenderGeneration++
            true
        }
        if (!selected) return
        val targetSnapshot = checkNotNull(target).currentnessFact()
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
            targetGeneration = targetSnapshot.targetIdentity.generation,
            reconciliationFacts = checkNotNull(calculation).frameReconciliationFacts,
        ) ?: run {
            offerFailure(ScreenCaptureProblem.ResourceExhausted, null)
            return
        }
        val targetRecheck = checkNotNull(target).currentnessFact()
        val rooted = sessionGate.withLock {
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.currentTarget !== target || topologyFacet.currentCalculation !== calculation ||
                topologyFacet.glOwner !== owner || topologyFacet.installedRenderTarget != null || topologyFacet.pendingRenderConstruction != null ||
                targetRecheck.targetIdentity !== targetSnapshot.targetIdentity || targetRecheck.version != targetSnapshot.version
            ) {
                return@withLock false
            }
            topologyFacet.pendingRenderConstruction = command
            true
        }
        if (!rooted) {
            val retainedForCleanup = sessionGate.withLock {
                if (topologyFacet.glOwner !== owner || topologyFacet.pendingRenderConstruction != null) return@withLock false
                topologyFacet.pendingRenderConstruction = command
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
        val existing = sessionGate.withLock { topologyFacet.pendingJpegPreparation }
        if (existing != null) {
            existing.operation.controlWakeLink?.let(::serviceWakeLink)
            val mechanicallyReturned = existing.operation.settlementGate.withLock {
                existing.operation.returnCell.disposition != OperationReturnDisposition.Empty
            }
            if (!mechanicallyReturned) return
            val owner = sessionGate.withLock { topologyFacet.jpegOwner }
            val installed = owner?.installPrepared(existing) == true
            sessionGate.withLock {
                if (topologyFacet.pendingJpegPreparation === existing) topologyFacet.pendingJpegPreparation = null
                if (installed) {
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
            owner = topologyFacet.jpegOwner
            calculation = topologyFacet.currentCalculation
            desired = topologyFacet.desiredRevision
            geometry = topologyFacet.geometryGeneration
            epoch = topologyFacet.lifecycleEpoch
            !terminalWinner.fixed && !admissionsClosed && owner != null && calculation != null &&
                    topologyFacet.installedRenderTarget != null && topologyFacet.pendingJpegPreparation == null
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
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.jpegOwner !== owner || topologyFacet.currentCalculation !== calculation ||
                topologyFacet.pendingJpegPreparation != null
            ) {
                return@withLock false
            }
            topologyFacet.pendingJpegPreparation = occurrence
            true
        }
        if (!rooted) return
        occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun advanceFrameworkPreparation() {
        val existing = sessionGate.withLock { topologyFacet.pendingFrameworkCreation }
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
            runtime = topologyFacet.jpegOwner
            calculation = topologyFacet.currentCalculation
            desired = topologyFacet.desiredRevision
            geometry = topologyFacet.geometryGeneration
            epoch = topologyFacet.lifecycleEpoch
            !terminalWinner.fixed && !admissionsClosed && runtime != null && calculation != null &&
                    topologyFacet.installedRenderTarget != null && topologyFacet.installedFrameworkOwner == null && topologyFacet.pendingFrameworkCreation == null &&
                    cleanupFacet.owner.jpegRoot.frameworkOwner == null
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
            if (terminalWinner.fixed || admissionsClosed || topologyFacet.jpegOwner !== runtime || topologyFacet.currentCalculation !== calculation ||
                topologyFacet.pendingFrameworkCreation != null
            ) {
                return@withLock false
            }
            topologyFacet.pendingFrameworkCreation = occurrence
            true
        }
        if (!rooted) return
        occurrence.operation.controlWakeLink?.let(::serviceWakeLink)
    }

    private fun acceptCompleteTopology() {
        var calculation: Resolved? = null
        val eligible = sessionGate.withLock {
            calculation = topologyFacet.currentCalculation
            !terminalWinner.fixed && !admissionsClosed && calculation != null && topologyFacet.currentTarget != null &&
                    topologyFacet.installedRenderTarget != null && topologyFacet.acceptedTopologySnapshot == null &&
                    calculation?.targetAction == ReconciliationResourceAction.Retain &&
                    calculation?.renderAction == ReconciliationResourceAction.Retain &&
                    calculation?.jpegAction == ReconciliationResourceAction.Retain &&
                    calculation?.frameworkAction != ReconciliationResourceAction.Create &&
                    calculation?.frameworkAction != ReconciliationResourceAction.Replace
        }
        if (eligible) commitActive(checkNotNull(calculation).effectiveParameters)
    }

    private fun serviceWakeLink(link: ControlWakeLink) {
        link.claimSubmissionAction()?.let { action -> executeWakeSubmission(action) }
        link.claimCancellationAction()?.let { action -> executeWakeCancellation(link, action) }
        when (val successor = link.prepareBoundFiniteDeadlineEarlySuccessor(controlPoisonAuthority)) {
            ControlWakeSuccessorResult.NotEligible -> Unit
            ControlWakeSuccessorResult.PoisonFenced -> Unit
            ControlWakeSuccessorResult.IdentityExhausted -> {
                publishAggregateFact(controlSuccessorIdentityExhaustedFact, IDENTITY_EXHAUSTED)
                settleRecordedControlFailure(IDENTITY_EXHAUSTED, null, controlSuccessorSecondaryFact)
            }

            ControlWakeSuccessorResult.ClaimExhausted -> {
                settleControlClaimExhausted(
                    controlSuccessorClaimExhaustedFact,
                    controlSuccessorSecondaryFact,
                )
            }

            is ControlWakeSuccessorResult.Requested -> {
                val action = successor.action.claimSubmissionAction() ?: return
                executeWakeSubmission(action)
            }
        }
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
        topologyFacet.currentTarget === target && topologyFacet.installedRenderTarget === renderTarget &&
                topologyFacet.jpegOwner?.stableTopologySnapshot()?.product === jpegProduct &&
                topologyFacet.installedFrameworkOwner === frameworkOwner
    }

    private fun isTargetRequestCurrentLocked(requestedIdentity: TargetRequestedIdentity): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        val reconciliationInput = topologyFacet.currentCalculation?.input ?: topologyFacet.currentProvisional?.input
            ?: return false
        val stamp = reconciliationInput.stamp
        return requestedIdentity.desiredRevision == stamp.desiredRevision &&
                requestedIdentity.geometryGeneration == stamp.geometryGeneration &&
                requestedIdentity.lifecycleEpoch == stamp.lifecycleEpoch &&
                requestedIdentity.reconciliationIdentity == reconciliationInput.reconciliationOccurrenceIdentity &&
                stamp.desiredRevision == topologyFacet.desiredRevision &&
                stamp.geometryGeneration == topologyFacet.geometryGeneration &&
                stamp.lifecycleEpoch == topologyFacet.lifecycleEpoch &&
                reconciliationInput.reconciliationOccurrenceIdentity == topologyFacet.reconciliationIdentity
    }

    private fun retainCurrentTargetForCleanupLocked(target: CurrentTarget): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        val predecessor = cleanupFacet.owner.currentTargetRoot.target
        if (predecessor === target) return true
        if (predecessor != null) {
            if (cleanupFacet.owner.quarantineRoot.targetChild != null ||
                !recordCleanupMutationLocked(cleanupFacet.owner.quarantineCurrentTarget(predecessor))
            ) {
                return false
            }
            clearTargetRetirementFactsLocked(predecessor)
        }
        if (!recordCleanupMutationLocked(cleanupFacet.owner.attachCurrentTarget(target))) return false
        cleanupFacet.workPending = true
        return true
    }

    private fun retainStaleCurrentTargetInTopologyLocked(
        target: CurrentTarget,
        exactPreparedTarget: PreparedTarget,
        exactAdmissionFact: PreparedTargetAdmissionFact,
    ): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (lifecycle != Lifecycle.Running || terminalWinner.fixed || admissionsClosed ||
            topologyFacet.currentTarget != null || topologyFacet.preparedTarget !== exactPreparedTarget ||
            topologyFacet.preparedTargetAdmissionFact !== exactAdmissionFact
        ) {
            return false
        }
        topologyFacet.currentTarget = target
        invalidateReconciliationForTopologyMutationLocked()
        return true
    }

    private fun isTargetConstructionResultRetainedLocked(result: TargetConstructionResultFact): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        return when (result) {
            is TargetConstructionInstalledFact -> {
                val target = result.targetIdentity.target
                result.targetIdentity.matches(target) &&
                        (topologyFacet.currentTarget === target || cleanupFacet.owner.currentTargetRoot.target === target)
            }

            is TargetConstructionFailureFact -> {
                val target = result.cleanupTarget
                result.targetIdentity.target === target &&
                        cleanupFacet.owner.preparedTargetRoot.target === target &&
                        topologyFacet.preparedTargetAdmissionFact?.preparedTarget === target &&
                        cleanupFacet.preparedTargetFailureFact === result
            }
        }
    }

    private fun clearTargetRetirementFactsLocked(target: CurrentTarget): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        if (cleanupFacet.targetRetirementRoot !== target) return false
        cleanupFacet.targetRetirementRoot = null
        cleanupFacet.targetRetirementAdmissionClosedFact = null
        cleanupFacet.targetWorkDrainedFact = null
        cleanupFacet.targetGenerationFencedFact = null
        cleanupFacet.targetSurfaceReleaseReadyFact = null
        return true
    }

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
        private val CONTROL_SCHEDULER_UNAVAILABLE = IllegalStateException("Control scheduler unavailable")
        private val CONTROL_STARTUP_CONSTRUCTION_FACT_NOT_ELIGIBLE =
            IllegalStateException("Control construction failure fact was not eligible")
        private val CONTROL_STARTUP_NOT_ELIGIBLE =
            IllegalStateException("Control startup was not eligible")
        private val CONTROL_STARTUP_RELEASE_RECEIPT_MISMATCH =
            IllegalStateException("Control startup cleanup released without its exact termination receipt")
        private val CONTROL_SCHEDULE_PREINVOCATION_FACT_NOT_ELIGIBLE =
            IllegalStateException("Control schedule pre-invocation fact was not eligible")
        private val CONTROL_SCHEDULE_MARK_NOT_ELIGIBLE =
            IllegalStateException("Control schedule invocation mark was not eligible")
        private val CONTROL_SCHEDULE_FAILURE_FACT_NOT_ELIGIBLE =
            IllegalStateException("Control schedule invocation failure fact was not eligible")
        private val CONTROL_SCHEDULE_RETURN_NOT_ELIGIBLE =
            IllegalStateException("Control schedule return fact was not eligible")
        private val CONTROL_CANCEL_ADMITTED_WITHOUT_SCHEDULER =
            IllegalStateException("Control cancel was admitted without a scheduler")
        private val CONTROL_CANCELLATION_FACT_NOT_ELIGIBLE =
            IllegalStateException("Control cancellation fact was not eligible")
        private val CONTROL_DETACHED_ADMITTED_WITHOUT_SCHEDULER =
            IllegalStateException("Detached Control cancel was admitted without a scheduler")
        private val CONTROL_DETACHED_FACT_NOT_ELIGIBLE =
            IllegalStateException("Detached Control settlement fact was not eligible")
        private val CONTROL_DRAINER_ACCEPTANCE_NOT_ELIGIBLE =
            IllegalStateException("Control drainer acceptance fact was not eligible")
        private val CONTROL_AGGREGATE_FACT_IDENTITY_MISMATCH =
            IllegalStateException("Control aggregate fact identity mismatched")
        private val CONTROL_AGGREGATE_FACT_EXHAUSTED =
            IllegalStateException("Control aggregate fact cell was exhausted")
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
