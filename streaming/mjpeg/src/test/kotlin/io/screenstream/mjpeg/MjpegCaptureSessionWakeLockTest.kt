package io.screenstream.mjpeg

import android.os.PowerManager
import io.screenstream.capture.ScreenCaptureProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class, FallibleWakeLockShadow::class])
internal class MjpegCaptureSessionWakeLockTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    @Before
    fun initialize() {
        ShadowPowerManager.clearWakeLocks()
        FallibleWakeLockShadow.failAcquire = false
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun optionalWakeLockTracksConfirmationReconfigurationSuspensionAndImmediateStop() = runTest {
        val engine = ControlledMjpegEngineSession(holdStart = true, holdStop = true)
        val session = MjpegCaptureSessionInputs().createSession(backgroundScope, { _, _, _ -> engine })
        try {
            session.updateScreenAwake(true)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            session.start()
            runCurrent()
            session.updateScreenAwake(true)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            engine.allowStartToReturn.complete(Unit)
            runCurrent()
            session.updateScreenAwake(true)
            val wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(wakeLock.isHeld)
            for (phase in listOf(MjpegEngineState.Phase.Reconfiguring, MjpegEngineState.Phase.Suspended)) {
                engine.publish(MjpegEngineState(phase, if (phase == MjpegEngineState.Phase.Suspended) ScreenCaptureProblem.InvalidRequest else null))
                runCurrent()
                session.updateScreenAwake(true)
                assertTrue(wakeLock.isHeld)
            }
            session.updateScreenAwake(false)
            assertFalse(wakeLock.isHeld)
            session.updateScreenAwake(true)
            assertTrue(wakeLock.isHeld)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            assertFalse(wakeLock.isHeld)
            session.updateScreenAwake(true)
            assertFalse(wakeLock.isHeld)
            runCurrent()
            assertTrue(engine.stopEntered.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            engine.releaseAllGates()
            runCurrent()
            assertTrue(session.awaitCleanup())
        } finally {
            engine.releaseAllGates()
            session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
            runCurrent()
            session.awaitCleanup()
        }
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun optionalAcquireAndReleaseFailuresNeverBecomeCaptureOrCleanupFailures() = runTest {
        for (shouldFailAcquire in listOf(true, false)) {
            val engine = ControlledMjpegEngineSession()
            val session = MjpegCaptureSessionInputs().createSession(backgroundScope, { _, _, _ -> engine })
            var wakeLock: PowerManager.WakeLock? = null
            try {
                session.start()
                runCurrent()
                FallibleWakeLockShadow.failAcquire = shouldFailAcquire
                session.updateScreenAwake(true)
                wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
                assertEquals(!shouldFailAcquire, wakeLock.isHeld)
                if (!shouldFailAcquire) Shadow.extract<FallibleWakeLockShadow>(wakeLock).failRelease = true
                session.requestStop(MjpegCaptureSession.StopReason.User)
                runCurrent()
                if (!shouldFailAcquire) assertTrue(Shadow.extract<FallibleWakeLockShadow>(wakeLock).releaseFailureTriggered)
                assertTrue(session.awaitCleanup())
                assertNull(session.state.value.captureFailure)
                assertNull(session.state.value.cleanupFailure)
                assertFalse(session.state.value.isStreaming)
            } finally {
                FallibleWakeLockShadow.failAcquire = false
                wakeLock?.let { Shadow.extract<FallibleWakeLockShadow>(it).failRelease = false; if (it.isHeld) it.release() }
                engine.releaseAllGates()
                session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
                runCurrent()
                session.awaitCleanup()
            }
        }
    }
}

@Implements(PowerManager.WakeLock::class)
internal class FallibleWakeLockShadow : ShadowPowerManager.ShadowWakeLock() {
    companion object {
        var failAcquire = false
    }

    var failRelease = false
    var releaseFailureTriggered = false
        private set

    @Implementation
    protected override fun acquire() {
        if (failAcquire) throw IllegalStateException("Optional wake-lock acquisition failed")
        super.acquire()
    }

    @Implementation
    protected override fun release(flags: Int) {
        if (failRelease) {
            releaseFailureTriggered = true
            throw IllegalStateException("Optional wake-lock release failed")
        }
        super.release(flags)
    }
}
