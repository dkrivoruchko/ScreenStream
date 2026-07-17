package io.screenstream.engine.internal.android

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed class AndroidLaneStartupResult {
    internal class Ready(internal val handler: Handler) : AndroidLaneStartupResult()

    internal class Failed : AndroidLaneStartupResult() {
        private var recordedCause: Throwable? = null

        internal val cause: Throwable
            get() = checkNotNull(recordedCause)

        internal fun recordCause(cause: Throwable) {
            check(recordedCause == null)
            recordedCause = cause
        }
    }
}

internal class AndroidLaneStartupCell {
    private val result = AtomicReference<AndroidLaneStartupResult?>(null)
    private val startFailure = AndroidLaneStartupResult.Failed()
    private val looperFailure = AndroidLaneStartupResult.Failed()

    internal val current: AndroidLaneStartupResult?
        get() = result.get()

    internal fun publishReady(handler: Handler): Boolean =
        result.compareAndSet(null, AndroidLaneStartupResult.Ready(handler))

    internal fun publishStartFailure(cause: Throwable): Boolean {
        startFailure.recordCause(cause)
        return result.compareAndSet(null, startFailure)
    }

    internal fun publishLooperFailure(cause: Throwable): Boolean {
        looperFailure.recordCause(cause)
        return result.compareAndSet(null, looperFailure)
    }
}

internal class AndroidLaneQuitRequestCell {
    private val requested = AtomicBoolean(false)

    internal val isRequested: Boolean
        get() = requested.get()

    internal fun request(): Boolean = requested.compareAndSet(false, true)
}

internal class AndroidLaneTerminationCell {
    private val returned = AtomicBoolean(false)
    private var threadCause: Throwable? = null

    internal val hasReturned: Boolean
        get() = returned.get()

    internal val cause: Throwable?
        get() {
            if (!returned.get()) return null
            return threadCause
        }

    internal fun publishThreadReturn(cause: Throwable?): Boolean {
        threadCause = cause
        return returned.compareAndSet(false, true)
    }
}
