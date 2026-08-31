package io.screenstream.capture.internal.session

import android.os.Handler
import android.os.HandlerThread
import io.screenstream.capture.internal.runtime.HandlerTaskPoster

/**
 * Owns the control-thread mechanism used to enter coordinator turns.
 *
 * Posting acceptance proves only that Android accepted the task; it does not prove task entry or
 * progress. This class therefore carries no Session semantic authority: [SessionCoordinator] is the sole cross-owner,
 * Link-correlation, publication, and terminal arbiter, while Lifecycle, Topology, Production, and Session Delivery
 * retain their exclusive semantic state. A successful [requestQuit] is a quit request rather than a thread-termination
 * receipt.
 */
internal class SessionControlExecutor(
    private val coordinator: SessionCoordinator,
    private val controlThread: HandlerThread,
    private val controlHandler: Handler,
    private val handlerTaskPoster: HandlerTaskPoster,
) {
    internal fun enterTurn() {
        try {
            coordinator.enterControlTurn(this)
        } catch (failure: Exception) {
            if (!coordinator.onControlTurnFailure(this, failure)) throw failure
        }
        coordinator.finishControlTurn(this)
    }

    internal fun post(task: Runnable): Boolean = handlerTaskPoster.post(controlHandler, task)

    internal fun postDelayed(task: Runnable, delayMillis: Long): Boolean =
        handlerTaskPoster.postDelayed(controlHandler, task, delayMillis)

    internal fun removeCallbacks(task: Runnable) {
        handlerTaskPoster.removeCallbacks(controlHandler, task)
    }

    internal fun requestQuit(): Boolean = controlThread.quitSafely()
}
