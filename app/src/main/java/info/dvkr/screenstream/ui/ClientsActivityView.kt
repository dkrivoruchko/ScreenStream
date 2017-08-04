package info.dvkr.screenstream.ui

import android.support.annotation.Keep
import info.dvkr.screenstream.model.HttpServer
import rx.Observable

interface ClientsActivityView {

    // From ClientsActivity to ClientsActivityPresenter
    @Keep sealed class FromEvent {
        @Keep class TrafficHistoryRequest : FromEvent()
    }

    // Events from ClientsActivity to ClientsActivityPresenter
    fun fromEvent(): Observable<FromEvent>

    // To ClientsActivity from ClientsActivityPresenter
    @Keep sealed class ToEvent {
        // From HttpServer
        @Keep data class CurrentClients(val clientsList: List<HttpServer.Client>) : ToEvent()

        // From HttpServer
        @Keep data class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>, val maxY: Long) : ToEvent()

        // From HttpServer
        @Keep data class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint, val maxY: Long) : ToEvent()
    }

    // Events to ClientsActivity from ClientsActivityPresenter
    fun toEvent(toEvent: ToEvent)
}