package info.dvkr.screenstream.data.httpserver


import io.netty.util.concurrent.FastThreadLocal
import io.netty.util.concurrent.FastThreadLocalThread
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal class HttpServerThreadFactory private constructor(
    poolName: String,
    private val daemon: Boolean,
    private val priority: Int,
    private val threadGroup: ThreadGroup
) : ThreadFactory {

    private val poolId = AtomicInteger()
    private val nextId = AtomicInteger()
    private val prefix: String

    init {
        require(priority in Thread.MIN_PRIORITY..Thread.MAX_PRIORITY) {
            "Priority ($priority) out of range: ${Thread.MIN_PRIORITY}..${Thread.MAX_PRIORITY}"
        }

        prefix = poolName + '-' + poolId.incrementAndGet() + '-'
    }

    internal constructor(poolName: String, daemon: Boolean, priority: Int) :
            this(poolName, daemon, priority, Thread.currentThread().threadGroup!!)

    override fun newThread(r: Runnable): Thread {
        val t = newThread(DefaultRunnableDecorator(r), prefix + nextId.incrementAndGet())
        try {
            if (t.isDaemon) {
                if (!daemon) t.isDaemon = false
            } else {
                if (daemon) t.isDaemon = true
            }

            if (t.priority != priority) {
                t.priority = priority
            }
        } catch (ignored: Exception) {
            // Doesn't matter even if failed to set.
        }
        return t
    }

    private fun newThread(r: Runnable, name: String): Thread = FastThreadLocalThread(threadGroup, r, name)

    private class DefaultRunnableDecorator constructor(private val r: Runnable) : Runnable {
        override fun run() {
            try {
                r.run()
            } catch (ignored: CancellationException) {
            } finally {
                FastThreadLocal.removeAll()
            }
        }
    }
}