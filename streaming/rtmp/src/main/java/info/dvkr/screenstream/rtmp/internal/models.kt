package info.dvkr.screenstream.rtmp.internal

import android.media.MediaCodecInfo
import androidx.compose.runtime.Immutable
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

internal sealed class MediaFrame {
    data class Info(val offset: Int, val size: Int, val timestamp: Long, val isKeyFrame: Boolean)

    abstract val data: ByteBuffer
    abstract val info: Info

    /**
     * Must be called once you have finished reading from [data].
     * This releases the MediaCodec buffer back to the codec.
     */
    abstract fun release()

    data class VideoFrame(override val data: ByteBuffer, override val info: Info, private val releaseCallback: () -> Unit) : MediaFrame() {
        override fun release() = releaseCallback.invoke()
    }

    data class AudioFrame(override val data: ByteBuffer, override val info: Info, private val releaseCallback: () -> Unit) : MediaFrame() {
        override fun release() = releaseCallback.invoke()
    }

    internal fun detachedCopy(): MediaFrame {
        val source = data.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        val bytes = ByteArray(info.size).also { source.get(it) }
        val copiedInfo = info.copy(offset = 0, size = bytes.size)
        val copiedBuffer = ByteBuffer.wrap(bytes)

        return when (this) {
            is VideoFrame -> VideoFrame(copiedBuffer, copiedInfo) {}
            is AudioFrame -> AudioFrame(copiedBuffer, copiedInfo) {}
        }
    }
}

internal sealed class Codec(val name: String, val mimeType: String) {

    sealed class Video(name: String, mimeType: String) : Codec(name, mimeType) {
        data object H264 : Video("H.264", "video/avc")
        data object H265 : Video("H.265", "video/hevc")
        data object AV1 : Video("AV1", "video/av01")
    }

    sealed class Audio(name: String, mimeType: String) : Codec(name, mimeType) {
        data object G711 : Audio("G.711", "audio/g711-alaw")
        data object AAC : Audio("AAC", "audio/mp4a-latm")
        data object OPUS : Audio("OPUS", "audio/opus")
    }
}

@Immutable
internal data class VideoCodecInfo(
    val name: String,
    val codec: Codec.Video,
    val vendorName: String,
    val isHardwareAccelerated: Boolean,
    val isCBRModeSupported: Boolean,
    val capabilities: MediaCodecInfo.CodecCapabilities
)

@Immutable
internal data class AudioCodecInfo(
    val name: String,
    val codec: Codec.Audio,
    val vendorName: String,
    val isHardwareAccelerated: Boolean,
    val isCBRModeSupported: Boolean,
    val capabilities: MediaCodecInfo.CodecCapabilities?
)

internal data class RtmpNetInterface(val label: String, val address: InetAddress) {

    internal val hostAddress: String = address.hostAddress ?: "unknown"
    internal val bindKey: String = "$label|$hostAddress"

    internal fun buildUrl(port: Int, path: String): String {
        val baseUrl = if (address is Inet6Address) {
            "rtmp://[${address.hostAddress!!.substringBefore('%')}]:$port"
        } else {
            "rtmp://${address.hostAddress}:$port"
        }
        val path = path.trimStart('/')
        return if (path.isEmpty()) baseUrl else "$baseUrl/$path"
    }
}

internal data class RtmpServerEndpoint(val address: InetAddress, val bindHost: String, val rtmpUrl: String)

internal class VideoParams(val codec: Codec.Video, val sps: ByteArray, val pps: ByteArray?, val vps: ByteArray?) {
    val isOk: Boolean
        get() = when (codec) {
            Codec.Video.H264 -> pps != null
            Codec.Video.H265 -> pps != null && vps != null
            Codec.Video.AV1 -> true
        }

    internal fun contentEquals(other: VideoParams?): Boolean {
        if (other == null) return false
        if (codec != other.codec) return false
        if (!sps.contentEquals(other.sps)) return false
        if (pps == null && other.pps != null) return false
        if (pps != null && other.pps == null) return false
        if (pps != null && !pps.contentEquals(other.pps)) return false
        if (vps == null && other.vps != null) return false
        if (vps != null && other.vps == null) return false
        if (vps != null && !vps.contentEquals(other.vps)) return false
        return true
    }
}

internal class AudioParams(val codec: Codec.Audio, val sampleRate: Int, val isStereo: Boolean)
