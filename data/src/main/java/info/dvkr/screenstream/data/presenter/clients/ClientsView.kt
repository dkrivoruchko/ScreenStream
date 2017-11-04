package info.dvkr.screenstream.data.presenter.clients

import android.support.annotation.Keep
import info.dvkr.screenstream.domain.httpserver.HttpServer
import rx.Observable

interface ClientsView {
    // From ClientsActivity to ClientsPresenter
    @Keep sealed class FromEvent {
        @Keep object TrafficHistoryRequest : FromEvent()
    }

    // Events from ClientsActivity to ClientsPresenter
    fun fromEvent(): Observable<FromEvent>

    // To ClientsActivity from ClientsPresenter
    @Keep sealed class ToEvent {
        // From HttpServer
        @Keep data class CurrentClients(val clientsList: List<HttpServer.Client>) : ToEvent()

        // From HttpServer
        @Keep data class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>, val maxY: Long) : ToEvent()

        // From HttpServer
        @Keep data class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint, val maxY: Long) : ToEvent()
    }

    // Events to ClientsActivity from ClientsPresenter
    fun toEvent(toEvent: ToEvent)
}