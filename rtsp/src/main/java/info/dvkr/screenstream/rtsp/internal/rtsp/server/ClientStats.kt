package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal data class MediaStats(
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val packetsDropped: Long = 0,
    val enqueued: Long = 0,
    val queueSize: Int = 0,
    val queueCapacity: Int = 0,
)

internal data class ClientStats(
    val sessionId: String,
    val remoteHost: String,
    val protocol: Protocol,
    val startedAtMs: Long,
    val lastSentAtMs: Long = 0,
    val video: MediaStats = MediaStats(),
    val audio: MediaStats = MediaStats(),
)

internal class ClientStatsReporter(
    sessionId: String,
    remoteHost: String,
    protocol: Protocol,
    queueCapVideo: Int,
    queueCapAudio: Int
) {
    private val startedAt = System.currentTimeMillis()
    private val state = MutableStateFlow(
        ClientStats(
            sessionId = sessionId,
            remoteHost = remoteHost,
            protocol = protocol,
            startedAtMs = startedAt,
            video = MediaStats(queueCapacity = queueCapVideo),
            audio = MediaStats(queueCapacity = queueCapAudio)
        )
    )

    val stats: StateFlow<ClientStats> = state

    fun setProtocol(protocol: Protocol) = state.update { it.copy(protocol = protocol) }

    fun setQueueSizes(video: Int, audio: Int) = state.update {
        it.copy(video = it.video.copy(queueSize = video), audio = it.audio.copy(queueSize = audio))
    }

    fun onVideoDrop() = state.update { it.copy(video = it.video.copy(packetsDropped = it.video.packetsDropped + 1)) }
    fun onAudioDrop() = state.update { it.copy(audio = it.audio.copy(packetsDropped = it.audio.packetsDropped + 1)) }

    fun onVideoEnqueue() = state.update { it.copy(video = it.video.copy(enqueued = it.video.enqueued + 1)) }
    fun onAudioEnqueue() = state.update { it.copy(audio = it.audio.copy(enqueued = it.audio.enqueued + 1)) }

    fun onVideoSent(packetCount: Int, bytes: Int) = state.update {
        it.copy(
            lastSentAtMs = System.currentTimeMillis(),
            video = it.video.copy(
                packetsSent = it.video.packetsSent + packetCount,
                bytesSent = it.video.bytesSent + bytes
            )
        )
    }

    fun onAudioSent(packetCount: Int, bytes: Int) = state.update {
        it.copy(
            lastSentAtMs = System.currentTimeMillis(),
            audio = it.audio.copy(
                packetsSent = it.audio.packetsSent + packetCount,
                bytesSent = it.audio.bytesSent + bytes
            )
        )
    }
}
