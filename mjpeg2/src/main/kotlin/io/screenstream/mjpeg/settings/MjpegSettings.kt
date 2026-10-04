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
 * Saved preferences in `mjpeg.preferences_pb`; saving does not confirm application to the stream.
 * Supported read/decode failures return defaults. Legacy MJPEG preferences are not imported.
 */
@Singleton
internal class MjpegSettings(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Groups update atomically; URL tokens, viewing cookies, applied state, discovery and statistics are runtime-only.
     * [web] is required so the initial device title is saved rather than recalculated on later launches.
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
     * Runtime edit policy, never saved. UI rejects forbidden deltas; [effective] preserves running setup
     * while accepting live values. LiveOnly covers consent through cleanup, including failed cleanup;
     * successful cleanup permits All even without a listening server.
     */
    internal enum class EditPolicy {
        All,

        /** Only image processing, address selection, web appearance and behavior can change. */
        LiveOnly;

        val canEditStreamSetup: Boolean get() = this == All

        /** Validate the proposed delta, allowing unchanged restricted values in live-only transactions. */
        fun allows(current: Data, proposed: Data): Boolean = effective(current, proposed) == proposed

        fun effective(current: Data, proposed: Data): Data = when (this) {
            All -> proposed
            LiveOnly -> proposed.copy(
                image = proposed.image.copy(jpegBackendPolicy = current.image.jpegBackendPolicy),
                network = proposed.network.copy(httpPort = current.network.httpPort),
                access = current.access,
            )
        }
    }

    private val storage: JsonPreferencesStore<Data> = JsonPreferencesStore(
        serializer = Data.serializer(),
        defaultValue = Data(web = WebPageSettings()),
        produceFile = { context.preferencesDataStoreFile("mjpeg") },
    )

    internal val data: Flow<Data> = storage.data

    /**
     * Transform the latest stored snapshot atomically, rather than a potentially stale [data] value.
     * Enabling protection without a PIN generates one in the same update.
     * Once admitted into the non-cancellable write, caller cancellation does not stop it. Transform
     * and storage failures propagate; completing the save does not confirm application to the stream.
     */
    internal suspend fun updateData(transform: Data.() -> Data): Unit = withContext(NonCancellable + dispatcher) {
        storage.updateData {
            val updated = transform()
            if (updated.access.pinEnabled && updated.access.pin == null) {
                updated.copy(access = updated.access.withGeneratedPin())
            } else {
                updated
            }
        }
    }
}
