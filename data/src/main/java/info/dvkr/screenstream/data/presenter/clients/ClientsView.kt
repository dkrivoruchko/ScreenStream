package info.dvkr.screenstream.data.presenter.clients

import android.support.annotation.Keep
import info.dvkr.screenstream.data.presenter.BaseView
import info.dvkr.screenstream.domain.httpserver.HttpServer

interface ClientsView : BaseView {
    @Keep sealed class FromEvent : BaseView.BaseFromEvent() {
        @Keep object TrafficHistoryRequest : FromEvent()
    }

    @Keep sealed class ToEvent : BaseView.BaseToEvent() {
        @Keep class CurrentClients(val clientsList: List<HttpServer.Client>) : ToEvent()
        @Keep class TrafficHistory(val trafficHistory: List<HttpServer.TrafficPoint>, val maxY: Long) : ToEvent()
        @Keep class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint, val maxY: Long) : ToEvent()
    }
}