package io.screenstream.mjpeg

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [FallibleScreenOffContextShadow::class])
internal class MjpegControllerScreenOffFailureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
        FallibleScreenOffContextShadow.registrationFailure = null
        FallibleScreenOffContextShadow.removalFailure = null
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-14
    @Test
    fun registrationFailureAtStartRejectsTheAttemptAndReportsARecoverableGenericError() = runBlocking<Unit> {
        val host = host(stopOnScreenOff = true)
        try {
            ready(host)
            FallibleScreenOffContextShadow.registrationFailure = SecurityException("Screen-off registration denied")
            host.controller.requestStreamStart()
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.error != null } }
            assertNull(host.managerHandle.status.value!!.error!!.message)
            assertTrue(host.http.captures.value.isEmpty())
            assertTrue(host.access.requested.value.isEmpty())
            assertTrue(host.managerHandle.isCurrent())
            assertFalse(host.managerHandle.failed.isCompleted)
            FallibleScreenOffContextShadow.registrationFailure = null
            host.controller.requestStreamStart()
            withTimeout(5.seconds) { host.access.requested.first { it.size == 1 } }
        } finally {
            FallibleScreenOffContextShadow.registrationFailure = null; assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-11, MJPEG-CTL-14
    @Test
    fun registrationFailureWhileEnablingThePreferenceStopsTheCurrentAttempt() = runBlocking {
        val host = host(stopOnScreenOff = false)
        try {
            ready(host)
            host.controller.requestStreamStart()
            val attempt = withTimeout(5.seconds) { host.access.requested.first { it.size == 1 }.single() }
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.captureAttempt == attempt } }
            FallibleScreenOffContextShadow.registrationFailure = SecurityException("Live screen-off registration denied")
            host.settings.updateData { copy(behavior = behavior.copy(stopOnScreenOff = true)) }
            withTimeout(5.seconds) { host.managerHandle.status.first { it != null && it.captureAttempt == null && it.error != null } }
            assertEquals(listOf(attempt), host.access.finishCalls.value)
            assertNull(host.managerHandle.status.value!!.error!!.message)
            assertFalse(host.http.closed)
            assertFalse(host.managerHandle.failed.isCompleted)
        } finally {
            FallibleScreenOffContextShadow.registrationFailure = null; assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-13, MJPEG-CTL-14
    @Test
    fun removalFailureRetainsTheErrorRejectsLaterStartAndMakesModuleCleanupUnsuccessful() = runBlocking {
        val host = host(stopOnScreenOff = true)
        try {
            ready(host)
            host.controller.requestStreamStart()
            val attempt = withTimeout(5.seconds) { host.access.requested.first { it.size == 1 }.single() }
            withTimeout(5.seconds) { host.managerHandle.status.first { it?.captureAttempt == attempt } }
            FallibleScreenOffContextShadow.removalFailure = IllegalStateException("Receiver removal failed")
            host.controller.requestStreamStop(attempt, false)
            withTimeout(5.seconds) { host.managerHandle.status.first { it != null && it.captureAttempt == null && it.error != null } }
            assertNull(host.managerHandle.status.value!!.error!!.message)
            assertFalse(host.http.closed)
            FallibleScreenOffContextShadow.removalFailure = null
            host.controller.requestStreamStart()
            checkpoint(host)
            assertEquals(listOf(attempt), host.http.captures.value)
            assertEquals(listOf(attempt), host.access.requested.value)
            assertFalse(host.managerHandle.failed.isCompleted)
        } finally {
            FallibleScreenOffContextShadow.removalFailure = null
            assertFalse(host.close())
            shadowOf(application).clearRegisteredReceivers()
        }
    }

    private suspend fun host(stopOnScreenOff: Boolean): MjpegServiceHost {
        val context = IsolatedSettingsContext(application, temporaryFolder.newFolder())
        val data = MjpegSettings.Data(web = WebPageSettings(title = "Screen-off contract"))
        seedSettings(context, Json.encodeToString(data.copy(behavior = data.behavior.copy(stopOnScreenOff = stopOnScreenOff))))
        return MjpegServiceHost(MjpegSettings(context))
    }

    private suspend fun ready(host: MjpegServiceHost) {
        withTimeout(5.seconds) { host.http.configuration.first { it != null } }
        host.monitor.state.value = observedAddresses(listOf(address))
        withTimeout(5.seconds) { host.http.plan.first { it?.addresses == listOf(address) } }
        checkpoint(host)
    }

    private suspend fun checkpoint(host: MjpegServiceHost) {
        val id = Uuid.random()
        host.http.info.value = host.http.info.value.copy(servers = listOf(HttpDelivery.ServerInfo(id, address, 8080, HttpDelivery.ServerState.Listening)))
        withTimeout(5.seconds) { host.controller.state.first { it.addressServers.singleOrNull()?.serverId == id } }
    }

    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val address = NetworkAddress(1, InetAddress.getByName("192.168.1.2"), "fixture1", InterfaceType.Wifi)
}

@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
internal class FallibleScreenOffContextShadow : ShadowContextImpl() {
    companion object {
        var registrationFailure: RuntimeException? = null
        var removalFailure: RuntimeException? = null
    }

    private val screenOffReceivers = mutableSetOf<BroadcastReceiver>()

    @Implementation
    protected override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        if (filter.hasAction(Intent.ACTION_SCREEN_OFF)) registrationFailure?.let { throw it }
        val result = super.registerReceiver(receiver, filter)
        if (filter.hasAction(Intent.ACTION_SCREEN_OFF) && receiver != null) screenOffReceivers += receiver
        return result
    }

    @Implementation
    protected override fun unregisterReceiver(receiver: BroadcastReceiver) {
        if (receiver in screenOffReceivers) removalFailure?.let { throw it }
        super.unregisterReceiver(receiver)
        screenOffReceivers -= receiver
    }
}
