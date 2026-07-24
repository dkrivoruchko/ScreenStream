package io.screenstream.engine.internal.session

import android.os.Handler
import android.os.HandlerThread
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.FrameRate
import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.internal.capture.Applied
import io.screenstream.engine.internal.capture.ApplyFailed
import io.screenstream.engine.internal.capture.ApplyPlan
import io.screenstream.engine.internal.capture.ApplyPlanResult
import io.screenstream.engine.internal.capture.ApplyPreflightResourceDenied
import io.screenstream.engine.internal.capture.ApplySkippedBeforeEntry
import io.screenstream.engine.internal.capture.ApplyUnsafeFailure
import io.screenstream.engine.internal.capture.CaptureCapsule
import io.screenstream.engine.internal.capture.CaptureClosed
import io.screenstream.engine.internal.capture.CaptureColorFact
import io.screenstream.engine.internal.capture.CaptureCommand
import io.screenstream.engine.internal.capture.CaptureLane
import io.screenstream.engine.internal.capture.CaptureLaneExchange
import io.screenstream.engine.internal.capture.CaptureRetainedLocally
import io.screenstream.engine.internal.capture.CaptureRetirement
import io.screenstream.engine.internal.capture.CaptureTargetMode
import io.screenstream.engine.internal.capture.CloseCapture
import io.screenstream.engine.internal.capture.CloseCaptureResult
import io.screenstream.engine.internal.capture.FrameReady
import io.screenstream.engine.internal.capture.NoCurrentSource
import io.screenstream.engine.internal.capture.OpenCapture
import io.screenstream.engine.internal.capture.OpenCaptureResult
import io.screenstream.engine.internal.capture.OpenFailed
import io.screenstream.engine.internal.capture.Opened
import io.screenstream.engine.internal.capture.ProjectionToken
import io.screenstream.engine.internal.capture.ReadFailed
import io.screenstream.engine.internal.capture.ReadFrame
import io.screenstream.engine.internal.capture.ReadFrameResult
import io.screenstream.engine.internal.capture.ReadSkippedBeforeEntry
import io.screenstream.engine.internal.capture.SourceAvailable
import io.screenstream.engine.internal.delivery.DeliveryCapsule
import io.screenstream.engine.internal.delivery.DeliveryClosedResult
import io.screenstream.engine.internal.delivery.DeliveryHandoff
import io.screenstream.engine.internal.delivery.DeliveryInstallDisposition
import io.screenstream.engine.internal.delivery.DeliveryInstallOutcome
import io.screenstream.engine.internal.delivery.DeliveryOutputKind
import io.screenstream.engine.internal.delivery.DeliveryRetirement
import io.screenstream.engine.internal.delivery.DeliveryRole
import io.screenstream.engine.internal.delivery.DeliverySessionBoundary
import io.screenstream.engine.internal.delivery.DeliverySubmission
import io.screenstream.engine.internal.jpeg.EncoderBackendPreparation
import io.screenstream.engine.internal.jpeg.EncoderBackendPreparationTask
import io.screenstream.engine.internal.jpeg.EncoderBackendProduct
import io.screenstream.engine.internal.jpeg.EncoderCapsule
import io.screenstream.engine.internal.jpeg.EncoderCarrierRetirementTask
import io.screenstream.engine.internal.jpeg.EncoderEntryState
import io.screenstream.engine.internal.jpeg.EncoderFrameworkPreparation
import io.screenstream.engine.internal.jpeg.EncoderFrameworkPreparationTask
import io.screenstream.engine.internal.jpeg.EncoderFrameworkRetirement
import io.screenstream.engine.internal.jpeg.EncoderManagedAllocationTask
import io.screenstream.engine.internal.jpeg.EncoderNativeAllocationTask
import io.screenstream.engine.internal.jpeg.EncoderProductionTask
import io.screenstream.engine.internal.jpeg.EncoderRetirement
import io.screenstream.engine.internal.jpeg.EncoderRuntime
import io.screenstream.engine.internal.jpeg.EncoderRuntimeCreation
import io.screenstream.engine.internal.jpeg.EncoderRuntimeRetirement
import io.screenstream.engine.internal.jpeg.EncoderRuntimeRetirementTask
import io.screenstream.engine.internal.jpeg.EncoderSetupTask
import io.screenstream.engine.internal.jpeg.EncoderTask
import io.screenstream.engine.internal.jpeg.EncoderTaskResult
import io.screenstream.engine.internal.jpeg.EncoderTaskReturn
import io.screenstream.engine.internal.jpeg.EncoderTerminalCaptureSettlement
import io.screenstream.engine.internal.jpeg.EncoderUninstalledFrameworkRetirementTask
import io.screenstream.engine.internal.jpeg.FrameworkBitmapRetirement
import io.screenstream.engine.internal.jpeg.FrameworkCaptureRead
import io.screenstream.engine.internal.jpeg.FrameworkJpegFailure
import io.screenstream.engine.internal.jpeg.FrameworkJpegResult
import io.screenstream.engine.internal.jpeg.FrameworkJpegSkipped
import io.screenstream.engine.internal.jpeg.FrameworkJpegSuccess
import io.screenstream.engine.internal.jpeg.FrameworkOnManagedCarrier
import io.screenstream.engine.internal.jpeg.FrameworkOnNativeCarrier
import io.screenstream.engine.internal.jpeg.FrameworkProduction
import io.screenstream.engine.internal.jpeg.JpegEnteredOperation
import io.screenstream.engine.internal.jpeg.JpegEntryDecision
import io.screenstream.engine.internal.jpeg.JpegPermitReleasedSink
import io.screenstream.engine.internal.jpeg.JpegRole
import io.screenstream.engine.internal.jpeg.JpegSubmission
import io.screenstream.engine.internal.jpeg.JpegTaskBoundary
import io.screenstream.engine.internal.jpeg.NativeEnabled
import io.screenstream.engine.internal.jpeg.NativeJpegFailure
import io.screenstream.engine.internal.jpeg.NativeJpegFailureKind
import io.screenstream.engine.internal.jpeg.NativeJpegResult
import io.screenstream.engine.internal.jpeg.NativeJpegSkipped
import io.screenstream.engine.internal.jpeg.NativeJpegSuccess
import io.screenstream.engine.internal.jpeg.NativeReturnedResult
import io.screenstream.engine.internal.jpeg.ProductionRecord
import io.screenstream.engine.internal.jpeg.jpegEnteredOperationSafetyNanos
import io.screenstream.engine.internal.metrics.MetricsAttachmentDispatch
import io.screenstream.engine.internal.metrics.MetricsAttachmentOutcome
import io.screenstream.engine.internal.metrics.MetricsAvailabilityFact
import io.screenstream.engine.internal.metrics.MetricsCloseDispatch
import io.screenstream.engine.internal.metrics.MetricsCloseOutcome
import io.screenstream.engine.internal.metrics.MetricsCutoff
import io.screenstream.engine.internal.metrics.MetricsExpiryOutcome
import io.screenstream.engine.internal.metrics.MetricsLateCutoffFact
import io.screenstream.engine.internal.metrics.MetricsLateCutoffSink
import io.screenstream.engine.internal.metrics.MetricsSubmissionOutcome
import io.screenstream.engine.internal.metrics.MetricsTerminalFact
import io.screenstream.engine.internal.metrics.MetricsTerminalPhase
import io.screenstream.engine.internal.metrics.SessionMetricsRole
import io.screenstream.engine.internal.metrics.SessionMetricsSummary
import io.screenstream.engine.internal.metrics.sameAuthorityValue
import io.screenstream.engine.internal.observation.CaptureCleanupReturn
import io.screenstream.engine.internal.observation.DeliveryCleanupReturn
import io.screenstream.engine.internal.observation.DiagnosticRequest
import io.screenstream.engine.internal.observation.EncoderCleanupReturn
import io.screenstream.engine.internal.observation.MetricsCleanupReturn
import io.screenstream.engine.internal.runtime.ControlDelayedWakes
import io.screenstream.engine.internal.runtime.JpegTimeoutWake
import io.screenstream.engine.internal.runtime.ReadinessWake
import io.screenstream.engine.internal.runtime.SessionSerialRoles
import io.screenstream.engine.internal.runtime.StatsWake
import io.screenstream.engine.internal.storage.FrameStore
import io.screenstream.engine.internal.storage.FrameStoreTerminalTransfer
import io.screenstream.engine.internal.storage.FrameworkEncodedTransaction
import io.screenstream.engine.internal.storage.ManagedEncodedTransaction
import io.screenstream.engine.internal.storage.NativeEncodedTransaction
import io.screenstream.engine.internal.storage.PublishedEncodedFrame
import java.util.concurrent.atomic.AtomicReference

