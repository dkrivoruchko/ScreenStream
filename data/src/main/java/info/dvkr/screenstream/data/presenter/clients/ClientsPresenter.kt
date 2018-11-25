package info.dvkr.screenstream.data.presenter.clients

import info.dvkr.screenstream.data.presenter.BasePresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

class ClientsPresenter(eventBus: EventBus) :
    BasePresenter<ClientsView, ClientsView.FromEvent>(eventBus) {

    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()
    private var maxYValue = 0L

    init {
        viewChannel = GlobalScope.actor(baseJob, capacity = 16) {
            for (fromEvent in this) when (fromEvent) {
                ClientsView.FromEvent.TrafficHistoryRequest -> { // Requesting current traffic history
                    if (trafficHistory.isEmpty()) {
                        eventBus.send(EventBus.GlobalEvent.TrafficHistoryRequest)
                    } else {
                        maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                        notifyView(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                    }
                }
            }

        }
    }

    fun attach(newView: ClientsView) {
        super.attach(newView) { globalEvent ->
            when (globalEvent) {
                is EventBus.GlobalEvent.CurrentClients ->
                    notifyView(ClientsView.ToEvent.CurrentClients(globalEvent.clientsList))

                is EventBus.GlobalEvent.TrafficHistory -> {
                    trafficHistory.clear()
                    trafficHistory.addAll(globalEvent.trafficHistory.sortedBy { it.time })
                    maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                    notifyView(ClientsView.ToEvent.TrafficHistory(trafficHistory.toList(), maxYValue))
                }

                is EventBus.GlobalEvent.TrafficPoint ->
                    if (trafficHistory.isNotEmpty() && trafficHistory.last.time < globalEvent.trafficPoint.time) {
                        trafficHistory.removeFirst()
                        trafficHistory.addLast(globalEvent.trafficPoint)
                        maxYValue = trafficHistory.maxBy { it.bytes }?.bytes ?: 0
                        notifyView(ClientsView.ToEvent.TrafficPoint(globalEvent.trafficPoint, maxYValue))
                    }
            }

        }

        // Requesting current clients
        GlobalScope.launch(baseJob) { eventBus.send(EventBus.GlobalEvent.CurrentClientsRequest) }
    }
}