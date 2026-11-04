package io.screenstream.mjpeg

import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
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
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegOpeningPinShutdownTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-06, MJPEG-CTL-12, MJPEG-CTL-13
    @Test
    fun shutdownWaitsForAnEnteredNonCancellableOpeningPinSaveAfterOtherOwnersFinish() = runBlocking {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val data = MjpegSettings.Data(
            web = WebPageSettings(title = "Opening save lifetime"),
            access = AccessSettings(pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = SecretValue("123456")),
        )
        seedSettings(context, Json.encodeToString(data))
        val scheduler = TestCoroutineScheduler()
        val queued = StandardTestDispatcher(scheduler)
        val admitted = CompletableDeferred<Job>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.dispatch(context, block)
                admitted.complete(checkNotNull(context[Job]))
            }
        }
        val host = MjpegServiceHost(MjpegSettings(context, dispatcher))
        var cleanup: Deferred<Boolean>? = null
        try {
            withTimeout(5.seconds) { admitted.await() }
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            host.controller.requestModuleShutdown()
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
            cleanup = receipt
            withTimeout(5.seconds) { host.http.stopSettled.await(); host.monitor.closeSettled.await(); host.awaitStopRequested() }
            host.destroy()
            assertFalse(receipt.isCompleted)
            host.controller.requestStreamStart()
            host.controller.startModule()
            withTimeout(10.seconds) {
                while (!receipt.isCompleted) {
                    scheduler.runCurrent()
                    yield()
                }
            }
            assertTrue(receipt.await())
            val saved = Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context))
            val pin = checkNotNull(saved.access.pin).value
            assertEquals(6, pin.length)
            assertTrue(pin.all { it in '0'..'9' })
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            assertTrue(host.http.captures.value.isEmpty())
        } finally {
            host.controller.requestModuleShutdown()
            val pending = cleanup ?: async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
            try {
                withTimeout(10.seconds) {
                    while (!pending.isCompleted || (admitted.isCompleted && !admitted.getCompleted().isCompleted)) {
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
