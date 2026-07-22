package io.screenstream.engine.internal.delivery

import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class DeliveryEndpointTerminationIdentity internal constructor()

internal class DeliveryTerminationReceipt private constructor(
    internal val identity: DeliveryEndpointTerminationIdentity,
) {
    internal companion object {
        internal fun create(identity: DeliveryEndpointTerminationIdentity): DeliveryTerminationReceipt =
            DeliveryTerminationReceipt(identity)
    }
}

internal class DeliveryTicket internal constructor(
    internal val endpoint: DeliveryEndpoint,
    internal val identity: Long,
) {
    init {
        require(identity > 0L)
    }

    private val installedHandoff = AtomicReference<DeliveryHandoffRecord?>(null)
    internal val handoff: DeliveryHandoffRecord?
        get() = installedHandoff.get()

    internal fun install(record: DeliveryHandoffRecord): Boolean = installedHandoff.compareAndSet(null, record)
}

/** Exactly one prestarted, serial Delivery endpoint for the Session lifetime. */
internal class DeliveryEndpoint internal constructor(
    internal val owner: DeliveryOwner,
    threadName: String,
    private val signal: SettlementSignal,
) {
    private class OwnedExecutor(endpoint: DeliveryEndpoint, threadFactory: ThreadFactory) : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.NANOSECONDS,
        ArrayBlockingQueue(1),
        threadFactory,
        AbortPolicy(),
    ) {
        private val retainedEndpoint = endpoint

        override fun terminated() {
            retainedEndpoint.publishTerminated()
        }
    }

    private val workerSequence = AtomicLong(0L)
    private val poisoned = AtomicBoolean(false)
    internal val terminationIdentity = DeliveryEndpointTerminationIdentity()
    private val termination = DeliveryTerminationReceipt.create(terminationIdentity)
    private val publishedTermination = AtomicReference<DeliveryTerminationReceipt?>(null)
    internal val shutdownCell = DeliveryShutdownCell()
    private val executor: OwnedExecutor

    init {
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "$threadName-${workerSequence.incrementAndGet()}").apply { isDaemon = false }
        }
        executor = OwnedExecutor(this, threadFactory)
    }

    internal val isPoisoned: Boolean
        get() = poisoned.get()
    internal val isShutdownRequested: Boolean
        get() = shutdownCell.disposition != DeliveryShutdownDisposition.Empty
    internal val terminationReceipt: DeliveryTerminationReceipt?
        get() = publishedTermination.get()
    internal val ownedTerminationReceipt: DeliveryTerminationReceipt
        get() = termination

    internal fun prestart(): Int = executor.prestartAllCoreThreads()

    internal fun execute(runnable: Runnable) {
        executor.execute(runnable)
    }

    internal fun poison() {
        poisoned.set(true)
    }

    internal fun requestShutdown(): DeliveryShutdownDisposition {
        if (!shutdownCell.begin()) return shutdownCell.disposition
        try {
            executor.shutdown()
        } catch (raw: Throwable) {
            shutdownCell.publishThrown(raw)
            throw raw
        }
        shutdownCell.publishReturned()
        return DeliveryShutdownDisposition.Returned
    }

    internal fun accepts(receipt: DeliveryTerminationReceipt): Boolean =
        receipt === termination && receipt.identity === terminationIdentity

    private fun publishTerminated() {
        if (!publishedTermination.compareAndSet(null, termination)) return
        owner.publishEndpointTerminated(this)
        if (shutdownCell.disposition == DeliveryShutdownDisposition.InCall) return
        try {
            signal.signal()
        } catch (_: Throwable) {
            // The release-published receipt remains authoritative.
        }
    }
}
