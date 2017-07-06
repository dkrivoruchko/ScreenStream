package info.dvkr.screenstream.ui


import android.support.annotation.Keep
import rx.Observable
import java.net.InetSocketAddress

interface StartActivityView {

    // TODO Add translation
    companion object {
        const val FEEDBACK_EMAIL_ADDRESS = "Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"
        const val FEEDBACK_EMAIL_SUBJECT = "Screen Stream feedback"
        const val FEEDBACK_EMAIL_NAME = "Sending email..."
    }

    sealed class Event {
        @Keep class TryStartStream : Event() // To StartActivityPresenter
        @Keep class StopStream : Event() // To StartActivityPresenter
        @Keep class AppExit : Event() // To StartActivityPresenter
    }

    fun onEvent(): Observable<Event> // Events from Activity

    fun onAppStatus(appStatus: Set<String>) // Status events from app

    fun onTryToStart()

    fun onStreamStart()

    fun onStreamStop()

    fun onConnectedClients(clientAddresses: List<InetSocketAddress>)
}