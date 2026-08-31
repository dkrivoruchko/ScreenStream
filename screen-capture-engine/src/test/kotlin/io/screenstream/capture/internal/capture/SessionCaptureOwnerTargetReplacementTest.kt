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
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
/*
 * Contract: Target replacement reports its exact owner-visible result and preserves or quarantines the exact graph
 * roots justified by platform settlement. Paused-Looper drains only arrange accepted Capture work and callbacks.
 * Private state, reflection, GC, queue/scheduler counts, and incidental global call order are not verdicts.
 */
internal class SessionCaptureOwnerTargetReplacementTest {
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

    // Verification: TGT-01
    @Test
    fun preAttachmentResourceDenialRollsBackCandidateAndPreservesOldTarget() {
        val fixture = OwnerFixture()
        val opened = fixture.open()
        val denial = Surface.OutOfResourcesException("candidate Surface denied")
        fixture.targetPlatform.candidateSurfaceCreationFailure = denial

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.ResourceDenied
            ?: error("Target replacement did not return ResourceDenied")
        assertSame(denial, result.cause)
        assertEquals(listOf(fixture.candidateSurfaceTexture), fixture.targetPlatform.releasedSurfaceTextures)
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        assertEquals(1, fixture.gles.deletedTextures.count { it == CANDIDATE_OES_TEXTURE })
        assertEquals(0, fixture.projectionPlatform.resizeCount)
        assertEquals(0, fixture.projectionPlatform.replacementSurfaceCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        assertSame(opened.sourceIdentity, fixture.factPort.sourceIdentities.single())
        val carrier = ByteBuffer.allocateDirect(fixture.initialPlan.rgbaCarrierByteCount)
        var readResult: CaptureReadResult? = null
        assertTrue(
            fixture.owner.read(fixture.initialPlan, opened.sourceIdentity, carrier) { returned ->
                check(readResult == null)
                readResult = returned
            },
        )
        fixture.enterAcceptedWork()

        val filled = readResult as? CaptureReadResult.Filled
            ?: error("The preserved Target did not return a filled read")
        assertEquals(READBACK_DURATION_NANOS, filled.readbackDurationNanos)
        assertEquals(listOf(fixture.initialSurfaceTexture), fixture.targetPlatform.updatedSurfaceTextures)
        assertEquals(1, fixture.gles.readPixelsCount)
        assertTrue(fixture.factPort.captureFailures.isEmpty())

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(listOf(fixture.initialSurface), fixture.targetPlatform.releasedSurfaces)
        assertEquals(2, fixture.targetPlatform.releasedSurfaceTextures.size)
        assertEquals(1, fixture.targetPlatform.releasedSurfaceTextures.count { it === fixture.initialSurfaceTexture })
        assertEquals(1, fixture.targetPlatform.releasedSurfaceTextures.count { it === fixture.candidateSurfaceTexture })
        assertEquals(1, fixture.gles.deletedTextures.count { it == INITIAL_OES_TEXTURE })
        assertEquals(1, fixture.gles.deletedTextures.count { it == CANDIDATE_OES_TEXTURE })
        assertEquals(1, fixture.gles.deletedTextures.count { it == OUTPUT_TEXTURE })
        assertEquals(1, fixture.projectionPlatform.detachSurfaceCount)
        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
        assertEquals(1, fixture.egl.unbindCount)
        assertEquals(1, fixture.egl.destroyContextCount)
        assertEquals(1, fixture.egl.destroySurfaceCount)
        assertEquals(1, fixture.egl.releaseThreadCount)
        assertEquals(1, fixture.captureThreadQuitCount)
    }

    // Verification: TGT-02
    @Test
    fun ambiguousSurfaceReplacementWithUnprovedDisplayReleaseQuarantinesBothTargets() {
        val fixture = OwnerFixture()
        fixture.open()
        val replacementFailure = IllegalStateException("setSurface effect then failure")
        val displayReleaseFailure = IllegalStateException("display release unproved")
        fixture.projectionPlatform.replacementFailure = replacementFailure
        fixture.projectionPlatform.releaseFailure = displayReleaseFailure

        assertTrue(fixture.owner.apply(fixture.replacementPlan))
        fixture.enterAcceptedWork()

        val result = fixture.factPort.applyResults.single() as? CaptureApplyResult.Failed
            ?: error("Ambiguous Target replacement did not return Failed")
        assertSame(ScreenCaptureProblem.InternalFailure, result.problem)
        assertSame(replacementFailure, result.cause)
        assertSame(CaptureFailureScope.OwnerInvalidated, result.scope)
        assertSame(fixture.candidateSurface, fixture.projectionPlatform.platformAttachedSurface)
        assertEquals(1, fixture.projectionPlatform.resizeCount)
        assertEquals(1, fixture.projectionPlatform.replacementSurfaceCount)

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertTrue(fixture.factPort.sourceIdentities.isEmpty())
        assertTrue(fixture.targetPlatform.updatedSurfaceTextures.isEmpty())
        assertEquals(0, fixture.gles.readPixelsCount)

        fixture.owner.retire()
        fixture.enterAcceptedWork()

        assertEquals(1, fixture.projectionPlatform.releaseCount)
        assertEquals(0, fixture.projectionPlatform.detachSurfaceCount)
        assertTrue(fixture.targetPlatform.releasedSurfaces.isEmpty())
        assertTrue(fixture.targetPlatform.releasedSurfaceTextures.isEmpty())
        assertFalse(fixture.gles.deletedTextures.contains(INITIAL_OES_TEXTURE))
        assertFalse(fixture.gles.deletedTextures.contains(CANDIDATE_OES_TEXTURE))
        assertEquals(1, fixture.gles.deletedTextures.count { it == OUTPUT_TEXTURE })
        assertEquals(0, fixture.egl.unbindCount)
        assertEquals(0, fixture.egl.destroyContextCount)
        assertEquals(0, fixture.egl.destroySurfaceCount)
        assertEquals(0, fixture.egl.releaseThreadCount)
        assertEquals(1, fixture.targetPlatform.initialListenerRemovalCount)
        assertEquals(1, fixture.targetPlatform.candidateListenerRemovalCount)
        assertEquals(1, fixture.projectionPlatform.unregisterCount)
        assertEquals(1, fixture.projectionPlatform.stopCount)
        assertEquals(1, fixture.captureThreadQuitCount)
        assertTrue(fixture.factPort.captureFailures.isEmpty())

        fixture.deliverSourceFrame(fixture.initialSurfaceTexture)
        fixture.deliverSourceFrame(fixture.candidateSurfaceTexture)
        assertTrue(fixture.factPort.sourceIdentities.isEmpty())
    }

    private class OwnerFixture {
        val initialPlan: CapturePlan = capturePlan(sourceWidthPx = 4, sourceHeightPx = 4)
        val replacementPlan: CapturePlan = capturePlan(sourceWidthPx = 6, sourceHeightPx = 4)
        val projection: MediaProjection = mockk()
        val virtualDisplay: VirtualDisplay = mockk()
        val initialSurfaceTexture: SurfaceTexture = mockk()
        val candidateSurfaceTexture: SurfaceTexture = mockk()
        val initialSurface: Surface = mockk()
        val candidateSurface: Surface = mockk()
        val factPort = RecordingFactPort()
        val targetPlatform = RecordingTargetPlatform(
            initialSurfaceTexture = initialSurfaceTexture,
            candidateSurfaceTexture = candidateSurfaceTexture,
            initialSurface = initialSurface,
            candidateSurface = candidateSurface,
        )
        val projectionPlatform = RecordingProjectionPlatform(
            projection = projection,
            display = virtualDisplay,
            initialSurface = initialSurface,
            candidateSurface = candidateSurface,
        )
        val egl = RecordingEglPlatform()
        val gles = RecordingGlesPlatform()
        private val captureHandler = Handler(Looper.getMainLooper())
        private val captureThread: HandlerThread = mockk()
        var captureThreadQuitCount = 0
            private set

        val owner: SessionCaptureOwner

        init {
            every { captureThread.quitSafely() } answers {
                captureThreadQuitCount += 1
                true
            }
            val poster = object : HandlerTaskPoster {
                override fun post(handler: Handler, task: Runnable): Boolean = handler.post(task)
                override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
                    handler.postDelayed(task, delayMillis)

                override fun removeCallbacks(handler: Handler, task: Runnable) = handler.removeCallbacks(task)
            }
            var readbackTimeNanos = READBACK_START_NANOS
            owner = SessionCaptureOwner(
                captureThread = captureThread,
                captureHandler = captureHandler,
                controlHandler = captureHandler,
                handlerTaskPoster = poster,
                factPort = factPort,
                readbackClock = ElapsedRealtimeClock {
                    readbackTimeNanos.also { readbackTimeNanos += READBACK_DURATION_NANOS }
                },
                platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                projectionPlatform = projectionPlatform,
                eglPlatform = egl.platform,
                glesPlatform = gles.platform,
                targetPlatform = targetPlatform,
            )
        }

        fun open(): CaptureOpenResult.Opened {
            owner.adoptProjection(projection)
            check(owner.open(initialPlan))
            enterAcceptedWork()
            return factPort.openResult as? CaptureOpenResult.Opened
                ?: error("Capture owner did not open")
        }

        fun enterAcceptedWork() {
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun deliverSourceFrame(surfaceTexture: SurfaceTexture) {
            val listener = targetPlatform.listenerFor(surfaceTexture)
            check(captureHandler.post { listener.onFrameAvailable(surfaceTexture) })
            enterAcceptedWork()
        }
    }

    private class RecordingFactPort : SessionCaptureFactPort {
        var openResult: CaptureOpenResult? = null
            private set
        val applyResults = mutableListOf<CaptureApplyResult>()
        val sourceIdentities = mutableListOf<CaptureSourceIdentity>()
        val captureFailures = mutableListOf<Exception>()

        override fun onOpenReturned(result: CaptureOpenResult) {
            check(openResult == null)
            openResult = result
        }

        override fun onApplyReturned(result: CaptureApplyResult) {
            applyResults += result
        }

        override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) {
            sourceIdentities += sourceIdentity
        }

        override fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity) =
            error("Unexpected projection stop")

        override fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) =
            error("Unexpected captured-content resize")

