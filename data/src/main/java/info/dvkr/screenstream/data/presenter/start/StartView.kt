package info.dvkr.screenstream.data.presenter.start

import android.support.annotation.Keep
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import rx.Observable


interface StartView {
    // From StartActivity to StartActivityPresenter
    @Keep sealed class FromEvent {
        @Keep object CurrentInterfacesRequest : FromEvent()
        @Keep object TryStartStream : FromEvent()
        @Keep object StopStream : FromEvent()
        @Keep object AppExit : FromEvent()
        @Keep data class Error(val error: Throwable) : FromEvent()
        @Keep object GetError : FromEvent()
    }

    // To StartActivity from StartActivityPresenter
    @Keep sealed class ToEvent {
        @Keep class TryToStart : ToEvent()

        // From ImageGeneratorImpl
        @Keep data class OnStreamStartStop(val running: Boolean) : ToEvent()

        // From SettingsPresenter
        @Keep data class ResizeFactor(val value: Int) : ToEvent()

        // From SettingsPresenter
        @Keep data class EnablePin(val value: Boolean) : ToEvent()

        // From SettingsPresenter
        @Keep data class SetPin(val value: String) : ToEvent()

        // From StartActivityPresenter
        @Keep data class StreamRunning(val running: Boolean) : ToEvent()

        // From SettingsPresenter
        @Keep data class Error(val error: Throwable?) : ToEvent()

        // From HttpServer
        @Keep data class CurrentClients(val clientsList: Collection<HttpServer.Client>) : ToEvent()

        // From FgPresenter
        @Keep data class CurrentInterfaces(val interfaceList: List<EventBus.Interface>) : ToEvent()

        // From HttpServer
        @Keep data class TrafficPoint(val trafficPoint: HttpServer.TrafficPoint) : ToEvent()
    }

    // Events to StartActivity from StartActivityPresenter
    fun toEvent(toEvent: ToEvent)
}