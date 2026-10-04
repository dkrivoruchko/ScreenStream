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
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.SecureRandom
import kotlin.time.Duration

/**
 * Image parameters are live-editable; changing the JPEG backend requires successful capture cleanup.
 * Crop/size choices remain saved when disabled or unselected. Insets apply inside the selected region
 * before rotation; output size follows the transformed image. Runtime geometry can reject a request
 * that passed value validation. Frame-rate limits do not guarantee a delivery rate.
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

    @Serializable
    enum class OutputSelection {
        Scale,

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
 * Address filters remain live-editable; the HTTP port requires setup edits to be allowed.
 * Each saved filter group must be nonempty. Selection does not guarantee a bind or viewer reachability.
 */
@Serializable
internal data class NetworkSettings(
    @Serializable(with = FilterSerializer::class)
    val filter: NetworkAddressFilter = NetworkAddressFilter(
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

    private object FilterSerializer : KSerializer<NetworkAddressFilter> {
        @Serializable
        private data class Saved(
            val families: Set<AddressFamily>,
            val interfaceTypes: Set<InterfaceType>,
            val categories: Set<AddressCategory>,
        )

        override val descriptor: SerialDescriptor = Saved.serializer().descriptor
        override fun serialize(encoder: Encoder, value: NetworkAddressFilter): Unit = encoder.encodeSerializableValue(
            Saved.serializer(), Saved(value.families, value.interfaceTypes, value.categories),
        )

        override fun deserialize(decoder: Decoder): NetworkAddressFilter {
            val value = decoder.decodeSerializableValue(Saved.serializer())
            return NetworkAddressFilter(value.families, value.interfaceTypes, value.categories)
        }
    }
}

/**
 * Access changes require setup edits to be allowed; late observations cannot change busy credentials.
 * Random PIN regeneration works for either policy; typed edits require Permanent. Enabling protection
 * without a PIN generates and saves six ASCII digits before protected viewing opens.
 *
 * [hidePinOnCaptureStart] is a saved display preference; PIN display handling is not connected yet.
 * [limitWrongPins] blocks after five wrong entries per immediate IP, shared across listening addresses.
 * Existing authorization survives a block; requests do not extend it, and success/expiry resets attempts.
 * [blockMinutes] changes affect later blocks.
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
        require(pin == null || isValidPin(pin.value)) { "PIN must have six digits" }
    }

    fun withGeneratedPin(): AccessSettings =
        copy(pin = SecretValue(SecureRandom().nextInt(PIN_RANGE).toString().padStart(PIN_DIGITS, '0')))

    @Serializable
    enum class PinPolicy {
        /** Keep the PIN until random regeneration or a typed edit while fully idle. */
        Permanent,

        /** Generate once on protected module activation; capture Start/Stop and background resume keep it. */
        NewOnModuleStart,
    }

    companion object {
        private const val PIN_RANGE = 1_000_000
        private const val PIN_DIGITS = 6

        fun isValidPin(pin: String): Boolean = pin.length == PIN_DIGITS && pin.all { it in '0'..'9' }
    }
}

/**
 * Redacts generated settings/UI strings through [toString]; serialization preserves the supplied text.
 * PIN validation belongs to [AccessSettings]. Reveal [value] only for an explicit user action.
 */
@Serializable
internal class SecretValue(val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "<redacted>"
}

/**
 * Shared live page settings; browser-local viewing choices remain separate.
 * The default device title is always saved; explicitly empty titles remain empty.
 * [retainImageOnMediaFailure] preserves displayed media during media recovery, not authorization denial
 * or image unavailability. Its old serialized name preserves existing saved values.
 */
@Serializable
internal data class WebPageSettings(
    val background: String = "#101418",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val title: String = deviceTitle(),
    val titleEnabled: Boolean = true,
    val titleLayout: BlockLayout = BlockLayout(Edge.Top, Presentation.Overlay, true),
    val controlsLayout: BlockLayout = BlockLayout(Edge.Bottom, Presentation.Overlay, true),
    @SerialName("retainImageOnReconnect")
    val retainImageOnMediaFailure: Boolean = true,
) {
    init {
        require(background.matches(Regex("#[0-9a-fA-F]{6}"))) { "Background must be an RGB color" }
    }

    @Serializable
    enum class Edge { Top, Bottom }

    /** Whether a block overlaps the image or occupies its own page strip. */
    @Serializable
    enum class Presentation {
        Overlay,

        Bar,
    }

    /**
     * Independent title/control placement; Bar reserves space while Overlay covers the image.
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
 * Network-loss Stop tracks an empty selected-address list, not HTTP bind failures; returning addresses
 * cancel it. Timeout edits keep the same loss episode. Post-Stop selection applies immediately while
 * stopped, but cleared frames cannot be restored and capture errors leave no placeholder.
 * Screen wakefulness, screen-off Stop and slow-viewer notifications are not connected yet;
 * notification preferences do not control statistics collection.
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

    @Serializable
    enum class PostStopImage {
        None,

        Placeholder,

        LastAvailableFrame,
    }
}
