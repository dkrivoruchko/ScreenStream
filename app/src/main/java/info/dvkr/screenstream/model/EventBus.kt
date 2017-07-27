package info.dvkr.screenstream.model

import android.support.annotation.Keep
import info.dvkr.screenstream.service.ForegroundServiceView
import rx.Observable


interface EventBus {

    @Keep sealed class GlobalEvent {
        // From ImageGeneratorImpl to StartActivityPresenter
        @Keep class StreamStatus : GlobalEvent()

        // From StartActivityPresenter & ProjectionCallback to ForegroundServicePresenter
        @Keep class StopStream : GlobalEvent()

        // From StartActivityPresenter to ForegroundServicePresenter
        @Keep class AppExit : GlobalEvent()

        // From SettingsActivityPresenter & ForegroundServicePresenter to ForegroundServicePresenter
        @Keep data class HttpServerRestart(val reason: String) : GlobalEvent()

        // From SettingsActivityPresenter to StartActivityPresenter & ImageGenerator
        @Keep data class ResizeFactor(val value: Int) : GlobalEvent()

        // From SettingsActivityPresenter to ImageGenerator
        @Keep data class JpegQuality(val value: Int) : GlobalEvent()

        // From SettingsActivityPresenter to StartActivityPresenter
        @Keep data class EnablePin(val value: Boolean) : GlobalEvent()

        // From SettingsActivityPresenter to StartActivityPresenter
        @Keep data class SetPin(val value: String) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep class CurrentClientsRequest : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsActivityPresenter
        @Keep data class CurrentClients(val clientsList: List<HttpServer.Client>) : GlobalEvent()

        // From StartActivityPresenter to ForegroundServicePresenter
        @Keep class CurrentInterfacesRequest : GlobalEvent()

        // From ForegroundServicePresenter to StartActivityPresenter
        @Keep data class CurrentInterfaces(val interfaceList: List<ForegroundServiceView.Interface>) : GlobalEvent()

        // From HttpServer & ImageGenerator to ForegroundServicePresenter
        @Keep data class Error(val error: Throwable) : GlobalEvent()

        // From StartActivityPresenter to HttpServer
        @Keep class TrafficHistoryRequest : GlobalEvent()

        // From HttpServer to ClientsActivityPresenter
        @Keep data class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>) : GlobalEvent()

        // From HttpServer to StartActivityPresenter & ClientsActivityPresenter
        @Keep data class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint) : GlobalEvent()
    }

    fun getEvent(): Observable<GlobalEvent>

    fun sendEvent(event: GlobalEvent)
}