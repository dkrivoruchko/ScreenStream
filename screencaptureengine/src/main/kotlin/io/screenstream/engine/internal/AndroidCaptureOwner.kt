package io.screenstream.engine.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.androidEnteredOperationSafetyNanos
import io.screenstream.engine.internal.settlement.initialCapturedResizeReadinessNanos
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetNoProducerEvidence
import io.screenstream.engine.internal.target.TargetNoProducerReason
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetProducerOperationKind
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal sealed class AndroidCaptureFact(
    internal val sessionEpoch: Long,
    internal val callbackIdentity: Long,
    internal val sampleNanos: Long,
) {
    init {
        require(sessionEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal class CapturedContentResized(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
        internal val widthPx: Int,
        internal val heightPx: Int,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)

    internal class CapturedContentVisibilityChanged(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
        internal val isVisible: Boolean,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)

    internal class CaptureEnded(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)
}

internal fun interface AndroidCaptureFactSink {
    fun publish(fact: AndroidCaptureFact)
}

internal sealed class AndroidLaneStartupResult {
    internal class Ready(internal val handler: Handler) : AndroidLaneStartupResult()

    internal class Failed(internal val cause: Throwable) : AndroidLaneStartupResult()
}

internal class AndroidLaneStartupCell {
    private val result = AtomicReference<AndroidLaneStartupResult?>(null)

    internal val current: AndroidLaneStartupResult?
        get() = result.get()

    internal fun publishReady(handler: Handler): Boolean =
        result.compareAndSet(null, AndroidLaneStartupResult.Ready(handler))

    internal fun publishFailure(cause: Throwable): Boolean =
        result.compareAndSet(null, AndroidLaneStartupResult.Failed(cause))
}

internal class AndroidLaneQuitRequestCell {
    private val requested = AtomicBoolean(false)

    internal val isRequested: Boolean
        get() = requested.get()

    internal fun request(): Boolean = requested.compareAndSet(false, true)
}

internal class AndroidLaneTerminationCell {
    private val returned = AtomicBoolean(false)

    internal val hasReturned: Boolean
        get() = returned.get()

    internal fun publishThreadReturn(): Boolean = returned.compareAndSet(false, true)
}

internal class AndroidFiniteOperationIdentity(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val deadlineWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(operationIdentity > 0L)
        require(deadlineIdentity > 0L)
        require(deadlineWakeGeneration > 0L)
    }
}

internal object AndroidProjectionCallbackRegistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackRegistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackRegistrationReceipt =
        AndroidProjectionCallbackRegistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackRegistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag

internal object AndroidTargetListenerInstallationReceipt : OperationReceipt

internal class AndroidTargetListenerInstallationEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerInstallationReceipt = AndroidTargetListenerInstallationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidTargetListenerInstallationOwnerBag(
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidListenerInstallationPort,
) : OperationOwnerBag

internal object AndroidVirtualDisplayCreationReceipt : OperationReceipt

internal class AndroidVirtualDisplayCreationEvidence : OperationEvidence, OperationReturnedOwner {
    internal var virtualDisplay: VirtualDisplay? = null
        private set

    override val receipt: AndroidVirtualDisplayCreationReceipt = AndroidVirtualDisplayCreationReceipt
    override val returnedOwner: OperationReturnedOwner?
        get() = if (virtualDisplay == null) null else this

    internal var initialResizeStartNanos: Long? = null
        private set

    internal var initialResizeDeadlineNanos: Long? = null
        private set

    internal var initialResizeDeadlineGuardFailed: Boolean = false
        private set

    private val producerEvidence = AtomicReference<TargetProducerEvidence?>(null)
    private val noProducerEvidence = AtomicReference<TargetNoProducerEvidence?>(null)

    internal val publishedProducerEvidence: TargetProducerEvidence?
        get() = producerEvidence.get()

    internal val publishedNoProducerEvidence: TargetNoProducerEvidence?
        get() = noProducerEvidence.get()

    internal fun publishProducerEvidence(evidence: TargetProducerEvidence): Boolean =
        noProducerEvidence.get() == null && producerEvidence.compareAndSet(null, evidence)

    internal fun publishNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
        producerEvidence.get() == null && noProducerEvidence.compareAndSet(null, evidence)

    internal fun recordReturnLocked(virtualDisplay: VirtualDisplay?, sdkInt: Int, sampleNanos: Long) {
        this.virtualDisplay = virtualDisplay
        if (virtualDisplay == null || sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        initialResizeStartNanos = sampleNanos
        if (sampleNanos < 0L || sampleNanos > Long.MAX_VALUE - initialCapturedResizeReadinessNanos) {
            initialResizeDeadlineGuardFailed = true
            return
        }
        initialResizeDeadlineNanos = Math.addExact(sampleNanos, initialCapturedResizeReadinessNanos)
    }

    internal fun isTimelyInitialResize(fact: AndroidCaptureFact.CapturedContentResized): Boolean {
        val deadlineNanos = initialResizeDeadlineNanos ?: return false
        return fact.widthPx > 0 && fact.heightPx > 0 && fact.sampleNanos < deadlineNanos
    }
}

internal class AndroidVirtualDisplayCreationOwnerBag(
    internal val projection: MediaProjection,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidSurfacePort,
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val densityDpi: Int,
) : OperationOwnerBag {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
    }
}

internal object AndroidVirtualDisplayResizeReceipt : OperationReceipt

internal class AndroidVirtualDisplayResizeEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayResizeReceipt = AndroidVirtualDisplayResizeReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidVirtualDisplayResizeOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val densityDpi: Int,
) : OperationOwnerBag {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
    }
}

