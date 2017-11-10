package info.dvkr.screenstream.data.presenter.clients

import info.dvkr.screenstream.data.presenter.BasePresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import rx.Scheduler
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque

class ClientsPresenter internal constructor(private val eventScheduler: Scheduler,
                                            private val eventBus: EventBus) : BasePresenter<ClientsView>() {

    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()
    private var maxYValue = 0L

    override fun attach(newView: ClientsView) {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Attach")

        view?.let { detach() }
        view = newView

        // Events from ClientsActivity
        view?.fromEvent()?.observeOn(eventScheduler)?.subscribe { fromEvent ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] fromEvent: $fromEvent")

            when (fromEvent) {
            // Getting current traffic history
                is ClientsView.FromEvent.TrafficHistoryRequest -> {
                    // Requesting current traffic history
                    if (trafficHistory.isEmpty()) {
                        eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistoryRequest())
                    } else {
                        maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                        view?.toEvent(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                    }
                }

                else -> throw IllegalArgumentException("Unknown fromEvent")
            }
        }.also { subscriptions.add(it) }

        // Global events
        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.CurrentClients ||
                    it is EventBus.GlobalEvent.TrafficHistory ||
                    it is EventBus.GlobalEvent.TrafficPoint
        }.subscribe { globalEvent ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] globalEvent: $globalEvent")

            when (globalEvent) {
            // From HttpServerImpl
                is EventBus.GlobalEvent.CurrentClients -> {
                    view?.toEvent(ClientsView.ToEvent.CurrentClients(globalEvent.clientsList))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficHistory -> {
                    trafficHistory.clear()
                    trafficHistory.addAll(globalEvent.trafficHistory.sortedBy { it.time })
                    maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                    view?.toEvent(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                }

            // From HttpServerImpl
                is EventBus.GlobalEvent.TrafficPoint -> {
                    if (trafficHistory.isEmpty() || trafficHistory.last.time >= globalEvent.trafficPoint.time) {
                        return@subscribe
                    }
                    trafficHistory.removeFirst()
                    trafficHistory.addLast(globalEvent.trafficPoint)
                    maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                    view?.toEvent(ClientsView.ToEvent.TrafficPoint(globalEvent.trafficPoint, maxYValue))
                }
            }
        }.also { subscriptions.add(it) }

        // Requesting current clients
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())
    }
}