package io.screenstream.mjpeg.networkaddress

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.TetheringManager
import android.os.SystemClock
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowSystemClock
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.SocketException
import java.time.Duration
import java.util.Enumeration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], instrumentedPackages = ["io.screenstream.mjpeg.networkaddress"])
@LooperMode(LooperMode.Mode.PAUSED)
internal class AndroidNetworkAddressCadenceTest {
    @Before
    fun initializeLogging() {
        XLog.init(LogLevel.NONE)
    }

    // Cases: MJPEG-NET-06
    @Test
    fun incompleteBackoffBecomesPeriodicAndCompleteObservationRestartsTheRetrySequence() = runTest(timeout = 20.seconds) {
        val fixture = Fixture(testScheduler)
        try {
            fixture.startAndApplyFirst(complete = false)
            for (delay in listOf(250L, 1_000L, 3_000L, 30_000L)) {
                fixture.applyAfter(delay, complete = false)
            }
            fixture.applyAfter(30_000L, complete = true)
            fixture.applyAfter(30_000L, complete = false)
            fixture.applyAfter(250L, complete = false)
            assertEquals(94_500L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-09
    @Test
    fun platformChangeWhileIdleRestartsAnExhaustedIncompleteBackoff() = runTest(timeout = 20.seconds) {
        val fixture = Fixture(testScheduler)
        try {
            fixture.startAndApplyFirst(complete = false)
            for (delay in listOf(250L, 1_000L, 3_000L)) fixture.applyAfter(delay, complete = false)
            val immediate = fixture.enqueue(complete = false)
            fixture.callback.onAvailable(ShadowNetwork.newInstance(901))
            fixture.enterAtCurrentTime(immediate)
            fixture.apply(immediate)
            fixture.applyAfter(250L, complete = false)
            assertEquals(4_500L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-08, MJPEG-NET-09
    @Test
    fun platformChangeDuringHeldScanRescansImmediatelyWithoutResettingItsBackoff() = runTest(timeout = 20.seconds) {
        val fixture = Fixture(testScheduler)
        try {
            fixture.startAndApplyFirst(complete = false)
            val previous = fixture.applyAfter(250L, complete = false)
            val stale = fixture.enqueue(complete = false)
            fixture.enterAfter(1_000L, stale)
            val current = fixture.enqueue(complete = false)
            fixture.callback.onAvailable(ShadowNetwork.newInstance(902))
            testScheduler.runCurrent()
            assertFalse("A platform change cannot start another native read while one is held", current.entered.await(100, TimeUnit.MILLISECONDS))
            fixture.apply(stale)
            fixture.awaitEntry(current)
            val staleSelection = fixture.selection()
            assertEquals(setOf(previous.ip, stale.ip), staleSelection.map { it.ip }.toSet())
            fixture.apply(current)
            fixture.applyAfter(3_000L, complete = false)
            assertEquals(4_250L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-09
    @Test
    @Config(sdk = [36, 37], shadows = [SourceTetheringShadow::class])
    fun nativeCompleteScansRetryUntilTetheringMetadataIsReadyThenUsePeriodicRefresh() = runTest(timeout = 20.seconds) {
        val context = tetheringContext()
        val tether = Shadow.extract<SourceTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        val fixture = Fixture(testScheduler, context)
        try {
            fixture.startAndApplyFirst(complete = true)
            fixture.applyAfter(250L, complete = true)
            val ready = fixture.enqueue(complete = true)
            val callback = checkNotNull(tether.registeredCallback)
            checkNotNull(tether.registeredExecutor).execute { callback.onTetheredInterfacesChanged(emptySet()) }
            fixture.enterAtCurrentTime(ready)
            fixture.apply(ready)
            fixture.applyAfter(30_000L, complete = true)
            assertEquals(30_250L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture(
        val scheduler: TestCoroutineScheduler,
        context: Context = RuntimeEnvironment.getApplication(),
    ) {
        private val queued = ConcurrentLinkedQueue<ScanStep>()
        private val steps = mutableListOf<ScanStep>()
        private val sequence = AtomicInteger()
        private val initialUptime = SystemClock.uptimeMillis()
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private val platform = shadowOf(connectivity).also { it.clearAllNetworks() }
        val callback: ConnectivityManager.NetworkCallback get() = platform.networkCallbacks.single()
        val monitor = AndroidNetworkAddressMonitor(
            context,
            NativeInterfaceFixture(enumeration = {
                val step = queued.poll() ?: throw AssertionError("Native scan entered without a supplied step")
                step.enumerate()
            }),
            observationDispatcher = StandardTestDispatcher(scheduler),
        )

        fun enqueue(complete: Boolean): ScanStep = ScanStep(sequence.incrementAndGet(), complete).also {
            steps += it
            queued += it
        }

        fun startAndApplyFirst(complete: Boolean) {
            val first = enqueue(complete)
            monitor.start()
            enterAtCurrentTime(first)
            apply(first)
        }

        fun applyAfter(delayMillis: Long, complete: Boolean): ScanStep = enqueue(complete).also {
            enterAfter(delayMillis, it)
            apply(it)
        }

        fun enterAfter(delayMillis: Long, step: ScanStep) {
            advance(delayMillis - 1)
            assertFalse("A native scan entered before its monotonic deadline", step.entered.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1L, step.release.count)
            advance(1)
            awaitEntry(step)
        }

        fun enterAtCurrentTime(step: ScanStep) {
            scheduler.runCurrent()
            awaitEntry(step)
        }

        fun awaitEntry(step: ScanStep) {
            assertTrue("Expected native scan did not enter", step.entered.await(5, TimeUnit.SECONDS))
            assertEquals(initialUptime + scheduler.currentTime, step.enteredAtUptimeMillis)
        }

        fun apply(step: ScanStep) {
            step.release.countDown()
            assertTrue("Supplied native read did not complete", step.readFinished.await(5, TimeUnit.SECONDS))
            drainUntil(scheduler) { selection().any { it.ip == step.ip } }
            assertEquals(initialUptime + scheduler.currentTime, SystemClock.uptimeMillis())
        }

        fun selection(): List<NetworkAddress> = (monitor.state.value as? NetworkAddressMonitor.State.Observed)
            ?.inventory?.select(all).orEmpty()

        private fun advance(millis: Long) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
            assertEquals(initialUptime + scheduler.currentTime, SystemClock.uptimeMillis())
        }

        suspend fun close(): Unit = withContext(NonCancellable) {
            steps.forEach { it.release.countDown() }
            coroutineScope {
                val closing = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }
                drainUntil(scheduler) { closing.isCompleted }
                closing.await()
            }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    private class ScanStep(number: Int, private val complete: Boolean) {
        val ip: InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, number.toByte()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readFinished = CountDownLatch(1)

        @Volatile
        var enteredAtUptimeMillis: Long = -1
            private set

        fun enumerate(): Enumeration<NativeInterfaceAccess.Handle> {
            enteredAtUptimeMillis = SystemClock.uptimeMillis()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Native input was not released" }
            return object : Enumeration<NativeInterfaceAccess.Handle> {
                private var supplied = false
                override fun hasMoreElements(): Boolean {
                    if (!supplied) return true
                    readFinished.countDown()
                    if (!complete) throw SocketException("Supplied incomplete enumeration")
                    return false
                }

                override fun nextElement(): NativeInterfaceAccess.Handle {
                    check(!supplied)
                    supplied = true
                    return nativeInterface("wlan-cadence0", listOf(ip))
                }
            }
        }
    }

    private fun drainUntil(scheduler: TestCoroutineScheduler, condition: () -> Boolean) {
        val deadline = hostUptimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (true) {
            scheduler.runCurrent()
            if (condition()) return
            check(hostUptimeMillis() < deadline) { "Current-time observation did not reach its external checkpoint" }
            Thread.yield()
        }
    }

    private fun hostUptimeMillis(): Long = ManagementFactory.getRuntimeMXBean().uptime

    private fun tetheringContext(): Context {
        val application: Context = RuntimeEnvironment.getApplication()
        val manager = Shadow.newInstanceOf(TetheringManager::class.java)
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSystemServiceName(serviceClass: Class<*>): String? =
                if (serviceClass == TetheringManager::class.java) TETHERING_SERVICE else super.getSystemServiceName(serviceClass)

            override fun getSystemService(name: String): Any? =
                if (name == TETHERING_SERVICE) manager else super.getSystemService(name)
        }
    }

    private val all = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
}
