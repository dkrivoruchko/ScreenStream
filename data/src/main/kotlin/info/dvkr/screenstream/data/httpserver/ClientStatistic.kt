package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

class ClientStatistic(
    private val onError: (AppError) -> Unit
) {
    internal sealed class StatisticEvent {
        object SendStatistic : StatisticEvent()
        object ClearClients : StatisticEvent()

        data class Connected(val id: Long, val clientAddressAndPort: String) : StatisticEvent()
        data class Disconnected(val id: Long) : StatisticEvent()
        data class Backpressure(val id: Long) : StatisticEvent()
        data class NextBytes(val id: Long, val bytesCount: Int) : StatisticEvent()

        override fun toString(): String = javaClass.simpleName
    }

    private data class StatisticClient(
        val id: Long,
        val clientAddressAndPort: String,
        var isSlowConnection: Boolean = false,
        var isDisconnected: Boolean = false,
        var sendBytes: Long = 0,
        var disconnectedTime: Long = 0
    ) {
        fun isDisconnectHoldTimePass(now: Long) = (now - disconnectedTime) > CLIENT_DISCONNECT_HOLD_TIME_SECONDS * 1000

        fun toHttpClient() = HttpClient(id, clientAddressAndPort, isSlowConnection, isDisconnected)
    }

    companion object {
        private const val CLIENT_DISCONNECT_HOLD_TIME_SECONDS = 5
        private const val TRAFFIC_HISTORY_SECONDS = 30
    }

    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default)

    private val clientsMap: MutableMap<Long, StatisticClient> = mutableMapOf()
    private val trafficHistory = LinkedList<TrafficPoint>()
    private val past = System.currentTimeMillis() - TRAFFIC_HISTORY_SECONDS * 1000
    private val _statisticEventSharedFlow = MutableSharedFlow<StatisticEvent>(extraBufferCapacity = 64)

    private val _statisticStateFlow =
        MutableStateFlow<Pair<List<HttpClient>, List<TrafficPoint>>>(Pair(emptyList(), emptyList()))
    val statisticFlow: StateFlow<Pair<List<HttpClient>, List<TrafficPoint>>> = _statisticStateFlow.asStateFlow()

    internal fun sendEvent(event: StatisticEvent) {
        XLog.v(getLog("sendEvent", event.toString()))

        _statisticEventSharedFlow.tryEmit(event) || throw IllegalStateException("_eventSharedFlow IsFull")
    }

    fun destroy() {
        XLog.d(getLog("destroy"))
        statisticScope.cancel()
    }

    init {
        XLog.d(getLog("init"))

        (0..TRAFFIC_HISTORY_SECONDS + 1).forEach { i -> trafficHistory.addLast(TrafficPoint(i * 1000 + past, 0)) }

        statisticScope.launch(CoroutineName("ClientStatistic.SendEvent flow")) {
            _statisticEventSharedFlow
                .onEach { event -> onEvent(event) }
                .catch { cause ->
                    XLog.e(this@ClientStatistic.getLog("_statisticEventSharedFlow.catch"), cause)
                    onError(FatalError.CoroutineException)
                }
                .collect()
        }

        statisticScope.launch(CoroutineName("ClientStatistic.SendStatistic timer")) {
            while (isActive) {
                sendEvent(StatisticEvent.SendStatistic)
                delay(1000)
            }
        }
    }

    private fun onEvent(event: StatisticEvent) {
        XLog.v(getLog("onEvent", event.toString()))

        when (event) {
            is StatisticEvent.Connected ->
                clientsMap[event.id] = StatisticClient(event.id, event.clientAddressAndPort)

            is StatisticEvent.Disconnected ->
                clientsMap[event.id]?.apply {
                    isDisconnected = true
                    disconnectedTime = System.currentTimeMillis()
                }

            is StatisticEvent.Backpressure ->
                clientsMap[event.id]?.isSlowConnection = true

            is StatisticEvent.NextBytes ->
                clientsMap[event.id]?.apply { sendBytes = sendBytes.plus(event.bytesCount) }

            is StatisticEvent.SendStatistic -> {
                val now = System.currentTimeMillis()
                clientsMap.values.removeAll { it.isDisconnected && it.isDisconnectHoldTimePass(now) }
                val traffic = clientsMap.values.map { it.sendBytes }.sum()
                clientsMap.values.forEach { it.sendBytes = 0 }
                trafficHistory.removeFirst()
                trafficHistory.addLast(TrafficPoint(now, traffic))

                val clients = clientsMap.values.map { it.toHttpClient() }.sortedBy { it.clientAddressAndPort }
                _statisticStateFlow.tryEmit(Pair(clients, trafficHistory.sortedBy { it.time }))
            }

            is StatisticEvent.ClearClients -> clientsMap.clear()
        }
    }
}