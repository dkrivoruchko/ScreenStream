package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.EngineAttachableCaptureMetricsProvider
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Internal startup-to-runtime observation of a caller-owned metrics provider.
 *
 * The provider object remains caller-owned. The supplied coroutine context contributes only
 * non-`Job` elements such as dispatcher/test scheduler context. This observation owns its internal
 * collector job, optional provider attachment, and latest valid metrics snapshot while held by
 * startup, pre-active, or initial-runtime resource owners. Closing the observation cancels only
 * observation-owned jobs and attachments, never the caller or provider's shared job.
 */
internal class CaptureMetricsObservation private constructor(
    private val metricsAttachment: DisposableHandle?,
    private val observationJob: Job,
    initialState: CaptureMetricsState,
    private val externalMetricsChangedListener: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var latestState = initialState
    private var latestValidMetrics = initialState.availableMetricsOrNull()
    private var metricsCollector: Job? = null
    private var runtimeMetricsChangedListener: (() -> Unit)? = null

    internal val latestMetrics: CaptureMetrics
        get() = synchronized(lock) {
            latestValidMetrics ?: error("No valid capture metrics are available.")
        }

    internal val latestMetricsOrNull: CaptureMetrics?
        get() = synchronized(lock) { latestValidMetrics }

    internal val latestAvailableMetricsOrNull: CaptureMetrics?
        get() = synchronized(lock) { latestState.availableMetricsOrNull() }

    internal val latestProviderState: CaptureMetricsState
        get() = synchronized(lock) { latestState }

    internal fun installRuntimeMetricsChangedListener(listener: (() -> Unit)?) {
        synchronized(lock) {
            if (!closed) {
                runtimeMetricsChangedListener = listener
            }
        }
    }

    internal fun refreshLatestProviderState(state: CaptureMetricsState) {
        update(state, notifyExternal = false)
    }

    private fun update(state: CaptureMetricsState, notifyExternal: Boolean = true): Boolean {
        val runtimeListener: (() -> Unit)?
        synchronized(lock) {
            if (closed || latestState == state) {
                return false
            }
            latestState = state
            state.availableMetricsOrNull()?.let { latestValidMetrics = it }
            runtimeListener = runtimeMetricsChangedListener
        }
        if (notifyExternal) {
            externalMetricsChangedListener()
        }
        runtimeListener?.invoke()
        return true
    }

    private fun attachCollector(collector: Job) {
        synchronized(lock) {
            if (closed) {
                collector.cancel()
            } else {
                metricsCollector = collector
            }
        }
    }

    override fun close() {
        val closeState = synchronized(lock) {
            if (closed) return
            closed = true
            MetricsObservationCloseState(
                attachment = metricsAttachment,
                collector = metricsCollector,
                observationJob = observationJob,
            )
        }
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collect { closeState.collector?.cancel() }
        cleanupFailures.collect { closeState.observationJob.cancel() }
        cleanupFailures.collect { closeState.attachment?.dispose() }
        cleanupFailures.throwIfAny()
    }

    internal companion object {
        internal fun start(
            provider: CaptureMetricsProvider,
            coroutineContext: CoroutineContext,
            onMetricsChanged: () -> Unit = {},
        ): CaptureMetricsObservation {
            val observationJob = SupervisorJob()
            var attachment: DisposableHandle? = null
            var observation: CaptureMetricsObservation? = null
            var collector: Job? = null
            var latestAttachedState: CaptureMetricsState? = null
            try {
                attachment = (provider as? EngineAttachableCaptureMetricsProvider)
                    ?.attachSessionAttachment {
                        val state = provider.metrics.value
                        latestAttachedState = state
                        observation?.update(state, notifyExternal = false)
                        onMetricsChanged()
                    }
                observation = CaptureMetricsObservation(
                    metricsAttachment = attachment,
                    observationJob = observationJob,
                    initialState = latestAttachedState ?: provider.metrics.value,
                    externalMetricsChangedListener = onMetricsChanged,
                )
                collector = CoroutineScope(coroutineContext.minusKey(Job) + observationJob).launch {
                    provider.metrics.collect(observation::update)
                }
                observation.attachCollector(collector)
                return observation
            } catch (cause: Throwable) {
                val cleanupFailures = CleanupFailureCollector()
                cleanupFailures.collect { collector?.cancel() }
                val createdObservation = observation
                if (createdObservation == null) {
                    cleanupFailures.collect { observationJob.cancel() }
                    cleanupFailures.collect { attachment?.dispose() }
                } else {
                    cleanupFailures.collect { createdObservation.close() }
                }
                cleanupFailures.failureOrNull()
                    ?.takeIf { cleanupFailure -> cleanupFailure !== cause }
                    ?.let(cause::addSuppressed)
                throw cause
            }
        }
    }

    private fun CaptureMetricsState.availableMetricsOrNull(): CaptureMetrics? =
        when (this) {
            is CaptureMetricsState.Available -> metrics
            is CaptureMetricsState.Unavailable -> null
        }

    private class MetricsObservationCloseState(
        val attachment: DisposableHandle?,
        val collector: Job?,
        val observationJob: Job,
    )
}
