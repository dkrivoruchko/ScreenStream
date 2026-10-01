package io.screenstream.mjpeg.settings

import android.os.Build
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameRate
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.AddressCategory
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.AddressFamily
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.Filter
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.InterfaceType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration

/**
 * Saved image choices. The controller may apply image and frame-rate edits during capture;
 * encoder-policy edits require stopped capture. Capture geometry and resource limits are checked
 * by the engine, rather than by these preferences.
 *
 * @property sourceRegion Whole capture area by default; half-area choices precede cropping.
 * @property cropEnabled Whether saved insets are applied; false uses zero insets without erasing them.
 * @property cropInsets Nonnegative pixel insets in the selected region before rotation, initially zero.
 * Whether they leave a nonempty image depends on the actual source dimensions.
 * @property outputSelection Scale by default; changing the choice retains both saved size options.
 * @property scalePercent Default 50%; finite and positive after division by 100, including enlargement.
 * @property targetSize Optional positive pixel bounds and AspectFit/Stretch policy; initially unset.
 * A value is required when TargetSize is selected, and is retained while Scale is selected.
 * @property rotation Clockwise image rotation, initially zero degrees.
 * @property mirror Reflection after rotation, initially disabled.
 * @property colorMode Color by default, with grayscale available.
 * @property jpegQuality JPEG quality in 0..100, initially 80.
 * @property frameRate Auto by default; a 1..120 FPS cap or 1-second..1-hour sampling interval limits
 * new images without guaranteeing a production rate.
 * @property jpegBackendPolicy Auto by default; FrameworkOnly restricts the engine's JPEG encoder.
 */
@Serializable
internal data class ImageSettings(
    val sourceRegion: SourceRegion = SourceRegion.Full,
    val cropEnabled: Boolean = false,
    @Serializable(with = CropInsetsSerializer::class)
    val cropInsets: CropInsetsPx = CropInsetsPx.ZERO,
    val outputSelection: OutputSelection = OutputSelection.Scale,
    val scalePercent: Double = 50.0,
    @Serializable(with = TargetSizeSerializer::class)
    val targetSize: OutputSize.TargetSize? = null,
    val rotation: Rotation = Rotation.Degrees0,
    val mirror: Mirror = Mirror.None,
    val colorMode: ColorMode = ColorMode.Color,
    val jpegQuality: Int = 80,
    @Serializable(with = FrameRateSerializer::class)
    val frameRate: FrameRate = FrameRate.Auto,
    val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
) {
    init {
        require(scalePercent.isFinite() && scalePercent / 100.0 > 0) { "Scale must be finite and positive" }
        require(jpegQuality in 0..100) { "JPEG quality must be in 0..100" }
        require(outputSelection != OutputSelection.TargetSize || targetSize != null) { "Target size must be set when selected" }
    }

    /** Which saved output-size choice the controller applies. */
    @Serializable
    enum class OutputSelection {
        /** Apply [ImageSettings.scalePercent] to the transformed source size. */
        Scale,

        /** Apply [ImageSettings.targetSize], including its aspect-ratio policy. */
        TargetSize,
    }

    private object CropInsetsSerializer : KSerializer<CropInsetsPx> {
        @Serializable
        private data class Saved(val left: Int, val top: Int, val right: Int, val bottom: Int)

        override val descriptor: SerialDescriptor = Saved.serializer().descriptor
        override fun serialize(encoder: Encoder, value: CropInsetsPx): Unit = encoder.encodeSerializableValue(
            Saved.serializer(), Saved(value.left, value.top, value.right, value.bottom),
        )

        override fun deserialize(decoder: Decoder): CropInsetsPx {
            val value = decoder.decodeSerializableValue(Saved.serializer())
            return CropInsetsPx(value.left, value.top, value.right, value.bottom)
        }
    }

    private object TargetSizeSerializer : KSerializer<OutputSize.TargetSize> {
        @Serializable
        private data class Saved(
            val widthPx: Int,
            val heightPx: Int,
            val contentMode: OutputSize.ContentMode = OutputSize.ContentMode.AspectFit,
        )

        override val descriptor: SerialDescriptor = Saved.serializer().descriptor
        override fun serialize(encoder: Encoder, value: OutputSize.TargetSize): Unit = encoder.encodeSerializableValue(
            Saved.serializer(), Saved(value.widthPx, value.heightPx, value.contentMode),
        )

        override fun deserialize(decoder: Decoder): OutputSize.TargetSize {
            val value = decoder.decodeSerializableValue(Saved.serializer())
            return OutputSize.TargetSize(value.widthPx, value.heightPx, value.contentMode)
        }
    }

    private object FrameRateSerializer : KSerializer<FrameRate> {
        @Serializable
        private sealed interface Saved {
            @Serializable
            @SerialName("Auto")
            data object Auto : Saved

            @Serializable
            @SerialName("MaxFps")
            data class MaxFps(val fps: Int) : Saved

            @Serializable
            @SerialName("SamplingInterval")
            data class SamplingInterval(val interval: String) : Saved
        }

        override val descriptor: SerialDescriptor = Saved.serializer().descriptor
        override fun serialize(encoder: Encoder, value: FrameRate): Unit = encoder.encodeSerializableValue(
            Saved.serializer(),
            when (value) {
                FrameRate.Auto -> Saved.Auto
                is FrameRate.MaxFps -> Saved.MaxFps(value.fps)
                is FrameRate.SamplingInterval -> Saved.SamplingInterval(value.interval.toIsoString())
            },
        )

        override fun deserialize(decoder: Decoder): FrameRate = when (val value = decoder.decodeSerializableValue(Saved.serializer())) {
            Saved.Auto -> FrameRate.Auto
            is Saved.MaxFps -> FrameRate.MaxFps(value.fps)
            is Saved.SamplingInterval -> FrameRate.SamplingInterval(Duration.parseIsoString(value.interval))
        }
    }
}

