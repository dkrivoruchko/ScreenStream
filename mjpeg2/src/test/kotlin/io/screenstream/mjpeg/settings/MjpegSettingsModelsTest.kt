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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

internal class MjpegSettingsModelsTest {
    private val json = Json { encodeDefaults = true }

    // Cases: MJPEG-SET-01
    @Test
    fun productDefaultsDescribeOpenHalfScaleLocalStreaming() {
        val actual = MjpegSettings.Data(web = WebPageSettings(title = "Fixture device"))
        assertEquals(
            ImageSettings(
                sourceRegion = SourceRegion.Full, cropEnabled = false, cropInsets = CropInsetsPx(0, 0, 0, 0),
                outputSelection = ImageSettings.OutputSelection.Scale, scalePercent = 50.0, targetSize = null,
                rotation = Rotation.Degrees0, mirror = Mirror.None, colorMode = ColorMode.Color, jpegQuality = 80,
                frameRate = FrameRate.Auto, jpegBackendPolicy = JpegBackendPolicy.Auto,
            ),
            actual.image,
        )
        assertEquals(
            NetworkSettings(
                NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Wifi, InterfaceType.Ethernet), setOf(AddressCategory.Private)),
                httpPort = 8080,
            ),
            actual.network,
        )
        assertEquals(
            AccessSettings(
                pinEnabled = false, pinPolicy = AccessSettings.PinPolicy.NewOnModuleStart, pin = null,
                hidePinOnCaptureStart = true, limitWrongPins = true, blockMinutes = 1,
            ),
            actual.access,
        )
        assertEquals(
            WebPageSettings(
                background = "#101418", title = "Fixture device", titleEnabled = true,
                titleLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Top, WebPageSettings.Presentation.Overlay, true),
                controlsLayout = WebPageSettings.BlockLayout(WebPageSettings.Edge.Bottom, WebPageSettings.Presentation.Overlay, true),
                retainImageOnMediaFailure = true,
            ),
            actual.web,
        )
        assertEquals(
            StreamBehaviorSettings(
                keepScreenAwake = false, stopOnScreenOff = false, stopAfterNetworkLossSeconds = 60,
                postStopImage = StreamBehaviorSettings.PostStopImage.Placeholder, notifySlowViewers = true,
            ),
            actual.behavior,
        )
    }

    // Cases: MJPEG-SET-01
    @Test
    fun settingsRejectInvalidInputsAndAcceptBoundaryValues() {
        for (scale in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, Double.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ImageSettings(scalePercent = scale) }
        }
        assertEquals(250.0, ImageSettings(scalePercent = 250.0).scalePercent, 0.0)
        for (quality in listOf(-1, 101)) assertThrows(IllegalArgumentException::class.java) { ImageSettings(jpegQuality = quality) }
        for (quality in listOf(0, 100)) assertEquals(quality, ImageSettings(jpegQuality = quality).jpegQuality)
        assertThrows(IllegalArgumentException::class.java) { ImageSettings(outputSelection = ImageSettings.OutputSelection.TargetSize) }
        val target = OutputSize.TargetSize(640, 480)
        assertEquals(target, ImageSettings(outputSelection = ImageSettings.OutputSelection.TargetSize, targetSize = target).targetSize)
        val defaultFilter = NetworkSettings().filter
        for (filter in listOf(
            NetworkAddressFilter(emptySet(), defaultFilter.interfaceTypes, defaultFilter.categories),
            NetworkAddressFilter(defaultFilter.families, emptySet(), defaultFilter.categories),
            NetworkAddressFilter(defaultFilter.families, defaultFilter.interfaceTypes, emptySet()),
        )) assertThrows(IllegalArgumentException::class.java) { NetworkSettings(filter = filter) }
        for (port in listOf(1023, 65536)) assertThrows(IllegalArgumentException::class.java) { NetworkSettings(httpPort = port) }
        for (port in listOf(1024, 65535)) assertEquals(port, NetworkSettings(httpPort = port).httpPort)
        for (pin in listOf("12345", "1234567", "12 456", "１２３４５６", "abcdef")) {
            assertFalse(AccessSettings.isValidPin(pin))
            assertThrows(IllegalArgumentException::class.java) { AccessSettings(pin = SecretValue(pin)) }
        }
        assertTrue(AccessSettings.isValidPin("000007"))
        assertThrows(IllegalArgumentException::class.java) { AccessSettings(blockMinutes = 0) }
        assertEquals(Int.MAX_VALUE, AccessSettings(blockMinutes = Int.MAX_VALUE).blockMinutes)
        for (color in listOf("101418", "#123", "#12345678", "#12345g", " #101418")) {
            assertThrows(IllegalArgumentException::class.java) { WebPageSettings(title = "", background = color) }
        }
        assertEquals("#aAbBcC", WebPageSettings(title = "", background = "#aAbBcC").background)
        for (timeout in listOf(9, 1801)) assertThrows(IllegalArgumentException::class.java) { StreamBehaviorSettings(stopAfterNetworkLossSeconds = timeout) }
        for (timeout in listOf(10, 1800)) assertEquals(timeout, StreamBehaviorSettings(stopAfterNetworkLossSeconds = timeout).stopAfterNetworkLossSeconds)
    }

    // Cases: MJPEG-SET-02
    @Test
    fun imageSavedSchemaRetainsDisabledCropAndUnselectedTarget() {
        val fixture =
            """{"sourceRegion":"RightHalf","cropEnabled":false,"cropInsets":{"left":1,"top":2,"right":3,"bottom":4},"outputSelection":"Scale","scalePercent":75.0,"targetSize":{"widthPx":320,"heightPx":240,"contentMode":"Stretch"},"rotation":"Degrees90","mirror":"Horizontal","colorMode":"Grayscale","jpegQuality":91,"frameRate":{"type":"SamplingInterval","interval":"PT2S"},"jpegBackendPolicy":"FrameworkOnly"}"""
        val settings = json.decodeFromString<ImageSettings>(fixture)
        assertEquals(
            ImageSettings(
                sourceRegion = SourceRegion.RightHalf, cropEnabled = false, cropInsets = CropInsetsPx(1, 2, 3, 4),
                outputSelection = ImageSettings.OutputSelection.Scale, scalePercent = 75.0,
                targetSize = OutputSize.TargetSize(320, 240, OutputSize.ContentMode.Stretch),
                rotation = Rotation.Degrees90, mirror = Mirror.Horizontal, colorMode = ColorMode.Grayscale,
                jpegQuality = 91, frameRate = FrameRate.SamplingInterval(2.seconds), jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
            ),
            settings,
        )
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(json.encodeToString(settings)))
        assertEquals(
            OutputSize.TargetSize(640, 360, OutputSize.ContentMode.AspectFit),
            json.decodeFromString<ImageSettings>("""{"targetSize":{"widthPx":640,"heightPx":360}}""").targetSize,
        )
        for ((frame, expected) in listOf(
            """{"type":"Auto"}""" to FrameRate.Auto,
            """{"type":"MaxFps","fps":24}""" to FrameRate.MaxFps(24),
        )) {
            val encoded = """{"frameRate":$frame}"""
            val decoded = json.decodeFromString<ImageSettings>(encoded)
            assertEquals(expected, decoded.frameRate)
            assertEquals(
                Json.parseToJsonElement(frame),
                Json.parseToJsonElement(json.encodeToString(decoded)).jsonObject.getValue("frameRate"),
            )
        }
        for (invalid in listOf(
            """{"cropInsets":{"left":-1,"top":0,"right":0,"bottom":0}}""",
            """{"targetSize":{"widthPx":0,"heightPx":360}}""",
            """{"frameRate":{"type":"MaxFps","fps":0}}""",
            """{"frameRate":{"type":"SamplingInterval","interval":"not-duration"}}""",
        )) assertThrows(IllegalArgumentException::class.java) { json.decodeFromString<ImageSettings>(invalid) }
    }

    // Cases: MJPEG-SET-03, MJPEG-SET-04, MJPEG-SET-10, MJPEG-SET-11
    @Test
    fun networkAccessAndDormantChoicesKeepTheirSavedSchemaAndRedactDisplayText() {
        val fixture =
            """{"image":{},"network":{"filter":{"families":["Ipv4","Ipv6"],"interfaceTypes":["Mobile","Vpn"],"categories":["NonPrivate","Loopback"]},"httpPort":9000},"access":{"pinEnabled":true,"pinPolicy":"Permanent","pin":{"value":"001234"},"hidePinOnCaptureStart":false,"limitWrongPins":false,"blockMinutes":7},"web":{"title":"","retainImageOnReconnect":false},"behavior":{"keepScreenAwake":true,"stopOnScreenOff":true,"notifySlowViewers":false}}"""
        val settings = Json.decodeFromString<MjpegSettings.Data>(fixture)
        assertEquals(
            NetworkSettings(
                NetworkAddressFilter(
                    setOf(AddressFamily.Ipv4, AddressFamily.Ipv6), setOf(InterfaceType.Mobile, InterfaceType.Vpn),
                    setOf(AddressCategory.NonPrivate, AddressCategory.Loopback),
                ),
                httpPort = 9000,
            ),
            settings.network,
        )
        assertEquals(
            AccessSettings(
                pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.Permanent, pin = SecretValue("001234"),
                hidePinOnCaptureStart = false, limitWrongPins = false, blockMinutes = 7,
            ),
            settings.access,
        )
        assertEquals("", settings.web.title)
        assertFalse(settings.web.retainImageOnMediaFailure)
        assertTrue(settings.behavior.keepScreenAwake)
        assertTrue(settings.behavior.stopOnScreenOff)
        assertFalse(settings.behavior.notifySlowViewers)
        assertEquals(settings, Json.decodeFromString<MjpegSettings.Data>(json.encodeToString(settings)))
        val expectedSchema = Json.parseToJsonElement(fixture).jsonObject
        val savedSchema = Json.parseToJsonElement(Json.encodeToString(settings)).jsonObject
        assertEquals("Saved access schema", expectedSchema.getValue("access"), savedSchema.getValue("access"))
        val expectedNetwork = expectedSchema.getValue("network").jsonObject
        val savedNetwork = savedSchema.getValue("network").jsonObject
        assertEquals("Saved network keys", expectedNetwork.keys, savedNetwork.keys)
        assertEquals("Saved HTTP port", expectedNetwork.getValue("httpPort"), savedNetwork.getValue("httpPort"))
        val expectedFilter = expectedNetwork.getValue("filter").jsonObject
        val savedFilter = savedNetwork.getValue("filter").jsonObject
        assertEquals("Saved filter keys", expectedFilter.keys, savedFilter.keys)
        for (group in expectedFilter.keys) {
            // Filter groups are sets; sorted lists preserve duplicate detection without imposing order.
            assertEquals(
                "Saved $group values",
                expectedFilter.getValue(group).jsonArray.sortedBy { it.toString() },
                savedFilter.getValue(group).jsonArray.sortedBy { it.toString() },
            )
        }
        assertEquals("<redacted>", settings.access.pin.toString())
        assertTrue(settings.toString().contains("<redacted>"))
        assertFalse(settings.toString().contains("001234"))
        assertEquals(Json.parseToJsonElement("""{"value":"001234"}"""), Json.parseToJsonElement(Json.encodeToString(settings.access.pin)))
    }
}
