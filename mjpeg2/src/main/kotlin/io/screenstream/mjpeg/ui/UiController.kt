package io.screenstream.mjpeg.ui

import androidx.compose.runtime.Immutable
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/** Bound to one module instance; collecting [state] does not start or own streaming resources. */
internal interface UiController {
    val state: StateFlow<State>

    /** Request admission from the owning module; return confirms neither acceptance nor completion. */
    fun send(uiCommand: Command)

    sealed interface Command {
        data object Start : Command

        /** A stale Stop cannot end a replacement capture. */
        data class Stop(val attempt: StreamingModule.CaptureAttemptId) : Command

        /** Retry only the current Failed planned incarnation; stale UUIDs do nothing. */
        data class RetryServer(val serverId: Uuid) : Command
    }

    /** Setup edits remain restricted through capture cleanup and after failed cleanup. */
    data class State(
        val instanceId: StreamingModule.InstanceId,
        val action: Action,
        val addressServers: List<AddressServer> = emptyList(),
        val suspensionProblem: SuspensionProblem? = null,
        val editPolicy: MjpegSettings.EditPolicy = MjpegSettings.EditPolicy.LiveOnly,
        val appliedPin: SecretValue? = null,
        val hidePinOnCapture: Boolean = false,
    )

    /**
     * [addressId] identifies the discovery row; [serverId] identifies its planned HTTP incarnation,
     * including Pending/Failed, and is absent without a permitted plan.
     * Address actions use the supplied row snapshot: [accessUrl] when available, otherwise its public endpoint.
     * UI does not reread discovery identity or Listening state before using that URL.
     * Retry sends the supplied [serverId]; the owning module controls retry admission.
     */
    @Immutable
    data class AddressServer(
        val addressId: Long,
        val serverId: Uuid?,
        val interfaceName: String,
        val interfaceType: InterfaceType,
        val family: AddressFamily,
        val isPrivate: Boolean,
        val address: String,
        val port: Int,
        val status: ServerStatus,
        val accessUrl: SecretValue?,
        val isLoopback: Boolean,
    )

    /** Local availability; a failure does not stop other servers or capture. */
    @Immutable
    sealed interface ServerStatus {
        data object Pending : ServerStatus

        data object Listening : ServerStatus

        /** Missing LAN grant fails the row without a [AddressServer.serverId], so Retry is unavailable. */
        data class Failed(val reason: ServerFailureReason) : ServerStatus
    }

    enum class ServerFailureReason {
        AddressInUse,

        AddressUnavailable,

        /** Missing LAN grant or a platform-denied bind. */
        PermissionDenied,

        IoFailure,

        Unknown,
    }

    /** Screen controls are advisory; module state can change before a command is handled. */
    @Immutable
    sealed interface Action {
        data class Start(val enabled: Boolean) : Action

        /** No capture action is offered during consent, startup or cleanup. */
        data object Busy : Action

        data class Stop(val attempt: StreamingModule.CaptureAttemptId) : Action
    }
}
