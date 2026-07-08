package dev.dmkr.screencaptureengine.internal.platform.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaProjectionCallbackAdapterTest {
    @Test
    fun callback_resizeAndVisibilityBeforeStopAreDeliveredInOrder() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        MediaProjectionCallbackAdapter(listener, listenerExecutor = executor).use { adapter ->
            adapter.callback.onCapturedContentResize(100, 200)
            adapter.callback.onCapturedContentVisibilityChanged(false)
            adapter.callback.onCapturedContentResize(300, 400)
            adapter.callback.onCapturedContentVisibilityChanged(true)

            assertEquals(4, executor.drain())
            adapter.callback.onStop()
            assertEquals(1, executor.drain())
            assertEquals(0, executor.drain())
            assertEquals(
                listOf(
                    CallbackEvent.Resize(width = 100, height = 200),
                    CallbackEvent.Visibility(isVisible = false),
                    CallbackEvent.Resize(width = 300, height = 400),
                    CallbackEvent.Visibility(isVisible = true),
                    CallbackEvent.Stop,
                ),
                listener.events(),
            )
        }
    }

    @Test
    fun callback_onStopIsDeliveredOnce() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        MediaProjectionCallbackAdapter(listener, listenerExecutor = executor).use { adapter ->
            adapter.callback.onStop()
            adapter.callback.onStop()

            executor.drain()
            assertEquals(0, executor.drain())
            assertEquals(listOf(CallbackEvent.Stop), listener.events())
        }
    }

    @Test
    fun callback_onStopMarksProjectionStopBeforeListenerDrain() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        val rawEvents = CopyOnWriteArrayList<ProjectionCallbackRawEvent>()
        MediaProjectionCallbackAdapter(
            listener = listener,
            synchronousEventObserver = rawEvents::add,
            listenerExecutor = executor,
        ).use { adapter ->
            adapter.callback.onStop()

            assertTrue(adapter.projectionStopObserved)
            assertEquals(listOf(ProjectionCallbackRawEvent.Stop), rawEvents.toList())
            assertEquals(emptyList<CallbackEvent>(), listener.events())

            assertEquals(1, executor.drain())
            assertEquals(listOf(CallbackEvent.Stop), listener.events())
        }
    }

    @Test
    fun callback_onStopMarksProjectionStopBeforeBlockedListenerSubmissionReturns() {
        val listener = RecordingListener()
        val executor = BlockingExecuteExecutorService()
        val callbackEnteredExecutor = CountDownLatch(1)
        val releaseExecutor = CountDownLatch(1)
        executor.onExecuteEntered = { callbackEnteredExecutor.countDown() }
        executor.awaitExecuteRelease = {
            assertTrue(releaseExecutor.await(2, TimeUnit.SECONDS))
        }
        MediaProjectionCallbackAdapter(listener, listenerExecutor = executor).use { adapter ->
            val callbackThread = Thread { adapter.callback.onStop() }.apply { start() }

            assertTrue(callbackEnteredExecutor.await(2, TimeUnit.SECONDS))
            assertTrue(adapter.projectionStopObserved)
            assertEquals(emptyList<CallbackEvent>(), listener.events())

            releaseExecutor.countDown()
            callbackThread.joinOrFail("projection stop callback")
            executor.drain()

            assertEquals(listOf(CallbackEvent.Stop), listener.events())
        }
    }

    @Test
    fun arbiterRawStopMarkWaitsForPublicOutcomeGate() {
        val arbiter = ProjectionStopArbiter()
        val publicOutcomeEntered = CountDownLatch(1)
        val allowPublicOutcomeToReturn = CountDownLatch(1)
        val rawMarkReturned = CountDownLatch(1)

        val publicOutcomeThread = Thread {
            arbiter.arbitratePublicOutcome { rawStopObserved ->
                assertEquals(false, rawStopObserved)
                publicOutcomeEntered.countDown()
                assertTrue(allowPublicOutcomeToReturn.await(2, TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(publicOutcomeEntered.await(2, TimeUnit.SECONDS))

        val rawMarkThread = Thread {
            arbiter.markRawStopObserved()
            rawMarkReturned.countDown()
        }.apply { start() }

        assertEquals(1L, rawMarkReturned.count)
        allowPublicOutcomeToReturn.countDown()
        assertTrue(rawMarkReturned.await(2, TimeUnit.SECONDS))
        publicOutcomeThread.joinOrFail("public outcome arbitration")
        rawMarkThread.joinOrFail("raw stop mark")
        assertTrue(arbiter.projectionStopObserved)
    }

    @Test
    fun callback_resizeAndVisibilityAfterStopAreIgnored() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        MediaProjectionCallbackAdapter(listener, listenerExecutor = executor).use { adapter ->
            adapter.callback.onStop()
            adapter.callback.onCapturedContentResize(100, 200)
            adapter.callback.onCapturedContentVisibilityChanged(false)

            executor.drain()
            assertEquals(0, executor.drain())
            assertEquals(listOf(CallbackEvent.Stop), listener.events())
        }
    }

    @Test
    fun callback_queuedResizeAndVisibilityAreSuppressedWhenStopWinsBeforeListenerExecution() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        MediaProjectionCallbackAdapter(listener, listenerExecutor = executor).use { adapter ->
            adapter.callback.onCapturedContentResize(10, 20)
            adapter.callback.onCapturedContentResize(30, 40)
            adapter.callback.onCapturedContentVisibilityChanged(false)
            adapter.callback.onStop()

            executor.drain()
            assertEquals(0, executor.drain())
            assertEquals(listOf(CallbackEvent.Stop), listener.events())
        }
    }

    @Test
    fun close_isIdempotentAndIgnoresLaterCallbacks() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        val adapter = MediaProjectionCallbackAdapter(listener, listenerExecutor = executor)

        adapter.close()
        adapter.close()

        adapter.callback.onStop()
        adapter.callback.onCapturedContentResize(100, 200)
        adapter.callback.onCapturedContentVisibilityChanged(true)

        assertEquals(0, executor.drain())
        assertEquals(emptyList<CallbackEvent>(), listener.events())
    }

    @Test
    fun close_ignoresLaterCallbacksBeforeSynchronousObserver() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        val rawEvents = CopyOnWriteArrayList<ProjectionCallbackRawEvent>()
        val adapter = MediaProjectionCallbackAdapter(
            listener = listener,
            synchronousEventObserver = rawEvents::add,
            listenerExecutor = executor,
        )

        adapter.close()

        adapter.callback.onStop()
        adapter.callback.onCapturedContentResize(100, 200)
        adapter.callback.onCapturedContentVisibilityChanged(true)

        assertEquals(0, executor.drain())
        assertEquals(emptyList<ProjectionCallbackRawEvent>(), rawEvents.toList())
        assertEquals(emptyList<CallbackEvent>(), listener.events())
    }

    @Test
    fun callback_synchronousObserverDoesNotBlockClose() {
        val listener = RecordingListener()
        val executor = DeterministicExecutorService()
        val observerStarted = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val callbackFailure = AtomicReference<Throwable?>()
        val adapter = MediaProjectionCallbackAdapter(
            listener = listener,
            synchronousEventObserver = {
                observerStarted.countDown()
                check(releaseObserver.await(2, TimeUnit.SECONDS)) {
                    "Timed out waiting to release synchronous observer."
                }
            },
            listenerExecutor = executor,
        )
        val callbackThread = Thread {
            runCatching { adapter.callback.onStop() }.onFailure(callbackFailure::set)
        }
        val closeThread = Thread {
            runCatching { adapter.close() }.onFailure(closeFailure::set)
            closeReturned.countDown()
        }

        callbackThread.start()
        assertTrue(observerStarted.await(2, TimeUnit.SECONDS))
        closeThread.start()
        assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
        releaseObserver.countDown()
        callbackThread.joinOrFail("projection stop callback")
        closeThread.joinOrFail("adapter close")

        assertNull(callbackFailure.get())
        assertNull(closeFailure.get())
        assertEquals(0, executor.drain())
        assertEquals(emptyList<CallbackEvent>(), listener.events())
    }

    private open class RecordingListener : MediaProjectionCallbackAdapter.Listener {
        private val events = CopyOnWriteArrayList<CallbackEvent>()

        override fun onProjectionStopped() {
            events += CallbackEvent.Stop
        }

        override fun onCapturedContentResized(width: Int, height: Int) {
            events += CallbackEvent.Resize(width = width, height = height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            events += CallbackEvent.Visibility(isVisible)
        }

        fun events(): List<CallbackEvent> = events.toList()
    }

    private class DeterministicExecutorService : AbstractExecutorService() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        @Volatile
        private var isShutdown = false

        override fun execute(command: Runnable) {
            if (isShutdown) throw RejectedExecutionException("Executor is shut down.")
            tasks += command
        }

        override fun shutdown() {
            isShutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            isShutdown = true
            val remaining = mutableListOf<Runnable>()
            while (true) {
                remaining += tasks.poll() ?: break
            }
            return remaining
        }

        override fun isShutdown(): Boolean = isShutdown

        override fun isTerminated(): Boolean = isShutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        fun drain(): Int {
            var count = 0
            while (true) {
                val task = tasks.poll() ?: return count
                task.run()
                count++
            }
        }
    }

    private class BlockingExecuteExecutorService : AbstractExecutorService() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        @Volatile
        private var isShutdown = false
        var onExecuteEntered: (() -> Unit)? = null
        var awaitExecuteRelease: (() -> Unit)? = null

        override fun execute(command: Runnable) {
            if (isShutdown) throw RejectedExecutionException("Executor is shut down.")
            onExecuteEntered?.invoke()
            awaitExecuteRelease?.invoke()
            tasks += command
        }

        override fun shutdown() {
            isShutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            isShutdown = true
            val remaining = mutableListOf<Runnable>()
            while (true) {
                remaining += tasks.poll() ?: break
            }
            return remaining
        }

        override fun isShutdown(): Boolean = isShutdown

        override fun isTerminated(): Boolean = isShutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        fun drain(): Int {
            var count = 0
            while (true) {
                val task = tasks.poll() ?: return count
                task.run()
                count++
            }
        }
    }

    private sealed interface CallbackEvent {
        data object Stop : CallbackEvent
        data class Resize(val width: Int, val height: Int) : CallbackEvent
        data class Visibility(val isVisible: Boolean) : CallbackEvent
    }

    private fun Thread.joinOrFail(description: String) {
        join(2_000L)
        assertFalse("$description thread did not finish", isAlive)
    }
}
