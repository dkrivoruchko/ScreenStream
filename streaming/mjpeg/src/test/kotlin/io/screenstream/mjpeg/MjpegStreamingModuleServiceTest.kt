package io.screenstream.mjpeg

import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegStreamingModuleServiceTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11
    @Test
    fun capturePromotionFailureClosesOriginalMjpegCleanupOwners() = runBlocking {
        val host = createHost()
        try {
            val failure = IllegalStateException("Platform promotion failed")
            shadowOf(host.service).setThrowInStartForeground(failure)
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            val thrown = runCatching { host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false) }.exceptionOrNull()
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            awaitOriginalMjpegCleanupOwners(host)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CAP-13, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun waitingRestorationFailureLeavesOriginalMjpegCleanupOwnersRunning() = runBlocking {
        val host = createHost()
        try {
            val attempt = StreamingModule.CaptureAttemptId(host.managerObservation.instanceId)
            assertTrue(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
            val failure = IllegalStateException("Waiting foreground restoration failed")
            shadowOf(host.service).setThrowInStartForeground(failure)
            val thrown = runCatching { host.service.leaveCaptureForeground(attempt) }.exceptionOrNull()
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            awaitOriginalMjpegCleanupOwners(host)
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
            assertTrue(host.service.enterCaptureForeground(attempt, requiresMicrophoneType = false))
            host.destroy()
            awaitOriginalMjpegCleanupOwners(host)
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
        } finally {
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CFG-01
    @Test
    fun descriptorExposesMjpegMetadata() {
        val descriptor = MjpegStreamingModule()
        assertEquals(StreamingModule.Id("MJPEG"), descriptor.id)
        assertEquals(40, descriptor.priority)
        assertEquals(400, descriptor.foregroundSlot.id)
        assertEquals(MjpegStreamingModuleService::class.java, descriptor.serviceClass)
        assertEquals(R.string.mjpeg_method_title, descriptor.description.titleRes)
        assertTrue(RuntimeEnvironment.getApplication().getString(descriptor.description.titleRes).isNotBlank())
    }

    private suspend fun awaitOriginalMjpegCleanupOwners(host: MjpegServiceTestHost) {
        withTimeout(5.seconds) {
            host.managerObservation.failed.await()
            host.http.stopSettled.await()
            host.monitor.closeSettled.await()
        }
        assertTrue(host.http.closed)
    }

    private fun createHost(): MjpegServiceTestHost {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        return MjpegServiceTestHost(MjpegSettings(context))
    }
}
