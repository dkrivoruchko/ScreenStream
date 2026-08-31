package io.screenstream.capture.internal.metrics

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import java.lang.AutoCloseable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal interface BuiltInCaptureMetricsSink {
    fun onMetricsChanged(metrics: CaptureMetrics?)

    fun onObservationFailure(cause: Exception)
}

internal fun interface BuiltInMetricsOwnerDispatcher {
    fun dispatch(task: Runnable)
}

internal class BuiltInCaptureMetricsObservation private constructor(
    private val source: BuiltInCaptureMetricsSource,
    private val sink: BuiltInCaptureMetricsSink,
    private val ownerDispatcher: BuiltInMetricsOwnerDispatcher?,
    workerDispatcher: NonInlineDispatcher?,
) : AutoCloseable {
    private enum class ListenerState { Prepared, RegistrationAttempted, RegisteredAwaitingInitialDispatch, Registered, Closed, }

    private enum class PublishedAvailability { NotPublished, Available, Unavailable, }

    private class DisplayEpoch(val display: Display, val windowContext: Context?, val windowManager: WindowManager?)

    private inner class PublicObservationDispatcher(
        private val delegate: NonInlineDispatcher,
    ) {
        private val currentAttempt = AtomicReference<PublicDispatchAttempt?>()
        private val initialAttemptClaimed = AtomicBoolean()

        fun dispatch() {
            val attempt = PublicDispatchAttempt(initial = !initialAttemptClaimed.getAndSet(true))
            check(currentAttempt.compareAndSet(null, attempt)) { "Built-in metrics schedule already has an owner" }
            val accepted = try {
                delegate.tryDispatch(attempt)
            } catch (failure: Exception) {
                if (!attempt.resolveIfPending(DispatchOutcome.Rejected)) {
                    release(attempt)
                    throw PublicDispatchContractViolation()
                }
                if (attempt.initial) {
                    release(attempt)
                    throw failure
                }
                containPublicDispatchFailure(failure)
                release(attempt)
                return
            }
            if (!accepted) {
                val resolved = attempt.resolve(DispatchOutcome.Rejected)
                if (!resolved) {
                    release(attempt)
                    throw PublicDispatchContractViolation()
                }
                val rejection = IllegalStateException("Built-in metrics dispatch rejected")
                if (attempt.initial) {
                    release(attempt)
                    throw rejection
                }
                containPublicDispatchFailure(rejection)
                release(attempt)
                return
            }
            if (!attempt.resolve(DispatchOutcome.Enter)) {
                release(attempt)
                throw PublicDispatchContractViolation()
            }
        }

        fun release(attempt: PublicDispatchAttempt): Boolean {
            if (!currentAttempt.compareAndSet(attempt, null)) return false
            clearScheduled()
            return true
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private inner class PublicDispatchAttempt(val initial: Boolean) : Runnable {
        private val dispatchingThread: Thread = Thread.currentThread()
        private var outcome = DispatchOutcome.Pending

        fun resolve(resolved: DispatchOutcome): Boolean = synchronized(this) {
            check((resolved === DispatchOutcome.Enter) || (resolved === DispatchOutcome.Rejected))
            if (outcome === DispatchOutcome.ContractViolation) return@synchronized false
            check(outcome === DispatchOutcome.Pending)
            outcome = resolved
            (this as Object).notifyAll()
            true
        }

        fun resolveIfPending(resolved: DispatchOutcome): Boolean = synchronized(this) {
            if (outcome !== DispatchOutcome.Pending) return@synchronized false
            outcome = resolved
            (this as Object).notifyAll()
            true
        }

        override fun run() {
            var interrupted = false
            val enter = synchronized(this) {
                if ((outcome === DispatchOutcome.Pending) && (Thread.currentThread() === dispatchingThread)) {
                    outcome = DispatchOutcome.ContractViolation
                    (this as Object).notifyAll()
                    return@synchronized false
                }
                while (outcome === DispatchOutcome.Pending) {
                    try {
                        (this as Object).wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (outcome !== DispatchOutcome.Enter) return@synchronized false
                true
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (enter) drainOneTurn(this)
        }
    }

    private enum class DispatchOutcome { Pending, Enter, Rejected, ContractViolation, }

    private class PublicDispatchContractViolation : IllegalStateException(
        "NonInlineDispatcher invoked a built-in metrics task reentrantly on the calling thread",
    )

    private val publicDispatcher = workerDispatcher?.let(::PublicObservationDispatcher)
    private val started = AtomicBoolean()
    private val closeGate = Any()
    private val closeAttempted = AtomicBoolean()
    private val listenerState = AtomicReference(ListenerState.Prepared)
    private val closeFailure = AtomicReference<Exception?>()
    private val signals = AtomicInteger(OPEN or DIRTY)
    private val sdkInt: Int = source.platform.sdkInt
    private val mainHandler = source.platform.mainHandler()
    private val ownerDrainTask = Runnable { drainOneTurn(publicAttempt = null) }
    private val legacyDimensions = Point()

    private val displayEpoch = AtomicReference<DisplayEpoch?>()
    private var publishedAvailability = PublishedAvailability.NotPublished

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = true)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = true)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = false)
        }
    }

    constructor(
        source: BuiltInCaptureMetricsSource,
        sink: BuiltInCaptureMetricsSink,
        ownerDispatcher: BuiltInMetricsOwnerDispatcher,
    ) : this(source, sink, ownerDispatcher, null)

    constructor(
        source: BuiltInCaptureMetricsSource,
        sink: BuiltInCaptureMetricsSink,
        workerDispatcher: NonInlineDispatcher,
    ) : this(source, sink, null, workerDispatcher)

    internal fun start() {
        claimStart()
        try {
            registerListener()
        } catch (failure: Exception) {
            try {
                failObservation(failure)
            } catch (_: Exception) {
                requestCloseFromDispatcher()
                closeResources()
            }
            return
        }
        try {
            if (publicDispatcher == null) {
                scheduleDrain()
            } else {
                startInitialPublicDispatch()
            }
        } catch (failure: Exception) {
            requestCloseFromDispatcher()
            val closeFailure = closeResources()
            if ((closeFailure != null) && (closeFailure !== failure)) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    internal fun startOnMetricsOwner() {
        claimStart()
        check(publicDispatcher == null) { "Public metrics observation cannot start on the Session Metrics owner" }
        markDirectDrainScheduled()
        drainOneTurn(publicAttempt = null)
    }

    internal fun closeOnMetricsOwner(): Exception? {
        check((ownerDispatcher != null) && (publicDispatcher == null)) {
            "Only an owner-bound built-in metrics observation may close on the Metrics owner"
        }
        requestCloseFromDispatcher()
        val failure = closeResources()
        clearWorkerStateAfterClose()
        return failure
    }

    override fun close() {
        requestCloseFromDispatcher()
        closeResources()?.let { throw it }
        scheduleDrain()
    }

    private fun claimStart() {
        check(started.compareAndSet(false, true)) { "Built-in metrics observation already started" }
    }

    private fun requestRefresh(invalidateEpoch: Boolean) {
        val requestedSignals = DIRTY or if (invalidateEpoch) EPOCH_INVALIDATED else 0
        while (true) {
            val current = signals.get()
            if ((current and OPEN) == 0) return
            val next = current or requestedSignals
            if ((next == current) || signals.compareAndSet(current, next)) break
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!started.get()) return
        if ((listenerState.get() === ListenerState.RegistrationAttempted) ||
            (listenerState.get() === ListenerState.RegisteredAwaitingInitialDispatch)
        ) {
            return
        }
        while (true) {
            val current = signals.get()
            if ((current and (SCHEDULED or FINISHED)) != 0) return
            if ((current and (DIRTY or CLOSE_REQUESTED)) == 0) return
            if (!signals.compareAndSet(current, current or SCHEDULED)) continue
            if ((listenerState.get() === ListenerState.RegistrationAttempted) ||
                (listenerState.get() === ListenerState.RegisteredAwaitingInitialDispatch)
            ) {
                return
            }
            publicDispatcher?.dispatch() ?: dispatchOwnerDrain()
            return
        }
    }

    private fun startInitialPublicDispatch() {
        val dispatcher = checkNotNull(publicDispatcher)
        check(listenerState.get() === ListenerState.RegisteredAwaitingInitialDispatch) {
            "Built-in metrics public registration is not awaiting its initial dispatch"
        }
        claimInitialSchedule()
        val registered = listenerState.compareAndSet(ListenerState.RegisteredAwaitingInitialDispatch, ListenerState.Registered)
        check(registered) {
            "Built-in metrics public registration was closed before its initial dispatch"
        }
        dispatcher.dispatch()
        scheduleDrain()
    }

    private fun claimInitialSchedule() {
        while (true) {
            check(listenerState.get() === ListenerState.RegisteredAwaitingInitialDispatch) {
                "Built-in metrics public registration is not awaiting its initial dispatch"
            }
            val current = signals.get()
            if ((current and (SCHEDULED or FINISHED)) != 0) return
            if (signals.compareAndSet(current, current or SCHEDULED)) return
        }
    }

    private fun dispatchOwnerDrain() {
        try {
            checkNotNull(ownerDispatcher).dispatch(ownerDrainTask)
        } catch (failure: Exception) {
            clearScheduled()
            throw failure
        }
    }

    private fun releaseScheduled(publicAttempt: PublicDispatchAttempt?): Boolean {
        publicAttempt?.let { return checkNotNull(publicDispatcher).release(it) }
        clearScheduled()
        return true
    }

    private fun markDirectDrainScheduled() {
        while (true) {
            val current = signals.get()
            check((current and (SCHEDULED or FINISHED)) == 0) { "Built-in metrics direct drain is not available" }
            if (signals.compareAndSet(current, current or SCHEDULED)) return
        }
    }

    private fun drainOneTurn(publicAttempt: PublicDispatchAttempt?) {
        val turnFailure = try {
            run turn@{
                if ((signals.get() and CLOSE_REQUESTED) != 0) {
                    closeResources()
                    clearWorkerStateAfterClose()
                    return@turn
                }
                if (listenerState.get() === ListenerState.Prepared) {
                    try {
                        registerListener()
                    } catch (failure: Exception) {
                        failObservation(failure)
                        return@turn
                    }
                }
                if ((signals.get() and CLOSE_REQUESTED) != 0) {
                    closeResources()
                    return@turn
                }
                val refreshSignals = claimRefreshSignals()
                if (refreshSignals != 0) {
                    try {
                        val epochInvalidated = refreshSignals and EPOCH_INVALIDATED
                        performRefresh(epochInvalidated != 0)
                    } catch (failure: Exception) {
                        failObservation(failure)
                    }
                }
            }
            null
        } catch (failure: Exception) {
            failure
        }
        if (turnFailure != null) {
            requestCloseFromDispatcher()
            releaseScheduled(publicAttempt)
            try {
                closeResources()
            } catch (_: Exception) {
            }
            clearWorkerStateAfterClose()
        } else if (releaseScheduled(publicAttempt)) {
            clearWorkerStateAfterClose()
            scheduleDrain()
        }
    }

    private fun registerListener() {
        check(listenerState.compareAndSet(ListenerState.Prepared, ListenerState.RegistrationAttempted)) {
            "Built-in metrics listener registration is not in the prepared state"
        }
        source.platform.registerDisplayListener(source.displayManager, listener, mainHandler)
        val registeredState = if (publicDispatcher == null) {
            ListenerState.Registered
        } else {
            ListenerState.RegisteredAwaitingInitialDispatch
        }
        check(listenerState.compareAndSet(ListenerState.RegistrationAttempted, registeredState)) {
            "Built-in metrics listener registration was closed before it completed"
        }
    }

    private fun claimRefreshSignals(): Int {
        while (true) {
            val current = signals.get()
            if (((current and OPEN) == 0) || ((current and DIRTY) == 0)) return 0
            val next = current and (DIRTY or EPOCH_INVALIDATED).inv()
            if (signals.compareAndSet(current, next)) return current
        }
    }

    private fun performRefresh(epochInvalidated: Boolean) {
        if (epochInvalidated) {
            displayEpoch.set(null)
            publishUnavailable()
            requestRefresh(invalidateEpoch = false)
            return
        }

        val selectedDisplay = source.resolveSelectedDisplay()
        if ((selectedDisplay == null) || !source.isSelectedDisplayValid(selectedDisplay)) {
            displayEpoch.set(null)
            publishUnavailable()
            return
        }

        val readEpoch = displayEpoch.get()?.takeIf { it.display === selectedDisplay } ?: run {
            displayEpoch.set(null)
            createEpoch(selectedDisplay).also { displayEpoch.set(it) }
        }
        if (!source.isSelectedDisplayValid(readEpoch.display)) {
            displayEpoch.compareAndSet(readEpoch, null)
            publishUnavailable()
            requestRefresh(invalidateEpoch = false)
            return
        }

        val metrics = readMetrics(readEpoch)
        val epochInvalidatedDuringRead = (signals.get() and EPOCH_INVALIDATED) != 0
        if ((displayEpoch.get() !== readEpoch) || epochInvalidatedDuringRead || !source.isSelectedDisplayValid(readEpoch.display)) {
            displayEpoch.compareAndSet(readEpoch, null)
            publishUnavailable()
            requestRefresh(invalidateEpoch = false)
            return
        }
        publish(metrics)
    }

    private fun createEpoch(display: Display): DisplayEpoch {
        if (sdkInt < Build.VERSION_CODES.R) {
            return DisplayEpoch(display, null, null)
        }
        val windowContext = if (sdkInt == Build.VERSION_CODES.R) {
            val displayContext = source.platform.createDisplayContext(source.applicationContext, display)
            source.platform.createApi30WindowContext(displayContext)
        } else {
            source.platform.createApi31WindowContext(source.applicationContext, display)
        }
        return DisplayEpoch(
            display = display,
            windowContext = windowContext,
            windowManager = source.platform.windowManager(windowContext),
        )
    }

    @Suppress("DEPRECATION")
    private fun readMetrics(readEpoch: DisplayEpoch): CaptureMetrics? {
        if (!source.platform.isValid(readEpoch.display)) return null
        val widthPx: Int
        val heightPx: Int
        if (sdkInt < Build.VERSION_CODES.R) {
            source.platform.getRealSize(readEpoch.display, legacyDimensions)
            widthPx = legacyDimensions.x
            heightPx = legacyDimensions.y
        } else {
            val bounds = source.platform.maximumWindowBounds(checkNotNull(readEpoch.windowManager))
            widthPx = bounds.width()
            heightPx = bounds.height()
        }
        val densityDpi = source.platform
            .createDisplayContext(source.applicationContext, readEpoch.display)
            .resources.configuration.densityDpi
        if (!source.platform.isValid(readEpoch.display)) return null
        return if ((widthPx > 0) && (heightPx > 0) && (densityDpi > 0)) {
            CaptureMetrics(widthPx, heightPx, densityDpi)
        } else {
            null
        }
    }

    private fun publish(metrics: CaptureMetrics?) {
        if (metrics == null) {
            publishUnavailable()
            return
        }
        if (!admitObserverCallback(closeIngress = false, requireCurrentRefresh = true)) return
        publishedAvailability = PublishedAvailability.Available
        try {
            sink.onMetricsChanged(metrics)
        } catch (failure: Exception) {
            leaveObserverCallback()
            throw failure
        }
        leaveObserverCallback()
    }

    private fun publishUnavailable() {
        if (publishedAvailability === PublishedAvailability.Unavailable) return
        if (!admitObserverCallback(closeIngress = false, requireCurrentRefresh = true)) return
        publishedAvailability = PublishedAvailability.Unavailable
        try {
            sink.onMetricsChanged(null)
        } catch (failure: Exception) {
            leaveObserverCallback()
            throw failure
        }
        leaveObserverCallback()
    }

    private fun failObservation(cause: Exception) {
        if (!admitObserverCallback(closeIngress = true, requireCurrentRefresh = false)) {
            closeResources()
            return
        }
        try {
            sink.onObservationFailure(cause)
        } catch (failure: Exception) {
            leaveObserverCallback()
            throw failure
        }
        leaveObserverCallback()
        closeResources()
    }

    private fun containPublicDispatchFailure(cause: Exception) {
        val containmentFailure = try {
            failObservation(cause)
            null
        } catch (failure: Exception) {
            failure
        }
        if ((containmentFailure != null) && !closeAttempted.get()) {
            try {
                closeResources()
            } catch (_: Exception) {
            }
        }
    }

    private fun admitObserverCallback(closeIngress: Boolean, requireCurrentRefresh: Boolean): Boolean {
        while (true) {
            val current = signals.get()
            if ((current and OBSERVER_ENTERED) != 0) return false
            if ((current and OPEN) == 0) return false
            if (requireCurrentRefresh && ((current and (DIRTY or EPOCH_INVALIDATED)) != 0)) return false
            var next = current or OBSERVER_ENTERED
            if (closeIngress) {
                next = (next and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED
            }
            if (signals.compareAndSet(current, next)) return true
        }
    }

    private fun leaveObserverCallback() {
        while (true) {
            val current = signals.get()
            check((current and OBSERVER_ENTERED) != 0)
            if (signals.compareAndSet(current, current and OBSERVER_ENTERED.inv())) return
        }
    }

    private fun requestCloseFromDispatcher() {
        while (true) {
            val current = signals.get()
            if ((current and (CLOSE_REQUESTED or FINISHED)) != 0) return
            val next = (current and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED
            if (signals.compareAndSet(current, next)) return
        }
    }

    private fun closeResources(): Exception? {
        val failure: Exception?
        synchronized(closeGate) {
            if ((listenerState.get() === ListenerState.Closed) || !closeAttempted.compareAndSet(false, true)) {
                return closeFailure.get()
            }
            val state = listenerState.get()
            val mustUnregister = (state === ListenerState.RegistrationAttempted) ||
                    (state === ListenerState.RegisteredAwaitingInitialDispatch) ||
                    (state === ListenerState.Registered)
            failure = try {
                if (mustUnregister) {
                    source.platform.unregisterDisplayListener(source.displayManager, listener)
                }
                null
            } catch (cause: Exception) {
                cause
            }
            listenerState.set(ListenerState.Closed)
            closeFailure.set(failure)
            markFinished()
        }
        displayEpoch.set(null)
        return failure
    }

    private fun clearWorkerStateAfterClose() {
        if (listenerState.get() === ListenerState.Closed) {
            displayEpoch.set(null)
        }
    }

    private fun markFinished() {
        while (true) {
            val current = signals.get()
            val next = (current and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED or FINISHED
            if (signals.compareAndSet(current, next)) return
        }
    }

    private fun clearScheduled() {
        while (true) {
            val current = signals.get()
            if ((current and SCHEDULED) == 0) return
            if (signals.compareAndSet(current, current and SCHEDULED.inv())) return
        }
    }

    private companion object {
        const val OPEN = 1
        const val DIRTY = 1 shl 1
        const val EPOCH_INVALIDATED = 1 shl 2
        const val SCHEDULED = 1 shl 3
        const val CLOSE_REQUESTED = 1 shl 4
        const val FINISHED = 1 shl 5
        const val OBSERVER_ENTERED = 1 shl 6
    }
}