        override fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean) =
            error("Unexpected captured-content visibility")

        override fun onCaptureFailure(failure: Exception) {
            captureFailures += failure
        }
    }

    private class RecordingProjectionPlatform(
        private val projection: MediaProjection,
        private val display: VirtualDisplay,
        private val initialSurface: Surface,
        private val candidateSurface: Surface,
    ) : ProjectionPlatform {
        var replacementFailure: Exception? = null
        var releaseFailure: Exception? = null
        var platformAttachedSurface: Surface? = null
            private set
        var resizeCount = 0
            private set
        var replacementSurfaceCount = 0
            private set
        var detachSurfaceCount = 0
            private set
        var releaseCount = 0
            private set
        var unregisterCount = 0
            private set
        var stopCount = 0
            private set

        override fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler) {
            check(projection === this.projection)
        }

        override fun createVirtualDisplay(
            projection: MediaProjection,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            surface: Surface,
        ): VirtualDisplay {
            check(projection === this.projection)
            check(surface === initialSurface)
            platformAttachedSurface = surface
            return display
        }

        override fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int) {
            check(display === this.display)
            resizeCount += 1
        }

        override fun setSurface(display: VirtualDisplay, surface: Surface?) {
            check(display === this.display)
            when {
                surface === candidateSurface -> {
                    replacementSurfaceCount += 1
                    platformAttachedSurface = candidateSurface
                    replacementFailure?.let { throw it }
                }

                surface == null -> {
                    detachSurfaceCount += 1
                    platformAttachedSurface = null
                }

                else -> error("Unexpected VirtualDisplay Surface")
            }
        }

        override fun release(display: VirtualDisplay) {
            check(display === this.display)
            releaseCount += 1
            releaseFailure?.let { throw it }
            platformAttachedSurface = null
        }

        override fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback) {
            check(projection === this.projection)
            unregisterCount += 1
        }

        override fun stop(projection: MediaProjection) {
            check(projection === this.projection)
            stopCount += 1
        }
    }

    private class RecordingTargetPlatform(
        private val initialSurfaceTexture: SurfaceTexture,
        private val candidateSurfaceTexture: SurfaceTexture,
        private val initialSurface: Surface,
        private val candidateSurface: Surface,
    ) : TargetPlatform {
        var candidateSurfaceCreationFailure: Surface.OutOfResourcesException? = null
        val releasedSurfaces = mutableListOf<Surface>()
        val releasedSurfaceTextures = mutableListOf<SurfaceTexture>()
        val updatedSurfaceTextures = mutableListOf<SurfaceTexture>()
        var initialListenerRemovalCount = 0
            private set
        var candidateListenerRemovalCount = 0
            private set
        private var initialListener: SurfaceTexture.OnFrameAvailableListener? = null
        private var candidateListener: SurfaceTexture.OnFrameAvailableListener? = null

        override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture = when (oesTextureName) {
            INITIAL_OES_TEXTURE -> initialSurfaceTexture
            CANDIDATE_OES_TEXTURE -> candidateSurfaceTexture
            else -> error("Unexpected OES texture name")
        }

        override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) = Unit

        override fun createSurface(surfaceTexture: SurfaceTexture): Surface = when {
            surfaceTexture === initialSurfaceTexture -> initialSurface
            surfaceTexture === candidateSurfaceTexture -> {
                candidateSurfaceCreationFailure?.let { throw it }
                candidateSurface
            }

            else -> error("Unexpected SurfaceTexture")
        }

        override fun setFrameListener(
            surfaceTexture: SurfaceTexture,
            listener: SurfaceTexture.OnFrameAvailableListener,
            handler: Handler,
        ) {
            when {
                surfaceTexture === initialSurfaceTexture -> initialListener = listener
                surfaceTexture === candidateSurfaceTexture -> candidateListener = listener
                else -> error("Unexpected SurfaceTexture listener")
            }
        }

        override fun clearFrameListener(surfaceTexture: SurfaceTexture) {
            when {
                surfaceTexture === initialSurfaceTexture -> initialListenerRemovalCount += 1
                surfaceTexture === candidateSurfaceTexture -> candidateListenerRemovalCount += 1
                else -> error("Unexpected SurfaceTexture listener removal")
            }
        }

        override fun updateTexImage(surfaceTexture: SurfaceTexture) {
            updatedSurfaceTextures += surfaceTexture
        }

        override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) {
            destination.fill(0f)
            destination[0] = 1f
            destination[5] = 1f
            destination[10] = 1f
            destination[15] = 1f
        }

        override fun dataSpace(surfaceTexture: SurfaceTexture): Int = DataSpace.DATASPACE_UNKNOWN

        override fun releaseSurface(surface: Surface) {
            releasedSurfaces += surface
        }

        override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
            releasedSurfaceTextures += surfaceTexture
        }

        fun listenerFor(surfaceTexture: SurfaceTexture): SurfaceTexture.OnFrameAvailableListener = when {
            surfaceTexture === initialSurfaceTexture -> checkNotNull(initialListener)
            surfaceTexture === candidateSurfaceTexture -> checkNotNull(candidateListener)
            else -> error("Unexpected SurfaceTexture listener lookup")
        }
    }

    private class RecordingEglPlatform {
        val platform: EglPlatform = mockk()
        private val display: EGLDisplay = mockk()
        private val config: EGLConfig = mockk()
        private val context: EGLContext = mockk()
        private val pbuffer: EGLSurface = mockk()
        private var currentDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var currentContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var currentSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var unbindCount = 0
            private set
        var destroyContextCount = 0
            private set
        var destroySurfaceCount = 0
            private set
        var releaseThreadCount = 0
            private set

        init {
            every { platform.currentDisplay } answers { currentDisplay }
            every { platform.currentContext } answers { currentContext }
            every { platform.currentReadSurface } answers { currentSurface }
            every { platform.currentDrawSurface } answers { currentSurface }
            every { platform.getDisplay() } returns display
            every { platform.initialize(display, any()) } answers {
                secondArg<IntArray>()[0] = 1
                secondArg<IntArray>()[1] = 5
                true
            }
            every { platform.chooseConfig(display, any(), any(), any()) } answers {
                arg<Array<EGLConfig?>>(2)[0] = config
                arg<IntArray>(3)[0] = 1
                true
            }
            every { platform.createContext(display, config, any()) } returns context
            every { platform.createPbufferSurface(display, config, any()) } returns pbuffer
            every { platform.makeCurrent(display, any(), any()) } answers {
                val returnedContext = thirdArg<EGLContext>()
                if (returnedContext === EGL14.EGL_NO_CONTEXT) {
                    unbindCount += 1
                    currentDisplay = EGL14.EGL_NO_DISPLAY
                    currentContext = EGL14.EGL_NO_CONTEXT
                    currentSurface = EGL14.EGL_NO_SURFACE
                } else {
                    currentDisplay = display
                    currentContext = context
                    currentSurface = pbuffer
                }
                true
            }
            every { platform.destroyContext(display, context) } answers {
                destroyContextCount += 1
                true
            }
            every { platform.destroySurface(display, pbuffer) } answers {
                destroySurfaceCount += 1
                true
            }
            every { platform.releaseThread() } answers {
                releaseThreadCount += 1
                true
            }
            every { platform.getError() } returns EGL14.EGL_SUCCESS
        }
    }

    private class RecordingGlesPlatform {
        val platform: GlesPlatform = mockk(relaxed = true)
        val deletedTextures = mutableListOf<Int>()
        var readPixelsCount = 0
            private set
        private val generatedTextureNames = intArrayOf(INITIAL_OES_TEXTURE, OUTPUT_TEXTURE, CANDIDATE_OES_TEXTURE)
        private var nextTextureIndex = 0
        private var nextFramebufferName = FRAMEBUFFER_NAME
        private var nextShaderName = FIRST_SHADER_NAME
        private var nextProgramName = PROGRAM_NAME

        init {
            every { platform.getError() } returns GLES20.GL_NO_ERROR
            every { platform.getInteger(any(), any()) } answers {
                when (firstArg<Int>()) {
                    GLES20.GL_MAX_TEXTURE_SIZE -> secondArg<IntArray>()[0] = 4_096
                    GLES20.GL_MAX_VIEWPORT_DIMS -> {
                        secondArg<IntArray>()[0] = 4_096
                        secondArg<IntArray>()[1] = 4_096
                    }

                    GLES20.GL_RED_BITS,
                    GLES20.GL_GREEN_BITS,
                    GLES20.GL_BLUE_BITS,
                        -> secondArg<IntArray>()[0] = 8

                    else -> error("Unexpected GLES integer query")
                }
            }
            every { platform.getShaderPrecisionFormat(any(), any()) } answers {
                firstArg<IntArray>().fill(0)
                secondArg<IntArray>().fill(0)
            }
            every { platform.genTextures(any()) } answers {
                firstArg<IntArray>()[0] = generatedTextureNames[nextTextureIndex++]
            }
            every { platform.deleteTextures(any()) } answers {
                deletedTextures += firstArg<IntArray>()[0]
            }
            every { platform.genFramebuffers(any()) } answers {
                firstArg<IntArray>()[0] = nextFramebufferName++
            }
            every { platform.createShader(any()) } answers { nextShaderName++ }
            every { platform.getShaderStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { platform.createProgram() } answers { nextProgramName++ }
            every { platform.getProgramStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { platform.getUniformLocation(any(), any()) } returns 1
            every { platform.checkFramebufferStatus() } returns GLES20.GL_FRAMEBUFFER_COMPLETE
            every { platform.readPixels(any(), any(), any()) } answers {
                readPixelsCount += 1
            }
        }
    }

    private companion object {
        private const val INITIAL_OES_TEXTURE = 101
        private const val OUTPUT_TEXTURE = 102
        private const val CANDIDATE_OES_TEXTURE = 103
        private const val FRAMEBUFFER_NAME = 201
        private const val FIRST_SHADER_NAME = 301
        private const val PROGRAM_NAME = 401
        private const val READBACK_START_NANOS = 10L
        private const val READBACK_DURATION_NANOS = 7L

        private fun capturePlan(sourceWidthPx: Int, sourceHeightPx: Int): CapturePlan = CapturePlan(
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.ZERO,
            appliedSourceRect = ImageRect.create(0, 0, sourceWidthPx, sourceHeightPx),
            rotation = Rotation.Degrees0,
            mirror = Mirror.None,
            colorMode = ColorMode.Color,
            sourceWidthPx = sourceWidthPx,
            sourceHeightPx = sourceHeightPx,
            densityDpi = 320,
            targetMode = CaptureTargetMode.Full,
            targetWidthPx = sourceWidthPx,
            targetHeightPx = sourceHeightPx,
            rgbaLayout = Rgba8888Layout.create(widthPx = 2, heightPx = 2),
        )
    }
}
