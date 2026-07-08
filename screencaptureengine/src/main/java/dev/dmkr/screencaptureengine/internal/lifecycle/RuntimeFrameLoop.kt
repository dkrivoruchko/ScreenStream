package dev.dmkr.screencaptureengine.internal.lifecycle

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import dev.dmkr.screencaptureengine.internal.startup.StartupRuntimePendingSignals
import dev.dmkr.screencaptureengine.internal.startup.StartupToRuntimeSignalMailbox

/**
 * Gated runtime frame-loop state installed before the initial Active commit.
 *
 * Frame callbacks recorded before commit are retained as latest-only input signals, but no signal is
 * admitted for GL consumption until [drainPendingSignalsForCommit] marks the loop committed. Raw
 * frame callbacks that are overwritten before [admitLatestFrameSignal] are coalesced input, not
 * production drops. Periodic refresh ticks are intentionally not represented as source frames here;
 * before the first real source frame is acquired they materialize no work and no drop accounting.
 */
internal class RuntimeFrameLoop internal constructor(
    private val startupGeometry: CaptureGeometry,
    private val onRuntimeSignalRecorded: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val signalMailbox = StartupToRuntimeSignalMailbox(startupGeometry = startupGeometry)
    private val frameSignalSource = ConflatedRuntimeFrameSignalSource()
    internal val frameSignalSink: RuntimeFrameSignalSink = RuntimeFrameSignalSink(::recordSourceFrameAvailable)
    private var closed = false
    private var committed = false
    private var sourceFrameSignalPending = false
    private var periodicRefreshWakePending = false
    private var latestSourceFrameGeneration: Long? = null
    private var admittedProductionAttempts = 0L
    private var metricsDrainedAtCommit: CaptureMetrics? = null

    internal fun recordSourceFrameAvailable(generation: Long) {
        recordSourceFrameAvailable(generation = generation, notifyRuntimeWhenCommitted = true)
    }

    internal fun recordSourceFrameAvailableWithoutRuntimeWakeForTesting(generation: Long) {
        recordSourceFrameAvailable(generation = generation, notifyRuntimeWhenCommitted = false)
    }

    private fun recordSourceFrameAvailable(generation: Long, notifyRuntimeWhenCommitted: Boolean) {
        require(generation > 0L) { "generation must be positive, was $generation" }
        var shouldNotify = false
        synchronized(lock) {
            if (closed) return
            sourceFrameSignalPending = true
            latestSourceFrameGeneration = generation
            frameSignalSource.enqueueFrameAvailable(generation)
            shouldNotify = committed && notifyRuntimeWhenCommitted
        }
        if (shouldNotify) onRuntimeSignalRecorded()
    }

    internal fun recordPeriodicRefreshWakeForTesting() {
        recordPeriodicRefreshWake(notifyRuntimeWhenCommitted = false)
    }

    internal fun recordPeriodicRefreshWake() {
        recordPeriodicRefreshWake(notifyRuntimeWhenCommitted = true)
    }

    private fun recordPeriodicRefreshWake(notifyRuntimeWhenCommitted: Boolean) {
        var shouldNotify = false
        synchronized(lock) {
            if (closed) return
            periodicRefreshWakePending = true
            shouldNotify = committed && notifyRuntimeWhenCommitted
        }
        if (shouldNotify) onRuntimeSignalRecorded()
    }

    internal fun recordProjectionStopped() {
        var shouldNotify = false
        synchronized(lock) {
            if (closed) return
            signalMailbox.recordProjectionStopped()
            shouldNotify = committed
        }
        if (shouldNotify) onRuntimeSignalRecorded()
    }

    internal fun recordCapturedContentResize(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        var shouldNotify = false
        synchronized(lock) {
            if (closed) return
            signalMailbox.recordCapturedContentResize(resize)
            shouldNotify = committed
        }
        if (shouldNotify) onRuntimeSignalRecorded()
    }

    internal fun recordCapturedContentVisibility(isVisible: Boolean) {
        var shouldNotify = false
        synchronized(lock) {
            if (closed) return
            signalMailbox.recordCapturedContentVisibility(isVisible)
            shouldNotify = committed
        }
        if (shouldNotify) onRuntimeSignalRecorded()
    }

    internal fun pendingSignalsSnapshot(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): StartupRuntimePendingSignals =
        synchronized(lock) {
            signalMailbox
                .snapshot(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved)
                .withoutAlreadyDrainedMetricsGeometry(latestMetrics)
        }

    internal fun drainPendingSignalsForCommit(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): StartupRuntimePendingSignals =
        synchronized(lock) {
            committed = true
            metricsDrainedAtCommit = latestMetrics
            signalMailbox.drain(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved)
        }

    internal fun drainPendingSignalsForRuntime(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): StartupRuntimePendingSignals =
        synchronized(lock) {
            if (closed || !committed) {
                StartupRuntimePendingSignals(
                    projectionStopObserved = projectionStopObserved,
                    pendingCapturedContentResize = null,
                    latestCaptureMetrics = latestMetrics,
                    pendingCaptureGeometry = null,
                    latestCapturedContentVisible = null,
                )
            } else {
                metricsDrainedAtCommit = latestMetrics
                signalMailbox.drain(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved)
            }
        }

    internal fun hasPendingRuntimeWork(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (closed || !committed) return@synchronized false
            if (sourceFrameSignalPending) return@synchronized true
            if (periodicRefreshWakePending) return@synchronized true
            val signals = signalMailbox
                .snapshot(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved)
                .withoutAlreadyDrainedMetricsGeometry(latestMetrics)
            signals.projectionStopObserved ||
                    signals.pendingCaptureGeometry != null ||
                    signals.latestCapturedContentVisible != null
        }

    internal fun admitLatestFrameSignal(): RuntimeFrameSignal? =
        synchronized(lock) {
            if (closed || !committed) return null
            val signal = frameSignalSource.drainLatestFrameSignal() ?: return null
            sourceFrameSignalPending = false
            latestSourceFrameGeneration = null
            admittedProductionAttempts = Math.addExact(admittedProductionAttempts, 1L)
            signal
        }

    internal fun consumePeriodicRefreshWake(): Boolean =
        synchronized(lock) {
            if (closed || !committed || !periodicRefreshWakePending) return false
            periodicRefreshWakePending = false
            true
        }

    internal fun fenceTerminal() {
        synchronized(lock) {
            closed = true
            sourceFrameSignalPending = false
            periodicRefreshWakePending = false
            latestSourceFrameGeneration = null
            frameSignalSource.drainLatestFrameSignal()
        }
    }

    internal fun snapshot(): RuntimeFrameLoopSnapshot =
        synchronized(lock) {
            RuntimeFrameLoopSnapshot(
                committed = committed,
                sourceFrameSignalPending = sourceFrameSignalPending,
                latestSourceFrameGeneration = latestSourceFrameGeneration,
                admittedProductionAttempts = admittedProductionAttempts,
            )
        }

    override fun close() {
        fenceTerminal()
    }

    private fun StartupRuntimePendingSignals.withoutAlreadyDrainedMetricsGeometry(
        latestMetrics: CaptureMetrics,
    ): StartupRuntimePendingSignals {
        if (!committed || metricsDrainedAtCommit != latestMetrics || pendingCapturedContentResize != null || projectionStopObserved) {
            return this
        }
        return StartupRuntimePendingSignals(
            projectionStopObserved = false,
            pendingCapturedContentResize = null,
            latestCaptureMetrics = latestCaptureMetrics,
            pendingCaptureGeometry = null,
            latestCapturedContentVisible = latestCapturedContentVisible,
        )
    }
}

internal class RuntimeFrameLoopSnapshot internal constructor(
    internal val committed: Boolean,
    internal val sourceFrameSignalPending: Boolean,
    internal val latestSourceFrameGeneration: Long?,
    internal val admittedProductionAttempts: Long,
)
