package io.screenstream.mjpeg.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import info.dvkr.screenstream.common.settings.JsonPreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Singleton

/**
 * Process-owned MJPEG preferences in `mjpeg.preferences_pb`, under the DATA key.
 * Observation belongs to each controller. Existing JSON-store recovery supplies defaults
 * on supported read/decode failures; there is no legacy import or additional error-state hierarchy.
 * Applying desired settings, admission rules and PIN generation belong to the future controller.
 *
 * @param context Supplies the preferences file.
 * @param dispatcher Runs writes, defaulting to IO.
 */
@Singleton
internal class MjpegSettings(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * One saved snapshot; an update can change related groups in a single transaction.
     * Runtime tokens, applied state, discovered addresses and statistics are not saved here.
     *
     * @property image Image and frame-rate choices, including inactive crop and size values.
     * @property network Address filter and HTTP port choices.
     * @property access Access preferences and the single saved PIN.
     * @property web Shared page preferences; always serialized so its device-default title is retained.
     * @property behavior Capture and post-Stop behavior preferences.
     */
    @Serializable
    internal data class Data(
        val image: ImageSettings = ImageSettings(),
        val network: NetworkSettings = NetworkSettings(),
        val access: AccessSettings = AccessSettings(),
        val web: WebPageSettings,
        val behavior: StreamBehaviorSettings = StreamBehaviorSettings(),
    )

    /**
     * Controller commands that transform the latest value of one group, avoiding replacement from
     * stale UI snapshots. The controller decides whether an edit is currently allowed; this type
     * itself neither performs writes nor applies settings.
     */
    internal sealed interface Edit {
        class Image(val change: (ImageSettings) -> ImageSettings) : Edit
        class Network(val change: (NetworkSettings) -> NetworkSettings) : Edit
        class Access(val change: (AccessSettings) -> AccessSettings) : Edit
        class Web(val change: (WebPageSettings) -> WebPageSettings) : Edit
        class Behavior(val change: (StreamBehaviorSettings) -> StreamBehaviorSettings) : Edit
    }

    private val storage: JsonPreferencesStore<Data> = JsonPreferencesStore(
        serializer = Data.serializer(),
        defaultValue = Data(web = WebPageSettings()),
        produceFile = { context.preferencesDataStoreFile("mjpeg") },
    )

    /** Available snapshots, including recovery defaults; each observer owns its collection. */
    internal val data: Flow<Data> = storage.data

    /**
     * Transform the latest stored snapshot atomically, rather than a potentially stale [data] value.
     * Once admitted into the non-cancellable write, caller cancellation does not stop it. Transform
     * and storage failures propagate; controller admission and application are separate work.
     */
    internal suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData(transform)
    }
}
