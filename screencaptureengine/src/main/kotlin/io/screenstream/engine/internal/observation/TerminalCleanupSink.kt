package io.screenstream.engine.internal.observation

import io.screenstream.engine.ScreenCaptureDiagnosticEvent
import io.screenstream.engine.internal.capture.CaptureCapsule
import io.screenstream.engine.internal.capture.CaptureRetirement
import io.screenstream.engine.internal.delivery.DeliveryCapsule
import io.screenstream.engine.internal.delivery.DeliveryRetirement
import io.screenstream.engine.internal.jpeg.EncoderCapsule
import io.screenstream.engine.internal.jpeg.EncoderRetirement
import io.screenstream.engine.internal.metrics.MetricsCapsule
import io.screenstream.engine.internal.metrics.MetricsRetirement
import io.screenstream.engine.internal.runtime.WallClock
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicReference

internal class MetricsCleanupReturn internal constructor(
    internal val capsule: MetricsCapsule,
    internal val expectedPrevious: MetricsCleanupReturn?,
    internal val residueRemains: Boolean,
    internal val cause: Throwable?,
)

internal class CaptureCleanupReturn internal constructor(
    internal val capsule: CaptureCapsule,
    internal val expectedPrevious: CaptureCleanupReturn?,
    internal val residueRemains: Boolean,
    internal val cause: Throwable?,
)

internal class EncoderCleanupReturn internal constructor(
    internal val capsule: EncoderCapsule,
    internal val expectedPrevious: EncoderCleanupReturn?,
    internal val residueRemains: Boolean,
    internal val cause: Throwable?,
)

internal class DeliveryCleanupReturn internal constructor(
    internal val capsule: DeliveryCapsule,
    internal val expectedPrevious: DeliveryCleanupReturn?,
    internal val residueRemains: Boolean,
    internal val cause: Throwable?,
)

