package info.dvkr.screenstream.vnc.internal

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal class RfbServer(
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val maxFps: () -> Int,
    private val zlibEnabled: () -> Boolean,
    private val onClientsChange: (List<String>) -> Unit,
    private val onBindFailure: (Throwable) -> Unit
) {
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var serverJob: Job? = null

    private val serverSocketsLock = Any()
    private val serverSockets = mutableListOf<ServerSocket>()

    private val clientsLock = Any()
    private val clients = mutableListOf<String>()

    internal fun start(endpoints: List<VncServerEndpoint>, port: Int) {
        if (serverJob?.isActive == true) stop()
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }

        serverJob = scope.launch {
            val selectorManager = SelectorManager(coroutineContext).also { this@RfbServer.selectorManager = it }
            val bound = try {
                bindAll(endpoints, port, selectorManager)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                XLog.e(getLog("RfbServer.start", "Failed to bind VNC server on port $port"), throwable)
                onBindFailure(throwable)
                return@launch
            }

            synchronized(serverSocketsLock) {
                serverSockets.clear()
                serverSockets.addAll(bound)
            }
            XLog.i(getLog("RfbServer.start", "Started VNC server on ${bound.size} socket(s), port $port"))
            bound.forEach { launchAcceptor(it, selectorManager) }
        }
    }

    private suspend fun bindAll(endpoints: List<VncServerEndpoint>, port: Int, selectorManager: SelectorManager): List<ServerSocket> {
        val sockets = mutableListOf<ServerSocket>()
        var failures = 0
        endpoints.forEach { endpoint ->
            try {
                val serverSocket = aSocket(selectorManager).tcp().bind(endpoint.bindHost, port) { reuseAddress = true }
                sockets += serverSocket
                XLog.i(getLog("RfbServer.bindAll", "Bound ${endpoint.fullAddress}:$port"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failures++
                XLog.w(getLog("RfbServer.bindAll", "Failed to bind ${endpoint.fullAddress}:$port: ${throwable.message}"))
            }
        }
        if (sockets.isEmpty() && failures > 0) {
            throw IllegalStateException("Failed to bind VNC server on port $port ($failures interface(s) failed)")
        }
        return sockets
    }

    private fun CoroutineScope.launchAcceptor(serverSocket: ServerSocket, selectorManager: SelectorManager) {
        launch {
            while (isActive) {
                val clientSocket = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    if (!isActive) break
                    delay(50.milliseconds)
                    continue
                }

                val address = (clientSocket.remoteAddress as? io.ktor.network.sockets.InetSocketAddress)?.hostname ?: "unknown"
                addClient(address)
                RfbConnection(
                    parentScope = scope,
                    socket = clientSocket,
                    address = address,
                    bitmapStateFlow = bitmapStateFlow,
                    maxFps = maxFps,
                    zlibEnabled = zlibEnabled,
                    onClosed = { removeClient(address) }
                ).start()
            }
        }
    }

    private fun addClient(address: String) {
        val updated = synchronized(clientsLock) { clients.add(address); clients.toList() }
        onClientsChange(updated)
    }

    private fun removeClient(address: String) {
        val updated = synchronized(clientsLock) {
            clients.indexOf(address).takeIf { it >= 0 }?.let { clients.removeAt(it) }
            clients.toList()
        }
        onClientsChange(updated)
    }

    internal fun stop() {
        runBlocking { withContext(NonCancellable + Dispatchers.IO) { stopSuspend() } }
    }

    private suspend fun stopSuspend() {
        val sockets = synchronized(serverSocketsLock) { serverSockets.toList().also { serverSockets.clear() } }
        sockets.forEach { runCatching { it.close() } }
        runCatching { selectorManager?.close() }
        selectorManager = null
        runCatching { serverJob?.cancelAndJoin() }
        serverJob = null
        runCatching { scopeJob.cancel() }
        synchronized(clientsLock) { clients.clear() }
        onClientsChange(emptyList())
    }
}
