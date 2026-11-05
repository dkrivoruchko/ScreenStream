package io.screenstream.capture.internal.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
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
import io.screenstream.capture.ScreenCaptureProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.CINNAMON_BUN])
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
            projectionCallbackHandler = controlHandler,
            callbackSink = callbackSink,
            callbackBoundary = callbackBoundary,
            stopCompletion = ProjectionStopCompletion(),
        )

        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        assertSame(ProjectionOwner.VirtualDisplayCreationResult.Created, owner.createVirtualDisplay(widthPx = 1080, heightPx = 1920, densityDpi = 420, surface = surface))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.resizeIfChanged(widthPx = 720, heightPx = 1280, densityDpi = 320))
        assertThrows(IllegalStateException::class.java) {
            owner.createVirtualDisplay(widthPx = 1080, heightPx = 1920, densityDpi = 420, surface = surface)
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
            projection.stop()
            projection.unregisterCallback(refEq(callback.captured))
        }
        confirmVerified(projection, display)
    }

    // Verification: CAP-01
    @Test
    fun configuredExtentAndDensityDeduplicateWhileCallbacksKeepSourceDimensions() {
        val platform = RecordingProjectionPlatform().apply { creationResult = mockk() }
        val callbacks = RecordingCallbackSink()
        val surface = mockk<Surface>()
        val owner = owner(mockk(), platform, callbackSink = callbacks)
        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        assertSame(ProjectionOwner.VirtualDisplayCreationResult.Created,
            owner.createVirtualDisplay(widthPx = 540, heightPx = 1200, densityDpi = 420, surface = surface))

        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 540, heightPx = 1200, densityDpi = 420))
        assertTrue(platform.resizeConfigurations.isEmpty())
        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 540, heightPx = 1200, densityDpi = 480))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 810, heightPx = 1800, densityDpi = 480))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 810, heightPx = 1800, densityDpi = 480))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 810, heightPx = 1200, densityDpi = 480))
        assertSame(ProjectionOwner.ProjectionOperationResult.Success,
            owner.resizeIfChanged(widthPx = 540, heightPx = 1200, densityDpi = 480))
        platform.registeredCallbacks.single().onCapturedContentResize(1080, 2400)

        assertEquals(listOf(Triple(540, 1200, 420)), platform.creationConfigurations)
        assertEquals(
            listOf(Triple(540, 1200, 480), Triple(810, 1800, 480), Triple(810, 1200, 480), Triple(540, 1200, 480)),
            platform.resizeConfigurations,
        )
        assertEquals(listOf(Triple(owner.token, 1080, 2400)), callbacks.resizes)
        assertEquals(1, platform.createCount)
        val retired = owner.retireDisplay(surface)
        assertTrue(retired.detachProof?.matches(surface) == true)
        assertNotNull(retired.releaseProof)
        assertNull(retired.residue)
        owner.fenceCallbacks()
        assertNull(owner.retireCallbackAndProjection().residue)
        assertEquals(1, platform.releaseCount)
    }

    // Verification: CAP-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun projectionStopPrecedesEnteredCallbackUnregister() {
        val projection = mockk<MediaProjection>()
        val unregisterEntered = CountDownLatch(1)
        val unregisterMayReturn = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val unregisterCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val platform = mockk<ProjectionPlatform>()
        every { platform.registerCallback(refEq(projection), any(), any()) } just Runs
        every { platform.unregisterCallback(refEq(projection), any()) } answers {
            unregisterCount.incrementAndGet()
            unregisterEntered.countDown()
            check(unregisterMayReturn.await(5L, TimeUnit.SECONDS)) { "Callback unregister was not released" }
        }
        every { platform.stop(refEq(projection)) } answers {
            stopCount.incrementAndGet()
            stopEntered.countDown()
        }
        val owner = owner(projection, platform)
        assertSame(ProjectionOwner.ProjectionOperationResult.Success, owner.registerCallback())
        val outcome = AtomicReference<ProjectionOwner.ProjectionRetirementOutcome?>()
        val retirementFailure = AtomicReference<Throwable?>()
        val retirementReturned = CountDownLatch(1)
        val retirementThread = Thread({
            try {
                outcome.set(owner.retireCallbackAndProjection())
            } catch (failure: Throwable) {
                retirementFailure.set(failure)
            } finally {
                retirementReturned.countDown()
            }
        }, "ScreenCaptureEngine-Blocking-Unregister")

        try {
            retirementThread.start()
            check(unregisterEntered.await(5L, TimeUnit.SECONDS)) { "Callback unregister did not enter" }

            assertEquals(0L, stopEntered.count)
            assertEquals(1L, retirementReturned.count)
            assertEquals(1, unregisterCount.get())
            assertEquals(1, stopCount.get())

            unregisterMayReturn.countDown()
            check(stopEntered.await(5L, TimeUnit.SECONDS)) { "Projection stop did not enter" }
            check(retirementReturned.await(5L, TimeUnit.SECONDS)) { "Projection retirement did not return" }
            retirementThread.join(5_000L)

            assertFalse("Projection retirement thread did not return", retirementThread.isAlive)
            retirementFailure.get()?.let { throw AssertionError("Projection retirement failed", it) }
            assertNull(checkNotNull(outcome.get()).cleanupFailure)
            assertNull(checkNotNull(outcome.get()).residue)
            assertEquals(1, unregisterCount.get())
            assertEquals(1, stopCount.get())
        } finally {
            unregisterMayReturn.countDown()
            retirementThread.join(5_000L)
        }
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
            owner.createVirtualDisplay(widthPx = 1080, heightPx = 1920, densityDpi = 420, surface = surface)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.createVirtualDisplay(widthPx = 1080, heightPx = 1920, densityDpi = 420, surface = surface)
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
        assertEquals(listOf("register", "stop", "unregister"), platform.calls)
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
        assertResult(owner.createVirtualDisplay(widthPx = 1080, heightPx = 1920, densityDpi = 420, surface = surface))
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
        projectionCallbackHandler = Handler(Looper.getMainLooper()),
        callbackSink = callbackSink,
        callbackBoundary = callbackBoundary,
        platform = platform,
        stopCompletion = ProjectionStopCompletion(),
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
        val creationConfigurations = mutableListOf<Triple<Int, Int, Int>>()
        val resizeConfigurations = mutableListOf<Triple<Int, Int, Int>>()
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
            creationConfigurations += Triple(widthPx, heightPx, densityDpi)
            creationFailure?.let { throw it }
            return creationResult
        }

        override fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int) {
            resizeCount += 1
            resizeConfigurations += Triple(widthPx, heightPx, densityDpi)
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
