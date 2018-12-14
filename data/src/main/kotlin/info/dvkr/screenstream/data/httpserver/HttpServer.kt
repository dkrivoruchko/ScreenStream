package info.dvkr.screenstream.data.httpserver

import info.dvkr.screenstream.data.settings.SettingsReadOnly
import java.net.InetSocketAddress

interface HttpServer {

    companion object {
        const val CLIENT_DISCONNECT_HOLD_TIME_SECONDS = 5
        const val TRAFFIC_HISTORY_SECONDS = 30
    }

    fun configure(settingsReadOnly: SettingsReadOnly)

    fun start(serverAddress: InetSocketAddress)

    fun stop()

    fun destroy()
}