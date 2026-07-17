package io.screenstream.engine.internal.android

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetNoProducerEvidence
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetProducerOperationKind
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal class AndroidCaptureOwner(
    private val projection: MediaProjection,
    private val sessionEpoch: Long,
    private val callbackIdentity: Long,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    private val factSink: AndroidCaptureFactSink,
) {
    internal val startupCell: AndroidLaneStartupCell = AndroidLaneStartupCell()
    internal val quitRequestCell: AndroidLaneQuitRequestCell = AndroidLaneQuitRequestCell()
    internal val terminationCell: AndroidLaneTerminationCell = AndroidLaneTerminationCell()

    private val laneStartRequested = AtomicBoolean(false)
    private val callbackRegistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    private val callbackRegistrationNoPlatformEntryProven = AtomicBoolean(false)
    private val callbackRegistrationReturned = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    private val callbackAuthorityOpen = AtomicBoolean(true)
    private val callbackUnregistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>?>(null)
    private val callbackUnregisterReturned = AtomicBoolean(false)
    private val virtualDisplayCreationOperation =
        AtomicReference<OperationOccurrence<AndroidVirtualDisplayCreationEvidence>?>(null)
    private val virtualDisplayCreationNoPlatformEntryProven = AtomicBoolean(false)
    private val virtualDisplayCreationReturned = AtomicBoolean(false)
    private val virtualDisplayOwner = AtomicReference<AndroidVirtualDisplayOwnership?>(null)
    private val virtualDisplayReleaseOperation =
        AtomicReference<OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>?>(null)
    private val virtualDisplayReleaseReturned = AtomicBoolean(false)
    private val projectionStopOperation = AtomicReference<OperationOccurrence<AndroidProjectionStopEvidence>?>(null)
    private val projectionStopReturned = AtomicBoolean(false)

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !callbackAuthorityOpen.get()) return
            val fact = AndroidCaptureFact.CapturedContentResized(
                sessionEpoch = sessionEpoch,
                callbackIdentity = callbackIdentity,
                sampleNanos = clock.nowNanos(),
                widthPx = width,
                heightPx = height,
            )
            val operation = virtualDisplayCreationOperation.get()
            val recorded = operation?.settlementGate?.withLock {
                virtualDisplayCreationOperation.get() === operation && operation.returnCell.evidence.recordInitialResizeLocked(fact)
            } == true
            factSink.publish(fact)
            if (recorded) settlementSignal.signal()
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !callbackAuthorityOpen.get()) return
            factSink.publish(
                AndroidCaptureFact.CapturedContentVisibilityChanged(
                    sessionEpoch = sessionEpoch,
                    callbackIdentity = callbackIdentity,
                    sampleNanos = clock.nowNanos(),
                    isVisible = isVisible,
                ),
            )
        }

        override fun onStop() {
            if (!callbackAuthorityOpen.get()) return
            factSink.publish(
                AndroidCaptureFact.CaptureEnded(
                    sessionEpoch = sessionEpoch,
                    callbackIdentity = callbackIdentity,
                    sampleNanos = clock.nowNanos(),
                ),
            )
        }
    }

    private val handlerThread: HandlerThread = object : HandlerThread("ScreenCaptureEngine-Android") {
        override fun onLooperPrepared() {
            try {
                val preparedLooper = checkNotNull(Looper.myLooper())
                startupCell.publishReady(Handler(preparedLooper))
            } catch (thrown: Throwable) {
                startupCell.publishLooperFailure(thrown)
                signalBestEffort()
                throw thrown
            }
            settlementSignal.signal()
        }

        override fun run() {
            try {
                super.run()
            } catch (thrown: Throwable) {
                terminationCell.publishThreadReturn(thrown)
                signalBestEffort()
                throw thrown
            }
            terminationCell.publishThreadReturn(null)
            signalBestEffort()
        }
    }

    init {
        require(sessionEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal val isProjectionCallbackRegistered: Boolean
        get() = callbackRegistered.get()

    internal fun startLane(): Boolean {
        if (!laneStartRequested.compareAndSet(false, true)) return false
        try {
            handlerThread.start()
        } catch (thrown: Throwable) {
            startupCell.publishStartFailure(thrown)
            signalBestEffort()
            throw thrown
        }
        return true
    }

    internal fun createProjectionCallbackRegistrationOperation(
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? {
        val operation = finiteOccurrence(
            identity = identity,
            evidence = AndroidProjectionCallbackRegistrationEvidence(),
            ownerBag = AndroidProjectionCallbackRegistrationOwnerBag(projection, projectionCallback),
        )
        return if (callbackRegistrationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitProjectionCallbackRegistration(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean = submitToLane(
        operation = operation,
        postRejectionMessage = "Android projection callback registration rejected",
        onReturnedThrow = { callbackRegistrationReturned.set(true) },
    ) {
        projection.registerCallback(projectionCallback, it)
        callbackRegistered.set(true)
        callbackRegistrationReturned.set(true)
    }

    internal fun createTargetListenerInstallationOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidTargetListenerInstallationEvidence>? {
        val port = target.registerListenerInstallationPort(identity.operationIdentity) ?: return null
        return finiteOccurrence(
            identity = identity,
            evidence = AndroidTargetListenerInstallationEvidence(),
            ownerBag = AndroidTargetListenerInstallationOwnerBag(target, port),
        )
    }

    internal fun submitTargetListenerInstallation(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidTargetListenerInstallationOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android target-listener installation rejected",
        ) { handler ->
            check(
                ownerBag.port.withListener { surfaceTexture, listener ->
                    surfaceTexture.setOnFrameAvailableListener(listener, handler)
                },
            )
        }
    }

    internal fun createVirtualDisplayCreationOperation(
        target: CurrentTarget,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        identity: AndroidFiniteOperationIdentity,
        initialResizeDeadlineIdentity: AndroidInitialResizeDeadlineIdentity?,
    ): OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
        require(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                initialResizeDeadlineIdentity != null
            } else {
                initialResizeDeadlineIdentity == null
            },
        )
        if (!callbackRegistered.get() || !callbackAuthorityOpen.get() || virtualDisplayCreationOperation.get() != null) {
            return null
        }
        val port = target.registerProducerPort(identity.operationIdentity, TargetProducerOperationKind.VirtualDisplayCreation) ?: return null
        val returnedOwnerCell = AndroidVirtualDisplayReturnedOwnerCell()
        val evidence = AndroidVirtualDisplayCreationEvidence(returnedOwnerCell)
        val applicationCandidate = AndroidAttachedVirtualDisplay(returnedOwnerCell, target, port, evidence)
        val operation = finiteOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = AndroidVirtualDisplayCreationOwnerBag(
                projection = projection,
                target = target,
                port = port,
                widthPx = widthPx,
                heightPx = heightPx,
                densityDpi = densityDpi,
                applicationCandidate = applicationCandidate,
            ),
        )
        check(applicationCandidate.bindProducerOperation(operation))
        if (initialResizeDeadlineIdentity != null) {
            val initialResizeDeadlineOccurrence = DeadlineOccurrence(
                identity = initialResizeDeadlineIdentity.deadlineIdentity,
                boundOccurrenceIdentity = operation.identity,
                durationNanos = initialCapturedResizeReadinessNanos,
                initialWakeGeneration = initialResizeDeadlineIdentity.deadlineWakeGeneration,
                timeoutCause = initialResizeDeadlineIdentity.timeoutCause,
                settlementGate = operation.settlementGate,
                clock = clock,
                signal = settlementSignal,
            )
            val deadlineBound = operation.settlementGate.withLock {
                evidence.bindInitialResizeDeadlineLocked(initialResizeDeadlineOccurrence)
            }
            check(deadlineBound)
        }
        check(target.prepareProducerApplicationCandidates(port, operation))
        return if (virtualDisplayCreationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitVirtualDisplayCreation(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayCreationOwnerBag
        val directCreateOutOfMemoryRecordFailure =
            IllegalStateException("Android VirtualDisplay direct OOME evidence was already recorded")
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay creation rejected",
            publishNormalReturn = false,
            onReturnedThrow = { virtualDisplayCreationReturned.set(true) },
            onThrownSettlement = { thrown -> publishVirtualDisplayCreationThrown(operation, thrown) },
        ) {
            var returnedDisplay: VirtualDisplay? = null
            var directCreateOutOfMemoryError: OutOfMemoryError? = null
            val rawCallEntered = ownerBag.port.withSurface { surface ->
                val createdDisplay = try {
                    projection.createVirtualDisplay(
                        "ScreenCaptureEngine",
                        ownerBag.widthPx,
                        ownerBag.heightPx,
                        ownerBag.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null,
                    )
                } catch (error: OutOfMemoryError) {
                    val recorded = operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordDirectCreateOutOfMemoryLocked(error)
                    }
                    if (!recorded) throw directCreateOutOfMemoryRecordFailure
                    directCreateOutOfMemoryError = error
                    return@withSurface
                }
                if (createdDisplay != null) {
                    val returnedOwnerRooted = operation.settlementGate.withLock {
                        operation.returnCell.evidence.rootReturnedVirtualDisplayLocked(createdDisplay)
                    }
                    check(returnedOwnerRooted)
                }
                returnedDisplay = createdDisplay
            }
            check(rawCallEntered)
            val directCreateFailure = directCreateOutOfMemoryError
            if (directCreateFailure != null) {
                publishVirtualDisplayCreationThrown(operation, directCreateFailure)
                virtualDisplayCreationReturned.set(true)
                return@submitToLane
            }
            val returnedDisplaySnapshot = returnedDisplay
            publishVirtualDisplayCreationReturn(operation, returnedDisplaySnapshot)
            virtualDisplayCreationReturned.set(true)
        }
    }

    internal fun applyVirtualDisplayCreationTargetFact(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        fact: TargetProducerApplicationFact,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        val evidence = operation.returnCell.evidence
        val candidate = ownerBag.applicationCandidate
        return operation.settlementGate.withLock {
            if (virtualDisplayCreationOperation.get() !== operation ||
                evidence.appliedTargetFact != null ||
                !matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)
            ) {
                return@withLock false
            }
            when (fact) {
                is TargetNoProducerEvidence -> {
                    if (evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
                        evidence.returnedVirtualDisplay != null
                    ) {
                        return@withLock false
                    }
                    if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
                    val existingOwnership = virtualDisplayOwner.get()
                    if (existingOwnership == null) {
                        true
                    } else {
                        if (!evidence.recordCollisionLocked(existingOwnership)) return@withLock false
                        false
                    }
                }

                is TargetProducerEvidence -> {
                    val returnedDisplay = evidence.returnedVirtualDisplay ?: return@withLock false
                    if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
                        evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Rooted
                    ) {
                        return@withLock false
                    }
                    if (candidate.virtualDisplay !== returnedDisplay || !evidence.recordAppliedTargetFactLocked(fact)) {
                        return@withLock false
                    }
                    if (virtualDisplayOwner.compareAndSet(null, candidate)) {
                        evidence.recordInstalledLocked()
                    } else {
                        if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                        false
                    }
                }
            }
        }
    }

    internal fun createVirtualDisplayResizeOperation(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayResizeEvidence>? {
        val ownership = virtualDisplayOwner.get() as? AndroidAttachedVirtualDisplay ?: return null
        return finiteOccurrence(
            identity = identity,
            evidence = AndroidVirtualDisplayResizeEvidence(),
            ownerBag = AndroidVirtualDisplayResizeOwnerBag(ownership, widthPx, heightPx, densityDpi),
        )
    }

    internal fun submitVirtualDisplayResize(
        operation: OperationOccurrence<AndroidVirtualDisplayResizeEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayResizeOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay resize rejected",
        ) {
            check(virtualDisplayOwner.get() === ownerBag.ownership)
            ownerBag.virtualDisplay.resize(ownerBag.widthPx, ownerBag.heightPx, ownerBag.densityDpi)
        }
    }

    internal fun createVirtualDisplayAttachOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayAttachEvidence>? {
        if (!target.isProducerAttachmentPermitted) return null
        val ownership = virtualDisplayOwner.get() as? AndroidMechanicallyDetachedVirtualDisplay ?: return null
        val port = target.registerProducerPort(identity.operationIdentity, TargetProducerOperationKind.VirtualDisplayAttachment) ?: return null
        val evidence = AndroidVirtualDisplayAttachEvidence()
        val applicationCandidate = AndroidAttachedVirtualDisplay(ownership.virtualDisplay, target, port, evidence)
        val operation = finiteOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = AndroidVirtualDisplayAttachOwnerBag(ownership, target, port, applicationCandidate),
        )
        check(applicationCandidate.bindProducerOperation(operation))
        check(target.prepareProducerApplicationCandidates(port, operation))
        return operation
    }

    internal fun submitVirtualDisplayAttach(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayAttachOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay attach rejected",
        ) {
            check(virtualDisplayOwner.get() === ownerBag.previousOwnership)
            check(ownerBag.port.withSurface { surface -> ownerBag.virtualDisplay.surface = surface })
        }
    }

    internal fun applyVirtualDisplayAttachTargetFact(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        fact: TargetProducerApplicationFact,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return false
        val evidence = operation.returnCell.evidence
        val candidate = ownerBag.applicationCandidate
        return operation.settlementGate.withLock {
            if (evidence.appliedTargetFact != null || !matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)) {
                return@withLock false
            }
            if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
            if (virtualDisplayOwner.get() !== ownerBag.previousOwnership) {
                if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                return@withLock false
            }
            when (fact) {
                is TargetNoProducerEvidence -> true
                is TargetProducerEvidence -> {
                    if (operation.returnCell.disposition != OperationReturnDisposition.Normal) return@withLock false
                    if (virtualDisplayOwner.compareAndSet(ownerBag.previousOwnership, candidate)) {
                        true
                    } else {
                        if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                        false
                    }
                }
            }
        }
    }

    internal fun createVirtualDisplayDetachOperation(
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayDetachEvidence>? {
        val ownership = virtualDisplayOwner.get() as? AndroidAttachedVirtualDisplay ?: return null
        val port = ownership.target.registerSetSurfaceDetachPort(identity.operationIdentity) ?: return null
        val evidence = AndroidVirtualDisplayDetachEvidence()
        val applicationCandidate = AndroidMechanicallyDetachedVirtualDisplay(ownership, port, evidence)
        val operation = finiteOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = AndroidVirtualDisplayDetachOwnerBag(ownership, port, applicationCandidate),
        )
        check(applicationCandidate.bindDetachOperation(operation))
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag
        check(ownerBag.target.prepareProducerDetachApplicationCandidate(ownerBag.port, operation))
        return operation
    }

    internal fun submitVirtualDisplayDetach(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay detach rejected",
        ) {
            check(virtualDisplayOwner.get() === ownerBag.ownership)
            ownerBag.virtualDisplay.surface = null
        }
    }

    internal fun applyVirtualDisplayDetachTargetFact(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
        fact: TargetProducerDetachReceipt,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayDetachOwnerBag ?: return false
        val evidence = operation.returnCell.evidence
        val candidate = ownerBag.applicationCandidate
        return operation.settlementGate.withLock {
            if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
                evidence.appliedTargetFact != null ||
                !matchesDetachFact(fact, operation, ownerBag.target, ownerBag.port)
            ) {
                return@withLock false
            }
            if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
            if (virtualDisplayOwner.get() !== ownerBag.ownership) {
                if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                return@withLock false
            }
            if (virtualDisplayOwner.compareAndSet(ownerBag.ownership, candidate)) {
                true
            } else {
                if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                false
            }
        }
    }

    internal fun createTargetListenerRemovalOperation(
        target: CurrentTarget,
        operationIdentity: Long,
        finiteIdentity: AndroidFiniteOperationIdentity?,
    ): OperationOccurrence<AndroidTargetListenerRemovalEvidence>? {
        require(operationIdentity > 0L)
        require(finiteIdentity == null || finiteIdentity.operationIdentity == operationIdentity)
        val evidence = AndroidTargetListenerRemovalEvidence()
        val port = target.registerListenerRemovalPort(operationIdentity) ?: return null
        val ownerBag = AndroidTargetListenerRemovalOwnerBag(target, port)
        val operation = if (finiteIdentity == null) {
            cleanupOccurrence(operationIdentity, evidence, ownerBag)
        } else {
            finiteOccurrence(finiteIdentity, evidence, ownerBag)
        }
        return operation
    }

    internal fun submitTargetListenerRemoval(operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>): Boolean {
        val ownerBag = operation.ownerBag as AndroidTargetListenerRemovalOwnerBag
        val sentinelArmFailure = IllegalStateException("Android target-listener sentinel could not be armed")
        val sentinelPostRejection = RejectedExecutionException("Android target-listener sentinel rejected")
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android target-listener removal rejected",
            publishNormalReturn = false,
        ) { handler ->
            check(ownerBag.port.withSurfaceTexture { surfaceTexture -> surfaceTexture.setOnFrameAvailableListener(null, handler) })
            val removalReturnRecorded = operation.settlementGate.withLock {
                operation.returnCell.evidence.recordListenerRemovalReturnLocked()
            }
            check(removalReturnRecorded)
            check(ownerBag.target.recordListenerRemovalReturn(ownerBag.port))
            val sentinel = ownerBag.target.armListenerSentinelAfterRemovalReturn(operation.identity)
            if (sentinel == null) {
                val rejectionRecorded = operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordSentinelSubmissionLocked(
                        AndroidListenerSentinelSubmissionDisposition.Rejected,
                        sentinelArmFailure,
                    )
                }
                check(rejectionRecorded)
                operation.publishThrownReturn(sentinelArmFailure)
                return@submitToLane
            }
            val sentinelAccepted = try {
                handler.post(sentinel)
            } catch (error: Error) {
                try {
                    operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordSentinelSubmissionLocked(
                            AndroidListenerSentinelSubmissionDisposition.AmbiguousError,
                            error,
                        )
                    }
                } catch (_: Throwable) {
                }
                throw error
            } catch (thrown: Throwable) {
                val rejectionRecorded = operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordSentinelSubmissionLocked(
                        AndroidListenerSentinelSubmissionDisposition.Rejected,
                        thrown,
                    )
                }
                check(rejectionRecorded)
                operation.publishThrownReturn(thrown)
                return@submitToLane
            }
            if (!sentinelAccepted) {
                val rejectionRecorded = operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordSentinelSubmissionLocked(
                        AndroidListenerSentinelSubmissionDisposition.Rejected,
                        sentinelPostRejection,
                    )
                }
                check(rejectionRecorded)
                operation.publishThrownReturn(sentinelPostRejection)
                return@submitToLane
            }
            val acceptanceRecorded = operation.settlementGate.withLock {
                operation.returnCell.evidence.recordSentinelSubmissionLocked(
                    AndroidListenerSentinelSubmissionDisposition.Accepted,
                    null,
                )
            }
            check(acceptanceRecorded)
            operation.publishNormalReturn()
        }
    }

    internal fun closeProjectionCallbackAuthority(): Boolean = callbackAuthorityOpen.compareAndSet(true, false)

    internal fun createProjectionCallbackUnregistrationOperation(
        operationIdentity: Long,
    ): OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? {
        require(operationIdentity > 0L)
        if (callbackAuthorityOpen.get() || callbackUnregisterReturned.get()) return null
        val registrationOperation = callbackRegistrationOperation.get() ?: return null
        publishNoPlatformEntryIfProven(
            operation = registrationOperation,
            retainedOperation = callbackRegistrationOperation,
            noPlatformEntryFact = callbackRegistrationNoPlatformEntryProven,
        )
        if (callbackRegistrationNoPlatformEntryProven.get() || !callbackRegistrationReturned.get()) return null

        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidProjectionCallbackUnregistrationEvidence(),
            ownerBag = AndroidProjectionCallbackUnregistrationOwnerBag(projection, projectionCallback),
        )
        return if (callbackUnregistrationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitProjectionCallbackUnregistration(
        operation: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>,
    ): Boolean = submitToLane(
        operation = operation,
        postRejectionMessage = "Android projection callback unregistration rejected",
        onReturnedThrow = { callbackUnregisterReturned.set(true) },
    ) {
        projection.unregisterCallback(projectionCallback)
        callbackRegistered.set(false)
        callbackUnregisterReturned.set(true)
    }

    internal fun createVirtualDisplayReleaseOperation(
        operationIdentity: Long,
    ): OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? {
        require(operationIdentity > 0L)
        if (!isProjectionCallbackCleanupComplete() || virtualDisplayReleaseReturned.get()) return null
        val creationOperation = virtualDisplayCreationOperation.get() ?: return null
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryFact = virtualDisplayCreationNoPlatformEntryProven,
        )
        if (virtualDisplayCreationNoPlatformEntryProven.get() || !virtualDisplayCreationReturned.get()) return null
        val ownership = virtualDisplayOwner.get() ?: return null
        val mode = when (ownership) {
            is AndroidAttachedVirtualDisplay -> AndroidVirtualDisplayReleaseMode.Attached(
                ownership,
                ownership.target.registerVirtualDisplayReleasePort(operationIdentity) ?: return null,
            )

            is AndroidMechanicallyDetachedVirtualDisplay ->
                AndroidVirtualDisplayReleaseMode.MechanicallyDetached(ownership)
        }
        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidVirtualDisplayReleaseEvidence(),
            ownerBag = AndroidVirtualDisplayReleaseOwnerBag(mode),
        )
        if (mode is AndroidVirtualDisplayReleaseMode.Attached) {
            check(mode.ownership.target.prepareProducerDetachApplicationCandidate(mode.targetPort, operation))
        }
        return if (virtualDisplayReleaseOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitVirtualDisplayRelease(operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayReleaseOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay release rejected",
            onReturnedThrow = { virtualDisplayReleaseReturned.set(true) },
        ) {
            check(virtualDisplayOwner.get() === ownerBag.mode.ownership)
            ownerBag.virtualDisplay.release()
            virtualDisplayReleaseReturned.set(true)
        }
    }

    internal fun applyAttachedVirtualDisplayReleaseTargetFact(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
        fact: TargetProducerDetachReceipt,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val mode = ownerBag.mode as? AndroidVirtualDisplayReleaseMode.Attached ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (virtualDisplayReleaseOperation.get() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.appliedTargetFact != null ||
            !matchesDetachFact(fact, operation, mode.ownership.target, mode.targetPort)
        ) {
            return@withLock false
        }
        if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
        if (virtualDisplayOwner.get() !== mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(mode.ownership, null)) {
            evidence.recordClearedLocked(mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun completeMechanicallyDetachedVirtualDisplayRelease(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val mode = ownerBag.mode as? AndroidVirtualDisplayReleaseMode.MechanicallyDetached ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (virtualDisplayReleaseOperation.get() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.clearedOwnership != null ||
            evidence.collisionObserved
        ) {
            return@withLock false
        }
        if (virtualDisplayOwner.get() !== mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(mode.ownership, null)) {
            evidence.recordClearedLocked(mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun createProjectionStopOperation(operationIdentity: Long): OperationOccurrence<AndroidProjectionStopEvidence>? {
        require(operationIdentity > 0L)
        if (!isProjectionCallbackCleanupComplete() || !isVirtualDisplayCleanupComplete() || projectionStopReturned.get()) {
            return null
        }
        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidProjectionStopEvidence(),
            ownerBag = AndroidProjectionStopOwnerBag(projection),
        )
        return if (projectionStopOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitProjectionStop(
        operation: OperationOccurrence<AndroidProjectionStopEvidence>,
    ): Boolean = submitToLane(
        operation = operation,
        postRejectionMessage = "Android projection stop rejected",
        onReturnedThrow = { projectionStopReturned.set(true) },
    ) {
        projection.stop()
        projectionStopReturned.set(true)
    }

    internal fun requestLaneQuitSafely(): Boolean {
        if (!projectionStopReturned.get() || !quitRequestCell.request()) return false
        val requested = handlerThread.quitSafely()
        settlementSignal.signal()
        return requested
    }

    private fun isProjectionCallbackCleanupComplete(): Boolean {
        val operation = callbackRegistrationOperation.get() ?: return true
        publishNoPlatformEntryIfProven(
            operation = operation,
            retainedOperation = callbackRegistrationOperation,
            noPlatformEntryFact = callbackRegistrationNoPlatformEntryProven,
        )
        return callbackRegistrationNoPlatformEntryProven.get() || callbackUnregisterReturned.get()
    }

    private fun isVirtualDisplayCleanupComplete(): Boolean {
        val creationOperation = virtualDisplayCreationOperation.get() ?: return true
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryFact = virtualDisplayCreationNoPlatformEntryProven,
        )
        if (!virtualDisplayCreationNoPlatformEntryProven.get() && !virtualDisplayCreationReturned.get()) return false
        if (virtualDisplayCreationNoPlatformEntryProven.get()) return true

        val releaseOperation = virtualDisplayReleaseOperation.get()
            ?: return creationOperation.settlementGate.withLock {
                val evidence = creationOperation.returnCell.evidence
                creationOperation.returnCell.disposition != OperationReturnDisposition.Empty &&
                        evidence.returnedOwnerDisposition == AndroidVirtualDisplayReturnedOwnerDisposition.Empty &&
                        evidence.returnedVirtualDisplay == null
            }

        return releaseOperation.settlementGate.withLock {
            if (virtualDisplayReleaseOperation.get() !== releaseOperation) return@withLock false
            val evidence = releaseOperation.returnCell.evidence
            when (releaseOperation.returnCell.disposition) {
                OperationReturnDisposition.Empty -> false
                OperationReturnDisposition.Thrown -> true
                OperationReturnDisposition.Normal -> {
                    val mode = (releaseOperation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag)?.mode
                        ?: return@withLock false
                    val ownershipSettled = evidence.clearedOwnership === mode.ownership || evidence.collisionObserved
                    when (mode) {
                        is AndroidVirtualDisplayReleaseMode.Attached ->
                            evidence.appliedTargetFact != null && ownershipSettled

                        is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> ownershipSettled
                    }
                }
            }
        }
    }

    private fun <R : OperationEvidence> publishNoPlatformEntryIfProven(
        operation: OperationOccurrence<R>,
        retainedOperation: AtomicReference<OperationOccurrence<R>?>,
        noPlatformEntryFact: AtomicBoolean,
    ): Boolean {
        if (retainedOperation.get() !== operation) return false

        return operation.settlementGate.withLock {
            if (retainedOperation.get() !== operation ||
                operation.returnCell.disposition != OperationReturnDisposition.Empty
            ) {
                return@withLock false
            }

            val noPlatformEntryProven = when (operation.entryDisposition) {
                OperationEntryDisposition.Cancelled -> when (operation.disposition) {
                    OperationDisposition.Cancelled,
                    OperationDisposition.SchedulerRejected,
                    OperationDisposition.DeadlineGuardFailed,
                        -> true

                    else -> false
                }

                OperationEntryDisposition.Unentered -> when (operation.disposition) {
                    OperationDisposition.SchedulerRejected ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Rejected

                    OperationDisposition.DeadlineGuardFailed -> true
                    else -> false
                }

                OperationEntryDisposition.Entered -> false
            }
            if (!noPlatformEntryProven) return@withLock false

            noPlatformEntryFact.compareAndSet(false, true)
        }
    }

    private fun <R : OperationEvidence> finiteOccurrence(
        identity: AndroidFiniteOperationIdentity,
        evidence: R,
        ownerBag: OperationOwnerBag
    ): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity.operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
            deadlineIdentity = identity.deadlineIdentity,
            deadlineDurationNanos = androidEnteredOperationSafetyNanos,
            initialWakeGeneration = identity.deadlineWakeGeneration,
            timeoutCause = identity.timeoutCause,
            wakeSignal = settlementSignal,
        )

    private fun <R : OperationEvidence> cleanupOccurrence(identity: Long, evidence: R, ownerBag: OperationOwnerBag): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
        ).also { occurrence ->
            check(occurrence.arbitrateTerminal(mandatoryCleanup = true) == OperationTerminalArbitration.Transferred)
        }

    private fun publishVirtualDisplayCreationReturn(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        returnedDisplay: VirtualDisplay?,
    ): Boolean = operation.settlementGate.withLock {
        if (operation.entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        val sampleNanos = clock.nowNanos()
        operation.returnCell.evidence.armOrRetireInitialResizeLocked(
            sampleNanos = sampleNanos,
            returnedDisplayPresent = returnedDisplay != null,
        )
        operation.returnCell.publishNormalLocked(sampleNanos)
    }

    private fun publishVirtualDisplayCreationThrown(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        thrown: Throwable,
    ): Boolean = operation.settlementGate.withLock {
        operation.returnCell.evidence.retireInitialResizeLocked()
        operation.publishThrownReturn(thrown)
    }

    private fun matchesProducerFact(
        fact: TargetProducerApplicationFact,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidSurfacePort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.operationKind == port.operationKind &&
            fact.provenance === port.provenance

    private fun matchesDetachFact(
        fact: TargetProducerDetachReceipt,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidDetachPort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.detachKind == port.detachKind &&
            fact.provenance === port.provenance

    private fun <R : OperationEvidence> submitToLane(
        operation: OperationOccurrence<R>,
        postRejectionMessage: String,
        publishNormalReturn: Boolean = true,
        onReturnedThrow: (Throwable) -> Unit = {},
        onThrownSettlement: (Throwable) -> Boolean = operation::publishThrownReturn,
        call: (Handler) -> Unit,
    ): Boolean {
        val ready = startupCell.current
        val handler = (ready as? AndroidLaneStartupResult.Ready)?.handler
        val laneNotReadyRejection = RejectedExecutionException("Android capture lane is not ready")
        val postRejection = RejectedExecutionException(postRejectionMessage)
        val runnable = handler?.let { readyHandler ->
            Runnable {
                try {
                    val entryResult = operation.tryEnter()
                    settlementSignal.signal()
                    if (entryResult != OperationEntryResult.Entered) return@Runnable

                    try {
                        call(readyHandler)
                        if (publishNormalReturn) operation.publishNormalReturn()
                    } catch (error: Error) {
                        throw error
                    } catch (thrown: Throwable) {
                        onReturnedThrow(thrown)
                        onThrownSettlement(thrown)
                    }
                    settlementSignal.signal()
                } catch (error: Error) {
                    try {
                        onReturnedThrow(error)
                    } catch (_: Throwable) {
                    }
                    try {
                        onThrownSettlement(error)
                    } catch (_: Throwable) {
                    }
                    signalBestEffort()
                    throw error
                }
            }
        }

        if (!operation.beginSubmission()) return false
        if (handler == null || runnable == null) {
            val rejection = when (ready) {
                is AndroidLaneStartupResult.Failed -> ready.cause
                else -> laneNotReadyRejection
            }
            operation.publishSubmissionRejected(rejection)
            settlementSignal.signal()
            return false
        }

        val accepted = try {
            handler.post(runnable)
        } catch (error: Error) {
            try {
                operation.publishSubmissionAmbiguousError(error)
            } catch (_: Throwable) {
            }
            signalBestEffort()
            throw error
        } catch (thrown: Throwable) {
            operation.publishSubmissionRejected(thrown)
            settlementSignal.signal()
            return false
        }
        if (accepted) {
            operation.publishSubmissionAccepted()
        } else {
            operation.publishSubmissionRejected(postRejection)
        }
        settlementSignal.signal()
        return accepted
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
        }
    }
}
