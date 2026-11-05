package io.screenstream.mjpeg

import android.content.pm.ServiceInfo
import android.os.Build
import com.elvishew.xlog.XLog
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegStreamingModuleServiceTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CAP-13, MJPEG-CFG-02
    @Test
    @Config(sdk = [24, 26, 29, 34, 37])
    fun capturePromotionKeepsExitUntilConfirmationThenReturnsToWaitingForeground() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.Exit)
            assertTrue(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
            val platform = shadowOf(host.service)
            assertEquals(400, platform.lastForegroundNotificationId)
            assertTrue(platform.isLastForegroundNotificationAttached)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.Exit)
            if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, host.service.foregroundServiceType)

            host.service.updateCaptureNotification(attempt, true)
            val action = host.notifications.actions.last() as StreamingModuleNotifications.ForegroundAction.StopStream
            val stop = shadowOf(action.pendingIntent).savedIntent
            assertEquals("io.screenstream.action.STOP_CAPTURE", stop.action)
            assertEquals("capture-stop", stop.data!!.authority)
            assertEquals(listOf(attempt.instanceId.moduleId.value, attempt.instanceId.uuid.toString(), attempt.uuid.toString()), stop.data!!.pathSegments)
            host.service.leaveCaptureForeground(attempt)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.Exit)
            assertFalse(platform.isForegroundStopped)
            if (Build.VERSION.SDK_INT >= 34) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, host.service.foregroundServiceType)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13
    @Test
    fun exactForegroundOwnerRejectsOverlapAndIgnoresStaleReleaseAndPresentation() = runBlocking {
        val host = createHost()
        try {
            val first = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            val other = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            assertTrue(host.service.enterCaptureForeground(first, false))
            assertThrows(IllegalStateException::class.java) { host.service.enterCaptureForeground(other, false) }
            host.service.leaveCaptureForeground(other)
            host.service.updateCaptureNotification(other, true)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.Exit)
            host.service.updateCaptureNotification(first, true)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.StopStream)
            host.service.leaveCaptureForeground(first)
            assertTrue(host.service.enterCaptureForeground(other, false))
            host.service.leaveCaptureForeground(other)
            host.service.closeForegroundAdmission()
            assertFalse(host.service.enterCaptureForeground(first, false))
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11
    @Test
    fun throwingPromotionRetainsPlatformCauseAndClosesOriginalController() = runBlocking {
        val host = createHost()
        try {
            val failure = IllegalStateException("Platform promotion failed")
            shadowOf(host.service).setThrowInStartForeground(failure)
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            val thrown = runCatching { host.service.enterCaptureForeground(attempt, false) }.exceptionOrNull()
            assertNotNull(thrown)
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopEntered.await(); host.monitor.closeEntered.await() }
            assertTrue(host.http.closed)
            assertFalse(host.service.enterCaptureForeground(attempt, false))
            host.service.leaveCaptureForeground(attempt)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun waitingRestorationFailureClosesAdmissionAndLeavesOtherCleanupOwnersRunning() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            assertTrue(host.service.enterCaptureForeground(attempt, false))
            val failure = IllegalStateException("Waiting foreground restoration failed")
            shadowOf(host.service).setThrowInStartForeground(failure)
            assertSame(failure, runCatching { host.service.leaveCaptureForeground(attempt) }.exceptionOrNull())
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopSettled.await(); host.monitor.closeSettled.await() }
            assertFalse(host.service.enterCaptureForeground(attempt, false))
            host.service.leaveCaptureForeground(attempt)
            assertTrue(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun unexpectedServiceDestructionReportsFailureAndMakesModuleReceiptFalse() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            assertTrue(host.service.enterCaptureForeground(attempt, false))
            host.destroy()
            withTimeout(5.seconds) { host.managerObservation.failed.await() }
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
            assertFalse(host.service.enterCaptureForeground(attempt, false))
            assertNotNull(runCatching { host.service.leaveCaptureForeground(attempt) }.exceptionOrNull())
            assertTrue(host.http.closed)
            assertTrue(host.monitor.closeEntered.isCompleted)
        } finally {
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CFG-01
    @Test
    fun descriptorListsMetadataWithoutAcquiringResourcesAndRollbackBeforePromotionIsSafe() = runBlocking {
        val descriptor = MjpegStreamingModule()
        assertEquals(StreamingModule.Id("MJPEG"), descriptor.id)
        assertEquals(40, descriptor.priority)
        assertEquals(400, descriptor.foregroundSlot.id)
        assertEquals(MjpegStreamingModuleService::class.java, descriptor.serviceClass)
        assertEquals(R.string.mjpeg_method_title, descriptor.description.titleRes)
        assertTrue(RuntimeEnvironment.getApplication().getString(descriptor.description.titleRes).isNotBlank())
        val host = createHost()
        try {
            host.service.leaveCaptureForeground(StreamingModule.CaptureAttemptId(host.managerObservation.instanceId))
            assertFalse(shadowOf(host.service).isForegroundStopped)
            assertTrue(host.notifications.actions.last() is StreamingModuleNotifications.ForegroundAction.Exit)
        } finally {
            assertTrue(host.close())
        }
    }

    private fun createHost(): MjpegServiceHost {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        return MjpegServiceHost(MjpegSettings(context))
    }
}
