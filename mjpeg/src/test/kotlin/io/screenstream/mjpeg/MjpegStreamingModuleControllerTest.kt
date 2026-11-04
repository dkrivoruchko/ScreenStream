package io.screenstream.mjpeg

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import com.elvishew.xlog.XLog
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegStreamingModuleControllerTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-01
    @Test
    fun serviceInstallsOriginalControllerAndRepeatedCurrentStartHasOneLifetime() = runBlocking {
        val settings = settings()
        val host = MjpegServiceHost(settings)
        try {
            assertTrue(host.access.requested.value.isEmpty())
            host.controller.requestStreamStart()
            host.controller.startModule()
            host.controller.startModule()
            val configuration = host.http.configuration.await { it != null }
            assertEquals("Fixture device", configuration!!.web.title)
            host.monitor.started.await { it }
            host.managerHandle.status.await { it != null }
            applySettingsCheckpoint(settings, host, "Repeated start remains healthy")
            assertEquals(1, host.http.startCalls.value)
            assertTrue(host.managerHandle.isCurrent())
            assertFalse(host.managerHandle.failed.isCompleted)
            assertFalse(host.http.closed)
            assertFalse(host.managerHandle.status.value!!.isStreaming)
            assertTrue(host.http.captures.value.isEmpty())
            val packagedLogo = host.service.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            assertTrue(packagedLogo.isNotEmpty())
            assertArrayEquals(packagedLogo, host.http.initialLogo)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-01, MJPEG-CTL-12
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staleStartAndShutdownBeforeStartCloseAdmissionWithoutOrdinaryWork() = runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            for (stale in listOf(false, true)) {
                val handle = TestManagerHandle().also { it.current = !stale }
                val serviceController = org.robolectric.Robolectric.buildService(MjpegStreamingModuleService::class.java).create()
                val http = TestHttpDelivery()
                val monitor = TestAddressMonitor()
                val controller = MjpegStreamingModuleController(
                    handle,
                    serviceController.get(),
                    lazyOf(settings()),
                    monitor,
                    TestCaptureAccess(),
                    http,
                    TestSessionAnalyticsFactory
                )
                try {
                    assertFalse(monitor.started.value)
                    assertNull(http.configuration.value)
                    if (!stale) controller.requestModuleShutdown()
                    controller.startModule()
                    controller.startModule()
                    controller.requestStreamStart()
                    withTimeout(5.seconds) { http.stopEntered.await(); monitor.closeEntered.await() }
                    assertTrue(http.closed)
                    assertFalse(monitor.started.value)
                    assertTrue(http.captures.value.isEmpty())
                } finally {
                    controller.requestModuleShutdown()
                    withTimeout(5.seconds) {
                        while (!shadowOf(serviceController.get()).isStoppedBySelf) kotlinx.coroutines.yield()
                    }
                    serviceController.destroy()
                    assertTrue(withTimeout(5.seconds) { controller.awaitCleanup() })
                }
            }
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    // Cases: MJPEG-CTL-01, MJPEG-CTL-04
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun idleLoopReportsProcessedUptimeHeartbeatsAndStaleHandleRetiresWithoutFailure() = runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val service = org.robolectric.Robolectric.buildService(MjpegStreamingModuleService::class.java).create()
        val handle = TestManagerHandle()
        val http = TestHttpDelivery()
        val monitor = TestAddressMonitor()
        val controller =
            MjpegStreamingModuleController(handle, service.get(), lazyOf(settings()), monitor, TestCaptureAccess(), http, TestSessionAnalyticsFactory)
        var destroyed = false
        try {
            assertTrue(handle.reports.isEmpty())
            assertTrue(controller.state.value.statistics.history.isEmpty())
            controller.startModule()
            http.configuration.await { it != null }
            withTimeout(5.seconds) { while (handle.reports.isEmpty()) kotlinx.coroutines.yield() }
            val before = handle.reports.size
            val previousUptime = handle.reports.last().second
            ShadowSystemClock.advanceBy(Duration.ofMillis(1500))
            http.info.value = HttpDelivery.Info(servers = listOf(HttpDelivery.ServerInfo(Uuid.random(), address(), 8080, HttpDelivery.ServerState.Listening)))
            withTimeout(5.seconds) { while (handle.reports.size <= before) kotlinx.coroutines.yield() }
            val heartbeat = handle.reports.last()
            assertEquals(SystemClock.uptimeMillis(), heartbeat.second)
            assertTrue(heartbeat.second - previousUptime >= 1500L)
            assertFalse(heartbeat.first.isStreaming)
            assertFalse(heartbeat.first.hasConsumer)
            assertNull(heartbeat.first.captureAttempt)
            handle.current = false
            http.info.value = http.info.value.copy(servers = emptyList())
            withTimeout(5.seconds) { http.stopEntered.await(); monitor.closeEntered.await() }
            assertFalse(handle.failed.isCompleted)
            controller.requestStreamStart()
            assertTrue(http.captures.value.isEmpty())
            withTimeout(5.seconds) { while (!shadowOf(service.get()).isStoppedBySelf) kotlinx.coroutines.yield() }
            service.destroy()
            destroyed = true
            assertTrue(withTimeout(5.seconds) { controller.awaitCleanup() })
        } finally {
            try {
                controller.requestModuleShutdown()
                if (!destroyed) {
                    withTimeout(5.seconds) { while (!shadowOf(service.get()).isStoppedBySelf) kotlinx.coroutines.yield() }
                    service.destroy()
                }
                assertTrue(withTimeout(5.seconds) { controller.awaitCleanup() })
            } finally {
                kotlinx.coroutines.Dispatchers.resetMain()
            }
        }
    }

    // Cases: MJPEG-CTL-12, MJPEG-CTL-13
    @Test
    fun shutdownDuringEnteredHttpStartClosesOwnersAndWaitsForOrdinaryWork() = runBlocking {
        withTimeout(10.seconds) {
            val startEntered = CompletableDeferred<Unit>()
            val releaseStart = CountDownLatch(1)
            val http = TestHttpDelivery(onStart = {
                startEntered.complete(Unit)
                check(releaseStart.await(20, TimeUnit.SECONDS)) { "HTTP start release was not permitted" }
            })
            val host = MjpegServiceHost(settings(), http = http)
            try {
                host.controller.startModule()
                startEntered.await()
                host.controller.requestModuleShutdown()
                assertTrue(http.closed)
                host.controller.requestStreamStart()
                val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
                http.stopEntered.await()
                host.monitor.closeEntered.await()
                http.stopSettled.await()
                host.monitor.closeSettled.await()
                host.awaitStopRequested()
                host.destroy()
                assertNull(withTimeoutOrNull(100.milliseconds) { receipt.await() })
                assertFalse(receipt.isCompleted)
                releaseStart.countDown()
                assertTrue(receipt.await())
                assertEquals(1, http.startCalls.value)
                assertNull(http.configuration.value)
                assertTrue(http.captures.value.isEmpty())
                assertTrue(host.access.requested.value.isEmpty())
                assertEquals(NetworkAddressMonitor.State.Closed, host.monitor.state.value)
            } finally {
                releaseStart.countDown()
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-02
    @Test
    fun startNeedsMatchingReadyAddressAndListeningServerAtCurrentPort() = runBlocking {
        for (gate in listOf("loading", "empty", "wrong-filter", "pending", "failed", "wrong-port", "wrong-address")) {
            val settings = settings()
            val host = MjpegServiceHost(settings)
            try {
                startModuleWithListeningServer(host)
                val filter = host.settings.data.first().network.filter
                val selected = address()
                when (gate) {
                    "loading" -> publishDiscovery(host, NetworkAddressMonitor.State.Loading)
                    "empty" -> publishDiscovery(host, observedAddresses(emptyList()))
                    "wrong-filter" -> publishDiscovery(
                        host,
                        observedAddresses(listOf(address(id = 99, ip = "203.0.113.9", type = InterfaceType.Vpn))),
                    )

                    "pending" -> publishServerState(host, selected, state = HttpDelivery.ServerState.Pending)
                    "failed" -> publishServerState(host, selected, state = HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.AddressInUse, 3))
                    "wrong-port" -> publishServerState(host, selected, port = 9000)
                    "wrong-address" -> publishServerState(host, address(id = 99))
                }
                host.controller.requestStreamStart()
                applySettingsCheckpoint(settings, host, "Rejected $gate")
                assertTrue(gate, host.http.captures.value.isEmpty())
                assertTrue(gate, host.access.requested.value.isEmpty())
                publishDiscovery(host, observedAddresses(listOf(selected)))
                publishServerState(host, selected)
                applySettingsCheckpoint(settings, host, "Ready $gate")
                host.controller.requestStreamStart()
                host.access.requested.await { it.size == 1 }
                assertEquals(host.http.captures.value.single(), host.access.requested.value.single())
                assertFalse(host.managerHandle.status.await { it?.captureAttempt != null }!!.isStreaming)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-CTL-03, MJPEG-CTL-04
    @Test
    fun duplicateStartAndStaleStopCannotReplaceHeldConsentButExactStopAllowsNextAttempt() = runBlocking<Unit> {
        val settings = settings()
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val first = host.access.requested.await { it.size == 1 }.single()
            host.controller.requestStreamStart()
            host.controller.requestStreamStop(StreamingModule.CaptureAttemptId(host.managerHandle.instanceId), false)
            applySettingsCheckpoint(settings, host, "Stale Stop processed")
            assertEquals(listOf(first), host.http.captures.value)
            assertTrue(host.access.finishCalls.value.isEmpty())
            assertEquals(first, host.managerHandle.status.await { it?.captureAttempt == first }!!.captureAttempt)
            val delivery = host.http.info.value
            host.http.info.value = delivery.copy(
                clients = listOf(
                    HttpDelivery.ClientInfo(
                        id = 1, ip = "198.51.100.2", remotePort = 49152,
                        serverId = delivery.servers.single().id, format = HttpDelivery.MediaFormat.Jpeg,
                        isSlow = false, completedJpegBytes = 0,
                    ),
                ),
            )
            val consumed = host.managerHandle.status.await { it?.hasConsumer == true }!!
            assertEquals(first, consumed.captureAttempt)
            assertFalse(consumed.isStreaming)
            host.controller.requestStreamStop(first, false)
            host.http.captureStatus.await { it?.first == first && it.second == MjpegCaptureSession.State.Status.Stopped }
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertFalse(host.http.closed)
            host.controller.requestStreamStart()
            val second = host.access.requested.await { it.size == 2 }.last()
            assertNotEquals(first, second)
            host.controller.requestStreamStop(first, false)
            applySettingsCheckpoint(settings, host, "Old Stop after replacement")
            assertEquals(second, host.managerHandle.status.await { it?.captureAttempt == second }!!.captureAttempt)
            assertEquals(listOf(first), host.access.finishCalls.value)
            host.controller.requestStreamStop(second, false)
            host.http.captureStatus.await { it?.first == second && it.second == MjpegCaptureSession.State.Status.Stopped }
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05
    @Test
    fun busySavedSetupRemainsDeferredWhileLiveAppearanceAndFilterApply() = runBlocking {
        val settings = settings()
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            val originalAccess = host.http.configuration.value!!.access
            settings.updateData {
                copy(
                    image = image.copy(jpegQuality = 45, jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly),
                    network = network.copy(filter = otherFilter(), httpPort = 9000),
                    access = AccessSettings(true, AccessSettings.PinPolicy.Permanent, SecretValue("001234")),
                    web = web.copy(title = "Live appearance"),
                )
            }
            host.http.planApplications.await { it.lastOrNull()?.addresses?.isEmpty() == true }
            val busyConfiguration = host.http.configuration.await { it?.web?.title == "Live appearance" }!!
            assertEquals(originalAccess, busyConfiguration.access)
            assertEquals(8080, host.http.plan.value!!.port)
            assertEquals(9000, settings.data.first().network.httpPort)
            host.controller.requestStreamStop(attempt, false)
            host.http.configuration.await { it?.access?.pinEnabled == true }
            host.http.plan.await { it?.port == 9000 }
            assertEquals(settings.data.first().access, host.http.configuration.value!!.access)
            assertEquals("Live appearance", host.http.configuration.value!!.web.title)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05
    @Test
    fun failedAttemptCleanupKeepsSetupRestrictedAndBlocksReplacement() = runBlocking {
        val settings = settings()
        val access = TestCaptureAccess().also { it.cleanupFailure = IllegalStateException("Guard cleanup failed") }
        val host = MjpegServiceHost(settings, access = access)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = access.requested.await { it.size == 1 }.single()
            host.controller.requestStreamStop(attempt, false)
            host.http.captureStatus.await { it?.second == MjpegCaptureSession.State.Status.Failed }
            withTimeout(5.seconds) { host.managerHandle.failed.await(); host.http.stopEntered.await() }
            settings.updateData { copy(network = network.copy(httpPort = 9000), web = web.copy(title = "After failed cleanup")) }
            host.controller.requestStreamStart()
            assertEquals(9000, settings.data.first().network.httpPort)
            assertTrue(host.http.closed)
            assertEquals(listOf(attempt), host.http.captures.value)
            assertEquals(attempt, host.managerHandle.status.value!!.captureAttempt)
            assertEquals(8080, host.http.plan.value!!.port)
            assertFalse(host.managerHandle.status.value!!.isStreaming)
        } finally {
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CTL-08
    @Test
    fun loadingRetainsSelectionButObservedInventoryIsReselectedByTheCurrentFilter() = runBlocking {
        val settings = settings()
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            val first = host.http.plan.value!!
            publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            assertEquals(first, host.http.plan.value)
            publishDiscovery(
                host,
                observedAddresses(listOf(address(id = 99, ip = "203.0.113.9", type = InterfaceType.Vpn))),
            )
            assertTrue(host.http.plan.value!!.addresses.isEmpty())
            val replacement = address(id = 2)
            publishDiscovery(host, observedAddresses(listOf(replacement)))
            assertEquals(listOf(replacement), host.http.plan.value!!.addresses)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-06
    @Test
    fun modulePinPreparationAppliesValidSavedPinAndPermanentPolicyRetainsItsPin() = runBlocking {
        for (policy in AccessSettings.PinPolicy.entries) {
            val saved = MjpegSettings.Data(
                access = AccessSettings(true, policy, SecretValue("000007")), web = WebPageSettings(title = "Protected"),
            )
            val settings = settings(saved)
            val host = MjpegServiceHost(settings)
            try {
                startModuleWithListeningServer(host)
                val applied = host.http.configuration.value!!.access
                assertTrue(AccessSettings.isValidPin(applied.pin!!.value))
                assertEquals(settings.data.first().access, applied)
                if (policy == AccessSettings.PinPolicy.Permanent) assertEquals("000007", applied.pin.value)
                host.controller.requestStreamStart()
                val attempt = host.access.requested.await { it.size == 1 }.single()
                host.controller.requestStreamStop(attempt, false)
                host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
                host.managerHandle.status.await { it?.captureAttempt == null }
                host.controller.startModule()
                applySettingsCheckpoint(settings, host, "Same module")
                assertEquals(applied, settings.data.first().access)
                assertEquals(applied, host.http.configuration.value!!.access)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-06
    @Test
    fun enablingProtectionAfterOpenModulePreparationGeneratesMissingPinAndRetainsExistingPin() = runBlocking {
        for (savedPin in listOf(null, SecretValue("000007"))) {
            val initialAccess = AccessSettings(
                pinEnabled = false,
                pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart,
                pin = savedPin,
            )
            val settings = settings(MjpegSettings.Data(access = initialAccess, web = WebPageSettings(title = "Initially open")))
            val host = MjpegServiceHost(settings)
            try {
                startModuleWithListeningServer(host)
                assertEquals(initialAccess, host.http.configuration.value!!.access)
                settings.updateData { copy(access = access.copy(pinEnabled = true)) }
                val applied = host.http.configuration.await { it?.access?.pinEnabled == true }!!.access
                assertTrue(AccessSettings.isValidPin(applied.pin!!.value))
                assertEquals(settings.data.first().access, applied)
                if (savedPin != null) assertEquals(savedPin, applied.pin)
                host.controller.requestStreamStart()
                val attempt = host.access.requested.await { it.size == 1 }.single()
                assertEquals(applied, host.http.configuration.value!!.access)
                host.controller.requestStreamStop(attempt, false)
                host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
                host.managerHandle.status.await { it?.captureAttempt == null }
                host.controller.startModule()
                applySettingsCheckpoint(settings, host, "Protection remains prepared")
                assertEquals(applied, settings.data.first().access)
                assertEquals(applied, host.http.configuration.value!!.access)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-13
    @Test
    fun independentlyHeldCleanupWaitsForCaptureHttpMonitorAndOriginalServiceDespiteCanceledWaiter() = runBlocking {
        for (lastOwner in listOf("capture", "HTTP", "monitor", "Service")) {
            withTimeout(10.seconds) {
                val http = TestHttpDelivery(holdStop = true)
                val monitor = TestAddressMonitor(holdClose = true)
                val captureCleanupEntered = CompletableDeferred<Unit>()
                val releaseCapture = CountDownLatch(1)
                if (lastOwner != "capture") releaseCapture.countDown()
                val access = TestCaptureAccess(onFinish = {
                    captureCleanupEntered.complete(Unit)
                    check(releaseCapture.await(20, TimeUnit.SECONDS)) { "Capture guard release was not permitted" }
                })
                val host = MjpegServiceHost(settings(), http = http, monitor = monitor, access = access)
                try {
                    val attempt = if (lastOwner == "capture") {
                        startModuleWithListeningServer(host)
                        host.controller.requestStreamStart()
                        access.requested.await { it.size == 1 }.single()
                    } else null
                    host.controller.requestModuleShutdown()
                    val abandoned = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
                    val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
                    http.stopEntered.await()
                    monitor.closeEntered.await()
                    if (attempt != null) {
                        captureCleanupEntered.await()
                        // Entry is recorded before the held guard returns; the final receipt proves settlement.
                        assertEquals(listOf(attempt), access.finishCalls.value)
                    }
                    abandoned.cancelAndJoin()
                    if (lastOwner != "HTTP") {
                        http.finishStop.complete(Unit)
                        http.stopSettled.await()
                    }
                    if (lastOwner != "monitor") {
                        monitor.finishClose.complete(Unit)
                        monitor.closeSettled.await()
                        assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
                    }
                    if (lastOwner != "Service" && lastOwner != "capture") {
                        host.awaitStopRequested()
                        host.destroy()
                    }
                    // Await the public receipt in real time while only this owner's obligation remains.
                    assertNull(lastOwner, withTimeoutOrNull(100.milliseconds) { receipt.await() })
                    assertFalse(receipt.isCompleted)
                    when (lastOwner) {
                        "capture" -> {
                            assertFalse(shadowOf(host.service).isStoppedBySelf)
                            releaseCapture.countDown()
                            host.awaitStopRequested()
                            host.destroy()
                        }

                        "HTTP" -> http.finishStop.complete(Unit)
                        "monitor" -> monitor.finishClose.complete(Unit)
                        "Service" -> {
                            host.awaitStopRequested(); host.destroy()
                        }
                    }
                    assertTrue(lastOwner, receipt.await())
                    assertTrue(shadowOf(host.service).isStoppedBySelf)
                } finally {
                    releaseCapture.countDown()
                    assertTrue(host.close())
                }
            }
        }
    }

    // Cases: MJPEG-CTL-13
    @Test
    fun cleanupFailureStillSettlesOtherOwnersAndReturnsFalse() = runBlocking {
        for (failedOwner in listOf("HTTP", "monitor")) {
            val http = TestHttpDelivery().also {
                if (failedOwner == "HTTP") it.stopFailure = IllegalStateException("HTTP cleanup failed")
            }
            val monitor = TestAddressMonitor().also {
                if (failedOwner == "monitor") it.closeFailure = IllegalStateException("Monitor cleanup failed")
            }
            val host = MjpegServiceHost(settings(), http = http, monitor = monitor)
            try {
                host.controller.requestModuleShutdown()
                withTimeout(5.seconds) {
                    http.stopSettled.await()
                    monitor.closeSettled.await()
                }
                host.awaitStopRequested()
                host.destroy()
                assertFalse(failedOwner, withTimeout(5.seconds) { host.controller.awaitCleanup() })
                assertTrue(shadowOf(host.service).isStoppedBySelf)
                assertTrue(http.closed)
                if (failedOwner == "HTTP") assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            } finally {
                assertFalse(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-11
    @Test
    fun terminalMonitorHttpAndConfigurationFailuresCloseModuleAdmission() = runBlocking {
        for (fault in listOf("monitor-failed", "monitor-closed", "http-fatal", "initial-rejected", "update-rejected", "service-destroyed")) {
            val settings = settings()
            val http = TestHttpDelivery().also { if (fault == "initial-rejected") it.acceptConfiguration = false }
            val host = MjpegServiceHost(settings, http = http)
            try {
                host.controller.startModule()
                if (fault != "initial-rejected") host.http.configuration.await { it != null }
                when (fault) {
                    "monitor-failed" -> host.monitor.state.value = NetworkAddressMonitor.State.Failed
                    "monitor-closed" -> host.monitor.state.value = NetworkAddressMonitor.State.Closed
                    "http-fatal" -> host.http.info.value = HttpDelivery.Info(fatalCause = IllegalStateException("Fatal delivery"))
                    "update-rejected" -> {
                        http.acceptConfiguration = false
                        settings.updateData { copy(web = web.copy(title = "Rejected appearance")) }
                    }

                    "service-destroyed" -> host.destroy()
                }
                withTimeout(5.seconds) {
                    host.managerHandle.failed.await()
                    host.http.stopEntered.await()
                    host.monitor.closeEntered.await()
                }
                assertTrue(host.http.closed)
                host.controller.requestStreamStart()
                assertTrue(host.http.captures.value.isEmpty())
            } finally {
                assertEquals(fault != "service-destroyed", host.close())
            }
        }
    }

    // Cases: MJPEG-LAN-01
    @Test
    @Config(manifest = Config.NONE, sdk = [24, 34, 36])
    fun pre37RuntimeAllowsLanWithTarget37AndDeniedPermission() = runBlocking {
        assertPermissionPlan(targetSdk = 37, expectedLanAllowed = true)
    }

    // Cases: MJPEG-LAN-01
    @Test
    @Config(manifest = Config.NONE, sdk = [37])
    fun sdk37AllowsTarget36LanButFiltersDeniedTarget37Lan() = runBlocking {
        assertPermissionPlan(targetSdk = 36, expectedLanAllowed = true)
        assertPermissionPlan(targetSdk = 37, expectedLanAllowed = false)
    }

    // Cases: MJPEG-LAN-03, MJPEG-CTL-08
    @Test
    @Config(manifest = Config.NONE, sdk = [37])
    fun processedHeartbeatRechecksGrantAndRevocationAgainstRetainedSelection() = runBlocking {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val settings = settingsWithBroadFilter()
        val host = MjpegServiceHost(settings, targetSdk = 37)
        try {
            host.controller.startModule()
            host.http.configuration.await { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = address()
            val exempt = address(id = 2, type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.planApplications.await { it.lastOrNull()?.addresses == listOf(exempt) }
            publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            publishServerState(host, lan)
            host.http.plan.await { it?.addresses == listOf(lan, exempt) }
            shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.plan.await { it?.addresses == listOf(exempt) }
            assertEquals(listOf(lan, exempt), (host.monitor.state.value as NetworkAddressMonitor.State.Observed).inventory.select(filter))
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-LAN-04, MJPEG-CTL-02
    @Test
    @Config(manifest = Config.NONE, sdk = [37])
    fun deniedLanListenerRejectsStartAndNonemptyDeniedDiscoveryDoesNotStopExemptAttempt() = runBlocking {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val settings = settingsWithBroadFilter()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings, targetSdk = 37)
        try {
            host.controller.startModule()
            host.http.configuration.await { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = address()
            val exempt = address(id = 2, type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.planApplications.await { it.lastOrNull()?.addresses == listOf(exempt) }
            assertEquals(listOf(exempt), host.http.plan.value!!.addresses)
            publishServerState(host, lan)
            host.controller.requestStreamStart()
            applySettingsCheckpoint(settings, host, "Denied LAN Start processed")
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())

            publishServerState(host, exempt)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            assertEquals(listOf(attempt), host.http.captures.value)
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            publishDiscovery(host, observedAddresses(listOf(lan)))
            assertTrue(host.http.plan.value!!.addresses.isEmpty())
            applySettingsCheckpoint(settings, host, "Denied nonempty discovery processed")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            applySettingsCheckpoint(settings, host, "Denied discovery outlasted loss interval")
            applySettingsCheckpoint(settings, host, "Denied discovery deadline turn completed")
            assertHeldConsent(host, attempt)
            host.controller.requestStreamStop(attempt, false)
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09
    @Test
    fun failedBindDoesNotStartLossAndRecoveryResetsNextEmptySelectionDeadline() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            val filter = host.settings.data.first().network.filter
            val selected = address()
            publishServerState(host, selected, state = HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.AddressInUse, 3))
            applySettingsCheckpoint(settings, host, "Failed bind has nonempty discovery")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            applySettingsCheckpoint(settings, host, "Failed bind outlasted loss interval")
            applySettingsCheckpoint(settings, host, "Failed bind deadline turn completed")
            assertHeldConsent(host, attempt)

            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "First loss began")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            applySettingsCheckpoint(settings, host, "First loss remains pending")
            applySettingsCheckpoint(settings, host, "First loss deadline turn completed")
            assertHeldConsent(host, attempt)
            publishDiscovery(host, observedAddresses(listOf(selected)))
            applySettingsCheckpoint(settings, host, "Recovery processed")
            assertHeldConsent(host, attempt)

            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "Second loss began")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            applySettingsCheckpoint(settings, host, "Second loss has a fresh deadline")
            applySettingsCheckpoint(settings, host, "Second loss deadline turn completed")
            assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            applySettingsCheckpoint(settings, host, "Second loss deadline reached")
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09
    @Test
    fun lengthenedNetworkLossTimeoutRetainsOriginalOnset() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "Loss began before extension")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            settings.updateData {
                copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 20), web = web.copy(title = "Lengthened loss deadline"))
            }
            host.http.configuration.await { it?.web?.title == "Lengthened loss deadline" }
            applySettingsCheckpoint(settings, host, "Lengthened deadline processed")

            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            applySettingsCheckpoint(settings, host, "Original ten-second deadline passed")
            applySettingsCheckpoint(settings, host, "Original deadline turn completed")
            assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            applySettingsCheckpoint(settings, host, "Nineteen seconds since loss")
            applySettingsCheckpoint(settings, host, "Extended deadline remains pending")
            assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            applySettingsCheckpoint(settings, host, "Twenty seconds since original loss")
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun loadingDoesNotStartLossAndEditedDeadlineIncludesDeepSleep() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 20)) }
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            val filter = host.settings.data.first().network.filter
            publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            applySettingsCheckpoint(settings, host, "Loading has no loss fact")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            applySettingsCheckpoint(settings, host, "Loading remains pending")
            applySettingsCheckpoint(settings, host, "Loading deadline turn completed")
            assertHeldConsent(host, attempt)
            publishDiscovery(host, observedAddresses(emptyList()))
            // The plan receipt proves discovery was handled. This later turn establishes its loss onset.
            applySettingsCheckpoint(settings, host, "Empty selection began loss")
            val uptimeBeforeSleep = SystemClock.uptimeMillis()
            val elapsedBeforeSleep = SystemClock.elapsedRealtime()
            ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(10))
            assertEquals(uptimeBeforeSleep, SystemClock.uptimeMillis())
            val expectedElapsedAfterSleepMillis = elapsedBeforeSleep + 10_000
            assertEquals(expectedElapsedAfterSleepMillis, SystemClock.elapsedRealtime())
            settings.updateData {
                copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10), web = web.copy(title = "Shortened loss deadline"))
            }
            host.http.configuration.await { it?.web?.title == "Shortened loss deadline" }
            applySettingsCheckpoint(settings, host, "Elapsed deadline turn completed")
            host.http.captureStatus.await { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-06, MJPEG-CTL-11
    @Test
    fun failedOpenTimePinSaveClosesTheModuleBeforeAnySettingsReachHttp() = runBlocking {
        val base = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val fileAccessDenied = java.util.concurrent.atomic.AtomicBoolean(false)
        val deniedFileAccess = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                if (fileAccessDenied.get()) {
                    deniedFileAccess.complete(Unit)
                    throw SecurityException("Settings files access denied")
                }
                return base.filesDir
            }
        }
        val data = MjpegSettings.Data(
            web = WebPageSettings(title = "Opening PIN failure"),
            access = AccessSettings(pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = SecretValue("123456")),
        )
        seedSettings(context, Json.encodeToString(data))
        val settings = MjpegSettings(context)
        assertFalse(deniedFileAccess.isCompleted)
        fileAccessDenied.set(true)
        val host = MjpegServiceHost(settings)
        try {
            withTimeout(5.seconds) {
                deniedFileAccess.await()
                host.managerHandle.failed.await()
                host.http.stopEntered.await()
                host.monitor.closeEntered.await()
            }
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            assertTrue(host.http.captures.value.isEmpty())
            assertEquals("123456", Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(base)).access.pin!!.value)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11
    @Test
    fun thrownListenerConfigurationFailureReportsTerminalFailureAndClosesAllOwners() = runBlocking {
        val settings = settings()
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.http.configureFailure = IllegalStateException("Listener configuration failed")
            settings.updateData { copy(network = network.copy(filter = otherFilter())) }
            withTimeout(5.seconds) { host.managerHandle.failed.await(); host.http.stopEntered.await(); host.monitor.closeEntered.await() }
            assertTrue(host.http.closed)
            host.controller.requestStreamStart()
            assertTrue(host.http.captures.value.isEmpty())
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09, MJPEG-CTL-10
    @Test
    fun expiredAddressLossRereadsRecoveryOrMissingEvidenceBeforeQueuedDiscoveryIsProcessed() = runBlocking {
        for (fresh in listOf("recovered", "loading", "empty")) {
            val settings = settings()
            settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
            val host = MjpegServiceHost(settings)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val armed = java.util.concurrent.atomic.AtomicBoolean(true)
            try {
                startModuleWithListeningServer(host)
                host.controller.requestStreamStart()
                val attempt = host.access.requested.await { it.size == 1 }.single()
                host.managerHandle.status.await { it?.captureAttempt == attempt }
                publishDiscovery(host, observedAddresses(emptyList()))
                applySettingsCheckpoint(settings, host, "Loss onset established")
                host.http.onConfiguration = {
                    if (armed.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                }
                settings.updateData { copy(web = web.copy(title = "Held before expiry")) }
                withTimeout(5.seconds) { entered.await() }
                ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
                host.monitor.state.value = when (fresh) {
                    "recovered" -> observedAddresses(listOf(address()))
                    "loading" -> NetworkAddressMonitor.State.Loading
                    else -> observedAddresses(emptyList())
                }
                release.countDown()
                applySettingsCheckpoint(settings, host, "Expired deadline evaluated")
                applySettingsCheckpoint(settings, host, "Queued discovery evaluated")
                if (fresh == "empty") {
                    host.managerHandle.status.await { it?.captureAttempt == null }
                    assertEquals(listOf(attempt), host.access.finishCalls.value)
                } else {
                    assertHeldConsent(host, attempt)
                }
                assertFalse(host.http.closed)
            } finally {
                release.countDown(); assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-LAN-04
    @Test
    @Config(sdk = [37])
    fun startRereadsFreshGrantDependentClassificationBeforeItsDiscoveryEventIsHandled() = runBlocking {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val settings = settingsWithBroadFilter()
        val host = MjpegServiceHost(settings, targetSdk = 37)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            host.http.configuration.await { it != null }
            val vpn = address(type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(vpn))
            host.http.plan.await { it?.addresses == listOf(vpn) }
            publishServerState(host, vpn)
            applySettingsCheckpoint(settings, host, "Processed VPN is ready")
            host.http.onConfiguration = {
                if (armed.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            settings.updateData { copy(web = web.copy(title = "Hold before Start")) }
            withTimeout(5.seconds) { entered.await() }
            host.controller.requestStreamStart()
            host.monitor.state.value = observedAddresses(listOf(address(type = InterfaceType.Wifi)))
            release.countDown()
            applySettingsCheckpoint(settings, host, "Start and fresh classification processed")
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())
        } finally {
            release.countDown(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09, MJPEG-CTL-10
    @Test
    fun changingTheFilterRestartsAnEmptySelectionEpisodeInsteadOfReusingItsOnset() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.await { it.size == 1 }.single()
            host.managerHandle.status.await { it?.captureAttempt == attempt }
            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "Initial filter loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            settings.updateData { copy(network = network.copy(filter = otherFilter()), web = web.copy(title = "New empty filter")) }
            host.http.configuration.await { it?.web?.title == "New empty filter" }
            applySettingsCheckpoint(settings, host, "Replacement filter onset established")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            applySettingsCheckpoint(settings, host, "Old filter deadline passed")
            applySettingsCheckpoint(settings, host, "Old deadline turn completed")
            assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
            applySettingsCheckpoint(settings, host, "New filter nine seconds elapsed")
            applySettingsCheckpoint(settings, host, "New deadline remains pending")
            assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            applySettingsCheckpoint(settings, host, "New filter deadline reached")
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            assertEquals(listOf(attempt), host.http.captures.value)
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }


    // Cases: MJPEG-CTL-09, MJPEG-CTL-10
    @Test
    fun replacementAttemptKeepsItsOwnAddressLossDeadlineAfterThePreviousAttemptStopped() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val first = host.access.requested.await { it.size == 1 }.single()
            host.managerHandle.status.await { it?.captureAttempt == first }
            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "First attempt loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            host.controller.requestStreamStop(first, false)
            host.managerHandle.status.await { it?.captureAttempt == null }
            publishDiscovery(host, observedAddresses(listOf(address())))
            publishServerState(host, address())
            applySettingsCheckpoint(settings, host, "Second attempt ready")
            host.controller.requestStreamStart()
            val second = host.access.requested.await { it.size == 2 }.last()
            host.managerHandle.status.await { it?.captureAttempt == second }
            assertNotEquals(first, second)
            publishDiscovery(host, observedAddresses(emptyList()))
            applySettingsCheckpoint(settings, host, "Second attempt loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            applySettingsCheckpoint(settings, host, "First attempt old deadline passed")
            applySettingsCheckpoint(settings, host, "Old deadline turn completed")
            assertEquals(second to MjpegCaptureSession.State.Status.WaitingForPermission, host.http.captureStatus.value)
            assertEquals(second, host.managerHandle.status.value!!.captureAttempt)
            assertEquals(listOf(first), host.access.finishCalls.value)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            applySettingsCheckpoint(settings, host, "Second attempt own deadline reached")
            host.managerHandle.status.await { it?.captureAttempt == null }
            assertEquals(listOf(first, second), host.access.finishCalls.value)
            assertEquals(listOf(first, second), host.http.captures.value)
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-12, MJPEG-CTL-13
    @Test
    fun shutdownDuringEnteredHttpCaptureRegistrationRetiresEveryAdmittedOwnerBeforeItsReceipt() = runBlocking {
        val settings = settings()
        settings.updateData { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
        val enteredRegistration = CompletableDeferred<StreamingModule.CaptureAttemptId>()
        val releaseRegistration = CountDownLatch(1)
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CountDownLatch(1)
        val cleanupReturned = java.util.concurrent.atomic.AtomicBoolean(false)
        val access = TestCaptureAccess(onFinish = {
            enteredCleanup.complete(Unit)
            check(releaseCleanup.await(20, TimeUnit.SECONDS))
            cleanupReturned.set(true)
        })
        val http = TestHttpDelivery().also {
            it.onCaptureRegistered = { attempt ->
                enteredRegistration.complete(attempt)
                check(releaseRegistration.await(20, TimeUnit.SECONDS))
            }
        }
        val host = MjpegServiceHost(settings, http = http, access = access)
        var shutdown: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val admitted = withTimeout(5.seconds) { enteredRegistration.await() }
            assertEquals(listOf(admitted), http.captures.value)
            val application: Application = RuntimeEnvironment.getApplication()
            val screenOff = Intent(Intent.ACTION_SCREEN_OFF)
            assertEquals(1, shadowOf(application).registeredReceivers.filter { it.intentFilter.hasAction(screenOff.action) }.size)
            val shutdownCall = async(kotlinx.coroutines.Dispatchers.Default) {
                host.controller.requestModuleShutdown()
            }
            shutdown = shutdownCall
            withTimeout(5.seconds) {
                while (shadowOf(application).registeredReceivers.filter { it.intentFilter.hasAction(screenOff.action) }.isNotEmpty()) kotlinx.coroutines.yield()
            }
            releaseRegistration.countDown()
            withTimeout(5.seconds) { shutdownCall.await(); http.stopSettled.await(); host.monitor.closeSettled.await() }
            val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
            withTimeout(5.seconds) {
                while (!enteredCleanup.isCompleted && !shadowOf(host.service).isStoppedBySelf) kotlinx.coroutines.yield()
            }
            if (enteredCleanup.isCompleted) {
                assertFalse(shadowOf(host.service).isStoppedBySelf)
                assertFalse(receipt.isCompleted)
                assertFalse(cleanupReturned.get())
            }
            releaseCleanup.countDown()
            host.awaitStopRequested()
            host.destroy()
            assertTrue(withTimeout(5.seconds) { receipt.await() })
            assertEquals(listOf(admitted), http.captures.value)
            assertTrue(access.requested.value.all { it == admitted })
            assertTrue(access.finishCalls.value.all { it == admitted })
            assertTrue(access.requested.value.isEmpty() || access.finishCalls.value.contains(admitted))
            if (enteredCleanup.isCompleted) assertTrue(cleanupReturned.get())
            host.controller.requestStreamStart()
            host.controller.startModule()
            assertEquals(listOf(admitted), http.captures.value)
        } finally {
            releaseRegistration.countDown()
            releaseCleanup.countDown()
            try {
                withTimeout(5.seconds) { shutdown?.await() }
            } finally {
                assertTrue(host.close())
            }
        }
    }

    private suspend fun assertPermissionPlan(targetSdk: Int, expectedLanAllowed: Boolean) {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val host = MjpegServiceHost(settingsWithBroadFilter(), targetSdk = targetSdk)
        try {
            host.controller.startModule()
            host.http.configuration.await { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = address()
            val mobile = address(id = 2, type = InterfaceType.Mobile)
            val vpn = address(id = 3, type = InterfaceType.Vpn)
            val loopback = address(id = 4, ip = "127.0.0.1")
            val expected = if (expectedLanAllowed) listOf(lan, mobile, vpn, loopback) else listOf(mobile, vpn, loopback)
            host.monitor.state.value = observedAddresses(listOf(lan, mobile, vpn, loopback))
            assertEquals(expected, host.http.plan.await { it?.addresses == expected }!!.addresses)
        } finally {
            assertTrue(host.close())
        }
    }

    private suspend fun settings(data: MjpegSettings.Data = MjpegSettings.Data(web = WebPageSettings(title = "Fixture device"))): MjpegSettings {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(data))
        return MjpegSettings(context)
    }

    private suspend fun settingsWithBroadFilter(): MjpegSettings {
        val data = MjpegSettings.Data(web = WebPageSettings(title = "Fixture device"))
        return settings(
            data.copy(
                network = data.network.copy(
                    filter = NetworkAddressFilter(
                        families = setOf(AddressFamily.Ipv4),
                        interfaceTypes = InterfaceType.entries.toSet(),
                        categories = AddressCategory.entries.toSet(),
                    ),
                ),
            ),
        )
    }

    private suspend fun startModuleWithListeningServer(host: MjpegServiceHost) {
        host.controller.startModule()
        host.http.configuration.await { it != null }
        val filter = host.settings.data.first().network.filter
        val address = address()
        host.monitor.state.value = observedAddresses(listOf(address))
        host.http.planApplications.await { it.lastOrNull()?.addresses == listOf(address) }
        publishServerState(host, address)
    }

    private suspend fun publishDiscovery(host: MjpegServiceHost, state: NetworkAddressMonitor.State) {
        if (host.monitor.state.value == state) return
        val applicationsBefore = host.http.planApplications.value.size
        host.monitor.state.value = state
        // Every distinct discovery state applies a plan: Loading keeps the last selection, and Observed is reselected with the current filter.
        // startModuleWithListeningServer() already observed the distinct ready plan; no earlier discovery can trail it.
        host.http.planApplications.await { it.size > applicationsBefore }
    }

    private fun publishServerState(
        host: MjpegServiceHost,
        address: NetworkAddress,
        port: Int = 8080,
        state: HttpDelivery.ServerState = HttpDelivery.ServerState.Listening
    ) {
        host.http.info.value = HttpDelivery.Info(servers = listOf(HttpDelivery.ServerInfo(Uuid.random(), address, port, state)))
    }

    private suspend fun applySettingsCheckpoint(settings: MjpegSettings, host: MjpegServiceHost, title: String) {
        // The public Start/Stop command was enqueued before this distinct settings observation.
        settings.updateData { copy(web = web.copy(title = title)) }
        host.http.configuration.await { it?.web?.title == title }
    }

    private fun assertHeldConsent(host: MjpegServiceHost, attempt: StreamingModule.CaptureAttemptId) {
        assertEquals(attempt to MjpegCaptureSession.State.Status.WaitingForPermission, host.http.captureStatus.value)
        assertTrue(host.access.finishCalls.value.isEmpty())
        assertEquals(attempt, host.managerHandle.status.value!!.captureAttempt)
    }

    private fun address(id: Long = 1, ip: String = "192.168.1.${id + 1}", type: InterfaceType = InterfaceType.Wifi): NetworkAddress =
        NetworkAddress(id, InetAddress.getByName(ip), "fixture$id", type)

    private fun otherFilter(): NetworkAddressFilter =
        NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Vpn), setOf(AddressCategory.NonPrivate))

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5.seconds) { first(predicate) }
}