internal object AndroidVirtualDisplayAttachReceipt : OperationReceipt

internal class AndroidVirtualDisplayAttachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayAttachReceipt = AndroidVirtualDisplayAttachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val producerEvidence = AtomicReference<TargetProducerEvidence?>(null)
    private val noProducerEvidence = AtomicReference<TargetNoProducerEvidence?>(null)

    internal val publishedProducerEvidence: TargetProducerEvidence?
        get() = producerEvidence.get()

    internal val publishedNoProducerEvidence: TargetNoProducerEvidence?
        get() = noProducerEvidence.get()

    internal fun publishProducerEvidence(evidence: TargetProducerEvidence): Boolean =
        noProducerEvidence.get() == null && producerEvidence.compareAndSet(null, evidence)

    internal fun publishNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
        producerEvidence.get() == null && noProducerEvidence.compareAndSet(null, evidence)
}

internal class AndroidVirtualDisplayAttachOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidSurfacePort,
) : OperationOwnerBag

internal object AndroidVirtualDisplayDetachReceipt : OperationReceipt

internal class AndroidVirtualDisplayDetachEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayDetachReceipt = AndroidVirtualDisplayDetachReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val targetReceipt = AtomicReference<TargetProducerDetachReceipt?>(null)

    internal val publishedTargetReceipt: TargetProducerDetachReceipt?
        get() = targetReceipt.get()

    internal fun publishTargetReceipt(receipt: TargetProducerDetachReceipt): Boolean =
        targetReceipt.compareAndSet(null, receipt)
}

internal class AndroidVirtualDisplayDetachOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidDetachPort,
) : OperationOwnerBag

internal object AndroidTargetListenerRemovalReceipt : OperationReceipt

internal class AndroidTargetListenerRemovalEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerRemovalReceipt = AndroidTargetListenerRemovalReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var listenerRemovalReturned: Boolean = false
        private set

    internal var sentinelSubmissionAccepted: Boolean = false
        private set

    internal var sentinelSubmissionRejection: Throwable? = null
        private set

    internal fun recordListenerRemovalReturn() {
        listenerRemovalReturned = true
    }

    internal fun recordSentinelSubmissionAccepted() {
        sentinelSubmissionAccepted = true
    }

    internal fun recordSentinelSubmissionRejected(cause: Throwable) {
        sentinelSubmissionRejection = cause
    }
}

internal class AndroidTargetListenerRemovalOwnerBag(
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidListenerRemovalPort,
) : OperationOwnerBag

internal object AndroidProjectionCallbackUnregistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackUnregistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackUnregistrationReceipt =
        AndroidProjectionCallbackUnregistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackUnregistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag

internal object AndroidVirtualDisplayReleaseReceipt : OperationReceipt

internal class AndroidVirtualDisplayReleaseEvidence : OperationEvidence {
    override val receipt: AndroidVirtualDisplayReleaseReceipt = AndroidVirtualDisplayReleaseReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    private val targetReceipt = AtomicReference<TargetProducerDetachReceipt?>(null)

    internal val publishedTargetReceipt: TargetProducerDetachReceipt?
        get() = targetReceipt.get()

    internal fun publishTargetReceipt(receipt: TargetProducerDetachReceipt): Boolean =
        targetReceipt.compareAndSet(null, receipt)
}

