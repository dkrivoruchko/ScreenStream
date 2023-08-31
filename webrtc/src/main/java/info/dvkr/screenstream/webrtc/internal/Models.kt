package info.dvkr.screenstream.webrtc.internal

import android.util.Base64
import info.dvkr.screenstream.common.randomString
import org.webrtc.AudioTrack
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.security.MessageDigest

@JvmInline
internal value class PlayIntegrityToken(internal val value: String) {
    override fun toString(): String = value.take(96)
}

@JvmInline
internal value class Answer(private val description: String) {
    internal fun isEmpty(): Boolean = description.isBlank()
    internal fun asSessionDescription(): SessionDescription = SessionDescription(SessionDescription.Type.ANSWER, description)
    override fun toString(): String = "*"
}

@JvmInline
internal value class ClientId(internal val value: String) {
    internal fun isEmpty(): Boolean = value.isBlank()
    override fun toString(): String = value
}

@JvmInline
internal value class Offer(internal val description: String) {
    override fun toString(): String = "*"
}

@JvmInline
internal value class StreamId(internal val value: String) {
    internal companion object {
        internal val EMPTY: StreamId = StreamId("")
    }

    internal fun isEmpty(): Boolean = value.isBlank()

    override fun toString(): String = value
}

@JvmInline
internal value class StreamPassword private constructor(internal val value: String) {
    internal companion object {
        internal val EMPTY: StreamPassword = StreamPassword("")

        internal fun generateNew(): StreamPassword = StreamPassword(randomString(6, true))
    }

    internal fun isValid(clientId: ClientId, streamId: StreamId, passwordHash: String): Boolean =
        (clientId.value + streamId.value + value).encodeToByteArray().toSHA384Bytes().toBase64UrlSafe() == passwordHash

    override fun toString(): String = value

    private fun ByteArray.toSHA384Bytes(): ByteArray = MessageDigest.getInstance("SHA-384").digest(this)
    private fun ByteArray.toBase64UrlSafe(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.URL_SAFE)
}

@JvmInline
internal value class MediaStreamId private constructor(internal val value: String) {
    internal companion object {
        internal fun create(streamId: StreamId): MediaStreamId = MediaStreamId("${streamId.value}#${randomString(8)}")
    }

    override fun toString(): String = value
}

internal class LocalMediaSteam(internal val id: MediaStreamId, internal val videoTrack: VideoTrack, internal val audioTrack: AudioTrack)