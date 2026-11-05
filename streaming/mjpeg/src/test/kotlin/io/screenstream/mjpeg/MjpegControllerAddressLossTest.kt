package io.screenstream.mjpeg

import android.os.SystemClock
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegControllerAddressLossTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val inputs = MjpegControllerTestInputs(temporaryFolder)

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-09
    @Test
    fun failedBindDoesNotStartLossAndRecoveryResetsNextEmptySelectionDeadline() = runBlocking {
        val settings = inputs.settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            val filter = host.settings.data.first().network.filter
            val selected = inputs.address()
            inputs.publishServerState(host, selected, state = HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.AddressUnavailable, 3))
            inputs.applySettingsCheckpoint(settings, host, "Failed bind has nonempty discovery")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            inputs.applySettingsCheckpoint(settings, host, "Failed bind outlasted loss interval")
            inputs.applySettingsCheckpoint(settings, host, "Failed bind deadline turn completed")
            inputs.assertHeldConsent(host, attempt)

            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "First loss began")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            inputs.applySettingsCheckpoint(settings, host, "First loss remains pending")
            inputs.applySettingsCheckpoint(settings, host, "First loss deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            inputs.publishDiscovery(host, observedAddresses(listOf(selected)))
            inputs.applySettingsCheckpoint(settings, host, "Recovery processed")
            inputs.assertHeldConsent(host, attempt)

            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "Second loss began")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            inputs.applySettingsCheckpoint(settings, host, "Second loss has a fresh deadline")
            inputs.applySettingsCheckpoint(settings, host, "Second loss deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            inputs.applySettingsCheckpoint(settings, host, "Second loss deadline reached")
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09
    @Test
    fun lengthenedNetworkLossTimeoutRetainsOriginalOnset() = runBlocking {
        val settings = inputs.settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "Loss began before extension")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            settings.updateData {
                copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 20), web = web.copy(title = "Lengthened loss deadline"))
            }
            host.http.configuration.awaitControllerValue { it?.web?.title == "Lengthened loss deadline" }
            inputs.applySettingsCheckpoint(settings, host, "Lengthened deadline processed")

            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            inputs.applySettingsCheckpoint(settings, host, "Original ten-second deadline passed")
            inputs.applySettingsCheckpoint(settings, host, "Original deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            inputs.applySettingsCheckpoint(settings, host, "Nineteen seconds since loss")
            inputs.applySettingsCheckpoint(settings, host, "Extended deadline remains pending")
            inputs.assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            inputs.applySettingsCheckpoint(settings, host, "Twenty seconds since original loss")
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun loadingDoesNotStartLossAndEditedDeadlineIncludesDeepSleep() = runBlocking {
        val settings = inputs.settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 20)) }
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.WaitingForPermission }
            val filter = host.settings.data.first().network.filter
            inputs.publishDiscovery(host, NetworkAddressMonitor.State.Loading)
            inputs.applySettingsCheckpoint(settings, host, "Loading has no loss fact")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            inputs.applySettingsCheckpoint(settings, host, "Loading remains pending")
            inputs.applySettingsCheckpoint(settings, host, "Loading deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            // The plan receipt proves discovery was handled. This later turn establishes its loss onset.
            inputs.applySettingsCheckpoint(settings, host, "Empty selection began loss")
            val uptimeBeforeSleep = SystemClock.uptimeMillis()
            val elapsedBeforeSleep = SystemClock.elapsedRealtime()
            ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(10))
            assertEquals(uptimeBeforeSleep, SystemClock.uptimeMillis())
            val expectedElapsedAfterSleepMillis = elapsedBeforeSleep + 10_000
            assertEquals(expectedElapsedAfterSleepMillis, SystemClock.elapsedRealtime())
            settings.updateData {
                copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10), web = web.copy(title = "Shortened loss deadline"))
            }
            host.http.configuration.awaitControllerValue { it?.web?.title == "Shortened loss deadline" }
            inputs.applySettingsCheckpoint(settings, host, "Elapsed deadline turn completed")
            host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-09, MJPEG-CTL-10
    @Test
    fun expiredAddressLossRereadsRecoveryOrMissingEvidenceBeforeQueuedDiscoveryIsProcessed() = runBlocking {
        for (fresh in listOf("recovered", "loading", "empty")) {
            val settings = inputs.settings()
            settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
            val host = MjpegServiceHost(settings)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val armed = java.util.concurrent.atomic.AtomicBoolean(true)
            try {
                inputs.startModuleWithListeningServer(host)
                host.controller.requestStreamStart()
                val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
                host.managerObservation.status.awaitControllerValue { it?.captureAttempt == attempt }
                inputs.publishDiscovery(host, observedAddresses(emptyList()))
                inputs.applySettingsCheckpoint(settings, host, "Loss onset established")
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
                    "recovered" -> observedAddresses(listOf(inputs.address()))
                    "loading" -> NetworkAddressMonitor.State.Loading
                    else -> observedAddresses(emptyList())
                }
                release.countDown()
                inputs.applySettingsCheckpoint(settings, host, "Expired deadline evaluated")
                inputs.applySettingsCheckpoint(settings, host, "Queued discovery evaluated")
                if (fresh == "empty") {
                    host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
                    assertEquals(listOf(attempt), host.access.finishCalls.value)
                } else {
                    inputs.assertHeldConsent(host, attempt)
                }
                assertFalse(host.http.closed)
            } finally {
                release.countDown(); assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-09, MJPEG-CTL-10
    @Test
    fun changingTheFilterRestartsAnEmptySelectionEpisodeInsteadOfReusingItsOnset() = runBlocking {
        val settings = inputs.settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val attempt = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == attempt }
            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "Initial filter loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            settings.updateData { copy(network = network.copy(filter = inputs.otherFilter()), web = web.copy(title = "New empty filter")) }
            host.http.configuration.awaitControllerValue { it?.web?.title == "New empty filter" }
            inputs.applySettingsCheckpoint(settings, host, "Replacement filter onset established")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            inputs.applySettingsCheckpoint(settings, host, "Old filter deadline passed")
            inputs.applySettingsCheckpoint(settings, host, "Old deadline turn completed")
            inputs.assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
            inputs.applySettingsCheckpoint(settings, host, "New filter nine seconds elapsed")
            inputs.applySettingsCheckpoint(settings, host, "New deadline remains pending")
            inputs.assertHeldConsent(host, attempt)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            inputs.applySettingsCheckpoint(settings, host, "New filter deadline reached")
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
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
        val settings = inputs.settings()
        settings.updateData { copy(behavior = behavior.copy(stopAfterNetworkLossSeconds = 10)) }
        val host = MjpegServiceHost(settings)
        try {
            inputs.startModuleWithListeningServer(host)
            host.controller.requestStreamStart()
            val first = host.access.requested.awaitControllerValue { it.size == 1 }.single()
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == first }
            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "First attempt loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            host.controller.requestStreamStop(first, false)
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            inputs.publishDiscovery(host, observedAddresses(listOf(inputs.address())))
            inputs.publishServerState(host, inputs.address())
            inputs.applySettingsCheckpoint(settings, host, "Second attempt ready")
            host.controller.requestStreamStart()
            val second = host.access.requested.awaitControllerValue { it.size == 2 }.last()
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == second }
            assertNotEquals(first, second)
            inputs.publishDiscovery(host, observedAddresses(emptyList()))
            inputs.applySettingsCheckpoint(settings, host, "Second attempt loss onset")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            inputs.applySettingsCheckpoint(settings, host, "First attempt old deadline passed")
            inputs.applySettingsCheckpoint(settings, host, "Old deadline turn completed")
            assertEquals(second to MjpegCaptureSession.State.Status.WaitingForPermission, host.http.captureStatus.value)
            assertEquals(second, host.managerObservation.status.value!!.captureAttempt)
            assertEquals(listOf(first), host.access.finishCalls.value)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
            inputs.applySettingsCheckpoint(settings, host, "Second attempt own deadline reached")
            host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
            assertEquals(listOf(first, second), host.access.finishCalls.value)
            assertEquals(listOf(first, second), host.http.captures.value)
            assertFalse(host.http.closed)
        } finally {
            assertTrue(host.close())
        }
    }
}
