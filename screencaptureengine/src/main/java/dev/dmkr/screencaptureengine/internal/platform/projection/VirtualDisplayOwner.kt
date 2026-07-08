package dev.dmkr.screencaptureengine.internal.platform.projection

import android.hardware.display.VirtualDisplay
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the single VirtualDisplay and its currently bound projection target surface.
 *
 * Retargeting is transactional from the caller's perspective: resize is attempted before
 * `setSurface`. If resize succeeds but `setSurface` fails, the display target state is no longer
 * safe to keep using, so this owner closes itself, releases the VirtualDisplay, and rethrows the
 * original failure with cleanup failures suppressed. Candidate target retirement remains with the
 * caller-owned transaction bag.
 */
internal class VirtualDisplayOwner private constructor(
    private val virtualDisplay: VirtualDisplay,
) : ProjectionVirtualDisplayOwner {
    private val lock = ReentrantLock()
    private val closed = AtomicBoolean()
    private var currentTarget: ProjectionTargetHandle? = null

    override val isClosed: Boolean
        get() = closed.get()

    internal fun attachInitialTarget(target: ProjectionTargetHandle) {
        lock.withLock {
            check(!closed.get()) { "VirtualDisplayOwner is closed." }
            check(currentTarget == null) { "Initial projection target is already attached." }
            currentTarget = target
        }
    }

    override fun currentTargetSnapshot(): ProjectionTargetSnapshot? =
        lock.withLock {
            currentTarget?.let { target ->
                ProjectionTargetSnapshot(
                    generation = target.generation,
                    width = target.width,
                    height = target.height,
                    densityDpi = target.densityDpi,
                    surface = target.surface,
                )
            }
        }

    override fun bindTarget(target: ProjectionTargetHandle): ProjectionTargetHandle? =
        bindTargetTransactionally(target)

    private fun bindTargetTransactionally(target: ProjectionTargetHandle): ProjectionTargetHandle? {
        var failure: Throwable? = null
        var shouldCloseOwner = false
        val previous = lock.withLock {
            check(!closed.get()) { "VirtualDisplayOwner is closed." }
            val previous = currentTarget
            var resizeSucceeded = false
            try {
                virtualDisplay.resize(target.width, target.height, target.densityDpi)
                resizeSucceeded = true
                virtualDisplay.surface = target.surface.androidSurface()
                currentTarget = target
            } catch (cause: Throwable) {
                failure = cause
                if (resizeSucceeded && closed.compareAndSet(false, true)) {
                    currentTarget = null
                    shouldCloseOwner = true
                }
            }
            previous
        }
        failure?.let { cause ->
            if (shouldCloseOwner) {
                closeReleasedResources()?.let(cause::addSuppressed)
            }
            throw cause
        }
        return previous
    }

    override fun close() {
        lock.withLock {
            if (!closed.compareAndSet(false, true)) return
            currentTarget = null
        }
        closeReleasedResources()?.let { failure -> throw failure }
    }

    private fun closeReleasedResources(): Throwable? {
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collect { virtualDisplay.surface = null }
        cleanupFailures.collect { virtualDisplay.release() }
        return cleanupFailures.failureOrNull()
    }

    internal companion object {
        internal fun create(
            projection: ProjectionHandle,
            name: String,
            target: ProjectionTargetHandle,
            callback: VirtualDisplay.Callback? = null,
            callbackHandler: ProjectionCallbackHandlerHandle? = null,
        ): VirtualDisplayOwner {
            require(name.isNotBlank()) { "name must not be blank" }
            val virtualDisplay = projection.createVirtualDisplay(
                name,
                target.width,
                target.height,
                target.densityDpi,
                target.surface,
                callback,
                callbackHandler,
            ) ?: throw IllegalStateException("MediaProjection.createVirtualDisplay returned null.")
            return VirtualDisplayOwner(virtualDisplay).also { owner -> owner.attachInitialTarget(target) }
        }
    }
}
