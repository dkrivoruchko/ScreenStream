package io.screenstream.engine.internal.gl

/** Exact completion of one fail-closed GLES group; never a Session or lifecycle result. */
internal enum class GlGlesGroupCompletion(
    internal val commandsSucceeded: Boolean,
    internal val cleanPostprobeObserved: Boolean,
) {
    Success(commandsSucceeded = true, cleanPostprobeObserved = true),
    LogicalFailureAfterCleanPostprobe(commandsSucceeded = false, cleanPostprobeObserved = true),
    FailedClosed(commandsSucceeded = false, cleanPostprobeObserved = false),
}
