package info.dvkr.screenstream.mjpeg.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.asString
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegClient
import info.dvkr.screenstream.mjpeg.MjpegTrafficPoint
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.toByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal class ClientData(
    private val mjpegSettings: MjpegSettings,
    private val onHttpSeverEvent: (HttpServer.Event) -> Unit
) {

    private data class ConnectedClient(
        val id: Long,
        val ipAddress: InetSocketAddress?,
        val fallbackHost: String,
        var pinCheckAttempt: Int = 0,
        var isPinValidated: Boolean = false,
        var isBlocked: Boolean = false,
        var isSlowConnection: Boolean = false,
        var isDisconnected: Boolean = false,
        var sendBytes: Long = 0,
        var holdUntil: Long = 0
    ) {

        private companion object {
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
        fun toHttpClient() = MjpegClient(id, ipAddress?.asString() ?: fallbackHost, isSlowConnection, isDisconnected, isBlocked)
    }

    internal companion object {
        fun getId(address: InetSocketAddress?, fallback: String): Long =
            address?.address?.address?.plus(address.port.toByteArray())?.contentHashCode()?.toLong()
                ?: fallback.hashCode().toLong()

        private const val TRAFFIC_HISTORY_SECONDS = 30
    }

    private val clients = ConcurrentHashMap<Long, ConnectedClient>()
    private val trafficHistory: LinkedList<MjpegTrafficPoint> = LinkedList<MjpegTrafficPoint>()
    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default)

    internal var enablePin: Boolean = false
    internal var blockAddress: Boolean = false

    internal fun onConnected(id: Long, ipAddress: InetSocketAddress?, fallbackHost: String) {
        if (clients[id] == null) clients[id] = ConnectedClient(id, ipAddress, fallbackHost)
    }

    internal fun onDisconnected(id: Long) = clients[id]?.setDisconnected()

    internal fun onPinCheck(id: Long, isPinValid: Boolean) = clients[id]?.onPinCheck(isPinValid, blockAddress)

    internal fun isClientAuthorized(id: Long): Boolean = clients[id]?.isPinValidated ?: false

    internal fun isClientBlocked(id: Long): Boolean = enablePin && blockAddress && (clients[id]?.isBlocked ?: false)

    internal fun isAddressBlocked(ipAddress: InetSocketAddress?, fallbackHost: String): Boolean {
        if (enablePin.not() || blockAddress.not()) return false

        val hostAddress = ipAddress?.address?.hostAddress
        return if (hostAddress != null) {
            clients.filter { it.value.ipAddress?.address?.hostAddress == hostAddress && it.value.isBlocked }
                .isNotEmpty()
        } else {
            clients.filter { it.value.fallbackHost == fallbackHost && it.value.isBlocked }.isNotEmpty()
        }
    }

    internal fun isClientAllowed(id: Long, ipAddress: InetSocketAddress?, fallbackHost: String): Boolean =
        enablePin.not() || blockAddress.not() ||
                (isAddressBlocked(ipAddress, fallbackHost).not() && isClientAuthorized(id))

    internal fun onNextBytes(id: Long, bytesCount: Int) = clients[id]?.appendBytes(bytesCount)

    internal fun onSlowConnection(id: Long) = clients[id]?.setSlowConnection()

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