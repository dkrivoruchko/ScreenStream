package io.screenstream.mjpeg

import android.content.Context
import android.content.ContextWrapper
import com.elvishew.xlog.XLog
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegControllerSettingsTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val inputs = MjpegControllerTestInputs(temporaryFolder)

    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CTL-05
    @Test
    fun busySavedSetupRemainsDeferredWhileLiveAppearanceAndFilterApply() = runBlocking {
        val originalPin = SecretValue("000007")
        val settings = inputs.createSettings(
            MjpegSettings.Data(
                access = AccessSettings(true, AccessSettings.PinPolicy.Permanent, originalPin),
                web = WebPageSettings(title = "Fixture device"),
            ),
        )
        val host = MjpegServiceTestHost(settings)
        try {
            inputs.startModuleWithReadyListener(host)
            host.controller.requestStreamStart()
            val attempt = host.access.consentRequests.awaitControllerValue { it.size == 1 }.single()
            val originalAccess = host.http.configuration.value!!.access
            val pendingUi = host.controller.state.awaitControllerValue { it.action == UiController.Action.Busy }
            assertEquals(originalPin, pendingUi.appliedPin)
            assertTrue(pendingUi.hidePinOnCapture)
            settings.updateData {
                copy(
                    image = image.copy(jpegQuality = 45, jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly),
                    network = network.copy(filter = inputs.vpnNonPrivateIpv4Filter(), httpPort = 9000),
                    access = AccessSettings(true, AccessSettings.PinPolicy.Permanent, SecretValue("001234")),
                    web = web.copy(title = "Live appearance"),
                )
            }
            host.http.planApplications.awaitControllerValue { it.lastOrNull()?.addresses?.isEmpty() == true }
            val busyConfiguration = host.http.configuration.awaitControllerValue { it?.web?.title == "Live appearance" }!!
            assertEquals(originalAccess, busyConfiguration.access)
            assertEquals(8080, host.http.plan.value!!.port)
            assertEquals(9000, settings.data.first().network.httpPort)
            val busyUi = host.controller.state.awaitControllerValue { it.addressServers.isEmpty() }
            assertEquals(busyConfiguration.access.pin, busyUi.appliedPin)
            assertTrue(busyUi.hidePinOnCapture)
            host.controller.requestStreamStop(attempt, false)
            host.http.configuration.awaitControllerValue { it?.access?.pin == SecretValue("001234") }
            host.http.plan.awaitControllerValue { it?.port == 9000 }
            assertEquals(settings.data.first().access, host.http.configuration.value!!.access)
            assertEquals("Live appearance", host.http.configuration.value!!.web.title)
            val idleUi = host.controller.state.awaitControllerValue { it.action is UiController.Action.Start }
            assertEquals(SecretValue("001234"), idleUi.appliedPin)
            assertFalse(idleUi.hidePinOnCapture)
        } finally {
            assertTrue(host.close())
        }
    }

    // Cases: MJPEG-CTL-06
    @Test
    fun openingModuleAppliesValidPinAndPermanentPolicyPreservesSavedValue() = runBlocking {
        for (policy in AccessSettings.PinPolicy.entries) {
            val saved = MjpegSettings.Data(
                access = AccessSettings(true, policy, SecretValue("000007")), web = WebPageSettings(title = "Protected"),
            )
            val settings = inputs.createSettings(saved)
            val host = MjpegServiceTestHost(settings)
            try {
                inputs.startModuleWithReadyListener(host)
                val applied = host.http.configuration.value!!.access
                assertTrue(AccessSettings.isValidPin(applied.pin!!.value))
                assertEquals(settings.data.first().access, applied)
                if (policy == AccessSettings.PinPolicy.Permanent) assertEquals("000007", applied.pin.value)
                host.controller.requestStreamStart()
                val attempt = host.access.consentRequests.awaitControllerValue { it.size == 1 }.single()
                host.controller.requestStreamStop(attempt, false)
                host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
                host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
                host.controller.startModule()
                inputs.applySettingsCheckpoint(settings, host, "Same module")
                assertEquals(applied, settings.data.first().access)
                assertEquals(applied, host.http.configuration.value!!.access)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-06
    @Test
    fun enablingProtectionAfterOpenModulePreparationGeneratesMissingPinAndRetainsExistingPin() = runBlocking {
        for (savedPin in listOf(null, SecretValue("000007"))) {
            val initialAccess = AccessSettings(
                pinEnabled = false,
                pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart,
                pin = savedPin,
            )
            val settings = inputs.createSettings(MjpegSettings.Data(access = initialAccess, web = WebPageSettings(title = "Initially open")))
            val host = MjpegServiceTestHost(settings)
            try {
                inputs.startModuleWithReadyListener(host)
                assertEquals(initialAccess, host.http.configuration.value!!.access)
                settings.updateData { copy(access = access.copy(pinEnabled = true)) }
                val applied = host.http.configuration.awaitControllerValue { it?.access?.pinEnabled == true }!!.access
                assertTrue(AccessSettings.isValidPin(applied.pin!!.value))
                assertEquals(settings.data.first().access, applied)
                if (savedPin != null) assertEquals(savedPin, applied.pin)
                host.controller.requestStreamStart()
                val attempt = host.access.consentRequests.awaitControllerValue { it.size == 1 }.single()
                assertEquals(applied, host.http.configuration.value!!.access)
                host.controller.requestStreamStop(attempt, false)
                host.http.captureStatus.awaitControllerValue { it?.first == attempt && it.second == MjpegCaptureSession.State.Status.Stopped }
                host.managerObservation.status.awaitControllerValue { it?.captureAttempt == null }
                host.controller.startModule()
                inputs.applySettingsCheckpoint(settings, host, "Protection remains prepared")
                assertEquals(applied, settings.data.first().access)
                assertEquals(applied, host.http.configuration.value!!.access)
            } finally {
                assertTrue(host.close())
            }
        }
    }

    // Cases: MJPEG-CTL-06, MJPEG-CTL-11
    @Test
    fun failedOpeningSettingsFileInitializationClosesTheModuleBeforeHttpActivation() = runBlocking {
        val base = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val fileAccessDenied = java.util.concurrent.atomic.AtomicBoolean(false)
        val deniedFileAccess = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                if (fileAccessDenied.get()) {
                    deniedFileAccess.complete(Unit)
                    throw SecurityException("Settings files access denied")
                }
                return base.filesDir
            }
        }
        val data = MjpegSettings.Data(
            web = WebPageSettings(title = "Opening PIN failure"),
            access = AccessSettings(pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = SecretValue("123456")),
        )
        seedSettings(context, Json.encodeToString(data))
        val settings = MjpegSettings(context)
        assertFalse(deniedFileAccess.isCompleted)
        fileAccessDenied.set(true)
        val host = MjpegServiceTestHost(settings)
        try {
            withTimeout(5.seconds) {
                deniedFileAccess.await()
                host.managerObservation.failed.await()
                host.http.stopEntered.await()
                host.monitor.closeEntered.await()
            }
            assertNull(host.http.configuration.value)
            assertFalse(host.monitor.started.value)
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            assertEquals("123456", Json.decodeFromString<MjpegSettings.Data>(readSavedSettingsJson(base)).access.pin!!.value)
        } finally {
            assertTrue(host.close())
        }
    }
}
