package io.screenstream.mjpeg.settings

import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameRate
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MjpegSettingsEditPolicyTest {
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
            behavior = current.behavior.copy(stopAfterNetworkLossSeconds = 10, postStopImage = StreamBehaviorSettings.PostStopImage.LastAvailableFrame),
        )
        val expectedLive = proposed.copy(
            image = proposed.image.copy(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly),
            network = proposed.network.copy(httpPort = 9000),
            access = AccessSettings(
                pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.Permanent, pin = SecretValue("000123"),
                hidePinOnCaptureStart = true, limitWrongPins = false, blockMinutes = 3,
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

    // Cases: MJPEG-SET-09
    @Test
    fun everyRestrictedDeltaIsRejectedAndEveryLiveFieldRemainsEditable() {
        val current = MjpegSettings.Data(web = WebPageSettings(title = "Before"))
        val restricted = listOf(
            current.copy(image = current.image.copy(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly)),
            current.copy(network = current.network.copy(httpPort = 9000)),
            current.copy(access = current.access.copy(pinEnabled = true)),
            current.copy(access = current.access.copy(pinPolicy = AccessSettings.PinPolicy.Permanent)),
            current.copy(access = current.access.copy(pin = SecretValue("001234"))),
            current.copy(access = current.access.copy(limitWrongPins = false)),
            current.copy(access = current.access.copy(blockMinutes = 7)),
        )
        for (proposed in restricted) {
            assertTrue(MjpegSettings.EditPolicy.All.allows(current, proposed))
            assertEquals(proposed, MjpegSettings.EditPolicy.All.effective(current, proposed))
            assertFalse(MjpegSettings.EditPolicy.LiveOnly.allows(current, proposed))
            assertEquals(current, MjpegSettings.EditPolicy.LiveOnly.effective(current, proposed))
        }
        val liveImages = listOf(
            current.image.copy(sourceRegion = SourceRegion.LeftHalf),
            current.image.copy(cropEnabled = true),
            current.image.copy(cropInsets = CropInsetsPx(1, 2, 3, 4)),
            current.image.copy(scalePercent = 75.0),
            current.image.copy(targetSize = OutputSize.TargetSize(640, 360)),
            current.image.copy(outputSelection = ImageSettings.OutputSelection.TargetSize, targetSize = OutputSize.TargetSize(640, 360)),
            current.image.copy(rotation = Rotation.Degrees90),
            current.image.copy(mirror = Mirror.Horizontal),
            current.image.copy(colorMode = ColorMode.Grayscale),
            current.image.copy(jpegQuality = 42),
            current.image.copy(frameRate = FrameRate.MaxFps(24)),
        )
        val live = liveImages.map { current.copy(image = it) } + listOf(
            current.copy(
                network = current.network.copy(
                    filter = NetworkAddressFilter(
                        setOf(AddressFamily.Ipv6), setOf(InterfaceType.Vpn), setOf(AddressCategory.NonPrivate),
                    )
                )
            ),
            current.copy(access = current.access.copy(hidePinOnCaptureStart = false)),
            current.copy(web = current.web.copy(background = "#AABBCC")),
            current.copy(web = current.web.copy(title = "After")),
            current.copy(web = current.web.copy(titleEnabled = false)),
            current.copy(web = current.web.copy(titleLayout = current.web.titleLayout.copy(position = WebPageSettings.Edge.Bottom))),
            current.copy(web = current.web.copy(controlsLayout = current.web.controlsLayout.copy(presentation = WebPageSettings.Presentation.Bar))),
            current.copy(behavior = current.behavior.copy(keepScreenAwake = true)),
            current.copy(behavior = current.behavior.copy(stopOnScreenOff = true)),
            current.copy(behavior = current.behavior.copy(stopAfterNetworkLossSeconds = 10)),
            current.copy(behavior = current.behavior.copy(postStopImage = StreamBehaviorSettings.PostStopImage.LastAvailableFrame)),
            current.copy(behavior = current.behavior.copy(notifySlowViewers = false)),
        )
        for (proposed in live) {
            for (policy in MjpegSettings.EditPolicy.entries) {
                assertTrue(policy.allows(current, proposed))
                assertEquals(proposed, policy.effective(current, proposed))
            }
        }
        assertTrue(MjpegSettings.EditPolicy.All.canEditStreamSetup)
        assertFalse(MjpegSettings.EditPolicy.LiveOnly.canEditStreamSetup)
    }
}
