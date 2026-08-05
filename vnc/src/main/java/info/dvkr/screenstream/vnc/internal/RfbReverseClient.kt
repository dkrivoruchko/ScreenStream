package info.dvkr.screenstream.vnc.internal

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.vnc.ui.VncReverseStatus
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
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
import kotlin.time.Duration.Companion.seconds

internal class RfbReverseClient(
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val maxFps: () -> Int,
    private val zlibEnabled: () -> Boolean,
    private val onStatus: (VncReverseStatus, String?) -> Unit,
    private val onClientsChange: (List<String>) -> Unit
) {
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var connectJob: Job? = null

    internal fun start(host: String, port: Int, reconnectDelaySeconds: Int) {
        if (connectJob?.isActive == true) stop()
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }
        onClientsChange(emptyList())

        connectJob = scope.launch {
            val selectorManager = SelectorManager(coroutineContext).also { this@RfbReverseClient.selectorManager = it }
            val reconnectDelay = reconnectDelaySeconds.coerceAtLeast(1).seconds

            while (isActive) {
                onStatus(VncReverseStatus.CONNECTING, null)
                val connection = try {
                    val socket = aSocket(selectorManager).tcp().connect(InetSocketAddress(host, port))
                    RfbConnection(
                        parentScope = scope,
                        socket = socket,
                        address = "$host:$port",
                        bitmapStateFlow = bitmapStateFlow,
                        maxFps = maxFps,
                        zlibEnabled = zlibEnabled,
                        onClosed = { }
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    XLog.w(getLog("RfbReverseClient", "Connection to $host:$port failed: ${throwable.message}"))
                    onClientsChange(emptyList())
                    onStatus(VncReverseStatus.RECONNECTING, throwable.message ?: throwable.javaClass.simpleName)
                    delay(reconnectDelay)
                    continue
                }

                onClientsChange(listOf("$host:$port"))
                connection.start()
                onStatus(VncReverseStatus.ACTIVE, null)
                XLog.i(getLog("RfbReverseClient", "Connected to $host:$port"))
                connection.awaitTermination()

                XLog.i(getLog("RfbReverseClient", "Disconnected from $host:$port"))
                onClientsChange(emptyList())
                onStatus(VncReverseStatus.RECONNECTING, "Disconnected")
                delay(reconnectDelay)
            }
        }
    }

    internal fun stop() {
        runBlocking { withContext(NonCancellable + Dispatchers.IO) { stopSuspend() } }
    }

    private suspend fun stopSuspend() {
        runCatching { selectorManager?.close() }
        selectorManager = null
        runCatching { connectJob?.cancelAndJoin() }
        connectJob = null
        runCatching { scopeJob.cancel() }
        onStatus(VncReverseStatus.IDLE, null)
        onClientsChange(emptyList())
    }
}
