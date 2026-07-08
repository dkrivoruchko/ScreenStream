package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
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
    initialMetrics: CaptureMetrics,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var latest = initialMetrics
    private var metricsCollector: Job? = null

    internal val latestMetrics: CaptureMetrics
        get() = synchronized(lock) { latest }

    private fun update(metrics: CaptureMetrics) {
        synchronized(lock) {
            if (!closed) {
                latest = metrics
            }
        }
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
        internal fun start(provider: CaptureMetricsProvider, coroutineContext: CoroutineContext): CaptureMetricsObservation {
            val observationJob = SupervisorJob()
            var attachment: DisposableHandle? = null
            var observation: CaptureMetricsObservation? = null
            var collector: Job? = null
            try {
                attachment = (provider as? EngineAttachableCaptureMetricsProvider)
                    ?.attachSessionAttachment {}
                observation = CaptureMetricsObservation(
                    metricsAttachment = attachment,
                    observationJob = observationJob,
                    initialMetrics = provider.metrics.value,
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

    private class MetricsObservationCloseState(
        val attachment: DisposableHandle?,
        val collector: Job?,
        val observationJob: Job,
    )
}
