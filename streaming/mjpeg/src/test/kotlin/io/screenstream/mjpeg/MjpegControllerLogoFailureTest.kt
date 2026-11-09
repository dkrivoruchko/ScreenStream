package io.screenstream.mjpeg

import android.content.res.AssetManager
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowArscAssetManager14
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [MissingMjpegLogoShadow::class])
internal class MjpegControllerLogoFailureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Cases: MJPEG-CTL-11, MJPEG-CTL-13
    @Test
    fun aLogoAssetOpenFailureClosesTheModuleBeforeHttpActivationAndCleansStartedDiscovery() = runBlocking {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val host = MjpegServiceTestHost(MjpegSettings(context))
        try {
            withTimeout(5.seconds) { host.managerObservation.failed.await(); host.http.stopSettled.await(); host.monitor.closeSettled.await() }
            assertTrue(host.monitor.started.value)
            assertNull(host.http.configuration.value)
            assertEquals(0, host.http.startCalls.value)
            assertTrue(host.http.closed)
            host.controller.requestStreamStart()
            assertTrue(host.http.registeredCaptureAttempts.value.isEmpty())
            assertTrue(host.access.consentRequests.value.isEmpty())
            assertFalse(host.managerObservation.isCurrent())
        } finally {
            assertTrue(host.close())
        }
    }
}

@Implements(value = AssetManager::class, minSdk = 34, maxSdk = 34)
internal class MissingMjpegLogoShadow : ShadowArscAssetManager14() {
    @RealObject
    private lateinit var assetManager: AssetManager

    @Implementation
    protected fun open(fileName: String, accessMode: Int): InputStream {
        if (fileName == "mjpeg/screenstream-logo.jpg") throw FileNotFoundException("Missing fixture logo")
        return Shadow.directlyOn(
            assetManager, AssetManager::class.java, "open",
            ClassParameter.from(String::class.java, fileName),
            ClassParameter.from(Int::class.javaPrimitiveType!!, accessMode),
        )
    }
}
