package io.screenstream.mjpeg

import android.os.SystemClock
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.shadows.ShadowSystemClock
import java.net.InetAddress
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [34])
internal class MjpegControllerStatisticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-15
    @Test
    fun completedByteRatesRequireDistinctPositiveSamplesAndExactClientBaselines() = runBlocking {
        val host = host()
        try {
            ready(host)
            val client = client(1, 100)
            assertNull(sample(host, 0, 100, listOf(client)).outgoingBytesPerSecond)
            assertNull(sample(host, 1000, 300, listOf(client)).outgoingBytesPerSecond)
            val repeatedFirst = sample(host, 1000, 900, listOf(client.copy(completedJpegBytes = 700)))
            assertNull(repeatedFirst.outgoingBytesPerSecond)
            assertTrue(repeatedFirst.clientBytesPerSecond.isEmpty())
            val second = sample(host, 2000, 500, listOf(client.copy(completedJpegBytes = 240)))
            assertEquals(200.0, second.outgoingBytesPerSecond!!, 0.0)
            assertEquals(mapOf(1L to 140.0), second.clientBytesPerSecond)
            val repeated = sample(host, 2000, 999, listOf(client.copy(completedJpegBytes = 999)))
            assertEquals(200.0, repeated.outgoingBytesPerSecond!!, 0.0)
            assertEquals(mapOf(1L to 140.0), repeated.clientBytesPerSecond)
            val newClient = sample(host, 3000, 950, listOf(client.copy(completedJpegBytes = 300), client(2, 50)))
            assertEquals(450.0, newClient.outgoingBytesPerSecond!!, 0.0)
            assertEquals(mapOf(1L to 60.0), newClient.clientBytesPerSecond)
            val reversedTime = sample(host, 2500, 980, listOf(client.copy(completedJpegBytes = 305)))
            assertNull(reversedTime.outgoingBytesPerSecond)
            assertTrue(reversedTime.clientBytesPerSecond.isEmpty())
            val decreased = sample(host, 3500, 900, listOf(client.copy(completedJpegBytes = 1)))
            assertNull(decreased.outgoingBytesPerSecond)
            assertTrue(decreased.clientBytesPerSecond.isEmpty())
            val unchanged = sample(host, 4500, 900, listOf(client.copy(completedJpegBytes = 1)))
            assertEquals(0.0, unchanged.outgoingBytesPerSecond!!, 0.0)
            assertEquals(mapOf(1L to 0.0), unchanged.clientBytesPerSecond)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-04, MJPEG-CTL-15, MJPEG-CTL-16
    @Test
    fun httpBaselinesAndHistorySurviveCaptureStopAndStartAndResetInAnotherModule() = runBlocking {
        val host = host()
        try {
            ready(host)
            sample(host, 1000, 100, listOf(client(1, 40)))
            advanceElapsed(1000)
            sample(host, 2000, 300, listOf(client(1, 140)))
            advanceElapsed(1000)
            host.controller.requestStreamStart()
            val first = withTimeout(5.seconds) { host.access.requested.first { it.size == 1 }.single() }
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.captureAttempt == first } }
            val beforeStop = host.controller.state.value.statistics
            val earlierPoints = beforeStop.history.filter { it.atElapsedRealtimeMillis / 1000L < SystemClock.elapsedRealtime() / 1000L }
            assertTrue(earlierPoints.isNotEmpty())
            host.controller.requestStreamStop(first, false)
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.captureAttempt == null } }
            host.controller.requestStreamStart()
            val second = withTimeout(5.seconds) { host.access.requested.first { it.size == 2 }.last() }
            assertNotEquals(first, second)
            val after = sample(host, 3000, 700, listOf(client(1, 340)))
            assertEquals(400.0, after.outgoingBytesPerSecond!!, 0.0)
            assertEquals(mapOf(1L to 200.0), after.clientBytesPerSecond)
            assertTrue(after.history.isNotEmpty())
            assertTrue(after.history.containsAll(earlierPoints))
            assertFalse(host.managerHandle.status.value!!.isStreaming)
            assertTrue(host.managerHandle.status.value!!.hasConsumer)
        } finally {
            assertTrue(host.close())
        }
        val fresh = host()
        try {
            ready(fresh)
            val first = sample(fresh, 4000, 9000, listOf(client(1, 9000)))
            assertNull(first.outgoingBytesPerSecond)
            assertTrue(first.clientBytesPerSecond.isEmpty())
            assertNull(first.engineStats)
            assertNull(first.currentFps)
            assertTrue(first.history.size <= 1)
        } finally {
            assertTrue(fresh.close())
        }
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun elapsedSecondHistoryReplacesChangedPointsSkipsGapsAndPrunesTheSixtySecondWindow() = runBlocking {
        val host = host()
        try {
            ready(host)
            val nextSecond = (SystemClock.elapsedRealtime() / 1000L + 1) * 1000L
            advanceElapsed(nextSecond - SystemClock.elapsedRealtime())
            sample(host, 1000, 0)
            val first = sample(host, 2000, 200)
            val slot = SystemClock.elapsedRealtime() / 1000L
            assertEquals(1, first.history.count { it.atElapsedRealtimeMillis / 1000L == slot })
            val changed = sample(host, 3000, 600)
            val latest = changed.history.filter { it.atElapsedRealtimeMillis / 1000L == slot }.single()
            assertEquals(400.0, latest.outgoingBytesPerSecond!!, 0.0)
            assertNull(latest.fps)

            val previousSize = changed.history.size
            advanceElapsed(5000)
            val gap = sample(host, 4000, 1200)
            assertEquals(previousSize + 1, gap.history.size)
            assertTrue(gap.history.zipWithNext().any { (a, b) -> b.atElapsedRealtimeMillis - a.atElapsedRealtimeMillis >= 5000 })
            for (second in 1..65) {
                advanceElapsed(1000)
                sample(host, 4000L + second * 1000L, 1200L + second * 100L)
            }
            val history = host.controller.state.value.statistics.history
            assertEquals(60, history.size)
            assertTrue(history.all { it.atElapsedRealtimeMillis > SystemClock.elapsedRealtime() - 60000L })
            assertEquals(history.size, history.map { it.atElapsedRealtimeMillis / 1000L }.distinct().size)
            assertTrue(history.all { it.fps == null })
        } finally {
            assertTrue(host.close())
        }
    }

    private fun host(): MjpegServiceHost =
        MjpegServiceHost(MjpegSettings(IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())))

    private suspend fun ready(host: MjpegServiceHost) {
        withTimeout(5.seconds) { host.http.configuration.first { it != null } }
        host.monitor.state.value = observedAddresses(listOf(address))
        withTimeout(5.seconds) { host.http.plan.first { it?.addresses == listOf(address) } }
        sample(host, 0, 0)
    }

    private suspend fun sample(host: MjpegServiceHost, time: Long, total: Long, clients: List<HttpDelivery.ClientInfo> = emptyList()): UiController.Statistics {
        val serverId = Uuid.random()
        host.http.info.value = HttpDelivery.Info(
            servers = listOf(HttpDelivery.ServerInfo(serverId, address, 8080, HttpDelivery.ServerState.Listening)),
            sampledAtElapsedRealtimeMillis = time, completedJpegBytes = total, clients = clients,
        )
        return withTimeout(5.seconds) {
            host.controller.state.first { it.addressServers.singleOrNull()?.serverId == serverId }.statistics
        }
    }

    private fun client(id: Long, bytes: Long) =
        HttpDelivery.ClientInfo(id, "198.51.100.2", 4000 + id.toInt(), Uuid.random(), HttpDelivery.MediaFormat.Jpeg, false, bytes)

    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)
    private fun advanceElapsed(millis: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    }
}
