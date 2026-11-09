package io.screenstream.mjpeg

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.net.InetAddress
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class MjpegControllerCaptureContractsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val inputs = MjpegControllerTestInputs(temporaryFolder)

    @Before
    fun initialize() {
        ShadowToast.reset()
        ShadowPowerManager.clearWakeLocks()
    }

    // Cases: MJPEG-CTL-03, MJPEG-CTL-04, MJPEG-CAP-13
    @Test
    fun confirmedCaptureConsumerPresenceSuspensionAndPendingCleanupAreIndependent() = runBlocking<Unit> {
        val engine = ControlledMjpegEngineSession(holdStart = true, holdStop = true)
        val host = createHost(listOf(engine))
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            inputs.publishDeliveryCheckpoint(host, address)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            engine.allowStartToReturn.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            inputs.publishDeliveryCheckpoint(host, address)
            assertEquals(UiController.Action.Stop(attempt), host.controller.state.value.action)
            assertIs<io.screenstream.core.module.StreamingModuleNotifications.ForegroundAction.StopStream>(host.notifications.actions.last())
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.InvalidRequest))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem == MjpegCaptureSession.State.SuspensionProblem.InvalidRequest } }
            assertTrue(host.managerObservation.status.value!!.isStreaming)
            val info = host.http.info.value
            host.http.info.value = info.copy(clients = listOf(client(info.servers.single().id)))
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.hasConsumer == true } }
            host.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { engine.stopEntered.await() }
            inputs.publishDeliveryCheckpoint(host, address)
            withTimeout(5.seconds) { host.managerObservation.status.first { it != null && !it.isStreaming && it.captureAttempt == attempt } }
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            engine.allowStopToReturn.complete(Unit)
            awaitCaptureIdle(host)
            assertFalse(host.http.closed)
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
            assertIs<UiController.Action.Start>(host.controller.state.value.action)
            assertIs<io.screenstream.core.module.StreamingModuleNotifications.ForegroundAction.Exit>(host.notifications.actions.last())
        } finally {
            engine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-03, MJPEG-CTL-04
    @Test
    fun unconfirmedSuspensionKeepsTheCaptureActionBusy() = runBlocking {
        val engine = ControlledMjpegEngineSession(holdStart = true)
        val host = createHost(listOf(engine))
        try {
            inputs.awaitReadyListener(host, address)
            startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Active))
            engine.allowStartToReturn.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertTrue(host.managerObservation.status.value!!.isStreaming)
            assertEquals(UiController.Action.Stop(host.access.consentRequests.value.single()), host.controller.state.value.action)
        } finally {
            engine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05, MJPEG-CAP-06
    @Test
    fun liveParametersApplyWhileBackendPortAndAccessWaitThroughStoppingForSuccessfulCleanup() = runBlocking {
        val firstEngine = ControlledMjpegEngineSession(holdStop = true)
        val secondEngine = ControlledMjpegEngineSession()
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(defaults()))
        val configurations = java.util.concurrent.CopyOnWriteArrayList<io.screenstream.capture.ScreenCaptureConfig>()
        val engines = java.util.concurrent.CopyOnWriteArrayList(listOf(firstEngine, secondEngine))
        val host = MjpegServiceTestHost(MjpegSettings(context), engineFactory = { _, _, config -> configurations += config; engines.removeAt(0) })
        host.access.consentGrant.complete(object : ScreenCaptureAccess.Grant {})
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            applySettingsAndAwaitController(host) {
                copy(
                    image = image.copy(jpegQuality = 45, jpegBackendPolicy = io.screenstream.capture.JpegBackendPolicy.FrameworkOnly),
                    network = network.copy(httpPort = 9000),
                    access = access.copy(pinEnabled = true, pin = io.screenstream.mjpeg.settings.SecretValue("123456")),
                )
            }
            withTimeout(5.seconds) { while (firstEngine.parameterUpdates.none { it.jpegQuality == 45 }) yield() }
            assertEquals(io.screenstream.capture.JpegBackendPolicy.Auto, configurations.single().jpegBackendPolicy)
            assertEquals(8080, host.http.plan.value!!.port)
            assertFalse(host.http.configuration.value!!.access.pinEnabled)
            assertEquals(MjpegSettings.EditPolicy.LiveOnly, host.controller.state.value.editPolicy)
            host.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { firstEngine.stopEntered.await() }
            applySettingsAndAwaitController(host) {
                copy(
                    image = image.copy(jpegQuality = 71),
                    network = network.copy(httpPort = 9100),
                    access = access.copy(pin = io.screenstream.mjpeg.settings.SecretValue("654321"))
                )
            }
            assertTrue(firstEngine.parameterUpdates.none { it.jpegQuality == 71 })
            assertEquals(8080, host.http.plan.value!!.port)
            firstEngine.allowStopToReturn.complete(Unit)
            awaitCaptureIdle(host)
            withTimeout(5.seconds) { host.http.plan.first { it?.port == 9100 } }
            assertEquals("654321", host.http.configuration.value!!.access.pin!!.value)
            assertEquals(MjpegSettings.EditPolicy.All, host.controller.state.value.editPolicy)
            val serverId = Uuid.random()
            host.http.info.value =
                host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(serverId, address, 9100, HttpDelivery.ServerState.Listening)))
            withTimeout(5.seconds) { host.controller.state.first { it.action == UiController.Action.Start(true) } }
            startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { secondEngine.startEntered.await() }
            assertEquals(71, secondEngine.initialParameters!!.jpegQuality)
            assertEquals(io.screenstream.capture.JpegBackendPolicy.FrameworkOnly, configurations.last().jpegBackendPolicy)
        } finally {
            firstEngine.releaseAllGates()
            secondEngine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11
    @Test
    fun captureFailureCanRecoverOnlyAfterSuccessfulCleanupAndLaterConfirmationClearsItsError(): Unit = runBlocking {
        val cause = IllegalStateException("Controlled Engine startup failure")
        val firstEngine = ControlledMjpegEngineSession().also { it.startFailure = cause }
        val secondEngine = ControlledMjpegEngineSession(holdStart = true)
        val host = createHost(listOf(firstEngine, secondEngine))
        try {
            inputs.awaitReadyListener(host, address)
            startCaptureAndAwaitAttempt(host, awaitPending = false)
            withTimeout(5.seconds) { firstEngine.stopEntered.await() }
            awaitCaptureIdle(host)
            assertNotNull(host.managerObservation.status.value!!.error)
            assertNull(host.managerObservation.status.value!!.error!!.message)
            assertTrue(host.managerObservation.isCurrent())
            assertFalse(host.managerObservation.failed.isCompleted)
            startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { secondEngine.startEntered.await() }
            inputs.publishDeliveryCheckpoint(host, address)
            assertNotNull(host.managerObservation.status.value!!.error)
            secondEngine.allowStartToReturn.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true && it.error == null } }
            assertFalse(host.http.closed)
        } finally {
            firstEngine.releaseAllGates()
            secondEngine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun failedCaptureCleanupIsTerminalAndNeverAdmitsAReplacement() = runBlocking {
        val engine = ControlledMjpegEngineSession().also { it.stopFailure = IllegalStateException("Media stop failed") }
        val host = createHost(listOf(engine))
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            host.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopSettled.await(); host.monitor.closeSettled.await() }
            host.controller.requestStreamStart()
            assertEquals(listOf(attempt), host.http.registeredCaptureAttempts.value)
            assertTrue(host.http.closed)
            host.awaitStopRequested()
            host.destroy()
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
        } finally {
            engine.releaseAllGates()
            assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun delayedStopReportsItsMessageUntilCleanupAndRetainedCaptureFailureTakesPrecedence(): Unit = runBlocking {
        for (retainFailure in listOf(false, true)) {
            val engine = ControlledMjpegEngineSession(holdStop = true)
            val host = createHost(listOf(engine))
            try {
                inputs.awaitReadyListener(host, address)
                val attempt = startCaptureAndAwaitAttempt(host)
                withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
                if (retainFailure) engine.publish(MjpegEngineState(MjpegEngineState.Phase.Failed, ScreenCaptureProblem.InternalFailure))
                else host.controller.requestStreamStop(attempt, fromNotification = false)
                withTimeout(5.seconds) { engine.stopEntered.await() }
                ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
                inputs.publishDeliveryCheckpoint(host, address)
                val error = withTimeout(5.seconds) { host.managerObservation.status.first { it?.error != null } }!!.error
                assertNotNull(error)
                if (retainFailure) assertNull(error.message) else assertEquals(host.service.getString(R.string.mjpeg_stream_stop_delayed), error.message)
                engine.releaseAllGates()
                awaitCaptureIdle(host)
                if (retainFailure) assertNotNull(host.managerObservation.status.value!!.error) else assertNull(host.managerObservation.status.value!!.error)
            } finally {
                engine.releaseAllGates()
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-14, MJPEG-CTL-03
    @Test
    fun screenOffRegistrationRejectsAnAlreadySleepingDisplayAndDefensivelyFencesAnOldCallbackFromItsSuccessor() = runBlocking {
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val firstEngine = ControlledMjpegEngineSession()
        val secondEngine = ControlledMjpegEngineSession()
        val host = createHost(listOf(firstEngine, secondEngine), data, grantConsent = false)
        val powerManagerShadow = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            inputs.awaitReadyListener(host, address)
            powerManagerShadow.turnScreenOn(false)
            host.controller.requestStreamStart()
            inputs.publishDeliveryCheckpoint(host, address)
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            assertTrue(host.access.consentRequests.value.isEmpty())
            powerManagerShadow.turnScreenOn(true)
            val first = startCaptureAndAwaitAttempt(host)
            val oldReceiver = currentScreenOffReceiver()
            host.controller.requestStreamStop(first, fromNotification = false)
            awaitCaptureIdle(host)
            assertTrue(screenOffReceivers().isEmpty())
            val second = startCaptureAndAwaitAttempt(host)
            val currentReceiver = currentScreenOffReceiver()
            assertNotSame(oldReceiver, currentReceiver)
            oldReceiver.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF))
            inputs.publishDeliveryCheckpoint(host, address)
            assertEquals(second, host.managerObservation.status.value!!.captureAttempt)
            powerManagerShadow.turnScreenOn(false)
            shadowOf(Looper.getMainLooper()).idle()
            awaitCaptureIdle(host)
            assertFalse(host.http.closed)
            assertTrue(screenOffReceivers().isEmpty())
        } finally {
            firstEngine.releaseAllGates()
            secondEngine.releaseAllGates()
            try {
                assertTrue(host.close())
            } finally {
                powerManagerShadow.turnScreenOn(true)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    // Cases: MJPEG-CTL-14
    @Test
    fun enablingScreenOffDuringConsentWithTheDisplayAlreadyOffStopsThatAttempt() = runBlocking {
        val engine = ControlledMjpegEngineSession()
        val host = createHost(listOf(engine), grantConsent = false)
        val power = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            power.turnScreenOn(false)
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
            awaitCaptureIdle(host)
            assertEquals(listOf(attempt), host.access.projectionCreationFinishes.value)
            assertFalse(engine.startEntered.isCompleted)
            assertFalse(host.http.closed)
        } finally {
            engine.releaseAllGates()
            try {
                assertTrue(host.close())
            } finally {
                power.turnScreenOn(true)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun keepAwakeFollowsConfirmedCaptureAcrossSuspensionAndReleasesBeforeMediaCleanup() = runBlocking {
        val data = defaults().let { it.copy(behavior = it.behavior.copy(keepScreenAwake = true)) }
        val engine = ControlledMjpegEngineSession(holdStart = true, holdStop = true)
        val host = createHost(listOf(engine), data)
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { engine.startEntered.await() }; inputs.publishDeliveryCheckpoint(host, address)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            engine.allowStartToReturn.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }; inputs.publishDeliveryCheckpoint(host, address)
            val wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(wakeLock.isHeld)
            assertFalse(shadowOf(wakeLock).isReferenceCounted)
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(keepScreenAwake = false)) }
            assertFalse(wakeLock.isHeld)
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(keepScreenAwake = true)) }
            assertTrue(wakeLock.isHeld)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.ResourceExhausted))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertTrue(wakeLock.isHeld)
            host.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { engine.stopEntered.await() }
            assertFalse(wakeLock.isHeld)
            inputs.publishDeliveryCheckpoint(host, address)
            assertFalse(wakeLock.isHeld)
            engine.releaseAllGates()
            awaitCaptureIdle(host)
        } finally {
            engine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-17
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun slowViewerWarningsUseFreshSamplesEpisodesAndCooldownWithoutDependingOnConsumerCounters() = runBlocking {
        val data = defaults().let { it.copy(behavior = it.behavior.copy(notifySlowViewers = true)) }
        val engine = ControlledMjpegEngineSession()
        val host = createHost(listOf(engine), data)
        val mainDispatcher = createMjpegLooperDispatcher("mjpeg-test-main")
        Dispatchers.setMain(mainDispatcher)
        try {
            inputs.awaitReadyListener(host, address)
            startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            inputs.publishDeliveryCheckpoint(host, address)
            val watermark = SystemClock.elapsedRealtime()
            publishSlowViewerSampleAndAwaitController(host, watermark, slow = true)
            assertEquals(0, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                1,
                ShadowToast.shownToastCount(),
                "sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                1,
                ShadowToast.shownToastCount(),
                "sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                1,
                ShadowToast.shownToastCount(),
                "sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(2, ShadowToast.shownToastCount())
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(notifySlowViewers = false)) }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = false)
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(notifySlowViewers = true)) }
            assertEquals(2, ShadowToast.shownToastCount())
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(2, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSampleAndAwaitController(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(3, ShadowToast.shownToastCount())
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
        } finally {
            engine.releaseAllGates()
            try {
                assertTrue(host.close())
            } finally {
                mainDispatcher.close()
            }
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-CTL-14
    @Test
    fun rejectedHttpCaptureRegistrationRemovesScreenOffSubscriptionAndAnotherStartCanSucceed() = runBlocking {
        val engine = ControlledMjpegEngineSession()
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = createHost(listOf(engine), data, grantConsent = false)
        try {
            inputs.awaitReadyListener(host, address)
            host.http.acceptCapture = false
            host.controller.requestStreamStart()
            inputs.publishDeliveryCheckpoint(host, address)
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            assertTrue(host.access.consentRequests.value.isEmpty())
            assertNull(host.managerObservation.status.value!!.captureAttempt)
            assertNull(host.managerObservation.status.value!!.error)
            assertTrue(screenOffReceivers().isEmpty())
            host.http.acceptCapture = true
            val attempt = startCaptureAndAwaitAttempt(host)
            assertEquals(listOf(attempt), host.http.registeredCaptureAttempts.value)
            assertEquals(1, screenOffReceivers().size)
        } finally {
            engine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-14
    @Test
    @Config(shadows = [FallibleScreenOffContextShadow::class])
    fun confirmationCannotClearANewerReceiverRemovalFailureRecordedAfterAttemptAdmission(): Unit = runBlocking {
        FallibleScreenOffContextShadow.registrationFailure = null
        FallibleScreenOffContextShadow.removalFailure = null
        val engine = ControlledMjpegEngineSession(holdStart = true)
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = createHost(listOf(engine), data)
        try {
            inputs.awaitReadyListener(host, address)
            startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            assertNull(host.managerObservation.status.value!!.error)
            FallibleScreenOffContextShadow.removalFailure = IllegalStateException("Newer receiver removal failure")
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(stopOnScreenOff = false)) }
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.error != null } }
            engine.allowStartToReturn.complete(Unit)
            val confirmed = withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }!!
            assertNotNull(confirmed.error)
            assertNull(confirmed.error!!.message)
            assertFalse(host.managerObservation.failed.isCompleted)
            assertFalse(host.http.closed)
        } finally {
            FallibleScreenOffContextShadow.removalFailure = null
            engine.releaseAllGates()
            try {
                assertFalse(host.close())
            } finally {
                shadowOf(application).clearRegisteredReceivers()
            }
        }
    }

    // Cases: MJPEG-CTL-14
    @Test
    fun disableAndReenableDefensivelyFenceAnOldScreenOffCallbackWithinTheSameAttempt() = runBlocking {
        val engine = ControlledMjpegEngineSession()
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = createHost(listOf(engine), data, grantConsent = false)
        val powerManagerShadow = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            val oldReceiver = currentScreenOffReceiver()
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(stopOnScreenOff = false)) }
            assertTrue(screenOffReceivers().isEmpty())
            oldReceiver.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF))
            inputs.publishDeliveryCheckpoint(host, address)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            applySettingsAndAwaitController(host) { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
            val currentReceiver = currentScreenOffReceiver()
            assertNotSame(oldReceiver, currentReceiver)
            oldReceiver.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF))
            inputs.publishDeliveryCheckpoint(host, address)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            powerManagerShadow.turnScreenOn(false)
            shadowOf(Looper.getMainLooper()).idle()
            awaitCaptureIdle(host)
            assertFalse(host.http.closed)
            assertTrue(screenOffReceivers().isEmpty())
        } finally {
            engine.releaseAllGates()
            try {
                assertTrue(host.close())
            } finally {
                powerManagerShadow.turnScreenOn(true)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun initialZeroEngineStatisticsAreForwardedDuringCaptureAndRetainedAfterStop() = runBlocking {
        val engine = ControlledMjpegEngineSession()
        val host = createHost(listOf(engine))
        try {
            inputs.awaitReadyListener(host, address)
            val attempt = startCaptureAndAwaitAttempt(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            val during = withTimeout(5.seconds) { host.controller.state.first { it.statistics.engineStats != null } }.statistics
            val statistics = checkNotNull(during.engineStats)
            assertEquals(engine.stats.value, statistics)
            assertEquals(0L, statistics.producedFrameCount)
            assertEquals(0L, statistics.encodedFrameCount)
            assertEquals(0.0, statistics.averageProducedFps, 0.0)
            assertNull(during.currentFps)
            host.controller.requestStreamStop(attempt, fromNotification = false)
            awaitCaptureIdle(host)
            val stopped = host.controller.state.value.statistics
            assertEquals(during.engineStats, stopped.engineStats)
            assertNull(stopped.currentFps)
            assertFalse(host.http.closed)
        } finally {
            engine.releaseAllGates()
            assertTrue(host.close())
        }
    }

    private suspend fun applySettingsAndAwaitController(host: MjpegServiceTestHost, update: MjpegSettings.Data.() -> MjpegSettings.Data) {
        val title = Uuid.random().toString()
        host.settings.updateData { update().let { it.copy(web = it.web.copy(title = title)) } }
        withTimeout(5.seconds) { host.http.configuration.first { it?.web?.title == title } }
        inputs.publishDeliveryCheckpoint(host, address)
    }

    private suspend fun createHost(
        engines: List<ControlledMjpegEngineSession>,
        data: MjpegSettings.Data = defaults(),
        grantConsent: Boolean = true
    ): MjpegServiceTestHost {
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(data))
        val remaining = java.util.concurrent.CopyOnWriteArrayList(engines)
        return MjpegServiceTestHost(MjpegSettings(context), engineFactory = { _, _, _ -> remaining.removeAt(0) }).also {
            if (grantConsent) it.access.consentGrant.complete(object : ScreenCaptureAccess.Grant {})
        }
    }

    private suspend fun startCaptureAndAwaitAttempt(host: MjpegServiceTestHost, awaitPending: Boolean = true): StreamingModule.CaptureAttemptId {
        val count = host.access.consentRequests.value.size
        host.controller.requestStreamStart()
        val attempt = withTimeout(5.seconds) { host.access.consentRequests.first { it.size > count }.last() }
        if (awaitPending) withTimeout(5.seconds) { host.managerObservation.status.first { it?.captureAttempt == attempt } }
        return attempt
    }

    private suspend fun awaitCaptureIdle(host: MjpegServiceTestHost) {
        withTimeout(5.seconds) { host.controller.state.first { it.action is UiController.Action.Start } }
        withTimeout(5.seconds) { host.managerObservation.status.first { it?.captureAttempt == null } }
    }

    private suspend fun publishSlowViewerSampleAndAwaitController(host: MjpegServiceTestHost, at: Long, slow: Boolean) {
        val info = host.http.info.value
        host.http.info.value = info.copy(sampledAtElapsedRealtimeMillis = at, clients = listOf(client(info.servers.single().id).copy(isSlow = slow)))
        inputs.publishDeliveryCheckpoint(host, address)
        assertEquals(MjpegCaptureSession.State.Status.Active, host.http.captureStatus.value?.second)
        assertTrue(host.managerObservation.status.value!!.isStreaming)
        assertEquals(at, host.http.info.value.sampledAtElapsedRealtimeMillis)
    }

    private fun screenOffReceivers(): List<BroadcastReceiver> = shadowOf(application).registeredReceivers
        .filter { it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) }
        .map { it.broadcastReceiver }

    private fun currentScreenOffReceiver(): BroadcastReceiver = screenOffReceivers().single()
    private fun client(server: Uuid) = HttpDelivery.ClientInfo(1, "198.51.100.2", 4000, server, HttpDelivery.MediaFormat.Jpeg, false, 10)
    private fun defaults() = MjpegSettings.Data(web = WebPageSettings(title = "Capture contract"))
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)
}