/** Sole applied Session authority after the first Control Runnable enters. */
internal class SessionControlLoop internal constructor(
    private val frontDoor: SessionFrontDoor,
    private val observations: SessionObservations,
    private val metricsCapsule: io.screenstream.engine.internal.metrics.MetricsCapsule,
    private val captureCapsule: CaptureCapsule,
    private val encoderCapsule: EncoderCapsule,
    private val deliveryCapsule: DeliveryCapsule,
    private val serialRoles: SessionSerialRoles,
) : CaptureLaneExchange, DeliverySessionBoundary, JpegTaskBoundary,
    JpegPermitReleasedSink {
    private lateinit var controlThread: HandlerThread
    private lateinit var controlHandler: Handler
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var captureLane: CaptureLane
    private lateinit var metricsRole: SessionMetricsRole
    private lateinit var jpegRole: JpegRole
    private lateinit var deliveryRole: DeliveryRole

    private val frameStore = FrameStore()
    private val publicMetrics = SessionMetrics()
    private val delayedWakes = ControlDelayedWakes()
    private val encoderPermitContinuations = SessionEncoderPermitContinuations()
    private val lastEncoderCleanup = AtomicReference<EncoderCleanupReturn?>(null)
    private val lastDeliveryCleanup = AtomicReference<DeliveryCleanupReturn?>(null)
    private val turn = Runnable { runTurn() }
    private var metricsAttachmentQueued = false
    private var metricsReady: CaptureMetrics? = null
    private var lastMetricsAvailability: MetricsAvailabilityFact? = null
    private var metricsCompletionObserved = false
    private var desiredRevision = 0L
    private var desiredParameters = io.screenstream.engine.ScreenCaptureParameters()
    private var resolvedPlan: SessionPlanResolution.Resolved? = null
    private var resolvedPlanRevision = 0L
    private var captureOpenStarted = false
    private var captureOpened = false
    private var captureAppliedPlan: io.screenstream.engine.internal.capture.CapturePlan? = null
    private var captureApplyPending = false
    private var suspendedRevision = 0L
    private var encoderSetupPending = false
    private var encoderSetupPlan: io.screenstream.engine.internal.capture.CapturePlan? = null
    private var encoderSetupRevision = 0L
    private var encoderReturnedResult: EncoderTaskReturn? = null
    private var encoderPermitReleasedResult: EncoderTaskReturn? = null
    private var encoderFallbackPending = false
    private var pendingRuntimeModeChange = false
    private var initialJpegCapabilityCause: Throwable? = null
    private var active = false
    private var firstActivePublished = false
    private var capturedResizeWidth = 0
    private var capturedResizeHeight = 0
    private var capturedResizeRevisionPending = false
    private var pendingInitialResize: CapturedResizeSample? = null
    private var initialResizeAccepted = false
    private var initialResizeExpirySticky = false
    private var initialResizeExpiryCause: Throwable? = null
    private var initialResizeCapabilityEmitted = false
    private var capturedVisibility: Boolean? = null
    private var lastPublishedVisibility: Boolean? = null
    private var currentProjectionToken: ProjectionToken? = null
    private var initialResizeToken: ProjectionToken? = null
    private var resizeDeadlineNanos: Long? = null
    private var lastStatsPublicationNanos = frontDoor.creationElapsedRealtimeNanos
    private var currentRead: FrameworkCaptureRead? = null
    private var currentProduction: ProductionRecord? = null
    private var nextProductionId = 0L
    private var nextOutputSequence = 0L
    private var openResult: OpenCaptureResult? = null
    private var applyResult: ApplyPlanResult? = null
    private var readResult: ReadFrameResult? = null
    private var deliveryResult: DeliveryClosedResult? = null
    private var deliveryPermitReleasedResult: DeliveryClosedResult? = null
    private var deliveryPermitContinuation: DeliveryClosedResult? = null
    private var terminalDeliveryAwaitingPermit: DeliveryClosedResult? = null
    private var deliveryDirectFatalResult: DeliveryClosedResult.DirectFatal? = null
    private var lastOfferedFrame: PublishedEncodedFrame? = null
    private var runtimeProfileEmitted = false
    private var lastPublishedTargetMode: CaptureTargetMode? = null
    private var colorAction: CaptureColorFact? = null
    private var lastCaptureReturnedCommand: CaptureCommand? = null

    /** Cleanup-routing phase only; ordinary admission is owned solely by SessionFrontDoor's gate fence. */
    private var terminalTransferPrepared = false
    private var terminalMetricsClose: MetricsCloseDispatch? = null
    private var terminalCaptureClose: CloseCapture? = null
    private var terminalCaptureThreadCanQuit = false
    private var terminalEncoderTask: EncoderTask? = null
    private var terminalOutstandingRead: FrameworkCaptureRead? = null
    private var terminalReturnedReadResult: ReadFrameResult? = null
    private var cutoffReadBeforeTerminalTransfer: ReadFrameResult? = null
    private var deliveryStorageMismatchResult: DeliveryClosedResult? = null
    private var deliveryCleanupBeforeTerminalTransfer: DeliveryClosedResult? = null
    private val lastMetricsCleanup = AtomicReference<MetricsCleanupReturn?>(null)
    private val lastCaptureCleanup = AtomicReference<CaptureCleanupReturn?>(null)

    internal fun enterFirstTurn(bootstrap: BootstrapCapsule) {
        val transfer = frontDoor.transferToControl(bootstrap, this) ?: return
        controlThread = transfer.controlThread
        controlHandler = transfer.controlHandler
        captureThread = transfer.captureThread
        captureHandler = transfer.captureHandler
        captureLane = CaptureLane(captureHandler, controlHandler, this)
        metricsRole = SessionMetricsRole(
            sessionGate = frontDoor.sessionGate,
            serialView = serialRoles.metrics,
            elapsedRealtimeClock = frontDoor.executionClock,
            source = frontDoor.metricsSource,
            capsule = metricsCapsule,
            signalControl = frontDoor::signalControl,
            lateCutoffSink = MetricsLateCutoffSink(::onLateMetricsFact),
            ordinaryAdmissionOpenLocked = { frontDoor.ordinaryAdmissionOpenLocked(this) },
        )
        jpegRole = JpegRole(
            serialPermit = serialRoles.jpeg,
            clock = frontDoor.executionClock,
            boundary = this,
            permitReleasedSink = this,
        )
        deliveryRole = DeliveryRole(
            sessionGate = frontDoor.sessionGate,
            serialView = serialRoles.delivery,
            capsule = deliveryCapsule,
            boundary = this,
            terminalCleanupSink = observations.terminalCleanupSink,
        )
        runTurn()
    }

    internal fun requestWake(): Boolean = controlHandler.post(turn)

    internal fun isSelfUnsubscribeLocked(registrationId: Long): Boolean =
        ::deliveryRole.isInitialized && deliveryRole.isSelfUnsubscribeLocked(registrationId)

    internal fun captureCommandRootedLocked(command: CaptureCommand): Boolean {
        check(Thread.holdsLock(frontDoor.sessionGate))
        return captureCapsule.currentCommand === command || lastCaptureReturnedCommand === command
    }

    internal fun prepareTerminalLocked(): TerminalFold {
        check(Thread.holdsLock(frontDoor.sessionGate))
        terminalTransferPrepared = true
        delayedWakes.suppressAll()

        val installedEncoder = encoderReturnedResult
        val cleanupEncoder = installedEncoder ?: encoderPermitContinuations.currentReturn
        val installedDelivery = deliveryResult
        val deliveryPermitReleased = installedDelivery != null && installedDelivery === deliveryPermitReleasedResult
        val deliveryProblem = installedDelivery?.let { foldTerminalDelivery(it, deliveryPermitReleased) }
        readResult?.let(::foldTerminalRead)
        installedEncoder?.let(::foldTerminalEncoderReturn)
        if (installedDelivery != null && !deliveryPermitReleased) {
            check(deliveryPermitContinuation === installedDelivery)
            terminalDeliveryAwaitingPermit = installedDelivery
        } else {
            check(deliveryPermitContinuation == null)
        }

        deliveryCleanupBeforeTerminalTransfer?.let { returned ->
            check(frameStore.consumeLeaseRelease(returned.handoff.registrationId, returned.leaseRelease))
            deliveryCleanupBeforeTerminalTransfer = null
        }

        val metricsCutoff: MetricsCutoff = metricsRole.cutoffLocked()
        terminalMetricsClose = metricsCutoff.closeDispatch

        val pendingReadResult = readResult ?: cutoffReadBeforeTerminalTransfer
        val pendingRead = currentRead
        if (pendingReadResult != null && pendingRead != null && pendingReadResult.command === pendingRead.command) {
            terminalOutstandingRead = pendingRead
            terminalReturnedReadResult = pendingReadResult
            cutoffReadBeforeTerminalTransfer = null
        } else if (captureCapsule.currentCommand is ReadFrame) {
            terminalOutstandingRead = checkNotNull(
                pendingRead?.takeIf { it.command === captureCapsule.currentCommand },
            ) { "Capture capsule ReadFrame had no exact carrier ownership record" }
        }

        if (captureCapsule.entryState == io.screenstream.engine.internal.capture.CaptureEntryState.Queued) {
            captureCapsule.markCutoffInert()
        }
        if (captureCapsule.entryState == io.screenstream.engine.internal.capture.CaptureEntryState.Closed &&
            captureCapsule.hasUnresolvedOwnership && captureCapsule.closeRequested == null
        ) {
            CloseCapture().also {
                captureCapsule.requestClose(it)
                terminalCaptureClose = it
            }
        }
        terminalCaptureThreadCanQuit = !captureCapsule.hasUnresolvedOwnership

        if (encoderCapsule.entryState == EncoderEntryState.Queued &&
            (encoderCapsule.task is EncoderSetupTask || encoderCapsule.task is EncoderProductionTask)
        ) {
            encoderCapsule.markCutoffInert(checkNotNull(encoderCapsule.task))
        }
        val terminalRuntime = encoderCapsule.runtime
        if (terminalOutstandingRead == null &&
            encoderCapsule.entryState == EncoderEntryState.Closed
        ) {
            val returnedSuccessor = cleanupEncoder?.let { terminalEncoderSuccessor(it, terminalRuntime) }
            when {
                encoderCapsule.pendingTaskAfterPermitRelease != null -> Unit
                encoderPermitContinuations.hasCurrent -> {
                    returnedSuccessor?.let(encoderCapsule::holdAfterPermitRelease)
                }

                returnedSuccessor != null -> returnedSuccessor.also {
                    encoderCapsule.queue(it)
                    terminalEncoderTask = it
                }

                cleanupEncoder == null && terminalRuntime != null -> EncoderRuntimeRetirementTask(terminalRuntime).also {
                    encoderCapsule.queue(it)
                    terminalEncoderTask = it
                }
            }
        }
        if (deliveryCapsule.entryState == io.screenstream.engine.internal.delivery.DeliveryEntryState.Queued) {
            deliveryCapsule.markCutoffInert(checkNotNull(deliveryCapsule.handoff))
        }

        deliveryDirectFatalResult?.let { returned ->
            check(frameStore.consumeLeaseRelease(returned.handoff.registrationId, returned.leaseRelease))
            deliveryDirectFatalResult = null
        }
        check(deliveryStorageMismatchResult == null) {
            "A mismatched Delivery release remains exact-rooted and cannot be fabricated as capsule cleanup"
        }
        val terminalProductionId = frameStore.attachedProductionId
            ?: frameStore.unpublishedProduction?.productionId
        val terminalDeliveryLease = frameStore.lease
        check(
            terminalDeliveryLease == null || deliveryCapsule.handoff?.lease === terminalDeliveryLease ||
                    deliveryResult?.handoff?.lease === terminalDeliveryLease,
        ) { "Terminal Delivery lease had no exact capsule/result ownership root" }
        val terminalTransfer = frameStore.transferTerminal(
            expectedProductionId = terminalProductionId,
            expectedAttachedTransaction = frameStore.attachedProduction,
            expectedUnpublished = frameStore.unpublishedProduction,
            expectedLatest = frameStore.latest,
            expectedDisplaced = frameStore.displaced,
            expectedDeliveryLease = terminalDeliveryLease,
        )
        check(terminalTransfer is FrameStoreTerminalTransfer.Detached) {
            "Control's exact FrameStore terminal role snapshot did not match"
        }
        deliveryResult = null
        deliveryPermitReleasedResult = null
        encoderReturnedResult = null
        encoderPermitReleasedResult = null

        observations.terminalCleanupSink.initializeMetrics(metricsCutoff.retirement)
        observations.terminalCleanupSink.initializeCapture(
            if (captureCapsule.hasUnresolvedOwnership) {
                CaptureRetirement.ReturnExpected(captureCapsule)
            } else CaptureRetirement.Closed,
        )
        observations.terminalCleanupSink.initializeEncoder(
            when {
                encoderCapsule.hasOnlyProcessLifetimeResidue ->
                    EncoderRetirement.ProcessLifetimeResidue(encoderCapsule)

                encoderCapsule.hasUnresolvedOwnership || encoderSetupPending ||
                        encoderPermitContinuations.hasCurrent || terminalEncoderTask != null ->
                    EncoderRetirement.ReturnExpected(encoderCapsule)

                else -> EncoderRetirement.Closed
            },
        )
        observations.terminalCleanupSink.initializeDelivery(
            if (deliveryCapsule.hasUnresolvedOwnership || terminalDeliveryAwaitingPermit != null) {
                DeliveryRetirement.ReturnExpected(deliveryCapsule)
            } else DeliveryRetirement.Closed,
        )
        return TerminalFold(deliveryProblem)
    }

    private fun runTurn() {
        val ingress = frontDoor.beginControlTurn(this) ?: return
        if (ingress.terminalDecision != null) {
            finishTerminal()
            return
        }
        if (!ordinaryAdmissionOpen()) return
        if (!reconcileJpegTimeout()) return

        val revisionChanged = desiredRevision != 0L && desiredRevision != ingress.configRevision
        if (revisionChanged) suspendedRevision = 0L
        if (revisionChanged && firstActivePublished) {
            val historical = historicalEffectiveParameters()
                ?: run {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Running Session has no Active history"))
                    return
                }
            if (!publishOrdinaryState(
                    ScreenCaptureState.Reconfiguring.create(
                        ingress.desiredParameters,
                        historical,
                        historical.captureGeometry,
                        capturedVisibility,
                    ),
                    ingress.configRevision,
                )
            ) return
            active = false
            retireLatestOrdinary()
        }
        desiredRevision = ingress.configRevision
        desiredParameters = ingress.desiredParameters
        ensureMetricsAttachment()
        if (!ordinaryAdmissionOpen()) return
        val metricsSummary = synchronized(frontDoor.sessionGate) { metricsRole.summaryLocked() }
        if (!consumeMetrics(metricsSummary)) return
        if (!ordinaryAdmissionOpen()) return
        consumeClosedFacts()
        if (terminalTransferPrepared || !ordinaryAdmissionOpen()) return
        if (!consumeCapturedResizeTopology()) return
        if (!ordinaryAdmissionOpen()) return
        if (!resolveCurrentPlan()) return
        if (!ordinaryAdmissionOpen()) return
        convergeTopology()
        if (!tryPublishActive()) return
        if (active) maybeStartProduction()
        maybePublishStats()
    }

    private fun ensureMetricsAttachment() {
        if (metricsAttachmentQueued) return
        val dispatch: MetricsAttachmentDispatch = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            metricsRole.queueAttachmentLocked().also { metricsAttachmentQueued = true }
        }
        val submission = try {
            metricsRole.submitAttachment(dispatch)
        } catch (fatal: Throwable) {
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        when (submission) {
            MetricsSubmissionOutcome.Accepted -> Unit
            MetricsSubmissionOutcome.Rejected -> Unit
            is MetricsSubmissionOutcome.Failed -> Unit
        }
    }

    private fun consumeMetrics(summary: SessionMetricsSummary): Boolean {
        summary.ingressFailure?.let {
            fail(ScreenCaptureProblem.InternalFailure, it.cause)
            return false
        }
        when (val outcome = summary.attachmentOutcome) {
            is MetricsAttachmentOutcome.Thrown -> {
                fail(ScreenCaptureProblem.InternalFailure, outcome.cause)
                return false
            }

            is MetricsAttachmentOutcome.InvalidHandle -> {
                fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Metrics source returned no close handle"))
                return false
            }

            is MetricsAttachmentOutcome.DispatchFailed -> {
                fail(ScreenCaptureProblem.InternalFailure, outcome.cause)
                return false
            }

            is MetricsAttachmentOutcome.BoundaryFailed -> {
                fail(ScreenCaptureProblem.InternalFailure, outcome.cause)
                return false
            }

            is MetricsAttachmentOutcome.DirectFatal -> return false
            MetricsAttachmentOutcome.CutoffInert,
            MetricsAttachmentOutcome.NotQueued,
            MetricsAttachmentOutcome.Queued,
            MetricsAttachmentOutcome.Entered,
            is MetricsAttachmentOutcome.ReturnedWithHandle,
                -> Unit
        }
        summary.expiry?.let {
            fail(ScreenCaptureProblem.CaptureUnavailable, it.boundary.expiryCause)
            return false
        }
        if (!firstActivePublished && summary.lossBeforeFirstActive != null) {
            fail(ScreenCaptureProblem.CaptureUnavailable, null)
            return false
        }
        when (val terminal = summary.firstTerminal) {
            is MetricsTerminalFact.Failed -> {
                fail(ScreenCaptureProblem.InternalFailure, terminal.cause)
                return false
            }

            is MetricsTerminalFact.Completed -> if (terminal.phase == MetricsTerminalPhase.BeforeJointReadiness) {
                fail(ScreenCaptureProblem.CaptureUnavailable, null)
                return false
            } else if (!metricsCompletionObserved) {
                metricsCompletionObserved = true
                emitOrdinaryDiagnostic(
                    DiagnosticRequest("MetricsSource", "CapabilityCheck", "Metrics source completed after readiness", null),
                )
            }

            null -> Unit
        }
        val joint = summary.jointReadiness
        if (joint != null && metricsReady == null) metricsReady = joint.positive.metrics
        when (val latest = summary.latest) {
            is MetricsAvailabilityFact.Available -> if (joint != null || firstActivePublished) {
                if (!consumeMetricsAuthority(latest)) return false
                metricsReady = latest.metrics
            }

            is MetricsAvailabilityFact.Unavailable -> if (firstActivePublished) {
                if (!consumeMetricsAuthority(latest)) return false
                if (active) {
                    active = false
                    val historical = historicalEffectiveParameters()
                    if (historical != null) {
                        if (!publishOrdinaryState(
                                ScreenCaptureState.Suspended.create(
                                    desiredParameters,
                                    ScreenCaptureProblem.CaptureUnavailable,
                                    historical,
                                    historical.captureGeometry,
                                    capturedVisibility,
                                ),
                                desiredRevision,
                            )
                        ) return false
                    }
                    retireLatestOrdinary()
                }
                return false
            }

            null -> Unit
        }
        if (metricsReady == null) {
            summary.boundary?.let { armReadinessWake(it.deadlineNanos) }
            val now = frontDoor.executionClock.nanos()
            val expiryOutcome = synchronized(frontDoor.sessionGate) {
                metricsRole.expireFirstPositiveLocked(now)
            }
            if (expiryOutcome is MetricsExpiryOutcome.Expired) {
                expiryOutcome.closeDispatch?.let(metricsRole::submitClose)
                fail(ScreenCaptureProblem.CaptureUnavailable, expiryOutcome.fact.boundary.expiryCause)
                return false
            }
        }
        when (val close = summary.closeOutcome) {
            is MetricsCloseOutcome.Failed -> {
                if (!terminalTransferPrepared) fail(ScreenCaptureProblem.InternalFailure, close.cause)
                return false
            }

            is MetricsCloseOutcome.DispatchFailed -> {
                if (!terminalTransferPrepared) fail(ScreenCaptureProblem.InternalFailure, close.cause)
                return false
            }

            is MetricsCloseOutcome.DirectFatal -> return false
            else -> Unit
        }
        return true
    }

    private fun consumeMetricsAuthority(fact: MetricsAvailabilityFact): Boolean {
        val previous = lastMetricsAvailability
        if (previous != null && previous.sequence == fact.sequence) return true
        if (previous != null && previous.sameAuthorityValue(fact)) {
            lastMetricsAvailability = fact
            return true
        }
        if (!advanceTopologyRevision(publishReconfiguring = fact is MetricsAvailabilityFact.Available)) {
            return false
        }
        lastMetricsAvailability = fact
        return true
    }

    private fun consumeCapturedResizeTopology(): Boolean {
        val pending = synchronized(frontDoor.sessionGate) { capturedResizeRevisionPending }
        if (!pending) return true
        if (!advanceTopologyRevision(publishReconfiguring = true)) return false
        synchronized(frontDoor.sessionGate) {
            capturedResizeRevisionPending = false
        }
        return true
    }

    private fun advanceTopologyRevision(publishReconfiguring: Boolean): Boolean {
        val newRevision = try {
            synchronized(frontDoor.sessionGate) {
                frontDoor.advanceTopologyRevisionLocked(this, desiredRevision)
            }
        } catch (failure: IllegalStateException) {
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return false
        } ?: return false
        desiredRevision = newRevision
        suspendedRevision = 0L
        frontDoor.signalControl()
        if (!publishReconfiguring || !active) return true
        val historical = historicalEffectiveParameters()
            ?: run {
                fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Active topology has no resolved plan"))
                return false
            }
        if (!publishOrdinaryState(
                ScreenCaptureState.Reconfiguring.create(
                    desiredParameters,
                    historical,
                    historical.captureGeometry,
                    capturedVisibility,
                ),
                newRevision,
            )
        ) return false
        active = false
        retireLatestOrdinary()
        return true
    }

    private fun resolveCurrentPlan(): Boolean {
        if (suspendedRevision == desiredRevision) return false
        val metrics = metricsReady ?: return false
        val width = if (frontDoor.platformSdkInt >= 34 && capturedResizeWidth > 0) capturedResizeWidth else metrics.widthPx
        val height = if (frontDoor.platformSdkInt >= 34 && capturedResizeHeight > 0) capturedResizeHeight else metrics.heightPx
        val resolution = SessionPlanResolver.resolve(
            parameters = desiredParameters,
            widthPx = width,
            heightPx = height,
            densityDpi = metrics.densityDpi,
            platformSdkInt = frontDoor.platformSdkInt,
            sourceDimensionsAuthoritative = frontDoor.platformSdkInt < 34 || initialResizeAccepted,
        )
        if (resolution is SessionPlanResolution.Rejected) {
            resolvedPlanRevision = 0L
            if (firstActivePublished &&
                (resolution.problem == ScreenCaptureProblem.InvalidRequest ||
                        resolution.problem == ScreenCaptureProblem.ResourceExhausted)
            ) {
                suspendCurrentRevision(resolution.problem)
            } else {
                fail(resolution.problem, resolution.cause)
            }
            return false
        }
        val resolved = resolution as SessionPlanResolution.Resolved
        val previous = resolvedPlan
        if (active && previous != null && !samePlan(previous.capturePlan, resolved.capturePlan)) {
            active = false
            if (!publishOrdinaryState(
                    ScreenCaptureState.Reconfiguring.create(
                        desiredParameters,
                        previous.effectiveParameters,
                        previous.effectiveParameters.captureGeometry,
                        capturedVisibility,
                    ),
                    desiredRevision,
                )
            ) return false
            retireLatestOrdinary()
        }
        val admitted = synchronized(frontDoor.sessionGate) {
            frontDoor.isCurrentRevisionLocked(this, desiredRevision)
        }
        if (!admitted) return false
        resolvedPlan = resolved
        resolvedPlanRevision = desiredRevision
        return true
    }

    private fun convergeTopology() {
        if (!synchronized(frontDoor.sessionGate) { frontDoor.ordinaryAdmissionOpenLocked(this) }) return
        val desired = resolvedPlan ?: return
        if (currentProduction != null) return
        if (!captureOpenStarted) {
            dispatchOpen(desired)
            return
        }
        if (!captureOpened || captureApplyPending) return
        if (!samePlan(captureAppliedPlan, desired.capturePlan)) {
            dispatchApply(desired)
            return
        }
        if (encoderFallbackPending) {
            progressFrameworkFallback(desired)
        }
        val encoderSnapshot = synchronized(frontDoor.sessionGate) {
            Pair(encoderCapsule.entryState, encoderCapsule.runtime)
        }
        if (!encoderFallbackPending && !encoderSetupPending && encoderSnapshot.first == EncoderEntryState.Closed &&
            encoderSnapshot.second?.isCompatible(desired.capturePlan) != true
        ) {
            dispatchEncoderSetup(desired.capturePlan)
        }
    }

    private fun dispatchOpen(desired: SessionPlanResolution.Resolved) {
        val projection = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            captureCapsule.acceptedProjection
        } ?: run {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Accepted projection is not rooted"))
            return
        }
        val command = OpenCapture(desiredRevision, projection, desired.capturePlan)
        val adopted = try {
            synchronized(frontDoor.sessionGate) {
                if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
                val owner = captureLane.claim(command)
                captureCapsule.adopt(command, owner)
                true
            }
        } catch (failure: Exception) {
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        if (!adopted) return
        captureOpenStarted = true
        val accepted = try {
            captureLane.post(command)
        } catch (exception: Exception) {
            fail(ScreenCaptureProblem.InternalFailure, exception)
            return
        } catch (fatal: Throwable) {
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        if (!accepted) {
            synchronized(frontDoor.sessionGate) {
                check(captureCapsule.markCutoffInert(command))
                captureCapsule.settleCutoff(command)
            }
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Capture Open dispatch was rejected"))
        }
    }

    private fun dispatchApply(desired: SessionPlanResolution.Resolved) {
        val command = ApplyPlan(desiredRevision, desired.capturePlan)
        val queued = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            captureCapsule.queue(command)
            true
        }
        if (!queued) return
        captureApplyPending = true
        val accepted = try {
            captureLane.post(command)
        } catch (exception: Exception) {
            fail(ScreenCaptureProblem.InternalFailure, exception)
            return
        } catch (fatal: Throwable) {
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        if (!accepted) {
            synchronized(frontDoor.sessionGate) {
                check(captureCapsule.markCutoffInert(command))
                captureCapsule.settleCutoff(command)
            }
            captureApplyPending = false
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Capture ApplyPlan dispatch was rejected"))
        }
    }

    private fun dispatchEncoderSetup(plan: io.screenstream.engine.internal.capture.CapturePlan) {
        if (!synchronized(frontDoor.sessionGate) { frontDoor.ordinaryAdmissionOpenLocked(this) }) return
        encoderSetupPending = true
        encoderSetupPlan = plan
        encoderSetupRevision = desiredRevision
        val existing = synchronized(frontDoor.sessionGate) { encoderCapsule.runtime }
        if (existing != null) {
            queueAndSubmitEncoderTask(EncoderRuntimeRetirementTask(existing))
        } else {
            queueAndSubmitEncoderTask(newEncoderBackendPreparationTask(plan))
        }
    }

    private fun newEncoderBackendPreparationTask(
        plan: io.screenstream.engine.internal.capture.CapturePlan,
    ): EncoderBackendPreparationTask = synchronized(frontDoor.sessionGate) {
        EncoderBackendPreparationTask(
            plan = plan,
            backendPolicy = encoderBackendPolicy(),
            existingHealthCell = encoderCapsule.nativeHealthCell,
        )
    }

    private fun dispatchNativeAllocation(
        plan: io.screenstream.engine.internal.capture.CapturePlan,
        layout: io.screenstream.engine.internal.jpeg.RgbaLayout,
        preparation: EncoderBackendPreparation.NativeCarrier,
    ) {
        val task = try {
            val candidate = EncoderRuntime.newNativeAllocationCandidate(layout)
            EncoderNativeAllocationTask(plan, layout, preparation, candidate)
        } catch (allocationFailure: OutOfMemoryError) {
            encoderSetupPending = false
            fail(ScreenCaptureProblem.ResourceExhausted, allocationFailure)
            return
        } catch (failure: Exception) {
            encoderSetupPending = false
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        queueAndSubmitEncoderTask(task)
    }

    private fun queueAndSubmitEncoderTask(task: EncoderTask) {
        val queued = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            encoderCapsule.queue(task)
            true
        }
        if (!queued) return
        submitQueuedEncoderTask(task)
    }

    private fun submitQueuedEncoderTask(task: EncoderTask) {
        when (val submission = jpegRole.submitQueued(encoderCapsule, task)) {
            JpegSubmission.Accepted -> Unit
            JpegSubmission.PermitUnavailableRetained ->
                fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("JPEG task permit was unavailable"))

            is JpegSubmission.SubmissionFailedRetained ->
                fail(ScreenCaptureProblem.InternalFailure, submission.cause)
        }
    }

    private fun consumeClosedFacts() {
        val facts = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            val releasedDelivery = deliveryResult?.takeIf { it === deliveryPermitReleasedResult }
            val releasedEncoder = encoderReturnedResult?.takeIf { it === encoderPermitReleasedResult }
            ClosedFacts(
                openResult,
                applyResult,
                readResult,
                releasedEncoder,
                releasedDelivery,
            ).also {
                openResult = null
                applyResult = null
                readResult = null
                if (releasedEncoder != null) {
                    encoderReturnedResult = null
                    encoderPermitReleasedResult = null
                }
                if (releasedDelivery != null) {
                    deliveryResult = null
                    deliveryPermitReleasedResult = null
                }
            }
        }
        facts.encoder?.let(::consumeEncoderReturn)
        facts.open?.let(::consumeOpen)
        facts.apply?.let(::consumeApply)
        facts.read?.let(::consumeRead)
        facts.delivery?.let(::consumeDelivery)
        colorAction?.let {
            colorAction = null
            emitOrdinaryDiagnostic(
                DiagnosticRequest(
                    "ColorPipeline",
                    "ColorAction",
                    "${it.classification.name} with ${it.colorMode.name}",
                    null,
                ),
            )
        }
    }

    private fun consumeEncoderReturn(returned: EncoderTaskReturn) {
        if (!returned.returnedTimely) {
            stageExpiredEncoderCleanup(returned)
            cleanupReturnedProduction(returned)
            val cause = jpegTimeoutCause(returned.task)
            emitJpegTimeoutDiagnostic(returned.task, cause)
            fail(ScreenCaptureProblem.InternalFailure, cause)
            return
        }
        val task = returned.task
        val result = returned.result
        when (task) {
            is EncoderBackendPreparationTask -> {
                val prepared = (result as? EncoderTaskResult.BackendPrepared)?.result ?: return
                if (!currentSetupTask(task)) {
                    abandonEncoderSetup()
                    return
                }
                when (prepared) {
                    is EncoderBackendPreparation.NativeCarrier -> {
                        val layout = try {
                            EncoderRuntime.layoutFor(task.plan)
                        } catch (failure: Exception) {
                            encoderSetupPending = false
                            fail(ScreenCaptureProblem.InternalFailure, failure)
                            return
                        }
                        dispatchNativeAllocation(task.plan, layout, prepared)
                    }

                    is EncoderBackendPreparation.ManagedCarrier -> {
                        initialJpegCapabilityCause = prepared.cleanUnavailableCause
                        val layout = try {
                            EncoderRuntime.layoutFor(task.plan)
                        } catch (failure: Exception) {
                            encoderSetupPending = false
                            fail(ScreenCaptureProblem.InternalFailure, failure)
                            return
                        }
                        queueAndSubmitEncoderTask(EncoderManagedAllocationTask(task.plan, layout, prepared))
                    }

                    is EncoderBackendPreparation.Failed -> {
                        encoderSetupPending = false
                        fail(prepared.problem, prepared.cause)
                    }
                }
            }

            is EncoderNativeAllocationTask,
            is EncoderManagedAllocationTask,
                -> consumeRuntimeAllocation(task as EncoderSetupTask, result)

            is EncoderFrameworkPreparationTask -> consumeFrameworkPreparation(task, result)
            is EncoderRuntimeRetirementTask -> consumeFrameworkRetirement(task, result)
            is EncoderCarrierRetirementTask -> consumeCarrierRetirement(task, result)
            is EncoderUninstalledFrameworkRetirementTask -> consumeUninstalledFrameworkRetirement(task, result)
            is EncoderProductionTask -> consumeJpeg(task, checkNotNull(result))
        }
    }

    private fun currentSetupTask(task: EncoderSetupTask): Boolean = encoderSetupPending &&
            task.plan === encoderSetupPlan && encoderSetupRevision == desiredRevision &&
            encoderPlanReadyAfterCapture(task.plan)

    private fun encoderPlanReadyAfterCapture(
        plan: io.screenstream.engine.internal.capture.CapturePlan,
    ): Boolean = captureOpened && !captureApplyPending && samePlan(captureAppliedPlan, plan) &&
            resolvedPlanRevision == desiredRevision && suspendedRevision != desiredRevision &&
            resolvedPlan?.capturePlan?.let { samePlan(plan, it) } == true

    private fun abandonEncoderSetup() {
        encoderSetupPending = false
        encoderSetupPlan = null
        encoderSetupRevision = 0L
    }

    private fun consumeRuntimeAllocation(task: EncoderSetupTask, result: EncoderTaskResult?) {
        val creation = (result as? EncoderTaskResult.RuntimeAllocated)?.result ?: return
        if (!currentSetupTask(task)) {
            abandonEncoderSetup()
            val runtime = when (creation) {
                is EncoderRuntimeCreation.Created -> creation.runtime
                is EncoderRuntimeCreation.Failed -> creation.retainedRuntime
            }
            if (runtime != null) queueAndSubmitEncoderTask(EncoderRuntimeRetirementTask(runtime))
            return
        }
        when (creation) {
            is EncoderRuntimeCreation.Created -> when (creation.runtime.backendProduct) {
                is NativeEnabled -> encoderSetupPending = false
                is FrameworkOnNativeCarrier,
                is FrameworkOnManagedCarrier,
                    -> queueAndSubmitEncoderTask(
                    EncoderFrameworkPreparationTask(task.plan, creation.runtime, encoderFallbackPending),
                )
            }

            is EncoderRuntimeCreation.Failed -> {
                encoderSetupPending = false
                fail(creation.problem, creation.cause)
            }
        }
    }

    private fun consumeFrameworkPreparation(task: EncoderFrameworkPreparationTask, result: EncoderTaskResult?) {
        val preparation = (result as? EncoderTaskResult.FrameworkPrepared)?.result ?: return
        if (!currentSetupTask(task)) {
            abandonEncoderSetup()
            queueAndSubmitEncoderTask(EncoderUninstalledFrameworkRetirementTask(preparation))
            return
        }
        when (preparation) {
            is EncoderFrameworkPreparation.Prepared -> {
                if (!task.runtime.installFrameworkOwner(preparation)) {
                    queueAndSubmitEncoderTask(EncoderUninstalledFrameworkRetirementTask(preparation))
                    encoderSetupPending = false
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Framework owner installation failed"))
                    return
                }
                encoderSetupPending = false
                if (task.fallback) {
                    pendingRuntimeModeChange = true
                    encoderFallbackPending = false
                }
            }

            is EncoderFrameworkPreparation.Failed -> {
                preparation.ownerResidue?.let { check(task.runtime.retainFrameworkOwnerResidue(preparation)) }
                encoderSetupPending = false
                fail(preparation.problem, preparation.cause)
            }
        }
    }

    private fun consumeFrameworkRetirement(task: EncoderRuntimeRetirementTask, result: EncoderTaskResult?) {
        when (val retirement = (result as? EncoderTaskResult.FrameworkRetired)?.result ?: return) {
            EncoderFrameworkRetirement.Closed -> queueAndSubmitEncoderTask(EncoderCarrierRetirementTask(task.runtime))
            is EncoderFrameworkRetirement.Retained -> {
                encoderSetupPending = false
                fail(ScreenCaptureProblem.InternalFailure, retirement.cause ?: IllegalStateException("Bitmap owner retained"))
            }
        }
    }

    private fun consumeCarrierRetirement(task: EncoderCarrierRetirementTask, result: EncoderTaskResult?) {
        when (val retirement = (result as? EncoderTaskResult.CarrierRetired)?.result ?: return) {
            EncoderRuntimeRetirement.Closed -> {
                val plan = encoderSetupPlan
                if (encoderSetupPending && plan != null && encoderSetupRevision == desiredRevision &&
                    encoderPlanReadyAfterCapture(plan)
                ) {
                    queueAndSubmitEncoderTask(newEncoderBackendPreparationTask(plan))
                } else if (encoderSetupPending) {
                    abandonEncoderSetup()
                }
            }

            is EncoderRuntimeRetirement.Retained -> {
                encoderSetupPending = false
                fail(ScreenCaptureProblem.InternalFailure, retirement.cause ?: IllegalStateException("Carrier retained"))
            }
        }
    }

    private fun consumeUninstalledFrameworkRetirement(
        task: EncoderUninstalledFrameworkRetirementTask,
        result: EncoderTaskResult?,
    ) {
        when (val retirement = (result as? EncoderTaskResult.UninstalledFrameworkRetired)?.result ?: return) {
            FrameworkBitmapRetirement.Closed -> queueAndSubmitEncoderTask(EncoderRuntimeRetirementTask(task.runtime))
            is FrameworkBitmapRetirement.Retained -> {
                queueAndSubmitEncoderTask(EncoderRuntimeRetirementTask(task.runtime))
                fail(ScreenCaptureProblem.InternalFailure, retirement.cause ?: IllegalStateException("Framework residue retained"))
            }
        }
    }

    private fun consumeOpen(result: OpenCaptureResult) {
        when (result) {
            is Opened -> {
                captureOpened = true
                captureAppliedPlan = result.command.plan
                if (frontDoor.platformSdkInt >= 34) {
                    val deadline = result.firstResizeDeadlineNanos
                    if (deadline == null) {
                        fail(
                            ScreenCaptureProblem.InternalFailure,
                            IllegalStateException("API 34+ display opened without an initial-resize deadline"),
                        )
                        return
                    }
                    when (bindInitialResizeDeadline(deadline)) {
                        InitialResizeReadiness.Ready -> Unit
                        InitialResizeReadiness.Waiting -> armReadinessWake(deadline)
                        InitialResizeReadiness.Expired -> failInitialResizeExpiry()
                    }
                }
            }

            is OpenFailed -> fail(result.problem, result.cause)
        }
    }

    private fun consumeApply(result: ApplyPlanResult) {
        captureApplyPending = false
        when (result) {
            is Applied -> captureAppliedPlan = result.command.plan
            is ApplyFailed -> when (val disposition = result.disposition) {
                is ApplyPreflightResourceDenied -> {
                    val current = synchronized(frontDoor.sessionGate) {
                        frontDoor.isCurrentRevisionLocked(this, result.command.configRevision)
                    }
                    if (current) {
                        if (firstActivePublished) {
                            suspendCurrentRevision(ScreenCaptureProblem.ResourceExhausted)
                        } else {
                            fail(ScreenCaptureProblem.ResourceExhausted, disposition.cause)
                        }
                    }
                }

                is ApplyUnsafeFailure -> fail(disposition.problem, disposition.cause)
            }

            is ApplySkippedBeforeEntry -> Unit
        }
    }

    private fun consumeRead(result: ReadFrameResult) {
        val read = currentRead ?: run {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Capture result has no production"))
            return
        }
        if (!read.runtime.acceptCaptureResult(read, result)) {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Capture result did not match carrier ownership"))
            return
        }
        var revisionStaleAtAdmission = false
        val productionCurrent = synchronized(frontDoor.sessionGate) {
            val current = frontDoor.productionRevisionOpenLocked(this, read.command.configRevision)
            revisionStaleAtAdmission = !current && frontDoor.ordinaryAdmissionOpenLocked(this)
            current
        }
        if (!productionCurrent) {
            when (result) {
                is FrameReady -> {
                    publicMetrics.recordReadback(result.readbackDurationNanos)
                    check(read.runtime.settleReadyWithoutEncoding(read))
                    if (revisionStaleAtAdmission) publicMetrics.recordStaleWork()
                }

                is ReadFailed -> publicMetrics.recordProductionFailure()
                is NoCurrentSource,
                is ReadSkippedBeforeEntry,
                    -> Unit
            }
            clearProduction()
            if (result is ReadFailed) {
                fail(result.problem, result.cause)
            }
            return
        }
        when (result) {
            is FrameReady -> {
                publicMetrics.recordReadback(result.readbackDurationNanos)
                val record = checkNotNull(currentProduction)
                val runtime = read.runtime
                val transaction: ManagedEncodedTransaction
                val task: EncoderProductionTask
                when (runtime.backendProduct) {
                    is NativeEnabled -> {
                        val nativeTransaction = NativeEncodedTransaction()
                        transaction = nativeTransaction
                        val production = runtime.newNativeProduction(read, nativeTransaction, desiredParameters.jpegQuality)
                        if (production == null) {
                            nativeTransaction.abort()
                            check(runtime.settleReadyWithoutEncoding(read))
                            failProduction(
                                record.configRevision,
                                ScreenCaptureProblem.InternalFailure,
                                IllegalStateException("Native production could not be formed"),
                            )
                            return
                        }
                        task = EncoderProductionTask.Native(production)
                    }

                    is FrameworkOnNativeCarrier,
                    is FrameworkOnManagedCarrier,
                        -> {
                        val frameworkTransaction = FrameworkEncodedTransaction()
                        transaction = frameworkTransaction
                        val production = runtime.newFrameworkProduction(
                            read,
                            frameworkTransaction,
                            desiredParameters.jpegQuality,
                        )
                        if (production == null) {
                            frameworkTransaction.abort()
                            check(runtime.settleReadyWithoutEncoding(read))
                            failProduction(
                                record.configRevision,
                                ScreenCaptureProblem.InternalFailure,
                                IllegalStateException("Framework production could not be formed"),
                            )
                            return
                        }
                        task = EncoderProductionTask.Framework(production)
                    }
                }
                if (!frameStore.attachProduction(record.productionId, transaction)) {
                    skipProductionBeforeQueue(task)
                    failProduction(
                        record.configRevision,
                        ScreenCaptureProblem.InternalFailure,
                        IllegalStateException("FrameStore production slot was occupied"),
                    )
                    return
                }
                var revisionStaleAtQueue = false
                val queued = synchronized(frontDoor.sessionGate) {
                    if (!frontDoor.productionRevisionOpenLocked(this, record.configRevision)) {
                        revisionStaleAtQueue = frontDoor.ordinaryAdmissionOpenLocked(this)
                        return@synchronized false
                    }
                    encoderCapsule.queue(task)
                    true
                }
                if (!queued) {
                    skipProductionBeforeQueue(task)
                    check(frameStore.detachProduction(record.productionId, transaction) === transaction)
                    if (revisionStaleAtQueue) publicMetrics.recordStaleWork()
                    clearProduction()
                    return
                }
                when (val submission = jpegRole.submitQueued(encoderCapsule, task)) {
                    JpegSubmission.Accepted -> Unit
                    JpegSubmission.PermitUnavailableRetained ->
                        fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("JPEG permit was unavailable"))

                    is JpegSubmission.SubmissionFailedRetained ->
                        fail(ScreenCaptureProblem.InternalFailure, submission.cause)
                }
            }

            is NoCurrentSource,
            is ReadSkippedBeforeEntry,
                -> clearProduction()

            is ReadFailed -> {
                publicMetrics.recordProductionFailure()
                clearProduction()
                failProduction(result.command.configRevision, result.problem, result.cause)
            }
        }
    }

    private fun skipProductionBeforeQueue(task: EncoderProductionTask) {
        when (task) {
            is EncoderProductionTask.Framework -> checkNotNull(task.runtime.skipBeforeEntry(task.production))
            is EncoderProductionTask.Native -> checkNotNull(task.runtime.skipNativeBeforeEntry(task.production))
        }
    }

    private fun consumeJpeg(task: EncoderProductionTask, result: EncoderTaskResult) {
        when (task) {
            is EncoderProductionTask.Framework -> consumeFrameworkJpeg(
                task.production,
                (result as EncoderTaskResult.FrameworkProduced).result,
            )

            is EncoderProductionTask.Native -> consumeNativeJpeg(
                task,
                (result as EncoderTaskResult.NativeProduced).result,
            )
        }
    }

    private fun consumeFrameworkJpeg(production: FrameworkProduction, result: FrameworkJpegResult) {
        val record = currentProduction
        if (record == null || record !== production.record ||
            result.productionId != record.productionId || result.configRevision != record.configRevision
        ) {
            consumeStaleJpeg(result)
            return
        }
        var revisionStaleAtAdmission = false
        val productionCurrent = synchronized(frontDoor.sessionGate) {
            val current = frontDoor.productionRevisionOpenLocked(this, record.configRevision)
            revisionStaleAtAdmission = !current && frontDoor.ordinaryAdmissionOpenLocked(this)
            current
        }
        if (!productionCurrent) {
            consumeStaleJpeg(result, revisionStaleAtAdmission)
            return
        }
        when (result) {
            is FrameworkJpegSuccess -> {
                val unpublished = frameStore.completeProduction(result.productionId, result.transaction)
                if (unpublished == null || unpublished.payload !== result.payload) {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Committed JPEG did not match FrameStore"))
                    return
                }
                publicMetrics.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                if (!ordinaryAdmissionOpen()) return
                if (record.configRevision != desiredRevision || !active) {
                    frameStore.retireUnpublished(unpublished)
                    publicMetrics.recordStaleWork()
                    clearProduction()
                    return
                }
                var published: PublishedEncodedFrame? = null
                var revisionStaleAtOutput = false
                val outputCommitted = frontDoor.serializePublication {
                    val reserved = synchronized(frontDoor.sessionGate) {
                        if (!frontDoor.productionRevisionOpenLocked(this, record.configRevision)) {
                            revisionStaleAtOutput = frontDoor.ordinaryAdmissionOpenLocked(this)
                            return@synchronized false
                        }
                        frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.Output)
                    }
                    if (!reserved) return@serializePublication false
                    try {
                        val sequence = nextSequence() ?: return@serializePublication false
                        val timestamp = frontDoor.executionClock.nanos()
                        published = frameStore.publish(
                            unpublished,
                            frameStore.latest,
                            checkNotNull(resolvedPlan).effectiveParameters,
                            sequence,
                            timestamp,
                        ) ?: run {
                            fail(
                                ScreenCaptureProblem.InternalFailure,
                                IllegalStateException("JPEG publication transition failed"),
                            )
                            return@serializePublication false
                        }
                        publicMetrics.recordFreshOutput(timestamp)
                        clearProduction()
                        true
                    } finally {
                        frontDoor.completeControlPublication(this, ControlPublicationKind.Output)
                    }
                }
                if (!outputCommitted) {
                    check(frameStore.retireUnpublished(unpublished))
                    if (revisionStaleAtOutput) publicMetrics.recordStaleWork()
                    clearProduction()
                    return
                }
                offerDelivery(checkNotNull(published), record.configRevision)
            }

            is FrameworkJpegFailure -> {
                frameStore.detachProduction(result.productionId, result.transaction)
                publicMetrics.recordProductionFailure()
                clearProduction()
                if (result.problem != null) {
                    failProduction(record.configRevision, result.problem, result.cause)
                }
            }

            is FrameworkJpegSkipped -> {
                frameStore.detachProduction(result.productionId, result.transaction)
                clearProduction()
            }
        }
    }

    private fun consumeNativeJpeg(task: EncoderProductionTask.Native, result: NativeJpegResult) {
        val production = task.production
        val record = currentProduction
        val matchesCurrent = record === production.record && result.record === production.record
        val productionCurrent = matchesCurrent && synchronized(frontDoor.sessionGate) {
            frontDoor.productionRevisionOpenLocked(this, production.record.configRevision)
        }
        when (result) {
            is NativeJpegSuccess -> {
                val unpublished = frameStore.completeProduction(result.record.productionId, result.transaction)
                if (unpublished == null || unpublished.payload !== result.payload) {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Native JPEG did not match FrameStore"))
                    return
                }
                publicMetrics.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                if (!productionCurrent || !active || production.record.configRevision != desiredRevision) {
                    frameStore.retireUnpublished(unpublished)
                    publicMetrics.recordStaleWork()
                    clearProduction()
                    return
                }
                publishEncodedSuccess(production.record, unpublished)
            }

            is NativeJpegFailure -> {
                frameStore.detachProduction(result.record.productionId, result.transaction)
                publicMetrics.recordProductionFailure()
                clearProduction()
                when (result.kind) {
                    NativeJpegFailureKind.SafeCompressorAllocationFailure ->
                        activateFrameworkFallback(production, result)

                    NativeJpegFailureKind.RequiredAllocationExhaustion -> if (productionCurrent) {
                        fail(
                            ScreenCaptureProblem.ResourceExhausted,
                            result.cause ?: IllegalStateException(
                                "Native JPEG required allocation exhausted: ${result.returnedEvidence}",
                            ),
                        )
                    }

                    NativeJpegFailureKind.InternalFailure -> {
                        val malformed = (result.returnedEvidence as? NativeReturnedResult.InternalFailure)?.malformed
                        if (productionCurrent || malformed != null) {
                            fail(
                                ScreenCaptureProblem.InternalFailure,
                                result.cause ?: malformed?.inspectionFailure ?: malformed?.transactionFailure
                                ?: IllegalStateException("Native JPEG returned internal failure"),
                            )
                        }
                    }
                }
            }

            is NativeJpegSkipped -> {
                frameStore.detachProduction(result.record.productionId, result.transaction)
                clearProduction()
            }
        }
    }

    private fun publishEncodedSuccess(
        record: ProductionRecord,
        unpublished: io.screenstream.engine.internal.storage.UnpublishedEncodedFrame,
    ) {
        var published: PublishedEncodedFrame? = null
        var revisionStaleAtOutput = false
        val outputCommitted = frontDoor.serializePublication {
            val reserved = synchronized(frontDoor.sessionGate) {
                if (!frontDoor.productionRevisionOpenLocked(this, record.configRevision)) {
                    revisionStaleAtOutput = frontDoor.ordinaryAdmissionOpenLocked(this)
                    return@synchronized false
                }
                frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.Output)
            }
            if (!reserved) return@serializePublication false
            try {
                val sequence = nextSequence() ?: return@serializePublication false
                val timestamp = frontDoor.executionClock.nanos()
                published = frameStore.publish(
                    unpublished,
                    frameStore.latest,
                    checkNotNull(resolvedPlan).effectiveParameters,
                    sequence,
                    timestamp,
                ) ?: run {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("JPEG publication transition failed"))
                    return@serializePublication false
                }
                publicMetrics.recordFreshOutput(timestamp)
                clearProduction()
                true
            } finally {
                frontDoor.completeControlPublication(this, ControlPublicationKind.Output)
            }
        }
        if (!outputCommitted) {
            check(frameStore.retireUnpublished(unpublished))
            if (revisionStaleAtOutput) publicMetrics.recordStaleWork()
            clearProduction()
            return
        }
        offerDelivery(checkNotNull(published), record.configRevision)
    }

    private fun activateFrameworkFallback(
        production: io.screenstream.engine.internal.jpeg.NativeJpegProduction,
        result: NativeJpegFailure,
    ) {
        val disabled = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) || encoderCapsule.runtime !== production.runtime ||
                result.healthCell !== production.healthCell ||
                result.healthCell !== encoderCapsule.nativeHealthCell
            ) return@synchronized false
            if (!production.runtime.disableNativeForLaterFrames(result.healthCell)) return@synchronized false
            encoderFallbackPending = true
            if (resolvedPlanRevision == desiredRevision && suspendedRevision != desiredRevision) {
                encoderSetupPending = true
                resolvedPlan?.capturePlan?.let { encoderSetupPlan = it }
                encoderSetupRevision = desiredRevision
            } else {
                encoderSetupPending = false
                encoderSetupPlan = null
                encoderSetupRevision = 0L
            }
            frontDoor.pauseProductionForEncoderFallbackLocked(this, desiredRevision)
            true
        }
        if (!disabled) return
        resolvedPlan?.takeIf {
            resolvedPlanRevision == desiredRevision && suspendedRevision != desiredRevision
        }?.let(::progressFrameworkFallback)
    }

    private fun progressFrameworkFallback(desired: SessionPlanResolution.Resolved) {
        if (!encoderFallbackPending || resolvedPlan !== desired || resolvedPlanRevision != desiredRevision ||
            suspendedRevision == desiredRevision
        ) return
        val paused = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return
            encoderSetupPending = true
            encoderSetupPlan = desired.capturePlan
            encoderSetupRevision = desiredRevision
            frontDoor.pauseProductionForEncoderFallbackLocked(this, desiredRevision)
        }
        if (!paused) return
        if (active) {
            val historical = historicalEffectiveParameters()
                ?: run {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Active fallback has no history"))
                    return
                }
            if (!publishOrdinaryState(
                    ScreenCaptureState.Reconfiguring.create(
                        desiredParameters,
                        historical,
                        historical.captureGeometry,
                        capturedVisibility,
                    ),
                    desiredRevision,
                )
            ) return
            active = false
        }
        if (!retireLatestOrdinary()) return

        var invalidFallbackRuntime = false
        val nextTask = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) ||
                !frontDoor.pauseProductionForEncoderFallbackLocked(this, desiredRevision) ||
                encoderCapsule.entryState != EncoderEntryState.Closed ||
                encoderCapsule.task != null || encoderReturnedResult != null ||
                encoderPermitContinuations.hasCurrent
            ) return@synchronized null
            val runtime = encoderCapsule.runtime
                ?: return@synchronized newEncoderBackendPreparationTask(desired.capturePlan)
            val sameLayout = runtime.layout.hasShape(
                desired.capturePlan.outputWidthPx,
                desired.capturePlan.outputHeightPx,
                desired.capturePlan.rgbaCarrierByteCount,
            )
            if (!sameLayout) return@synchronized EncoderRuntimeRetirementTask(runtime)
            when (runtime.backendProduct) {
                is NativeEnabled -> {
                    invalidFallbackRuntime = true
                    null
                }

                is FrameworkOnNativeCarrier,
                is FrameworkOnManagedCarrier,
                    -> EncoderFrameworkPreparationTask(
                    desired.capturePlan,
                    runtime,
                    fallback = true,
                )
            }
        }
        if (invalidFallbackRuntime) {
            fail(
                ScreenCaptureProblem.InternalFailure,
                IllegalStateException("Fallback intent retained a Native-enabled runtime"),
            )
            return
        }
        nextTask?.let(::queueAndSubmitEncoderTask)
    }

    private fun encoderBackendPolicy(): JpegBackendPolicy =
        if (encoderFallbackPending) JpegBackendPolicy.FrameworkOnly else frontDoor.jpegBackendPolicy

    private fun consumeStaleJpeg(result: FrameworkJpegResult, recordSuccessfulStaleWork: Boolean = true) {
        when (result) {
            is FrameworkJpegSuccess -> {
                val unpublished = frameStore.completeProduction(result.productionId, result.transaction)
                if (unpublished == null || unpublished.payload !== result.payload) {
                    fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Stale JPEG did not match FrameStore"))
                    return
                }
                publicMetrics.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                frameStore.retireUnpublished(unpublished)
                if (recordSuccessfulStaleWork) publicMetrics.recordStaleWork()
            }

            is FrameworkJpegFailure -> {
                frameStore.detachProduction(result.productionId, result.transaction)
                publicMetrics.recordProductionFailure()
            }

            is FrameworkJpegSkipped -> frameStore.detachProduction(result.productionId, result.transaction)
        }
        clearProduction()
    }

    private fun consumeDelivery(result: DeliveryClosedResult) {
        if (!frameStore.consumeLeaseRelease(result.handoff.registrationId, result.leaseRelease)) {
            deliveryStorageMismatchResult = result
            fail(
                ScreenCaptureProblem.InternalFailure,
                IllegalStateException("Delivery release did not match the Control-owned FrameStore lease"),
            )
            return
        }
        val registration = synchronized(frontDoor.sessionGate) {
            val current = frontDoor.currentRegistrationLocked(result.handoff.registrationId)
            current
        }
        when (result) {
            is DeliveryClosedResult.CallbackException -> {
                publicMetrics.recordCallbackFailure()
                emitOrdinaryDiagnostic(
                    DiagnosticRequest("FrameConsumer", "DeliveryProblem", "Frame callback threw", result.exception),
                )
            }

            is DeliveryClosedResult.InternalFailure -> fail(ScreenCaptureProblem.InternalFailure, result.exception)
            is DeliveryClosedResult.DirectFatal -> return
            is DeliveryClosedResult.CallbackReturned,
            is DeliveryClosedResult.CutoffBeforeEntry,
                -> Unit
        }
        if (registration != null) frontDoor.completeDeliveryHandoff(registration)
        lastOfferedFrame = null
    }

    /** Final-accounting fold for a Capture result whose gate installation won before cutoff. */
    private fun foldTerminalRead(result: ReadFrameResult) {
        val read = currentRead ?: return
        if (result.command !== read.command) return
        when (result) {
            is FrameReady -> publicMetrics.recordReadback(result.readbackDurationNanos)
            is ReadFailed -> publicMetrics.recordProductionFailure()
            is NoCurrentSource,
            is ReadSkippedBeforeEntry,
                -> Unit
        }
    }

    /** Final-accounting fold for a timely JPEG result whose gate installation won before cutoff. */
    private fun foldTerminalEncoderReturn(returned: EncoderTaskReturn) {
        if (!returned.returnedTimely) return
        when (val task = returned.task) {
            is EncoderProductionTask.Framework -> foldTerminalFrameworkJpeg(
                task.production,
                (returned.result as? EncoderTaskResult.FrameworkProduced)?.result ?: return,
            )

            is EncoderProductionTask.Native -> foldTerminalNativeJpeg(
                task,
                (returned.result as? EncoderTaskResult.NativeProduced)?.result ?: return,
            )

            else -> Unit
        }
    }

    private fun foldTerminalFrameworkJpeg(production: FrameworkProduction, result: FrameworkJpegResult) {
        val record = currentProduction ?: return
        if (record !== production.record || result.productionId != record.productionId ||
            result.configRevision != record.configRevision
        ) {
            return
        }
        when (result) {
            is FrameworkJpegSuccess -> {
                val unpublished = frameStore.completeProduction(result.productionId, result.transaction)
                if (unpublished?.payload === result.payload) {
                    publicMetrics.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                }
            }

            is FrameworkJpegFailure -> {
                frameStore.detachProduction(result.productionId, result.transaction)
                publicMetrics.recordProductionFailure()
            }

            is FrameworkJpegSkipped -> frameStore.detachProduction(result.productionId, result.transaction)
        }
    }

    private fun foldTerminalNativeJpeg(task: EncoderProductionTask.Native, result: NativeJpegResult) {
        val record = currentProduction ?: return
        if (record !== task.record || result.record !== record) return
        when (result) {
            is NativeJpegSuccess -> {
                val unpublished = frameStore.completeProduction(record.productionId, result.transaction)
                if (unpublished?.payload === result.payload) {
                    publicMetrics.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                }
            }

            is NativeJpegFailure -> {
                frameStore.detachProduction(record.productionId, result.transaction)
                publicMetrics.recordProductionFailure()
            }

            is NativeJpegSkipped -> frameStore.detachProduction(record.productionId, result.transaction)
        }
    }

    /** Final-accounting fold for a Delivery result whose gate installation won before cutoff. */
    private fun foldTerminalDelivery(
        result: DeliveryClosedResult,
        permitReleased: Boolean,
    ): DiagnosticRequest? {
        if (permitReleased) {
            check(frameStore.consumeLeaseRelease(result.handoff.registrationId, result.leaseRelease))
        }
        return if (result is DeliveryClosedResult.CallbackException) {
            publicMetrics.recordCallbackFailure()
            DiagnosticRequest("FrameConsumer", "DeliveryProblem", "Frame callback threw", result.exception)
        } else {
            null
        }
    }

    private fun tryPublishActive(): Boolean {
        val desired = resolvedPlan ?: return true
        if (currentProduction != null || encoderReturnedResult != null ||
            encoderPermitContinuations.hasCurrent || encoderFallbackPending
        ) return true
        if (encoderSetupPending) return true
        if (!captureOpened || captureApplyPending || !samePlan(captureAppliedPlan, desired.capturePlan)) return true
        if (frontDoor.platformSdkInt >= 34) {
            when (initialResizeReadiness(frontDoor.executionClock.nanos())) {
                InitialResizeReadiness.Ready -> Unit
                InitialResizeReadiness.Waiting -> return true
                InitialResizeReadiness.Expired -> {
                    failInitialResizeExpiry()
                    return false
                }
            }
        }
        val visibility = synchronized(frontDoor.sessionGate) { capturedVisibility }
        if (active && lastPublishedVisibility == visibility) return true
        val state = ScreenCaptureState.Active.create(desired.effectiveParameters, visibility)
        val first = !firstActivePublished
        val completion = frontDoor.serializePublication {
            val committed = synchronized(frontDoor.sessionGate) {
                if (capturedVisibility != visibility) return@synchronized false
                val runtime = encoderCapsule.runtime ?: return@synchronized false
                if (!runtime.isCompatible(desired.capturePlan)) return@synchronized false
                val runtimeModes = runtimeModes(desired.capturePlan.targetMode, runtime.backendProduct)
                val metricsSummary = metricsRole.summaryLocked()
                val metricsCurrent = metricsSummary.latest !is MetricsAvailabilityFact.Unavailable &&
                        if (first) {
                            metricsSummary.jointReadiness != null && metricsSummary.lossBeforeFirstActive == null
                        } else {
                            metricsSummary.latest is MetricsAvailabilityFact.Available ||
                                    metricsSummary.jointReadiness != null
                        }
                if (!metricsCurrent) return@synchronized false
                val reserved = frontDoor.enterActivePublicationLocked(
                    owner = this,
                    expectedRevision = desiredRevision,
                    effectiveParameters = desired.effectiveParameters,
                    modes = runtimeModes,
                    first = first,
                )
                if (reserved) metricsRole.markFirstActiveLocked()
                reserved
            }
            if (!committed) return@serializePublication null
            observations.publishState(state)
            firstActivePublished = true
            lastPublishedVisibility = visibility
            val activeTargetMode = desired.capturePlan.targetMode
            val previousTargetMode = lastPublishedTargetMode
            lastPublishedTargetMode = activeTargetMode
            if (first && !runtimeProfileEmitted) {
                runtimeProfileEmitted = true
                val modes = synchronized(frontDoor.sessionGate) {
                    runtimeModes(activeTargetMode, checkNotNull(encoderCapsule.runtime).backendProduct)
                }
                observations.emitDiagnostic(
                    DiagnosticRequest(
                        "SurfaceTarget",
                        "CapabilityCheck",
                        "Selected ${modes.target.name} target with ${modes.carrier.name} carrier",
                        null,
                    ),
                )
                observations.emitDiagnostic(
                    DiagnosticRequest(
                        if (modes.jpeg == ActiveJpegMode.Native) "NativeJpeg" else "FrameworkJpeg",
                        "CapabilityCheck",
                        "Selected ${modes.jpeg.name} JPEG backend",
                        initialJpegCapabilityCause,
                    ),
                )
                observations.emitDiagnostic(
                    DiagnosticRequest(
                        "Session",
                        "RuntimeProfile",
                        modes.describe(),
                        null,
                    ),
                )
            }
            if (!first && previousTargetMode != null && previousTargetMode != activeTargetMode) {
                observations.emitDiagnostic(
                    DiagnosticRequest(
                        "SurfaceTarget",
                        "RuntimeModeChanged",
                        "Surface target changed from ${previousTargetMode.name} to ${activeTargetMode.name}",
                        null,
                    ),
                )
            }
            if (!first && pendingRuntimeModeChange) {
                pendingRuntimeModeChange = false
                observations.emitDiagnostic(
                    DiagnosticRequest(
                        "FrameworkJpeg",
                        "RuntimeModeChanged",
                        "Native JPEG disabled; Framework JPEG active on the retained native carrier",
                        null,
                    ),
                )
            }
            frontDoor.completeActivePublication(this, desiredRevision).also {
                active = it.ordinaryWorkMayContinue
            }
        }
        if (completion == null) return false
        delayedWakes.readiness.suppress()
        return completion.ordinaryWorkMayContinue
    }

    private fun runtimeModes(targetMode: CaptureTargetMode, product: EncoderBackendProduct): ActiveRuntimeModes {
        val activeTarget = when (targetMode) {
            CaptureTargetMode.Full -> ActiveTargetMode.Full
            CaptureTargetMode.Downscaled -> ActiveTargetMode.Downscaled
        }
        return when (product) {
            is NativeEnabled -> ActiveRuntimeModes(
                activeTarget,
                ActiveCarrierMode.NativeMalloc,
                ActiveJpegMode.Native,
            )

            is FrameworkOnNativeCarrier -> ActiveRuntimeModes(
                activeTarget,
                ActiveCarrierMode.NativeMalloc,
                ActiveJpegMode.Framework,
            )

            is FrameworkOnManagedCarrier -> ActiveRuntimeModes(
                activeTarget,
                ActiveCarrierMode.ManagedDirect,
                ActiveJpegMode.Framework,
            )
        }
    }

    private fun maybeStartProduction() {
        if (currentProduction != null) return
        if (desiredParameters.frameRate !is FrameRate.Auto) return
        val runtime = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.productionRevisionOpenLocked(this, desiredRevision)) return@synchronized null
            if (encoderCapsule.entryState != EncoderEntryState.Closed) return@synchronized null
            val currentRuntime = encoderCapsule.runtime ?: return@synchronized null
            val candidate = captureCapsule.sourceCandidate ?: return@synchronized null
            if (!candidate.consume()) return@synchronized null
            currentRuntime
        } ?: return
        if (nextProductionId == Long.MAX_VALUE) {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("productionId exhausted"))
            return
        }
        nextProductionId += 1L
        val record = ProductionRecord(desiredRevision, nextProductionId)
        val read = runtime.newCaptureRead(record, checkNotNull(resolvedPlan).capturePlan) ?: run {
            publicMetrics.recordPipelineBusy()
            return
        }
        currentProduction = record
        currentRead = read
        val queued = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.productionRevisionOpenLocked(this, record.configRevision)) return@synchronized false
            captureCapsule.queue(read.command)
            true
        }
        if (!queued) {
            runtime.acceptCaptureResult(read, ReadSkippedBeforeEntry(read.command))
            clearProduction()
            return
        }
        val accepted = try {
            captureLane.post(read.command)
        } catch (exception: Exception) {
            fail(ScreenCaptureProblem.InternalFailure, exception)
            return
        } catch (fatal: Throwable) {
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        if (!accepted) {
            synchronized(frontDoor.sessionGate) {
                check(captureCapsule.markCutoffInert(read.command))
                captureCapsule.settleCutoff(read.command)
            }
            runtime.acceptCaptureResult(read, ReadSkippedBeforeEntry(read.command))
            clearProduction()
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Capture ReadFrame dispatch was rejected"))
        }
    }

    private fun offerDelivery(frame: PublishedEncodedFrame, configRevision: Long) {
        val handoff = frontDoor.serializePublication {
            synchronized(frontDoor.sessionGate) {
                if (!frontDoor.productionRevisionOpenLocked(this, configRevision)) return@synchronized null
                val registration = frontDoor.openRegistrationLocked() ?: return@synchronized null
                if (deliveryCapsule.hasUnresolvedOwnership || registration.handoffOutstanding) {
                    publicMetrics.recordConsumerBusy()
                    return@synchronized null
                }
                val lease = frameStore.acquireLease(frame, registration.id) ?: return@synchronized null
                DeliveryHandoff(
                    registration.id,
                    DeliveryOutputKind.Fresh,
                    registration.callback,
                    lease,
                ).also {
                    registration.admitHandoff()
                    deliveryCapsule.queue(it)
                }
            }
        } ?: return
        lastOfferedFrame = frame
        val submission = try {
            deliveryRole.submit(handoff)
        } catch (fatal: Throwable) {
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal) {
                frontDoor.ordinaryAdmissionOpenLocked(this) && deliveryCapsule.handoff === handoff
            }
            throw fatal
        }
        when (submission) {
            DeliverySubmission.Accepted,
            is DeliverySubmission.Rejected,
                -> Unit

            is DeliverySubmission.SubmissionFailedRetained ->
                fail(ScreenCaptureProblem.InternalFailure, submission.cause)
        }
    }

    private fun finishTerminal() {
        val fold = frontDoor.prepareTerminalFromControl(this) ?: return
        settleReturnedTerminalRead()
        fold.deliveryProblem?.let(observations::emitDiagnostic)
        val terminal = frontDoor.serializePublication {
            val claimed = frontDoor.claimPreparedTerminalFromControl(this, publicMetrics.snapshot())
                ?: return@serializePublication null
            observations.publishTerminal(claimed.publication)
            claimed
        } ?: return
        observations.terminalCleanupSink.activateMetrics()
        observations.terminalCleanupSink.activateCapture()
        observations.terminalCleanupSink.activateEncoder()
        observations.terminalCleanupSink.activateDelivery()

        terminalMetricsClose?.let(metricsRole::submitClose)
        terminalCaptureClose?.let(::postTerminalCaptureClose)
        if (terminalCaptureThreadCanQuit) captureThread.quitSafely()
        terminalEncoderTask?.let(::submitTerminalEncoderTask)
        frontDoor.completeTerminalPublication(terminal)
        controlThread.quitSafely()
    }

    private fun settleReturnedTerminalRead() {
        val read = terminalOutstandingRead ?: return
        val result = terminalReturnedReadResult ?: return
        val settlement = read.runtime.settleTerminalCaptureReturn(read, result)
        check(settlement is EncoderTerminalCaptureSettlement.Settled) {
            "Durable Capture return did not match its exact carrier ownership"
        }
        synchronized(frontDoor.sessionGate) {
            check(terminalOutstandingRead === read && terminalReturnedReadResult === result)
            check(encoderCapsule.entryState == EncoderEntryState.Closed)
            check(encoderCapsule.runtime === settlement.successor.runtime)
            encoderCapsule.queue(settlement.successor)
            terminalEncoderTask = settlement.successor
            terminalOutstandingRead = null
            terminalReturnedReadResult = null
            readResult = null
            clearProduction()
        }
    }

    private fun postTerminalCaptureClose(command: CloseCapture) {
        val accepted = try {
            captureLane.post(command)
        } catch (exception: Exception) {
            reportCaptureCleanup(true, exception)
            return
        }
        if (!accepted) {
            synchronized(frontDoor.sessionGate) {
                check(captureCapsule.markCutoffInert(command))
                captureCapsule.retainCloseCutoff(command)
            }
            reportCaptureCleanup(true, IllegalStateException("Capture Close dispatch was rejected"))
        }
    }

    private fun submitTerminalEncoderTask(task: EncoderTask) {
        when (val submission = jpegRole.submitQueued(encoderCapsule, task)) {
            JpegSubmission.Accepted -> Unit
            JpegSubmission.PermitUnavailableRetained -> {
                val failure = IllegalStateException("Encoder retirement permit was unavailable")
                reportEncoderCleanup(true, failure)
            }

            is JpegSubmission.SubmissionFailedRetained -> reportEncoderCleanup(true, submission.cause)
        }
    }

    private fun reconcileJpegTimeout(): Boolean {
        val operation = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return true
            encoderCapsule.enteredOperation
        } ?: run {
            delayedWakes.jpegTimeout.suppress()
            return true
        }
        val now = frontDoor.executionClock.nanos()
        if (now >= operation.deadlineNanos) {
            delayedWakes.jpegTimeout.suppress()
            val cause = jpegTimeoutCause(operation.task)
            emitJpegTimeoutDiagnostic(operation.task, cause)
            fail(ScreenCaptureProblem.InternalFailure, cause)
            return false
        }
        val wake = JpegTimeoutWake(operation.deadlineNanos, operation)
        if (!delayedWakes.jpegTimeout.arm(wake)) return true
        val remaining = try {
            Math.subtractExact(operation.deadlineNanos, now)
        } catch (failure: ArithmeticException) {
            delayedWakes.jpegTimeout.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return false
        }
        val accepted = try {
            controlHandler.postDelayed(
                {
                    if (!delayedWakes.jpegTimeout.enter(wake)) return@postDelayed
                    frontDoor.signalControl()
                },
                nanosToCeilingMillis(remaining),
            )
        } catch (failure: Exception) {
            delayedWakes.jpegTimeout.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return false
        }
        if (!accepted) {
            delayedWakes.jpegTimeout.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("JPEG timeout wake was rejected"))
            return false
        }
        return true
    }

    private fun jpegTimeoutCause(task: EncoderTask): IllegalStateException = IllegalStateException(
        "${jpegTaskSource(task)} entered operation did not return before its safety deadline",
    )

    private fun emitJpegTimeoutDiagnostic(task: EncoderTask, cause: Throwable) {
        emitOrdinaryDiagnostic(
            DiagnosticRequest(
                jpegTaskSource(task),
                "CapabilityCheck",
                "Entered JPEG operation exceeded its safety window",
                cause,
            ),
        )
    }

    private fun jpegTaskSource(task: EncoderTask): String = when (task) {
        is EncoderBackendPreparationTask,
        is EncoderNativeAllocationTask,
        is EncoderProductionTask.Native,
            -> "NativeJpeg"

        is EncoderManagedAllocationTask,
        is EncoderFrameworkPreparationTask,
        is EncoderRuntimeRetirementTask,
        is EncoderUninstalledFrameworkRetirementTask,
        is EncoderProductionTask.Framework,
            -> "FrameworkJpeg"

        is EncoderCarrierRetirementTask -> when (task.runtime.backendProduct) {
            is NativeEnabled,
            is FrameworkOnNativeCarrier,
                -> "NativeJpeg"

            is FrameworkOnManagedCarrier -> "FrameworkJpeg"
        }
    }

    private fun cleanupReturnedProduction(returned: EncoderTaskReturn) {
        when (val result = returned.result) {
            is EncoderTaskResult.FrameworkProduced -> when (val jpeg = result.result) {
                is FrameworkJpegSuccess -> frameStore.completeProduction(jpeg.productionId, jpeg.transaction)
                    ?.let(frameStore::retireUnpublished)

                is FrameworkJpegFailure -> frameStore.detachProduction(jpeg.productionId, jpeg.transaction)
                is FrameworkJpegSkipped -> frameStore.detachProduction(jpeg.productionId, jpeg.transaction)
            }

            is EncoderTaskResult.NativeProduced -> when (val jpeg = result.result) {
                is NativeJpegSuccess -> frameStore.completeProduction(jpeg.record.productionId, jpeg.transaction)
                    ?.let(frameStore::retireUnpublished)

                is NativeJpegFailure -> frameStore.detachProduction(jpeg.record.productionId, jpeg.transaction)
                is NativeJpegSkipped -> frameStore.detachProduction(jpeg.record.productionId, jpeg.transaction)
            }

            else -> Unit
        }
        if (returned.task is EncoderProductionTask) clearProduction()
    }

    private fun stageExpiredEncoderCleanup(returned: EncoderTaskReturn) {
        synchronized(frontDoor.sessionGate) {
            if (encoderCapsule.entryState != EncoderEntryState.Closed || terminalEncoderTask != null) return
            val successor = terminalEncoderSuccessor(returned, encoderCapsule.runtime) ?: return
            encoderCapsule.queue(successor)
            terminalEncoderTask = successor
        }
    }

    private fun armReadinessWake(deadlineNanos: Long) {
        if (!ordinaryAdmissionOpen()) return
        val wake = ReadinessWake(deadlineNanos)
        if (!delayedWakes.readiness.arm(wake)) return
        val now = frontDoor.executionClock.nanos()
        val remaining = try {
            maxOf(0L, Math.subtractExact(deadlineNanos, now))
        } catch (failure: ArithmeticException) {
            delayedWakes.readiness.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        val delayMillis = nanosToCeilingMillis(remaining)
        val accepted = try {
            controlHandler.postDelayed(
                {
                    if (!delayedWakes.readiness.enter(wake)) return@postDelayed
                    frontDoor.signalControl()
                },
                delayMillis,
            )
        } catch (exception: Exception) {
            delayedWakes.readiness.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, exception)
            return
        } catch (fatal: Throwable) {
            delayedWakes.readiness.suppress(wake)
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        if (!accepted) {
            delayedWakes.readiness.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Readiness wake dispatch was rejected"))
        }
    }

    private fun bindInitialResizeDeadline(deadlineNanos: Long): InitialResizeReadiness {
        val nowNanos = frontDoor.executionClock.nanos()
        return synchronized(frontDoor.sessionGate) {
            resizeDeadlineNanos = deadlineNanos
            if (initialResizeToken == null) initialResizeToken = currentProjectionToken
            val pending = pendingInitialResize
            if (pending != null && !initialResizeAccepted) {
                initialResizeToken = pending.token
                pendingInitialResize = null
                if (pending.token !== currentProjectionToken) {
                    initialResizeExpirySticky = true
                } else if (pending.arrivalElapsedRealtimeNanos < deadlineNanos) {
                    capturedResizeWidth = pending.widthPx
                    capturedResizeHeight = pending.heightPx
                    initialResizeAccepted = true
                    capturedResizeRevisionPending = true
                } else {
                    initialResizeExpirySticky = true
                }
            }
            initialResizeReadinessLocked(nowNanos)
        }
    }

    private fun initialResizeReadiness(nowNanos: Long): InitialResizeReadiness =
        synchronized(frontDoor.sessionGate) { initialResizeReadinessLocked(nowNanos) }

    private fun initialResizeReadinessLocked(nowNanos: Long): InitialResizeReadiness {
        check(Thread.holdsLock(frontDoor.sessionGate))
        if (initialResizeAccepted) return InitialResizeReadiness.Ready
        if (initialResizeExpirySticky) return InitialResizeReadiness.Expired
        val deadline = resizeDeadlineNanos ?: return InitialResizeReadiness.Waiting
        if (nowNanos >= deadline) {
            initialResizeExpirySticky = true
            return InitialResizeReadiness.Expired
        }
        return InitialResizeReadiness.Waiting
    }

    private fun failInitialResizeExpiry() {
        val cause = initialResizeExpiryCause ?: IllegalStateException(
            "Initial captured-content resize did not arrive before its exact deadline",
        ).also { initialResizeExpiryCause = it }
        active = false
        if (!frontDoor.selectControlFailure(ScreenCaptureProblem.CaptureUnavailable, cause)) return
        if (!initialResizeCapabilityEmitted) {
            initialResizeCapabilityEmitted = true
            observations.emitDiagnostic(
                DiagnosticRequest(
                    "MediaProjection",
                    "CapabilityCheck",
                    "Initial captured-content resize expired",
                    cause,
                ),
            )
        }
    }

    private fun maybePublishStats() {
        if (!publicMetrics.dirty || terminalTransferPrepared) return
        if (!ordinaryAdmissionOpen()) return
        val now = frontDoor.executionClock.nanos()
        val eligibleAt = try {
            Math.addExact(lastStatsPublicationNanos, STATS_CADENCE_NANOS)
        } catch (failure: ArithmeticException) {
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        if (now >= eligibleAt) {
            val snapshot = publicMetrics.snapshot()
            if (!publishOrdinaryStats(snapshot)) return
            publicMetrics.markPublished()
            lastStatsPublicationNanos = now
            delayedWakes.stats.suppress()
            return
        }
        val wake = StatsWake(eligibleAt)
        if (!delayedWakes.stats.arm(wake)) return
        val remaining = try {
            Math.subtractExact(eligibleAt, now)
        } catch (failure: ArithmeticException) {
            delayedWakes.stats.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        val delayMillis = nanosToCeilingMillis(remaining)
        val accepted = try {
            controlHandler.postDelayed(
                {
                    if (delayedWakes.stats.enter(wake)) {
                        frontDoor.signalControl()
                    }
                },
                delayMillis,
            )
        } catch (exception: Exception) {
            delayedWakes.stats.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, exception)
            return
        } catch (fatal: Throwable) {
            delayedWakes.stats.suppress(wake)
            frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal)
            throw fatal
        }
        if (!accepted) {
            delayedWakes.stats.suppress(wake)
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Stats wake dispatch was rejected"))
        }
    }

    private fun nanosToCeilingMillis(nanos: Long): Long {
        check(nanos >= 0L)
        val wholeMillis = nanos / NANOS_PER_MILLISECOND
        return if (nanos % NANOS_PER_MILLISECOND == 0L) wholeMillis else wholeMillis + 1L
    }

    private fun historicalEffectiveParameters(): io.screenstream.engine.ScreenCaptureEffectiveParameters? =
        synchronized(frontDoor.sessionGate) {
            frontDoor.lastEffectiveParametersLocked(this)
        }

    private fun suspendCurrentRevision(problem: ScreenCaptureProblem) {
        check(problem == ScreenCaptureProblem.InvalidRequest || problem == ScreenCaptureProblem.ResourceExhausted)
        if (suspendedRevision == desiredRevision) return
        val historical = synchronized(frontDoor.sessionGate) {
            if (!frontDoor.pauseProductionForSuspensionLocked(this, desiredRevision)) return
            frontDoor.lastEffectiveParametersLocked(this)
        } ?: run {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("Running suspension has no Active history"))
            return
        }
        active = false
        if (!publishOrdinaryState(
                ScreenCaptureState.Suspended.create(
                    desiredParameters,
                    problem,
                    historical,
                    historical.captureGeometry,
                    capturedVisibility,
                ),
                desiredRevision,
            )
        ) return
        suspendedRevision = desiredRevision
        retireLatestOrdinary()
    }

    private fun fail(problem: ScreenCaptureProblem, cause: Throwable?) {
        active = false
        frontDoor.selectControlFailure(problem, cause)
    }

    private fun failProduction(
        expectedRevision: Long,
        problem: ScreenCaptureProblem,
        cause: Throwable?,
    ): Boolean {
        val selected = frontDoor.selectProductionFailure(this, expectedRevision, problem, cause)
        if (selected) active = false
        return selected
    }

    private fun ordinaryAdmissionOpen(): Boolean = synchronized(frontDoor.sessionGate) {
        frontDoor.ordinaryAdmissionOpenLocked(this)
    }

    private fun publishOrdinaryState(state: ScreenCaptureState, expectedRevision: Long): Boolean {
        return frontDoor.serializePublication {
            val committed = synchronized(frontDoor.sessionGate) {
                if (!frontDoor.isCurrentRevisionLocked(this, expectedRevision)) return@synchronized false
                frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.State)
            }
            if (!committed) return@serializePublication false
            try {
                observations.publishState(state)
            } finally {
                frontDoor.completeControlPublication(this, ControlPublicationKind.State)
            }
            true
        }
    }

    private fun publishOrdinaryStats(stats: io.screenstream.engine.ScreenCaptureStats): Boolean {
        return frontDoor.serializePublication {
            val committed = synchronized(frontDoor.sessionGate) {
                frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.Stats)
            }
            if (!committed) return@serializePublication false
            try {
                observations.publishStats(stats)
            } finally {
                frontDoor.completeControlPublication(this, ControlPublicationKind.Stats)
            }
            true
        }
    }

    private fun emitOrdinaryDiagnostic(request: DiagnosticRequest): Boolean {
        return frontDoor.serializePublication {
            val committed = synchronized(frontDoor.sessionGate) {
                frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.Diagnostic)
            }
            if (!committed) return@serializePublication false
            try {
                observations.emitDiagnostic(request)
            } finally {
                frontDoor.completeControlPublication(this, ControlPublicationKind.Diagnostic)
            }
            true
        }
    }

    private fun retireLatestOrdinary(): Boolean {
        val latest = frameStore.latest ?: return true
        return frontDoor.serializePublication {
            val committed = synchronized(frontDoor.sessionGate) {
                frontDoor.reserveControlPublicationLocked(this, ControlPublicationKind.Cache)
            }
            if (!committed) return@serializePublication false
            try {
                check(frameStore.retireLatest(latest))
            } finally {
                frontDoor.completeControlPublication(this, ControlPublicationKind.Cache)
            }
            true
        }
    }

    private fun nextSequence(): Long? {
        if (nextOutputSequence == Long.MAX_VALUE) {
            fail(ScreenCaptureProblem.InternalFailure, IllegalStateException("output sequence exhausted"))
            return null
        }
        nextOutputSequence += 1L
        return nextOutputSequence
    }

    private fun clearProduction() {
        currentRead = null
        currentProduction = null
    }

    private fun samePlan(
        left: io.screenstream.engine.internal.capture.CapturePlan?,
        right: io.screenstream.engine.internal.capture.CapturePlan,
    ): Boolean = left != null && left.parameters == right.parameters &&
            left.sourceWidthPx == right.sourceWidthPx && left.sourceHeightPx == right.sourceHeightPx &&
            left.densityDpi == right.densityDpi && left.targetMode == right.targetMode &&
            left.targetWidthPx == right.targetWidthPx && left.targetHeightPx == right.targetHeightPx &&
            left.outputWidthPx == right.outputWidthPx &&
            left.outputHeightPx == right.outputHeightPx

    private fun onLateMetricsFact(fact: MetricsLateCutoffFact) {
        while (true) {
            val previous = lastMetricsCleanup.get()
            val next = MetricsCleanupReturn(fact.capsule, previous, fact.residueRemains, fact.cause)
            if (!lastMetricsCleanup.compareAndSet(previous, next)) continue
            observations.terminalCleanupSink.metricsReturned(next)
            return
        }
    }

    override fun arbitrate(capsule: EncoderCapsule, task: EncoderTask): JpegEntryDecision {
        var arithmeticFailure: ArithmeticException? = null
        val decision = synchronized(frontDoor.sessionGate) {
            val eligible = when (task) {
                is EncoderProductionTask -> frontDoor.productionRevisionOpenLocked(
                    this,
                    task.record.configRevision,
                )

                is EncoderSetupTask -> frontDoor.ordinaryAdmissionOpenLocked(this)
                is EncoderRuntimeRetirementTask,
                is EncoderCarrierRetirementTask,
                is EncoderUninstalledFrameworkRetirementTask,
                    -> true
            }
            if (!eligible) {
                capsule.markCutoffInert(task)
                return@synchronized JpegEntryDecision.CutoffInert
            }
            val operation = if (!terminalTransferPrepared && frontDoor.ordinaryAdmissionOpenLocked(this)) {
                val started = frontDoor.executionClock.nanos()
                val deadline = try {
                    Math.addExact(started, jpegEnteredOperationSafetyNanos)
                } catch (failure: ArithmeticException) {
                    arithmeticFailure = failure
                    capsule.markCutoffInert(task)
                    return@synchronized JpegEntryDecision.CutoffInert
                }
                JpegEnteredOperation(task, started, deadline)
            } else null
            check(capsule.markEntered(task, operation))
            JpegEntryDecision.Entered(operation)
        }
        arithmeticFailure?.let { fail(ScreenCaptureProblem.InternalFailure, it) }
        if (decision is JpegEntryDecision.Entered && decision.operation != null) frontDoor.signalControl()
        return decision
    }

    override fun onReturned(capsule: EncoderCapsule, returned: EncoderTaskReturn) {
        synchronized(frontDoor.sessionGate) {
            capsule.closeAfterRealReturn(returned.task, checkNotNull(returned.result))
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) encoderReturnedResult = returned
            encoderPermitContinuations.install(returned)
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) && capsule.pendingTaskAfterPermitRelease == null) {
                terminalEncoderSuccessor(returned, capsule.runtime)?.let(capsule::holdAfterPermitRelease)
            }
        }
    }

    override fun onSkippedBeforeEntry(capsule: EncoderCapsule, returned: EncoderTaskReturn) {
        synchronized(frontDoor.sessionGate) {
            capsule.closeSkippedBeforeEntry(returned.task)
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) encoderReturnedResult = returned
            encoderPermitContinuations.install(returned)
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) && capsule.pendingTaskAfterPermitRelease == null) {
                terminalEncoderSuccessor(returned, capsule.runtime)?.let(capsule::holdAfterPermitRelease)
            }
        }
    }

    override fun onFatal(capsule: EncoderCapsule, task: EncoderTask, fatal: Throwable) {
        frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, fatal) {
            capsule.task === task || encoderReturnedResult?.task === task ||
                    encoderPermitContinuations.currentReturn?.task === task
        }
    }

    override fun onPermitReleased(capsule: EncoderCapsule, task: EncoderTask) {
        var wakeControl = false
        var successor: EncoderTask? = null
        var cleanupResidue: Boolean? = null
        var cleanupCause: Throwable? = null
        synchronized(frontDoor.sessionGate) {
            val continuation = encoderPermitContinuations.release(task) ?: return
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) {
                encoderPermitReleasedResult = encoderReturnedResult?.takeIf { it === continuation.returned }
                wakeControl = true
            } else {
                successor = capsule.promoteAfterPermitRelease()
                if (successor == null) {
                    cleanupResidue = capsule.hasUnresolvedOwnership
                    cleanupCause = encoderFailureCause(continuation.returned)
                        ?: capsule.processLifetimeResidueCause
                }
            }
        }
        if (wakeControl) frontDoor.signalControl()
        cleanupResidue?.let { reportEncoderCleanup(it, cleanupCause) }
        successor?.let(::submitTerminalEncoderTask)
    }

    private fun reportEncoderCleanup(residueRemains: Boolean, cause: Throwable?) {
        while (true) {
            val previous = lastEncoderCleanup.get()
            val next = EncoderCleanupReturn(encoderCapsule, previous, residueRemains, cause)
            if (!lastEncoderCleanup.compareAndSet(previous, next)) continue
            observations.terminalCleanupSink.encoderReturned(next)
            return
        }
    }

    private fun encoderFailureCause(returned: EncoderTaskReturn): Throwable? = when (val result = returned.result) {
        is EncoderTaskResult.BackendPrepared -> (result.result as? EncoderBackendPreparation.Failed)?.cause
        is EncoderTaskResult.RuntimeAllocated -> (result.result as? EncoderRuntimeCreation.Failed)?.cause
        is EncoderTaskResult.FrameworkPrepared -> (result.result as? EncoderFrameworkPreparation.Failed)?.cause
        is EncoderTaskResult.FrameworkRetired -> (result.result as? EncoderFrameworkRetirement.Retained)?.cause
        is EncoderTaskResult.CarrierRetired -> (result.result as? EncoderRuntimeRetirement.Retained)?.cause
        is EncoderTaskResult.UninstalledFrameworkRetired ->
            (result.result as? FrameworkBitmapRetirement.Retained)?.cause

        is EncoderTaskResult.FrameworkProduced -> (result.result as? FrameworkJpegFailure)?.cause
        is EncoderTaskResult.NativeProduced -> (result.result as? NativeJpegFailure)?.cause
        null -> null
    }

    private fun terminalEncoderSuccessor(returned: EncoderTaskReturn, runtime: EncoderRuntime?): EncoderTask? =
        when (val task = returned.task) {
            is EncoderBackendPreparationTask -> null
            is EncoderNativeAllocationTask,
            is EncoderManagedAllocationTask,
                -> runtime?.let(::EncoderRuntimeRetirementTask)

            is EncoderFrameworkPreparationTask -> {
                val preparation = (returned.result as? EncoderTaskResult.FrameworkPrepared)?.result
                preparation?.let(::EncoderUninstalledFrameworkRetirementTask)
                    ?: runtime?.let(::EncoderRuntimeRetirementTask)
            }

            is EncoderProductionTask -> runtime?.let(::EncoderRuntimeRetirementTask)
            is EncoderRuntimeRetirementTask -> when (
                (returned.result as? EncoderTaskResult.FrameworkRetired)?.result
            ) {
                EncoderFrameworkRetirement.Closed -> EncoderCarrierRetirementTask(task.runtime)
                is EncoderFrameworkRetirement.Retained,
                null,
                    -> null
            }

            is EncoderCarrierRetirementTask -> null
            is EncoderUninstalledFrameworkRetirementTask -> when (
                (returned.result as? EncoderTaskResult.UninstalledFrameworkRetired)?.result
            ) {
                FrameworkBitmapRetirement.Closed,
                is FrameworkBitmapRetirement.Retained,
                    -> EncoderRuntimeRetirementTask(task.runtime)

                null,
                    -> null
            }
        }

    override fun tryEnter(command: OpenCapture): Boolean = synchronized(frontDoor.sessionGate) {
        frontDoor.ordinaryAdmissionOpenLocked(this) && captureCapsule.markEntered(command)
    }

    override fun tryEnter(command: ApplyPlan): Boolean = synchronized(frontDoor.sessionGate) {
        frontDoor.ordinaryAdmissionOpenLocked(this) && captureCapsule.markEntered(command)
    }

    override fun tryEnter(command: ReadFrame): Boolean = synchronized(frontDoor.sessionGate) {
        if (frontDoor.productionRevisionOpenLocked(this, command.configRevision) &&
            captureCapsule.markEntered(command)
        ) {
            true
        } else {
            if (captureCapsule.entryState != io.screenstream.engine.internal.capture.CaptureEntryState.CutoffInert) {
                check(captureCapsule.markCutoffInert(command))
            } else {
                check(captureCapsule.currentCommand === command)
            }
            false
        }
    }

    override fun tryEnter(command: CloseCapture): Boolean = synchronized(frontDoor.sessionGate) {
        captureCapsule.markEntered(command)
    }

    override fun onOpenCutoff(command: OpenCapture) {
        synchronized(frontDoor.sessionGate) { captureCapsule.settleCutoff(command) }
        ensureTerminalCaptureClose()
    }

    override fun onCloseCutoff(command: CloseCapture) {
        synchronized(frontDoor.sessionGate) { captureCapsule.retainCloseCutoff(command) }
    }

    override fun onCommandException(command: CaptureCommand, failure: Exception) {
        frontDoor.selectCaptureCommandException(this, command, failure)
    }

    override fun onFatal(command: CaptureCommand, fatal: Throwable) {
        frontDoor.fenceCaptureDirectFatal(this, command, fatal)
    }

    override fun onResult(result: OpenCaptureResult) {
        var normal = false
        var terminalFullyClosed = false
        synchronized(frontDoor.sessionGate) {
            lastCaptureReturnedCommand = result.command
            if (result is Opened) captureCapsule.adoptOpened(result) else captureCapsule.adoptOpenFailed(result as OpenFailed)
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) {
                openResult = result
                normal = true
            } else {
                terminalFullyClosed = !captureCapsule.hasUnresolvedOwnership
            }
        }
        if (normal) {
            frontDoor.signalControl()
        } else if (terminalFullyClosed) {
            reportCaptureCleanup(false, (result as? OpenFailed)?.cause)
            captureThread.quitSafely()
        } else {
            ensureTerminalCaptureClose()
        }
    }

    override fun onResult(result: ApplyPlanResult) {
        var normal = false
        synchronized(frontDoor.sessionGate) {
            lastCaptureReturnedCommand = result.command
            captureCapsule.closeOperationAfterRealReturn(result)
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) {
                applyResult = result
                normal = true
            }
        }
        if (normal) frontDoor.signalControl() else ensureTerminalCaptureClose()
    }

    override fun onResult(result: ReadFrameResult) {
        var normal = false
        var terminalRead: FrameworkCaptureRead? = null
        synchronized(frontDoor.sessionGate) {
            lastCaptureReturnedCommand = result.command
            captureCapsule.closeOperationAfterRealReturn(result)
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) {
                readResult = result
                normal = true
            } else {
                if (terminalTransferPrepared) {
                    terminalRead = terminalOutstandingRead
                    terminalOutstandingRead = null
                } else {
                    cutoffReadBeforeTerminalTransfer = result
                }
            }
        }
        if (normal) {
            frontDoor.signalControl()
        } else if (terminalTransferPrepared) {
            val exactRead = terminalRead
            if (exactRead == null) {
                reportEncoderCleanup(true, IllegalStateException("Terminal Capture return had no exact carrier loan"))
            } else {
                when (val settlement = exactRead.runtime.settleTerminalCaptureReturn(exactRead, result)) {
                    is EncoderTerminalCaptureSettlement.Settled -> {
                        var queued = false
                        synchronized(frontDoor.sessionGate) {
                            if (encoderCapsule.entryState == EncoderEntryState.Closed &&
                                encoderCapsule.runtime === settlement.successor.runtime
                            ) {
                                encoderCapsule.queue(settlement.successor)
                                currentRead = null
                                currentProduction = null
                                queued = true
                            } else {
                                Unit
                            }
                        }
                        if (queued) submitTerminalEncoderTask(settlement.successor)
                        else reportEncoderCleanup(
                            true,
                            IllegalStateException("Terminal encoder retirement successor could not be queued"),
                        )
                    }

                    EncoderTerminalCaptureSettlement.IdentityMismatch -> {
                        reportEncoderCleanup(
                            true,
                            IllegalStateException("Terminal Capture return did not match carrier ownership"),
                        )
                    }
                }
            }
            ensureTerminalCaptureClose()
        }
    }

    override fun onResult(result: CloseCaptureResult) {
        val residue: Boolean
        val cause: Throwable?
        val terminal: Boolean
        synchronized(frontDoor.sessionGate) {
            lastCaptureReturnedCommand = result.command
            when (result) {
                is CaptureClosed -> {
                    captureCapsule.closeCaptureAfterRealReturn(result)
                    residue = false
                    cause = result.cleanupFailure
                }

                is CaptureRetainedLocally -> {
                    captureCapsule.retainCaptureAfterRealReturn(result)
                    residue = true
                    cause = result.cause
                }
            }
            terminal = terminalTransferPrepared
        }
        if (terminal) {
            reportCaptureCleanup(residue, cause)
            if (!residue) captureThread.quitSafely()
        }
    }

    private fun reportCaptureCleanup(residueRemains: Boolean, cause: Throwable?) {
        while (true) {
            val previous = lastCaptureCleanup.get()
            val next = CaptureCleanupReturn(captureCapsule, previous, residueRemains, cause)
            if (!lastCaptureCleanup.compareAndSet(previous, next)) continue
            observations.terminalCleanupSink.captureReturned(next)
            return
        }
    }

    override fun onSourceAvailable(fact: SourceAvailable) {
        val accepted = synchronized(frontDoor.sessionGate) {
            frontDoor.ordinaryAdmissionOpenLocked(this) && captureCapsule.retainSource(fact.source)
        }
        if (accepted) frontDoor.signalControl()
    }

    override fun onProjectionStopped(token: ProjectionToken) {
        val accepted = synchronized(frontDoor.sessionGate) { acceptProjectionCallbackLocked(token) }
        if (accepted) frontDoor.selectCaptureEnded()
    }

    override fun onCapturedContentResize(
        token: ProjectionToken,
        widthPx: Int,
        heightPx: Int,
        arrivalElapsedRealtimeNanos: Long,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) || !acceptProjectionCallbackLocked(token)) return
            if (!initialResizeAccepted) {
                val windowToken = initialResizeToken
                if (windowToken == null) initialResizeToken = token else if (windowToken !== token) return
                val deadline = resizeDeadlineNanos
                if (deadline == null) {
                    val first = pendingInitialResize
                    pendingInitialResize = CapturedResizeSample(
                        token,
                        widthPx,
                        heightPx,
                        first?.arrivalElapsedRealtimeNanos ?: arrivalElapsedRealtimeNanos,
                    )
                } else if (arrivalElapsedRealtimeNanos < deadline && !initialResizeExpirySticky) {
                    capturedResizeWidth = widthPx
                    capturedResizeHeight = heightPx
                    initialResizeAccepted = true
                    capturedResizeRevisionPending = true
                } else {
                    initialResizeExpirySticky = true
                }
            } else {
                if (capturedResizeWidth == widthPx && capturedResizeHeight == heightPx) return
                capturedResizeWidth = widthPx
                capturedResizeHeight = heightPx
                capturedResizeRevisionPending = true
            }
        }
        frontDoor.signalControl()
    }

    override fun onCapturedContentVisibilityChanged(token: ProjectionToken, isVisible: Boolean) {
        synchronized(frontDoor.sessionGate) {
            if (!frontDoor.ordinaryAdmissionOpenLocked(this) || !acceptProjectionCallbackLocked(token)) return
            capturedVisibility = isVisible
        }
        frontDoor.signalControl()
    }

    private fun acceptProjectionCallbackLocked(token: ProjectionToken): Boolean {
        check(Thread.holdsLock(frontDoor.sessionGate))
        if (!frontDoor.ordinaryAdmissionOpenLocked(this)) return false
        val current = currentProjectionToken
        if (current == null) currentProjectionToken = token
        return current == null || current === token
    }

    override fun onColorAction(fact: CaptureColorFact) {
        synchronized(frontDoor.sessionGate) {
            if (frontDoor.ordinaryAdmissionOpenLocked(this)) colorAction = fact
        }
        frontDoor.signalControl()
    }

    private fun ensureTerminalCaptureClose() {
        val close = synchronized(frontDoor.sessionGate) {
            if (!terminalTransferPrepared || !captureCapsule.hasUnresolvedOwnership ||
                captureCapsule.entryState != io.screenstream.engine.internal.capture.CaptureEntryState.Closed ||
                captureCapsule.closeRequested != null
            ) return@synchronized null
            CloseCapture().also(captureCapsule::requestClose)
        } ?: return
        postTerminalCaptureClose(close)
    }

    override fun isEntryAdmittedLocked(capsule: DeliveryCapsule, handoff: DeliveryHandoff): Boolean {
        val registration = frontDoor.currentRegistrationLocked(handoff.registrationId)
        return frontDoor.ordinaryAdmissionOpenLocked(this) && capsule.handoff === handoff &&
                registration?.state == RegistrationState.Open && registration.handoffOutstanding
    }

    override fun installClosedResultLocked(
        capsule: DeliveryCapsule,
        handoff: DeliveryHandoff,
        result: DeliveryClosedResult,
    ): DeliveryInstallOutcome {
        if (!frontDoor.ordinaryAdmissionOpenLocked(this)) {
            if (!terminalTransferPrepared) deliveryCleanupBeforeTerminalTransfer = result
            return DeliveryInstallOutcome(DeliveryInstallDisposition.TerminalCleanup, null)
        }
        if (capsule.handoff !== handoff) {
            return DeliveryInstallOutcome(DeliveryInstallDisposition.StaleCleanup, null)
        }
        if (result is DeliveryClosedResult.DirectFatal) {
            deliveryDirectFatalResult = result
            return DeliveryInstallOutcome(DeliveryInstallDisposition.DirectFatalNoContinuation, null)
        }
        deliveryResult = result
        deliveryPermitContinuation = result
        return DeliveryInstallOutcome(
            DeliveryInstallDisposition.ControlAccepted,
            {
                var wakeControl = false
                var cleanupCause: Throwable? = null
                var reportCleanup = false
                var settleDetachedLease = false
                synchronized(frontDoor.sessionGate) {
                    check(deliveryPermitContinuation === result)
                    deliveryPermitContinuation = null
                    if (frontDoor.ordinaryAdmissionOpenLocked(this) && deliveryResult === result) {
                        deliveryPermitReleasedResult = result
                        wakeControl = true
                    } else if (terminalDeliveryAwaitingPermit === result) {
                        terminalDeliveryAwaitingPermit = null
                        cleanupCause = deliveryFailureCause(result)
                        settleDetachedLease = true
                        reportCleanup = true
                    } else {
                        deliveryPermitReleasedResult = result
                    }
                }
                if (wakeControl) frontDoor.signalControl()
                if (settleDetachedLease) {
                    check(result.leaseRelease.settleDetached(result.handoff.lease))
                }
                if (reportCleanup) reportDeliveryCleanup(false, cleanupCause)
            },
        )
    }

    override fun selectDirectFatal(result: DeliveryClosedResult.DirectFatal) {
        frontDoor.selectControlDirectFatal(this, ScreenCaptureProblem.InternalFailure, result.fatal) {
            deliveryDirectFatalResult === result
        }
    }

    private fun reportDeliveryCleanup(residueRemains: Boolean, cause: Throwable?) {
        while (true) {
            val previous = lastDeliveryCleanup.get()
            val next = DeliveryCleanupReturn(deliveryCapsule, previous, residueRemains, cause)
            if (!lastDeliveryCleanup.compareAndSet(previous, next)) continue
            observations.terminalCleanupSink.deliveryReturned(next)
            return
        }
    }

    private fun deliveryFailureCause(result: DeliveryClosedResult): Throwable? = when (result) {
        is DeliveryClosedResult.CallbackException -> result.exception
        is DeliveryClosedResult.InternalFailure -> result.exception
        is DeliveryClosedResult.DirectFatal -> result.fatal
        is DeliveryClosedResult.CallbackReturned,
        is DeliveryClosedResult.CutoffBeforeEntry,
            -> null
    }

    private companion object {
        private const val STATS_CADENCE_NANOS: Long = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }
}

private class ClosedFacts(
    val open: OpenCaptureResult?,
    val apply: ApplyPlanResult?,
    val read: ReadFrameResult?,
    val encoder: EncoderTaskReturn?,
    val delivery: DeliveryClosedResult?,
)

internal class TerminalFold internal constructor(
    internal val deliveryProblem: DiagnosticRequest?,
)

private class CapturedResizeSample(
    val token: ProjectionToken,
    val widthPx: Int,
    val heightPx: Int,
    val arrivalElapsedRealtimeNanos: Long,
) {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(arrivalElapsedRealtimeNanos >= 0L)
    }
}

private enum class InitialResizeReadiness {
    Ready,
    Waiting,
    Expired,
}
