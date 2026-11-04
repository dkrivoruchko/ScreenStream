package info.dvkr.screenstream.rtsp.internal.onvif

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.RtspServerEndpoint
import info.dvkr.screenstream.rtsp.internal.VideoParams
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OnvifServer(
    context: Context,
    deviceId: String,
    appVersion: String,
    protocolPolicy: RtspSettings.Values.ProtocolPolicy,
    endpoints: List<RtspServerEndpoint>,
) {
    private val scopeJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val endpoints: List<RtspServerEndpoint> = endpoints.filterNot { it.address.isLoopbackAddress }
    private val deviceInfo = OnvifDeviceInfo(deviceId = deviceId, appVersion = appVersion)
    private val httpServer = OnvifHttpServer(deviceInfo, protocolPolicy)
    private val discoveryServer = OnvifDiscoveryServer(context, deviceInfo)
    private val stateMutex = Mutex()
    private var videoMetadata: OnvifVideoMetadata? = null
    private var enabled: Boolean = false
    private var published: Boolean = false

    suspend fun setVideoMetadata(videoParams: VideoParams?, width: Int, height: Int, fps: Int) {
        val metadata = OnvifVideoMetadata.fromVideoParams(videoParams, width, height, fps)
        stateMutex.withLock {
            videoMetadata = metadata
            httpServer.setVideoMetadata(metadata)
            syncPublicationLocked()
        }
    }

    suspend fun setEnabled(value: Boolean) {
        stateMutex.withLock {
            enabled = value
            syncPublicationLocked()
        }
    }

    suspend fun close() {
        stateMutex.withLock {
            enabled = false
            stopPublicationLocked()
        }
        runCatching { scopeJob.cancelAndJoin() }
    }

    private suspend fun syncPublicationLocked() {
        val shouldPublish = enabled && endpoints.isNotEmpty() && (videoMetadata?.codec == Codec.Video.H264)
        if (shouldPublish && !published) {
            publishLocked()
        } else if (!shouldPublish && published) {
            stopPublicationLocked()
        }
    }

    private suspend fun publishLocked() {
        try {
            val serviceEndpoints = httpServer.start(scope, endpoints)
            if (serviceEndpoints.isEmpty()) {
                XLog.w(getLog("OnvifServer", "No ONVIF HTTP endpoints started"))
                httpServer.stop()
            } else {
                discoveryServer.start(scope, serviceEndpoints)
                published = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            XLog.w(getLog("OnvifServer", "Failed to start: ${error.message}"), error)
            stopPublicationLocked()
        }
    }

    private suspend fun stopPublicationLocked() {
        if (published) {
            runCatching { discoveryServer.stop() }
            runCatching { httpServer.stop() }
            published = false
        } else {
            runCatching { discoveryServer.stop() }
            runCatching { httpServer.stop() }
        }
    }
}
