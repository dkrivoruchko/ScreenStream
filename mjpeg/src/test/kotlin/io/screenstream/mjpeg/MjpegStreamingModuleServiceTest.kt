package io.screenstream.mjpeg

import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
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

    // Cases: MJPEG-CAP-13
    @Test
    @Config(sdk = [24, 26, 29, 34, 37])
    fun capturePromotionUsesModuleNotificationAndProjectionTypeThenRemovesForeground() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.runtime.instanceId)
            host.service.enterCaptureForeground(attempt)
            val serviceShadow = shadowOf(host.service)
            assertEquals(400, serviceShadow.lastForegroundNotificationId)
            assertNotNull(serviceShadow.lastForegroundNotification)
            assertTrue(serviceShadow.isLastForegroundNotificationAttached)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, host.service.foregroundServiceType)
            }
            val stop = shadowOf(host.notifications.stopAction!!).savedIntent
            assertEquals("io.screenstream.action.STOP_CAPTURE", stop.action)
            assertEquals("screenstream", stop.data!!.scheme)
            assertEquals("capture-stop", stop.data!!.authority)
            assertEquals(
                listOf(attempt.instanceId.moduleId.value, attempt.instanceId.uuid.toString(), attempt.uuid.toString()),
                stop.data!!.pathSegments,
            )
            host.service.closeForegroundAdmission()
            host.service.leaveCaptureForeground()
            assertTrue(serviceShadow.isForegroundStopped)
            assertTrue(serviceShadow.notificationShouldRemoved)
            host.service.leaveCaptureForeground()
            val rejected = runCatching { host.service.enterCaptureForeground(attempt) }.exceptionOrNull()
            assertEquals("Foreground promotion rejected", rejected?.message)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13
    @Test
    fun secondPromotionIsRejectedUntilFirstObligationIsRemoved() = runBlocking {
        val host = createHost()
        try {
            host.service.enterCaptureForeground(StreamingModule.CaptureAttemptId(host.runtime.instanceId))
            val rejected = runCatching {
                host.service.enterCaptureForeground(StreamingModule.CaptureAttemptId(host.runtime.instanceId))
            }.exceptionOrNull()
            assertEquals("Foreground promotion rejected", rejected?.message)
            host.service.leaveCaptureForeground()
            host.service.enterCaptureForeground(StreamingModule.CaptureAttemptId(host.runtime.instanceId))
            assertFalse(shadowOf(host.service).isForegroundStopped)
            host.service.leaveCaptureForeground()
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13
    @Test
    fun throwingPromotionPreservesCauseAndStillRequiresForegroundRemoval() = runBlocking {
        val host = createHost()
        try {
            val failure = IllegalStateException("Platform promotion failed")
            val serviceShadow = shadowOf(host.service)
            serviceShadow.setThrowInStartForeground(failure)
            val thrown = runCatching {
                host.service.enterCaptureForeground(StreamingModule.CaptureAttemptId(host.runtime.instanceId))
            }.exceptionOrNull()!!
            assertEquals(failure.javaClass, thrown.javaClass)
            assertEquals(failure.message, thrown.message)
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            host.service.leaveCaptureForeground()
            assertTrue(serviceShadow.isForegroundStopped)
            assertTrue(serviceShadow.notificationShouldRemoved)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    @Config(shadows = [ThrowingForegroundRemovalShadow::class])
    fun throwingForegroundRemovalRetainsFailureWithoutRetryAndClosesOriginalController() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.runtime.instanceId)
            host.service.enterCaptureForeground(attempt)
            val serviceShadow = Shadow.extract<ThrowingForegroundRemovalShadow>(host.service)
            val failure = IllegalStateException("Platform foreground removal failed")
            serviceShadow.removalFailure = failure

            val first = runCatching { host.service.leaveCaptureForeground() }.exceptionOrNull()
            assertSame(failure, first)
            assertSame(first, runCatching { host.service.leaveCaptureForeground() }.exceptionOrNull())
            assertEquals(listOf(Service.STOP_FOREGROUND_REMOVE), serviceShadow.removalCalls)
            assertEquals("Foreground promotion rejected", runCatching { host.service.enterCaptureForeground(attempt) }.exceptionOrNull()?.message)
            assertFalse(serviceShadow.isForegroundStopped)
            assertTrue(serviceShadow.isLastForegroundNotificationAttached)
            withTimeout(5.seconds) {
                host.runtime.failed.await()
                host.http.stopSettled.await()
                host.monitor.closeSettled.await()
            }
            assertTrue(host.http.closed)
            host.destroy()
            withTimeout(5.seconds) { host.service.awaitDestroyed() }
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
            assertSame(first, runCatching { host.service.leaveCaptureForeground() }.exceptionOrNull())
            assertEquals(listOf(Service.STOP_FOREGROUND_REMOVE), serviceShadow.removalCalls)
        } finally {
            assertFalse(host.close())
        }
    }

    @Implements(Service::class)
    class ThrowingForegroundRemovalShadow : ShadowService() {
        var removalFailure: RuntimeException? = null
        val removalCalls = java.util.concurrent.CopyOnWriteArrayList<Int>()

        @Implementation(minSdk = 24)
        protected override fun stopForeground(flags: Int) {
            removalCalls += flags
            removalFailure?.let { throw it }
            super.stopForeground(flags)
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun destroyedPromotedServiceMakesRemovalUnconfirmedAndCleanupUnsuccessful() = runBlocking {
        val host = createHost()
        try {
            host.service.enterCaptureForeground(StreamingModule.CaptureAttemptId(host.runtime.instanceId))
            host.destroy()
            val failure = runCatching { host.service.leaveCaptureForeground() }.exceptionOrNull()
            assertEquals("Foreground release unconfirmed", failure?.message)
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
            assertTrue(host.runtime.failed.isCompleted)
            assertTrue(host.http.closed)
            assertTrue(host.monitor.closeEntered.isCompleted)
            assertEquals("Foreground release unconfirmed", runCatching { host.service.leaveCaptureForeground() }.exceptionOrNull()?.message)
        } finally {
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CAP-13
    @Test
    fun noPromotionNeedsNoRemovalAfterHostLossAndCaptureErrorUsesModuleResources() = runBlocking {
        val host = createHost()
        try {
            host.service.leaveCaptureForeground()
            host.service.showCaptureError()
            assertEquals(listOf(410), host.notifications.shown.value)
            assertEquals(host.service.getString(R.string.mjpeg_capture_error), host.notifications.errorMessage)
            assertFalse(shadowOf(host.service).isForegroundStopped)
            host.destroy()
            host.service.leaveCaptureForeground()
            assertTrue(withTimeout(5.seconds) { host.controller.awaitCleanup() })
        } finally {
            assertTrue(host.close())
        }
    }

    private fun createHost(): MjpegServiceHost {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        return MjpegServiceHost(MjpegSettings(context))
    }
}
