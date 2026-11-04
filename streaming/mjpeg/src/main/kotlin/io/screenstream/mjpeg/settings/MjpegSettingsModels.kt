package io.screenstream.mjpeg.settings

import android.os.Build
import androidx.compose.runtime.Immutable
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
import kotlin.time.Duration.Companion.seconds

/**
 * Image parameters are live-editable; changing the JPEG backend requires successful capture cleanup.
 * Crop/size choices remain saved when disabled or unselected. Insets apply inside the selected region
 * before rotation; output size follows the transformed image. Runtime geometry can reject a request
 * that passed value validation. Frame-rate limits do not guarantee a delivery rate.
 */
@Immutable
@Serializable
internal data class ImageSettings(
    val sourceRegion: SourceRegion = DEFAULT_SOURCE_REGION,
    val cropEnabled: Boolean = DEFAULT_CROP_ENABLED,
    @Serializable(with = CropInsetsSerializer::class)
    val cropInsets: CropInsetsPx = DEFAULT_CROP_INSETS,
    val outputSelection: OutputSelection = DEFAULT_OUTPUT_SELECTION,
    val scalePercent: Double = DEFAULT_SCALE_PERCENT,
    @Serializable(with = TargetSizeSerializer::class)
    val targetSize: OutputSize.TargetSize? = DEFAULT_TARGET_SIZE,
    val rotation: Rotation = DEFAULT_ROTATION,
    val mirror: Mirror = DEFAULT_MIRROR,
    val colorMode: ColorMode = DEFAULT_COLOR_MODE,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    @Serializable(with = FrameRateSerializer::class)
    val frameRate: FrameRate = DEFAULT_FRAME_RATE,
    val jpegBackendPolicy: JpegBackendPolicy = DEFAULT_JPEG_BACKEND_POLICY,
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

    companion object {
        val DEFAULT_SOURCE_REGION: SourceRegion = SourceRegion.Full
        const val DEFAULT_CROP_ENABLED: Boolean = false
        val DEFAULT_CROP_INSETS: CropInsetsPx = CropInsetsPx.ZERO
        val DEFAULT_OUTPUT_SELECTION: OutputSelection = OutputSelection.Scale
        const val DEFAULT_SCALE_PERCENT: Double = 50.0
        val DEFAULT_TARGET_SIZE: OutputSize.TargetSize? = null
        val DEFAULT_ROTATION: Rotation = Rotation.Degrees0
        val DEFAULT_MIRROR: Mirror = Mirror.None
        val DEFAULT_COLOR_MODE: ColorMode = ColorMode.Color
        const val DEFAULT_JPEG_QUALITY: Int = 80
        val DEFAULT_FRAME_RATE: FrameRate = FrameRate.Auto
        val DEFAULT_JPEG_BACKEND_POLICY: JpegBackendPolicy = JpegBackendPolicy.Auto

        // Draft values for manual modes when no corresponding value has been saved.
        const val UI_DEFAULT_MANUAL_FPS: Int = 30
        val UI_DEFAULT_SAMPLING_INTERVAL: Duration = 1.seconds
        val UI_DEFAULT_TARGET_CONTENT_MODE: OutputSize.ContentMode = OutputSize.ContentMode.AspectFit

        fun uiDefaultTargetDimension(dimensionPx: Int): Int = dimensionPx / 2 + dimensionPx % 2
    }
}

/**
 * Address filters remain live-editable; the HTTP port requires setup edits to be allowed.
 * Each saved filter group must be nonempty. Selection does not guarantee a bind or viewer reachability.
 */
@Immutable
@Serializable
internal data class NetworkSettings(
    @Serializable(with = FilterSerializer::class)
    val filter: NetworkAddressFilter = DEFAULT_FILTER,
    val httpPort: Int = DEFAULT_HTTP_PORT,
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

    companion object {
        val DEFAULT_FILTER: NetworkAddressFilter = NetworkAddressFilter(
            families = setOf(AddressFamily.Ipv4),
            interfaceTypes = setOf(InterfaceType.Wifi, InterfaceType.Ethernet),
            categories = setOf(AddressCategory.Private),
        )
        const val DEFAULT_HTTP_PORT: Int = 8080
    }
}

/**
 * Access changes except [hidePinOnCaptureStart] require setup edits; late observations cannot change busy credentials.
 * Random PIN regeneration works for either policy; typed edits require Permanent. Enabling protection
 * without a PIN generates and saves six ASCII digits before protected viewing opens.
 *
 * [hidePinOnCaptureStart] is live-editable and hides the PIN below stream addresses when capture starts.
 * [limitWrongPins] blocks after five wrong entries per immediate IP, shared across listening addresses.
 * Existing authorization survives a block; requests do not extend it, and success/expiry resets attempts.
 * [blockMinutes] changes affect later blocks.
 */
