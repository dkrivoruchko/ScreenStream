package info.dvkr.screenstream.rtsp.internal.onvif

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.RtspServerEndpoint
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.EOFException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import io.ktor.network.sockets.InetSocketAddress as KtorInetSocketAddress

internal class OnvifHttpServer(
    private val deviceInfo: OnvifDeviceInfo,
    private val protocolPolicy: RtspSettings.Values.ProtocolPolicy,
) {
    private var selectorManager: SelectorManager? = null
    private var clientJob: Job? = null
    private var clientScope: CoroutineScope? = null
    private val serverSockets = mutableListOf<ServerSocket>()
    private val serverJobs = mutableListOf<Job>()
    private val activeSocketsMutex = Mutex()
    private val activeSockets = mutableSetOf<Socket>()
    private val videoMetadata = AtomicReference<OnvifVideoMetadata?>()

    private companion object {
        const val PREFERRED_DEVICE_SERVICE_PORT: Int = 8000
        const val MAX_HTTP_HEADER_BYTES: Int = 16 * 1024
        const val MAX_HTTP_BODY_BYTES: Int = 64 * 1024
        const val MAX_ACTIVE_HTTP_CLIENTS: Int = 16
        val HTTP_REQUEST_TIMEOUT = 10.seconds
        val GET_CAPABILITIES_REGEX = Regex("<[^>:/]*:?GetCapabilities[\\s>/]")
        val GET_SERVICES_REGEX = Regex("<[^>:/]*:?GetServices[\\s>/]")
        val GET_DEVICE_INFORMATION_REGEX = Regex("<[^>:/]*:?GetDeviceInformation[\\s>/]")
        val GET_SYSTEM_DATE_AND_TIME_REGEX = Regex("<[^>:/]*:?GetSystemDateAndTime[\\s>/]")
        val GET_SCOPES_REGEX = Regex("<[^>:/]*:?GetScopes[\\s>/]")
        val GET_DISCOVERY_MODE_REGEX = Regex("<[^>:/]*:?GetDiscoveryMode[\\s>/]")
        val GET_PROFILES_REGEX = Regex("<[^>:/]*:?GetProfiles[\\s>/]")
        val GET_STREAM_URI_REGEX = Regex("<[^>:/]*:?GetStreamUri[\\s>/]")
        val GET_VIDEO_SOURCES_REGEX = Regex("<[^>:/]*:?GetVideoSources[\\s>/]")
        val PROFILE_TOKEN_REGEX = Regex("<[^>:/]*:?ProfileToken[^>]*>(.*?)</[^>:/]*:?ProfileToken>", RegexOption.DOT_MATCHES_ALL)
        val STREAM_TYPE_REGEX = Regex("<[^>:/]*:?Stream(?:\\s[^>]*)?>(.*?)</[^>:/]*:?Stream>", RegexOption.DOT_MATCHES_ALL)
        val STREAM_PROTOCOL_REGEX = Regex("<[^>:/]*:?Protocol[^>]*>(.*?)</[^>:/]*:?Protocol>", RegexOption.DOT_MATCHES_ALL)
    }

    fun setVideoMetadata(metadata: OnvifVideoMetadata) {
        videoMetadata.set(metadata)
    }

    suspend fun start(scope: CoroutineScope, endpoints: List<RtspServerEndpoint>): List<OnvifServiceEndpoint> {
        val selectorManager = SelectorManager(scope.coroutineContext).also { this.selectorManager = it }
        val clientJob = SupervisorJob(scope.coroutineContext[Job]).also { this.clientJob = it }
        val clientScope = CoroutineScope(scope.coroutineContext + clientJob).also { this.clientScope = it }

        return endpoints.mapNotNull { endpoint ->
            val serverSocket = bindEndpoint(endpoint, selectorManager) ?: return@mapNotNull null
            serverSockets += serverSocket

            val serviceEndpoint = OnvifServiceEndpoint(
                rtspEndpoint = endpoint,
                deviceServicePort = (serverSocket.localAddress as KtorInetSocketAddress).port,
            )

            serverJobs += scope.launch {
                acceptLoop(clientScope, serverSocket, serviceEndpoint)
            }

            XLog.d(getLog("OnvifHttpServer", "Listening on ${endpoint.bindHost}:${serviceEndpoint.deviceServicePort}"))
            serviceEndpoint
        }
    }

    suspend fun stop() {
        serverSockets.forEach { runCatching { it.close() } }
        serverSockets.clear()
        activeSocketsMutex.withLock {
            activeSockets.toList().forEach { runCatching { it.close() } }
            activeSockets.clear()
        }
        runCatching { clientJob?.cancelAndJoin() }
        clientJob = null
        clientScope = null
        serverJobs.forEach { runCatching { it.cancelAndJoin() } }
        serverJobs.clear()
        runCatching { selectorManager?.close() }
        selectorManager = null
    }

    private suspend fun bindEndpoint(endpoint: RtspServerEndpoint, selectorManager: SelectorManager): ServerSocket? {
        val tcp = aSocket(selectorManager).tcp()
        return runCatching {
            tcp.bind(endpoint.bindHost, PREFERRED_DEVICE_SERVICE_PORT) { reuseAddress = true }
        }.recoverCatching { firstError ->
            XLog.w(getLog("OnvifHttpServer", "Port $PREFERRED_DEVICE_SERVICE_PORT unavailable on ${endpoint.bindHost}: ${firstError.message}"))
            tcp.bind(endpoint.bindHost, 0) { reuseAddress = true }
        }.onFailure { error ->
            XLog.w(getLog("OnvifHttpServer", "Failed to bind ${endpoint.bindHost}: ${error.message}"), error)
        }.getOrNull()
    }

    private suspend fun acceptLoop(clientScope: CoroutineScope, serverSocket: ServerSocket, endpoint: OnvifServiceEndpoint) {
        while (clientScope.isActive) {
            val socket = try {
                serverSocket.accept()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                break
            }

            val socketTracked = activeSocketsMutex.withLock {
                if (activeSockets.size >= MAX_ACTIVE_HTTP_CLIENTS) false
                else {
                    activeSockets += socket
                    true
                }
            }
            if (!socketTracked) {
                runCatching { socket.close() }
                continue
            }

            clientScope.launch {
                try {
                    withTimeout(HTTP_REQUEST_TIMEOUT) {
                        handleHttpRequest(
                            readChannel = socket.openReadChannel(),
                            writeChannel = socket.openWriteChannel(autoFlush = true),
                            endpoint = endpoint,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    XLog.w(getLog("OnvifHttpServer", "HTTP request failed: ${error.message}"), error)
                } finally {
                    activeSocketsMutex.withLock { activeSockets -= socket }
                    runCatching { socket.close() }
                }
            }
        }
    }

    private suspend fun handleHttpRequest(readChannel: ByteReadChannel, writeChannel: ByteWriteChannel, endpoint: OnvifServiceEndpoint) {
        val requestLine = readChannel.readLine() ?: return
        val requestLineParts = requestLine.split(' ', limit = 3)
        val method = requestLineParts.getOrNull(0).orEmpty()
        val path = requestLineParts.getOrNull(1)?.substringBefore('?').orEmpty()
        XLog.v(getLog("OnvifHttpServer", "HTTP request: $method $path"))
        if ((method != "POST") || (path != endpoint.deviceServicePath)) {
            XLog.d(getLog("OnvifHttpServer", "HTTP response: $method $path -> 404 Not Found"))
            return sendHttpResponse(writeChannel, 404, "Not Found", "")
        }

        val contentLength = readChannel.readContentLength(requestLine.length + 2)
        if (contentLength <= 0) {
            XLog.d(getLog("OnvifHttpServer", "HTTP response: POST $path -> 404 Not Found, empty body"))
            return sendHttpResponse(writeChannel, 404, "Not Found", "")
        }
        if (contentLength > MAX_HTTP_BODY_BYTES) {
            XLog.d(getLog("OnvifHttpServer", "HTTP response: POST $path -> 413 Payload Too Large, contentLength=$contentLength"))
            return sendHttpResponse(writeChannel, 413, "Payload Too Large", "")
        }
        XLog.v(getLog("OnvifHttpServer", "HTTP body: path=$path contentLength=$contentLength"))

        val requestBody = try {
            ByteArray(contentLength).also { readChannel.readFully(it, 0, contentLength) }.toString(Charsets.UTF_8)
        } catch (_: EOFException) {
            XLog.d(getLog("OnvifHttpServer", "HTTP client disconnected while reading body: contentLength=$contentLength"))
            return
        }
        val metadata = videoMetadata.get()
        var action = "Unsupported"
        var faultReason: String? = null
        val soapResponse = when {
            GET_CAPABILITIES_REGEX.containsMatchIn(requestBody) -> {
                action = "GetCapabilities"
                OnvifMessages.getCapabilities(endpoint, protocolPolicy)
            }

            GET_SERVICES_REGEX.containsMatchIn(requestBody) -> {
                action = "GetServices"
                OnvifMessages.getServices(endpoint)
            }

            GET_DEVICE_INFORMATION_REGEX.containsMatchIn(requestBody) -> {
                action = "GetDeviceInformation"
                OnvifMessages.getDeviceInformation(deviceInfo)
            }

            GET_SYSTEM_DATE_AND_TIME_REGEX.containsMatchIn(requestBody) -> {
                action = "GetSystemDateAndTime"
                OnvifMessages.getSystemDateAndTime()
            }

            GET_SCOPES_REGEX.containsMatchIn(requestBody) -> {
                action = "GetScopes"
                OnvifMessages.getScopes(deviceInfo)
            }

            GET_DISCOVERY_MODE_REGEX.containsMatchIn(requestBody) -> {
                action = "GetDiscoveryMode"
                OnvifMessages.getDiscoveryMode()
            }

            GET_PROFILES_REGEX.containsMatchIn(requestBody) -> {
                action = "GetProfiles"
                OnvifMessages.getProfiles(metadata)
            }

            GET_STREAM_URI_REGEX.containsMatchIn(requestBody) -> {
                action = "GetStreamUri"
                validateStreamUriRequest(requestBody)
                    ?.also { faultReason = it }
                    ?.let { OnvifMessages.fault(it) }
                    ?: OnvifMessages.getStreamUri(endpoint)
            }

            GET_VIDEO_SOURCES_REGEX.containsMatchIn(requestBody) -> {
                action = "GetVideoSources"
                OnvifMessages.getVideoSources(metadata)
            }

            else -> {
                faultReason = "Action not supported"
                OnvifMessages.fault()
            }
        }

        val isFault = soapResponse.contains("<env:Fault>")
        val statusCode = if (isFault) 500 else 200
        val statusText = if (isFault) "Internal Server Error" else "OK"
        XLog.d(
            getLog(
                "OnvifHttpServer",
                buildString {
                    append("SOAP response: action=$action status=$statusCode responseBytes=${soapResponse.toByteArray(Charsets.UTF_8).size}")
                    faultReason?.let { append(" fault=\"$it\"") }
                }
            )
        )
        sendHttpResponse(writeChannel, statusCode, statusText, soapResponse)
    }

    private fun validateStreamUriRequest(requestBody: String): String? {
        val profileToken = PROFILE_TOKEN_REGEX.find(requestBody)?.groupValues?.get(1)?.trim()
        if (profileToken != "Profile_1") return "Profile not found"

        val streamType = STREAM_TYPE_REGEX.find(requestBody)?.groupValues?.get(1)?.trim()
        if (streamType != "RTP-Unicast") return "Stream type not supported"

        val requestedProtocol = STREAM_PROTOCOL_REGEX.findAll(requestBody)
            .map { it.groupValues[1].trim().uppercase() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "Stream transport not supported"

        val supported = when (requestedProtocol) {
            "UDP" -> protocolPolicy != RtspSettings.Values.ProtocolPolicy.TCP
            "RTSP" -> protocolPolicy != RtspSettings.Values.ProtocolPolicy.UDP
            else -> false
        }
        return if (supported) null else "Stream transport not supported"
    }

    private suspend fun ByteReadChannel.readContentLength(initialHeaderBytes: Int): Int {
        var headerBytes = initialHeaderBytes
        var contentLength = 0
        while (true) {
            val line = readLine() ?: break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HTTP_HEADER_BYTES) {
                throw IllegalArgumentException("HTTP header too large (> $MAX_HTTP_HEADER_BYTES bytes)")
            }
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return contentLength
    }

    private suspend fun sendHttpResponse(writeChannel: ByteWriteChannel, statusCode: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/soap+xml; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        writeChannel.writeStringUtf8(headers)
        if (body.isNotEmpty()) writeChannel.writeStringUtf8(body)
    }
}
