package io.screenstream.capture.internal.capture

import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.mockk.mockk
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.Buffer
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class GLRendererReadbackTest {
    private var savedNoDisplay: EGLDisplay? = null
    private var savedNoContext: EGLContext? = null
    private var savedNoSurface: EGLSurface? = null

    @Before
    fun installOpaqueEglSentinels() {
        savedNoDisplay = EglSentinelAccess.noDisplay()
        savedNoContext = EglSentinelAccess.noContext()
        savedNoSurface = EglSentinelAccess.noSurface()
        EglSentinelAccess.setNoDisplay(mockk())
        EglSentinelAccess.setNoContext(mockk())
        EglSentinelAccess.setNoSurface(mockk())
    }

    @After
    fun restoreOpaqueEglSentinels() {
        EglSentinelAccess.setNoDisplay(savedNoDisplay)
        EglSentinelAccess.setNoContext(savedNoContext)
        EglSentinelAccess.setNoSurface(savedNoSurface)
    }

    // Verification: CAP-04
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.S_V2])
    fun legacyApisReadWithoutDataspaceQuery() {
        RendererFixture(dataSpace = Int.MIN_VALUE).use { fixture ->
            val durationNanos = fixture.renderer.readFrame(fixture.carrier)

            assertEquals(9L, durationNanos)
            assertEquals(1, fixture.targetPlatform.updateCount)
            assertEquals(1, fixture.targetPlatform.transformCount)
            assertEquals(0, fixture.targetPlatform.dataSpaceCount)
            assertEquals(1, fixture.gl.drawCount)
            assertEquals(1, fixture.gl.readPixelsCount)
            assertEquals(1, fixture.gl.postprobeCount)
            assertEquals(2, fixture.clock.readCount)
            assertArrayEquals(fixture.expectedReadbackBytes(), fixture.carrierBytes())
            assertEquals(0, fixture.sourceCallbackCount)
            assertEquals(0, fixture.callbackBoundaryCount)
            fixture.events.assertContainsInOrder("update", "transform", "draw", "readPixels", "postprobe")
        }
    }

    // Verification: CAP-04
    // Verification: P3-01
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun exactDisplayP3FailsBeforeRead() {
        RendererFixture(dataSpace = DataSpace.DATASPACE_DISPLAY_P3).use { fixture ->
            fixture.fillCarrier(CARRIER_SENTINEL)
            val bytesBefore = fixture.carrierBytes()
            val positionBefore = fixture.carrier.position()
            val limitBefore = fixture.carrier.limit()
            val capacityBefore = fixture.carrier.capacity()

            val failure = assertThrows(CaptureBoundaryFailure::class.java) {
                fixture.renderer.readFrame(fixture.carrier)
            }

            assertSame(ScreenCaptureProblem.UnsupportedColorSpace, failure.problem)
            assertSame(failure.physicalCause, failure.cause)
            assertEquals(1, fixture.targetPlatform.updateCount)
            assertEquals(1, fixture.targetPlatform.transformCount)
            assertEquals(1, fixture.targetPlatform.dataSpaceCount)
            assertEquals(0, fixture.gl.drawCount)
            assertEquals(0, fixture.gl.readPixelsCount)
            assertEquals(1, fixture.gl.postprobeCount)
            assertEquals(0, fixture.clock.readCount)
            assertArrayEquals(bytesBefore, fixture.carrierBytes())
            assertEquals(positionBefore, fixture.carrier.position())
            assertEquals(limitBefore, fixture.carrier.limit())
            assertEquals(capacityBefore, fixture.carrier.capacity())
            assertFalse(fixture.renderer.sourceRestorableAfterLastReadFailure)
            assertTrue(fixture.eglOwner.isHealthy)
            assertEquals(0, fixture.sourceCallbackCount)
            assertEquals(0, fixture.callbackBoundaryCount)
            fixture.events.assertContainsInOrder("update", "transform", "dataSpace", "postprobe")
        }
    }

    // Verification: CAP-04
    @Test
    @Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.BAKLAVA])
    fun nonDisplayP3DataspacesRemainBestEffort() {
        listOf(
            DataSpace.DATASPACE_UNKNOWN,
            DataSpace.DATASPACE_SRGB,
            DataSpace.DATASPACE_DCI_P3,
        ).forEach { dataSpace ->
            RendererFixture(dataSpace).use { fixture ->
                val durationNanos = fixture.renderer.readFrame(fixture.carrier)

                assertEquals(dataSpace.toString(), 9L, durationNanos)
                assertEquals(dataSpace.toString(), 1, fixture.targetPlatform.updateCount)
                assertEquals(dataSpace.toString(), 1, fixture.targetPlatform.transformCount)
                assertEquals(dataSpace.toString(), 1, fixture.targetPlatform.dataSpaceCount)
                assertEquals(dataSpace.toString(), 1, fixture.gl.drawCount)
                assertEquals(dataSpace.toString(), 1, fixture.gl.readPixelsCount)
                assertEquals(dataSpace.toString(), 1, fixture.gl.postprobeCount)
                assertEquals(dataSpace.toString(), 2, fixture.clock.readCount)
                assertArrayEquals(dataSpace.toString(), fixture.expectedReadbackBytes(), fixture.carrierBytes())
                assertEquals(dataSpace.toString(), 0, fixture.sourceCallbackCount)
                assertEquals(dataSpace.toString(), 0, fixture.callbackBoundaryCount)
                fixture.events.assertContainsInOrder(
                    "update",
                    "transform",
                    "dataSpace",
                    "draw",
                    "readPixels",
                    "postprobe",
                )
            }
        }
    }

    private class RendererFixture(dataSpace: Int) : AutoCloseable {
        val events = SemanticEventRecorder()
        val eglPlatform = HappyEglPlatform()
        val gl = RecordingRendererGlesPlatform(events)
        val eglOwner = EglOwner(eglPlatform, gl)
        val targetPlatform = RecordingTargetPlatform(dataSpace, events)
        val clock = RecordingClock(events, 100L, 109L)
        val carrier: ByteBuffer
        val renderer: GLRenderer
        var sourceCallbackCount = 0
            private set
        var callbackBoundaryCount = 0
            private set

        private val plan = capturePlan()
        private val targetOwner: TargetOwner

        init {
            val precision = eglOwner.open()
            targetOwner = TargetOwner(
                captureHandler = Handler(Looper.getMainLooper()),
                eglOwner = eglOwner,
                sourceSink = TargetOwner.SourceSink { sourceCallbackCount += 1 },
                callbackBoundary = object : CaptureCallbackBoundary {
                    override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
                        callbackBoundaryCount += 1
                    }
                },
                platformSdkInt = Build.VERSION.SDK_INT,
                platform = targetPlatform,
            )
            targetOwner.open(plan)
            renderer = GLRenderer(
                eglOwner = eglOwner,
                targetOwner = targetOwner,
                precision = precision,
                clock = clock,
                platformSdkInt = Build.VERSION.SDK_INT,
            )
            renderer.open(plan)
            events.clear()
            carrier = ByteBuffer.allocateDirect(plan.rgbaCarrierByteCount)
            gl.clearReadEvidence()
            targetPlatform.clearFrameEvidence()
        }

        fun fillCarrier(value: Byte) {
            for (index in 0 until carrier.capacity()) carrier.put(index, value)
        }

        fun carrierBytes(): ByteArray = ByteArray(carrier.capacity()) { index -> carrier.get(index) }

        fun expectedReadbackBytes(): ByteArray = ByteArray(carrier.capacity()) { index -> index.toByte() }

        override fun close() {
            val rendererRetirement = renderer.close()
            assertNull(rendererRetirement.cleanupFailure)
            assertNull(rendererRetirement.residue)
            assertNull(rendererRetirement.glNameResidue)

            val listenerRemoval = targetOwner.fenceAndRemoveListener()
            assertNull(listenerRemoval.failure)
            val targetRetirement = targetOwner.releaseKnownUnattached(listenerRemoval.proof)
            assertNull(targetRetirement.cleanupFailure)
            assertNull(targetRetirement.residue)
            assertNull(targetRetirement.glNameResidue)
            assertEquals(1, targetPlatform.surfaceReleaseCount)
            assertEquals(1, targetPlatform.surfaceTextureReleaseCount)

            val eglRetirement = eglOwner.close()
            assertNull(eglRetirement.cleanupFailure)
            assertNull(eglRetirement.residue)
            assertTrue(eglRetirement.namespaceDestroyedProof?.matches(eglOwner) == true)
            assertEquals(1, eglPlatform.destroyContextCount)
            assertEquals(1, eglPlatform.destroySurfaceCount)
            assertEquals(1, eglPlatform.releaseThreadCount)
        }
    }

    private class SemanticEventRecorder {
        private val events = mutableListOf<String>()

        fun record(event: String) {
            events += event
        }

        fun clear() {
            events.clear()
        }

        fun assertContainsInOrder(vararg expected: String) {
            var expectedIndex = 0
            for (event in events) {
                if (expectedIndex < expected.size && event == expected[expectedIndex]) expectedIndex += 1
            }
            assertEquals("events=$events", expected.size, expectedIndex)
        }
    }

    private class RecordingClock(
        private val events: SemanticEventRecorder,
        private vararg val samples: Long,
    ) : ElapsedRealtimeClock {
        var readCount = 0
            private set

        override fun nowNanos(): Long {
            if (readCount >= samples.size) throw AssertionError("unexpected clock read")
            events.record(if (readCount == 0) "clock-start" else "clock-finish")
            return samples[readCount++]
        }
    }

    private class RecordingTargetPlatform(
        private val returnedDataSpace: Int,
        private val events: SemanticEventRecorder,
    ) : TargetPlatform {
        private val surfaceTexture: SurfaceTexture = mockk()
        private val surface: Surface = mockk()
        var updateCount = 0
            private set
        var transformCount = 0
            private set
        var dataSpaceCount = 0
            private set
        var surfaceReleaseCount = 0
            private set
        var surfaceTextureReleaseCount = 0
            private set

        fun clearFrameEvidence() {
            updateCount = 0
            transformCount = 0
            dataSpaceCount = 0
        }

        override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture = surfaceTexture

        override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) {
            assertSame(this.surfaceTexture, surfaceTexture)
        }

        override fun createSurface(surfaceTexture: SurfaceTexture): Surface {
            assertSame(this.surfaceTexture, surfaceTexture)
            return surface
        }

        override fun setFrameListener(
            surfaceTexture: SurfaceTexture,
            listener: SurfaceTexture.OnFrameAvailableListener,
            handler: Handler,
        ) = throw AssertionError("listener installation was not expected")

        override fun clearFrameListener(surfaceTexture: SurfaceTexture) =
            throw AssertionError("listener removal was not expected")

        override fun updateTexImage(surfaceTexture: SurfaceTexture) {
            assertSame(this.surfaceTexture, surfaceTexture)
            updateCount += 1
            events.record("update")
        }

        override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) {
            assertSame(this.surfaceTexture, surfaceTexture)
            destination.fill(0f)
            destination[0] = 1f
            destination[5] = 1f
            destination[10] = 1f
            destination[15] = 1f
            transformCount += 1
            events.record("transform")
        }

        override fun dataSpace(surfaceTexture: SurfaceTexture): Int {
            assertSame(this.surfaceTexture, surfaceTexture)
            dataSpaceCount += 1
            events.record("dataSpace")
            return returnedDataSpace
        }

        override fun releaseSurface(surface: Surface) {
            assertSame(this.surface, surface)
            surfaceReleaseCount += 1
        }

        override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
            assertSame(this.surfaceTexture, surfaceTexture)
            surfaceTextureReleaseCount += 1
        }
    }

    private class HappyEglPlatform : EglPlatform {
        private val display: EGLDisplay = mockk()
        private val config: EGLConfig = mockk()
        private val context: EGLContext = mockk()
        private val surface: EGLSurface = mockk()
        private var currentDisplayValue: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var currentContextValue: EGLContext = EGL14.EGL_NO_CONTEXT
        private var currentSurfaceValue: EGLSurface = EGL14.EGL_NO_SURFACE
        var destroyContextCount = 0
            private set
        var destroySurfaceCount = 0
            private set
        var releaseThreadCount = 0
            private set

        override val currentDisplay: EGLDisplay
            get() = currentDisplayValue
        override val currentContext: EGLContext
            get() = currentContextValue
        override val currentReadSurface: EGLSurface
            get() = currentSurfaceValue
        override val currentDrawSurface: EGLSurface
            get() = currentSurfaceValue

        override fun getDisplay(): EGLDisplay = display

        override fun initialize(display: EGLDisplay, version: IntArray): Boolean {
            assertSame(this.display, display)
            version[0] = 1
            version[1] = 5
            return true
        }

        override fun chooseConfig(
            display: EGLDisplay,
            attributes: IntArray,
            configs: Array<EGLConfig?>,
            count: IntArray,
        ): Boolean {
            assertSame(this.display, display)
            configs[0] = config
            count[0] = 1
            return true
        }

        override fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext {
            assertSame(this.display, display)
            assertSame(this.config, config)
            return context
        }

        override fun createPbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLSurface {
            assertSame(this.display, display)
            assertSame(this.config, config)
            return surface
        }

        override fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean {
            assertSame(this.display, display)
            if (context === EGL14.EGL_NO_CONTEXT) {
                assertSame(EGL14.EGL_NO_SURFACE, surface)
                currentDisplayValue = EGL14.EGL_NO_DISPLAY
                currentContextValue = EGL14.EGL_NO_CONTEXT
                currentSurfaceValue = EGL14.EGL_NO_SURFACE
            } else {
                assertSame(this.surface, surface)
                assertSame(this.context, context)
                currentDisplayValue = display
                currentContextValue = context
                currentSurfaceValue = surface
            }
            return true
        }

        override fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean {
            assertSame(this.display, display)
            assertSame(this.context, context)
            destroyContextCount += 1
            return true
        }

        override fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean {
            assertSame(this.display, display)
            assertSame(this.surface, surface)
            destroySurfaceCount += 1
            return true
        }

        override fun releaseThread(): Boolean {
            releaseThreadCount += 1
            return true
        }

        override fun getError(): Int = EGL14.EGL_SUCCESS
    }

    private class RecordingRendererGlesPlatform(
        private val events: SemanticEventRecorder,
    ) : GlesPlatform {
        private var nextName = 1
        var drawCount = 0
            private set
        var readPixelsCount = 0
            private set
        var postprobeCount = 0
            private set

        fun clearReadEvidence() {
            drawCount = 0
            readPixelsCount = 0
            postprobeCount = 0
        }

        override fun getError(): Int {
            postprobeCount += 1
            events.record("postprobe")
            return GLES20.GL_NO_ERROR
        }

        override fun getInteger(name: Int, values: IntArray) {
            when (name) {
                GLES20.GL_MAX_TEXTURE_SIZE -> values[0] = 4_096
                GLES20.GL_MAX_VIEWPORT_DIMS -> {
                    values[0] = 4_096
                    values[1] = 4_096
                }

                GLES20.GL_RED_BITS,
                GLES20.GL_GREEN_BITS,
                GLES20.GL_BLUE_BITS,
                    -> values[0] = 8

                else -> throw AssertionError("unexpected integer query: $name")
            }
        }

        override fun getShaderPrecisionFormat(range: IntArray, precision: IntArray) {
            range[0] = 127
            range[1] = 127
            precision[0] = 23
        }

        override fun genTextures(names: IntArray) {
            names[0] = nextName++
        }

        override fun bindTexture(target: Int, texture: Int) = Unit
        override fun texParameter(target: Int, name: Int, value: Int) = Unit
        override fun texImage2D(width: Int, height: Int) = Unit
        override fun deleteTextures(names: IntArray) = Unit

        override fun genFramebuffers(names: IntArray) {
            names[0] = nextName++
        }

        override fun bindFramebuffer(framebuffer: Int) = Unit
        override fun framebufferTexture2D(texture: Int) = Unit
        override fun checkFramebufferStatus(): Int = GLES20.GL_FRAMEBUFFER_COMPLETE
        override fun deleteFramebuffers(names: IntArray) = Unit

        override fun createShader(type: Int): Int = nextName++
        override fun shaderSource(shader: Int, source: String) = Unit
        override fun compileShader(shader: Int) = Unit

        override fun getShaderStatus(shader: Int, status: IntArray) {
            status[0] = GLES20.GL_TRUE
        }

        override fun deleteShader(shader: Int) = Unit
        override fun createProgram(): Int = nextName++
        override fun attachShader(program: Int, shader: Int) = Unit
        override fun bindAttribLocation(program: Int, index: Int, name: String) = Unit
        override fun linkProgram(program: Int) = Unit

        override fun getProgramStatus(program: Int, status: IntArray) {
            status[0] = GLES20.GL_TRUE
        }

        override fun getUniformLocation(program: Int, name: String): Int = nextName++
        override fun detachShader(program: Int, shader: Int) = Unit
        override fun deleteProgram(program: Int) = Unit
        override fun useProgram(program: Int) = Unit
        override fun viewport(width: Int, height: Int) = Unit
        override fun activeTexture(texture: Int) = Unit
        override fun uniform1i(location: Int, value: Int) = Unit
        override fun uniform1f(location: Int, value: Float) = Unit
        override fun uniformMatrix4fv(location: Int, values: FloatArray) = Unit
        override fun vertexAttribPointer(index: Int, values: Buffer) = Unit
        override fun enableVertexAttribArray(index: Int) = Unit
        override fun colorMask() = Unit
        override fun packAlignmentOne() = Unit
        override fun disable(capability: Int) = Unit

        override fun drawTriangleStrip() {
            drawCount += 1
            events.record("draw")
        }

        override fun readPixels(width: Int, height: Int, carrier: Buffer) {
            readPixelsCount += 1
            events.record("readPixels")
            val destination = carrier as ByteBuffer
            for (index in 0 until destination.capacity()) destination.put(index, index.toByte())
        }
    }

    private companion object {
        private const val CARRIER_SENTINEL: Byte = 0x5A

        private fun capturePlan(): CapturePlan = CapturePlan(
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.ZERO,
            appliedSourceRect = ImageRect.create(0, 0, 2, 1),
            rotation = Rotation.Degrees0,
            mirror = Mirror.None,
            colorMode = ColorMode.Color,
            sourceWidthPx = 2,
            sourceHeightPx = 1,
            densityDpi = 1,
            targetMode = CaptureTargetMode.Full,
            targetWidthPx = 2,
            targetHeightPx = 1,
            rgbaLayout = Rgba8888Layout.create(2, 1),
        )
    }
}
