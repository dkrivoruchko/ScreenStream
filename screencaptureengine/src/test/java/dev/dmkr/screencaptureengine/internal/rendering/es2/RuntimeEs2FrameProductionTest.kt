package dev.dmkr.screencaptureengine.internal.rendering.es2

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.gl.BlockingProjectionTargetGlAccess
import dev.dmkr.screencaptureengine.internal.gl.GlLaneAbandonment
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.gl.ProjectionTargetGlLane
import dev.dmkr.screencaptureengine.internal.gl.RuntimeGles20Api
import dev.dmkr.screencaptureengine.internal.lifecycle.ConflatedRuntimeFrameSignalSource
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeFrameSignal
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeProjectionTargetFrameAvailableListener
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSizeLimits
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetOwner
import dev.dmkr.screencaptureengine.internal.target.RuntimeExternalOesTexture
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetGlScope
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetInstanceIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetOwnerIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTextureFrame
import dev.dmkr.screencaptureengine.internal.target.consumeLatestFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeEs2FrameProductionTest {
    private val runtimeOwnerIdentity = RuntimeProjectionTargetOwnerIdentity()
    private val runtimeTargetIdentity = RuntimeProjectionTargetInstanceIdentity()

    @Test
    fun frameAvailableCallbackOnlyEnqueuesLatestSignal() {
        val source = ConflatedRuntimeFrameSignalSource()
        val surfaceTexture = SurfaceTexture(0)
        val listener = RuntimeProjectionTargetFrameAvailableListener(generation = 12L, sink = source)

        try {
            listener.onFrameAvailable(surfaceTexture)
            listener.onFrameAvailable(surfaceTexture)

            val signal = source.drainLatestFrameSignal()

            assertEquals(RuntimeFrameSignal(generation = 12L, sequence = 2L), signal)
            assertEquals(null, source.drainLatestFrameSignal())
        } finally {
            surfaceTexture.release()
        }
    }

    @Test
    fun runtimeProjectionTargetFrameConsumptionRunsOnlyOnGlLaneAndInvalidatesRetainedScope() = runTest {
        val owner = ProjectionTargetOwner(TestGlLane("runtime-target-gl"))
        val target = TestProjectionTarget(owner = owner, generation = 7L)
        var glThreadName = ""
        var retainedScope: RuntimeProjectionTargetGlScope? = null
        var transformMatrix = FloatArray(16)

        try {
            val frame = owner.withCurrentRuntimeProjectionTarget(target = target.handle, generation = 7L) {
                glThreadName = Thread.currentThread().name
                assertEquals(7L, generation)
                assertEquals(100, width)
                assertEquals(200, height)
                assertEquals(320, densityDpi)
                assertEquals(RuntimeExternalOesTexture(textureId = 11), externalOesTexture)
                assertEquals(7L, projectionTargetIdentity.generation)
                assertEquals(RuntimeExternalOesTexture(textureId = 11), projectionTargetIdentity.externalOesTexture)

                val consumed = consumeLatestFrame()
                transformMatrix = consumed.transformMatrix
                retainedScope = this
                consumed
            }

            val retainedFailure = runCatching { retainedScope?.timestampNanos() }.exceptionOrNull()

            assertTrue(glThreadName.contains("runtime-target-gl"))
            assertEquals(7L, frame.projectionTargetGeneration)
            assertEquals(RuntimeExternalOesTexture(textureId = 11), frame.externalOesTexture)
            assertEquals(16, transformMatrix.size)
            assertTrue(transformMatrix.all(Float::isFinite))
            assertTrue(retainedFailure is IllegalStateException)
            assertEquals("Runtime projection target GL access is no longer active.", retainedFailure?.message)
        } finally {
            target.close()
            owner.close()
        }
    }

    @Test
    fun runtimeEs2RendererDrawsThenReadsWithPackAlignmentAndScopedLease() {
        val gles = RecordingRuntimeGles20Api()
        val renderer = RuntimeEs2FrameRenderer(gles = gles)
        val resources = newEs2Resources()
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = resources,
            transformPackage = firstPlanTransformPackage(),
        )

        val result = renderer.renderReadback(request)

        assertTrue(result is RuntimeEs2RenderReadbackResult.Success)
        val success = result as RuntimeEs2RenderReadbackResult.Success
        assertEquals(123L, success.sourceTimestampNanos)
        assertEquals(4, success.lease.width)
        assertEquals(3, success.lease.height)
        assertEquals(16, success.lease.rowStrideBytes)
        assertEquals(ImageEncoderInputFormat.Rgba8888SrgbOpaque, success.lease.inputFormat)
        assertSame(success.lease.mutableReadbackBuffer(), gles.readPixelsBuffer)

        val readOnlyView = success.lease.readOnlyBufferView()
        assertTrue(readOnlyView.isReadOnly)
        assertNotSame(success.lease.mutableReadbackBuffer(), readOnlyView)
        assertEquals(0, readOnlyView.position())
        assertEquals(48, readOnlyView.limit())

        assertSame(RuntimeEs2RenderReadbackResult.ReadbackBusy, renderer.renderReadback(request))

        val packAlignmentIndex = gles.calls.indexOf("pixelStorei:${GLES20.GL_PACK_ALIGNMENT}:1")
        val drawIndex = gles.calls.indexOf("drawArrays:${GLES20.GL_TRIANGLE_STRIP}:0:4")
        val readIndex = gles.calls.indexOf("readPixels:0:0:4:3:${GLES20.GL_RGBA}:${GLES20.GL_UNSIGNED_BYTE}")
        assertTrue(packAlignmentIndex >= 0)
        assertTrue(drawIndex > packAlignmentIndex)
        assertTrue(readIndex > drawIndex)
        assertTrue(gles.calls.contains("bindTexture:${GLES11Ext.GL_TEXTURE_EXTERNAL_OES}:77"))
        assertTrue(gles.calls.contains("useProgram:20"))
        assertTrue(gles.calls.contains("bindFramebuffer:${GLES20.GL_FRAMEBUFFER}:2"))
        assertTrue(gles.calls.contains("uniform1i:5:0"))
        assertEquals(identityMatrix4().toList(), gles.lastUniformMatrix?.toList())

        success.lease.close()
        val nextResult = renderer.renderReadback(request)
        assertTrue(nextResult is RuntimeEs2RenderReadbackResult.Success)
        (nextResult as RuntimeEs2RenderReadbackResult.Success).lease.close()
    }

    @Test
    fun runtimeEs2RendererRejectsFrameGenerationMismatch() {
        val renderer = RuntimeEs2FrameRenderer(gles = RecordingRuntimeGles20Api())
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(generation = 8L),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = newEs2Resources(),
            transformPackage = firstPlanTransformPackage(),
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            renderer.renderReadback(request)
        }

        assertEquals("Runtime ES2 frame generation does not match render request.", thrown.message)
    }

    @Test
    fun runtimeEs2RendererRejectsFrameIdentityMismatch() {
        val renderer = RuntimeEs2FrameRenderer(gles = RecordingRuntimeGles20Api())
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(textureId = 77),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(textureId = 78),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = newEs2Resources(),
            transformPackage = firstPlanTransformPackage(),
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            renderer.renderReadback(request)
        }

        assertEquals("Runtime ES2 frame identity does not match render request.", thrown.message)
    }

    @Test
    fun runtimeEs2RendererRejectsFrameOwnerIdentityMismatchWithSameGenerationAndTexture() {
        val renderer = RuntimeEs2FrameRenderer(gles = RecordingRuntimeGles20Api())
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(
                ownerIdentity = RuntimeProjectionTargetOwnerIdentity(),
                textureId = 77,
            ),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(
                    ownerIdentity = RuntimeProjectionTargetOwnerIdentity(),
                    textureId = 77,
                ),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = newEs2Resources(),
            transformPackage = firstPlanTransformPackage(),
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            renderer.renderReadback(request)
        }

        assertEquals("Runtime ES2 frame identity does not match render request.", thrown.message)
    }

    @Test
    fun runtimeEs2RendererRestoresKnownRendererAttributeStateWithoutPointerQuery() {
        val gles = RecordingRuntimeGles20Api().apply {
            arrayBufferBinding = 55
            attributes[3] = RecordingRuntimeGles20Api.VertexAttributeState(
                enabled = false,
            )
            attributes[4] = RecordingRuntimeGles20Api.VertexAttributeState(
                enabled = false,
            )
        }
        val renderer = RuntimeEs2FrameRenderer(gles = gles)
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = newEs2Resources(),
            transformPackage = firstPlanTransformPackage(),
        )

        val result = renderer.renderReadback(request)

        assertTrue(result is RuntimeEs2RenderReadbackResult.Success)
        (result as RuntimeEs2RenderReadbackResult.Success).lease.close()
        assertEquals(55, gles.arrayBufferBinding)
        assertEquals(
            RecordingRuntimeGles20Api.VertexAttributeState(
                enabled = false,
                size = 2,
                type = GLES20.GL_FLOAT,
                normalized = false,
                stride = 0,
                arrayBufferBinding = 0,
                pointerOffset = 0,
            ),
            gles.attributes[3],
        )
        assertEquals(
            RecordingRuntimeGles20Api.VertexAttributeState(
                enabled = false,
                size = 2,
                type = GLES20.GL_FLOAT,
                normalized = false,
                stride = 0,
                arrayBufferBinding = 0,
                pointerOffset = 0,
            ),
            gles.attributes[4],
        )
        assertTrue(gles.calls.contains("enableVertexAttribArray:3"))
        assertTrue(gles.calls.contains("enableVertexAttribArray:4"))
        assertTrue(gles.calls.contains("disableVertexAttribArray:3"))
        assertTrue(gles.calls.contains("disableVertexAttribArray:4"))
        assertFalse(gles.calls.any { it == "getVertexAttribiv:3:${GLES20.GL_VERTEX_ATTRIB_ARRAY_POINTER}" })
        assertFalse(gles.calls.any { it == "getVertexAttribiv:4:${GLES20.GL_VERTEX_ATTRIB_ARRAY_POINTER}" })
        assertFalse(gles.calls.any { it.startsWith("vertexAttribPointerOffset:") })
    }

    @Test
    fun runtimeEs2RendererRejectsPreEnabledRendererOwnedAttribute() {
        val gles = RecordingRuntimeGles20Api().apply {
            attributes[3] = RecordingRuntimeGles20Api.VertexAttributeState(enabled = true)
        }
        val renderer = RuntimeEs2FrameRenderer(gles = gles)
        val request = RuntimeEs2RenderReadbackRequest(
            gl = FakeGlLaneScope(),
            projectionTargetGeneration = 9L,
            projectionTargetIdentity = runtimeProjectionTargetIdentity(),
            frame = RuntimeProjectionTextureFrame(
                projectionTargetIdentity = runtimeProjectionTargetIdentity(),
                transformMatrix = identityMatrix4(),
                timestampNanos = 123L,
            ),
            resources = newEs2Resources(),
            transformPackage = firstPlanTransformPackage(),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            renderer.renderReadback(request)
        }

        assertEquals("Runtime ES2 renderer-owned vertex attribute 3 must be disabled before render/readback.", thrown.message)
        assertFalse(gles.calls.any { it == "getVertexAttribiv:3:${GLES20.GL_VERTEX_ATTRIB_ARRAY_POINTER}" })
        gles.attributes[3] = RecordingRuntimeGles20Api.VertexAttributeState(enabled = false)
        val retryResult = renderer.renderReadback(request)
        assertTrue(retryResult is RuntimeEs2RenderReadbackResult.Success)
        (retryResult as RuntimeEs2RenderReadbackResult.Success).lease.close()
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

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun <T> executeCurrentBlocking(block: GlLaneScope.() -> T): T {
            val latch = CountDownLatch(1)
            var value: T? = null
            var failure: Throwable? = null
            executor.execute {
                laneThread = Thread.currentThread()
                try {
                    value = block(scope)
                } catch (cause: Throwable) {
                    failure = cause
                } finally {
                    latch.countDown()
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
                    laneThread = Thread.currentThread()
                    try {
                        val result = block(scope)
                        continuation.resume(result) { _, rejectedResult, _ ->
                            onCancellation(rejectedResult)
                        }
                    } catch (cause: Throwable) {
                        continuation.resumeWithException(cause)
                    }
                }
            }

        @OptIn(BlockingProjectionTargetGlAccess::class)
        override fun executeCurrentIfCreatedBlocking(block: GlLaneScope.() -> Unit) {
            executeCurrentBlocking(block)
        }

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            executor.execute {
                laneThread = Thread.currentThread()
                block(scope)
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

        private inner class Scope : GlLaneScope {
            override fun targetSizeLimits(): ProjectionTargetSizeLimits =
                ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

            override fun checkCurrentContext(operation: String) {
                check(isOnGlThread()) { "$operation must run on the GL lane." }
            }

            override fun checkGl(operation: String) = Unit
        }
    }

    private class FakeGlLaneScope : GlLaneScope {
        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

        override fun checkCurrentContext(operation: String) = Unit

        override fun checkGl(operation: String) = Unit
    }

    private class RecordingRetirementLane : GlResourceRetirementLane {
        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean = true
    }

    private class RecordingRuntimeGles20Api : RuntimeGles20Api {
        val calls = mutableListOf<String>()
        val attributes = mutableMapOf<Int, VertexAttributeState>()
        var readPixelsBuffer: Buffer? = null
        var lastUniformMatrix: FloatArray? = null
        var arrayBufferBinding: Int = 41

        override fun getIntegerv(pname: Int, params: IntArray, offset: Int) {
            calls += "getIntegerv:$pname"
            when (pname) {
                GLES20.GL_ACTIVE_TEXTURE -> params[offset] = GLES20.GL_TEXTURE1
                GLES20.GL_TEXTURE_BINDING_2D -> params[offset] = 31
                GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES -> params[offset] = 32
                GLES20.GL_ARRAY_BUFFER_BINDING -> params[offset] = arrayBufferBinding
                GLES20.GL_FRAMEBUFFER_BINDING -> params[offset] = 33
                GLES20.GL_CURRENT_PROGRAM -> params[offset] = 34
                GLES20.GL_PACK_ALIGNMENT -> params[offset] = 4
                GLES20.GL_VIEWPORT -> {
                    params[offset] = 1
                    params[offset + 1] = 2
                    params[offset + 2] = 300
                    params[offset + 3] = 400
                }

                else -> error("Unexpected getIntegerv pname $pname.")
            }
        }

        override fun activeTexture(texture: Int) {
            calls += "activeTexture:$texture"
        }

        override fun bindTexture(target: Int, texture: Int) {
            calls += "bindTexture:$target:$texture"
        }

        override fun bindBuffer(target: Int, buffer: Int) {
            calls += "bindBuffer:$target:$buffer"
            if (target == GLES20.GL_ARRAY_BUFFER) {
                arrayBufferBinding = buffer
            }
        }

        override fun useProgram(program: Int) {
            calls += "useProgram:$program"
        }

        override fun bindFramebuffer(target: Int, framebuffer: Int) {
            calls += "bindFramebuffer:$target:$framebuffer"
        }

        override fun viewport(x: Int, y: Int, width: Int, height: Int) {
            calls += "viewport:$x:$y:$width:$height"
        }

        override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer) {
            calls += "vertexAttribPointer:$index:$size:$type:$normalized:$stride"
            attributes[index] = vertexAttribute(index).copy(
                size = size,
                type = type,
                normalized = normalized,
                stride = stride,
                arrayBufferBinding = arrayBufferBinding,
                pointerOffset = 0,
            )
        }

        override fun getVertexAttribiv(index: Int, pname: Int, params: IntArray, offset: Int) {
            calls += "getVertexAttribiv:$index:$pname"
            val attribute = vertexAttribute(index)
            params[offset] = when (pname) {
                GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED -> if (attribute.enabled) 1 else 0
                GLES20.GL_VERTEX_ATTRIB_ARRAY_SIZE -> attribute.size
                GLES20.GL_VERTEX_ATTRIB_ARRAY_TYPE -> attribute.type
                GLES20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED -> if (attribute.normalized) 1 else 0
                GLES20.GL_VERTEX_ATTRIB_ARRAY_STRIDE -> attribute.stride
                GLES20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING -> attribute.arrayBufferBinding
                else -> error("Unexpected getVertexAttribiv pname $pname.")
            }
        }

        override fun enableVertexAttribArray(index: Int) {
            calls += "enableVertexAttribArray:$index"
            attributes[index] = vertexAttribute(index).copy(enabled = true)
        }

        override fun disableVertexAttribArray(index: Int) {
            calls += "disableVertexAttribArray:$index"
            attributes[index] = vertexAttribute(index).copy(enabled = false)
        }

        override fun uniform1i(location: Int, value: Int) {
            calls += "uniform1i:$location:$value"
        }

        override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
            calls += "uniformMatrix4fv:$location:$count:$transpose:$offset"
            lastUniformMatrix = value.copyOf()
        }

        override fun pixelStorei(pname: Int, param: Int) {
            calls += "pixelStorei:$pname:$param"
        }

        override fun drawArrays(mode: Int, first: Int, count: Int) {
            calls += "drawArrays:$mode:$first:$count"
        }

        override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
            calls += "readPixels:$x:$y:$width:$height:$format:$type"
            readPixelsBuffer = pixels
        }

        private fun vertexAttribute(index: Int): VertexAttributeState =
            attributes.getOrPut(index) { VertexAttributeState() }

        data class VertexAttributeState(
            val enabled: Boolean = false,
            val size: Int = 4,
            val type: Int = GLES20.GL_FLOAT,
            val normalized: Boolean = false,
            val stride: Int = 0,
            val arrayBufferBinding: Int = 0,
            val pointerOffset: Int = 0,
        )
    }

    private fun newEs2Resources(): PreparedEs2RenderingReadbackResources =
        PreparedEs2RenderingReadbackResources(
            retirementLane = RecordingRetirementLane(),
            glObjects = PreparedEs2RenderingReadbackGlObjects(
                outputTextureId = 1,
                outputFramebufferId = 2,
                outputRenderbufferId = 0,
                programId = 20,
                vertexShaderId = 21,
                fragmentShaderId = 22,
                programBinding = Es2RenderingProgramBindingMetadata(
                    programId = 20,
                    shaderVariant = Es2RenderingShaderVariant.OriginalExternalOes,
                    attributeLocations = Es2RenderingProgramAttributeLocations(
                        position = 3,
                        textureCoordinate = 4,
                    ),
                    uniformLocations = Es2RenderingProgramUniformLocations(
                        externalOesTextureSampler = 5,
                        textureMatrix = 6,
                    ),
                    dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                        uniformName = "uTexMatrix",
                        location = 6,
                        matrixElementCount = 16,
                        compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
                    ),
                ),
            ),
            width = 4,
            height = 3,
            rowStrideBytes = 16,
            readbackBuffer = ByteBuffer.allocateDirect(48),
        )

    private fun firstPlanTransformPackage(): FirstPlanRenderTransformPackage =
        FirstPlanRenderTransformPackage(
            projectionTargetGeneration = 9L,
            logicalContentSize = Size(width = 4, height = 3),
            captureTargetSize = Size(width = 4, height = 3),
            appliedSourceRect = ImageRect(left = 0, top = 0, right = 4, bottom = 3),
            logicalToCaptureTargetMapping = FirstPlanLogicalToCaptureTargetMapping(
                scaleX = 1.0f,
                scaleY = 1.0f,
                sourceRectInCaptureTargetPixels = FirstPlanFloatRect(left = 0.0f, top = 0.0f, right = 4.0f, bottom = 3.0f),
                sourceRectInCaptureTargetNormalized = FirstPlanFloatRect(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 1.0f),
            ),
            outputViewport = FirstPlanRenderViewport(x = 0, y = 0, width = 4, height = 3),
            sourceTransformMatrix = FirstPlanRenderMatrix4(identityMatrix4()),
            colorMode = ColorMode.Original,
            programBinding = FirstPlanEs2ProgramBinding(
                shaderVariant = FirstPlanEs2ShaderVariant.OriginalColor,
                programId = 20,
                positionAttributeLocation = 3,
                textureCoordinateAttributeLocation = 4,
                textureSamplerUniformLocation = 5,
                textureMatrixUniformLocation = 6,
            ),
            dynamicOesMatrixSlot = FirstPlanDynamicOesMatrixSlot(
                uniformName = "uTexMatrix",
                uniformLocation = 6,
                valueShape = FirstPlanMatrixValueShape.Mat4ColumnMajorFloatArray,
            ),
            oesCompositionRule = FirstPlanOesCompositionRule.DynamicOesMatrixAfterStaticPlanTransform,
            encoderInputNormalizationStrategy = FirstPlanEncoderInputNormalizationStrategy.RenderSpaceVerticalInversion,
            readbackShape = FirstPlanReadbackShape(
                width = 4,
                height = 3,
                rowStrideBytes = 16,
                byteCount = 48L,
                inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
                readbackMode = ReadbackMode.Es2,
                rowOrder = FirstPlanReadbackRowOrder.TopToBottom,
            ),
        )

    private fun runtimeProjectionTargetIdentity(
        generation: Long = 9L,
        textureId: Int = 77,
        ownerIdentity: RuntimeProjectionTargetOwnerIdentity = runtimeOwnerIdentity,
        targetIdentity: RuntimeProjectionTargetInstanceIdentity = runtimeTargetIdentity,
    ): RuntimeProjectionTargetIdentity =
        RuntimeProjectionTargetIdentity(
            ownerIdentity = ownerIdentity,
            targetIdentity = targetIdentity,
            generation = generation,
            externalOesTexture = RuntimeExternalOesTexture(textureId = textureId),
        )

    private fun identityMatrix4(): FloatArray =
        floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )
}
