package dev.dmkr.screencaptureengine.internal.runtime

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Owns the GL execution lane and its current EGL context.
 *
 * This owner deliberately does not own projection targets, OES texture lifetimes, rendering
 * programs, framebuffers, readback buffers, or encoder resources. It only provides the serialized
 * GL boundary needed by those owners.
 */
internal class GlLaneContextOwner internal constructor(
    threadName: String,
    private val glRetirementFailureSink: (Throwable) -> Unit = ::reportGlRetirementFailure,
) : ProjectionTargetGlLane, GlLaneAbandonment, GlResourceRetirementLane {
    private val handlerThread = HandlerThread(threadName, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = Scope()
    private val abandoned = AtomicBoolean()
    private val closeStarted = AtomicBoolean()
    private val lifecycleLock = Any()
    private val waiterLock = Any()
    private val abandonWaiters = LinkedHashSet<GlLaneAbandonWaiter>()
    private var eglState: EglState? = null

    @Volatile
    private var suspendedCallPostObserverForTest: (() -> Unit)? = null

    @Volatile
    private var glRetirementRunnerForTest: ((String, GlLaneScope.() -> Unit) -> Unit)? = null

    @Volatile
    private var isClosed = false

    override val isGlLaneAbandoned: Boolean
        get() = abandoned.get()

    override fun isOnGlThread(): Boolean =
        Thread.currentThread() == handlerThread.looper?.thread

    override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (!isAcceptingRetirementWork()) {
                false
            } else {
                handler.post {
                    if (!canRunAcceptedRetirementWork()) return@post
                    runGlRetirement(label = label, block = block)
                }
            }
        }

    @BlockingProjectionTargetGlAccess
    override fun <T> executeCurrentBlocking(block: GlLaneScope.() -> T): T {
        checkAcceptingWork()
        return runOnGlThread {
            checkAcceptingWork()
            ensureEglOnGlThread()
            block(scope)
        }
    }

    override suspend fun <T> executeCurrent(
        onCancellation: (T) -> Unit,
        block: GlLaneScope.() -> T,
    ): T {
        checkAcceptingWork()
        if (isOnGlThread()) {
            checkAcceptingWork()
            ensureEglOnGlThread()
            return block(scope)
        }

        return suspendCancellableCoroutine { continuation ->
            val call = SuspendedGlCall(continuation = continuation, onCancellation = onCancellation)
            continuation.invokeOnCancellation { call.cancel() }
            if (!call.isPending()) return@suspendCancellableCoroutine
            if (!registerAbandonWaiter(call)) {
                call.completeWithException(glLaneNotAcceptingException())
                return@suspendCancellableCoroutine
            }
            call.markRegistered()
            val posted = handler.post {
                if (!call.isPending()) return@post
                try {
                    checkAcceptingWork()
                    ensureEglOnGlThread()
                    call.complete(block(scope))
                } catch (cause: Throwable) {
                    call.completeWithException(cause)
                }
            }
            if (posted) {
                suspendedCallPostObserverForTest?.invoke()
            } else {
                call.completeWithException(glLaneNotAcceptingException())
            }
        }
    }

    @Suppress("unused")
    internal fun setSuspendedCallPostObserverForTest(observer: (() -> Unit)?) {
        suspendedCallPostObserverForTest = observer
    }

    @Suppress("unused")
    internal fun setGlRetirementRunnerForTest(runner: ((String, GlLaneScope.() -> Unit) -> Unit)?) {
        glRetirementRunnerForTest = runner
    }

    @Suppress("unused")
    internal fun abandonWaiterCountForTest(): Int =
        synchronized(waiterLock) {
            abandonWaiters.size
        }

    private fun checkAcceptingWork() {
        check(!abandoned.get()) { "GL lane is abandoned." }
        check(!closeStarted.get() && !isClosed) { "GL lane is closed." }
    }

    private fun isAcceptingRetirementWork(): Boolean =
        !abandoned.get() && !closeStarted.get() && !isClosed

    private fun canRunAcceptedRetirementWork(): Boolean =
        !abandoned.get() && !isClosed

    @BlockingProjectionTargetGlAccess
    override fun executeCurrentIfCreatedBlocking(block: GlLaneScope.() -> Unit) {
        if (abandoned.get() || closeStarted.get() || isClosed) return
        runOnGlThread {
            if (abandoned.get() || isClosed) return@runOnGlThread
            val state = eglState ?: return@runOnGlThread
            makeCurrent(state)
            block(scope)
        }
    }

    override fun abandonGlLane() {
        val didAbandon = synchronized(lifecycleLock) {
            if (!abandoned.compareAndSet(false, true)) {
                false
            } else {
                closeStarted.compareAndSet(false, true)
                true
            }
        }
        if (!didAbandon) return
        signalAbandonWaiters()
        handlerThread.quit()
    }

    override fun close() {
        if (abandoned.get()) return
        check(Thread.currentThread() != handlerThread.looper?.thread) {
            "GlLaneContextOwner.close must not be called from its GL thread."
        }
        val didStartClose = synchronized(lifecycleLock) {
            !abandoned.get() && closeStarted.compareAndSet(false, true)
        }
        if (!didStartClose) return
        val cleanupFailures = CleanupFailureCollector()
        try {
            runOnGlThread {
                if (!isClosed) {
                    isClosed = true
                    releaseEglOnGlThread()
                }
            }
        } catch (cause: Throwable) {
            if (!abandoned.get()) {
                cleanupFailures.collect { throw cause }
            }
        }
        if (abandoned.get()) return
        cleanupFailures.collect {
            handlerThread.quitSafely()
            joinGlThreadUnlessAbandoned()
        }
        if (abandoned.get()) return
        cleanupFailures.throwIfAny()
    }

    private fun ensureEglOnGlThread() {
        eglState?.let { state ->
            makeCurrent(state)
            return
        }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned EGL_NO_DISPLAY." }
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throwEglFailure("eglInitialize")
            }
            initialized = true
            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                throwEglFailure("eglBindAPI")
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val eglConfigAvailable = EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0,
            )
            if (!eglConfigAvailable || (configCount[0] <= 0)) {
                throwEglFailure("eglChooseConfig")
            }
            val config = checkNotNull(configs[0]) { "eglChooseConfig returned a null config." }

            val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                throwEglFailure("eglCreateContext")
            }

            val surfaceAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                throwEglFailure("eglCreatePbufferSurface")
            }

            val state = EglState(display = display, context = context, surface = surface)
            makeCurrent(state)
            eglState = state
        } catch (cause: Throwable) {
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            if (initialized) {
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            throw cause
        }
    }

    private fun queryTargetSizeLimits(): ProjectionTargetSizeLimits {
        val textureSize = IntArray(1)
        val viewportDims = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewportDims, 0)
        scope.checkGl("query GL target size limits")
        val maxWidth = min(textureSize[0], viewportDims[0])
        val maxHeight = min(textureSize[0], viewportDims[1])
        check((maxWidth > 0) && (maxHeight > 0)) { "GL returned invalid target size limits." }
        return ProjectionTargetSizeLimits(maxWidth = maxWidth, maxHeight = maxHeight)
    }

    private fun releaseEglOnGlThread() {
        val state = eglState ?: return
        EGL14.eglMakeCurrent(state.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(state.display, state.surface)
        EGL14.eglDestroyContext(state.display, state.context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(state.display)
        eglState = null
    }

    private fun runGlRetirement(label: String, block: GlLaneScope.() -> Unit) {
        val testRunner = glRetirementRunnerForTest
        val failure = if (testRunner != null) {
            runCatching {
                testRunner(label, block)
            }.exceptionOrNull()
        } else {
            runCatching {
                ensureEglOnGlThread()
                scope.checkCurrentContext("retire GL resources for $label")
                block(scope)
            }.exceptionOrNull()
        }

        if (failure != null) {
            val reported = IllegalStateException("GL retirement failed for $label.", failure)
            runCatching { glRetirementFailureSink(reported) }
                .onFailure { reportFailure ->
                    handleGlRetirementFailureSinkFailure(reported, reportFailure)
                }
        }
    }

    private fun makeCurrent(state: EglState) {
        if (!EGL14.eglMakeCurrent(state.display, state.surface, state.surface, state.context)) {
            throwEglFailure("eglMakeCurrent")
        }
    }

    private fun <T> runOnGlThread(block: () -> T): T {
        if (Thread.currentThread() == handlerThread.looper?.thread) {
            return block()
        }

        val call = BlockingGlCall<T>()
        if (!registerAbandonWaiter(call)) {
            throw glLaneNotAcceptingException()
        }
        if (!handler.post {
                try {
                    call.complete(block())
                } catch (cause: Throwable) {
                    call.completeWithException(cause)
                }
            }) {
            unregisterAbandonWaiter(call)
            throw glLaneNotAcceptingException()
        }
        try {
            return call.await()
        } finally {
            unregisterAbandonWaiter(call)
        }
    }

    private fun registerAbandonWaiter(waiter: GlLaneAbandonWaiter): Boolean =
        synchronized(waiterLock) {
            if (abandoned.get()) {
                false
            } else {
                abandonWaiters.add(waiter)
                true
            }
        }

    private fun unregisterAbandonWaiter(waiter: GlLaneAbandonWaiter) {
        synchronized(waiterLock) {
            abandonWaiters.remove(waiter)
        }
    }

    private fun signalAbandonWaiters() {
        val waiters = synchronized(waiterLock) {
            abandonWaiters.toList().also { abandonWaiters.clear() }
        }
        waiters.forEach(GlLaneAbandonWaiter::abandon)
    }

    private fun joinGlThreadUnlessAbandoned() {
        while (!abandoned.get() && handlerThread.isAlive) {
            try {
                handlerThread.join(GL_THREAD_JOIN_POLL_MS)
            } catch (cause: InterruptedException) {
                Thread.currentThread().interrupt()
                if (abandoned.get()) return
                throw IllegalStateException("Interrupted while waiting for GL thread.", cause)
            }
        }
    }

    private fun glLaneNotAcceptingException(): IllegalStateException =
        if (abandoned.get()) {
            IllegalStateException("GL lane is abandoned.")
        } else {
            IllegalStateException("GL thread is not accepting work.")
        }

    private fun throwEglFailure(operation: String): Nothing {
        throw IllegalStateException("$operation failed with EGL error 0x${Integer.toHexString(EGL14.eglGetError())}.")
    }

    private data class EglState(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface,
    )

    private interface GlLaneAbandonWaiter {
        fun abandon()
    }

    private inner class BlockingGlCall<T> : GlLaneAbandonWaiter {
        private val lock = ReentrantLock()
        private val completedOrAbandoned = lock.newCondition()
        private var completed = false
        private var abandoned = false
        private var value: Any? = null
        private var failure: Throwable? = null

        fun complete(result: T) {
            lock.withLock {
                if (completed || abandoned) return
                completed = true
                value = result ?: NULL_RESULT
                completedOrAbandoned.signalAll()
            }
        }

        fun completeWithException(cause: Throwable) {
            lock.withLock {
                if (completed || abandoned) return
                completed = true
                failure = cause
                completedOrAbandoned.signalAll()
            }
        }

        override fun abandon() {
            lock.withLock {
                if (completed || abandoned) return
                abandoned = true
                completedOrAbandoned.signalAll()
            }
        }

        fun await(): T {
            lock.withLock {
                while (!completed && !abandoned) {
                    try {
                        completedOrAbandoned.await()
                    } catch (cause: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("Interrupted while waiting for GL thread.", cause)
                    }
                }
                if (abandoned && !completed) {
                    throw glLaneNotAcceptingException()
                }
                failure?.let { cause -> throw cause }
                @Suppress("UNCHECKED_CAST")
                return if (value === NULL_RESULT) null as T else value as T
            }
        }
    }

    private inner class SuspendedGlCall<T>(
        private val continuation: CancellableContinuation<T>,
        private val onCancellation: (T) -> Unit,
    ) : GlLaneAbandonWaiter {
        private val completedOrCancelled = AtomicBoolean()
        private val registered = AtomicBoolean()

        fun markRegistered() {
            registered.set(true)
            if (completedOrCancelled.get()) {
                unregisterAbandonWaiter(this)
            }
        }

        fun isPending(): Boolean =
            !completedOrCancelled.get()

        fun complete(result: T) {
            if (!completedOrCancelled.compareAndSet(false, true)) {
                runCatching { onCancellation(result) }
                return
            }
            unregisterIfRegistered()
            continuation.resume(result) { _, rejectedResult, _ ->
                runCatching { onCancellation(rejectedResult) }
            }
        }

        fun completeWithException(cause: Throwable) {
            if (!completedOrCancelled.compareAndSet(false, true)) return
            unregisterIfRegistered()
            continuation.resumeWithException(cause)
        }

        fun cancel() {
            if (!completedOrCancelled.compareAndSet(false, true)) return
            unregisterIfRegistered()
        }

        override fun abandon() {
            completeWithException(glLaneNotAcceptingException())
        }

        private fun unregisterIfRegistered() {
            if (registered.get()) {
                unregisterAbandonWaiter(this)
            }
        }
    }

    private inner class Scope : GlLaneScope {
        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            queryTargetSizeLimits()

        override fun checkCurrentContext(operation: String) {
            check(isOnGlThread()) { "$operation must run on the GL lane." }
            val state = checkNotNull(eglState) { "$operation requires an EGL context." }
            check(EGL14.eglGetCurrentContext() == state.context) { "$operation requires the owning EGL context." }
            check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == state.surface) {
                "$operation requires the owning EGL draw surface."
            }
            check(EGL14.eglGetCurrentSurface(EGL14.EGL_READ) == state.surface) {
                "$operation requires the owning EGL read surface."
            }
        }

        override fun checkGl(operation: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) {
                "$operation failed with GL error 0x${Integer.toHexString(error)}."
            }
        }
    }

    private companion object {
        private const val GL_THREAD_JOIN_POLL_MS: Long = 50L
        private val NULL_RESULT = Any()
    }
}

