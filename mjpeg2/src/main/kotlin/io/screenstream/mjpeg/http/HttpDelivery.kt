package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.streaming.module.StreamingModule.CaptureAttemptId
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/** One module's HTTP lifetime, independent of capture resource cleanup. */
internal interface HttpDelivery {
    val info: StateFlow<Info>

    /** URL credential for Copy URL; null before activation or while access is open. */
    val appliedAccessToken: SecretValue?

    /**
     * Activate once. True means admission is open, even with an empty or unbound plan;
     * false means already activated or terminally stopped. Takes exclusive ownership of [logoBytes].
     */
    suspend fun start(
        accessSettings: AccessSettings,
        postStopImage: PostStopImage,
        webSettings: WebPageSettings,
        logoBytes: ByteArray,
    ): Boolean

    /**
     * Replace the ready address plan without waiting for bind or cleanup. Controller owns selection
     * and permission; unchanged incarnations and responses survive. Before activation, plans remain
     * Pending. Accepted work survives caller cancellation; terminal stop ignores later plans.
     */
    suspend fun configureServers(addresses: List<NetworkAddress>, port: Int)

    /** Reset the three-attempt budget only for this current Failed incarnation. Stale IDs do nothing. */
    fun retryServer(id: Uuid)

    /**
     * Credential rotation closes old responses' next-frame admission; an acquired JPEG may finish.
     * Policy edits and an unchanged enabled PIN preserve credentials.
     * False means not activated or terminally stopped.
     */
    fun updateConfiguration(accessSettings: AccessSettings, postStopImage: PostStopImage, webSettings: WebPageSettings): Boolean

    /**
     * Register before capture construction, retaining the selected image and setting Starting.
     * False means not activated or terminally stopped.
     */
    fun registerCapture(id: CaptureAttemptId): Boolean

    /**
     * Ignore stale attempts. Suspended/Failed clear selection; Stopping/Stopped apply stopped-image policy.
     * The caller retains Failed after a capture or cleanup failure until an accepted [registerCapture].
     */
    fun updateCaptureStatus(id: CaptureAttemptId, state: Status)

    /**
     * Copy borrowed bytes synchronously outside both monitors, fencing publication by attempt and source.
     * [copyTo] runs at most once, writes [byteCount] bytes at offset zero or throws, and must not retain the buffer.
     * Closed/stale sources ignore the offer; failed copies return reusable storage.
     */
    fun offerJpeg(id: CaptureAttemptId, byteCount: Int, copyTo: (ByteArray) -> Unit)

    /** Close admission immediately, force cancellation, and start cleanup independent of its waiter. */
    fun requestStop()

    /** Await the same cleanup; cancelling this waiter never abandons resources. */
    suspend fun stop()

    enum class ServerFailure {
        AddressInUse, AddressUnavailable, PermissionDenied, IoFailure, Unknown
    }

    /** Availability of one planned incarnation; Failed does not assert physical FD teardown. */
    sealed interface ServerState {
        /** Planned before activation, waiting for this socket, or opening its listener. */
        data object Pending : ServerState

        data object Listening : ServerState

        /** Last listener failure and attempts spent, including the initial attempt. */
        data class Failed(val reason: ServerFailure, val attempts: Int) : ServerState
    }

    /**
     * UUID identifies the planned incarnation separately from the network-address ID.
     * Removal/reselection or a port change creates a new UUID; metadata edits preserve it.
     */
    data class ServerInfo(
        val id: Uuid,
        val address: NetworkAddress,
        val port: Int,
        val state: ServerState,
    )

    /**
     * One row per planned server incarnation and immediate remote IP/port. Responses at that
     * endpoint share completed JPEG payload bytes; WebSockets neither create nor prolong rows.
     * After the final current response, a row stays Active for two seconds, then Disconnected for five.
     * Listener cutoff skips Active grace; history retains no request or image resources.
     */
    data class ClientInfo(
        val id: Long,
        val ip: String,
        val remotePort: Int,
        val serverId: Uuid,
        val format: MediaFormat,
        val isSlow: Boolean,
        val completedJpegBytes: Long,
        val state: State = State.Active,
    ) {
        enum class State { Active, Disconnected }
    }

    enum class MediaFormat { Jpeg, Mjpeg }

    /**
     * Servers/failures publish immediately; clients and counters are sampled once a second,
     * on credential rotation and after cleanup. Other publications preserve the monotonic
     * [sampledAtElapsedRealtimeMillis]. Counts include completed JPEG payloads and repeats,
     * excluding framing and partial writes; they do not prove network delivery.
     * Shutdown clears servers/clients; rotation clears clients. Both preserve global totals.
     * [fatalCause] reports a terminal runtime failure.
     */
    data class Info(
        val servers: List<ServerInfo> = emptyList(),
        val clients: List<ClientInfo> = emptyList(),
        val completedJpegs: Long = 0,
        val completedJpegBytes: Long = 0,
        val sampledAtElapsedRealtimeMillis: Long = 0,
        val fatalCause: Throwable? = null,
    ) {
        /** Active sampled rows include current admitted media and the two-second idle grace. */
        val hasConsumer: Boolean get() = clients.any { it.state == ClientInfo.State.Active }
    }
}
