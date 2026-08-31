package io.screenstream.capture.testutil

import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import java.util.ArrayDeque

internal class QueuedNonInlineDispatcher : NonInlineDispatcher {
    private val gate: Any = Any()
    private val tasks: ArrayDeque<Runnable> = ArrayDeque()

    override fun tryDispatch(task: Runnable): Boolean {
        synchronized(gate) {
            tasks.addLast(task)
        }
        return true
    }

    internal fun runNext() {
        val task = synchronized(gate) {
            check(tasks.isNotEmpty()) { "No accepted task is pending" }
            tasks.removeFirst()
        }
        task.run()
    }

    internal fun drain() {
        while (true) {
            val task = synchronized(gate) {
                if (tasks.isEmpty()) null else tasks.removeFirst()
            } ?: return
            task.run()
        }
    }

    internal fun pendingCount(): Int = synchronized(gate) { tasks.size }
}
