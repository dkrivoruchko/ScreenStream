package info.dvkr.screenstream.webrtc.internal

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Base64
import androidx.annotation.StringRes
import info.dvkr.screenstream.common.StreamingModule
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcService
import kotlinx.parcelize.Parcelize
import org.webrtc.AudioTrack
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.security.MessageDigest

internal open class WebRtcEvent(@JvmField val priority: Int) : StreamingModule.AppEvent() {

    internal object Priority {
        internal const val NONE: Int = -1
        internal const val STOP_IGNORE: Int = 10
        internal const val RECOVER_IGNORE: Int = 20
        internal const val DESTROY_IGNORE: Int = 30
    }

    internal sealed class Intentable(priority: Int) : WebRtcEvent(priority), Parcelable {
        internal companion object {
            private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"

            @Suppress("DEPRECATION")
            internal fun fromIntent(intent: Intent?): Intentable? =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) intent?.getParcelableExtra(EXTRA_PARCELABLE)
                else intent?.getParcelableExtra(EXTRA_PARCELABLE, Intentable::class.java)
        }

        @Parcelize internal data object StartService : Intentable(Priority.NONE)
        @Parcelize internal data class StopStream(val reason: String) : Intentable(Priority.RECOVER_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        internal fun toIntent(context: Context): Intent = WebRtcService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    internal data class CreateStreamingService( val service: Service) : WebRtcEvent(Priority.NONE)
    internal data object GetNewStreamId : WebRtcEvent(Priority.DESTROY_IGNORE)
    internal data object CreateNewPassword : WebRtcEvent(Priority.DESTROY_IGNORE)
    internal data class StartProjection(val intent: Intent) : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data class RemoveClient(val clientId: ClientId, val notifyServer: Boolean, val reason: String) : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data object CastPermissionsDenied : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data object UpdateState : WebRtcEvent(Priority.RECOVER_IGNORE)
}

internal data class WebRtcState(
    @JvmField val isBusy: Boolean = true,
    @JvmField val signalingServerUrl: String = "",
    @JvmField val streamId: String = "",
    @JvmField val streamPassword: String = "",
    @JvmField val waitingCastPermission: Boolean = false,
    @JvmField val isStreaming: Boolean = false,
    @JvmField val clients: List<Client> = emptyList(),
    @JvmField val error: WebRtcError? = null
) {
    internal data class Client(@JvmField val id: String, @JvmField val publicId: String, @JvmField val clientAddress: String)

    internal fun toAppState() = StreamingModule.AppState(isBusy, isStreaming)

    override fun toString(): String =
        "WebRtcState(isBusy=$isBusy, streamId='$streamId', wCP=$waitingCastPermission, isStreaming=$isStreaming, clients=${clients.size}, error=$error)"
}

internal sealed class WebRtcError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal data class PlayIntegrityError(
        val code: Int, val isAutoRetryable: Boolean, override val message: String?,
        @StringRes override val id: Int = R.string.webrtc_error_unspecified
    ) : WebRtcError(id)

    internal data class NetworkError(val code: Int, override val message: String?, override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_check_network) {
        internal fun isNonRetryable(): Boolean = code in 500..599
        override fun toString(context: Context): String = context.getString(id) + ":\n$message ${if (code > 0) "[$code]" else ""}"
    }

    internal data class SocketError(override val message: String?, override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String =
            context.getString(id) + " [$message] : ${if (cause?.message != null) cause.message else ""} "
    }

    internal data class UnknownError(override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}

@JvmInline
internal value class PlayIntegrityToken(val value: String) {
    override fun toString(): String = value.take(96)
}

@JvmInline
internal value class Answer(val description: String) {
    internal fun isEmpty(): Boolean = description.isBlank()
    internal fun asSessionDescription(): SessionDescription = SessionDescription(SessionDescription.Type.ANSWER, description)
    override fun toString(): String = "*"
}

@JvmInline
internal value class ClientId(val value: String) {
    internal fun isEmpty(): Boolean = value.isBlank()
    override fun toString(): String = value
}

@JvmInline
internal value class Offer(val description: String) {
    override fun toString(): String = "*"
}

@JvmInline
internal value class StreamId(val value: String) {
    internal companion object {
        internal val EMPTY: StreamId = StreamId("")
    }

    internal fun isEmpty(): Boolean = value.isBlank()

    override fun toString(): String = value
}

@JvmInline
internal value class StreamPassword private constructor(val value: String) {
    internal companion object {
        internal val EMPTY: StreamPassword = StreamPassword("")

        internal fun generateNew(): StreamPassword = StreamPassword(randomString(6, true))
    }

    internal fun isEmpty(): Boolean = value.isBlank()

    internal fun isValid(clientId: ClientId, streamId: StreamId, passwordHash: String): Boolean =
        (clientId.value + streamId.value + value).encodeToByteArray().toSHA384Bytes().toBase64UrlSafe() == passwordHash

    override fun toString(): String = value

    private fun ByteArray.toSHA384Bytes(): ByteArray = MessageDigest.getInstance("SHA-384").digest(this)
    private fun ByteArray.toBase64UrlSafe(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.URL_SAFE)
}

@JvmInline
internal value class MediaStreamId private constructor(@JvmField val value: String) {
    internal companion object {
        internal fun create(streamId: StreamId): MediaStreamId = MediaStreamId("${streamId.value}#${randomString(8)}")
    }

    override fun toString(): String = value
}

internal class LocalMediaSteam(val id: MediaStreamId, @JvmField val videoTrack: VideoTrack, @JvmField val audioTrack: AudioTrack)