internal class AndroidVirtualDisplayReleaseOwnerBag(
    internal val virtualDisplay: VirtualDisplay,
    internal val target: CurrentTarget?,
    internal val targetPort: TargetPorts.AndroidDetachPort?,
) : OperationOwnerBag

internal object AndroidProjectionStopReceipt : OperationReceipt

internal class AndroidProjectionStopEvidence : OperationEvidence {
    override val receipt: AndroidProjectionStopReceipt = AndroidProjectionStopReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionStopOwnerBag(
    internal val projection: MediaProjection,
) : OperationOwnerBag

internal class AndroidCaptureOwner(
    private val projection: MediaProjection,
    private val sessionEpoch: Long,
    private val callbackIdentity: Long,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    private val factSink: AndroidCaptureFactSink,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
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
    private val virtualDisplayOwner = AtomicReference<VirtualDisplay?>(null)
    private val virtualDisplayReleaseOperation =
        AtomicReference<OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>?>(null)
    private val virtualDisplayReleaseReturned = AtomicBoolean(false)
    private val projectionStopOperation = AtomicReference<OperationOccurrence<AndroidProjectionStopEvidence>?>(null)
    private val projectionStopReturned = AtomicBoolean(false)

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !callbackAuthorityOpen.get()) return
            factSink.publish(
                AndroidCaptureFact.CapturedContentResized(
                    sessionEpoch = sessionEpoch,
                    callbackIdentity = callbackIdentity,
                    sampleNanos = clock.nowNanos(),
                    widthPx = width,
                    heightPx = height,
                ),
            )
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !callbackAuthorityOpen.get()) return
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
                startupCell.publishFailure(thrown)
                throw thrown
            } finally {
                settlementSignal.signal()
            }
        }

        override fun run() {
            try {
                super.run()
            } finally {
                terminationCell.publishThreadReturn()
                settlementSignal.signal()
            }
        }
    }

    init {
        require(sessionEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal val currentVirtualDisplay: VirtualDisplay?
        get() = virtualDisplayOwner.get()

    internal val isProjectionCallbackRegistered: Boolean
        get() = callbackRegistered.get()

    internal fun startLane(): Boolean {
        if (!laneStartRequested.compareAndSet(false, true)) return false
        try {
            handlerThread.start()
        } catch (thrown: Throwable) {
            startupCell.publishFailure(thrown)
            settlementSignal.signal()
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
    ): OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
        if (!callbackRegistered.get() || !callbackAuthorityOpen.get() || virtualDisplayCreationOperation.get() != null) {
            return null
        }
        val port = target.registerProducerPort(identity.operationIdentity, TargetProducerOperationKind.VirtualDisplayCreation) ?: return null
        val operation = finiteOccurrence(
            identity = identity,
            evidence = AndroidVirtualDisplayCreationEvidence(),
            ownerBag = AndroidVirtualDisplayCreationOwnerBag(
                projection = projection,
                target = target,
                port = port,
                widthPx = widthPx,
                heightPx = heightPx,
                densityDpi = densityDpi,
            ),
        )
        return if (virtualDisplayCreationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitVirtualDisplayCreation(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayCreationOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay creation rejected",
            publishNormalReturn = false,
            onReturnedThrow = { virtualDisplayCreationReturned.set(true) },
        ) {
            var returnedDisplay: VirtualDisplay? = null
            val rawCallEntered = ownerBag.port.withSurface { surface ->
                returnedDisplay = projection.createVirtualDisplay(
                    "ScreenCaptureEngine",
                    ownerBag.widthPx,
                    ownerBag.heightPx,
                    ownerBag.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null,
                )
            }
            check(rawCallEntered)
            val returnedDisplaySnapshot = returnedDisplay
            if (returnedDisplaySnapshot != null && !virtualDisplayOwner.compareAndSet(null, returnedDisplaySnapshot)) {
                throw IllegalStateException("VirtualDisplay owner already present")
            }
            val settled = publishVirtualDisplayCreationReturn(operation, returnedDisplaySnapshot)
            if (settled) {
                val evidencePublished = if (returnedDisplaySnapshot == null) {
                    ownerBag.target.noProducerEvidenceAfterSettlement(
                        ownerBag.port,
                        operation,
                        TargetNoProducerReason.ReturnedWithoutProducer,
                    )
                        ?.let(operation.returnCell.evidence::publishNoProducerEvidence)
                } else {
                    ownerBag.target.producerEvidenceAfterSettlement(ownerBag.port, operation)
                        ?.let(operation.returnCell.evidence::publishProducerEvidence)
                }
                check(evidencePublished == true)
            }
            virtualDisplayCreationReturned.set(true)
        }
    }

    internal fun publishVirtualDisplayCreationNoProducerEvidence(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        reason: TargetNoProducerReason,
    ): Boolean {
        if (reason == TargetNoProducerReason.ReturnedWithoutProducer) return false
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        val evidence = ownerBag.target.noProducerEvidenceAfterSettlement(ownerBag.port, operation, reason) ?: return false
        return operation.returnCell.evidence.publishNoProducerEvidence(evidence).also { published ->
            if (published) settlementSignal.signal()
        }
    }

    internal fun createVirtualDisplayResizeOperation(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayResizeEvidence>? {
        val display = virtualDisplayOwner.get() ?: return null
        return finiteOccurrence(
            identity = identity,
            evidence = AndroidVirtualDisplayResizeEvidence(),
            ownerBag = AndroidVirtualDisplayResizeOwnerBag(display, widthPx, heightPx, densityDpi),
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
            ownerBag.virtualDisplay.resize(ownerBag.widthPx, ownerBag.heightPx, ownerBag.densityDpi)
        }
    }

    internal fun createVirtualDisplayAttachOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayAttachEvidence>? {
        if (!target.isProducerAttachmentPermitted) return null
        val display = virtualDisplayOwner.get() ?: return null
        val port = target.registerProducerPort(identity.operationIdentity, TargetProducerOperationKind.VirtualDisplayAttachment) ?: return null
        return finiteOccurrence(
            identity = identity,
            evidence = AndroidVirtualDisplayAttachEvidence(),
            ownerBag = AndroidVirtualDisplayAttachOwnerBag(display, target, port),
        )
    }

    internal fun submitVirtualDisplayAttach(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayAttachOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay attach rejected",
            afterNormalSettlement = {
                checkNotNull(
                    ownerBag.target.producerEvidenceAfterSettlement(ownerBag.port, operation),
                ).also { evidence ->
                    check(operation.returnCell.evidence.publishProducerEvidence(evidence))
                }
            },
        ) {
            check(
                ownerBag.port.withSurface { surface ->
                    ownerBag.virtualDisplay.surface = surface
                },
            )
        }
    }

    internal fun publishVirtualDisplayAttachNoProducerEvidence(
        operation: OperationOccurrence<AndroidVirtualDisplayAttachEvidence>,
        reason: TargetNoProducerReason,
    ): Boolean {
        if (reason == TargetNoProducerReason.ReturnedWithoutProducer) return false
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayAttachOwnerBag ?: return false
        val evidence = ownerBag.target.noProducerEvidenceAfterSettlement(ownerBag.port, operation, reason) ?: return false
        return operation.returnCell.evidence.publishNoProducerEvidence(evidence).also { published ->
            if (published) settlementSignal.signal()
        }
    }

    internal fun createVirtualDisplayDetachOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidVirtualDisplayDetachEvidence>? {
        val display = virtualDisplayOwner.get() ?: return null
        val operation = finiteOccurrence(
            identity = identity,
            evidence = AndroidVirtualDisplayDetachEvidence(),
            ownerBag = AndroidVirtualDisplayDetachOwnerBag(display, target, target.registerSetSurfaceDetachPort(identity.operationIdentity) ?: return null),
        )
        return operation
    }

    internal fun submitVirtualDisplayDetach(
        operation: OperationOccurrence<AndroidVirtualDisplayDetachEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayDetachOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay detach rejected",
            afterNormalSettlement = {
                checkNotNull(
                    ownerBag.target.producerDetachReceiptAfterSettlement(ownerBag.port, operation),
                ).also { receipt ->
                    check(operation.returnCell.evidence.publishTargetReceipt(receipt))
                }
            },
        ) {
            ownerBag.virtualDisplay.surface = null
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

    internal fun submitTargetListenerRemoval(
        operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidTargetListenerRemovalOwnerBag
        val sentinelArmFailure = IllegalStateException("Android target-listener sentinel could not be armed")
        val sentinelPostRejection = RejectedExecutionException("Android target-listener sentinel rejected")
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android target-listener removal rejected",
            publishNormalReturn = false,
        ) { handler ->
            check(
                ownerBag.port.withSurfaceTexture { surfaceTexture ->
                    surfaceTexture.setOnFrameAvailableListener(null, handler)
                },
            )
            operation.returnCell.evidence.recordListenerRemovalReturn()
            check(ownerBag.target.recordListenerRemovalReturn(ownerBag.port))
            val sentinel = ownerBag.target.armListenerSentinelAfterRemovalReturn(operation.identity)
            if (sentinel == null) {
                operation.returnCell.evidence.recordSentinelSubmissionRejected(sentinelArmFailure)
                operation.publishThrownReturn(sentinelArmFailure)
                return@submitToLane
            }
            val sentinelAccepted = try {
                handler.post(sentinel)
            } catch (thrown: Throwable) {
                operation.returnCell.evidence.recordSentinelSubmissionRejected(thrown)
                operation.publishThrownReturn(thrown)
                return@submitToLane
            }
            if (!sentinelAccepted) {
                operation.returnCell.evidence.recordSentinelSubmissionRejected(sentinelPostRejection)
                operation.publishThrownReturn(sentinelPostRejection)
                return@submitToLane
            }
            operation.returnCell.evidence.recordSentinelSubmissionAccepted()
            operation.publishNormalReturn()
        }
    }

    internal fun closeProjectionCallbackAuthority(): Boolean =
        callbackAuthorityOpen.compareAndSet(true, false)

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
        target: CurrentTarget?,
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
        val display = virtualDisplayOwner.get() ?: return null
        val targetPort = target?.registerVirtualDisplayReleasePort(operationIdentity) ?: if (target == null) null else {
            return null
        }
        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidVirtualDisplayReleaseEvidence(),
            ownerBag = AndroidVirtualDisplayReleaseOwnerBag(display, target, targetPort),
        )
        return if (virtualDisplayReleaseOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitVirtualDisplayRelease(operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayReleaseOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay release rejected",
            onReturnedThrow = { virtualDisplayReleaseReturned.set(true) },
            afterNormalSettlement = {
                val target = ownerBag.target
                val targetPort = ownerBag.targetPort
                if (target != null && targetPort != null) {
                    target.producerDetachReceiptAfterSettlement(targetPort, operation)?.also { receipt ->
                        check(operation.returnCell.evidence.publishTargetReceipt(receipt))
                    }
                }
            },
        ) {
            ownerBag.virtualDisplay.release()
            virtualDisplayOwner.compareAndSet(ownerBag.virtualDisplay, null)
            virtualDisplayReleaseReturned.set(true)
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
        val operation = virtualDisplayCreationOperation.get() ?: return true
        publishNoPlatformEntryIfProven(
            operation = operation,
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryFact = virtualDisplayCreationNoPlatformEntryProven,
        )
        if (!virtualDisplayCreationNoPlatformEntryProven.get() && !virtualDisplayCreationReturned.get()) return false
        return virtualDisplayOwner.get() == null || virtualDisplayReleaseReturned.get()
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
        ownerBag: OperationOwnerBag,
    ): OperationOccurrence<R> = OperationOccurrence(
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

    private fun <R : OperationEvidence> cleanupOccurrence(
        identity: Long,
        evidence: R,
        ownerBag: OperationOwnerBag,
    ): OperationOccurrence<R> =
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
        operation.returnCell.evidence.recordReturnLocked(virtualDisplay = returnedDisplay, sdkInt = sdkInt, sampleNanos = sampleNanos)
        operation.returnCell.publishNormalLocked(sampleNanos)
    }

    private fun <R : OperationEvidence> submitToLane(
        operation: OperationOccurrence<R>,
        postRejectionMessage: String,
        publishNormalReturn: Boolean = true,
        onReturnedThrow: (Throwable) -> Unit = {},
        afterNormalSettlement: () -> Unit = {},
        call: (Handler) -> Unit,
    ): Boolean {
        val ready = startupCell.current
        val handler = (ready as? AndroidLaneStartupResult.Ready)?.handler
        val laneNotReadyRejection = RejectedExecutionException("Android capture lane is not ready")
        val postRejection = RejectedExecutionException(postRejectionMessage)
        val runnable = handler?.let { readyHandler ->
            Runnable {
                val entryResult = operation.tryEnter()
                settlementSignal.signal()
                if (entryResult != OperationEntryResult.Entered) return@Runnable

                var normalSettlementPublished = false
                try {
                    call(readyHandler)
                    normalSettlementPublished = publishNormalReturn && operation.publishNormalReturn()
                } catch (thrown: Throwable) {
                    onReturnedThrow(thrown)
                    operation.publishThrownReturn(thrown)
                }
                if (normalSettlementPublished) afterNormalSettlement()
                settlementSignal.signal()
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
}