/**
 * Saved network choices. Filter edits can be applied at any time; changing the HTTP port requires
 * stopped capture. The controller coordinates discovery and HTTP application.
 *
 * @property filter Nonempty address-family, interface-type and category groups. Choices within a
 * group are ORed and groups are ANDed; All is the full set. Defaults are IPv4, Wi-Fi/Ethernet and
 * Private addresses. [Filter] owns copies of its sets and separately allows empty groups.
 * @property httpPort Fixed listening port in 1024..65535, initially 8080.
 */
@Serializable
internal data class NetworkSettings(
    @Serializable(with = FilterSerializer::class)
    val filter: Filter = Filter(
        families = setOf(AddressFamily.Ipv4),
        interfaceTypes = setOf(InterfaceType.Wifi, InterfaceType.Ethernet),
        categories = setOf(AddressCategory.Private),
    ),
    val httpPort: Int = 8080,
) {
    init {
        require(filter.families.isNotEmpty() && filter.interfaceTypes.isNotEmpty() && filter.categories.isNotEmpty()) {
            "Saved address filter groups must be nonempty"
        }
        require(httpPort in 1024..65535) { "HTTP port must be in 1024..65535" }
    }

    private object FilterSerializer : KSerializer<Filter> {
        @Serializable
        private data class Saved(
            val families: Set<AddressFamily>,
            val interfaceTypes: Set<InterfaceType>,
            val categories: Set<AddressCategory>,
        )

        override val descriptor: SerialDescriptor = Saved.serializer().descriptor
        override fun serialize(encoder: Encoder, value: Filter): Unit = encoder.encodeSerializableValue(
            Saved.serializer(), Saved(value.families, value.interfaceTypes, value.categories),
        )

        override fun deserialize(decoder: Decoder): Filter {
            val value = decoder.decodeSerializableValue(Saved.serializer())
            return Filter(value.families, value.interfaceTypes, value.categories)
        }
    }
}

/**
 * Saved access choices. The controller must admit edits only while capture is stopped, with no
 * Start/consent in progress and after Stop cleanup has completed. Link tokens and applied permissions are runtime
 * state; this model does not generate PINs or apply access changes.
 *
 * @property pinEnabled Whether PIN protection is requested, initially false.
 * @property pinPolicy When the controller replaces the same saved [pin], initially NewOnModuleStart.
 * @property pin Saved six-ASCII-digit PIN, including leading zeros, or null before one is generated.
 * Null does not itself disable requested protection; the controller must generate, save and apply it.
 * @property hidePinOnCaptureStart Hide the displayed PIN when capture starts, initially true;
 * the user may reveal it independently of this preference.
 * @property limitWrongPins Enable blocking after five wrong PIN entries per client IP, initially true.
 * Attempts are shared across listening addresses; already authorized viewers are not revoked by blocking.
 * @property blockMinutes Positive whole minutes for subsequent IP blocks, initially one; requests
 * during a block do not extend it, and success or expiry resets the attempt count.
 */
