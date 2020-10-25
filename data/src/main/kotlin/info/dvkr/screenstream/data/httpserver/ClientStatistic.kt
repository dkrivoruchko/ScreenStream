package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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

        override fun toString(): String = this::class.java.simpleName
    }

    companion object {
        private const val CLIENT_DISCONNECT_HOLD_TIME_SECONDS = 5
        private const val TRAFFIC_HISTORY_SECONDS = 30
    }

    private val statisticChannel = ConflatedBroadcastChannel<Pair<List<HttpClient>, List<TrafficPoint>>>()

    val statisticFlow: Flow<Pair<List<HttpClient>, List<TrafficPoint>>> = statisticChannel.asFlow()

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(FatalError.CoroutineException)
    }

    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

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

    private val statisticEventChannel = statisticScope.actor<StatisticEvent>(capacity = 64) {
        val clientsMap: MutableMap<Long, StatisticClient> = mutableMapOf()
        val trafficHistory = LinkedList<TrafficPoint>()

        val past = System.currentTimeMillis() - TRAFFIC_HISTORY_SECONDS * 1000
        (0..TRAFFIC_HISTORY_SECONDS + 1).forEach { i ->
            trafficHistory.addLast(TrafficPoint(i * 1000 + past, 0))
        }

        for (event in this) {
            ensureActive()
            try {
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
                        statisticChannel.offer(Pair(clients, trafficHistory.sortedBy { it.time }))
                    }

                    is StatisticEvent.ClearClients -> clientsMap.clear()
                }
            } catch (ignore: CancellationException) {
                XLog.w(this@ClientStatistic.getLog("actor.ignore", ignore.toString()))
                XLog.w(this@ClientStatistic.getLog("actor.ignore"), ignore)
            } catch (throwable: Throwable) {
                XLog.e(this@ClientStatistic.getLog("actor.catch", throwable.toString()))
                XLog.e(this@ClientStatistic.getLog("actor.catch"), throwable)
                onError(FatalError.CoroutineException)
            }
        }
    }

    init {
        XLog.d(getLog("init"))
        statisticScope.launch {
            while (true) {
                ensureActive()
                sendEvent(StatisticEvent.SendStatistic)
                delay(1000)
            }
        }
    }

    internal fun sendEvent(event: StatisticEvent) {
        XLog.v(getLog("sendEvent", event.toString()))

        if (statisticEventChannel.isClosedForSend) {
            XLog.e(getLog("sendEvent", "ChannelIsClosed"))
            return
        }

        try {
            statisticEventChannel.offer(event) //|| throw IllegalStateException("ChannelIsFull")
        } catch (ignore: CancellationException) {
            XLog.w(getLog("sendEvent.ignore", ignore.toString()))
            XLog.w(getLog("sendEvent.ignore"), ignore)
        } catch (closedChannel: ClosedSendChannelException) {
            XLog.w(getLog("sendEvent.closedChannel", closedChannel.toString()))
            XLog.w(getLog("sendEvent.closedChannel"), closedChannel)
        } catch (th: Throwable) {
            XLog.e(getLog("sendEvent", th.toString()))
            XLog.e(getLog("sendEvent"), th)
            onError(FatalError.ChannelException)
        }
    }

    fun destroy() {
        XLog.d(getLog("destroy"))
        statisticEventChannel.close()
        statisticScope.cancel()
    }
}