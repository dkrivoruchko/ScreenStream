package io.screenstream.mjpeg.networkaddress

import android.content.Context
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
        val fixture = ScanCadenceFixture(testScheduler)
        try {
            fixture.startAndObserveFirstScan(enumerationComplete = false)
            for (delay in listOf(250L, 1_000L, 3_000L, 30_000L)) {
                fixture.observeAfter(delay, enumerationComplete = false)
            }
            fixture.observeAfter(30_000L, enumerationComplete = true)
            fixture.observeAfter(30_000L, enumerationComplete = false)
            fixture.observeAfter(250L, enumerationComplete = false)
            assertEquals(94_500L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-09
    @Test
    fun platformChangeWhileIdleRestartsAnExhaustedIncompleteBackoff() = runTest(timeout = 20.seconds) {
        val fixture = ScanCadenceFixture(testScheduler)
        try {
            fixture.startAndObserveFirstScan(enumerationComplete = false)
            for (delay in listOf(250L, 1_000L, 3_000L)) fixture.observeAfter(delay, enumerationComplete = false)
            val immediate = fixture.enqueue(enumerationComplete = false)
            fixture.callback.onAvailable(ShadowNetwork.newInstance(901))
            fixture.awaitEntryAtCurrentTime(immediate)
            fixture.releaseAndAwaitObservation(immediate)
            fixture.observeAfter(250L, enumerationComplete = false)
            assertEquals(4_500L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-08, MJPEG-NET-09
    @Test
    fun platformChangeDuringHeldScanRescansImmediatelyWithoutResettingItsBackoff() = runTest(timeout = 20.seconds) {
        val fixture = ScanCadenceFixture(testScheduler)
        try {
            fixture.startAndObserveFirstScan(enumerationComplete = false)
            val previous = fixture.observeAfter(250L, enumerationComplete = false)
            val stale = fixture.enqueue(enumerationComplete = false)
            fixture.awaitEntryAfter(1_000L, stale)
            val current = fixture.enqueue(enumerationComplete = false)
            fixture.callback.onAvailable(ShadowNetwork.newInstance(902))
            testScheduler.runCurrent()
            assertFalse("A platform change cannot start another native read while one is held", current.entered.await(100, TimeUnit.MILLISECONDS))
            fixture.releaseAndAwaitObservation(stale)
            fixture.awaitScanEntry(current)
            val staleSelection = fixture.selectedAddresses()
            assertEquals(setOf(previous.ip, stale.ip), staleSelection.map { it.ip }.toSet())
            fixture.releaseAndAwaitObservation(current)
            fixture.observeAfter(3_000L, enumerationComplete = false)
            assertEquals(4_250L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-09
    @Test
    @Config(sdk = [36, 37], shadows = [ObservationTetheringShadow::class])
    fun nativeCompleteScansRetryUntilTetheringMetadataIsReadyThenUsePeriodicRefresh() = runTest(timeout = 20.seconds) {
        val context = tetheringTestContext()
        val tether = Shadow.extract<ObservationTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        val fixture = ScanCadenceFixture(testScheduler, context)
        try {
            fixture.startAndObserveFirstScan(enumerationComplete = true)
            fixture.observeAfter(250L, enumerationComplete = true)
            val ready = fixture.enqueue(enumerationComplete = true)
            val callback = checkNotNull(tether.registeredCallback)
            checkNotNull(tether.registeredExecutor).execute { callback.onTetheredInterfacesChanged(emptySet()) }
            fixture.awaitEntryAtCurrentTime(ready)
            fixture.releaseAndAwaitObservation(ready)
            fixture.observeAfter(30_000L, enumerationComplete = true)
            assertEquals(30_250L, testScheduler.currentTime)
        } finally {
            fixture.close()
        }
    }

    private inner class ScanCadenceFixture(
        val scheduler: TestCoroutineScheduler,
        context: Context = RuntimeEnvironment.getApplication(),
    ) {
        private val pendingScans = ConcurrentLinkedQueue<ScanStep>()
        private val steps = mutableListOf<ScanStep>()
        private val sequence = AtomicInteger()
        private val initialUptimeMillis = SystemClock.uptimeMillis()
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private val connectivityShadow = shadowOf(connectivity).also { it.clearAllNetworks() }
        val callback: ConnectivityManager.NetworkCallback get() = connectivityShadow.networkCallbacks.single()
        val monitor = AndroidNetworkAddressMonitor(
            context,
            NativeInterfaceFixture(enumeration = {
                val step = pendingScans.poll() ?: throw AssertionError("Native scan entered without a supplied step")
                step.enumerate()
            }),
            observationDispatcher = StandardTestDispatcher(scheduler),
        )

        fun enqueue(enumerationComplete: Boolean): ScanStep = ScanStep(sequence.incrementAndGet(), enumerationComplete).also {
            steps += it
            pendingScans += it
        }

        fun startAndObserveFirstScan(enumerationComplete: Boolean) {
            val first = enqueue(enumerationComplete)
            monitor.start()
            awaitEntryAtCurrentTime(first)
            releaseAndAwaitObservation(first)
        }

        fun observeAfter(delayMillis: Long, enumerationComplete: Boolean): ScanStep = enqueue(enumerationComplete).also {
            awaitEntryAfter(delayMillis, it)
            releaseAndAwaitObservation(it)
        }

        fun awaitEntryAfter(delayMillis: Long, step: ScanStep) {
            advanceClocksBy(delayMillis - 1)
            assertFalse("A native scan entered before its monotonic deadline", step.entered.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1L, step.release.count)
            advanceClocksBy(1)
            awaitScanEntry(step)
        }

        fun awaitEntryAtCurrentTime(step: ScanStep) {
            scheduler.runCurrent()
            awaitScanEntry(step)
        }

        fun awaitScanEntry(step: ScanStep) {
            assertTrue("Expected native scan did not enter", step.entered.await(5, TimeUnit.SECONDS))
            assertEquals(initialUptimeMillis + scheduler.currentTime, step.enteredAtUptimeMillis)
        }

        fun releaseAndAwaitObservation(step: ScanStep) {
            step.release.countDown()
            assertTrue("Supplied native read did not complete", step.readFinished.await(5, TimeUnit.SECONDS))
            runCurrentUntil(scheduler) { selectedAddresses().any { it.ip == step.ip } }
            assertEquals(initialUptimeMillis + scheduler.currentTime, SystemClock.uptimeMillis())
        }

        fun selectedAddresses(): List<NetworkAddress> = (monitor.state.value as? NetworkAddressMonitor.State.Observed)
            ?.inventory?.select(allAddressesFilter).orEmpty()

        private fun advanceClocksBy(millis: Long) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
            assertEquals(initialUptimeMillis + scheduler.currentTime, SystemClock.uptimeMillis())
        }

        suspend fun close(): Unit = withContext(NonCancellable) {
            steps.forEach { it.release.countDown() }
            coroutineScope {
                val closing = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }
                runCurrentUntil(scheduler) { closing.isCompleted }
                closing.await()
            }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
        }
    }

    private class ScanStep(number: Int, private val enumerationComplete: Boolean) {
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
                    if (!enumerationComplete) throw SocketException("Supplied incomplete enumeration")
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

    private fun runCurrentUntil(scheduler: TestCoroutineScheduler, condition: () -> Boolean) {
        val deadline = hostUptimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (true) {
            scheduler.runCurrent()
            if (condition()) return
            check(hostUptimeMillis() < deadline) { "Current-time observation did not reach its external checkpoint" }
            Thread.yield()
        }
    }

    private fun hostUptimeMillis(): Long = ManagementFactory.getRuntimeMXBean().uptime

    private val allAddressesFilter = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
}
