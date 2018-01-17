package info.dvkr.screenstream.data.presenter.start

import android.support.annotation.Keep
import info.dvkr.screenstream.data.presenter.BaseView
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.httpserver.HttpServer


interface StartView : BaseView {
    @Keep sealed class FromEvent : BaseView.BaseFromEvent() {
        @Keep object StreamRunningRequest : FromEvent()
        @Keep object CurrentInterfacesRequest : FromEvent()
        @Keep object TryStartStream : FromEvent()
        @Keep object StopStream : FromEvent()
        @Keep object AppExit : FromEvent()
        @Keep class Error(val error: Throwable) : FromEvent()
        @Keep object GetError : FromEvent()
    }

    @Keep sealed class ToEvent : BaseView.BaseToEvent() {
        @Keep class TryToStart : ToEvent()
        @Keep class OnStreamStartStop(val running: Boolean) : ToEvent()
        @Keep class ResizeFactor(val value: Int) : ToEvent()
        @Keep class EnablePin(val value: Boolean) : ToEvent()
        @Keep class SetPin(val value: String) : ToEvent()
        @Keep class StreamRunning(val running: Boolean) : ToEvent()
        @Keep class CurrentClients(val clientsList: Collection<HttpServer.Client>) : ToEvent()
        @Keep class CurrentInterfaces(val interfaceList: List<EventBus.Interface>) : ToEvent()
        @Keep class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint) : ToEvent()
    }
}