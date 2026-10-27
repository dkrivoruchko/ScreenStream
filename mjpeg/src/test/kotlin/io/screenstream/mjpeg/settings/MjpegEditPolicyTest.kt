package io.screenstream.mjpeg.settings

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MjpegEditPolicyTest {
    // Cases: MJPEG-SET-09
    @Test
    fun liveOnlyAppliesLiveChangesWhileKeepingRunningSetup() {
        val current = MjpegSettings.Data(
            image = ImageSettings(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly),
            network = NetworkSettings(httpPort = 9000),
            access = AccessSettings(
                pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.Permanent, pin = SecretValue("000123"),
                hidePinOnCaptureStart = false, limitWrongPins = false, blockMinutes = 3,
            ),
            web = WebPageSettings(title = "Before"),
        )
        val proposed = current.copy(
            image = current.image.copy(jpegQuality = 60, jpegBackendPolicy = JpegBackendPolicy.Auto),
            network = NetworkSettings(
                filter = NetworkAddressFilter(setOf(AddressFamily.Ipv6), setOf(InterfaceType.Vpn), setOf(AddressCategory.NonPrivate)),
                httpPort = 9090,
            ),
            access = AccessSettings(),
            web = current.web.copy(title = "After"),
            behavior = current.behavior.copy(stopAfterNetworkLossSeconds = 10, postStopImage = StreamBehaviorSettings.PostStopImage.None),
        )
        val expectedLive = proposed.copy(
            image = proposed.image.copy(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly),
            network = proposed.network.copy(httpPort = 9000),
            access = AccessSettings(
                pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.Permanent, pin = SecretValue("000123"),
                hidePinOnCaptureStart = false, limitWrongPins = false, blockMinutes = 3,
            ),
        )
        assertEquals(proposed, MjpegSettings.EditPolicy.All.effective(current, proposed))
        assertTrue(MjpegSettings.EditPolicy.All.allows(current, proposed))
        assertEquals(expectedLive, MjpegSettings.EditPolicy.LiveOnly.effective(current, proposed))
        assertFalse(MjpegSettings.EditPolicy.LiveOnly.allows(current, proposed))
        assertTrue(MjpegSettings.EditPolicy.LiveOnly.allows(current, expectedLive))
        assertTrue(MjpegSettings.EditPolicy.LiveOnly.allows(current, current))
        for (restricted in listOf(
            current.copy(image = proposed.image.copy(jpegQuality = current.image.jpegQuality)),
            current.copy(network = current.network.copy(httpPort = 9090)),
            current.copy(access = proposed.access),
        )) assertFalse(MjpegSettings.EditPolicy.LiveOnly.allows(current, restricted))
    }
}
