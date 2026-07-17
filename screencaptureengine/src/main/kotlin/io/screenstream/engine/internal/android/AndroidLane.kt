package io.screenstream.engine.internal.android

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed class AndroidLaneStartupResult {
    internal class Ready(internal val handler: Handler) : AndroidLaneStartupResult()

    internal class Failed(internal val cause: Throwable) : AndroidLaneStartupResult()
}

internal class AndroidLaneStartupCell {
    private val result = AtomicReference<AndroidLaneStartupResult?>(null)

    internal val current: AndroidLaneStartupResult?
        get() = result.get()

    internal fun publishReady(handler: Handler): Boolean =
        result.compareAndSet(null, AndroidLaneStartupResult.Ready(handler))

    internal fun publishFailure(cause: Throwable): Boolean =
        result.compareAndSet(null, AndroidLaneStartupResult.Failed(cause))
}

internal class AndroidLaneQuitRequestCell {
    private val requested = AtomicBoolean(false)

    internal val isRequested: Boolean
        get() = requested.get()

    internal fun request(): Boolean = requested.compareAndSet(false, true)
}

internal class AndroidLaneTerminationCell {
    private val returned = AtomicBoolean(false)

    internal val hasReturned: Boolean
        get() = returned.get()

    internal fun publishThreadReturn(): Boolean = returned.compareAndSet(false, true)
}
