@file:Suppress("unused") // Dormant until the v41 controller is integrated.

package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.internal.control.ControllerCancellationMarkerRevision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session-private mechanical observation of one caller-owned metrics provider.
 *
 * Getter access, collection, fact delivery, cancellation marking, and barrier handling are confined
 * to the injected dispatcher. The sink must return synchronously and must not throw. This collector never calls
 * public APIs and never owns or closes the provider. Its provider/flow references are cleared only
 * after collection has returned and the matching session barrier has been proved.
 */
internal class SessionMetricsCollector private constructor(
    dispatcher: CoroutineDispatcher,
    private val tag: SessionMetricsFactTag,
    private val sink: SessionMetricsFactSink,
    provider: CaptureMetricsProvider,
) {
    private val lane = dispatcher.limitedParallelism(1)
    private val collectorOwner = SupervisorJob()
    private val scope = CoroutineScope(lane + collectorOwner)
    private val retainedSource = RetainedSource(provider)
    private var acceptingSamples = true
    private var acceptingTerminalFacts = true
    private var collectionReturned = false
    private var barrierProved = false
    private var cancellationMarked = false
    private val collectionJob: Job = scope.launch { collectProvider() }

    /** Records the marker before best-effort cancellation; it performs no attribution. */
    internal suspend fun requestCancellation(marker: ControllerCancellationMarkerRevision) {
        withContext(lane) {
            if (!cancellationMarked) {
                cancellationMarked = true
                acceptingSamples = false
                sink.accept(SessionMetricsFact.CancellationMarked(tag, marker))
            }
            collectionJob.cancel()
        }
    }

    /**
     * Installs the late-fact fence and records explicit proof of the matching session barrier.
     * Cancellation must have been marked first so retirement cannot hide its ordering evidence.
     */
    internal suspend fun proveSessionBarrier(proof: SessionMetricsBarrierProof) {
        withContext(lane) {
            require(proof.session == tag.session && proof.attachment == tag.attachment)
            check(cancellationMarked) { "Metrics cancellation must be marked before the session barrier." }
            acceptingSamples = false
            acceptingTerminalFacts = false
            barrierProved = true
            releaseSourceIfSafe()
        }
    }

    /** Narrow mechanical visibility used only by deterministic ownership tests. */
    internal suspend fun testSnapshot(): TestSnapshot =
        withContext(lane) {
            TestSnapshot(
                cancellationMarked = cancellationMarked,
                collectionReturned = collectionReturned,
                barrierProved = barrierProved,
                sourceReferencesRetained = retainedSource.referencesRetained,
                sourceReleaseCount = retainedSource.releaseCount,
            )
        }

    private suspend fun collectProvider() {
        val flow = try {
            retainedSource.provider!!.metrics.also(retainedSource::retainFlow)
        } catch (cause: Throwable) {
            emitTerminalIfCurrent(SessionMetricsFact.GetterThrew(tag, cause))
            collectionDidReturn()
            return
        }

        try {
            val collection: Flow<CaptureMetricsState> = flow
            collection.collect { state -> emitIfCurrent(state.toFact()) }
            emitTerminalIfCurrent(SessionMetricsFact.CollectionCompleted(tag))
        } catch (cause: Throwable) {
            emitTerminalIfCurrent(SessionMetricsFact.CollectionThrew(tag, cause))
        } finally {
            collectionDidReturn()
        }
    }

    private fun CaptureMetricsState.toFact(): SessionMetricsFact =
        when (this) {
            is CaptureMetricsState.Available -> SessionMetricsFact.Available(
                tag = tag,
                widthPx = metrics.widthPx,
                heightPx = metrics.heightPx,
                densityDpi = metrics.densityDpi,
            )

            is CaptureMetricsState.Unavailable -> SessionMetricsFact.Unavailable(
                tag = tag,
                reason = reason,
                message = message,
            )
        }

    private fun emitIfCurrent(fact: SessionMetricsFact) {
        if (acceptingSamples) sink.accept(fact)
    }

    private fun emitTerminalIfCurrent(fact: SessionMetricsFact) {
        if (acceptingTerminalFacts) sink.accept(fact)
    }

    private fun collectionDidReturn() {
        collectionReturned = true
        collectorOwner.cancel()
        releaseSourceIfSafe()
    }

    private fun releaseSourceIfSafe() {
        if (collectionReturned && barrierProved) retainedSource.clear()
    }

    internal companion object {
        internal fun start(
            provider: CaptureMetricsProvider,
            dispatcher: CoroutineDispatcher,
            tag: SessionMetricsFactTag,
            sink: SessionMetricsFactSink,
        ): SessionMetricsCollector = SessionMetricsCollector(dispatcher, tag, sink, provider)
    }

    internal data class TestSnapshot(
        val cancellationMarked: Boolean,
        val collectionReturned: Boolean,
        val barrierProved: Boolean,
        val sourceReferencesRetained: Boolean,
        val sourceReleaseCount: Int,
    )

    /** Isolates strong-reference retirement from coroutine captures. */
    private class RetainedSource(
        var provider: CaptureMetricsProvider?,
    ) {
        var flow: StateFlow<CaptureMetricsState>? = null
            private set
        var releaseCount: Int = 0
            private set
        val referencesRetained: Boolean
            get() = provider != null || flow != null

        fun retainFlow(value: StateFlow<CaptureMetricsState>) {
            flow = value
        }

        fun clear() {
            if (!referencesRetained) return
            provider = null
            flow = null
            releaseCount++
        }
    }
}
