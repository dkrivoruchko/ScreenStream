package io.screenstream.mjpeg.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameRate
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.savedSettingsJson
import io.screenstream.mjpeg.seedSettings
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegSettingsMigrationTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun initialize() {
        XLog.init(Int.MAX_VALUE)
        ShadowBuild.setManufacturer("Acme")
        ShadowBuild.setModel("Tablet")
    }

    // Cases: MJPEG-SET-12
    @Test
    fun actualLegacyValuesBecomeValidNondefaultSettingsWithoutSavingSyntheticTitle() = runBlocking {
        withTimeout(10.seconds) {
            val context = context()
            seedLegacy(context, preferencesOf(
                intPreferencesKey("JPEG_QUALITY") to 42,
                intPreferencesKey("MAX_FPS") to -5,
                intPreferencesKey("HTML_BACK_COLOR") to 0xAA123456.toInt(),
                booleanPreferencesKey("IMAGE_CROP") to true,
                intPreferencesKey("IMAGE_CROP_LEFT") to 12,
                intPreferencesKey("INTERFACE_FILTER") to 2,
                intPreferencesKey("ADDRESS_FILTER") to 4,
                booleanPreferencesKey("ENABLE_IPV6") to true,
                intPreferencesKey("SERVER_PORT") to 9000,
                booleanPreferencesKey("ENABLE_PIN") to true,
                booleanPreferencesKey("NEW_PIN_ON_APP_START") to false,
                stringPreferencesKey("PIN") to "001234",
                booleanPreferencesKey("HIDE_PIN_ON_START") to false,
                booleanPreferencesKey("BLOCK_ADDRESS") to false,
                booleanPreferencesKey("KEEP_AWAKE") to true,
                booleanPreferencesKey("STOP_ON_SLEEP") to true,
                booleanPreferencesKey("NOTIFY_SLOW_CONNECTIONS") to false,
                booleanPreferencesKey("HTML_SHOW_PRESS_START") to false,
                booleanPreferencesKey("HTML_KEEP_IMAGE_ON_RECONNECT") to false,
            ))
            val actual = MjpegSettings(context).data.first()
            assertEquals(42, actual.image.jpegQuality)
            assertEquals(FrameRate.SamplingInterval(5.seconds), actual.image.frameRate)
            assertEquals(CropInsetsPx(12, 0, 0, 0), actual.image.cropInsets)
            assertTrue(actual.image.cropEnabled)
            assertEquals(setOf(AddressFamily.Ipv4, AddressFamily.Ipv6), actual.network.filter.families)
            assertEquals(setOf(InterfaceType.Mobile), actual.network.filter.interfaceTypes)
            assertEquals(setOf(AddressCategory.NonPrivate), actual.network.filter.categories)
            assertEquals(9000, actual.network.httpPort)
            assertEquals("001234", actual.access.pin?.value)
            assertEquals(AccessSettings.PinPolicy.Permanent, actual.access.pinPolicy)
            assertTrue(actual.access.pinEnabled)
            assertFalse(actual.access.hidePinOnCaptureStart)
            assertFalse(actual.access.limitWrongPins)
            assertEquals("#123456", actual.web.background)
            assertEquals("Acme Tablet", actual.web.title)
            assertFalse(actual.web.retainImageOnMediaFailure)
            assertEquals(StreamBehaviorSettings(true, true, 60, StreamBehaviorSettings.PostStopImage.LastAvailableFrame, false), actual.behavior)
            val saved = Json.parseToJsonElement(savedSettingsJson(context)).jsonObject
            assertFalse(saved.getValue("web").jsonObject.containsKey("title"))
            assertFalse(saved.getValue("image").jsonObject.containsKey("scalePercent"))
            assertFalse(saved.getValue("access").jsonObject.containsKey("blockMinutes"))
            assertConsumed(context)
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun absentInvalidUnsupportedAndNewDefaultEqualValuesProduceNoPayload() = runBlocking {
        withTimeout(10.seconds) {
            val fixtures = listOf(
                null,
                emptyPreferences(),
                preferencesOf(booleanPreferencesKey("AUTO_CHANGE_PIN") to true),
                preferencesOf(
                    intPreferencesKey("JPEG_QUALITY") to 80,
                    intPreferencesKey("MAX_FPS") to 0,
                    booleanPreferencesKey("KEEP_AWAKE") to false,
                    booleanPreferencesKey("NOTIFY_SLOW_CONNECTIONS") to true,
                    booleanPreferencesKey("NEW_PIN_ON_APP_START") to true,
                    booleanPreferencesKey("HTML_SHOW_PRESS_START") to true,
                    intPreferencesKey("INTERFACE_FILTER") to 5,
                    intPreferencesKey("ADDRESS_FILTER") to 1,
                ),
                preferencesOf(
                    stringPreferencesKey("JPEG_QUALITY") to "42",
                    intPreferencesKey("MAX_FPS") to Int.MIN_VALUE,
                    intPreferencesKey("RESIZE_FACTOR") to 0,
                    intPreferencesKey("ROTATION") to 45,
                    intPreferencesKey("FLIP") to 3,
                    intPreferencesKey("SERVER_PORT") to 80,
                    stringPreferencesKey("PIN") to "１２３４５６",
                ),
            )
            for (source in fixtures) {
                val context = context()
                if (source != null) seedLegacy(context, source)
                assertEquals(defaults(), MjpegSettings(context).data.first())
                assertFalse(savedPreferences(context).asMap().keys.any { it.name == "DATA" })
                assertConsumed(context)
            }
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun wholeCropAndTargetAreRejectedWhileIndependentImageChoicesSurvive() = runBlocking {
        withTimeout(10.seconds) {
            val invalidCrops = listOf(
                preferencesOf(intPreferencesKey("IMAGE_CROP_RIGHT") to -1),
                preferencesOf(stringPreferencesKey("IMAGE_CROP_TOP") to "3"),
                preferencesOf(intPreferencesKey("IMAGE_CROP") to 1),
            )
            for (invalid in invalidCrops) {
                val context = context()
                seedLegacy(context, merged(preferencesOf(
                    booleanPreferencesKey("IMAGE_CROP") to true,
                    intPreferencesKey("IMAGE_CROP_LEFT") to 9,
                    intPreferencesKey("JPEG_QUALITY") to 32,
                ), invalid))
                val image = MjpegSettings(context).data.first().image
                assertFalse(image.cropEnabled)
                assertEquals(CropInsetsPx.ZERO, image.cropInsets)
                assertEquals(32, image.jpegQuality)
            }
            val invalidTargets = listOf(
                preferencesOf(intPreferencesKey("RESOLUTION_WIDTH") to 0),
                preferencesOf(stringPreferencesKey("RESOLUTION_HEIGHT") to "360"),
                preferencesOf(intPreferencesKey("RESOLUTION_STRETCH") to 1),
            )
            for (invalid in invalidTargets) {
                val context = context()
                seedLegacy(context, merged(preferencesOf(
                    intPreferencesKey("RESOLUTION_WIDTH") to 640,
                    intPreferencesKey("RESOLUTION_HEIGHT") to 360,
                    intPreferencesKey("RESIZE_FACTOR") to 75,
                    intPreferencesKey("ROTATION") to 90,
                ), invalid))
                val image = MjpegSettings(context).data.first().image
                assertEquals(null, image.targetSize)
                assertEquals(ImageSettings.OutputSelection.Scale, image.outputSelection)
                assertEquals(75.0, image.scalePercent, 0.0)
                assertEquals(Rotation.Degrees90, image.rotation)
            }
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun targetsSwapOrientedBoundsUseNewContentDefaultAndRetainDormantScaleAndCrop() = runBlocking {
        withTimeout(10.seconds) {
            for ((rotation, expectedWidth, expectedHeight) in listOf(Triple(0, 640, 360), Triple(90, 360, 640), Triple(270, 360, 640))) {
                val context = context()
                seedLegacy(context, preferencesOf(
                    intPreferencesKey("RESOLUTION_WIDTH") to 640,
                    intPreferencesKey("RESOLUTION_HEIGHT") to 360,
                    intPreferencesKey("ROTATION") to rotation,
                    intPreferencesKey("RESIZE_FACTOR") to 75,
                    intPreferencesKey("IMAGE_CROP_RIGHT") to 7,
                ))
                val image = MjpegSettings(context).data.first().image
                assertEquals(OutputSize.TargetSize(expectedWidth, expectedHeight, OutputSize.ContentMode.AspectFit), image.targetSize)
                assertEquals(ImageSettings.OutputSelection.TargetSize, image.outputSelection)
                assertEquals(75.0, image.scalePercent, 0.0)
                assertFalse(image.cropEnabled)
                assertEquals(CropInsetsPx(0, 0, 7, 0), image.cropInsets)
            }
            val stretched = context()
            seedLegacy(stretched, preferencesOf(
                intPreferencesKey("RESOLUTION_WIDTH") to 640,
                intPreferencesKey("RESOLUTION_HEIGHT") to 360,
                booleanPreferencesKey("RESOLUTION_STRETCH") to true,
            ))
            assertEquals(OutputSize.ContentMode.Stretch, MjpegSettings(stretched).data.first().image.targetSize?.contentMode)
            val partial = context()
            seedLegacy(partial, preferencesOf(intPreferencesKey("RESOLUTION_WIDTH") to 640))
            assertEquals(null, MjpegSettings(partial).data.first().image.targetSize)
            assertFalse(savedPreferences(partial).asMap().keys.any { it.name == "DATA" })
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun anyInvalidFilterMemberRejectsWholeFilterWithoutRejectingPort() = runBlocking {
        withTimeout(10.seconds) {
            val invalidMembers = listOf(
                preferencesOf(booleanPreferencesKey("ENABLE_IPV4") to false, booleanPreferencesKey("ENABLE_IPV6") to false),
                preferencesOf(stringPreferencesKey("ENABLE_IPV6") to "true"),
                preferencesOf(intPreferencesKey("INTERFACE_FILTER") to 16),
                preferencesOf(stringPreferencesKey("ADDRESS_FILTER") to "4"),
            )
            for (invalid in invalidMembers) {
                val context = context()
                seedLegacy(context, merged(preferencesOf(
                    intPreferencesKey("INTERFACE_FILTER") to 2,
                    intPreferencesKey("ADDRESS_FILTER") to 4,
                    booleanPreferencesKey("ENABLE_IPV6") to true,
                    intPreferencesKey("SERVER_PORT") to 9000,
                ), invalid))
                val actual = MjpegSettings(context).data.first().network
                assertEquals(NetworkSettings().filter, actual.filter)
                assertEquals(9000, actual.httpPort)
                assertFalse(Json.parseToJsonElement(savedSettingsJson(context)).jsonObject.getValue("network").jsonObject.containsKey("filter"))
            }
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun zeroMasksAndActualFamilyFlagsMapWithoutAddingLinkLocal() = runBlocking {
        withTimeout(10.seconds) {
            val context = context()
            seedLegacy(context, preferencesOf(
                intPreferencesKey("INTERFACE_FILTER") to 0,
                intPreferencesKey("ADDRESS_FILTER") to 0,
                booleanPreferencesKey("ENABLE_IPV4") to false,
                booleanPreferencesKey("ENABLE_IPV6") to true,
            ))
            val filter = MjpegSettings(context).data.first().network.filter
            assertEquals(setOf(AddressFamily.Ipv6), filter.families)
            assertEquals(setOf(InterfaceType.Wifi, InterfaceType.Ethernet, InterfaceType.Mobile, InterfaceType.Vpn, InterfaceType.Other), filter.interfaceTypes)
            assertEquals(setOf(AddressCategory.Private, AddressCategory.NonPrivate, AddressCategory.Loopback), filter.categories)
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun historicalFiltersConvertOnlyWhenModernKeysAreRawAbsent() = runBlocking {
        withTimeout(10.seconds) {
            val historical = preferencesOf(
                booleanPreferencesKey("USE_WIFI_ONLY") to true,
                booleanPreferencesKey("LOCAL_HOST_ONLY") to false,
            )
            val legacyOnly = context()
            seedLegacy(legacyOnly, historical)
            val filter = MjpegSettings(legacyOnly).data.first().network.filter
            assertEquals(setOf(InterfaceType.Wifi), filter.interfaceTypes)
            assertEquals(setOf(AddressCategory.Private, AddressCategory.NonPrivate), filter.categories)
            val modern = context()
            seedLegacy(modern, merged(historical, preferencesOf(
                intPreferencesKey("INTERFACE_FILTER") to 4,
                intPreferencesKey("ADDRESS_FILTER") to 1,
            )))
            val modernFilter = MjpegSettings(modern).data.first().network.filter
            assertEquals(setOf(InterfaceType.Ethernet), modernFilter.interfaceTypes)
            assertEquals(setOf(AddressCategory.Private), modernFilter.categories)
            val invalidModern = context()
            seedLegacy(invalidModern, merged(historical, preferencesOf(stringPreferencesKey("INTERFACE_FILTER") to "4")))
            assertEquals(NetworkSettings().filter, MjpegSettings(invalidModern).data.first().network.filter)
            val invalidHistorical = context()
            seedLegacy(invalidHistorical, merged(historical, preferencesOf(stringPreferencesKey("ENABLE_LOCAL_HOST") to "true")))
            assertEquals(NetworkSettings().filter, MjpegSettings(invalidHistorical).data.first().network.filter)
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun frameRateConversionPreservesActualOldDefaultAndHandlesNewBounds() = runBlocking {
        withTimeout(10.seconds) {
            val fixtures = listOf(
                30 to FrameRate.MaxFps(30), 120 to FrameRate.MaxFps(120),
                -3600 to FrameRate.SamplingInterval(3600.seconds),
                121 to FrameRate.Auto, -3601 to FrameRate.Auto, Int.MIN_VALUE to FrameRate.Auto,
            )
            for ((legacy, expected) in fixtures) {
                val context = context()
                seedLegacy(context, preferencesOf(intPreferencesKey("MAX_FPS") to legacy))
                assertEquals(expected, MjpegSettings(context).data.first().image.frameRate)
            }
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun existingPayloadWinsEvenWhenInvalidAndConsumesLegacySource() = runBlocking {
        withTimeout(10.seconds) {
            for (payload in listOf("""{"web":{"title":"Existing"},"network":{"httpPort":9500}}""", "not json")) {
                val context = context()
                seedLegacy(context, preferencesOf(intPreferencesKey("SERVER_PORT") to 9000))
                seedSettings(context, payload)
                val expected = if (payload == "not json") defaults() else defaults().copy(network = NetworkSettings(httpPort = 9500), web = WebPageSettings(title = "Existing"))
                assertEquals(expected, MjpegSettings(context).data.first())
                assertConsumed(context)
            }
            val wrongType = context()
            seedLegacy(wrongType, preferencesOf(intPreferencesKey("SERVER_PORT") to 9000))
            writePreferences(wrongType.preferencesDataStoreFile("mjpeg"), preferencesOf(booleanPreferencesKey("DATA") to true))
            assertEquals(defaults(), MjpegSettings(wrongType).data.first())
            assertConsumed(wrongType)
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun consumedSourcePreventsRepeatUnlessLegacyFileIsRecreated() = runBlocking {
        withTimeout(10.seconds) {
            val context = context()
            seedLegacy(context, preferencesOf(intPreferencesKey("SERVER_PORT") to 9000))
            assertEquals(9000, MjpegSettings(context).data.first().network.httpPort)
            assertConsumed(context)
            val fresh = copyDestination(context)
            assertEquals(9000, MjpegSettings(fresh).data.first().network.httpPort)
            // A recreated/restored source is another attempt, with no marker suppressing it.
            val replaced = context()
            seedLegacy(replaced, preferencesOf(intPreferencesKey("SERVER_PORT") to 9000))
            writePreferences(replaced.preferencesDataStoreFile("mjpeg"), emptyPreferences())
            assertEquals(9000, MjpegSettings(replaced).data.first().network.httpPort)
            assertConsumed(replaced)
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun corruptSourceIsDeletedWithoutImport() = runBlocking {
        withTimeout(10.seconds) {
            val context = context()
            val source = context.preferencesDataStoreFile("MJPEG_settings")
            check(source.parentFile!!.mkdirs())
            source.writeBytes(byteArrayOf(0))
            assertEquals(defaults(), MjpegSettings(context).data.first())
            assertConsumed(context)
            assertFalse(savedPreferences(context).asMap().keys.any { it.name == "DATA" })
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun sourceReadAndDestinationWriteFailuresConsumeSourceAndCannotRetryImport() = runBlocking {
        withTimeout(10.seconds) {
            val unreadable = context()
            val source = unreadable.preferencesDataStoreFile("MJPEG_settings")
            check(source.mkdirs())
            val settings = MjpegSettings(unreadable)
            assertThrows(IOException::class.java) { runBlocking { settings.updateData { this } } }
            assertFalse(unreadable.preferencesDataStoreFile("mjpeg").exists())
            assertConsumed(unreadable)
            assertEquals(defaults(), settings.data.first())

            val blocked = context()
            seedLegacy(blocked, preferencesOf(intPreferencesKey("SERVER_PORT") to 9100))
            val destination = blocked.preferencesDataStoreFile("mjpeg")
            val scratch = File(destination.absolutePath + ".tmp")
            check(scratch.mkdir())
            val obstacle = File(scratch, "blocker").apply { writeText("Fixture storage fault") }
            val retrying = MjpegSettings(blocked)
            assertThrows(IOException::class.java) { runBlocking { retrying.updateData { this } } }
            assertFalse(destination.exists())
            assertConsumed(blocked)
            check(obstacle.delete())
            check(scratch.delete())
            assertEquals(defaults(), retrying.data.first())
            assertFalse(savedPreferences(blocked).asMap().keys.any { it.name == "DATA" })
        }
    }

    // Cases: MJPEG-SET-12
    @Test
    fun exactDeletionFailureIsVisibleAndPreservesPrimaryReadFailure() {
        val context = context()
        val source = context.preferencesDataStoreFile("MJPEG_settings")
        check(source.mkdirs())
        val child = File(source, "must-not-delete-recursively").apply { writeText("Fixture storage fault") }
        val settings = MjpegSettings(context)
        val failure = assertThrows(IOException::class.java) { runBlocking { settings.updateData { this } } }
        assertTrue(child.isFile)
        // Coroutine stack-trace recovery can copy the exception and retain the original as its cause.
        val primary = generateSequence<Throwable>(failure) { it.cause }.firstOrNull { cause ->
            cause.suppressed.any { it.message == "Failed to delete legacy MJPEG preferences" }
        }
        assertTrue("Cleanup failure must remain attached to the primary read failure", primary is IOException)
        assertFalse(primary!!.message == "Failed to delete legacy MJPEG preferences")
        assertEquals("Failed to delete legacy MJPEG preferences", primary.suppressed.single().message)
        assertFalse(context.preferencesDataStoreFile("mjpeg").exists())
    }

    private fun context(): IsolatedSettingsContext = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
    private fun defaults(): MjpegSettings.Data = MjpegSettings.Data(web = WebPageSettings(title = "Acme Tablet"))

    private suspend fun seedLegacy(context: IsolatedSettingsContext, source: Preferences) {
        writePreferences(context.preferencesDataStoreFile("MJPEG_settings"), source)
    }

    private suspend fun writePreferences(file: File, preferences: Preferences) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { PreferencesFileSerializer.writeTo(preferences, it) }
    }

    private suspend fun savedPreferences(context: IsolatedSettingsContext): Preferences {
        val file = context.preferencesDataStoreFile("mjpeg")
        return if (file.exists()) file.inputStream().use { PreferencesFileSerializer.readFrom(it) } else emptyPreferences()
    }

    private suspend fun assertConsumed(context: IsolatedSettingsContext) {
        assertFalse(context.preferencesDataStoreFile("MJPEG_settings").exists())
        assertTrue("Migration adds no completion metadata", savedPreferences(context).asMap().keys.all { it.name == "DATA" })
    }

    private fun copyDestination(original: IsolatedSettingsContext): IsolatedSettingsContext {
        val fresh = context()
        val destination = fresh.preferencesDataStoreFile("mjpeg")
        check(destination.parentFile!!.mkdirs())
        original.preferencesDataStoreFile("mjpeg").copyTo(destination)
        return fresh
    }

    private fun merged(base: Preferences, overrides: Preferences): Preferences = base.toMutablePreferences().apply {
        for ((key, value) in overrides.asMap()) {
            when (value) {
                is Boolean -> this[booleanPreferencesKey(key.name)] = value
                is Int -> this[intPreferencesKey(key.name)] = value
                is String -> this[stringPreferencesKey(key.name)] = value
                else -> error("Unsupported fixture value")
            }
        }
    }.toPreferences()
}
