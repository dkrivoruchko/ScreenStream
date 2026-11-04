package info.dvkr.screenstream.rtsp.internal.onvif

import android.content.Context
import android.net.wifi.WifiManager
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

internal class OnvifDiscoveryServer(
    context: Context,
    private val deviceInfo: OnvifDeviceInfo,
) {
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
    private val listeners = mutableListOf<Listener>()
    private val messageCounter = AtomicLong(0)
    private var multicastLock: WifiManager.MulticastLock? = null

    private companion object {
        const val DISCOVERY_PORT: Int = 3702
        val PROBE_REGEX = Regex("<[^>:/]*:?Probe[\\s>/]")
        val MESSAGE_ID_REGEX = Regex("<[^>:/]*:?MessageID[^>]*>(.*?)</[^>:/]*:?MessageID>", RegexOption.DOT_MATCHES_ALL)
        val TYPES_REGEX = Regex("<[^>:/]*:?Types[^>]*>(.*?)</[^>:/]*:?Types>", RegexOption.DOT_MATCHES_ALL)
        val SCOPES_REGEX = Regex("<[^>:/]*:?Scopes[^>]*>(.*?)</[^>:/]*:?Scopes>", RegexOption.DOT_MATCHES_ALL)
    }

    suspend fun start(scope: CoroutineScope, endpoints: List<OnvifServiceEndpoint>) {
        multicastLock = wifiManager?.createMulticastLock("ScreenStream:OnvifDiscovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        endpoints.forEach { endpoint ->
            val listener = createListener(endpoint) ?: return@forEach
            listeners += listener
            XLog.d(getLog("OnvifDiscoveryServer", "Listening on ${endpoint.rtspEndpoint.bindHost}:$DISCOVERY_PORT"))
            XLog.v(getLog("OnvifDiscoveryServer", "Sending Hello for ${endpoint.deviceServiceUrl}"))
            listener.send(OnvifMessages.hello(deviceInfo, endpoint, messageCounter.incrementAndGet()))
            listener.job = scope.launch { listener.receiveLoop() }
        }
    }

    suspend fun stop() {
        listeners.forEach { listener ->
            XLog.v(getLog("OnvifDiscoveryServer", "Sending Bye"))
            runCatching { listener.send(OnvifMessages.bye(deviceInfo, messageCounter.incrementAndGet())) }
            runCatching { listener.socket.close() }
        }
        listeners.forEach { listener ->
            runCatching { listener.job?.cancelAndJoin() }
        }
        listeners.clear()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun createListener(endpoint: OnvifServiceEndpoint): Listener? {
        val multicastAddress = when (endpoint.rtspEndpoint.address) {
            is Inet4Address -> InetAddress.getByName("239.255.255.250")
            is Inet6Address -> InetAddress.getByName("FF02::C")
            else -> return null
        }

        val networkInterface = runCatching { NetworkInterface.getByInetAddress(endpoint.rtspEndpoint.address) }.getOrNull()
        return runCatching {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = 2000
                bind(InetSocketAddress(DISCOVERY_PORT))
                if (networkInterface != null) {
                    this.networkInterface = networkInterface
                    joinGroup(InetSocketAddress(multicastAddress, DISCOVERY_PORT), networkInterface)
                } else {
                    @Suppress("DEPRECATION")
                    joinGroup(multicastAddress)
                }
            }
            Listener(socket, multicastAddress, endpoint)
        }.onFailure { error ->
            XLog.w(getLog("OnvifDiscoveryServer", "Failed to listen on ${endpoint.rtspEndpoint.bindHost}: ${error.message}"), error)
        }.getOrNull()
    }

    private inner class Listener(
        val socket: MulticastSocket,
        private val multicastAddress: InetAddress,
        private val endpoint: OnvifServiceEndpoint,
    ) {
        var job: Job? = null

        suspend fun send(xml: String) {
            val data = xml.toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                socket.send(DatagramPacket(data, data.size, multicastAddress, DISCOVERY_PORT))
            }
        }

        suspend fun receiveLoop() {
            val buffer = ByteArray(8192)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    withContext(Dispatchers.IO) { socket.receive(packet) }
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    break
                }

                val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val messageId = MESSAGE_ID_REGEX.find(request)?.groupValues?.get(1)?.trim()
                if (!PROBE_REGEX.containsMatchIn(request)) {
                    XLog.v(getLog("OnvifDiscoveryServer", "Ignoring non-Probe datagram from ${packet.address.hostAddress}:${packet.port}"))
                    continue
                }
                if (!acceptsProbe(request)) {
                    XLog.d(
                        getLog(
                            "OnvifDiscoveryServer",
                            "Ignoring Probe from ${packet.address.hostAddress}:${packet.port}, messageId=${messageId ?: "none"}"
                        )
                    )
                    continue
                }

                XLog.d(
                    getLog(
                        "OnvifDiscoveryServer",
                        "Accepted Probe from ${packet.address.hostAddress}:${packet.port}, messageId=${messageId ?: "none"}"
                    )
                )
                val response = OnvifMessages.probeMatch(
                    deviceInfo = deviceInfo,
                    endpoint = endpoint,
                    relatesTo = messageId,
                    messageNumber = messageCounter.incrementAndGet(),
                )
                val data = response.toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) {
                    socket.send(DatagramPacket(data, data.size, packet.address, packet.port))
                }
                XLog.v(
                    getLog(
                        "OnvifDiscoveryServer",
                        "Sent ProbeMatches to ${packet.address.hostAddress}:${packet.port}, bytes=${data.size}"
                    )
                )
            }
        }
    }

    private fun acceptsProbe(request: String): Boolean {
        val requestedTypes = TYPES_REGEX.find(request)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")).orEmpty()
        val hasMatchingType = requestedTypes.isEmpty() || requestedTypes.any { type ->
            when (type.substringAfterLast(':')) {
                "Device", "NetworkVideoTransmitter" -> true
                else -> false
            }
        }
        if (!hasMatchingType) return false

        val requestedScopes = SCOPES_REGEX.find(request)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")).orEmpty()
        return requestedScopes.isEmpty() || requestedScopes.all { requestedScope ->
            deviceInfo.scopes.any { scope -> scope.matchesRequestedScope(requestedScope) }
        }
    }

    private fun String.matchesRequestedScope(requestedScope: String): Boolean {
        val requested = requestedScope.trim().trimEnd('/')
        val actual = trim().trimEnd('/')
        return actual.equals(requested, ignoreCase = true) || actual.startsWith("$requested/", ignoreCase = true)
    }
}
