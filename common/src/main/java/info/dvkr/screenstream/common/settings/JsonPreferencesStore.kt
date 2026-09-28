package info.dvkr.screenstream.common.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Stores an immutable [T] as JSON in a Preferences DataStore file. Create one instance per file
 * and reuse it. A missing payload yields [defaultValue]. A raw JSON payload matching the last
 * successfully decoded one reuses its model. Unknown JSON fields are ignored on read; properties
 * equal to their serializer defaults are omitted on write.
 *
 * For example:
 * ```kotlin
 * val store = JsonPreferencesStore(
 *     serializer = Settings.serializer(),
 *     defaultValue = Settings(),
 *     produceFile = { context.preferencesDataStoreFile("settings") },
 * )
 * store.updateData { copy(enabled = true) }
 * ```
 *
 * @param T immutable model type stored in this file.
 * @param serializer serializer for [T].
 * @param defaultValue value used when the JSON preference is absent.
 * @param produceFile returns the same `.preferences_pb` file for this store.
 * @param key name of the JSON string preference, defaulting to `DATA`.
 */
public class JsonPreferencesStore<T : Any>(
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    produceFile: () -> File,
    key: String = "DATA",
) {
    private val payloadKey: Preferences.Key<String> = stringPreferencesKey(key)
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { cause ->
            logFailure("read", cause, "Attempting to restore defaults")
            emptyPreferences()
        },
        produceFile = produceFile,
    )
    private val successfulUpdates: MutableStateFlow<Long> = MutableStateFlow(0L)
    private val decodeLock: Any = Any()
    private var cachedPayload: String? = null
    private var cachedValue: T? = null

    /**
     * Emits the first real value after reading the file, then subsequent stored values. A
     * recoverable read or decode failure gets one repair attempt. Successful repair resumes
     * reading the latest file; failed repair emits [defaultValue] until a normally completed
     * [updateData] or a new collection. Equal consecutive values are suppressed. Cancellation and
     * unrelated failures propagate.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val data: Flow<T> = successfulUpdates
        .flatMapLatest { observeStore() }
        .distinctUntilChanged()

    /**
     * Applies [transform] to the latest model in one atomic Preferences transaction. A missing or
     * invalid payload supplies [defaultValue] as input; an invalid payload is replaced by the
     * encoded result even when the result equals the default. After the edit returns normally, the
     * result is durable and subscribers waiting after failed repair are notified. Transform,
     * encoding, and storage failures propagate. Cancellation propagates but does not prove that a
     * write was not committed.
     */
    public suspend fun updateData(transform: T.() -> T): Unit {
        dataStore.edit { preferences ->
            val stored: T? = try {
                decodeStored(preferences)
            } catch (cause: SerializationException) {
                logFailure("update/decode", cause, "Attempting to restore defaults")
                null
            }
            val updated = (stored ?: defaultValue).transform()
            if (updated != stored) {
                preferences[payloadKey] = JSON.encodeToString(serializer, updated)
            }
        }
        successfulUpdates.update { it + 1 }
    }

    private fun observeStore(): Flow<T> {
        var canAttemptRepair = true
        return dataStore.data
            .map { preferences ->
                val decoded = decodeStored(preferences)
                canAttemptRepair = true
                decoded
            }
            .retryWhen { cause, _ ->
                val stage = if (cause is SerializationException) "decode" else "read"
                when {
                    !isRecoverable(cause) -> false
                    cause is CorruptionException || !canAttemptRepair -> {
                        logFailure(stage, cause, "Failed to restore defaults")
                        false
                    }

                    else -> {
                        canAttemptRepair = false
                        logFailure(stage, cause, "Attempting to restore defaults")
                        attemptRestoreDefaults()
                    }
                }
            }
            .catch { cause ->
                if (!isRecoverable(cause)) throw cause
                emit(defaultValue)
            }
    }

    private fun decodeStored(preferences: Preferences): T {
        val payload = try {
            preferences[payloadKey]
        } catch (cause: ClassCastException) {
            throw SerializationException("Stored payload has unexpected type", cause)
        }
        if (payload == null) return defaultValue
        return synchronized(decodeLock) {
            val cached = cachedValue
            if (payload == cachedPayload && cached != null) {
                cached
            } else {
                val decoded = try {
                    JSON.decodeFromString(serializer, payload)
                } catch (cause: SerializationException) {
                    throw cause
                } catch (cause: IllegalArgumentException) {
                    throw SerializationException("Stored payload is invalid", cause)
                }
                cachedPayload = payload
                cachedValue = decoded
                decoded
            }
        }
    }

    private suspend fun attemptRestoreDefaults(): Boolean = try {
        dataStore.edit { preferences ->
            try {
                decodeStored(preferences)
            } catch (_: SerializationException) {
                preferences -= payloadKey
            }
        }
        true
    } catch (cause: IOException) {
        logFailure("repair", cause, "Failed to restore defaults")
        false
    }

    private fun isRecoverable(cause: Throwable): Boolean = cause is IOException || cause is SerializationException

    private fun logFailure(stage: String, cause: Throwable, action: String): Unit {
        XLog.e(getLog(stage, "type=${cause.javaClass.simpleName}. $action"), cause)
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            exceptionsWithDebugInfo = false
        }
    }
}
