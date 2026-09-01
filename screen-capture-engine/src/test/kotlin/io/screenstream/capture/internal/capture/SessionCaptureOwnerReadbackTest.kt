package io.screenstream.capture.internal.capture

import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import io.mockk.every
import io.mockk.mockk
import io.screenstream.capture.ColorMode
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.encoding.CarrierDisposition
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingOwner
import io.screenstream.capture.internal.encoding.EncodingRetirement
import io.screenstream.capture.internal.encoding.ManagedDirectCarrier
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.MutableElapsedRealtimeClock
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Owner-observable Capture read results and exact carrier ownership are the verdicts in this package.
 * A paused Robolectric Looper may only enter already accepted Handler work. Private fields, command identities,
 * queue sizes, turn counts, reflection, and scheduler checkpoints are not oracles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
@LooperMode(LooperMode.Mode.PAUSED)
internal class SessionCaptureOwnerReadbackTest {
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

    // Verification: P3-01
    @Test
    fun exactDisplayP3ConsumesSourceThenSuccessorSrgbReusesOwner() {
        val clockReads = AtomicInteger()
        val fixture = OwnerFixture(
            readbackClock = ElapsedRealtimeClock {
                when (clockReads.getAndIncrement()) {
                    0 -> 100L
                    1 -> 109L
                    else -> throw AssertionError("Unexpected readback clock sample")
                }
            },
        )
        val carrier = ByteBuffer.allocateDirect(fixture.plan.rgbaCarrierByteCount)
        repeat(carrier.capacity()) { index -> carrier.put(index, CARRIER_SENTINEL) }
        val bytesBefore = carrierBytes(carrier)

        fixture.owner.adoptProjection(fixture.projection)
        assertTrue(fixture.owner.open(fixture.plan))
        fixture.enterPostedCommands()
        val opened = fixture.factPort.openResult.get() as? CaptureOpenResult.Opened
            ?: error("Capture owner did not open")

        fixture.deliverSourceFrame()
        assertEquals(1, fixture.factPort.sourceIdentityEvents.size)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents.single())
        val returned = AtomicReference<CaptureReadResult?>()
        assertTrue(
            fixture.owner.read(fixture.plan, opened.sourceIdentity, carrier) { result ->
                check(returned.compareAndSet(null, result))
            },
        )
        fixture.enterPostedCommands()