@Immutable
@Serializable
internal data class AccessSettings(
    val pinEnabled: Boolean = DEFAULT_PIN_ENABLED,
    val pinPolicy: PinPolicy = DEFAULT_PIN_POLICY,
    val pin: SecretValue? = DEFAULT_PIN,
    val hidePinOnCaptureStart: Boolean = DEFAULT_HIDE_PIN_ON_CAPTURE_START,
    val limitWrongPins: Boolean = DEFAULT_LIMIT_WRONG_PINS,
    val blockMinutes: Int = DEFAULT_BLOCK_MINUTES,
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
        const val DEFAULT_PIN_ENABLED: Boolean = false
        val DEFAULT_PIN_POLICY: PinPolicy = PinPolicy.NewOnModuleStart
        val DEFAULT_PIN: SecretValue? = null
        const val DEFAULT_HIDE_PIN_ON_CAPTURE_START: Boolean = true
        const val DEFAULT_LIMIT_WRONG_PINS: Boolean = true
        const val DEFAULT_BLOCK_MINUTES: Int = 3

        private const val PIN_RANGE = 1_000_000
        private const val PIN_DIGITS = 6

        fun isValidPin(pin: String): Boolean = pin.length == PIN_DIGITS && pin.all { it in '0'..'9' }
    }
}

/**
 * Redacts generated settings/UI strings through [toString]; serialization preserves the supplied text.
 * PIN validation belongs to [AccessSettings]. Reveal [value] only for an explicit user action.
 */
@Immutable
@Serializable
internal class SecretValue(val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "<redacted>"
}

/**
 * Shared live page settings; browser-local viewing choices remain separate.
 * The default device title is always saved; explicitly empty titles remain empty.
 */
@Immutable
@Serializable
internal data class WebPageSettings(
    val background: String = DEFAULT_BACKGROUND,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val title: String = defaultTitle(),
    val titleEnabled: Boolean = DEFAULT_TITLE_ENABLED,
    val titleLayout: BlockLayout = DEFAULT_TITLE_LAYOUT,
    val controlsLayout: BlockLayout = DEFAULT_CONTROLS_LAYOUT,
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
    @Immutable
    @Serializable
    data class BlockLayout(val position: Edge, val presentation: Presentation, val autoHide: Boolean)

    companion object {
        const val DEFAULT_BACKGROUND: String = "#101418"
        const val DEFAULT_TITLE_ENABLED: Boolean = true
        val DEFAULT_TITLE_LAYOUT: BlockLayout = BlockLayout(Edge.Top, Presentation.Overlay, true)
        val DEFAULT_CONTROLS_LAYOUT: BlockLayout = BlockLayout(Edge.Bottom, Presentation.Overlay, true)

        fun defaultTitle(): String {
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
 * cancel it. Timeout edits keep the same loss episode. [postStopImage] applies whenever no current
 * capture image exists. Last frame falls back to logo; fallback edits retain the latest real frame.
 * Screen-off Stop ends capture when the device goes to sleep; waking does not restart it.
 * Notification preferences do not control statistics collection.
 */
@Immutable
@Serializable
internal data class StreamBehaviorSettings(
    val keepScreenAwake: Boolean = DEFAULT_KEEP_SCREEN_AWAKE,
    val stopOnScreenOff: Boolean = DEFAULT_STOP_ON_SCREEN_OFF,
    val stopAfterNetworkLossSeconds: Int = DEFAULT_STOP_AFTER_NETWORK_LOSS_SECONDS,
    val postStopImage: PostStopImage = DEFAULT_POST_STOP_IMAGE,
    val notifySlowViewers: Boolean = DEFAULT_NOTIFY_SLOW_VIEWERS,
) {
    init {
        require(stopAfterNetworkLossSeconds in 10..1800) { "Network-loss timeout must be in 10..1800 seconds" }
    }

    @Serializable
    enum class PostStopImage {
        Placeholder,

        LastAvailableFrame,
    }

    companion object {
        const val DEFAULT_KEEP_SCREEN_AWAKE: Boolean = false
        const val DEFAULT_STOP_ON_SCREEN_OFF: Boolean = false
        const val DEFAULT_STOP_AFTER_NETWORK_LOSS_SECONDS: Int = 60
        val DEFAULT_POST_STOP_IMAGE: PostStopImage = PostStopImage.Placeholder
        const val DEFAULT_NOTIFY_SLOW_VIEWERS: Boolean = true
    }
}
