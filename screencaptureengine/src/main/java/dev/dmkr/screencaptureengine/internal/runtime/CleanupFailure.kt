package dev.dmkr.screencaptureengine.internal.runtime

internal class CleanupFailureCollector {
    private var failure: Throwable? = null

    fun collect(block: () -> Unit) {
        try {
            block()
        } catch (cause: Throwable) {
            val existingFailure = failure
            if (existingFailure == null) {
                failure = cause
            } else {
                existingFailure.addSuppressed(cause)
            }
        }
    }

    fun failureOrNull(): Throwable? = failure

    fun throwIfAny() {
        failure?.let { throw it }
    }
}
