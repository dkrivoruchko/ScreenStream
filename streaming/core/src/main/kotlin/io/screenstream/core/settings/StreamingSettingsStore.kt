package io.screenstream.core.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.preferencesDataStoreFile
import info.dvkr.screenstream.common.settings.JsonPreferencesStore
import io.screenstream.core.module.StreamingModule
import io.screenstream.foundation.logging.Logger
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf

/**
 * Process-wide streaming settings in `streaming_settings.preferences_pb`, created when Koin starts.
 * Loading begins at construction; call [initialize] before reading [data].
 */
@Singleton(createdAtStart = true)
internal class StreamingSettingsStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KoinComponent {
    private val log: Logger by inject { parametersOf(this) }

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
        failureReporter = { stage, message, cause -> log.e(stage, message, cause) },
    )
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var readyData: StateFlow<Data>? = null
    private val preparation: Deferred<Unit> = scope.async {
        readyData = storage.data.stateIn(scope)
    }

    /** Suspends until the first stored value is loaded; cancelling a caller does not cancel loading. */
    internal suspend fun initialize(): Unit = preparation.await()

    /** Latest loaded value; throws before [initialize] completes and can briefly lag a completed edit. */
    internal val data: StateFlow<Data>
        get() = checkNotNull(readyData) { "StreamingSettingsStore.initialize() must complete before reading data" }

    /** Atomically transforms the stored value; the edit completes even if the caller is cancelled. Failures propagate. */
    internal suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData(transform)
    }

    internal val screenCaptureEducationCompleted: Boolean
        get() = data.value.screenCaptureEducationCompleted

    internal suspend fun markEducationCompleted(): Unit = updateData {
        copy(screenCaptureEducationCompleted = true)
    }
}
