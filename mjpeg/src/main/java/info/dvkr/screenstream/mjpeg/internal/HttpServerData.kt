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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.MessageDigest
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        val address: String,
        val port: Int,
        val session: DefaultWebSocketSession?,
        val pinCheckAttempt: AtomicInteger = AtomicInteger(0),
        val isPinValidated: AtomicBoolean = AtomicBoolean(false),
        val isSlowConnection: AtomicBoolean = AtomicBoolean(false),
        val isDisconnected: AtomicBoolean = AtomicBoolean(false),
        val transferBytes: AtomicLong = AtomicLong(0),
        val holdUntil: AtomicLong = AtomicLong(0)
    ) {

        fun onPinCheck(isPinValid: Boolean, blockAddress: Boolean): Boolean {
            isPinValidated.set(isPinValid)
            return if (isPinValid) {
                pinCheckAttempt.set(0)
                false
            } else {
                blockAddress && pinCheckAttempt.incrementAndGet() >= WRONG_PIN_MAX_COUNT
            }
        }

        fun setDisconnected() {
            isDisconnected.set(true)
            holdUntil.set(System.currentTimeMillis() + DISCONNECT_HOLD_TIME_MILLIS)
        }

        fun isAuthorized() = isPinValidated.get()
        fun setSlowConnection() = if (isDisconnected.get().not()) isSlowConnection.set(true) else Unit
        fun appendBytes(bytesCount: Int): Long = transferBytes.addAndGet(bytesCount.toLong())
        fun getBytes(): Long = transferBytes.getAndSet(0)
        fun canRemove(now: Long): Boolean = isDisconnected.get() && (holdUntil.get() <= now)

        fun toMjpegClient(isAddressBlocked: Boolean) = MjpegState.Client(
            id, "$address:$port",
            when {
                isAddressBlocked -> MjpegState.Client.State.BLOCKED
                isDisconnected.get() -> MjpegState.Client.State.DISCONNECTED
                isSlowConnection.get() -> MjpegState.Client.State.SLOW_CONNECTION
                else -> MjpegState.Client.State.CONNECTED
            }
        )

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

                val clients =
                    clientsList.map { c -> c.toMjpegClient(blockedAddresses.any { it.key == c.address }) }.sortedBy { it.clientAddress }
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

    internal fun setConnected(clientId: String, remoteAddress: String, remotePort: Int, session: DefaultWebSocketSession? = null) {
        val client = clients[clientId]
        if (client == null || client.isDisconnected.get()) clients[clientId] = Client(clientId, remoteAddress, remotePort, session)
    }

    internal fun setDisconnected(clientId: String) = clients[clientId]?.setDisconnected() ?: run {
        XLog.w(getLog("disconnected", "No client found: $clientId"), IllegalStateException("disconnected: No client found: $clientId"))
    }

    internal fun isDisconnected(clientId: String): Boolean = clients[clientId]?.isDisconnected?.get() ?: true

    internal fun isPinValid(clientId: String, pinHash: String?): Boolean {
        val client = clients[clientId] ?: run {
            XLog.w(getLog("isPinValid", "No client found: $clientId"), IllegalStateException("isPinValid: No client found: $clientId"))
            return false
        }

        @OptIn(ExperimentalStdlibApi::class)
        val isPinValid = (clientId + pin).encodeToByteArray().toSHA256Bytes().toHexString() == pinHash
        val blockAddress = client.onPinCheck(isPinValid, blockAddress)
        if (blockAddress) blockedAddresses[client.address] = System.currentTimeMillis() + ADDRESS_BLOCK_TIME_MILLIS
        return isPinValid
    }

    internal fun isClientAuthorized(clientId: String): Boolean =
        enablePin.not() || (clients[clientId]?.isAuthorized() ?: false)

    internal fun isAddressBlocked(remoteAddress: String): Boolean =
        enablePin && blockAddress && blockedAddresses.containsKey(remoteAddress)

    internal fun isClientAllowed(clientId: String, remoteAddress: String): Boolean =
        isClientAuthorized(clientId) && isAddressBlocked(remoteAddress).not()

    internal fun setNextBytes(clientId: String, bytesCount: Int) = clients[clientId]?.appendBytes(bytesCount) ?: run {
        XLog.w(getLog("setNextBytes", "No client found: $clientId"), IllegalStateException("setNextBytes: No client found: $clientId"))
    }

    internal fun setSlowConnection(clientId: String) = clients[clientId]?.setSlowConnection() ?: run {
        XLog.w(getLog("setSlowConnection", "No client found: $clientId"), IllegalStateException("setSlowConnection: No client found: $clientId"))
    }

    internal fun notifyClients(type: String, data: Any? = null) {
        runBlocking {
            clients.mapNotNull { it.value.session }.forEach { session ->
                if (session.isActive) session.send(JSONObject().put("type", type).put("data", data).toString())
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