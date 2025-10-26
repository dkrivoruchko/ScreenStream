package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet

internal class ParamInjector(
    private val config: PacketizationConfigAccessor = PacketizationConfigAccessor.Default
) {
    private var lastInjectNs: Long = 0L

    fun maybeInjectForH264(packet: H264Packet, isKeyFrame: Boolean) {
        if (!config.paramReinjectionEnabled() || isKeyFrame) return
        val now = System.nanoTime()
        if (now - lastInjectNs > config.reinjectParamsIntervalNs()) {
            packet.forceStapAOnce()
            lastInjectNs = now
        }
    }

    fun maybeInjectForH265(packet: H265Packet, isKeyFrame: Boolean) {
        if (!config.paramReinjectionEnabled() || isKeyFrame) return
        val now = System.nanoTime()
        if (now - lastInjectNs > config.reinjectParamsIntervalNs()) {
            packet.forceParamsOnce()
            lastInjectNs = now
        }
    }
}

internal interface PacketizationConfigAccessor {
    fun paramReinjectionEnabled(): Boolean
    fun reinjectParamsIntervalNs(): Long

    object Default : PacketizationConfigAccessor {
        override fun paramReinjectionEnabled(): Boolean = PacketizationConfig.paramReinjectionEnabled
        override fun reinjectParamsIntervalNs(): Long = PacketizationConfig.reinjectParamsIntervalSec.toLong() * 1_000_000_000L
    }
}

