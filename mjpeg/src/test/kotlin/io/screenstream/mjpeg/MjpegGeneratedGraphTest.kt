package io.screenstream.mjpeg

import android.app.Application
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegGeneratedGraphTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CFG-01, MJPEG-CFG-02, MJPEG-CTL-01
    @Test
    fun actualMjpegGraphKeepsProcessSettingsAndMetadataButCreatesInertInstanceOwners() = runBlocking {
        val application: Application = RuntimeEnvironment.getApplication()
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        val controllers = mutableListOf<MjpegStreamingModuleController>()
        val services = mutableListOf<ServiceController<MjpegStreamingModuleService>>()
        val deliveries = mutableListOf<HttpDelivery>()
        val monitors = mutableListOf<NetworkAddressMonitor>()
        var registered = false
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = startKoin {
                registered = true
                modules(MjpegKoinModule::class)
                modules(module {
                    single<Context> { context }
                    single<ScreenCaptureAccess> { TestCaptureAccess() }
                    single<StreamingSessionAnalyticsFactory> { TestSessionAnalyticsFactory }
                })
            }
            val descriptor = app.koin.get<StreamingModule>()
            assertSame(descriptor, app.koin.get<StreamingModule>())
            assertEquals(MjpegStreamingModule.Id, descriptor.id)
            assertEquals(MjpegStreamingModuleService::class.java, descriptor.serviceClass)
            assertSame(app.koin.get<MjpegSettings>(), app.koin.get<MjpegSettings>())
            repeat(2) {
                deliveries += app.koin.get<HttpDelivery>()
                monitors += app.koin.get<NetworkAddressMonitor>()
                val service = Robolectric.buildService(MjpegStreamingModuleService::class.java).create()
                services += service
                val recordingHandle = TestManagerHandle()
                val handle: StreamingModule.Controller.ManagerHandle = recordingHandle
                val originalService: MjpegStreamingModuleService = checkNotNull(service.get())
                val controller = app.koin.get<MjpegStreamingModuleController> { parametersOf(handle, originalService) }
                controllers += controller
                assertEquals(handle.instanceId, controller.instanceId)
                assertEquals(UiController.Action.Start(false), controller.state.value.action)
                assertTrue(controller.state.value.statistics.history.isEmpty())
                assertNull(recordingHandle.status.value)
                assertFalse(recordingHandle.failed.isCompleted)
            }
            assertNotSame(controllers[0], controllers[1])
            assertNotEquals(controllers[0].instanceId, controllers[1].instanceId)
            assertNotSame(deliveries[0], deliveries[1])
            assertNotSame(monitors[0], monitors[1])
            deliveries.forEach { assertEquals(HttpDelivery.Info(), it.info.value); assertNull(it.appliedToken) }
            monitors.forEach { assertEquals(NetworkAddressMonitor.State.NotStarted, it.state.value) }
            assertTrue(shadowOf(application).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) }.isEmpty())
        } finally {
            try {
                withContext(NonCancellable) {
                    controllers.forEach { it.requestModuleShutdown() }
                    deliveries.forEach { it.requestStop() }
                    services.forEachIndexed { index, service ->
                        if (index < controllers.size) withTimeout(10.seconds) { while (!shadowOf(service.get()).isStoppedBySelf) yield() }
                        service.destroy()
                    }
                    controllers.forEach { assertTrue(withTimeout(10.seconds) { it.awaitCleanup() }) }
                    deliveries.forEach { withTimeout(10.seconds) { it.stop() } }
                    monitors.forEach { withTimeout(10.seconds) { it.close() } }
                }
            } finally {
                try {
                    if (registered) stopKoin()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }
}
