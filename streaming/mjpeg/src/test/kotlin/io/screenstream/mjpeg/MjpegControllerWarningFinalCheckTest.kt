package io.screenstream.mjpeg

import android.os.Looper
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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
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
internal class MjpegControllerWarningFinalCheckTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE); ShadowToast.reset()
    }

    // Cases: MJPEG-CTL-17
    @Test
    fun aSuppressedMainThreadWarningStillConsumesItsEpisodeAndStartsTheCooldown() = runBlocking {
        val engine = ControlledMjpegEngine()
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val host = MjpegServiceHost(MjpegSettings(context), engineFactory = { _, _, _ -> engine })
        host.access.consent.complete(object : ScreenCaptureAccess.Grant {})
        val main = preparedMain()
        val held = HeldMain(main)
        Dispatchers.setMain(main)
        try {
            withTimeout(5.seconds) { host.http.configuration.first { it != null } }
            host.monitor.state.value = observedAddresses(listOf(address))
            withTimeout(5.seconds) { host.http.plan.first { it?.addresses == listOf(address) } }
            checkpoint(host)
            host.controller.requestStreamStart()
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.isStreaming == true } }
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            withTimeout(5.seconds) { host.controller.state.first { it.statistics.engineStats != null } }
            checkpoint(host)
            checkpoint(host)

            Dispatchers.setMain(held)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            publishSlow(host)
            val step = withTimeout(5.seconds) { held.next() }
            try {
                engine.publish(MjpegEngineState(MjpegEngineState.Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
                withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Suspended } }
            } finally {
                step.run()
            }
            held.release()
            Dispatchers.setMain(main)
            withTimeout(5.seconds) { host.controller.state.first { it.suspensionProblem != null } }
            assertEquals(0, ShadowToast.shownToastCount())

            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Active))
            withTimeout(5.seconds) { host.http.captureStatus.first { it?.second == MjpegCaptureSession.State.Status.Active } }
            checkpoint(host)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            val early = publishSlow(host)
            withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == early } }
            assertEquals(0, ShadowToast.shownToastCount())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            val later = publishSlow(host)
            withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == later } }
            assertEquals(1, ShadowToast.shownToastCount())
        } finally {
            held.release()
            Dispatchers.setMain(main)
            engine.release()
            try {
                assertTrue(host.close())
            } finally {
                main.close()
            }
        }
    }

    private suspend fun checkpoint(host: MjpegServiceHost) {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)))
        withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == id } }
    }

    private fun publishSlow(host: MjpegServiceHost): Uuid {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(
            servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)),
            sampledAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            clients = listOf(HttpDelivery.ClientInfo(1, "198.51.100.2", 4000, id, HttpDelivery.MediaFormat.Jpeg, true, 10)),
        )
        return id
    }

    private fun preparedMain(): ExecutorCoroutineDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { command ->
        Thread {
            Looper.prepare(); try {
            command.run()
        } finally {
            Looper.myLooper()?.quit()
        }
        }.apply { name = "mjpeg-warning-test-main"; isDaemon = true }
    }.asCoroutineDispatcher()

    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)

    private class HeldMain(private val fallback: CoroutineDispatcher) : CoroutineDispatcher() {
        private data class Step(val context: CoroutineContext, val body: Runnable)

        private val gate = Any()
        private val pending = Channel<Step>(8)
        private var released = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val forward = synchronized(gate) {
                if (released) true else {
                    check(pending.trySend(Step(context, block)).isSuccess); false
                }
            }
            if (forward) fallback.dispatch(context, block)
        }

        suspend fun next(): Runnable = pending.receive().body
        fun release() {
            val steps = synchronized(gate) {
                released = true
                buildList { while (true) add(pending.tryReceive().getOrNull() ?: break) }
            }
            steps.forEach { fallback.dispatch(it.context, it.body) }
        }
    }
}
