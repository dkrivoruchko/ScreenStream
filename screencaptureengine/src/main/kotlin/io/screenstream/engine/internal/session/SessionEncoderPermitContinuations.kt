package io.screenstream.engine.internal.session

import io.screenstream.engine.internal.jpeg.EncoderTask
import io.screenstream.engine.internal.jpeg.EncoderTaskReturn

/** The exact normal JPEG-role return which is waiting for physical serial-permit release. */
internal class SessionEncoderPermitContinuation internal constructor(
    internal val returned: EncoderTaskReturn,
) {
    internal val task: EncoderTask = returned.task
}

/** Session-owned one-slot proof state; it owns no encoder resource or outward continuation. */
internal class SessionEncoderPermitContinuations internal constructor() {
    private var current: SessionEncoderPermitContinuation? = null

    internal fun install(returned: EncoderTaskReturn) {
        check(current == null)
        current = SessionEncoderPermitContinuation(returned)
    }

    internal fun release(expected: EncoderTask): SessionEncoderPermitContinuation? {
        val installed = current ?: return null
        if (installed.task !== expected) return null
        current = null
        return installed
    }

    internal fun awaits(task: EncoderTask): Boolean = current?.task === task

    internal val hasCurrent: Boolean
        get() = current != null

    internal val currentReturn: EncoderTaskReturn?
        get() = current?.returned
}
