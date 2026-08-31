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
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.SourceRegion
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
    fun exactDisplayP3ReturnsConsumedOperationLocalFailure() {
        val fixture = OwnerFixture()
        val carrier = ByteBuffer.allocateDirect(fixture.plan.rgbaCarrierByteCount)
        repeat(carrier.capacity()) { index -> carrier.put(index, CARRIER_SENTINEL) }
        val bytesBefore = carrierBytes(carrier)

        fixture.owner.adoptProjection(fixture.projection)
        assertTrue(fixture.owner.open(fixture.plan))
        fixture.enterPostedCommands()
        val opened = fixture.factPort.openResult.get() as? CaptureOpenResult.Opened
            ?: error("Capture owner did not open")

        fixture.deliverSourceFrame()
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentity.get())
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
        assertNull(fixture.factPort.captureFailure.get())

        fixture.owner.retire()
        fixture.enterPostedCommands()
        assertNull(fixture.factPort.captureFailure.get())
    }

    // Verification: CAP-05
    @Test
    fun exactReadSettlesOnlyItsInputOnceAndRetirementMakesTheLateReturnCleanupOnly() {
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
            val firstCarrier = createCarrier()
            val firstInput = firstCarrier.lend(encodingOwner) { } ?: error("First carrier did not lend")
            val foreignCarrier = createCarrier()
            val foreignInput = foreignCarrier.lend(encodingOwner) { } ?: error("Foreign carrier did not lend")
            val firstReturnCount = AtomicInteger()
            val firstResult = AtomicReference<CaptureReadResult?>()
            val firstSettlement = AtomicReference<EncodingInput?>()

            try {
                fixture.owner.adoptProjection(fixture.projection)
                assertTrue(fixture.owner.open(fixture.plan))
                fixture.enterPostedCommands()
                val opened = fixture.factPort.openResult.get() as? CaptureOpenResult.Opened
                    ?: error("Capture owner did not open")

                assertNull(firstCarrier.settle(foreignInput, CarrierDisposition.Discarded))
                assertTrue(firstCarrier.ownsCaptureLoan(firstInput))
                fixture.deliverSourceFrame()
                assertTrue(
                    fixture.owner.read(fixture.plan, opened.sourceIdentity, firstInput.writableView) { result ->
                        firstReturnCount.incrementAndGet()
                        check(firstResult.compareAndSet(null, result))
                        check(firstSettlement.compareAndSet(null, firstCarrier.settle(firstInput, CarrierDisposition.Filled)))
                    },
                )
                fixture.enterPostedCommands()

                val filled = firstResult.get() as? CaptureReadResult.Filled
                    ?: error("Exact sRGB Capture read did not return Filled")
                assertEquals(0L, filled.readbackDurationNanos)
                assertEquals(1, firstReturnCount.get())
                assertSame(firstInput, firstSettlement.get())
                assertTrue(firstCarrier.ownsReadyLoan(firstInput))
                assertNull(firstCarrier.settle(firstInput, CarrierDisposition.Filled))
                assertSame(firstInput, firstCarrier.discardReady(firstInput))
                assertSame(foreignInput, foreignCarrier.settle(foreignInput, CarrierDisposition.Discarded))
                assertSame(EncodingRetirement.Closed, firstCarrier.retireIfIdle())
                assertSame(EncodingRetirement.Closed, foreignCarrier.retireIfIdle())
                assertNull(firstCarrier.lend(encodingOwner) { })

                fixture.deliverSourceFrame()
                val lateCarrier = createCarrier()
                val lateInput = lateCarrier.lend(encodingOwner) { } ?: error("Late carrier did not lend")
                val lateReturnCount = AtomicInteger()
                val lateResult = AtomicReference<CaptureReadResult?>()
                val lateSettlement = AtomicReference<EncodingInput?>()
                assertTrue(
                    fixture.owner.read(fixture.plan, opened.sourceIdentity, lateInput.writableView) { result ->
                        lateReturnCount.incrementAndGet()
                        check(lateResult.compareAndSet(null, result))
                        check(lateSettlement.compareAndSet(null, lateCarrier.settle(lateInput, CarrierDisposition.Discarded)))
                    },
                )

                fixture.owner.retire()
                val retained = lateCarrier.retireIfIdle() as EncodingRetirement.Retained
                assertNull(retained.cause)
                assertTrue(lateCarrier.ownsCaptureLoan(lateInput))
                assertNull(lateResult.get())
                fixture.enterPostedCommands()

                assertSame(CaptureReadResult.CutoffInert, lateResult.get())
                assertEquals(1, lateReturnCount.get())
                assertSame(lateInput, lateSettlement.get())
                assertFalse(lateCarrier.ownsCaptureLoan(lateInput))
                assertTrue(lateCarrier.isIdle)
                assertNull(lateCarrier.settle(lateInput, CarrierDisposition.Discarded))
                assertNull(lateCarrier.settle(foreignInput, CarrierDisposition.Discarded))
                assertSame(EncodingRetirement.Closed, lateCarrier.retireIfIdle())
                assertNull(lateCarrier.lend(encodingOwner) { })
                assertThrows(IllegalStateException::class.java) {
                    fixture.owner.read(fixture.plan, opened.sourceIdentity, lateInput.writableView) { }
                }
                assertNull(fixture.factPort.captureFailure.get())
            } finally {
                fixture.owner.retire()
                fixture.enterPostedCommands()
            }
        }
    }

    private class OwnerFixture(
        private val dataSpace: Int = DataSpace.DATASPACE_DISPLAY_P3,
        readbackClock: ElapsedRealtimeClock =
            ElapsedRealtimeClock { throw AssertionError("Display P3 read sampled the readback clock") },
    ) {
        val plan = capturePlan()
        val projection: MediaProjection = mockk()
        val factPort = RecordingFactPort()
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
            every { eglPlatform.destroyContext(eglDisplay, eglContext) } returns true
            every { eglPlatform.destroySurface(eglDisplay, eglSurface) } returns true
            every { eglPlatform.releaseThread() } returns true
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
        }

        private fun configureTarget() {
            every { targetPlatform.createSurfaceTexture(any()) } returns surfaceTexture
            every { targetPlatform.createSurface(surfaceTexture) } returns surface
            every { targetPlatform.setFrameListener(surfaceTexture, any(), any()) } answers {
                frameListener = secondArg()
            }
            every { targetPlatform.getTransformMatrix(surfaceTexture, any()) } answers {
                val matrix = secondArg<FloatArray>()
                matrix.fill(0f)
                matrix[0] = 1f
                matrix[5] = 1f
                matrix[10] = 1f
                matrix[15] = 1f
            }
            every { targetPlatform.dataSpace(surfaceTexture) } returns dataSpace
        }
    }

    private class RecordingFactPort : SessionCaptureFactPort {
        val openResult = AtomicReference<CaptureOpenResult?>()
        val sourceIdentity = AtomicReference<CaptureSourceIdentity?>()
        val captureFailure = AtomicReference<Exception?>()

        override fun onOpenReturned(result: CaptureOpenResult) {
            check(openResult.compareAndSet(null, result))
        }

        override fun onApplyReturned(result: CaptureApplyResult) = error("Unexpected Capture apply")

        override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) {
            val existing = this.sourceIdentity.get()
            check(existing == null || existing === sourceIdentity)
            this.sourceIdentity.set(sourceIdentity)
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
