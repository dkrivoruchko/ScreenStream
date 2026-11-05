package io.screenstream.mjpeg

import android.Manifest
import android.app.Application
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegControllerNetworkTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val inputs = MjpegControllerTestInputs(temporaryFolder)

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-08
    @Test
    fun loadingRetainsSelectionButObservedInventoryIsReselectedByTheCurrentFilter() = runBlocking {
        val settings = inputs.settings()
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            val first = host.http.plan.value!!
            inputs.publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            assertEquals(first, host.http.plan.value)
            inputs.publishDiscovery(
                host,
                observedAddresses(listOf(inputs.address(id = 99, ip = "203.0.113.9", type = InterfaceType.Vpn))),
            )
            assertTrue(host.http.plan.value!!.addresses.isEmpty())
            val replacement = inputs.address(id = 2)
            inputs.publishDiscovery(host, observedAddresses(listOf(replacement)))
            assertEquals(listOf(replacement), host.http.plan.value!!.addresses)
        } finally {
            assertTrue(host.close())
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
        val settings = inputs.settingsWithBroadFilter()
        val host = MjpegServiceHost(settings, targetSdk = 37)
        try {
            host.controller.startModule()
            host.http.configuration.awaitControllerValue { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = inputs.address()
            val exempt = inputs.address(id = 2, type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.planApplications.awaitControllerValue { it.lastOrNull()?.addresses == listOf(exempt) }
            inputs.publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            inputs.publishServerState(host, lan)
            host.http.plan.awaitControllerValue { it?.addresses == listOf(lan, exempt) }
            shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.plan.awaitControllerValue { it?.addresses == listOf(exempt) }
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
        val settings = inputs.settingsWithBroadFilter()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings, targetSdk = 37)
        try {
            host.controller.startModule()
            host.http.configuration.awaitControllerValue { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = inputs.address()
            val exempt = inputs.address(id = 2, type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(lan, exempt))
            host.http.planApplications.awaitControllerValue { it.lastOrNull()?.addresses == listOf(exempt) }
            assertEquals(listOf(exempt), host.http.plan.value!!.addresses)
            inputs.publishServerState(host, lan)
            host.controller.requestStreamStart()
            inputs.applySettingsCheckpoint(settings, host, "Denied LAN Start processed")
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())

            inputs.publishServerState(host, exempt)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            assertEquals(listOf(attempt), host.http.captures.value)
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            inputs.publishDiscovery(host, observedAddresses(listOf(lan)))
            assertTrue(host.http.plan.value!!.addresses.isEmpty())
            inputs.applySettingsCheckpoint(settings, host, "Denied nonempty discovery processed")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            inputs.applySettingsCheckpoint(settings, host, "Denied discovery outlasted loss interval")
            inputs.applySettingsCheckpoint(settings, host, "Denied discovery deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            host.controller.requestStreamStop(attempt, false)
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-LAN-04
    @Test
    @Config(sdk = [37])
    fun startRereadsFreshGrantDependentClassificationBeforeItsDiscoveryEventIsHandled() = runBlocking {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val settings = inputs.settingsWithBroadFilter()
        val host = MjpegServiceHost(settings, targetSdk = 37)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            host.http.configuration.awaitControllerValue { it != null }
            val vpn = inputs.address(type = InterfaceType.Vpn)
            host.monitor.state.value = observedAddresses(listOf(vpn))
            host.http.plan.awaitControllerValue { it?.addresses == listOf(vpn) }
            inputs.publishServerState(host, vpn)
            inputs.applySettingsCheckpoint(settings, host, "Processed VPN is ready")
            host.http.onConfiguration = {
                if (armed.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            settings.updateData { copy(web = web.copy(title = "Hold before Start")) }
            withTimeout(5.seconds) { entered.await() }
            host.controller.requestStreamStart()
            host.monitor.state.value = observedAddresses(listOf(inputs.address(type = InterfaceType.Wifi)))
            release.countDown()
            inputs.applySettingsCheckpoint(settings, host, "Start and fresh classification processed")
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())
        } finally {
            release.countDown(); assertTrue(host.close())
        }
    }

    private suspend fun assertPermissionPlan(targetSdk: Int, expectedLanAllowed: Boolean) {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val host = MjpegServiceHost(inputs.settingsWithBroadFilter(), targetSdk = targetSdk)
        try {
            host.controller.startModule()
            host.http.configuration.awaitControllerValue { it != null }
            val filter = host.settings.data.first().network.filter
            val lan = inputs.address()
            val mobile = inputs.address(id = 2, type = InterfaceType.Mobile)
            val vpn = inputs.address(id = 3, type = InterfaceType.Vpn)
            val loopback = inputs.address(id = 4, ip = "127.0.0.1")
            val expected = if (expectedLanAllowed) listOf(lan, mobile, vpn, loopback) else listOf(mobile, vpn, loopback)
            host.monitor.state.value = observedAddresses(listOf(lan, mobile, vpn, loopback))
            assertEquals(expected, host.http.plan.awaitControllerValue { it?.addresses == expected }!!.addresses)
        } finally {
            assertTrue(host.close())
        }
    }
}
