package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics

/**
 * Latest effective startup-to-runtime signal snapshot.
 *
 * [pendingCaptureGeometry] represents resize and/or density work that runtime must process after
 * the first Active commit. [latestCapturedContentVisible] is only the initializer for the first
 * public `capturedContentVisible` value when a public commit later exists; it is not a pending
 * runtime visibility update.
 */
internal class StartupRuntimePendingSignals internal constructor(
    internal val projectionStopObserved: Boolean,
    internal val pendingCapturedContentResize: ProjectionCapturedContentResize?,
    internal val latestCaptureMetrics: CaptureMetrics,
    internal val pendingCaptureGeometry: CaptureGeometry?,
    internal val latestCapturedContentVisible: Boolean?,
)

/**
 * Conflates pre-active callback and metrics signals to the latest effective startup-to-runtime state.
 *
 * Geometry and density changes after the startup geometry snapshot are retained for runtime
 * processing without mutating the frozen initial plan. Visibility is retained separately to
 * initialize the first public `capturedContentVisible` value and is drained exactly once with the
 * handoff snapshot.
 */
internal class StartupToRuntimeSignalMailbox(
    private val startupGeometry: CaptureGeometry,
) {
    private val lock = Any()
    private var projectionStopObserved = false
    private var latestCapturedContentResize: ProjectionCapturedContentResize? = null
    private var latestCapturedContentVisibility: Boolean? = null

    fun recordProjectionStopped() {
        synchronized(lock) {
            projectionStopObserved = true
            latestCapturedContentResize = null
            latestCapturedContentVisibility = null
        }
    }

    fun recordCapturedContentResize(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        synchronized(lock) {
            if (!projectionStopObserved) {
                latestCapturedContentResize = resize
            }
        }
    }

    fun recordCapturedContentVisibility(isVisible: Boolean) {
        synchronized(lock) {
            if (!projectionStopObserved) {
                latestCapturedContentVisibility = isVisible
            }
        }
    }

    fun drain(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): StartupRuntimePendingSignals =
        snapshotAndMaybeDrain(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved, drain = true)

    fun snapshot(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
    ): StartupRuntimePendingSignals =
        snapshotAndMaybeDrain(latestMetrics = latestMetrics, projectionStopObserved = projectionStopObserved, drain = false)

    private fun snapshotAndMaybeDrain(
        latestMetrics: CaptureMetrics,
        projectionStopObserved: Boolean,
        drain: Boolean,
    ): StartupRuntimePendingSignals =
        synchronized(lock) {
            if (projectionStopObserved) {
                this.projectionStopObserved = true
                latestCapturedContentResize = null
                latestCapturedContentVisibility = null
            }
            val isStopped = this.projectionStopObserved
            val resize = if (isStopped) null else latestCapturedContentResize
            val visibility = if (isStopped) null else latestCapturedContentVisibility
            val signals = StartupRuntimePendingSignals(
                projectionStopObserved = isStopped,
                pendingCapturedContentResize = resize,
                latestCaptureMetrics = latestMetrics,
                pendingCaptureGeometry = pendingGeometry(
                    resize = resize,
                    latestMetrics = latestMetrics,
                    isProjectionStopped = isStopped,
                ),
                latestCapturedContentVisible = visibility,
            )
            if (drain) {
                latestCapturedContentResize = null
                latestCapturedContentVisibility = null
            }
            signals
        }

    private fun pendingGeometry(
        resize: ProjectionCapturedContentResize?,
        latestMetrics: CaptureMetrics,
        isProjectionStopped: Boolean,
    ): CaptureGeometry? {
        if (isProjectionStopped) return null
        return when (startupGeometry.source) {
            CaptureGeometrySource.CapturedContentResize -> {
                if ((resize == null) && (latestMetrics.densityDpi == startupGeometry.densityDpi)) return null
                CaptureGeometry(
                    widthPx = resize?.width ?: startupGeometry.widthPx,
                    heightPx = resize?.height ?: startupGeometry.heightPx,
                    densityDpi = latestMetrics.densityDpi,
                    source = CaptureGeometrySource.CapturedContentResize,
                )
            }

            CaptureGeometrySource.MetricsProvider,
            CaptureGeometrySource.MetricsProviderProvisional -> {
                val metricsGeometry = CaptureGeometry(
                    widthPx = latestMetrics.widthPx,
                    heightPx = latestMetrics.heightPx,
                    densityDpi = latestMetrics.densityDpi,
                    source = startupGeometry.source,
                )
                metricsGeometry.takeUnless { geometry -> geometry == startupGeometry }
            }
        }
    }
}