@Serializable
internal data class AccessSettings(
    val pinEnabled: Boolean = false,
    val pinPolicy: PinPolicy = PinPolicy.NewOnModuleStart,
    val pin: SecretValue? = null,
    val hidePinOnCaptureStart: Boolean = true,
    val limitWrongPins: Boolean = true,
    val blockMinutes: Int = 1,
) {
    init {
        require(blockMinutes >= 1) { "Block duration must be at least one minute" }
        require(pin == null || pin.value.matches(Regex("[0-9]{6}"))) { "PIN must have six digits" }
    }

    /** Replacement timing for the single saved PIN; manual regeneration replaces that same value. */
    @Serializable
    enum class PinPolicy {
        /** Keep the PIN until the user changes it. */
        Permanent,

        /** Replace on each new module controller, including return from another mode, not background resume. */
        NewOnModuleStart,

        /**
         * Generate and save for each new controller even if a PIN was saved, then replace once when
         * an attempt that accepted projection logically ends, even if the engine failed.
         */
        NewAfterStream,
    }
}

/**
 * A secret used by settings and runtime access contracts; it is not restricted to PINs.
 * [toString] is redacted, including inside generated settings/UI strings. Serialization stores the
 * value as supplied; PIN format validation belongs to [AccessSettings].
 *
 * @property value Secret text; callers must not log or display it without an explicit user action.
 */
@Serializable
internal class SecretValue(val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "<redacted>"
}

/**
 * Page preferences shared with all viewers. The controller can apply edits at any time;
 * preferences local to one browser remain with that viewer.
 *
 * @property background RGB color in #RRGGBB form, initially #101418.
 * @property title Page caption and browser-tab title, initially the device manufacturer/model without
 * a repeated manufacturer, or Android device when unavailable. Every write includes this value,
 * even when equal to its current device default; an explicitly empty title remains empty.
 * @property titleEnabled Show the caption, initially true.
 * @property titleLayout Caption placement, initially top overlay with automatic hiding.
 * @property controlsLayout Control placement, initially bottom overlay with automatic hiding.
 * @property retainImageOnReconnect Retain the displayed image during temporary disconnects, initially true.
 */
@Serializable
internal data class WebPageSettings(
    val background: String = "#101418",
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val title: String = deviceTitle(),
    val titleEnabled: Boolean = true,
    val titleLayout: BlockLayout = BlockLayout(Edge.Top, Presentation.Overlay, true),
    val controlsLayout: BlockLayout = BlockLayout(Edge.Bottom, Presentation.Overlay, true),
    val retainImageOnReconnect: Boolean = true,
) {
    init {
        require(background.matches(Regex("#[0-9a-fA-F]{6}"))) { "Background must be an RGB color" }
    }

    /** Which page edge hosts a block. */
    @Serializable
    enum class Edge { Top, Bottom }

    /** Whether a block overlaps the image or occupies its own page strip. */
    @Serializable
    enum class Presentation {
        /** Display over the image. */
        Overlay,

        /** Reserve a separate strip. */
        Bar,
    }

    /**
     * Layout of one shared page block, independent of the other block.
     *
     * @property position Top or bottom page edge.
     * @property presentation Overlay on the image or a separate strip.
     * @property autoHide Hide the block automatically instead of keeping it visible.
     */
    @Serializable
    data class BlockLayout(val position: Edge, val presentation: Presentation, val autoHide: Boolean)

    private companion object {
        private fun deviceTitle(): String {
            val manufacturer = Build.MANUFACTURER.trim()
            val model = Build.MODEL.trim()
            return when {
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> listOf(manufacturer, model).filter(String::isNotBlank).joinToString(" ")
            }.ifBlank { "Android device" }
        }
    }
}

/**
 * Saved capture behavior. The controller applies wakefulness edits immediately, event preferences
 * to later events, and post-Stop display at Stop or immediately while already stopped. Platform
 * restrictions and system Stop take priority. These preferences do not implement those actions.
 *
 * @property keepScreenAwake Request dim-capable screen wakefulness during capture, initially false;
 * it does not override manual locking or platform restrictions.
 * @property stopOnScreenOff Request capture Stop when the screen turns off, initially false.
 * @property stopOnAllAddressesLost Request Stop when all usable device addresses disappear, initially
 * false; an HTTP-server failure while an address still exists is not address loss.
 * @property postStopImage Placeholder by default; the last frame is usable only while still available
 * and is not persisted for restoration.
 * @property notifySlowViewers Request slow-viewer notifications, initially true; statistics collection
 * is independent, and the notification surface remains to be implemented.
 */
@Serializable
internal data class StreamBehaviorSettings(
    val keepScreenAwake: Boolean = false,
    val stopOnScreenOff: Boolean = false,
    val stopOnAllAddressesLost: Boolean = false,
    val postStopImage: PostStopImage = PostStopImage.Placeholder,
    val notifySlowViewers: Boolean = true,
) {
    /** Image preference after capture stops. */
    @Serializable
    enum class PostStopImage {
        /** Do not display a post-Stop image. */
        None,

        /** Display a placeholder. */
        Placeholder,

        /** Display the most recent frame only if it is still available. */
        LastAvailableFrame,
    }
}
