package io.screenstream.mjpeg

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import com.elvishew.xlog.XLog
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegControllerLifecycleTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val inputs = MjpegControllerTestInputs(temporaryFolder)

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-01
    @Test
    fun serviceInstallsOriginalControllerAndRepeatedCurrentStartHasOneLifetime() = runBlocking {
        val settings = inputs.createSettings()
        val host = MjpegServiceTestHost(settings)
        try {
            assertTrue(host.access.consentRequests.value.isEmpty())
            host.controller.requestStreamStart()
            host.controller.startModule()
            host.controller.startModule()
            val configuration = host.http.configuration.awaitControllerValue { it != null }
            assertEquals("Fixture device", configuration!!.web.title)
            host.monitor.started.awaitControllerValue { it }
            host.managerObservation.status.awaitControllerValue { it != null }
            inputs.applySettingsCheckpoint(settings, host, "Repeated start remains healthy")
            assertEquals(1, host.http.startCalls.value)
            assertTrue(host.managerObservation.isCurrent())
            assertFalse(host.managerObservation.failed.isCompleted)
            assertFalse(host.http.closed)
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            val packagedLogo = host.service.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            assertTrue(packagedLogo.isNotEmpty())
            assertArrayEquals(packagedLogo, host.http.initialLogoBytes)
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
                val handle = RecordingManagerHandle().also { it.current = !stale }
                val serviceController = org.robolectric.Robolectric.buildService(MjpegStreamingModuleService::class.java).create()
                val http = ControlledHttpDelivery()
                val monitor = ControlledAddressMonitor()
                val controller = MjpegStreamingModuleController(
                    handle,
                    serviceController.get(),
                    lazyOf(inputs.createSettings()),
                    monitor,
                    ControlledCaptureAccess(),
                    http,
                    DiscardingSessionAnalyticsFactory
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
                    assertTrue(http.registeredCaptureAttempts.value.isEmpty())
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
        val handle = RecordingManagerHandle()
        val http = ControlledHttpDelivery()
        val monitor = ControlledAddressMonitor()
        val controller =
            MjpegStreamingModuleController(
                handle,
                service.get(),
                lazyOf(inputs.createSettings()),
                monitor,
                ControlledCaptureAccess(),
                http,
                DiscardingSessionAnalyticsFactory
            )
        var destroyed = false
        try {
            assertTrue(handle.reports.isEmpty())
            assertTrue(controller.state.value.statistics.history.isEmpty())
            controller.startModule()
            http.configuration.awaitControllerValue { it != null }
            withTimeout(5.seconds) { while (handle.reports.isEmpty()) kotlinx.coroutines.yield() }
            val before = handle.reports.size
            val previousUptime = handle.reports.last().second
            ShadowSystemClock.advanceBy(Duration.ofMillis(1500))
            http.info.value =
                HttpDelivery.Info(servers = listOf(HttpDelivery.ServerInfo(Uuid.random(), inputs.address(), 8080, HttpDelivery.ServerState.Listening)))
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
            assertTrue(http.registeredCaptureAttempts.value.isEmpty())
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
            val http = ControlledHttpDelivery(onStart = {
                startEntered.complete(Unit)
                check(releaseStart.await(20, TimeUnit.SECONDS)) { "HTTP start release was not permitted" }
            })
            val host = MjpegServiceTestHost(inputs.createSettings(), http = http)
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
                assertTrue(http.registeredCaptureAttempts.value.isEmpty())
                assertTrue(host.access.consentRequests.value.isEmpty())
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
        for (gate in listOf("loading", "empty", "listener-address-absent", "pending", "failed", "wrong-port", "wrong-address")) {
            val settings = inputs.createSettings()
            val host = MjpegServiceTestHost(settings)
            try {
                inputs.startModuleWithReadyListener(host)
                val filter = host.settings.data.first().network.filter
                val selected = inputs.address()
                when (gate) {
                    "loading" -> inputs.publishDiscovery(host, NetworkAddressMonitor.State.Loading)
                    "empty" -> inputs.publishDiscovery(host, observedAddressState(emptyList()))
                    "listener-address-absent" -> inputs.publishDiscovery(
                        host,
                        observedAddressState(listOf(inputs.address(id = 99, ip = "203.0.113.9", type = InterfaceType.Vpn))),
                    )

                    "pending" -> inputs.publishServerState(host, selected, state = HttpDelivery.ServerState.Pending)
                    "failed" -> inputs.publishServerState(
                        host,
                        selected,
                        state = HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.AddressUnavailable, 3)
                    )

                    "wrong-port" -> inputs.publishServerState(host, selected, port = 9000)
                    "wrong-address" -> inputs.publishServerState(host, inputs.address(id = 99))
                }
                host.controller.requestStreamStart()
                inputs.applySettingsCheckpoint(settings, host, "Rejected $gate")
                assertTrue(gate, host.http.registeredCaptureAttempts.value.isEmpty())
                assertTrue(gate, host.access.consentRequests.value.isEmpty())
                inputs.publishDiscovery(host, observedAddressState(listOf(selected)))
                inputs.publishServerState(host, selected)
                inputs.applySettingsCheckpoint(settings, host, "Ready $gate")
                host.controller.requestStreamStart()
                host.access.consentRequests.awaitControllerValue { it.size == 1 }
                assertEquals(host.http.registeredCaptureAttempts.value.single(), host.access.consentRequests.value.single())
                assertFalse(host.managerObservation.status.awaitControllerValue { it?.captureAttempt != null }!!.isStreaming)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-CTL-03, MJPEG-CTL-04
    @Test
    fun duplicateStartAndStaleStopCannotReplaceHeldConsentButExactStopAllowsNextAttempt() = runBlocking<Unit> {
        val settings = inputs.createSettings()
        val host = MjpegServiceTestHost(settings)
        try {
            inputs.startModuleWithReadyListener(host)
            host.controller.requestStreamStart()
            val first = host.access.consentRequests.awaitControllerValue { it.size == 1 }.single()
            host.controller.requestStreamStart()
            host.controller.requestStreamStop(StreamingModule.CaptureAttemptId(host.managerObservation.instanceId), false)
            inputs.applySettingsCheckpoint(settings, host, "Stale Stop processed")
            assertEquals(listOf(first), host.http.registeredCaptureAttempts.value)
            assertTrue(host.access.projectionCreationFinishes.value.isEmpty())
            assertEquals(first, host.managerObservation.status.awaitControllerValue { it?.captureAttempt == first }!!.captureAttempt)
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
            val consumed = host.managerObservation.status.awaitControllerValue { it?.hasConsumer == true }!!
            assertEquals(first, consumed.captureAttempt)
            assertFalse(consumed.isStreaming)
            host.controller.requestStreamStop(first, false)
            host.http.captureStatus.awaitControllerValue { it?.first == first && it.second == MjpegCaptureSession.State.Status.Stopped }
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            assertFalse(host.http.closed)
            host.controller.requestStreamStart()
            val second = host.access.consentRequests.awaitControllerValue { it.size == 2 }.last()
            assertNotEquals(first, second)
            host.controller.requestStreamStop(first, false)
            inputs.applySettingsCheckpoint(settings, host, "Old Stop after replacement")
            assertEquals(second, host.managerObservation.status.awaitControllerValue { it?.captureAttempt == second }!!.captureAttempt)
            assertEquals(listOf(first), host.access.projectionCreationFinishes.value)
            host.controller.requestStreamStop(second, false)
            host.http.captureStatus.awaitControllerValue { it?.first == second && it.second == MjpegCaptureSession.State.Status.Stopped }
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05
    @Test
    fun failedAttemptCleanupShutsDownTheModuleAndBlocksReplacement() = runBlocking {
        val settings = inputs.createSettings()
        val access = ControlledCaptureAccess().also { it.finishProjectionFailure = IllegalStateException("Guard cleanup failed") }
        val host = MjpegServiceTestHost(settings, access = access)
        try {
            inputs.startModuleWithReadyListener(host)
            host.controller.requestStreamStart()
            val attempt = access.consentRequests.awaitControllerValue { it.size == 1 }.single()
            host.controller.requestStreamStop(attempt, false)
            host.http.captureStatus.awaitControllerValue { it?.second == MjpegCaptureSession.State.Status.Failed }
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopEntered.await() }
            settings.updateData { copy(network = network.copy(httpPort = 9000), web = web.copy(title = "After failed cleanup")) }
            host.controller.requestStreamStart()
            assertEquals(9000, settings.data.first().network.httpPort)
            assertTrue(host.http.closed)
            assertEquals(listOf(attempt), host.http.registeredCaptureAttempts.value)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            assertEquals(8080, host.http.plan.value!!.port)
            assertFalse(host.managerObservation.status.value!!.isStreaming)
        } finally {
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CTL-13
    @Test
    fun independentlyHeldCleanupWaitsForCaptureHttpMonitorAndOriginalServiceDespiteCanceledWaiter() = runBlocking {
        for (lastOwner in listOf("capture", "HTTP", "monitor", "Service")) {
            withTimeout(10.seconds) {
                val http = ControlledHttpDelivery(holdStop = true)
                val monitor = ControlledAddressMonitor(holdClose = true)
                val captureCleanupEntered = CompletableDeferred<Unit>()
                val releaseCapture = CountDownLatch(1)
                if (lastOwner != "capture") releaseCapture.countDown()
                val access = ControlledCaptureAccess(onFinish = {
                    captureCleanupEntered.complete(Unit)
                    check(releaseCapture.await(20, TimeUnit.SECONDS)) { "Capture guard release was not permitted" }
                })
                val host = MjpegServiceTestHost(inputs.createSettings(), http = http, monitor = monitor, access = access)
                try {
                    val attempt = if (lastOwner == "capture") {
                        inputs.startModuleWithReadyListener(host)
                        host.controller.requestStreamStart()
                        access.consentRequests.awaitControllerValue { it.size == 1 }.single()
                    } else null
                    host.controller.requestModuleShutdown()
                    val abandoned = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
                    val receipt = async(start = CoroutineStart.UNDISPATCHED) { host.controller.awaitCleanup() }
                    http.stopEntered.await()
                    monitor.closeEntered.await()
                    if (attempt != null) {
                        captureCleanupEntered.await()
                        // Entry is recorded before the held guard returns; the final receipt proves settlement.
                        assertEquals(listOf(attempt), access.projectionCreationFinishes.value)
                    }
                    abandoned.cancelAndJoin()
                    if (lastOwner != "HTTP") {
                        http.allowStopToReturn.complete(Unit)
                        http.stopSettled.await()
                    }
                    if (lastOwner != "monitor") {
                        monitor.allowCloseToReturn.complete(Unit)
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

                        "HTTP" -> http.allowStopToReturn.complete(Unit)
                        "monitor" -> monitor.allowCloseToReturn.complete(Unit)
                        "Service" -> {
                            host.awaitStopRequested()
                            host.destroy()
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
            val http = ControlledHttpDelivery().also {
                if (failedOwner == "HTTP") it.stopFailure = IllegalStateException("HTTP cleanup failed")
            }
            val monitor = ControlledAddressMonitor().also {
                if (failedOwner == "monitor") it.closeFailure = IllegalStateException("Monitor cleanup failed")
            }
            val host = MjpegServiceTestHost(inputs.createSettings(), http = http, monitor = monitor)
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
            val settings = inputs.createSettings()
            val http = ControlledHttpDelivery().also { if (fault == "initial-rejected") it.acceptConfiguration = false }
            val host = MjpegServiceTestHost(settings, http = http)
            try {
                host.controller.startModule()
                if (fault != "initial-rejected") host.http.configuration.awaitControllerValue { it != null }
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
                    host.managerObservation.failed.await()
                    host.http.stopEntered.await()
                    host.monitor.closeEntered.await()
                }
                assertTrue(host.http.closed)
                host.controller.requestStreamStart()
                assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            } finally {
                assertEquals(fault != "service-destroyed", host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-11
    @Test
    fun thrownListenerConfigurationFailureReportsTerminalFailureAndClosesAllOwners() = runBlocking {
        val settings = inputs.createSettings()
        val host = MjpegServiceTestHost(settings)
        try {
            inputs.startModuleWithReadyListener(host)
            host.http.serverConfigurationFailure = IllegalStateException("Listener configuration failed")
            settings.updateData { copy(network = network.copy(filter = inputs.vpnNonPrivateIpv4Filter())) }
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopEntered.await(); host.monitor.closeEntered.await() }
            assertTrue(host.http.closed)
            host.controller.requestStreamStart()
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-12, MJPEG-CTL-13
    @Test
    fun shutdownDuringEnteredHttpCaptureRegistrationRetiresEveryAdmittedOwnerBeforeItsReceipt() = runBlocking {
        val settings = inputs.createSettings()
        settings.updateData { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
        val enteredRegistration = CompletableDeferred<StreamingModule.CaptureAttemptId>()
        val releaseRegistration = CountDownLatch(1)
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CountDownLatch(1)
        val cleanupReturned = java.util.concurrent.atomic.AtomicBoolean(false)
        val access = ControlledCaptureAccess(onFinish = {
            enteredCleanup.complete(Unit)
            check(releaseCleanup.await(20, TimeUnit.SECONDS))
            cleanupReturned.set(true)
        })
        val http = ControlledHttpDelivery().also {
            it.onCaptureRegistered = { attempt ->
                enteredRegistration.complete(attempt)
                check(releaseRegistration.await(20, TimeUnit.SECONDS))
            }
        }
        val host = MjpegServiceTestHost(settings, http = http, access = access)
        var shutdown: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            inputs.startModuleWithReadyListener(host)
            host.controller.requestStreamStart()
            val admitted = withTimeout(5.seconds) { enteredRegistration.await() }
            assertEquals(listOf(admitted), http.registeredCaptureAttempts.value)
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
            assertEquals(listOf(admitted), http.registeredCaptureAttempts.value)
            assertTrue(access.consentRequests.value.all { it == admitted })
            assertTrue(access.projectionCreationFinishes.value.all { it == admitted })
            assertTrue(access.consentRequests.value.isEmpty() || access.projectionCreationFinishes.value.contains(admitted))
            if (enteredCleanup.isCompleted) assertTrue(cleanupReturned.get())
            host.controller.requestStreamStart()
            host.controller.startModule()
            assertEquals(listOf(admitted), http.registeredCaptureAttempts.value)
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
}
