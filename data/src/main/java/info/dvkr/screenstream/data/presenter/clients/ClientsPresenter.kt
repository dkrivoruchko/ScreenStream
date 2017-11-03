package info.dvkr.screenstream.data.presenter.clients

import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.data.dagger.PersistentScope
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject

@PersistentScope
class ClientsPresenter @Inject internal constructor(private val eventScheduler: Scheduler,
                                                    private val eventBus: EventBus) {
    private val TAG = "ClientsPresenter"

    private val subscriptions = CompositeSubscription()
    private var clientsView: ClientsView? = null
    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()
    private var maxYValue = 0L

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
        Crashlytics.log(1, TAG, "Constructor")
    }

    fun attach(view: ClientsView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")
        Crashlytics.log(1, TAG, "Attach")

        clientsView?.let { detach() }
        clientsView = view

        // Events from ClientsActivity
        clientsView?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")
            when (fromEvent) {

            // Getting current traffic history
                is ClientsView.FromEvent.TrafficHistoryRequest -> {
                    // Requesting current traffic history
                    if (trafficHistory.isEmpty()) {
                        eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistoryRequest())
                    } else {
                        maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                        clientsView?.toEvent(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                    }
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent WARRING: IGNORED")
            }
        }.also { subscriptions.add(it) }

        // Global events
        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.CurrentClients ||
                    it is EventBus.GlobalEvent.TrafficHistory ||
                    it is EventBus.GlobalEvent.TrafficPoint
        }.subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    clientsView?.toEvent(ClientsView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficHistory -> {
                    trafficHistory.clear()
                    trafficHistory.addAll(globalEvent.trafficHistory.sortedBy { it.time })
                    maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                    clientsView?.toEvent(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficPoint -> {
                    if (trafficHistory.isEmpty() || trafficHistory.last.time >= globalEvent.trafficPoint.time) {
                        return@subscribe
                    }
                    trafficHistory.removeFirst()
                    trafficHistory.addLast(globalEvent.trafficPoint)
                    maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                    clientsView?.toEvent(ClientsView.ToEvent.TrafficPoint(globalEvent.trafficPoint, maxYValue))
                }
            }
        }.also { subscriptions.add(it) }

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())
    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        Crashlytics.log(1, TAG, "Detach")
        subscriptions.clear()
        clientsView = null
    }
}