package info.dvkr.screenstream.data.httpserver

import androidx.annotation.Keep
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getTag
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.*

internal class HttpServerStatistic(
    private val onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    onError: (AppError) -> Unit
) : HttpServerCoroutineScope(onError) {

    @Keep private data class LocalClient(
        val clientAddress: InetSocketAddress,
        var isSlowConnection: Boolean = false,
        var isDisconnected: Boolean = false,
        var sendBytes: Long = 0,
        var disconnectedTime: Long = 0
    ) {
        internal fun isDisconnectHoldTimePass(now: Long) =
            (now - disconnectedTime) > HttpServer.CLIENT_DISCONNECT_HOLD_TIME_SECONDS * 1000

        internal fun toHttpClient() = HttpClient(clientAddress, isSlowConnection, isDisconnected)
    }

    @Keep internal sealed class StatisticEvent {
        @Keep object Init : StatisticEvent()
        @Keep object CalculateTraffic : StatisticEvent()
        @Keep object SendStatistic : StatisticEvent()

        @Keep data class Connected(val address: InetSocketAddress) : StatisticEvent()
        @Keep data class Disconnected(val address: InetSocketAddress) : StatisticEvent()
        @Keep data class Backpressure(val address: InetSocketAddress) : StatisticEvent()
        @Keep data class NextBytes(val address: InetSocketAddress, val bytesCount: Int) : StatisticEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    private val statisticEventChannel: SendChannel<StatisticEvent> = actor(capacity = 16) {
        val clientsMap = HashMap<InetSocketAddress, LocalClient>()
        val trafficHistory = LinkedList<TrafficPoint>()

        for (event in this@actor) try {
            Timber.tag(this@HttpServerStatistic.getTag("Actor")).v(event.toString())

            when (event) {
                is StatisticEvent.Init -> {
                    val past = System.currentTimeMillis() - HttpServer.TRAFFIC_HISTORY_SECONDS * 1000
                    (0..HttpServer.TRAFFIC_HISTORY_SECONDS + 1).forEach { i ->
                        trafficHistory.addLast(TrafficPoint(i * 1000 + past, 0))
                    }

                    launch {
                        while (isActive) {
                            sendStatisticEvent(StatisticEvent.CalculateTraffic)
                            sendStatisticEvent(StatisticEvent.SendStatistic)
                            delay(1000)
                        }
                    }
                }

                is StatisticEvent.Connected -> clientsMap[event.address] = LocalClient(event.address)

                is StatisticEvent.Disconnected -> clientsMap[event.address]?.apply {
                    isDisconnected = true
                    disconnectedTime = System.currentTimeMillis()
                }

                is StatisticEvent.Backpressure -> clientsMap[event.address]?.isSlowConnection = true

                is StatisticEvent.NextBytes -> clientsMap[event.address]?.apply {
                    sendBytes = sendBytes.plus(event.bytesCount)
                }

                is StatisticEvent.CalculateTraffic -> {
                    val now = System.currentTimeMillis()
                    clientsMap.values.removeAll { it.isDisconnected && it.isDisconnectHoldTimePass(now) }
                    val traffic = clientsMap.values.map { it.sendBytes }.sum()
                    clientsMap.values.forEach { it.sendBytes = 0 }
                    trafficHistory.removeFirst()
                    trafficHistory.addLast(TrafficPoint(now, traffic))
                }

                is StatisticEvent.SendStatistic -> onStatistic(
                    clientsMap.values.toList().map { it.toHttpClient() },
                    trafficHistory.toList().sortedBy { it.time }
                )
            }
        } catch (throwable: Throwable) {
            Timber.tag(this@HttpServerStatistic.getTag("actor")).e(throwable)
            onError(FatalError.ActorException)
        }
    }

    init {
        Timber.tag(getTag("init")).d("Invoked")
        sendStatisticEvent(HttpServerStatistic.StatisticEvent.Init)
    }

    internal fun sendStatisticEvent(event: StatisticEvent) {
        parentJob.isActive || return

        if (statisticEventChannel.isClosedForSend) {
            Timber.tag(getTag("sendStatisticEvent")).e(IllegalStateException("Channel is ClosedForSend"))
            onError(FatalError.ChannelException)
        } else if (statisticEventChannel.offer(event).not()) {
            Timber.tag(getTag("sendStatisticEvent")).e(IllegalStateException("Channel is full"))
            onError(FatalError.ChannelException)
        }
    }
}