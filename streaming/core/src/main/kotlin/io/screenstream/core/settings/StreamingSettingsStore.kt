package io.screenstream.core.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.preferencesDataStoreFile
import info.dvkr.screenstream.common.settings.JsonPreferencesStore
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Singleton

/**
 * Process-owned streaming choices in `streaming_settings.preferences_pb`.
 * [initialize] awaits the first stored value. This store reads and writes its own preferences file;
 * former app preferences remain in their original store.
 */
@Singleton(createdAtStart = true)
internal class StreamingSettingsStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Immutable
    @Serializable
    internal data class Data(
        val selectedModuleId: StreamingModule.Id? = null,
        val screenCaptureEducationCompleted: Boolean = false,
    )

    private val storage: JsonPreferencesStore<Data> = JsonPreferencesStore(
        serializer = Data.serializer(),
        defaultValue = Data(),
        produceFile = { context.preferencesDataStoreFile("streaming_settings") },
    )
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var readyData: StateFlow<Data>? = null
    private val preparation: Deferred<Unit> = scope.async {
        readyData = storage.data.stateIn(scope)
    }

    /** Await the shared preparation; the settings owner continues after caller cancellation. */
    internal suspend fun initialize(): Unit = preparation.await()

    /** Latest published value after [initialize] completes, potentially lagging a completed edit. */
    internal val data: StateFlow<Data>
        get() = checkNotNull(readyData) { "StreamingSettingsStore.initialize() must complete before reading data" }

    /** Atomically transforms the latest stored value; an admitted edit survives caller cancellation. */
    internal suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData(transform)
    }

    internal val screenCaptureEducationCompleted: Boolean
        get() = data.value.screenCaptureEducationCompleted

    internal suspend fun markEducationCompleted(): Unit = updateData {
        copy(screenCaptureEducationCompleted = true)
    }
}
