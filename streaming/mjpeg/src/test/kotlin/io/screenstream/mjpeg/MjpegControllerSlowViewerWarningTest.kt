package io.screenstream.mjpeg

import android.os.SystemClock
import com.elvishew.xlog.XLog
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.net.InetAddress
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
internal class MjpegControllerSlowViewerWarningTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
        ShadowToast.reset()
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun suppressedWarningStartsCooldownAcrossRecovery() = runBlocking {
        val engine = ControlledMjpegEngineSession()
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val host = MjpegServiceTestHost(MjpegSettings(context), engineFactory = { _, _, _ -> engine })
        host.access.consentGrant.complete(object : ScreenCaptureAccess.Grant {})
        val mainDispatcher = createMjpegLooperDispatcher("mjpeg-warning-test-main")
        val gatedMainDispatcher = GatedMainDispatcher(mainDispatcher)
        Dispatchers.setMain(mainDispatcher)
        try {
            withTimeout(5.seconds) { host.http.configuration.first { it != null } }
            host.monitor.state.value = observedAddressState(listOf(address))
            withTimeout(5.seconds) { host.http.plan.first { it?.addresses == listOf(address) } }
            publishDeliveryCheckpoint(host)
            host.controller.requestStreamStart()
            withTimeout(5.seconds) { host.managerObservation.status.first { it?.isStreaming == true } }
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            withTimeout(5.seconds) { host.controller.state.first { it.statistics.engineStats != null } }
            publishDeliveryCheckpoint(host)
            publishDeliveryCheckpoint(host)

            Dispatchers.setMain(gatedMainDispatcher)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlowViewerSample(host)
            val pendingDispatch = withTimeout(5.seconds) { gatedMainDispatcher.awaitNextDispatch() }
            try {
                engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
                withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Suspended } }
            } finally {
                pendingDispatch.run()
            }
            gatedMainDispatcher.resumeDispatching()
            Dispatchers.setMain(mainDispatcher)
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertEquals(0, ShadowToast.shownToastCount())

            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Active))
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            publishDeliveryCheckpoint(host)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            val early = publishSlowViewerSample(host)
            withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == early } }
            assertEquals(0, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            val later = publishSlowViewerSample(host)
            withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == later } }
            assertEquals(1, ShadowToast.shownToastCount())
        } finally {
            gatedMainDispatcher.resumeDispatching()
            Dispatchers.setMain(mainDispatcher)
            engine.releaseAllGates()
            try {
                assertTrue(host.close())
            } finally {
                mainDispatcher.close()
            }
        }
    }

    private suspend fun publishDeliveryCheckpoint(host: MjpegServiceTestHost) {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)))
        withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == id } }
    }

    private fun publishSlowViewerSample(host: MjpegServiceTestHost): Uuid {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(
            servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)),
            sampledAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            clients = listOf(HttpDelivery.ClientInfo(1, "198.51.100.2", 4000, id, HttpDelivery.MediaFormat.Jpeg, true, 10)),
        )
        return id
    }

    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)

    private class GatedMainDispatcher(private val fallback: CoroutineDispatcher) : CoroutineDispatcher() {
        private data class PendingDispatch(val context: CoroutineContext, val body: Runnable)

        private val gate = Any()
        private val pending = Channel<PendingDispatch>(8)
        private var isReleased = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val forward = synchronized(gate) {
                if (isReleased) true else {
                    check(pending.trySend(PendingDispatch(context, block)).isSuccess)
                    false
                }
            }
            if (forward) fallback.dispatch(context, block)
        }

        suspend fun awaitNextDispatch(): Runnable = pending.receive().body
        fun resumeDispatching() {
            val pendingDispatches = synchronized(gate) {
                isReleased = true
                buildList { while (true) add(pending.tryReceive().getOrNull() ?: break) }
            }
            pendingDispatches.forEach { fallback.dispatch(it.context, it.body) }
        }
    }
}
