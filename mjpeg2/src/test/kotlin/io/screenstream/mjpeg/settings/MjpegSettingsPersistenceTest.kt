package io.screenstream.mjpeg.settings

import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import io.screenstream.mjpeg.IsolatedSettingsContext
import io.screenstream.mjpeg.savedSettingsJson
import io.screenstream.mjpeg.seedSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegSettingsPersistenceTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun initializeLoggerAndDevice() {
        XLog.init(Int.MAX_VALUE)
        ShadowBuild.setManufacturer("Acme")
        ShadowBuild.setModel("Tablet")
    }

    // Cases: MJPEG-SET-07
    @Test
    fun invalidExistingModulePayloadYieldsDefaultsWithoutImportingLegacyPreferences() = runBlocking {
        withTimeout(10.seconds) {
            val savedPayloads = listOf(
                "not json", "{}", """{"web":{"title":"Saved"},"network":{"httpPort":80}}""",
                """{"web":{"title":"Saved"},"network":{"filter":{"families":[],"interfaceTypes":["Wifi"],"categories":["Private"]}}}""",
                """{"web":{"title":"Saved"},"access":{"pin":{"value":"123"}}}""",
            )
            for (payload in savedPayloads) {
                val context = newSettingsContext()
                seedLegacyPreferences(context)
                seedSettings(context, payload)
                val settings = MjpegSettings(context)
                assertEquals(MjpegSettings.Data(web = WebPageSettings(title = "Acme Tablet")), settings.data.first())
            }
        }
    }

    // Cases: MJPEG-SET-07
    @Test
    fun identityUpdateRepairsInvalidPayloadBeforeAnyCollection() = runBlocking {
        withTimeout(10.seconds) {
            val expected = MjpegSettings.Data(web = WebPageSettings(title = "Acme Tablet"))
            for (payload in listOf("not json", """{"web":{"title":"Saved"},"network":{"httpPort":80}}""")) {
                val context = newSettingsContext()
                seedSettings(context, payload)
                val settings = MjpegSettings(context)

                settings.updateData {
                    assertEquals("Invalid payload must supply defaults to the transform", expected, this)
                    this
                }

                assertEquals(expected, Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)))
                assertEquals(expected, settings.data.first())
            }
        }
    }

    // Cases: MJPEG-SET-07
    @Test
    fun unreadablePreferencesYieldDefaultsWithoutClaimingSuccessfulRepair() = runBlocking {
        withTimeout(5.seconds) {
            val context = newSettingsContext()
            val file = context.preferencesDataStoreFile("mjpeg")
            check(file.mkdirs())
            val settings = MjpegSettings(context)

            assertEquals(MjpegSettings.Data(web = WebPageSettings(title = "Acme Tablet")), settings.data.first())
            assertTrue("Failed read repair must not invent a saved preferences file", file.isDirectory)
        }
    }

    // Cases: MJPEG-SET-07
    @Test
    fun corruptPreferencesAreReplacedAndLaterSettingsCanBeSaved() = runBlocking {
        withTimeout(5.seconds) {
            val context = newSettingsContext()
            val file = context.preferencesDataStoreFile("mjpeg")
            check(checkNotNull(file.parentFile).mkdirs())
            // Protobuf field tag zero is invalid, distinct from an empty valid preferences file.
            file.writeBytes(byteArrayOf(0))
            val settings = MjpegSettings(context)
            val expected = MjpegSettings.Data(web = WebPageSettings(title = "Acme Tablet"))

            assertEquals(expected, settings.data.first())
            val repaired = file.inputStream().use { PreferencesFileSerializer.readFrom(it) }
            assertTrue("Corruption recovery must durably replace the malformed preferences", repaired.asMap().isEmpty())
            settings.updateData { copy(web = web.copy(title = "After recovery")) }
            val saved = expected.copy(web = expected.web.copy(title = "After recovery"))
            assertEquals(saved, Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)))
            assertEquals(saved, settings.data.first())
        }
    }

    // Cases: MJPEG-SET-08
    @Test
    fun storageWriteFailurePropagatesWithoutChangingSavedSettings() {
        val context = newSettingsContext()
        val baseline = MjpegSettings.Data(network = NetworkSettings(httpPort = 9000), web = WebPageSettings(title = "Before failure"))
        runBlocking { seedSettings(context, """{"network":{"httpPort":9000},"web":{"title":"Before failure"}}""") }
        val file = context.preferencesDataStoreFile("mjpeg")
        val savedBytes = file.readBytes()
        // DataStore 1.2.1 FileStorage writes through <path>.tmp. Arrange the fault
        // before constructing its owner; a nonempty directory cannot be opened or deleted as a file.
        val blockedScratch = File(file.absolutePath + ".tmp")
        check(blockedScratch.mkdir())
        File(blockedScratch, "blocker").writeText("Fixture storage fault")
        val settings = MjpegSettings(context)
        runBlocking { withTimeout(5.seconds) { assertEquals(baseline, settings.data.first()) } }
        var transforms = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                withTimeout(5.seconds) {
                    settings.updateData {
                        transforms++
                        assertEquals(baseline, this)
                        copy(web = web.copy(title = "Must not be saved"))
                    }
                }
            }
        }

        assertEquals("The failing save must enter its transform exactly once", 1, transforms)
        assertArrayEquals(savedBytes, file.readBytes())
        runBlocking {
            withTimeout(5.seconds) {
                assertEquals(baseline, Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)))
                assertEquals(baseline, settings.data.first())
            }
        }
    }

    // Cases: MJPEG-SET-03, MJPEG-SET-04
    @Test
    fun savedSchemaAcceptsUnknownFieldsOptionalDefaultsAndOldPageAlias() = runBlocking {
        withTimeout(5.seconds) {
            val context = newSettingsContext()
            seedSettings(
                context,
                """{"futureGroup":{"version":2},"web":{"title":"","retainImageOnReconnect":false,"futurePage":true},"access":{"pinPolicy":"Permanent","pin":{"value":"000007"}}}"""
            )
            val actual = MjpegSettings(context).data.first()
            assertEquals("", actual.web.title)
            assertFalse(actual.web.retainImageOnMediaFailure)
            assertEquals("000007", actual.access.pin?.value)
            assertEquals(AccessSettings.PinPolicy.Permanent, actual.access.pinPolicy)
            assertFalse(actual.access.pinEnabled)
            assertEquals(8080, actual.network.httpPort)
            assertEquals(80, actual.image.jpegQuality)
        }
    }

    // Cases: MJPEG-SET-04, MJPEG-SET-10
    @Test
    fun savedDefaultTitleAndDormantPreferencesSurviveFreshInstanceAtNewPath() = runBlocking {
        withTimeout(5.seconds) {
            val original = newSettingsContext()
            val settings = MjpegSettings(original)
            settings.updateData {
                copy(
                    access = access.copy(hidePinOnCaptureStart = false),
                    behavior = behavior.copy(keepScreenAwake = true, stopOnScreenOff = true, notifySlowViewers = false),
                )
            }
            val savedJson = savedSettingsJson(original)
            assertEquals("Acme Tablet", Json.parseToJsonElement(savedJson).jsonObject.getValue("web").jsonObject.getValue("title").jsonPrimitive.content)
            val fresh = newSettingsContext()
            val destination = fresh.preferencesDataStoreFile("mjpeg")
            check(destination.parentFile!!.mkdirs())
            original.preferencesDataStoreFile("mjpeg").copyTo(destination)
            ShadowBuild.setManufacturer("Later")
            ShadowBuild.setModel("Device")
            val restored = MjpegSettings(fresh).data.first()
            assertEquals("Acme Tablet", restored.web.title)
            assertFalse(restored.access.hidePinOnCaptureStart)
            assertTrue(restored.behavior.keepScreenAwake)
            assertTrue(restored.behavior.stopOnScreenOff)
            assertFalse(restored.behavior.notifySlowViewers)
            assertEquals(settings.data.first(), restored)
        }
    }

    // Cases: MJPEG-SET-06
    @Test
    fun enablingProtectionCreatesAndDurablySavesPinWhileLaterEditsRetainIt() = runBlocking {
        withTimeout(5.seconds) {
            val context = newSettingsContext()
            val settings = MjpegSettings(context)
            assertEquals(null, settings.data.first().access.pin)
            settings.updateData { copy(access = access.copy(pinEnabled = true)) }
            val pin = checkNotNull(settings.data.first().access.pin)
            assertTrue(AccessSettings.isValidPin(pin.value))
            assertEquals("<redacted>", pin.toString())
            val saved = Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context))
            assertEquals(pin, saved.access.pin)
            assertTrue(saved.access.pinEnabled)
            settings.updateData { copy(web = web.copy(title = "Edited")) }
            settings.updateData { copy(access = access.copy(pinEnabled = false)) }
            assertEquals(pin, settings.data.first().access.pin)
            settings.updateData { copy(access = access.copy(pinEnabled = true)) }
            assertEquals(pin, settings.data.first().access.pin)
        }
    }

    // Cases: MJPEG-SET-05
    @Test
    fun independentEditsTransformLatestSnapshotAtomically() = runBlocking {
        withTimeout(10.seconds) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val context = newSettingsContext()
                val settings = MjpegSettings(context, dispatcher)
                val originalSnapshot = settings.data.first()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val first = async(Dispatchers.Default) {
                    settings.updateData {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "First settings transform was not released" }
                        copy(network = network.copy(httpPort = 9000))
                    }
                }
                var second: Job? = null
                try {
                    assertTrue("First settings transform did not enter", entered.await(5, TimeUnit.SECONDS))
                    // Enter the second public call before releasing the first transaction.
                    val secondSave = async(start = CoroutineStart.UNDISPATCHED) {
                        settings.updateData {
                            assertEquals(9000, network.httpPort)
                            copy(web = web.copy(title = "Latest edit"))
                        }
                    }
                    second = secondSave
                    release.countDown()
                    first.await()
                    secondSave.await()
                    val expected =
                        originalSnapshot.copy(network = originalSnapshot.network.copy(httpPort = 9000), web = originalSnapshot.web.copy(title = "Latest edit"))
                    assertEquals(expected, settings.data.first())
                    assertEquals(expected, Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)))
                } finally {
                    release.countDown()
                    withContext(NonCancellable) {
                        withTimeout(5.seconds) {
                            first.join()
                            second?.join()
                        }
                    }
                }
            }
        }
    }

    // Cases: MJPEG-SET-08
    @Test
    fun enteredSaveContinuesAfterOriginalCallerCancellation() = runBlocking {
        withTimeout(10.seconds) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val context = newSettingsContext()
                val settings = MjpegSettings(context, dispatcher)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val caller = launch(Dispatchers.Default) {
                    settings.updateData {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Cancelled caller's settings transform was not released" }
                        copy(web = web.copy(title = "Committed after cancellation"))
                    }
                }
                try {
                    assertTrue("Settings transform did not enter before caller cancellation", entered.await(5, TimeUnit.SECONDS))
                    caller.cancel()
                    release.countDown()
                    caller.join()
                    assertEquals("Committed after cancellation", settings.data.first().web.title)
                    assertEquals("Committed after cancellation", Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)).web.title)
                } finally {
                    release.countDown()
                    withContext(NonCancellable) {
                        withTimeout(5.seconds) { caller.join() }
                    }
                }
            }
        }
    }

    // Cases: MJPEG-SET-08
    @Test
    fun throwingTransformPropagatesAndPreservesSavedSnapshot() {
        val context = newSettingsContext()
        val settings = MjpegSettings(context)
        runBlocking { withTimeout(5.seconds) { settings.updateData { copy(web = web.copy(title = "Before failure")) } } }
        val failure = IllegalStateException("Fixture transform failure")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { withTimeout(5.seconds) { settings.updateData { throw failure } } }
        }
        assertEquals(failure.javaClass, (thrown as Any).javaClass)
        assertEquals(failure.message, thrown.message)
        // Coroutine stack recovery may copy the thrown exception and retain the original as its cause.
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
        runBlocking {
            withTimeout(5.seconds) {
                assertEquals("Before failure", settings.data.first().web.title)
                assertEquals("Before failure", Json.decodeFromString<MjpegSettings.Data>(savedSettingsJson(context)).web.title)
            }
        }
    }

    private fun newSettingsContext(): IsolatedSettingsContext =
        IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())

    private suspend fun seedLegacyPreferences(context: IsolatedSettingsContext) {
        val file = context.preferencesDataStoreFile("MJPEG_settings")
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        file.outputStream().use {
            PreferencesFileSerializer.writeTo(
                preferencesOf(
                    intPreferencesKey("SERVER_PORT") to 9000,
                    intPreferencesKey("JPEG_QUALITY") to 35,
                    booleanPreferencesKey("KEEP_AWAKE") to true,
                    booleanPreferencesKey("ENABLE_PIN") to true,
                    stringPreferencesKey("PIN") to "001234",
                ),
                it,
            )
        }
    }
}
