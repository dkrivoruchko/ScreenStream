package io.screenstream.capture.internal.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifySequence
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.Rgba8888Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ProjectionOwnerLifecycleTest {
    // Verification: CAP-01
    @Test
    fun registersResizesAndRetiresDisplay() {
        val projection = mockk<MediaProjection>()
        val surface = mockk<Surface>()
        val display = mockk<VirtualDisplay>()
        val controlHandler = Handler(Looper.getMainLooper())
        val callback = slot<MediaProjection.Callback>()
        val callbackSink = RecordingCallbackSink()
        val callbackBoundary = RecordingCallbackBoundary()
        val initialPlan = plan(widthPx = 1080, heightPx = 1920, densityDpi = 420)
        val resizedPlan = plan(widthPx = 720, heightPx = 1280, densityDpi = 320)
        every { projection.registerCallback(capture(callback), refEq(controlHandler)) } just Runs
        every {
            projection.createVirtualDisplay(
                "ScreenCaptureEngine",
                1080,
                1920,
                420,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                refEq(surface),
                null,
                null,
            )
        } returns display
        every { display.resize(720, 1280, 320) } just Runs
        every { display.surface = null } just Runs
        every { display.release() } just Runs
        every { projection.unregisterCallback(any()) } just Runs
        every { projection.stop() } just Runs
        val owner = ProjectionOwner(
            projection = projection,
            controlHandler = controlHandler,
            callbackSink = callbackSink,
            callbackBoundary = callbackBoundary,
        )

        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        assertSame(ProjectionOwner.VirtualDisplayCreationResult.Created, owner.createVirtualDisplay(initialPlan, surface))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.resizeIfChanged(resizedPlan))
        assertThrows(IllegalStateException::class.java) {
            owner.createVirtualDisplay(initialPlan, surface)
        }

        owner.fenceCallbacks()
        val displayRetirement = owner.retireDisplay(surface)
        val projectionRetirement = owner.retireCallbackAndProjection()

        assertTrue(callback.isCaptured)
        assertTrue(displayRetirement.detachProof?.matches(surface) == true)
        assertNotNull(displayRetirement.releaseProof)
        assertNull(displayRetirement.cleanupFailure)
        assertNull(displayRetirement.residue)
        assertNull(projectionRetirement.cleanupFailure)
        assertNull(projectionRetirement.residue)
        assertTrue(callbackSink.isEmpty())
        assertTrue(callbackBoundary.failures.isEmpty())
        verifySequence {
            projection.registerCallback(refEq(callback.captured), refEq(controlHandler))
            projection.createVirtualDisplay(
                "ScreenCaptureEngine",
                1080,
                1920,
                420,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                refEq(surface),
                null,
                null,
            )
            display.resize(720, 1280, 320)
            display.surface = null
            display.release()
            projection.unregisterCallback(refEq(callback.captured))
            projection.stop()
        }
        confirmVerified(projection, display)
    }

    // Verification: CAP-01
    @Test
    fun nullOrDeniedDisplayLeavesNoOwnedDisplay() {
        val surface = mockk<Surface>()

        assertDefiniteAbsence(surface = surface) { result ->
            assertSame(ProjectionOwner.VirtualDisplayCreationResult.ReturnedNull, result)
        }

        val denied = SecurityException("projection denied")
        assertDefiniteAbsence(surface = surface, creationFailure = denied) { result ->
            assertTrue(result is ProjectionOwner.VirtualDisplayCreationResult.Failed)
            result as ProjectionOwner.VirtualDisplayCreationResult.Failed
            assertSame(ScreenCaptureProblem.CaptureUnavailable, result.problem)
            assertSame(denied, result.cause)
        }
    }

    // Verification: CAP-01
    @Test
    fun outOfMemoryEscapesWithoutDisplayCleanup() {
        val projection = mockk<MediaProjection>()
        val surface = mockk<Surface>()
        val platform = RecordingProjectionPlatform()
        val owner = owner(projection = projection, platform = platform)
        val failure = OutOfMemoryError("opaque display creation")
        platform.creationFailure = failure

        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        val escaped = assertThrows(OutOfMemoryError::class.java) {
            owner.createVirtualDisplay(plan(), surface)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.createVirtualDisplay(plan(), surface)
        }
        val retirement = owner.retireDisplay(surface)

        assertSame(failure, escaped)
        assertEquals(1, platform.createCount)
        assertEquals(0, platform.resizeCount)
        assertEquals(0, platform.setSurfaceCount)
        assertEquals(0, platform.releaseCount)
        assertEquals(0, platform.unregisterCount)
        assertEquals(0, platform.stopCount)
        assertNull(retirement.detachProof)
        assertNull(retirement.releaseProof)
        assertNotNull(retirement.cleanupFailure)
        assertNotNull(retirement.residue)
    }

    // Verification: CAP-01
    @Test
    fun fencedCallbacksDropAndStopRunsOnce() {
        val projection = mockk<MediaProjection>()
        val platform = RecordingProjectionPlatform()
        val callbackSink = RecordingCallbackSink()
        val callbackBoundary = RecordingCallbackBoundary()
        val owner = owner(
            projection = projection,
            platform = platform,
            callbackSink = callbackSink,
            callbackBoundary = callbackBoundary,
        )
        val unregisterFailure = IllegalStateException("unregister failed")

        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        val callback = platform.registeredCallbacks.single()
        callback.onStop()
        owner.fenceCallbacks()
        callback.onStop()
        callback.onCapturedContentResize(640, 480)
        callback.onCapturedContentVisibilityChanged(true)
        platform.unregisterFailure = unregisterFailure

        val first = owner.retireCallbackAndProjection()
        val repeated = owner.retireCallbackAndProjection()

        assertEquals(1, callbackSink.stopped.size)
        assertSame(owner.token, callbackSink.stopped.single())
        assertTrue(callbackSink.resizes.isEmpty())
        assertTrue(callbackSink.visibilityChanges.isEmpty())
        assertTrue(callbackBoundary.failures.isEmpty())
        assertSame(unregisterFailure, first.cleanupFailure)
        assertSame(unregisterFailure, first.residue)
        assertSame(unregisterFailure, repeated.cleanupFailure)
        assertSame(unregisterFailure, repeated.residue)
        assertEquals(listOf("register", "unregister", "stop"), platform.calls)
        assertEquals(1, platform.unregisterCount)
        assertEquals(1, platform.stopCount)
        assertEquals(0, platform.createCount)
    }

    private fun assertDefiniteAbsence(
        surface: Surface,
        creationFailure: Throwable? = null,
        assertResult: (ProjectionOwner.VirtualDisplayCreationResult) -> Unit,
    ) {
        val projection = mockk<MediaProjection>()
        val platform = RecordingProjectionPlatform().apply {
            this.creationFailure = creationFailure
        }
        val owner = owner(projection = projection, platform = platform)

        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        assertResult(owner.createVirtualDisplay(plan(), surface))
        val displayRetirement = owner.retireDisplay(surface)
        owner.fenceCallbacks()
        val projectionRetirement = owner.retireCallbackAndProjection()

        assertTrue(displayRetirement.detachProof?.matches(surface) == true)
        assertNotNull(displayRetirement.releaseProof)
        assertNull(displayRetirement.cleanupFailure)
        assertNull(displayRetirement.residue)
        assertNull(projectionRetirement.cleanupFailure)
        assertNull(projectionRetirement.residue)
        assertEquals(1, platform.createCount)
        assertEquals(0, platform.resizeCount)
        assertEquals(0, platform.setSurfaceCount)
        assertEquals(0, platform.releaseCount)
        assertEquals(1, platform.unregisterCount)
        assertEquals(1, platform.stopCount)
    }

    private fun owner(
        projection: MediaProjection,
        platform: ProjectionPlatform,
        callbackSink: RecordingCallbackSink = RecordingCallbackSink(),
        callbackBoundary: RecordingCallbackBoundary = RecordingCallbackBoundary(),
    ): ProjectionOwner = ProjectionOwner(
        projection = projection,
        controlHandler = Handler(Looper.getMainLooper()),
        callbackSink = callbackSink,
        callbackBoundary = callbackBoundary,
        platform = platform,
    )

    private fun plan(
        widthPx: Int = 1080,
        heightPx: Int = 1920,
        densityDpi: Int = 420,
    ): CapturePlan = CapturePlan(
        sourceRegion = SourceRegion.Full,
        crop = CropInsetsPx.ZERO,
        appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = widthPx, bottomPx = heightPx),
        rotation = Rotation.Degrees0,
        mirror = Mirror.None,
        colorMode = ColorMode.Color,
        sourceWidthPx = widthPx,
        sourceHeightPx = heightPx,
        densityDpi = densityDpi,
        targetMode = CaptureTargetMode.Full,
        targetWidthPx = widthPx,
        targetHeightPx = heightPx,
        rgbaLayout = Rgba8888Layout.create(widthPx = widthPx, heightPx = heightPx),
    )

    private class RecordingCallbackSink : ProjectionOwner.CallbackSink {
        val stopped = mutableListOf<ProjectionOwner.Token>()
        val resizes = mutableListOf<Triple<ProjectionOwner.Token, Int, Int>>()
        val visibilityChanges = mutableListOf<Pair<ProjectionOwner.Token, Boolean>>()

        override fun onProjectionStopped(token: ProjectionOwner.Token) {
            stopped += token
        }

        override fun onCapturedContentResize(token: ProjectionOwner.Token, widthPx: Int, heightPx: Int) {
            resizes += Triple(token, widthPx, heightPx)
        }

        override fun onCapturedContentVisibilityChanged(token: ProjectionOwner.Token, isVisible: Boolean) {
            visibilityChanges += token to isVisible
        }

        fun isEmpty(): Boolean = stopped.isEmpty() && resizes.isEmpty() && visibilityChanges.isEmpty()
    }

    private class RecordingCallbackBoundary : CaptureCallbackBoundary {
        val failures = mutableListOf<Pair<CaptureCallbackIdentity, Exception>>()

        override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
            failures += identity to failure
        }
    }

    private class RecordingProjectionPlatform : ProjectionPlatform {
        val calls = mutableListOf<String>()
        val registeredCallbacks = mutableListOf<MediaProjection.Callback>()
        var creationResult: VirtualDisplay? = null
        var creationFailure: Throwable? = null
        var unregisterFailure: Exception? = null
        var createCount = 0
        var resizeCount = 0
        var setSurfaceCount = 0
        var releaseCount = 0
        var unregisterCount = 0
        var stopCount = 0

        override fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler) {
            calls += "register"
            registeredCallbacks += callback
        }

        override fun createVirtualDisplay(
            projection: MediaProjection,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            surface: Surface,
        ): VirtualDisplay? {
            calls += "create"
            createCount += 1
            creationFailure?.let { throw it }
            return creationResult
        }

        override fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int) {
            resizeCount += 1
        }

        override fun setSurface(display: VirtualDisplay, surface: Surface?) {
            setSurfaceCount += 1
        }

        override fun release(display: VirtualDisplay) {
            releaseCount += 1
        }

        override fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback) {
            calls += "unregister"
            unregisterCount += 1
            unregisterFailure?.let { throw it }
        }

        override fun stop(projection: MediaProjection) {
            calls += "stop"
            stopCount += 1
        }
    }
}
