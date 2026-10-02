package io.screenstream.mjpeg.ui

import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.flow.StateFlow

/** Controls and planned address servers for one installed controller; collection owns no streaming work. */
internal interface UiController {
    /** Current capture controls, server availability and optional platform permission request. */
    val state: StateFlow<State>

    /**
     * Retry only this exact failed server lifetime; stale identities do nothing.
     * @param id Server lifetime displayed by the Retry action.
     */
    fun retryServer(id: ServerId)

    /**
     * Claim an exact permission request; the controller limits automatic requests per selection.
     * @param key Permission selection displayed by these controls.
     * @param automatic Whether this is an automatic request rather than an explicit user action.
     * @return Whether the platform request is currently admitted.
     */
    fun claimLocalNetworkPermissionRequest(key: PermissionRequestKey, automatic: Boolean): Boolean

    /** Recheck the ordinary platform permission after a result or host resume. */
    fun refreshLocalNetworkPermission()

    /**
     * One controller's displayed state; Stop retains the exact capture identity.
     * @property instanceId Installed module instance that owns these controls.
     * @property action Current capture action.
     * @property addressServers Planned servers, including those not yet listening.
     * @property permissionRequest Exact current permission request, absent when none is needed.
     * @property suspensionProblem Current Engine problem while capture is suspended, absent in every other phase.
     */
    data class State(
        val instanceId: StreamingModule.InstanceId,
        val action: Action,
        val addressServers: List<AddressServer> = emptyList(),
        val permissionRequest: PermissionRequestKey? = null,
        val suspensionProblem: ScreenCaptureProblem? = null,
    )

    /** Opaque handle for one HTTP-owned socket lifetime; port changes and reappearance change its identity. */
    class ServerId internal constructor(internal val deliveryId: HttpDelivery.ServerId) {
        override fun equals(other: Any?): Boolean = other is ServerId && deliveryId === other.deliveryId
        override fun hashCode(): Int = deliveryId.hashCode()
    }

    /** Opaque identity for one permission selection, independent of any individual server. */
    class PermissionRequestKey

    /**
     * A planned address server; access secrets are revealed only through explicit Copy URL.
     * @property id Exact server lifetime used by Retry.
     * @property interfaceName Device interface that owns this address.
     * @property interfaceType Current network type of the device interface, including Other when undetermined.
     * @property address Numeric IP address, with no access token.
     * @property port Planned listening port.
     * @property status Current availability of this server.
     * @property copyUrl Portable viewer-page URL only while listening, admitted and using applied access; otherwise null.
     * @property sameDevice Whether this loopback server is reachable only from this device.
     */
    data class AddressServer(
        val id: ServerId,
        val interfaceName: String,
        val interfaceType: NetworkAddressMonitor.InterfaceType,
        val address: String,
        val port: Int,
        val status: ServerStatus,
        val copyUrl: SecretValue?,
        val sameDevice: Boolean,
    )

    /** Local availability; a failure does not stop other servers or capture. */
    sealed interface ServerStatus {
        /** The planned listener is waiting or opening. */
        data object Pending : ServerStatus
        /** The listener accepts requests; Copy URL still requires an admitted, portable URL. */
        data object Listening : ServerStatus
        /** Ordinary local network permission prevents listening. */
        data object PermissionRequired : ServerStatus
        /**
         * Last local bind failure; Retry addresses only this server.
         * @property reason Reason shown to the user.
         */
        data class Failed(val reason: FailureReason) : ServerStatus
    }

    /** Publicly displayed failure categories, without exceptions or HTTP implementation details. */
    enum class FailureReason {
        /** Another listener occupies the address and port. */
        AddressInUse,
        /** The address is no longer available for binding. */
        AddressUnavailable,
        /** The platform denied the bind operation. */
        PermissionDenied,
        /** An input/output failure prevented listening. */
        IoFailure,
        /** No more specific local failure category is available. */
        Unknown,
    }

    /** Universal capture actions; the owning core controller supplies Start and Stop callbacks. */
    sealed interface Action {
        /**
         * Start capture when available.
         * @property enabled Whether Start is currently admitted.
         */
        data class Start(val enabled: Boolean) : Action
        /** A capture transition is in progress. */
        data object Busy : Action
        /**
         * Stop the displayed capture.
         * @property attempt Exact capture identity to stop.
         */
        data class Stop(val attempt: StreamingModule.CaptureAttemptId) : Action
    }
}
