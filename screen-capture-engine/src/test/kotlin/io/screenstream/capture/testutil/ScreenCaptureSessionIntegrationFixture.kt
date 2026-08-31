package io.screenstream.capture.testutil

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
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
import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.ScreenCaptureEffectiveParameters
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
        harness: SessionStartHarness,
        platform: HappyCapturePlatform,
        parameters: ScreenCaptureParameters,
    ) = coroutineScope {
        val start = async(start = CoroutineStart.UNDISPATCHED) {
            harness.session.start(platform.projection, parameters)
        }
        harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
        start.await()
    }

    internal fun stopAndDrainSession(harness: SessionStartHarness) {
        harness.session.stop()
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
        harness: SessionStartHarness,
        platform: HappyCapturePlatform,
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

        fun invoke(@Suppress("UNUSED_PARAMETER") frame: EncodedImageFrame) {
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

    internal fun driveControlUntil(harness: SessionStartHarness, condition: () -> Boolean) {
        repeat(32) {
            if (condition()) return
            harness.enterNextControlTask()
            harness.enterNextCaptureTask()
        }
        check(condition()) { "Controlled Session work did not reach the requested public condition" }
    }

    internal fun drainAcceptedSessionWork(harness: SessionStartHarness) {
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
        val effectiveParameters: ScreenCaptureEffectiveParameters,
        val sequence: Long,
        val timestampElapsedRealtimeNanos: Long,
    )

    internal fun copyFrame(frame: EncodedImageFrame): FrameSnapshot = FrameSnapshot(
        bytes = frame.toByteArray(),
        effectiveParameters = frame.effectiveParameters,
        sequence = frame.sequence,
        timestampElapsedRealtimeNanos = frame.timestampElapsedRealtimeNanos,
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

    internal class SafeRejectingNativeJpegFacade(
        private val successfulCompressionCountBeforeRejection: Int = 0,
        private val blockCompression: Boolean = false,
    ) : NativeJpegFacade, AutoCloseable {
        private val outstandingCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
        private val allocatedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
        private val freedCarriers: MutableSet<ByteBuffer> =
            Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
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
                val segment = ByteBuffer.allocateDirect(NATIVE_SUCCESS_BYTES.size).apply {
                    put(NATIVE_SUCCESS_BYTES)
                    flip()
                }
                sink.adoptSegment(segment, NATIVE_SUCCESS_BYTES.size)
                resultBlock.putLong(0, NATIVE_SUCCESS_BYTES.size.toLong())
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

    internal class HappyCapturePlatform {
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
        private val initialOutputTextureName = 102
        private val replacementOesTextureName = 103
        private val replacementOutputTextureName = 104
        private var generatedTextureCount = 0
        private var generatedFramebufferCount = 0
        private var projectionCallback: MediaProjection.Callback? = null
        private var projectionCallbackHandler: Handler? = null
        private var sourceFrameListener: SurfaceTexture.OnFrameAvailableListener? = null
        private var sourceFrameHandler: Handler? = null
        private val sourceRgbaSeed = AtomicInteger()
        private val sourceDataSpace = AtomicInteger(DataSpace.DATASPACE_UNKNOWN)
        private val nextReadbackAction = AtomicReference<((ByteBuffer) -> Unit)?>(null)
        private var didReturnInitialVirtualDisplay = false

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

        fun deliverSourceFrame(rgbaSeed: Int, dataSpace: Int = DataSpace.DATASPACE_UNKNOWN) {
            val listener = checkNotNull(sourceFrameListener)
            val handler = checkNotNull(sourceFrameHandler)
            sourceRgbaSeed.set(rgbaSeed)
            sourceDataSpace.set(dataSpace)
            check(handler.post { listener.onFrameAvailable(initialSurfaceTexture) })
            shadowOf(handler.looper).idle()
        }

        fun runOnceDuringNextReadback(action: (ByteBuffer) -> Unit) {
            check(nextReadbackAction.compareAndSet(null, action)) { "A readback action is already armed" }
        }

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
                targetPlatform.createSurfaceTexture(replacementOesTextureName)
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
                didReturnInitialVirtualDisplay = true
                virtualDisplay
            }
            every { projectionPlatform.resize(refEq(virtualDisplay), any(), any(), any()) } just Runs
            every { projectionPlatform.setSurface(refEq(virtualDisplay), any()) } just Runs
        }

        private fun configureEgl() {
            every { eglPlatform.currentDisplay } returns eglDisplay
            every { eglPlatform.currentContext } returns eglContext
            every { eglPlatform.currentReadSurface } returns eglPbuffer
            every { eglPlatform.currentDrawSurface } returns eglPbuffer
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
            every { eglPlatform.makeCurrent(refEq(eglDisplay), refEq(eglPbuffer), refEq(eglContext)) } returns true
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
                firstArg<IntArray>()[0] = when (generatedTextureCount++) {
                    0 -> initialOesTextureName
                    1 -> initialOutputTextureName
                    2 -> replacementOesTextureName
                    3 -> replacementOutputTextureName
                    else -> error("Unexpected texture allocation")
                }
            }
            every { glesPlatform.bindTexture(any(), any()) } just Runs
            every { glesPlatform.texParameter(any(), any(), any()) } just Runs
            every { glesPlatform.texImage2D(any(), any()) } just Runs
            every { glesPlatform.genFramebuffers(any()) } answers {
                firstArg<IntArray>()[0] = when (generatedFramebufferCount++) {
                    0 -> 201
                    1 -> 202
                    else -> error("Unexpected framebuffer allocation")
                }
            }
            every { glesPlatform.deleteTextures(any()) } just Runs
            every { glesPlatform.deleteFramebuffers(any()) } just Runs
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
            every { targetPlatform.createSurfaceTexture(initialOesTextureName) } returns initialSurfaceTexture
            every { targetPlatform.createSurfaceTexture(replacementOesTextureName) } returns replacementSurfaceTexture
            every { targetPlatform.setDefaultBufferSize(refEq(initialSurfaceTexture), any(), any()) } just Runs
            every { targetPlatform.setDefaultBufferSize(refEq(replacementSurfaceTexture), any(), any()) } just Runs
            every { targetPlatform.createSurface(refEq(initialSurfaceTexture)) } returns initialSurface
            every { targetPlatform.createSurface(refEq(replacementSurfaceTexture)) } returns replacementSurface
            every { targetPlatform.setFrameListener(refEq(initialSurfaceTexture), any(), any()) } answers {
                sourceFrameListener = secondArg()
                sourceFrameHandler = thirdArg()
            }
            every { targetPlatform.setFrameListener(refEq(replacementSurfaceTexture), any(), any()) } just Runs
            every { targetPlatform.updateTexImage(refEq(initialSurfaceTexture)) } just Runs
            every { targetPlatform.dataSpace(refEq(initialSurfaceTexture)) } answers { sourceDataSpace.get() }
            every { targetPlatform.getTransformMatrix(refEq(initialSurfaceTexture), any()) } answers {
                val matrix = secondArg<FloatArray>()
                matrix.fill(0f)
                matrix[0] = 1f
                matrix[5] = 1f
                matrix[10] = 1f
                matrix[15] = 1f
            }
            every { targetPlatform.clearFrameListener(refEq(initialSurfaceTexture)) } just Runs
            every { targetPlatform.releaseSurface(refEq(initialSurface)) } just Runs
            every { targetPlatform.releaseSurfaceTexture(refEq(initialSurfaceTexture)) } just Runs
        }
    }
}
