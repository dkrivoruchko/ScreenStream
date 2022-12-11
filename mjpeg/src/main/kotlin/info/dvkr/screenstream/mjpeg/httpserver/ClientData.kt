package info.dvkr.screenstream.mjpeg.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegClient
import info.dvkr.screenstream.mjpeg.MjpegTrafficPoint
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal class ClientData(
    private val mjpegSettings: MjpegSettings,
    private val onHttpSeverEvent: (HttpServer.Event) -> Unit
) {

    internal data class ConnectedClient(
        val id: Long,
        val address: String,
        val port: Int,
        var pinCheckAttempt: Int = 0,
        var isPinValidated: Boolean = false,
        var isBlocked: Boolean = false,
        var isSlowConnection: Boolean = false,
        var isDisconnected: Boolean = false,
        var sendBytes: Long = 0,
        var holdUntil: Long = 0
    ) {

        internal companion object {
            internal fun fromRequestConnectionPoint(rcp: RequestConnectionPoint): ConnectedClient =
                ConnectedClient(getClientId(rcp), rcp.remoteAddress, rcp.remotePort)

            internal fun getClientId(rcp: RequestConnectionPoint): Long =
                "${rcp.remoteAddress}:${rcp.remotePort}".hashCode().toLong()

            private const val CLIENT_DISCONNECT_HOLD_TIME_MILLIS = 5 * 1000

            private const val DEFAULT_WRONG_PIN_MAX_COUNT = 5
            private const val DEFAULT_BLOCK_TIME_MILLIS = 5 * 60 * 1000  // 5 minutes
        }

        @Synchronized
        fun onPinCheck(isPinValid: Boolean, blockAddress: Boolean) {
            if (isPinValid.not()) {
                pinCheckAttempt += 1
                if (blockAddress && pinCheckAttempt >= DEFAULT_WRONG_PIN_MAX_COUNT) setBlocked()
            } else if (isBlocked.not()) {
                isPinValidated = isPinValid
                pinCheckAttempt = 0
            }
        }

        private fun setBlocked() {
            isPinValidated = false
            isBlocked = true
            holdUntil = System.currentTimeMillis() + DEFAULT_BLOCK_TIME_MILLIS
        }

        @Synchronized
        fun setDisconnected() {
            isDisconnected = true
            if (isBlocked.not()) holdUntil = System.currentTimeMillis() + CLIENT_DISCONNECT_HOLD_TIME_MILLIS
        }

        @Synchronized
        fun setSlowConnection() {
            isSlowConnection = true
        }

        @Synchronized
        fun appendBytes(bytesCount: Int) {
            sendBytes += bytesCount
        }

        @Synchronized
        fun clearSendBytes() {
            sendBytes = 0
        }

        @Synchronized
        fun removeFromStatistics(now: Long): Boolean = isDisconnected && (holdUntil <= now)

        @Synchronized
        fun toHttpClient() = MjpegClient(id, "$address:$port", isSlowConnection, isDisconnected, isBlocked)
    }

    internal companion object {
        val RequestConnectionPoint.clientId: Long
            get() = ConnectedClient.getClientId(this)

        private const val TRAFFIC_HISTORY_SECONDS = 30
    }

    private val clients = ConcurrentHashMap<Long, ConnectedClient>()
    private val trafficHistory: LinkedList<MjpegTrafficPoint> = LinkedList<MjpegTrafficPoint>()
    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default)

    internal var enablePin: Boolean = false
    internal var blockAddress: Boolean = false

    internal fun onConnected(rcp: RequestConnectionPoint) {
        if (clients[rcp.clientId] == null) clients[rcp.clientId] = ConnectedClient.fromRequestConnectionPoint(rcp)
    }

    internal fun onDisconnected(rcp: RequestConnectionPoint) = clients[rcp.clientId]?.setDisconnected()

    internal fun onPinCheck(rcp: RequestConnectionPoint, isPinValid: Boolean) = clients[rcp.clientId]?.onPinCheck(isPinValid, blockAddress)

    internal fun isClientAuthorized(rcp: RequestConnectionPoint): Boolean = clients[rcp.clientId]?.isPinValidated ?: false

    internal fun isClientBlocked(rcp: RequestConnectionPoint): Boolean =
        enablePin && blockAddress && (clients[rcp.clientId]?.isBlocked ?: false)

    internal fun isAddressBlocked(rcp: RequestConnectionPoint): Boolean =
        enablePin && blockAddress && clients.filter { it.value.address == rcp.remoteAddress && it.value.isBlocked }.isNotEmpty()

    internal fun isClientAllowed(rcp: RequestConnectionPoint): Boolean =
        enablePin.not() || blockAddress.not() || (isClientAuthorized(rcp) && isAddressBlocked(rcp).not())

    internal fun onNextBytes(rcp: RequestConnectionPoint, bytesCount: Int) = clients[rcp.clientId]?.appendBytes(bytesCount)

    internal fun onSlowConnection(rcp: RequestConnectionPoint) = clients[rcp.clientId]?.setSlowConnection()

    internal fun clearStatistics() = clients.clear()

    internal suspend fun configure() {
        XLog.d(getLog("configure"))

        enablePin = mjpegSettings.enablePinFlow.first()
        blockAddress = mjpegSettings.blockAddressFlow.first()
    }

    internal fun destroy() {
        XLog.d(getLog("destroy"))
        statisticScope.cancel()
    }

    init {
        XLog.d(getLog("init"))

        val past = System.currentTimeMillis() - TRAFFIC_HISTORY_SECONDS * 1000
        (0..TRAFFIC_HISTORY_SECONDS + 1).forEach { i -> trafficHistory.addLast(MjpegTrafficPoint(i * 1000 + past, 0)) }

        statisticScope.launch(CoroutineName("ClientStatistic.SendStatistic timer")) {
            enablePin = mjpegSettings.enablePinFlow.first()
            blockAddress = mjpegSettings.blockAddressFlow.first()

            while (isActive) {
                val now = System.currentTimeMillis()
                clients.values.removeAll { it.removeFromStatistics(now) }

                val trafficAtNow = clients.values.sumOf { it.sendBytes }
                clients.values.forEach { it.clearSendBytes() }
                trafficHistory.removeFirst()
                trafficHistory.addLast(MjpegTrafficPoint(now, trafficAtNow))

                val clients = clients.values.map { it.toHttpClient() }.sortedBy { it.clientAddress }
                val traffic = trafficHistory.sortedBy { it.time }

                onHttpSeverEvent(HttpServer.Event.Statistic.Clients(clients))
                onHttpSeverEvent(HttpServer.Event.Statistic.Traffic(traffic))

                delay(1000)
            }
        }
    }
}