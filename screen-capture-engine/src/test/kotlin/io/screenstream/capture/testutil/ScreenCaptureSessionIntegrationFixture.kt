package io.screenstream.capture.testutil

import android.graphics.BitmapFactory
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
import android.os.Handler
import android.view.Surface
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.screenstream.capture.CaptureOutputInfo
import io.screenstream.capture.EncodedFrame
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.internal.capture.EglPlatform
import io.screenstream.capture.internal.capture.GlesPlatform
import io.screenstream.capture.internal.capture.ProjectionPlatform
import io.screenstream.capture.internal.capture.TargetPlatform
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.encoding.NativeJpegProcess
import io.screenstream.capture.internal.encoding.NativeSegmentSink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared arrangements for public ScreenCaptureSession integration contracts.
 *
 * This owner is stateless; mutable test state lives in each nested fixture instance.
 */
internal object ScreenCaptureSessionIntegrationFixture {
    internal suspend fun startActiveSession(
        harness: SessionHarness,
        parameters: ScreenCaptureParameters,
    ) = coroutineScope {
        val start = async(start = CoroutineStart.UNDISPATCHED) {
            harness.session.start(parameters)
        }
        harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
        start.await()
    }

    internal fun requestStopAndDrainSession(harness: SessionHarness) {
        harness.session.requestStop()
        if (harness.session.state.value !is ScreenCaptureState.Stopped &&
            harness.session.state.value !is ScreenCaptureState.Failed
        ) {
            driveControlUntil(harness) {
                harness.session.state.value is ScreenCaptureState.Stopped ||
                        harness.session.state.value is ScreenCaptureState.Failed
            }
        }
        drainAcceptedSessionWork(harness)
    }

    internal suspend fun primeCachedFrame(
        harness: SessionHarness,
        platform: CapturePlatformFixture,
        rgbaSeed: Int,
    ) {
        val callbackEntered = CountDownLatch(1)
        val registration = harness.session.registerFrameConsumer {
            callbackEntered.countDown()
        }
        platform.deliverSourceFrame(rgbaSeed)
        harness.driveUntil { callbackEntered.count == 0L }
        harness.enterNextControlTask()
        registration.unregister()
    }

    internal class BlockingCallback {
        private val entered = CountDownLatch(1)
        private val mayReturn = CountDownLatch(1)
        private val returned = CountDownLatch(1)
        private val entries = AtomicInteger()

        fun invoke(@Suppress("UNUSED_PARAMETER") frame: EncodedFrame) {
            entries.incrementAndGet()
            entered.countDown()
            try {
                check(mayReturn.await(5L, TimeUnit.SECONDS)) {
                    "Entered callback was not released"
                }
            } finally {
                returned.countDown()
            }
        }

        fun awaitEntered() {
            check(entered.await(5L, TimeUnit.SECONDS)) { "Frame callback did not enter" }
        }

        fun release() {
            mayReturn.countDown()
        }

        fun awaitReturned() {
            check(returned.await(5L, TimeUnit.SECONDS)) { "Frame callback did not return" }
        }

        fun entryCount(): Int = entries.get()
    }

    internal fun driveControlUntil(harness: SessionHarness, condition: () -> Boolean) {
        repeat(32) {
            if (condition()) return
            harness.enterNextControlTask()
            harness.enterNextCaptureTask()
        }
        check(condition()) { "Controlled Session work did not reach the requested public condition" }
    }

    internal fun drainAcceptedSessionWork(harness: SessionHarness) {
        repeat(32) {
            var progressed = harness.enterNextWorkerSuccessfully()
            progressed = harness.enterNextControlTask() || progressed
            progressed = harness.enterNextCaptureTask() || progressed
            if (!progressed) return
        }
        error("Controlled Session work did not quiesce within the bounded drain")
    }

    internal class FrameSnapshot(
        val bytes: ByteArray,
        val outputInfo: CaptureOutputInfo,
        val sequence: Long,
        val outputTimestampElapsedRealtimeNanos: Long,
    )

