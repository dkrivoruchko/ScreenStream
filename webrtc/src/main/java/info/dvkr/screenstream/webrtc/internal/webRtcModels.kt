package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Base64
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.webrtc.WebRtcModuleService
import kotlinx.parcelize.Parcelize
import org.webrtc.AudioTrack
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.security.MessageDigest

internal open class WebRtcEvent(val priority: Int) {

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
            internal fun fromIntent(intent: Intent): Intentable? =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_PARCELABLE)
                else intent.getParcelableExtra(EXTRA_PARCELABLE, Intentable::class.java)
        }

        @Parcelize internal data object StartService : Intentable(Priority.NONE)
        @Parcelize internal data class StopStream(val reason: String) : Intentable(Priority.RECOVER_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        internal fun toIntent(context: Context): Intent = WebRtcModuleService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    internal data object GetNewStreamId : WebRtcEvent(Priority.DESTROY_IGNORE)
    internal data object CreateNewPassword : WebRtcEvent(Priority.DESTROY_IGNORE)
    internal data class StartProjection(val intent: Intent) : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data class RemoveClient(val clientId: ClientId, val notifyServer: Boolean, val reason: String) : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data object CastPermissionsDenied : WebRtcEvent(Priority.RECOVER_IGNORE)
    internal data object UpdateState : WebRtcEvent(Priority.RECOVER_IGNORE)
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
internal value class MediaStreamId private constructor(val value: String) {
    internal companion object {
        internal fun create(streamId: StreamId): MediaStreamId = MediaStreamId("${streamId.value}#${randomString(8)}")
    }

    override fun toString(): String = value
}

internal class LocalMediaSteam(val id: MediaStreamId, val videoTrack: VideoTrack, val audioTrack: AudioTrack)