package io.screenstream.engine.internal.settlement

import java.util.concurrent.atomic.AtomicReference

internal fun interface DirectFatalSettlement {
    fun settle(rawThrowable: Throwable)
}

internal class DirectFatalSlot {
    private val rawThrowable = AtomicReference<Throwable?>(null)

    internal val current: Throwable?
        get() = rawThrowable.get()

    internal fun publish(raw: Throwable): Boolean = rawThrowable.compareAndSet(null, raw)
}

internal object FatalThrowablePolicy {
    internal fun isDirectFatal(throwable: Throwable): Boolean = throwable !is Exception

    internal fun publish(
        slot: DirectFatalSlot,
        raw: Throwable,
        settlement: DirectFatalSettlement,
    ) {
        slot.publish(raw)
        settlement.settle(raw)
    }

    internal fun rethrow(raw: Throwable): Nothing {
        throw raw
    }
}
