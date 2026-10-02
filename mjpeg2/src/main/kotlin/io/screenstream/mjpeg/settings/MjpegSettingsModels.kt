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
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.AddressCategory
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.AddressFamily
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.Filter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.InterfaceType
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
 * Appearance, size and update rate of the streamed image. Image and frame-rate choices can change
 * during streaming; changing the JPEG encoder requires stopped capture. Cropping must leave a
 * nonempty image, and the output size must fit the device's capabilities.
 *
 * @property sourceRegion Stream the whole captured area, its left half or its right half; defaults
 * to the whole area. A half requires at least two source pixels in width and is selected before cropping.
 * @property cropEnabled Whether saved insets are applied; false uses zero insets without erasing them.
 * @property cropInsets Nonnegative pixel insets in the selected region before rotation, initially zero.
 * Whether they leave a nonempty image depends on the actual source dimensions.
 * @property outputSelection Choose percentage scaling or a target pixel size; defaults to Scale.
 * Switching choices retains the unused values for later use.
 * @property scalePercent Image size as a percentage after cropping and rotation, initially 50%.
 * 100% keeps that size; larger values enlarge it. Must be finite and yield a positive scale factor.
 * @property targetSize Positive width/height bounds, initially unset. AspectFit preserves proportions;
 * Stretch fills those dimensions. Required for TargetSize and retained when percentage scaling is used.
 * @property rotation Clockwise rotation by 0, 90, 180 or 270 degrees; defaults to 0.
 * @property mirror Horizontal or vertical reflection after rotation; defaults to None.
 * @property colorMode Color or grayscale output; defaults to Color.
 * @property jpegQuality JPEG quality in 0..100, initially 80; higher values favor detail over smaller files.
 * @property frameRate Auto follows available frames and processing capacity by default. A 1..120 FPS
 * cap or 1-second..1-hour sampling interval limits new images without guaranteeing that rate.
 * @property jpegBackendPolicy Auto chooses an available JPEG encoder; FrameworkOnly uses Android's
 * encoder only. Defaults to Auto and changes only while capture is stopped.
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

    /** How the streamed image is sized; the unselected size choice remains saved. */
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
 * Networks and addresses on which viewers can connect. Address filters can change while streaming;
 * changing the HTTP port requires stopped capture.
 *
 * @property filter Select eligible IP families, network types and address categories; each group
 * must have a selection. An address must match one selected choice in every group. Defaults to IPv4
 * on Wi-Fi/Ethernet with Private addresses. All includes every supported choice, but never IPv6
 * link-local; LinkLocal includes IPv4 only. Selecting an address does not guarantee viewers can reach it.
 * @property httpPort Port viewers use to connect, in 1024..65535; defaults to 8080.
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
 * Whether viewers need a PIN and when it changes. These choices are edited only after capture has
 * fully stopped, not while starting or awaiting consent. PIN-entry blocking and PIN visibility are
 * preferences for the future web client and full settings screen.
 *
 * @property pinEnabled Require PIN-authorized access instead of open viewing; defaults to false.
 * On the media-only stage, viewers use the protected link rather than a PIN form.
 * @property pinPolicy When to replace the saved [pin]; defaults to NewOnModuleStart.
 * @property pin Saved PIN of six ASCII digits, including leading zeros; initially null.
 * If protection is enabled without a PIN, one is generated and saved before protected viewing opens.
 * @property hidePinOnCaptureStart Hide the displayed PIN when capture starts, initially true;
 * the user may reveal it independently of this preference.
 * @property limitWrongPins Enable blocking after five wrong PIN entries per client IP, initially true.
 * Attempts are shared across listening addresses; already authorized viewers are not revoked by blocking.
 * @property blockMinutes Duration of future PIN-entry blocks in whole minutes, at least one;
 * defaults to one. Changes affect later blocks. Requests during a block do not extend it, and
 * successful PIN entry or expiry resets the attempt count.
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

        /** Generate a new PIN whenever MJPEG is enabled, including return from another mode, not background resume. */
        NewOnModuleStart,

        /**
         * Generate a new PIN whenever MJPEG is enabled, then replace it once after capture ends.
         * A startup that acquired screen capture but subsequently failed also changes the PIN;
         * declining or cancelling consent before capture is acquired does not.
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
 * Appearance of the future web page, shared by all viewers. These choices can change at any time;
 * browser-specific viewing preferences are separate. The media-only endpoints do not display a page.
 *
 * @property background RGB color in #RRGGBB form, initially #101418.
 * @property title Page caption and browser-tab title, initially the device manufacturer/model without
 * a repeated manufacturer, or Android device when unavailable. The saved title is retained on
 * later launches; an explicitly empty title remains empty.
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
 * When to stop streaming and what viewers see afterward. Changes are allowed at any time:
 * screen wakefulness applies immediately, Stop/notification choices affect later events, and
 * post-Stop imagery changes immediately if already stopped. System Stop and device restrictions
 * take priority. Screen wakefulness, screen-off Stop and slow-viewer notifications are not yet connected.
 *
 * @property keepScreenAwake Prevent automatic screen-off during capture while allowing dimming, initially false;
 * it does not override manual locking or platform restrictions.
 * @property stopOnScreenOff Request capture Stop when the screen turns off, initially false.
 * @property stopAfterNetworkLossSeconds Stop capture after the selected address list stays empty
 * for this many seconds; 10..1800, initially 60. Returning addresses cancel the pending Stop.
 * An HTTP-server failure while an address still exists is not address loss.
 * @property postStopImage What viewers see after an ordinary Stop: no image, a placeholder or the
 * last available frame; defaults to Placeholder. Cleared/lost frames cannot be restored, and capture
 * errors leave no image instead of showing a stopped-stream placeholder.
 * @property notifySlowViewers Request slow-viewer notifications, initially true; statistics collection
 * is independent, and the notification surface remains to be implemented.
 */
@Serializable
internal data class StreamBehaviorSettings(
    val keepScreenAwake: Boolean = false,
    val stopOnScreenOff: Boolean = false,
    val stopAfterNetworkLossSeconds: Int = 60,
    val postStopImage: PostStopImage = PostStopImage.Placeholder,
    val notifySlowViewers: Boolean = true,
) {
    init {
        require(stopAfterNetworkLossSeconds in 10..1800) { "Network-loss timeout must be in 10..1800 seconds" }
    }

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
