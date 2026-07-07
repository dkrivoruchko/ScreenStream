package dev.dmkr.screencaptureengine.internal.runtime

import android.graphics.SurfaceTexture
import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectionTargetGlCapabilityTest {
    @Test
    fun targetGlCapabilityRejectsTargetFromDifferentOwner() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("test-gl-owner-a"))
        val otherOwner = ProjectionTargetOwner(TestGlLane("test-gl-owner-b"))
        val target = TestProjectionTarget(owner = owner, generation = 1L)

        try {
            val failure = expectIllegalState {
                otherOwner.withCurrentProjectionTarget(target = target.handle, generation = 1L) {
                    throw AssertionError("GL access should not be granted.")
                }
            }

            assertEquals("Projection target is owned by a different ProjectionTargetOwner.", failure.message)
        } finally {
            target.close()
            owner.close()
            otherOwner.close()
        }
    }

    @Test
    fun targetGlCapabilityRejectsGenerationMismatchAndClosedTarget() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("test-gl-owner"))
        val target = TestProjectionTarget(owner = owner, generation = 7L)

        try {
            val generationFailure = expectIllegalState {
                owner.withCurrentProjectionTarget(target = target.handle, generation = 8L) {
                    throw AssertionError("GL access should not be granted.")
                }
            }
            target.handle.markClosedForOwner()
            val closedFailure = expectIllegalState {
                owner.withCurrentProjectionTarget(target = target.handle, generation = 7L) {
                    throw AssertionError("GL access should not be granted.")
                }
            }

            assertEquals("Projection target generation mismatch. Expected 8, was 7.", generationFailure.message)
            assertEquals("Projection target generation 7 is closed.", closedFailure.message)
        } finally {
            target.close()
            owner.close()
        }
    }

    @Test
    fun targetGlCapabilityDispatchesToGlLaneAndInvalidatesRetainedScope() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("test-gl-owner"))
        val target = TestProjectionTarget(owner = owner, generation = 3L)
        var glThreadName = ""
        var retainedScope: ProjectionTargetGlScope? = null
        var offLaneFailure: Throwable? = null

        try {
            owner.withCurrentProjectionTarget(target = target.handle, generation = 3L) {
                glThreadName = Thread.currentThread().name
                assertEquals(3L, generation)
                assertEquals(100, width)
                assertEquals(200, height)
                assertEquals(320, densityDpi)

                val latch = CountDownLatch(1)
                Thread {
                    offLaneFailure = runCatching { width }.exceptionOrNull()
                    latch.countDown()
                }.start()
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                retainedScope = this
            }

            val retainedFailure = assertThrows(IllegalStateException::class.java) {
                retainedScope?.height
            }

            assertTrue(glThreadName.contains("test-gl-owner"))
            assertTrue(offLaneFailure is IllegalStateException)
            assertEquals("Projection target GL access is no longer active.", retainedFailure.message)
        } finally {
            target.close()
            owner.close()
        }
    }

    @Test
    fun startupRenderingGlAccessExposesScopedTargetGlRetirementAndAbandonment() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("test-startup-rendering-gl"))
        val target = TestProjectionTarget(owner = owner, generation = 4L)
        val retirementLatch = CountDownLatch(1)
        var glThreadName = ""
        var retirementThreadName = ""
        var retainedScope: StartupRenderingGlScope? = null
        var retainedGl: GlLaneScope? = null

        try {
            val result = owner.withCurrentStartupRenderingTarget(target = target.handle, generation = 4L) {
                glThreadName = Thread.currentThread().name
                gl.checkCurrentContext("startup rendering GL access")
                assertEquals(4L, projectionTarget.generation)
                assertEquals(100, projectionTarget.width)
                assertEquals(200, projectionTarget.height)
                assertEquals(320, projectionTarget.densityDpi)
                assertFalse(abandonment.isGlLaneAbandoned)

                assertTrue(
                    retirementLane.retireGlResources("startup-rendering-test") {
                        retirementThreadName = Thread.currentThread().name
                        checkCurrentContext("startup rendering retirement")
                        retirementLatch.countDown()
                    },
                )
                retainedScope = this
                retainedGl = gl
                "ready"
            }

            val retainedScopeFailure = assertThrows(IllegalStateException::class.java) {
                retainedScope?.projectionTarget?.width
            }
            val retainedGlFailure = assertThrows(IllegalStateException::class.java) {
                retainedGl?.targetSizeLimits()
            }

            assertEquals("ready", result)
            assertTrue(retirementLatch.await(5, TimeUnit.SECONDS))
            assertTrue(glThreadName.contains("test-startup-rendering-gl"))
            assertEquals(glThreadName, retirementThreadName)
            assertEquals("Startup rendering GL access is no longer active.", retainedScopeFailure.message)
            assertEquals("Startup rendering GL access is no longer active.", retainedGlFailure.message)

            owner.abandonGlLane()

            assertTrue(owner.isGlLaneAbandoned)
        } finally {
            target.close()
            owner.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startupRenderingGlAccessCleansReturnedResourceWhenCallerCancelsBeforeDelivery() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("test-startup-rendering-gl-cancel"))
        val target = TestProjectionTarget(owner = owner, generation = 5L)
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val cleanupCount = AtomicInteger()

        try {
            val pending = launch {
                owner.withCurrentStartupRenderingTarget(
                    target = target.handle,
                    generation = 5L,
                    onCancellation = { resource: CloseCountingResource -> resource.close() },
                ) {
                    blockEntered.countDown()
                    releaseBlock.await(5, TimeUnit.SECONDS)
                    CloseCountingResource(cleanupCount)
                }
            }
            runCurrent()

            assertTrue(blockEntered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            releaseBlock.countDown()
            pending.join()

            assertEquals(1, cleanupCount.get())
        } finally {
            releaseBlock.countDown()
            target.close()
            owner.close()
        }
    }

    @Test
    fun projectionTargetGlScopeDoesNotExposeRawSurfaceTextureOrSurface() {
        val declaredMethods = ProjectionTargetGlScope::class.java.methods
            .filter { method -> method.declaringClass == ProjectionTargetGlScope::class.java }

        assertFalse(declaredMethods.any { method -> method.returnType == SurfaceTexture::class.java })
        assertFalse(declaredMethods.any { method -> method.returnType == Surface::class.java })
        assertFalse(declaredMethods.any { method -> method.name.contains("TextureId", ignoreCase = true) })
        assertFalse(declaredMethods.any { method -> method.name.contains("SurfaceTexture", ignoreCase = true) })
    }

    private class TestProjectionTarget(
        owner: ProjectionTargetOwner,
        generation: Long,
    ) : AutoCloseable {
        private val surfaceTexture = SurfaceTexture(0)
        private val surface = Surface(surfaceTexture)

        val handle = ProjectionTargetOwner.ProjectionTarget(
            generation = generation,
            width = 100,
            height = 200,
            densityDpi = 320,
            androidSurface = surface,
            owner = owner,
            surfaceTexture = surfaceTexture,
            textureId = 11,
        )

        override fun close() {
            surface.release()
            surfaceTexture.release()
        }
    }

    private suspend fun expectIllegalState(block: suspend () -> Unit): IllegalStateException =
        try {
            block()
            throw AssertionError("Expected IllegalStateException")
        } catch (exception: IllegalStateException) {
            exception
        }

    private class TestGlLane(
        threadName: String,
    ) : ProjectionTargetGlLane, GlResourceRetirementLane, GlLaneAbandonment {
        private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        private val scope = Scope()

        @Volatile
        private var laneThread: Thread? = null

        @Volatile
        override var isGlLaneAbandoned: Boolean = false
            private set

        private val currentContext = ThreadLocal.withInitial { false }

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun <T> executeCurrentBlocking(block: GlLaneScope.() -> T): T {
            val latch = CountDownLatch(1)
            var value: T? = null
            var failure: Throwable? = null
            executor.execute {
                runCurrent {
                    try {
                        value = block(scope)
                    } catch (cause: Throwable) {
                        failure = cause
                    } finally {
                        latch.countDown()
                    }
                }
            }
            check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for fake GL lane." }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override suspend fun <T> executeCurrent(
            onCancellation: (T) -> Unit,
            block: GlLaneScope.() -> T,
        ): T =
            suspendCancellableCoroutine { continuation ->
                executor.execute {
                    runCurrent {
                        try {
                            val result = block(scope)
                            if (continuation.isActive) {
                                continuation.resume(result) { _, rejectedResult, _ ->
                                    onCancellation(rejectedResult)
                                }
                            } else {
                                onCancellation(result)
                            }
                        } catch (cause: Throwable) {
                            continuation.resumeWithException(cause)
                        }
                    }
                }
            }

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun executeCurrentIfCreatedBlocking(block: GlLaneScope.() -> Unit) {
            executeCurrentBlocking(block)
        }

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            if (isGlLaneAbandoned) return false
            executor.execute {
                runCurrent {
                    block(scope)
                }
            }
            return true
        }

        override fun abandonGlLane() {
            isGlLaneAbandoned = true
            executor.shutdownNow()
        }

        override fun isOnGlThread(): Boolean =
            Thread.currentThread() == laneThread

        override fun close() {
            executor.shutdownNow()
        }

        private fun runCurrent(block: () -> Unit) {
            laneThread = Thread.currentThread()
            currentContext.set(true)
            try {
                block()
            } finally {
                currentContext.set(false)
            }
        }

        private inner class Scope : GlLaneScope {
            override fun targetSizeLimits(): ProjectionTargetSizeLimits =
                ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

            override fun checkCurrentContext(operation: String) {
                check(isOnGlThread()) { "$operation must run on the GL lane." }
                check(currentContext.get() == true) { "$operation requires the owning EGL context." }
            }

            override fun checkGl(operation: String) = Unit
        }
    }

    private class CloseCountingResource(
        private val closeCount: AtomicInteger,
    ) : AutoCloseable {
        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
