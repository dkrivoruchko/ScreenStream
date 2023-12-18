package info.dvkr.screenstream.mjpeg.internal

import android.util.Base64
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.MjpegSettings
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.set

internal class HttpServerData(private val sendEvent: (MjpegEvent) -> Unit) {

    internal companion object {
        private const val WRONG_PIN_MAX_COUNT = 5
        private const val ADDRESS_BLOCK_TIME_MILLIS = 5 * 60 * 1000
        private const val TRAFFIC_HISTORY_SECONDS = 30
        private const val DISCONNECT_HOLD_TIME_MILLIS = 10 * 1000

        internal fun ApplicationRequest.getClientId(): String =
            queryParameters["clientId"] ?: "${origin.remoteAddress}:${origin.remotePort}".toByteArray().toSHA256Bytes().toBase64()

        private fun ByteArray.toSHA256Bytes(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
        private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private class Client(
        val id: String,
        val pinCheckAttempt: AtomicInteger = AtomicInteger(0),
        val isPinValidated: AtomicBoolean = AtomicBoolean(false),
        val session: AtomicReference<DefaultWebSocketSession?> = AtomicReference(null),
        val connectionsMap: ConcurrentHashMap<String, Connection> = ConcurrentHashMap<String, Connection>()
    ) {

        private class Connection(
            val address: String,
            val port: String,
            val isSlowConnection: AtomicBoolean = AtomicBoolean(false),
            val isDisconnected: AtomicBoolean = AtomicBoolean(false),
            val transferBytes: AtomicLong = AtomicLong(0),
            val holdUntil: AtomicLong = AtomicLong(0)
        )

        fun addNewConnection(address: String, port: String) {
            connectionsMap["$address:$port"] = Connection(address, port)
        }

        fun onPinCheck(isPinValid: Boolean, blockAddress: Boolean): Boolean {
            isPinValidated.set(isPinValid)
            return if (isPinValid) {
                pinCheckAttempt.set(0)
                false
            } else {
                blockAddress && pinCheckAttempt.incrementAndGet() >= WRONG_PIN_MAX_COUNT
            }
        }

        fun setDisconnected(address: String, port: String) {
            connectionsMap["$address:$port"]?.apply {
                isDisconnected.set(true)
                holdUntil.set(System.currentTimeMillis() + DISCONNECT_HOLD_TIME_MILLIS)
            }
        }

        fun isDisconnected(address: String, port: String): Boolean = connectionsMap["$address:$port"]?.isDisconnected?.get() ?: true

        fun isAuthorized() = isPinValidated.get()

        fun setSlowConnection(address: String, port: String) {
            connectionsMap["$address:$port"]?.apply { if (isDisconnected.get().not()) isSlowConnection.set(true) }
        }

        fun appendBytes(address: String, port: String, bytesCount: Int) {
            connectionsMap["$address:$port"]?.apply { transferBytes.addAndGet(bytesCount.toLong()) }
        }

        fun getBytes(): Long = connectionsMap.map { it.value.transferBytes.getAndSet(0) }.sum()

        fun canRemove(now: Long): Boolean {
            connectionsMap.filter { it.value.isDisconnected.get() && it.value.holdUntil.get() <= now }
                .forEach { connectionsMap.remove(it.key) }
            return connectionsMap.isEmpty() && session.get() == null
        }

        fun toMjpegClients(blockedAddresses: Map<String, Long>): List<MjpegState.Client> {
            return connectionsMap.map { (_, connection) ->
                MjpegState.Client(
                    "$id:${connection.address}:${connection.port}", "${connection.address}:${connection.port}",
                    when {
                        blockedAddresses.containsKey(connection.address) -> MjpegState.Client.State.BLOCKED
                        connection.isDisconnected.get() -> MjpegState.Client.State.DISCONNECTED
                        connection.isSlowConnection.get() -> MjpegState.Client.State.SLOW_CONNECTION
                        else -> MjpegState.Client.State.CONNECTED
                    }
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Client
            if (id != other.id) return false
            return true
        }

        override fun hashCode(): Int = id.hashCode()
    }

    @Volatile
    internal var enablePin: Boolean = false

    @Volatile
    internal var pin: String = ""

    @Volatile
    internal var blockAddress: Boolean = false

    @Volatile
    internal var streamAddress: String = ""

    @Volatile
    internal var jpegFallbackAddress: String = ""

    private val statisticScope = CoroutineScope(Job() + Dispatchers.Default)
    private val clients = ConcurrentHashMap<String, Client>()
    private val blockedAddresses = ConcurrentHashMap<String, Long>()
    private val trafficHistory: LinkedList<MjpegState.TrafficPoint> = LinkedList<MjpegState.TrafficPoint>().also {
        val past = System.currentTimeMillis() - TRAFFIC_HISTORY_SECONDS * 1000
        repeat(TRAFFIC_HISTORY_SECONDS) { i -> it.addLast(MjpegState.TrafficPoint(i * 1000 + past, 0f)) }
    }

    internal suspend fun configure(mjpegSettings: MjpegSettings) {
        XLog.d(getLog("configure"))

        enablePin = mjpegSettings.enablePinFlow.first()
        pin = mjpegSettings.pinFlow.first()
        blockAddress = mjpegSettings.blockAddressFlow.first()
        val streamAddressBase = if (enablePin) randomString(16) else "stream"
        streamAddress = "$streamAddressBase.mjpeg"
        jpegFallbackAddress = "$streamAddressBase.jpeg"
    }

    init {
        XLog.d(getLog("init"))

        statisticScope.launch {
            val publishedClients = mutableListOf<MjpegState.Client>()
            val clientsList = clients.values

            while (isActive) {
                val now = System.currentTimeMillis()
                clientsList.removeAll { it.canRemove(now) }
                blockedAddresses.filterValues { it <= now }.forEach { blockedAddresses.remove(it.key) }

                val trafficAtNow = clientsList.sumOf { it.getBytes() }.bytesToMbit()
                trafficHistory.removeFirst()
                trafficHistory.addLast(MjpegState.TrafficPoint(now, trafficAtNow))
                sendEvent(MjpegStreamingService.InternalEvent.Traffic(now, trafficHistory.sortedBy { it.time }))

                val clients = clientsList.flatMap { c -> c.toMjpegClients(blockedAddresses) }.sortedBy { it.clientAddress }
                if (clients.size != publishedClients.size || clients.any { c ->
                        publishedClients.find { it.id == c.id }?.equals(c) != true
                    }) {
                    sendEvent(MjpegStreamingService.InternalEvent.Clients(clients))
                    publishedClients.clear()
                    publishedClients.addAll(clients)
                }

                delay(1000)
            }
        }
    }

    internal fun addClient(clientId: String, session: DefaultWebSocketSession) {
        val client = clients[clientId] ?: run { Client(clientId).also { clients[clientId] = it } }
        client.session.set(session)
    }

    internal fun removeSocket(clientId: String) {
        clients[clientId]?.session?.set(null)
    }

    internal fun addConnected(clientId: String, remoteAddress: String, remotePort: Int) {
        val client = clients[clientId] ?: run { Client(clientId).also { clients[clientId] = it } }
        client.addNewConnection(remoteAddress, remotePort.toString())
    }

    internal fun setDisconnected(clientId: String, remoteAddress: String, remotePort: Int) {
        clients[clientId]?.setDisconnected(remoteAddress, remotePort.toString())
    }

    internal fun isDisconnected(clientId: String, remoteAddress: String, remotePort: Int): Boolean {
        return clients[clientId]?.isDisconnected(remoteAddress, remotePort.toString()) ?: true
    }

    internal fun isPinValid(clientId: String, remoteAddress: String, pinHash: String?): Boolean {
        val client = clients[clientId] ?: run { Client(clientId).also { clients[clientId] = it } }

        @OptIn(ExperimentalStdlibApi::class)
        val isPinValid = (clientId + pin).encodeToByteArray().toSHA256Bytes().toHexString() == pinHash
        val blockAddress = client.onPinCheck(isPinValid, blockAddress)
        if (blockAddress) blockedAddresses[remoteAddress] = System.currentTimeMillis() + ADDRESS_BLOCK_TIME_MILLIS
        return isPinValid
    }

    internal fun isClientAuthorized(clientId: String): Boolean =
        enablePin.not() || (clients[clientId]?.isAuthorized() ?: false)

    internal fun isAddressBlocked(remoteAddress: String): Boolean =
        enablePin && blockAddress && blockedAddresses.containsKey(remoteAddress)

    internal fun isClientAllowed(clientId: String, remoteAddress: String): Boolean =
        isClientAuthorized(clientId) && isAddressBlocked(remoteAddress).not()

    internal fun setNextBytes(clientId: String, remoteAddress: String, remotePort: Int, bytesCount: Int) {
        clients[clientId]?.appendBytes(remoteAddress, remotePort.toString(), bytesCount) ?: run {
            XLog.w(getLog("setNextBytes", "No client found: $clientId"), IllegalStateException("setNextBytes: No client found: $clientId"))
        }
    }

    internal fun setSlowConnection(clientId: String, remoteAddress: String, remotePort: Int) {
        clients[clientId]?.setSlowConnection(remoteAddress, remotePort.toString()) ?: run {
            XLog.w(getLog("setSlowConnection", "No client found: $clientId"), IllegalStateException("setSlowConnection: No client found: $clientId"))
        }
    }

    internal suspend fun notifyClients(type: String, data: Any? = null, timeout: Long = 2000) = supervisorScope {
        val message = JSONObject().put("type", type).put("data", data).toString()
        withTimeoutOrNull(timeout) {
            clients.forEach { (_, client) ->
                client.session.get()?.let { socketSession -> if (socketSession.isActive) launch { socketSession.send(message) } }
            }
        }
    }

    internal fun clear() {
        clients.clear()
        blockedAddresses.clear()
    }

    internal fun destroy() {
        XLog.d(getLog("destroy"))
        statisticScope.cancel()
    }

    private fun Long.bytesToMbit(): Float = (this * 8).toFloat() / 1024 / 1024
}