private fun reportGlRetirementFailure(failure: Throwable) {
    val currentThread = Thread.currentThread()
    val handler = currentThread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler()
    if (handler != null) {
        handler.uncaughtException(currentThread, failure)
    } else {
        failure.printStackTrace()
    }
}

private fun handleGlRetirementFailureSinkFailure(reported: Throwable, reportFailure: Throwable) {
    if (reported !== reportFailure) {
        runCatching { reported.addSuppressed(reportFailure) }
    }
    runCatching { reported.printStackTrace() }
}

internal interface ProjectionTargetGlLane : AutoCloseable {
    fun isOnGlThread(): Boolean

    @BlockingProjectionTargetGlAccess
    fun <T> executeCurrentBlocking(block: GlLaneScope.() -> T): T

    suspend fun <T> executeCurrent(
        onCancellation: (T) -> Unit = {},
        block: GlLaneScope.() -> T,
    ): T

    @BlockingProjectionTargetGlAccess
    fun executeCurrentIfCreatedBlocking(block: GlLaneScope.() -> Unit)
}

internal interface GlLaneAbandonment {
    val isGlLaneAbandoned: Boolean

    fun abandonGlLane()
}

/**
 * Schedules non-blocking GL resource deletion on a healthy current GL lane.
 *
 * Returning `true` means the cleanup was accepted and will run before ordinary lane close completes,
 * unless the lane is explicitly abandoned. Returning `false` means callers must treat the resource
 * bag as abandoned with the lane and must not run GL deletion inline.
 */
internal fun interface GlResourceRetirementLane {
    fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean
}

internal interface GlLaneScope {
    fun targetSizeLimits(): ProjectionTargetSizeLimits

    fun checkCurrentContext(operation: String)

    fun checkGl(operation: String)
}

@RequiresOptIn(
    message = "Blocking GL execution is legacy projection-target startup debt. New GL code must use suspendable GL access.",
    level = RequiresOptIn.Level.ERROR,
)
internal annotation class BlockingProjectionTargetGlAccess
