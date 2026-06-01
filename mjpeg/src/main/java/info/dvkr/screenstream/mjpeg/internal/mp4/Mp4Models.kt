package info.dvkr.screenstream.mjpeg.internal.mp4

internal data class Mp4VideoConfig(
    val width: Int,
    val height: Int,
    val sps: ByteArray,
    val pps: ByteArray,
    val fps: Int
) {
    val codecString: String = buildString {
        append("avc1.")
        val profileBytes = if (sps.size >= 4) byteArrayOf(sps[1], sps[2], sps[3]) else byteArrayOf(0x42, 0x00, 0x1F)
        profileBytes.forEach { byte -> append((byte.toInt() and 0xFF).toString(16).padStart(2, '0')) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Mp4VideoConfig

        if (width != other.width) return false
        if (height != other.height) return false
        if (!sps.contentEquals(other.sps)) return false
        if (!pps.contentEquals(other.pps)) return false
        if (fps != other.fps) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + sps.contentHashCode()
        result = 31 * result + pps.contentHashCode()
        result = 31 * result + fps
        return result
    }
}

internal data class Mp4AudioConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val audioSpecificConfig: ByteArray
) {
    val codecString: String = "mp4a.40.2"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Mp4AudioConfig

        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (!audioSpecificConfig.contentEquals(other.audioSpecificConfig)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioSpecificConfig.contentHashCode()
        return result
    }
}

internal data class Mp4StreamConfig(
    val generation: Long,
    val video: Mp4VideoConfig?,
    val audio: Mp4AudioConfig?
) {
    val mimeCodecString: String = buildList {
        video?.let { add(it.codecString) }
        audio?.let { add(it.codecString) }
    }.joinToString(",")
}

internal data class Mp4VideoPacket(
    val generation: Long,
    val data: ByteArray,
    val timestampUs: Long,
    val durationUs: Long,
    val isKeyFrame: Boolean
)

internal data class Mp4AudioPacket(
    val generation: Long,
    val data: ByteArray,
    val timestampUs: Long,
    val durationSamples: Int
)
