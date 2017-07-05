package info.dvkr.screenstream.model.appevent

import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.HttpServer
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import java.net.InetSocketAddress
import java.util.*

class AppEventImpl : AppEvent {
    private val mAppEvents = PublishSubject.create<AppEvent.Event>()
    private val mSteamRunning = BehaviorSubject.create(false)
    private val mJpegBytesStream = BehaviorSubject.create<ByteArray>()
    private val mClients = BehaviorSubject.create<List<InetSocketAddress>>(ArrayList<InetSocketAddress>())
    private val mClientsList = ArrayList<InetSocketAddress>()

    override fun sendEvent(event: AppEvent.Event) = mAppEvents.onNext(event)

    override fun onEvent(): Observable<AppEvent.Event> = mAppEvents.asObservable()

    override fun getStreamRunning(): BehaviorSubject<Boolean> = mSteamRunning

    override fun getJpegBytesStream(): BehaviorSubject<ByteArray> = mJpegBytesStream

    override fun clearClients() {
        mClientsList.clear()
        mClients.onNext(mClientsList)
    }

    override fun sendClientEvent(clientEvent: HttpServer.ClientEvent) {
        when (clientEvent) {
            is HttpServer.ClientEvent.ClientConnected -> mClientsList.add(clientEvent.clientAddress)
            is HttpServer.ClientEvent.ClientDisconnected -> mClientsList.remove(clientEvent.clientAddress)
        }
        mClients.onNext(mClientsList)
    }

    override fun onClientEvent(): Observable<List<InetSocketAddress>> = mClients.asObservable()
}