/** Diagnostic serializer plus four independent identity-fenced retirement transitions. */
internal class TerminalCleanupSink internal constructor(
    private val wallClock: WallClock,
    private val diagnostics: MutableSharedFlow<ScreenCaptureDiagnosticEvent>,
) {
    private class MetricsSlot(val retirement: MetricsRetirement, val lastReturn: MetricsCleanupReturn?)
    private class CaptureSlot(val retirement: CaptureRetirement, val lastReturn: CaptureCleanupReturn?)
    private class EncoderSlot(val retirement: EncoderRetirement, val lastReturn: EncoderCleanupReturn?)
    private class DeliverySlot(val retirement: DeliveryRetirement, val lastReturn: DeliveryCleanupReturn?)

    private val diagnosticGate = Any()
    private var sequence = 0L
    private var terminalCleanupOnly = false
    private val metrics = AtomicReference<MetricsSlot?>(null)
    private val capture = AtomicReference<CaptureSlot?>(null)
    private val encoder = AtomicReference<EncoderSlot?>(null)
    private val delivery = AtomicReference<DeliverySlot?>(null)

    internal fun tryOrdinary(request: DiagnosticRequest) {
        try {
            synchronized(diagnosticGate) { if (!terminalCleanupOnly) emitLocked(request) }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }

    internal fun tryTerminal(request: DiagnosticRequest) {
        synchronized(diagnosticGate) { if (!terminalCleanupOnly) emitLocked(request) }
    }

    internal fun switchToTerminalCleanupOnly() {
        synchronized(diagnosticGate) { terminalCleanupOnly = true }
    }

    internal fun initializeMetrics(retirement: MetricsRetirement) {
        check(metrics.compareAndSet(null, MetricsSlot(retirement, null)))
    }

    internal fun initializeCapture(retirement: CaptureRetirement) {
        check(capture.compareAndSet(null, CaptureSlot(retirement, null)))
    }

    internal fun initializeEncoder(retirement: EncoderRetirement) {
        check(encoder.compareAndSet(null, EncoderSlot(retirement, null)))
    }

    internal fun initializeDelivery(retirement: DeliveryRetirement) {
        check(delivery.compareAndSet(null, DeliverySlot(retirement, null)))
    }

    internal fun activateMetrics() {
        val current = metrics.get() ?: return
        val expected = current.retirement as? MetricsRetirement.ReturnExpected ?: return
        val next = MetricsSlot(MetricsRetirement.ProcessLifetimeResidue(expected.capsule), current.lastReturn)
        if (metrics.compareAndSet(current, next)) emitCleanup("Attach", current.lastReturn?.cause)
    }

    internal fun activateCapture() {
        val current = capture.get() ?: return
        val expected = current.retirement as? CaptureRetirement.ReturnExpected ?: return
        val next = CaptureSlot(CaptureRetirement.ProcessLifetimeResidue(expected.capsule), current.lastReturn)
        if (capture.compareAndSet(current, next)) emitCleanup("Attach", current.lastReturn?.cause)
    }

    internal fun activateEncoder() {
        val current = encoder.get() ?: return
        val expected = current.retirement as? EncoderRetirement.ReturnExpected ?: return
        val next = EncoderSlot(EncoderRetirement.ProcessLifetimeResidue(expected.capsule), current.lastReturn)
        if (encoder.compareAndSet(current, next)) emitCleanup("Attach", current.lastReturn?.cause)
    }

    internal fun activateDelivery() {
        val current = delivery.get() ?: return
        val expected = current.retirement as? DeliveryRetirement.ReturnExpected ?: return
        val next = DeliverySlot(DeliveryRetirement.ProcessLifetimeResidue(expected.capsule), current.lastReturn)
        if (delivery.compareAndSet(current, next)) emitCleanup("Attach", current.lastReturn?.cause)
    }

    internal fun metricsReturned(fact: MetricsCleanupReturn) {
        while (true) {
            val current = metrics.get() ?: return
            if (current.lastReturn !== fact.expectedPrevious) return
            val next = when (val state = current.retirement) {
                is MetricsRetirement.ReturnExpected -> if (state.capsule !== fact.capsule) return else MetricsSlot(
                    if (fact.residueRemains) state else MetricsRetirement.Closed,
                    fact,
                )

                is MetricsRetirement.ProcessLifetimeResidue -> if (state.capsule !== fact.capsule) return else MetricsSlot(
                    if (fact.residueRemains) MetricsRetirement.ProcessLifetimeResidue(fact.capsule) else MetricsRetirement.Closed,
                    fact,
                )

                MetricsRetirement.Closed -> return
            }
            if (!metrics.compareAndSet(current, next)) continue
            if (current.retirement is MetricsRetirement.ProcessLifetimeResidue) emitReduction(fact.residueRemains, fact.cause)
            return
        }
    }

    internal fun captureReturned(fact: CaptureCleanupReturn) {
        while (true) {
            val current = capture.get() ?: return
            if (current.lastReturn !== fact.expectedPrevious) return
            val next = when (val state = current.retirement) {
                is CaptureRetirement.ReturnExpected -> if (state.capsule !== fact.capsule) return else CaptureSlot(
                    if (fact.residueRemains) state else CaptureRetirement.Closed,
                    fact,
                )

                is CaptureRetirement.ProcessLifetimeResidue -> if (state.capsule !== fact.capsule) return else CaptureSlot(
                    if (fact.residueRemains) CaptureRetirement.ProcessLifetimeResidue(fact.capsule) else CaptureRetirement.Closed,
                    fact,
                )

                CaptureRetirement.Closed -> return
            }
            if (!capture.compareAndSet(current, next)) continue
            if (current.retirement is CaptureRetirement.ProcessLifetimeResidue) emitReduction(fact.residueRemains, fact.cause)
            return
        }
    }

    internal fun encoderReturned(fact: EncoderCleanupReturn) {
        while (true) {
            val current = encoder.get() ?: return
            if (current.lastReturn !== fact.expectedPrevious) return
            val next = when (val state = current.retirement) {
                is EncoderRetirement.ReturnExpected -> if (state.capsule !== fact.capsule) return else EncoderSlot(
                    if (fact.residueRemains) state else EncoderRetirement.Closed,
                    fact,
                )

                is EncoderRetirement.ProcessLifetimeResidue -> if (state.capsule !== fact.capsule) return else EncoderSlot(
                    if (fact.residueRemains) EncoderRetirement.ProcessLifetimeResidue(fact.capsule) else EncoderRetirement.Closed,
                    fact,
                )

                EncoderRetirement.Closed -> return
            }
            if (!encoder.compareAndSet(current, next)) continue
            if (current.retirement is EncoderRetirement.ProcessLifetimeResidue) emitReduction(fact.residueRemains, fact.cause)
            return
        }
    }

    internal fun deliveryReturned(fact: DeliveryCleanupReturn) {
        while (true) {
            val current = delivery.get() ?: return
            if (current.lastReturn !== fact.expectedPrevious) return
            val next = when (val state = current.retirement) {
                is DeliveryRetirement.ReturnExpected -> if (state.capsule !== fact.capsule) return else DeliverySlot(
                    if (fact.residueRemains) state else DeliveryRetirement.Closed,
                    fact,
                )

                is DeliveryRetirement.ProcessLifetimeResidue -> if (state.capsule !== fact.capsule) return else DeliverySlot(
                    if (fact.residueRemains) DeliveryRetirement.ProcessLifetimeResidue(fact.capsule) else DeliveryRetirement.Closed,
                    fact,
                )

                DeliveryRetirement.Closed -> return
            }
            if (!delivery.compareAndSet(current, next)) continue
            if (current.retirement is DeliveryRetirement.ProcessLifetimeResidue) emitReduction(fact.residueRemains, fact.cause)
            return
        }
    }

    private fun emitReduction(residueRemains: Boolean, cause: Throwable?) {
        emitCleanup(if (residueRemains) "Reduce" else "Remove", cause)
    }

    private fun emitCleanup(action: String, cause: Throwable?) {
        try {
            synchronized(diagnosticGate) {
                if (!terminalCleanupOnly) return
                emitLocked(DiagnosticRequest("Cleanup", "QuarantineChanged", action, cause))
            }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }

    private fun emitLocked(request: DiagnosticRequest) {
        check(Thread.holdsLock(diagnosticGate))
        check(sequence != Long.MAX_VALUE) { "diagnostic sequence exhausted" }
        sequence += 1L
        diagnostics.tryEmit(
            ScreenCaptureDiagnosticEvent.create(
                sequence,
                wallClock.epochMillis(),
                request.source,
                request.label,
                request.message,
                request.cause,
            ),
        )
    }
}
