package info.dvkr.screenstream.data.httpserver

import info.dvkr.screenstream.data.model.NetInterface

interface AppHttpServer {

    companion object {
        const val CLIENT_DISCONNECT_HOLD_TIME_SECONDS = 5
        const val TRAFFIC_HISTORY_SECONDS = 30
    }

    fun start(serverAddresses: List<NetInterface>, severPort: Int, useWiFiOnly: Boolean)

    fun stop()
}