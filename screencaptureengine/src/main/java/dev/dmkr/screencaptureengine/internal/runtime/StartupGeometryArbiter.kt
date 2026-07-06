package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tracks projection freshness across startup.
 *
 * Callback registration is attachment only. The projection becomes consumed when
 * `createVirtualDisplay(...)` is entered, when projection stop is observed, or when this lifecycle
 * invokes `stop()`. After consumption or observed stop, startup retry requires a fresh projection.
 */
internal class StartupProjectionLifecycle(
    private val projection: ProjectionHandle,
    private val cleanupFailureSink: StartupCleanupFailureSink,
) {
    private val lock = Any()
    private var projectionConsumed = false
    private var projectionStopObserved = false
    private var projectionStopInvoked = false
    private var ingressClosed = false

    val requiresFreshProjection: Boolean
        get() = synchronized(lock) { projectionConsumed || projectionStopObserved }

    fun <T : Any> createVirtualDisplayIfNotStopped(
        onCreateEntered: () -> Unit,
        isProjectionStopObserved: () -> Boolean,
        create: () -> T,
    ): T? {
        if (isProjectionStopObserved()) {
            onProjectionStopObserved()
            return null
        }
        synchronized(lock) {
            if (ingressClosed || projectionStopObserved) return null
            projectionConsumed = true
        }
        onCreateEntered()
        if (isProjectionStopObserved()) {
            onProjectionStopObserved()
            return null
        }
        return create()
    }

    fun onProjectionStopObserved(): Boolean =
        synchronized(lock) {
            if (ingressClosed) return false
            projectionConsumed = true
            projectionStopObserved = true
            true
        }

    fun closeIngress() {
        synchronized(lock) {
            ingressClosed = true
        }
    }

    fun stopProjectionIfRequired() {
        val shouldStop = synchronized(lock) {
            if (!projectionConsumed || projectionStopObserved || projectionStopInvoked) return
            projectionConsumed = true
            projectionStopInvoked = true
            true
        }
        if (!shouldStop) return
        runCatching { projection.stop() }.onFailure(cleanupFailureSink::onCleanupFailure)
    }
}

/**
 * Serializes authoritative startup geometry decisions.
 *
 * Valid captured-content resize dimensions are positive. Invalid resize callbacks are ignored.
 * Decision priority before `AuthoritativeStartupGeometryReady` is projection stop, caller
 * cancellation, first valid resize, then timeout. Ordinary startup resource failures are checked by
 * the caller around this gate; after geometry commits, later resize/metrics/density signals are
 * pending runtime inputs instead of startup-success decisions.
 */
internal class StartupGeometryArbiter(
    private val projectionLifecycle: StartupProjectionLifecycle,
) {
    private val lock = Any()
    private var projectionStopped = false
    private var firstValidResize: StartupCapturedContentResize? = null
    private var timeoutObserved = false
    private var isClosed = false
    private var waiter: CompletableDeferred<Unit>? = null

    val isProjectionStopped: Boolean
        get() = synchronized(lock) { projectionStopped }

    fun recordProjectionStopped() {
        if (!projectionLifecycle.onProjectionStopObserved()) return
        wakeAfterUpdate {
            if (isClosed) return@wakeAfterUpdate
            projectionStopped = true
        }
    }

    fun recordCapturedContentResize(resize: StartupCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        wakeAfterUpdate {
            if (isClosed) return@wakeAfterUpdate
            if (firstValidResize == null) {
                firstValidResize = resize
            }
        }
    }

    fun recordTimeout() {
        wakeAfterUpdate {
            if (isClosed) return@wakeAfterUpdate
            timeoutObserved = true
        }
    }

    fun close() {
        wakeAfterUpdate {
            isClosed = true
        }
    }

    suspend fun awaitDecision(callerJob: Job?): StartupGeometryDecision {
        while (true) {
            val waitForUpdate = synchronized(lock) {
                resolvePriorityLocked(callerActive = callerJob?.isActive != false)?.let { return it }
                waiter ?: CompletableDeferred<Unit>().also { waiter = it }
            }
            try {
                waitForUpdate.await()
            } catch (cause: CancellationException) {
                synchronized(lock) {
                    resolvePriorityLocked(callerActive = false)
                }?.let { return it }
                throw cause
            }
        }
    }

    private fun wakeAfterUpdate(update: () -> Unit) {
        val waiterToWake = synchronized(lock) {
            update()
            waiter.also { waiter = null }
        }
        waiterToWake?.complete(Unit)
    }

    private fun resolvePriorityLocked(callerActive: Boolean): StartupGeometryDecision? =
        StartupGeometryGate.decide(
            StartupGeometryGateSnapshot(
                projectionStopped = projectionStopped,
                callerActive = callerActive,
                firstValidResize = firstValidResize,
                timeoutObserved = timeoutObserved,
            ),
        )
}

internal sealed interface StartupGeometryDecision {
    data object ProjectionStopped : StartupGeometryDecision
    data object CallerCancelled : StartupGeometryDecision
    class FirstValidResize(val resize: StartupCapturedContentResize) : StartupGeometryDecision
    data object Timeout : StartupGeometryDecision
}

internal class StartupGeometryGateSnapshot internal constructor(
    internal val projectionStopped: Boolean,
    internal val callerActive: Boolean,
    internal val firstValidResize: StartupCapturedContentResize?,
    internal val timeoutObserved: Boolean,
)

internal object StartupGeometryGate {
    internal fun decide(snapshot: StartupGeometryGateSnapshot): StartupGeometryDecision? =
        when {
            snapshot.projectionStopped -> StartupGeometryDecision.ProjectionStopped
            !snapshot.callerActive -> StartupGeometryDecision.CallerCancelled
            snapshot.firstValidResize != null -> StartupGeometryDecision.FirstValidResize(snapshot.firstValidResize)
            snapshot.timeoutObserved -> StartupGeometryDecision.Timeout
            else -> null
        }
}

internal class StartupAuthoritativeGeometry(
    val geometry: CaptureGeometry,
    val consumedResize: StartupCapturedContentResize?,
)
