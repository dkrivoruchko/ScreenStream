package info.dvkr.screenstream.ui


import rx.Observable
import java.net.InetSocketAddress

interface StartActivityView {

    sealed class Event {
        class TryStartStream : Event() // To StartActivityPresenter
        class StopStream : Event() // To StartActivityPresenter
        class AppExit : Event() // To StartActivityPresenter
    }

    fun onEvent(): Observable<Event> // Events from Activity

    fun onTryToStart()

    fun onStreamStart()

    fun onStreamStop()

    fun onConnectedClients(clientAddresses: List<InetSocketAddress>)
}