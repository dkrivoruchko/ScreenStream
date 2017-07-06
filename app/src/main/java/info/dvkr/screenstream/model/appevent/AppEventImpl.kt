package info.dvkr.screenstream.model.appevent

import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.model.ImageGenerator
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import java.net.InetSocketAddress

class AppEventImpl : AppEvent {
    private val mAppEvents = PublishSubject.create<AppEvent.Event>()
    private val mAppStatus = BehaviorSubject.create<Set<String>>(HashSet<String>())
    private val mAppStatusSet = HashSet<String>();

    private val mSteamRunning = BehaviorSubject.create(false)
    private val mJpegBytesStream = BehaviorSubject.create<ByteArray>()

    private val mClients = BehaviorSubject.create<List<InetSocketAddress>>(ArrayList<InetSocketAddress>())
    private val mClientsList = ArrayList<InetSocketAddress>()

    override fun sendEvent(event: AppEvent.Event) {
        if (event is AppEvent.Event.AppStatus) {
            when (event.status) {
                HttpServer.HTTP_SERVER_OK -> mAppStatusSet.remove(AppEvent.APP_STATUS_ERROR_SERVER_PORT_BUSY)
                HttpServer.HTTP_SERVER_ERROR_PORT_BUSY -> mAppStatusSet.add(AppEvent.APP_STATUS_ERROR_SERVER_PORT_BUSY)
                ImageGenerator.IMAGE_GENERATOR_ERROR_WRONG_IMAGE_FORMAT -> mAppStatusSet.add(AppEvent.APP_STATUS_ERROR_WRONG_IMAGE_FORMAT)
            }
            mAppStatus.onNext(mAppStatusSet)
        }
        mAppEvents.onNext(event)
    }

    override fun onEvent(): Observable<AppEvent.Event> = mAppEvents.asObservable()

    override fun getAppStatus(): Observable<Set<String>> = mAppStatus.asObservable()

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