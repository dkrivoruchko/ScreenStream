package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.flow.StateFlow

/** One controller's address listeners and shared JPEG publication, independent of capture lifetime. */
internal interface HttpDelivery {
    /** Authoritative listener/viewer state; counters survive stop, addresses and viewers do not. */
    val info: StateFlow<Info>

    /**
     * Activate once with prepared access, before starting any listener or sampler. The configured
     * plan may be empty. True means JPEG publication is available, not that a server is Listening.
     * False means already started or terminal stop has closed admission.
     * [isAllowed] checks the latest selected-address identity, port and permission synchronously;
     * it must be nonblocking, thread-safe and must not call back into this delivery. HTTP combines
     * this current admission with its accepted plan before opening or retaining a listener.
     */
    suspend fun start(access: AccessSettings, token: SecretValue?, isAllowed: (NetworkAddressMonitor.Address, Int) -> Boolean): Boolean

    /**
     * Replace the complete authoritative server plan. Before start, publish Pending or
     * PermissionRequired without creating jobs or listeners. After start, reconcile without
     * waiting for bind or cleanup; unchanged server incarnations and JPEG publication survive.
     * Addresses are already selected and canonicalized by the monitor. Accepted work survives
     * caller cancellation; terminal stop ignores later plans.
     */
    suspend fun configureServers(servers: List<DesiredServer>, port: Int)

    /** Retry only this exact Failed, currently admitted server; stale or repeated requests do nothing. */
    suspend fun retryServer(id: ServerId)

    /**
     * Atomically apply the saved PIN, URL token and blocking policy. Disabled PIN means open.
     * Credential changes revoke admitted pages/media; policy-only edits preserve viewing grants.
     * False means activation has not succeeded or terminal stop has closed admission.
     */
    fun updateAccess(access: AccessSettings, token: SecretValue?): Boolean

    /** Read-only browser facts; this never grants browser control of capture or settings. */
    fun updatePresentation(capture: WebCaptureState, issue: WebCaptureIssue?, display: WebPageSettings)

    /**
     * Apply an image-owner transition only when its revision is newer. The clear watermark carries
     * every preceding clear through delayed effects; applying a later retaining transition cannot
     * preserve an image invalidated by an earlier clear. True means a newer transition was applied;
     * repeated identities, older revisions and terminal stop return false.
     */
    fun updatePublication(publication: Publication): Boolean

    /**
     * Copy a borrowed JPEG synchronously for the exact current publication. Reservation and commit
     * both check its reference identity under the HTTP gate. Neither callback nor destination may
     * escape, and no external admission callback runs inside HTTP. False means no image was accepted.
     */
    fun offerJpeg(publication: Publication, byteCount: Int, copyTo: (ByteArray, Int) -> Int): Boolean

    /**
     * Immutable image-publication identity reserved by one image owner when it accepts a transition.
     * Revisions strictly increase. [clearRevision] is the revision of the most recent accepted clear,
     * or zero before any clear; it uses the same sequence and never exceeds [revision]. A null [kind]
     * permits no producer, while retained image metadata remains independent of this transition.
     */
    class Publication internal constructor(val revision: Long, val clearRevision: Long, val kind: ImageKind?)

    /** Immediately close publication/request admission and start independent cleanup. */
    fun requestStop()

    /** Await the same complete cleanup. Cancelling the waiter never abandons listeners or readers. */
    suspend fun stop()

    /**
     * Opaque reference identity for one planned server incarnation, owned by HTTP. A selected
     * address reappearance, removal/reselection or any port change (including A → B → A) creates
     * a new identity. Metadata-only updates preserve it and refresh the server's address.
     */
    class ServerId internal constructor()

    /** Permission suppression preserves this server's retry history. */
    enum class ServerAdmission { Allowed, MissingLocalNetworkPermission }

    /** One provider-selected address and its current permission decision. */
    data class DesiredServer(val address: NetworkAddressMonitor.Address, val admission: ServerAdmission)


    /** Local failure isolated to one server; other listeners continue serving. */
    enum class ServerFailure { AddressInUse, AddressUnavailable, PermissionDenied, IoFailure, Unknown }

    /** Shared delivery failure that requires the owning controller to stop. */
    enum class FatalIssue { InternalFailure, CleanupFailure }

    /** Current availability of this exact server incarnation. */
    sealed interface ServerState {
        /** Planned before activation, waiting for this socket, or opening its listener. */
        data object Pending : ServerState
        /** The exact-address listener accepts requests. */
        data object Listening : ServerState
        /** Permission prevents listening without spending or resetting retry attempts. */
        data object PermissionRequired : ServerState
        /** Last bind failure and attempts spent, including the initial attempt; the budget is three. */
        data class Failed(val reason: ServerFailure, val attempts: Int) : ServerState
    }

    /** One planned server and its truthful availability, including exhausted retries. */
    data class AddressInfo(
        val id: ServerId,
        val address: NetworkAddressMonitor.Address,
        val port: Int,
        val state: ServerState,
    )
    /** Capture facts visible to authorized pages, independently of retained JPEG availability. */
    enum class WebCaptureState { Stopped, WaitingForPermission, Starting, Active, Reconfiguring, Suspended, Stopping, Failed }
    /** Safe browser issue codes; underlying diagnostics remain in Android logs. */
    enum class WebCaptureIssue { CaptureFailed, CleanupFailed }
    /** Logical page viewers may reconnect while physical response owners finish separately. */
    enum class ViewerState { Active, Reconnecting }

    /** Single-image or continuously updated multipart response. */
    enum class ViewerMethod { Jpeg, Mjpeg }

    /** Metadata committed with JPEG bytes, independent of the current capture phase. */
    enum class ImageKind { StreamFrame, Placeholder }

    /**
     * A viewer begins at its first JPEG payload. Page-labelled responses share one viewer through
     * polling and bounded reconnect grace; raw responses each own a viewer until physical completion.
     * IP is the first immediate media peer; WS alone never creates or indefinitely retains a viewer.
     */
    data class ViewerInfo(val id: Long, val ip: String, val method: ViewerMethod, val isSlow: Boolean, val completedJpegBytes: Long, val state: ViewerState = ViewerState.Active)

    /**
     * Delivery snapshot. Completed payload counters exclude framing and incomplete writes, and include repeats.
     * @property addresses Desired listeners, empty after shutdown.
     * @property viewers Active or reconnecting logical viewers; physical response ownership is kept separately.
     * @property completedJpegs Fully written JPEG payloads, including repeated frames.
     * @property completedJpegBytes JPEG payload bytes, excluding multipart headers and delimiters.
     * @property slowWrites Payload writes that have crossed the slow-write threshold, counted once per write.
     * @property sampledAtElapsedRealtimeMillis Monotonic timestamp of this snapshot.
     * @property imageAvailable Whether a retained image can serve a new authorized request.
     * @property imageKind Kind of the published JPEG, or null when no image is available.
     * @property fatalIssue Shared failure requiring controller shutdown; local bind failures stay with their address.
     */
    data class Info(
        val addresses: List<AddressInfo> = emptyList(),
        val viewers: List<ViewerInfo> = emptyList(),
        val completedJpegs: Long = 0,
        val completedJpegBytes: Long = 0,
        val slowWrites: Long = 0,
        val sampledAtElapsedRealtimeMillis: Long = 0,
        val imageAvailable: Boolean = false,
        val imageKind: ImageKind? = null,
        val fatalIssue: FatalIssue? = null,
    )
}
