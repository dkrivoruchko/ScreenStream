package io.screenstream.capture.internal.capture

import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureProblem
import kotlinx.coroutines.CompletableDeferred

/**
 * One session's projection-stop result, shared across the Bootstrap-to-Capture ownership transfer.
 * The owner records its attempt before notification, which can resume waiters inline and must run outside its gates.
 * Dispatch failure stays final even if retained work retires later. Waiter cancellation leaves this result intact.
 */
internal class ProjectionStopCompletion {
    private val completion = CompletableDeferred<Unit>()

    internal suspend fun awaitCompletion(): Unit = completion.await()

    internal fun returned() {
        completion.complete(Unit)
    }

    internal fun failed(failure: Exception) {
        completion.completeExceptionally(ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, failure))
    }
}
