package info.dvkr.screenstream.rtsp.internal.rtsp.server

internal object RtspLogging {
    @Volatile var verboseRtsp: Boolean = false
    @Volatile var verboseRtp: Boolean = false
    @Volatile var rtpFirstN: Int = 8
}

