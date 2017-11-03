package info.dvkr.screenstream.domain.eventbus

import android.support.annotation.Keep
import info.dvkr.screenstream.domain.httpserver.HttpServer
import rx.Observable
import java.net.Inet4Address


interface EventBus {

    // System network interfaces
    @Keep data class Interface(val name: String, val address: Inet4Address)

    @Keep sealed class GlobalEvent {
        // From ImageGeneratorImpl to StartActivityPresenter
        @Keep class StreamStatus : GlobalEvent()

        // From StartActivityPresenter & ProjectionCallback to ForegroundPresenter
        @Keep class StopStream : GlobalEvent()

        // From StartActivityPresenter to ForegroundPresenter
        @Keep class AppExit : GlobalEvent()

        // From SettingsPresenter & ForegroundPresenter to ForegroundPresenter
        @Keep data class HttpServerRestart(val reason: String) : GlobalEvent()

        // From SettingsPresenter to StartActivityPresenter & ImageGenerator
        @Keep data class ResizeFactor(val value: Int) : GlobalEvent()

        // From SettingsPresenter to ImageGenerator
        @Keep data class JpegQuality(val value: Int) : GlobalEvent()

        // From SettingsPresenter to StartActivityPresenter
        @Keep data class EnablePin(val value: Boolean) : GlobalEvent()

        // From SettingsPresenter & ForegroundPresenter to StartActivityPresenter
        @Keep data class SetPin(val value: String) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep class CurrentClientsRequest : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsPresenter & ForegroundPresenter
        @Keep data class CurrentClients(val clientsList: List<HttpServer.Client>) : GlobalEvent()

        // From StartActivityPresenter to ForegroundPresenter
        @Keep class CurrentInterfacesRequest : GlobalEvent()

        // From ForegroundPresenter to StartActivityPresenter
        @Keep data class CurrentInterfaces(val interfaceList: List<Interface>) : GlobalEvent()

        // From HttpServer & ImageGenerator to ForegroundPresenter
        @Keep data class Error(val error: Throwable) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep class TrafficHistoryRequest : GlobalEvent()

        // From HttpServer to ClientsPresenter
        @Keep data class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>) : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsPresenter
        @Keep data class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint) : GlobalEvent()
    }

    fun getEvent(): Observable<GlobalEvent>

    fun sendEvent(event: GlobalEvent)
}