package io.screenstream.engine.internal.android

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun interface BuiltInCaptureMetricsSink {
    fun publish(metrics: CaptureMetrics?, displayAssociation: CaptureMetricsDisplayAssociation?)
}

/** Built-in listener, display epoch, read, refresh, and physical unregister mechanics. */
internal class BuiltInCaptureMetricsAttachment(
    private val definition: BuiltInCaptureMetricsDefinition,
    private val observer: CaptureMetricsObserver,
    private val sink: BuiltInCaptureMetricsSink,
    private val endpoint: PrivateExecutorRuntime,
    private val subscriptionOwner: CaptureMetricsSubscriptionOwner,
) {
    private sealed interface ListenerProgress {
        object Prepared : ListenerProgress
        object RegistrationAttempted : ListenerProgress
        object Registered : ListenerProgress
        object ClosedAfterRegistrationAttempt : ListenerProgress
    }

    private sealed interface PhysicalCloseProgress {
        object Open : PhysicalCloseProgress
        object Closed : PhysicalCloseProgress
    }

    private val attachmentSignals = AtomicInteger(ATTACHMENT_OPEN or ATTACHMENT_DIRTY)
    private val physicalClose = AtomicReference<PhysicalCloseProgress>(PhysicalCloseProgress.Open)
    private val reusableRealSizePoint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epochExhaustionCause = IllegalStateException("Capture metrics display epoch exhausted")
    private val associationExhaustionCause =
        IllegalStateException("Capture metrics display association identity exhausted")

    /** Metrics-lane confined. */
    private var listenerProgress: ListenerProgress = ListenerProgress.Prepared
    private var currentEpochDisplay: Display? = null
    private var currentEpochAssociation: CaptureMetricsDisplayAssociation? = null
    private var lastEpochIdentity = 0L
    private var lastAssociatedDisplay: Display? = null
    private var lastAssociationIdentity = 0L
    private var currentEpochWindowContext: Context? = null
    private var currentEpochWindowManager: WindowManager? = null

    private val listener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = signalBoundary(displayId)

        override fun onDisplayRemoved(displayId: Int) = signalBoundary(displayId)

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == definition.selectedDisplayId) requestRefresh(0)
        }
    }
    private val subscription = CaptureMetricsSubscription { closeOnMetricsLane() }

    init {
        check(subscriptionOwner.bind(subscription))
    }

    internal val hasPendingRefresh: Boolean
        get() = attachmentSignals.get() and ATTACHMENT_DIRTY != 0

    internal val hasCloseObligation: Boolean
        get() = listenerProgress !== ListenerProgress.Prepared

    /** Registration and the initial complete read are part of the attachment operation itself. */
    internal fun attachOnMetricsLane(): CaptureMetricsSubscription {
        check(listenerProgress === ListenerProgress.Prepared)
        listenerProgress = ListenerProgress.RegistrationAttempted
        definition.displayManager.registerDisplayListener(listener, mainHandler)
        listenerProgress = ListenerProgress.Registered
        performPendingRefresh()
        return subscription
    }

    internal fun performPendingRefresh() {
        val claimedSignals = claimPendingRefresh()
        if (claimedSignals == 0) return
        if (claimedSignals and ATTACHMENT_EPOCH_INVALIDATED != 0) {
            val retiredAssociation = currentEpochAssociation
            retireCurrentEpoch()
            sink.publish(null, retiredAssociation)
            requestRefresh()
            return
        }

        val selectedDisplay = resolveSelectedDisplay()
        if (selectedDisplay == null || !selectionAvailable(selectedDisplay)) {
            val retiredAssociation = currentEpochAssociation
            retireCurrentEpoch()
            val unavailableAssociation = retiredAssociation ?: selectedDisplay?.let(::unavailableAssociationFor)
            sink.publish(null, unavailableAssociation)
            return
        }
        if (currentEpochDisplay !== selectedDisplay) {
            retireCurrentEpoch()
            if (!installDisplayEpoch(selectedDisplay)) return
        }

        val epochDisplay = currentEpochDisplay ?: return
        val epochAssociation = checkNotNull(currentEpochAssociation)
        if (!selectionValidForRead(epochDisplay)) {
            retireCurrentEpoch()
            sink.publish(null, epochAssociation)
            return
        }
        val candidate = readCompleteMetrics(epochDisplay)
        if (!selectionValidForRead(epochDisplay)) {
            retireCurrentEpoch()
            sink.publish(null, epochAssociation)
            requestRefresh()
            return
        }
        sink.publish(candidate, epochAssociation)
    }

    /** Main-to-Metrics fence. Once this clears OPEN, no Main callback can publish new work. */
    internal fun fenceCallbacks(): Boolean {
        endpoint.sealCoalescedSignalCarrier()
        while (true) {
            val current = attachmentSignals.get()
            if (current and ATTACHMENT_OPEN == 0) return false
            if (attachmentSignals.compareAndSet(current, 0)) return true
        }
    }

    private fun closeOnMetricsLane() {
        if (!physicalClose.compareAndSet(PhysicalCloseProgress.Open, PhysicalCloseProgress.Closed)) return
        fenceCallbacks()
        retireCurrentEpoch()
        if (listenerProgress === ListenerProgress.RegistrationAttempted ||
            listenerProgress === ListenerProgress.Registered
        ) {
            definition.displayManager.unregisterDisplayListener(listener)
        }
        listenerProgress = ListenerProgress.ClosedAfterRegistrationAttempt
    }

    /** Main callback path: selected ID, atomic bits, and the existing reusable carrier only. */
    private fun signalBoundary(displayId: Int) {
        if (displayId == definition.selectedDisplayId) requestRefresh(ATTACHMENT_EPOCH_INVALIDATED)
    }

    private fun requestRefresh(additionalSignals: Int = 0) {
        while (true) {
            val current = attachmentSignals.get()
            if (current and ATTACHMENT_OPEN == 0) return
            val next = current or ATTACHMENT_DIRTY or additionalSignals
            if (next == current) {
                endpoint.requestCoalescedSignal()
                return
            }
            if (!attachmentSignals.compareAndSet(current, next)) continue
            endpoint.requestCoalescedSignal()
            return
        }
    }

    private fun claimPendingRefresh(): Int {
        while (true) {
            val current = attachmentSignals.get()
            if (current and ATTACHMENT_OPEN == 0 || current and ATTACHMENT_DIRTY == 0) return 0
            val next = current and (ATTACHMENT_DIRTY or ATTACHMENT_EPOCH_INVALIDATED).inv()
            if (attachmentSignals.compareAndSet(current, next)) return current
        }
    }

    private fun resolveSelectedDisplay(): Display? =
        definition.fixedDisplay ?: definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionAvailable(display: Display): Boolean =
        definition.fixedDisplay?.let { display === it && fixedSelectionValid(it) } ?: display.isValid

    private fun selectionValidForRead(epochDisplay: Display): Boolean {
        if (attachmentSignals.get() and ATTACHMENT_EPOCH_INVALIDATED != 0) return false
        val fixed = definition.fixedDisplay
        if (fixed != null) return epochDisplay === fixed && fixedSelectionValid(fixed)
        return epochDisplay.isValid && definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY) === epochDisplay
    }

    /** The manager wrapper is association evidence only; reads always use the exact caller-supplied Display. */
    private fun fixedSelectionValid(fixed: Display): Boolean {
        if (fixed.displayId != definition.selectedDisplayId || !fixed.isValid) return false
        val managerEvidence = definition.displayManager.getDisplay(definition.selectedDisplayId) ?: return false
        return managerEvidence.displayId == definition.selectedDisplayId && managerEvidence.isValid
    }

    private fun installDisplayEpoch(epochDisplay: Display): Boolean {
        if (lastEpochIdentity == Long.MAX_VALUE) {
            fenceCallbacks()
            observer.onFailure(epochExhaustionCause)
            return false
        }
        val associationIdentity = associationIdentityFor(epochDisplay) ?: return false
        val epochIdentity = lastEpochIdentity + 1L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                definition.applicationContext.createWindowContext(
                    epochDisplay,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    null,
                )
            } else {
                definition.applicationContext.createDisplayContext(epochDisplay)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            }
            currentEpochWindowContext = windowContext
            currentEpochWindowManager = requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
                "WindowManager must be available for the selected display"
            }
        }
        currentEpochDisplay = epochDisplay
        currentEpochAssociation = CaptureMetricsDisplayAssociation(
            displayId = epochDisplay.displayId,
            associationIdentity = associationIdentity,
            validityEpoch = epochIdentity,
        )
        lastEpochIdentity = epochIdentity
        return true
    }

    private fun unavailableAssociationFor(display: Display): CaptureMetricsDisplayAssociation? {
        val associationIdentity = associationIdentityFor(display) ?: return null
        return CaptureMetricsDisplayAssociation(
            displayId = display.displayId,
            associationIdentity = associationIdentity,
            validityEpoch = 0L,
        )
    }

    private fun associationIdentityFor(display: Display): Long? {
        if (lastAssociatedDisplay === display) return lastAssociationIdentity
        if (lastAssociationIdentity == Long.MAX_VALUE) {
            fenceCallbacks()
            observer.onFailure(associationExhaustionCause)
            return null
        }
        lastAssociatedDisplay = display
        lastAssociationIdentity += 1L
        return lastAssociationIdentity
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(epochDisplay: Display): CaptureMetrics? {
        val widthPx: Int
        val heightPx: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = checkNotNull(currentEpochWindowManager).maximumWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            val point = checkNotNull(reusableRealSizePoint)
            epochDisplay.getRealSize(point)
            widthPx = point.x
            heightPx = point.y
        }
        val densityDpi = definition.applicationContext
            .createDisplayContext(epochDisplay)
            .resources.configuration.densityDpi
        return if (widthPx > 0 && heightPx > 0 && densityDpi > 0) {
            CaptureMetrics(widthPx, heightPx, densityDpi)
        } else {
            null
        }
    }

    private fun retireCurrentEpoch() {
        currentEpochDisplay = null
        currentEpochAssociation = null
        currentEpochWindowContext = null
        currentEpochWindowManager = null
    }

    private companion object {
        private const val ATTACHMENT_OPEN = 1
        private const val ATTACHMENT_DIRTY = 1 shl 1
        private const val ATTACHMENT_EPOCH_INVALIDATED = 1 shl 2
    }
}
