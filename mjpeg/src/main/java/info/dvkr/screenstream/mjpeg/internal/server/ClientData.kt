package info.dvkr.screenstream.mjpeg.internal.server

import android.util.Base64
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegSettings
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import io.ktor.http.RequestConnectionPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

internal class ClientData(private val mjpegSettings: MjpegSettings, private val sendEvent: (MjpegEvent) -> Unit) {

    internal data class ConnectedClient(
        val id: String,
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
            internal fun getClientId(rcp: RequestConnectionPoint): String =
                "${rcp.remoteAddress}:${rcp.remotePort}".toByteArray().toSHA256Bytes().toBase64NoPadding()

            private const val CLIENT_DISCONNECT_HOLD_TIME_MILLIS = 5 * 1000
            private const val DEFAULT_WRONG_PIN_MAX_COUNT = 5
            private const val DEFAULT_BLOCK_TIME_MILLIS = 5 * 60 * 1000  // 5 minutes

            private fun ByteArray.toSHA256Bytes(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
            private fun ByteArray.toBase64NoPadding(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING)
        }

        @Synchronized
        internal fun onPinCheck(isPinValid: Boolean, blockAddress: Boolean) {
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
        internal fun setDisconnected() {
            isDisconnected = true
            if (isBlocked.not()) holdUntil = System.currentTimeMillis() + CLIENT_DISCONNECT_HOLD_TIME_MILLIS
        }

        @Synchronized
        internal fun setSlowConnection() {
            isSlowConnection = true
        }

        @Synchronized
        internal fun appendBytes(bytesCount: Int) {
            sendBytes += bytesCount
        }

        @Synchronized
        internal fun clearSendBytes() {
            sendBytes = 0
        }

        @Synchronized
        internal fun removeFromStatistics(now: Long): Boolean = isDisconnected && (holdUntil <= now)

        @Synchronized
        internal fun toHttpClient() = MjpegState.Client(
            id, "$address:$port",
            when {
                isBlocked -> MjpegState.Client.State.BLOCKED
                isDisconnected -> MjpegState.Client.State.DISCONNECTED
                isSlowConnection -> MjpegState.Client.State.SLOW_CONNECTION
                else -> MjpegState.Client.State.CONNECTED
            }
        )
    }

    internal companion object {
        internal val RequestConnectionPoint.clientId: String
            get() = ConnectedClient.getClientId(this)

        private const val TRAFFIC_HISTORY_SECONDS = 30
    }

    private val clients = ConcurrentHashMap<String, ConnectedClient>()
    private val publishedClients: MutableList<MjpegState.Client> = Collections.synchronizedList(ArrayList())
    private val trafficHistory: LinkedList<MjpegState.TrafficPoint> = LinkedList<MjpegState.TrafficPoint>()
    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default)

    internal var enablePin: Boolean = false
    internal var blockAddress: Boolean = false

    internal fun onConnected(rcp: RequestConnectionPoint) {
        if (clients[rcp.clientId] == null) clients[rcp.clientId] = ConnectedClient(rcp.clientId, rcp.remoteAddress, rcp.remotePort)
    }

    internal fun onDisconnected(rcp: RequestConnectionPoint) = clients[rcp.clientId]?.setDisconnected()

    internal fun onPinCheck(rcp: RequestConnectionPoint, isPinValid: Boolean) = clients[rcp.clientId]?.onPinCheck(isPinValid, blockAddress)

    internal fun isClientAuthorized(rcp: RequestConnectionPoint): Boolean = //clients[rcp.clientId]?.isPinValidated ?: false
        clients.filter { it.value.address == rcp.remoteAddress && it.value.isPinValidated }.isNotEmpty()

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
        (0..TRAFFIC_HISTORY_SECONDS + 1).forEach { i -> trafficHistory.addLast(MjpegState.TrafficPoint(i * 1000 + past, 0f)) }

        statisticScope.launch {
            enablePin = mjpegSettings.enablePinFlow.first()
            blockAddress = mjpegSettings.blockAddressFlow.first()

            while (isActive) {
                val now = System.currentTimeMillis()
                clients.values.removeAll { it.removeFromStatistics(now) }

                val trafficAtNow = clients.values.sumOf { it.sendBytes }.bytesToMbit()
                clients.values.forEach { it.clearSendBytes() }
                trafficHistory.removeFirst()
                trafficHistory.addLast(MjpegState.TrafficPoint(now, trafficAtNow))

                val clients = clients.values.map { it.toHttpClient() }.sortedBy { it.clientAddress }
                if (clients.size != publishedClients.size ||
                    clients.any { c -> publishedClients.find { it.id == c.id }?.equals(c) != true }
                ) {
                    sendEvent(MjpegStreamingService.InternalEvent.Clients(clients))
                    publishedClients.clear()
                    publishedClients.addAll(clients)
                }

                sendEvent(MjpegStreamingService.InternalEvent.Traffic(trafficHistory.sortedBy { it.time }))

                delay(1000)
            }
        }
    }

    private fun Long.bytesToMbit(): Float = (this * 8).toFloat() / 1024 / 1024
}