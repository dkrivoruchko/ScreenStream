package io.screenstream.mjpeg

import android.os.PowerManager
import com.elvishew.xlog.XLog
import io.screenstream.capture.ScreenCaptureProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class, FallibleWakeLockShadow::class])
internal class MjpegCaptureSessionWakeLockTest {
    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE); ShadowPowerManager.clearWakeLocks(); FallibleWakeLockShadow.failAcquire = false
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun optionalWakeLockTracksConfirmationReconfigurationSuspensionAndImmediateStop() = runTest {
        val engine = ControlledMjpegEngine(holdStart = true, holdStop = true)
        val session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine })
        try {
            session.updateScreenAwake(true)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            session.start(); runCurrent()
            session.updateScreenAwake(true)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            engine.finishStart.complete(Unit); runCurrent()
            session.updateScreenAwake(true)
            val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(lock.isHeld)
            for (phase in listOf(MjpegEngineState.Phase.Reconfiguring, MjpegEngineState.Phase.Suspended)) {
                engine.publish(MjpegEngineState(phase, if (phase == MjpegEngineState.Phase.Suspended) ScreenCaptureProblem.InvalidRequest else null))
                runCurrent(); session.updateScreenAwake(true)
                assertTrue(lock.isHeld)
            }
            session.updateScreenAwake(false)
            assertFalse(lock.isHeld)
            session.updateScreenAwake(true)
            assertTrue(lock.isHeld)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            assertFalse(lock.isHeld)
            session.updateScreenAwake(true)
            assertFalse(lock.isHeld)
            runCurrent()
            assertTrue(engine.stopEntered.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            engine.release(); runCurrent()
            assertTrue(session.awaitCleanup())
        } finally {
            engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup()
        }
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun optionalAcquireAndReleaseFailuresNeverBecomeCaptureOrCleanupFailures() = runTest {
        for (acquireFailure in listOf(true, false)) {
            val engine = ControlledMjpegEngine()
            val session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine })
            var lock: PowerManager.WakeLock? = null
            try {
                session.start(); runCurrent()
                FallibleWakeLockShadow.failAcquire = acquireFailure
                session.updateScreenAwake(true)
                lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
                assertEquals(!acquireFailure, lock.isHeld)
                if (!acquireFailure) Shadow.extract<FallibleWakeLockShadow>(lock).failRelease = true
                session.requestStop(MjpegCaptureSession.StopReason.User); runCurrent()
                if (!acquireFailure) assertTrue(Shadow.extract<FallibleWakeLockShadow>(lock).failedReleaseEntered)
                assertTrue(session.awaitCleanup())
                assertNull(session.state.value.captureFailure)
                assertNull(session.state.value.cleanupFailure)
                assertFalse(session.state.value.isStreaming)
            } finally {
                FallibleWakeLockShadow.failAcquire = false
                lock?.let { Shadow.extract<FallibleWakeLockShadow>(it).failRelease = false; if (it.isHeld) it.release() }
                engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup()
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
    var failedReleaseEntered = false
        private set

    @Implementation
    protected override fun acquire() {
        if (failAcquire) throw IllegalStateException("Optional wake-lock acquisition failed")
        super.acquire()
    }

    @Implementation
    protected override fun release(flags: Int) {
        if (failRelease) {
            failedReleaseEntered = true
            throw IllegalStateException("Optional wake-lock release failed")
        }
        super.release(flags)
    }
}