        val failed = returned.get() as? CaptureReadResult.Failed
            ?: error("Display P3 read did not return Failed")
        assertSame(ScreenCaptureProblem.UnsupportedColorSpace, failed.problem)
        assertTrue(failed.sourceConsumed)
        assertSame(CaptureFailureScope.OperationLocal, failed.scope)
        assertArrayEquals(bytesBefore, carrierBytes(carrier))
        assertEquals(0, carrier.position())
        assertEquals(carrier.capacity(), carrier.limit())
        assertEquals(0, clockReads.get())
        assertNull(fixture.factPort.captureFailure.get())
        assertEquals(1, fixture.factPort.sourceIdentityEvents.size)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents.single())

        fixture.dataSpace = DataSpace.DATASPACE_SRGB
        fixture.deliverSourceFrame()
        assertEquals(2, fixture.factPort.sourceIdentityEvents.size)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents[0])
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents[1])
        val successor = AtomicReference<CaptureReadResult?>()
        assertTrue(
            fixture.owner.read(fixture.plan, opened.sourceIdentity, carrier) { result ->
                check(successor.compareAndSet(null, result))
            },
        )
        fixture.enterPostedCommands()

        val filled = successor.get() as? CaptureReadResult.Filled
            ?: error("Successor sRGB read did not return Filled")
        assertEquals(9L, filled.readbackDurationNanos)
        assertEquals(2, clockReads.get())
        assertNull(fixture.factPort.captureFailure.get())
        assertEquals(2, fixture.factPort.sourceIdentityEvents.size)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents[0])
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentityEvents[1])

        fixture.owner.retire()
        fixture.enterPostedCommands()
        assertNull(fixture.factPort.captureFailure.get())
    }

    // Verification: CAP-03
    // Verification: CAP-04
    @Test
    fun glReadFailureConsumesSourcePoisonsOwnerAndRetiresNamespaceOnce() {
        val readFailure = IllegalStateException("expected glReadPixels failure")
        val fixture = OwnerFixture(
            dataSpace = DataSpace.DATASPACE_SRGB,
            readbackClock = ElapsedRealtimeClock { CONSTANT_READBACK_TIME_NANOS },
            readPixelsFailure = readFailure,
        )
        val carrier = ByteBuffer.allocateDirect(fixture.plan.rgbaCarrierByteCount)
        repeat(carrier.capacity()) { index -> carrier.put(index, CARRIER_SENTINEL) }
        val bytesBefore = carrierBytes(carrier)

        fixture.owner.adoptProjection(fixture.projection)
        assertTrue(fixture.owner.open(fixture.plan))
        fixture.enterPostedCommands()
        val opened = fixture.factPort.openResult.get() as? CaptureOpenResult.Opened
            ?: error("Capture owner did not open")

        fixture.deliverSourceFrame()
        val first = AtomicReference<CaptureReadResult?>()
        assertTrue(
            fixture.owner.read(fixture.plan, opened.sourceIdentity, carrier) { result ->
                check(first.compareAndSet(null, result))
            },
        )
        fixture.enterPostedCommands()

        val poisoned = first.get() as? CaptureReadResult.Failed
            ?: error("GL read failure did not return Failed")
        assertSame(ScreenCaptureProblem.InternalFailure, poisoned.problem)
        assertSame(readFailure, poisoned.cause)
        assertTrue(poisoned.sourceConsumed)
        assertSame(CaptureFailureScope.OwnerInvalidated, poisoned.scope)
        assertEquals(1, fixture.readPixelsCount)
        assertArrayEquals(bytesBefore, carrierBytes(carrier))

        fixture.deliverSourceFrame()
        val reuse = AtomicReference<CaptureReadResult?>()
        assertTrue(
            fixture.owner.read(fixture.plan, opened.sourceIdentity, carrier) { result ->
                check(reuse.compareAndSet(null, result))
            },
        )
        fixture.enterPostedCommands()
        val rejectedReuse = reuse.get() as? CaptureReadResult.Failed
            ?: error("Poisoned owner reuse did not return Failed")
        assertSame(ScreenCaptureProblem.InternalFailure, rejectedReuse.problem)
        assertFalse(rejectedReuse.sourceConsumed)
        assertSame(CaptureFailureScope.OwnerInvalidated, rejectedReuse.scope)
        assertEquals(1, fixture.readPixelsCount)

        val deletionsBeforeRetirement = fixture.glNameDeletionCount
        fixture.retirementEvents.clear()
        fixture.owner.retire()
        fixture.enterPostedCommands()

        assertEquals(deletionsBeforeRetirement, fixture.glNameDeletionCount)
        assertEquals(
            listOf(
                "listener-fence",
                "display-detach",
                "display-release",
                "surface-release",
                "surface-texture-release",
                "egl-unbind",
                "egl-context-destroy",
                "egl-surface-destroy",
                "egl-thread-release",
                "projection-callback-unregister",
                "projection-stop",
            ),
            fixture.retirementEvents,
        )
        assertNull(fixture.factPort.captureFailure.get())
        assertThrows(IllegalStateException::class.java) {
            fixture.owner.read(fixture.plan, opened.sourceIdentity, carrier) { }
        }
        val retiredEvents = fixture.retirementEvents.toList()
        fixture.owner.retire()
        fixture.enterPostedCommands()
        assertEquals(retiredEvents, fixture.retirementEvents)
    }

    // Verification: CAP-05
    @Test
    fun acceptedUnenteredReadReturnsCutoffOnceAndRetirementRejectsReuse() {
        ControlledNonInlineDispatcher().use { dispatcher ->
            fun createCarrier(): ManagedDirectCarrier {
                val candidate = ManagedDirectCarrier(capturePlan().rgbaLayout)
                val created = candidate.allocateIntoPendingOwner() as ManagedDirectCarrier.Creation.Created
                assertSame(candidate, created.carrier)
                return candidate
            }

            val fixture = OwnerFixture(
                dataSpace = DataSpace.DATASPACE_SRGB,
                readbackClock = ElapsedRealtimeClock { CONSTANT_READBACK_TIME_NANOS },
            )
            val encodingOwner = EncodingOwner(dispatcher, MutableElapsedRealtimeClock())
            val carrier = createCarrier()
            val input = carrier.lend(encodingOwner) { } ?: error("Capture carrier did not lend")
            val returnCount = AtomicInteger()
            val result = AtomicReference<CaptureReadResult?>()
            val settlement = AtomicReference<EncodingInput?>()

            try {
                fixture.owner.adoptProjection(fixture.projection)
                assertTrue(fixture.owner.open(fixture.plan))
                fixture.enterPostedCommands()
                val opened = fixture.factPort.openResult.get() as? CaptureOpenResult.Opened
                    ?: error("Capture owner did not open")

                fixture.deliverSourceFrame()
                assertTrue(
                    fixture.owner.read(fixture.plan, opened.sourceIdentity, input.writableView) { returned ->
                        returnCount.incrementAndGet()
                        check(result.compareAndSet(null, returned))
                        check(settlement.compareAndSet(null, carrier.settle(input, CarrierDisposition.Discarded)))
                    },
                )

                fixture.owner.retire()
                val retained = carrier.retireIfIdle() as EncodingRetirement.Retained
                assertNull(retained.cause)
                assertTrue(carrier.ownsCaptureLoan(input))
                assertNull(result.get())
                fixture.enterPostedCommands()

                assertSame(CaptureReadResult.CutoffInert, result.get())
                assertEquals(1, returnCount.get())
                assertSame(input, settlement.get())
                assertFalse(carrier.ownsCaptureLoan(input))
                assertThrows(IllegalStateException::class.java) {
                    fixture.owner.read(fixture.plan, opened.sourceIdentity, input.writableView) { }
                }
                assertNull(fixture.factPort.captureFailure.get())
            } finally {
                fixture.owner.retire()
                fixture.enterPostedCommands()
            }
        }
    }

    private class OwnerFixture(
        var dataSpace: Int = DataSpace.DATASPACE_DISPLAY_P3,
        readbackClock: ElapsedRealtimeClock =
            ElapsedRealtimeClock { throw AssertionError("Display P3 read sampled the readback clock") },
        private val readPixelsFailure: Exception? = null,
    ) {
        val plan = capturePlan()
        val projection: MediaProjection = mockk()
        val factPort = RecordingFactPort()
        val retirementEvents = mutableListOf<String>()
        var readPixelsCount = 0
            private set
        var glNameDeletionCount = 0
            private set
        private val mainHandler = Handler(Looper.getMainLooper())
        private val captureThread: HandlerThread = mockk()
        private val projectionPlatform: ProjectionPlatform = mockk(relaxed = true)
        private val eglPlatform: EglPlatform = mockk()
        private val glesPlatform: GlesPlatform = mockk(relaxed = true)
        private val targetPlatform: TargetPlatform = mockk(relaxed = true)
        private val virtualDisplay: VirtualDisplay = mockk()
        private val eglDisplay: EGLDisplay = mockk()
        private val eglConfig: EGLConfig = mockk()
        private val eglContext: EGLContext = mockk()
        private val eglSurface: EGLSurface = mockk()
        private val surfaceTexture: SurfaceTexture = mockk()
        private val surface: Surface = mockk()
        private var frameListener: SurfaceTexture.OnFrameAvailableListener? = null
        private var nextGlName = 1
        private var currentDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var currentContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var currentSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        val owner: SessionCaptureOwner

        init {
            configureProjection()
            configureEgl()
            configureGles()
            configureTarget()
            every { captureThread.quitSafely() } returns true
            val poster = object : HandlerTaskPoster {
                override fun post(handler: Handler, task: Runnable): Boolean = handler.post(task)
                override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
                    handler.postDelayed(task, delayMillis)

                override fun removeCallbacks(handler: Handler, task: Runnable) = handler.removeCallbacks(task)
            }
            owner = SessionCaptureOwner(
                captureThread = captureThread,
                captureHandler = mainHandler,
                controlHandler = mainHandler,
                handlerTaskPoster = poster,
                factPort = factPort,
                readbackClock = readbackClock,
                platformSdkInt = Build.VERSION_CODES.TIRAMISU,
                projectionPlatform = projectionPlatform,
                eglPlatform = eglPlatform,
                glesPlatform = glesPlatform,
                targetPlatform = targetPlatform,
            )
        }

        fun enterPostedCommands() {
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun deliverSourceFrame() {
            val listener = checkNotNull(frameListener)
            check(mainHandler.post { listener.onFrameAvailable(surfaceTexture) })
            enterPostedCommands()
        }

        private fun configureProjection() {
            every { projectionPlatform.createVirtualDisplay(projection, any(), any(), any(), surface) } returns virtualDisplay
            every { projectionPlatform.setSurface(virtualDisplay, null) } answers {
                retirementEvents += "display-detach"
            }
            every { projectionPlatform.release(virtualDisplay) } answers {
                retirementEvents += "display-release"
            }
            every { projectionPlatform.unregisterCallback(projection, any()) } answers {
                retirementEvents += "projection-callback-unregister"
            }
            every { projectionPlatform.stop(projection) } answers {
                retirementEvents += "projection-stop"
            }
        }

        private fun configureEgl() {
            every { eglPlatform.currentDisplay } answers { currentDisplay }
            every { eglPlatform.currentContext } answers { currentContext }
            every { eglPlatform.currentReadSurface } answers { currentSurface }
            every { eglPlatform.currentDrawSurface } answers { currentSurface }
            every { eglPlatform.getDisplay() } returns eglDisplay
            every { eglPlatform.initialize(eglDisplay, any()) } answers {
                secondArg<IntArray>()[0] = 1
                secondArg<IntArray>()[1] = 5
                true
            }
            every { eglPlatform.chooseConfig(eglDisplay, any(), any(), any()) } answers {
                arg<Array<EGLConfig?>>(2)[0] = eglConfig
                arg<IntArray>(3)[0] = 1
                true
            }
            every { eglPlatform.createContext(eglDisplay, eglConfig, any()) } returns eglContext
            every { eglPlatform.createPbufferSurface(eglDisplay, eglConfig, any()) } returns eglSurface
            every { eglPlatform.makeCurrent(eglDisplay, any(), any()) } answers {
                val returnedContext = thirdArg<EGLContext>()
                if (returnedContext === EGL14.EGL_NO_CONTEXT) {
                    retirementEvents += "egl-unbind"
                    currentDisplay = EGL14.EGL_NO_DISPLAY
                    currentContext = EGL14.EGL_NO_CONTEXT
                    currentSurface = EGL14.EGL_NO_SURFACE
                } else {
                    currentDisplay = eglDisplay
                    currentContext = eglContext
                    currentSurface = eglSurface
                }
                true
            }
            every { eglPlatform.destroyContext(eglDisplay, eglContext) } answers {
                retirementEvents += "egl-context-destroy"
                true
            }
            every { eglPlatform.destroySurface(eglDisplay, eglSurface) } answers {
                retirementEvents += "egl-surface-destroy"
                true
            }
            every { eglPlatform.releaseThread() } answers {
                retirementEvents += "egl-thread-release"
                true
            }
            every { eglPlatform.getError() } returns EGL14.EGL_SUCCESS
        }

        private fun configureGles() {
            every { glesPlatform.getError() } returns GLES20.GL_NO_ERROR
            every { glesPlatform.getInteger(any(), any()) } answers {
                when (firstArg<Int>()) {
                    GLES20.GL_MAX_TEXTURE_SIZE -> secondArg<IntArray>()[0] = 4_096
                    GLES20.GL_MAX_VIEWPORT_DIMS -> {
                        secondArg<IntArray>()[0] = 4_096
                        secondArg<IntArray>()[1] = 4_096
                    }

                    GLES20.GL_RED_BITS, GLES20.GL_GREEN_BITS, GLES20.GL_BLUE_BITS -> secondArg<IntArray>()[0] = 8
                }
            }
            every { glesPlatform.getShaderPrecisionFormat(any(), any()) } answers {
                firstArg<IntArray>().fill(0)
                secondArg<IntArray>().fill(0)
            }
            every { glesPlatform.genTextures(any()) } answers { firstArg<IntArray>()[0] = nextGlName++ }
            every { glesPlatform.genFramebuffers(any()) } answers { firstArg<IntArray>()[0] = nextGlName++ }
            every { glesPlatform.createShader(any()) } answers { nextGlName++ }
            every { glesPlatform.getShaderStatus(any(), any()) } answers { secondArg<IntArray>()[0] = GLES20.GL_TRUE }
            every { glesPlatform.createProgram() } answers { nextGlName++ }
            every { glesPlatform.getProgramStatus(any(), any()) } answers { secondArg<IntArray>()[0] = GLES20.GL_TRUE }
            every { glesPlatform.getUniformLocation(any(), any()) } returns 1
            every { glesPlatform.checkFramebufferStatus() } returns GLES20.GL_FRAMEBUFFER_COMPLETE
            every { glesPlatform.readPixels(any(), any(), any()) } answers {
                readPixelsCount += 1
                readPixelsFailure?.let { throw it }
            }
            every { glesPlatform.deleteTextures(any()) } answers {
                glNameDeletionCount += 1
            }
            every { glesPlatform.deleteFramebuffers(any()) } answers {
                glNameDeletionCount += 1
            }
            every { glesPlatform.deleteProgram(any()) } answers {
                glNameDeletionCount += 1
            }
            every { glesPlatform.deleteShader(any()) } answers {
                glNameDeletionCount += 1
            }
        }

        private fun configureTarget() {
            every { targetPlatform.createSurfaceTexture(any()) } returns surfaceTexture
            every { targetPlatform.createSurface(surfaceTexture) } returns surface
            every { targetPlatform.setFrameListener(surfaceTexture, any(), any()) } answers {
                frameListener = secondArg()
            }
            every { targetPlatform.clearFrameListener(surfaceTexture) } answers {
                frameListener = null
                retirementEvents += "listener-fence"
            }
            every { targetPlatform.getTransformMatrix(surfaceTexture, any()) } answers {
                val matrix = secondArg<FloatArray>()
                matrix.fill(0f)
                matrix[0] = 1f
                matrix[5] = 1f
                matrix[10] = 1f
                matrix[15] = 1f
            }
            every { targetPlatform.dataSpace(surfaceTexture) } answers { dataSpace }
            every { targetPlatform.releaseSurface(surface) } answers {
                retirementEvents += "surface-release"
            }
            every { targetPlatform.releaseSurfaceTexture(surfaceTexture) } answers {
                retirementEvents += "surface-texture-release"
            }
        }
    }

    private class RecordingFactPort : SessionCaptureFactPort {
        val openResult = AtomicReference<CaptureOpenResult?>()
        val sourceIdentityEvents = mutableListOf<CaptureSourceIdentity>()
        val captureFailure = AtomicReference<Exception?>()

        override fun onOpenReturned(result: CaptureOpenResult) {
            check(openResult.compareAndSet(null, result))
        }

        override fun onApplyReturned(result: CaptureApplyResult) = error("Unexpected Capture apply")

        override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) {
            sourceIdentityEvents += sourceIdentity
        }

        override fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity) = error("Unexpected projection stop")
        override fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) =
            error("Unexpected content resize")

        override fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean) =
            error("Unexpected visibility change")

        override fun onCaptureFailure(failure: Exception) {
            captureFailure.compareAndSet(null, failure)
        }
    }

    private companion object {
        private const val CARRIER_SENTINEL: Byte = 0x5A
        private const val CONSTANT_READBACK_TIME_NANOS: Long = 17L

        private fun carrierBytes(carrier: ByteBuffer): ByteArray =
            ByteArray(carrier.capacity()) { index -> carrier.get(index) }

        private fun capturePlan(): CapturePlan = CapturePlan(
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
