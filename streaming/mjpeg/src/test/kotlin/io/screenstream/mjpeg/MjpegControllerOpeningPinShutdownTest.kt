package io.screenstream.mjpeg

import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegControllerOpeningPinShutdownTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Cases: MJPEG-CTL-06, MJPEG-CTL-12, MJPEG-CTL-13
    @Test
    fun shutdownWaitsForAnEnteredNonCancellableOpeningPinSaveAfterOtherOwnersFinish() = runBlocking {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val data = MjpegSettings.Data(
            web = WebPageSettings(title = "Opening save lifetime"),
            access = AccessSettings(pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = null),
        )
        seedSettings(context, Json.encodeToString(data))
        assertNull(Json.decodeFromString<MjpegSettings.Data>(readSavedSettingsJson(context)).access.pin)
        val scheduler = TestCoroutineScheduler()
        val saveDispatcher = StandardTestDispatcher(scheduler)
        val admittedSaveJob = CompletableDeferred<Job>()
        val observedSaveDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                saveDispatcher.dispatch(context, block)
                admittedSaveJob.complete(checkNotNull(context[Job]))
            }
        }
        val host = MjpegServiceTestHost(MjpegSettings(context, observedSaveDispatcher))
        var cleanupResult: Deferred<Boolean>? = null
        try {
            withTimeout(5.seconds) { admittedSaveJob.await() }
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            host.controller.requestModuleShutdown()
            val cleanupWaiter = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
            cleanupResult = cleanupWaiter
            withTimeout(5.seconds) { host.http.stopSettled.await(); host.monitor.closeSettled.await(); host.awaitStopRequested() }
            host.destroy()
            // Cleanup runs on Default; give it real execution time while the admitted save stays queued.
            assertNull(withTimeoutOrNull(100.milliseconds) { cleanupWaiter.await() })
            assertFalse(cleanupWaiter.isCompleted)
            host.controller.requestStreamStart()
            host.controller.startModule()
            withTimeout(10.seconds) {
                while (!cleanupWaiter.isCompleted) {
                    scheduler.runCurrent()
                    yield()
                }
            }
            assertTrue(cleanupWaiter.await())
            val saved = Json.decodeFromString<MjpegSettings.Data>(readSavedSettingsJson(context))
            assertNotNull(saved.access.pin)
            assertTrue(saved.access.pinEnabled)
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
        } finally {
            host.controller.requestModuleShutdown()
            val pendingCleanup = cleanupResult ?: async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
            try {
                withTimeout(10.seconds) {
                    while (!pendingCleanup.isCompleted || (admittedSaveJob.isCompleted && !admittedSaveJob.getCompleted().isCompleted)) {
                        scheduler.runCurrent()
                        if (host.http.stopSettled.isCompleted && host.monitor.closeSettled.isCompleted) {
                            host.awaitStopRequested()
                            host.destroy()
                        }
                        yield()
                    }
                }
            } finally {
                assertTrue(host.close())
            }
        }
    }
}
