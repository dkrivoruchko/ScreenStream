package io.screenstream.mjpeg

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.elvishew.xlog.XLog
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.net.InetAddress
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class MjpegControllerCaptureContractsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE); ShadowToast.reset(); ShadowPowerManager.clearWakeLocks()
    }

    // Cases: MJPEG-CTL-03, MJPEG-CTL-04, MJPEG-CAP-13
    @Test
    fun confirmedCaptureConsumerPresenceSuspensionAndPendingCleanupAreIndependent() = runBlocking {
        val engine = ControlledMjpegEngine(holdStart = true, holdStop = true)
        val host = host(listOf(engine))
        try {
            ready(host)
            val attempt = start(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            checkpoint(host)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            engine.finishStart.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            checkpoint(host)
            assertEquals(UiController.Action.Stop(attempt), host.controller.state.value.action)
            assertTrue(host.notifications.actions.last() is io.screenstream.core.module.StreamingModuleNotifications.ForegroundAction.StopStream)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.InvalidRequest))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem == MjpegCaptureSession.State.SuspensionProblem.InvalidRequest } }
            assertTrue(host.managerObservation.status.value!!.isStreaming)
            val info = host.http.info.value
            host.http.info.value = info.copy(clients = listOf(client(info.servers.single().id)))
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.hasConsumer == true } }
            host.controller.requestStreamStop(attempt, false)
            withTimeout(5.seconds) { engine.stopEntered.await() }
            checkpoint(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it != null && !it.isStreaming && it.captureAttempt == attempt } }
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            engine.finishStop.complete(Unit)
            idle(host)
            assertFalse(host.http.closed)
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
            assertTrue(host.controller.state.value.action is UiController.Action.Start)
            assertTrue(host.notifications.actions.last() is io.screenstream.core.module.StreamingModuleNotifications.ForegroundAction.Exit)
        } finally {
            engine.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-03, MJPEG-CTL-04
    @Test
    fun unconfirmedSuspensionKeepsTheCaptureActionBusy() = runBlocking {
        val engine = ControlledMjpegEngine(holdStart = true)
        val host = host(listOf(engine))
        try {
            ready(host); start(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertFalse(host.managerObservation.status.value!!.isStreaming)
            assertEquals(UiController.Action.Busy, host.controller.state.value.action)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Active))
            engine.finishStart.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertTrue(host.managerObservation.status.value!!.isStreaming)
            assertEquals(UiController.Action.Stop(host.access.requested.value.single()), host.controller.state.value.action)
        } finally {
            engine.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05, MJPEG-CAP-06
    @Test
    fun liveParametersApplyWhileBackendPortAndAccessWaitThroughStoppingForSuccessfulCleanup() = runBlocking {
        val first = ControlledMjpegEngine(holdStop = true)
        val second = ControlledMjpegEngine()
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(defaults()))
        val configurations = java.util.concurrent.CopyOnWriteArrayList<io.screenstream.capture.ScreenCaptureConfig>()
        val engines = java.util.concurrent.CopyOnWriteArrayList(listOf(first, second))
        val host = MjpegServiceHost(MjpegSettings(context), engineFactory = { _, _, config -> configurations += config; engines.removeAt(0) })
        host.access.consent.complete(object : ScreenCaptureAccess.Grant {})
        try {
            ready(host)
            val attempt = start(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            applySettings(host) {
                copy(
                    image = image.copy(jpegQuality = 45, jpegBackendPolicy = io.screenstream.capture.JpegBackendPolicy.FrameworkOnly),
                    network = network.copy(httpPort = 9000),
                    access = access.copy(pinEnabled = true, pin = io.screenstream.mjpeg.settings.SecretValue("123456")),
                )
            }
            withTimeout(5.seconds) { while (first.updates.none { it.jpegQuality == 45 }) yield() }
            assertEquals(io.screenstream.capture.JpegBackendPolicy.Auto, configurations.single().jpegBackendPolicy)
            assertEquals(8080, host.http.plan.value!!.port)
            assertFalse(host.http.configuration.value!!.access.pinEnabled)
            assertEquals(MjpegSettings.EditPolicy.LiveOnly, host.controller.state.value.editPolicy)
            host.controller.requestStreamStop(attempt, false)
            withTimeout(5.seconds) { first.stopEntered.await() }
            applySettings(host) {
                copy(
                    image = image.copy(jpegQuality = 71),
                    network = network.copy(httpPort = 9100),
                    access = access.copy(pin = io.screenstream.mjpeg.settings.SecretValue("654321"))
                )
            }
            assertTrue(first.updates.none { it.jpegQuality == 71 })
            assertEquals(8080, host.http.plan.value!!.port)
            first.finishStop.complete(Unit); idle(host)
            withTimeout(5.seconds) { host.http.plan.first { it?.port == 9100 } }
            assertEquals("654321", host.http.configuration.value!!.access.pin!!.value)
            assertEquals(MjpegSettings.EditPolicy.All, host.controller.state.value.editPolicy)
            val serverId = Uuid.random()
            host.http.info.value =
                host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(serverId, address, 9100, HttpDelivery.ServerState.Listening)))
            withTimeout(5.seconds) { host.controller.state.first { it.action == UiController.Action.Start(true) } }
            start(host)
            withTimeout(5.seconds) { second.startEntered.await() }
            assertEquals(71, second.initialParameters!!.jpegQuality)
            assertEquals(io.screenstream.capture.JpegBackendPolicy.FrameworkOnly, configurations.last().jpegBackendPolicy)
        } finally {
            first.release(); second.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11
    @Test
    fun captureFailureCanRecoverOnlyAfterSuccessfulCleanupAndLaterConfirmationClearsItsError() = runBlocking {
        val cause = IllegalStateException("Controlled Engine startup failure")
        val first = ControlledMjpegEngine().also { it.startFailure = cause }
        val second = ControlledMjpegEngine(holdStart = true)
        val host = host(listOf(first, second))
        try {
            ready(host); start(host, awaitPending = false)
            withTimeout(5.seconds) { first.stopEntered.await() }
            idle(host)
            assertNotNull(host.managerObservation.status.value!!.error)
            assertNull(host.managerObservation.status.value!!.error!!.message)
            assertTrue(host.managerObservation.isCurrent())
            assertFalse(host.managerObservation.failed.isCompleted)
            start(host)
            withTimeout(5.seconds) { second.startEntered.await() }
            checkpoint(host)
            assertNotNull(host.managerObservation.status.value!!.error)
            second.finishStart.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true && it.error == null } }
            assertFalse(host.http.closed)
        } finally {
            first.release(); second.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-05, MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun failedCaptureCleanupIsTerminalAndNeverAdmitsAReplacement() = runBlocking {
        val engine = ControlledMjpegEngine().also { it.stopFailure = IllegalStateException("Media stop failed") }
        val host = host(listOf(engine))
        try {
            ready(host)
            val attempt = start(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            host.controller.requestStreamStop(attempt, false)
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopSettled.await(); host.monitor.closeSettled.await() }
            host.controller.requestStreamStart()
            assertEquals(listOf(attempt), host.http.captures.value)
            assertTrue(host.http.closed)
            host.awaitStopRequested(); host.destroy()
            assertFalse(withTimeout(5.seconds) { host.controller.awaitCleanup() })
        } finally {
            engine.release(); assertFalse(host.close())
        }
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun delayedStopReportsItsMessageUntilCleanupAndRetainedCaptureFailureTakesPrecedence() = runBlocking {
        for (retainFailure in listOf(false, true)) {
            val engine = ControlledMjpegEngine(holdStop = true)
            val host = host(listOf(engine))
            try {
                ready(host)
                val attempt = start(host)
                withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
                if (retainFailure) engine.publish(MjpegEngineState(MjpegEngineState.Phase.Failed, ScreenCaptureProblem.InternalFailure))
                else host.controller.requestStreamStop(attempt, false)
                withTimeout(5.seconds) { engine.stopEntered.await() }
                ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
                checkpoint(host)
                val error = withTimeout(5.seconds) { host.managerObservation.status.first { it?.error != null } }!!.error
                assertNotNull(error)
                if (retainFailure) assertNull(error!!.message) else assertEquals(host.service.getString(R.string.mjpeg_stream_stop_delayed), error!!.message)
                engine.release(); idle(host)
                if (retainFailure) assertNotNull(host.managerObservation.status.value!!.error) else assertNull(host.managerObservation.status.value!!.error)
            } finally {
                engine.release(); assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-14, MJPEG-CTL-03
    @Test
    fun screenOffRegistrationRejectsAnAlreadySleepingDisplayAndDefensivelyFencesAnOldCallbackFromItsSuccessor() = runBlocking {
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val firstEngine = ControlledMjpegEngine()
        val secondEngine = ControlledMjpegEngine()
        val host = host(listOf(firstEngine, secondEngine), data, grantConsent = false)
        val power = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            ready(host)
            power.turnScreenOn(false)
            host.controller.requestStreamStart(); checkpoint(host)
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())
            power.turnScreenOn(true)
            val first = start(host)
            val oldReceiver = receiver()
            host.controller.requestStreamStop(first, false); idle(host)
            assertTrue(screenOffReceivers().isEmpty())
            val second = start(host)
            val currentReceiver = receiver()
            assertNotSame(oldReceiver, currentReceiver)
            oldReceiver.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF)); checkpoint(host)
            assertEquals(second, host.managerObservation.status.value!!.captureAttempt)
            power.turnScreenOn(false)
            shadowOf(Looper.getMainLooper()).idle()
            idle(host)
            assertFalse(host.http.closed)
            assertTrue(screenOffReceivers().isEmpty())
        } finally {
            firstEngine.release()
            secondEngine.release()
            try {
                assertTrue(host.close())
            } finally {
                power.turnScreenOn(true)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    // Cases: MJPEG-CTL-14
    @Test
    fun enablingScreenOffDuringConsentWithTheDisplayAlreadyOffStopsThatAttempt() = runBlocking {
        val engine = ControlledMjpegEngine()
        val host = host(listOf(engine), grantConsent = false)
        val power = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            ready(host)
            val attempt = start(host)
            power.turnScreenOn(false)
            applySettings(host) { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
            idle(host)
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            assertFalse(engine.startEntered.isCompleted)
            assertFalse(host.http.closed)
        } finally {
            engine.release()
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
        val engine = ControlledMjpegEngine(holdStart = true, holdStop = true)
        val host = host(listOf(engine), data)
        try {
            ready(host)
            val attempt = start(host)
            withTimeout(5.seconds) { engine.startEntered.await() }; checkpoint(host)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            engine.finishStart.complete(Unit)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }; checkpoint(host)
            val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(lock.isHeld)
            assertFalse(shadowOf(lock).isReferenceCounted)
            applySettings(host) { copy(behavior = behavior.copy(keepScreenAwake = false)) }
            assertFalse(lock.isHeld)
            applySettings(host) { copy(behavior = behavior.copy(keepScreenAwake = true)) }
            assertTrue(lock.isHeld)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.ResourceExhausted))
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertTrue(lock.isHeld)
            host.controller.requestStreamStop(attempt, false)
            withTimeout(5.seconds) { engine.stopEntered.await() }
            assertFalse(lock.isHeld)
            checkpoint(host)
            assertFalse(lock.isHeld)
            engine.release(); idle(host)
        } finally {
            engine.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-17
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun slowViewerWarningsUseFreshSamplesEpisodesAndCooldownWithoutDependingOnConsumerCounters() = runBlocking {
        val warningFailures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        io.screenstream.core.configureLogWriter { _, tag, _, cause -> if (tag == "SlowViewer.toast" && cause != null) warningFailures += cause }
        val data = defaults().let { it.copy(behavior = it.behavior.copy(notifySlowViewers = true)) }
        val engine = ControlledMjpegEngine()
        val host = host(listOf(engine), data)
        val main = mjpegTestMainDispatcher("mjpeg-test-main")
        Dispatchers.setMain(main)
        try {
            ready(host); start(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            checkpoint(host)
            val watermark = SystemClock.elapsedRealtime()
            slowSample(host, watermark, slow = true)
            assertEquals(0, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                "Toast failures=$warningFailures; sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
                1,
                ShadowToast.shownToastCount()
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                "Toast failures=$warningFailures; sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
                1,
                ShadowToast.shownToastCount()
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            slowSample(host, SystemClock.elapsedRealtime(), slow = false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(
                "Toast failures=$warningFailures; sample=${host.http.info.value.sampledAtElapsedRealtimeMillis}; now=${SystemClock.elapsedRealtime()}",
                1,
                ShadowToast.shownToastCount()
            )
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(2, ShadowToast.shownToastCount())
            applySettings(host) { copy(behavior = behavior.copy(notifySlowViewers = false)) }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            slowSample(host, SystemClock.elapsedRealtime(), slow = false)
            applySettings(host) { copy(behavior = behavior.copy(notifySlowViewers = true)) }
            assertEquals(2, ShadowToast.shownToastCount())
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(2, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            slowSample(host, SystemClock.elapsedRealtime(), slow = true)
            assertEquals(3, ShadowToast.shownToastCount())
            assertTrue(host.managerObservation.status.value!!.hasConsumer)
        } finally {
            engine.release()
            try {
                assertTrue(host.close())
            } finally {
                main.close()
                io.screenstream.core.configureLogWriter { priority, _, message, cause ->
                    if (cause == null) XLog.log(priority, message) else XLog.log(
                        priority,
                        message,
                        cause
                    )
                }
            }
        }
    }

    // Cases: MJPEG-CTL-02, MJPEG-CTL-14
    @Test
    fun rejectedHttpCaptureRegistrationRemovesScreenOffSubscriptionAndAnotherStartCanSucceed() = runBlocking {
        val engine = ControlledMjpegEngine()
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = host(listOf(engine), data, grantConsent = false)
        try {
            ready(host)
            host.http.acceptCapture = false
            host.controller.requestStreamStart(); checkpoint(host)
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())
            assertNull(host.managerObservation.status.value!!.captureAttempt)
            assertNull(host.managerObservation.status.value!!.error)
            assertTrue(screenOffReceivers().isEmpty())
            host.http.acceptCapture = true
            val attempt = start(host)
            assertEquals(listOf(attempt), host.http.captures.value)
            assertEquals(1, screenOffReceivers().size)
        } finally {
            engine.release(); assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-14
    @Test
    @Config(shadows = [FallibleScreenOffContextShadow::class])
    fun confirmationCannotClearANewerReceiverRemovalFailureRecordedAfterAttemptAdmission() = runBlocking {
        FallibleScreenOffContextShadow.registrationFailure = null
        FallibleScreenOffContextShadow.removalFailure = null
        val engine = ControlledMjpegEngine(holdStart = true)
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = host(listOf(engine), data)
        try {
            ready(host); start(host)
            withTimeout(5.seconds) { engine.startEntered.await() }
            assertNull(host.managerObservation.status.value!!.error)
            FallibleScreenOffContextShadow.removalFailure = IllegalStateException("Newer receiver removal failure")
            applySettings(host) { copy(behavior = behavior.copy(stopOnScreenOff = false)) }
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.error != null } }
            engine.finishStart.complete(Unit)
            val confirmed = withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }!!
            assertNotNull(confirmed.error)
            assertNull(confirmed.error!!.message)
            assertFalse(host.managerObservation.failed.isCompleted)
            assertFalse(host.http.closed)
        } finally {
            FallibleScreenOffContextShadow.removalFailure = null
            engine.release()
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
        val engine = ControlledMjpegEngine()
        val data = defaults().let { it.copy(behavior = it.behavior.copy(stopOnScreenOff = true)) }
        val host = host(listOf(engine), data, grantConsent = false)
        val power = shadowOf(host.service.getSystemService(PowerManager::class.java))
        try {
            ready(host)
            val attempt = start(host)
            val old = receiver()
            applySettings(host) { copy(behavior = behavior.copy(stopOnScreenOff = false)) }
            assertTrue(screenOffReceivers().isEmpty())
            old.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF)); checkpoint(host)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            applySettings(host) { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
            val current = receiver()
            assertNotSame(old, current)
            old.onReceive(host.service, Intent(Intent.ACTION_SCREEN_OFF)); checkpoint(host)
            assertEquals(attempt, host.managerObservation.status.value!!.captureAttempt)
            power.turnScreenOn(false)
            shadowOf(Looper.getMainLooper()).idle()
            idle(host)
            assertFalse(host.http.closed)
            assertTrue(screenOffReceivers().isEmpty())
        } finally {
            engine.release()
            try {
                assertTrue(host.close())
            } finally {
                power.turnScreenOn(true)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun aLawfulZeroEngineSnapshotIsForwardedDuringCaptureAndRetainedAfterStop() = runBlocking {
        val engine = ControlledMjpegEngine()
        val host = host(listOf(engine))
        try {
            ready(host)
            val attempt = start(host)
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            val during = withTimeout(5.seconds) { host.controller.state.first { it.statistics.engineStats != null } }.statistics
            val statistics = checkNotNull(during.engineStats)
            assertEquals(engine.stats.value, statistics)
            assertEquals(0L, statistics.producedFrameCount)
            assertEquals(0L, statistics.encodedFrameCount)
            assertEquals(0.0, statistics.averageProducedFps, 0.0)
            assertNull(during.currentFps)
            host.controller.requestStreamStop(attempt, false); idle(host)
            val stopped = host.controller.state.value.statistics
            assertEquals(during.engineStats, stopped.engineStats)
            assertNull(stopped.currentFps)
            assertFalse(host.http.closed)
        } finally {
            engine.release(); assertTrue(host.close())
        }
    }

    private suspend fun applySettings(host: MjpegServiceHost, update: MjpegSettings.Data.() -> MjpegSettings.Data) {
        val title = Uuid.random().toString()
        host.settings.updateData { update().let { it.copy(web = it.web.copy(title = title)) } }
        withTimeout(5.seconds) { host.http.configuration.first { it?.web?.title == title } }
        checkpoint(host)
    }

    private suspend fun host(engines: List<ControlledMjpegEngine>, data: MjpegSettings.Data = defaults(), grantConsent: Boolean = true): MjpegServiceHost {
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        seedSettings(context, Json.encodeToString(data))
        val remaining = java.util.concurrent.CopyOnWriteArrayList(engines)
        return MjpegServiceHost(MjpegSettings(context), engineFactory = { _, _, _ -> remaining.removeAt(0) }).also {
            if (grantConsent) it.access.consent.complete(object : ScreenCaptureAccess.Grant {})
        }
    }

    private suspend fun ready(host: MjpegServiceHost) {
        withTimeout(5.seconds) { host.http.configuration.first { it != null } }
        host.monitor.state.value = observedAddresses(listOf(address))
        withTimeout(5.seconds) { host.http.plan.first { it?.addresses == listOf(address) } }
        checkpoint(host)
    }

    private suspend fun start(host: MjpegServiceHost, awaitPending: Boolean = true): StreamingModule.CaptureAttemptId {
        val count = host.access.requested.value.size
        host.controller.requestStreamStart()
        val attempt = withTimeout(5.seconds) { host.access.requested.first { it.size > count }.last() }
        if (awaitPending) withTimeout(5.seconds) { host.managerObservation.status.first { it?.captureAttempt == attempt } }
        return attempt
    }

    private suspend fun idle(host: MjpegServiceHost) {
        withTimeout(5.seconds) { host.controller.state.first { it.action is UiController.Action.Start } }
        withTimeout(5.seconds) { host.managerObservation.status.first { it?.captureAttempt == null } }
    }

    private suspend fun checkpoint(host: MjpegServiceHost) {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)))
        withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == id } }
    }

    private suspend fun slowSample(host: MjpegServiceHost, at: Long, slow: Boolean) {
        val info = host.http.info.value
        host.http.info.value = info.copy(sampledAtElapsedRealtimeMillis = at, clients = listOf(client(info.servers.single().id).copy(isSlow = slow)))
        checkpoint(host)
        assertEquals(MjpegCaptureSession.State.Status.Active, host.http.captureStatus.value?.second)
        assertTrue(host.managerObservation.status.value!!.isStreaming)
        assertEquals(at, host.http.info.value.sampledAtElapsedRealtimeMillis)
    }

    private fun screenOffReceivers(): List<BroadcastReceiver> = shadowOf(application).registeredReceivers
        .filter { it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) }
        .map { it.broadcastReceiver }

    private fun receiver(): BroadcastReceiver = screenOffReceivers().single()
    private fun client(server: Uuid) = HttpDelivery.ClientInfo(1, "198.51.100.2", 4000, server, HttpDelivery.MediaFormat.Jpeg, false, 10)
    private fun defaults() = MjpegSettings.Data(web = WebPageSettings(title = "Capture contract"))
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)
}
