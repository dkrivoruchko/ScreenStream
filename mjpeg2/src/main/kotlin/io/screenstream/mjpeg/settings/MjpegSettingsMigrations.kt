package io.screenstream.mjpeg.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameRate
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** Imports legacy preferences; file identities are independent of the Gradle module's name. */
internal class MjpegSettingsMigration(context: Context) : DataMigration<Preferences> {
    private val sourceFile: File = context.preferencesDataStoreFile("MJPEG_settings")
    private val defaults: MjpegSettings.Data = MjpegSettings.Data(web = WebPageSettings())

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = sourceFile.exists()

    override suspend fun migrate(currentData: Preferences): Preferences {
        var failure: Throwable? = null
        try {
            // A present destination payload wins even when its type or JSON requires normal recovery.
            if (currentData.asMap().keys.any { it.name == PAYLOAD.name }) return currentData
            val source = try {
                sourceFile.inputStream().use { PreferencesFileSerializer.readFrom(it) }
            } catch (_: CorruptionException) {
                XLog.w("Legacy MJPEG preferences are corrupt; import skipped")
                null
            }
            if (source == null) return currentData
            val candidate = LegacyPreferences(source).toSettings()
            if (candidate == defaults) return currentData
            val encoded = JSON.encodeToJsonElement(MjpegSettings.Data.serializer(), candidate).jsonObject
            // No old title exists. Keep the required web object without saving a synthetic title.
            val web = JsonObject(encoded.getValue("web").jsonObject - "title")
            val payload = JsonObject(encoded + ("web" to web))
            return currentData.toMutablePreferences().apply { this[PAYLOAD] = payload.toString() }.toPreferences()
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            // Deliberately consume even failed attempts, before DataStore commits the result.
            // This is exact, nonrecursive deletion; a commit failure cannot retry the import.
            if (!sourceFile.delete() && sourceFile.exists()) {
                val cleanupFailure = IOException("Failed to delete legacy MJPEG preferences")
                val primaryFailure = failure
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    override suspend fun cleanUp(): Unit = Unit

    private inner class LegacyPreferences(preferences: Preferences) {
        private val values: Map<String, Any> = preferences.asMap().entries.associate { it.key.name to it.value }

        private fun has(name: String): Boolean = name in values
        private fun boolean(name: String): Boolean? = values[name] as? Boolean
        private fun integer(name: String): Int? = values[name] as? Int
        private inline fun <reified T> invalid(vararg names: String): Boolean =
            names.any { has(it) && values[it] !is T }

        fun toSettings(): MjpegSettings.Data {
            val rotation = when (integer("ROTATION")) {
                0 -> Rotation.Degrees0
                90 -> Rotation.Degrees90
                180 -> Rotation.Degrees180
                270 -> Rotation.Degrees270
                else -> ImageSettings.DEFAULT_ROTATION
            }
            val crop = crop()
            val target = target(rotation)
            val image = defaults.image.copy(
                sourceRegion = when (integer("VR_MODE")) {
                    0 -> SourceRegion.Full
                    1 -> SourceRegion.LeftHalf
                    2 -> SourceRegion.RightHalf
                    else -> ImageSettings.DEFAULT_SOURCE_REGION
                },
                cropEnabled = crop?.first ?: ImageSettings.DEFAULT_CROP_ENABLED,
                cropInsets = crop?.second ?: ImageSettings.DEFAULT_CROP_INSETS,
                outputSelection = if (target != null) ImageSettings.OutputSelection.TargetSize else ImageSettings.DEFAULT_OUTPUT_SELECTION,
                targetSize = target ?: ImageSettings.DEFAULT_TARGET_SIZE,
                scalePercent = integer("RESIZE_FACTOR")?.takeIf { it > 0 }?.toDouble() ?: ImageSettings.DEFAULT_SCALE_PERCENT,
                rotation = rotation,
                mirror = when (integer("FLIP")) {
                    0 -> Mirror.None
                    1 -> Mirror.Horizontal
                    2 -> Mirror.Vertical
                    else -> ImageSettings.DEFAULT_MIRROR
                },
                colorMode = boolean("IMAGE_GRAYSCALE")?.let {
                    if (it) ColorMode.Grayscale else ColorMode.Color
                } ?: ImageSettings.DEFAULT_COLOR_MODE,
                jpegQuality = integer("JPEG_QUALITY")?.takeIf { it in 0..100 } ?: ImageSettings.DEFAULT_JPEG_QUALITY,
                frameRate = frameRate() ?: ImageSettings.DEFAULT_FRAME_RATE,
            )
            return defaults.copy(
                image = image,
                network = defaults.network.copy(
                    filter = filter() ?: NetworkSettings.DEFAULT_FILTER,
                    httpPort = integer("SERVER_PORT")?.takeIf { it in 1024..65535 } ?: NetworkSettings.DEFAULT_HTTP_PORT,
                ),
                access = defaults.access.copy(
                    pinEnabled = boolean("ENABLE_PIN") ?: AccessSettings.DEFAULT_PIN_ENABLED,
                    pinPolicy = boolean("NEW_PIN_ON_APP_START")?.let {
                        if (it) AccessSettings.PinPolicy.NewOnModuleStart else AccessSettings.PinPolicy.Permanent
                    } ?: AccessSettings.DEFAULT_PIN_POLICY,
                    pin = (values["PIN"] as? String)?.takeIf(AccessSettings::isValidPin)?.let(::SecretValue) ?: AccessSettings.DEFAULT_PIN,
                    hidePinOnCaptureStart = boolean("HIDE_PIN_ON_START") ?: AccessSettings.DEFAULT_HIDE_PIN_ON_CAPTURE_START,
                    limitWrongPins = boolean("BLOCK_ADDRESS") ?: AccessSettings.DEFAULT_LIMIT_WRONG_PINS,
                ),
                web = defaults.web.copy(
                    background = integer("HTML_BACK_COLOR")?.let { "#%06X".format(it and 0xFFFFFF) } ?: WebPageSettings.DEFAULT_BACKGROUND,
                ),
                behavior = defaults.behavior.copy(
                    keepScreenAwake = boolean("KEEP_AWAKE") ?: StreamBehaviorSettings.DEFAULT_KEEP_SCREEN_AWAKE,
                    stopOnScreenOff = boolean("STOP_ON_SLEEP") ?: StreamBehaviorSettings.DEFAULT_STOP_ON_SCREEN_OFF,
                    notifySlowViewers = boolean("NOTIFY_SLOW_CONNECTIONS") ?: StreamBehaviorSettings.DEFAULT_NOTIFY_SLOW_VIEWERS,
                    // Approximate ordinary legacy Stop; restart/error transitions are not equivalent.
                    postStopImage = boolean("HTML_SHOW_PRESS_START")?.let {
                        if (it) StreamBehaviorSettings.PostStopImage.Placeholder else StreamBehaviorSettings.PostStopImage.LastAvailableFrame
                    } ?: StreamBehaviorSettings.DEFAULT_POST_STOP_IMAGE,
                ),
            )
        }

        private fun crop(): Pair<Boolean, CropInsetsPx>? {
            val edges = listOf("IMAGE_CROP_LEFT", "IMAGE_CROP_TOP", "IMAGE_CROP_RIGHT", "IMAGE_CROP_BOTTOM")
            if (invalid<Boolean>("IMAGE_CROP") || invalid<Int>(*edges.toTypedArray()) || edges.any { (integer(it) ?: 0) < 0 }) {
                return null
            }
            return (boolean("IMAGE_CROP") ?: ImageSettings.DEFAULT_CROP_ENABLED) to CropInsetsPx(
                integer("IMAGE_CROP_LEFT") ?: ImageSettings.DEFAULT_CROP_INSETS.left,
                integer("IMAGE_CROP_TOP") ?: ImageSettings.DEFAULT_CROP_INSETS.top,
                integer("IMAGE_CROP_RIGHT") ?: ImageSettings.DEFAULT_CROP_INSETS.right,
                integer("IMAGE_CROP_BOTTOM") ?: ImageSettings.DEFAULT_CROP_INSETS.bottom,
            )
        }

        private fun target(rotation: Rotation): OutputSize.TargetSize? {
            val width = integer("RESOLUTION_WIDTH")?.takeIf { it > 0 } ?: return null
            val height = integer("RESOLUTION_HEIGHT")?.takeIf { it > 0 } ?: return null
            if (invalid<Boolean>("RESOLUTION_STRETCH")) return null
            val rotated = rotation == Rotation.Degrees90 || rotation == Rotation.Degrees270
            return OutputSize.TargetSize(
                widthPx = if (rotated) height else width,
                heightPx = if (rotated) width else height,
                contentMode = if (boolean("RESOLUTION_STRETCH") == true) {
                    OutputSize.ContentMode.Stretch
                } else {
                    OutputSize.ContentMode.AspectFit
                },
            )
        }

        private fun frameRate(): FrameRate? {
            val value = integer("MAX_FPS") ?: return null
            return when {
                value == 0 -> FrameRate.Auto
                value in 1..120 -> FrameRate.MaxFps(value)
                value < 0 && abs(value.toLong()) in 1..3600 -> FrameRate.SamplingInterval(abs(value.toLong()).seconds)
                else -> null
            }
        }

        private fun filter(): NetworkAddressFilter? {
            if (invalid<Boolean>("ENABLE_IPV4", "ENABLE_IPV6")) return null
            val families = NetworkSettings.DEFAULT_FILTER.families.toMutableSet()
            boolean("ENABLE_IPV4")?.let { if (it) families.add(AddressFamily.Ipv4) else families.remove(AddressFamily.Ipv4) }
            boolean("ENABLE_IPV6")?.let { if (it) families.add(AddressFamily.Ipv6) else families.remove(AddressFamily.Ipv6) }
            if (families.isEmpty()) return null

            val interfaceMask = when {
                has("INTERFACE_FILTER") -> integer("INTERFACE_FILTER")?.takeIf { it in 0..15 } ?: return null
                has("USE_WIFI_ONLY") -> boolean("USE_WIFI_ONLY")?.let { if (it) 1 else 0 } ?: return null
                else -> null
            }
            val addressMask = when {
                has("ADDRESS_FILTER") -> integer("ADDRESS_FILTER")?.takeIf { it in 0..7 } ?: return null
                has("LOCAL_HOST_ONLY") || has("ENABLE_LOCAL_HOST") -> {
                    if (invalid<Boolean>("LOCAL_HOST_ONLY", "ENABLE_LOCAL_HOST")) return null
                    // Interpret the historical format exactly when modern keys are absent.
                    when {
                        boolean("LOCAL_HOST_ONLY") == true -> 2
                        boolean("ENABLE_LOCAL_HOST") != true -> 1 or 4
                        else -> 0
                    }
                }

                else -> null
            }
            val interfaces = when (interfaceMask) {
                null -> NetworkSettings.DEFAULT_FILTER.interfaceTypes
                0 -> InterfaceType.entries.toSet()
                else -> buildSet {
                    if (interfaceMask and 1 != 0) add(InterfaceType.Wifi)
                    if (interfaceMask and 2 != 0) add(InterfaceType.Mobile)
                    if (interfaceMask and 4 != 0) add(InterfaceType.Ethernet)
                    if (interfaceMask and 8 != 0) add(InterfaceType.Vpn)
                }
            }
            val categories = when (addressMask) {
                null -> NetworkSettings.DEFAULT_FILTER.categories
                0 -> setOf(AddressCategory.Private, AddressCategory.NonPrivate, AddressCategory.Loopback)
                else -> buildSet {
                    if (addressMask and 1 != 0) add(AddressCategory.Private)
                    if (addressMask and 2 != 0) add(AddressCategory.Loopback)
                    if (addressMask and 4 != 0) add(AddressCategory.NonPrivate)
                }
            }
            return NetworkAddressFilter(families, interfaces, categories)
        }
    }

    private companion object {
        val PAYLOAD: Preferences.Key<String> = stringPreferencesKey("DATA")
        val JSON: Json = Json { encodeDefaults = false }
    }
}
