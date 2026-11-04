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
        val ENVELOPE_OPEN_REGEX = Regex("<([A-Za-z0-9_.-]+:)?Envelope[\\s>]")
        val ENVELOPE_CLOSE_REGEX = Regex("</([A-Za-z0-9_.-]+:)?Envelope\\s*>\\s*$")
        val BODY_REGEX = Regex("<([A-Za-z0-9_.-]+:)?Body[\\s>]")
        const val SOAP12_NS: String = "http://www.w3.org/2003/05/soap-envelope"
        const val SOAP11_NS: String = "http://schemas.xmlsoap.org/soap/envelope/"

        const val MAX_PROBES_PER_SOURCE_PER_SECOND: Int = 5
        const val MAX_PROBES_PER_SECOND: Int = 50
        const val MAX_TRACKED_SOURCES: Int = 256
    }

    private class RateWindow(var windowStartMs: Long, var count: Int)

    private val rateLock = Any()
    private val perSourceRate = HashMap<InetAddress, RateWindow>()
    private val globalRate = RateWindow(0L, 0)

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
            Listener(socket, multicastAddress, endpoint, networkInterface)
        }.onFailure { error ->
            XLog.w(getLog("OnvifDiscoveryServer", "Failed to listen on ${endpoint.rtspEndpoint.bindHost}: ${error.message}"), error)
        }.getOrNull()
    }

    private inner class Listener(
        val socket: MulticastSocket,
        private val multicastAddress: InetAddress,
        private val endpoint: OnvifServiceEndpoint,
        networkInterface: NetworkInterface?,
    ) {
        var job: Job? = null

        // Prefix length of the endpoint address on its interface, if known
        private val prefixLength: Int? = networkInterface?.interfaceAddresses
            ?.firstOrNull { it.address == endpoint.rtspEndpoint.address }
            ?.networkPrefixLength?.toInt()

        /**
         * WS-Discovery answers are only sent to hosts on the same subnet as the selected server address.
         * This prevents spoofed unicast probes from turning the device into a reflector.
         */
        private fun isFromLocalSubnet(source: InetAddress): Boolean {
            val local = endpoint.rtspEndpoint.address
            if (source.javaClass != local.javaClass) return false
            if (source.isLinkLocalAddress) return true // Link-local sources are on-link by definition (IPv6 discovery uses fe80::)
            val prefix = prefixLength
            if (prefix == null || prefix <= 0) {
                // Unknown prefix: accept only private / link-local sources
                return source.isSiteLocalAddress ||
                        (source is Inet6Address && (source.address[0].toInt() and 0xFE) == 0xFC)
            }
            val a = source.address
            val b = local.address
            val fullBytes = prefix / 8
            for (i in 0 until minOf(fullBytes, a.size)) if (a[i] != b[i]) return false
            val remainingBits = prefix % 8
            if (remainingBits == 0 || fullBytes >= a.size) return true
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
        }

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

                val source = packet.address
                if (source == null || !isFromLocalSubnet(source)) {
                    XLog.v(getLog("OnvifDiscoveryServer", "Ignoring datagram from outside local subnet: ${source?.hostAddress}"))
                    continue
                }

                val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val messageId = MESSAGE_ID_REGEX.find(request)?.groupValues?.get(1)?.trim()
                if (!isWellFormedSoapEnvelope(request) || messageId.isNullOrEmpty()) {
                    XLog.v(getLog("OnvifDiscoveryServer", "Ignoring malformed datagram from ${source.hostAddress}:${packet.port}"))
                    continue
                }
                if (!PROBE_REGEX.containsMatchIn(request)) {
                    XLog.v(getLog("OnvifDiscoveryServer", "Ignoring non-Probe datagram from ${packet.address.hostAddress}:${packet.port}"))
                    continue
                }
                if (!allowProbeFrom(source)) continue
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

    private fun isWellFormedSoapEnvelope(request: String): Boolean {
        val trimmed = request.trim { it <= ' ' }
        if (!ENVELOPE_OPEN_REGEX.containsMatchIn(trimmed) || !ENVELOPE_CLOSE_REGEX.containsMatchIn(trimmed)) return false
        if (!trimmed.contains(SOAP12_NS) && !trimmed.contains(SOAP11_NS)) return false
        return BODY_REGEX.containsMatchIn(trimmed)
    }

    /** Simple fixed-window rate limit: per source and global. */
    private fun allowProbeFrom(source: InetAddress): Boolean = synchronized(rateLock) {
        val now = System.nanoTime() / 1_000_000L
        if (now - globalRate.windowStartMs >= 1000L) {
            globalRate.windowStartMs = now
            globalRate.count = 0
        }
        if (++globalRate.count > MAX_PROBES_PER_SECOND) return@synchronized false

        if (perSourceRate.size >= MAX_TRACKED_SOURCES) {
            perSourceRate.entries.removeAll { (_, window) -> now - window.windowStartMs >= 1000L }
            if (perSourceRate.size >= MAX_TRACKED_SOURCES) perSourceRate.clear()
        }
        val window = perSourceRate.getOrPut(source) { RateWindow(now, 0) }
        if (now - window.windowStartMs >= 1000L) {
            window.windowStartMs = now
            window.count = 0
        }
        ++window.count <= MAX_PROBES_PER_SOURCE_PER_SECOND
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
