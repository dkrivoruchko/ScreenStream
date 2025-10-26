package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket

internal object RtpTimestampCalculator {
    fun videoRtpTime(nowUs: Long): Long = (nowUs * BaseRtpPacket.Companion.VIDEO_CLOCK_FREQUENCY) / 1_000_000L
    fun audioRtpTime(nowUs: Long, sampleRate: Int): Long = (nowUs * sampleRate) / 1_000_000L
}