package info.dvkr.screenstream.presenter

import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.dagger.PersistentScope
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.HttpServer
import info.dvkr.screenstream.ui.ClientsActivityView
import rx.Scheduler
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject

@PersistentScope
class ClientsActivityPresenter @Inject internal constructor(private val eventScheduler: Scheduler,
                                                            private val eventBus: EventBus) {
    private val TAG = "ClientsActivityPresenter"

    private val subscriptions = CompositeSubscription()
    private var clientsActivity: ClientsActivityView? = null
    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Constructor")
    }

    fun attach(activity: ClientsActivityView) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Attach")

        clientsActivity?.let { detach() }
        clientsActivity = activity

        // Events from ClientsActivity
        subscriptions.add(clientsActivity?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            if (BuildConfig.DEBUG_MODE)   println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent")
            when (fromEvent) {

            // Getting current traffic history
                is ClientsActivityView.FromEvent.TrafficHistoryRequest -> {
                    // Requesting current traffic history
                    if (trafficHistory.isEmpty()) {
                        eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistoryRequest())
                    } else {
                        clientsActivity?.toEvent(ClientsActivityView.ToEvent.TrafficHistory(trafficHistory.toList()))
                    }
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $fromEvent IGNORED")
            }
        })

        // Global events
        subscriptions.add(eventBus.getEvent().observeOn(eventScheduler).subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")

            when (globalEvent) {

            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    clientsActivity?.toEvent(ClientsActivityView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficHistory -> {
                    trafficHistory.clear()
                    trafficHistory.addAll(globalEvent.trafficHistory)
                    clientsActivity?.toEvent(ClientsActivityView.ToEvent.TrafficHistory(trafficHistory.toList()))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficPoint -> {
                    if (trafficHistory.isNotEmpty()) trafficHistory.removeFirst()
                    trafficHistory.addLast(globalEvent.trafficPoint)
                    clientsActivity?.toEvent(ClientsActivityView.ToEvent.TrafficPoint(globalEvent.trafficPoint))
                }

                else -> println(TAG + ": Thread [${Thread.currentThread().name}] fromEvent: $globalEvent IGNORED")
            }
        })

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())

    }

    fun detach() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Detach")
        subscriptions.clear()
        clientsActivity = null
    }
}