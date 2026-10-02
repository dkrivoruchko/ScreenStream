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
 * Saved MJPEG image, network, access, page and behavior preferences, retained across app launches
 * in `mjpeg.preferences_pb`. Saving a choice does not mean the current stream has applied it yet.
 * Supported read/decode failures can return defaults; old MJPEG preferences are not imported.
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

    private val storage: JsonPreferencesStore<Data> = JsonPreferencesStore(
        serializer = Data.serializer(),
        defaultValue = Data(web = WebPageSettings()),
        produceFile = { context.preferencesDataStoreFile("mjpeg") },
    )

    /** Saved preferences, including defaults returned after supported read recovery. */
    internal val data: Flow<Data> = storage.data

    /**
     * Transform the latest stored snapshot atomically, rather than a potentially stale [data] value.
     * Once admitted into the non-cancellable write, caller cancellation does not stop it. Transform
     * and storage failures propagate; completing the save does not confirm application to the stream.
     */
    internal suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData(transform)
    }
}
