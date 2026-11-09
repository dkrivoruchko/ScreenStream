package info.dvkr.screenstream.rtsp.internal.onvif

import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.RtspServerEndpoint
import info.dvkr.screenstream.rtsp.internal.VideoParams
import java.net.Inet6Address

internal data class OnvifServiceEndpoint(val rtspEndpoint: RtspServerEndpoint, val deviceServicePort: Int) {
    private val httpHost: String
        get() {
            val host = (rtspEndpoint.address.hostAddress ?: "unknown").substringBefore('%')
            return if (rtspEndpoint.address is Inet6Address) "[$host]" else host
        }

    val deviceServicePath: String = "/onvif/device_service"
    val deviceServiceUrl: String = "http://$httpHost:$deviceServicePort$deviceServicePath"
}

internal data class OnvifVideoMetadata(
    val codec: Codec.Video?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val h264Profile: String?,
) {
    companion object {
        fun fromVideoParams(videoParams: VideoParams?, width: Int, height: Int, fps: Int): OnvifVideoMetadata =
            OnvifVideoMetadata(
                codec = videoParams?.codec,
                width = width,
                height = height,
                fps = fps,
                h264Profile = videoParams?.takeIf { it.codec == Codec.Video.H264 }?.sps?.let(::h264ProfileFromSps),
            )

        private fun h264ProfileFromSps(sps: ByteArray): String? {
            if (sps.size < 2) return null
            return when (sps[1].toInt() and 0xFF) {
                66 -> "Baseline"
                77 -> "Main"
                88 -> "Extended"
                100 -> "High"
                else -> null
            }
        }
    }
}

internal data class OnvifDeviceInfo(
    val deviceId: String,
    val appVersion: String,
    val manufacturer: String = "ScreenStream",
    val model: String = "ScreenStream",
    val hardwareId: String = "Android",
) {
    val endpointUrn: String = "urn:uuid:$deviceId"
    val firmwareVersion: String = appVersion
    val serialNumber: String = "ScreenStream-$deviceId"
    val instanceId: Long = System.currentTimeMillis() / 1000L
    val scopes: List<String> = listOf(
        "onvif://www.onvif.org/Profile/Streaming",
        "onvif://www.onvif.org/type/Network_Video_Transmitter",
        "onvif://www.onvif.org/type/video_encoder",
        "onvif://www.onvif.org/location/unknown",
        "onvif://www.onvif.org/name/$model",
        "onvif://www.onvif.org/hardware/$hardwareId",
    )
}
