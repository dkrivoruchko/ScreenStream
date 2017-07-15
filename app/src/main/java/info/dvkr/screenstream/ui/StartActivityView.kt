package info.dvkr.screenstream.ui


import android.support.annotation.Keep
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.service.ForegroundServiceView
import rx.Observable

interface StartActivityView {

    // From StartActivity to StartActivityPresenter
    @Keep sealed class FromEvent {
        @Keep class TryStartStream : FromEvent()
        @Keep class StopStream : FromEvent()
        @Keep class AppExit : FromEvent()
        @Keep class GetError : FromEvent()
    }

    // Events from StartActivity to StartActivityPresenter
    fun fromEvent(): Observable<FromEvent>

    // To StartActivity from StartActivityPresenter
    @Keep sealed class ToEvent {
        @Keep class TryToStart : ToEvent()

        @Keep data class StreamStartStop(val running: Boolean) : ToEvent()

        // From SettingsActivityPresenter
        @Keep data class ResizeFactor(val value: Int) : ToEvent()

        // From SettingsActivityPresenter
        @Keep data class EnablePin(val value: Boolean) : ToEvent()

        // From SettingsActivityPresenter
        @Keep data class SetPin(val value: String) : ToEvent()

        // From SettingsActivityPresenter
        @Keep data class StreamRunning(val running: Boolean) : ToEvent()

        // From SettingsActivityPresenter
        @Keep data class Error(val error: Throwable?) : ToEvent()

        // From HttpServer
        @Keep data class CurrentClients(val clientsList: Collection<HttpServer.Client>) : ToEvent()

        // From ForegroundServicePresenter
        @Keep data class CurrentInterfaces(val interfaceList: List<ForegroundServiceView.Interface>) : ToEvent()
    }

    // Events to StartActivity from StartActivityPresenter
    fun toEvent(toEvent: ToEvent)

    // TODO Add translation
    companion object {
        const val FEEDBACK_EMAIL_ADDRESS = "Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"
        const val FEEDBACK_EMAIL_SUBJECT = "Screen Stream feedback"
        const val FEEDBACK_EMAIL_NAME = "Sending email..."
    }
}