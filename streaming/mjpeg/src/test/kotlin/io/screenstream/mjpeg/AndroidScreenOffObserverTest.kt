package io.screenstream.mjpeg

import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.screenstream.mjpeg.screenoff.AndroidScreenOffObserver
import io.screenstream.mjpeg.screenoff.AndroidScreenOffObserver.RegistrationResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
internal class AndroidScreenOffObserverTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    // Cases: MJPEG-CTL-14
    @Test
    fun bothInteractivityChecksRejectSleepingDisplayAndRetireTheInstalledReceiver() {
        for (screenOffBeforeRegistration in listOf(true, false)) {
            val context = RecordingReceiverContext()
            context.turnScreenOn(!screenOffBeforeRegistration)
            var registrationAttempts = 0
            context.beforeRegister = {
                registrationAttempts++
                context.turnScreenOn(false)
            }
            val observer = AndroidScreenOffObserver(context)
            try {
                assertEquals(RegistrationResult.ScreenOff, observer.register(Uuid.random()) { fail("No broadcast expected") })
                assertEquals(if (screenOffBeforeRegistration) 0 else 1, registrationAttempts)
                assertTrue(context.installed.isEmpty())
                context.beforeRegister = {}
                context.turnScreenOn(true)
                assertEquals(RegistrationResult.Registered, observer.register(Uuid.random()) {})
                assertEquals(1, context.installed.size)
            } finally {
                assertTrue(observer.close())
                context.clear()
            }
        }
    }

    // Cases: MJPEG-CTL-14
    @Test
    fun unregisterRequiresExactIdentityAndOnlyScreenOffBroadcastsNotify() {
        val context = RecordingReceiverContext()
        val observer = AndroidScreenOffObserver(context)
        val id = Uuid.random()
        var notifications = 0
        try {
            assertEquals(RegistrationResult.Registered, observer.register(id) { notifications++ })
            observer.unregister(Uuid.random())
            val receiver = context.installed.single()
            receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
            receiver.onReceive(context, null)
            assertEquals(0, notifications)
            context.turnScreenOn(false)
            assertEquals(0, notifications)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, notifications)
            assertFailsWith<IllegalStateException> { observer.register(Uuid.random()) {} }
            observer.unregister(id)
            observer.unregister(id)
            assertTrue(context.installed.isEmpty())
            context.turnScreenOn(true)
            assertEquals(RegistrationResult.Registered, observer.register(Uuid.random()) {})
        } finally {
            assertTrue(observer.close())
            context.clear()
        }
    }

    // Cases: MJPEG-CTL-14
    @Test
    fun failedRegistrationRollsBackWhetherAndroidInstalledTheReceiverOrNot() {
        for (installedBeforeFailure in listOf(false, true)) {
            val context = RecordingReceiverContext()
            val failure = SecurityException("Registration denied")
            context.beforeRegisterFailure = if (installedBeforeFailure) null else failure
            context.onRegister = { if (installedBeforeFailure) throw failure }
            val observer = AndroidScreenOffObserver(context)
            try {
                assertSame(failure, runCatching { observer.register(Uuid.random()) {} }.exceptionOrNull())
                assertTrue(context.installed.isEmpty())
                context.beforeRegisterFailure = null
                context.onRegister = {}
                assertEquals(RegistrationResult.Registered, observer.register(Uuid.random()) {})
            } finally {
                assertTrue(observer.close())
                context.clear()
            }
        }
    }

    // Cases: MJPEG-CTL-13, MJPEG-CTL-14
    @Test
    fun removalFailureRemainsAnUnsuccessfulReceiptAndPermanentlyRejectsRegistration() {
        val context = RecordingReceiverContext()
        val observer = AndroidScreenOffObserver(context)
        val failure = IllegalStateException("Receiver dispatcher removal failed")
        val id = Uuid.random()
        try {
            assertEquals(RegistrationResult.Registered, observer.register(id) {})
            context.removeFailure = failure
            assertSame(failure, runCatching { observer.unregister(id) }.exceptionOrNull())
            assertEquals(1, context.installed.size)
            assertSame(failure, runCatching { observer.register(Uuid.random()) {} }.exceptionOrNull())
            assertFalse(observer.close())
            assertFalse(observer.close())
        } finally {
            context.removeFailure = null
            context.clear()
        }
    }

    // Cases: MJPEG-CTL-12, MJPEG-CTL-14
    @Test
    fun closeWinsBeforeRegistrationAndWaitsForAnEnteredRegistrationBeforeRemovingIt() {
        val closed = AndroidScreenOffObserver(RecordingReceiverContext())
        assertTrue(closed.close())
        assertEquals(RegistrationResult.Rejected, closed.register(Uuid.random()) { fail("Observer is closed") })

        val context = RecordingReceiverContext()
        val observer = AndroidScreenOffObserver(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        context.onRegister = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            val registration = executor.submit<RegistrationResult> { observer.register(Uuid.random()) {} }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val cleanup = executor.submit<Boolean> { closing.countDown(); observer.close() }
            assertTrue(closing.await(10, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { cleanup.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1, context.installed.size)
            assertFalse(registration.isDone)
            release.countDown()
            assertEquals(RegistrationResult.Registered, registration.get(10, TimeUnit.SECONDS))
            assertTrue(cleanup.get(10, TimeUnit.SECONDS))
            assertTrue(context.installed.isEmpty())
            assertEquals(RegistrationResult.Rejected, observer.register(Uuid.random()) {})
        } finally {
            release.countDown()
            try {
                observer.close()
                context.clear()
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private class RecordingReceiverContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val installed = java.util.concurrent.CopyOnWriteArrayList<BroadcastReceiver>()
        var beforeRegister: () -> Unit = {}
        var onRegister: () -> Unit = {}
        var beforeRegisterFailure: RuntimeException? = null
        var removeFailure: RuntimeException? = null

        init {
            turnScreenOn(true)
        }

        fun turnScreenOn(value: Boolean) {
            shadowOf(getSystemService(PowerManager::class.java)).turnScreenOn(value)
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            beforeRegisterFailure?.let { throw it }
            beforeRegister()
            val result = ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            installed += checkNotNull(receiver)
            onRegister()
            return result
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            removeFailure?.let { throw it }
            super.unregisterReceiver(receiver)
            installed -= receiver
        }

        fun clear() {
            installed.toList().forEach { super.unregisterReceiver(it); installed -= it }
        }
    }
}
