package io.screenstream.foundation.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class JsonPreferencesStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun missingPayloadEmitsDefaultThenUpdatesTheExistingSubscriber() = runStoreTest {
        val store = store(newFile())
        val values = Channel<Settings>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) { store.data.collect { values.send(it) } }
        try {
            assertEquals(Settings(), values.receive())
            store.updateData { copy(count = 4) }
            assertEquals(Settings(4), values.receive())
        } finally {
            collection.cancelAndJoin()
        }
    }

    @Test
    fun firstEmissionUsesMigratedCustomPayloadAndIgnoresUnknownFields() = runStoreTest {
        val file = newFile()
        val customKey = stringPreferencesKey("CUSTOM")
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":99}", customKey to "{\"count\":3}"))
        val store = store(file, key = customKey.name, migrations = listOf(migration { preferences ->
            preferences.toMutablePreferences().apply {
                this[customKey] = "{\"count\":7,\"futureField\":true}"
            }
        }))

        assertEquals(Settings(7), store.data.first())
        assertEquals("{\"count\":99}", snapshot(file)[payloadKey])
    }

    @Test
    fun writingSerializerDefaultsOmitsTheirProperties() = runStoreTest {
        val file = newFile()
        val store = store(file)
        store.updateData { Settings(5) }
        store.updateData { Settings() }

        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(checkNotNull(snapshot(file)[payloadKey])))
        assertEquals(Settings(), store.data.first())
    }

    @Test
    fun unchangedPayloadReusesDecodedModelAcrossReadsAndNoOpUpdate() = runStoreTest {
        val file = newFile()
        val payload = "{ \"count\": 7 }"
        seed(file, mutablePreferencesOf(payloadKey to payload))
        val decodes = AtomicInteger()
        val serializer = object : KSerializer<Settings> by SettingsSerializer {
            override fun deserialize(decoder: Decoder): Settings {
                decodes.incrementAndGet()
                return SettingsSerializer.deserialize(decoder)
            }
        }
        val store = store(file, serializer = serializer)
        val first = store.data.first()
        assertEquals(Settings(7), first)
        assertSame(first, store.data.first())
        store.updateData { this }

        assertSame(first, store.data.first())
        assertEquals(1, decodes.get())
        assertEquals(payload, snapshot(file)[payloadKey])
    }

    @Test
    fun concurrentTransformsUseLatestValueAndCompletedWritesAreDurable() = runStoreTest {
        val file = newFile()
        val store = store(file)
        val start = CompletableDeferred<Unit>()
        val updates = List(32) {
            async(Dispatchers.Default) {
                start.await()
                store.updateData { copy(count = count + 1) }
            }
        }
        start.complete(Unit)
        updates.awaitAll()

        assertEquals(Settings(32), store.data.first())
        assertEquals(Settings(32), decodeSnapshot(file))
    }

    @Test
    fun transformFailuresIncludingCancellationPropagateWithoutChangingStoredValue() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val store = store(file)
        val failures = listOf(IOException("transform"), IllegalArgumentException("transform"), CancellationException("transform"))
        for (failure in failures) {
            assertFailureMatches(failure, failureFrom { store.updateData { throw failure } })
            assertEquals(Settings(7), store.data.first())
            assertEquals(Settings(7), decodeSnapshot(file))
        }
    }

    @Test
    fun encodingFailurePropagatesAndAbortsTheTransaction() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val failure = SerializationException("cannot encode")
        val serializer = object : KSerializer<Settings> by SettingsSerializer {
            override fun serialize(encoder: Encoder, value: Settings) {
                if (value.count == 8) throw failure
                SettingsSerializer.serialize(encoder, value)
            }
        }
        val store = store(file, serializer = serializer)

        assertFailureMatches(failure, failureFrom { store.updateData { Settings(8) } })
        assertEquals(Settings(7), store.data.first())
        assertEquals(Settings(7), decodeSnapshot(file))
        store.updateData { copy(count = count + 2) }
        assertEquals(Settings(9), decodeSnapshot(file))
    }

    @Test
    fun invalidJsonAndWrongPreferenceTypeAreRemovedWithoutLosingOtherPreferences() = runStoreTest {
        val untouchedKey = stringPreferencesKey("UNRELATED")
        val invalidPreferences = listOf(
            mutablePreferencesOf(payloadKey to "{broken", untouchedKey to "keep"),
            mutablePreferencesOf(intPreferencesKey(payloadKey.name) to 42, untouchedKey to "keep"),
        )
        for (preferences in invalidPreferences) {
            val file = newFile()
            seed(file, preferences)
            assertEquals(Settings(), store(file).data.first())
            val repaired = snapshot(file)
            assertFalse(repaired.asMap().containsKey(payloadKey))
            assertEquals("keep", repaired[untouchedKey])
        }
    }

    @Test
    fun invalidPayloadUpdateUsesDefaultAndPersistsEvenAnEqualDefaultResult() = runStoreTest {
        for (preferences in listOf(
            mutablePreferencesOf(payloadKey to "{broken"),
            mutablePreferencesOf(intPreferencesKey(payloadKey.name) to 42),
        )) {
            val file = newFile()
            seed(file, preferences)
            val store = store(file)
            store.updateData {
                assertEquals(Settings(), this)
                this
            }

            assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(checkNotNull(snapshot(file)[payloadKey])))
            assertEquals(Settings(), store.data.first())
        }
    }

    @Test
    fun corruptPreferencesFileIsReplacedWithDefaults() = runStoreTest {
        val file = newFile()
        file.writeBytes(byteArrayOf(0)) // Invalid protobuf tag, rather than malformed JSON inside valid preferences.

        assertEquals(Settings(), store(file).data.first())
        assertTrue(snapshot(file).asMap().isEmpty())
    }

    @Test
    fun successfulReadRepairEmitsTheStoredValueWithoutATransientDefault() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val attempts = AtomicInteger()
        val store = store(file, migrations = listOf(migration { preferences ->
            if (attempts.incrementAndGet() == 1) throw IOException("temporarily unavailable")
            preferences
        }))

        assertEquals(Settings(7), store.data.first())
        assertEquals(Settings(7), decodeSnapshot(file))
    }

    @Test
    fun failedReadAndRepairEmitDefaultThenSuccessfulUpdateResumesTheSameSubscriber() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val attempts = AtomicInteger()
        val store = store(file, migrations = listOf(migration { preferences ->
            if (attempts.incrementAndGet() <= 2) throw IOException("migration unavailable")
            preferences
        }))
        val values = Channel<Settings>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) { store.data.collect { values.send(it) } }
        try {
            assertEquals(Settings(), values.receive())
            assertEquals(2, attempts.get())
            store.updateData { copy(count = count + 1) }
            assertEquals(Settings(8), values.receive())
            assertEquals(Settings(8), decodeSnapshot(file))
        } finally {
            collection.cancelAndJoin()
        }
    }

    @Test
    fun newCollectionRetriesAfterFailedRepair() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val attempts = AtomicInteger()
        val store = store(file, migrations = listOf(migration { preferences ->
            if (attempts.incrementAndGet() <= 2) throw IOException("migration unavailable")
            preferences
        }))

        assertEquals(Settings(), store.data.first())
        assertEquals(2, attempts.get())
        assertEquals(Settings(7), store.data.first())
    }

    @Test
    fun unrelatedReadFailurePropagatesWithoutRepair() = runStoreTest {
        val failure = IllegalStateException("migration")
        val attempts = AtomicInteger()
        val store = store(newFile(), migrations = listOf(migration {
            attempts.incrementAndGet()
            throw failure
        }))

        assertFailureMatches(failure, failureFrom { store.data.first() })
        assertEquals(1, attempts.get())
    }

    @Test
    fun actualStorageFailurePropagatesAndALaterUpdateCanRecover() = runStoreTest {
        val file = newFile()
        assertTrue(file.mkdir())
        val store = store(file)

        assertTrue(failureFrom { store.updateData { Settings(3) } } is IOException)
        assertTrue(file.delete())
        store.updateData {
            assertEquals(Settings(), this)
            Settings(4)
        }
        assertEquals(Settings(4), decodeSnapshot(file))
    }

    @Test
    fun cancellingACollectorDuringInitialReadPreservesSubsequentReadsAndWrites() = runStoreTest {
        val file = newFile()
        seed(file, mutablePreferencesOf(payloadKey to "{\"count\":7}"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = store(file, migrations = listOf(migration { preferences ->
            entered.complete(Unit)
            release.await()
            preferences
        }))
        val values = Channel<Settings>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            store.data.collect { values.send(it) }
        }
        try {
            entered.await()
            collection.cancel()
            release.complete(Unit)
            collection.join()
            assertTrue(collection.isCancelled)
            assertTrue(values.tryReceive().isFailure)
            assertEquals(Settings(7), store.data.first())
            store.updateData { Settings(6) }
            assertEquals(Settings(6), store.data.first())
        } finally {
            release.complete(Unit)
            collection.cancelAndJoin()
        }
    }

    private fun store(
        file: File,
        serializer: KSerializer<Settings> = SettingsSerializer,
        key: String = payloadKey.name,
        migrations: List<DataMigration<Preferences>> = emptyList(),
    ): JsonPreferencesStore<Settings> = JsonPreferencesStore(
        serializer = serializer,
        defaultValue = Settings(),
        produceFile = { file },
        failureReporter = { _, _, _ -> },
        key = key,
        migrations = migrations,
    )

    private fun newFile(): File = File(temporaryFolder.newFolder(), "settings.preferences_pb")

    private suspend fun seed(file: File, preferences: Preferences) {
        withPreferencesStore(file) { it.updateData { preferences } }
    }

    private suspend fun snapshot(file: File): Preferences {
        // The wrapper owns its DataStore for life. Inspect a completed write through a different file.
        val copy = newFile()
        file.copyTo(copy)
        return withPreferencesStore(copy) { it.data.first() }
    }

    private suspend fun decodeSnapshot(file: File): Settings =
        Json.decodeFromString(SettingsSerializer, checkNotNull(snapshot(file)[payloadKey]))

    private suspend fun <T> withPreferencesStore(file: File, block: suspend (DataStore<Preferences>) -> T): T {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            return block(dataStore)
        } finally {
            // Release the seeded file before the wrapper opens it; never overlap DataStore instances.
            withContext(NonCancellable) { withTimeout(5.seconds) { job.cancelAndJoin() } }
        }
    }

    private fun migration(transform: suspend (Preferences) -> Preferences): DataMigration<Preferences> =
        object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean = true
            override suspend fun migrate(currentData: Preferences): Preferences = transform(currentData)
            override suspend fun cleanUp() = Unit
        }

    private fun runStoreTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        withTimeout(10.seconds, block)
    }

    private suspend fun failureFrom(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected operation to fail")
    }

    private fun assertFailureMatches(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
    }

    private data class Settings(val count: Int = 0)

    // A default-aware schema fixture, without a serialization compiler plugin just for this test.
    private object SettingsSerializer : KSerializer<Settings> {
        override val descriptor = buildClassSerialDescriptor("JsonPreferencesStoreTest.Settings") {
            element<Int>("count", isOptional = true)
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: Settings) = encoder.encodeStructure(descriptor) {
            if (value.count != 0 || shouldEncodeElementDefault(descriptor, 0)) {
                encodeIntElement(descriptor, 0, value.count)
            }
        }

        override fun deserialize(decoder: Decoder): Settings = decoder.decodeStructure(descriptor) {
            var count = 0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> count = decodeIntElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("Unexpected field index: $index")
                }
            }
            Settings(count)
        }
    }

    private companion object {
        private val payloadKey = stringPreferencesKey("DATA")
    }
}
