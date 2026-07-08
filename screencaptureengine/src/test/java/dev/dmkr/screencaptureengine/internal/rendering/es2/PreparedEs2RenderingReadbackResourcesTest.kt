package dev.dmkr.screencaptureengine.internal.rendering.es2

import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.gl.Gles20Api
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSizeLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PreparedEs2RenderingReadbackResourcesTest {
    @Test
    fun closeFromNonGlThreadEnqueuesCleanupAndDoesNotDeleteInline() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)

        resources.close()

        assertEquals(1, lane.enqueuedCount)
        assertEquals(1, lane.queuedCount)
        assertEquals(emptyList<String>(), gles.calls)

        lane.runNext()

        assertEquals(
            listOf(
                "deleteProgram:4",
                "deleteShader:5",
                "deleteShader:6",
                "deleteFramebuffer:2",
                "deleteRenderbuffer:3",
                "deleteTexture:1",
            ),
            gles.calls,
        )
    }

    @Test
    fun racingCloseFromMultipleThreadsEnqueuesAndCleansOnce() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val threadCount = 16
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val closed = CountDownLatch(threadCount)
        val closeThreads = List(threadCount) {
            Thread {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to start racing close thread."
                }
                resources.close()
                closed.countDown()
            }.apply { start() }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally {
            start.countDown()
            closeThreads.forEach { it.joinOrFail("racing close") }
        }

        assertEquals(1, lane.enqueuedCount)
        assertEquals(1, lane.queuedCount)
        assertEquals(emptyList<String>(), gles.calls)

        lane.runNext()

        assertEquals(
            listOf(
                "deleteProgram:4",
                "deleteShader:5",
                "deleteShader:6",
                "deleteFramebuffer:2",
                "deleteRenderbuffer:3",
                "deleteTexture:1",
            ),
            gles.calls,
        )
    }

    @Test
    fun abandonedLaneCloseReturnsPromptlyAndDoesNotEnqueueOrDelete() {
        val lane = ManualRetirementLane().apply { abandon() }
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)

        resources.close()

        assertEquals(0, lane.enqueuedCount)
        assertEquals(0, lane.queuedCount)
        assertEquals(emptyList<String>(), gles.calls)
    }

    @Test
    fun readbackBufferAfterCloseFailsFastWithoutReusingBuffer() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)

        resources.close()

        val thrown = assertThrows(IllegalStateException::class.java) {
            resources.readbackBuffer
        }

        assertEquals("Prepared ES2 rendering/readback resources are closed.", thrown.message)
    }

    @Test
    fun acquireReadbackLeaseDuringInProgressCloseFailsFast() {
        val lane = BlockingAdmissionRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val closeReturned = CountDownLatch(1)
        val closeThread = Thread {
            resources.close()
            closeReturned.countDown()
        }.apply { start() }

        try {
            assertTrue(lane.retirementAdmissionEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, lane.enqueuedCount.get())
            assertEquals(1, closeReturned.count)

            val thrown = assertThrows(IllegalStateException::class.java) {
                resources.tryAcquireRgbaReadbackLease()
            }

            assertEquals("Prepared ES2 rendering/readback resources are closed.", thrown.message)
        } finally {
            lane.releaseRetirementAdmission.countDown()
            closeThread.joinOrFail("close during retirement admission")
        }

        assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun queuedRetirementWorkUsesExplicitSnapshotCommand() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val readbackMetadata = resources.readbackBuffer

        resources.close()

        val queuedBlock = lane.singleQueuedBlock()
        assertTrue(queuedBlock is PreparedEs2RenderingReadbackResourceRetirement)
        assertEquals(0, readbackMetadata.capacity())

        lane.runNext()

        assertEquals(
            listOf(
                "deleteProgram:4",
                "deleteShader:5",
                "deleteShader:6",
                "deleteFramebuffer:2",
                "deleteRenderbuffer:3",
                "deleteTexture:1",
            ),
            gles.calls,
        )
    }

    @Test
    fun cleanupDeleteFailureStillAttemptsLaterHandlesAndSuppressesLaterFailures() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val deleteProgramFailure = IllegalStateException("delete program failed")
        val deleteTextureFailure = IllegalStateException("delete texture failed")
        gles.deleteFailures["deleteProgram:4"] = deleteProgramFailure
        gles.deleteFailures["deleteTexture:1"] = deleteTextureFailure
        val resources = newResources(lane = lane, gles = gles)

        resources.close()

        val thrown = runCatching {
            lane.runNext()
        }.exceptionOrNull()

        assertSame(deleteProgramFailure, thrown)
        assertSame(deleteTextureFailure, deleteProgramFailure.suppressed.single())
        assertEquals(
            listOf(
                "deleteProgram:4",
                "deleteShader:5",
                "deleteShader:6",
                "deleteFramebuffer:2",
                "deleteRenderbuffer:3",
                "deleteTexture:1",
            ),
            gles.calls,
        )
    }

    @Test
    fun readbackBufferReturnsNonRawReadOnlyMetadata() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val backingBuffer = ByteBuffer.allocateDirect(48)
        val resources = PreparedEs2RenderingReadbackResources(
            retirementLane = lane,
            glObjects = PreparedEs2RenderingReadbackGlObjects(
                outputTextureId = 1,
                outputFramebufferId = 2,
                outputRenderbufferId = 0,
                programId = 4,
                vertexShaderId = 5,
                fragmentShaderId = 6,
                programBinding = originalProgramBinding(),
            ),
            width = 4,
            height = 3,
            rowStrideBytes = 16,
            readbackBuffer = backingBuffer,
            gles = gles,
        )
        backingBuffer.position(7)
        backingBuffer.limit(47)

        val readbackBuffer = resources.readbackBuffer

        assertNotSame(backingBuffer, readbackBuffer)
        assertTrue(readbackBuffer.isDirect)
        assertTrue(readbackBuffer.isReadOnly)
        assertEquals(0, readbackBuffer.position())
        assertEquals(0, readbackBuffer.limit())
        assertEquals(0, readbackBuffer.capacity())
    }

    @Test
    fun rgbaReadbackLeaseIsOnlyMutableReadbackPathAndIsExclusive() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)

        val firstLease = checkNotNull(resources.tryAcquireRgbaReadbackLease())

        assertEquals(null, resources.tryAcquireRgbaReadbackLease())
        assertFalse(firstLease.mutableReadbackBuffer().isReadOnly)

        firstLease.close()

        val secondLease = checkNotNull(resources.tryAcquireRgbaReadbackLease())
        secondLease.close()
    }

    @Test
    fun retainedReadbackMetadataDoesNotAliasLeaseStorage() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val retainedMetadata = resources.readbackBuffer

        val lease = checkNotNull(resources.tryAcquireRgbaReadbackLease())
        lease.mutableReadbackBuffer().put(0, 42)
        val leaseReadOnlyView = lease.readOnlyBufferView()

        assertTrue(retainedMetadata.isReadOnly)
        assertEquals(0, retainedMetadata.capacity())
        assertNotSame(retainedMetadata, lease.mutableReadbackBuffer())
        assertNotSame(retainedMetadata, leaseReadOnlyView)
        assertEquals(42, leaseReadOnlyView.get(0).toInt())

        lease.close()
    }

    @Test
    fun cleanupQueuedBehindBlockedGlWorkThenLaneAbandonDoesNotBlockLogicalClose() {
        val lane = BlockingRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val closed = CountDownLatch(1)

        lane.enqueueBlockedGlWork()
        assertTrue(lane.blockedWorkEntered.await(5, TimeUnit.SECONDS))

        val closeThread = Thread {
            resources.close()
            closed.countDown()
        }.apply { start() }

        try {
            assertTrue(closed.await(500, TimeUnit.MILLISECONDS))
            assertEquals(1, lane.enqueuedCount.get())

            lane.abandon()

            assertEquals(emptyList<String>(), gles.calls)
        } finally {
            lane.releaseBlockedWork.countDown()
            closeThread.joinOrFail("close queued behind blocked GL work")
            lane.close()
        }

        assertEquals(emptyList<String>(), gles.calls)
    }

    @Test
    fun closeWhileReadbackLeaseActivePreventsFurtherLeaseAcquisition() {
        val lane = ManualRetirementLane()
        val gles = RecordingEs2Gles()
        val resources = newResources(lane = lane, gles = gles)
        val activeLease = checkNotNull(resources.tryAcquireRgbaReadbackLease())

        resources.close()

        val whileLeaseActiveThrown = assertThrows(IllegalStateException::class.java) {
            resources.tryAcquireRgbaReadbackLease()
        }
        activeLease.close()
        val afterLeaseReleaseThrown = assertThrows(IllegalStateException::class.java) {
            resources.tryAcquireRgbaReadbackLease()
        }

        assertEquals("Prepared ES2 rendering/readback resources are closed.", whileLeaseActiveThrown.message)
        assertEquals("Prepared ES2 rendering/readback resources are closed.", afterLeaseReleaseThrown.message)
        assertEquals(1, lane.enqueuedCount)
    }

    private fun newResources(
        lane: GlResourceRetirementLane,
        gles: RecordingEs2Gles,
    ): PreparedEs2RenderingReadbackResources =
        PreparedEs2RenderingReadbackResources(
            retirementLane = lane,
            glObjects = PreparedEs2RenderingReadbackGlObjects(
                outputTextureId = 1,
                outputFramebufferId = 2,
                outputRenderbufferId = 3,
                programId = 4,
                vertexShaderId = 5,
                fragmentShaderId = 6,
                programBinding = originalProgramBinding(),
            ),
            width = 4,
            height = 3,
            rowStrideBytes = 16,
            readbackBuffer = ByteBuffer.allocateDirect(16 * 3),
            gles = gles,
        )

    private fun originalProgramBinding(): Es2RenderingProgramBindingMetadata =
        Es2RenderingProgramBindingMetadata(
            programId = 4,
            shaderVariant = Es2RenderingShaderVariant.OriginalExternalOes,
            attributeLocations = Es2RenderingProgramAttributeLocations(
                position = 7,
                textureCoordinate = 8,
            ),
            uniformLocations = Es2RenderingProgramUniformLocations(
                externalOesTextureSampler = 9,
                textureMatrix = 10,
            ),
            dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                uniformName = "uTexMatrix",
                location = 10,
                matrixElementCount = 16,
                compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
            ),
        )

    private class ManualRetirementLane : GlResourceRetirementLane {
        private val lock = Any()
        private val queued = ArrayDeque<GlLaneScope.() -> Unit>()
        private val scope = FakeGlLaneScope()
        private var abandoned = false
        private var enqueued = 0
        val enqueuedCount: Int
            get() = synchronized(lock) { enqueued }
        val queuedCount: Int
            get() = synchronized(lock) { queued.size }

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            synchronized(lock) {
                if (abandoned) return false
                enqueued += 1
                queued.addLast(block)
                return true
            }
        }

        fun runNext() {
            val block = synchronized(lock) { queued.removeFirst() }
            block.invoke(scope)
        }

        fun singleQueuedBlock(): GlLaneScope.() -> Unit =
            synchronized(lock) { queued.single() }

        fun abandon() {
            synchronized(lock) {
                abandoned = true
                queued.clear()
            }
        }
    }

    private class BlockingRetirementLane : GlResourceRetirementLane, AutoCloseable {
        val blockedWorkEntered = CountDownLatch(1)
        val releaseBlockedWork = CountDownLatch(1)
        val enqueuedCount = AtomicInteger()
        private val abandoned = AtomicBoolean()
        private val queue = LinkedBlockingQueue<GlLaneScope.() -> Unit>()
        private val scope = FakeGlLaneScope()
        private val worker = Thread {
            try {
                while (!abandoned.get()) {
                    val work = queue.take()
                    if (abandoned.get()) return@Thread
                    work(scope)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            if (abandoned.get()) return false
            enqueuedCount.incrementAndGet()
            queue.put(block)
            return true
        }

        fun enqueueBlockedGlWork() {
            queue.put {
                blockedWorkEntered.countDown()
                check(releaseBlockedWork.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release blocked GL work."
                }
            }
        }

        fun abandon() {
            abandoned.set(true)
            queue.clear()
            worker.interrupt()
        }

        override fun close() {
            abandon()
            worker.join(5_000L)
            assertFalse("blocking retirement worker did not finish", worker.isAlive)
        }
    }

    private class BlockingAdmissionRetirementLane : GlResourceRetirementLane {
        val retirementAdmissionEntered = CountDownLatch(1)
        val releaseRetirementAdmission = CountDownLatch(1)
        val enqueuedCount = AtomicInteger()

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            enqueuedCount.incrementAndGet()
            retirementAdmissionEntered.countDown()
            check(releaseRetirementAdmission.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release retirement admission."
            }
            return true
        }
    }

    private class FakeGlLaneScope : GlLaneScope {
        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

        override fun checkCurrentContext(operation: String) = Unit

        override fun checkGl(operation: String) = Unit
    }

    private class RecordingEs2Gles : Gles20Api {
        val calls = mutableListOf<String>()
        val deleteFailures = mutableMapOf<String, Throwable>()

        override fun getIntegerv(pname: Int, params: IntArray, offset: Int) = unsupported()

        override fun getBooleanv(pname: Int, params: BooleanArray, offset: Int) = unsupported()

        override fun activeTexture(texture: Int) = unsupported()

        override fun createShader(type: Int): Int = unsupported()

        override fun shaderSource(shader: Int, source: String) = unsupported()

        override fun compileShader(shader: Int) = unsupported()

        override fun getShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) = unsupported()

        override fun getShaderInfoLog(shader: Int): String = unsupported()

        override fun createProgram(): Int = unsupported()

        override fun attachShader(program: Int, shader: Int) = unsupported()

        override fun linkProgram(program: Int) = unsupported()

        override fun validateProgram(program: Int) = unsupported()

        override fun getProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) = unsupported()

        override fun getProgramInfoLog(program: Int): String = unsupported()

        override fun getAttribLocation(program: Int, name: String): Int = unsupported()

        override fun getUniformLocation(program: Int, name: String): Int = unsupported()

        override fun genTextures(n: Int, textures: IntArray, offset: Int) = unsupported()

        override fun bindTexture(target: Int, texture: Int) = unsupported()

        override fun texParameteri(target: Int, pname: Int, param: Int) = unsupported()

        override fun texImage2D(
            target: Int,
            level: Int,
            internalformat: Int,
            width: Int,
            height: Int,
            border: Int,
            format: Int,
            type: Int,
            pixels: Buffer?,
        ) = unsupported()

        override fun genFramebuffers(n: Int, framebuffers: IntArray, offset: Int) = unsupported()

        override fun bindFramebuffer(target: Int, framebuffer: Int) = unsupported()

        override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) = unsupported()

        override fun checkFramebufferStatus(target: Int): Int = unsupported()

        override fun pixelStorei(pname: Int, param: Int) = unsupported()

        override fun deleteTexture(textureId: Int) {
            recordDelete("deleteTexture:$textureId")
        }

        override fun deleteFramebuffer(framebufferId: Int) {
            recordDelete("deleteFramebuffer:$framebufferId")
        }

        override fun deleteRenderbuffer(renderbufferId: Int) {
            recordDelete("deleteRenderbuffer:$renderbufferId")
        }

        override fun deleteProgram(programId: Int) {
            recordDelete("deleteProgram:$programId")
        }

        override fun deleteShader(shaderId: Int) {
            recordDelete("deleteShader:$shaderId")
        }

        private fun unsupported(): Nothing =
            error("Only delete calls are supported by this fake.")

        private fun recordDelete(call: String) {
            calls += call
            deleteFailures[call]?.let { throw it }
        }
    }

    private fun Thread.joinOrFail(description: String) {
        join(5_000L)
        assertFalse("$description thread did not finish", isAlive)
    }
}