    internal fun copyFrame(frame: EncodedFrame): FrameSnapshot = FrameSnapshot(
        bytes = frame.toByteArray(),
        outputInfo = frame.outputInfo,
        sequence = frame.sequence,
        outputTimestampElapsedRealtimeNanos = frame.outputTimestampElapsedRealtimeNanos,
    )

    internal fun assertJpegDimensions(bytes: ByteArray, widthPx: Int, heightPx: Int) {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Robolectric BitmapFactory did not decode the Framework JPEG")
        try {
            assertEquals(widthPx, decoded.width)
            assertEquals(heightPx, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    /** Emits deterministic synthetic encoded bytes and does not exercise a JPEG codec. */
    internal class SafeRejectingNativeJpegFacade(
        private val successfulCompressionCountBeforeRejection: Int = 0,
        blockCompression: Boolean = false,
        private val successfulBytesForCompression: (Int) -> ByteArray = { NATIVE_SUCCESS_BYTES },
    ) : NativeJpegFacade, AutoCloseable {
        private val outstandingCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap())
        private val allocatedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap())
        private val freedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap())
        private val freeAttempts = ArrayList<ByteBuffer>()
        private val compressionEntered = CountDownLatch(1)
        private val compressionMayReturn = CountDownLatch(if (blockCompression) 1 else 0)
        private val compressionReturned = CountDownLatch(1)
        private var compressionCount: Int = 0
        private var resultBlockCount: Int = 0

        init {
            require(successfulCompressionCountBeforeRejection >= 0)
        }

        override fun resolveAvailability(): NativeJpegProcess.Availability = NativeJpegProcess.Availability.Available

        override fun hasWeakCompressor(): Boolean = true

        @Synchronized
        override fun newResultBlock(): ByteBuffer {
            resultBlockCount += 1
            return NativeJpegProcess.newResultBlock()
        }

        @Synchronized
        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer =
            ByteBuffer.allocateDirect(Math.toIntExact(carrierByteCount)).also { carrier ->
                check(allocatedCarriers.add(carrier))
                check(outstandingCarriers.add(carrier))
            }

        @Synchronized
        override fun freeCarrier(carrierBuffer: ByteBuffer) {
            freeAttempts += carrierBuffer
            check(allocatedCarriers.contains(carrierBuffer))
            check(outstandingCarriers.remove(carrierBuffer))
            check(freedCarriers.add(carrierBuffer))
        }

        @Synchronized
        override fun compress(
            carrierBuffer: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            quality: Int,
            sink: NativeSegmentSink,
            resultBlock: ByteBuffer,
        ) {
            check(outstandingCarriers.contains(carrierBuffer))
            check(!freedCarriers.contains(carrierBuffer))
            check(NativeJpegProcess.hasExactResultShape(resultBlock))
            check(resultBlock.getLong(0) == NativeJpegProcess.NATIVE_RESULT_PENDING)
            check(resultBlock.getLong(8) == NativeJpegProcess.NATIVE_RESULT_PENDING)
            compressionCount += 1
            if (compressionCount <= successfulCompressionCountBeforeRejection) {
                val successfulBytes = successfulBytesForCompression(compressionCount)
                require(successfulBytes.isNotEmpty())
                val segment = ByteBuffer.allocateDirect(successfulBytes.size).apply {
                    put(successfulBytes)
                    flip()
                }
                sink.copySegment(segment, successfulBytes.size)
                resultBlock.putLong(0, successfulBytes.size.toLong())
                resultBlock.putLong(8, 0L)
                return
            }
            compressionEntered.countDown()
            try {
                check(compressionMayReturn.await(5L, TimeUnit.SECONDS)) {
                    "Native compression return was not released"
                }
                resultBlock.putLong(0, 0L)
                resultBlock.putLong(8, 1L)
            } finally {
                compressionReturned.countDown()
            }
        }

        fun awaitCompressionEntered() {
            check(compressionEntered.await(5L, TimeUnit.SECONDS)) {
                "Native compression did not enter"
            }
        }

        fun releaseCompression() {
            compressionMayReturn.countDown()
        }

        fun awaitCompressionReturned() {
            check(compressionReturned.await(5L, TimeUnit.SECONDS)) {
                "Native compression did not return"
            }
        }

        @Synchronized
        fun carrierSnapshot(): NativeCarrierSnapshot = NativeCarrierSnapshot(
            allocationCount = allocatedCarriers.size,
            outstandingCount = outstandingCarriers.size,
            freeAttemptCount = freeAttempts.size,
            freedCount = freedCarriers.size,
            compressionCount = compressionCount,
            resultBlockCount = resultBlockCount,
            allocatedCarrier = allocatedCarriers.singleOrNull(),
            freeAttemptCarrier = freeAttempts.singleOrNull(),
            freedCarrier = freedCarriers.singleOrNull(),
        )

        @Synchronized
        override fun close() {
            check(outstandingCarriers.isEmpty())
        }

        private companion object {
            private val NATIVE_SUCCESS_BYTES: ByteArray = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0x53, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte(),
            )
        }
    }

    internal class NativeCarrierSnapshot(
        val allocationCount: Int,
        val outstandingCount: Int,
        val freeAttemptCount: Int,
        val freedCount: Int,
        val compressionCount: Int,
        val resultBlockCount: Int,
        val allocatedCarrier: ByteBuffer?,
        val freeAttemptCarrier: ByteBuffer?,
        val freedCarrier: ByteBuffer?,
    )

    internal class CapturePlatformFixture {
        val projection: MediaProjection = mockk()
        val projectionPlatform: ProjectionPlatform = mockk()
        val eglPlatform: EglPlatform = mockk()
        val glesPlatform: GlesPlatform = mockk()
        val targetPlatform: TargetPlatform = mockk()

        private val virtualDisplay: VirtualDisplay = mockk()
        private val eglDisplay: EGLDisplay = mockk()
        private val eglConfig: EGLConfig = mockk()
        private val eglContext: EGLContext = mockk()
        private val eglPbuffer: EGLSurface = mockk()
        private val initialSurfaceTexture: SurfaceTexture = mockk()
        private val replacementSurfaceTexture: SurfaceTexture = mockk()
        private val initialSurface: Surface = mockk()
        private val replacementSurface: Surface = mockk()
        private val initialOesTextureName = 101
        private var generatedTextureCount = 0
        private var generatedFramebufferCount = 0
        private var projectionCallback: MediaProjection.Callback? = null
        private var projectionCallbackHandler: Handler? = null

        private class FrameListenerRegistration(
            val listener: SurfaceTexture.OnFrameAvailableListener,
            val handler: Handler,
        )

        private val targetGate = Any()
        private val frameListeners = IdentityHashMap<SurfaceTexture, FrameListenerRegistration>()
        private val surfaceTexturesBySurface = IdentityHashMap<Surface, SurfaceTexture>()
        private var attachedSurfaceTexture: SurfaceTexture? = null
        private val sourceRgbaSeed = AtomicInteger()
        private val sourceDataSpace = AtomicInteger(DataSpace.DATASPACE_UNKNOWN)
        private val sourceUpdates = AtomicInteger()
        private val nextReadbackAction = AtomicReference<((ByteBuffer) -> Unit)?>(null)
        private val nextSourceUpdateFailure = AtomicReference<Exception?>(null)
        private val nextSurfaceTextureCreationFailure = AtomicReference<Surface.OutOfResourcesException?>(null)
        private var didReturnInitialVirtualDisplay = false

        // Robolectric may leave EGL14's opaque native sentinels null. Fill missing values for this fixture so real
        // EglOwner teardown can pass the same non-null NO_* handles through its Kotlin boundary.
        private val noDisplay = EGL14.EGL_NO_DISPLAY ?: mockk<EGLDisplay>().also { EGL14.EGL_NO_DISPLAY = it }
        private val noContext = EGL14.EGL_NO_CONTEXT ?: mockk<EGLContext>().also { EGL14.EGL_NO_CONTEXT = it }
        private val noSurface = EGL14.EGL_NO_SURFACE ?: mockk<EGLSurface>().also { EGL14.EGL_NO_SURFACE = it }
        private var currentDisplay: EGLDisplay = noDisplay
        private var currentContext: EGLContext = noContext
        private var currentSurface: EGLSurface = noSurface
        private val createdSurfaceTextures = mutableListOf<SurfaceTexture>()
        private val createdSurfaces = mutableListOf<Surface>()
        private val createdTextures = mutableListOf<Int>()
        private val createdFramebuffers = mutableListOf<Int>()
        private val retiredTextures = mutableListOf<Int>()
        private val retiredFramebuffers = mutableListOf<Int>()

        fun verifySuccessfulRetirement() {
            check(currentDisplay === EGL14.EGL_NO_DISPLAY)
            check(currentContext === EGL14.EGL_NO_CONTEXT)
            check(currentSurface === EGL14.EGL_NO_SURFACE)
            verify(exactly = 1) {
                projectionPlatform.unregisterCallback(refEq(projection), any())
                projectionPlatform.stop(refEq(projection))
                eglPlatform.destroyContext(refEq(eglDisplay), refEq(eglContext))
                eglPlatform.destroySurface(refEq(eglDisplay), refEq(eglPbuffer))
                eglPlatform.releaseDisplayInitialization(refEq(eglDisplay))
                eglPlatform.releaseThread()
                glesPlatform.deleteShader(301)
                glesPlatform.deleteShader(302)
                glesPlatform.deleteProgram(401)
            }
            if (didReturnInitialVirtualDisplay) verify(exactly = 1) { projectionPlatform.release(refEq(virtualDisplay)) }
            createdSurfaceTextures.forEach { texture ->
                verify(exactly = 1) {
                    targetPlatform.clearFrameListener(refEq(texture))
                    targetPlatform.releaseSurfaceTexture(refEq(texture))
                }
            }
            createdSurfaces.forEach { surface -> verify(exactly = 1) { targetPlatform.releaseSurface(refEq(surface)) } }
            assertEquals(createdTextures.sorted(), retiredTextures.sorted())
            assertEquals(createdFramebuffers.sorted(), retiredFramebuffers.sorted())
        }

        fun failInitialSurfaceTextureRelease(failure: Exception) {
            every { targetPlatform.releaseSurfaceTexture(refEq(initialSurfaceTexture)) } throws failure
        }


        init {
            configureProjection()
            configureEgl()
            configureGles()
            configureTarget()
        }

        fun verifyUntouched() {
            verify { projectionPlatform wasNot Called }
            verify { eglPlatform wasNot Called }
            verify { glesPlatform wasNot Called }
            verify { targetPlatform wasNot Called }
        }

        fun initialVirtualDisplayReturned(): Boolean = didReturnInitialVirtualDisplay

        fun deliverCapturedContentResize(widthPx: Int, heightPx: Int) {
            deliverProjectionCallback { callback ->
                callback.onCapturedContentResize(widthPx, heightPx)
            }
        }

        fun deliverCapturedContentVisibilityChanged(isVisible: Boolean) {
            deliverProjectionCallback { callback ->
                callback.onCapturedContentVisibilityChanged(isVisible)
            }
        }

        fun deliverProjectionStopped() {
            deliverProjectionCallback(MediaProjection.Callback::onStop)
        }

        fun deliverSourceFrame(rgbaSeed: Int, dataSpace: Int = DataSpace.DATASPACE_UNKNOWN) {
            sourceRgbaSeed.set(rgbaSeed)
            sourceDataSpace.set(dataSpace)
            val (surfaceTexture, registration) = synchronized(targetGate) {
                val current = checkNotNull(attachedSurfaceTexture) { "The virtual display has no attached Target" }
                current to checkNotNull(frameListeners[current]) {
                    "The attached Target has no current frame listener"
                }
            }
            check(registration.handler.post { registration.listener.onFrameAvailable(surfaceTexture) })
            shadowOf(registration.handler.looper).idle()
        }

        fun runOnceDuringNextReadback(action: (ByteBuffer) -> Unit) {
            check(nextReadbackAction.compareAndSet(null, action)) { "A readback action is already armed" }
        }

        fun failNextSourceUpdate(failure: Exception) {
            check(nextSourceUpdateFailure.compareAndSet(null, failure)) { "A source-update failure is already armed" }
        }

        fun failNextReplacementSurfaceTextureCreation(failure: Surface.OutOfResourcesException) {
            check(nextSurfaceTextureCreationFailure.compareAndSet(null, failure)) {
                "A replacement SurfaceTexture-creation failure is already armed"
            }
        }

        fun sourceUpdateCount(): Int = sourceUpdates.get()

        fun verifyOpenBoundaries(widthPx: Int, heightPx: Int, densityDpi: Int) {
            verifyInitialProjectionBoundaries(widthPx, heightPx, densityDpi)
            verify {
                eglPlatform.makeCurrent(refEq(eglDisplay), refEq(eglPbuffer), refEq(eglContext))
            }
            verify {
                glesPlatform.getInteger(GLES20.GL_MAX_TEXTURE_SIZE, any())
                glesPlatform.getInteger(GLES20.GL_MAX_VIEWPORT_DIMS, any())
            }
        }

        fun verifyInitialProjectionBoundaries(widthPx: Int, heightPx: Int, densityDpi: Int) {
            verify(exactly = 1) {
                projectionPlatform.registerCallback(refEq(projection), any(), any())
            }
            verify {
                targetPlatform.createSurfaceTexture(initialOesTextureName)
                targetPlatform.setDefaultBufferSize(refEq(initialSurfaceTexture), widthPx, heightPx)
                targetPlatform.createSurface(refEq(initialSurfaceTexture))
                targetPlatform.setFrameListener(refEq(initialSurfaceTexture), any(), any())
            }
            verify(exactly = 1) {
                projectionPlatform.createVirtualDisplay(refEq(projection), any(), any(), any(), any())
            }
            verify {
                projectionPlatform.createVirtualDisplay(refEq(projection), widthPx, heightPx, densityDpi, refEq(initialSurface))
            }
            verifyOrder {
                projectionPlatform.registerCallback(refEq(projection), any(), any())
                projectionPlatform.createVirtualDisplay(refEq(projection), widthPx, heightPx, densityDpi, refEq(initialSurface))
            }
        }

        fun verifyAuthoritativeResizeBoundaries(widthPx: Int, heightPx: Int, densityDpi: Int) {
            verify(exactly = 1) {
                projectionPlatform.resize(refEq(virtualDisplay), widthPx, heightPx, densityDpi)
                targetPlatform.setDefaultBufferSize(refEq(replacementSurfaceTexture), widthPx, heightPx)
                targetPlatform.createSurface(refEq(replacementSurfaceTexture))
                targetPlatform.setFrameListener(refEq(replacementSurfaceTexture), any(), any())
                projectionPlatform.setSurface(refEq(virtualDisplay), refEq(replacementSurface))
            }
        }

        fun verifyNoProjectionTopologyChanges() {
            verify(exactly = 1) {
                projectionPlatform.createVirtualDisplay(refEq(projection), any(), any(), any(), any())
            }
            verify(exactly = 0) {
                projectionPlatform.resize(refEq(virtualDisplay), any(), any(), any())
                projectionPlatform.setSurface(refEq(virtualDisplay), any())
            }
        }

        fun verifyNoReplacementTargetWasCreated() {
            verify(exactly = 0) {
                targetPlatform.createSurfaceTexture(match { it != initialOesTextureName })
            }
        }

        private fun deliverProjectionCallback(action: (MediaProjection.Callback) -> Unit) {
            check(didReturnInitialVirtualDisplay)
            val callback = checkNotNull(projectionCallback)
            val handler = checkNotNull(projectionCallbackHandler)
            check(handler.post { action(callback) })
            shadowOf(handler.looper).idle()
        }

        private fun configureProjection() {
            every { projectionPlatform.registerCallback(refEq(projection), any(), any()) } answers {
                projectionCallback = secondArg()
                projectionCallbackHandler = thirdArg()
            }
            every {
                projectionPlatform.createVirtualDisplay(refEq(projection), any(), any(), any(), refEq(initialSurface))
            } answers {
                synchronized(targetGate) {
                    attachedSurfaceTexture = checkNotNull(surfaceTexturesBySurface[initialSurface])
                }
                didReturnInitialVirtualDisplay = true
                virtualDisplay
            }
            every { projectionPlatform.unregisterCallback(refEq(projection), any()) } just Runs
            every { projectionPlatform.stop(refEq(projection)) } just Runs
            every { projectionPlatform.release(refEq(virtualDisplay)) } just Runs
            every { projectionPlatform.resize(refEq(virtualDisplay), any(), any(), any()) } just Runs
            every { projectionPlatform.setSurface(refEq(virtualDisplay), any()) } answers {
                val surface = secondArg<Surface>()
                synchronized(targetGate) {
                    attachedSurfaceTexture = checkNotNull(surfaceTexturesBySurface[surface])
                }
            }
        }

        private fun configureEgl() {
            every { eglPlatform.currentDisplay } answers { currentDisplay }
            every { eglPlatform.currentContext } answers { currentContext }
            every { eglPlatform.currentReadSurface } answers { currentSurface }
            every { eglPlatform.currentDrawSurface } answers { currentSurface }
            every { eglPlatform.getDisplay() } returns eglDisplay
            every { eglPlatform.initialize(refEq(eglDisplay), any()) } answers {
                secondArg<IntArray>()[0] = 1
                secondArg<IntArray>()[1] = 5
                true
            }
            every { eglPlatform.chooseConfig(refEq(eglDisplay), any(), any(), any()) } answers {
                arg<Array<EGLConfig?>>(2)[0] = eglConfig
                arg<IntArray>(3)[0] = 1
                true
            }
            every { eglPlatform.createContext(refEq(eglDisplay), refEq(eglConfig), any()) } returns eglContext
            every { eglPlatform.createPbufferSurface(refEq(eglDisplay), refEq(eglConfig), any()) } returns eglPbuffer
            every { eglPlatform.makeCurrent(refEq(eglDisplay), refEq(eglPbuffer), refEq(eglContext)) } answers {
                currentDisplay = eglDisplay
                currentContext = eglContext
                currentSurface = eglPbuffer
                true
            }
            every { eglPlatform.makeCurrent(refEq(eglDisplay), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } answers {
                currentDisplay = EGL14.EGL_NO_DISPLAY
                currentContext = EGL14.EGL_NO_CONTEXT
                currentSurface = EGL14.EGL_NO_SURFACE
                true
            }
            every { eglPlatform.destroyContext(refEq(eglDisplay), refEq(eglContext)) } returns true
            every { eglPlatform.destroySurface(refEq(eglDisplay), refEq(eglPbuffer)) } returns true
            every { eglPlatform.releaseDisplayInitialization(refEq(eglDisplay)) } returns true
            every { eglPlatform.releaseThread() } returns true
        }

        private fun configureGles() {
            every { glesPlatform.getError() } returns GLES20.GL_NO_ERROR
            every { glesPlatform.getInteger(any(), any()) } answers {
                val values = secondArg<IntArray>()
                when (firstArg<Int>()) {
                    GLES20.GL_MAX_TEXTURE_SIZE -> values[0] = 4_096
                    GLES20.GL_MAX_VIEWPORT_DIMS -> {
                        values[0] = 4_096
                        values[1] = 4_096
                    }

                    GLES20.GL_RED_BITS, GLES20.GL_GREEN_BITS, GLES20.GL_BLUE_BITS -> values[0] = 8
                    else -> error("Unexpected GLES integer query")
                }
            }
            every { glesPlatform.getShaderPrecisionFormat(any(), any()) } answers {
                firstArg<IntArray>().fill(0)
                secondArg<IntArray>().fill(0)
            }
            every { glesPlatform.genTextures(any()) } answers {
                firstArg<IntArray>()[0] = (initialOesTextureName + generatedTextureCount++)
                    .also { createdTextures += it }
            }
            every { glesPlatform.bindTexture(any(), any()) } just Runs
            every { glesPlatform.texParameter(any(), any(), any()) } just Runs
            every { glesPlatform.texImage2D(any(), any()) } just Runs
            every { glesPlatform.genFramebuffers(any()) } answers {
                firstArg<IntArray>()[0] = (201 + generatedFramebufferCount++)
                    .also { createdFramebuffers += it }
            }
            every { glesPlatform.deleteTextures(any()) } answers { retiredTextures.addAll(firstArg<IntArray>().toList()) }
            every { glesPlatform.deleteFramebuffers(any()) } answers { retiredFramebuffers.addAll(firstArg<IntArray>().toList()) }
            every { glesPlatform.bindFramebuffer(any()) } just Runs
            every { glesPlatform.framebufferTexture2D(any()) } just Runs
            every { glesPlatform.checkFramebufferStatus() } returns GLES20.GL_FRAMEBUFFER_COMPLETE
            every { glesPlatform.createShader(GLES20.GL_VERTEX_SHADER) } returns 301
            every { glesPlatform.createShader(GLES20.GL_FRAGMENT_SHADER) } returns 302
            every { glesPlatform.shaderSource(any(), any()) } just Runs
            every { glesPlatform.compileShader(any()) } just Runs
            every { glesPlatform.getShaderStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { glesPlatform.createProgram() } returns 401
            every { glesPlatform.attachShader(any(), any()) } just Runs
            every { glesPlatform.bindAttribLocation(any(), any(), any()) } just Runs
            every { glesPlatform.linkProgram(any()) } just Runs
            every { glesPlatform.getProgramStatus(any(), any()) } answers {
                secondArg<IntArray>()[0] = GLES20.GL_TRUE
            }
            every { glesPlatform.getUniformLocation(any(), any()) } returns 1
            every { glesPlatform.detachShader(any(), any()) } just Runs
            every { glesPlatform.deleteShader(301) } just Runs
            every { glesPlatform.deleteShader(302) } just Runs
            every { glesPlatform.deleteProgram(401) } just Runs
            every { glesPlatform.useProgram(any()) } just Runs
            every { glesPlatform.viewport(any(), any()) } just Runs
            every { glesPlatform.activeTexture(any()) } just Runs
            every { glesPlatform.uniform1i(any(), any()) } just Runs
            every { glesPlatform.uniform1f(any(), any()) } just Runs
            every { glesPlatform.uniformMatrix4fv(any(), any()) } just Runs
            every { glesPlatform.vertexAttribPointer(any(), any()) } just Runs
            every { glesPlatform.enableVertexAttribArray(any()) } just Runs
            every { glesPlatform.colorMask() } just Runs
            every { glesPlatform.packAlignmentOne() } just Runs
            every { glesPlatform.disable(any()) } just Runs
            every { glesPlatform.drawTriangleStrip() } just Runs
            every { glesPlatform.readPixels(any(), any(), any()) } answers {
                // This fixture writes synthetic RGBA bytes directly; it does not execute a shader.
                val widthPx = firstArg<Int>()
                val heightPx = secondArg<Int>()
                val destination = thirdArg<Buffer>() as ByteBuffer
                check(destination.capacity() == widthPx * heightPx * 4)
                nextReadbackAction.getAndSet(null)?.invoke(destination)
                val seed = sourceRgbaSeed.get()
                for (pixelOffset in 0 until destination.capacity() step 4) {
                    destination.put(pixelOffset, (seed + pixelOffset).toByte())
                    destination.put(pixelOffset + 1, (seed + pixelOffset + 37).toByte())
                    destination.put(pixelOffset + 2, (seed + pixelOffset + 83).toByte())
                    destination.put(pixelOffset + 3, 0xFF.toByte())
                }
            }
        }

        private fun configureTarget() {
            every { targetPlatform.createSurfaceTexture(any()) } answers {
                if (firstArg<Int>() == initialOesTextureName) {
                    initialSurfaceTexture.also { createdSurfaceTextures += it }
                } else {
                    nextSurfaceTextureCreationFailure.getAndSet(null)?.let { throw it }
                    replacementSurfaceTexture.also { createdSurfaceTextures += it }
                }
            }
            every { targetPlatform.setDefaultBufferSize(refEq(initialSurfaceTexture), any(), any()) } just Runs
            every { targetPlatform.setDefaultBufferSize(refEq(replacementSurfaceTexture), any(), any()) } just Runs
            every { targetPlatform.createSurface(refEq(initialSurfaceTexture)) } answers {
                synchronized(targetGate) { surfaceTexturesBySurface[initialSurface] = initialSurfaceTexture }
                initialSurface.also { createdSurfaces += it }
            }
            every { targetPlatform.createSurface(refEq(replacementSurfaceTexture)) } answers {
                synchronized(targetGate) { surfaceTexturesBySurface[replacementSurface] = replacementSurfaceTexture }
                replacementSurface.also { createdSurfaces += it }
            }
            every { targetPlatform.setFrameListener(any(), any(), any()) } answers {
                val surfaceTexture = firstArg<SurfaceTexture>()
                synchronized(targetGate) {
                    frameListeners[surfaceTexture] = FrameListenerRegistration(
                        listener = secondArg<SurfaceTexture.OnFrameAvailableListener>(),
                        handler = thirdArg<Handler>(),
                    )
                }
            }
            every { targetPlatform.updateTexImage(any()) } answers {
                val surfaceTexture = firstArg<SurfaceTexture>()
                synchronized(targetGate) {
                    check(surfaceTexture === attachedSurfaceTexture) { "Readback targeted a detached SurfaceTexture" }
                }
                sourceUpdates.incrementAndGet()
                nextSourceUpdateFailure.getAndSet(null)?.let { throw it }
            }
            every { targetPlatform.dataSpace(any()) } answers {
                val surfaceTexture = firstArg<SurfaceTexture>()
                synchronized(targetGate) {
                    check(surfaceTexture === attachedSurfaceTexture) { "Dataspace queried from a detached SurfaceTexture" }
                }
                sourceDataSpace.get()
            }
            every { targetPlatform.getTransformMatrix(any(), any()) } answers {
                val surfaceTexture = firstArg<SurfaceTexture>()
                synchronized(targetGate) {
                    check(surfaceTexture === attachedSurfaceTexture) { "Transform queried from a detached SurfaceTexture" }
                }
                val matrix = secondArg<FloatArray>()
                matrix.fill(0f)
                matrix[0] = 1f
                matrix[5] = 1f
                matrix[10] = 1f
                matrix[15] = 1f
            }
            every { targetPlatform.clearFrameListener(refEq(replacementSurfaceTexture)) } answers {
                synchronized(targetGate) { frameListeners.remove(replacementSurfaceTexture) }
            }
            every { targetPlatform.releaseSurface(refEq(replacementSurface)) } just Runs
            every { targetPlatform.releaseSurfaceTexture(refEq(replacementSurfaceTexture)) } just Runs
            every { targetPlatform.clearFrameListener(refEq(initialSurfaceTexture)) } answers {
                synchronized(targetGate) { frameListeners.remove(initialSurfaceTexture) }
            }
            every { targetPlatform.releaseSurface(refEq(initialSurface)) } just Runs
            every { targetPlatform.releaseSurfaceTexture(refEq(initialSurfaceTexture)) } just Runs
        }
    }
}
