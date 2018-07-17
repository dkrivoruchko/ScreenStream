package info.dvkr.screenstream.domain.eventbus

import android.support.annotation.Keep
import info.dvkr.screenstream.domain.httpserver.HttpServer
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import java.net.Inet4Address


interface EventBus {

    // System network interfaces
    @Keep data class Interface(val name: String, val address: Inet4Address)

    @Keep sealed class GlobalEvent {
        // From ImageGeneratorImpl to StartActivityPresenter
        @Keep object StreamStatus : GlobalEvent()

        // From StartActivityPresenter & ProjectionCallback to ForegroundPresenter
        @Keep object StopStream : GlobalEvent()

        // From StartActivityPresenter to ForegroundPresenter
        @Keep object AppExit : GlobalEvent()

        // From SettingsPresenter & ForegroundPresenter to ForegroundPresenter
        @Keep class HttpServerRestart(val reason: String) : GlobalEvent()

        // From SettingsPresenter to StartActivityPresenter & ImageGenerator
        @Keep class ResizeFactor(val value: Int) : GlobalEvent()

        // From SettingsPresenter to ImageGenerator
        @Keep class JpegQuality(val value: Int) : GlobalEvent()

        // From SettingsPresenter to StartActivityPresenter
        @Keep class EnablePin(val value: Boolean) : GlobalEvent()

        // From SettingsPresenter & ForegroundPresenter to StartActivityPresenter
        @Keep class SetPin(val value: String) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep object CurrentClientsRequest : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsPresenter & ForegroundPresenter
        @Keep class CurrentClients(val clientsList: List<HttpServer.Client>) : GlobalEvent()

        // From StartActivityPresenter to ForegroundPresenter
        @Keep object CurrentInterfacesRequest : GlobalEvent()

        // From ForegroundPresenter to StartActivityPresenter
        @Keep class CurrentInterfaces(val interfaceList: List<Interface>) : GlobalEvent()

        // From HttpServer & ImageGenerator to ForegroundPresenter
        @Keep class Error(val error: Throwable) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep object TrafficHistoryRequest : GlobalEvent()

        // From HttpServer to ClientsPresenter
        @Keep class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>) : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsPresenter
        @Keep class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint) : GlobalEvent()
    }

    suspend fun send(event: GlobalEvent)

    fun openSubscription(): ReceiveChannel<GlobalEvent>
}