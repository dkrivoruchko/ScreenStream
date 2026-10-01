package info.dvkr.screenstream.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.preferencesDataStoreFile
import info.dvkr.screenstream.common.settings.JsonPreferencesStore
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
 * App-wide choices stored in `app_settings.preferences_pb`. Creation starts one process-owned
 * observation asynchronously; [initialize] awaits its first value before [data] is read, so
 * callers never see a seeded default.
 *
 * @param context supplies the app settings DataStore file.
 * @param dispatcher runs settings observation and writes, defaulting to IO.
 */
@Singleton(createdAtStart = true)
public class AppSettings(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Values used when the JSON settings payload is absent or unrecoverable. */
    public object Default {
        public const val NIGHT_MODE: Int = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        public const val DYNAMIC_THEME: Boolean = false
    }

    /** The app-wide settings stored together as one immutable value. */
    @Immutable
    @Serializable
    public data class Data(
        public val nightMode: Int = Default.NIGHT_MODE,
        public val dynamicTheme: Boolean = Default.DYNAMIC_THEME,
    )

    private val storage: JsonPreferencesStore<Data> = JsonPreferencesStore(
        serializer = Data.serializer(),
        defaultValue = Data(),
        produceFile = { context.preferencesDataStoreFile("app_settings") },
    )
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile
    private var readyData: StateFlow<Data>? = null
    private val preparation: Deferred<Unit> = scope.async {
        readyData = storage.data.stateIn(scope)
    }

    /**
     * Awaits the first stored or recovered value from preparation started at creation. Concurrent
     * callers share one preparation owned by this instance; cancelling a caller does not cancel it.
     * An unexpected initial source failure reaches the owning scope's uncaught-exception handling
     * and remains available to awaiting callers.
     */
    public suspend fun initialize(): Unit = preparation.await()

    /**
     * Latest published settings after [initialize] succeeds. Its value may briefly lag a completed
     * [updateData] until observation publishes the change. A fallback value remains until a
     * successful write restarts observation.
     *
     * @throws IllegalStateException if settings have not been initialized successfully.
     */
    public val data: StateFlow<Data>
        get() = checkNotNull(readyData) { "AppSettings.initialize() must complete before reading data" }

    /**
     * Applies [transform] to the latest stored settings on [dispatcher]. Once the edit enters
     * [NonCancellable], cancelling the caller does not stop that edit; storage and transform errors
     * still propagate when the caller remains active.
     */
    public suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData(transform)
    }
}
